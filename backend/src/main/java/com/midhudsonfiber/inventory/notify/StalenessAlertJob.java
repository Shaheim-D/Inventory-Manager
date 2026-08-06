package com.midhudsonfiber.inventory.notify;

import com.midhudsonfiber.inventory.domain.NotificationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;

/**
 * The other scheduled trigger: stock that nobody has laid eyes on inside the
 * window its category asks for (Staleness design §4).
 *
 * <p>Raised per category rather than per asset, deliberately. Staleness is a
 * bulk problem — the categories that carry a verification interval are the ones
 * holding hundreds of patch leads and SFPs, and an alert per asset would be a
 * hundred notifications saying the same sentence. One notice per category with
 * a count is the thing a person can act on, and the verification queue is
 * already the screen for working through it.
 *
 * <p>The de-duplication key carries the ISO week, so a category that stays
 * behind is mentioned once a week until it is dealt with rather than once ever.
 * Saying it once and going quiet would let a queue rot unnoticed; saying it
 * every hour, which is how often this runs, would train people to ignore it.
 */
@Component
public class StalenessAlertJob {

    private static final Logger log = LoggerFactory.getLogger(StalenessAlertJob.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    public StalenessAlertJob(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    // One clock for both sweeps. The dedupe key, not the cron, decides how often
    // anything is actually said.
    @Scheduled(cron = "${app.notifications.staleness-cron:0 45 * * * *}")
    public void run() {
        int raised = sweep();
        if (raised > 0) log.info("Staleness alerts: {} notification(s) raised", raised);
    }

    /** @return how many notifications were raised */
    public int sweep() {
        // A NULL interval means the category is not verified at all, which is
        // most of them -- a router's location is known because it is racked and
        // reachable, and asking somebody to confirm it every quarter is busywork.
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                SELECT c.id                              AS category_id,
                       c.name                            AS category_name,
                       c.verification_interval_days      AS interval_days,
                       count(*)                          AS overdue_count,
                       min(a.last_verified_at)           AS oldest
                FROM asset a
                JOIN asset_category c ON c.id = a.asset_category_id
                JOIN lifecycle_state s ON s.id = a.lifecycle_state_id
                WHERE a.is_deleted = FALSE
                  AND c.verification_interval_days IS NOT NULL
                  AND s.name NOT IN ('Disposed', 'Retired')
                  AND a.last_verified_at
                      < now() - make_interval(days => c.verification_interval_days)
                GROUP BY c.id, c.name, c.verification_interval_days
                ORDER BY c.id
                """);

        LocalDate today = LocalDate.now();
        WeekFields weeks = WeekFields.ISO;
        String week = "%d-W%02d".formatted(
                today.get(weeks.weekBasedYear()), today.get(weeks.weekOfWeekBasedYear()));

        int raised = 0;
        for (Map<String, Object> row : overdue) {
            Long categoryId = ((Number) row.get("category_id")).longValue();
            String category = String.valueOf(row.get("category_name"));
            int days = ((Number) row.get("interval_days")).intValue();
            long count = ((Number) row.get("overdue_count")).longValue();

            raised += notifications.publish(new NotificationService.Event(
                    NotificationRule.TriggerType.INVENTORY_STALENESS_CHECK,
                    categoryId,
                    "%d %s %s overdue for verification".formatted(
                            count, category, count == 1 ? "item is" : "items are"),
                    """
                    %d %s %s not been confirmed as still in inventory within the \
                    %d-day window that category asks for.

                    They are listed on the inventory verification screen."""
                            .formatted(count, category, count == 1 ? "item has" : "items have", days),
                    "VERIFICATION_QUEUE",
                    categoryId,
                    // Category and week: mentioned again next week if it is still
                    // behind, and not again before then.
                    "STALENESS:%d:%s".formatted(categoryId, week)));
        }
        return raised;
    }
}
