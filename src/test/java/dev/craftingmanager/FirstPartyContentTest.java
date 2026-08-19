package dev.craftingmanager;

import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.ExampleProcessProvider;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FirstPartyContentTest {
    @Test void enableRegistersStableAlloySmelter() {
        RuntimeEngine engine = new RuntimeEngine();
        ExampleProcessProvider provider = new ExampleProcessProvider(engine, new ExampleGuiListener());
        provider.enable();
        assertEquals("craftingmanager:alloy-smelt", ExampleProcessProvider.PROCESS_ID);
        assertTrue(engine.process(ExampleProcessProvider.PROCESS_ID).isPresent());
        assertTrue(engine.functionalBlockDefinition(ExampleProcessProvider.BLOCK_ID).isPresent());
        assertTrue(engine.recipe(ExampleProcessProvider.RECIPE_ID).isPresent());
        assertEquals(ExampleProcessProvider.PROCESS_ID,
                engine.recipe(ExampleProcessProvider.RECIPE_ID).orElseThrow().processId().orElseThrow());
    }
}
