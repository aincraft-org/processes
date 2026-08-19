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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** First-party gem polisher and tonic mixer registered on the abstract SPI. */
public final class ExtraProcessProvider {
    public static final String POLISH_PROCESS_ID = "craftingmanager:gem-polish";
    public static final String POLISH_BLOCK_ID = "craftingmanager:gem-polisher";
    public static final String POLISH_RECIPE_ID = "craftingmanager:polished-gem";
    public static final String POLISH_MATERIAL = "GRINDSTONE";

    public static final String MIX_PROCESS_ID = "craftingmanager:mix-tonic";
    public static final String MIX_BLOCK_ID = "craftingmanager:tonic-mixer";
    public static final String MIX_RECIPE_ID = "craftingmanager:mixed-tonic";
    public static final String MIX_MATERIAL = "CAULDRON";

    private final CraftingManagerApi api;
    private final ExampleGuiListener guiListener;
    private final List<RegistrationHandle> handles = new ArrayList<>();

    public ExtraProcessProvider(CraftingManagerApi api, ExampleGuiListener guiListener) {
        this.api = api;
        this.guiListener = guiListener;
    }

    public void enable() {
        handles.add(api.registerProcess(new ProcessDefinition(
                POLISH_PROCESS_ID,
                List.of(
                        new ProcessInput("rough", InputRole.PRIMARY_MATERIAL, "AMETHYST_SHARD", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.NORTH)),
                        new ProcessInput("tool", InputRole.TOOL, "IRON_PICKAXE", 1,
                                ConsumptionPolicy.RETURN_ON_SUCCESS, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.UP))),
                List.of(new ProcessStep("polish", "Polish", 40)),
                List.of(new ItemOutput("gem", new ItemSnapshot("QUARTZ", 1, null), Set.of(ProcessFace.WEST))))));
        handles.add(api.registerFunctionalBlock(new FunctionalBlockDefinition(
                POLISH_BLOCK_ID, POLISH_MATERIAL, List.of(POLISH_PROCESS_ID))));
        handles.add(api.registerRecipe(new RecipeDefinition(
                POLISH_RECIPE_ID,
                Mode.PROCESS,
                List.of(new Ingredient("rough", "AMETHYST_SHARD", 1), new Ingredient("tool", "IRON_PICKAXE", 1)),
                Optional.empty(),
                Optional.of(POLISH_PROCESS_ID))));

        handles.add(api.registerProcess(new ProcessDefinition(
                MIX_PROCESS_ID,
                List.of(
                        new ProcessInput("base", InputRole.PRIMARY_MATERIAL, "REDSTONE", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.EAST)),
                        new ProcessInput("reagent", InputRole.SECONDARY_MATERIAL, "GLOWSTONE_DUST", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.WEST)),
                        new ProcessInput("catalyst", InputRole.CATALYST, "BLAZE_POWDER", 1,
                                ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.UP)),
                        new ProcessInput("sweetener", InputRole.ADDITIVE, "SUGAR", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, true, null,
                                Set.of(ProcessFace.SOUTH))),
                List.of(new ProcessStep("mix", "Mix", 40)),
                List.of(new ItemOutput("tonic", new ItemSnapshot("GLOW_INK_SAC", 1, null), Set.of(ProcessFace.NORTH))))));
        handles.add(api.registerFunctionalBlock(new FunctionalBlockDefinition(
                MIX_BLOCK_ID, MIX_MATERIAL, List.of(MIX_PROCESS_ID))));
        handles.add(api.registerRecipe(new RecipeDefinition(
                MIX_RECIPE_ID,
                Mode.PROCESS,
                List.of(
                        new Ingredient("base", "REDSTONE", 1),
                        new Ingredient("reagent", "GLOWSTONE_DUST", 1),
                        new Ingredient("catalyst", "BLAZE_POWDER", 1)),
                Optional.empty(),
                Optional.of(MIX_PROCESS_ID))));
    }

    public void disable() {
        for (int i = handles.size() - 1; i >= 0; i--) {
            handles.get(i).close();
        }
        handles.clear();
    }

    public ExampleProcessGui openGui(Player player, BlockKey block, String processId) {
        ExampleProcessGui gui = new ExampleProcessGui(api, block, player.getUniqueId(), processId);
        guiListener.track(player, gui);
        gui.open(player);
        return gui;
    }
}
