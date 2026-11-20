package com.snapvault.model;

/**
 * Represents a file's metadata within a snapshot.
 *
 * Each file backed up in a snapshot gets one FileRecord. The record stores
 * the relative path (from the source directory root), the file size, and
 * the last-modified timestamp. These are used for incremental backup:
 * if size + lastModified haven't changed since the previous snapshot,
 * we skip re-chunking and reuse the old chunk list.
 */
public class FileRecord {

    private Long id;
    private Long snapshotId;    // which snapshot this file belongs to
    private String path;        // relative path from the backup source root
    private long size;          // file size in bytes
    private long lastModified;  // last-modified time in epoch milliseconds
    private int chunkCount;     // number of chunks this file was split into

    public FileRecord() {
    }

    public FileRecord(Long id, Long snapshotId, String path,
                      long size, long lastModified, int chunkCount) {
        this.id = id;
        this.snapshotId = snapshotId;
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.chunkCount = chunkCount;
    }

    // ---- Getters and Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    @Override
    public String toString() {
        return String.format("FileRecord{id=%d, snapshot=%d, path='%s', size=%d, chunks=%d}",
                id, snapshotId, path, size, chunkCount);
    }
}
