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

    /**
     * Marks a column as a category custom field rather than a core one:
     * {@code custom:VIN}. A prefix rather than "any unrecognised column is a
     * custom field", because an unknown column has to keep failing the file --
     * silently ignoring a misspelled core column is how an import looks
     * successful and loses data.
     */
    public static final String CUSTOM_PREFIX = "custom:";

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
    private final AssetRepository assets;
    private final ImportRowCommitter rowCommitter;
    private final CustomFieldDefinitionRepository customFields;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public ImportService(ImportBatchRepository batches, ImportBatchRowRepository rows,
                         AssetCategoryRepository categories, LocationRepository locations,
                         AssetRepository assets,
                         ImportRowCommitter rowCommitter,
                         CustomFieldDefinitionRepository customFields,
                         AuditService audit, CurrentUser currentUser) {
        this.batches = batches;
        this.rows = rows;
        this.categories = categories;
        this.locations = locations;
        this.assets = assets;
        this.rowCommitter = rowCommitter;
        this.customFields = customFields;
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
                .filter(header -> !isCustomColumn(header))
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
        // ...and the ones already belonging to a live asset. Asked once for the
        // whole file: without this a serial that collides with existing stock
        // looks fine in the preview and fails at commit with a constraint name.
        Set<String> serialsInUse = serialsAlreadyInUse(sheet.rows());
        // Asset tags get the same treatment: uq_asset_tag makes them unique, so
        // a collision has to be visible in the preview rather than at commit.
        Set<String> tagsInFile = new HashSet<>();
        Set<String> tagsInUse = tagsAlreadyInUse(sheet.rows());

        int valid = 0;
        int invalid = 0;
        for (CsvReader.Row row : sheet.rows()) {
            Map<String, String> values = normaliseKeys(row.values());
            ImportBatchRow stored = new ImportBatchRow();
            stored.setBatch(saved);
            stored.setRowNumber(row.lineNumber());
            stored.setRawData(new LinkedHashMap<>(values));

            String problem = check(values, categoryByName, locationByName,
                    serialsInFile, serialsInUse, tagsInFile, tagsInUse);
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
     * Imports every row currently marked valid. Rows that are already imported
     * are left alone, so this is safe to call again after re-checking.
     */
    @Transactional
    public ImportBatch commitAll(Long batchId) {
        ImportBatch batch = batch(batchId);
        List<ImportBatchRow> pending =
                rows.findByBatchIdAndStatusOrderByRowNumberAsc(batchId, ImportBatchRow.Status.VALID);
        if (pending.isEmpty()) {
            throw new ApiExceptions.BadRequestException("There is nothing left to import in this file.");
        }

        Lookups lookups = lookups();
        for (ImportBatchRow row : pending) {
            importOne(row, lookups);
        }
        return recount(batch);
    }

    /**
     * Imports one row. The preview is a list someone is reading, and a reader
     * who spots the three rows they actually wanted should be able to take just
     * those without accepting the rest of the file.
     */
    @Transactional
    public ImportBatch commitRow(Long batchId, int rowNumber) {
        ImportBatch batch = batch(batchId);
        ImportBatchRow row = rows.findByBatchIdOrderByRowNumberAsc(batchId).stream()
                .filter(r -> r.getRowNumber() == rowNumber)
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such row in this file"));

        if (row.getStatus() == ImportBatchRow.Status.IMPORTED) {
            throw new ApiExceptions.BadRequestException("That row has already been imported.");
        }
        if (row.getStatus() == ImportBatchRow.Status.INVALID) {
            throw new ApiExceptions.BadRequestException(
                    "That row cannot be imported: " + row.getErrorMessage());
        }

        importOne(row, lookups());
        return recount(batch);
    }

    /**
     * Re-runs validation over the rows that have not been imported.
     *
     * <p>This exists because of how the failure actually plays out: a file is
     * rejected for naming a location that does not exist, the person creates the
     * location, and then has nothing to do but upload the same file again. The
     * parse is already stored; checking it again against the current database
     * costs nothing and saves the round trip.
     */
    @Transactional
    public ImportBatch revalidate(Long batchId) {
        ImportBatch batch = batch(batchId);
        Lookups lookups = lookups();
        Set<String> serialsInFile = new HashSet<>();
        List<ImportBatchRow> all = rows.findByBatchIdOrderByRowNumberAsc(batchId);
        // Asked again rather than reused: the point of re-checking is that the
        // database has changed since the file was read.
        Set<String> serialsInUse = serialsAlreadyInUseFromRows(all);
        Set<String> tagsInFile = new HashSet<>();
        Set<String> tagsInUse = tagsAlreadyInUseFromRows(all);

        for (ImportBatchRow row : all) {
            if (row.getStatus() == ImportBatchRow.Status.IMPORTED) {
                // Already real, and its serial and tag are now taken -- they
                // still have to count towards the in-file duplicate checks.
                Map<String, String> done = stringValues(row);
                String serial = done.getOrDefault("serial_number", "");
                if (!serial.isBlank()) serialsInFile.add(serial.toLowerCase());
                String tag = done.getOrDefault("asset_tag", "");
                if (!tag.isBlank()) tagsInFile.add(tag.toLowerCase());
                continue;
            }

            Map<String, String> values = stringValues(row);
            String problem = check(values, lookups.categories(), lookups.locations(),
                    serialsInFile, serialsInUse, tagsInFile, tagsInUse);
            if (problem == null) {
                row.setStatus(ImportBatchRow.Status.VALID);
                row.setErrorMessage(null);
            } else {
                row.setStatus(ImportBatchRow.Status.INVALID);
                row.setErrorMessage(problem);
            }
            rows.save(row);
        }
        return recount(batch);
    }

    /**
     * Throws the staged file away.
     *
     * <p>An import is a thing someone does, not a record worth keeping: the
     * assets it created are the record, and each carries its own audit history.
     * Keeping the staged rows around would leave half-finished uploads lying
     * about looking like they still needed attention.
     */
    @Transactional
    public void discard(Long batchId) {
        batches.findById(batchId).ifPresent(batches::delete);
    }

    /** Creates the asset for one row, recording success or failure on the row. */
    private void importOne(ImportBatchRow row, Lookups lookups) {
        try {
            // In its own transaction: see ImportRowCommitter for why.
            Asset asset = rowCommitter.create(
                    toRequest(stringValues(row), lookups.categories(), lookups.locations()));
            row.setStatus(ImportBatchRow.Status.IMPORTED);
            row.setCreatedAssetId(asset.getId());
            row.setErrorMessage(null);
        } catch (Exception e) {
            // Something that passed the checks can still be rejected here -- a
            // duplicate serial against a row imported moments ago, for instance.
            // The row records why and the others are unaffected.
            row.setStatus(ImportBatchRow.Status.INVALID);
            row.setErrorMessage(rootMessage(e));
        }
        rows.save(row);
    }

    /** Recomputes the batch counters from the rows, which are the truth. */
    private ImportBatch recount(ImportBatch batch) {
        List<ImportBatchRow> all = rows.findByBatchIdOrderByRowNumberAsc(batch.getId());
        int imported = (int) all.stream()
                .filter(r -> r.getStatus() == ImportBatchRow.Status.IMPORTED).count();
        int invalid = (int) all.stream()
                .filter(r -> r.getStatus() == ImportBatchRow.Status.INVALID).count();

        batch.setSuccessCount(imported);
        batch.setFailureCount(invalid);
        // COMMITTED only once nothing is left to do, so a partly-imported file
        // stays open rather than looking finished with rows still waiting.
        boolean anythingLeft = all.stream()
                .anyMatch(r -> r.getStatus() == ImportBatchRow.Status.VALID);
        batch.setStatus(anythingLeft ? ImportBatch.Status.VALIDATED : ImportBatch.Status.COMMITTED);
        ImportBatch saved = batches.save(batch);

        if (!anythingLeft && imported > 0) {
            audit.recordCreate("IMPORT_BATCH", batch.getId(),
                    "%s: %d imported, %d skipped".formatted(batch.getFilename(), imported, invalid));
        }
        return saved;
    }

    /** The serials in this sheet that a live asset already holds. */
    private Set<String> serialsAlreadyInUse(List<CsvReader.Row> sheetRows) {
        Set<String> offered = new HashSet<>();
        for (CsvReader.Row row : sheetRows) {
            String serial = normaliseKeys(row.values()).getOrDefault("serial_number", "");
            if (!serial.isBlank()) offered.add(serial.toLowerCase());
        }
        return lookupSerials(offered);
    }

    /** The asset tags in this sheet that a live asset already holds. */
    private Set<String> tagsAlreadyInUse(List<CsvReader.Row> sheetRows) {
        Set<String> offered = new HashSet<>();
        for (CsvReader.Row row : sheetRows) {
            String tag = normaliseKeys(row.values()).getOrDefault("asset_tag", "");
            if (!tag.isBlank()) offered.add(tag.toLowerCase());
        }
        return lookupTags(offered);
    }

    private Set<String> tagsAlreadyInUseFromRows(List<ImportBatchRow> storedRows) {
        Set<String> offered = new HashSet<>();
        for (ImportBatchRow row : storedRows) {
            if (row.getStatus() == ImportBatchRow.Status.IMPORTED) continue;
            String tag = stringValues(row).getOrDefault("asset_tag", "");
            if (!tag.isBlank()) offered.add(tag.toLowerCase());
        }
        return lookupTags(offered);
    }

    private Set<String> lookupTags(Set<String> offered) {
        if (offered.isEmpty()) return Set.of();
        Set<String> inUse = new HashSet<>();
        assets.findAssetTagsInUse(offered).forEach(t -> inUse.add(t.toLowerCase()));
        return inUse;
    }

    private Set<String> serialsAlreadyInUseFromRows(List<ImportBatchRow> storedRows) {
        Set<String> offered = new HashSet<>();
        for (ImportBatchRow row : storedRows) {
            // A row this batch already imported holds its own serial; counting it
            // would make the row look like a duplicate of itself.
            if (row.getStatus() == ImportBatchRow.Status.IMPORTED) continue;
            String serial = stringValues(row).getOrDefault("serial_number", "");
            if (!serial.isBlank()) offered.add(serial.toLowerCase());
        }
        return lookupSerials(offered);
    }

    private Set<String> lookupSerials(Set<String> offered) {
        if (offered.isEmpty()) return Set.of();
        Set<String> inUse = new HashSet<>();
        assets.findSerialsInUse(offered).forEach(s -> inUse.add(s.toLowerCase()));
        return inUse;
    }

    private ImportBatch batch(Long batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Import not found"));
    }

    /** Category and location names, resolved once rather than per row. */
    private record Lookups(Map<String, AssetCategory> categories, Map<String, Location> locations) {}

    private Lookups lookups() {
        Map<String, AssetCategory> categoryByName = new HashMap<>();
        categories.findAll().forEach(c -> categoryByName.put(c.getName().toLowerCase(), c));
        Map<String, Location> locationByName = new HashMap<>();
        locations.findAll().forEach(l -> locationByName.put(l.getName().toLowerCase(), l));
        return new Lookups(categoryByName, locationByName);
    }

    private static Map<String, String> stringValues(ImportBatchRow row) {
        Map<String, String> values = new LinkedHashMap<>();
        row.getRawData().forEach((k, v) -> values.put(k, v == null ? "" : String.valueOf(v)));
        return values;
    }

    /** Checks a row without creating anything. Returns null when it is fine. */
    private String check(Map<String, String> values,
                         Map<String, AssetCategory> categoryByName,
                         Map<String, Location> locationByName,
                         Set<String> serialsInFile,
                         Set<String> serialsInUse,
                         Set<String> tagsInFile,
                         Set<String> tagsInUse) {
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
        if (!serial.isBlank()) {
            if (serialsInUse.contains(serial.toLowerCase())) {
                return "Serial number \"" + serial + "\" already belongs to an asset. "
                        + "Importing it would be a duplicate.";
            }
            if (!serialsInFile.add(serial.toLowerCase())) {
                return "Serial number \"" + serial + "\" appears more than once in this file.";
            }
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

        String tag = values.getOrDefault("asset_tag", "");
        if (!tag.isBlank()) {
            if (tagsInUse.contains(tag.toLowerCase())) {
                return "Asset tag \"" + tag + "\" already belongs to an asset. "
                        + "A tag identifies one physical item.";
            }
            if (!tagsInFile.add(tag.toLowerCase())) {
                return "Asset tag \"" + tag + "\" appears more than once in this file.";
            }
        }

        // A category can require custom fields -- a Vehicle needs its VIN. Without
        // this the row passes validation and then fails during the commit, which
        // is the worst place to find out.
        for (var definition : customFields.findByCategoryIdOrderBySortOrderAscIdAsc(category.getId())) {
            if (!definition.isRequired()) continue;
            String supplied = values.get(CUSTOM_PREFIX + definition.getFieldName().toLowerCase());
            if (supplied == null || supplied.isBlank()) {
                return "%s requires \"%s\". Add a column named \"custom:%s\"."
                        .formatted(category.getName(), definition.getFieldName(), definition.getFieldName());
            }
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
                customFieldsFrom(values, category));
    }

    /**
     * Pulls the {@code custom:} columns back out, restoring each definition's
     * real name -- the header was lower-cased for matching, and the stored key
     * has to be exactly what the definition calls it.
     */
    private Map<String, Object> customFieldsFrom(Map<String, String> values, AssetCategory category) {
        Map<String, Object> supplied = new LinkedHashMap<>();
        for (var definition : customFields.findByCategoryIdOrderBySortOrderAscIdAsc(category.getId())) {
            String value = values.get(CUSTOM_PREFIX + definition.getFieldName().toLowerCase());
            if (value != null && !value.isBlank()) supplied.put(definition.getFieldName(), value);
        }
        return supplied;
    }

    static boolean isCustomColumn(String header) {
        return header.trim().toLowerCase().startsWith(CUSTOM_PREFIX);
    }

    /** Column names are matched case- and separator-insensitively. */
    private static Map<String, String> normaliseKeys(Map<String, String> values) {
        Map<String, String> normalised = new LinkedHashMap<>();
        values.forEach((key, value) -> normalised.put(normalise(key), value));
        return normalised;
    }

    /**
     * "Serial Number", "serial number", and "serial_number" are the same column.
     * A {@code custom:} column keeps its spacing beyond the prefix, since it has
     * to match a custom field definition's name exactly.
     */
    static String normalise(String header) {
        String trimmed = header.trim().toLowerCase();
        if (trimmed.startsWith(CUSTOM_PREFIX)) {
            return CUSTOM_PREFIX + trimmed.substring(CUSTOM_PREFIX.length()).trim();
        }
        return trimmed.replaceAll("[\\s-]+", "_");
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

    /**
     * The innermost message, since the outer ones are usually framework noise.
     *
     * <p>A unique-serial violation gets said in words. The preview catches these
     * now, but only as of the moment it ran: someone else can create the asset
     * in between, and "duplicate key value violates unique constraint
     * uq_asset_serial" is not something to put in front of a person.
     */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message != null && message.contains("uq_asset_serial")) {
            return "That serial number already belongs to another asset. "
                    + "It may have been created since this file was checked.";
        }
        if (message != null && message.contains("uq_asset_tag")) {
            return "That asset tag already belongs to another asset. "
                    + "It may have been created since this file was checked.";
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String displayName(String filename) {
        if (filename == null || filename.isBlank()) return "upload.csv";
        String name = filename.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
