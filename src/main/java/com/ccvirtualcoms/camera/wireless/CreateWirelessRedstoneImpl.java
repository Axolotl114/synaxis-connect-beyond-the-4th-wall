package com.ccvirtualcoms.camera.wireless;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Isolated reflective implementation of the Create wireless redstone read.
 *
 * <p>Kept in a separate class so that a {@link Throwable} from Create's internals only disables
 * this bridge instead of breaking the camera block. The exact classes involved are
 * {@code com.simibubi.create.Create#REDSTONE_LINK_NETWORK_HANDLER},
 * {@code RedstoneLinkNetworkHandler#getNetworkOf(Level, IRedstoneLinkable)} and
 * {@code RedstoneLinkNetworkHandler.Frequency#of(ItemStack)}.
 */
final class CreateWirelessRedstoneImpl {

    private CreateWirelessRedstoneImpl() {
    }

    static int queryStrength(Level level, ItemStack frequencyA, ItemStack frequencyB) {
        // Wired in with the trigger-integration milestone; until then the reflective lookup is
        // intentionally a no-op so the camera always falls back to neighbour redstone.
        return 0;
    }
}
