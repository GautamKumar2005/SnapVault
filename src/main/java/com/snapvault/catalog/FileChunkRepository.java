package com.snapvault.catalog;

import com.snapvault.model.FileChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JDBC repository for the file_chunks join table.
 *
 * This table maps each file to its ordered list of chunks. During restore,
 * we query chunks by file_id ordered by chunk_index to reconstruct the file.
 *
 * Performance: saveBatch() uses JDBC batch mode to insert all chunk mappings
 * for a file in a single round-trip, which is significantly faster than
 * inserting one-by-one for large files (a 1 GB file has ~1024 chunks).
 */
@Repository
public class FileChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(FileChunkRepository.class);

    private final DataSource dataSource;

    public FileChunkRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a single file-chunk mapping.
     */
    public void save(long fileId, int chunkIndex, String chunkHash) {
        String sql = "INSERT INTO file_chunks (file_id, chunk_index, chunk_hash) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, fileId);
            ps.setInt(2, chunkIndex);
            ps.setString(3, chunkHash);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save file_chunk mapping", e);
        }
    }

    /**
     * Batch insert all chunk mappings for a file.
     * Uses JDBC batch mode for performance (one round-trip instead of N).
     *
     * @param fileId the file these chunks belong to
     * @param chunks list of FileChunk objects with chunkIndex and chunkHash set
     */
    public void saveBatch(long fileId, List<FileChunk> chunks) {
        if (chunks.isEmpty()) return;

        String sql = "INSERT INTO file_chunks (file_id, chunk_index, chunk_hash) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (FileChunk chunk : chunks) {
                ps.setLong(1, fileId);
                ps.setInt(2, chunk.getChunkIndex());
                ps.setString(3, chunk.getChunkHash());
                ps.addBatch();
            }
            ps.executeBatch();

            log.debug("Batch inserted {} file_chunks for file {}", chunks.size(), fileId);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch insert file_chunks for file: " + fileId, e);
        }
    }

    /**
     * Find all chunks for a file, ordered by chunk_index.
     * This is the core query for restore: read chunks in order to rebuild the file.
     */
    public List<FileChunk> findByFileId(long fileId) {
        String sql = "SELECT * FROM file_chunks WHERE file_id = ? ORDER BY chunk_index";
        List<FileChunk> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find file_chunks for file: " + fileId, e);
        }
    }

    /**
     * Copy chunk mappings from one file to another.
     * Used for incremental backup: when a file hasn't changed, we copy
     * its chunk references from the previous snapshot instead of re-chunking.
     *
     * @param sourceFileId the file in the previous snapshot
     * @param targetFileId the file in the new snapshot
     */
    public void copyChunks(long sourceFileId, long targetFileId) {
        String sql = "INSERT INTO file_chunks (file_id, chunk_index, chunk_hash) " +
                     "SELECT ?, chunk_index, chunk_hash FROM file_chunks WHERE file_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, targetFileId);
            ps.setLong(2, sourceFileId);
            int copied = ps.executeUpdate();

            log.debug("Copied {} chunk mappings from file {} to file {}", copied, sourceFileId, targetFileId);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to copy chunks from file " + sourceFileId, e);
        }
    }

    /**
     * Find all unique chunk hashes referenced by any file in a snapshot.
     * Used during snapshot deletion to know which chunk ref_counts to decrement.
     */
    public List<String> findChunkHashesBySnapshotId(long snapshotId) {
        // Join file_chunks with files to filter by snapshot_id
        String sql = "SELECT DISTINCT fc.chunk_hash FROM file_chunks fc " +
                     "JOIN files f ON fc.file_id = f.id WHERE f.snapshot_id = ?";
        List<String> hashes = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString("chunk_hash"));
                }
            }
            return hashes;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find chunk hashes for snapshot: " + snapshotId, e);
        }
    }

    /**
     * Find ALL chunk hashes (non-distinct) for files in a snapshot.
     * Used for ref_count management: each occurrence must be counted.
     */
    public List<String> findAllChunkHashesBySnapshotId(long snapshotId) {
        String sql = "SELECT fc.chunk_hash FROM file_chunks fc " +
                     "JOIN files f ON fc.file_id = f.id WHERE f.snapshot_id = ?";
        List<String> hashes = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString("chunk_hash"));
                }
            }
            return hashes;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all chunk hashes for snapshot: " + snapshotId, e);
        }
    }

    private FileChunk mapRow(ResultSet rs) throws SQLException {
        return new FileChunk(
                rs.getLong("id"),
                rs.getLong("file_id"),
                rs.getInt("chunk_index"),
                rs.getString("chunk_hash")
        );
    }
}
