package dev.craftingmanager.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class SqlStatements {
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private SqlStatements() {}

    public static String load(String name) {
        return read(name);
    }

    public static String load(String name, String schema) {
        String sql = read(name);
        if (schema == null || schema.isBlank()) return sql;
        return sql.replace("{schema}", schema);
    }

    private static String read(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.startsWith("/") || name.contains("..")) {
            throw new IllegalArgumentException("invalid SQL resource name: " + name);
        }
        return CACHE.computeIfAbsent(name, SqlStatements::readUncached);
    }

    private static String readUncached(String name) {
        String path = "/sql/" + name;
        try (InputStream in = SqlStatements.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing SQL resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            throw new IllegalStateException("failed to load SQL resource: " + path, error);
        }
    }
}
