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

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteProcessStoreTest {
    @TempDir Path temp;

    @Test void opensDatabaseAndLoadsAfterSaveAndFlush() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.randomUUID();
        ProcessInstanceRecord record = new ProcessInstanceRecord(instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of(), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
            store.saveBlock(record.block(), record.processId());
            store.flush();
            assertEquals(1, store.loadAll().size());
            assertEquals(1, store.loadBlocks().size());
            assertEquals(0, store.loadSlots().size());
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            assertEquals(1, store.loadAll().size());
            assertEquals(1, store.loadBlocks().size());
        }
    }

    @Test void reopensDatabaseAndLoadsSavedState() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        BlockKey block = new BlockKey(UUID.fromString("22222222-2222-2222-2222-222222222222"), 1, 64, -3);
        UUID owner = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ProcessInstanceRecord record = new ProcessInstanceRecord(instanceId, block, "craftingmanager:alloy-smelt", owner, 2, 1, 7, ProcessState.RUNNING, Reservation.State.RESERVED, List.of(new Reservation.Claim(
                Reservation.Source.PLAYER_INVENTORY, 0,
                new ItemSnapshot("IRON_INGOT", 1, null), 1, "iron", ConsumptionPolicy.CONSUME)), List.of(new EffectExecution(instanceId + ":2:0:craftingmanager:item-output",
                "craftingmanager:item-output", EffectExecutionState.PENDING)), "UNKNOWN");
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

    @Test void sqlStatementsReplaceSchemaAndRejectUnsafeNames() {
        assertEquals(
                "DELETE FROM craftingmanager.reservations WHERE instance_id = ?",
                SqlStatements.load("process/delete-reservations.sql", SqliteProcessStore.SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> SqlStatements.load("../secret.sql"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatements.load("/sql/process/delete-reservations.sql"));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> SqlStatements.load("missing.sql"));
        assertTrue(missing.getMessage().contains("/sql/missing.sql"));
    }

    @Test void closeDrainsPendingWrites(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        ProcessInstanceRecord record = new ProcessInstanceRecord(UUID.randomUUID(), new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of(), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            assertEquals(1, store.loadAll().size());
        }
    }

    @Test void persistsReservationsLedgerAndBlocksAcrossReopen() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        BlockKey block = new BlockKey(UUID.fromString("22222222-2222-2222-2222-222222222222"), 1, 64, -3);
        UUID owner = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ProcessInstanceRecord record = new ProcessInstanceRecord(instanceId, block, "craftingmanager:alloy-smelt", owner, 2, 1, 7, ProcessState.RUNNING, Reservation.State.RESERVED, List.of(new Reservation.Claim(
                Reservation.Source.PLAYER_INVENTORY, 0,
                new ItemSnapshot("IRON_INGOT", 1, null), 1, "iron", ConsumptionPolicy.CONSUME)), List.of(new EffectExecution(instanceId + ":2:0:craftingmanager:item-output",
                "craftingmanager:item-output", EffectExecutionState.PENDING)), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
            store.saveBlock(block, "craftingmanager:alloy-smelter");
            store.saveSlot(block, "input", new ItemSnapshot("IRON_INGOT", 1, null));
            store.flush();
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
            assertEquals(1, store.loadSlots().size());
        }
    }

}
