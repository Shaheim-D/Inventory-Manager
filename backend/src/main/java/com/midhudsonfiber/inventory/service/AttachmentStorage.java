package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.config.AppProperties;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Where attachment bytes actually live.
 *
 * <p>The uploader never influences the path. A name they supply is recorded as
 * a label and used only when handing the file back; the name on disk is one
 * this class generates. Building a path out of user input is what turns an
 * upload endpoint into a way to write anywhere the process can reach, and
 * sanitising the input instead would mean getting every encoding of ".." right
 * forever.
 */
@Service
public class AttachmentStorage {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final Path root;
    private final long maxBytes;

    public AttachmentStorage(AppProperties properties) {
        this.root = Paths.get(properties.getAttachments().getDirectory()).toAbsolutePath().normalize();
        this.maxBytes = properties.getAttachments().getMaxBytes();
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Writes the upload and returns the path to record, relative to the root.
     *
     * <p>Foldered by month so the directory stays navigable by a human after a
     * few years rather than becoming one flat directory of thousands of files.
     */
    public String store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiExceptions.BadRequestException("That file is empty.");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiExceptions.BadRequestException(
                    "That file is larger than the %d MB limit.".formatted(maxBytes / (1024 * 1024)));
        }

        String relative = LocalDate.now().format(MONTH) + "/" + UUID.randomUUID()
                + extensionOf(file.getOriginalFilename());
        Path destination = resolve(relative);

        try {
            Files.createDirectories(destination.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("Could not save that file: " + e.getMessage());
        }
        return relative;
    }

    public Path read(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isReadable(file)) {
            // The row outliving its file is a real state -- a restore that
            // brought back the database but not the upload directory produces
            // exactly this -- so it gets an honest error rather than a 500.
            throw new ApiExceptions.NotFoundException(
                    "That file is missing from storage. It may not have been included in the last restore.");
        }
        return file;
    }

    /** Best-effort: a file that is already gone is not a reason to fail the delete. */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ignored) {
            // The row is the record; an orphaned file wastes disk but breaks nothing.
        }
    }

    /**
     * Resolves against the root and refuses anything that escapes it. Every path
     * this class stores is machine-generated, so this should never fire — it is
     * here because the day it does, the alternative is writing outside the
     * directory entirely.
     */
    private Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ApiExceptions.BadRequestException("Invalid file path.");
        }
        return resolved;
    }

    /** Kept only so a downloaded file opens in the right application. */
    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot + 1);
        // Anything that is not plainly alphanumeric is dropped rather than
        // cleaned: the extension is a convenience, not information worth risking.
        return extension.matches("[A-Za-z0-9]{1,10}") ? "." + extension.toLowerCase() : "";
    }
}
