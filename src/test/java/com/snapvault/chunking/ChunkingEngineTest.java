package com.snapvault.chunking;

import com.snapvault.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingEngineTest {

    private ChunkingEngine engine;

    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig();
        config.setChunkSizeBytes(1024); // 1 KB chunks for testing boundaries
        engine = new ChunkingEngine(config);
    }

    @Test
    void testExactChunkBoundary(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("exact.bin");
        byte[] data = new byte[2048]; // exactly 2 chunks of 1024 bytes
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 128);
        Files.write(file, data);

        List<ChunkingEngine.ChunkResult> chunks = engine.chunkFile(file);
        assertEquals(2, chunks.size());
        assertEquals(1024, chunks.get(0).size());
        assertEquals(1024, chunks.get(1).size());
        assertEquals(0, chunks.get(0).index());
        assertEquals(1, chunks.get(1).index());
    }

    @Test
    void testPartialLastChunkBoundary(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("partial.bin");
        byte[] data = new byte[2500]; // 2 full chunks + 452 byte last chunk
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 256);
        Files.write(file, data);

        List<ChunkingEngine.ChunkResult> chunks = engine.chunkFile(file);
        assertEquals(3, chunks.size());
        assertEquals(1024, chunks.get(0).size());
        assertEquals(1024, chunks.get(1).size());
        assertEquals(452, chunks.get(2).size());
    }

    @Test
    void testDeterministicSha256() {
        byte[] sample = "SnapVault chunk content".getBytes();
        String hash1 = ChunkingEngine.computeSha256(sample);
        String hash2 = ChunkingEngine.computeSha256(sample);
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }
}
