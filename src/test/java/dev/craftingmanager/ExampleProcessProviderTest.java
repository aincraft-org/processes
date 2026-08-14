package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.ExampleProcessProvider;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExampleProcessProviderTest {
    @Test void providerRegistersRunnableProcessDependencies() {
        RuntimeEngine engine = new RuntimeEngine();
        ExampleProcessProvider provider = new ExampleProcessProvider(engine, new ExampleGuiListener());
        provider.enable();
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        CraftingManagerApi.ProcessStartResult result = engine.start(
                block, ExampleProcessProvider.PROCESS_ID, UUID.randomUUID());
        assertTrue(result.started(), result.reason());
        assertEquals("started", result.reason());
        engine.invalidateBlock(block);
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
