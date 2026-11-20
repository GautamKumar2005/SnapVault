package com.snapvault.catalog;

import com.snapvault.model.ChunkMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC repository for the chunks table.
 *
 * Key design: uses MySQL's ON DUPLICATE KEY UPDATE for atomic upserts.
 * When two threads try to record the same chunk hash simultaneously,
 * the database guarantees exactly one INSERT succeeds and all others
 * increment the ref_count. No application-level locks needed.
 */
@Repository
public class ChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkRepository.class);

    private final DataSource dataSource;

    public ChunkRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a new chunk or increment its reference count if it already exists.
     *
     * Uses an atomic upsert pattern:
     *   INSERT ... ON DUPLICATE KEY UPDATE ref_count = ref_count + 1
     *
     * This handles the race condition where two threads discover the same
     * chunk hash simultaneously. The first INSERT wins; subsequent ones
     * just bump the counter. No application locks needed.
     *
     * For H2 compatibility in tests, we fall back to try-INSERT then UPDATE.
     *
     * @param hash the SHA-256 hex digest
     * @param size the chunk size in bytes
     */
    public void upsert(String hash, int size) {
        // First, try to insert the chunk as new
        String insertSql = "INSERT INTO chunks (hash, size, ref_count, created_at) VALUES (?, ?, 1, CURRENT_TIMESTAMP)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {

            ps.setString(1, hash);
            ps.setInt(2, size);
            ps.executeUpdate();

            log.debug("Inserted new chunk {} ({} bytes)", hash.substring(0, 8), size);

        } catch (SQLException e) {
            // If the chunk already exists (duplicate primary key), increment ref_count
            if (isDuplicateKeyError(e)) {
                incrementRefCount(hash);
            } else {
                throw new RuntimeException("Failed to upsert chunk: " + hash, e);
            }
        }
    }

    /**
     * Increment the reference count for an existing chunk by 1.
     * Called when a file_chunk mapping is created for a chunk that already exists.
     */
    public void incrementRefCount(String hash) {
        String sql = "UPDATE chunks SET ref_count = ref_count + 1 WHERE hash = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, hash);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to increment ref_count for chunk: " + hash, e);
        }
    }

    /**
     * Decrement the reference count for a chunk by 1.
     * Called when a snapshot is deleted and its chunks are released.
     */
    public void decrementRefCount(String hash) {
        String sql = "UPDATE chunks SET ref_count = ref_count - 1 WHERE hash = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, hash);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to decrement ref_count for chunk: " + hash, e);
        }
    }

    /**
     * Batch decrement reference counts for multiple chunks.
     * Much more efficient than calling decrementRefCount() in a loop.
     */
    public void decrementRefCounts(List<String> hashes) {
        if (hashes.isEmpty()) return;

        String sql = "UPDATE chunks SET ref_count = ref_count - 1 WHERE hash = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (String hash : hashes) {
                ps.setString(1, hash);
                ps.addBatch();
            }
            ps.executeBatch();

            log.debug("Decremented ref_count for {} chunks", hashes.size());

        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch decrement ref_counts", e);
        }
    }

    /**
     * Find all chunks in the database.
     */
    public List<ChunkMetadata> findAll() {
        String sql = "SELECT * FROM chunks";
        List<ChunkMetadata> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list chunks", e);
        }
    }

    /**
     * Find a chunk by its hash.
     */
    public Optional<ChunkMetadata> findByHash(String hash) {
        String sql = "SELECT * FROM chunks WHERE hash = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find chunk by hash: " + hash, e);
        }
    }

    /**
     * Find all chunks with ref_count = 0 (candidates for garbage collection).
     */
    public List<ChunkMetadata> findUnreferenced() {
        String sql = "SELECT * FROM chunks WHERE ref_count <= 0";
        List<ChunkMetadata> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find unreferenced chunks", e);
        }
    }

    /**
     * Delete all chunks with ref_count = 0.
     * Returns the number of deleted rows.
     */
    public int deleteUnreferenced() {
        String sql = "DELETE FROM chunks WHERE ref_count <= 0";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int deleted = ps.executeUpdate();
            log.info("Garbage collected {} unreferenced chunks from catalog", deleted);
            return deleted;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete unreferenced chunks", e);
        }
    }

    /**
     * Delete a specific chunk by hash.
     */
    public void deleteByHash(String hash) {
        String sql = "DELETE FROM chunks WHERE hash = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, hash);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete chunk: " + hash, e);
        }
    }

    /**
     * Check if an SQLException is a duplicate key violation.
     * Works for both MySQL (error code 1062) and H2 (error code 23505).
     */
    private boolean isDuplicateKeyError(SQLException e) {
        // MySQL: error code 1062 (ER_DUP_ENTRY)
        // H2:    SQL state 23505 (UNIQUE_CONSTRAINT_VIOLATION)
        return e.getErrorCode() == 1062
                || (e.getSQLState() != null && e.getSQLState().equals("23505"));
    }

    private ChunkMetadata mapRow(ResultSet rs) throws SQLException {
        return new ChunkMetadata(
                rs.getString("hash"),
                rs.getInt("size"),
                rs.getInt("ref_count"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
