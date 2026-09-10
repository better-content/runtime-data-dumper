package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Exact, cached fallback for Fabric API's otherwise-unencodable empty biome modifier. */
final class ReflectiveFabricBiomeModifierAdapter {
    private static final String FAMILY =
            "net.fabricmc.fabric.impl.biome.modification.BiomeModificationImpl$FabricBiomeModifier";
    private static final ClassValue<Optional<Method>> MODIFIERS = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            if (!FAMILY.equals(type.getName())) return Optional.empty();
            try {
                return Optional.of(type.getMethod("modifiers"));
            } catch (ReflectiveOperationException | LinkageError error) {
                RecipeGraphMod.LOGGER.warn(
                        "Fabric biome modifier adapter rejected {}: {}", type.getName(), error.toString());
                return Optional.empty();
            }
        }
    };

    private ReflectiveFabricBiomeModifierAdapter() {}

    static JsonObject encodeIfEmpty(Object value) {
        Optional<Method> accessor = MODIFIERS.get(value.getClass());
        if (accessor.isEmpty()) return null;
        try {
            Object modifiers = accessor.get().invoke(value);
            if (!(modifiers instanceof List<?> list) || !list.isEmpty()) return null;
            JsonObject encoded = new JsonObject();
            encoded.addProperty("type", "fabric_biome_api_v1:empty_modifier");
            encoded.add("modifiers", new JsonArray());
            JsonObject row = new JsonObject();
            row.addProperty("java_class", value.getClass().getName());
            row.addProperty("normalization_adapter", "fabric_biome_api_v1:empty_modifier");
            row.add("value", encoded);
            return row;
        } catch (IllegalAccessException | InvocationTargetException error) {
            RecipeGraphMod.LOGGER.warn(
                    "Fabric biome modifier accessor failed {}#modifiers: {}",
                    value.getClass().getName(), error.toString());
            return null;
        }
    }
}
