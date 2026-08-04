package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.MailSettings;
import com.midhudsonfiber.inventory.notify.MailDeliveryService;
import com.midhudsonfiber.inventory.repo.MailSettingsRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SMTP relay, configured from Settings rather than from a deploy.
 *
 * <p>Gated on {@code notification_rule:manage}, which already means "may decide
 * how this system notifies people" — configuring the relay those notifications
 * leave by is the same job, and a new permission key for it would be a
 * distinction nobody administering this would recognise.
 *
 * <p>The password goes in and never comes out. The response says whether one is
 * set, never what it is, and an update that omits it keeps the stored one — so
 * saving the form after changing the port does not silently blank the
 * credential.
 */
@RestController
@RequestMapping("/api/admin/mail-settings")
public class MailSettingsController {

    private final MailSettingsRepository settings;
    private final MailDeliveryService mail;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public MailSettingsController(MailSettingsRepository settings, MailDeliveryService mail,
                                  AuditService audit, CurrentUser currentUser) {
        this.settings = settings;
        this.mail = mail;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record SettingsRequest(boolean enabled, String host, Integer port, String username,
                                  String password, String fromAddress, boolean startTls) {}

    public record TestRequest(String to) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    public Map<String, Object> get() {
        return toView(mail.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    @Transactional
    public Map<String, Object> update(@RequestBody SettingsRequest request) {
        MailSettings current = settings.findById((short) 1).orElseGet(MailSettings::new);

        current.setEnabled(request.enabled());
        current.setHost(blankToNull(request.host()));
        current.setPort(request.port());
        current.setUsername(blankToNull(request.username()));
        current.setFromAddress(blankToNull(request.fromAddress()));
        current.setStartTls(request.startTls());
        // Absent means unchanged; an explicit empty string means clear it.
        if (request.password() != null) {
            current.setPassword(request.password().isEmpty() ? null : request.password());
        }
        current.setUpdatedBy(currentUser.idOrNull());

        if (request.enabled() && (current.getHost() == null || current.getPort() == null
                || current.getFromAddress() == null)) {
            throw new ApiExceptions.BadRequestException(
                    "A host, a port and a from address are needed before email can be turned on.");
        }

        MailSettings saved = settings.save(current);
        // The password is never in the audit trail, only the fact of a change.
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, 1L, List.of(
                AuditService.FieldChange.of("mail_settings", null,
                        saved.isEnabled() ? "Email enabled via " + saved.getHost() : "Email disabled")));
        return toView(saved);
    }

    /**
     * Proves the settings work before anything depends on them. Uses what was
     * submitted rather than what is stored, so a relay can be checked before it
     * is saved — and reports the failure verbatim, because this is the one
     * place somebody is explicitly asking what went wrong.
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    public Map<String, Object> test(@RequestBody TestRequest request) {
        MailSettings config = mail.current();
        if (!config.isUsable()) {
            throw new ApiExceptions.BadRequestException(
                    "Save a host, port and from address, and turn email on, before sending a test.");
        }
        String to = blankToNull(request.to());
        if (to == null) {
            throw new ApiExceptions.BadRequestException("Give an address to send the test to.");
        }

        try {
            mail.sendTest(config, to);
            return Map.of("ok", true, "message", "Test message sent to " + to + ".");
        } catch (Exception e) {
            return Map.of("ok", false, "message",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Map<String, Object> toView(MailSettings config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", config.isEnabled());
        view.put("host", config.getHost());
        view.put("port", config.getPort());
        view.put("username", config.getUsername());
        view.put("fromAddress", config.getFromAddress());
        view.put("startTls", config.isStartTls());
        // Whether there is one, never what it is.
        view.put("passwordSet", config.getPassword() != null && !config.getPassword().isBlank());
        view.put("usable", config.isUsable());
        view.put("updatedAt", config.getUpdatedAt());
        return view;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
