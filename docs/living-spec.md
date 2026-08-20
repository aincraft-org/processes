# Crafting Manager Living Spec

> Status: active
> Last updated: 2026-08-20

## Intent

Crafting Manager is the manufacturing **runtime other gameplay plugins depend on**. A server that installs it should be able to run processes (including a first-party alloy smelter) without a second plugin reinventing matching, reservations, ledgers, GUIs, or restart recovery.

Providers still *add* machines, recipes, and effects. They should not have to *reimplement* the factory.

Success looks like: professions / chemistry / furniture plugins compile against this SPI, register extra processes, and inherit execution, namespaced persistence, CustomPack models, and station interaction.

## Boundaries

### In scope
- Runtime SPI for recipes, processes, functional blocks, and completion effects.
- Recipe matching (free-form, pattern, process-backed).
- Process execution, reservations, and the completion ledger.
- First-party default stations: alloy smelter, gem polisher, and tonic mixer (enabled on plugin enable).
- Plugin-owned SQLite for **instance and station** state, namespaced `craftingmanager.<table>`.
- CustomPack item models for first-party GUI/station display.
- Paper interaction and block-lifecycle invalidation.
- Process usage events that identify who started a station process.
- Optional Bolt (pop4959) softdepend so registered station hosts can be locked.

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
- Register first-party stations on enable (`craftingmanager:alloy-smelt`, `craftingmanager:gem-polish`, `craftingmanager:mix-tonic`). Demo code that is never registered is not a product.
- Recipe matching is core. `RecipeApi` types must have a registry and matcher, not only records.
- Item take/give is abstract: processes declare `ProcessInput` + `ItemOutput` snapshots. Do not put alloy-specific consume/grant logic in `RuntimeEngine`. Paper `PlayerItemVault` is one `ItemVault`; tests use `MapItemVault`.
- Hopper I/O is abstract face routing: `ProcessInput.insertFaces` and `ItemOutput.extractFaces`. `insertAt`/`extractAt` match those ports. Paper hoppers only translate `BlockFace` → `ProcessFace`.
- Station inventory is the live `ItemSnapshot` map on a `BlockKey` (input/output ids). Persist as `craftingmanager.station_inventories` rows (`material`, `amount`, metadata text — not Base64). Hydrate into memory; write on insert/extract/start/complete, chunk unload, and shutdown. `start()` claims station slots first (`STATION_SLOT`); player inventory is only a fallback. Returned station claims go back to the station slot. GUI displays stored snapshots; it does not own a second inventory.
- Process usage is observable: listen to `ProcessEvent` for every phase. `PreProcessEvent` is the cancellable start; `ProcessStartedEvent` and `ProcessFinishedEvent` follow. All carry owner UUID, `BlockKey`, and process id. The engine fires through `ProcessEventSink`; tests record; Paper publishes Bukkit events. Do not skip identity fields.
- Functional-block hosts are lockable materials. Providers may also call `registerLockableBlock`. When Bolt is present, core adds missing materials to Bolt's in-memory protectable set (no Bolt `config.yml` edits). Start is denied when Bolt says the player cannot `interact`.
- After restart: reload instance rows, wait for definition re-registration, resume or park as `NEEDS_PROVIDER_ACTION`.
- Process steps tick like a furnace: `ProcessStep.durationTicks` is real elapsed server ticks. `advance(instanceId)` applies one tick; Paper runs `RuntimeEngine.tick()` every server tick. A process gains time only while its host chunk is loaded (`loadChunk` / `unloadChunk`). Starting a process marks that chunk loaded. After hydrate, wait for a chunk load (or Paper's currently-loaded seed) before ticking. Unload persists `step_ticks` and pauses ticking while state remains `RUNNING`. There is no wall-clock catch-up.
- Persist cook progress as `craftingmanager.process_instances.step_ticks`. Write on step completion, shutdown, chunk unload, and every 20 ticks of progress — not every tick.
- Testing: domain tests for execution; persistence tests for namespaced schema and restart reload; structural tests for CustomPack models.

## Current

Shipped kernel:

- [x] Paper plugin target (26.2) and Java + Gradle.
- [x] Provider SPI: processes, functional blocks, triggers, inventory adapters, effect handlers.
- [x] In-memory execution, reservations, completion ledger, unregister policies.
- [x] Paper right-click trigger routing and break/explosion/piston invalidation.
- [x] Recipe *types* (free-form / pattern / process) without a live matcher.
- [x] Example alloy-smelter GUI + CustomPack item models.
- [x] Core SQLite with schema `craftingmanager` and qualified tables.
- [x] Persist and restore process instances, reservations, and effect ledgers across restart.
- [x] Live recipe registry and matcher on `CraftingManagerApi`.
- [x] Enable first-party alloy smelter on plugin enable (`craftingmanager:alloy-smelt`).
- [x] First-party gem polisher (`craftingmanager:gem-polish`) on grindstone: `TOOL` + `RETURN_ON_SUCCESS`, hopper faces NORTH rough / UP tool / WEST output.
- [x] First-party tonic mixer (`craftingmanager:mix-tonic`) on cauldron: `SECONDARY_MATERIAL` + optional `ADDITIVE` + `CATALYST` `RETURN_ALWAYS`, hopper faces EAST/WEST/UP/SOUTH in and NORTH out.
- [x] Persist first-party functional-block placements (`craftingmanager.functional_blocks`).
- [x] Generic item I/O: `ItemOutput` effect, `ItemVault`/`SlotInventoryAdapter`, required-input coverage. First-party smelter is only a process definition.
- [x] Process face ports for hopper I/O (`insertFaces` / `extractFaces`). First-party smelter: UP iron, sides fuel, DOWN output.
- [x] Process usage events: `ProcessEvent` base, `PreProcessEvent` (cancellable), started, finished, with owner / block / process / instance.
- [x] Lockable station hosts; Bolt softdepend registers them as protectable (grindstone is not in Bolt's default list).
- [x] Furnace-like step scheduler: honor `durationTicks`, persist `step_ticks`, tick only loaded chunks, resume mid-step after restart with no catch-up.
- [x] Chunk load/unload lifecycle: `loadChunk`/`unloadChunk`, Paper `ChunkLoadEvent`/`ChunkUnloadEvent`, seed currently loaded chunks on enable. Processes do not tick until their chunk is loaded.
- [x] Persisted station inventories: SQLite `station_inventories`, in-memory `ItemSnapshot` slots, hopper/`start()` share those slots, player backpack is fallback only.
- [x] Safe invalidation of parked instances: `NEEDS_PROVIDER_ACTION` instances are dismissed without returning claims or rerunning effects; running instances use normal cancellation.

### Current notes

Private DB file `plugins/CraftingManager/craftingmanager.db`. First-party stations are a placed blast furnace (alloy smelter), grindstone (gem polisher), and cauldron (tonic mixer). Paper 26.2; CustomPack models still softdepend. `ProcessFinishedEvent` is emitted for completed, cancelled, failed, and provider-action-required outcomes; it is not success-only.

## Next

- [ ] Public cancel/dismiss and progress queries on the public API (GUI currently start-only).
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
| 2026-08-19 | Extra first-party examples are a gem polisher and tonic mixer, not a vanilla-machine catalog | Prove unused input roles, return policies, and hopper faces without copying the smelter. |
| 2026-08-19 | Process usage is a Bukkit event with owner identity | Other plugins (logs, professions, Bolt) must see who used a station without reading SQLite. |
| 2026-08-19 | Register lockable hosts into Bolt at runtime; do not write Bolt config | Bolt has no public protectable-block API. Mutating the live map lets `/lock` work on grindstones without a user `config.yml`. |
| 2026-08-19 | Still no user recipe `config.yml` | Definitions stay code/SPI. Persistence is instances, not a YAML recipe book. |
| 2026-08-19 | Process time is elapsed loaded-chunk ticks, persisted as `step_ticks` | Matches vanilla furnaces: progress survives restart, pauses while unloaded, and does not skip ahead for offline time. |
| 2026-08-19 | Tick only tracked loaded chunks; Paper seeds on enable and listens to load/unload | Polling `World.isChunkLoaded` during unload is racy. An explicit loaded set matches furnace tile-entity lifetime. |
| 2026-08-19 | Station slots are ItemSnapshot in memory and SQLite rows, not Base64 | Decode once on hydrate. Hopper, start, and GUI share one inventory on the BlockKey. |
| 2026-08-20 | Parked invalidation is dismissal, not refunding cancellation | A parked ledger may contain applied or unknown effects; returning claims can duplicate resources. |

## Open questions

- [x] Shared server SQLite vs plugin-private file — private `plugins/CraftingManager/craftingmanager.db`.
- [x] First-party process id — `craftingmanager:alloy-smelt`.
- [x] Extra first-party process ids — `craftingmanager:gem-polish` and `craftingmanager:mix-tonic`.
