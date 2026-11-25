package com.pawfect;

/** The client's baseRevision does not match the database: another save happened first. */
final class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long currentRevision;

    ConflictException(long currentRevision) {
        super("Pawfect data was changed by another tab or window. Reload the latest data before saving.");
        this.currentRevision = currentRevision;
    }

    long currentRevision() { return currentRevision; }
}
