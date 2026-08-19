# Example Process GUI

The `dev.craftingmanager.example` package is the first-party stations. Each is a process definition plus this start GUI. Consume/grant go through the generic `ItemVault` / `ItemOutput` SPI. Placing a blast furnace, grindstone, or cauldron persists `craftingmanager.functional_blocks`; right-click opens the matching GUI. The alloy smelter (`craftingmanager:alloy-smelt`) takes iron + coal. The gem polisher (`craftingmanager:gem-polish`) takes a rough gem plus a returned tool. The tonic mixer (`craftingmanager:mix-tonic`) takes two reagents, a returned catalyst, and an optional additive. Hoppers use the `ProcessFace` ports on each process definition.

## Scope

Each station exposes a 27-slot input screen for its process. The screens intentionally do not implement pause, cancel, or progress controls because the current public API exposes process start and advance, but not instance pause/cancel/progress queries.

Closing the inventory discards the transient view model. Process instances and station placements are owned by the core runtime (durable after spec Next).

## Interaction flow

1. A provider opens `ExampleProcessGui` for a player and a `BlockKey`.
2. The GUI renders the process title, input requirements, and a start control.
3. Clicking the start control calls `CraftingManagerApi.start(block, processId, owner)`.
4. A successful start closes the GUI; a rejected start remains visible and displays the rejection reason in the model.
5. Decorative slots and unknown slots are cancelled and do not mutate inventory contents.
6. Inventory close removes the transient GUI session.

## Slots

Alloy smelter:

- Slot 10: iron input summary.
- Slot 12: coal fuel summary.
- Slot 14: alloy output preview.
- Slot 16: start button.

Gem polisher: slots 10/12/14 show rough gem, returned tool, and polished output. Tonic mixer: slots 10–14 show base, reagent, returned catalyst, optional additive, and tonic output. Start is always slot 16. All other slots are decorative and non-interactive.

The example uses immutable `ItemSnapshot` values for displayed input/output summaries. It does not retain mutable Bukkit `ItemStack` instances.

Displayed slot items apply CustomPack item-model keys (`craftingmanager:iron_input`, `craftingmanager:coal_fuel`, `craftingmanager:alloy_output`, `craftingmanager:start_process`). Those models are authored as CustomPack item-model sources under `src/main/item-models/<id>/` and compiled into `META-INF/custompack/` by `dev.custompack.bundle`. They are not loaded from `src/main/resources` and are not hand-laid pack files. When CustomPack is present on the server, clients receive the composed pack; without it the vanilla materials still render.
