package com.snapvault.model;

/**
 * Maps a file to its chunks in order.
 *
 * The file_chunks table is the join table between files and chunks.
 * chunk_index preserves the order: chunk 0 is the first 1 MB of the file,
 * chunk 1 is the next 1 MB, etc. During restore, we read chunks in order
 * by chunk_index and concatenate them to reconstruct the original file.
 */
public class FileChunk {

    private Long id;
    private Long fileId;        // references files.id
    private int chunkIndex;     // 0-based position within the file
    private String chunkHash;   // references chunks.hash

    public FileChunk() {
    }

    public FileChunk(Long id, Long fileId, int chunkIndex, String chunkHash) {
        this.id = id;
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.chunkHash = chunkHash;
    }

    // ---- Getters and Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }

    @Override
    public String toString() {
        return String.format("FileChunk{file=%d, index=%d, hash='%s'}", fileId, chunkIndex, chunkHash);
    }
}
