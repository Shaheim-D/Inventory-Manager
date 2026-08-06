package com.midhudsonfiber.inventory.backup;

import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Taking a backup without leaving the application.
 *
 * <p><b>The artefacts are byte-for-byte the same shape {@code scripts/backup.sh}
 * produces</b>, with the same names: a {@code pg_dump -Fc} of the database and a
 * gzipped tar of the attachment directory, stamped with one timestamp so the
 * pair is obviously a pair. That is the whole point. A backup this screen made
 * is restored by {@code scripts/restore.sh}, unchanged — an in-app backup that
 * needed its own restore path would be a second recovery mechanism to keep
 * true, and the one thing this project cannot afford is two of those.
 *
 * <p>Two artefacts, never one. Attachment bytes are files on a volume with only
 * their path in the database, so a dump on its own restores every attachment
 * row pointing at a file that is not there.
 *
 * <p>This deliberately does <b>not</b> restore. A restore drops the database
 * this application is running against; it cannot sensibly do that to itself,
 * and putting it behind a button would make the most destructive operation in
 * the system the most reachable one. Restoring stays in the runbook, performed
 * by somebody at a shell who meant to.
 *
 * <p>No Docker socket is involved, which is the other half of the design. The
 * application already holds the database credentials and already owns the
 * attachment directory, so it can produce both halves itself with the tools in
 * its own image. Handing a web-facing process the Docker socket to run
 * {@code docker compose exec} would turn a web vulnerability into a host
 * compromise — the same reasoning that keeps {@code update.sh} out of the app.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** The naming backup.sh uses, and therefore what restore.sh knows how to pair. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    private static final String DUMP_PREFIX = "inventory-manager-";
    private static final String DUMP_SUFFIX = ".dump";
    private static final String FILES_PREFIX = "inventory-manager-files-";
    private static final String FILES_SUFFIX = ".tar.gz";

    /**
     * Only ever a name this service itself generated. Downloads and deletes are
     * matched against it before touching the filesystem, so a crafted name can
     * never walk out of the backup directory.
     */
    private static final Pattern SAFE_NAME = Pattern.compile(
            "^inventory-manager-(files-)?\\d{8}T\\d{6}\\.(dump|tar\\.gz)$");

    private final Path backupDirectory;
    private final Path attachmentDirectory;
    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;
    private final long timeoutSeconds;
    /**
     * Both default to a bare name found on PATH, which is what the deployed
     * image provides. They are configurable because a developer machine often
     * does not: the PostgreSQL installer for Windows does not put its bin
     * directory on PATH, so pg_dump is present but unreachable by name.
     */
    private final String pgDumpPath;
    private final String tarPath;
    /** Resolved on first use; the lookup is not worth repeating per backup. */
    private volatile String resolvedPgDump;
    private volatile String resolvedTar;

    public BackupService(
            @Value("${app.backups.directory}") String backupDirectory,
            @Value("${app.attachments.directory}") String attachmentDirectory,
            @Value("${DB_HOST:localhost}") String host,
            @Value("${DB_PORT:5432}") String port,
            @Value("${DB_NAME:inventory_manager}") String database,
            @Value("${DB_USER:inventory_manager}") String username,
            @Value("${DB_PASSWORD:inventory_manager}") String password,
            @Value("${app.backups.timeout-seconds:900}") long timeoutSeconds,
            @Value("${app.backups.pg-dump-path:pg_dump}") String pgDumpPath,
            @Value("${app.backups.tar-path:tar}") String tarPath) {
        this.backupDirectory = Path.of(backupDirectory);
        this.attachmentDirectory = Path.of(attachmentDirectory);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.timeoutSeconds = timeoutSeconds;
        this.pgDumpPath = pgDumpPath;
        this.tarPath = tarPath;
    }

    public record Artefact(String name, long sizeBytes, Instant createdAt, boolean attachments) {}

    /** One backup: the two files that belong together. */
    public record BackupSet(String stamp, Artefact dump, Artefact files) {}

    /**
     * Runs the backup and returns what it produced.
     *
     * <p>Synchronous. At this data volume a dump is well under a second and the
     * archive not much more, so a job queue and a progress endpoint would be
     * machinery for a wait nobody notices. The timeout is the guard against
     * that assumption turning out to be wrong on a much larger installation.
     */
    public BackupSet create() throws IOException, InterruptedException {
        Files.createDirectories(backupDirectory);

        String stamp = nextFreeStamp();
        Path dump = backupDirectory.resolve(DUMP_PREFIX + stamp + DUMP_SUFFIX);
        Path files = backupDirectory.resolve(FILES_PREFIX + stamp + FILES_SUFFIX);

        try {
            run(dump, pgDump(), "-Fc", "-h", host, "-p", port, "-U", username, database);

            // A zero-byte dump is worse than no dump, because it looks like success.
            if (Files.size(dump) == 0) {
                throw new ApiExceptions.BadRequestException(
                        "The database dump came out empty. Nothing was kept.");
            }

            // An installation that has never had an upload has no directory yet.
            // That is a legitimate empty archive, not a failure.
            Files.createDirectories(attachmentDirectory);
            run(files, tar(), "-czf", "-", "-C", attachmentDirectory.toString(), ".");

            log.info("Backup created: {} ({} bytes) and {} ({} bytes)",
                    dump.getFileName(), Files.size(dump), files.getFileName(), Files.size(files));

            return new BackupSet(stamp, describe(dump), describe(files));
        } catch (RuntimeException | IOException | InterruptedException failure) {
            // Never leave half a backup behind. A lone dump next to no archive
            // is the shape of a complete backup, and somebody will restore from
            // it one day and find every attachment gone.
            Files.deleteIfExists(dump);
            Files.deleteIfExists(files);
            throw failure;
        }
    }

    /** Newest first, paired up. A half of a pair is reported rather than hidden. */
    public List<BackupSet> list() throws IOException {
        if (!Files.isDirectory(backupDirectory)) return List.of();

        Map<String, List<Path>> byStamp;
        try (Stream<Path> entries = Files.list(backupDirectory)) {
            byStamp = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> SAFE_NAME.matcher(p.getFileName().toString()).matches())
                    .collect(java.util.stream.Collectors.groupingBy(BackupService::stampOf));
        }

        List<BackupSet> sets = new ArrayList<>();
        for (var entry : byStamp.entrySet()) {
            Artefact dump = null;
            Artefact files = null;
            for (Path path : entry.getValue()) {
                if (path.getFileName().toString().startsWith(FILES_PREFIX)) files = describe(path);
                else dump = describe(path);
            }
            sets.add(new BackupSet(entry.getKey(), dump, files));
        }
        sets.sort(Comparator.comparing(BackupSet::stamp).reversed());
        return sets;
    }

    /**
     * Both halves of one backup, zipped into the stream, for people rather than
     * for scripts.
     *
     * <p>The two files stay two files on disk, because that is the format
     * restore.sh reads and backup.sh writes, and a third stored artefact would
     * be a third thing to keep in step. The zip is built on the fly purely so
     * there is one thing to download, attach to a ticket, or copy to a USB
     * stick — a backup people carry around in two pieces is a backup that
     * arrives somewhere in one piece.
     *
     * <p>The entries keep their original names, which is load-bearing: restore.sh
     * pairs a dump with its archive by the timestamp in the filename, so it can
     * unpack this and proceed exactly as if the two files had been handed over
     * separately.
     */
    public void writeArchive(String stamp, java.io.OutputStream out) throws IOException {
        if (!stamp.matches("^\\d{8}T\\d{6}$")) {
            throw new ApiExceptions.NotFoundException("No such backup.");
        }
        Path dump = backupDirectory.resolve(DUMP_PREFIX + stamp + DUMP_SUFFIX);
        Path files = backupDirectory.resolve(FILES_PREFIX + stamp + FILES_SUFFIX);
        if (!Files.isRegularFile(dump) && !Files.isRegularFile(files)) {
            throw new ApiExceptions.NotFoundException("No such backup.");
        }

        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            // Both members are already compressed -- a custom-format dump and a
            // gzip -- so deflating again costs CPU to save almost nothing.
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            for (Path member : List.of(dump, files)) {
                if (!Files.isRegularFile(member)) continue;
                zip.putNextEntry(new java.util.zip.ZipEntry(member.getFileName().toString()));
                Files.copy(member, zip);
                zip.closeEntry();
            }
        }
    }

    /** What a single-file download of this backup is called. */
    public static String archiveName(String stamp) {
        return "inventory-manager-backup-" + stamp + ".zip";
    }

    /** The file behind a name this service generated, or a 404. */
    public Path resolve(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ApiExceptions.NotFoundException("No such backup file.");
        }
        Path path = backupDirectory.resolve(name).normalize();
        // Belt as well as braces: the pattern already forbids a separator, and
        // this catches anything a future change to it might let through.
        if (!path.startsWith(backupDirectory) || !Files.isRegularFile(path)) {
            throw new ApiExceptions.NotFoundException("No such backup file.");
        }
        return path;
    }

    /** Removes both halves of one backup. */
    public void delete(String stamp) throws IOException {
        if (!stamp.matches("^\\d{8}T\\d{6}$")) {
            throw new ApiExceptions.NotFoundException("No such backup.");
        }
        Files.deleteIfExists(backupDirectory.resolve(DUMP_PREFIX + stamp + DUMP_SUFFIX));
        Files.deleteIfExists(backupDirectory.resolve(FILES_PREFIX + stamp + FILES_SUFFIX));
    }

    /**
     * A stamp no existing backup is already using.
     *
     * <p>The name carries seconds and no more, because that is the format
     * backup.sh writes and restore.sh reads, and changing it would break the
     * pairing that makes an in-app backup restorable by the shipped script. But
     * two backups started within the same second would then resolve to the same
     * two filenames, and the second would silently overwrite the first —
     * leaving one backup where the operator watched two being taken.
     *
     * <p>So a taken second is stepped over rather than written into. The clock
     * catches up within a second or two; the alternative, an error the user
     * cannot act on, helps nobody.
     */
    private String nextFreeStamp() {
        Instant at = Instant.now();
        for (int attempt = 0; attempt < 120; attempt++) {
            String candidate = STAMP.format(at);
            boolean taken = Files.exists(backupDirectory.resolve(DUMP_PREFIX + candidate + DUMP_SUFFIX))
                    || Files.exists(backupDirectory.resolve(FILES_PREFIX + candidate + FILES_SUFFIX));
            if (!taken) return candidate;
            at = at.plusSeconds(1);
        }
        throw new ApiExceptions.BadRequestException(
                "Could not find a free backup timestamp. Remove some existing backups first.");
    }

    private static String stampOf(Path path) {
        String name = path.getFileName().toString();
        String withoutPrefix = name.startsWith(FILES_PREFIX)
                ? name.substring(FILES_PREFIX.length())
                : name.substring(DUMP_PREFIX.length());
        return withoutPrefix.replace(FILES_SUFFIX, "").replace(DUMP_SUFFIX, "");
    }

    private Artefact describe(Path path) throws IOException {
        return new Artefact(
                path.getFileName().toString(),
                Files.size(path),
                Files.getLastModifiedTime(path).toInstant(),
                path.getFileName().toString().startsWith(FILES_PREFIX));
    }

    /**
     * Runs a command, sending its stdout to a file.
     *
     * <p>The password goes in the environment rather than the argument list, so
     * it never appears in {@code ps} output for every other process on the box
     * to read.
     */
    /**
     * Where pg_dump actually is, worked out once and remembered.
     *
     * <p>On the deployed image it is on PATH and the first candidate wins. On a
     * developer machine it very often is not: the PostgreSQL installer for
     * Windows puts pg_dump in {@code C:\Program Files\PostgreSQL\<major>\bin}
     * and adds nothing to PATH, so the tool is installed and unreachable by
     * name. Reporting that as "the system cannot find the file specified" and
     * stopping there makes the operator do a search the application could have
     * done itself.
     *
     * <p>So the usual locations are tried, newest major version first, and each
     * candidate is proved by actually running {@code --version} rather than by
     * the file merely existing. An explicit setting short-circuits all of it:
     * if somebody named a path, a silent fallback to a different binary would
     * be worse than an error.
     */
    private String pgDump() {
        if (resolvedPgDump != null) return resolvedPgDump;
        if (!"pg_dump".equals(pgDumpPath)) return resolvedPgDump = pgDumpPath;

        List<String> candidates = new ArrayList<>();
        candidates.add("pg_dump");
        candidates.addAll(installedPostgresBinaries("pg_dump"));

        for (String candidate : candidates) {
            if (canRun(candidate)) {
                if (!"pg_dump".equals(candidate)) {
                    log.info("pg_dump is not on PATH; using {}", candidate);
                }
                return resolvedPgDump = candidate;
            }
        }
        throw new ApiExceptions.BadRequestException(
                "pg_dump could not be found. It ships with PostgreSQL, so it is normally already "
                + "on this machine -- the Windows installer just does not put it on PATH. Set "
                + "APP_BACKUPS_PG_DUMP_PATH to it (for example "
                + "C:\\Program Files\\PostgreSQL\\16\\bin\\pg_dump.exe), matching the major "
                + "version of the server. Tried: " + String.join(", ", candidates));
    }

    private String tar() {
        if (resolvedTar != null) return resolvedTar;
        if (!"tar".equals(tarPath)) return resolvedTar = tarPath;
        // Windows 11 ships bsdtar in System32, which is on PATH, so this
        // effectively always resolves on the first try.
        if (canRun("tar")) return resolvedTar = "tar";
        throw new ApiExceptions.BadRequestException(
                "tar could not be found. Set APP_BACKUPS_TAR_PATH to it.");
    }

    /** Every pg_dump under a standard PostgreSQL install, newest major first. */
    private static List<String> installedPostgresBinaries(String tool) {
        List<String> found = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        String executable = windows ? tool + ".exe" : tool;

        List<Path> roots = windows
                ? List.of(Path.of("C:\\Program Files\\PostgreSQL"),
                          Path.of("C:\\Program Files (x86)\\PostgreSQL"))
                : List.of(Path.of("/usr/lib/postgresql"), Path.of("/usr/pgsql"),
                          Path.of("/opt/homebrew/opt"), Path.of("/usr/local/opt"));

        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> versions = Files.list(root)) {
                versions.filter(Files::isDirectory)
                        // Newest major version first: pg_dump can dump a server
                        // older than itself but never a newer one.
                        .sorted(Comparator.comparing((Path v) -> v.getFileName().toString()).reversed())
                        .map(v -> v.resolve("bin").resolve(executable))
                        .filter(Files::isRegularFile)
                        .forEach(p -> found.add(p.toString()));
            } catch (IOException ignored) {
                // An unreadable directory is simply not a candidate.
            }
        }

        for (String fixed : windows ? List.<String>of() : List.of("/usr/bin/" + tool, "/usr/local/bin/" + tool)) {
            if (Files.isRegularFile(Path.of(fixed))) found.add(fixed);
        }
        return found;
    }

    /** Proves a candidate by running it, rather than trusting that it exists. */
    private static boolean canRun(String executable) {
        try {
            Process probe = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException notThere) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void run(Path output, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectErrorStream(false);
        builder.environment().put("PGPASSWORD", password);

        Path errors = Files.createTempFile("backup-stderr-", ".log");
        builder.redirectError(errors.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException notFound) {
            Files.deleteIfExists(errors);
            throw new ApiExceptions.BadRequestException(
                    command[0] + " could not be run. It must be installed and on PATH, or named "
                    + "explicitly with app.backups." + (command[0].contains("tar") ? "tar" : "pg-dump")
                    + "-path. On Windows the PostgreSQL installer does not add its bin directory "
                    + "to PATH, so pg_dump is usually present at "
                    + "C:\\Program Files\\PostgreSQL\\16\\bin\\pg_dump.exe but not reachable by name.");
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiExceptions.BadRequestException(
                        command[0] + " did not finish within " + timeoutSeconds + " seconds.");
            }
            if (process.exitValue() != 0) {
                String detail = Files.readString(errors).trim();
                log.error("{} failed ({}): {}", command[0], process.exitValue(), detail);
                throw new ApiExceptions.BadRequestException(
                        command[0] + " failed: " + (detail.isBlank() ? "no detail" : lastLine(detail)));
            }
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    /** pg_dump's useful message is its last line; the rest is usually context. */
    private static String lastLine(String text) {
        String[] lines = text.split("\\R");
        return lines[lines.length - 1];
    }
}
