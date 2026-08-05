package com.midhudsonfiber.inventory.report;

/**
 * One thing a report can have a column of.
 *
 * @param key   what the report asks for and what the assembled row is looked up
 *              by. For a core field it is the view map's key ({@code purchasePrice});
 *              for a custom field it is {@code custom:<definition id>}, because a
 *              custom field's name is only unique inside its category.
 * @param label what to call it on screen and in the export header
 * @param group how the picker files it, so a list of eighty fields is navigable
 */
public record ReportField(String key, String label, String group) {

    /** The prefix that marks a custom-field key. */
    public static final String CUSTOM_PREFIX = "custom:";

    public static ReportField custom(Long definitionId, String label, String categoryName) {
        return new ReportField(CUSTOM_PREFIX + definitionId, label, categoryName);
    }

    public boolean isCustom() {
        return key.startsWith(CUSTOM_PREFIX);
    }

    public Long customFieldId() {
        return isCustom() ? Long.valueOf(key.substring(CUSTOM_PREFIX.length())) : null;
    }
}
