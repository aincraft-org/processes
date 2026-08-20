package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.EffectExecution;
import dev.craftingmanager.api.Domain.EffectExecutionState;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.persistence.ProcessInstanceRecord;
import dev.craftingmanager.persistence.SqlStatements;
import dev.craftingmanager.persistence.SqliteProcessStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteProcessStoreTest {
    @TempDir Path temp;

    @Test void createsQualifiedCraftingManagerTables() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            assertEquals(Set.of(
                    "effect_ledger",
                    "functional_blocks",
                    "process_instances",
                    "reservations",
                    "schema_version",
                    "station_inventories"), tableNames(store));
            try (var statement = store.connection().prepareStatement(
                    "SELECT version FROM craftingmanager.schema_version WHERE schema = ?")) {
                statement.setString(1, SqliteProcessStore.SCHEMA);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(3, rows.getInt(1));
                }
            }
            try (Statement statement = store.connection().createStatement();
                 ResultSet columns = statement.executeQuery(
                         "PRAGMA craftingmanager.table_info(process_instances)")) {
                Set<String> names = new TreeSet<>();
                while (columns.next()) names.add(columns.getString("name"));
                assertTrue(names.contains("step_ticks"));
            }
        }
        String v002 = SqlStatements.load("migrations/V002__step_ticks.sql", SqliteProcessStore.SCHEMA);
        assertTrue(v002.contains("ALTER TABLE craftingmanager.process_instances ADD COLUMN step_ticks"));
        assertFalse(v002.contains("ALTER TABLE process_instances"));
        String v003 = SqlStatements.load("migrations/V003__station_inventories.sql", SqliteProcessStore.SCHEMA);
        assertTrue(v003.contains("CREATE TABLE IF NOT EXISTS craftingmanager.station_inventories"));
        assertFalse(v003.contains("CREATE TABLE IF NOT EXISTS station_inventories"));
        String ddl = SqlStatements.load("migrations/V001__initial.sql", SqliteProcessStore.SCHEMA);
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS craftingmanager.process_instances"));
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS craftingmanager.reservations"));
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS craftingmanager.effect_ledger"));
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS craftingmanager.functional_blocks"));
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS craftingmanager.schema_version"));
        assertFalse(ddl.contains("CREATE TABLE IF NOT EXISTS process_instances"));
        assertFalse(ddl.contains("CREATE TABLE IF NOT EXISTS reservations "));
        String template = SqlStatements.load("migrations/V001__initial.sql");
        assertTrue(template.contains("CREATE TABLE"));
        assertTrue(template.contains("{schema}.process_instances"));
        String source = Files.readString(
                Path.of("src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("CREATE TABLE"));
        assertTrue(source.contains("ATTACH DATABASE"));
    }

    @Test void upgradesV001DatabaseWithStepTicksColumn() throws Exception {
        Path db = temp.resolve("legacy.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:")) {
            String path = db.toAbsolutePath().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + path + "' AS craftingmanager");
                for (String part : SqlStatements.load("migrations/V001__initial.sql", "craftingmanager").split(";")) {
                    String sql = part.strip();
                    if (!sql.isEmpty()) statement.execute(sql);
                }
                statement.execute(
                        "INSERT OR REPLACE INTO craftingmanager.schema_version(schema, version) VALUES ('craftingmanager', 1)");
            }
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            ProcessInstanceRecord record = new ProcessInstanceRecord(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    new BlockKey(UUID.fromString("22222222-2222-2222-2222-222222222222"), 0, 64, 0),
                    "forge", UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    1, 0, 11, ProcessState.RUNNING, null, List.of(), List.of());
            store.save(record);
            assertEquals(11, store.loadAll().getFirst().stepTicks());
        }
    }

    @Test void sqlStatementsReplaceSchemaAndRejectUnsafeNames() {
        assertEquals(
                "DELETE FROM craftingmanager.reservations WHERE instance_id = ?",
                SqlStatements.load("process/delete-reservations.sql", SqliteProcessStore.SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> SqlStatements.load("../secret.sql"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatements.load("/sql/process/delete-reservations.sql"));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> SqlStatements.load("missing.sql"));
        assertTrue(missing.getMessage().contains("/sql/missing.sql"));
    }

    @Test void persistsInstanceReservationsLedgerAndBlocksAcrossReopen() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        BlockKey block = new BlockKey(UUID.fromString("22222222-2222-2222-2222-222222222222"), 1, 64, -3);
        UUID owner = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ProcessInstanceRecord record = new ProcessInstanceRecord(
                instanceId, block, "craftingmanager:alloy-smelt", owner, 2, 1, 7,
                ProcessState.RUNNING, Reservation.State.RESERVED,
                List.of(new Reservation.Claim(
                        Reservation.Source.PLAYER_INVENTORY, 0,
                        new ItemSnapshot("IRON_INGOT", 1, null), 1, "iron", ConsumptionPolicy.CONSUME)),
                List.of(new EffectExecution(instanceId + ":2:0:craftingmanager:item-output",
                        "craftingmanager:item-output", EffectExecutionState.PENDING)));
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
            store.saveBlock(block, "craftingmanager:alloy-smelter");
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            ProcessInstanceRecord loaded = store.loadAll().getFirst();
            assertEquals(record.instanceId(), loaded.instanceId());
            assertEquals(record.block(), loaded.block());
            assertEquals(record.processId(), loaded.processId());
            assertEquals(record.state(), loaded.state());
            assertEquals(7, loaded.stepTicks());
            assertEquals(record.claims(), loaded.claims());
            assertEquals(record.ledger(), loaded.ledger());
            assertEquals("craftingmanager:alloy-smelter", store.loadBlocks().getFirst().definitionId());
            assertEquals(block, store.loadBlocks().getFirst().key());
        }
    }

    private static Set<String> tableNames(SqliteProcessStore store) throws Exception {
        try (Statement statement = store.connection().createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM craftingmanager.sqlite_master WHERE type='table'")) {
            Set<String> names = new TreeSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }
}
