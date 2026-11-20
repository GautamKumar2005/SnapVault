package com.snapvault.service;

import com.snapvault.catalog.ChunkRepository;
import com.snapvault.catalog.FileRepository;
import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.chunking.ChunkingEngine;
import com.snapvault.model.ChunkMetadata;
import com.snapvault.model.FileRecord;
import com.snapvault.model.Snapshot;
import com.snapvault.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Service to verify the cryptographic integrity of stored chunks.
 *
 * Scans all chunks recorded in the catalog, checks if their file exists in
 * storage, re-hashes chunk content, compares with the expected SHA-256,
 * and pinpoints corrupted/missing chunks and which files/snapshots are affected.
 */
@Service
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    private final ChunkRepository chunkRepo;
    private final ChunkStorage chunkStorage;
    private final DataSource dataSource;

    public VerifyService(ChunkRepository chunkRepo,
                         ChunkStorage chunkStorage,
                         DataSource dataSource) {
        this.chunkRepo = chunkRepo;
        this.chunkStorage = chunkStorage;
        this.dataSource = dataSource;
    }

    public record AffectedFile(long snapshotId, String snapshotSource, String filePath) {}

    public record ChunkIssue(String chunkHash, String status, List<AffectedFile> affectedFiles) {}

    public record VerificationReport(
            int totalChunksChecked,
            int healthyChunks,
            int missingChunks,
            int corruptedChunks,
            List<ChunkIssue> issues
    ) {
        public boolean isClean() {
            return missingChunks == 0 && corruptedChunks == 0;
        }
    }

    /**
     * Re-hash all chunks in the storage and report any integrity issues.
     */
    public VerificationReport verifyAll() {
        List<ChunkMetadata> allChunks = chunkRepo.findAll();
        log.info("Starting integrity verification across {} catalog chunks...", allChunks.size());

        int healthy = 0;
        int missing = 0;
        int corrupted = 0;
        List<ChunkIssue> issues = new ArrayList<>();

        for (ChunkMetadata chunk : allChunks) {
            String hash = chunk.getHash();
            if (!chunkStorage.exists(hash)) {
                missing++;
                List<AffectedFile> affected = findAffectedFiles(hash);
                issues.add(new ChunkIssue(hash, "MISSING", affected));
                log.error("Missing chunk detected: {}", hash);
                continue;
            }

            try {
                byte[] data = chunkStorage.retrieve(hash);
                String actualHash = ChunkingEngine.computeSha256(data);
                if (!actualHash.equalsIgnoreCase(hash)) {
                    corrupted++;
                    List<AffectedFile> affected = findAffectedFiles(hash);
                    issues.add(new ChunkIssue(hash, "CORRUPTED", affected));
                    log.error("Corrupted chunk detected: {}. Actual hash is {}", hash, actualHash);
                } else {
                    healthy++;
                }
            } catch (IOException e) {
                corrupted++;
                List<AffectedFile> affected = findAffectedFiles(hash);
                issues.add(new ChunkIssue(hash, "READ_ERROR: " + e.getMessage(), affected));
                log.error("I/O error reading chunk {}: {}", hash, e.getMessage());
            }
        }

        log.info("Verification complete. Healthy: {}, Missing: {}, Corrupted: {}", healthy, missing, corrupted);
        return new VerificationReport(allChunks.size(), healthy, missing, corrupted, issues);
    }

    private List<AffectedFile> findAffectedFiles(String chunkHash) {
        String sql = "SELECT s.id AS snapshot_id, s.source_path, f.path AS file_path " +
                     "FROM file_chunks fc " +
                     "JOIN files f ON fc.file_id = f.id " +
                     "JOIN snapshots s ON f.snapshot_id = s.id " +
                     "WHERE fc.chunk_hash = ?";

        List<AffectedFile> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunkHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AffectedFile(
                            rs.getLong("snapshot_id"),
                            rs.getString("source_path"),
                            rs.getString("file_path")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query affected files for chunk {}: {}", chunkHash, e.getMessage());
        }
        return list;
    }
}
