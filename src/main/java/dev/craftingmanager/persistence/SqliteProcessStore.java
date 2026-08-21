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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class SqliteProcessStore implements ProcessStore, AutoCloseable {
    public static final String SCHEMA = "craftingmanager";

    private static final String SELECT_SCHEMA_VERSION = SqlStatements.load("migrations/select-schema_version.sql", SCHEMA);
    private static final String UPSERT_SCHEMA_VERSION = SqlStatements.load("migrations/upsert-schema_version.sql", SCHEMA);
    private static final String INITIAL_SCHEMA = SqlStatements.load("migrations/V001__initial.sql", SCHEMA);
    private static final String STEP_TICKS_SCHEMA = SqlStatements.load("migrations/V002__step_ticks.sql", SCHEMA);
    private static final String STATION_INVENTORIES_SCHEMA = SqlStatements.load("migrations/V003__station_inventories.sql", SCHEMA);
    private static final String PARKED_REASON_SCHEMA = SqlStatements.load("migrations/V004__parked_reason.sql", SCHEMA);
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
    private static final String UPSERT_SLOT = SqlStatements.load("station/upsert.sql", SCHEMA);
    private static final String DELETE_SLOT = SqlStatements.load("station/delete.sql", SCHEMA);
    private static final String DELETE_SLOTS = SqlStatements.load("station/delete-block.sql", SCHEMA);
    private static final String SELECT_SLOTS = SqlStatements.load("station/select-all.sql", SCHEMA);
    private static final String CREATE_TOMBSTONE = "CREATE TABLE IF NOT EXISTS craftingmanager.instance_tombstones (instance_id TEXT PRIMARY KEY)";

    private final Connection connection;
    private final Connection readConnection;
    private final BlockingQueue<QueuedOp> writeQueue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private final Object submitLock = new Object();
    private volatile boolean open = true;
    private final Object closeLock = new Object();
    private volatile Throwable writerError;
    private volatile boolean terminated;
    private final CompletableFuture<Void> writerTermination = new CompletableFuture<>();

    private SqliteProcessStore(Connection connection, Connection readConnection) {
        this.connection = connection;
        this.readConnection = readConnection;
        this.writer = new Thread(this::drain, "craftingmanager-sqlite-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public static SqliteProcessStore open(Path file) {
        Objects.requireNonNull(file, "database file");
        Connection connection = null;
        Connection readConnection = null;
        try {
            Class.forName("org.sqlite.JDBC");
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:");
            readConnection = DriverManager.getConnection("jdbc:sqlite:");
            String path = file.toAbsolutePath().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + path + "' AS " + SCHEMA);
                applyMigrations(connection, statement);
            }
            try (Statement statement = readConnection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + path + "' AS " + SCHEMA);
                statement.execute(CREATE_TOMBSTONE);
            }
            return new SqliteProcessStore(connection, readConnection);
        } catch (Exception error) {
            closeSilent(connection);
            closeSilent(readConnection);
            throw new IllegalStateException("failed to open " + SCHEMA + " database", error);
        }
    }

    @Override
    public void save(ProcessInstanceRecord record) {
        Objects.requireNonNull(record);
        enqueue(new QueuedWrite(record, record.revision()));
    }

    @Override
    public void delete(UUID instanceId) {
        Objects.requireNonNull(instanceId);
        enqueue(new QueuedDelete(instanceId));
    }

    @Override
    public void saveBlock(BlockKey key, String definitionId) {
        Objects.requireNonNull(key);
        if (definitionId == null || definitionId.isBlank()) throw new IllegalArgumentException("definitionId is required");
        enqueue(new QueuedBlockSave(key, definitionId));
    }

    @Override
    public void removeBlock(BlockKey key) {
        Objects.requireNonNull(key);
        enqueue(new QueuedBlockRemove(key));
    }

    @Override
    public void saveSlot(BlockKey key, String slotId, ItemSnapshot item) {
        Objects.requireNonNull(key);
        if (slotId == null || slotId.isBlank()) throw new IllegalArgumentException("slotId is required");
        Objects.requireNonNull(item);
        enqueue(new QueuedSlotSave(key, slotId, item));
    }

    @Override
    public void removeSlot(BlockKey key, String slotId) {
        Objects.requireNonNull(key);
        if (slotId == null || slotId.isBlank()) throw new IllegalArgumentException("slotId is required");
        enqueue(new QueuedSlotRemove(key, slotId));
    }

    @Override
    public void removeSlots(BlockKey key) {
        Objects.requireNonNull(key);
        enqueue(new QueuedSlotRemoveBlock(key));
    }

    @Override
    public void flush() {
        QueuedFlush barrier = new QueuedFlush(new CompletableFuture<>());
        synchronized (submitLock) {
            if (!open) throw new IllegalStateException("store is closing");
            writeQueue.offer(barrier);
        }
        while (true) {
            if (barrier.future.isDone()) break;
            if (writerTermination.isDone()) {
                throw new IllegalStateException("writer terminated before flush barrier", writerError);
            }
            try { Thread.sleep(1); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("flush interrupted", e);
            }
        }
        Throwable error = writerError;
        if (error != null) {
            throw new IllegalStateException("writer failed", error);
        }
    }

    @Override
    public void close() {
        Poison poison;
        synchronized (submitLock) {
            if (!open) return;
            open = false;
            poison = new Poison(new CountDownLatch(1));
            writeQueue.offer(poison);
        }
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (!terminated) {
            try { Thread.sleep(1); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                writer.interrupt();
                throw new IllegalStateException("close interrupted", e);
            }
            if (System.currentTimeMillis() > deadline) {
                writer.interrupt();
                throw new IllegalStateException("writer did not terminate");
            }
        }
        Throwable error = writerError;
        try {
            if (error != null) {
                throw new IllegalStateException("writer failed", error);
            }
        } finally {
            synchronized (closeLock) {
                try { connection.close(); } catch (SQLException ignored) { }
                try { readConnection.close(); } catch (SQLException ignored) { }
            }
        }
    }

    @Override
    public List<ProcessInstanceRecord> loadAll() {
        try (PreparedStatement instances = readConnection.prepareStatement(SELECT_INSTANCES);
             ResultSet rows = instances.executeQuery()) {
            List<ProcessInstanceRecord> records = new ArrayList<>();
            while (rows.next()) {
                UUID instanceId = UUID.fromString(rows.getString("instance_id"));
                BlockKey block = new BlockKey(
                        UUID.fromString(rows.getString("world_id")),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                String reservation = rows.getString("reservation_state");
                String parkedReason = rows.getString("parked_reason");
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
                        loadLedger(instanceId),
                        parkedReason));
            }
            return List.copyOf(records);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to load process instances", error);
        }
    }

    @Override
    public List<StationSlotRecord> loadSlots() {
        try (PreparedStatement statement = readConnection.prepareStatement(SELECT_SLOTS);
             ResultSet rows = statement.executeQuery()) {
            List<StationSlotRecord> records = new ArrayList<>();
            while (rows.next()) {
                records.add(new StationSlotRecord(
                        new BlockKey(UUID.fromString(rows.getString("world_id")),
                                rows.getInt("x"), rows.getInt("y"), rows.getInt("z")),
                        rows.getString("slot_id"),
                        new ItemSnapshot(rows.getString("material"), rows.getInt("amount"),
                                decodeMetadata(rows.getString("metadata")))));
            }
            return List.copyOf(records);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to load station slots", error);
        }
    }

    @Override
    public List<FunctionalBlockRecord> loadBlocks() {
        try (PreparedStatement statement = readConnection.prepareStatement(SELECT_BLOCKS);
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

    private void enqueue(QueuedOp op) {
        synchronized (submitLock) {
            if (!open) throw new IllegalStateException("store is closed");
            writeQueue.offer(op);
        }
    }

    private void drain() {
        try {
            while (true) {
                QueuedOp op = writeQueue.poll();
                if (op == null) {
                    try { Thread.sleep(1); } catch (InterruptedException ignored) { }
                    continue;
                }
                if (op instanceof QueuedWrite write) applySave(write);
                else if (op instanceof QueuedDelete delete) applyDelete(delete);
                else if (op instanceof QueuedBlockSave blockSave) applyBlockSave(blockSave);
                else if (op instanceof QueuedBlockRemove blockRemove) applyBlockRemove(blockRemove);
                else if (op instanceof QueuedSlotSave slotSave) applySlotSave(slotSave);
                else if (op instanceof QueuedSlotRemove slotRemove) applySlotRemove(slotRemove);
                else if (op instanceof QueuedSlotRemoveBlock slotBlockRemove) applySlotRemoveBlock(slotBlockRemove);
                else if (op instanceof QueuedFlush flush) flush.future.complete(null);
                else if (op instanceof Poison) {
                    ((Poison) op).done.countDown();
                    break;
                }
            }
        } catch (Throwable error) {
            writerError = error;
            writerTermination.completeExceptionally(error);
        } finally {
            if (!writerTermination.isDone()) {
                writerTermination.complete(null);
            }
            terminated = true;
            // writer is done; ensure no further JDBC use
        }
    }

    private void applySave(QueuedWrite write) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement tombstone = connection.prepareStatement(
                    "SELECT 1 FROM craftingmanager.instance_tombstones WHERE instance_id = ?")) {
                tombstone.setString(1, write.record().instanceId().toString());
                try (ResultSet rs = tombstone.executeQuery()) {
                    if (rs.next()) {
                        connection.rollback();
                        return;
                    }
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT revision FROM craftingmanager.process_instances WHERE instance_id = ?")) {
                select.setString(1, write.record().instanceId().toString());
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > write.revision()) {
                        connection.rollback();
                        return;
                    }
                }
            }
            try (PreparedStatement deleteClaims = connection.prepareStatement(DELETE_RESERVATIONS);
                 PreparedStatement deleteLedger = connection.prepareStatement(DELETE_EFFECT_LEDGER);
                 PreparedStatement upsert = connection.prepareStatement(UPSERT_INSTANCE)) {
                upsert.setString(1, write.record().instanceId().toString());
                upsert.setString(2, write.record().block().worldId().toString());
                upsert.setInt(3, write.record().block().x());
                upsert.setInt(4, write.record().block().y());
                upsert.setInt(5, write.record().block().z());
                upsert.setString(6, write.record().processId());
                upsert.setString(7, write.record().owner().toString());
                upsert.setLong(8, write.record().revision());
                upsert.setInt(9, write.record().step());
                upsert.setInt(10, write.record().stepTicks());
                upsert.setString(11, write.record().state().name());
                if (write.record().reservationState() == null) upsert.setNull(12, Types.VARCHAR);
                else upsert.setString(12, write.record().reservationState().name());
                upsert.setString(13, write.record().parkedReason());
                upsert.executeUpdate();
                deleteClaims.setString(1, write.record().instanceId().toString());
                deleteClaims.executeUpdate();
                deleteLedger.setString(1, write.record().instanceId().toString());
                deleteLedger.executeUpdate();
            }
            try (PreparedStatement insertClaim = connection.prepareStatement(INSERT_RESERVATION)) {
                int index = 0;
                for (Reservation.Claim claim : write.record().claims()) {
                    insertClaim.setString(1, write.record().instanceId().toString());
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
                for (EffectExecution entry : write.record().ledger()) {
                    insertLedger.setString(1, write.record().instanceId().toString());
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
            throw new IllegalStateException("failed to async save process instance", error);
        } finally {
            restoreAutoCommit();
        }
    }

    private void applyDelete(QueuedDelete delete) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement claims = connection.prepareStatement(DELETE_RESERVATIONS);
                 PreparedStatement ledger = connection.prepareStatement(DELETE_EFFECT_LEDGER);
                 PreparedStatement instance = connection.prepareStatement(DELETE_INSTANCE);
                 PreparedStatement tombstone = connection.prepareStatement(
                         "INSERT OR IGNORE INTO craftingmanager.instance_tombstones (instance_id) VALUES (?)")) {
                claims.setString(1, delete.instanceId().toString());
                ledger.setString(1, delete.instanceId().toString());
                instance.setString(1, delete.instanceId().toString());
                claims.executeUpdate();
                ledger.executeUpdate();
                instance.executeUpdate();
                tombstone.setString(1, delete.instanceId().toString());
                tombstone.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) {
            rollback();
            throw new IllegalStateException("failed to async delete process instance", error);
        } finally {
            restoreAutoCommit();
        }
    }

    private void applyBlockSave(QueuedBlockSave op) {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_BLOCK)) {
            statement.setString(1, op.key().worldId().toString());
            statement.setInt(2, op.key().x());
            statement.setInt(3, op.key().y());
            statement.setInt(4, op.key().z());
            statement.setString(5, op.definitionId());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to async save functional block", error);
        }
    }

    private void applyBlockRemove(QueuedBlockRemove op) {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_BLOCK)) {
            statement.setString(1, op.key().worldId().toString());
            statement.setInt(2, op.key().x());
            statement.setInt(3, op.key().y());
            statement.setInt(4, op.key().z());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to async remove functional block", error);
        }
    }

    private void applySlotSave(QueuedSlotSave op) {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SLOT)) {
            statement.setString(1, op.key().worldId().toString());
            statement.setInt(2, op.key().x());
            statement.setInt(3, op.key().y());
            statement.setInt(4, op.key().z());
            statement.setString(5, op.slotId());
            statement.setString(6, op.item().material());
            statement.setInt(7, op.item().amount());
            statement.setString(8, encodeMetadata(op.item().metadata()));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to async save station slot", error);
        }
    }

    private void applySlotRemove(QueuedSlotRemove op) {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SLOT)) {
            statement.setString(1, op.key().worldId().toString());
            statement.setInt(2, op.key().x());
            statement.setInt(3, op.key().y());
            statement.setInt(4, op.key().z());
            statement.setString(5, op.slotId());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to async remove station slot", error);
        }
    }

    private void applySlotRemoveBlock(QueuedSlotRemoveBlock op) {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SLOTS)) {
            statement.setString(1, op.key().worldId().toString());
            statement.setInt(2, op.key().x());
            statement.setInt(3, op.key().y());
            statement.setInt(4, op.key().z());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("failed to async remove station slots", error);
        }
    }

    private List<Reservation.Claim> loadClaims(UUID instanceId) throws SQLException {
        try (PreparedStatement statement = readConnection.prepareStatement(SELECT_RESERVATIONS)) {
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
        try (PreparedStatement statement = readConnection.prepareStatement(SELECT_EFFECTS)) {
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
        return Map.copyOf(metadata);
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

    private static void closeSilent(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { }
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
            version = 2;
        }
        if (version < 3) {
            executeScript(statement, STATION_INVENTORIES_SCHEMA);
            writeSchemaVersion(connection, 3);
        }
        if (version < 4) {
            executeScript(statement, PARKED_REASON_SCHEMA);
            writeSchemaVersion(connection, 4);
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

    private record QueuedWrite(ProcessInstanceRecord record, long revision) implements QueuedOp { }
    private record QueuedDelete(UUID instanceId) implements QueuedOp { }
    private record QueuedBlockSave(BlockKey key, String definitionId) implements QueuedOp { }
    private record QueuedBlockRemove(BlockKey key) implements QueuedOp { }
    private record QueuedSlotSave(BlockKey key, String slotId, ItemSnapshot item) implements QueuedOp { }
    private record QueuedSlotRemove(BlockKey key, String slotId) implements QueuedOp { }
    private record QueuedSlotRemoveBlock(BlockKey key) implements QueuedOp { }
    private record QueuedFlush(CompletableFuture<Void> future) implements QueuedOp { }
    private record Poison(CountDownLatch done) implements QueuedOp { }

    private interface QueuedOp { }
}
