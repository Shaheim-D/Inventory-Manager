package com.midhudsonfiber.inventory.plugin;

import java.util.List;

/**
 * One field in a plugin's configuration form.
 *
 * <p>The admin screen renders whatever a plugin declares here — the platform
 * hardcodes no per-plugin-type form anywhere, which is the acceptance criterion
 * the whole framework exists to meet: a new integration is a new class and a
 * row in {@code plugin}, not a change to core code.
 *
 * @param secretRef true when this field names an environment variable rather
 *                  than carrying a value. Secrets never enter the database, so
 *                  the form asks for the variable's name and the resolver reads
 *                  it at connection time.
 */
public record ConfigField(String name,
                          String label,
                          Type type,
                          boolean required,
                          boolean secretRef,
                          String help,
                          List<String> options) {

    public enum Type { TEXT, NUMBER, BOOLEAN, SELECT, MULTI_SELECT }

    public static ConfigField text(String name, String label, boolean required, String help) {
        return new ConfigField(name, label, Type.TEXT, required, false, help, List.of());
    }

    public static ConfigField secret(String name, String label, String help) {
        return new ConfigField(name, label, Type.TEXT, true, true, help, List.of());
    }

    public static ConfigField number(String name, String label, boolean required, String help) {
        return new ConfigField(name, label, Type.NUMBER, required, false, help, List.of());
    }

    public static ConfigField flag(String name, String label, String help) {
        return new ConfigField(name, label, Type.BOOLEAN, false, false, help, List.of());
    }

    public static ConfigField multi(String name, String label, List<String> options, String help) {
        return new ConfigField(name, label, Type.MULTI_SELECT, false, false, help, options);
    }
}
