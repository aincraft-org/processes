# Example Process GUI

The `dev.craftingmanager.example` package is the first-party alloy smelter. It is only a process definition (`craftingmanager:alloy-smelt`) plus this GUI. Consume/grant go through the generic `ItemVault` / `ItemOutput` SPI. Placing a blast furnace persists `craftingmanager.functional_blocks`; right-click opens this GUI. Start can take iron + coal from the player. Hoppers insert iron from above and fuel from the sides; they extract alloy from below (`ProcessFace` ports on the process definition).

## Scope

The example exposes a 27-slot process-selection and input screen for a provider-defined alloy-smelting process. It intentionally does not implement pause, cancel, or progress controls because the current public API exposes process start and advance, but not instance pause/cancel/progress queries.

Closing the inventory discards the transient view model. Process instances and station placements are owned by the core runtime (durable after spec Next).

## Interaction flow

1. A provider opens `ExampleProcessGui` for a player and a `BlockKey`.
2. The GUI renders the process title, input requirements, and a start control.
3. Clicking the start control calls `CraftingManagerApi.start(block, processId, owner)`.
4. A successful start closes the GUI; a rejected start remains visible and displays the rejection reason in the model.
5. Decorative slots and unknown slots are cancelled and do not mutate inventory contents.
6. Inventory close removes the transient GUI session.

## Slots

- Slot 10: iron input summary.
- Slot 12: coal fuel summary.
- Slot 14: alloy output preview.
- Slot 16: start button.
- All other slots: decorative, non-interactive.

The example uses immutable `ItemSnapshot` values for displayed input/output summaries. It does not retain mutable Bukkit `ItemStack` instances.

Displayed slot items apply CustomPack item-model keys (`craftingmanager:iron_input`, `craftingmanager:coal_fuel`, `craftingmanager:alloy_output`, `craftingmanager:start_process`). Those models are authored as CustomPack item-model sources under `src/main/item-models/<id>/` and compiled into `META-INF/custompack/` by `dev.custompack.bundle`. They are not loaded from `src/main/resources` and are not hand-laid pack files. When CustomPack is present on the server, clients receive the composed pack; without it the vanilla materials still render.
