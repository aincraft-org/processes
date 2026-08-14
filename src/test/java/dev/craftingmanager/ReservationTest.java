package dev.craftingmanager;

import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.api.Domain.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReservationTest {
    @Test void capturesExactSourceClaim() {
        ItemSnapshot item = new ItemSnapshot("COAL", 4, null);
        Reservation.Claim claim = new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 7, item, 2, "fuel");
        Reservation.Intent intent = new Reservation.Intent(UUID.randomUUID(), UUID.randomUUID(), new BlockKey(UUID.randomUUID(), -1, 64, 2), List.of(claim));
        assertEquals(7, intent.claims().getFirst().slot());
        assertEquals("fuel", intent.claims().getFirst().inputId());
    }
}
