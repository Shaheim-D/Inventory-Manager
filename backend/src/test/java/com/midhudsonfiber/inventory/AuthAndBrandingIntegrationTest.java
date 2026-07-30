package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAndBrandingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "TestPassword123";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("five failed sign-ins lock the account for the configured window")
    void lockoutAfterFiveFailures() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        String username = createUser(admin, "Customer Service");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(signInStatus(username, "wrong-password"))
                    .as("attempt %d should still be a plain rejection", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The fifth failure is what trips the lock.
        assertThat(signInStatus(username, "wrong-password")).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(signInStatus(username, PASSWORD))
                .as("the correct password is refused while the account is locked")
                .isEqualTo(HttpStatus.LOCKED);

        Boolean locked = jdbc.queryForObject(
                "SELECT locked_until > now() FROM app_user WHERE username = ?", Boolean.class, username);
        assertThat(locked).isTrue();
    }

    @Test
    @DisplayName("an admin-issued account must change its password at first sign-in")
    void adminIssuedPasswordsAreTemporary() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        String username = createUser(admin, "Customer Service");

        JsonNode me = get(signIn(username, PASSWORD), "/api/auth/me").getBody();
        assertThat(me.get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a DENY override beats the permission the user's role grants")
    void denyOverrideWins() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        String username = createUser(admin, "Customer Service");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        Long assetRead = jdbc.queryForObject(
                "SELECT id FROM permission WHERE permission_key = 'asset:read'", Long.class);

        assertThat(get(signIn(username, PASSWORD), "/api/auth/me").getBody().get("permissions").toString())
                .contains("asset:read");

        post(admin, "/api/admin/users/" + userId + "/overrides",
                "{\"permissionId\":%d,\"effect\":\"DENY\"}".formatted(assetRead));

        assertThat(get(signIn(username, PASSWORD), "/api/auth/me").getBody().get("permissions").toString())
                .doesNotContain("asset:read");
    }

    @Test
    @DisplayName("branding: an administrator can upload a logo, and it is served without a session")
    void brandingLogoUploadAndPublicRead() {
        Session admin = signIn("admin", "BootstrapAdmin123");

        ResponseEntity<JsonNode> upload = postMultipart(admin, "/api/branding/logo",
                "mid-hudson-fiber.png", "image/png", onePixelPng());
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload.getBody().get("hasLogo").asBoolean()).isTrue();

        // The sign-in screen fetches this before anyone has authenticated.
        ResponseEntity<byte[]> logo = rest.getForEntity("/api/branding/logo", byte[].class);
        assertThat(logo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logo.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("branding: a file whose bytes contradict its declared type is rejected")
    void brandingRejectsMislabelledUpload() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        ResponseEntity<JsonNode> upload = postMultipart(admin, "/api/branding/logo",
                "not-really.png", "image/png", "<html>gotcha</html>".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("branding: an SVG carrying script is rejected")
    void brandingRejectsScriptedSvg() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(postMultipart(admin, "/api/branding/logo", "x.svg", "image/svg+xml", svg).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("branding: a viewer without branding:manage cannot change it")
    void brandingRequiresItsOwnPermission() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        String username = createUser(admin, "Customer Service");

        ResponseEntity<JsonNode> attempt = put(signIn(username, PASSWORD), "/api/branding",
                "{\"organizationName\":\"Not Allowed\"}");
        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("branding: the palette round-trips and rejects a malformed color")
    void brandingPalette() {
        Session admin = signIn("admin", "BootstrapAdmin123");

        JsonNode saved = put(admin, "/api/branding", """
                {"organizationName":"Mid-Hudson Fiber","primaryColor":"#1B34C8","secondaryColor":"#C210C2"}
                """).getBody();
        assertThat(saved.get("primaryColor").asText()).isEqualTo("#1B34C8");

        assertThat(put(admin, "/api/branding", "{\"primaryColor\":\"blue-ish\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String createUser(Session admin, String roleName) {
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = ?", Long.class, roleName);
        String username = unique("auth." + roleName.toLowerCase().replace(' ', '.'));
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","authProvider":"LOCAL","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));
        return username;
    }

    /** A minimal but genuinely valid PNG, so the signature check has something real to accept. */
    private static byte[] onePixelPng() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(intBytes(1));
        ihdr.writeBytes(intBytes(1));
        ihdr.writeBytes(new byte[]{8, 2, 0, 0, 0});
        writeChunk(out, "IHDR", ihdr.toByteArray());
        writeChunk(out, "IDAT", deflate(new byte[]{0, 0x1B, 0x34, (byte) 0xC8}));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        out.writeBytes(intBytes(data.length));
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(intBytes((int) crc.getValue()));
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] buffer = new byte[64];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] result = new byte[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }
}
