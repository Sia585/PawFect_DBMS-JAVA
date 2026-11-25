package com.pawfect;

import java.sql.Connection;
import java.sql.SQLException;

/** One additive, versioned schema change. The Migrator runs it inside a transaction. */
interface Migration {
    /** The PRAGMA user_version this migration brings the database to. */
    int version();

    String description();

    void apply(Connection connection) throws SQLException;
}
