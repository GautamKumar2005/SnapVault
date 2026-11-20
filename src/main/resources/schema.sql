-- =============================================================================
-- SnapVault Database Schema (MySQL 8)
-- =============================================================================
-- This schema defines the metadata catalog for the backup engine.
-- Tables track snapshots, files within each snapshot, chunk metadata,
-- and the mapping between files and their ordered chunks.
--
-- Run automatically on startup via Spring Boot's SQL initialization.
-- Uses CREATE TABLE IF NOT EXISTS for idempotent re-runs.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- snapshots: each backup run creates one row
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS snapshots (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_path     VARCHAR(1024)   NOT NULL,                    -- directory that was backed up
    status          VARCHAR(20)     NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS | COMPLETED | FAILED
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP       NULL,                        -- set when status becomes COMPLETED
    file_count      INT             NOT NULL DEFAULT 0,          -- total files in this snapshot
    total_size      BIGINT          NOT NULL DEFAULT 0           -- total logical bytes
);

-- Fast lookup for retention policies ("older than X days")
CREATE INDEX IF NOT EXISTS idx_snapshots_created_at ON snapshots(created_at);
CREATE INDEX IF NOT EXISTS idx_snapshots_status     ON snapshots(status);

-- -----------------------------------------------------------------------------
-- chunks: each unique data chunk stored exactly once (content-addressable)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunks (
    hash        VARCHAR(64)  PRIMARY KEY,                        -- SHA-256 hex digest
    size        INT          NOT NULL,                           -- chunk size in bytes
    ref_count   INT          NOT NULL DEFAULT 0,                 -- how many file_chunks reference this
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- files: metadata for each file within a snapshot
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS files (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id     BIGINT          NOT NULL,                    -- which snapshot owns this file
    path            VARCHAR(1024)   NOT NULL,                    -- relative path from source root
    size            BIGINT          NOT NULL,                    -- file size in bytes
    last_modified   BIGINT          NOT NULL,                    -- epoch millis of last modification
    chunk_count     INT             NOT NULL DEFAULT 0,          -- number of chunks in this file

    CONSTRAINT fk_files_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES snapshots(id) ON DELETE CASCADE
);

-- Composite index: quickly find a file by snapshot + path (for incremental comparison)
CREATE INDEX IF NOT EXISTS idx_files_snapshot_id   ON files(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_files_snapshot_path ON files(snapshot_id, path(255));

-- -----------------------------------------------------------------------------
-- file_chunks: ordered mapping from file → chunks
-- Each row says "chunk at position chunk_index of file file_id has hash chunk_hash"
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_chunks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id     BIGINT       NOT NULL,                           -- which file
    chunk_index INT          NOT NULL,                           -- 0-based position within the file
    chunk_hash  VARCHAR(64)  NOT NULL,                           -- references chunks.hash

    CONSTRAINT fk_fc_file  FOREIGN KEY (file_id)    REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_fc_chunk FOREIGN KEY (chunk_hash)  REFERENCES chunks(hash)
);

-- Look up all chunks for a file (restore)
CREATE INDEX IF NOT EXISTS idx_fc_file_id    ON file_chunks(file_id);
-- Look up which files use a given chunk (verify / GC impact analysis)
CREATE INDEX IF NOT EXISTS idx_fc_chunk_hash ON file_chunks(chunk_hash);
-- Ensure each (file_id, chunk_index) pair is unique
CREATE UNIQUE INDEX IF NOT EXISTS idx_fc_file_chunk ON file_chunks(file_id, chunk_index);
