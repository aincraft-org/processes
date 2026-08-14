package dev.craftingmanager.api;

import java.util.List;
import java.util.Optional;

public final class RecipeApi {
    private RecipeApi() {}

    public enum Mode { FREEFORM, PATTERN, PROCESS }

    public record Ingredient(String id, String matcher, int amount) {
        public Ingredient {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("ingredient id is required");
            if (matcher == null || matcher.isBlank()) throw new IllegalArgumentException("matcher is required");
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        }
    }

    public record PatternDefinition(List<String> rows) {
        public PatternDefinition {
            rows = List.copyOf(rows == null ? List.of() : rows);
            if (rows.isEmpty()) throw new IllegalArgumentException("pattern cannot be empty");
            int width = rows.getFirst().length();
            if (width == 0 || rows.stream().anyMatch(row -> row.length() != width)) throw new IllegalArgumentException("pattern rows must have equal width");
        }
    }

    public record RecipeDefinition(String id, Mode mode, List<Ingredient> ingredients, Optional<PatternDefinition> pattern, Optional<String> processId) {
        public RecipeDefinition {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("recipe id is required");
            if (mode == null) throw new IllegalArgumentException("recipe mode is required");
            ingredients = List.copyOf(ingredients == null ? List.of() : ingredients);
            pattern = pattern == null ? Optional.empty() : pattern;
            processId = processId == null ? Optional.empty() : processId;
            if (mode == Mode.PATTERN && pattern.isEmpty()) throw new IllegalArgumentException("pattern mode requires a pattern");
            if (mode != Mode.PATTERN && pattern.isPresent()) throw new IllegalArgumentException("only pattern recipes may define a pattern");
            if (mode == Mode.PROCESS && processId.isEmpty()) throw new IllegalArgumentException("process mode requires a process id");
        }
    }
}
