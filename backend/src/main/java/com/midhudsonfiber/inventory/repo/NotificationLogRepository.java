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

    Page<NotificationLog> findByRecipientUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(Long userId);

    /** The civil half of de-duplication; the unique index is the real guard. */
    boolean existsByDedupeKeyAndRecipientUserId(String dedupeKey, Long recipientUserId);

    boolean existsByDedupeKeyAndRecipientEmail(String dedupeKey, String recipientEmail);

    List<NotificationLog> findTop200ByEmailStatusOrderByIdAsc(NotificationLog.EmailStatus status);

    /** Everything a digest-frequency rule has been holding back. */
    List<NotificationLog> findByNotificationRuleIdAndEmailStatusOrderByIdAsc(
            Long notificationRuleId, NotificationLog.EmailStatus emailStatus);

    /** New since the caller last looked — what the on-screen popup keys off. */
    List<NotificationLog> findByRecipientUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long afterId);

    @Modifying
    @Query("""
            UPDATE NotificationLog n SET n.readAt = :now
            WHERE n.recipientUserId = :userId AND n.readAt IS NULL
            """)
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
