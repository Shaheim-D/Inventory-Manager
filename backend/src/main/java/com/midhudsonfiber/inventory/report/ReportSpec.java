package com.midhudsonfiber.inventory.report;

import com.midhudsonfiber.inventory.domain.SavedReportDefinition.EntityType;

import java.util.List;
import java.util.Map;

/**
 * What to report on: an entity, the columns wanted, and how to narrow the rows.
 *
 * <p>The same shape whether it came from a canned report, the custom builder, or
 * a saved definition — there is one engine and three ways of describing what to
 * feed it, rather than three implementations that drift.
 *
 * @param filters loosely typed on purpose. It is stored as JSONB in a saved
 *                definition and arrives as JSON from the builder; giving it a
 *                fixed record would mean a schema change and a migration every
 *                time a report learns a new way to narrow itself.
 */
public record ReportSpec(EntityType entity,
                         List<String> fields,
                         Map<String, Object> filters) {

    public ReportSpec {
        fields = fields == null ? List.of() : List.copyOf(fields);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    public List<Long> ids(String key) {
        Object raw = filters.get(key);
        if (raw instanceof Number one) return List.of(one.longValue());
        if (raw instanceof List<?> many) {
            return many.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value instanceof Number number
                            ? number.longValue()
                            : Long.parseLong(String.valueOf(value)))
                    .toList();
        }
        if (raw instanceof String text && !text.isBlank()) return List.of(Long.parseLong(text));
        return List.of();
    }

    public String text(String key) {
        Object raw = filters.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).trim();
    }

    public Integer number(String key) {
        Object raw = filters.get(key);
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String text && !text.isBlank()) return Integer.parseInt(text.trim());
        return null;
    }

    public java.time.LocalDate date(String key) {
        String raw = text(key);
        return raw == null ? null : java.time.LocalDate.parse(raw);
    }
}
