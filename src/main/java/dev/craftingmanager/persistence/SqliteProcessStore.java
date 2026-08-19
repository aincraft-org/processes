package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.EffectExecution;
import dev.craftingmanager.api.Domain.EffectExecutionState;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SqliteProcessStore implements ProcessStore, AutoCloseable {
    public static final String SCHEMA = "craftingmanager";

    private static final String SELECT_SCHEMA_VERSION = SqlStatements.load("migrations/select-schema_version.sql", SCHEMA);
    private static final String UPSERT_SCHEMA_VERSION = SqlStatements.load("migrations/upsert-schema_version.sql", SCHEMA);
    private static final String INITIAL_SCHEMA = SqlStatements.load("migrations/V001__initial.sql", SCHEMA);
    private static final String STEP_TICKS_SCHEMA = SqlStatements.load("migrations/V002__step_ticks.sql", SCHEMA);
    private static final String DELETE_RESERVATIONS = SqlStatements.load("process/delete-reservations.sql", SCHEMA);
    private static final String DELETE_EFFECT_LEDGER = SqlStatements.load("process/delete-effect-ledger.sql", SCHEMA);
    private static final String UPSERT_INSTANCE = SqlStatements.load("process/upsert-instance.sql", SCHEMA);
    private static final String INSERT_RESERVATION = SqlStatements.load("process/insert-reservation.sql", SCHEMA);
    private static final String INSERT_EFFECT = SqlStatements.load("process/insert-effect.sql", SCHEMA);
    private static final String DELETE_INSTANCE = SqlStatements.load("process/delete-instance.sql", SCHEMA);
    private static final String SELECT_INSTANCES = SqlStatements.load("process/select-instances.sql", SCHEMA);
    private static final String SELECT_RESERVATIONS = SqlStatements.load("process/select-reservations.sql", SCHEMA);
    private static final String SELECT_EFFECTS = SqlStatements.load("process/select-effects.sql", SCHEMA);
    private static final String UPSERT_BLOCK = SqlStatements.load("blocks/upsert.sql", SCHEMA);
    private static final String DELETE_BLOCK = SqlStatements.load("blocks/delete.sql", SCHEMA);
    private static final String SELECT_BLOCKS = SqlStatements.load("blocks/select-all.sql", SCHEMA);

    private final Connection connection;

    private SqliteProcessStore(Connection connection) {
        this.connection = connection;
    }

    public static SqliteProcessStore open(Path file) {
        Objects.requireNonNull(file, "database file");
        try {
            Class.forName("org.sqlite.JDBC");
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:");
            String path = file.toAbsolutePath().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + path + "' AS " + SCHEMA);
                applyMigrations(connection, statement);
            }
            return new SqliteProcessStore(connection);
        } catch (Exception error) {
            throw new IllegalStateException("failed to open " + SCHEMA + " database", error);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override public void save(ProcessInstanceRecord record) {
        Objects.requireNonNull(record);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteClaims = connection.prepareStatement(DELETE_RESERVATIONS);
                 PreparedStatement deleteLedger = connection.prepareStatement(DELETE_EFFECT_LEDGER);
                 PreparedStatement upsert = connection.prepareStatement(UPSERT_INSTANCE)) {
                upsert.setString(1, record.instanceId().toString());
                upsert.setString(2, record.block().worldId().toString());
                upsert.setInt(3, record.block().x());
                upsert.setInt(4, record.block().y());
                upsert.setInt(5, record.block().z());
                upsert.setString(6, record.processId());
                upsert.setString(7, record.owner().toString());
                upsert.setLong(8, record.revision());
                upsert.setInt(9, record.step());
                upsert.setInt(10, record.stepTicks());
                upsert.setString(11, record.state().name());
                if (record.reservationState() == null) upsert.setNull(12, Types.VARCHAR);
                else upsert.setString(12, record.reservationState().name());
                upsert.executeUpdate();
                deleteClaims.setString(1, record.instanceId().toString());
                deleteClaims.executeUpdate();
                deleteLedger.setString(1, record.instanceId().toString());
                deleteLedger.executeUpdate();
            }
            try (PreparedStatement insertClaim = connection.prepareStatement(INSERT_RESERVATION)) {
                int index = 0;
                for (Reservation.Claim claim : record.claims()) {
                    insertClaim.setString(1, record.instanceId().toString());
                    insertClaim.setInt(2, index++);
                    insertClaim.setString(3, claim.source().name());
                    insertClaim.setInt(4, claim.slot());
                    insertClaim.setString(5, claim.expected().material());
                    insertClaim.setInt(6, claim.expected().amount());
                    insertClaim.setString(7, encodeMetadata(claim.expected().metadata()));
                    insertClaim.setString(8, claim.inputId());
                    insertClaim.setString(9, claim.policy().name());
                    insertClaim.addBatch();
                }
                insertClaim.executeBatch();
            }
            try (PreparedStatement insertLedger = connection.prepareStatement(INSERT_EFFECT)) {
                int index = 0;
                for (EffectExecution entry : record.ledger()) {
                    insertLedger.setString(1, record.instanceId().toString());
                    insertLedger.setInt(2, index++);
                    insertLedger.setString(3, entry.effectId());
                    insertLedger.setString(4, entry.effectType());
                    insertLedger.setString(5, entry.state().name());
                    insertLedger.addBatch();
                }
                insertLedger.executeBatch();
            }
            connection.commit();
        } catch (SQLException error) {
            rollback();
            throw new IllegalStateException("failed to save process instance", error);
        } finally {
            restoreAutoCommit();
        }
    }

    @Override public void delete(UUID instanceId) {
        Objects.requireNonNull(instanceId);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement claims = connection.prepareStatement(DELETE_RESERVATIONS);
                 PreparedStatement ledger = connection.prepareStatement(DELETE_EFFECT_LEDGER);
                 PreparedStatement instance = connection.prepareStatement(DELETE_INSTANCE)) {
                claims.setString(1, instanceId.toString());
                ledger.setString(1, instanceId.toString());
                instance.setString(1, instanceId.toString());
                claims.executeUpdate();
                ledger.executeUpdate();
                instance.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) {
            rollback();
            throw new IllegalStateException("failed to delete process instance", error);
        } finally {
            restoreAutoCommit();
        }
    }

    @Override public List<ProcessInstanceRecord> loadAll() {
        try (PreparedStatement instances = connection.prepareStatement(SELECT_INSTANCES);
             ResultSet rows = instances.executeQuery()) {
            List<ProcessInstanceRecord> records = new ArrayList<>();
            while (rows.next()) {
                UUID instanceId = UUID.fromString(rows.getString("instance_id"));
                BlockKey block = new BlockKey(
                        UUID.fromString(rows.getString("world_id")),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                String reservation = rows.getString("reservation_state");
                records.add(new ProcessInstanceRecord(
                        instanceId,
                        block,
                        rows.getString("process_id"),
                        UUID.fromString(rows.getString("owner")),
                        rows.getLong("revision"),
                        rows.getInt("step"),
                        rows.getInt("step_ticks"),
                        ProcessState.valueOf(rows.getString("state")),
                        reservation == null ? null : Reservation.State.valueOf(reservation),
                        loadClaims(instanceId),
                        loadLedger(instanceId)));
            }
            return List.copyOf(records);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to load process instances", error);
        }
    }

    @Override public void saveBlock(BlockKey key, String definitionId) {
        Objects.requireNonNull(key);
        if (definitionId == null || definitionId.isBlank()) throw new IllegalArgumentException("definitionId is required");
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_BLOCK)) {
            statement.setString(1, key.worldId().toString());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            statement.setString(5, definitionId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to save functional block", error);
        }
    }

    @Override public void removeBlock(BlockKey key) {
        Objects.requireNonNull(key);
        try (PreparedStatement statement = connection.prepareStatement(DELETE_BLOCK)) {
            statement.setString(1, key.worldId().toString());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to remove functional block", error);
        }
    }

    @Override public List<FunctionalBlockRecord> loadBlocks() {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BLOCKS);
             ResultSet rows = statement.executeQuery()) {
            List<FunctionalBlockRecord> records = new ArrayList<>();
            while (rows.next()) {
                records.add(new FunctionalBlockRecord(
                        new BlockKey(UUID.fromString(rows.getString("world_id")),
                                rows.getInt("x"), rows.getInt("y"), rows.getInt("z")),
                        rows.getString("definition_id")));
            }
            return List.copyOf(records);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to load functional blocks", error);
        }
    }

    @Override public void close() {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to close " + SCHEMA + " database", error);
        }
    }

    private List<Reservation.Claim> loadClaims(UUID instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_RESERVATIONS)) {
            statement.setString(1, instanceId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                List<Reservation.Claim> claims = new ArrayList<>();
                while (rows.next()) {
                    claims.add(new Reservation.Claim(
                            Reservation.Source.valueOf(rows.getString("source")),
                            rows.getInt("slot"),
                            new ItemSnapshot(rows.getString("material"), rows.getInt("amount"),
                                    decodeMetadata(rows.getString("metadata"))),
                            rows.getInt("amount"),
                            rows.getString("input_id"),
                            ConsumptionPolicy.valueOf(rows.getString("policy"))));
                }
                return List.copyOf(claims);
            }
        }
    }

    private List<EffectExecution> loadLedger(UUID instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EFFECTS)) {
            statement.setString(1, instanceId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                List<EffectExecution> ledger = new ArrayList<>();
                while (rows.next()) {
                    ledger.add(new EffectExecution(
                            rows.getString("effect_id"),
                            rows.getString("effect_type"),
                            EffectExecutionState.valueOf(rows.getString("state"))));
                }
                return List.copyOf(ledger);
            }
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // keep the original save failure
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // connection is unusable after this
        }
    }

    static String encodeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        metadata.forEach((key, value) -> {
            if (!out.isEmpty()) out.append('\n');
            out.append(escape(key)).append('=').append(escape(value));
        });
        return out.toString();
    }

    static Map<String, String> decodeMetadata(String encoded) {
        if (encoded == null || encoded.isBlank()) return Map.of();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : encoded.split("\n", -1)) {
            int split = line.indexOf('=');
            if (split < 0) continue;
            metadata.put(unescape(line.substring(0, split)), unescape(line.substring(split + 1)));
        }
        return metadata;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') out.append('\n');
                else out.append(next);
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }

    private static void applyMigrations(Connection connection, Statement statement) throws SQLException {
        int version = schemaVersion(connection);
        if (version < 1) {
            executeScript(statement, INITIAL_SCHEMA);
            writeSchemaVersion(connection, 1);
            version = 1;
        }
        if (version < 2) {
            executeScript(statement, STEP_TICKS_SCHEMA);
            writeSchemaVersion(connection, 2);
        }
    }

    private static void executeScript(Statement statement, String script) throws SQLException {
        for (String part : script.split(";")) {
            String sql = part.strip();
            if (!sql.isEmpty()) statement.execute(sql);
        }
    }

    private static void writeSchemaVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_SCHEMA_VERSION)) {
            upsert.setString(1, SCHEMA);
            upsert.setInt(2, version);
            upsert.executeUpdate();
        }
    }

    private static int schemaVersion(Connection connection) {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SCHEMA_VERSION)) {
            select.setString(1, SCHEMA);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) return 0;
                return rows.getInt(1);
            }
        } catch (SQLException ignored) {
            return 0;
        }
    }
}
