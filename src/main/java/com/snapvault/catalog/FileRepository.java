package com.snapvault.catalog;

import com.snapvault.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC repository for the files table.
 *
 * Supports batch inserts for performance: when backing up thousands of files,
 * inserting them one-by-one would be slow due to network round-trips.
 * saveBatch() uses JDBC batch mode to send all inserts in one round-trip.
 */
@Repository
public class FileRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRepository.class);

    private final DataSource dataSource;

    public FileRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a single file record and return it with the generated ID.
     */
    public FileRecord save(FileRecord file) {
        String sql = "INSERT INTO files (snapshot_id, path, size, last_modified, chunk_count) " +
                     "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, file.getSnapshotId());
            ps.setString(2, file.getPath());
            ps.setLong(3, file.getSize());
            ps.setLong(4, file.getLastModified());
            ps.setInt(5, file.getChunkCount());

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    file.setId(keys.getLong(1));
                }
            }
            return file;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save file record", e);
        }
    }

    /**
     * Batch insert multiple file records for performance.
     * Uses JDBC addBatch()/executeBatch() to minimize round-trips.
     *
     * @param files list of file records to insert
     * @return list of generated IDs in the same order
     */
    public List<Long> saveBatch(List<FileRecord> files) {
        String sql = "INSERT INTO files (snapshot_id, path, size, last_modified, chunk_count) " +
                     "VALUES (?, ?, ?, ?, ?)";
        List<Long> generatedIds = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            for (FileRecord file : files) {
                ps.setLong(1, file.getSnapshotId());
                ps.setString(2, file.getPath());
                ps.setLong(3, file.getSize());
                ps.setLong(4, file.getLastModified());
                ps.setInt(5, file.getChunkCount());
                ps.addBatch();
            }

            ps.executeBatch();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                int idx = 0;
                while (keys.next()) {
                    long generatedId = keys.getLong(1);
                    generatedIds.add(generatedId);
                    if (idx < files.size()) {
                        files.get(idx).setId(generatedId);
                    }
                    idx++;
                }
            }

            log.debug("Batch inserted {} file records", files.size());
            return generatedIds;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch insert file records", e);
        }
    }

    /**
     * Find a file by its ID.
     */
    public Optional<FileRecord> findById(long id) {
        String sql = "SELECT * FROM files WHERE id = ?";

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
            throw new RuntimeException("Failed to find file by id: " + id, e);
        }
    }

    /**
     * Find all files belonging to a snapshot.
     */
    public List<FileRecord> findBySnapshotId(long snapshotId) {
        String sql = "SELECT * FROM files WHERE snapshot_id = ? ORDER BY path";
        List<FileRecord> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find files for snapshot: " + snapshotId, e);
        }
    }

    /**
     * Find a specific file by snapshot ID and relative path.
     * Used for incremental backup comparison.
     */
    public Optional<FileRecord> findBySnapshotAndPath(long snapshotId, String path) {
        String sql = "SELECT * FROM files WHERE snapshot_id = ? AND path = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, snapshotId);
            ps.setString(2, path);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to find file by snapshot and path", e);
        }
    }

    /**
     * Delete all files for a snapshot (called during snapshot deletion).
     * Note: file_chunks are deleted by CASCADE.
     */
    public void deleteBySnapshotId(long snapshotId) {
        String sql = "DELETE FROM files WHERE snapshot_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, snapshotId);
            int deleted = ps.executeUpdate();
            log.debug("Deleted {} files for snapshot {}", deleted, snapshotId);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete files for snapshot: " + snapshotId, e);
        }
    }

    /**
     * Map a ResultSet row to a FileRecord domain object.
     */
    private FileRecord mapRow(ResultSet rs) throws SQLException {
        return new FileRecord(
                rs.getLong("id"),
                rs.getLong("snapshot_id"),
                rs.getString("path"),
                rs.getLong("size"),
                rs.getLong("last_modified"),
                rs.getInt("chunk_count")
        );
    }
}
