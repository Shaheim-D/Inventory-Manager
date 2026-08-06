package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.RadiusSettings;
import com.midhudsonfiber.inventory.plugin.SecretResolver;
import com.midhudsonfiber.inventory.repo.RadiusSettingsRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.tinyradius.packet.AccessRequest;
import org.tinyradius.packet.RadiusPacket;
import org.tinyradius.util.RadiusClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings &gt; RADIUS. Gated on {@code user:manage} -- the same key as creating
 * accounts and assigning roles, because deciding who may sign in and how is the
 * same job.
 *
 * <p>The shared secret is never accepted or returned here. The form takes the
 * <b>name of an environment variable</b>, and the response says only whether
 * that name currently resolves to something.
 */
@RestController
@RequestMapping("/api/admin/radius-settings")
public class RadiusSettingsController {

    private final RadiusSettingsRepository settings;
    private final SecretResolver secrets;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public RadiusSettingsController(RadiusSettingsRepository settings, SecretResolver secrets,
                                    AuditService audit, CurrentUser currentUser) {
        this.settings = settings;
        this.secrets = secrets;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record SettingsRequest(boolean enabled, String host, @NotNull Integer port,
                                  String sharedSecretRef, Integer timeoutSeconds,
                                  Integer retries, String nasIdentifier) {}

    public record TestRequest(String username, String password) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> get() {
        return view(settings.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    @Transactional
    public Map<String, Object> save(@RequestBody SettingsRequest request) {
        RadiusSettings current = settings.current();

        // The same rule the CHECK constraint enforces, stated here so the answer
        // is a readable message rather than a constraint violation.
        if (request.enabled()) {
            if (request.host() == null || request.host().isBlank()) {
                throw new ApiExceptions.BadRequestException(
                        "A server address is needed before RADIUS sign-in can be switched on.");
            }
            if (request.sharedSecretRef() == null || request.sharedSecretRef().isBlank()) {
                throw new ApiExceptions.BadRequestException(
                        "Name the environment variable holding the shared secret before switching this on.");
            }
        }

        current.setEnabled(request.enabled());
        current.setHost(trimmedOrNull(request.host()));
        current.setPort(request.port() == null ? 1812 : request.port());
        current.setSharedSecretRef(trimmedOrNull(request.sharedSecretRef()));
        current.setTimeoutSeconds(request.timeoutSeconds() == null ? 5 : request.timeoutSeconds());
        current.setRetries(request.retries() == null ? 1 : request.retries());
        current.setNasIdentifier(trimmedOrNull(request.nasIdentifier()));
        current.setUpdatedBy(currentUser.principal().map(p -> p.getId()).orElse(null));
        current.setUpdatedAt(Instant.now());

        RadiusSettings saved = settings.save(current);
        // The same convention mail_settings uses: the fact of the change, never
        // anything that could be a credential. There is nothing secret to leak
        // here anyway -- only the NAME of an environment variable is stored --
        // but the audit trail should not become the first exception to that.
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, 1L, List.of(
                AuditService.FieldChange.of("radius_settings", null,
                        saved.isEnabled()
                                ? "RADIUS sign-in enabled against " + saved.getHost() + ":" + saved.getPort()
                                : "RADIUS sign-in disabled")));
        return view(saved);
    }

    /**
     * A real Access-Request with a real credential, because that is the only
     * thing that proves the whole path works. A reachability check would pass
     * against a server whose shared secret is wrong, whose network policy
     * excludes this NAS, or which rejects PAP -- all of which look like a wrong
     * password later, to somebody who cannot see this screen.
     *
     * <p>The credential is used and discarded. It is never stored, never logged,
     * and never returned.
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> test(@RequestBody TestRequest request) {
        RadiusSettings config = settings.current();
        if (config.getHost() == null || config.getHost().isBlank()) {
            return Map.of("ok", false, "message", "Set a server address first.");
        }
        String secret = secrets.resolve(config.getSharedSecretRef());
        if (secret == null) {
            return Map.of("ok", false, "message",
                    "'" + config.getSharedSecretRef() + "' is not set in this application's environment, "
                            + "or is empty. Set it and restart the application.");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isEmpty()) {
            return Map.of("ok", false, "message",
                    "Enter a username and password to test with. They are used once and not stored.");
        }

        RadiusClient client = new RadiusClient(config.getHost(), secret);
        try {
            client.setAuthPort(config.getPort());
            client.setRetryCount(Math.max(1, config.getRetries()));
            client.setSocketTimeout(config.getTimeoutSeconds() * 1000);

            AccessRequest accessRequest = new AccessRequest(request.username(), request.password());
            accessRequest.setAuthProtocol(AccessRequest.AUTH_PAP);
            if (config.getNasIdentifier() != null && !config.getNasIdentifier().isBlank()) {
                accessRequest.addAttribute("NAS-Identifier", config.getNasIdentifier());
            }

            RadiusPacket reply = client.authenticate(accessRequest);
            if (reply != null && reply.getPacketType() == RadiusPacket.ACCESS_ACCEPT) {
                return Map.of("ok", true, "message", "Accepted. RADIUS sign-in is working.");
            }
            // A reject is a working server saying no, which is still proof the
            // path is sound -- and is worth distinguishing from not reaching it.
            return Map.of("ok", false, "message",
                    "The server replied, and rejected those credentials. The connection and shared "
                            + "secret are working; the username or password is what it did not like.");
        } catch (Exception e) {
            return Map.of("ok", false, "message",
                    "Could not complete the exchange with " + config.getHost() + ":" + config.getPort()
                            + " -- " + rootMessage(e)
                            + ". A timeout here usually means a firewall, the wrong port, or a shared "
                            + "secret that does not match.");
        } finally {
            client.close();
        }
    }

    private Map<String, Object> view(RadiusSettings s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", s.isEnabled());
        view.put("host", s.getHost());
        view.put("port", s.getPort());
        view.put("sharedSecretRef", s.getSharedSecretRef());
        // Whether it resolves, never what to. The screen has to be able to show
        // "that variable is not set" without ever holding the secret.
        view.put("sharedSecretResolves", secrets.isSet(s.getSharedSecretRef()));
        view.put("timeoutSeconds", s.getTimeoutSeconds());
        view.put("retries", s.getRetries());
        view.put("nasIdentifier", s.getNasIdentifier());
        view.put("updatedAt", s.getUpdatedAt());
        return view;
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
