package dev.craftingmanager;

import dev.craftingmanager.api.Domain.FunctionalBlockDefinition;
import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.ExampleProcessProvider;
import dev.craftingmanager.example.ExtraProcessProvider;
import dev.craftingmanager.example.FirstPartyContent;
import dev.craftingmanager.paper.BoltProtectableBlocks;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LockableBlockTest {
    @Test void firstPartyEnableRegistersStationMaterialsAsLockable() {
        RuntimeEngine engine = new RuntimeEngine();
        FirstPartyContent content = new FirstPartyContent(engine, new ExampleGuiListener());
        content.enable();
        assertTrue(engine.isLockableBlock(ExampleProcessProvider.BLOCK_MATERIAL));
        assertTrue(engine.isLockableBlock(ExtraProcessProvider.POLISH_MATERIAL));
        assertTrue(engine.isLockableBlock(ExtraProcessProvider.MIX_MATERIAL));
        content.disable();
        assertFalse(engine.isLockableBlock(ExampleProcessProvider.BLOCK_MATERIAL));
        assertFalse(engine.isLockableBlock(ExtraProcessProvider.POLISH_MATERIAL));
        assertFalse(engine.isLockableBlock(ExtraProcessProvider.MIX_MATERIAL));
    }

    @Test void providersCanRegisterAndUnregisterLockableMaterials() {
        RuntimeEngine engine = new RuntimeEngine();
        var handle = engine.registerLockableBlock("LODESTONE");
        assertTrue(engine.isLockableBlock("LODESTONE"));
        assertTrue(engine.lockableBlocks().contains("LODESTONE"));
        handle.close();
        assertFalse(engine.isLockableBlock("LODESTONE"));
    }

    @Test void functionalBlockRegistrationMarksHostMaterialLockable() {
        RuntimeEngine engine = new RuntimeEngine();
        var handle = engine.registerFunctionalBlock(new FunctionalBlockDefinition(
                "craftingmanager:demo", "SMITHING_TABLE", List.of("demo")));
        assertTrue(engine.isLockableBlock("SMITHING_TABLE"));
        handle.close();
        assertFalse(engine.isLockableBlock("SMITHING_TABLE"));
    }

    @Test void boltProtectableMapGainsMissingStationMaterials() {
        List<String> missing = BoltProtectableBlocks.missing(
                Set.of("BLAST_FURNACE"), List.of("BLAST_FURNACE", "GRINDSTONE", "CAULDRON"));
        assertEquals(List.of("GRINDSTONE", "CAULDRON"), missing);
        assertTrue(BoltProtectableBlocks.missing(Set.of("GRINDSTONE"), List.of("grindstone")).isEmpty());
    }

    @Test void pluginSoftDependsOnBoltAndShipsUsageEvents() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.resolve("..").normalize();
        }
        String descriptor = Files.readString(root.resolve("src/main/resources/plugin.yml"));
        assertTrue(descriptor.contains("Bolt"));
        assertTrue(descriptor.contains("softdepend"));
        String plugin = Files.readString(root.resolve("src/main/java/dev/craftingmanager/CraftingManagerPlugin.java"));
        assertTrue(plugin.contains("PaperProcessEventSink") || plugin.contains("ProcessEventSink"));
        assertTrue(plugin.contains("BoltLockableHook") || plugin.contains("BoltProtectableBlocks"));
        assertTrue(Files.isRegularFile(root.resolve(
                "src/main/java/dev/craftingmanager/api/event/ProcessEvent.java")));
        assertTrue(Files.isRegularFile(root.resolve(
                "src/main/java/dev/craftingmanager/api/event/PreProcessEvent.java")));
        assertTrue(Files.isRegularFile(root.resolve(
                "src/main/java/dev/craftingmanager/api/event/ProcessStartedEvent.java")));
        assertTrue(Files.isRegularFile(root.resolve(
                "src/main/java/dev/craftingmanager/api/event/ProcessFinishedEvent.java")));
        String starting = Files.readString(root.resolve(
                "src/main/java/dev/craftingmanager/api/event/PreProcessEvent.java"));
        assertTrue(starting.contains("extends ProcessEvent"));
        assertTrue(starting.contains("implements Cancellable"));
        assertTrue(starting.contains("HandlerList"));
    }
}
