package com.midhudsonfiber.inventory.plugin;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Where a secret reference actually resolves from.
 *
 * <p>Spring's {@code Environment} rather than {@code System.getenv} directly, so
 * the same name can come from an environment variable in production and from
 * test properties in a test — without the plugin, or this class, knowing which.
 */
@Component
public class SecretResolver {

    private final Environment environment;

    public SecretResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String value = environment.getProperty(reference);
        if (value == null) value = System.getenv(reference);
        return value == null || value.isBlank() ? null : value;
    }

    /** Whether a reference has a value, for a settings screen that must not show one. */
    public boolean isSet(String reference) {
        return resolve(reference) != null;
    }
}
