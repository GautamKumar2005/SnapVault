package com.snapvault.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.snapvault.config.AppConfig;

import java.io.IOException;
import java.nio.file.*;

/**
 * Stores chunks on the local filesystem with content-addressable layout.
 *
 * Directory layout:
 *   {vault}/chunks/{first 2 hex chars of hash}/{full 64-char hash}
 *
 * Example:
 *   vault/chunks/e3/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
 */
@Component
public class LocalDiskChunkStore implements ChunkStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskChunkStore.class);

    private final Path vaultPath;

    @Autowired
    public LocalDiskChunkStore(AppConfig config) {
        this.vaultPath = Path.of(config.getVaultPath()).resolve("chunks");
        log.info("LocalDiskChunkStore initialized at: {}", vaultPath.toAbsolutePath());
    }

    /**
     * Constructor for manual instantiation / unit tests.
     */
    public LocalDiskChunkStore(Path vaultPath) {
        this.vaultPath = vaultPath.resolve("chunks");
    }

    @Override
    public void store(String hash, byte[] data) throws IOException {
        Path target = getChunkPath(hash);

        // Fast path: chunk already exists on disk (deduplication)
        if (Files.exists(target)) {
            log.debug("Chunk {} already exists, skipping write", hash.substring(0, 8));
            return;
        }

        // Ensure parent directory exists (e.g., vault/chunks/e3/)
        Files.createDirectories(target.getParent());

        // Step 1: Write to temporary file in the same directory
        Path tempFile = Files.createTempFile(target.getParent(), "chunk-", ".tmp");

        try {
            Files.write(tempFile, data);

            // Step 2: Atomically move temp -> target.
            // Under Windows, if target file was just created concurrently by another thread,
            // move with ATOMIC_MOVE might throw AccessDeniedException or FileAlreadyExistsException.
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
                log.debug("Stored chunk {} ({} bytes)", hash.substring(0, 8), data.length);
            } catch (FileAlreadyExistsException e) {
                // Another thread wrote this chunk concurrently
                log.debug("Chunk {} already written concurrently", hash.substring(0, 8));
                Files.deleteIfExists(tempFile);
            } catch (AccessDeniedException e) {
                // Windows specific: if target exists, ATOMIC_MOVE can throw AccessDeniedException
                if (Files.exists(target)) {
                    log.debug("Chunk {} target already exists on Windows move collision", hash.substring(0, 8));
                    Files.deleteIfExists(tempFile);
                } else {
                    throw e;
                }
            }

        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            if (!Files.exists(target)) {
                throw e;
            }
        }
    }

    @Override
    public byte[] retrieve(String hash) throws IOException {
        Path chunkPath = getChunkPath(hash);

        if (!Files.exists(chunkPath)) {
            throw new IOException("Chunk not found: " + hash);
        }

        return Files.readAllBytes(chunkPath);
    }

    @Override
    public boolean exists(String hash) {
        return Files.exists(getChunkPath(hash));
    }

    @Override
    public void delete(String hash) throws IOException {
        Path chunkPath = getChunkPath(hash);
        boolean deleted = Files.deleteIfExists(chunkPath);

        if (deleted) {
            log.debug("Deleted chunk {}", hash.substring(0, 8));

            // Clean up empty parent directory
            Path parent = chunkPath.getParent();
            try (var entries = Files.list(parent)) {
                if (entries.findFirst().isEmpty()) {
                    Files.delete(parent);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public Path getChunkPath(String hash) {
        String prefix = hash.substring(0, 2);
        return vaultPath.resolve(prefix).resolve(hash);
    }
}
