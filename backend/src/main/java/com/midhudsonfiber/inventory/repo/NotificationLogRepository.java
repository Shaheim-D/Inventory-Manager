package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * The inbox. Cleared rows are excluded everywhere the recipient looks --
     * they still exist, because they are also the de-duplication record, but
     * from the notification centre's point of view they are gone.
     */
    Page<NotificationLog> findByRecipientUserIdAndClearedAtIsNullOrderByCreatedAtDesc(
            Long userId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNullAndClearedAtIsNull(Long userId);

    /** The civil half of de-duplication; the unique index is the real guard. */
    boolean existsByDedupeKeyAndRecipientUserId(String dedupeKey, Long recipientUserId);

    boolean existsByDedupeKeyAndRecipientEmail(String dedupeKey, String recipientEmail);

    List<NotificationLog> findTop200ByEmailStatusOrderByIdAsc(NotificationLog.EmailStatus status);

    /** Everything a digest-frequency rule has been holding back. */
    List<NotificationLog> findByNotificationRuleIdAndEmailStatusOrderByIdAsc(
            Long notificationRuleId, NotificationLog.EmailStatus emailStatus);

    /** New since the caller last looked — what the on-screen popup keys off. */
    List<NotificationLog> findByRecipientUserIdAndClearedAtIsNullAndIdGreaterThanOrderByIdAsc(
            Long userId, Long afterId);

    @Modifying
    @Query("""
            UPDATE NotificationLog n SET n.readAt = :now
            WHERE n.recipientUserId = :userId AND n.readAt IS NULL AND n.clearedAt IS NULL
            """)
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Empties somebody's notification centre.
     *
     * <p>Marks them read at the same time, so a cleared notification cannot go
     * on counting towards a badge for something no longer on screen. Kept as an
     * update rather than a delete: see {@code cleared_at}.
     */
    @Modifying
    @Query("""
            UPDATE NotificationLog n
            SET n.clearedAt = :now, n.readAt = coalesce(n.readAt, :now)
            WHERE n.recipientUserId = :userId AND n.clearedAt IS NULL
            """)
    int clearAll(@Param("userId") Long userId, @Param("now") Instant now);
}
