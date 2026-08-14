# Example Process GUI

The `dev.craftingmanager.example` package demonstrates how a provider can place a Paper inventory GUI on top of the Crafting Manager SPI without adding configuration or persistence to the core plugin.

## Scope

The example exposes a 27-slot process-selection and input screen for a provider-defined alloy-smelting process. It intentionally does not implement pause, cancel, or progress controls because the current public API exposes process start and advance, but not instance pause/cancel/progress queries.

The GUI is runtime-only. Closing the inventory discards the transient view model; provider definitions, reservations, and process instances remain owned by the existing runtime/provider boundaries.

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
