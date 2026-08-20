package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.ExampleProcessGui;
import dev.craftingmanager.example.ExampleProcessProvider;
import dev.craftingmanager.example.ExtraProcessProvider;
import dev.craftingmanager.example.FirstPartyContent;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.MapItemAccess;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.RuntimeEngine;
import dev.craftingmanager.runtime.SlotInventoryAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FirstPartyExamplesTest {
    @Test void enableRegistersExtraProcessesBlocksAndRecipes() {
        RuntimeEngine engine = new RuntimeEngine();
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        assertTrue(engine.process(ExtraProcessProvider.POLISH_PROCESS_ID).isPresent());
        assertTrue(engine.functionalBlockDefinition(ExtraProcessProvider.POLISH_BLOCK_ID).isPresent());
        assertTrue(engine.recipe(ExtraProcessProvider.POLISH_RECIPE_ID).isPresent());
        assertEquals(ExtraProcessProvider.POLISH_PROCESS_ID,
                engine.recipe(ExtraProcessProvider.POLISH_RECIPE_ID).orElseThrow().processId().orElseThrow());
        assertTrue(engine.process(ExtraProcessProvider.MIX_PROCESS_ID).isPresent());
        assertTrue(engine.functionalBlockDefinition(ExtraProcessProvider.MIX_BLOCK_ID).isPresent());
        assertTrue(engine.recipe(ExtraProcessProvider.MIX_RECIPE_ID).isPresent());
        assertEquals(ExtraProcessProvider.MIX_PROCESS_ID,
                engine.recipe(ExtraProcessProvider.MIX_RECIPE_ID).orElseThrow().processId().orElseThrow());
        assertNotEquals(ExampleProcessProvider.PROCESS_ID, ExtraProcessProvider.POLISH_PROCESS_ID);
        assertNotEquals(ExampleProcessProvider.PROCESS_ID, ExtraProcessProvider.MIX_PROCESS_ID);
        assertNotEquals(ExtraProcessProvider.POLISH_PROCESS_ID, ExtraProcessProvider.MIX_PROCESS_ID);
    }

    @Test void extraProcessesUseDistinctRolesReturnPoliciesAndHopperFaces() {
        RuntimeEngine engine = new RuntimeEngine();
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        ProcessDefinition polish = engine.process(ExtraProcessProvider.POLISH_PROCESS_ID).orElseThrow();
        ProcessDefinition mix = engine.process(ExtraProcessProvider.MIX_PROCESS_ID).orElseThrow();
        ProcessDefinition smelt = engine.process(ExampleProcessProvider.PROCESS_ID).orElseThrow();

        assertTrue(hasRole(polish, InputRole.TOOL));
        assertTrue(hasConsumption(polish, ConsumptionPolicy.RETURN_ON_SUCCESS));
        assertTrue(hasRole(mix, InputRole.SECONDARY_MATERIAL));
        assertTrue(hasRole(mix, InputRole.CATALYST));
        assertTrue(hasRole(mix, InputRole.ADDITIVE));
        assertTrue(hasConsumption(mix, ConsumptionPolicy.RETURN_ALWAYS));
        assertTrue(mix.inputs().stream().anyMatch(ProcessInput::optional));

        assertNotEquals(insertFaces(smelt), insertFaces(polish));
        assertNotEquals(insertFaces(smelt), insertFaces(mix));
        assertFalse(extractFaces(polish).isEmpty());
        assertFalse(extractFaces(mix).isEmpty());
        assertNotEquals(extractFaces(smelt), extractFaces(polish));
        assertNotEquals(extractFaces(smelt), extractFaces(mix));

        assertEquals("GRINDSTONE",
                engine.functionalBlockDefinition(ExtraProcessProvider.POLISH_BLOCK_ID).orElseThrow().material());
        assertEquals("CAULDRON",
                engine.functionalBlockDefinition(ExtraProcessProvider.MIX_BLOCK_ID).orElseThrow().material());
        assertEquals("BLAST_FURNACE",
                engine.functionalBlockDefinition(ExampleProcessProvider.BLOCK_ID).orElseThrow().material());
    }

    @Test void gemPolisherStartsCompletesGrantsOutputAndReturnsTool() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        MapItemAccess access = vault.open(owner, 9);
        access.set(0, new ItemSnapshot("AMETHYST_SHARD", 1, null));
        access.set(1, new ItemSnapshot("IRON_PICKAXE", 1, null));
        RuntimeEngine engine = engine(vault);
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        BlockKey block = new BlockKey(UUID.randomUUID(), 2, 64, 2);
        engine.placeFunctionalBlock(block, ExtraProcessProvider.POLISH_BLOCK_ID);
        CraftingManagerApi.ProcessStartResult result = engine.start(
                block, ExtraProcessProvider.POLISH_PROCESS_ID, owner);
        assertTrue(result.started(), result.reason());
        assertEquals(0, count(access, "AMETHYST_SHARD"));
        assertEquals(0, count(access, "IRON_PICKAXE"));
        assertEquals(ProcessState.COMPLETED, complete(engine, result.instanceId()));
        assertEquals(new ItemSnapshot("QUARTZ", 1, null),
                engine.extractAt(block, ProcessFace.WEST, 1).orElseThrow());
        assertEquals(0, count(access, "AMETHYST_SHARD"));
        assertEquals(1, count(access, "IRON_PICKAXE"));
        content.disable();
        assertTrue(engine.process(ExtraProcessProvider.POLISH_PROCESS_ID).isEmpty());
    }

    @Test void tonicMixerStartsCompletesGrantsOutputAndReturnsCatalyst() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        MapItemAccess access = vault.open(owner, 9);
        access.set(0, new ItemSnapshot("REDSTONE", 1, null));
        access.set(1, new ItemSnapshot("GLOWSTONE_DUST", 1, null));
        access.set(2, new ItemSnapshot("BLAZE_POWDER", 1, null));
        RuntimeEngine engine = engine(vault);
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        BlockKey block = new BlockKey(UUID.randomUUID(), 3, 64, 3);
        engine.placeFunctionalBlock(block, ExtraProcessProvider.MIX_BLOCK_ID);
        CraftingManagerApi.ProcessStartResult result = engine.start(
                block, ExtraProcessProvider.MIX_PROCESS_ID, owner);
        assertTrue(result.started(), result.reason());
        assertEquals(0, count(access, "REDSTONE"));
        assertEquals(0, count(access, "GLOWSTONE_DUST"));
        assertEquals(0, count(access, "BLAZE_POWDER"));
        assertEquals(ProcessState.COMPLETED, complete(engine, result.instanceId()));
        assertEquals(new ItemSnapshot("GLOW_INK_SAC", 1, null),
                engine.extractAt(block, ProcessFace.NORTH, 1).orElseThrow());
        assertEquals(0, count(access, "REDSTONE"));
        assertEquals(0, count(access, "GLOWSTONE_DUST"));
        assertEquals(1, count(access, "BLAZE_POWDER"));
        content.disable();
        assertTrue(engine.process(ExtraProcessProvider.MIX_PROCESS_ID).isEmpty());
    }

    @Test void alloySmelterStillStartsAndCompletesWithFirstPartyContent() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        vault.open(owner, 9).set(0, new ItemSnapshot("IRON_INGOT", 1, null));
        vault.open(owner, 9).set(1, new ItemSnapshot("COAL", 1, null));
        RuntimeEngine engine = engine(vault);
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        assertTrue(engine.process(ExampleProcessProvider.PROCESS_ID).isPresent());
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        engine.placeFunctionalBlock(block, ExampleProcessProvider.BLOCK_ID);
        CraftingManagerApi.ProcessStartResult result = engine.start(
                block, ExampleProcessProvider.PROCESS_ID, owner);
        assertTrue(result.started(), result.reason());
        assertEquals(ProcessState.COMPLETED, complete(engine, result.instanceId()));
        assertEquals(new ItemSnapshot("IRON_NUGGET", 1, null),
                engine.extractAt(block, ProcessFace.DOWN, 1).orElseThrow());
        content.disable();
        assertTrue(engine.process(ExampleProcessProvider.PROCESS_ID).isEmpty());
        assertTrue(engine.process(ExtraProcessProvider.POLISH_PROCESS_ID).isEmpty());
        assertTrue(engine.process(ExtraProcessProvider.MIX_PROCESS_ID).isEmpty());
    }

    @Test void extraHopperFacesAcceptMatchingInserts() {
        RuntimeEngine engine = engine(new MapItemVault());
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        BlockKey polish = new BlockKey(UUID.randomUUID(), 5, 64, 5);
        engine.placeFunctionalBlock(polish, ExtraProcessProvider.POLISH_BLOCK_ID);
        assertTrue(engine.insertAt(polish, ProcessFace.NORTH, new ItemSnapshot("AMETHYST_SHARD", 1, null)));
        assertFalse(engine.insertAt(polish, ProcessFace.UP, new ItemSnapshot("AMETHYST_SHARD", 1, null)));
        assertTrue(engine.insertAt(polish, ProcessFace.UP, new ItemSnapshot("IRON_PICKAXE", 1, null)));
        BlockKey mix = new BlockKey(UUID.randomUUID(), 6, 64, 6);
        engine.placeFunctionalBlock(mix, ExtraProcessProvider.MIX_BLOCK_ID);
        assertTrue(engine.insertAt(mix, ProcessFace.EAST, new ItemSnapshot("REDSTONE", 1, null)));
        assertTrue(engine.insertAt(mix, ProcessFace.WEST, new ItemSnapshot("GLOWSTONE_DUST", 1, null)));
        assertTrue(engine.insertAt(mix, ProcessFace.UP, new ItemSnapshot("BLAZE_POWDER", 1, null)));
        assertTrue(engine.insertAt(mix, ProcessFace.SOUTH, new ItemSnapshot("SUGAR", 1, null)));
        assertFalse(engine.insertAt(mix, ProcessFace.NORTH, new ItemSnapshot("REDSTONE", 1, null)));
    }

    @Test void startUiHandleClickStartsExtraProcesses() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        vault.open(owner, 9).set(0, new ItemSnapshot("AMETHYST_SHARD", 1, null));
        vault.open(owner, 9).set(1, new ItemSnapshot("IRON_PICKAXE", 1, null));
        RuntimeEngine engine = engine(vault);
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        BlockKey polish = new BlockKey(UUID.randomUUID(), 7, 64, 7);
        engine.placeFunctionalBlock(polish, ExtraProcessProvider.POLISH_BLOCK_ID);
        ExampleProcessGui polishGui = new ExampleProcessGui(
                engine, polish, owner, ExtraProcessProvider.POLISH_PROCESS_ID);
        assertEquals("Gem Polisher", polishGui.title());
        assertTrue(polishGui.handleClick(ExampleProcessGui.START_SLOT));

        UUID mixerOwner = UUID.randomUUID();
        vault.open(mixerOwner, 9).set(0, new ItemSnapshot("REDSTONE", 1, null));
        vault.open(mixerOwner, 9).set(1, new ItemSnapshot("GLOWSTONE_DUST", 1, null));
        vault.open(mixerOwner, 9).set(2, new ItemSnapshot("BLAZE_POWDER", 1, null));
        BlockKey mix = new BlockKey(UUID.randomUUID(), 8, 64, 8);
        engine.placeFunctionalBlock(mix, ExtraProcessProvider.MIX_BLOCK_ID);
        ExampleProcessGui mixGui = new ExampleProcessGui(
                engine, mix, mixerOwner, ExtraProcessProvider.MIX_PROCESS_ID);
        assertEquals("Tonic Mixer", mixGui.title());
        assertTrue(mixGui.handleClick(ExampleProcessGui.START_SLOT));
    }

    @Test void pluginWiresExtraHostsAndDoesNotShipUserConfig() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.resolve("..").normalize();
        }
        String plugin = Files.readString(root.resolve("src/main/java/dev/craftingmanager/CraftingManagerPlugin.java"));
        String listener = Files.readString(
                root.resolve("src/main/java/dev/craftingmanager/example/FirstPartyStationListener.java"));
        String chunks = Files.readString(
                root.resolve("src/main/java/dev/craftingmanager/paper/ProcessChunkListener.java"));
        assertTrue(plugin.contains("FirstPartyContent"));
        assertTrue(plugin.contains("FirstPartyStationListener"));
        assertTrue(plugin.contains("firstParty.enable()"));
        assertTrue(plugin.contains("firstParty.disable()"));
        assertTrue(plugin.contains("runTaskTimer"));
        assertTrue(plugin.contains("engine::tick"));
        assertTrue(plugin.contains("ProcessChunkListener"));
        assertTrue(plugin.contains("markLoadedChunks"));
        assertTrue(plugin.contains("loadChunk"));
        assertTrue(chunks.contains("ChunkLoadEvent"));
        assertTrue(chunks.contains("ChunkUnloadEvent"));
        assertTrue(chunks.contains("loadChunk"));
        assertTrue(chunks.contains("unloadChunk"));
        assertTrue(listener.contains("GRINDSTONE"));
        assertTrue(listener.contains("CAULDRON"));
        assertTrue(listener.contains("BLAST_FURNACE"));
        assertTrue(listener.contains("openGui"));
        assertFalse(Files.exists(root.resolve("src/main/resources/config.yml")));
        assertFalse(Files.exists(root.resolve("config.yml")));
        assertFalse(Files.exists(root.resolve("src/main/resources/recipes.yml")));
    }

    private static RuntimeEngine engine(MapItemVault vault) {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerInventoryAdapter(new SlotInventoryAdapter(vault));
        engine.registerEffectHandler(new ItemOutputHandler(vault, engine));
        return engine;
    }

    private static ProcessState complete(RuntimeEngine engine, UUID instanceId) {
        ProcessState state = null;
        for (int i = 0; i < 256; i++) {
            state = engine.advance(instanceId).toCompletableFuture().join();
            if (state != ProcessState.RUNNING) return state;
        }
        return state;
    }

    private static int count(MapItemAccess access, String material) {
        int total = 0;
        for (int slot = 0; slot < access.size(); slot++) {
            ItemSnapshot item = access.get(slot);
            if (item != null && material.equals(item.material())) total += item.amount();
        }
        return total;
    }

    private static boolean hasRole(ProcessDefinition definition, InputRole role) {
        return definition.inputs().stream().anyMatch(input -> input.role() == role);
    }

    private static boolean hasConsumption(ProcessDefinition definition, ConsumptionPolicy policy) {
        return definition.inputs().stream().anyMatch(input -> input.consumption() == policy);
    }

    private static List<Set<ProcessFace>> insertFaces(ProcessDefinition definition) {
        return definition.inputs().stream().map(ProcessInput::insertFaces).toList();
    }

    private static List<Set<ProcessFace>> extractFaces(ProcessDefinition definition) {
        return definition.effects().stream()
                .filter(effect -> effect instanceof dev.craftingmanager.api.Domain.ItemOutput)
                .map(effect -> ((dev.craftingmanager.api.Domain.ItemOutput) effect).extractFaces())
                .toList();
    }
}
