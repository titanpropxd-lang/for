package com.servidor.forja;

import java.io.File;
import java.sql.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** SQLite (WAL) con un único hilo de escritura/lectura: no bloquea el hilo principal. */
public final class Database {
    public interface SqlTask { void run(Connection c) throws SQLException; }
    public interface SqlFn<T> { T apply(Connection c) throws SQLException; }

    private final ForjaPlugin plugin;
    private Connection conn;
    private final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Forja-DB");
        t.setDaemon(true);
        return t;
    });

    public Database(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException {
        File f = new File(plugin.getDataFolder(), "forja.db");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("El servidor no incluye el driver SQLite", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS prof(uuid TEXT NOT NULL, prof TEXT NOT NULL, level INTEGER NOT NULL, xp INTEGER NOT NULL, PRIMARY KEY(uuid, prof))");
            s.execute("CREATE TABLE IF NOT EXISTS flags(uuid TEXT NOT NULL, recipe TEXT NOT NULL, flag TEXT NOT NULL, PRIMARY KEY(uuid, recipe))");
            s.execute("CREATE TABLE IF NOT EXISTS stats(uuid TEXT NOT NULL, item TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(uuid, item))");
            s.execute("CREATE TABLE IF NOT EXISTS acc(uuid TEXT PRIMARY KEY, data TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS stations(world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type TEXT NOT NULL, PRIMARY KEY(world, x, y, z))");
        }
    }

    public void run(SqlTask t) {
        ex.execute(() -> {
            try {
                t.run(conn);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error SQL", e);
            }
        });
    }

    public <T> CompletableFuture<T> query(SqlFn<T> f) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return f.apply(conn);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, ex);
    }

    public void close() {
        ex.shutdown();
        try {
            ex.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }
}
