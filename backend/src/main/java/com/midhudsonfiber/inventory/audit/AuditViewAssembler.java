package com.midhudsonfiber.inventory.audit;

import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns audit rows into something a person can read: "who" is a username, not a
 * database id. The id is still returned alongside it, since a username can be
 * changed and the id is what the row actually recorded.
 *
 * <p>Names are resolved in one batch per page rather than per row, and a null
 * actor is reported as "system" — that is a real case, not missing data: plugin
 * and scheduled writes have no user behind them.
 */
@Component
public class AuditViewAssembler {

    public static final String SYSTEM_ACTOR = "system";

    private final AppUserRepository users;

    public AuditViewAssembler(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> toViews(List<AuditEvent> events) {
        Set<Long> actorIds = events.stream()
                .map(AuditEvent::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> usernames = actorIds.isEmpty()
                ? Map.of()
                : users.findAllById(actorIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername, (a, b) -> a));

        return events.stream().map(toView(usernames)).toList();
    }

    private Function<AuditEvent, Map<String, Object>> toView(Map<Long, String> usernames) {
        return event -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", event.getId());
            view.put("entityType", event.getEntityType());
            view.put("entityId", event.getEntityId());
            view.put("occurredAt", event.getOccurredAt());
            view.put("action", event.getAction());
            view.put("fieldName", event.getFieldName());
            view.put("previousValue", event.getPreviousValue());
            view.put("newValue", event.getNewValue());
            view.put("reason", event.getReason());
            view.put("userId", event.getUserId());
            view.put("username", event.getUserId() == null
                    ? SYSTEM_ACTOR
                    // A deleted account leaves rows behind it; audit outlives the user.
                    : usernames.getOrDefault(event.getUserId(), "user #" + event.getUserId()));
            return view;
        };
    }
}
