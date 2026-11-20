package com.snapvault.catalog;

import com.snapvault.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC repository for the snapshots table.
 *
 * Uses PreparedStatement for all queries to prevent SQL injection.
 * Each method obtains a Connection from the HikariCP DataSource,
 * uses try-with-resources for automatic cleanup, and maps ResultSet
 * rows to Snapshot domain objects manually.
 */
@Repository
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private final DataSource dataSource;

    public SnapshotRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a new snapshot and return it with the generated ID.
     */
    public Snapshot save(Snapshot snapshot) {
        String sql = "INSERT INTO snapshots (source_path, status, created_at) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, snapshot.getSourcePath());
            ps.setString(2, snapshot.getStatus());
            ps.setTimestamp(3, Timestamp.valueOf(
                    snapshot.getCreatedAt() != null ? snapshot.getCreatedAt() : LocalDateTime.now()));

            ps.executeUpdate();

            // Retrieve the auto-generated ID
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    snapshot.setId(keys.getLong(1));
                }
            }

            log.debug("Saved snapshot: {}", snapshot);
            return snapshot;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    /**
     * Find a snapshot by its ID.
     */
    public Optional<Snapshot> findById(long id) {
        String sql = "SELECT * FROM snapshots WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find snapshot by id: " + id, e);
        }
    }

    /**
     * List all snapshots ordered by creation time (newest first).
     */
    public List<Snapshot> findAll() {
        String sql = "SELECT * FROM snapshots ORDER BY created_at DESC";
        List<Snapshot> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list snapshots", e);
        }
    }

    /**
     * Find the most recent COMPLETED snapshot (used for incremental comparison).
     */
    public Optional<Snapshot> findLatestCompleted() {
        String sql = "SELECT * FROM snapshots WHERE status = 'COMPLETED' ORDER BY created_at DESC LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find latest completed snapshot", e);
        }
    }

    /**
     * Mark a snapshot as COMPLETED with final file count and total size.
     * This is called inside a transaction after all file/chunk metadata is saved.
     */
    public void markCompleted(long id, int fileCount, long totalSize) {
        String sql = "UPDATE snapshots SET status = 'COMPLETED', completed_at = ?, " +
                     "file_count = ?, total_size = ? WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setInt(2, fileCount);
            ps.setLong(3, totalSize);
            ps.setLong(4, id);
            ps.executeUpdate();

            log.info("Snapshot {} marked COMPLETED ({} files, {} bytes)", id, fileCount, totalSize);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark snapshot completed: " + id, e);
        }
    }

    /**
     * Mark a snapshot as FAILED (called when backup encounters an error).
     */
    public void markFailed(long id) {
        String sql = "UPDATE snapshots SET status = 'FAILED' WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark snapshot failed: " + id, e);
        }
    }

    /**
     * Delete a snapshot by ID.
     * File and file_chunk records are deleted by CASCADE.
     */
    public void deleteById(long id) {
        String sql = "DELETE FROM snapshots WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            ps.executeUpdate();

            log.info("Deleted snapshot {}", id);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete snapshot: " + id, e);
        }
    }

    /**
     * Find snapshots created before a given cutoff date (for retention policies).
     */
    public List<Snapshot> findOlderThan(LocalDateTime cutoff) {
        String sql = "SELECT * FROM snapshots WHERE created_at < ? AND status = 'COMPLETED' ORDER BY created_at";
        List<Snapshot> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.valueOf(cutoff));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find snapshots older than " + cutoff, e);
        }
    }

    /**
     * Map a ResultSet row to a Snapshot domain object.
     */
    private Snapshot mapRow(ResultSet rs) throws SQLException {
        return new Snapshot(
                rs.getLong("id"),
                rs.getString("source_path"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") != null
                        ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                rs.getInt("file_count"),
                rs.getLong("total_size")
        );
    }
}
