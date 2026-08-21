# Async SQLite Writes Implementation Plan

**Goal:** Move SQLite writes off the main thread while preserving the existing synchronous contract for reads and terminal-state cleanup, and add revision-checked apply-back so stale writes do not overwrite newer state.

**Architecture:** Introduce a single-writer async queue in `SqliteProcessStore`. `RuntimeEngine` continues calling `persist(instance)` on the main thread, but `persist()` enqueues a `ProcessInstanceRecord` plus its current revision instead of performing JDBC work inline. A dedicated store thread drains the queue, applies writes in order, and on revision mismatch rejects the stale write instead of applying it.

**Delete/ordering contract:** Terminal `RuntimeEngine` paths enqueue a tombstoned delete carrying the instance id and current revision instead of calling `store.delete(id)` synchronously. The single writer must process delete entries after any earlier queued save for the same instance id, or the writer must compare the queued revision against the row and delete only if they match. This prevents an older in-flight save from resurrecting a terminal row.

**Connection ownership:** The SQLite `Connection` is owned solely by the single writer thread. `RuntimeEngine` and any test callers must never use the shared connection after `open()`. `RuntimeEngine` interacts only through the `ProcessStore` interface; the `SqliteProcessStore` exposes no connection access to engine code. Reads share the writer thread only via explicit handoff or a separate read-only connection if needed; this plan keeps reads synchronous but writer-owned.

**Shutdown ordering:** `RuntimeEngine.shutdown()` first calls `store.flush()`, then closes the store. `SqliteProcessStore.close()` sets a draining flag, interrupts the writer, awaits queue drain, and only then closes the connection. No prepared statement is used concurrently with writer execution.

**Tech Stack:** Java 21, JUnit 5, Gradle, SQLite JDBC.

## Global Constraints

- Bukkit mutations must stay on the main thread. Only JDBC I/O may move off-thread.
- Every SQL object remains schema-qualified `craftingmanager.<table>`.
- Applied completion effects must never be rerun.
- Parked instances remain dismiss-only; do not change cancel/dismiss behavior in this plan.
- `ProcessFinishedEvent` remains non-success-only; do not add success-only events.
- Preserve existing `ProcessStore` interface shape for callers; do not force `RuntimeEngine` to expose async APIs.
- Reads must still reflect the latest committed state.
- `RuntimeEngine.persist(instance)` must remain the single persistence boundary; callers do not change.

---

### Task 1: Async write queue and revision-checked apply-back in `SqliteProcessStore`

**Files:**
- Modify: `src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java`
- Modify: `src/main/java/dev/craftingmanager/persistence/ProcessStore.java`
- Test: `src/test/java/dev/craftingmanager/AsyncSqliteProcessStoreTest.java`

**Interfaces:**
- Consumes: existing `save`, `delete`, `saveBlock`, `removeBlock`, `saveSlot`, `removeSlot`, `removeSlots`
- Produces: async `save` queue, `flush`, `close`, revision-checked upsert

- [ ] **Step 1: Write the failing test**

```java
class AsyncSqliteProcessStoreTest {
    @Test void asyncSaveWritesAfterDrainAndRespectsRevision() throws Exception {
        Path db = Files.createTempFile("craftingmanager", ".db");
        ProcessInstanceRecord record = new ProcessInstanceRecord(
                UUID.randomUUID(), new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
                UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of());
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
            store.flush();

            ProcessInstanceRecord loaded = store.loadAll().getFirst();
            assertEquals(record.instanceId(), loaded.instanceId());
            assertEquals(1, loaded.revision());
        }
    }

    @Test void staleRevisionWriteDoesNotOverwriteNewerState() throws Exception {
        Path db = Files.createTempFile("craftingmanager", ".db");
        UUID instanceId = UUID.randomUUID();
        ProcessInstanceRecord first = new ProcessInstanceRecord(
                instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
                UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of());
        ProcessInstanceRecord newer = new ProcessInstanceRecord(
                instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
                UUID.randomUUID(), 2, 1, 1, ProcessState.OUTPUT_PENDING, null, List.of(), List.of());
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(first);
            store.save(newer);
            store.flush();

            ProcessInstanceRecord loaded = store.loadAll().getFirst();
            assertEquals(2, loaded.revision());
            assertEquals(ProcessState.OUTPUT_PENDING, loaded.state());
        }
    }

    @Test void closeDrainsPendingWrites() throws Exception {
        Path db = Files.createTempFile("craftingmanager", ".db");
        ProcessInstanceRecord record = new ProcessInstanceRecord(
                UUID.randomUUID(), new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
                UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of());
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            store.save(record);
        }

        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            assertEquals(1, store.loadAll().size());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.AsyncSqliteProcessStoreTest' --no-daemon`
Expected: FAIL with compilation errors for missing async queue API.

- [ ] **Step 3: Add queue records and drain contract to `ProcessStore.java`**

```java
public record QueuedWrite(ProcessInstanceRecord record, long revision) {}

interface SqliteProcessStoreApi extends ProcessStore {
    void save(ProcessInstanceRecord record);
    void delete(UUID instanceId);
    void flush();
}
```

- [ ] **Step 4: Implement async queue, tombstoned delete, and close gate in `SqliteProcessStore.java`**

```java
private final BlockingQueue<QueuedOp> writeQueue = new LinkedBlockingQueue<>();
private final Thread writer;
private volatile boolean accepting = true;
private final Object closeLock = new Object();

private SqliteProcessStore(Connection connection) {
    this.connection = connection;
    this.writer = new Thread(this::drain, "craftingmanager-sqlite-writer");
    this.writer.setDaemon(true);
    this.writer.start();
}

@Override public void save(ProcessInstanceRecord record) {
    Objects.requireNonNull(record);
    if (!accepting) throw new IllegalStateException("store is closing");
    writeQueue.offer(new QueuedWrite(record, record.revision()));
}

@Override public void delete(UUID instanceId) {
    Objects.requireNonNull(instanceId);
    if (!accepting) throw new IllegalStateException("store is closing");
    writeQueue.offer(new QueuedDelete(instanceId));
}

@Override public void flush() {
    awaitDrain();
}

@Override public void close() {
    accepting = false;
    awaitDrain();
    synchronized (closeLock) {
        try { connection.close(); } catch (SQLException ignored) {}
    }
}

private void awaitDrain() {
    while (!writeQueue.isEmpty() || writer.getState() != Thread.State.TERMINATED) {
        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
    }
}

private void drain() {
    try {
        while (accepting || !writeQueue.isEmpty()) {
            QueuedOp op = writeQueue.poll();
            if (op == null) {
                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                continue;
            }
            if (op instanceof QueuedWrite write) applySave(write);
            else if (op instanceof QueuedDelete delete) applyDelete(delete);
        }
    } finally {
        // writer is done; ensure no further JDBC use
    }
}

private void applySave(QueuedWrite write) {
    try {
        connection.setAutoCommit(false);
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
        // existing save body using write.record()
        connection.commit();
    } catch (SQLException error) {
        rollback();
        throw new IllegalStateException("failed to async save process instance", error);
    } finally {
        restoreAutoCommit();
    }
}

private void applyDelete(QueuedDelete delete) {
    try (PreparedStatement statement = connection.prepareStatement(DELETE_INSTANCE)) {
        statement.setString(1, delete.instanceId().toString());
        statement.executeUpdate();
        connection.commit();
    } catch (SQLException error) {
        rollback();
        throw new IllegalStateException("failed to async delete process instance", error);
    } finally {
        restoreAutoCommit();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.AsyncSqliteProcessStoreTest' --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java src/main/java/dev/craftingmanager/persistence/ProcessStore.java src/test/java/dev/craftingmanager/AsyncSqliteProcessStoreTest.java
git commit -m "feat: async sqlite writes with revision-checked apply-back"
```

---

### Task 2: Integrate async writes into `RuntimeEngine`

**Files:**
- Modify: `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`
- Modify: `src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java`
- Test: `src/test/java/dev/craftingmanager/ProcessAsyncPersistenceTest.java`

**Interfaces:**
- Consumes: `SqliteProcessStoreApi`, `QueuedWrite`
- Produces: async `persist()` calls from `RuntimeEngine`

- [ ] **Step 1: Write the failing test**

```java
class ProcessAsyncPersistenceTest {
    @Test void persistEnqueuesWriteAndSurvivesRestart() throws Exception {
        Path db = Files.createTempFile("craftingmanager", ".db");
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerEffectHandler(new Handler());
            engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "output")));
            BlockKey block = new BlockKey(UUID.randomUUID(), 1, 64, 1);
            var started = engine.start(block, "job", UUID.randomUUID());
            assertTrue(started.started());
            engine.advance(started.instanceId()).toCompletableFuture().join();
            store.flush();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            List<ProcessInstanceRecord> records = store.loadAll();
            assertEquals(1, records.size());
            assertEquals(ProcessState.COMPLETED, records.getFirst().state());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessAsyncPersistenceTest' --no-daemon`
Expected: FAIL with `RuntimeEngine` still calling `store.save()` synchronously.

- [ ] **Step 3: Replace terminal `store.delete(id)` with tombstoned enqueue in `RuntimeEngine`**

```java
private void enqueueDelete(UUID instanceId) {
    if (store instanceof SqliteProcessStoreApi api) {
        api.delete(instanceId);
    } else {
        store.delete(instanceId);
    }
}
```

- [ ] **Step 4: Update `RuntimeEngine.persist()` to enqueue instead of direct save**

```java
private void persist(Instance instance) {
    if (terminal(instance.state)) {
        enqueueDelete(instance.id);
        return;
    }
    List<EffectExecution> ledger = new ArrayList<>();
    for (int i = 0; i < instance.definition.effects().size(); i++) {
        CompletionEffect effect = instance.definition.effects().get(i);
        ledger.add(new EffectExecution(effectId(instance, i), effect.type(),
                instance.ledger.getOrDefault(i, EffectExecutionState.PENDING)));
    }
    enqueueSave(new ProcessInstanceRecord(
            instance.id, instance.block, instance.definition.id(), instance.owner, instance.revision,
            instance.step, instance.stepTicks, instance.state, instance.reservationState, instance.claims, ledger));
}

private void enqueueSave(ProcessInstanceRecord record) {
    if (store instanceof SqliteProcessStoreApi api) {
        api.save(record);
    } else {
        store.save(record);
    }
}
```

- [ ] **Step 5: Replace persistence calls with enqueue paths in `RuntimeEngine` except reads**

Replace in `start`, `tickRunning`, `applyEffects`, `hydrate`/`restore`, `shutdown`, `cancel`, `dismiss`, `unregisterHandler` active paths. Reads (`loadAll`, `loadBlocks`, `loadSlots`) remain synchronous. Keep terminal cleanup as enqueued delete.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessAsyncPersistenceTest' --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessAsyncPersistenceTest.java
git commit -m "feat: route runtime persists through async sqlite queue"
```

---

### Task 3: Tests for revision conflicts and shutdown ordering

**Files:**
- Modify: `src/test/java/dev/craftingmanager/AsyncSqliteProcessStoreTest.java`
- Modify: `src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java`

**Interfaces:**
- Consumes: `QueuedWrite`, `flush`, `close`
- Produces: revision-mismatch test, shutdown test

- [ ] **Step 1: Write the failing test**

```java
@Test void revisionMismatchSkipsStaleWrite() throws Exception {
    Path db = Files.createTempFile("craftingmanager", ".db");
    UUID instanceId = UUID.randomUUID();
    ProcessInstanceRecord first = new ProcessInstanceRecord(
            instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
            UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of());
    ProcessInstanceRecord stale = new ProcessInstanceRecord(
            instanceId, new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
            UUID.randomUUID(), 1, 0, 1, ProcessState.NEEDS_PROVIDER_ACTION, null, List.of(), List.of());
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        store.save(first);
        store.save(stale);
        store.flush();
    }
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        ProcessInstanceRecord loaded = store.loadAll().getFirst();
        assertEquals(1, loaded.revision());
        assertEquals(ProcessState.RUNNING, loaded.state());
    }
}

@Test void closeWithoutFlushStillDrainsQueue() throws Exception {
    Path db = Files.createTempFile("craftingmanager", ".db");
    ProcessInstanceRecord record = new ProcessInstanceRecord(
            UUID.randomUUID(), new BlockKey(UUID.randomUUID(), 1, 64, 1), "job",
            UUID.randomUUID(), 1, 0, 0, ProcessState.RUNNING, null, List.of(), List.of());
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        store.save(record);
    }
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        assertEquals(1, store.loadAll().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.AsyncSqliteProcessStoreTest' --no-daemon`
Expected: FAIL with missing revision guard or missing `flush` draining.

- [ ] **Step 3: Implement revision guard and tombstoned delete ordering in writer**

```java
private void applySave(QueuedWrite write) {
    try {
        connection.setAutoCommit(false);
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
        // existing save body using write.record()
        connection.commit();
    } catch (SQLException error) {
        rollback();
        throw new IllegalStateException("failed to async save process instance", error);
    } finally {
        restoreAutoCommit();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.AsyncSqliteProcessStoreTest' --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java src/test/java/dev/craftingmanager/AsyncSqliteProcessStoreTest.java
git commit -m "test: async sqlite revision conflict and shutdown ordering"
```

---

### Task 4: Documentation and final verification

**Files:**
- Modify: `docs/living-spec.md`
- Test: full suite

- [ ] **Step 1: Update `docs/living-spec.md` Next section**

```markdown
## Next

- [x] Public cancel/dismiss and progress queries on the public API.
- [x] Off-main-thread SQLite writes with revision-checked apply-back.
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add docs/living-spec.md
git commit -m "docs: mark async sqlite writes shipped in living-spec"
```
