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
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("error", "This account is temporarily locked. Try again later."));
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (LockedException ex) {
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("error", "This account is temporarily locked. Try again later."));
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

        if (user.getAuthProvider() != AppUser.AuthProvider.LOCAL) {
            throw new ApiExceptions.BadRequestException(
                    "Passwords for directory accounts are managed in the directory, not here.");
        }
        if (user.getPasswordHash() != null
                && !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
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
    static final int MINIMUM_PASSWORD_LENGTH = 8;

    static void validatePasswordStrength(String password) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new ApiExceptions.BadRequestException(
                    "Password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
    }
}
