package com.snapvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the SnapVault application.
 *
 * Can run as:
 * 1. CLI tool: pass command-line arguments (e.g., `backup /path/to/data`)
 * 2. REST API server: run with no arguments to start the Spring Web embedded server.
 */
@SpringBootApplication
public class SnapVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnapVaultApplication.class, args);
    }
}
