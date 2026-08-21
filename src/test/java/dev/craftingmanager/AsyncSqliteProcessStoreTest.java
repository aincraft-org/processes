package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.persistence.ProcessInstanceRecord;
import dev.craftingmanager.persistence.SqliteProcessStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncSqliteProcessStoreTest {

    @Test void saveFlushReopenPersistsRecord(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.randomUUID();
        ProcessInstanceRecord record = new ProcessInstanceRecord(instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of(), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
            store.flush();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            List<ProcessInstanceRecord> loaded = store.loadAll();
            assertEquals(1, loaded.size());
            assertEquals(instanceId, loaded.getFirst().instanceId());
        }
    }

    @Test void deleteThenSaveIsRejectedByTombstone(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.randomUUID();
        ProcessInstanceRecord first = new ProcessInstanceRecord(instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of(), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(first);
            store.flush();
            store.delete(instanceId);
            store.flush();
            ProcessInstanceRecord resurrected = new ProcessInstanceRecord(instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 2, 1, 1, ProcessState.OUTPUT_PENDING, null, List.of(), List.of(), "UNKNOWN");
            store.save(resurrected);
            store.flush();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            List<ProcessInstanceRecord> loaded = store.loadAll();
            assertEquals(0, loaded.size());
        }
    }

    @Test void saveDeleteSaveKeepsLatestTerminalState(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        UUID instanceId = UUID.randomUUID();
        ProcessInstanceRecord first = new ProcessInstanceRecord(instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of(), "UNKNOWN");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(first);
            store.delete(instanceId);
            store.flush();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            List<ProcessInstanceRecord> loaded = store.loadAll();
            assertEquals(0, loaded.size());
        }
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
}
