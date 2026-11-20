package com.snapvault.service;

import com.snapvault.catalog.FileChunkRepository;
import com.snapvault.catalog.FileRepository;
import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.chunking.ChunkingEngine;
import com.snapvault.model.FileChunk;
import com.snapvault.model.FileRecord;
import com.snapvault.model.Snapshot;
import com.snapvault.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Service responsible for restoring files from a snapshot back onto disk.
 *
 * It reconstructs files by:
 * 1. Looking up file records belonging to a snapshot.
 * 2. Fetching the chunks in their original chunk_index order.
 * 3. Verifying the SHA-256 hash of each chunk on the fly before writing.
 * 4. Writing chunks sequentially into the target destination.
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final SnapshotRepository snapshotRepo;
    private final FileRepository fileRepo;
    private final FileChunkRepository fileChunkRepo;
    private final ChunkStorage chunkStorage;

    public RestoreService(SnapshotRepository snapshotRepo,
                          FileRepository fileRepo,
                          FileChunkRepository fileChunkRepo,
                          ChunkStorage chunkStorage) {
        this.snapshotRepo = snapshotRepo;
        this.fileRepo = fileRepo;
        this.fileChunkRepo = fileChunkRepo;
        this.chunkStorage = chunkStorage;
    }

    /**
     * Restore all files from a snapshot into targetDir.
     *
     * @param snapshotId ID of the snapshot to restore
     * @param targetDir Destination directory path
     * @return Number of files restored
     */
    public int restoreSnapshot(long snapshotId, String targetDir) throws IOException {
        Snapshot snapshot = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("Snapshot not found with ID: " + snapshotId));

        if (!"COMPLETED".equalsIgnoreCase(snapshot.getStatus())) {
            throw new IllegalStateException("Cannot restore incomplete or failed snapshot: " + snapshotId);
        }

        Path targetRoot = Path.of(targetDir);
        Files.createDirectories(targetRoot);

        List<FileRecord> files = fileRepo.findBySnapshotId(snapshotId);
        log.info("Restoring snapshot #{} ({} files) into '{}'", snapshotId, files.size(), targetDir);

        for (FileRecord fileRecord : files) {
            restoreSingleFileRecord(fileRecord, targetRoot);
        }

        log.info("Successfully restored {} files for snapshot #{}", files.size(), snapshotId);
        return files.size();
    }

    /**
     * Restore a single file from a snapshot by its relative path.
     *
     * @param snapshotId Snapshot ID
     * @param relativePath Relative file path inside the snapshot
     * @param targetDir Destination directory path
     */
    public void restoreFile(long snapshotId, String relativePath, String targetDir) throws IOException {
        String normalizedPath = relativePath.replace('\\', '/');
        FileRecord fileRecord = fileRepo.findBySnapshotAndPath(snapshotId, normalizedPath)
                .orElseThrow(() -> new IllegalArgumentException("File '" + relativePath + "' not found in snapshot #" + snapshotId));

        Path targetRoot = Path.of(targetDir);
        Files.createDirectories(targetRoot);

        restoreSingleFileRecord(fileRecord, targetRoot);
    }

    private void restoreSingleFileRecord(FileRecord fileRecord, Path targetRoot) throws IOException {
        Path destFile = targetRoot.resolve(fileRecord.getPath());
        Files.createDirectories(destFile.getParent());

        List<FileChunk> chunks = fileChunkRepo.findByFileId(fileRecord.getId());

        try (OutputStream out = Files.newOutputStream(destFile)) {
            for (FileChunk chunk : chunks) {
                byte[] chunkData = chunkStorage.retrieve(chunk.getChunkHash());

                // Verify integrity on the fly
                String actualHash = ChunkingEngine.computeSha256(chunkData);
                if (!actualHash.equalsIgnoreCase(chunk.getChunkHash())) {
                    throw new IOException("Corrupted chunk detected during restore! Expected hash: "
                            + chunk.getChunkHash() + ", actual: " + actualHash);
                }

                out.write(chunkData);
            }
        }

        // Restore last modified timestamp if possible
        try {
            Files.setLastModifiedTime(destFile, java.nio.file.attribute.FileTime.fromMillis(fileRecord.getLastModified()));
        } catch (Exception e) {
            log.warn("Could not set last-modified time for restored file '{}'", destFile);
        }

        log.debug("Restored file '{}' ({} chunks)", fileRecord.getPath(), chunks.size());
    }
}
