package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.entity.Player;

/** Enables and closes every first-party station on plugin lifecycle. */
public final class FirstPartyContent {
    private final CraftingManagerApi api;
    private final ExampleProcessProvider alloy;
    private final ExtraProcessProvider extras;

    public FirstPartyContent(CraftingManagerApi api, ExampleGuiListener guiListener) {
        this.api = api;
        this.alloy = new ExampleProcessProvider(api, guiListener);
        this.extras = new ExtraProcessProvider(api, guiListener);
    }

    public void enable() {
        alloy.enable();
        extras.enable();
    }

    public void disable() {
        extras.disable();
        alloy.disable();
    }

    public ExampleProcessGui openGui(Player player, BlockKey block) {
        String placed = api.placedFunctionalBlock(block).orElse(null);
        if (ExtraProcessProvider.POLISH_BLOCK_ID.equals(placed)) {
            return extras.openGui(player, block, ExtraProcessProvider.POLISH_PROCESS_ID);
        }
        if (ExtraProcessProvider.MIX_BLOCK_ID.equals(placed)) {
            return extras.openGui(player, block, ExtraProcessProvider.MIX_PROCESS_ID);
        }
        return alloy.openGui(player, block);
    }
}
