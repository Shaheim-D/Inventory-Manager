package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.NotificationLog;
import com.midhudsonfiber.inventory.notify.NotificationService;
import com.midhudsonfiber.inventory.repo.NotificationLogRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A person's own notifications.
 *
 * <p>Deliberately gated on nothing but being signed in. There is no
 * {@code notification:read} key because there is nothing to grant: these rows
 * are addressed to you, and every query here is scoped to the caller rather
 * than taking a user id from the request. A permission check would be
 * describing a boundary that the scoping already makes unreachable.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationLogRepository logs;
    private final NotificationService notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationLogRepository logs, NotificationService notifications,
                                  CurrentUser currentUser) {
        this.logs = logs;
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        Long me = currentUser.idOrNull();
        if (me == null) return Map.of("content", java.util.List.of(), "unread", 0, "totalElements", 0);

        Page<NotificationLog> found = logs.findByRecipientUserIdOrderByCreatedAtDesc(
                me, PageRequest.of(page, Math.min(size, 100)));
        return Map.of(
                "content", found.getContent().stream().map(NotificationController::toView).toList(),
                "page", found.getNumber(),
                "totalElements", found.getTotalElements(),
                "totalPages", found.getTotalPages(),
                "unread", notifications.unreadCount(me));
    }

    /** The bell's badge. Its own endpoint so it can be cheap and polled. */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("unread", notifications.unreadCount(currentUser.idOrNull()));
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        NotificationLog entry = logs.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Notification not found"));
        // Someone else's notification is not found, rather than forbidden: the
        // existence of a row addressed to another person is not the caller's to
        // learn about.
        if (!java.util.Objects.equals(entry.getRecipientUserId(), currentUser.idOrNull())) {
            throw new ApiExceptions.NotFoundException("Notification not found");
        }
        if (entry.getReadAt() == null) {
            entry.setReadAt(Instant.now());
            logs.save(entry);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Object> markAllRead() {
        Long me = currentUser.idOrNull();
        int updated = me == null ? 0 : logs.markAllRead(me, Instant.now());
        return Map.of("marked", updated);
    }

    private static Map<String, Object> toView(NotificationLog entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entry.getId());
        view.put("triggerType", entry.getTriggerType());
        view.put("subject", entry.getSubject());
        view.put("body", entry.getBody());
        view.put("entityType", entry.getEntityType());
        view.put("entityId", entry.getEntityId());
        view.put("createdAt", entry.getCreatedAt());
        view.put("readAt", entry.getReadAt());
        // Shown so somebody chasing a missing email can see it was recorded as
        // sent, skipped for want of a relay, or failed with a reason.
        view.put("emailStatus", entry.getEmailStatus().name());
        view.put("emailError", entry.getEmailError());
        return view;
    }
}
