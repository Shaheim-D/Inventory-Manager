package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import com.midhudsonfiber.inventory.web.dto.AssetRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Bulk import: upload, check, look at what will happen, then commit.
 *
 * <p>Nothing is created until someone has seen the preview and agreed to it,
 * and the preview is built from stored parsed rows rather than from a second
 * reading of the file — otherwise the thing approved and the thing applied
 * could differ.
 *
 * <p>Rows are validated independently and a bad one never stops the others.
 * A spreadsheet of six hundred assets with four typos should import five
 * hundred and ninety-six and tell you about the four, not refuse the lot.
 */
@Service
public class ImportService {

    /** Enough for a real inventory load, low enough that one upload cannot exhaust memory. */
    static final int MAX_ROWS = 10_000;

    /** Column names the importer understands, in the order the template offers them. */
    public static final List<String> COLUMNS = List.of(
            "category", "location", "name", "manufacturer", "model", "serial_number",
            "asset_tag", "hostname", "management_ip", "mac_addresses", "device_role",
            "firmware_version", "software_version", "purchase_date", "purchase_price",
            "vendor", "invoice_number", "warranty_start", "warranty_term_months",
            "condition", "quantity", "notes");

    private final ImportBatchRepository batches;
    private final ImportBatchRowRepository rows;
    private final AssetCategoryRepository categories;
    private final LocationRepository locations;
    private final AssetService assets;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public ImportService(ImportBatchRepository batches, ImportBatchRowRepository rows,
                         AssetCategoryRepository categories, LocationRepository locations,
                         AssetService assets, AuditService audit, CurrentUser currentUser) {
        this.batches = batches;
        this.rows = rows;
        this.categories = categories;
        this.locations = locations;
        this.assets = assets;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * Reads the upload, checks every row, and stores the result. Creates nothing.
     */
    @Transactional
    public ImportBatch upload(MultipartFile file) {
        ImportBatch batch = new ImportBatch();
        batch.setFilename(displayName(file.getOriginalFilename()));
        batch.setImportedBy(currentUser.idOrNull());
        batch.setStatus(ImportBatch.Status.PENDING);
        ImportBatch saved = batches.save(batch);

        CsvReader.Sheet sheet;
        try (var stream = file.getInputStream()) {
            sheet = CsvReader.read(stream, MAX_ROWS);
        } catch (ApiExceptions.BadRequestException e) {
            // A file that could not be parsed at all is a batch that failed, not
            // an exception that leaves no trace of the attempt.
            saved.setStatus(ImportBatch.Status.FAILED);
            batches.save(saved);
            throw e;
        } catch (Exception e) {
            saved.setStatus(ImportBatch.Status.FAILED);
            batches.save(saved);
            throw new ApiExceptions.BadRequestException("Could not read that file: " + e.getMessage());
        }

        List<String> unknown = sheet.headers().stream()
                .filter(header -> !header.isBlank())
                .filter(header -> !COLUMNS.contains(normalise(header)))
                .toList();
        if (!unknown.isEmpty()) {
            saved.setStatus(ImportBatch.Status.FAILED);
            batches.save(saved);
            throw new ApiExceptions.BadRequestException(
                    "Unrecognised column(s): " + String.join(", ", unknown)
                            + ". Expected any of: " + String.join(", ", COLUMNS));
        }

        // Resolved once rather than per row: a thousand-row file otherwise runs a
        // thousand lookups for the same handful of names.
        Map<String, AssetCategory> categoryByName = new HashMap<>();
        categories.findAll().forEach(c -> categoryByName.put(c.getName().toLowerCase(), c));
        Map<String, Location> locationByName = new HashMap<>();
        locations.findAll().forEach(l -> locationByName.put(l.getName().toLowerCase(), l));

        // Serial numbers already in this file, so a file that repeats one is
        // caught here rather than by the database halfway through committing.
        Set<String> serialsInFile = new HashSet<>();

        int valid = 0;
        int invalid = 0;
        for (CsvReader.Row row : sheet.rows()) {
            Map<String, String> values = normaliseKeys(row.values());
            ImportBatchRow stored = new ImportBatchRow();
            stored.setBatch(saved);
            stored.setRowNumber(row.lineNumber());
            stored.setRawData(new LinkedHashMap<>(values));

            String problem = check(values, categoryByName, locationByName, serialsInFile);
            if (problem == null) {
                stored.setStatus(ImportBatchRow.Status.VALID);
                valid++;
            } else {
                stored.setStatus(ImportBatchRow.Status.INVALID);
                stored.setErrorMessage(problem);
                invalid++;
            }
            rows.save(stored);
        }

        saved.setRowCount(sheet.rows().size());
        saved.setSuccessCount(valid);
        saved.setFailureCount(invalid);
        // VALIDATED even when every row failed: the file was read and the
        // preview is meaningful. FAILED is reserved for "could not read it".
        saved.setStatus(ImportBatch.Status.VALIDATED);
        return batches.save(saved);
    }

    /**
     * Creates an asset for each valid row. Invalid rows are left alone and stay
     * visible on the batch afterwards as the record of what was skipped.
     */
    @Transactional
    public ImportBatch commit(Long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Import not found"));

        if (batch.getStatus() != ImportBatch.Status.VALIDATED) {
            throw new ApiExceptions.BadRequestException(
                    batch.getStatus() == ImportBatch.Status.COMMITTED
                            ? "That import has already been committed."
                            : "That import cannot be committed in its current state.");
        }

        Map<String, AssetCategory> categoryByName = new HashMap<>();
        categories.findAll().forEach(c -> categoryByName.put(c.getName().toLowerCase(), c));
        Map<String, Location> locationByName = new HashMap<>();
        locations.findAll().forEach(l -> locationByName.put(l.getName().toLowerCase(), l));

        int created = 0;
        int failed = batch.getFailureCount();

        for (ImportBatchRow row : rows.findByBatchIdAndStatusOrderByRowNumberAsc(
                batchId, ImportBatchRow.Status.VALID)) {
            Map<String, String> values = new LinkedHashMap<>();
            row.getRawData().forEach((k, v) -> values.put(k, v == null ? "" : String.valueOf(v)));

            try {
                Asset asset = assets.create(toRequest(values, categoryByName, locationByName));
                row.setStatus(ImportBatchRow.Status.IMPORTED);
                row.setCreatedAssetId(asset.getId());
                created++;
            } catch (Exception e) {
                // Something that passed the checks can still be rejected here --
                // a duplicate serial against a row created earlier in this same
                // commit, for instance. The row records why and the rest carry on.
                row.setStatus(ImportBatchRow.Status.INVALID);
                row.setErrorMessage(rootMessage(e));
                failed++;
            }
            rows.save(row);
        }

        batch.setSuccessCount(created);
        batch.setFailureCount(failed);
        batch.setStatus(ImportBatch.Status.COMMITTED);
        ImportBatch saved = batches.save(batch);

        audit.recordCreate("IMPORT_BATCH", batch.getId(),
                "%s: %d imported, %d skipped".formatted(batch.getFilename(), created, failed));
        return saved;
    }

    /** Checks a row without creating anything. Returns null when it is fine. */
    private String check(Map<String, String> values,
                         Map<String, AssetCategory> categoryByName,
                         Map<String, Location> locationByName,
                         Set<String> serialsInFile) {
        String categoryName = values.getOrDefault("category", "");
        if (categoryName.isBlank()) return "Category is required.";
        AssetCategory category = categoryByName.get(categoryName.toLowerCase());
        if (category == null) return "No category named \"" + categoryName + "\".";

        String locationName = values.getOrDefault("location", "");
        if (locationName.isBlank()) return "Location is required.";
        if (!locationByName.containsKey(locationName.toLowerCase())) {
            return "No location named \"" + locationName + "\".";
        }

        if (values.getOrDefault("name", "").isBlank()
                && values.getOrDefault("serial_number", "").isBlank()
                && values.getOrDefault("asset_tag", "").isBlank()) {
            return "Needs at least a name, serial number, or asset tag to be identifiable.";
        }

        String serial = values.getOrDefault("serial_number", "");
        if (!serial.isBlank() && !serialsInFile.add(serial.toLowerCase())) {
            return "Serial number \"" + serial + "\" appears more than once in this file.";
        }

        // A bulk category counts stock, so a quantity that is not a positive
        // number is a data problem rather than something to silently default.
        String quantity = values.getOrDefault("quantity", "");
        if (!quantity.isBlank()) {
            try {
                if (Integer.parseInt(quantity) < 1) return "Quantity must be at least 1.";
            } catch (NumberFormatException e) {
                return "Quantity \"" + quantity + "\" is not a whole number.";
            }
        } else if (!category.isSerialized()) {
            return "Quantity is required for %s, which is counted in bulk.".formatted(category.getName());
        }

        String price = values.getOrDefault("purchase_price", "");
        if (!price.isBlank() && parsePrice(price) == null) {
            return "Purchase price \"" + price + "\" is not a number.";
        }

        for (String dateColumn : List.of("purchase_date", "warranty_start")) {
            String value = values.getOrDefault(dateColumn, "");
            if (!value.isBlank() && parseDate(value) == null) {
                return "%s \"%s\" is not a date. Use YYYY-MM-DD.".formatted(dateColumn, value);
            }
        }

        String term = values.getOrDefault("warranty_term_months", "");
        if (!term.isBlank()) {
            try {
                if (Integer.parseInt(term) < 1) return "Warranty term must be at least 1 month.";
            } catch (NumberFormatException e) {
                return "Warranty term \"" + term + "\" is not a whole number of months.";
            }
        }
        return null;
    }

    private AssetRequest toRequest(Map<String, String> values,
                                   Map<String, AssetCategory> categoryByName,
                                   Map<String, Location> locationByName) {
        AssetCategory category = categoryByName.get(values.get("category").toLowerCase());
        Location location = locationByName.get(values.get("location").toLowerCase());

        String macs = values.getOrDefault("mac_addresses", "");
        String[] macAddresses = macs.isBlank() ? null
                : Arrays.stream(macs.split("[;,]")).map(String::trim).filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

        return new AssetRequest(
                category.getId(),
                location.getId(),
                null,                                   // lifecycle: the category's default
                blankToNull(values.get("name")),
                blankToNull(values.get("manufacturer")),
                blankToNull(values.get("model")),
                blankToNull(values.get("serial_number")),
                blankToNull(values.get("asset_tag")),
                macAddresses,
                blankToNull(values.get("management_ip")),
                blankToNull(values.get("hostname")),
                blankToNull(values.get("firmware_version")),
                blankToNull(values.get("software_version")),
                blankToNull(values.get("device_role")),
                parseDate(values.getOrDefault("purchase_date", "")),
                parsePrice(values.getOrDefault("purchase_price", "")),
                blankToNull(values.get("vendor")),
                null,                                   // purchase link: not a column
                blankToNull(values.get("invoice_number")),
                parseDate(values.getOrDefault("warranty_start", "")),
                parseInt(values.getOrDefault("warranty_term_months", "")),
                null,                                   // license information
                blankToNull(values.get("condition")),
                null,                                   // status
                null, null,                             // customer name / address
                blankToNull(values.get("notes")),
                Asset.AssigneeType.NONE,
                null, null,
                parseInt(values.getOrDefault("quantity", "")),
                Set.of(),
                Map.of());
    }

    /** Column names are matched case- and separator-insensitively. */
    private static Map<String, String> normaliseKeys(Map<String, String> values) {
        Map<String, String> normalised = new LinkedHashMap<>();
        values.forEach((key, value) -> normalised.put(normalise(key), value));
        return normalised;
    }

    /** "Serial Number", "serial number", and "serial_number" are the same column. */
    static String normalise(String header) {
        return header.trim().toLowerCase().replaceAll("[\\s-]+", "_");
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parsePrice(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // Tolerates what a spreadsheet exports: "$1,234.56".
            return new BigDecimal(value.replaceAll("[$,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The innermost message, since the outer ones are usually framework noise. */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String displayName(String filename) {
        if (filename == null || filename.isBlank()) return "upload.csv";
        String name = filename.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
