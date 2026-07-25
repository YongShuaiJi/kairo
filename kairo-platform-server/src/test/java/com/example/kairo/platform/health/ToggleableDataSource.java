package com.example.kairo.platform.health;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * V1.7 M1-F &sect;8.6 item 1: a delegating {@link DataSource} used by the dependency-health test to
 * simulate a real PostgreSQL outage at the connection level (not a mocked health indicator). When
 * {@link #down} is set, {@code getConnection()} throws {@code SQLException} exactly as it would when
 * the database is unreachable, so Spring's {@code db} readiness probe really fails; when cleared,
 * connections flow again so readiness recovers and pending DURABLE commands can be processed.
 */
public final class ToggleableDataSource extends AbstractDataSource {

    private final DataSource delegate;
    private volatile boolean down;

    public ToggleableDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    public boolean isDown() {
        return down;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (down) {
            throw new SQLException("simulated database outage: connection refused",
                    "08001", 0, null);
        }
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
}
