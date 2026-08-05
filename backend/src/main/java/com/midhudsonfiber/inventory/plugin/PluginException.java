package com.midhudsonfiber.inventory.plugin;

/**
 * Something went wrong inside a plugin.
 *
 * <p>Nothing catches this except the orchestrator, which turns it into a FAILURE
 * row. That is the whole failure-isolation guarantee: a plugin cannot take down
 * the scheduler, the web application, another plugin, or — the one that matters
 * most — authentication.
 */
public class PluginException extends RuntimeException {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
