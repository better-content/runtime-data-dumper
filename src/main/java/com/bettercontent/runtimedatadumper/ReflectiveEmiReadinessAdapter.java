package com.bettercontent.runtimedatadumper;

import java.lang.reflect.Method;

/** Exact optional-EMI boundary used only by the Debug world probe. */
final class ReflectiveEmiReadinessAdapter {
    private static Method isLoaded;

    private ReflectiveEmiReadinessAdapter() {}

    static boolean isLoaded() {
        try {
            if (isLoaded == null) {
                isLoaded = Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            }
            return (boolean) isLoaded.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Debug world save cannot inspect EMI recipe readiness", error);
        }
    }
}
