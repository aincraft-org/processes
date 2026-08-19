# Crafting Manager Living Spec

> Status: active
> Last updated: 2026-08-19

## Intent

Crafting Manager is the manufacturing **runtime other gameplay plugins depend on**. A server that installs it should be able to run processes (including a first-party alloy smelter) without a second plugin reinventing matching, reservations, ledgers, GUIs, or restart recovery.

Providers still *add* machines, recipes, and effects. They should not have to *reimplement* the factory.

Success looks like: professions / chemistry / furniture plugins compile against this SPI, register extra processes, and inherit execution, namespaced persistence, CustomPack models, and station interaction.

## Boundaries

### In scope
- Runtime SPI for recipes, processes, functional blocks, and completion effects.
- Recipe matching (free-form, pattern, process-backed).
- Process execution, reservations, and the completion ledger.
- First-party default station: alloy smelter (enabled on plugin enable).
- Plugin-owned SQLite for **instance and station** state, namespaced `craftingmanager.<table>`.
- CustomPack item models for first-party GUI/station display.
- Paper interaction and block-lifecycle invalidation.

### Out of scope / non-goals
- User-edited `config.yml` recipe books.
- A general recipe/process editor command suite.
- Provider-owned recipe YAML/JSON ingested by core.
- Cross-server instance synchronization.
- Every machine in the game living in this plugin.

## Invariants

1. Core never reads user recipe/process configuration files.
2. Core owns durable **instance** state (running processes, reservations, ledgers, placed first-party stations). Providers own extra **definitions**.
3. Providers re-register definitions and handlers after every enable. Surviving instance rows whose definition is missing become `NEEDS_PROVIDER_ACTION`.
4. Every Bukkit mutation occurs on the server thread.
5. Every asynchronous callback is checked against the current process instance and revision.
6. Every process input has an explicit role, timing, and consumption policy.
7. Every completion result, including item output, has a ledger entry.
8. A process cannot complete while any effect is missing, failed, unknown, or unapplied.
9. Applied completion effects are never executed again for the same effect ID.
10. Provider-owned mutable `ItemStack` objects never cross into retained runtime definitions.
11. Arbitrary blocks are not assumed to support PDC. `BlockKey` is runtime identity.
12. Missing provider dependencies result in explicit provider action, never silent success.
13. Plugin-owned resource-pack assets are compiled by CustomPack; they do not live under `src/main/resources`.
14. Every SQLite object is schema-qualified as `craftingmanager.<table>`. Bare table names are rejected.

## Implementation guidance

- Public SPI stays CustomPack-type-free. First-party models live under `src/main/item-models/<id>/` and compile into `META-INF/custompack/`.
- Persistence is a core module, not a provider. Default file: plugin data folder SQLite. No `config.yml`.
- Table names: `craftingmanager.schema_version`, `craftingmanager.process_instances`, `craftingmanager.reservations`, `craftingmanager.effect_ledger`, `craftingmanager.functional_blocks`. Always qualify in SQL (`CREATE TABLE IF NOT EXISTS craftingmanager.process_instances ...`).
- Register the first-party alloy smelter on enable (`example:alloy-smelt` or a stable `craftingmanager:alloy-smelt` id). Demo code that is never registered is not a product.
- Recipe matching is core. `RecipeApi` types must have a registry and matcher, not only records.
- Item take/give is abstract: processes declare `ProcessInput` + `ItemOutput` snapshots. Do not put alloy-specific consume/grant logic in `RuntimeEngine`. Paper `PlayerItemVault` is one `ItemVault`; tests use `MapItemVault`.
- Hopper I/O is abstract face routing: `ProcessInput.insertFaces` and `ItemOutput.extractFaces`. `insertAt`/`extractAt` match those ports. Paper hoppers only translate `BlockFace` → `ProcessFace`.
- After restart: reload instance rows, wait for definition re-registration, resume or park as `NEEDS_PROVIDER_ACTION`.
- Testing: domain tests for execution; persistence tests for namespaced schema and restart reload; structural tests for CustomPack models.

## Current

Shipped kernel (still in-memory only until Next lands):

- [x] Paper plugin target (26.2) and Java + Gradle.
- [x] Provider SPI: processes, functional blocks, triggers, inventory adapters, effect handlers.
- [x] In-memory execution, reservations, completion ledger, unregister policies.
- [x] Paper right-click trigger routing and break/explosion/piston invalidation.
- [x] Recipe *types* (free-form / pattern / process) without a live matcher.
- [x] Example alloy-smelter GUI + CustomPack item models (not registered on enable).
- [x] Core SQLite with schema `craftingmanager` and qualified tables.
- [x] Persist and restore process instances, reservations, and effect ledgers across restart.
- [x] Live recipe registry and matcher on `CraftingManagerApi`.
- [x] Enable first-party alloy smelter on plugin enable (`craftingmanager:alloy-smelt`).
- [x] Persist first-party functional-block placements (`craftingmanager.functional_blocks`).
- [x] Generic item I/O: `ItemOutput` effect, `ItemVault`/`SlotInventoryAdapter`, required-input coverage. First-party smelter is only a process definition.
- [x] Process face ports for hopper I/O (`insertFaces` / `extractFaces`). First-party smelter: UP iron, sides fuel, DOWN output.

### Current notes

Private DB file `plugins/CraftingManager/craftingmanager.db`. First-party station is a placed blast furnace. Paper 26.2; CustomPack models still softdepend.

## Next

- [ ] Pause/cancel/progress queries on the public API (GUI currently start-only).
- [ ] Off-main-thread SQLite writes with revision-checked apply-back.

## Future

- [ ] Provider compatibility module as a separate artifact if the SPI needs isolation.
- [ ] Public API versioning policy.
- [ ] Multiblock functional stations.
- [ ] Provider-defined skill, power, fuel, and fluid systems.
- [ ] Optional GUI/editor provider.
- [ ] Cross-server provider synchronization.

## Decisions log

| Date | Decision | Why |
|------|----------|-----|
| 2026-08-13 | Configless, stateless core; providers own persistence | Avoided a second recipe format becoming the product. |
| 2026-08-13 | `BlockKey` is runtime identity | Durable placement is not universal PDC. |
| 2026-08-13 | Unified completion ledger | Prevents duplicate outputs on partial failure. |
| 2026-08-19 | Core is a useful runtime dependency, not an empty kernel | A dependable manufacturing host must run a default station, match recipes, and recover instances. Providers add content; they do not rebuild the factory. |
| 2026-08-19 | Core owns SQLite instance state; tables are `craftingmanager.<name>` | Restart recovery cannot be optional if other plugins depend on this. Schema prefix avoids collisions in a shared server DB. |
| 2026-08-19 | First-party alloy smelter is enabled product, not dead example code | Proves the SPI and gives the machine people expect from this plugin. |
| 2026-08-19 | Still no user recipe `config.yml` | Definitions stay code/SPI. Persistence is instances, not a YAML recipe book. |

## Open questions

- [x] Shared server SQLite vs plugin-private file — private `plugins/CraftingManager/craftingmanager.db`.
- [x] First-party process id — `craftingmanager:alloy-smelt`.
