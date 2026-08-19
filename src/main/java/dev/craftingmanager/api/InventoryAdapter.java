package dev.craftingmanager.api;

import java.util.List;

public interface InventoryAdapter {
    default boolean supports(Domain.BlockKey block, java.util.UUID owner) { return true; }
    List<Reservation.Claim> captureClaims(List<Domain.ProcessInput> inputs);
    default List<Reservation.Claim> captureClaims(
            Domain.BlockKey block, java.util.UUID owner, List<Domain.ProcessInput> inputs) {
        return captureClaims(inputs);
    }
    boolean claimsStillMatch(List<Reservation.Claim> claims);
    default boolean claimsStillMatch(java.util.UUID owner, List<Reservation.Claim> claims) {
        return claimsStillMatch(claims);
    }
    void remove(List<Reservation.Claim> claims);
    default void remove(java.util.UUID owner, List<Reservation.Claim> claims) {
        remove(claims);
    }
    void returnItems(List<Reservation.Claim> claims);
    default void returnItems(java.util.UUID owner, List<Reservation.Claim> claims) {
        returnItems(claims);
    }
}
