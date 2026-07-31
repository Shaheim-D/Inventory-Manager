package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.Branding;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.BrandingService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read is public so the sign-in screen can already carry the organization's own
 * logo; writing requires {@code branding:manage}.
 */
@RestController
@RequestMapping("/api/branding")
public class BrandingController {

    private final BrandingService branding;

    public BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    public record BrandingRequest(String organizationName, String primaryColor, String secondaryColor) {}

    @GetMapping
    public Map<String, Object> get() {
        return toView(branding.current());
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        Branding record = branding.current();
        if (record.getLogoBytes() == null) {
            return ResponseEntity.notFound().build();
        }
        // Cached briefly and revalidated by the ?v= stamp the client appends, so a
        // re-upload shows up immediately without hammering the database per page load.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.getLogoContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("Content-Disposition", "inline; filename=\"" + record.getLogoFilename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(record.getLogoBytes());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.BRANDING_MANAGE + "')")
    public Map<String, Object> update(@RequestBody BrandingRequest request) {
        return toView(branding.updateSettings(
                request.organizationName(), request.primaryColor(), request.secondaryColor()));
    }

    @PostMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + PermissionKeys.BRANDING_MANAGE + "')")
    public Map<String, Object> uploadLogo(@RequestPart("file") MultipartFile file) {
        return toView(branding.uploadLogo(file));
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasAuthority('" + PermissionKeys.BRANDING_MANAGE + "')")
    public ResponseEntity<Void> removeLogo() {
        branding.removeLogo();
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toView(Branding record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("organizationName", record.getOrganizationName());
        view.put("primaryColor", record.getPrimaryColor());
        view.put("secondaryColor", record.getSecondaryColor());
        view.put("hasLogo", record.getLogoBytes() != null);
        view.put("logoFilename", record.getLogoFilename());
        view.put("logoUpdatedAt", record.getLogoUpdatedAt());
        return view;
    }
}
