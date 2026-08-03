package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.UUID;

/**
 * Integration tests run against a real PostgreSQL instance with the full V1-V10
 * migration chain applied. That is deliberate rather than convenient: this
 * schema carries real behavior in triggers and CHECK constraints, and an
 * in-memory substitute would quietly test none of it.
 *
 * <p>Tests share the database and stay independent by naming everything they
 * create uniquely, rather than cleaning between runs — cleaning would also drop
 * the Spring Session table the application creates at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    /** A signed-in session: the cookies to replay plus the CSRF token to echo. */
    protected record Session(List<String> cookies, String csrfToken) {}

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected Session signIn(String username, String password) {
        // An unauthenticated GET issues the CSRF cookie the login POST must echo.
        ResponseEntity<String> seed = rest.getForEntity("/api/branding", String.class);
        List<String> seedCookies = seed.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        String csrf = csrfFrom(seedCookies);

        ResponseEntity<JsonNode> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                        headers(seedCookies, csrf)),
                JsonNode.class);

        List<String> sessionCookies = response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("Sign-in failed for " + username + ": " + response.getStatusCode());
        }
        return new Session(merge(seedCookies, sessionCookies), csrfFrom(merge(seedCookies, sessionCookies)));
    }

    protected HttpStatusCode signInStatus(String username, String password) {
        ResponseEntity<String> seed = rest.getForEntity("/api/branding", String.class);
        List<String> seedCookies = seed.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                        headers(seedCookies, csrfFrom(seedCookies))),
                String.class).getStatusCode();
    }

    protected ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(session.cookies(), session.csrfToken())), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(Session session, String path, String json) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(json, headers(session.cookies(), session.csrfToken())), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> delete(Session session, String path) {
        return rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(headers(session.cookies(), session.csrfToken())), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> put(Session session, String path, String json) {
        return rest.exchange(path, HttpMethod.PUT,
                new HttpEntity<>(json, headers(session.cookies(), session.csrfToken())), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> postMultipart(Session session, String path,
                                                     String filename, String contentType, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });

        HttpHeaders headers = headers(session.cookies(), session.csrfToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // Pin the part's declared type so the upload validator sees what a browser would send.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> typedBody = new LinkedMultiValueMap<>();
        typedBody.add("file", new HttpEntity<>(body.getFirst("file"), partHeaders));

        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(typedBody, headers), JsonNode.class);
    }

    /** The raw response, for assertions about status and headers rather than body. */
    protected ResponseEntity<byte[]> rawGet(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(session.cookies(), session.csrfToken())), byte[].class);
    }

    /** The bytes a download actually produced, for comparing against what went up. */
    protected byte[] getBytes(Session session, String path) {
        return rawGet(session, path).getBody();
    }

    private static HttpHeaders headers(List<String> cookies, String csrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!cookies.isEmpty()) {
            headers.put(HttpHeaders.COOKIE, cookies.stream().map(c -> c.split(";", 2)[0]).toList());
        }
        if (csrf != null) headers.set("X-XSRF-TOKEN", csrf);
        return headers;
    }

    private static List<String> merge(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(second.stream(), first.stream()).toList();
    }

    private static String csrfFrom(List<String> cookies) {
        for (String cookie : cookies) {
            if (cookie.startsWith("XSRF-TOKEN=")) {
                return cookie.substring("XSRF-TOKEN=".length()).split(";", 2)[0];
            }
        }
        return null;
    }
}
