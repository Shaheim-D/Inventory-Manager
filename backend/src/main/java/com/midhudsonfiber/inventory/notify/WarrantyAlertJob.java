package com.midhudsonfiber.inventory.notify;

import com.midhudsonfiber.inventory.domain.NotificationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The scheduled half of Milestone 4: assets whose warranty is coming up on a
 * threshold their category cares about.
 *
 * <p>Thresholds are per category and there may be several — 90 days and 30 days
 * for a router, say. Each is its own thing to say, so crossing 90 notifies once
 * and crossing 30 notifies again a couple of months later; the de-duplication
 * key carries the threshold for exactly that reason. Without it a nightly run
 * would repeat the same alert for three months and train people to ignore it.
 *
 * <p>The query is SQL rather than JPA because it is a set operation across two
 * tables with a date comparison, and expressing it as entities would mean
 * loading every asset with a warranty to filter in Java.
 */
@Component
public class WarrantyAlertJob {

    private static final Logger log = LoggerFactory.getLogger(WarrantyAlertJob.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    public WarrantyAlertJob(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /**
     * Early morning, so a person's first look at the day already has it. Runs
     * daily because a threshold is crossed on a particular date and nothing
     * finer than a day is meaningful here.
     */
    // Hourly, with each rule deciding whether its own cadence has come round.
    // One clock rather than one cron per frequency, so adding a frequency is an
    // enum constant instead of another scheduled method.
    @Scheduled(cron = "${app.notifications.warranty-cron:0 15 * * * *}")
    public void run() {
        int sent = sweep();
        if (sent > 0) log.info("Warranty alerts: {} notification(s) raised", sent);
    }

    /**
     * Exposed so the milestone is demonstrable on demand rather than only at
     * 6:15am, and so a test can run the real thing instead of a copy of it.
     *
     * @return how many notifications were raised
     */
    public int sweep() {
        // An asset is due when its expiration falls within a threshold window its
        // category defines. Disposed and retired kit is excluded -- its warranty
        // running out is not news.
        //
        // DISTINCT ON picks the tightest window the asset is currently inside,
        // and only that one. A router has 90-, 60- and 30-day thresholds, so an
        // asset with 29 days left is inside all three; firing each would send
        // three alerts at once, one of them announcing a 90-day notice for
        // something expiring next month. Crossing them in turn still notifies in
        // turn -- at 89 days the tightest match is 90, at 59 it is 60 -- because
        // each is a separate dedupe key.
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT DISTINCT ON (a.id)
                       a.id             AS asset_id,
                       a.asset_category_id,
                       t.days_before_expiration,
                       a.warranty_expiration,
                       coalesce(a.name, a.hostname, a.asset_tag, 'Asset #' || a.id) AS label,
                       c.name           AS category_name
                FROM asset a
                JOIN asset_category c ON c.id = a.asset_category_id
                JOIN warranty_alert_threshold t ON t.asset_category_id = a.asset_category_id
                JOIN lifecycle_state s ON s.id = a.lifecycle_state_id
                WHERE a.is_deleted = FALSE
                  AND a.warranty_expiration IS NOT NULL
                  AND s.name NOT IN ('Disposed', 'Retired')
                  AND a.warranty_expiration >= current_date
                  AND a.warranty_expiration <= current_date + t.days_before_expiration
                ORDER BY a.id, t.days_before_expiration ASC
                """);

        int raised = 0;
        for (Map<String, Object> row : due) {
            Long assetId = ((Number) row.get("asset_id")).longValue();
            Long categoryId = ((Number) row.get("asset_category_id")).longValue();
            int days = ((Number) row.get("days_before_expiration")).intValue();
            LocalDate expires = ((java.sql.Date) row.get("warranty_expiration")).toLocalDate();
            String label = String.valueOf(row.get("label"));
            String category = String.valueOf(row.get("category_name"));

            raised += notifications.publish(new NotificationService.Event(
                    NotificationRule.TriggerType.WARRANTY_EXPIRATION,
                    categoryId,
                    "Warranty expiring: " + label,
                    """
                    %s (%s) has a warranty expiring on %s.

                    This is the %d-day notice for its category. It will not be \
                    repeated at this threshold."""
                            .formatted(label, category, expires, days),
                    "ASSET",
                    assetId,
                    // The asset and the threshold together: crossing 90 days and
                    // later crossing 30 are two different things to say.
                    "WARRANTY:%d:%d".formatted(assetId, days)));
        }
        return raised;
    }
}
