# Crafting Manager Living Spec

## Design intent

Crafting Manager is the execution kernel for custom Minecraft manufacturing systems. It lets provider plugins describe anything from a simple free-form recipe to a multi-stage machine process without forcing all behavior into vanilla crafting-table semantics.

The core stays deliberately small: runtime SPI, validation, in-memory execution, Paper integration, and safe effect dispatch. Providers own content and persistence.

## Current

- [x] Paper plugin target established.
- [x] Java + Gradle implementation target established.
- [x] Configless/stateless boundary established.
- [x] Provider-owned definitions and persistence established.
- [x] Runtime-only registries established.
- [x] `BlockKey(UUID worldId, x, y, z)` established.
- [x] Recipes separated from processes and functional blocks.
- [x] Free-form and pattern recipe modes defined.
- [x] Ordered timed process steps defined.
- [x] Multiple input roles defined: primary, secondary, catalyst, fuel, tool, fluid, container, additive.
- [x] Optional and stage-specific inputs defined.
- [x] Per-input consumption policies defined.
- [x] Extensible completion-effect handlers defined.
- [x] Item outputs unified with non-item effects.
- [x] Immutable item-output snapshots required.
- [x] Partial completion ledger and provider-action state defined.
- [x] Main-thread Bukkit mutation boundary defined.
- [x] Gradle project, plugin metadata, entrypoint, API, registries, and basic runtime ledger scaffold created.

- [x] Add reservation intent and immutable claim domain values.
- [x] Add runtime inventory-adapter claim capture, revalidation, removal, and return lifecycle.
- [x] Add explicit provider unregister policies for active instances.
- [x] Add initial recipe API model with free-form, pattern, and process modes.
- [x] Add Paper block lifecycle adapter with runtime invalidation for break, explosion, and piston events.
- [x] Add provider-selected interaction trigger routing.
- [x] Add immutable item snapshot value object.
- [x] Add focused tests for handler registration, ledger failure, runtime state transitions, and reservation return failure.
- [x] Verify main and test sources directly with cached Paper/JUnit artifacts.

- [x] Add provider-isolated example process GUI model and Paper inventory listener.
- [x] Add runnable example provider registrations for inputs and output handler.
- [ ] Provider compatibility module and example provider plugin.
- [ ] Public API versioning policy.
- [ ] Multiblock functional stations.
- [ ] Provider-defined skill and progression integrations.
- [ ] Provider-defined power, fuel, and fluid systems.
- [ ] Optional durable provider-side process coordination.
- [ ] Optional GUI/editor provider.
- [ ] Optional SQLite-backed provider implementation.
- [ ] Cross-server provider synchronization.

## Invariants

1. The core plugin never reads user recipe/process configuration.
2. The core plugin never owns durable recipe or process state.
3. Every provider registration is runtime-only and must be repeated after enable.
4. Every Bukkit mutation occurs on the server thread.
5. Every asynchronous callback is checked against the current process instance and revision.
6. Every process input has an explicit role, timing, and consumption policy.
7. Every completion result, including item output, has a ledger entry.
8. A process cannot complete while any effect is missing, failed, unknown, or unapplied.
9. Applied completion effects are never executed again for the same effect ID.
10. Provider-owned mutable `ItemStack` objects never cross into retained runtime definitions.
11. Arbitrary blocks are not assumed to support PDC.
12. Missing provider dependencies result in explicit provider action, never silent success.

## Decision log

### Configless and stateless core

Definitions and persistence belong to providers. This keeps the engine reusable across content plugins and prevents a second configuration format from becoming the product.

### Runtime block identity

`BlockKey` is canonical for the current server lifetime. Durable placement identity is intentionally outside the core plugin.

### Unified completion effects

Item outputs and world/provider effects share one ledger so partial completion cannot duplicate outputs or silently lose later effects.

### Defensive item snapshots

Bukkit `ItemStack` is mutable. Definitions retain immutable snapshots and reconstruct fresh stacks for each execution attempt.

### Explicit provider-action state

A provider may unregister a handler while a process is active. The engine must preserve the instance and expose an explicit resolution path instead of hanging or skipping the result.
