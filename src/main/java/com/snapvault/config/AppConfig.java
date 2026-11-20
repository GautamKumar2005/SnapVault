package com.snapvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the snapvault.* properties from application.yml to Java fields.
 *
 * Example configuration:
 *   snapvault:
 *     vault-path: ./vault
 *     chunk-size-bytes: 1048576
 *     thread-pool-size: 4
 */
@Component
@ConfigurationProperties(prefix = "snapvault")
public class AppConfig {

    /** Root directory for the chunk vault (e.g., ./vault). */
    private String vaultPath = "./vault";

    /** Size of each chunk in bytes (default 1 MB = 1,048,576 bytes). */
    private int chunkSizeBytes = 1_048_576;

    /** Number of threads for parallel chunking and hashing. */
    private int threadPoolSize = 4;

    // ---- Getters and Setters ----

    public String getVaultPath() { return vaultPath; }
    public void setVaultPath(String vaultPath) { this.vaultPath = vaultPath; }

    public int getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(int chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
}
