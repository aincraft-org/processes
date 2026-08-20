# Public API Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose safe instance control, progress observation, and observer registration on `CraftingManagerApi` without changing the existing internal policy for parked instances.

**Architecture:** Add small immutable snapshot/result types on the public API, promote the engine’s existing state/ledger/block lookups behind them, and add an observer registration method. Keep all Bukkit/mutable state inside `RuntimeEngine`; the API only returns read-only views. Parked instances remain dismiss-only; cancel remains a separate explicit action for running instances.

**Prerequisite:** The parked-instance invalidation safety fix (`releaseInstance`/`dismiss`, `invalidateBlock` routing, and `unregisterHandler`/`clear()` rewiring) is already present in the working tree and was included in Task 1’s commit. Task 2 builds on top of that behavior; do not revert it.

**Tech Stack:** Java 21, JUnit 5, Gradle, existing Paper API module layout.

## Global Constraints

- Use `CraftingManagerApi` as the sole public extension point; do not add Bukkit types to the API package.
- Every SQL object remains schema-qualified `craftingmanager.<table>`.
- Bukkit mutations must stay on the main thread.
- Applied effects must never be rerun.
- `ProcessFinishedEvent` is not success-only; do not add a new success-only event in this plan.
- Parked instances must not receive automatic input refunds; the new API must preserve the existing dismiss/cancel distinction.
- Reuse existing test patterns: `RuntimeEngine` direct instantiation, `ProcessEventSink` recording, `SqliteProcessStore.open(temp)` for persistence tests.
- Use `docs/superpowers/plans/2026-08-20-public-api-control-plane.md` for the plan artifact if executing via subagent workflow.

---

### Task 1: Public instance snapshot and progress query types

**Files:**
- Modify: `src/main/java/dev/craftingmanager/api/Domain.java`
- Modify: `src/main/java/dev/craftingmanager/api/CraftingManagerApi.java`
- Test: `src/test/java/dev/craftingmanager/ProcessApiQueryTest.java`

**Interfaces:**
- Consumes: `ProcessState`, `ProcessDefinition`, `ItemSnapshot`, `EffectExecution`
- Produces: `ProcessInstanceSnapshot`, new API query methods

- [ ] **Step 1: Write the failing test**

```java
class ProcessApiQueryTest {
    @Test void activeInstanceReturnsSnapshotForRunningProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(new ProcessDefinition("job", List.of(),
                List.of(new ProcessStep("one", "One", 1), new ProcessStep("two", "Two", 1)),
                List.of((CompletionEffect) () -> "item-output")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, 64, 1);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        engine.advance(started.instanceId()).toCompletableFuture().join();

        var snapshot = engine.activeInstance(block).orElseThrow();

        assertEquals(started.instanceId(), snapshot.instanceId());
        assertEquals("job", snapshot.processId());
        assertEquals(ProcessState.RUNNING, snapshot.state());
        assertEquals(1, snapshot.stepIndex());
        assertEquals(0, snapshot.stepTicks());
        assertEquals(1, snapshot.stepDurationTicks());
    }

    @Test void activeInstanceReturnsEmptyForFreeBlock() {
        RuntimeEngine engine = new RuntimeEngine();
        assertTrue(engine.activeInstance(new BlockKey(UUID.randomUUID(), 0, 64, 0)).isEmpty());
    }

    @Test void activeInstanceReturnsParkedSnapshotAndBlockRemainsBusy() {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger calls = new AtomicInteger();
        engine.registerEffectHandler(handler("first", calls, false));
        engine.registerEffectHandler(handler("second", calls, true));
        engine.registerProcess(new ProcessDefinition("job", List.of(),
                List.of(new ProcessStep("one", "One", 1)),
                List.of(() -> "first", () -> "second")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var snapshot = engine.activeInstance(block).orElseThrow();

        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, snapshot.state());
        assertFalse(engine.start(block, "job", UUID.randomUUID()).started());
    }

    private static EffectHandler<CompletionEffect> handler(String type, AtomicInteger calls, boolean fail) {
        return new EffectHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiQueryTest' --no-daemon`
Expected: FAIL with compilation errors for missing `activeInstance` and `ProcessInstanceSnapshot`.

- [ ] **Step 3: Add snapshot type to `Domain.java`**

```java
public record ProcessInstanceSnapshot(
        UUID instanceId,
        String processId,
        ProcessState state,
        int stepIndex,
        long stepTicks,
        long stepDurationTicks,
        UUID owner,
        List<EffectExecution> ledger
) {
    public ProcessInstanceSnapshot {
        if (instanceId == null) throw new IllegalArgumentException("instanceId is required");
        if (processId == null || processId.isBlank()) throw new IllegalArgumentException("processId is required");
        if (state == null) throw new IllegalArgumentException("state is required");
        if (stepIndex < 0) throw new IllegalArgumentException("stepIndex cannot be negative");
        if (stepTicks < 0) throw new IllegalArgumentException("stepTicks cannot be negative");
        if (stepDurationTicks < 0) throw new IllegalArgumentException("stepDurationTicks cannot be negative");
        if (owner == null) throw new IllegalArgumentException("owner is required");
        ledger = List.copyOf(ledger == null ? List.of() : ledger);
    }
}
```

- [ ] **Step 4: Add API methods to `CraftingManagerApi.java`**

```java
Optional<ProcessInstanceSnapshot> activeInstance(BlockKey block);
Optional<ProcessInstanceSnapshot> activeInstance(UUID instanceId);
```

- [ ] **Step 5: Implement in `RuntimeEngine.java`**

```java
@Override public synchronized Optional<ProcessInstanceSnapshot> activeInstance(BlockKey block) {
    UUID instanceId = activeByBlock.get(Objects.requireNonNull(block));
    return instanceId == null ? Optional.empty() : snapshot(instances.get(instanceId));
}

@Override public synchronized Optional<ProcessInstanceSnapshot> activeInstance(UUID instanceId) {
    Instance instance = instances.get(Objects.requireNonNull(instanceId));
    return instance == null ? Optional.empty() : snapshot(instance);
}

private ProcessInstanceSnapshot snapshot(Instance instance) {
    List<ProcessStep> steps = instance.definition.steps();
    long duration = instance.step < steps.size() ? steps.get(instance.step).durationTicks() : 0;
    return new ProcessInstanceSnapshot(
            instance.id, instance.definition.id(), instance.state, instance.step, instance.stepTicks, duration,
            instance.owner, ledger(instance.id).orElseGet(List::of));
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiQueryTest' --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/craftingmanager/api/Domain.java src/main/java/dev/craftingmanager/api/CraftingManagerApi.java src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessApiQueryTest.java
git commit -m "feat: expose active instance query API"
```

---

### Task 2: Public cancel/dismiss API with policy enforcement

**Files:**
- Modify: `src/main/java/dev/craftingmanager/api/CraftingManagerApi.java`
- Modify: `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`
- Test: `src/test/java/dev/craftingmanager/ProcessApiCancelTest.java`

**Interfaces:**
- Consumes: `releaseInstance`, `cancel`, `dismiss`, `ProcessState`
- Produces: `cancelInstance`, `dismissInstance`, `ProcessCancelResult`

- [ ] **Step 1: Write the failing test**

```java
class ProcessApiCancelTest {
    @Test void cancelRunningInstanceReturnsInputsAndFreesBlock() {
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition("smelt", List.of(input()), List.of(), List.of((CompletionEffect) () -> "output")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "smelt", UUID.randomUUID());
        assertTrue(started.started());

        var result = engine.cancelInstance(started.instanceId());

        assertTrue(result.cancelled());
        assertEquals(1, inventory.returned);
        assertEquals(ProcessState.CANCELLED, engine.state(started.instanceId()).orElseThrow());
        assertTrue(engine.start(block, "smelt", UUID.randomUUID()).started());
    }

    @Test void cancelParkedInstanceIsRejected() {
        RuntimeEngine engine = parkedEngine();
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());

        var result = engine.cancelInstance(started.instanceId());

        assertFalse(result.cancelled());
        assertEquals("parked instance requires dismiss", result.reason());
    }

    @Test void dismissParkedInstanceFreesBlockWithoutReturningInputs() {
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = parkedEngine(inventory);
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var result = engine.dismissInstance(started.instanceId());

        assertTrue(result.dismissed());
        assertEquals(0, inventory.returned);
        assertTrue(engine.start(block, "job", UUID.randomUUID()).started());
    }

    @Test void dismissRunningInstanceIsRejected() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerProcess(new ProcessDefinition("smelt", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "output")));
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "smelt", UUID.randomUUID());

        var result = engine.dismissInstance(started.instanceId());

        assertFalse(result.dismissed());
        assertEquals("only parked instances can be dismissed", result.reason());
    }

    private static ProcessInput input() {
        return new ProcessInput("fuel", InputRole.FUEL, "COAL", 1, ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null);
    }

    private static RuntimeEngine parkedEngine() {
        return parkedEngine(new TrackingInventory());
    }

    private static RuntimeEngine parkedEngine(TrackingInventory inventory) {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger calls = new AtomicInteger();
        engine.registerEffectHandler(handler("first", calls, false));
        engine.registerEffectHandler(handler("second", calls, true));
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition("job", List.of(input()), List.of(), List.of(() -> "first", () -> "second")));
        return engine;
    }

    private static EffectHandler<CompletionEffect> handler(String type, AtomicInteger calls, boolean fail) {
        return new EffectHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }

    private static final class Handler implements EffectHandler<CompletionEffect> {
        public String type() { return "output"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {}
    }

    private static final class TrackingInventory implements InventoryAdapter {
        int removed;
        int returned;

        public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot("COAL", input.amount(), null), input.amount(), input.id(), input.consumption()));
        }

        public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }

        public void remove(List<Reservation.Claim> claims) { removed++; }

        public void returnItems(List<Reservation.Claim> claims) { returned++; }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiCancelTest' --no-daemon`
Expected: FAIL with missing API methods/types.

- [ ] **Step 3: Add result types to `Domain.java`**

```java
public record ProcessCancelResult(boolean cancelled, String reason) {
    public static ProcessCancelResult rejected(String reason) { return new ProcessCancelResult(false, reason); }
}
public record ProcessDismissResult(boolean dismissed, String reason) {
    public static ProcessDismissResult rejected(String reason) { return new ProcessDismissResult(false, reason); }
}
```

- [ ] **Step 4: Add API methods to `CraftingManagerApi.java`**

```java
ProcessCancelResult cancelInstance(UUID instanceId);
ProcessDismissResult dismissInstance(UUID instanceId);
```

- [ ] **Step 5: Implement in `RuntimeEngine.java`**

```java
@Override public synchronized ProcessCancelResult cancelInstance(UUID instanceId) {
    Instance instance = instances.get(Objects.requireNonNull(instanceId));
    if (instance == null || terminal(instance.state)) return ProcessCancelResult.rejected("unknown or terminal instance");
    if (instance.state == ProcessState.NEEDS_PROVIDER_ACTION) return ProcessCancelResult.rejected("parked instance requires dismiss");
    cancel(instance);
    return new ProcessCancelResult(true, "cancelled");
}

@Override public synchronized ProcessDismissResult dismissInstance(UUID instanceId) {
    Instance instance = instances.get(Objects.requireNonNull(instanceId));
    if (instance == null || instance.state != ProcessState.NEEDS_PROVIDER_ACTION) return ProcessDismissResult.rejected("only parked instances can be dismissed");
    dismiss(instance);
    return new ProcessDismissResult(true, "dismissed");
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiCancelTest' --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/craftingmanager/api/Domain.java src/main/java/dev/craftingmanager/api/CraftingManagerApi.java src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessApiCancelTest.java
git commit -m "feat: expose cancel/dismiss API with parked-instance policy"
```

---

### Task 3: Observer registration on `CraftingManagerApi`

**Files:**
- Modify: `src/main/java/dev/craftingmanager/api/CraftingManagerApi.java`
- Modify: `src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java`
- Test: `src/test/java/dev/craftingmanager/ProcessApiObserverTest.java`

**Interfaces:**
- Consumes: `ProcessEventSink`
- Produces: `registerProcessEventSink`, `RegistrationHandle` lifecycle

**Observer sink policy:**
- The engine keeps its existing constructor-provided `ProcessEventSink` as the primary sink.
- `registerProcessEventSink` adds fan-out observers.
- `emitStarting`: call the primary sink first; if it returns `false`, reject the start. Regardless of veto outcome, call every registered observer sink as fan-out, but ignore their return values for the veto decision. This preserves existing Paper behavior.
- `emitStarted` and `emitFinished`: fan out to the primary sink and all registered observers.

- [ ] **Step 1: Write the failing test**

```java
class ProcessApiObserverTest {
    @Test void registerSinkReceivesStartedAndFinished() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = new RuntimeEngine();
        try (var handle = engine.registerProcessEventSink(sink)) {
            engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
                public String type() { return "item-output"; }
                public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
                public void execute(CompletionEffect effect, String effectId) {}
            });
            engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "item-output")));
            var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());
            assertTrue(started.started());
            engine.advance(started.instanceId()).toCompletableFuture().join();
        }
        assertEquals(1, sink.started.size());
        assertEquals(1, sink.finished.size());
        assertEquals(ProcessState.COMPLETED, sink.finishedStates.getFirst());
    }

    @Test void closingHandleStopsEvents() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = new RuntimeEngine();
        var handle = engine.registerProcessEventSink(sink);
        handle.close();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "item-output")));
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());
        assertTrue(started.started());

        assertEquals(0, sink.started.size());
    }

    private static final class RecordingSink implements ProcessEventSink {
        final List<ProcessUsage> started = new ArrayList<>();
        final List<ProcessUsage> starting = new ArrayList<>();
        final List<ProcessUsage> finished = new ArrayList<>();
        final List<ProcessState> finishedStates = new ArrayList<>();

        @Override public boolean emitStarting(ProcessUsage usage) { starting.add(usage); return true; }
        @Override public void emitStarted(ProcessUsage usage) { started.add(usage); }
        @Override public void emitFinished(ProcessUsage usage, ProcessState state) { finished.add(usage); finishedStates.add(state); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiObserverTest' --no-daemon`
Expected: FAIL with missing `registerProcessEventSink`.

- [ ] **Step 3: Add API method to `CraftingManagerApi.java`**

```java
RegistrationHandle registerProcessEventSink(ProcessEventSink sink);
```

- [ ] **Step 4: Implement in `RuntimeEngine.java`**

```java
private final List<ProcessEventSink> eventSinks = new ArrayList<>();

@Override public synchronized RegistrationHandle registerProcessEventSink(ProcessEventSink sink) {
    Objects.requireNonNull(sink);
    eventSinks.add(sink);
    return handle(() -> { synchronized (this) { eventSinks.remove(sink); } });
}

private boolean emitStarting(ProcessUsage usage) {
    boolean veto = events.emitStarting(usage);
    for (ProcessEventSink sink : List.copyOf(eventSinks)) {
        sink.emitStarting(usage);
    }
    return veto;
}

private void emitStarted(ProcessUsage usage) {
    events.emitStarted(usage);
    for (ProcessEventSink sink : List.copyOf(eventSinks)) sink.emitStarted(usage);
}

private void emitFinished(Instance instance) {
    ProcessUsage usage = new ProcessUsage(instance.id, instance.block, instance.definition.id(), instance.owner);
    events.emitFinished(usage, instance.state);
    for (ProcessEventSink sink : List.copyOf(eventSinks)) sink.emitFinished(usage, instance.state);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.craftingmanager.ProcessApiObserverTest' --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/craftingmanager/api/CraftingManagerApi.java src/main/java/dev/craftingmanager/runtime/RuntimeEngine.java src/test/java/dev/craftingmanager/ProcessApiObserverTest.java
git commit -m "feat: expose process event sink registration"
```

---

### Task 4: Documentation and final verification

**Files:**
- Modify: `docs/living-spec.md`
- Test: full suite

- [ ] **Step 1: Update `docs/living-spec.md` Next section**

```markdown
## Next

- [ ] Public cancel/dismiss and progress queries on the public API.
- [ ] Off-main-thread SQLite writes with revision-checked apply-back.
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add docs/living-spec.md
git commit -m "docs: update Next checklist for public API work"
```
