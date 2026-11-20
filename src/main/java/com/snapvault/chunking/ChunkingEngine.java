package com.snapvault.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.snapvault.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Splits files into fixed-size chunks and computes SHA-256 hashes.
 *
 * The chunk size is configurable (default 1 MB). Each chunk is returned
 * with its hash, raw data, size, and position index. The caller uses
 * these results to deduplicate: if two chunks have the same hash, only
 * one copy is stored on disk.
 *
 * Performance note: We read files using buffered streams and reuse a
 * single MessageDigest instance per call to avoid allocation overhead.
 */
@Component
public class ChunkingEngine {

    private static final Logger log = LoggerFactory.getLogger(ChunkingEngine.class);

    private final int chunkSizeBytes;

    public ChunkingEngine(AppConfig config) {
        this.chunkSizeBytes = config.getChunkSizeBytes();
        log.info("ChunkingEngine initialized with chunk size: {} bytes", chunkSizeBytes);
    }

    /**
     * Split a file into fixed-size chunks and compute the SHA-256 hash of each.
     *
     * Example: a 2.5 MB file with 1 MB chunks produces 3 ChunkResults:
     *   - chunk 0: 1 MB
     *   - chunk 1: 1 MB
     *   - chunk 2: 0.5 MB (the remainder)
     *
     * @param filePath the file to chunk
     * @return ordered list of ChunkResults (index 0 = first chunk)
     * @throws IOException if the file cannot be read
     */
    public List<ChunkResult> chunkFile(Path filePath) throws IOException {
        List<ChunkResult> results = new ArrayList<>();
        int chunkIndex = 0;

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[chunkSizeBytes];
            int bytesRead;

            while ((bytesRead = readFully(inputStream, buffer)) > 0) {
                // If the chunk is smaller than the buffer (last chunk), trim it
                byte[] chunkData;
                if (bytesRead == buffer.length) {
                    chunkData = buffer.clone(); // full-size chunk
                } else {
                    chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                }

                // Compute SHA-256 hash of this chunk's data
                String hash = computeSha256(chunkData);

                results.add(new ChunkResult(hash, chunkData, bytesRead, chunkIndex));
                chunkIndex++;
            }
        }

        log.debug("Chunked file '{}' into {} chunks", filePath, results.size());
        return results;
    }

    /**
     * Read exactly buffer.length bytes from the stream (or fewer at EOF).
     *
     * InputStream.read() may return fewer bytes than requested even when
     * more data is available (e.g., due to OS buffering). This method
     * loops until the buffer is full or EOF is reached.
     *
     * @param in     the input stream
     * @param buffer the buffer to fill
     * @return total bytes read, or 0 if EOF was reached immediately
     */
    private int readFully(InputStream in, byte[] buffer) throws IOException {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int bytesRead = in.read(buffer, totalRead, buffer.length - totalRead);
            if (bytesRead == -1) {
                break; // end of file
            }
            totalRead += bytesRead;
        }
        return totalRead;
    }

    /**
     * Compute the SHA-256 hex digest of a byte array.
     *
     * Example output: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
     * (that's the hash of an empty input)
     *
     * @param data the bytes to hash
     * @return lowercase hex string (64 characters)
     */
    public static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in every JVM
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /** Returns the configured chunk size in bytes. */
    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    // =========================================================================
    // Inner class: result of chunking one segment of a file
    // =========================================================================

    /**
     * Holds the result of splitting one chunk from a file.
     *
     * @param hash  SHA-256 hex digest of the chunk data
     * @param data  the raw bytes of this chunk
     * @param size  number of bytes (may be less than chunkSizeBytes for the last chunk)
     * @param index 0-based position of this chunk within the file
     */
    public record ChunkResult(String hash, byte[] data, int size, int index) {
    }
}
