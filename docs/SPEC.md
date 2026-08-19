# Crafting Manager Specification

## Product

Crafting Manager is a Paper Minecraft manufacturing runtime. Gameplay plugins depend on it at runtime to register recipes, processes, functional blocks, and completion-effect handlers. The core also ships and **enables** a first-party alloy smelter so the dependency is useful on its own.

The core is not a user-edited recipe book, editor, or configuration loader. It **is** the execution host and the owner of process-instance persistence.

## Responsibilities

### Core plugin owns

- Paper lifecycle and metadata.
- Runtime validation and in-memory registries.
- Recipe and process matching.
- Functional-block interaction and first-party station GUI.
- Process execution and input reservations.
- Main-thread Bukkit mutations.
- Completion-effect dispatch and execution tracking.
- Registration lifecycle and diagnostics.
- SQLite persistence for process instances, reservations, ledgers, and first-party station placements.
- First-party alloy-smelter process, models (CustomPack), and GUI.

### Provider plugins own

- Additional recipe, process, and functional-block definitions.
- Player-specific visibility and restrictions.
- Definition reload (re-register on enable).
- Provider-specific effects and external side effects.

## Deliberate exclusions

- `config.yml` and user-edited recipe files.
- Recipe/process editor commands.
- Assumed `PersistentDataContainer` support for arbitrary blocks.
- Cross-server instance synchronization.

`plugin.yml` remains required Paper metadata and is not user configuration.

## Persistence

Core stores durable **instance** state in SQLite. All objects are schema-qualified:

```text
craftingmanager.schema_version
craftingmanager.process_instances
craftingmanager.reservations
craftingmanager.effect_ledger
craftingmanager.functional_blocks
```

SQL must use the qualified name (`craftingmanager.process_instances`), never a bare table name.

After restart, core reloads instance rows. If the owning process definition or effect handler has not been re-registered, the instance becomes `NEEDS_PROVIDER_ACTION`. Applied ledger entries are never rerun.

Default store is a plugin-private database file under the plugin data folder. No user config file is required to enable persistence.

## Concepts

### Recipe

A transformation definition. Supported modes:

- Free-form input sets.
- Pattern/grid matching.
- Process-backed transformations.

Core matches recipes; providers register them.

### Process

An ordered, timed operation that may run on a functional block. A process can have any number of inputs and completion effects.

The first-party process is the alloy smelter. Other plugins register more.

### Functional block

A registration describing where a process may run. Matching may use material, block state, provider identity, and runtime registration data. First-party placements are persisted in `craftingmanager.functional_blocks`.

### Process instance

State for one execution: instance ID, `BlockKey`, definition reference, owner, revision, current step, reservations, state, and completion ledger. Live in memory while the JVM is up; durable rows survive restart.

## Process inputs

Every input has a unique ID and typed role:

```java
public enum InputRole {
    PRIMARY_MATERIAL,
    SECONDARY_MATERIAL,
    CATALYST,
    FUEL,
    TOOL,
    FLUID,
    CONTAINER,
    ADDITIVE
}
```

Input timing:

```java
public enum InputTiming {
    ON_START,
    BEFORE_STAGE,
    DURING_STAGE,
    ON_COMPLETION
}
```

Consumption policies:

```java
public enum ConsumptionPolicy {
    CONSUME,
    RETURN_ON_SUCCESS,
    RETURN_ALWAYS,
    DAMAGE,
    RETAIN_IN_STATION
}
```

Inputs may be optional, stage-specific, or supplied during execution. Matching must use immutable item snapshots rather than shared mutable Bukkit objects.

## Process lifecycle

The runtime state machine includes:

```text
CREATED
CLAIM_CAPTURED
PENDING_RESERVATION
RESERVED
RUNNING
PAUSED
OUTPUT_PENDING
COMPLETED
CANCELLED
FAILED
NEEDS_PROVIDER_ACTION
```

The engine must:

1. Capture exact input sources and immutable snapshots.
2. Revalidate claims before mutation.
3. Mutate inventories only on the Bukkit main thread.
4. Reject stale callbacks using instance ID and revision.
5. Execute ordered steps for `durationTicks`. Progress is elapsed loaded-chunk ticks, persisted as `step_ticks`, and does not catch up while the chunk or server is down.
6. Dispatch completion effects through registered handlers.
7. Persist instance, reservation, and ledger rows so restart can resume or park them.
8. Never silently skip an unavailable or failed effect.

## Block identity

```java
public record BlockKey(UUID worldId, int x, int y, int z) {}
```

The live registry is keyed by `BlockKey`. First-party placements also have a durable row. Tile-state PDC may be used by providers where supported, but it is not a universal identity mechanism.

## Completion effects

All completion results use one extensible interface:

```java
public interface CompletionEffect {
    String type();
}
```

Examples include item output, block transformation, world effect, event, and provider-owned result.

Handlers are registered through the SPI and declare an idempotency policy. Item outputs use immutable snapshots and fresh Bukkit stacks for each execution attempt.

Each effect receives a stable effect ID and a ledger entry:

```text
PENDING → RUNNING → APPLIED
                  ↘ FAILED
                  ↘ UNKNOWN
```

A process is `COMPLETED` only when every effect is `APPLIED`. If a handler is missing, unregisters while in use, or fails after another effect has applied, the process becomes `NEEDS_PROVIDER_ACTION`. Already-applied effects are never rerun.

## Provider SPI shape

The public API must support registration and lookup for:

- Recipes.
- Processes.
- Functional blocks.
- Completion-effect handlers.

Each registration returns a closeable handle. Duplicate IDs and effect types are rejected unless an explicit replacement policy is added.

Providers must be able to observe started, progressed, completed, cancelled, failed, and provider-action-required instances.

## Paper constraints

- All Bukkit API access and world/inventory mutation runs on the server thread.
- Async provider callbacks carry immutable data only.
- SQLite IO must not block the main thread; hydrate and mutate world/inventory on the main thread after load.
- Block placement, break, explosion, piston, and chunk events must invalidate or update runtime registrations safely.
- First-party display assets go through CustomPack (`dev.custompack.bundle`), not `src/main/resources`.
