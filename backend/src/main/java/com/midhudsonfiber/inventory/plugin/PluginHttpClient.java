package com.midhudsonfiber.inventory.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one place a plugin reaches the outside world.
 *
 * <p>Every upstream call goes through here so timeouts and redirect handling are
 * decided once rather than per plugin, and so a test can put a stub in front of
 * a whole integration without a live Zabbix to talk to. That seam is the reason
 * the plugins are testable at all — a sync that can only be exercised against a
 * real monitoring server is a sync nobody exercises.
 */
@Component
public class PluginHttpClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            // Short on purpose: a plugin waiting on a dead host must fail and be
            // logged, not hold a scheduler thread until somebody notices.
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public JsonNode postJson(String url, Map<String, Object> body, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            headers.forEach(request::header);
            return send(request.build());
        } catch (IOException e) {
            throw new PluginException("Could not reach " + url + ": " + e.getMessage(), e);
        }
    }

    public JsonNode getJson(String url, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PluginException("Upstream answered " + response.statusCode()
                        + ": " + summarise(response.body()));
            }
            return response.body() == null || response.body().isBlank()
                    ? JSON.createObjectNode() : JSON.readTree(response.body());
        } catch (IOException e) {
            throw new PluginException("Could not reach " + request.uri() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("Interrupted while waiting for " + request.uri(), e);
        }
    }

    /** Enough of the body to diagnose, not enough to fill a log with HTML. */
    private static String summarise(String body) {
        if (body == null) return "no body";
        String trimmed = body.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }
}
