package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 2: files held against an asset. */
class AttachmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long newAsset(Session admin) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class);
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        Long locationId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("att"), locationType)).getBody().get("id").asLong();

        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId, locationId, unique("router"), unique("SN")))
                .getBody().get("id").asLong();
    }

    @Test
    @DisplayName("a photo can be uploaded, listed, and downloaded back byte for byte")
    void uploadAndDownload() {
        Session admin = admin();
        Long assetId = newAsset(admin);
        byte[] bytes = "not really a photo, but the bytes must survive".getBytes(StandardCharsets.UTF_8);

        JsonNode uploaded = postMultipart(admin,
                "/api/assets/" + assetId + "/attachments?fileCategory=PHOTO",
                "front-panel.jpg", "image/jpeg", bytes).getBody();

        assertThat(uploaded.get("fileCategory").asText()).isEqualTo("PHOTO");
        assertThat(uploaded.get("originalFilename").asText()).isEqualTo("front-panel.jpg");
        assertThat(uploaded.get("uploadedBy").asText()).isEqualTo("admin");
        // The path on disk is an internal detail; publishing it invites someone
        // to try requesting it directly.
        assertThat(uploaded.has("filePath")).isFalse();

        assertThat(get(admin, "/api/assets/" + assetId + "/attachments").getBody()).hasSize(1);

        byte[] downloaded = getBytes(admin,
                "/api/assets/" + assetId + "/attachments/" + uploaded.get("id").asLong());
        assertThat(downloaded).isEqualTo(bytes);
    }

    @Test
    @DisplayName("an uploaded file is never served in a way a browser will render")
    void downloadsAreAlwaysAttachments() {
        Session admin = admin();
        Long assetId = newAsset(admin);

        // An uploaded page served inline under this origin would run as whoever
        // opened it, with their session. The content type it was uploaded as
        // must not decide how it comes back.
        JsonNode uploaded = postMultipart(admin,
                "/api/assets/" + assetId + "/attachments?fileCategory=MISCELLANEOUS",
                "evil.html", "text/html",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).getBody();

        var response = rawGet(admin,
                "/api/assets/" + assetId + "/attachments/" + uploaded.get("id").asLong());

        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/octet-stream");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment;");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("a filename cannot steer where the bytes are written")
    void filenameCannotEscapeTheDirectory() {
        Session admin = admin();
        Long assetId = newAsset(admin);

        JsonNode uploaded = postMultipart(admin,
                "/api/assets/" + assetId + "/attachments?fileCategory=MISCELLANEOUS",
                "../../../../etc/passwd", "text/plain",
                "harmless".getBytes(StandardCharsets.UTF_8)).getBody();

        // The name is kept as a label, stripped to its last segment, and the
        // path on disk is generated instead of derived from it.
        assertThat(uploaded.get("originalFilename").asText()).isEqualTo("passwd");
        String storedPath = jdbc.queryForObject(
                "SELECT file_path FROM attachment WHERE id = ?", String.class,
                uploaded.get("id").asLong());
        assertThat(storedPath).doesNotContain("..");
        assertThat(storedPath).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("an unknown file category is refused rather than stored")
    void unknownCategoryIsRejected() {
        Session admin = admin();
        Long assetId = newAsset(admin);

        // The column has a CHECK constraint; failing in the application means an
        // explanation instead of a constraint-violation stack trace.
        assertThat(postMultipart(admin,
                "/api/assets/" + assetId + "/attachments?fileCategory=SOMETHING_ELSE",
                "x.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an attachment id is not usable against another asset")
    void attachmentIsBoundToItsAsset() {
        Session admin = admin();
        Long assetA = newAsset(admin);
        Long assetB = newAsset(admin);

        Long attachmentId = postMultipart(admin,
                "/api/assets/" + assetA + "/attachments?fileCategory=INVOICE",
                "invoice.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8))
                .getBody().get("id").asLong();

        assertThat(rawGet(admin, "/api/assets/" + assetB + "/attachments/" + attachmentId)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("uploading and removing are recorded against the asset")
    void attachmentsAreAudited() {
        Session admin = admin();
        Long assetId = newAsset(admin);

        Long attachmentId = postMultipart(admin,
                "/api/assets/" + assetId + "/attachments?fileCategory=MANUAL",
                "handbook.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8))
                .getBody().get("id").asLong();

        assertThat(get(admin, "/api/assets/" + assetId + "/audit").getBody().toString())
                .contains("handbook.pdf");

        assertThat(delete(admin, "/api/assets/" + assetId + "/attachments/" + attachmentId)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(admin, "/api/assets/" + assetId + "/attachments").getBody()).isEmpty();
    }

    @Test
    @DisplayName("uploading needs attachment:upload, reading needs only asset:read")
    void uploadIsPermissionGated() {
        Session admin = admin();
        Long assetId = newAsset(admin);

        String username = unique("viewer");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ViewerPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        Session viewer = signIn(username, "ViewerPass123");

        assertThat(postMultipart(viewer,
                "/api/assets/" + assetId + "/attachments?fileCategory=PHOTO",
                "x.jpg", "image/jpeg", "x".getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Knowing what is attached is part of knowing what the asset is.
        assertThat(get(viewer, "/api/assets/" + assetId + "/attachments").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
