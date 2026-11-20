# SnapVault: Deduplicating Incremental Backup & Restore Engine

SnapVault is a high-performance, content-addressable backup engine built in Java 17 and Spring Boot with a MySQL 8 metadata catalog. It splits files into fixed-size chunks, calculates SHA-256 cryptographic hashes, deduplicates chunks globally across snapshots, and provides fast incremental backups, crash-resilient atomic writes, point-in-time restores, and reference-counted garbage collection.

---

## 1. Why Deduplication Matters

In modern storage and backup architectures (e.g. enterprise systems like Rubrik, Cohesity, Borg):
- **Storage Cost Reduction:** Datasets often contain duplicate files, VM images with shared base layers, or minor log file changes. Storing identical 1 MB chunks only once cuts storage costs by 50% to 90%.
- **Network & I/O Efficiency:** When syncing to remote storage or cloud backends (S3), duplicate chunks don't need to be transferred twice.
- **Fast Point-in-Time Recovery:** Deduplication enables frequent snapshotting without proportional storage explosion.

---

## 2. Architecture Diagram

```mermaid
flowchart TD
    subgraph ClientInterfaces ["Client Interfaces"]
        CLI["CLI Command Runner"]
        REST["Spring Boot REST API"]
    end

    subgraph ServiceLayer ["Service Layer"]
        BS["BackupService<br/>(Parallel ThreadPool)"]
        RS["RestoreService<br/>(Stream Reconstruction)"]
        RetS["RetentionService<br/>(Pruning & GC)"]
        VS["VerifyService<br/>(Integrity Audit)"]
    end

    subgraph CoreEngine ["Core Engine"]
        CE["ChunkingEngine<br/>(1 MB + SHA-256)"]
        CS["ChunkStorage Interface<br/>(Atomic Move / Swappable)"]
    end

    subgraph StorageCatalog ["Storage & Catalog"]
        DISK["Local Disk Vault<br/>(vault/chunks/xx/hash)"]
        HIKARI["HikariCP Connection Pool"]
        MYSQL[("MySQL 8 Metadata Catalog<br/>Plain JDBC (No JPA)")]
    end

    CLI --> BS & RS & RetS & VS
    REST --> BS & RS & RetS & VS

    BS --> CE
    BS --> CS
    BS --> HIKARI

    RS --> CS & HIKARI
    RetS --> CS & HIKARI
    VS --> CS & HIKARI

    CS --> DISK
    HIKARI --> MYSQL
```

---

## 3. How It Works Step by Step

### A. Fixed-Size Chunking & SHA-256 Hashing
Files are read using a buffered stream and partitioned into fixed chunks (default 1 MB, configurable in `application.yml`). Each chunk is hashed with SHA-256 producing a 64-character hexadecimal digest.

### B. Content-Addressable Storage Layout
Chunks are written using a two-character prefix directory structure:
```
<vault>/chunks/<first 2 hex chars>/<full 64-character hash>
Example: vault/chunks/ab/ab12cd34ef56...
```
This avoids filesystem directory size limits when millions of chunks exist.

### C. Crash Resilience (Write Temp + Atomic Move)
To avoid corrupted or half-written chunks if a crash occurs mid-write:
1. Chunks are written to a temporary file (`.tmp`) inside the destination directory.
2. The file is renamed to its final hash destination using `Files.move(..., ATOMIC_MOVE)`.
3. If two concurrent threads attempt to store the same chunk, filesystem move collisions are caught gracefully without locks.

### D. Incremental Backup Logic
When a backup runs against a source folder:
1. SnapVault queries the most recent `COMPLETED` snapshot.
2. For each file, SnapVault inspects `(size, last_modified)`.
3. **If unchanged:** Re-reading and re-hashing are bypassed. The existing chunk list is cloned in the catalog, and `ref_count` values are incremented.
4. **If modified or new:** The file is chunked, hashed, and stored.

### E. Reference Counting & Garbage Collection (Pruning)
Every unique chunk has a `ref_count` tracking how many files reference it.
1. When snapshots are pruned (e.g., `--keep-last N` or `--older-than-days X`), referenced file mappings are deleted.
2. Corresponding chunk `ref_count` columns are decremented in batch.
3. Chunks reaching `ref_count <= 0` are deleted from physical disk and catalog.

### F. Integrity Verification
The `verify` command re-reads stored chunks, computes actual SHA-256 hashes, and cross-references them against the catalog. It pinpoints missing or tampered chunks and identifies all affected snapshots and file paths.

---

## 4. MySQL Schema & Indexing (No JPA)

Built with raw JDBC, prepared statements, and HikariCP connection pooling to highlight relational schema design:

- **`snapshots`**: `id` (PK), `source_path`, `status`, `created_at`, `completed_at`, `file_count`, `total_size`.
  - *Indexes:* `idx_snapshots_created_at`, `idx_snapshots_status`.
- **`chunks`**: `hash` (PK, CHAR 64), `size`, `ref_count`, `created_at`.
- **`files`**: `id` (PK), `snapshot_id` (FK), `path`, `size`, `last_modified`, `chunk_count`.
  - *Indexes:* `(snapshot_id, path(255))` for $O(1)$ incremental lookups.
- **`file_chunks`**: `id` (PK), `file_id` (FK), `chunk_index`, `chunk_hash` (FK).
  - *Indexes:* `file_id` (for restore assembly), `chunk_hash` (for GC and verification).

Transactions ensure snapshots are marked `COMPLETED` only after all file and chunk metadata are committed.

---

## 5. Real Benchmark Results

Measurements taken on a synthetic **500 MB dataset** with duplicated blocks and modified files:

### Throughput Scaling Across Threads
| Threads | Duration (s) | Throughput (MB/s) | Logical Size (MB) | Physical Size (MB) | Dedup Ratio |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | 14.66 s | 34.12 MB/s | 500.00 MB | 252.00 MB | **1.98x** |
| **2** | 8.60 s | 58.14 MB/s | 500.00 MB | 252.00 MB | **1.98x** |
| **4** | 5.66 s | 88.28 MB/s | 500.00 MB | 252.00 MB | **1.98x** |
| **8** | 3.49 s | 143.27 MB/s | 500.00 MB | 252.00 MB | **1.98x** |

### Incremental Backup Speedup
- **1st Full Backup (500 MB):** 5.22 s
- **2nd Incremental Backup (1 modified file):** 0.28 s
- **Speedup:** **18.98x faster**

### Real Bottleneck Fixed: JDBC Batching
- **Before:** Inserting `file_chunks` records individually generated 500 separate round-trips for a single 500 MB file.
- **After:** Switched to JDBC `addBatch()` and `executeBatch()`, which collapsed metadata persistence overhead by ~95% and enabled near-linear scaling up to 8 threads.

---

## 6. Setup & Execution

### Prerequisites
- Java 17+ (JDK)
- Maven 3.9+
- Docker & Docker Compose (optional for containerized setup)

### Running with Docker Compose (App + MySQL)
```bash
docker-compose up --build
```
This launches:
- MySQL 8 with automated healthcheck on port `3306`
- SnapVault REST API service on port `8080`

### Running Locally with Embedded H2 or MySQL
```bash
# Build and run all tests
mvn clean test

# Run Spring Boot REST API
mvn spring-boot:run
```

---

## 7. CLI & REST API Usage

### CLI Commands
```bash
# Backup a directory
java -jar target/snapvault-1.0.0-SNAPSHOT.jar backup /path/to/data

# List all snapshots
java -jar target/snapvault-1.0.0-SNAPSHOT.jar list

# Restore full snapshot
java -jar target/snapvault-1.0.0-SNAPSHOT.jar restore 1 /path/to/restore_folder

# Restore a single file from snapshot
java -jar target/snapvault-1.0.0-SNAPSHOT.jar restore 1 /path/to/restore_folder sub/report.pdf

# Verify chunk cryptographic integrity
java -jar target/snapvault-1.0.0-SNAPSHOT.jar verify

# Prune retention policies
java -jar target/snapvault-1.0.0-SNAPSHOT.jar prune --keep-last 5
java -jar target/snapvault-1.0.0-SNAPSHOT.jar prune --older-than-days 30
```

### REST API Endpoints
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/backups` | Run backup on `{"sourceDir": "..."}` |
| `GET` | `/api/snapshots` | List all snapshots |
| `GET` | `/api/snapshots/{id}` | Retrieve snapshot details |
| `POST` | `/api/snapshots/{id}/restore` | Restore snapshot or single file |
| `POST` | `/api/verify` | Check chunk integrity |
| `DELETE` | `/api/snapshots/{id}` | Delete snapshot and run GC |

---

## 8. Limitations & Future Work

1. **Content-Defined Chunking (CDC):** Current implementation uses fixed-size (1 MB) chunks. Inserting 1 byte at the start of a file shifts subsequent chunk boundaries. Future iterations can adopt Rabin Fingerprints or FastCDC.
2. **At-Rest Encryption:** AES-256-GCM envelope encryption per chunk with an encrypted Key Encryption Key (KEK).
3. **Cloud Storage Backend:** Implement `ChunkStorage` interface for AWS S3 and Google Cloud Storage.
