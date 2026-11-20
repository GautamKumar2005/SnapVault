package com.snapvault.model;

import java.time.LocalDateTime;

/**
 * Represents a point-in-time backup snapshot.
 *
 * Each backup run creates one Snapshot record. While the backup is running,
 * its status is IN_PROGRESS. Once all files and chunks are recorded, it
 * transitions to COMPLETED inside a transaction. If anything fails, it
 * becomes FAILED so it can be cleaned up later.
 */
public class Snapshot {

    private Long id;
    private String sourcePath;      // the directory that was backed up
    private String status;          // IN_PROGRESS, COMPLETED, or FAILED
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private int fileCount;          // total files in this snapshot
    private long totalSize;         // total logical bytes across all files

    /** Default constructor for creating new snapshots. */
    public Snapshot() {
    }

    /** Full constructor for loading from the database. */
    public Snapshot(Long id, String sourcePath, String status,
                    LocalDateTime createdAt, LocalDateTime completedAt,
                    int fileCount, long totalSize) {
        this.id = id;
        this.sourcePath = sourcePath;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.fileCount = fileCount;
        this.totalSize = totalSize;
    }

    // ---- Getters and Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }

    @Override
    public String toString() {
        return String.format("Snapshot{id=%d, source='%s', status='%s', files=%d, size=%d, created=%s}",
                id, sourcePath, status, fileCount, totalSize, createdAt);
    }
}
