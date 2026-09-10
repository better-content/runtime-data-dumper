package com.bettercontent.runtimedatadumper;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact, cached reflective adapter for PneumaticCraft's pressure-chamber display contract. */
final class ReflectivePneumaticRecipeAdapter {
    private static final AtomicInteger RESOLUTIONS = new AtomicInteger();
    private static final ClassValue<Optional<Accessors>> ACCESSORS = new ClassValue<>() {
        @Override protected Optional<Accessors> computeValue(Class<?> type) {
            RESOLUTIONS.incrementAndGet();
            try {
                return Optional.of(new Accessors(
                        type.getMethod("getInputsForDisplay"),
                        type.getMethod("getResultsForDisplay"),
                        type.getMethod("getCraftingPressureForDisplay")));
            } catch (ReflectiveOperationException | LinkageError error) {
                RecipeGraphMod.LOGGER.warn(
                        "PneumaticCraft recipe adapter rejected {}: {}", type.getName(), error.toString());
                return Optional.empty();
            }
        }
    };

    private ReflectivePneumaticRecipeAdapter() {}

    static boolean supports(Object recipe, String recipeType) {
        return "pneumaticcraft:pressure_chamber".equals(recipeType)
                && ACCESSORS.get(recipe.getClass()).isPresent();
    }

    @SuppressWarnings("unchecked")
    static List<Ingredient> inputs(Object recipe) throws ReflectiveOperationException {
        return (List<Ingredient>) invoke(recipe, accessors(recipe).inputs());
    }

    @SuppressWarnings("unchecked")
    static List<List<ItemStack>> outputs(Object recipe) throws ReflectiveOperationException {
        return (List<List<ItemStack>>) invoke(recipe, accessors(recipe).outputs());
    }

    static float pressure(Object recipe) throws ReflectiveOperationException {
        return ((Number) invoke(recipe, accessors(recipe).pressure())).floatValue();
    }

    static int resolutionCountForTests() {
        return RESOLUTIONS.get();
    }

    private static Accessors accessors(Object recipe) throws NoSuchMethodException {
        return ACCESSORS.get(recipe.getClass()).orElseThrow(() ->
                new NoSuchMethodException("Unsupported PneumaticCraft recipe family " + recipe.getClass().getName()));
    }

    private static Object invoke(Object target, Method method) throws ReflectiveOperationException {
        try {
            return method.invoke(target);
        } catch (InvocationTargetException error) {
            RecipeGraphMod.LOGGER.warn(
                    "PneumaticCraft recipe accessor failed {}#{}: {}",
                    target.getClass().getName(), method.getName(), String.valueOf(error.getCause()));
            throw error;
        }
    }

    private record Accessors(Method inputs, Method outputs, Method pressure) {}
}
