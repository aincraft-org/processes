# Crafting Manager Specification

## Product

Crafting Manager is a configless, stateless Paper Minecraft runtime engine. It exposes an SPI that provider plugins use to register custom recipes, ordered processes, functional blocks, and completion-effect handlers.

The core plugin is not a recipe database, editor, configuration loader, or persistence layer.

## Responsibilities

### Core plugin owns

- Paper lifecycle and metadata.
- Runtime validation.
- In-memory registries.
- Recipe and process matching.
- Functional-block interaction.
- Runtime process execution.
- Main-thread Bukkit mutations.
- Input reservations while the JVM is alive.
- Completion-effect dispatch and execution tracking.
- Registration lifecycle and diagnostics.

### Provider plugins own

- Recipe, process, and functional-block definitions.
- Player-specific visibility and restrictions.
- Definition persistence and reload behavior.
- Durable process state, if required.
- Provider-specific effects and external side effects.
- Re-registration after every enable or manager reload.

## Deliberate exclusions

- `config.yml` and user configuration.
- Plugin-owned recipe/process files.
- Databases and filesystem persistence.
- Restart recovery in the core plugin.
- Cross-server state.
- Recipe/process editor commands.
- Assumed `PersistentDataContainer` support for arbitrary blocks.

`plugin.yml` remains required Paper metadata and is not user configuration.

## Concepts

### Recipe

A transformation definition. Supported modes:

- Free-form input sets.
- Pattern/grid matching.
- Process-backed transformations.

### Process

An ordered, timed operation that may run on a functional block. A process can have any number of inputs and completion effects.

### Functional block

A runtime registration describing where a process may run. Matching may use material, block state, provider identity, and runtime registration data.

### Process instance

Runtime-only state for one execution. It includes an instance ID, `BlockKey`, definition reference, owner, revision, current step, reservations, state, and completion ledger.

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
5. Execute ordered steps.
6. Dispatch completion effects through registered handlers.
7. Preserve partial completion information.
8. Never silently skip an unavailable or failed effect.

The engine is runtime-only. It does not promise recovery after JVM termination.

## Block identity

```java
public record BlockKey(UUID worldId, int x, int y, int z) {}
```

The core registry is keyed by `BlockKey` and exists only in memory. Providers must register and unregister functional blocks. Tile-state PDC may be used by providers where supported, but it is not a universal identity mechanism.

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
- No blocking storage or filesystem operation is performed by the core plugin.
- Block placement, break, explosion, piston, and chunk events must invalidate or update runtime registrations safely.
