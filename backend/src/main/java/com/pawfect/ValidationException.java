package com.pawfect;

/** A client error (bad input): carries an HTTP status and a stable machine-readable code. */
final class ValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    ValidationException(String message) {
        this(400, "INVALID_REQUEST", message);
    }

    ValidationException(String code, String message) {
        this(400, code, message);
    }

    ValidationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() { return status; }

    String code() { return code; }
}
