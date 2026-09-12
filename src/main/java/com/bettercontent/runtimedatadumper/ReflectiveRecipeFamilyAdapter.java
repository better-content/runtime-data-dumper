package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The single reflective boundary for optional recipe families without stable compile-time APIs.
 * Accessor discovery is cached per concrete recipe class; failures are diagnosed with the exact member.
 */
final class ReflectiveRecipeFamilyAdapter {
    private static final int MAX_DEPTH = 4;
    private static final ClassValue<List<Method>> PUBLIC_METHODS = new ClassValue<>() {
        @Override protected List<Method> computeValue(Class<?> type) {
            return List.copyOf(safeMembers(type::getMethods));
        }
    };
    private static final ClassValue<List<Field>> PUBLIC_FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            return List.copyOf(safeMembers(type::getFields));
        }
    };
    private static final ClassValue<List<Field>> ALL_FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                fields.addAll(safeMembers(cursor::getDeclaredFields));
            }
            fields.sort(Comparator.comparing(Field::toGenericString));
            return List.copyOf(fields);
        }
    };

    private ReflectiveRecipeFamilyAdapter() {}

    static Result inspect(Recipe<?> recipe) {
        Collector collector = new Collector();
        boolean exactFamily = classOrSuperclassNamed(recipe.getClass(),
                "com.simibubi.create.content.processing.recipe.ProcessingRecipe")
                || classOrSuperclassNamed(recipe.getClass(),
                        "slimeknights.tconstruct.library.recipe.melting.MeltingRecipe")
                || classOrSuperclassNamed(recipe.getClass(),
                        "slimeknights.tconstruct.library.recipe.casting.ItemCastingRecipe");
        if (!exactFamily) {
            List<Method> methods = publicMethods(recipe.getClass());
            methods.sort(Comparator.comparing(Method::toGenericString));
            for (Method method : methods) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || method.isBridge()
                        || method.getName().equals("rollResults")) continue;
                Direction direction = direction(method.getName());
                String requirement = requirement(method.getName());
                if (direction == Direction.UNKNOWN && requirement == null) continue;
                try {
                    Object value = method.invoke(recipe);
                    if (requirement != null && value instanceof Number number) {
                        collector.requirement(requirement, number, method.getName() + "()");
                    } else if (direction != Direction.UNKNOWN) {
                        collector.collect(value, direction, method.getName() + "()", 0);
                    }
                } catch (Throwable error) {
                    diagnose(recipe.getClass(), method.getName(), error);
                }
            }
            collector.publicFields(recipe);
        }
        collector.knownRecipeSemantics(recipe);
        return collector.result();
    }

    static boolean classOrSuperclassNamed(Class<?> type, String className) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (cursor.getName().equals(className)) return true;
        }
        return false;
    }

    static void addChance(JsonObject edge, double chance) {
        if (chance < 1.0) edge.addProperty("chance", chance);
    }

    /**
     * Optional recipe classes can expose client-only types in otherwise public
     * method signatures. The JVM resolves those signatures while enumerating
     * methods, so reflection itself can throw a linkage error on a dedicated
     * server. Such a signature is unavailable evidence, not a reason to abort
     * the complete live dump.
     */
    static List<Method> publicMethods(Class<?> type) {
        return new ArrayList<>(PUBLIC_METHODS.get(type));
    }

    static Object invokeNoArg(Object root, String name) {
        if (root == null) return null;
        for (Class<?> cursor = root.getClass(); cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name);
                method.trySetAccessible();
                return method.invoke(root);
            } catch (NoSuchMethodException | LinkageError | SecurityException ignored) {
                // Continue through the hierarchy without resolving unrelated optional signatures.
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static <T> List<T> safeMembers(Supplier<T[]> source) {
        try {
            return new ArrayList<>(List.of(source.get()));
        } catch (LinkageError | SecurityException ignored) {
            return new ArrayList<>();
        }
    }

    private static void diagnose(Class<?> family, String member, Throwable error) {
        RecipeGraphMod.LOGGER.warn(
                "Reflective recipe-family adapter skipped {}#{}: {}",
                family.getName(), member, error.toString());
    }

    static String materialVariantId(String display) {
        String prefix = "MaterialVariant{";
        if (display.startsWith(prefix) && display.endsWith("}")) {
            return display.substring(prefix.length(), display.length() - 1);
        }
        return display;
    }

    static String operationKind(String className) {
        if (isBloodMagicPotionStateMutation(className)) return "potion_flask_state_mutation";
        if (isVanillaSpecialCrafting(className)) return "dynamic_item_state_crafting";
        if (isKnownModSpecialRecipe(className)) return "dynamic_item_state_crafting";
        return switch (className) {
            case "slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe" -> "material_scaled_melting";
            case "slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle" -> "conditional_part_recycling";
            case "slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle" -> "conditional_tool_part_recycling";
            case "slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe" -> "tool_state_mutation";
            case "appeng.recipes.mattercannon.MatterCannonAmmo" -> "matter_cannon_ammo_metadata";
            case "com.almostreliable.unified.recipe.ClientRecipeTracker" -> "non_gameplay_client_recipe_metadata";
            case "com.sammy.malum.common.recipe.SpiritRepairRecipe" -> "spirit_item_repair";
            case "me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl" -> "block_heat_property_metadata";
            case "me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl" -> "fluid_fuel_property_metadata";
            case "com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe" -> "ritual_block_highlight";
            case "wayoftime.bloodmagic.recipe.RecipeLivingDowngrade" -> "living_armor_downgrade_mutation";
            case "com.Polarice3.Goety.common.crafting.BrewingRecipe" -> "entity_brewing_effect";
            case "com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes" -> "soul_absorption";
            case "slimeknights.tconstruct.library.recipe.modifiers.adding.OverslimeCraftingTableRecipe" -> "tool_overslime_restoration";
            case "slimeknights.tconstruct.library.recipe.fuel.MeltingFuel" -> "smeltery_fuel_metadata";
            case "slimeknights.tconstruct.library.recipe.casting.TippingCastingRecipe" -> "potion_tool_tipping";
            case "slimeknights.tconstruct.library.recipe.casting.TipClearingCastingRecipe" -> "potion_tool_tip_clearing";
            case "slimeknights.tconstruct.library.recipe.tinkerstation.repairing.ModifierRepairTinkerStationRecipe" -> "modifier_damage_repair";
            case "slimeknights.tconstruct.tools.recipe.ModifierRemovalRecipe" -> "tool_modifier_removal";
            case "slimeknights.tconstruct.tools.recipe.ExtractModifierRecipe" -> "tool_modifier_extraction";
            case "slimeknights.tconstruct.tools.recipe.EnchantmentConvertingRecipe" -> "enchantment_to_modifier_conversion";
            case "wayoftime.bloodmagic.recipe.RecipeMeteor" -> "ritual_meteor_world_effect";
            case "com.aetherteam.aether.recipe.recipes.ban.ItemBanRecipe",
                 "com.aetherteam.aether.recipe.recipes.ban.BlockBanRecipe" -> "placement_policy_metadata";
            case "net.mehvahdjukaar.supplementaries.common.items.crafting.SusRecipe" -> "item_transform";
            case "com.stal111.forbidden_arcanus.common.recipe.ApplyModifierRecipe" -> "item_modifier_application";
            case "com.hollingsworth.arsnouveau.api.enchanting_apparatus.ArmorUpgradeRecipe" -> "armor_tier_upgrade";
            case "com.stal111.forbidden_arcanus.common.recipe.IncreaseEdelwoodBucketFullnessRecipe" -> "container_fullness_mutation";
            case "org.valkyrienskies.clockwork.content.logistics.gas.crafter.GasCraftingRecipe" -> "gas_reaction";
            case "slimeknights.tconstruct.tables.recipe.TinkerStationPartSwapping" -> "tool_part_replacement";
            case "slimeknights.tconstruct.library.recipe.worktable.ModifierSetWorktableRecipe" -> "tool_modifier_set_mutation";
            case "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe" -> "fluid_brewing";
            case "twilightforest.item.recipe.UncraftingRecipe" -> "item_disassembly";
            case "appeng.recipes.entropy.EntropyRecipe" -> "world_state_transform";
            case "com.hollingsworth.arsnouveau.api.recipe.DispelEntityRecipe" -> "entity_dispel_loot";
            case "com.hollingsworth.arsnouveau.api.enchanting_apparatus.ReactiveEnchantmentRecipe" -> "reactive_enchantment_application";
            case "com.hollingsworth.arsnouveau.api.enchanting_apparatus.SpellWriteRecipe" -> "spell_state_write";
            case "com.hollingsworth.arsnouveau.api.recipe.SummonRitualRecipe" -> "ritual_entity_summoning";
            case "com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe" -> "fan_splashing";
            case "net.mehvahdjukaar.jeed.recipes.EffectProviderRecipe" -> "effect_provider_metadata";
            case "net.mehvahdjukaar.jeed.recipes.PotionProviderRecipe" -> "potion_provider_metadata";
            case "com.accbdd.complicated_bees.recipe.TempUnitRecipe" -> "bee_temperature_tolerance_modifier";
            case "com.accbdd.complicated_bees.recipe.MutatorRecipe" -> "bee_mutation_chance_modifier";
            case "com.accbdd.complicated_bees.recipe.HoneyGeneratorRecipe" -> "honey_generator_fuel";
            case "alexthw.ars_elemental.recipe.NetheriteUpgradeRecipe" -> "spellbook_tier_upgrade";
            default -> null;
        };
    }

    private static boolean isVanillaSpecialCrafting(String className) {
        return className.equals("net.minecraft.world.item.crafting.TippedArrowRecipe")
                || className.equals("net.minecraft.world.item.crafting.SuspiciousStewRecipe")
                || className.equals("net.minecraft.world.item.crafting.ShulkerBoxColoring")
                || className.equals("net.minecraft.world.item.crafting.ShieldDecorationRecipe")
                || className.equals("net.minecraft.world.item.crafting.RepairItemRecipe")
                || className.equals("net.minecraft.world.item.crafting.MapCloningRecipe")
                || className.equals("net.minecraft.world.item.crafting.FireworkStarFadeRecipe")
                || className.equals("net.minecraft.world.item.crafting.DecoratedPotRecipe")
                || className.equals("net.minecraft.world.item.crafting.BookCloningRecipe")
                || className.equals("net.minecraft.world.item.crafting.BannerDuplicateRecipe")
                || className.equals("net.minecraft.world.item.crafting.ArmorDyeRecipe");
    }

    private static boolean isKnownModSpecialRecipe(String className) {
        return switch (className) {
            case "wayoftime.bloodmagic.recipe.RecipeTomeCombine",
                 "wayoftime.bloodmagic.recipe.RecipeFilterCopy",
                 "wayoftime.bloodmagic.recipe.RecipeAnointmentApply",
                 "vectorwing.farmersdelight.common.crafting.FoodServingRecipe",
                 "vectorwing.farmersdelight.common.crafting.DoughRecipe",
                 "twilightforest.item.recipe.MoonwormQueenRepairRecipe",
                 "twilightforest.item.recipe.MazeMapCloningRecipe",
                 "twilightforest.item.recipe.MagicMapCloningRecipe",
                 "slimeknights.tconstruct.tools.recipe.ToggleInteractionWorktableRecipe",
                 "slimeknights.tconstruct.tools.recipe.ModifierSortingRecipe",
                 "slimeknights.tconstruct.tools.recipe.ArmorTrimRecipe",
                 "slimeknights.tconstruct.tools.recipe.ArmorDyeingRecipe",
                 "slimeknights.tconstruct.tables.recipe.TinkerStationRepairRecipe",
                 "slimeknights.tconstruct.tables.recipe.CraftingTableRepairKitRecipe",
                 "org.violetmoon.zeta.recipe.ZetaDyeRecipe",
                 "org.violetmoon.quark.content.tweaks.recipe.SlabToBlockRecipe",
                 "org.valkyrienskies.clockwork.content.contraptions.propeller.blades.item.CraftingTableBladeRecipe",
                 "net.p3pp3rf1y.sophisticatedstorage.crafting.StorageDyeRecipe",
                 "net.p3pp3rf1y.sophisticatedstorage.crafting.FlatTopBarrelToggleRecipe",
                 "net.p3pp3rf1y.sophisticatedstorage.crafting.BarrelMaterialRecipe",
                 "net.p3pp3rf1y.sophisticatedcore.crafting.UpgradeClearRecipe",
                 "net.p3pp3rf1y.sophisticatedbackpacks.crafting.BackpackDyeRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.WeatheredMapRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.TrappedPresentRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.TippedBambooSpikesRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.TatteredBookRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.SoapClearRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.SafeRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.RopeArrowCreateRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.RopeArrowAddRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.RepairBubbleBlowerRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.PresentDyeRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.ItemLoreRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.FlagFromBannerRecipe",
                 "net.mehvahdjukaar.supplementaries.common.items.crafting.BlackboardDuplicateRecipe",
                 "net.mehvahdjukaar.amendments.common.recipe.DyeBottleRecipe",
                 "com.alrex.parcool.common.item.recipe.special.ZiplineRopeDyeRecipe",
                 "net.joefoxe.hexerei.data.recipes.WhistleBindRecipe",
                 "net.joefoxe.hexerei.data.recipes.KeychainUndoRecipe",
                 "net.joefoxe.hexerei.data.recipes.KeychainRecipe",
                 "net.joefoxe.hexerei.data.recipes.FillWaxingKitRecipe",
                 "net.joefoxe.hexerei.data.recipes.CutCandleRecipe",
                 "net.joefoxe.hexerei.data.recipes.CrowAmuletUndoRecipe",
                 "net.joefoxe.hexerei.data.recipes.CrowAmuletRecipe",
                 "net.joefoxe.hexerei.data.recipes.BookOfShadowsDyeRecipe",
                 "dev.murad.shipping.setup.ModRecipeSerializers$2",
                 "dev.murad.shipping.setup.ModRecipeSerializers$1",
                 "dev.lukebemish.excavatedvariants.impl.recipe.OreConversionRecipe",
                 "com.simibubi.create.foundation.recipe.ItemCopyingRecipe",
                 "com.simibubi.create.content.equipment.toolbox.ToolboxDyeingRecipe",
                 "com.klikli_dev.occultism.crafting.recipe.BoundBookOfBindingRecipe",
                 "com.endertech.minecraft.mods.adpother.recipes.FilterChangeRecipe",
                 "com.aetherteam.aether.recipe.recipes.item.SwetBannerRecipe",
                 "com.bettercontent.realisticores.compat.ExcavatedSeparationRecipe",
                 "com.bettercontent.realisticores.compat.ExcavatedReassemblyRecipe",
                 "com.bettercontent.proceduralbouquets.recipe.PottedBouquetRecipe",
                 "com.github.alexthe666.rats.server.recipes.DemonRatSwitchRecipe",
                 "appeng.recipes.game.FacadeRecipe" -> true;
            default -> false;
        };
    }

    private static boolean isBloodMagicPotionStateMutation(String className) {
        return className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionIncreaseLength")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionIncreasePotency")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionEffect")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionTransform")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionFill")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionCycle");
    }

    static Direction direction(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("output") || value.contains("result") || value.contains("byproduct") || value.contains("fluidout")) return Direction.OUTPUT;
        if (value.contains("input") || value.contains("ingredient") || value.contains("reagent") || value.contains("fluidin")) return Direction.INPUT;
        if (value.contains("catalyst") || value.contains("tool")) return Direction.CATALYST;
        return Direction.UNKNOWN;
    }

    static String requirement(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("temperature") || value.equals("getheat") || value.equals("heat")) return "heat";
        if (value.contains("pressure")) return "pressure";
        if (value.contains("time") || value.contains("ticks") || value.contains("duration")) return "time";
        if (value.contains("energy") || value.contains("syphon") || value.contains("mana") || value.contains("sourcecost")) return "energy";
        return null;
    }

    enum Direction { INPUT, OUTPUT, CATALYST, UNKNOWN }

    record Result(
            JsonArray inputGroups,
            JsonArray inputs,
            JsonArray outputGroups,
            JsonArray outputs,
            JsonArray catalysts,
            JsonArray fluidsIn,
            JsonArray fluidsOut,
            String operationKind,
            JsonArray effects,
            JsonObject requirements,
            JsonArray evidence,
            boolean contextualComplete,
            boolean authoritativeOutputs
    ) {
        boolean hasSemantics() {
            return !inputs.isEmpty() || !outputs.isEmpty() || !fluidsIn.isEmpty() || !fluidsOut.isEmpty()
                    || !catalysts.isEmpty() || !effects.isEmpty() || operationKind != null;
        }
    }

    private static final class Collector {
        private final JsonArray inputGroups = new JsonArray();
        private final JsonArray inputs = new JsonArray();
        private final JsonArray outputGroups = new JsonArray();
        private final JsonArray outputs = new JsonArray();
        private final JsonArray catalysts = new JsonArray();
        private final JsonArray fluidsIn = new JsonArray();
        private final JsonArray fluidsOut = new JsonArray();
        private final JsonArray effects = new JsonArray();
        private final JsonObject requirements = new JsonObject();
        private final JsonArray evidence = new JsonArray();
        private final Set<String> seenEdges = new TreeSet<>();
        private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private String operationKind;
        private boolean contextualComplete = true;
        private boolean authoritativeOutputs;

        Result result() {
            return new Result(inputGroups, inputs, outputGroups, outputs, catalysts, fluidsIn, fluidsOut,
                    operationKind, effects, requirements, evidence, contextualComplete, authoritativeOutputs);
        }

        void requirement(String kind, Number value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, String value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, boolean value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, JsonElement value, String path) {
            if (requirements.has(kind) || value == null) return;
            requirements.add(kind, value);
            evidence(kind, path);
        }

        void publicFields(Object root) {
            List<Field> fields = publicFields(root.getClass());
            fields.sort(Comparator.comparing(Field::toGenericString));
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Direction direction = direction(field.getName());
                String requirement = ReflectiveRecipeFamilyAdapter.requirement(field.getName());
                if (direction == Direction.UNKNOWN && requirement == null) continue;
                try {
                    Object value = field.get(root);
                    if (requirement != null && value instanceof Number number) {
                        requirement(requirement, number, field.getName());
                    } else {
                        collect(value, direction, field.getName(), 0);
                    }
                } catch (Throwable error) {
                    diagnose(root.getClass(), field.getName(), error);
                }
            }
        }

        void knownRecipeSemantics(Recipe<?> recipe) {
            String className = recipe.getClass().getName();
            String knownOperation = operationKind(className);
            if (knownOperation != null) operation(knownOperation, className);
            if (classOrSuperclassNamed(recipe.getClass(),
                    "com.simibubi.create.content.processing.recipe.ProcessingRecipe")) {
                createProcessingRecipe(recipe);
                if (className.equals("org.valkyrienskies.clockwork.content.logistics.gas.crafter.GasCraftingRecipe")) {
                    clockworkGasCrafting(recipe);
                }
            } else if (classOrSuperclassNamed(recipe.getClass(),
                    "slimeknights.tconstruct.library.recipe.melting.MeltingRecipe")) {
                tconstructMelting(recipe);
            } else if (classOrSuperclassNamed(recipe.getClass(),
                    "slimeknights.tconstruct.library.recipe.casting.ItemCastingRecipe")) {
                tconstructItemCasting(recipe);
            } else if (isBloodMagicPotionStateMutation(className)) {
                bloodMagicFlask(recipe, className);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe")) {
                tconstructMaterialMelting(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle")) {
                tconstructPartRecycle(recipe);
            } else if (className.equals("slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle")) {
                tconstructToolRecycle(recipe);
            } else if (className.equals("slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe")) {
                tconstructToolDamage(recipe);
            } else if (className.equals("appeng.recipes.mattercannon.MatterCannonAmmo")) {
                matterCannonAmmo(recipe);
            } else if (className.equals("com.almostreliable.unified.recipe.ClientRecipeTracker")) {
                clientRecipeTracker(recipe);
            } else if (className.equals("com.sammy.malum.common.recipe.SpiritRepairRecipe")) {
                malumSpiritRepair(recipe);
            } else if (className.equals("me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl")) {
                pneumaticHeatProperties(recipe);
            } else if (className.equals("me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl")) {
                pneumaticFuelQuality(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe")) {
                arsScryRitual(recipe);
            } else if (className.equals("wayoftime.bloodmagic.recipe.RecipeLivingDowngrade")) {
                bloodMagicLivingDowngrade(recipe);
            } else if (className.equals("com.Polarice3.Goety.common.crafting.BrewingRecipe")) {
                goetyBrewing(recipe);
            } else if (className.equals("com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes")) {
                goetySoulAbsorber(recipe);
            } else if (isVanillaSpecialCrafting(className)) {
                vanillaSpecialCrafting(className);
            } else if (isKnownModSpecialRecipe(className)) {
                modSpecialRecipe(className);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.modifiers.adding.OverslimeCraftingTableRecipe")) {
                tconstructOverslime(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.fuel.MeltingFuel")) {
                tconstructMeltingFuel(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.casting.TippingCastingRecipe")
                    || className.equals("slimeknights.tconstruct.library.recipe.casting.TipClearingCastingRecipe")) {
                tconstructPotionTip(recipe, className);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.tinkerstation.repairing.ModifierRepairTinkerStationRecipe")) {
                tconstructModifierRepair(recipe);
            } else if (className.equals("slimeknights.tconstruct.tools.recipe.ModifierRemovalRecipe")
                    || className.equals("slimeknights.tconstruct.tools.recipe.ExtractModifierRecipe")) {
                tconstructModifierRemoval(recipe, className);
            } else if (className.equals("slimeknights.tconstruct.tools.recipe.EnchantmentConvertingRecipe")) {
                tconstructEnchantmentConversion(recipe);
            } else if (className.equals("wayoftime.bloodmagic.recipe.RecipeMeteor")) {
                bloodMagicMeteor(recipe);
            } else if (className.equals("com.aetherteam.aether.recipe.recipes.ban.ItemBanRecipe")
                    || className.equals("com.aetherteam.aether.recipe.recipes.ban.BlockBanRecipe")) {
                aetherPlacementPolicy(recipe, className);
            } else if (className.equals("net.mehvahdjukaar.supplementaries.common.items.crafting.SusRecipe")) {
                supplementariesSus(recipe);
            } else if (className.equals("com.stal111.forbidden_arcanus.common.recipe.ApplyModifierRecipe")) {
                forbiddenApplyModifier(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.ArmorUpgradeRecipe")) {
                arsArmorUpgrade(recipe);
            } else if (className.equals("com.stal111.forbidden_arcanus.common.recipe.IncreaseEdelwoodBucketFullnessRecipe")) {
                forbiddenBucketFullness();
            } else if (className.equals("slimeknights.tconstruct.tables.recipe.TinkerStationPartSwapping")) {
                tconstructPartSwapping(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.worktable.ModifierSetWorktableRecipe")) {
                tconstructModifierSet(recipe);
            } else if (className.equals("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe")) {
                ironsCauldronBrew(recipe);
            } else if (className.equals("twilightforest.item.recipe.UncraftingRecipe")) {
                twilightUncrafting(recipe);
            } else if (className.equals("appeng.recipes.entropy.EntropyRecipe")) {
                ae2Entropy(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.recipe.DispelEntityRecipe")) {
                arsDispelEntity(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.ReactiveEnchantmentRecipe")) {
                arsReactiveEnchantment(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.SpellWriteRecipe")) {
                arsSpellWrite(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.recipe.SummonRitualRecipe")) {
                arsSummonRitual(recipe);
            } else if (className.equals("com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe")) {
                createSplashing(recipe);
            } else if (className.equals("net.mehvahdjukaar.jeed.recipes.EffectProviderRecipe")) {
                jeedEffectProvider(recipe);
            } else if (className.equals("net.mehvahdjukaar.jeed.recipes.PotionProviderRecipe")) {
                jeedPotionProvider(recipe);
            } else if (className.equals("com.accbdd.complicated_bees.recipe.TempUnitRecipe")) {
                complicatedBeesTemperature(recipe);
            } else if (className.equals("com.accbdd.complicated_bees.recipe.MutatorRecipe")) {
                complicatedBeesMutator(recipe);
            } else if (className.equals("com.accbdd.complicated_bees.recipe.HoneyGeneratorRecipe")) {
                complicatedBeesHoneyFuel(recipe);
            } else if (className.equals("alexthw.ars_elemental.recipe.NetheriteUpgradeRecipe")) {
                arsElementalNetheriteUpgrade(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.material.MaterialRecipe")) {
                collectAccessor(recipe, "getMaterial", Direction.OUTPUT);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.modifiers.ModifierSalvage")) {
                collectDeclaredField(recipe, "toolIngredient", Direction.INPUT);
                collectAccessor(recipe, "getModifier", Direction.INPUT);
                collectModifierSlots(readDeclaredField(recipe, "slots"), "slots");
                collectNumberAccessor(recipe, "getMaxToolSize", "max_tool_size");
                collectIntRange(readDeclaredField(recipe, "level"), "modifier_level");
            } else if (className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantmentRecipe")) {
                collectField(recipe, "pedestalItems", Direction.INPUT);
                collectField(recipe, "reagent", Direction.INPUT);
                collectField(recipe, "enchantment", Direction.OUTPUT);
            }
        }

        private void createProcessingRecipe(Object recipe) {
            authoritativeOutputs = true;
            operation("processing_recipe", "ProcessingRecipe");
            Object resultValue = invokeNoArg(recipe, "getRollableResults");
            if (resultValue instanceof Collection<?> results) {
                int index = 0;
                for (Object result : results) {
                    Object stackValue = invokeNoArg(result, "getStack");
                    Object chanceValue = invokeNoArg(result, "getChance");
                    if (stackValue instanceof ItemStack stack && !stack.isEmpty()
                            && chanceValue instanceof Number chance) {
                        JsonObject edge = itemEdge(stack, "getRollableResults()[" + index + "].getStack()");
                        addChance(edge, chance.doubleValue());
                        addEdge(Direction.OUTPUT, edge);
                        addOutputGroup(edge, "getRollableResults()[" + index + "]");
                        evidence("edge", "getRollableResults()[" + index + "]");
                    } else {
                        incomplete("getRollableResults()[" + index + "]");
                    }
                    index++;
                }
            } else {
                incomplete("getRollableResults()");
            }

            Object ingredientValue = invokeNoArg(recipe, "getFluidIngredients");
            if (ingredientValue instanceof Collection<?> ingredients) {
                int index = 0;
                for (Object ingredient : ingredients) {
                    Object amountValue = invokeNoArg(ingredient, "getRequiredAmount");
                    Object stacksValue = invokeNoArg(ingredient, "getMatchingFluidStacks");
                    int found = 0;
                    if (amountValue instanceof Number amount && stacksValue instanceof Collection<?> stacks) {
                        for (Object value : stacks) {
                            if (value instanceof FluidStack stack && !stack.isEmpty()) {
                                FluidStack exact = stack.copy();
                                exact.setAmount(amount.intValue());
                                fluid(exact, Direction.INPUT,
                                        "getFluidIngredients()[" + index + "].getMatchingFluidStacks()");
                                found++;
                            }
                        }
                    }
                    if (found == 0) incomplete("getFluidIngredients()[" + index + "]");
                    index++;
                }
            } else {
                incomplete("getFluidIngredients()");
            }
            collectFluidStacks(invokeNoArg(recipe, "getFluidResults"), Direction.OUTPUT, "getFluidResults()");
            Object duration = invokeNoArg(recipe, "getProcessingDuration");
            if (duration instanceof Number number) requirement("time", number, "getProcessingDuration()");
            else incomplete("getProcessingDuration()");
            Object heat = invokeNoArg(recipe, "getRequiredHeat");
            if (heat != null && !String.valueOf(heat).equalsIgnoreCase("NONE")) {
                requirement("heat", String.valueOf(heat).toLowerCase(Locale.ROOT), "getRequiredHeat()");
            }
            Object waterlogged = invokeNoArg(recipe, "isWaterlogged");
            if (waterlogged instanceof Boolean value && value) {
                requirement("waterlogged", true, "isWaterlogged()");
            }
        }

        private void tconstructMelting(Object recipe) {
            operation("item_to_fluid_melting", "MeltingRecipe");
            if (!collectFluidOutput(readDeclaredField(recipe, "output"), Direction.OUTPUT, "output")) {
                incomplete("output");
            }
            Object byproductValue = readDeclaredField(recipe, "byproducts");
            if (byproductValue instanceof Collection<?> byproducts) {
                int index = 0;
                for (Object byproduct : byproducts) {
                    if (!collectFluidOutput(byproduct, Direction.OUTPUT, "byproducts[" + index + "]")) {
                        incomplete("byproducts[" + index + "]");
                    }
                    index++;
                }
            } else {
                incomplete("byproducts");
            }
            Object temperature = readDeclaredField(recipe, "temperature");
            Object time = readDeclaredField(recipe, "time");
            if (temperature instanceof Number number) requirement("heat", number, "temperature");
            else incomplete("temperature");
            if (time instanceof Number number) requirement("time", number, "time");
            else incomplete("time");
            Object oreType = invokeNoArg(recipe, "getOreType");
            if (oreType != null && !String.valueOf(oreType).equalsIgnoreCase("NONE")) {
                requirement("quantity_basis", "machine_ore_rate:" + String.valueOf(oreType).toLowerCase(Locale.ROOT),
                        "getOreType()");
            }
        }

        private void tconstructItemCasting(Object recipe) {
            operation("fluid_casting", "ItemCastingRecipe");
            if (collectFluidStacks(invokeNoArg(recipe, "getFluids"), Direction.INPUT, "getFluids()") == 0) {
                incomplete("getFluids()");
            }
            Object time = invokeNoArg(recipe, "getCoolingTime");
            if (time instanceof Number number) requirement("time", number, "getCoolingTime()");
            else incomplete("getCoolingTime()");
        }

        private void bloodMagicFlask(Object recipe, String className) {
            collectNumberAccessor(recipe, "getMinimumTier", "machine_tier");
            if (collectRegistryItemsBySuperclass("wayoftime.bloodmagic.common.item.potion.ItemAlchemyFlask", "eligible_flask_item_class") == 0) {
                incomplete("eligible_flask_item_class");
            }
            if (className.endsWith("RecipePotionIncreaseLength")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object multiplier = readDeclaredField(recipe, "lengthDurationMod");
                if (!(target instanceof MobEffect) || !(multiplier instanceof Number)) incomplete("outputEffect+lengthDurationMod");
                JsonObject effect = effect("set_potion_effect_duration_multiplier", "outputEffect+lengthDurationMod");
                addMobEffectTarget(effect, target);
                addNumber(effect, "multiplier", multiplier);
            } else if (className.endsWith("RecipePotionIncreasePotency")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object amplifier = readDeclaredField(recipe, "amplifier");
                Object durationMultiplier = readDeclaredField(recipe, "ampDurationMod");
                if (!(target instanceof MobEffect) || !(amplifier instanceof Number) || !(durationMultiplier instanceof Number)) {
                    incomplete("outputEffect+amplifier+ampDurationMod");
                }
                JsonObject effect = effect("set_potion_effect_potency", "outputEffect+amplifier+ampDurationMod");
                addMobEffectTarget(effect, target);
                addNumber(effect, "amplifier", amplifier);
                addNumber(effect, "duration_multiplier", durationMultiplier);
            } else if (className.endsWith("RecipePotionEffect")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object duration = readDeclaredField(recipe, "baseDuration");
                if (!(target instanceof MobEffect) || !(duration instanceof Number)) incomplete("outputEffect+baseDuration");
                JsonObject effect = effect("add_potion_effect", "outputEffect+baseDuration");
                addMobEffectTarget(effect, target);
                addNumber(effect, "base_duration_ticks", duration);
            } else if (className.endsWith("RecipePotionTransform")) {
                Object inputValue = readDeclaredField(recipe, "inputEffectList");
                JsonArray required = mobEffectIds(inputValue);
                if (!(inputValue instanceof Collection<?>) || required.isEmpty()) incomplete("inputEffectList");
                requirement("required_potion_effects", required, "inputEffectList");
                if (inputValue instanceof Collection<?> inputEffects) {
                    for (Object inputEffect : inputEffects) {
                        JsonObject effect = effect("remove_potion_effect", "inputEffectList");
                        addMobEffectTarget(effect, inputEffect);
                    }
                }
                Object outputValue = readDeclaredField(recipe, "outputEffectList");
                if (outputValue instanceof Collection<?> outputEffects) {
                    for (Object pair : outputEffects) {
                        Object target = invokeNoArg(pair, "getKey");
                        Object duration = invokeNoArg(pair, "getValue");
                        if (!(target instanceof MobEffect) || !(duration instanceof Number)) incomplete("outputEffectList");
                        JsonObject effect = effect("add_potion_effect", "outputEffectList");
                        addMobEffectTarget(effect, target);
                        addNumber(effect, "base_duration_ticks", duration);
                    }
                } else incomplete("outputEffectList");
            } else if (className.endsWith("RecipePotionFill")) {
                Object maximum = readDeclaredField(recipe, "maxEffects");
                if (!(maximum instanceof Number)) incomplete("maxEffects");
                JsonObject effect = effect("truncate_potion_effect_list", "maxEffects");
                addNumber(effect, "maximum_effects", maximum);
                requirement("minimum_existing_effects", 1, "canModifyFlask()");
            } else if (className.endsWith("RecipePotionCycle")) {
                Object cycles = readDeclaredField(recipe, "numCycles");
                if (!(cycles instanceof Number)) incomplete("numCycles");
                JsonObject effect = effect("cycle_potion_effect_order", "getOutput()");
                addNumber(effect, "cycle_count", cycles);
                effect.addProperty("output_source", "mutable_alchemy_flask_input");
            }
        }

        private void tconstructMaterialMelting(Object recipe) {
            Object material = readDeclaredField(recipe, "input");
            String materialId = materialVariantId(String.valueOf(material));
            if (material == null || materialId.isBlank() || materialId.equals("null")) incomplete("input");
            else resource("material", materialId, Direction.INPUT, "input");
            if (!collectFluidOutput(readDeclaredField(recipe, "result"), Direction.OUTPUT, "result")) incomplete("result");
            Object byproducts = readDeclaredField(recipe, "byproducts");
            if (byproducts instanceof Collection<?> values) {
                int index = 0;
                for (Object output : values) {
                    if (!collectFluidOutput(output, Direction.OUTPUT, "byproducts[" + index + "]")) incomplete("byproducts[" + index + "]");
                    index++;
                }
            } else incomplete("byproducts");
            Object temperature = readDeclaredField(recipe, "temperature");
            if (temperature instanceof Number number) requirement("heat", number, "temperature");
            else incomplete("temperature");
            requirement("quantity_basis", "tconstruct_part_material_cost", "MaterialCastingLookup.getItemCost()");
            JsonObject effect = effect("scale_fluid_outputs_by_material_cost", "MaterialCastingLookup.getItemCost()");
            effect.addProperty("input_material", materialId);
        }

        private void tconstructPartRecycle(Object recipe) {
            collectDeclaredField(recipe, "tool", Direction.INPUT);
            collectDeclaredField(recipe, "pattern", Direction.INPUT);
            Object resultCount = readDeclaredField(recipe, "resultCount");
            if (resultCount instanceof Number number) requirement("total_recoverable_units", number, "resultCount");
            requirement("tool_must_have_no_upgrades", true, "matches()");
            requirement("quantity_basis", "remaining_durability_fraction", "getAmount()");
            Object results = readDeclaredField(recipe, "results");
            if (results instanceof Map<?, ?> map) {
                map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey()))).forEach(entry -> {
                    Object stackValue = invokeNoArg(entry.getValue(), "get");
                    Object countValue = invokeNoArg(entry.getValue(), "getCount");
                    if (!(stackValue instanceof ItemStack stack) || stack.isEmpty() || !(countValue instanceof Number)) {
                        incomplete("results[" + entry.getKey() + "]");
                        return;
                    }
                    JsonObject effect = effect("select_recycling_output", "results[" + entry.getKey() + "]");
                    effect.addProperty("selector_kind", "part_pattern");
                    effect.addProperty("selector_id", String.valueOf(entry.getKey()));
                    JsonObject output = itemEdge(stack, "results[" + entry.getKey() + "]");
                    effect.add("output", output.deepCopy());
                    item(stack, Direction.OUTPUT, "results[" + entry.getKey() + "]");
                    addNumber(effect, "maximum_count", countValue);
                });
            } else incomplete("results");
        }

        private void tconstructToolRecycle(Object recipe) {
            if (!collectSizedIngredient(readDeclaredField(recipe, "toolRequirement"), "toolRequirement")) incomplete("toolRequirement");
            collectDeclaredField(recipe, "pattern", Direction.INPUT);
            requirement("tool_must_have_no_modifiers", true, "matches()");
            requirement("quantity_basis", "tool_material_slots_and_durability", "assemble()+getLeftover()");
            JsonObject effect = effect("recover_selected_material_tool_part", "assemble()+getLeftover()");
            effect.addProperty("selector_kind", "part_pattern");
            effect.addProperty("material_source", "selected_tool_material_slot");
            Object parts = readDeclaredField(recipe, "parts");
            Set<String> allowedIds = new TreeSet<>();
            if (parts instanceof Collection<?> values) {
                for (Object value : values) {
                    if (value instanceof net.minecraft.world.item.Item item) {
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                        if (id != null) allowedIds.add(id.toString());
                    }
                }
            }
            if (allowedIds.isEmpty()) {
                effect.addProperty("allowed_parts", "tool_definition_material_parts");
            } else {
                JsonArray allowed = new JsonArray();
                allowedIds.forEach(allowed::add);
                effect.add("allowed_parts", allowed);
            }
        }

        private void tconstructToolDamage(Object recipe) {
            Object ingredient = readDeclaredField(recipe, "ingredient");
            if (ingredient instanceof Ingredient value) collect(value, Direction.INPUT, "ingredient", 0);
            else incomplete("ingredient");
            requirement("mutable_tool_input", true, "ITinkerStationContainer.getTinkerableStack()");
            Object damage = readDeclaredField(recipe, "damageAmount");
            if (!(damage instanceof Number)) incomplete("damageAmount");
            JsonObject effect = effect("add_tool_damage", "damageAmount");
            addNumber(effect, "damage", damage);
        }

        private void matterCannonAmmo(Object recipe) {
            Object ammo = invokeNoArg(recipe, "getAmmo");
            if (ammo instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getAmmo()", 0);
            else incomplete("getAmmo()");
            Object weight = invokeNoArg(recipe, "getWeight");
            if (weight instanceof Number number) requirement("ammo_weight", number, "getWeight()");
            else incomplete("getWeight()");
            requirement("consumer_machine", "ae2:matter_cannon", "MatterCannonAmmo.TYPE_ID");
            JsonObject effect = effect("define_projectile_damage_profile", "getWeight()");
            addNumber(effect, "weight", weight);
        }

        private void clientRecipeTracker(Object recipe) {
            Object namespaceValue = readDeclaredField(recipe, "namespace");
            Object recipesValue = readDeclaredField(recipe, "recipes");
            if (!(namespaceValue instanceof String namespace) || !(recipesValue instanceof Map<?, ?> links)) {
                incomplete("namespace+recipes");
                return;
            }
            requirement("gameplay_recipe", false, "ClientRecipeTracker.matches()=false");
            requirement("scope", "client_recipe_synchronization", "ClientRecipeTracker");
            JsonObject effect = effect("exclude_non_gameplay_recipe_tracker", "namespace+recipes");
            effect.addProperty("namespace", namespace);
            JsonArray exactLinks = new JsonArray();
            links.values().stream().sorted(Comparator.comparing(link -> String.valueOf(invokeNoArg(link, "id"))))
                    .forEach(link -> {
                        Object id = invokeNoArg(link, "id");
                        Object unified = invokeNoArg(link, "isUnified");
                        Object duplicate = invokeNoArg(link, "isDuplicate");
                        if (!(id instanceof ResourceLocation) || !(unified instanceof Boolean) || !(duplicate instanceof Boolean)) {
                            incomplete("recipes.ClientRecipeLink");
                            return;
                        }
                        JsonObject row = new JsonObject();
                        row.addProperty("recipe_id", id.toString());
                        row.addProperty("unified", (Boolean) unified);
                        row.addProperty("duplicate", (Boolean) duplicate);
                        exactLinks.add(row);
                    });
            effect.add("linked_recipes", exactLinks);
        }

        private void malumSpiritRepair(Object recipe) {
            Object eligibleValue = readDeclaredField(recipe, "inputs");
            JsonArray eligibleIds = new JsonArray();
            if (eligibleValue instanceof Collection<?> eligible) {
                addItemAlternatives(eligible, "inputs", eligibleIds);
            }
            if (eligibleIds.isEmpty()) incomplete("inputs");

            Object repairMaterial = readDeclaredField(recipe, "repairMaterial");
            Object repairIngredient = readDeclaredField(repairMaterial, "ingredient");
            Object repairCount = readDeclaredField(repairMaterial, "count");
            if (repairIngredient instanceof Ingredient ingredient && repairCount instanceof Number count) {
                ingredient(ingredient, Direction.INPUT, "repairMaterial.ingredient", count.intValue());
            } else incomplete("repairMaterial.ingredient+count");

            Object spiritValue = readDeclaredField(recipe, "spirits");
            if (spiritValue instanceof Collection<?> spirits && !spirits.isEmpty()) {
                int index = 0;
                for (Object spirit : spirits) {
                    Object stack = invokeNoArg(spirit, "getStack");
                    if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                        item(itemStack, Direction.INPUT, "spirits[" + index + "].getStack()");
                    } else incomplete("spirits[" + index + "].getStack()");
                    index++;
                }
            } else incomplete("spirits");

            Object durability = readDeclaredField(recipe, "durabilityPercentage");
            if (!(durability instanceof Number)) incomplete("durabilityPercentage");
            requirement("mutable_item_input", true, "SpiritRepairRecipe.getRepairRecipeOutput()");
            JsonObject effect = effect("repair_item_durability_fraction", "durabilityPercentage");
            addNumber(effect, "durability_fraction", durability);
            effect.add("eligible_item_ids", eligibleIds);
            effect.addProperty("output_source", "mutable_item_input");
        }

        private void pneumaticHeatProperties(Object recipe) {
            Object blockValue = invokeNoArg(recipe, "getBlock");
            Object capacity = invokeNoArg(recipe, "getHeatCapacity");
            Object temperature = invokeNoArg(recipe, "getTemperature");
            Object resistance = invokeNoArg(recipe, "getThermalResistance");
            if (!(blockValue instanceof Block block) || !(capacity instanceof Number)
                    || !(temperature instanceof Number) || !(resistance instanceof Number)) {
                incomplete("getBlock()+getHeatCapacity()+getTemperature()+getThermalResistance()");
                return;
            }
            resource("block", BuiltInRegistries.BLOCK.getKey(block), Direction.INPUT, "getBlock()");
            JsonObject effect = effect("define_block_heat_properties", "HeatPropertiesRecipe display API");
            effect.addProperty("block_id", String.valueOf(BuiltInRegistries.BLOCK.getKey(block)));
            addNumber(effect, "heat_capacity", capacity);
            addNumber(effect, "temperature", temperature);
            addNumber(effect, "thermal_resistance", resistance);
            addBlockState(effect, "input_state", invokeNoArg(recipe, "getBlockState"));
            addBlockState(effect, "hot_transform", invokeNoArg(recipe, "getTransformHot"));
            addBlockState(effect, "hot_flowing_transform", invokeNoArg(recipe, "getTransformHotFlowing"));
            addBlockState(effect, "cold_transform", invokeNoArg(recipe, "getTransformCold"));
            addBlockState(effect, "cold_flowing_transform", invokeNoArg(recipe, "getTransformColdFlowing"));
            Object predicateValue = invokeNoArg(recipe, "getBlockStatePredicates");
            if (predicateValue instanceof Map<?, ?> predicates) {
                JsonObject exactPredicates = new JsonObject();
                predicates.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                        .forEach(entry -> exactPredicates.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
                effect.add("state_predicates", exactPredicates);
            } else incomplete("getBlockStatePredicates()");
            Object description = invokeNoArg(recipe, "getDescriptionKey");
            if (description instanceof String value && !value.isBlank()) effect.addProperty("description_key", value);
        }

        private void pneumaticFuelQuality(Object recipe) {
            Object fuel = invokeNoArg(recipe, "getFuel");
            Object fluidsValue = invokeNoArg(fuel, "getFluidStacks");
            int fluids = 0;
            if (fluidsValue instanceof Collection<?> stacks) {
                for (Object value : stacks) {
                    if (value instanceof FluidStack stack && !stack.isEmpty()) {
                        fluid(stack, Direction.INPUT, "getFuel().getFluidStacks()");
                        fluids++;
                    }
                }
            }
            Object air = invokeNoArg(recipe, "getAirPerBucket");
            Object burnRate = invokeNoArg(recipe, "getBurnRate");
            if (fluids == 0 || !(air instanceof Number) || !(burnRate instanceof Number)) {
                incomplete("getFuel()+getAirPerBucket()+getBurnRate()");
            }
            JsonObject effect = effect("define_fluid_fuel_quality", "FuelQualityRecipe display API");
            addNumber(effect, "air_per_bucket", air);
            addNumber(effect, "burn_rate", burnRate);
            requirement("consumer_machine", "pneumaticcraft:liquid_compressor", "FuelQualityRecipe.TYPE");
        }

        private void arsScryRitual(Object recipe) {
            Object augment = invokeNoArg(recipe, "augment");
            Object highlight = invokeNoArg(recipe, "highlight");
            ResourceLocation augmentId = tagLocation(augment);
            ResourceLocation highlightId = tagLocation(highlight);
            if (augmentId == null || highlightId == null) incomplete("augment()+highlight()");
            if (augmentId != null) resource("item_tag", augmentId, Direction.INPUT, "augment()");
            JsonObject effect = effect("highlight_blocks_matching_tag", "highlight()");
            if (highlightId != null) effect.addProperty("block_tag", highlightId.toString());
            requirement("consumer_ritual", "ars_nouveau:scrying", "ScryRitualRecipe");
        }

        private void bloodMagicLivingDowngrade(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            Object downgrade = invokeNoArg(recipe, "getLivingArmourResource");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getInput()", 0);
            else incomplete("getInput()");
            if (!(downgrade instanceof ResourceLocation)) incomplete("getLivingArmourResource()");
            requirement("mutable_living_armor_input", true, "RecipeLivingDowngrade");
            JsonObject effect = effect("remove_living_armor_downgrade", "getLivingArmourResource()");
            if (downgrade instanceof ResourceLocation id) effect.addProperty("downgrade_id", id.toString());
            effect.addProperty("output_source", "mutable_living_armor_input");
        }

        private void goetyBrewing(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getInput()", 0);
            else incomplete("getInput()");
            Object mobEffect = invokeNoArg(recipe, "getOutput");
            ResourceLocation effectId = mobEffect instanceof MobEffect effect ? BuiltInRegistries.MOB_EFFECT.getKey(effect) : null;
            Object entityType = invokeNoArg(recipe, "getEntityType");
            Object entityTag = invokeNoArg(recipe, "getEntityTypeTag");
            ResourceLocation entityId = entityType instanceof EntityType<?> type ? BuiltInRegistries.ENTITY_TYPE.getKey(type) : null;
            ResourceLocation entityTagId = tagLocation(entityTag);
            Object soulCost = invokeNoArg(recipe, "getSoulCost");
            Object capacity = invokeNoArg(recipe, "getCapacityExtra");
            Object duration = invokeNoArg(recipe, "getDuration");
            if (effectId == null || !(soulCost instanceof Number)
                    || !(capacity instanceof Number) || !(duration instanceof Number)) incomplete("Goety BrewingRecipe display API");
            JsonObject effect = effect("apply_mob_effect_to_entity_selector", "BrewingRecipe display API");
            if (effectId != null) effect.addProperty("mob_effect_id", effectId.toString());
            if (entityId != null) effect.addProperty("entity_type_id", entityId.toString());
            if (entityTagId != null) effect.addProperty("entity_type_tag", entityTagId.toString());
            if (entityId == null && entityTagId == null) effect.addProperty("entity_selector", "unrestricted");
            addNumber(effect, "duration_ticks", duration);
            addNumber(effect, "capacity_extra", capacity);
            if (soulCost instanceof Number number) requirement("soul_cost", number, "getSoulCost()");
        }

        private void goetySoulAbsorber(Object recipe) {
            Object ingredientValue = readDeclaredField(recipe, "ingredient");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "ingredient", 0);
            else incomplete("ingredient");
            Object souls = invokeNoArg(recipe, "getSoulIncrease");
            Object time = invokeNoArg(recipe, "getCookingTime");
            if (!(souls instanceof Number) || !(time instanceof Number)) incomplete("getSoulIncrease()+getCookingTime()");
            JsonObject effect = effect("generate_souls", "getSoulIncrease()");
            addNumber(effect, "amount", souls);
            if (time instanceof Number number) requirement("time", number, "getCookingTime()");
            requirement("consumer_machine", "goety:soul_absorber", "SoulAbsorberRecipes");
        }

        private void vanillaSpecialCrafting(String className) {
            requirement("dynamic_crafting_grid", true, "vanilla special recipe matches()+assemble()");
            switch (className) {
                case "net.minecraft.world.item.crafting.TippedArrowRecipe" -> dynamicCrafting(
                        "copy_potion_to_tipped_arrows", "lingering_potion", "eight_arrows");
                case "net.minecraft.world.item.crafting.SuspiciousStewRecipe" -> dynamicCrafting(
                        "derive_suspicious_stew_effect_from_flower", "bowl", "red_mushroom", "brown_mushroom", "small_flower");
                case "net.minecraft.world.item.crafting.ShulkerBoxColoring" -> dynamicCrafting(
                        "copy_container_state_and_apply_dye_color", "shulker_box", "dye");
                case "net.minecraft.world.item.crafting.ShieldDecorationRecipe" -> dynamicCrafting(
                        "copy_banner_patterns_to_shield", "undecorated_shield", "banner");
                case "net.minecraft.world.item.crafting.RepairItemRecipe" -> dynamicCrafting(
                        "merge_item_durability_with_repair_bonus", "two_same_damageable_items");
                case "net.minecraft.world.item.crafting.MapCloningRecipe" -> dynamicCrafting(
                        "copy_filled_map_id", "filled_map", "empty_maps");
                case "net.minecraft.world.item.crafting.FireworkStarFadeRecipe" -> dynamicCrafting(
                        "append_firework_fade_colors", "firework_star", "dyes");
                case "net.minecraft.world.item.crafting.DecoratedPotRecipe" -> dynamicCrafting(
                        "compose_decorated_pot_sides", "four_pottery_sherds_or_bricks");
                case "net.minecraft.world.item.crafting.BookCloningRecipe" -> dynamicCrafting(
                        "copy_written_book_and_increment_generation", "written_book", "writable_books");
                case "net.minecraft.world.item.crafting.BannerDuplicateRecipe" -> dynamicCrafting(
                        "copy_banner_patterns", "patterned_banner", "blank_same_color_banner");
                case "net.minecraft.world.item.crafting.ArmorDyeRecipe" -> dynamicCrafting(
                        "blend_dyeable_armor_color", "dyeable_armor", "dyes");
                default -> incomplete(className);
            }
        }

        private void dynamicCrafting(String effectKind, String... selectors) {
            for (String selector : selectors) resource("runtime_selector", selector, Direction.INPUT, "matches()");
            JsonObject effect = effect(effectKind, "matches()+assemble()");
            effect.addProperty("output_source", "runtime_crafting_inputs");
        }

        private void modSpecialRecipe(String className) {
            requirement("runtime_recipe_inputs", true, "matches()+assemble()/getResult()");
            switch (className) {
                case "wayoftime.bloodmagic.recipe.RecipeTomeCombine" -> dynamicCrafting(
                        "merge_bloodmagic_tome_state", "bloodmagic_tome", "compatible_tome_or_upgrade");
                case "wayoftime.bloodmagic.recipe.RecipeFilterCopy" -> dynamicCrafting(
                        "copy_bloodmagic_filter_configuration", "configured_filter", "blank_compatible_filter");
                case "wayoftime.bloodmagic.recipe.RecipeAnointmentApply" -> dynamicCrafting(
                        "apply_anointment_to_item", "anointment", "anointable_item");
                case "com.github.alexthe666.rats.server.recipes.DemonRatSwitchRecipe" -> dynamicCrafting(
                        "toggle_demon_rat_upgrade_soul_form", "rats:demon_rat_upgrade");
                case "vectorwing.farmersdelight.common.crafting.FoodServingRecipe" -> dynamicCrafting(
                        "serve_food_into_container", "serving_container", "servable_food");
                case "vectorwing.farmersdelight.common.crafting.DoughRecipe" -> dynamicCrafting(
                        "hydrate_flour_into_dough", "flour", "water_container");
                case "twilightforest.item.recipe.MoonwormQueenRepairRecipe" -> dynamicCrafting(
                        "repair_moonworm_queen", "damaged_moonworm_queen", "torchberries");
                case "twilightforest.item.recipe.MazeMapCloningRecipe" -> dynamicCrafting(
                        "copy_twilight_maze_map_id", "filled_maze_map", "empty_maze_maps");
                case "twilightforest.item.recipe.MagicMapCloningRecipe" -> dynamicCrafting(
                        "copy_twilight_magic_map_id", "filled_magic_map", "empty_magic_maps");
                case "slimeknights.tconstruct.tools.recipe.ToggleInteractionWorktableRecipe" -> dynamicCrafting(
                        "toggle_selected_tinker_interaction", "modifiable_tinker_tool", "runtime_interaction_choice");
                case "slimeknights.tconstruct.tools.recipe.ModifierSortingRecipe" -> dynamicCrafting(
                        "reorder_tinker_tool_modifiers", "modifiable_tinker_tool", "runtime_modifier_order");
                case "slimeknights.tconstruct.tools.recipe.ArmorTrimRecipe" -> dynamicCrafting(
                        "apply_armor_trim_to_tinker_armor", "trimmable_tinker_armor", "trim_template", "trim_material");
                case "slimeknights.tconstruct.tools.recipe.ArmorDyeingRecipe" -> dynamicCrafting(
                        "blend_tinker_armor_dye_color", "dyeable_tinker_armor", "dyes");
                case "slimeknights.tconstruct.tables.recipe.TinkerStationRepairRecipe" -> dynamicCrafting(
                        "repair_tinker_tool_from_compatible_material", "damaged_tinker_tool", "compatible_repair_material");
                case "slimeknights.tconstruct.tables.recipe.CraftingTableRepairKitRecipe" -> dynamicCrafting(
                        "repair_tinker_repair_kit_from_material", "damaged_repair_kit", "compatible_repair_material");
                case "org.violetmoon.zeta.recipe.ZetaDyeRecipe" -> dynamicCrafting(
                        "apply_dye_color_preserving_item_state", "zeta_dyeable_item", "dye");
                case "org.violetmoon.quark.content.tweaks.recipe.SlabToBlockRecipe" -> dynamicCrafting(
                        "combine_matching_slabs_into_block", "two_matching_slabs");
                case "org.valkyrienskies.clockwork.content.contraptions.propeller.blades.item.CraftingTableBladeRecipe" -> dynamicCrafting(
                        "assemble_clockwork_propeller_blade", "propeller_blade_components");
                case "net.p3pp3rf1y.sophisticatedstorage.crafting.StorageDyeRecipe" -> dynamicCrafting(
                        "dye_sophisticated_storage_preserving_state", "sophisticated_storage", "dyes");
                case "com.alrex.parcool.common.item.recipe.special.ZiplineRopeDyeRecipe" -> dynamicCrafting(
                        "blend_zipline_rope_dye_color", "parcool:zipline_rope", "dye");
                case "net.p3pp3rf1y.sophisticatedstorage.crafting.FlatTopBarrelToggleRecipe" -> dynamicCrafting(
                        "toggle_barrel_flat_top", "sophisticated_barrel");
                case "net.p3pp3rf1y.sophisticatedstorage.crafting.BarrelMaterialRecipe" -> dynamicCrafting(
                        "apply_barrel_material_layers", "sophisticated_barrel", "barrel_material_blocks");
                case "net.p3pp3rf1y.sophisticatedcore.crafting.UpgradeClearRecipe" -> dynamicCrafting(
                        "clear_sophisticated_upgrade_configuration", "configured_sophisticated_upgrade");
                case "net.p3pp3rf1y.sophisticatedbackpacks.crafting.BackpackDyeRecipe" -> dynamicCrafting(
                        "dye_sophisticated_backpack_preserving_state", "sophisticated_backpack", "dyes");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.WeatheredMapRecipe" -> dynamicCrafting(
                        "weather_map_preserving_map_id", "filled_map", "weathering_ingredient");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.TrappedPresentRecipe" -> dynamicCrafting(
                        "convert_present_to_trapped_preserving_contents", "present", "trap_component");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.TippedBambooSpikesRecipe" -> dynamicCrafting(
                        "apply_potion_to_bamboo_spikes", "bamboo_spikes", "potion");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.TatteredBookRecipe" -> dynamicCrafting(
                        "tatter_book_preserving_book_state", "book", "tattering_ingredient");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.SoapClearRecipe" -> dynamicCrafting(
                        "clear_supported_item_cosmetic_state", "soap", "soap_clearable_item");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.SafeRecipe" -> dynamicCrafting(
                        "craft_safe_preserving_container_state", "safe_components");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.RopeArrowCreateRecipe" -> dynamicCrafting(
                        "create_rope_arrow", "arrow", "rope");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.RopeArrowAddRecipe" -> dynamicCrafting(
                        "extend_rope_arrow", "rope_arrow", "rope");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.RepairBubbleBlowerRecipe" -> dynamicCrafting(
                        "restore_bubble_blower_charge", "bubble_blower", "soap");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.PresentDyeRecipe" -> dynamicCrafting(
                        "dye_present_preserving_contents", "present", "dye");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.ItemLoreRecipe" -> dynamicCrafting(
                        "copy_or_apply_item_lore", "target_item", "lore_source");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.FlagFromBannerRecipe" -> dynamicCrafting(
                        "convert_banner_to_flag_preserving_patterns", "banner");
                case "net.mehvahdjukaar.supplementaries.common.items.crafting.BlackboardDuplicateRecipe" -> dynamicCrafting(
                        "copy_blackboard_drawing", "configured_blackboard", "blank_blackboard");
                case "net.mehvahdjukaar.amendments.common.recipe.DyeBottleRecipe" -> dynamicCrafting(
                        "dye_potion_bottle_preserving_contents", "potion_bottle", "dye");
                case "net.joefoxe.hexerei.data.recipes.WhistleBindRecipe" -> dynamicCrafting(
                        "bind_whistle_to_entity_reference", "whistle", "bindable_entity_token");
                case "net.joefoxe.hexerei.data.recipes.KeychainUndoRecipe" -> dynamicCrafting(
                        "remove_item_from_keychain", "filled_keychain");
                case "net.joefoxe.hexerei.data.recipes.KeychainRecipe" -> dynamicCrafting(
                        "attach_item_to_keychain", "keychain", "keychain_item");
                case "net.joefoxe.hexerei.data.recipes.FillWaxingKitRecipe" -> dynamicCrafting(
                        "fill_waxing_kit", "waxing_kit", "wax");
                case "net.joefoxe.hexerei.data.recipes.CutCandleRecipe" -> dynamicCrafting(
                        "cut_candle_stack", "hexerei_candle", "cutting_tool");
                case "net.joefoxe.hexerei.data.recipes.CrowAmuletUndoRecipe" -> dynamicCrafting(
                        "remove_item_from_crow_amulet", "filled_crow_amulet");
                case "net.joefoxe.hexerei.data.recipes.CrowAmuletRecipe" -> dynamicCrafting(
                        "attach_item_to_crow_amulet", "crow_amulet", "amulet_item");
                case "net.joefoxe.hexerei.data.recipes.BookOfShadowsDyeRecipe" -> dynamicCrafting(
                        "dye_book_of_shadows_preserving_state", "book_of_shadows", "dye");
                case "dev.murad.shipping.setup.ModRecipeSerializers$2" -> dynamicCrafting(
                        "copy_locomotive_route", "route_configured_locomotive", "blank_locomotive");
                case "dev.murad.shipping.setup.ModRecipeSerializers$1" -> dynamicCrafting(
                        "copy_tug_route", "route_configured_tug", "blank_tug");
                case "dev.lukebemish.excavatedvariants.impl.recipe.OreConversionRecipe" -> dynamicCrafting(
                        "convert_ore_host_variant_preserving_ore_type", "excavated_variant_ore", "target_host_stone");
                case "com.simibubi.create.foundation.recipe.ItemCopyingRecipe" -> dynamicCrafting(
                        "copy_create_item_configuration", "configured_create_item", "blank_compatible_items");
                case "com.simibubi.create.content.equipment.toolbox.ToolboxDyeingRecipe" -> dynamicCrafting(
                        "dye_toolbox_preserving_inventory", "create_toolbox", "dye");
                case "com.klikli_dev.occultism.crafting.recipe.BoundBookOfBindingRecipe" -> dynamicCrafting(
                        "bind_book_of_binding_to_spirit", "book_of_binding", "spirit_binding_reference");
                case "com.endertech.minecraft.mods.adpother.recipes.FilterChangeRecipe" -> dynamicCrafting(
                        "change_air_filter_material_preserving_state", "air_filter", "filter_material");
                case "com.aetherteam.aether.recipe.recipes.item.SwetBannerRecipe" -> dynamicCrafting(
                        "apply_swet_pattern_to_banner", "banner", "swet_ball");
                case "com.bettercontent.realisticores.compat.ExcavatedSeparationRecipe" -> dynamicCrafting(
                        "separate_excavated_ore_into_chunk_and_substrate", "single_excavated_ore_variant");
                case "com.bettercontent.realisticores.compat.ExcavatedReassemblyRecipe" -> dynamicCrafting(
                        "reassemble_excavated_ore_from_chunk_and_substrate", "matching_ore_chunk", "compatible_substrate");
                case "com.bettercontent.proceduralbouquets.recipe.PottedBouquetRecipe" -> dynamicCrafting(
                        "pot_bouquet_preserving_flower_composition", "configured_bouquet", "flower_pot");
                case "appeng.recipes.game.FacadeRecipe" -> dynamicCrafting(
                        "encode_block_state_into_ae2_facade", "ae2_facade_blank", "facade_block_item");
                default -> incomplete(className);
            }
        }

        private void tconstructOverslime(Object recipe) {
            Object tools = readDeclaredField(recipe, "tools");
            Object ingredientValue = readDeclaredField(recipe, "ingredient");
            Object amount = readDeclaredField(recipe, "restoreAmount");
            if (tools instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "tools", 0); else incomplete("tools");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "ingredient", 0); else incomplete("ingredient");
            if (!(amount instanceof Number)) incomplete("restoreAmount");
            requirement("mutable_tinker_tool_input", true, "assemble()");
            JsonObject effect = effect("restore_tool_overslime", "restoreAmount");
            addNumber(effect, "amount_per_ingredient", amount);
            effect.addProperty("output_source", "mutable_tinker_tool_input");
        }

        private void tconstructMeltingFuel(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            int fluidCount = collectFluidStacks(invokeNoArg(recipe, "getInputs"), Direction.INPUT, "getInputs()");
            JsonElement definition = serializeOptional(input);
            Object duration = invokeNoArg(recipe, "getDuration");
            Object temperature = invokeNoArg(recipe, "getTemperature");
            Object rate = invokeNoArg(recipe, "getRate");
            if (!(duration instanceof Number) || !(temperature instanceof Number) || !(rate instanceof Number)) {
                incomplete("getInput()+getDuration()+getTemperature()+getRate()");
            }
            JsonObject effect = effect("define_smeltery_fuel_profile", "MeltingFuel display API");
            if (definition != null) effect.add("fluid_ingredient", definition);
            else effect.addProperty("fluid_selector", "none");
            effect.addProperty("display_fluid_count", fluidCount);
            addNumber(effect, "duration_ticks", duration);
            addNumber(effect, "temperature", temperature);
            addNumber(effect, "consumption_rate", rate);
            requirement("consumer_machine", "tconstruct:smeltery", "MeltingFuel");
        }

        private void tconstructPotionTip(Object recipe, String className) {
            Object bottle = readDeclaredField(recipe, "bottle");
            if (bottle instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "bottle", 0); else incomplete("bottle");
            Object fluidIngredient = readDeclaredField(recipe, "fluid");
            int fluids = collectFluidStacks(invokeNoArg(fluidIngredient, "getFluids"), Direction.INPUT, "fluid.getFluids()");
            Object modifier = readDeclaredField(recipe, "modifier");
            Object cooling = readDeclaredField(recipe, "coolingTime");
            if (fluids == 0 || modifier == null || !(cooling instanceof Number)) incomplete("fluid+modifier+coolingTime");
            JsonObject effect = effect(className.endsWith("TippingCastingRecipe")
                    ? "apply_potion_tip_modifier" : "clear_potion_tip_modifier", "modifier+fluid");
            if (modifier != null) effect.addProperty("modifier_id", String.valueOf(modifier));
            effect.addProperty("output_source", "mutable_cast_item");
            if (cooling instanceof Number number) requirement("time", number, "coolingTime");
        }

        private void tconstructModifierRepair(Object recipe) {
            Object ingredientValue = invokeNoArg(recipe, "getIngredient");
            Object modifier = invokeNoArg(recipe, "getModifier");
            Object amount = invokeNoArg(recipe, "getRepairAmount");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getIngredient()", 0); else incomplete("getIngredient()");
            if (modifier == null || !(amount instanceof Number)) incomplete("getModifier()+getRepairAmount()");
            JsonObject effect = effect("repair_tool_modifier_damage", "getModifier()+getRepairAmount()");
            if (modifier != null) effect.addProperty("modifier_id", String.valueOf(modifier));
            addNumber(effect, "repair_amount", amount);
            effect.addProperty("output_source", "mutable_tinker_tool_input");
        }

        private void tconstructModifierRemoval(Object recipe, String className) {
            if (!collectSizedIngredient(readDeclaredField(recipe, "sizedTool"), "sizedTool")) incomplete("sizedTool");
            collectSizedIngredients(readDeclaredField(recipe, "inputs"), "inputs");
            Object predicate = readDeclaredField(recipe, "modifierPredicate");
            JsonElement predicateJson = serializeOptional(predicate);
            if (predicateJson == null) incomplete("modifierPredicate");
            JsonObject effect = effect(className.endsWith("ExtractModifierRecipe")
                    ? "extract_selected_tool_modifier" : "remove_selected_tool_modifier", "modifierPredicate+getResult()");
            if (predicateJson != null) effect.add("modifier_predicate", predicateJson);
            effect.addProperty("selection_source", "runtime_modifier_choice");
            effect.addProperty("output_source", "mutable_tinker_tool_input");
            if (className.endsWith("ExtractModifierRecipe")) effect.addProperty("emits_removed_modifier_item", true);
        }

        private void tconstructEnchantmentConversion(Object recipe) {
            Object toolRequirement = readDeclaredField(recipe, "toolRequirement");
            if (toolRequirement instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "toolRequirement", 0);
            else resource("runtime_selector", "enchantable_tinker_tool_or_book", Direction.INPUT, "matches()");
            collectSizedIngredients(readDeclaredField(recipe, "inputs"), "inputs");
            Object predicate = readDeclaredField(recipe, "modifierPredicate");
            JsonElement predicateJson = serializeOptional(predicate);
            Object matchBook = readDeclaredField(recipe, "matchBook");
            Object returnInput = readDeclaredField(recipe, "returnInput");
            if (predicateJson == null || !(matchBook instanceof Boolean) || !(returnInput instanceof Boolean)) {
                incomplete("modifierPredicate+matchBook+returnInput");
            }
            JsonObject effect = effect("convert_matching_enchantment_to_tinker_modifier", "getEnchantments()+getResult()");
            if (predicateJson != null) effect.add("modifier_predicate", predicateJson);
            if (matchBook instanceof Boolean value) effect.addProperty("accepts_enchanted_book", value);
            if (returnInput instanceof Boolean value) effect.addProperty("returns_input_item", value);
            effect.addProperty("output_source", "mutable_enchanted_input");
        }

        private void bloodMagicMeteor(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getInput()", 0); else incomplete("getInput()");
            Object syphon = invokeNoArg(recipe, "getSyphon");
            Object radius = readDeclaredField(recipe, "explosionRadius");
            Object layersValue = readDeclaredField(recipe, "layerList");
            JsonArray layers = new JsonArray();
            if (layersValue instanceof Collection<?> values) {
                for (Object layer : values) {
                    JsonElement serialized = serializeOptional(layer);
                    if (serialized == null) incomplete("layerList.serialize()"); else layers.add(serialized);
                }
            } else incomplete("layerList");
            if (!(syphon instanceof Number) || !(radius instanceof Number) || layers.isEmpty()) incomplete("syphon+explosionRadius+layerList");
            if (syphon instanceof Number number) requirement("energy", number, "getSyphon()");
            JsonObject effect = effect("spawn_weighted_block_meteor", "spawnMeteorInWorld()");
            addNumber(effect, "explosion_radius", radius);
            effect.add("layers", layers);
        }

        private void aetherPlacementPolicy(Object recipe, String className) {
            Object ingredientValue = invokeNoArg(recipe, "getIngredient");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getIngredient()", 0);
            JsonElement ingredientJson = ingredientValue instanceof Ingredient ? null : serializeOptional(ingredientValue);
            Object biomeKeyValue = invokeNoArg(recipe, "getBiomeKey");
            Object biomeTagValue = invokeNoArg(recipe, "getBiomeTag");
            ResourceLocation biomeKey = biomeKeyValue instanceof ResourceKey<?> key ? key.location() : null;
            ResourceLocation biomeTag = tagLocation(biomeTagValue);
            JsonElement bypass = serializeOptional(invokeNoArg(recipe, "getBypassBlock"));
            if (!(ingredientValue instanceof Ingredient) && ingredientJson == null) incomplete("getIngredient()");
            if (biomeKey == null && biomeTag == null) incomplete("getBiomeKey()+getBiomeTag()");
            if (bypass == null) incomplete("getBypassBlock()");
            JsonObject effect = effect("deny_placement_in_biome", "AbstractPlacementBanRecipe.matches()");
            effect.addProperty("target_kind", className.endsWith("ItemBanRecipe") ? "item" : "block_state");
            if (ingredientJson != null) effect.add("target_predicate", ingredientJson);
            if (biomeKey != null) effect.addProperty("biome_id", biomeKey.toString());
            if (biomeTag != null) effect.addProperty("biome_tag", biomeTag.toString());
            if (bypass != null) effect.add("bypass_block_predicate", bypass);
            requirement("gameplay_recipe", false, "placement policy metadata");
        }

        private void supplementariesSus(Object recipe) {
            Object ingredientValue = readDeclaredField(recipe, "ingredient");
            Object resultValue = readDeclaredField(recipe, "result");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "ingredient", 0); else incomplete("ingredient");
            if (resultValue instanceof ItemStack stack && !stack.isEmpty()) item(stack, Direction.OUTPUT, "result"); else incomplete("result");
        }

        private void forbiddenApplyModifier(Object recipe) {
            Object template = invokeNoArg(recipe, "getTemplate");
            Object addition = invokeNoArg(recipe, "getAddition");
            Object modifier = invokeNoArg(recipe, "getModifier");
            if (template instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getTemplate()", 0); else incomplete("getTemplate()");
            if (addition instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getAddition()", 0); else incomplete("getAddition()");
            Object validItems = invokeNoArg(modifier, "getValidItems");
            if (validItems instanceof Collection<?> items && !items.isEmpty()) collect(items, Direction.INPUT, "getModifier().getValidItems()", 0);
            else incomplete("getModifier().getValidItems()");
            Object modifierId = invokeNoArg(modifier, "getRegistryName");
            if (!(modifierId instanceof ResourceLocation)) incomplete("getModifier().getRegistryName()");
            JsonObject effect = effect("apply_forbidden_arcanus_item_modifier", "getModifier().onApplied()");
            if (modifierId instanceof ResourceLocation id) effect.addProperty("modifier_id", id.toString());
            Object incompatibleItems = invokeNoArg(modifier, "getIncompatibleItems");
            Object incompatibleEnchantments = invokeNoArg(modifier, "getIncompatibleEnchantments");
            ResourceLocation itemTag = tagLocation(incompatibleItems);
            ResourceLocation enchantmentTag = tagLocation(incompatibleEnchantments);
            if (itemTag != null) effect.addProperty("incompatible_item_tag", itemTag.toString());
            if (enchantmentTag != null) effect.addProperty("incompatible_enchantment_tag", enchantmentTag.toString());
            effect.addProperty("output_source", "mutable_smithing_base_input");
        }

        private void arsArmorUpgrade(Object recipe) {
            Object tier = readDeclaredField(recipe, "tier");
            JsonElement definition = serializeOptional(recipe);
            if (!(tier instanceof Number) || definition == null) incomplete("tier+asRecipe()");
            JsonObject effect = effect("set_ars_armor_tier", "getResult()");
            addNumber(effect, "tier", tier);
            if (definition != null) effect.add("recipe_definition", definition);
            effect.addProperty("output_source", "mutable_armor_reagent");
        }

        private void forbiddenBucketFullness() {
            resource("runtime_selector", "forbidden_arcanus:edelwood_bucket", Direction.INPUT, "matches()");
            resource("runtime_selector", "compatible_filled_bucket", Direction.INPUT, "isValidIncreasementItem()");
            JsonObject effect = effect("increase_edelwood_bucket_fullness_from_consumed_container", "assemble()");
            effect.addProperty("output_source", "mutable_edelwood_bucket_input");
        }

        private void clockworkGasCrafting(Object recipe) {
            Object gasRecipe = invokeNoArg(recipe, "getGasRecipe");
            JsonObject inputGasses = gasMap(invokeNoArg(gasRecipe, "getGasses"));
            JsonObject outputGasses = gasMap(invokeNoArg(gasRecipe, "getResult"));
            JsonObject reactionRequirements = gasRequirements(invokeNoArg(gasRecipe, "getRequirements"));
            Object energy = invokeNoArg(gasRecipe, "getEnergy");
            if (gasRecipe == null || inputGasses == null || outputGasses == null
                    || reactionRequirements == null || !(energy instanceof Number)) {
                incomplete("getGasRecipe() accessors");
            }
            JsonObject effect = effect("apply_kelvin_gas_reaction", "getGasRecipe()");
            if (inputGasses != null) effect.add("gas_inputs_kg", inputGasses);
            if (outputGasses != null) effect.add("gas_outputs_kg", outputGasses);
            if (reactionRequirements != null) effect.add("reaction_requirements", reactionRequirements);
            addNumber(effect, "energy", energy);
            requirement("consumer_machine", "vs_clockwork:gas_crafter", "GasCraftingRecipe");
        }

        private static JsonObject gasMap(Object value) {
            if (!(value instanceof Map<?, ?> map)) return null;
            JsonObject result = new JsonObject();
            map.entrySet().stream().sorted(Comparator.comparing(entry -> gasResourceId(entry.getKey())))
                    .forEach(entry -> {
                        String id = gasResourceId(entry.getKey());
                        if (!id.isBlank() && entry.getValue() instanceof Number amount) result.addProperty(id, amount);
                    });
            return result.size() == map.size() ? result : null;
        }

        private static JsonObject gasRequirements(Object value) {
            if (!(value instanceof Map<?, ?> map)) return null;
            JsonObject result = new JsonObject();
            map.entrySet().stream().sorted(Comparator.comparing(entry -> gasResourceId(entry.getKey())))
                    .forEach(entry -> {
                        String id = gasResourceId(entry.getKey());
                        if (!id.isBlank() && entry.getValue() instanceof JsonElement json) result.add(id, json.deepCopy());
                    });
            return result.size() == map.size() ? result : null;
        }

        private static String gasResourceId(Object value) {
            Object id = invokeNoArg(value, "getResourceLocation");
            return id instanceof ResourceLocation resource ? resource.toString() : "";
        }

        private void tconstructPartSwapping(Object recipe) {
            Object tools = readDeclaredField(recipe, "tools");
            Object maximum = readDeclaredField(recipe, "maxStackSize");
            if (tools instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "tools", 0);
            else incomplete("tools");
            resource("runtime_selector", "compatible_tconstruct_tool_part", Direction.INPUT, "matches()");
            if (maximum instanceof Number number) requirement("base_maximum_parts_per_operation", number, "maxStackSize");
            else incomplete("maxStackSize");
            requirement("maximum_replacements", "tool_material_slot_count", "getValidatedResult()");
            JsonObject effect = effect("replace_matching_tinker_tool_material_parts", "getValidatedResult()");
            effect.addProperty("part_slot_source", "runtime_tool_part_item_type");
            effect.addProperty("material_source", "runtime_tool_part_material");
            effect.addProperty("output_source", "mutable_tinker_tool_input");
        }

        private void tconstructModifierSet(Object recipe) {
            Object tools = readDeclaredField(recipe, "toolRequirement");
            if (tools instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "toolRequirement", 0);
            else incomplete("toolRequirement");
            collectSizedIngredients(readDeclaredField(recipe, "inputs"), "inputs");
            Object dataKey = readDeclaredField(recipe, "dataKey");
            Object predicate = readDeclaredField(recipe, "modifierPredicate");
            Object addToSet = readDeclaredField(recipe, "addToSet");
            Object allowTraits = readDeclaredField(recipe, "allowTraits");
            JsonElement predicateJson = serializeOptional(predicate);
            if (!(dataKey instanceof ResourceLocation) || predicateJson == null
                    || !(addToSet instanceof Boolean) || !(allowTraits instanceof Boolean)) {
                incomplete("dataKey+modifierPredicate+addToSet+allowTraits");
            }
            resource("runtime_selector", "matching_tinker_modifier", Direction.INPUT, "getModifierOptions()");
            JsonObject effect = effect(Boolean.TRUE.equals(addToSet)
                    ? "add_selected_modifier_to_tool_data_set" : "remove_selected_modifier_from_tool_data_set",
                    "getModifierOptions()+getResult()");
            if (dataKey instanceof ResourceLocation id) effect.addProperty("tool_data_key", id.toString());
            if (predicateJson != null) effect.add("modifier_predicate", predicateJson);
            if (allowTraits instanceof Boolean value) effect.addProperty("allows_trait_modifiers", value);
            effect.addProperty("selection_source", "runtime_modifier_choice");
            effect.addProperty("output_source", "mutable_tinker_tool_input");
        }

        private void ironsCauldronBrew(Object recipe) {
            Object fluidInput = readDeclaredField(recipe, "fluidIn");
            Object reagent = readDeclaredField(recipe, "reagent");
            int inputCount = fluidInput instanceof FluidStack stack && !stack.isEmpty() ? 1 : 0;
            if (fluidInput instanceof FluidStack stack && !stack.isEmpty()) fluid(stack, Direction.INPUT, "fluidIn()");
            if (reagent instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "reagent()", 0);
            Object fluidResults = readDeclaredField(recipe, "results");
            int outputCount = collectFluidStacks(fluidResults, Direction.OUTPUT, "results()");
            Object byproduct = readDeclaredField(recipe, "byproduct");
            Object byproductStack = byproduct instanceof java.util.Optional<?> optional ? optional.orElse(null) : null;
            if (byproductStack instanceof ItemStack stack && !stack.isEmpty()) item(stack, Direction.OUTPUT, "byproduct()");
            if (inputCount == 0 || !(reagent instanceof Ingredient) || !(fluidResults instanceof List<?>)) {
                incomplete("fluidIn()+reagent()+results()");
            }
            if (outputCount == 0 && !(byproductStack instanceof ItemStack stack && !stack.isEmpty())) incomplete("results()+byproduct()");
            requirement("consumer_machine", "irons_spellbooks:alchemist_cauldron", "BrewAlchemistCauldronRecipe");
        }

        private void twilightUncrafting(Object recipe) {
            Object input = invokeNoArg(recipe, "input");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "input()", 0);
            else incomplete("input()");
            Object results = invokeNoArg(recipe, "resultItems");
            JsonArray slots = new JsonArray();
            if (results instanceof List<?> values) {
                for (int index = 0; index < values.size(); index++) {
                    Object value = values.get(index);
                    JsonElement ingredient = value instanceof Ingredient item ? item.toJson() : null;
                    if (ingredient == null) incomplete("resultItems()[" + index + "]");
                    else slots.add(ingredient);
                }
            } else incomplete("resultItems()");
            JsonObject effect = effect("emit_uncrafting_ingredient_grid", "resultItems()");
            effect.add("result_slots", slots);
            addNumber(effect, "experience_cost", invokeNoArg(recipe, "cost"));
            addNumber(effect, "input_count", invokeNoArg(recipe, "count"));
            addNumber(effect, "width", invokeNoArg(recipe, "width"));
            addNumber(effect, "height", invokeNoArg(recipe, "height"));
        }

        private void ae2Entropy(Object recipe) {
            Object mode = invokeNoArg(recipe, "getMode");
            Object inputBlock = invokeNoArg(recipe, "getInputBlock");
            Object inputFluid = invokeNoArg(recipe, "getInputFluid");
            Object outputBlock = invokeNoArg(recipe, "getOutputBlock");
            Object outputFluid = invokeNoArg(recipe, "getOutputFluid");
            if (inputBlock instanceof Block block) resource("block", BuiltInRegistries.BLOCK.getKey(block), Direction.INPUT, "getInputBlock()");
            if (inputFluid instanceof net.minecraft.world.level.material.Fluid fluidValue) {
                resource("fluid", ForgeRegistries.FLUIDS.getKey(fluidValue), Direction.INPUT, "getInputFluid()");
            }
            if (outputBlock instanceof Block block) resource("block", BuiltInRegistries.BLOCK.getKey(block), Direction.OUTPUT, "getOutputBlock()");
            if (outputFluid instanceof net.minecraft.world.level.material.Fluid fluidValue) {
                resource("fluid", ForgeRegistries.FLUIDS.getKey(fluidValue), Direction.OUTPUT, "getOutputFluid()");
            }
            collect(invokeNoArg(recipe, "getDrops"), Direction.OUTPUT, "getDrops()", 0);
            JsonObject effect = effect("apply_ae2_entropy_world_state", "EntropyRecipe");
            if (mode != null) effect.addProperty("mode", String.valueOf(mode)); else incomplete("getMode()");
            Object keepBlock = invokeNoArg(recipe, "getOutputBlockKeep");
            Object keepFluid = invokeNoArg(recipe, "getOutputFluidKeep");
            if (keepBlock instanceof Boolean value) effect.addProperty("keep_input_block", value);
            if (keepFluid instanceof Boolean value) effect.addProperty("keep_input_fluid", value);
            effect.addProperty("state_matchers_and_appliers", "serializer_payload");
        }

        private void arsDispelEntity(Object recipe) {
            Object entity = invokeNoArg(recipe, "entity");
            Object lootTable = invokeNoArg(recipe, "lootTable");
            if (entity instanceof EntityType<?> entityType) {
                resource("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entityType), Direction.INPUT, "entity()");
            } else incomplete("entity()");
            JsonObject effect = effect("roll_loot_when_entity_is_dispelled", "result()");
            if (lootTable instanceof ResourceLocation id) effect.addProperty("loot_table", id.toString());
            else incomplete("lootTable()");
            JsonElement definition = serializeOptional(recipe);
            if (definition != null) effect.add("recipe_definition", definition); else incomplete("asRecipe()");
        }

        private void arsReactiveEnchantment(Object recipe) {
            collectField(recipe, "pedestalItems", Direction.INPUT);
            collectField(recipe, "reagent", Direction.INPUT);
            Object enchantment = readDeclaredField(recipe, "enchantment");
            if (enchantment instanceof Enchantment value) resource("enchantment", BuiltInRegistries.ENCHANTMENT.getKey(value), Direction.OUTPUT, "enchantment");
            else incomplete("enchantment");
            JsonObject effect = effect("apply_reactive_enchantment", "getResult()");
            addNumber(effect, "level", readDeclaredField(recipe, "enchantLevel"));
            effect.addProperty("output_source", "mutable_apparatus_reagent");
            JsonElement definition = serializeOptional(recipe);
            if (definition != null) effect.add("recipe_definition", definition); else incomplete("asRecipe()");
        }

        private void arsSpellWrite(Object recipe) {
            collectField(recipe, "pedestalItems", Direction.INPUT);
            collectField(recipe, "reagent", Direction.INPUT);
            JsonElement definition = serializeOptional(recipe);
            if (definition == null) incomplete("asRecipe()");
            JsonObject effect = effect("write_player_selected_spell_to_reagent", "getResult()");
            if (definition != null) effect.add("recipe_definition", definition);
            effect.addProperty("spell_source", "runtime_apparatus_player_context");
            effect.addProperty("output_source", "mutable_apparatus_reagent");
        }

        private void arsSummonRitual(Object recipe) {
            Object catalyst = readDeclaredField(recipe, "catalyst");
            if (catalyst instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "catalyst", 0);
            else incomplete("catalyst");
            JsonElement definition = serializeOptional(recipe);
            if (definition == null) incomplete("asRecipe()");
            JsonObject effect = effect("summon_weighted_entities", "SummonRitualRecipe");
            if (definition != null) effect.add("recipe_definition", definition);
            Object source = readDeclaredField(recipe, "mobSource");
            if (source != null) effect.addProperty("mob_source", String.valueOf(source)); else incomplete("mobSource");
            addNumber(effect, "count", readDeclaredField(recipe, "count"));
        }

        private void createSplashing(Object recipe) {
            JsonObject definition = invokeJsonWriter(recipe, "writeAdditional");
            if (definition == null) incomplete("writeAdditional(JsonObject)");
            JsonObject effect = effect("perform_create_fan_splashing", "writeAdditional(JsonObject)");
            if (definition != null) effect.add("processing_definition", definition);
            effect.addProperty("output_source", "create_processing_results_or_intentional_consumption");
            requirement("consumer_machine", "create:fan_splashing", "SplashingRecipe");
        }

        private void jeedEffectProvider(Object recipe) {
            Object target = readDeclaredField(recipe, "effect");
            JsonObject row = effect("register_effect_provider_metadata", "effect+providers");
            addMobEffectTarget(row, target);
            if (!(target instanceof MobEffect)) incomplete("effect");
            row.add("effect_provider_ids", mobEffectIds(readDeclaredField(recipe, "effectProviders")));
            row.add("fluid_provider_ids", registryIds(readDeclaredField(recipe, "fluidProviders"), BuiltInRegistries.FLUID));
            requirement("consumer_system", "jeed:effect_provider", "EffectProviderRecipe");
        }

        private void jeedPotionProvider(Object recipe) {
            JsonArray potions = registryIds(invokeNoArg(recipe, "getPotions"), BuiltInRegistries.POTION);
            JsonObject row = effect("register_potion_provider_metadata", "getPotions()+providers");
            row.add("potion_ids", potions);
            if (potions.isEmpty()) incomplete("getPotions()");
            requirement("consumer_system", "jeed:potion_provider", "PotionProviderRecipe");
        }

        private void complicatedBeesTemperature(Object recipe) {
            JsonObject row = effect("modify_bee_temperature_tolerance", "getTempChange()+getUseChance()");
            Object change = invokeNoArg(recipe, "getTempChange");
            Object chance = invokeNoArg(recipe, "getUseChance");
            if (change != null) row.addProperty("tolerance_change", String.valueOf(change)); else incomplete("getTempChange()");
            addNumber(row, "use_chance", chance);
            if (!(chance instanceof Number)) incomplete("getUseChance()");
            requirement("consumer_machine", "complicated_bees:temp_unit", "TempUnitRecipe");
        }

        private void complicatedBeesMutator(Object recipe) {
            JsonObject row = effect("scale_bee_mutation_chance", "getMutationModifier()");
            Object modifier = invokeNoArg(recipe, "getMutationModifier");
            addNumber(row, "mutation_modifier", modifier);
            if (!(modifier instanceof Number)) incomplete("getMutationModifier()");
            requirement("consumer_machine", "complicated_bees:mutator", "MutatorRecipe");
        }

        private void complicatedBeesHoneyFuel(Object recipe) {
            JsonObject row = effect("generate_energy_from_honey_fuel", "getBurnTime()");
            Object burnTime = invokeNoArg(recipe, "getBurnTime");
            addNumber(row, "burn_time", burnTime);
            if (!(burnTime instanceof Number)) incomplete("getBurnTime()");
            requirement("consumer_machine", "complicated_bees:honey_generator", "HoneyGeneratorRecipe");
        }

        private void arsElementalNetheriteUpgrade(Object recipe) {
            collectDeclaredField(recipe, "pedestalItems", Direction.INPUT);
            collectDeclaredField(recipe, "reagent", Direction.INPUT);
            JsonObject row = effect("upgrade_spellbook_tier", "getResult()+asRecipe()");
            row.addProperty("output_source", "mutable_apparatus_reagent");
            JsonElement definition = serializeOptional(recipe);
            if (definition != null) row.add("recipe_definition", definition); else incomplete("asRecipe()");
            requirement("consumer_machine", "ars_nouveau:enchanting_apparatus", "NetheriteUpgradeRecipe");
        }

        @SuppressWarnings("unchecked")
        private static <T> JsonArray registryIds(Object value, net.minecraft.core.Registry<T> registry) {
            JsonArray ids = new JsonArray();
            if (value instanceof Collection<?> entries) {
                entries.stream().map(entry -> registry.getKey((T) entry))
                        .filter(java.util.Objects::nonNull).map(ResourceLocation::toString).sorted().forEach(ids::add);
            }
            return ids;
        }

        private void collectSizedIngredients(Object value, String path) {
            if (!(value instanceof Collection<?> values)) {
                incomplete(path);
                return;
            }
            int index = 0;
            for (Object sized : values) {
                if (!collectSizedIngredient(sized, path + "[" + index + "]")) incomplete(path + "[" + index + "]");
                index++;
            }
        }

        private int collectFluidStacks(Object value, Direction direction, String path) {
            int count = 0;
            if (value instanceof Collection<?> values) {
                int index = 0;
                for (Object entry : values) {
                    if (entry instanceof FluidStack stack && !stack.isEmpty()) {
                        fluid(stack, direction, path + "[" + index + "]");
                        count++;
                    }
                    index++;
                }
            }
            return count;
        }

        private static JsonElement serializeOptional(Object value) {
            if (value == null) return null;
            for (String methodName : List.of("serialize", "toJson", "asRecipe")) {
                Object serialized = invokeNoArg(value, methodName);
                if (serialized instanceof JsonElement json) return json.deepCopy();
            }
            Object loader = invokeNoArg(value, "getLoader");
            Object serialized = invokeOneArg(loader, "serialize", value);
            if (serialized instanceof JsonElement json) return json.deepCopy();
            return null;
        }

        private static Object invokeOneArg(Object root, String name, Object argument) {
            if (root == null) return null;
            for (Method method : publicMethods(root.getClass())) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
                try {
                    method.trySetAccessible();
                    return method.invoke(root, argument);
                } catch (Throwable ignored) {
                    // Try another overload with the same stable method name.
                }
            }
            return null;
        }

        private static JsonObject invokeJsonWriter(Object root, String name) {
            if (root == null) return null;
            for (Method method : publicMethods(root.getClass())) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(JsonObject.class)) continue;
                try {
                    method.trySetAccessible();
                    JsonObject target = new JsonObject();
                    method.invoke(root, target);
                    return target;
                } catch (Throwable ignored) {
                    // Try another overload with the same stable method name.
                }
            }
            return null;
        }

        private void addItemAlternatives(Collection<?> values, String path, JsonArray ids) {
            JsonObject group = new JsonObject();
            group.addProperty("slot", inputGroups.size());
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            values.stream().filter(Item.class::isInstance).map(Item.class::cast)
                    .sorted(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))))
                    .forEach(item -> {
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                        if (id == null) return;
                        JsonObject edge = edge("item", id.toString(), 1, path);
                        alternatives.add(edge.deepCopy());
                        addEdge(Direction.INPUT, edge);
                        ids.add(id.toString());
                    });
            group.add("alternatives", alternatives);
            if (!alternatives.isEmpty()) inputGroups.add(group);
        }

        private static ResourceLocation tagLocation(Object value) {
            return value instanceof TagKey<?> tag ? tag.location() : null;
        }

        private static void addBlockState(JsonObject target, String key, Object value) {
            if (!(value instanceof BlockState state)) return;
            JsonObject row = new JsonObject();
            row.addProperty("block_id", String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
            JsonObject properties = new JsonObject();
            state.getValues().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> properties.addProperty(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue())));
            row.add("properties", properties);
            target.add(key, row);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static String propertyValueName(Property property, Comparable value) {
            return property.getName(value);
        }

        private void operation(String kind, String path) {
            if (operationKind != null) return;
            operationKind = kind;
            evidence("operation_kind", path);
        }

        private JsonObject effect(String kind, String path) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("semantic_path", path);
            effects.add(row);
            evidence("effect", path);
            return row;
        }

        private void incomplete(String path) {
            contextualComplete = false;
            evidence("unavailable_contextual_evidence", path);
        }

        private static void addNumber(JsonObject target, String key, Object value) {
            if (value instanceof Number number) target.addProperty(key, number);
        }

        private static void addMobEffectTarget(JsonObject target, Object value) {
            if (value instanceof MobEffect effect) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
                if (id != null) {
                    target.addProperty("target_kind", "mob_effect");
                    target.addProperty("target_id", id.toString());
                }
            }
        }

        private static JsonArray mobEffectIds(Object value) {
            JsonArray ids = new JsonArray();
            if (value instanceof Collection<?> effects) {
                effects.stream().filter(MobEffect.class::isInstance).map(MobEffect.class::cast)
                        .map(BuiltInRegistries.MOB_EFFECT::getKey).filter(java.util.Objects::nonNull)
                        .map(ResourceLocation::toString).sorted().forEach(ids::add);
            }
            return ids;
        }

        private int collectRegistryItemsBySuperclass(String className, String path) {
            List<net.minecraft.world.item.Item> matches = BuiltInRegistries.ITEM.stream()
                    .filter(item -> superclassNamed(item.getClass(), className))
                    .sorted(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))))
                    .toList();
            matches.forEach(item -> resource("item", BuiltInRegistries.ITEM.getKey(item), Direction.INPUT, path));
            return matches.size();
        }

        private static boolean superclassNamed(Class<?> type, String className) {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                if (cursor.getName().equals(className)) return true;
            }
            return false;
        }

        private boolean collectFluidOutput(Object output, Direction direction, String path) {
            Object value = invokeNoArg(output, "get");
            if (value instanceof FluidStack stack && !stack.isEmpty()) {
                fluid(stack, direction, path + ".get()");
                return true;
            }
            return false;
        }

        private boolean collectSizedIngredient(Object sized, String path) {
            Object ingredientValue = invokeNoArg(sized, "getIngredient");
            Object amountValue = invokeNoArg(sized, "getAmountNeeded");
            if (ingredientValue instanceof Ingredient ingredient) {
                ingredient(ingredient, Direction.INPUT, path + ".getIngredient()",
                        amountValue instanceof Number number ? number.intValue() : 1);
                return amountValue instanceof Number;
            }
            return false;
        }

        private void collectAccessor(Object root, String name, Direction direction) {
            try {
                Method method = root.getClass().getMethod(name);
                collect(method.invoke(root), direction, name + "()", 0);
            } catch (Throwable ignored) {
                // The exact pinned accessor is optional evidence.
            }
        }

        private void collectField(Object root, String name, Direction direction) {
            try {
                Field field = root.getClass().getField(name);
                collect(field.get(root), direction, name, 0);
            } catch (Throwable ignored) {
                // The exact pinned public field is optional evidence.
            }
        }

        private void collectDeclaredField(Object root, String name, Direction direction) {
            Object value = readDeclaredField(root, name);
            if (value != null) collect(value, direction, name, 0);
        }

        private Object readDeclaredField(Object root, String name) {
            for (Class<?> cursor = root.getClass(); cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    if (field.trySetAccessible()) return field.get(root);
                } catch (Throwable ignored) {
                    // Try the next superclass; absence is not evidence.
                }
            }
            return null;
        }

        private void collectNumberAccessor(Object root, String accessor, String requirement) {
            try {
                Object value = root.getClass().getMethod(accessor).invoke(root);
                if (value instanceof Number number) requirement(requirement, number, accessor + "()");
            } catch (Throwable ignored) {
                // A missing optional accessor is not evidence.
            }
        }

        private void collectIntRange(Object range, String prefix) {
            if (range == null) return;
            collectNumberAccessor(range, "min", prefix + "_min");
            collectNumberAccessor(range, "max", prefix + "_max");
        }

        private void collectModifierSlots(Object slots, String path) {
            if (slots == null) return;
            try {
                Object type = slots.getClass().getMethod("type").invoke(slots);
                Object countValue = slots.getClass().getMethod("count").invoke(slots);
                Object name = type.getClass().getMethod("getName").invoke(type);
                if (name instanceof String id && countValue instanceof Number count) {
                    resource("modifier_slot", "tconstruct:" + id, count.intValue(), Direction.OUTPUT, path);
                }
            } catch (Throwable ignored) {
                // A missing optional slot API is not evidence.
            }
        }

        private static List<Field> publicFields(Class<?> type) {
            return new ArrayList<>(PUBLIC_FIELDS.get(type));
        }

        void collect(Object value, Direction direction, String path, int depth) {
            if (value == null || depth > MAX_DEPTH) return;
            String className = value.getClass().getName();
            if (className.equals("slimeknights.tconstruct.library.materials.definition.MaterialVariant")) {
                resource("material", materialVariantId(value.toString()), direction, path);
                return;
            }
            if (className.equals("slimeknights.tconstruct.library.modifiers.ModifierEntry")
                    || className.equals("slimeknights.tconstruct.library.modifiers.util.LazyModifier")) {
                collectIdAccessor(value, "modifier", direction, path);
                return;
            }
            if (value instanceof Ingredient ingredient) {
                ingredient(ingredient, direction, path);
                return;
            }
            if (value instanceof ItemStack stack) {
                item(stack, direction, path);
                return;
            }
            if (value instanceof Item item) {
                resource("item", BuiltInRegistries.ITEM.getKey(item), direction, path);
                return;
            }
            if (value instanceof FluidStack stack) {
                fluid(stack, direction, path);
                return;
            }
            if (value instanceof Block block) {
                resource("block", BuiltInRegistries.BLOCK.getKey(block), direction, path);
                return;
            }
            if (value instanceof Enchantment enchantment) {
                resource("enchantment", BuiltInRegistries.ENCHANTMENT.getKey(enchantment), direction, path);
                return;
            }
            if (value instanceof ResourceLocation id) {
                resource(resourceKind(value.getClass()), id, direction, path);
                return;
            }
            if (value instanceof Collection<?> collection) {
                int index = 0;
                for (Object element : collection) collect(element, direction, path + "[" + index++ + "]", depth + 1);
                return;
            }
            if (value.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) {
                    collect(Array.get(value, index), direction, path + "[" + index + "]", depth + 1);
                }
                return;
            }
            if (isTerminal(value) || !visited.add(value)) return;
            List<Field> fields = allFields(value.getClass());
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || !field.trySetAccessible()) continue;
                Direction nested = direction(field.getName());
                if (nested == Direction.UNKNOWN) nested = direction;
                String nestedPath = path + "." + field.getName();
                try {
                    Object child = field.get(value);
                    String req = ReflectiveRecipeFamilyAdapter.requirement(field.getName());
                    if (req != null && child instanceof Number number) requirement(req, number, nestedPath);
                    collect(child, nested, nestedPath, depth + 1);
                } catch (Throwable ignored) {
                    // Inaccessible implementation detail is not evidence.
                }
            }
        }

        private void ingredient(Ingredient ingredient, Direction direction, String path) {
            ingredient(ingredient, direction, path, 1);
        }

        private void ingredient(Ingredient ingredient, Direction direction, String path, int count) {
            if (direction == Direction.UNKNOWN || direction == Direction.OUTPUT) return;
            JsonElement definition;
            try {
                definition = ingredient.toJson();
            } catch (Throwable ignored) {
                return;
            }
            JsonObject group = new JsonObject();
            group.addProperty("slot", inputGroups.size());
            group.add("ingredient", definition);
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            String tagId = RecipeGraphExporter.ingredientTag(definition);
            if (tagId != null) {
                JsonObject edge = edge("tag", tagId, Math.max(count, RecipeGraphExporter.ingredientCount(definition, ingredient)), path);
                addEdge(direction, edge);
                group.addProperty("membership", "generated/runtime-dumps/tags.json#item_tags/" + tagId);
            } else {
                try {
                    for (ItemStack stack : ingredient.getItems()) {
                        if (count > 1) stack = stack.copyWithCount(count);
                        JsonObject edge = itemEdge(stack, path);
                        alternatives.add(edge.deepCopy());
                        addEdge(direction, edge);
                    }
                } catch (Throwable ignored) {
                    return;
                }
            }
            group.add("alternatives", alternatives);
            inputGroups.add(group);
            evidence("edge", path);
        }

        private void item(ItemStack stack, Direction direction, String path) {
            if (stack.isEmpty() || direction == Direction.UNKNOWN) return;
            JsonObject edge = itemEdge(stack, path);
            addEdge(direction, edge);
            if (direction == Direction.OUTPUT) {
                JsonObject group = new JsonObject();
                group.addProperty("slot", outputGroups.size());
                group.addProperty("semantic_path", path);
                JsonArray alternatives = new JsonArray();
                alternatives.add(edge.deepCopy());
                group.add("alternatives", alternatives);
                outputGroups.add(group);
            }
            evidence("edge", path);
        }

        private void resource(String kind, ResourceLocation id, Direction direction, String path) {
            if (id != null) resource(kind, id.toString(), direction, path);
        }

        private void resource(String kind, String id, Direction direction, String path) {
            resource(kind, id, 1, direction, path);
        }

        private void resource(String kind, String id, int count, Direction direction, String path) {
            if (id == null || id.isBlank() || direction == Direction.UNKNOWN) return;
            JsonObject edge = edge(kind, id, count, path);
            addEdge(direction, edge);
            if (direction == Direction.OUTPUT) addOutputGroup(edge, path);
            evidence("edge", path);
        }

        private void collectIdAccessor(Object value, String kind, Direction direction, String path) {
            try {
                Object id = value.getClass().getMethod("getId").invoke(value);
                if (id instanceof ResourceLocation resourceId) resource(kind, resourceId, direction, path + ".getId()");
            } catch (Throwable ignored) {
                // A missing optional accessor is not evidence.
            }
        }

        private static String resourceKind(Class<?> type) {
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (name.contains("material")) return "material";
            if (name.contains("modifier")) return "modifier";
            return "resource";
        }

        private void fluid(FluidStack stack, Direction direction, String path) {
            if (stack.isEmpty() || direction == Direction.UNKNOWN || direction == Direction.CATALYST) return;
            String id = String.valueOf(ForgeRegistries.FLUIDS.getKey(stack.getFluid()));
            JsonObject edge = edge("fluid", id, stack.getAmount(), path);
            if (stack.hasTag()) edge.addProperty("nbt", stack.getTag().toString());
            addUnique(direction == Direction.OUTPUT ? fluidsOut : fluidsIn, edge);
            evidence("edge", path);
        }

        private void addEdge(Direction direction, JsonObject edge) {
            JsonArray target = switch (direction) {
                case INPUT -> inputs;
                case OUTPUT -> outputs;
                case CATALYST -> catalysts;
                case UNKNOWN -> null;
            };
            if (target != null) addUnique(target, edge);
        }

        private void addOutputGroup(JsonObject edge, String path) {
            JsonObject group = new JsonObject();
            group.addProperty("slot", outputGroups.size());
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            alternatives.add(edge.deepCopy());
            group.add("alternatives", alternatives);
            outputGroups.add(group);
        }

        private void addUnique(JsonArray target, JsonObject edge) {
            String key = System.identityHashCode(target) + ":" + edge;
            if (seenEdges.add(key)) target.add(edge);
        }

        private void evidence(String kind, String path) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("path", path);
            evidence.add(row);
        }

        private static JsonObject itemEdge(ItemStack stack, String path) {
            JsonObject edge = edge("item", String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())), stack.getCount(), path);
            if (stack.hasTag()) edge.addProperty("nbt", stack.getTag().toString());
            return edge;
        }

        private static JsonObject edge(String kind, String id, int count, String path) {
            JsonObject edge = new JsonObject();
            edge.addProperty("kind", kind);
            edge.addProperty("id", id);
            edge.addProperty("count", Math.max(1, count));
            edge.addProperty("semantic_path", path);
            return edge;
        }

        private static boolean isTerminal(Object value) {
            Class<?> type = value.getClass();
            return type.isPrimitive() || value instanceof Number || value instanceof CharSequence
                    || value instanceof Boolean || value instanceof Enum<?> || type.getName().startsWith("java.time.")
                    || type.getName().startsWith("net.minecraft.resources.");
        }

        private static List<Field> allFields(Class<?> type) {
            return new ArrayList<>(ALL_FIELDS.get(type));
        }
    }
}
