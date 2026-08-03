package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.ImportBatch;
import com.midhudsonfiber.inventory.domain.ImportBatchRow;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.ImportBatchRepository;
import com.midhudsonfiber.inventory.repo.ImportBatchRowRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.ImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk import of assets from a spreadsheet.
 *
 * <p>Upload checks the file and creates nothing. The preview says exactly which
 * rows will import and why the others will not. Committing is a separate,
 * deliberate action — the point of the feature is that nothing irreversible
 * happens before someone has looked.
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;
    private final ImportBatchRepository batches;
    private final ImportBatchRowRepository rows;
    private final AppUserRepository users;

    public ImportController(ImportService imports, ImportBatchRepository batches,
                            ImportBatchRowRepository rows, AppUserRepository users) {
        this.imports = imports;
        this.batches = batches;
        this.rows = rows;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.IMPORT_RUN + "')")
    public List<Map<String, Object>> list() {
        return batches.findTop50ByOrderByIdDesc().stream().map(this::toView).toList();
    }

    /**
     * A starting file with the right column names, so the first attempt is not
     * a guess about what the importer accepts.
     */
    @GetMapping("/template")
    @PreAuthorize("hasAuthority('" + PermissionKeys.IMPORT_RUN + "')")
    public ResponseEntity<byte[]> template() {
        String header = String.join(",", ImportService.COLUMNS);
        // One filled example row: the columns alone do not convey that a date
        // wants YYYY-MM-DD or that MACs are separated by semicolons.
        String example = String.join(",", List.of(
                "Router", "Kingston Warehouse", "Edge Router 1", "Cisco", "ISR4331",
                "FTX1234ABCD", "IM-0001", "edge-rtr-01", "10.0.0.1",
                "\"00:11:22:33:44:55;00:11:22:33:44:56\"", "Edge Router",
                "17.3.5", "", "2026-03-01", "2450.00", "Ingram Micro", "INV-88213",
                "2026-03-01", "36", "New", "1", "Replaced the old 2921"));

        byte[] body = (header + "\n" + example + "\n").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"inventory-import-template.csv\"")
                .body(body);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + PermissionKeys.IMPORT_RUN + "')")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
        return toView(imports.upload(file));
    }

    /** The batch plus every row, which is what the preview screen renders. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.IMPORT_RUN + "')")
    public Map<String, Object> detail(@PathVariable Long id) {
        ImportBatch batch = batches.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Import not found"));

        Map<String, Object> view = toView(batch);
        view.put("rows", rows.findByBatchIdOrderByRowNumberAsc(id).stream()
                .map(ImportController::toRowView)
                .toList());
        return view;
    }

    @PostMapping("/{id}/commit")
    @PreAuthorize("hasAuthority('" + PermissionKeys.IMPORT_RUN + "')")
    public Map<String, Object> commit(@PathVariable Long id) {
        return toView(imports.commit(id));
    }

    private Map<String, Object> toView(ImportBatch batch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", batch.getId());
        view.put("filename", batch.getFilename());
        view.put("status", batch.getStatus().name());
        view.put("rowCount", batch.getRowCount());
        view.put("successCount", batch.getSuccessCount());
        view.put("failureCount", batch.getFailureCount());
        view.put("importedAt", batch.getImportedAt());
        view.put("importedBy", batch.getImportedBy() == null ? "system"
                : users.findById(batch.getImportedBy())
                    .map(com.midhudsonfiber.inventory.domain.AppUser::getUsername)
                    .orElse("user #" + batch.getImportedBy()));
        return view;
    }

    private static Map<String, Object> toRowView(ImportBatchRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rowNumber", row.getRowNumber());
        view.put("status", row.getStatus().name());
        view.put("errorMessage", row.getErrorMessage());
        view.put("createdAssetId", row.getCreatedAssetId());
        view.put("data", row.getRawData());
        return view;
    }
}
