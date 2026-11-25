package com.pawfect;

/** Raised when the database cannot be safely upgraded; startup is aborted. */
final class MigrationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MigrationException(String message) {
        super(message);
    }

    MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
