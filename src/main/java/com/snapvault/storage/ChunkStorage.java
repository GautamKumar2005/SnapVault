package com.snapvault.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for chunk storage backends.
 *
 * This abstraction decouples the backup engine from the physical storage
 * mechanism. The default implementation (LocalDiskChunkStore) writes chunks
 * to a local directory tree. By implementing this interface, you could
 * swap in an S3-backed store, GCS, Azure Blob Storage, etc.
 *
 * All implementations must be thread-safe: multiple threads may call
 * store() concurrently with the same hash, and the result must be that
 * exactly one copy of the chunk data exists.
 */
public interface ChunkStorage {

    /**
     * Store a chunk identified by its SHA-256 hash.
     * If the chunk already exists, this is a no-op (idempotent).
     * Implementations must write atomically (temp file + move) so a crash
     * never leaves a half-written chunk.
     *
     * @param hash SHA-256 hex digest of the chunk data
     * @param data the raw chunk bytes
     * @throws IOException if the write fails
     */
    void store(String hash, byte[] data) throws IOException;

    /**
     * Retrieve a chunk's data by its hash.
     *
     * @param hash SHA-256 hex digest
     * @return the raw chunk bytes
     * @throws IOException if the chunk cannot be read or doesn't exist
     */
    byte[] retrieve(String hash) throws IOException;

    /**
     * Check whether a chunk exists in storage.
     *
     * @param hash SHA-256 hex digest
     * @return true if the chunk file exists
     */
    boolean exists(String hash);

    /**
     * Delete a chunk from storage.
     *
     * @param hash SHA-256 hex digest
     * @throws IOException if deletion fails
     */
    void delete(String hash) throws IOException;

    /**
     * Get the filesystem path where a chunk would be stored.
     * Layout: {vault}/chunks/{first 2 hex chars}/{full hash}
     *
     * @param hash SHA-256 hex digest
     * @return the path to the chunk file
     */
    Path getChunkPath(String hash);
}
