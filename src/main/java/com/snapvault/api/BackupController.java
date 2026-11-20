package com.snapvault.api;

import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.model.Snapshot;
import com.snapvault.service.BackupService;
import com.snapvault.service.RestoreService;
import com.snapvault.service.RetentionService;
import com.snapvault.service.VerifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API layer for SnapVault.
 *
 * Endpoints:
 * - POST /api/backups: Run a backup on sourceDir
 * - GET /api/snapshots: List all snapshots
 * - GET /api/snapshots/{id}: Get snapshot metadata
 * - POST /api/snapshots/{id}/restore: Restore snapshot to targetDir
 * - POST /api/verify: Check chunk integrity
 * - DELETE /api/snapshots/{id}: Delete a snapshot and trigger GC
 */
@RestController
@RequestMapping("/api")
public class BackupController {

    private final BackupService backupService;
    private final RestoreService restoreService;
    private final RetentionService retentionService;
    private final VerifyService verifyService;
    private final SnapshotRepository snapshotRepo;

    public BackupController(BackupService backupService,
                            RestoreService restoreService,
                            RetentionService retentionService,
                            VerifyService verifyService,
                            SnapshotRepository snapshotRepo) {
        this.backupService = backupService;
        this.restoreService = restoreService;
        this.retentionService = retentionService;
        this.verifyService = verifyService;
        this.snapshotRepo = snapshotRepo;
    }

    public record BackupRequest(String sourceDir) {}
    public record RestoreRequest(String targetDir, String relativePath) {}

    @PostMapping("/backups")
    public ResponseEntity<Snapshot> createBackup(@RequestBody BackupRequest request) {
        if (request == null || request.sourceDir() == null || request.sourceDir().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Snapshot snapshot = backupService.backup(request.sourceDir());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<Snapshot>> listSnapshots() {
        return ResponseEntity.ok(snapshotRepo.findAll());
    }

    @GetMapping("/snapshots/{id}")
    public ResponseEntity<Snapshot> getSnapshot(@PathVariable("id") long id) {
        return snapshotRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/snapshots/{id}/restore")
    public ResponseEntity<?> restoreSnapshot(@PathVariable("id") long id,
                                             @RequestBody RestoreRequest request) throws IOException {
        if (request == null || request.targetDir() == null || request.targetDir().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetDir is required"));
        }

        if (request.relativePath() != null && !request.relativePath().isBlank()) {
            restoreService.restoreFile(id, request.relativePath(), request.targetDir());
            return ResponseEntity.ok(Map.of("message", "File restored successfully", "path", request.relativePath()));
        } else {
            int filesRestored = restoreService.restoreSnapshot(id, request.targetDir());
            return ResponseEntity.ok(Map.of("message", "Snapshot restored successfully", "filesRestored", filesRestored));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyService.VerificationReport> verifyIntegrity() {
        return ResponseEntity.ok(verifyService.verifyAll());
    }

    @DeleteMapping("/snapshots/{id}")
    public ResponseEntity<?> deleteSnapshot(@PathVariable("id") long id) throws IOException {
        int chunksCollected = retentionService.deleteSnapshot(id);
        return ResponseEntity.ok(Map.of(
                "message", "Snapshot deleted successfully",
                "snapshotId", id,
                "garbageCollectedChunks", chunksCollected
        ));
    }
}
