package dev.sfmgraph.client;

import net.neoforged.fml.ModList;

/**
 * Checks for optional mod support without ever touching their classes.
 *
 * <p>Kept in the client package so the graph and compiler packages stay free of loader dependencies.
 */
public final class OptionalMods {

    private static final String MEKANISM = "mekanism";

    private OptionalMods() {
    }

    /** Mekanism registers SFM's chemical resource types, so chemical links only work when it is there. */
    public static boolean hasChemicals() {
        try {
            return ModList.get().isLoaded(MEKANISM);
        } catch (Throwable t) {
            return false;
        }
    }
}
