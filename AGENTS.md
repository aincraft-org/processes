# Crafting Manager Agent Guide

## Project

Crafting Manager is a configless, stateless Paper Minecraft plugin. It provides a runtime SPI for provider plugins that define recipes, ordered processes, functional blocks, and completion effects.

## Non-negotiable boundaries

- Use Java and Gradle with Paper API.
- Include Paper metadata in `plugin.yml`; do not create `config.yml`.
- Do not add YAML, JSON, SQLite, or other plugin-owned definition/persistence storage.
- Definitions, player-specific data, and durable state belong to provider plugins.
- Core registries and process instances are in-memory only and are cleared on disable/restart.
- Providers must register definitions and handlers on every enable and close registration handles on disable.
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
2. Reuse existing patterns; avoid introducing configuration or persistence.
3. Keep API/domain objects immutable where practical.
4. Validate registrations structurally and reject duplicate IDs/types.
5. Add focused tests for every new observable contract.
6. Run the specific tests and build/smoke checks before claiming completion.
7. Update `docs/living-spec.md` when implementation changes the domain contract.

## Paper integration boundary

Paper listeners must not infer provider content or hard-code process IDs. Providers select the process through the SPI; listeners may only translate Bukkit events into validated runtime inputs such as `BlockKey`.

## Scope discipline

Do not add recipe editors, configuration commands, databases, restart recovery, cross-server synchronization, or provider-owned persistence to the core plugin unless the project specification is explicitly changed.