package com.snapvault.model;

import java.time.LocalDateTime;

/**
 * Metadata for a stored chunk in the catalog database.
 *
 * The hash (SHA-256 hex) is the primary key — content-addressable storage.
 * ref_count tracks how many file_chunks rows point to this chunk across
 * all snapshots. When ref_count drops to 0 after a snapshot deletion,
 * the garbage collector can safely remove the chunk from disk and DB.
 */
public class ChunkMetadata {

    private String hash;            // SHA-256 hex string (64 chars), primary key
    private int size;               // chunk size in bytes
    private int refCount;           // number of file_chunks referencing this chunk
    private LocalDateTime createdAt;

    public ChunkMetadata() {
    }

    public ChunkMetadata(String hash, int size, int refCount, LocalDateTime createdAt) {
        this.hash = hash;
        this.size = size;
        this.refCount = refCount;
        this.createdAt = createdAt;
    }

    // ---- Getters and Setters ----

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public int getRefCount() { return refCount; }
    public void setRefCount(int refCount) { this.refCount = refCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return String.format("ChunkMetadata{hash='%s', size=%d, refs=%d}",
                hash, size, refCount);
    }
}
