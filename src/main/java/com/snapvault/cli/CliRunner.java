package com.snapvault.cli;

import com.snapvault.catalog.SnapshotRepository;
import com.snapvault.model.Snapshot;
import com.snapvault.service.BackupService;
import com.snapvault.service.RestoreService;
import com.snapvault.service.RetentionService;
import com.snapvault.service.VerifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Command-Line Interface (CLI) runner.
 *
 * Supported commands:
 * - backup <sourceDir>
 * - restore <snapshotId> <targetDir> [relativePath]
 * - list
 * - verify
 * - prune --keep-last <N>
 * - prune --older-than-days <X>
 */
@Component
public class CliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final BackupService backupService;
    private final RestoreService restoreService;
    private final RetentionService retentionService;
    private final VerifyService verifyService;
    private final SnapshotRepository snapshotRepo;

    public CliRunner(BackupService backupService,
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

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            // Started without CLI flags (e.g. running as REST API server)
            return;
        }

        String command = args[0].toLowerCase();
        switch (command) {
            case "backup" -> handleBackup(args);
            case "restore" -> handleRestore(args);
            case "list" -> handleList();
            case "verify" -> handleVerify();
            case "prune" -> handlePrune(args);
            case "help", "--help", "-h" -> printUsage();
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
            }
        }
    }

    private void handleBackup(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: backup <sourceDir>");
            return;
        }
        String sourceDir = args[1];
        System.out.println("Starting backup of directory: " + sourceDir);
        Snapshot snapshot = backupService.backup(sourceDir);
        System.out.printf("Backup successful! Snapshot ID: %d | Files: %d | Size: %d bytes%n",
                snapshot.getId(), snapshot.getFileCount(), snapshot.getTotalSize());
    }

    private void handleRestore(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: restore <snapshotId> <targetDir> [optional: relativePath]");
            return;
        }
        long snapshotId = Long.parseLong(args[1]);
        String targetDir = args[2];

        if (args.length >= 4) {
            String relativePath = args[3];
            System.out.printf("Restoring file '%s' from snapshot #%d to '%s'...%n", relativePath, snapshotId, targetDir);
            restoreService.restoreFile(snapshotId, relativePath, targetDir);
            System.out.println("File restored successfully.");
        } else {
            System.out.printf("Restoring snapshot #%d to '%s'...%n", snapshotId, targetDir);
            int count = restoreService.restoreSnapshot(snapshotId, targetDir);
            System.out.printf("Restore complete! %d files restored.%n", count);
        }
    }

    private void handleList() {
        List<Snapshot> snapshots = snapshotRepo.findAll();
        System.out.println("==========================================================================================");
        System.out.printf("%-5s | %-12s | %-6s | %-12s | %-20s | %s%n",
                "ID", "STATUS", "FILES", "SIZE (BYTES)", "CREATED AT", "SOURCE PATH");
        System.out.println("==========================================================================================");
        for (Snapshot s : snapshots) {
            System.out.printf("%-5d | %-12s | %-6d | %-12d | %-20s | %s%n",
                    s.getId(), s.getStatus(), s.getFileCount(), s.getTotalSize(), s.getCreatedAt(), s.getSourcePath());
        }
        System.out.println("==========================================================================================");
    }

    private void handleVerify() {
        System.out.println("Running SHA-256 chunk integrity verification...");
        VerifyService.VerificationReport report = verifyService.verifyAll();
        System.out.println("--------------------------------------------------");
        System.out.printf("Total chunks:     %d%n", report.totalChunksChecked());
        System.out.printf("Healthy chunks:   %d%n", report.healthyChunks());
        System.out.printf("Missing chunks:   %d%n", report.missingChunks());
        System.out.printf("Corrupted chunks: %d%n", report.corruptedChunks());
        System.out.println("--------------------------------------------------");

        if (report.isClean()) {
            System.out.println("ALL CHUNKS VERIFIED HEALTHY! No corruption found.");
        } else {
            System.err.println("INTEGRITY ISSUES DETECTED:");
            for (VerifyService.ChunkIssue issue : report.issues()) {
                System.err.printf("  Chunk %s: %s%n", issue.chunkHash(), issue.status());
                for (VerifyService.AffectedFile af : issue.affectedFiles()) {
                    System.err.printf("    - Affected: Snapshot #%d, File: %s%n", af.snapshotId(), af.filePath());
                }
            }
        }
    }

    private void handlePrune(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: prune --keep-last <N> OR prune --older-than-days <X>");
            return;
        }

        String flag = args[1];
        int value = Integer.parseInt(args[2]);

        if ("--keep-last".equalsIgnoreCase(flag)) {
            System.out.printf("Pruning snapshots to keep last %d completed snapshots...%n", value);
            int gc = retentionService.pruneKeepLast(value);
            System.out.printf("Pruning complete. Garbage collected %d chunks.%n", gc);
        } else if ("--older-than-days".equalsIgnoreCase(flag)) {
            System.out.printf("Pruning snapshots older than %d days...%n", value);
            int gc = retentionService.pruneOlderThanDays(value);
            System.out.printf("Pruning complete. Garbage collected %d chunks.%n", gc);
        } else {
            System.err.println("Unknown prune option: " + flag);
        }
    }

    private void printUsage() {
        System.out.println("""
            SnapVault - Deduplicating Incremental Backup Engine
            Commands:
              backup <sourceDir>
              restore <snapshotId> <targetDir> [relativePath]
              list
              verify
              prune --keep-last <N>
              prune --older-than-days <X>
            """);
    }
}
