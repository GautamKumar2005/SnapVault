package com.snapvault.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LocalDiskChunkStoreTest {

    private LocalDiskChunkStore chunkStore;
    private Path vaultDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        vaultDir = tempDir.resolve("vault");
        chunkStore = new LocalDiskChunkStore(vaultDir);
    }

    @Test
    void testStoreRetrieveAndDelete() throws IOException {
        String hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        byte[] data = "Hello SnapVault".getBytes();

        assertFalse(chunkStore.exists(hash));
        chunkStore.store(hash, data);
        assertTrue(chunkStore.exists(hash));

        // Test layout: vault/chunks/ab/...
        Path expectedPath = vaultDir.resolve("chunks").resolve("ab").resolve(hash);
        assertTrue(Files.exists(expectedPath));

        byte[] retrieved = chunkStore.retrieve(hash);
        assertArrayEquals(data, retrieved);

        chunkStore.delete(hash);
        assertFalse(chunkStore.exists(hash));
    }

    @Test
    void testConcurrentStoreThreadSafety() throws Exception {
        String hash = "deadbeef1234567890abcdef1234567890abcdef1234567890abcdef12345678";
        byte[] data = "Concurrent chunk write test".getBytes();

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                chunkStore.store(hash, data);
                return null;
            }));
        }

        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertTrue(chunkStore.exists(hash));
        assertArrayEquals(data, chunkStore.retrieve(hash));
    }
}
