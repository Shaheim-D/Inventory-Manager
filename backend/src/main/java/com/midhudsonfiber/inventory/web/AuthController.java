package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttempts;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          LoginAttemptService loginAttempts,
                          AppUserRepository users,
                          PasswordEncoder passwordEncoder,
                          CurrentUser currentUser) {
        this.authenticationManager = authenticationManager;
        this.loginAttempts = loginAttempts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        if (loginAttempts.isLocked(request.username())) {
            return lockedResponse(request.username());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (LockedException ex) {
            return lockedResponse(request.username());
        } catch (AuthenticationServiceException ex) {
            // The RADIUS server could not be reached, or is misconfigured. That is
            // not a wrong password, and two things follow from saying so.
            //
            // It is deliberately NOT recorded as a failed attempt. Counting it
            // would mean an NPS outage locks out every person who tries during
            // it, and they would still be locked out for fifteen minutes after
            // the server came back -- an outage turned into an incident by the
            // thing meant to protect the accounts.
            //
            // And the message is different, because "Incorrect username or
            // password" sends somebody to reset a password that was always
            // right. Local sign-in is unaffected and still works, which is the
            // useful thing to tell them.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "The network sign-in server could not be reached. "
                            + "An account with a password set in this application can still sign in."));
        } catch (AuthenticationException ex) {
            loginAttempts.recordFailure(request.username());
            // Deliberately identical whether the account exists or not.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Incorrect username or password."));
        }

        // Rotate the session on login so a pre-authentication session id cannot be replayed.
        httpRequest.getSession(true).invalidate();
        httpRequest.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);

        loginAttempts.recordSuccess(request.username());
        return ResponseEntity.ok(me());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * The single source the frontend uses to decide what to render. It returns
     * the resolved permission key set -- never role names, which the UI must
     * never branch on.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        AppUserPrincipal principal = currentUser.principal()
                .orElseThrow(() -> new ApiExceptions.UnauthenticatedException("Not signed in"));
        return Map.of(
                "id", principal.getId(),
                "username", principal.getUsername(),
                "authProvider", principal.getAuthProvider().name(),
                "mustChangePassword", principal.isMustChangePassword(),
                "roles", principal.getRoleNames(),
                "permissions", principal.getPermissions());
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request) {
        AppUserPrincipal principal = currentUser.principal()
                .orElseThrow(() -> new ApiExceptions.UnauthenticatedException("Not signed in"));

        AppUser user = users.findById(principal.getId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found"));

        // Keyed on whether a local password exists, not on auth_provider, because
        // since V26 an account can legitimately have both: a RADIUS identity and
        // a password set here, either of which signs them in.
        //
        // No local password means this endpoint refuses, rather than treating a
        // blank current password as permission to set one. Otherwise anybody who
        // signed in through RADIUS could give themselves a local password that
        // keeps working after NPS stops recognising them -- a self-service
        // backdoor around the directory. Setting the first one is an
        // administrator's job, on Settings > Users.
        if (user.getPasswordHash() == null) {
            throw new ApiExceptions.BadRequestException(
                    "This account signs in with your network credentials, which are managed on the "
                            + "network, not here. An administrator can set a password for it in this "
                            + "application if you need one.");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiExceptions.BadRequestException("Current password is incorrect.");
        }
        validatePasswordStrength(request.newPassword());

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        users.save(user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Length only, deliberately. Composition rules (an upper, a digit, a symbol)
     * push people toward predictable substitutions and written-down passwords
     * without adding much real strength, so the client asked for a plain minimum
     * and that is what this enforces.
     */
    /**
     * "Locked", with how long is left on it.
     *
     * <p>Without the number somebody retries every minute for fifteen, and every
     * one of those attempts is another failure against an account that is
     * already locked. Telling them how long turns a locked account into a wait
     * instead of an escalation.
     *
     * <p>Discloses nothing new: this response only ever reaches an account that
     * genuinely is locked, and an unknown username is not locked and still gets
     * the same generic "incorrect username or password" as a wrong one.
     */
    private ResponseEntity<?> lockedResponse(String username) {
        long seconds = loginAttempts.lockedSecondsRemaining(username);
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "error", "This account is temporarily locked. Try again in " + humanize(seconds) + ".",
                // The raw number too, so the screen can count down rather than
                // showing a figure that quietly goes stale while somebody reads it.
                "retryAfterSeconds", seconds));
    }

    /** Rounds up, because "try again in 0 minutes" is worse than waiting a moment. */
    static String humanize(long seconds) {
        if (seconds <= 60) return "less than a minute";
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = (minutes + 59) / 60;
        return hours + (hours == 1 ? " hour" : " hours");
    }

    static final int MINIMUM_PASSWORD_LENGTH = 8;

    static void validatePasswordStrength(String password) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new ApiExceptions.BadRequestException(
                    "Password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
    }
}
