package com.midhudsonfiber.inventory.notify;

import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.NotificationLogRepository;
import com.midhudsonfiber.inventory.repo.NotificationRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns "this happened" into notifications for the right people.
 *
 * <p>Targets are resolved when the notification is sent, never stored against
 * it. That is the whole point of the mechanism: somebody who joins the Asset
 * Manager role today starts receiving warranty alerts today, and there is no
 * recipient list anywhere for anyone to forget to update.
 *
 * <p>In-app delivery is unconditional — a row in {@code notification_log} is the
 * notification. Email is an addition on top, attempted only when a relay is
 * configured, and its failure never fails the thing that triggered it: an SMTP
 * server being down is not a reason a purchase request cannot be submitted.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRuleRepository rules;
    private final NotificationLogRepository logs;
    private final AppUserRepository users;
    private final MailDeliveryService mail;

    public NotificationService(NotificationRuleRepository rules, NotificationLogRepository logs,
                               AppUserRepository users, MailDeliveryService mail) {
        this.rules = rules;
        this.logs = logs;
        this.users = users;
        this.mail = mail;
    }

    /**
     * One thing worth telling people about.
     *
     * @param dedupeKey what makes this the same as a notification already sent.
     *                  A scheduled trigger re-runs every night, so this is what
     *                  stops it re-saying the same thing until the underlying
     *                  situation goes away.
     */
    public record Event(NotificationRule.TriggerType triggerType,
                        Long categoryId,
                        String subject,
                        String body,
                        String entityType,
                        Long entityId,
                        String dedupeKey) {}

    /**
     * Fires every active rule for this event's trigger.
     *
     * <p>Runs in its own transaction so a notification is never rolled back by
     * whatever came after it, and never rolls back its caller: the point of a
     * purchase request being submitted is the request, not the alert.
     */
    /*
     * Frequency never throttles the notice itself. An event rule fires whenever
     * its event happens -- dropping some because a cadence had not elapsed would
     * mean silently losing things that occurred. The cadence batches the email
     * and nothing else; a scheduled rule decides how often to go looking in the
     * sweep that raises it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publish(Event event) {
        List<NotificationLog> created = new ArrayList<>();

        for (NotificationRule rule : rules.findActiveFor(event.triggerType())) {
            // A rule narrowed to a category ignores everything else. Null means
            // every category, which is how the seeded rules are set up.
            if (rule.getCategory() != null && event.categoryId() != null
                    && !rule.getCategory().getId().equals(event.categoryId())) {
                continue;
            }
            created.addAll(deliver(rule, event));
        }

        // Email after the rows exist, so an in-app notification is never lost to
        // a relay problem, and a crash between the two leaves something to retry.
        // Deferred ones are left for the digest job.
        for (NotificationLog entry : created) {
            if (entry.getEmailStatus() == NotificationLog.EmailStatus.PENDING) mail.trySend(entry);
        }
        return created.size();
    }

    private List<NotificationLog> deliver(NotificationRule rule, Event event) {
        List<NotificationLog> created = new ArrayList<>();

        // A person in two targeted roles is one person, and should be told once.
        Set<Long> userIds = new LinkedHashSet<>();
        Set<String> addresses = new LinkedHashSet<>();

        for (DistributionTarget target : rule.getTargets()) {
            if (target.getTargetType() == DistributionTarget.TargetType.ROLE) {
                if (target.getRole() == null) continue;
                users.findActiveByRoleId(target.getRole().getId())
                        .forEach(user -> userIds.add(user.getId()));
            } else if (target.getEmailAddress() != null) {
                addresses.add(target.getEmailAddress().trim());
            }
        }

        for (Long userId : userIds) {
            NotificationLog entry = record(rule, event, userId, null);
            if (entry != null) created.add(entry);
        }
        for (String address : addresses) {
            NotificationLog entry = record(rule, event, null, address);
            if (entry != null) created.add(entry);
        }
        return created;
    }

    /** @return the stored row, or null if this recipient already has it */
    private NotificationLog record(NotificationRule rule, Event event, Long userId, String address) {
        boolean already = userId != null
                ? logs.existsByDedupeKeyAndRecipientUserId(event.dedupeKey(), userId)
                : logs.existsByDedupeKeyAndRecipientEmail(event.dedupeKey(), address);
        if (already) return null;

        NotificationLog entry = new NotificationLog();
        entry.setNotificationRuleId(rule.getId());
        entry.setTriggerType(event.triggerType().name());
        entry.setRecipientUserId(userId);
        entry.setRecipientEmail(address != null ? address : emailOf(userId));
        entry.setSubject(event.subject());
        entry.setBody(event.body());
        entry.setEntityType(event.entityType());
        entry.setEntityId(event.entityId());
        entry.setDedupeKey(event.dedupeKey());
        // The in-app copy is written either way; only the email waits. A rule
        // set to a digest holds its mail for the batch, which is the whole point
        // of choosing a cadence -- but nobody's notification centre goes quiet
        // because somebody preferred weekly email.
        boolean emailable = mail.configured() && entry.getRecipientEmail() != null;
        entry.setEmailStatus(!emailable ? NotificationLog.EmailStatus.SKIPPED
                : rule.getFrequency() == NotificationRule.Frequency.IMMEDIATE
                        ? NotificationLog.EmailStatus.PENDING
                        : NotificationLog.EmailStatus.DEFERRED);

        try {
            return logs.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            // Two scheduler runs overlapping. The unique index is the authority
            // and it has already refused this; losing the race is the correct
            // outcome, not an error worth surfacing.
            log.debug("Notification already recorded for {}", event.dedupeKey());
            return null;
        }
    }

    /** A user with no address still gets the in-app copy; only email needs one. */
    private String emailOf(Long userId) {
        return users.findById(userId).map(AppUser::getEmail).filter(e -> !e.isBlank()).orElse(null);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return userId == null ? 0 : logs.countByRecipientUserIdAndReadAtIsNull(userId);
    }
}
