package com.ccvirtualcoms.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CameraConfig}: documented defaults, immutable copy helpers, the server side
 * {@link CameraConfig#sanitized()} clamps and the content aware {@code signature}/{@code equals}.
 *
 * <p><b>Classpath note:</b> this test touches {@link ItemStack} (the two wireless frequency slots),
 * so it requires the Minecraft/FML test classpath that MDG's {@code unitTest} mode provides. The
 * other three tests in this source set are pure JDK.
 *
 * <p><b>Design decisions (2026-11-13):</b>
 * <ul>
 *   <li>{@code sanitized()} no longer shrinks the resolution to meet the pixel budget; the frame
 *       scheduler degrades the effective frame rate instead, so the user always gets the size they
 *       selected (within the legal size clamps).</li>
 *   <li>{@code signature()}/{@code equals()} are content aware: frequency stacks contribute
 *       count + item + component hash, and offsets use full {@link Double#toString()} precision.</li>
 * </ul>
 */
class CameraConfigTest {

    /**
     * Creates an item stack that needs the item registry to be bootstrapped.
     *
     * <p>If the test harness did not bootstrap Minecraft (e.g. the test is accidentally run outside
     * MDG's {@code unitTest} mode), the test is skipped with an explicit message instead of failing
     * with an obscure linker error.
     */
    private static ItemStack registeredStack(Supplier<ItemStack> supplier) {
        try {
            return supplier.get();
        } catch (Throwable failure) {
            Assumptions.assumeTrue(false, "requires a bootstrapped item registry (MDG unitTest): " + failure);
            throw new AssertionError(failure);
        }
    }

    private static CameraConfig raw(int fps, boolean color, boolean nightVision, boolean redstoneControl,
                                    boolean holdOn, CameraRedstoneMode redstoneMode, int threshold,
                                    CameraAxisMode yaw, CameraAxisMode pitch, CameraAxisMode roll,
                                    double offsetX, double offsetY, double offsetZ,
                                    CameraRenderMode renderMode) {
        return new CameraConfig(CameraConfig.DEFAULT.resolution(), fps, color, nightVision, redstoneControl,
                holdOn, redstoneMode, threshold, ItemStack.EMPTY, ItemStack.EMPTY, yaw, pitch, roll,
                offsetX, offsetY, offsetZ, renderMode);
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void defaultsMatchTheDocumentedStartValues() {
        CameraConfig config = CameraConfig.DEFAULT;

        assertEquals(CameraResolution.DEFAULT, config.resolution());
        assertEquals(30, config.fps());
        assertTrue(config.color());
        assertFalse(config.nightVision());
        assertFalse(config.redstoneControl());
        assertFalse(config.holdOn());
        assertEquals(CameraRedstoneMode.HOLD, config.redstoneMode());
        assertEquals(0, config.redstoneThreshold());
        assertTrue(config.frequencyA().isEmpty());
        assertTrue(config.frequencyB().isEmpty());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.yawMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.pitchMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.rollMode());
        assertEquals(0.0D, config.offsetX());
        assertEquals(0.0D, config.offsetY());
        assertEquals(0.0D, config.offsetZ());
        assertEquals(CameraRenderMode.FOLLOW_PLAYER, config.renderMode());
    }

    @Test
    void nullComponentsFallBackToTheDefaults() {
        CameraConfig config = new CameraConfig(null, 30, true, false, false, false,
                null, 0, null, null, null, null, null, 0, 0, 0, null);

        assertEquals(CameraResolution.DEFAULT, config.resolution());
        assertEquals(CameraRedstoneMode.HOLD, config.redstoneMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.yawMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.pitchMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, config.rollMode());
        assertEquals(CameraRenderMode.FOLLOW_PLAYER, config.renderMode());
        assertTrue(config.frequencyA().isEmpty());
        assertTrue(config.frequencyB().isEmpty());
    }

    // ------------------------------------------------------------------ copy helpers

    @Test
    void withHelpersChangeOnlyTheTargetField() {
        CameraConfig base = CameraConfig.DEFAULT;
        CameraResolution newResolution = new CameraResolution(1280, 720);

        CameraConfig changed = base.withResolution(newResolution);
        assertEquals(newResolution, changed.resolution());
        assertEquals(base.fps(), changed.fps());
        assertEquals(base.color(), changed.color());
        assertEquals(base.signature(), base.withFps(base.fps()).signature());

        assertEquals(60, base.withFps(60).fps());
        assertFalse(base.withColor(false).color());
        assertTrue(base.withNightVision(true).nightVision());
        assertTrue(base.withRedstoneControl(true).redstoneControl());
        assertTrue(base.withHoldOn(true).holdOn());
        assertEquals(CameraRedstoneMode.TOGGLE, base.withRedstoneMode(CameraRedstoneMode.TOGGLE).redstoneMode());
        assertEquals(9, base.withRedstoneThreshold(9).redstoneThreshold());
        assertEquals(CameraRenderMode.OFF, base.withRenderMode(CameraRenderMode.OFF).renderMode());
        assertEquals(new CameraResolution(800, 600),
                base.withResolution(new CameraResolution(800, 600)).resolution());

        CameraConfig axes = base.withAxisModes(CameraAxisMode.FOLLOW_SUBLEVEL, CameraAxisMode.WORLD_STABLE,
                CameraAxisMode.FOLLOW_SUBLEVEL);
        assertEquals(CameraAxisMode.FOLLOW_SUBLEVEL, axes.yawMode());
        assertEquals(CameraAxisMode.WORLD_STABLE, axes.pitchMode());
        assertEquals(CameraAxisMode.FOLLOW_SUBLEVEL, axes.rollMode());

        CameraConfig offset = base.withOffset(1.5D, -2.5D, 3.5D);
        assertEquals(1.5D, offset.offsetX());
        assertEquals(-2.5D, offset.offsetY());
        assertEquals(3.5D, offset.offsetZ());
        assertEquals(base.resolution(), offset.resolution(), "withOffset must not touch other fields");
    }

    @Test
    void withResolutionNullFallsBackToTheDefaultResolution() {
        assertEquals(CameraResolution.DEFAULT, CameraConfig.DEFAULT.withResolution(null).resolution());
    }

    // ------------------------------------------------------------------ clamps

    @Test
    void fpsMustBeOneOfTheSupportedValues() {
        for (int allowed : CameraResolution.FPS_VALUES) {
            assertEquals(allowed, CameraConfig.DEFAULT.withFps(allowed).fps());
        }
        for (int unsupported : new int[] {0, 1, 29, 31, 61, 120, -30}) {
            assertEquals(30, CameraConfig.DEFAULT.withFps(unsupported).fps(),
                    "unsupported fps " + unsupported + " should clamp to 30");
        }
        assertEquals(30, raw(29, true, false, false, false, CameraRedstoneMode.HOLD, 0,
                CameraAxisMode.WORLD_STABLE, CameraAxisMode.WORLD_STABLE, CameraAxisMode.WORLD_STABLE,
                0, 0, 0, CameraRenderMode.FOLLOW_PLAYER).fps());
    }

    @Test
    void redstoneThresholdIsClampedTo0Through15() {
        assertEquals(0, CameraConfig.DEFAULT.withRedstoneThreshold(-5).redstoneThreshold());
        assertEquals(0, CameraConfig.DEFAULT.withRedstoneThreshold(0).redstoneThreshold());
        assertEquals(9, CameraConfig.DEFAULT.withRedstoneThreshold(9).redstoneThreshold());
        assertEquals(15, CameraConfig.DEFAULT.withRedstoneThreshold(15).redstoneThreshold());
        assertEquals(15, CameraConfig.DEFAULT.withRedstoneThreshold(99).redstoneThreshold());
    }

    @Test
    void offsetsAreClampedToMaxOffset() {
        CameraConfig config = CameraConfig.DEFAULT.withOffset(100.0D, -100.0D, 2.0D);
        assertEquals(CameraConfig.MAX_OFFSET, config.offsetX());
        assertEquals(-CameraConfig.MAX_OFFSET, config.offsetY());
        assertEquals(2.0D, config.offsetZ());

        assertEquals(0.0D, CameraConfig.DEFAULT.withOffset(Double.NaN, 0, 0).offsetX());
        assertEquals(0.0D, CameraConfig.DEFAULT.withOffset(Double.POSITIVE_INFINITY, 0, 0).offsetX());
        assertEquals(0.0D, CameraConfig.DEFAULT.withOffset(0, Double.NEGATIVE_INFINITY, 0).offsetY());
    }

    // ------------------------------------------------------------------ sanitized (server trust boundary)

    @Test
    void sanitizedKeepsTheRequestedResolutionEvenWhenItIsOverBudget() {
        CameraConfig requested = CameraConfig.DEFAULT
                .withResolution(new CameraResolution(1920, 1080))
                .withFps(60);

        CameraConfig safe = requested.sanitized();

        assertEquals(new CameraResolution(1920, 1080), safe.resolution(),
                "resolution must not be silently shrunk; the frame scheduler degrades the effective fps");
        assertEquals(60, safe.fps());
        assertFalse(safe.resolution().fitsBudget(safe.fps()),
                "1920x1080@60 is over budget - fitsBudget is only the GUI warning metric");
        assertEquals(requested, safe, "an in-range request survives sanitisation unchanged");
    }

    @Test
    void sanitizedKeepsTheTallFourByThreePreset() {
        CameraConfig requested = CameraConfig.DEFAULT
                .withResolution(new CameraResolution(1920, 1440))
                .withFps(30);

        CameraConfig safe = requested.sanitized();

        assertEquals(new CameraResolution(1920, 1440), safe.resolution());
        assertEquals(30, safe.fps());
    }

    @Test
    void sanitizedIsIdempotent() {
        CameraConfig once = CameraConfig.DEFAULT
                .withResolution(new CameraResolution(1920, 1440))
                .withFps(45)
                .sanitized();
        assertEquals(new CameraResolution(1920, 1440), once.resolution());
        assertEquals(once, once.sanitized());
    }

    @Test
    void sanitizedPreservesFlagsModesAndClampedFieldValues() {
        CameraConfig requested = CameraConfig.DEFAULT
                .withColor(false)
                .withNightVision(true)
                .withRedstoneControl(true)
                .withHoldOn(true)
                .withRedstoneMode(CameraRedstoneMode.TOGGLE)
                .withRedstoneThreshold(9)
                .withAxisModes(CameraAxisMode.FOLLOW_SUBLEVEL, CameraAxisMode.WORLD_STABLE,
                        CameraAxisMode.FOLLOW_SUBLEVEL)
                .withOffset(1.5D, -2.5D, 3.5D)
                .withRenderMode(CameraRenderMode.ON);

        assertEquals(requested, requested.sanitized(),
                "a request that is already legal must survive sanitisation unchanged");
    }

    // ------------------------------------------------------------------ derived flags

    @Test
    void derivedTriggerHelpersReflectTheFields() {
        CameraConfig config = CameraConfig.DEFAULT.withHoldOn(true).withRedstoneControl(true);
        assertTrue(config.holdState());
        assertTrue(config.usesRedstoneInput());
        assertFalse(config.hasWirelessFrequency());

        CameraConfig withEmptyFrequencies = CameraConfig.DEFAULT.withFrequencies(null, null);
        assertFalse(withEmptyFrequencies.hasWirelessFrequency());
        assertTrue(withEmptyFrequencies.frequencyA().isEmpty());
        assertTrue(withEmptyFrequencies.frequencyB().isEmpty());
    }

    // ------------------------------------------------------------------ signature / equals

    @Test
    void signatureIsAStableSnapshotWithEighteenFields() {
        String[] fields = CameraConfig.DEFAULT.signature().split("\\|", -1);
        assertEquals(18, fields.length, "signature field count (see CameraConfig#signature)");
        assertEquals("640", fields[0]);
        assertEquals("480", fields[1]);
        assertEquals("30", fields[2]);
        assertEquals("empty", fields[9], "frequency A signature");
        assertEquals("empty", fields[10], "frequency B signature");
        assertEquals("0.0", fields[14], "offset X uses Double.toString");
        assertEquals("0.0", fields[15], "offset Y uses Double.toString");
        assertEquals("0.0", fields[16], "offset Z uses Double.toString");
        assertTrue(CameraConfig.DEFAULT.signature().contains("|hold|"),
                "redstone mode serialized name: " + CameraConfig.DEFAULT.signature());
        assertTrue(CameraConfig.DEFAULT.signature().endsWith("|follow"),
                "render mode serialized name: " + CameraConfig.DEFAULT.signature());
    }

    @Test
    void signatureOffsetsKeepFullDoublePrecision() {
        CameraConfig config = CameraConfig.DEFAULT.withOffset(0.000123D, -2.5D, 3.25D);
        String[] fields = config.signature().split("\\|", -1);

        assertEquals(Double.toString(0.000123D), fields[14]);
        assertEquals("-2.5", fields[15]);
        assertEquals("3.25", fields[16]);
    }

    @Test
    void signatureUsesSerializedEnumNames() {
        CameraConfig config = CameraConfig.DEFAULT
                .withRedstoneMode(CameraRedstoneMode.TOGGLE)
                .withAxisModes(CameraAxisMode.FOLLOW_SUBLEVEL, CameraAxisMode.FOLLOW_SUBLEVEL,
                        CameraAxisMode.FOLLOW_SUBLEVEL)
                .withRenderMode(CameraRenderMode.ON);

        String signature = config.signature();
        assertTrue(signature.contains("|toggle|"), signature);
        assertTrue(signature.contains("|follow|follow|follow|"), signature);
        assertTrue(signature.endsWith("|on"), signature);
    }

    @Test
    void equalConfigsAreEqualAndShareTheSameHashCode() {
        CameraConfig a = CameraConfig.DEFAULT;
        CameraConfig b = CameraConfig.DEFAULT.withOffset(0.0D, 0.0D, 0.0D);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalityDetectsMeaningfulFieldChanges() {
        CameraConfig base = CameraConfig.DEFAULT;

        assertNotEquals(base, base.withFps(60));
        assertNotEquals(base, base.withColor(false));
        assertNotEquals(base, base.withNightVision(true));
        assertNotEquals(base, base.withRedstoneControl(true));
        assertNotEquals(base, base.withHoldOn(true));
        assertNotEquals(base, base.withRedstoneMode(CameraRedstoneMode.TOGGLE));
        assertNotEquals(base, base.withRedstoneThreshold(5));
        assertNotEquals(base, base.withResolution(new CameraResolution(800, 600)));
        assertNotEquals(base, base.withAxisModes(CameraAxisMode.FOLLOW_SUBLEVEL, CameraAxisMode.WORLD_STABLE,
                CameraAxisMode.WORLD_STABLE));
        assertNotEquals(base, base.withRenderMode(CameraRenderMode.OFF));
        assertNotEquals(base, base.withOffset(1.0D, 0, 0));

        // Decision 2: full precision offsets are distinguishable now.
        assertNotEquals(base.withOffset(0.0001D, 0, 0), base.withOffset(0.0002D, 0, 0));
    }

    // ------------------------------------------------------------------ frequency stacks (content aware)

    @Test
    void differentFrequencyItemsAreDetectedAsAConfigurationChange() {
        ItemStack stick = registeredStack(() -> new ItemStack(Items.STICK));
        ItemStack stone = registeredStack(() -> new ItemStack(Items.STONE));

        CameraConfig withStick = CameraConfig.DEFAULT.withFrequencies(stick, ItemStack.EMPTY);
        CameraConfig withStone = CameraConfig.DEFAULT.withFrequencies(stone, ItemStack.EMPTY);

        assertNotEquals(CameraConfig.DEFAULT, withStick, "a filled frequency slot changes the config");
        assertNotEquals(withStick, withStone, "replacing the frequency item must be detected");
        assertTrue(withStick.hasWirelessFrequency());
        assertTrue(withStick.signature().contains(stick.getItem().toString()),
                "item identity is part of the signature: " + withStick.signature());
    }

    @Test
    void theTwoFrequencySlotsAreIndependent() {
        ItemStack stick = registeredStack(() -> new ItemStack(Items.STICK));

        CameraConfig slotA = CameraConfig.DEFAULT.withFrequencies(stick, ItemStack.EMPTY);
        CameraConfig slotB = CameraConfig.DEFAULT.withFrequencies(ItemStack.EMPTY, stick);

        assertNotEquals(slotA, slotB);
        assertEquals("empty", slotB.signature().split("\\|", -1)[9], "slot A stays empty");
        assertNotEquals("empty", slotB.signature().split("\\|", -1)[10], "slot B is filled");
    }

    @Test
    void frequencyStacksAreNormalisedToOneItem() {
        ItemStack stackOf64 = registeredStack(() -> new ItemStack(Items.STICK, 64));
        ItemStack stackOf1 = registeredStack(() -> new ItemStack(Items.STICK, 1));

        CameraConfig normalised = CameraConfig.DEFAULT.withFrequencies(stackOf64, ItemStack.EMPTY);

        assertEquals(1, normalised.frequencyA().getCount(), "a crafted count must be reduced to one item");
        assertEquals(normalised, CameraConfig.DEFAULT.withFrequencies(stackOf1, ItemStack.EMPTY),
                "count normalisation makes the two configs equivalent");
    }
}
