package com.pawfect;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The photos folder. Files are named by the server (32 random hex characters + ".jpg"), so a name can
 * never contain a path, and every lookup re-validates the name and checks the resolved path.
 */
final class PhotoStore {
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{32}\\.jpg");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;

    PhotoStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    Path directory() { return dir; }

    static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Writes the JPEG under a new random name (temp file, then an atomic move) and returns the name. */
    String store(byte[] jpeg) throws IOException {
        Files.createDirectories(dir);
        byte[] r = new byte[16];
        RANDOM.nextBytes(r);
        String name = HexFormat.of().formatHex(r) + ".jpg";
        Path tmp = dir.resolve(name + ".tmp");
        Files.write(tmp, jpeg);
        try {
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(name));
        }
        return name;
    }

    /** The file's bytes, or null when the name is invalid or the file does not exist. */
    byte[] read(String name) throws IOException {
        Path p = path(name);
        return p != null && Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    /** Best-effort removal; a leftover file is harmless, a failed delete must never fail a request. */
    void delete(String name) {
        try {
            Path p = path(name);
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) { }
    }

    private Path path(String name) {
        if (!isValidName(name)) return null;
        Path p = dir.resolve(name).normalize();
        return p.getParent().equals(dir) ? p : null;
    }
}
