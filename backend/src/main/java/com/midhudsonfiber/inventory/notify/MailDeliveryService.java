package com.midhudsonfiber.inventory.notify;

import com.midhudsonfiber.inventory.domain.MailSettings;
import com.midhudsonfiber.inventory.domain.NotificationLog;
import com.midhudsonfiber.inventory.repo.MailSettingsRepository;
import com.midhudsonfiber.inventory.repo.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Properties;

/**
 * Sending the email half of a notification, when there is anywhere to send it.
 *
 * <p>The relay is configured in the application and lives in {@code mail_settings},
 * so this builds its own sender from the current row rather than using a
 * Spring-configured one. That is the price of letting an administrator change
 * the relay without a redeploy, and it is the right trade: the alternative is a
 * restart every time an SMTP password rotates.
 *
 * <p>Nothing here throws. A relay that is down, wrong, or simply absent must not
 * stop a purchase request being submitted — the in-app notification has already
 * been written by the time this runs, and the failure is recorded against the
 * row so somebody can see what happened.
 */
@Service
public class MailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryService.class);

    private final MailSettingsRepository settings;
    private final NotificationLogRepository logs;

    public MailDeliveryService(MailSettingsRepository settings, NotificationLogRepository logs) {
        this.settings = settings;
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public MailSettings current() {
        return settings.findById((short) 1).orElseGet(MailSettings::new);
    }

    /** Whether email is worth attempting at all. */
    public boolean configured() {
        return current().isUsable();
    }

    /**
     * Attempts the email for one already-recorded notification.
     *
     * <p>Its own transaction, because the outcome must survive whatever happens
     * to the caller: a notification that went out and was then forgotten would
     * be sent again on the next run.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trySend(NotificationLog entry) {
        if (entry.getEmailStatus() != NotificationLog.EmailStatus.PENDING) return;

        NotificationLog row = logs.findById(entry.getId()).orElse(null);
        if (row == null) return;

        MailSettings config = current();
        if (!config.isUsable() || row.getRecipientEmail() == null) {
            row.setEmailStatus(NotificationLog.EmailStatus.SKIPPED);
            logs.save(row);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(config.getFromAddress());
            message.setTo(row.getRecipientEmail());
            message.setSubject(row.getSubject());
            message.setText(row.getBody());
            senderFor(config).send(message);

            row.setEmailStatus(NotificationLog.EmailStatus.SENT);
            row.setEmailedAt(Instant.now());
            row.setEmailError(null);
        } catch (Exception e) {
            // Recorded rather than thrown. The person still has the in-app copy,
            // and an administrator can see on the row why the email did not go.
            row.setEmailStatus(NotificationLog.EmailStatus.FAILED);
            row.setEmailError(summarise(e));
            log.warn("Could not email notification {}: {}", row.getId(), e.toString());
        }
        logs.save(row);
    }

    /**
     * Sends a test message to prove the settings work, and reports the failure
     * rather than swallowing it — this is the one place a person is asking to be
     * told exactly what went wrong.
     */
    public void sendTest(MailSettings config, String to) throws Exception {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.getFromAddress());
        message.setTo(to);
        message.setSubject("Inventory Manager test message");
        message.setText("""
                This is a test from Inventory Manager.

                If you are reading it, the SMTP settings work and notifications \
                will be emailed as well as shown in the application.""");
        senderFor(config).send(message);
    }

    private JavaMailSenderImpl senderFor(MailSettings config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            sender.setUsername(config.getUsername());
            sender.setPassword(config.getPassword());
        }

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(
                config.getUsername() != null && !config.getUsername().isBlank()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(config.isStartTls()));
        // Bounded so a relay that accepts the connection and then stops talking
        // cannot hold a scheduled run open indefinitely.
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    /** The useful line, not the stack: this is shown to a human in the UI. */
    private static String summarise(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
