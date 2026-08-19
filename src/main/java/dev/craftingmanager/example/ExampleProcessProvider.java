package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.FunctionalBlockDefinition;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ItemOutput;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.Domain.RegistrationHandle;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.RecipeApi.Ingredient;
import dev.craftingmanager.api.RecipeApi.Mode;
import dev.craftingmanager.api.RecipeApi.RecipeDefinition;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** First-party alloy smelter: one process definition on the abstract SPI. */
public final class ExampleProcessProvider {
    public static final String PROCESS_ID = "craftingmanager:alloy-smelt";
    public static final String BLOCK_ID = "craftingmanager:alloy-smelter";
    public static final String RECIPE_ID = "craftingmanager:alloy-ingot";
    public static final String BLOCK_MATERIAL = "BLAST_FURNACE";

    private final CraftingManagerApi api;
    private final ExampleGuiListener guiListener;
    private RegistrationHandle processRegistration;
    private RegistrationHandle blockRegistration;
    private RegistrationHandle recipeRegistration;

    public ExampleProcessProvider(CraftingManagerApi api, ExampleGuiListener guiListener) {
        this.api = api;
        this.guiListener = guiListener;
    }

    public void enable() {
        processRegistration = api.registerProcess(new ProcessDefinition(
                PROCESS_ID,
                List.of(
                        new ProcessInput("iron", InputRole.PRIMARY_MATERIAL, "IRON_INGOT", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null, Set.of(ProcessFace.UP)),
                        new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.NORTH, ProcessFace.SOUTH, ProcessFace.EAST, ProcessFace.WEST))),
                List.of(
                        new ProcessStep("heat", "Heat", 40),
                        new ProcessStep("smelt", "Smelt", 60)),
                List.of(new ItemOutput("alloy", new ItemSnapshot("IRON_NUGGET", 1, null), Set.of(ProcessFace.DOWN)))));
        blockRegistration = api.registerFunctionalBlock(new FunctionalBlockDefinition(
                BLOCK_ID, BLOCK_MATERIAL, List.of(PROCESS_ID)));
        recipeRegistration = api.registerRecipe(new RecipeDefinition(
                RECIPE_ID,
                Mode.PROCESS,
                List.of(new Ingredient("iron", "IRON_INGOT", 1), new Ingredient("fuel", "COAL", 1)),
                Optional.empty(),
                Optional.of(PROCESS_ID)));
    }

    public void disable() {
        if (recipeRegistration != null) recipeRegistration.close();
        if (blockRegistration != null) blockRegistration.close();
        if (processRegistration != null) processRegistration.close();
        recipeRegistration = null;
        blockRegistration = null;
        processRegistration = null;
    }

    public ExampleProcessGui openGui(Player player, BlockKey block) {
        ExampleProcessGui gui = new ExampleProcessGui(api, block, player.getUniqueId(), PROCESS_ID);
        guiListener.track(player, gui);
        gui.open(player);
        return gui;
    }
}
