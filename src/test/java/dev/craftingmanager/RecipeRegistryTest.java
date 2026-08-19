package dev.craftingmanager;

import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.RecipeApi.Ingredient;
import dev.craftingmanager.api.RecipeApi.Mode;
import dev.craftingmanager.api.RecipeApi.PatternDefinition;
import dev.craftingmanager.api.RecipeApi.RecipeDefinition;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecipeRegistryTest {
    @Test void registersAndMatchesFreeformRecipe() {
        RuntimeEngine engine = new RuntimeEngine();
        RecipeDefinition recipe = new RecipeDefinition(
                "craftingmanager:alloy-ingot",
                Mode.FREEFORM,
                List.of(new Ingredient("iron", "IRON_INGOT", 1), new Ingredient("fuel", "COAL", 1)),
                Optional.empty(),
                Optional.empty());
        engine.registerRecipe(recipe);
        assertEquals(recipe.id(), engine.recipe(recipe.id()).orElseThrow().id());
        assertEquals(1, engine.match(List.of(
                new ItemSnapshot("IRON_INGOT", 1, null),
                new ItemSnapshot("COAL", 1, null))).size());
        assertTrue(engine.match(List.of(new ItemSnapshot("IRON_INGOT", 1, null))).isEmpty());
    }

    @Test void processRecipeMatchesWhenProcessIdPresent() {
        RuntimeEngine engine = new RuntimeEngine();
        RecipeDefinition recipe = new RecipeDefinition(
                "craftingmanager:alloy-process-recipe",
                Mode.PROCESS,
                List.of(new Ingredient("iron", "IRON_INGOT", 1)),
                Optional.empty(),
                Optional.of("craftingmanager:alloy-smelt"));
        engine.registerRecipe(recipe);
        assertEquals(recipe.id(), engine.match(List.of(new ItemSnapshot("IRON_INGOT", 1, null))).getFirst().id());
    }

    @Test void patternRecipeRequiresShapedGrid() {
        RuntimeEngine engine = new RuntimeEngine();
        RecipeDefinition recipe = new RecipeDefinition(
                "grid",
                Mode.PATTERN,
                List.of(new Ingredient("a", "IRON_INGOT", 1)),
                Optional.of(new PatternDefinition(List.of("A"))),
                Optional.empty());
        engine.registerRecipe(recipe);
        assertTrue(engine.match(List.of(new ItemSnapshot("IRON_INGOT", 1, null))).isEmpty());
        assertEquals("grid", engine.matchPattern(new PatternDefinition(List.of("A")),
                List.of(new ItemSnapshot("IRON_INGOT", 1, null))).getFirst().id());
    }
}
