package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.config.AppProperties;
import com.midhudsonfiber.inventory.domain.Branding;
import com.midhudsonfiber.inventory.repo.BrandingRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Branding is uploaded into a running instance rather than committed as source
 * assets, so a logo and palette can be supplied at any point without a rebuild
 * (MOP §1.5). The upload path is deliberately narrow: a short allow-list of
 * image types, a size cap, and a signature check, because this is the one place
 * the application accepts a file that is later served back to every browser.
 */
@Service
public class BrandingService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/svg+xml", "image/webp");

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final BrandingRepository branding;
    private final AppProperties props;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public BrandingService(BrandingRepository branding, AppProperties props,
                           AuditService audit, CurrentUser currentUser) {
        this.branding = branding;
        this.props = props;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Branding current() {
        return branding.findById(Branding.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Branding row missing; V10 did not run"));
    }

    @Transactional
    public Branding updateSettings(String organizationName, String primaryColor, String secondaryColor) {
        Branding record = current();
        validateColor(primaryColor, "Primary color");
        validateColor(secondaryColor, "Secondary color");

        List<AuditService.FieldChange> changes = List.of(
                AuditService.FieldChange.of("organization_name", record.getOrganizationName(), organizationName),
                AuditService.FieldChange.of("primary_color", record.getPrimaryColor(), primaryColor),
                AuditService.FieldChange.of("secondary_color", record.getSecondaryColor(), secondaryColor));

        record.setOrganizationName(blankToNull(organizationName));
        record.setPrimaryColor(blankToNull(primaryColor));
        record.setSecondaryColor(blankToNull(secondaryColor));
        record.setUpdatedBy(currentUser.idOrNull());

        Branding saved = branding.save(record);
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, (long) Branding.SINGLETON_ID, changes);
        return saved;
    }

    @Transactional
    public Branding uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiExceptions.BadRequestException("No file was uploaded.");
        }
        if (file.getSize() > props.getBranding().getMaxLogoBytes()) {
            throw new ApiExceptions.BadRequestException(
                    "Logo must be smaller than " + (props.getBranding().getMaxLogoBytes() / 1024) + " KB.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiExceptions.BadRequestException("Logo must be a PNG, JPEG, SVG, or WebP image.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new ApiExceptions.BadRequestException("The uploaded file could not be read.");
        }
        if (!looksLikeDeclaredType(bytes, contentType)) {
            throw new ApiExceptions.BadRequestException(
                    "The uploaded file's contents do not match its declared image type.");
        }

        Branding record = current();
        record.setLogoBytes(bytes);
        record.setLogoContentType(contentType);
        record.setLogoFilename(sanitizeFilename(file.getOriginalFilename()));
        record.setLogoUpdatedAt(Instant.now());
        record.setUpdatedBy(currentUser.idOrNull());

        Branding saved = branding.save(record);
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, (long) Branding.SINGLETON_ID,
                List.of(AuditService.FieldChange.of("logo", "(previous)", saved.getLogoFilename())));
        return saved;
    }

    @Transactional
    public void removeLogo() {
        Branding record = current();
        record.setLogoBytes(null);
        record.setLogoContentType(null);
        record.setLogoFilename(null);
        record.setLogoUpdatedAt(null);
        record.setUpdatedBy(currentUser.idOrNull());
        branding.save(record);
        audit.recordFieldChanges(AuditService.ENTITY_BRANDING, (long) Branding.SINGLETON_ID,
                List.of(AuditService.FieldChange.of("logo", "(previous)", null)));
    }

    /**
     * A declared content type is just a claim by the uploader, so the bytes are
     * checked against it. SVG is markup rather than a binary format, so it is
     * additionally rejected when it carries script or event handlers — the logo is
     * served to every browser that loads the app, including the login page.
     */
    private static boolean looksLikeDeclaredType(byte[] bytes, String contentType) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(bytes, new int[]{0xFF, 0xD8, 0xFF});
            case "image/webp" -> startsWith(bytes, new int[]{0x52, 0x49, 0x46, 0x46})
                    && bytes.length > 12
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            case "image/svg+xml" -> isSafeSvg(bytes);
            default -> false;
        };
    }

    private static boolean isSafeSvg(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
        if (!text.contains("<svg")) return false;
        return !text.contains("<script")
                && !text.contains("javascript:")
                && !text.contains("<foreignobject")
                && !text.matches("(?s).*\\son\\w+\\s*=.*");
    }

    private static boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "logo";
        String base = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private static void validateColor(String color, String label) {
        if (color == null || color.isBlank()) return;
        if (!HEX_COLOR.matcher(color).matches()) {
            throw new ApiExceptions.BadRequestException(label + " must be a hex value like #1B34C8.");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
