package com.ccvirtualcoms.camera.wireless;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Read-only bridge to Create's wireless redstone ("Redstone Link") network.
 *
 * <p>The camera GUI stores up to two Create frequency items. While both are present, the signal
 * strength received on that frequency pair is used as the redstone input instead of the block's
 * six neighbours. The bridge only ever reads: it never registers a terminal, adds itself to the
 * network or mutates Create state, so it cannot interfere with real redstone links.
 *
 * <p>Create's internals are not a stable public API (and its {@code Couple} helper class lives in
 * a nested jar that is not on our compile classpath), so the lookup is performed reflectively in
 * one place. Any failure degrades to "no wireless signal", which makes the camera fall back to its
 * neighbour redstone input instead of crashing.
 */
public final class CreateWirelessRedstone {

    /** Set to true once a lookup has failed, to avoid reflective overhead every tick. */
    private static boolean unavailable;

    private CreateWirelessRedstone() {
    }

    /** {@code true} when the reflective Create bridge failed and neighbour redstone is used. */
    public static boolean unavailable() {
        return unavailable;
    }

    /**
     * Queries the received redstone strength on the given frequency pair.
     *
     * @return 0-15, or 0 when Create is absent, the bridge failed or the slots are not a pair
     */
    public static int queryStrength(Level level, ItemStack frequencyA, ItemStack frequencyB) {
        if (level == null || unavailable || frequencyA == null || frequencyB == null
                || frequencyA.isEmpty() || frequencyB.isEmpty()) {
            return 0;
        }
        try {
            return CreateWirelessRedstoneImpl.queryStrength(level, frequencyA, frequencyB);
        } catch (Throwable failure) {
            unavailable = true;
            com.ccvirtualcoms.CCVirtualComs.LOGGER.warn(
                    "Create wireless redstone bridge unavailable; falling back to neighbour redstone: {}",
                    failure.toString());
            return 0;
        }
    }
}
