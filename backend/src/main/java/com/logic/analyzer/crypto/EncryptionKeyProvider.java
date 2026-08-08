package com.logic.analyzer.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Supplies the AES-256 key used to encrypt SFTP passwords at rest. Prefers ENCRYPTION_KEY
 * (a base64-encoded 32-byte key) for production use; otherwise generates one on first run
 * and persists it next to the H2 data file, so a plain restart doesn't lock out already-saved
 * credentials. Losing that key file (without ENCRYPTION_KEY set) means saved SFTP passwords
 * can no longer be decrypted.
 */
@Component
public class EncryptionKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyProvider.class);
    private static final int KEY_BYTES = 32;

    private final String configuredKey;
    private final Path keyFile;
    private SecretKeySpec key;

    public EncryptionKeyProvider(@Value("${ENCRYPTION_KEY:#{null}}") String configuredKey,
                                  @Value("${ENCRYPTION_KEY_FILE:./data/encryption.key}") String keyFilePath) {
        this.configuredKey = configuredKey;
        this.keyFile = Path.of(keyFilePath);
    }

    @PostConstruct
    void init() {
        byte[] bytes;
        if (configuredKey != null && !configuredKey.isBlank()) {
            bytes = Base64.getDecoder().decode(configuredKey.trim());
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "ENCRYPTION_KEY must decode to " + KEY_BYTES + " bytes (a base64-encoded AES-256 key)");
            }
            log.info("Encryption key loaded from ENCRYPTION_KEY.");
        } else {
            bytes = loadOrGenerateKeyFile();
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    private byte[] loadOrGenerateKeyFile() {
        try {
            if (Files.exists(keyFile)) {
                byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                if (bytes.length != KEY_BYTES) {
                    throw new IllegalStateException(
                            keyFile + " does not contain a valid " + KEY_BYTES + "-byte key");
                }
                log.info("Encryption key loaded from {}.", keyFile);
                return bytes;
            }

            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(bytes), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem (e.g. Windows) - best effort only
            }
            log.warn("Generated a new encryption key at {} - back it up, or set ENCRYPTION_KEY in production. " +
                    "Losing this file makes any saved SFTP passwords unrecoverable.", keyFile);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load or create encryption key file at " + keyFile, e);
        }
    }

    public SecretKeySpec getKey() {
        return key;
    }
}
