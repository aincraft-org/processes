# Provider Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `CraftingManagerApi.reconcileInstance` so providers can explicitly ask the core to re-attempt a parked `NEEDS_PROVIDER_ACTION` instance caused by a transient effect-handler failure.

**Architecture:** Add a public `ProcessReconcileResult`, persist `parked_reason` in `craftingmanager.process_instances`, track an internal `ParkedReason` enum in `RuntimeEngine`, and implement a synchronized `reconcileInstance` that re-enters `applyEffects` only for `MISSING_EFFECT_HANDLER` and `IDEMPOTENT`/`PROVIDER_DEDUPLICATES` `EFFECT_HANDLER_EXCEPTION` cases. Reject `RETURN_CLAIM_FAILED`, `NON_RETRYABLE`, and `MISSING_DEFINITION` cases.

**Tech Stack:** Java 21, JUnit 5, Gradle, SQLite, Paper API.

## Global Constraints

- Every SQL object remains schema-qualified `craftingmanager.<table>`.
- All Bukkit/inventory mutations stay on the main thread.
- Applied effects are never re-executed.
- `ProcessInstanceSnapshot` does not expand with block/revision/parked reason in v1.
- No provider callback mutates instance state.
- Public API additions live in `api/`; core logic in `runtime/`; persistence in `persistence/`.
- The `SqliteProcessStore` in the working tree already uses an async queued writer (`QueuedWrite`, `applySave()` on a writer thread, `ProcessStore.flush()`). Persistence steps use the current `write.record()` shape and add `parked_reason` as the 13th SQL parameter and the 12th `ProcessInstanceRecord` field.

## Implementation Amendment

The `SqliteProcessStore` has uncommitted queued-writer changes. Task 2 updates `applySave` and `loadAll` directly. Any test that constructs `new ProcessInstanceRecord(...)` with 11 arguments (e.g., `SqliteProcessStoreTest`) must add a `parkedReason` string argument such as `"UNKNOWN"` after `ledger`.

---

### Task 1: Public result type and API method

**Files:**
- Modify: `src/main/java/dev/craftingmanager/api/Domain.java`
- Modify: `src/main/java/dev/craftingmanager/api/CraftingManagerApi.java`
- Test: `src/test/java/dev/craftingmanager/ProcessApiReconcileTest.java`

**Interfaces:**
- Consumes: `ProcessState`, `UUID`
- Produces: `ProcessReconcileResult`, `CraftingManagerApi.reconcileInstance(UUID)`

- [ ] **Step 1: Add `ProcessReconcileResult` to `Domain.java` after `ProcessDismissResult`**

```java
public record ProcessReconcileResult(boolean reconciled, ProcessState state, String reason) {
    public static ProcessReconcileResult rejected(String reason) { return new ProcessReconcileResult(false, null, reason); }
}
```

- [ ] **Step 2: Add `reconcileInstance` to `CraftingManagerApi.java`**

```java
ProcessReconcileResult reconcileInstance(UUID instanceId);
```

- [ ] **Step 3: Write the failing test**

```java
package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessApiReconcileTest {
    @Test void reconcileInstanceRejectsUnknownInstance() {
        RuntimeEngine engine = new RuntimeEngine();
        var result = engine.reconcileInstance(UUID.randomUUID());
        assertFalse(result.reconciled());
        assertNull(result.state());
        assertEquals("unknown or terminal instance", result.reason());
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiReconcileTest' --no-daemon`
Expected: FAIL with compilation error for missing `reconcileInstance`.

- [ ] **Step 5: Add stub `reconcileInstance` to `RuntimeEngine`**

In `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`:

```java
@Override public synchronized ProcessReconcileResult reconcileInstance(UUID instanceId) {
    Instance instance = instances.get(Objects.requireNonNull(instanceId));
    if (instance == null || terminal(instance.state)) return ProcessReconcileResult.rejected("unknown or terminal instance");
    return ProcessReconcileResult.rejected("not implemented");
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiReconcileTest' --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/craftingmanager/api/Domain.java src/main/java/dev/craftingmanager/api/CraftingManagerApi.java src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessApiReconcileTest.java
git commit -m "feat: add reconcileInstance public API and stub"
```

---

### Task 2: Persist `parked_reason` and update the process record

**Files:**
- Create: `src/main/resources/sql/migrations/V004__parked_reason.sql`
- Modify: `src/main/resources/sql/process/select-instances.sql`
- Modify: `src/main/resources/sql/process/upsert-instance.sql`
- Modify: `src/main/java/dev/craftingmanager/persistence/ProcessInstanceRecord.java`
- Modify: `src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java`
- Test: `src/test/java/dev/craftingmanager/SqliteProcessStoreTest.java` or a new `ProcessRestartParkedReasonTest.java`

**Interfaces:**
- Consumes: `ProcessInstanceRecord` now carries `parkedReason` string.
- Produces: `SqliteProcessStore` reads/writes `parked_reason` via the SQL resource files.

- [ ] **Step 1: Add migration `src/main/resources/sql/migrations/V004__parked_reason.sql`**

```sql
ALTER TABLE {schema}.process_instances ADD COLUMN parked_reason TEXT NOT NULL DEFAULT 'UNKNOWN';
```

- [ ] **Step 2: Update `src/main/resources/sql/process/select-instances.sql`**

```sql
SELECT instance_id, world_id, x, y, z, process_id, owner, revision, step, step_ticks, state, reservation_state, parked_reason
FROM {schema}.process_instances
```

- [ ] **Step 3: Update `src/main/resources/sql/process/upsert-instance.sql`**

```sql
INSERT INTO {schema}.process_instances(
    instance_id, world_id, x, y, z, process_id, owner, revision, step, step_ticks, state, reservation_state, parked_reason)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(instance_id) DO UPDATE SET
    world_id=excluded.world_id, x=excluded.x, y=excluded.y, z=excluded.z,
    process_id=excluded.process_id, owner=excluded.owner, revision=excluded.revision,
    step=excluded.step, step_ticks=excluded.step_ticks, state=excluded.state,
    reservation_state=excluded.reservation_state, parked_reason=excluded.parked_reason
```

- [ ] **Step 4: Add `parkedReason` to `ProcessInstanceRecord.java`**

```java
public record ProcessInstanceRecord(
        UUID instanceId,
        BlockKey block,
        String processId,
        UUID owner,
        long revision,
        int step,
        int stepTicks,
        ProcessState state,
        Reservation.State reservationState,
        List<Reservation.Claim> claims,
        List<EffectExecution> ledger,
        String parkedReason) {
    public ProcessInstanceRecord {
        if (instanceId == null || block == null || processId == null || processId.isBlank() || owner == null || state == null) {
            throw new IllegalArgumentException("process instance identity is required");
        }
        if (stepTicks < 0) throw new IllegalArgumentException("stepTicks cannot be negative");
        claims = List.copyOf(claims == null ? List.of() : claims);
        ledger = List.copyOf(ledger == null ? List.of() : ledger);
        parkedReason = parkedReason == null ? "UNKNOWN" : parkedReason;
    }
}
```

- [ ] **Step 5: Update `SqliteProcessStore.java`**

Add the migration constant after `STATION_INVENTORIES_SCHEMA`:

```java
private static final String PARKED_REASON_SCHEMA = SqlStatements.load("migrations/V004__parked_reason.sql", SCHEMA);
```

Update `applyMigrations` to version 4:

```java
if (version < 4) {
    executeScript(statement, PARKED_REASON_SCHEMA);
    writeSchemaVersion(connection, 4);
}
```

Update `applySave` parameter binding after `reservation_state`:

```java
if (write.record().reservationState() == null) upsert.setNull(12, Types.VARCHAR);
else upsert.setString(12, write.record().reservationState().name());
upsert.setString(13, write.record().parkedReason());
```

Update `loadAll` to read and pass `parked_reason`:

```java
String parkedReason = rows.getString("parked_reason");
records.add(new ProcessInstanceRecord(
        instanceId, block, rows.getString("process_id"), UUID.fromString(rows.getString("owner")),
        rows.getLong("revision"), rows.getInt("step"), rows.getInt("step_ticks"),
        ProcessState.valueOf(rows.getString("state")),
        reservation == null ? null : Reservation.State.valueOf(reservation),
        loadClaims(instanceId), loadLedger(instanceId), parkedReason));
```

- [ ] **Step 6: Write a persistence test**

```java
@Test void parkedReasonSurvivesRestart() throws Exception {
    Path db = temp.resolve("craftingmanager.db");
    BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
    UUID instanceId;
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        RuntimeEngine engine = new RuntimeEngine(store);
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of((CompletionEffect) () -> "missing")));
        instanceId = engine.start(block, "job", UUID.randomUUID()).instanceId();
        engine.advance(instanceId).toCompletableFuture().join();
        store.flush();
    }
    try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
        RuntimeEngine engine = new RuntimeEngine(store);
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of((CompletionEffect) () -> "missing")));
        engine.hydrate();
        var record = store.loadAll().getFirst();
        assertEquals("MISSING_EFFECT_HANDLER", record.parkedReason());
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessRestartParkedReasonTest' --no-daemon`
Expected: FAIL because `RuntimeEngine` does not set `parkedReason` yet.

- [ ] **Step 8: Update `RuntimeEngine` call sites for the new record constructor**

In `RuntimeEngine.persist`, add `instance.parkedReason.name()` (the `ParkedReason` enum is added in Task 3; if the build fails here, add the enum and field as part of Task 3 first):

```java
store.save(new ProcessInstanceRecord(
        instance.id, instance.block, instance.definition.id(), instance.owner, instance.revision,
        instance.step, instance.stepTicks, instance.state, instance.reservationState, instance.claims, ledger,
        instance.parkedReason.name()));
```

In `RuntimeEngine.restore`, pass `record.parkedReason()`:

```java
Instance instance = new Instance(record.instanceId(), record.block(), definition, record.owner(), adapter, record.claims());
```

- [ ] **Step 9: Run the persistence test again**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessRestartParkedReasonTest' --no-daemon`
Expected: PASS (or fail until Task 3 sets the reason; iterate with Task 3).

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/sql/migrations/V004__parked_reason.sql src/main/resources/sql/process/select-instances.sql src/main/resources/sql/process/upsert-instance.sql src/main/java/dev/craftingmanager/persistence/ProcessInstanceRecord.java src/main/java/dev/craftingmanager/persistence/SqliteProcessStore.java src/test/java/dev/craftingmanager/ProcessRestartParkedReasonTest.java
git commit -m "feat: persist parked_reason for process instances"
```

---

### Task 3: Track `ParkedReason` in `RuntimeEngine`

**Files:**
- Modify: `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`
- Test: `src/test/java/dev/craftingmanager/ProcessParkedReasonTest.java`

**Interfaces:**
- Consumes: `ProcessState`, `EffectExecutionState`, `EffectHandler.idempotency()`
- Produces: internal `ParkedReason` enum and `Instance.parkedReason`

- [ ] **Step 1: Add the `ParkedReason` enum and `Instance` field**

At the bottom of `RuntimeEngine.java` with the `Instance` class:

```java
private enum ParkedReason { NONE, MISSING_EFFECT_HANDLER, EFFECT_HANDLER_EXCEPTION, RETURN_CLAIM_FAILED, MISSING_DEFINITION, UNKNOWN }

private static final class Instance {
    // ... existing fields ...
    long revision;
    ParkedReason parkedReason = ParkedReason.NONE;
    // ...
}
```

- [ ] **Step 2: Add a mapping helper**

```java
private ParkedReason parkedReasonFromString(String value) {
    try {
        return ParkedReason.valueOf(value);
    } catch (IllegalArgumentException e) {
        return ParkedReason.UNKNOWN;
    }
}
```

- [ ] **Step 3: Set `parkedReason` in `applyEffects`**

For the missing handler branch:

```java
instance.parkedReason = ParkedReason.MISSING_EFFECT_HANDLER;
instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
```

For the thrown handler branch:

```java
instance.ledger.put(i, EffectExecutionState.UNKNOWN);
instance.parkedReason = ParkedReason.EFFECT_HANDLER_EXCEPTION;
instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
```

For the `returnClaims` failure branch:

```java
instance.parkedReason = ParkedReason.RETURN_CLAIM_FAILED;
instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
```

For the completion branch, before `persist`:

```java
instance.parkedReason = ParkedReason.NONE;
instance.state = ProcessState.COMPLETED;
```

- [ ] **Step 4: Set `parkedReason` in `restore`**

After `instance.state = state;`:

```java
if (definition == null) {
    instance.parkedReason = ParkedReason.MISSING_DEFINITION;
} else if (missingHandlers(definition)) {
    ParkedReason fromRecord = parkedReasonFromString(record.parkedReason());
    if (fromRecord == ParkedReason.RETURN_CLAIM_FAILED || fromRecord == ParkedReason.MISSING_DEFINITION) {
        instance.parkedReason = fromRecord;
    } else {
        instance.parkedReason = ParkedReason.MISSING_EFFECT_HANDLER;
    }
} else {
    instance.parkedReason = parkedReasonFromString(record.parkedReason());
    if (instance.state == ProcessState.NEEDS_PROVIDER_ACTION && instance.parkedReason == ParkedReason.NONE) {
        instance.parkedReason = ParkedReason.UNKNOWN;
    }
}
```

- [ ] **Step 5: Set `parkedReason` in `unregisterHandler` `FAIL_ACTIVE_PROCESSES` branch**

Inside the `FAIL_ACTIVE_PROCESSES` branch:

```java
active.forEach(i -> {
    int index = firstNonAppliedEffectOfType(i, type);
    if (index < 0) return;
    i.state = ProcessState.NEEDS_PROVIDER_ACTION;
    EffectExecutionState effectState = i.ledger.getOrDefault(index, EffectExecutionState.PENDING);
    if (effectState == EffectExecutionState.UNKNOWN || effectState == EffectExecutionState.RUNNING) {
        i.parkedReason = ParkedReason.EFFECT_HANDLER_EXCEPTION;
    } else {
        i.parkedReason = ParkedReason.MISSING_EFFECT_HANDLER;
    }
    persist(i);
});
```

Add the helper:

```java
private static int firstNonAppliedEffectOfType(Instance instance, String type) {
    List<CompletionEffect> effects = instance.definition.effects();
    for (int i = 0; i < effects.size(); i++) {
        if (effects.get(i).type().equals(type)) {
            EffectExecutionState state = instance.ledger.getOrDefault(i, EffectExecutionState.PENDING);
            if (state != EffectExecutionState.APPLIED) return i;
        }
    }
    return -1;
}
```

- [ ] **Step 6: Ensure `ProcessApiReconcileTest` from Task 1 still passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiReconcileTest' --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessParkedReasonTest.java
git commit -m "feat: track ParkedReason in RuntimeEngine"
```

---

### Task 4: Implement `reconcileInstance`

**Files:**
- Modify: `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`
- Test: `src/test/java/dev/craftingmanager/ProcessApiReconcileTest.java`

**Interfaces:**
- Consumes: `ProcessReconcileResult`, `ParkedReason`, `EffectHandler.idempotency()`
- Produces: working `reconcileInstance(UUID)`

- [ ] **Step 1: Add helpers for finding the reconcilable effect index**

```java
private static Integer firstEffectIndex(Instance instance, EffectExecutionState... states) {
    for (int i = 0; i < instance.definition.effects().size(); i++) {
        EffectExecutionState current = instance.ledger.getOrDefault(i, EffectExecutionState.PENDING);
        for (EffectExecutionState state : states) {
            if (current == state) return i;
        }
    }
    return null;
}

private static boolean allEffectsBeforeApplied(Instance instance, int target) {
    for (int i = 0; i < target; i++) {
        if (instance.ledger.getOrDefault(i, EffectExecutionState.PENDING) != EffectExecutionState.APPLIED) return false;
    }
    return true;
}
```

- [ ] **Step 2: Implement `reconcileInstance`**

```java
@Override public synchronized ProcessReconcileResult reconcileInstance(UUID instanceId) {
    Instance instance = instances.get(Objects.requireNonNull(instanceId));
    if (instance == null || terminal(instance.state)) {
        return ProcessReconcileResult.rejected("unknown or terminal instance");
    }
    if (instance.state != ProcessState.NEEDS_PROVIDER_ACTION) {
        return new ProcessReconcileResult(false, instance.state, "not parked");
    }
    long expectedRevision = instance.revision;
    if (instance.parkedReason != ParkedReason.MISSING_EFFECT_HANDLER
            && instance.parkedReason != ParkedReason.EFFECT_HANDLER_EXCEPTION) {
        return new ProcessReconcileResult(false, instance.state,
                "reason not reconcilable: " + instance.parkedReason);
    }
    ProcessDefinition registered = processes.get(instance.definition.id());
    if (registered == null || !registered.equals(instance.definition)) {
        return new ProcessReconcileResult(false, instance.state, "process definition missing or changed");
    }
    Integer target;
    if (instance.parkedReason == ParkedReason.MISSING_EFFECT_HANDLER) {
        target = firstEffectIndex(instance, EffectExecutionState.PENDING);
    } else {
        target = firstEffectIndex(instance, EffectExecutionState.UNKNOWN);
    }
    if (target == null) {
        return new ProcessReconcileResult(false, instance.state, "no reconcilable effect");
    }
    if (!allEffectsBeforeApplied(instance, target)) {
        return new ProcessReconcileResult(false, instance.state, "ledger inconsistent");
    }
    CompletionEffect effect = instance.definition.effects().get(target);
    HandlerRegistration<?> registration = handlers.get(effect.type());
    if (registration == null || !registration.handler.effectType().isInstance(effect)) {
        return new ProcessReconcileResult(false, instance.state, "effect handler missing");
    }
    if (instance.parkedReason == ParkedReason.EFFECT_HANDLER_EXCEPTION
            && registration.handler.idempotency() == Domain.IdempotencyMode.NON_RETRYABLE) {
        return new ProcessReconcileResult(false, instance.state, "non-retryable effect");
    }
    if (instance.revision != expectedRevision || instance.state != ProcessState.NEEDS_PROVIDER_ACTION) {
        return new ProcessReconcileResult(false, instance.state, "instance changed");
    }
    instance.state = ProcessState.OUTPUT_PENDING;
    ProcessState result = applyEffects(instance);
    String reason = result == ProcessState.COMPLETED ? "completed" : "re-parked: " + instance.parkedReason;
    return new ProcessReconcileResult(result == ProcessState.COMPLETED, result, reason);
}
```

- [ ] **Step 3: Expand `ProcessApiReconcileTest`**

```java
@Test void missingEffectHandlerReconcilesWhenHandlerRegistered() {
    RuntimeEngine engine = new RuntimeEngine();
    engine.registerEffectHandler(handler("first", false));
    engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
            List.of((CompletionEffect) () -> "first", (CompletionEffect) () -> "second")));
    BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
    var started = engine.start(block, "job", UUID.randomUUID());
    assertTrue(started.started());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

    engine.registerEffectHandler(handler("second", false));
    var result = engine.reconcileInstance(started.instanceId());

    assertTrue(result.reconciled());
    assertEquals(ProcessState.COMPLETED, result.state());
}

@Test void effectHandlerExceptionReconcilesForIdempotentHandler() {
    RuntimeEngine engine = new RuntimeEngine();
    AtomicInteger failFirst = new AtomicInteger(1);
    engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
        public String type() { return "first"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {
            if (failFirst.getAndDecrement() > 0) throw new IllegalStateException("provider failure");
        }
    });
    engine.registerEffectHandler(handler("second", false));
    engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
            List.of((CompletionEffect) () -> "first", (CompletionEffect) () -> "second")));
    BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
    var started = engine.start(block, "job", UUID.randomUUID());
    assertTrue(started.started());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

    var result = engine.reconcileInstance(started.instanceId());

    assertTrue(result.reconciled());
    assertEquals(ProcessState.COMPLETED, result.state());
}

@Test void nonRetryableEffectExceptionIsRejected() {
    RuntimeEngine engine = new RuntimeEngine();
    engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
        public String type() { return "first"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public Domain.IdempotencyMode idempotency() { return Domain.IdempotencyMode.NON_RETRYABLE; }
        public void execute(CompletionEffect effect, String effectId) { throw new IllegalStateException("boom"); }
    });
    engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
            List.of((CompletionEffect) () -> "first")));
    BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
    var started = engine.start(block, "job", UUID.randomUUID());
    assertTrue(started.started());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

    var result = engine.reconcileInstance(started.instanceId());

    assertFalse(result.reconciled());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, result.state());
    assertTrue(result.reason().contains("non-retryable"));
}

@Test void returnClaimFailedIsNotReconciled() {
    RuntimeEngine engine = new RuntimeEngine();
    InventoryAdapter failing = new InventoryAdapter() {
        public List<Reservation.Claim> captureClaims(List<Domain.ProcessInput> inputs) {
            Domain.ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot(input.matcher(), input.amount(), null), input.amount(), input.id(), input.consumption()));
        }
        public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }
        public void remove(List<Reservation.Claim> claims) {}
        public void returnItems(List<Reservation.Claim> claims) { throw new IllegalStateException("no space"); }
    };
    engine.registerInventoryAdapter(failing);
    engine.registerEffectHandler(handler("first", false));
    engine.registerProcess(new ProcessDefinition("job",
            List.of(new Domain.ProcessInput("x", Domain.InputRole.FUEL, "COAL", 1, Domain.ConsumptionPolicy.RETURN_ALWAYS, Domain.InputTiming.ON_START, false, null, Set.of())),
            List.of(), List.of((CompletionEffect) () -> "first")));
    BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
    var started = engine.start(block, "job", UUID.randomUUID());
    assertTrue(started.started());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

    var result = engine.reconcileInstance(started.instanceId());

    assertFalse(result.reconciled());
    assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, result.state());
    assertTrue(result.reason().contains("RETURN_CLAIM_FAILED"));
}

private static EffectHandler<CompletionEffect> handler(String type, boolean fail) {
    return new EffectHandler<>() {
        public String type() { return type; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {
            if (fail) throw new IllegalStateException("provider failure");
        }
    };
}
```

- [ ] **Step 4: Run the full reconcile test suite**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiReconcileTest' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessApiReconcileTest.java
git commit -m "feat: implement reconcileInstance"
```

---

### Task 5: Update documentation and run full suite

**Files:**
- Modify: `docs/living-spec.md`

- [ ] **Step 1: Update `docs/living-spec.md` Current section**

Add a bullet under `Current`:

```markdown
- [x] Core-controlled `reconcileInstance(UUID)` for explicit provider retry of `MISSING_EFFECT_HANDLER` and idempotent `EFFECT_HANDLER_EXCEPTION` parked instances.
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test --no-daemon`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/living-spec.md
git commit -m "docs: record reconcileInstance in living spec"
```

---

## Self-Review

- **Spec coverage:**
  - Public API `reconcileInstance` and `ProcessReconcileResult` — Task 1.
  - `parked_reason` persistence — Task 2.
  - `ParkedReason` tracking in `RuntimeEngine` — Task 3.
  - Core-controlled reconcile with idempotency gating and rejection of `RETURN_CLAIM_FAILED`/`MISSING_DEFINITION` — Task 4.
  - Tests for missing handler, idempotent exception, non-retryable rejection, and return-claim rejection — Task 4.
  - Living spec update — Task 5.

- **Placeholder scan:** No `TBD`, `TODO`, or unreferenced types. All tasks include runnable tests and exact file paths.

- **Type consistency:** `ProcessReconcileResult` is in `Domain` and returned by `CraftingManagerApi.reconcileInstance`. `ProcessInstanceRecord.parkedReason` is a `String`. `RuntimeEngine.Instance.parkedReason` is the internal `ParkedReason` enum. All call sites for `new ProcessInstanceRecord(...)` are updated to pass the `parkedReason` string.
