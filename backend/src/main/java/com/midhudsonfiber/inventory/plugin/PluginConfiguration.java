package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.web.ApiExceptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A plugin's stored configuration, read the way plugin code wants to read it.
 *
 * <p>Secrets are resolved here rather than being stored: a field marked as a
 * reference holds the <em>name</em> of an environment variable, and this looks
 * it up at the moment it is needed. The database never holds the value, which
 * is the same decision Phase 6 made for the LDAP bind password, applied
 * unchanged rather than reinvented for plugins.
 */
public class PluginConfiguration implements SyncPlugin.PluginConfig {

    private final Map<String, Object> values;
    private final SecretResolver secrets;

    public PluginConfiguration(Map<String, Object> values, SecretResolver secrets) {
        this.values = values == null ? Map.of() : values;
        this.secrets = secrets;
    }

    @Override
    public Map<String, Object> values() {
        return values;
    }

    @Override
    public String text(String key) {
        Object raw = values.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).trim();
    }

    @Override
    public Integer number(String key) {
        Object raw = values.get(key);
        if (raw instanceof Number number) return number.intValue();
        String text = text(key);
        try {
            return text == null ? null : Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean flag(String key) {
        Object raw = values.get(key);
        return raw instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(raw));
    }

    @Override
    public String secret(String key) {
        String reference = text(key);
        if (reference == null) return null;
        String value = secrets.resolve(reference);
        if (value == null) {
            // Said plainly, and naming the variable rather than the secret: the
            // usual cause is a plugin configured before the environment was.
            throw new ApiExceptions.BadRequestException(
                    "No value is set for the environment variable " + reference
                            + ", which this plugin's configuration names. Set it where the "
                            + "application's other secrets live, then try again.");
        }
        return value;
    }

    @Override
    public List<String> list(String key) {
        Object raw = values.get(key);
        if (raw instanceof List<?> items) {
            List<String> out = new ArrayList<>();
            items.forEach(item -> out.add(String.valueOf(item)));
            return out;
        }
        String text = text(key);
        return text == null ? List.of() : List.of(text.split("\\s*,\\s*"));
    }
}
