# Crafting Manager Implementation Plan

## Phase 1: Bootstrap

- Create Gradle Java project.
- Add Paper API and test dependencies.
- Add `plugin.yml` with main class and API version.
- Add the plugin main class and lifecycle hooks.
- Do not add `config.yml` or persistence directories.

## Phase 2: Public API and domain

- Implement immutable `BlockKey`.
- Implement recipe definitions and free-form/pattern modes.
- Implement process definitions, steps, options, and states.
- Implement typed multi-input definitions.
- Implement immutable item snapshots and matchers.
- Implement completion effects, handler contracts, and idempotency modes.
- Implement registration handles and structured validation errors.

## Phase 3: Runtime registries

- Implement in-memory recipe registry.
- Implement in-memory process registry.
- Implement in-memory functional-block registry.
- Implement in-memory effect-handler registry.
- Reject duplicate registrations.
- Track active references so handler unregister policies are explicit.
- Clear all registries on plugin disable.

## Phase 4: Process execution

- Implement process start validation.
- Capture exact inventory source slots and immutable snapshots.
- Implement claim revalidation and runtime reservations.
- Implement typed consumption, return, damage, and retain behavior.
- Implement optional and stage-specific inputs.
- Implement ordered scheduler progression.
- Serialize active execution per `BlockKey`.
- Re-check permissions, ownership, block identity, and revision at transitions.
- Implement cancellation and failure notifications.

## Phase 5: Completion effects

- Treat item outputs as ordinary completion effects.
- Generate stable effect IDs from process instance, revision, and effect index/type.
- Resolve every handler before executing any effect.
- Execute effects in deterministic order.
- Track `PENDING`, `RUNNING`, `APPLIED`, `FAILED`, and `UNKNOWN` entries.
- Never rerun applied effects.
- Transition partial or ambiguous completion to `NEEDS_PROVIDER_ACTION`.
- Implement explicit resume/cancel/reconcile hooks for providers.

## Phase 6: Paper integration

- Add interaction handling for registered functional blocks.
- Add placement, break, explosion, piston, and replacement invalidation.
- Add chunk load/unload behavior for runtime instances.
- Enforce Bukkit main-thread boundaries.
- Reject stale asynchronous callbacks.
- Add optional permission and diagnostics hooks without configuration commands.

## Phase 7: Verification

- Run focused domain tests.
- Test `BlockKey`, including negative coordinates and world identity.
- Test duplicate registration and handle closure.
- Test multi-input matching and input roles.
- Test optional and stage-specific inputs.
- Test consumption, return, damage, and retain policies.
- Test stale callbacks and per-block execution locking.
- Test missing handlers and unregister policies.
- Test partial completion and applied-effect preservation.
- Test immutable output snapshots and retry reconstruction.
- Build the plugin.
- Run a Paper smoke test if a Paper runtime is available.

## Non-goals for the core plugin

Do not implement configuration loading, recipe editing commands, YAML/JSON/SQLite persistence, restart recovery, cross-server synchronization, or provider-owned data storage.

## Delivery checkpoints

1. Public API compiles with domain tests.
2. Registries and handles pass lifecycle tests.
3. Runtime process engine passes multi-input and state-transition tests.
4. Completion ledger passes partial-failure tests.
5. Paper adapter passes main-thread and interaction smoke tests.
