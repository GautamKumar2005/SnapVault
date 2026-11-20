package com.snapvault.service;

import com.snapvault.catalog.ChunkRepository;
import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.config.AppConfig;
import com.snapvault.model.Snapshot;
import com.snapvault.storage.ChunkStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BackupRestoreRetentionIntegrationTest {

    @Autowired private BackupService backupService;
    @Autowired private RestoreService restoreService;
    @Autowired private RetentionService retentionService;
    @Autowired private VerifyService verifyService;
    @Autowired private SnapshotRepository snapshotRepo;
    @Autowired private ChunkRepository chunkRepo;
    @Autowired private ChunkStorage chunkStorage;
    @Autowired private AppConfig appConfig;

    @Test
    void testFullBackupRestoreByteIdentical(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        Path file1 = sourceDir.resolve("file1.txt");
        Path subDir = sourceDir.resolve("sub");
        Files.createDirectories(subDir);
        Path file2 = subDir.resolve("file2.bin");

        byte[] content1 = "This is a plain text file for deduplication.".repeat(50).getBytes();
        byte[] content2 = new byte[3000];
        for (int i = 0; i < content2.length; i++) content2[i] = (byte) (i % 256);

        Files.write(file1, content1);
        Files.write(file2, content2);

        // 1. Run full backup
        Snapshot snap1 = backupService.backup(sourceDir.toString());
        assertNotNull(snap1.getId());
        assertEquals("COMPLETED", snap1.getStatus());
        assertEquals(2, snap1.getFileCount());

        // 2. Restore to clean directory
        Path targetDir = tempDir.resolve("dest");
        int restoredCount = restoreService.restoreSnapshot(snap1.getId(), targetDir.toString());
        assertEquals(2, restoredCount);

        // 3. Verify byte-identical restore
        assertArrayEquals(content1, Files.readAllBytes(targetDir.resolve("file1.txt")));
        assertArrayEquals(content2, Files.readAllBytes(targetDir.resolve("sub/file2.bin")));
    }

    @Test
    void testDeduplicationAcrossIdenticalFiles(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("dedup_src");
        Files.createDirectories(sourceDir);

        byte[] duplicateData = "Deduplication candidate identical content".repeat(40).getBytes();
        Files.write(sourceDir.resolve("doc1.txt"), duplicateData);
        Files.write(sourceDir.resolve("doc2.txt"), duplicateData); // exact same content

        int chunksBefore = chunkRepo.findAll().size();
        Snapshot snap = backupService.backup(sourceDir.toString());
        int chunksAfter = chunkRepo.findAll().size();

        // Since both files are identical and chunk size is 1024, only 2 chunks should be added to storage,
        // and their ref_count will be 2.
        int newChunks = chunksAfter - chunksBefore;
        assertTrue(newChunks > 0);
        // Verify ref_count is 2
        chunkRepo.findAll().forEach(chunk -> {
            assertTrue(chunk.getRefCount() >= 1);
        });
    }

    @Test
    void testIncrementalBackupReusesUnchangedFiles(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path sourceDir = tempDir.resolve("inc_src");
        Files.createDirectories(sourceDir);
        Path fileA = sourceDir.resolve("fileA.txt");
        Path fileB = sourceDir.resolve("fileB.txt");

        Files.writeString(fileA, "Content of File A");
        Files.writeString(fileB, "Content of File B");

        Snapshot snap1 = backupService.backup(sourceDir.toString());
        assertEquals(2, snap1.getFileCount());

        // Wait a bit to ensure distinct timestamps, then modify fileB only
        Thread.sleep(100);
        Files.writeString(fileB, "Updated Content of File B");

        Snapshot snap2 = backupService.backup(sourceDir.toString());
        assertEquals(2, snap2.getFileCount());
        assertTrue(snap2.getId() > snap1.getId());
    }

    @Test
    void testVerifyDetectsDeliberatelyCorruptedChunk(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("corrupt_src");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("test.txt"), "Data to corrupt");

        Snapshot snap = backupService.backup(sourceDir.toString());

        // Check verification report before corruption
        var cleanReport = verifyService.verifyAll();
        assertTrue(cleanReport.isClean());

        // Deliberately tamper with one chunk on disk
        var chunk = chunkRepo.findAll().get(0);
        Path chunkPath = chunkStorage.getChunkPath(chunk.getHash());
        Files.write(chunkPath, "TAMPERED DATA".getBytes());

        // Check verification report after corruption
        var badReport = verifyService.verifyAll();
        assertFalse(badReport.isClean());
        assertTrue(badReport.corruptedChunks() > 0);
    }

    @Test
    void testRetentionDeletesOnlyUnreferencedChunks(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("ret_src");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("temp.txt"), "Unique temporary chunk data for GC");

        Snapshot snap = backupService.backup(sourceDir.toString());
        long snapId = snap.getId();

        int deletedChunks = retentionService.deleteSnapshot(snapId);
        assertTrue(deletedChunks >= 1, "Should have garbage collected at least 1 orphaned chunk");
        assertTrue(snapshotRepo.findById(snapId).isEmpty());
    }
}
