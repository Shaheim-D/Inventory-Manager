package com.midhudsonfiber.inventory.notify;

import com.midhudsonfiber.inventory.domain.NotificationLog;
import com.midhudsonfiber.inventory.domain.NotificationRule;
import com.midhudsonfiber.inventory.repo.NotificationLogRepository;
import com.midhudsonfiber.inventory.repo.NotificationRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the email a rule chose not to send immediately.
 *
 * <p>A rule set to daily or weekly still notifies in the application the moment
 * something happens — what its cadence buys is one email instead of forty. This
 * gathers everything that has been waiting for a given rule and recipient and
 * sends it as one message.
 *
 * <p>Runs every quarter of an hour and asks each rule whether its interval has
 * elapsed, rather than each frequency getting its own cron. One clock, and
 * adding a frequency is a enum constant rather than another scheduled method.
 */
@Component
public class NotificationDigestJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDigestJob.class);

    private final NotificationRuleRepository rules;
    private final NotificationLogRepository logs;
    private final MailDeliveryService mail;

    public NotificationDigestJob(NotificationRuleRepository rules, NotificationLogRepository logs,
                                 MailDeliveryService mail) {
        this.rules = rules;
        this.logs = logs;
        this.mail = mail;
    }

    @Scheduled(cron = "${app.notifications.digest-cron:0 */15 * * * *}")
    public void run() {
        int sent = sweep();
        if (sent > 0) log.info("Notification digests: {} message(s) sent", sent);
    }

    /** @return how many digest emails went out */
    @Transactional
    public int sweep() {
        if (!mail.configured()) return 0;
        Instant now = Instant.now();
        int sent = 0;

        for (NotificationRule rule : rules.findAll()) {
            if (rule.getFrequency() == NotificationRule.Frequency.IMMEDIATE) continue;
            if (!rule.isDue(now)) continue;

            List<NotificationLog> waiting = logs.findByNotificationRuleIdAndEmailStatusOrderByIdAsc(
                    rule.getId(), NotificationLog.EmailStatus.DEFERRED);
            // The clock advances even with nothing to send, so a quiet week does
            // not leave the rule permanently "overdue" and firing every quarter
            // hour the moment something finally arrives.
            rule.setLastRunAt(now);
            rules.save(rule);
            if (waiting.isEmpty()) continue;

            // One message per person, not one per notification -- which is the
            // entire reason somebody chose a digest.
            Map<String, List<NotificationLog>> byRecipient = new LinkedHashMap<>();
            for (NotificationLog entry : waiting) {
                if (entry.getRecipientEmail() == null) {
                    entry.setEmailStatus(NotificationLog.EmailStatus.SKIPPED);
                    logs.save(entry);
                    continue;
                }
                byRecipient.computeIfAbsent(entry.getRecipientEmail(), key -> new java.util.ArrayList<>())
                        .add(entry);
            }

            for (Map.Entry<String, List<NotificationLog>> batch : byRecipient.entrySet()) {
                sent += send(rule, batch.getKey(), batch.getValue()) ? 1 : 0;
            }
        }
        return sent;
    }

    private boolean send(NotificationRule rule, String address, List<NotificationLog> entries) {
        StringBuilder body = new StringBuilder();
        body.append(entries.size() == 1
                ? "One thing has happened since the last digest:\n\n"
                : "%d things have happened since the last digest:\n\n".formatted(entries.size()));
        for (NotificationLog entry : entries) {
            body.append("- ").append(entry.getSubject()).append('\n');
        }
        body.append("\nEach is also in the application under the bell.");

        try {
            mail.sendDigest(address,
                    "%s: %d update(s)".formatted(rule.getName(), entries.size()),
                    body.toString());
            for (NotificationLog entry : entries) {
                entry.setEmailStatus(NotificationLog.EmailStatus.SENT);
                entry.setEmailedAt(Instant.now());
                logs.save(entry);
            }
            return true;
        } catch (Exception e) {
            // Recorded on every row it covered, so the failure is visible next to
            // the notifications it was carrying rather than only in a log file.
            for (NotificationLog entry : entries) {
                entry.setEmailStatus(NotificationLog.EmailStatus.FAILED);
                entry.setEmailError(e.getMessage());
                logs.save(entry);
            }
            log.warn("Digest for {} to {} failed: {}", rule.getName(), address, e.toString());
            return false;
        }
    }
}
