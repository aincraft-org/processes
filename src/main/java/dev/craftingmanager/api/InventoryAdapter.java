package dev.craftingmanager.api;

import java.util.List;

public interface InventoryAdapter {
    default boolean supports(Domain.BlockKey block, java.util.UUID owner) { return true; }
    List<Reservation.Claim> captureClaims(List<Domain.ProcessInput> inputs);
    boolean claimsStillMatch(List<Reservation.Claim> claims);
    void remove(List<Reservation.Claim> claims);
    void returnItems(List<Reservation.Claim> claims);
}
