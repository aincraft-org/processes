package dev.craftingmanager.api;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;

import java.util.List;
import java.util.UUID;

public final class Reservation {
    private Reservation() {}

    public enum Source { PLAYER_INVENTORY, BLOCK_INVENTORY, STATION_SLOT, FUEL_SLOT }
    public enum State { REQUIRED, CLAIMED, RESERVED, CONSUMED, RETURN_PENDING, RETURNED, RELEASED, FAILED }

    public record Claim(Source source, int slot, ItemSnapshot expected, int amount, String inputId, ConsumptionPolicy policy) {
        public Claim(Source source, int slot, ItemSnapshot expected, int amount, String inputId) {
            this(source, slot, expected, amount, inputId, ConsumptionPolicy.CONSUME);
        }

        public Claim {
            if (source == null || expected == null || inputId == null || inputId.isBlank() || policy == null) {
                throw new IllegalArgumentException("claim fields are required");
            }
            if (slot < 0 || amount <= 0 || amount > expected.amount()) throw new IllegalArgumentException("invalid claim bounds");
        }
    }

    public record Intent(UUID instanceId, UUID owner, BlockKey block, List<Claim> claims) {
        public Intent {
            if (instanceId == null || owner == null || block == null) throw new IllegalArgumentException("intent identity is required");
            claims = List.copyOf(claims == null ? List.of() : claims);
        }
    }
}
