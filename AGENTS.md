# Crafting Manager Agent Guide

## Project

Crafting Manager is a Paper Minecraft manufacturing runtime. Other plugins depend on it to register recipes, processes, functional blocks, and completion effects. The core also enables a first-party alloy smelter and owns SQLite instance persistence.

## Non-negotiable boundaries

- Use Java and Gradle with Paper API.
- Include Paper metadata in `plugin.yml`; do not create `config.yml` or user recipe files.
- Core owns SQLite for process instances, reservations, ledgers, and first-party station placements.
- Every SQL object is schema-qualified `craftingmanager.<table>` (never a bare table name).
- Providers own extra definitions and re-register them on every enable; close handles on disable.
- Live registries stay in memory; durable instance rows survive restart. Missing definitions become `NEEDS_PROVIDER_ACTION`.
- Never assume arbitrary blocks support `PersistentDataContainer`.
- Use `BlockKey(UUID worldId, int x, int y, int z)` for runtime block identity.
- Perform Bukkit inventory/world mutations on the main thread.
- Reject stale asynchronous callbacks using instance identity and revision checks.
- Never silently skip missing or failed completion effects.

## Domain rules

- Recipes describe transformations and may be free-form, patterned, or process-backed.
- Processes describe ordered timed operations hosted by functional blocks.
- Processes support multiple typed inputs: primary materials, secondary materials, catalysts, fuel, tools, fluids, containers, and additives.
- Inputs may be optional, stage-specific, supplied during execution, consumed, returned, damaged, or retained.
- Item outputs are completion effects backed by immutable snapshots, never provider-owned mutable `ItemStack` instances.
- Item and non-item completion effects share one execution ledger with stable effect IDs.
- A process reaches `COMPLETED` only when every completion effect is applied.
- Partial or ambiguous completion transitions to `NEEDS_PROVIDER_ACTION`; applied effects must never be rerun.

## Implementation workflow

1. Read the relevant specification and living spec before changing behavior.
2. Reuse existing patterns; do not add user configuration or a second recipe file format.
3. Keep API/domain objects immutable where practical.
4. Validate registrations structurally and reject duplicate IDs/types.
5. Add focused tests for every new observable contract.
6. Run the specific tests and build/smoke checks before claiming completion.
7. Update `docs/living-spec.md` when implementation changes the domain contract.

## Paper integration boundary

Paper listeners must not infer third-party content or hard-code provider process IDs. The core plugin may register its first-party alloy smelter on enable. Listeners still only translate Bukkit events into validated runtime inputs such as `BlockKey`; process selection stays on the SPI/trigger path.

## Scope discipline

Do not add recipe editors, configuration commands, user recipe files, or cross-server synchronization unless the project specification is explicitly changed. Core SQLite and restart recovery of instances are in scope.