package com.snapvault.service;

import com.snapvault.catalog.ChunkRepository;
import com.snapvault.catalog.FileChunkRepository;
import com.snapvault.catalog.FileRepository;
import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.model.ChunkMetadata;
import com.snapvault.model.Snapshot;
import com.snapvault.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service managing snapshot pruning, retention policies, and garbage collection.
 *
 * Implements reference-counting based GC:
 * 1. When a snapshot is deleted, its files and file_chunk references are examined.
 * 2. Each referenced chunk's ref_count is decremented.
 * 3. Any chunk whose ref_count reaches 0 is deleted from physical storage (vault) and the catalog.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final SnapshotRepository snapshotRepo;
    private final FileRepository fileRepo;
    private final ChunkRepository chunkRepo;
    private final FileChunkRepository fileChunkRepo;
    private final ChunkStorage chunkStorage;

    public RetentionService(SnapshotRepository snapshotRepo,
                            FileRepository fileRepo,
                            ChunkRepository chunkRepo,
                            FileChunkRepository fileChunkRepo,
                            ChunkStorage chunkStorage) {
        this.snapshotRepo = snapshotRepo;
        this.fileRepo = fileRepo;
        this.chunkRepo = chunkRepo;
        this.fileChunkRepo = fileChunkRepo;
        this.chunkStorage = chunkStorage;
    }

    /**
     * Delete a snapshot by ID and garbage collect any orphaned chunks.
     *
     * @param snapshotId Snapshot ID
     * @return Number of unreferenced chunks cleaned up from disk
     */
    public synchronized int deleteSnapshot(long snapshotId) throws IOException {
        Snapshot snapshot = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("Snapshot not found: " + snapshotId));

        log.info("Deleting snapshot #{} ('{}')", snapshotId, snapshot.getSourcePath());

        // 1. Fetch all chunk references used by this snapshot (all occurrences, to balance ref counts)
        List<String> chunkHashes = fileChunkRepo.findAllChunkHashesBySnapshotId(snapshotId);

        // 2. Decrement reference counts
        chunkRepo.decrementRefCounts(chunkHashes);

        // 3. Delete file records (file_chunks are deleted via CASCADE or manual delete)
        fileRepo.deleteBySnapshotId(snapshotId);

        // 4. Delete snapshot record
        snapshotRepo.deleteById(snapshotId);

        // 5. Run garbage collection on 0-ref chunks
        return garbageCollect();
    }

    /**
     * Retain only the last N completed snapshots, deleting older ones.
     *
     * @param keepLast Number of most recent completed snapshots to preserve
     * @return Total number of orphaned chunks deleted
     */
    public int pruneKeepLast(int keepLast) throws IOException {
        if (keepLast < 1) {
            throw new IllegalArgumentException("keepLast must be at least 1");
        }

        List<Snapshot> snapshots = snapshotRepo.findAll();
        List<Snapshot> completed = snapshots.stream()
                .filter(s -> "COMPLETED".equalsIgnoreCase(s.getStatus()))
                .toList();

        if (completed.size() <= keepLast) {
            log.info("Total completed snapshots ({}) <= keepLast ({}), no snapshots pruned", completed.size(), keepLast);
            return 0;
        }

        List<Snapshot> toPrune = completed.subList(keepLast, completed.size());
        int totalGcChunks = 0;
        for (Snapshot s : toPrune) {
            totalGcChunks += deleteSnapshot(s.getId());
        }

        log.info("Pruned {} snapshots, garbage collected {} chunks", toPrune.size(), totalGcChunks);
        return totalGcChunks;
    }

    /**
     * Delete completed snapshots older than X days.
     *
     * @param days Age threshold in days
     * @return Total number of orphaned chunks deleted
     */
    public int pruneOlderThanDays(int days) throws IOException {
        if (days < 0) {
            throw new IllegalArgumentException("Days cannot be negative");
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Snapshot> olderSnapshots = snapshotRepo.findOlderThan(cutoff);

        int totalGcChunks = 0;
        for (Snapshot s : olderSnapshots) {
            totalGcChunks += deleteSnapshot(s.getId());
        }

        log.info("Pruned {} snapshots older than {} days, garbage collected {} chunks",
                olderSnapshots.size(), days, totalGcChunks);
        return totalGcChunks;
    }

    /**
     * Identify and delete any chunks in the catalog whose ref_count <= 0.
     *
     * @return Number of chunks removed
     */
    public int garbageCollect() throws IOException {
        List<ChunkMetadata> unreferenced = chunkRepo.findUnreferenced();
        int count = 0;

        for (ChunkMetadata chunk : unreferenced) {
            try {
                chunkStorage.delete(chunk.getHash());
            } catch (Exception e) {
                log.warn("Failed to delete chunk file {} from storage: {}", chunk.getHash(), e.getMessage());
            }
            chunkRepo.deleteByHash(chunk.getHash());
            count++;
        }

        log.info("Garbage collection finished. Removed {} orphaned chunks.", count);
        return count;
    }
}
