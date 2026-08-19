package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.ExampleProcessProvider;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.RuntimeEngine;
import dev.craftingmanager.runtime.SlotInventoryAdapter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExampleProcessProviderTest {
    @Test void providerRegistersRunnableProcessDependencies() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        vault.open(owner, 9).set(0, new ItemSnapshot("IRON_INGOT", 1, null));
        vault.open(owner, 9).set(1, new ItemSnapshot("COAL", 1, null));
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerInventoryAdapter(new SlotInventoryAdapter(vault));
        engine.registerEffectHandler(new ItemOutputHandler(vault, engine));
        ExampleProcessProvider provider = new ExampleProcessProvider(engine, new ExampleGuiListener());
        provider.enable();
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        engine.placeFunctionalBlock(block, ExampleProcessProvider.BLOCK_ID);
        CraftingManagerApi.ProcessStartResult result = engine.start(
                block, ExampleProcessProvider.PROCESS_ID, owner);
        assertTrue(result.started(), result.reason());
        ProcessState state = ProcessState.RUNNING;
        for (int i = 0; i < 200 && state == ProcessState.RUNNING; i++) {
            state = engine.advance(result.instanceId()).toCompletableFuture().join();
        }
        assertEquals(ProcessState.COMPLETED, state);
        assertEquals(new ItemSnapshot("IRON_NUGGET", 1, null),
                engine.extractAt(block, ProcessFace.DOWN, 1).orElseThrow());
        provider.disable();
    }

    @Test void providerDisableRemovesProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        ExampleProcessProvider provider = new ExampleProcessProvider(engine, new ExampleGuiListener());
        provider.enable();
        provider.disable();
        assertTrue(engine.process(ExampleProcessProvider.PROCESS_ID).isEmpty());
    }
}
