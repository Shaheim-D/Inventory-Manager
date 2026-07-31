package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import com.midhudsonfiber.inventory.repo.CustomFieldDefinitionRepository;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom field values live in one JSONB column, so the database cannot type-check
 * them. That trade was made deliberately (MOP Part 10) on the condition that the
 * application layer validates against {@code custom_field_definition} — this is
 * that validation, and it is the only way values reach {@code asset.custom_fields}.
 */
@Service
public class CustomFieldValidator {

    private final CustomFieldDefinitionRepository definitions;

    public CustomFieldValidator(CustomFieldDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    /**
     * Returns the values to store: coerced to the declared type, unknown keys
     * dropped, required fields enforced.
     *
     * @param retainedValues values the caller is not allowed to see and therefore
     *                       could not have submitted — carried through untouched so
     *                       a restricted viewer editing an asset never erases a
     *                       field that was hidden from them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(Long categoryId,
                                        Map<String, Object> submitted,
                                        Map<String, Object> retainedValues) {
        Map<String, Object> result = new LinkedHashMap<>(retainedValues == null ? Map.of() : retainedValues);
        Map<String, Object> input = submitted == null ? Map.of() : submitted;

        for (CustomFieldDefinition definition : definitions.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
            String fieldName = definition.getFieldName();
            if (retainedValues != null && retainedValues.containsKey(fieldName) && !input.containsKey(fieldName)) {
                continue;   // hidden from this viewer; keep what was already stored
            }

            Object raw = input.get(fieldName);
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                if (definition.isRequired()) {
                    throw new ApiExceptions.BadRequestException("\"" + fieldName + "\" is required.");
                }
                result.remove(fieldName);
                continue;
            }
            result.put(fieldName, coerce(definition, raw));
        }
        return result;
    }

    private Object coerce(CustomFieldDefinition definition, Object raw) {
        String fieldName = definition.getFieldName();
        String text = String.valueOf(raw);
        return switch (definition.getFieldType()) {
            case TEXT -> text;
            case NUMBER -> {
                try {
                    yield new java.math.BigDecimal(text);
                } catch (NumberFormatException ex) {
                    throw new ApiExceptions.BadRequestException("\"" + fieldName + "\" must be a number.");
                }
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(text).toString();
                } catch (DateTimeParseException ex) {
                    throw new ApiExceptions.BadRequestException("\"" + fieldName + "\" must be a date (YYYY-MM-DD).");
                }
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) yield b;
                if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) yield Boolean.parseBoolean(text);
                throw new ApiExceptions.BadRequestException("\"" + fieldName + "\" must be true or false.");
            }
            case ENUM -> {
                List<String> options = definition.getEnumOptions() == null
                        ? List.of() : Arrays.asList(definition.getEnumOptions());
                if (!options.contains(text)) {
                    throw new ApiExceptions.BadRequestException(
                            "\"" + fieldName + "\" must be one of: " + String.join(", ", options));
                }
                yield text;
            }
        };
    }
}
