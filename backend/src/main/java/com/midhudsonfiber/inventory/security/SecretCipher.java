package com.midhudsonfiber.inventory.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Encrypts the handful of secrets this application has to store because a
 * person types them into a settings screen -- currently the RADIUS shared
 * secrets.
 *
 * <p>AES-256-GCM. Authenticated, so a tampered ciphertext fails to decrypt
 * rather than producing plausible rubbish, and a fresh 12-byte nonce per
 * encryption, so the same secret stored twice does not produce the same
 * ciphertext.
 *
 * <p><b>The key lives outside the database, and that is the entire point.</b>
 * {@code pg_dump} captures {@code radius_server}, so a database backup that
 * leaked would otherwise be a leaked shared secret. Without the key, what is in
 * it is inert.
 *
 * <p>The key comes from {@code APP_ENCRYPTION_KEY} (base64, 32 bytes) if it is
 * set. If it is not, one is generated on first start and written to a file
 * beside the application's data, readable only by the account running it. That
 * fallback is deliberate: refusing to start without a configured key would make
 * the first run of a fresh install fail on something nobody had been told to
 * set, and storing the key in the database would make it not a key at all.
 *
 * <p>The consequence is operational and has to be said out loud:
 * <b>restoring a backup onto a new host needs the key as well as the backup.</b>
 * Without it the stored secrets cannot be read and have to be typed in again.
 * They are two RADIUS secrets, not the inventory, so this is an inconvenience
 * rather than a disaster -- but a silent one would not be.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;    // AES-256
    private static final int NONCE_BYTES = 12;  // the size GCM is defined for
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public SecretCipher(@Value("${APP_ENCRYPTION_KEY:}") String configuredKey,
                        @Value("${app.security.key-file:data/secret.key}") String keyFile) {
        this.key = resolveKey(configuredKey, Path.of(keyFile));
    }

    /** True when a value is ciphertext this instance can actually read. */
    public boolean canDecrypt(String stored) {
        if (stored == null || stored.isBlank()) return false;
        try {
            decrypt(stored);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // nonce || ciphertext || tag, base64'd. The nonce is not secret and
            // has to travel with the message to decrypt it.
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Deliberately does not include the plaintext, here or anywhere.
            throw new IllegalStateException("Could not encrypt a stored secret.", e);
        }
    }

    /**
     * @throws IllegalStateException when the value cannot be read with this
     *         instance's key -- a restore onto a host that does not have the
     *         original key, or a tampered row.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("stored value is too short to be a GCM message");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "A stored secret could not be decrypted. This normally means the encryption key "
                            + "changed or was not carried over with a restore; the secret has to be entered again.", e);
        }
    }

    private static SecretKey resolveKey(String configured, Path keyFile) {
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configured.trim());
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "APP_ENCRYPTION_KEY must be base64 of exactly " + KEY_BYTES + " bytes, and is "
                                + bytes.length + ". Generate one with: openssl rand -base64 32");
            }
            return new SecretKeySpec(bytes, "AES");
        }
        return keyFromFile(keyFile);
    }

    private static SecretKey keyFromFile(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile).trim());
                if (bytes.length != KEY_BYTES) {
                    throw new IllegalStateException(keyFile + " does not contain a " + KEY_BYTES + "-byte key.");
                }
                return new SecretKeySpec(bytes, "AES");
            }

            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            if (keyFile.getParent() != null) Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(bytes));
            restrictToOwner(keyFile);

            log.warn("""
                    Generated a new encryption key at {}.
                    Stored secrets (RADIUS shared secrets) are encrypted with it, and it is NOT in \
                    the database -- so it is not in a pg_dump either. Back this file up alongside \
                    the database, or set APP_ENCRYPTION_KEY instead, or a restore onto another host \
                    will need those secrets entered again.""", keyFile.toAbsolutePath());
            return new SecretKeySpec(bytes, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read or create the encryption key at " + keyFile, e);
        }
    }

    /** Best effort: POSIX 0600 where the filesystem has an opinion, skipped on Windows. */
    private static void restrictToOwner(Path keyFile) {
        try {
            Files.setPosixFilePermissions(keyFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            log.info("Could not restrict permissions on {} ({}). Check them by hand if the host is shared.",
                    keyFile, e.getMessage());
        }
    }
}
