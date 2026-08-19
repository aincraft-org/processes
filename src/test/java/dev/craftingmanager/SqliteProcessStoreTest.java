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
                    "schema_version"), tableNames(store));
        }
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
                instanceId, block, "craftingmanager:alloy-smelt", owner, 2, 1,
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
            assertEquals(record.claims(), loaded.claims());
            assertEquals(record.ledger(), loaded.ledger());
            assertEquals("craftingmanager:alloy-smelter", store.loadBlocks().getFirst().definitionId());
            assertEquals(block, store.loadBlocks().getFirst().key());
        }
    }

    private static Set<String> tableNames(SqliteProcessStore store) throws Exception {
        try (Connection connection = store.connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM craftingmanager.sqlite_master WHERE type='table'")) {
            Set<String> names = new TreeSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }
}
