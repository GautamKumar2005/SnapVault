package com.snapvault.service;

import com.snapvault.catalog.*;
import com.snapvault.chunking.ChunkingEngine;
import com.snapvault.chunking.ChunkingEngine.ChunkResult;
import com.snapvault.config.AppConfig;
import com.snapvault.model.FileChunk;
import com.snapvault.model.FileRecord;
import com.snapvault.model.Snapshot;
import com.snapvault.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Core backup service: performs full and incremental backups.
 *
 * Full backup flow:
 *   1. Create a snapshot record (IN_PROGRESS)
 *   2. Walk the source directory tree
 *   3. For each file, split into chunks, hash each chunk, store unique chunks
 *   4. Record file and file→chunk mappings in the catalog
 *   5. Mark snapshot COMPLETED (or FAILED on error)
 *
 * Incremental backup flow:
 *   Steps 1-2 are the same. At step 3, for each file:
 *   - Look up the same path in the previous COMPLETED snapshot
 *   - If the file's size AND lastModified time match, skip re-chunking
 *     and copy the chunk list from the previous snapshot (huge time saver)
 *   - If different (or new file), chunk and hash normally
 *
 * Parallelism:
 *   File chunking and hashing are CPU-intensive. We use an ExecutorService
 *   with a configurable thread count to process multiple files in parallel.
 *   Thread safety is ensured by:
 *   - LocalDiskChunkStore: atomic temp-file + move (no locks)
 *   - ChunkRepository: try-INSERT catch duplicate (database handles race)
 *   - AtomicInteger/AtomicLong for counters
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final SnapshotRepository snapshotRepo;
    private final FileRepository fileRepo;
    private final ChunkRepository chunkRepo;
    private final FileChunkRepository fileChunkRepo;
    private final ChunkingEngine chunkingEngine;
    private final ChunkStorage chunkStorage;
    private final int threadPoolSize;

    public BackupService(SnapshotRepository snapshotRepo,
                         FileRepository fileRepo,
                         ChunkRepository chunkRepo,
                         FileChunkRepository fileChunkRepo,
                         ChunkingEngine chunkingEngine,
                         ChunkStorage chunkStorage,
                         AppConfig config) {
        this.snapshotRepo = snapshotRepo;
        this.fileRepo = fileRepo;
        this.chunkRepo = chunkRepo;
        this.fileChunkRepo = fileChunkRepo;
        this.chunkingEngine = chunkingEngine;
        this.chunkStorage = chunkStorage;
        this.threadPoolSize = config.getThreadPoolSize();
    }

    /**
     * Run a backup of the given source directory.
     *
     * @param sourcePath absolute path to the directory to back up
     * @return the completed Snapshot with its ID, file count, and total size
     * @throws RuntimeException if the backup fails
     */
    public Snapshot backup(String sourcePath) {
        Path sourceDir = Path.of(sourcePath);
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("Source path is not a directory: " + sourcePath);
        }

        // Step 1: Create snapshot record
        Snapshot snapshot = new Snapshot();
        snapshot.setSourcePath(sourcePath);
        snapshot.setStatus("IN_PROGRESS");
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotRepo.save(snapshot);

        log.info("Starting backup of '{}' (snapshot #{})", sourcePath, snapshot.getId());

        try {
            // Step 2: Find previous completed snapshot for incremental comparison
            Optional<Snapshot> previousOpt = snapshotRepo.findLatestCompleted();
            Snapshot previous = previousOpt.orElse(null);

            if (previous != null) {
                log.info("Incremental backup: comparing against snapshot #{}", previous.getId());
            } else {
                log.info("Full backup: no previous snapshot found");
            }

            // Step 3: Discover all regular files in the source directory
            List<Path> filePaths;
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                filePaths = walk
                        .filter(Files::isRegularFile)
                        .toList();
            }

            log.info("Found {} files to process", filePaths.size());

            // Step 4: Process files in parallel using a thread pool
            ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger skippedCount = new AtomicInteger(0);
            AtomicLong totalSize = new AtomicLong(0);

            List<Future<Void>> futures = new ArrayList<>();

            for (Path filePath : filePaths) {
                futures.add(executor.submit(() -> {
                    processFile(filePath, sourceDir, snapshot, previous, skippedCount);
                    totalSize.addAndGet(Files.size(filePath));
                    int done = processedCount.incrementAndGet();

                    // Log progress every 100 files
                    if (done % 100 == 0) {
                        log.info("Progress: {}/{} files processed", done, filePaths.size());
                    }
                    return null;
                }));
            }

            // Wait for all file processing to complete
            executor.shutdown();
            for (Future<Void> future : futures) {
                future.get(); // propagates exceptions from worker threads
            }

            // Step 5: Mark snapshot as COMPLETED
            int fileCount = filePaths.size();
            snapshotRepo.markCompleted(snapshot.getId(), fileCount, totalSize.get());

            snapshot.setStatus("COMPLETED");
            snapshot.setFileCount(fileCount);
            snapshot.setTotalSize(totalSize.get());
            snapshot.setCompletedAt(LocalDateTime.now());

            log.info("Backup complete: snapshot #{}, {} files ({} bytes), {} skipped (unchanged)",
                    snapshot.getId(), fileCount, totalSize.get(), skippedCount.get());

            return snapshot;

        } catch (Exception e) {
            // Mark the snapshot as FAILED so it can be cleaned up later
            snapshotRepo.markFailed(snapshot.getId());
            log.error("Backup failed for snapshot #{}", snapshot.getId(), e);
            throw new RuntimeException("Backup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Process a single file: chunk it, store unique chunks, record metadata.
     *
     * If the file is unchanged from the previous snapshot (same size and
     * lastModified), we skip re-chunking and copy the previous chunk list.
     * This is the core of incremental backup — only changed files are re-read.
     */
    private void processFile(Path filePath, Path sourceDir, Snapshot snapshot,
                             Snapshot previous, AtomicInteger skippedCount) throws IOException {

        // Compute the relative path (e.g., "docs/readme.txt")
        String relativePath = sourceDir.relativize(filePath).toString()
                .replace('\\', '/'); // normalize to forward slashes

        long fileSize = Files.size(filePath);
        long lastModified = Files.getLastModifiedTime(filePath).toMillis();

        // --- Incremental check ---
        // If the file exists in the previous snapshot with the same size and
        // modification time, skip re-chunking and reuse the old chunk list.
        if (previous != null) {
            Optional<FileRecord> prevFileOpt = fileRepo.findBySnapshotAndPath(
                    previous.getId(), relativePath);

            if (prevFileOpt.isPresent()) {
                FileRecord prevFile = prevFileOpt.get();

                if (prevFile.getSize() == fileSize && prevFile.getLastModified() == lastModified) {
                    // File unchanged — copy chunk references instead of re-reading
                    reuseChunks(prevFile, snapshot.getId(), relativePath, fileSize, lastModified);
                    skippedCount.incrementAndGet();
                    log.debug("Skipped unchanged file: {}", relativePath);
                    return;
                }
            }
        }

        // --- File is new or changed: chunk and hash it ---
        List<ChunkResult> chunks = chunkingEngine.chunkFile(filePath);

        // Save the file record
        FileRecord fileRecord = new FileRecord();
        fileRecord.setSnapshotId(snapshot.getId());
        fileRecord.setPath(relativePath);
        fileRecord.setSize(fileSize);
        fileRecord.setLastModified(lastModified);
        fileRecord.setChunkCount(chunks.size());
        fileRepo.save(fileRecord);

        // Store each chunk on disk and record metadata
        List<FileChunk> fileChunks = new ArrayList<>();
        for (ChunkResult chunk : chunks) {
            // Store chunk data (dedup: skips if already on disk)
            chunkStorage.store(chunk.hash(), chunk.data());

            // Record chunk in catalog (upsert: insert or increment ref_count)
            chunkRepo.upsert(chunk.hash(), chunk.size());

            // Build file→chunk mapping
            FileChunk fc = new FileChunk();
            fc.setFileId(fileRecord.getId());
            fc.setChunkIndex(chunk.index());
            fc.setChunkHash(chunk.hash());
            fileChunks.add(fc);
        }

        // Batch insert all file→chunk mappings
        fileChunkRepo.saveBatch(fileRecord.getId(), fileChunks);

        log.debug("Backed up file: {} ({} chunks)", relativePath, chunks.size());
    }

    /**
     * Reuse chunk references from a previous snapshot's file.
     *
     * Instead of re-reading and re-hashing the file, we:
     * 1. Create a new file record pointing to the current snapshot
     * 2. Copy the file_chunks rows from the previous file
     * 3. Increment ref_count for each referenced chunk
     *
     * This is what makes incremental backup fast: unchanged files only
     * require a few database operations, not disk I/O.
     */
    private void reuseChunks(FileRecord prevFile, long newSnapshotId,
                             String path, long size, long lastModified) {

        // Create new file record for the current snapshot
        FileRecord newFile = new FileRecord();
        newFile.setSnapshotId(newSnapshotId);
        newFile.setPath(path);
        newFile.setSize(size);
        newFile.setLastModified(lastModified);
        newFile.setChunkCount(prevFile.getChunkCount());
        fileRepo.save(newFile);

        // Copy chunk mappings from previous file to new file
        fileChunkRepo.copyChunks(prevFile.getId(), newFile.getId());

        // Increment ref_count for each chunk that is now referenced by the new file.
        // We get ALL (non-distinct) chunk hashes because a file might reference
        // the same chunk multiple times (e.g., a file with repeated content).
        List<FileChunk> prevChunks = fileChunkRepo.findByFileId(prevFile.getId());
        for (FileChunk fc : prevChunks) {
            chunkRepo.incrementRefCount(fc.getChunkHash());
        }
    }
}
