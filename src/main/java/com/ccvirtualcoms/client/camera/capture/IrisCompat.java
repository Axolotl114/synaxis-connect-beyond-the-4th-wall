package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import java.lang.reflect.Method;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Iris detection without a compile-time or run-time hard dependency on Iris.
 *
 * <p>Everything goes through {@link ModList} (which never links an Iris class) and reflection on
 * Iris' stable public API v0 ({@code net.irisshaders.iris.api.v0.IrisApi}) plus one internal getter
 * for the active pack name. Any failure degrades to "no shaders", which is the safe direction: the
 * capture pipeline then uses the off-screen path instead of assuming a shader pipeline that is not
 * there.
 *
 * <p>Why detection matters at all: Iris renders its final image into the main render target and
 * rebinds it even when shaders are off (see the research notes), so a self-contained render target
 * only produces usable pixels when Iris is absent.
 */
public final class IrisCompat {

    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_CLASS = "net.irisshaders.iris.Iris";

    /** Cached install state; the mod list does not change at runtime. */
    private static volatile Boolean installed;

    private static volatile boolean reflectionBroken;
    private static boolean reportedFailure;

    private static Object apiInstance;
    private static Method isShaderPackInUse;
    private static Method isRenderingShadowPass;

    private IrisCompat() {
    }

    /** {@code true} when Iris or Oculus is installed, regardless of whether shaders are enabled. */
    public static boolean irisInstalled() {
        Boolean cached = installed;
        if (cached == null) {
            cached = isModLoaded("iris") || isModLoaded("oculus");
            installed = cached;
        }
        return cached;
    }

    /** {@code true} when a shader pack is loaded and active, per Iris' public API. */
    public static boolean shaderPackInUse() {
        if (!irisInstalled() || reflectionBroken) {
            return false;
        }
        try {
            Object api = api();
            if (api == null) {
                return false;
            }
            if (isShaderPackInUse == null) {
                isShaderPackInUse = api.getClass().getMethod("isShaderPackInUse");
            }
            return Boolean.TRUE.equals(isShaderPackInUse.invoke(api));
        } catch (Throwable failure) {
            reportFailure(failure);
            return false;
        }
    }

    /** {@code true} while Iris renders its shadow pass; capturing then would grab the sun view. */
    public static boolean isRenderingShadowPass() {
        if (!irisInstalled() || reflectionBroken) {
            return false;
        }
        try {
            Object api = api();
            if (api == null) {
                return false;
            }
            if (isRenderingShadowPass == null) {
                isRenderingShadowPass = api.getClass().getMethod("isRenderingShadowPass");
            }
            return Boolean.TRUE.equals(isRenderingShadowPass.invoke(api));
        } catch (Throwable failure) {
            reportFailure(failure);
            return false;
        }
    }

    /** The active shader pack name (for example {@code photon-slo}), or an empty string. */
    public static String currentPackName() {
        if (!irisInstalled() || reflectionBroken) {
            return "";
        }
        try {
            Class<?> iris = Class.forName(IRIS_CLASS);
            Method getter = iris.getMethod("getCurrentPackName");
            Object value = getter.invoke(null);
            return value instanceof String name ? name : "";
        } catch (Throwable failure) {
            // Pack name is diagnostic only; do not mark reflection as broken for it.
            return "";
        }
    }

    /** One-line description for logs and the debug command. */
    public static String describe() {
        if (!irisInstalled()) {
            return "iris=absent";
        }
        return "iris=present shaders=" + shaderPackInUse()
                + (currentPackName().isEmpty() ? "" : " pack=" + currentPackName())
                + (reflectionBroken ? " reflection=failed" : "");
    }

    /** Resets cached state; used by tests only. */
    static void resetCacheForTests() {
        installed = null;
        reflectionBroken = false;
        reportedFailure = false;
        apiInstance = null;
        isShaderPackInUse = null;
        isRenderingShadowPass = null;
    }

    private static @Nullable Object api() {
        if (apiInstance != null) {
            return apiInstance;
        }
        try {
            Class<?> apiClass = Class.forName(IRIS_API_CLASS);
            Method getInstance = apiClass.getMethod("getInstance");
            apiInstance = getInstance.invoke(null);
            return apiInstance;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Iris API is unavailable", failure);
        }
    }

    private static void reportFailure(Throwable failure) {
        reflectionBroken = true;
        if (!reportedFailure) {
            reportedFailure = true;
            CCVirtualComs.LOGGER.warn("[camera] Iris detection failed; assuming no shader pipeline. {}",
                    failure.toString());
        }
    }

    private static boolean isModLoaded(String modId) {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
