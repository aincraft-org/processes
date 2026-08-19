package dev.craftingmanager;

import dev.craftingmanager.example.ExampleProcessGui;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExampleGuiAssetsTest {
    @Test void guiSlotsResolveCustomPackItemModels() {
        assertEquals("craftingmanager:iron_input", ExampleProcessGui.itemModel(ExampleProcessGui.IRON_SLOT));
        assertEquals("craftingmanager:coal_fuel", ExampleProcessGui.itemModel(ExampleProcessGui.COAL_SLOT));
        assertEquals("craftingmanager:alloy_output", ExampleProcessGui.itemModel(ExampleProcessGui.OUTPUT_SLOT));
        assertEquals("craftingmanager:start_process", ExampleProcessGui.itemModel(ExampleProcessGui.START_SLOT));
    }
}
