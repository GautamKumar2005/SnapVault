-- =============================================================================
-- SnapVault Schema for H2 (MySQL compatibility mode, used in tests)
-- =============================================================================
-- Same logical schema as schema.sql but without MySQL-specific syntax
-- (ENGINE=InnoDB, CHARSET, prefix indexes) that H2 doesn't support.
-- =============================================================================

CREATE TABLE IF NOT EXISTS snapshots (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_path     VARCHAR(1024)   NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'IN_PROGRESS',
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP       NULL,
    file_count      INT             NOT NULL DEFAULT 0,
    total_size      BIGINT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS chunks (
    hash        VARCHAR(64)  PRIMARY KEY,
    size        INT          NOT NULL,
    ref_count   INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS files (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id     BIGINT          NOT NULL,
    path            VARCHAR(1024)   NOT NULL,
    size            BIGINT          NOT NULL,
    last_modified   BIGINT          NOT NULL,
    chunk_count     INT             NOT NULL DEFAULT 0,
    CONSTRAINT fk_files_snapshot FOREIGN KEY (snapshot_id) REFERENCES snapshots(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS file_chunks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id     BIGINT       NOT NULL,
    chunk_index INT          NOT NULL,
    chunk_hash  VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_fc_file  FOREIGN KEY (file_id)   REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_fc_chunk FOREIGN KEY (chunk_hash) REFERENCES chunks(hash)
);
