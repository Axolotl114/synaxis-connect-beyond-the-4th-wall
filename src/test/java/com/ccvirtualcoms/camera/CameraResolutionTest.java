package com.ccvirtualcoms.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * Pure JVM tests for {@link CameraResolution}: preset bookkeeping and the server side clamps.
 *
 * <p>No Minecraft classes are touched, so this test runs both under MDG's {@code unitTest} mode and
 * in a plain JUnit 5 setup.
 *
 * <p><b>Design decision (2026-11-13):</b> the {@code width * height * fps} budget no longer shrinks
 * the stored resolution. {@link CameraResolution#sanitizedForFps(int, int, int)} only applies the
 * legal-size clamps/even rounding; the frame scheduler degrades the effective frame rate instead.
 * {@link CameraResolution#fitsBudget(int)} / {@link CameraResolution#pixelRate(int)} remain as the
 * advisory metric the GUI uses for its warning.
 */
class CameraResolutionTest {

    private static void assertResolution(int expectedWidth, int expectedHeight, CameraResolution actual) {
        assertEquals(expectedWidth, actual.width(), "width");
        assertEquals(expectedHeight, actual.height(), "height");
    }

    // ------------------------------------------------------------------ defaults and presets

    @Test
    void defaultIs640x480AndAPreset() {
        assertResolution(640, 480, CameraResolution.DEFAULT);
        assertTrue(CameraResolution.DEFAULT.isPreset());
        assertEquals(CameraResolution.DEFAULT, CameraResolution.DEFAULT.presetOrNull());
    }

    @Test
    void maxHeightAllowsTheTallestFourByThreePreset() {
        assertEquals(1440, CameraResolution.MAX_HEIGHT);
        assertTrue(CameraResolution.PRESETS_4_3.contains(new CameraResolution(1920, 1440)),
                "1920x1440 must survive normalisation, not be clamped to 1920x1080");
    }

    @Test
    void presetListsAreNotEmptyAndCoverBothAspectRatios() {
        assertTrue(CameraResolution.PRESETS_4_3.size() >= 5, "4:3 presets");
        assertTrue(CameraResolution.PRESETS_16_9.size() >= 5, "16:9 presets");
        assertEquals(
                CameraResolution.PRESETS_4_3.size() + CameraResolution.PRESETS_16_9.size(),
                CameraResolution.PRESETS.size(),
                "PRESETS is the concatenation of both lists");
    }

    @Test
    void presetsContainNoDuplicates() {
        assertEquals(CameraResolution.PRESETS.size(), new HashSet<>(CameraResolution.PRESETS).size(),
                "PRESETS must not contain duplicate sizes: " + CameraResolution.PRESETS);
    }

    @Test
    void fourByThreePresetsAreExactFourByThreeSizes() {
        for (CameraResolution preset : CameraResolution.PRESETS_4_3) {
            assertEquals(preset.width() * 3L, preset.height() * 4L,
                    "4:3 preset is not 4:3 after normalisation: " + preset);
        }
    }

    @Test
    void everyPresetIsNormalisedEvenAndWithinBounds() {
        for (CameraResolution preset : CameraResolution.PRESETS) {
            // The record constructor is the normaliser, so a preset must equal its re-construction.
            assertEquals(preset, new CameraResolution(preset.width(), preset.height()),
                    "preset " + preset + " is not normalised");
            assertEquals(0, preset.width() % 2, "preset width must be even: " + preset);
            assertEquals(0, preset.height() % 2, "preset height must be even: " + preset);
            assertTrue(preset.width() >= CameraResolution.MIN_DIMENSION, "preset below min width: " + preset);
            assertTrue(preset.height() >= CameraResolution.MIN_DIMENSION, "preset below min height: " + preset);
            assertTrue(preset.width() <= CameraResolution.MAX_WIDTH, "preset above max width: " + preset);
            assertTrue(preset.height() <= CameraResolution.MAX_HEIGHT, "preset above max height: " + preset);
        }
    }

    @Test
    void everyPresetIdentifiesItselfAndHasALabel() {
        for (CameraResolution preset : CameraResolution.PRESETS) {
            assertTrue(preset.isPreset(), "preset should report itself as preset: " + preset);
            assertNotNull(preset.presetOrNull());
            assertEquals(preset.width() + "x" + preset.height(), preset.label());
        }
    }

    @Test
    void customSizeIsNotAPreset() {
        CameraResolution custom = new CameraResolution(800, 800);
        assertFalse(custom.isPreset());
        assertNull(custom.presetOrNull());
    }

    // ------------------------------------------------------------------ sanitised (server side trust boundary)

    @Test
    void sanitizedClampsToMinimum() {
        assertResolution(64, 64, CameraResolution.sanitized(0, 0));
        assertResolution(64, 64, CameraResolution.sanitized(-100, 10));
        assertResolution(64, 64, CameraResolution.sanitized(Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void sanitizedClampsToMaximum() {
        assertResolution(1920, 1440, CameraResolution.sanitized(9999, 9999));
    }

    @Test
    void sanitizedRoundsOddDimensionsDownToEven() {
        assertResolution(640, 480, CameraResolution.sanitized(641, 481));
        assertResolution(64, 64, CameraResolution.sanitized(63, 63));
    }

    // ------------------------------------------------------------------ pixel budget (advisory)

    @Test
    void pixelRateIsWidthTimesHeightTimesMaxOneFps() {
        CameraResolution resolution = new CameraResolution(640, 480);
        assertEquals(640L * 480L, resolution.pixelRate(0), "fps below 1 is treated as 1");
        assertEquals(640L * 480L * 30L, resolution.pixelRate(30));
    }

    @Test
    void fitsBudgetAtTheReferenceWorkload() {
        CameraResolution reference = new CameraResolution(1280, 720);
        assertEquals(CameraResolution.PIXEL_BUDGET_PER_SECOND, reference.pixelRate(30),
                "1280x720@30 is the reference workload and must exactly fill the budget");
        assertTrue(reference.fitsBudget(30));
        assertFalse(reference.fitsBudget(45));
        assertFalse(new CameraResolution(1920, 1080).fitsBudget(30));
        assertTrue(new CameraResolution(640, 480).fitsBudget(60));
    }

    @Test
    void budgetIsAdvisoryAndDoesNotShrinkTheStoredResolution() {
        CameraResolution uhd = new CameraResolution(1920, 1440);
        assertFalse(uhd.fitsBudget(30), "1920x1440@30 is over budget - GUI warns, scheduler degrades fps");

        assertEquals(uhd, CameraResolution.sanitizedForFps(1920, 1440, 30));
        assertEquals(uhd, CameraResolution.sanitizedForFps(1920, 1440, 60));
        assertFalse(CameraResolution.sanitizedForFps(1920, 1440, 60).fitsBudget(60));
    }

    // ------------------------------------------------------------------ sanitizedForFps keeps the size

    @Test
    void sanitizedForFpsKeepsTheRequestedResolutionAndOnlyNormalisesIt() {
        // Budget is irrelevant here now: the frame scheduler owns the fps degradation.
        assertResolution(1920, 1080, CameraResolution.sanitizedForFps(1920, 1080, 60));
        assertResolution(1920, 1440, CameraResolution.sanitizedForFps(1920, 1440, 30));
        assertResolution(1600, 1200, CameraResolution.sanitizedForFps(1600, 1200, 60));
        assertResolution(1280, 720, CameraResolution.sanitizedForFps(1280, 720, 45));

        // ... but it still clamps/rounds like the canonical constructor.
        assertResolution(1920, 1440, CameraResolution.sanitizedForFps(9999, 9999, 60));
        assertResolution(1920, 1440, CameraResolution.sanitizedForFps(1920, 1441, 60));
        assertResolution(64, 64, CameraResolution.sanitizedForFps(0, 0, 60));
        assertResolution(64, 64, CameraResolution.sanitizedForFps(-100, 10, 60));
    }

    @Test
    void sanitizedForFpsAlwaysReturnsALegalEvenSize() {
        int[][] requests = {
                {1920, 1080}, {1920, 1440}, {1601, 901}, {64, 64}, {63, 63}, {0, 0}, {-5, -5},
                {Integer.MAX_VALUE, Integer.MIN_VALUE}, {1280, 720},
        };
        for (int[] request : requests) {
            for (int fps : CameraResolution.FPS_VALUES) {
                CameraResolution result = CameraResolution.sanitizedForFps(request[0], request[1], fps);
                assertEquals(0, result.width() % 2, "width must be even: " + result);
                assertEquals(0, result.height() % 2, "height must be even: " + result);
                assertTrue(result.width() >= CameraResolution.MIN_DIMENSION, "width below min: " + result);
                assertTrue(result.height() >= CameraResolution.MIN_DIMENSION, "height below min: " + result);
                assertTrue(result.width() <= CameraResolution.MAX_WIDTH, "width above max: " + result);
                assertTrue(result.height() <= CameraResolution.MAX_HEIGHT, "height above max: " + result);
            }
        }
    }

    @Test
    void everyPresetSurvivesSanitizedForFpsAtEverySupportedFps() {
        for (CameraResolution preset : CameraResolution.PRESETS) {
            for (int fps : CameraResolution.FPS_VALUES) {
                assertEquals(preset, CameraResolution.sanitizedForFps(preset.width(), preset.height(), fps),
                        "preset " + preset + " must not be resized for " + fps + " fps");
            }
        }
    }
}
