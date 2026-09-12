package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecipeGraphExporterTest {
    private static class FamilyBase {}
    private static final class FamilyChild extends FamilyBase {}

    @Test
    void reflectionIsConfinedToExactNamedCachedAdapterBoundaries() throws Exception {
        Set<String> allowed = Set.of(
                "com/bettercontent/runtimedatadumper/ReflectiveFabricBiomeModifierAdapter.java",
                "com/bettercontent/runtimedatadumper/ReflectivePneumaticRecipeAdapter.java",
                "com/bettercontent/runtimedatadumper/ReflectiveRecipeFamilyAdapter.java");
        String reflectionSyntax = "java.lang.reflect|\\.getDeclared(?:Field|Fields|Method|Methods|Constructor|Constructors)\\("
                + "|\\.getMethod\\(|\\.getMethods\\(|trySetAccessible\\(|setAccessible\\(|\\.invoke\\(";

        Set<String> matches;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            matches = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).matches("(?s).*?(?:" + reflectionSyntax + ").*");
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    })
                    .map(path -> Path.of("src/main/java").relativize(path).toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(allowed, matches);
    }

    @Test
    void recipeGraphV3UsesDeterministicQuantitativeFamilyAdapters() throws Exception {
        String exporter = Files.readString(Path.of(
                "src/main/java/com/bettercontent/runtimedatadumper/RecipeGraphExporter.java"));
        String adapter = Files.readString(Path.of(
                "src/main/java/com/bettercontent/runtimedatadumper/ReflectiveRecipeFamilyAdapter.java"));
        assertTrue(exporter.contains("bc.recipe_graph.v3"));
        assertTrue(exporter.contains("semantics.authoritativeOutputs()"));
        assertTrue(adapter.contains("getRollableResults"));
        assertTrue(adapter.contains("getMatchingFluidStacks"));
        assertTrue(adapter.contains("getFluids"));
        assertTrue(adapter.contains("method.getName().equals(\"rollResults\")"));
    }

    @Test
    void optionalRecipeFamiliesAreRecognizedThroughTheirSuperclass() {
        assertTrue(ReflectiveRecipeFamilyAdapter.classOrSuperclassNamed(
                FamilyChild.class, FamilyBase.class.getName()));
        assertFalse(ReflectiveRecipeFamilyAdapter.classOrSuperclassNamed(
                FamilyChild.class, String.class.getName()));
    }

    @Test
    void deterministicOutputsExposeOnlyNonCertainProbabilities() {
        JsonObject probabilistic = JsonParser.parseString("{\"kind\":\"item\"}").getAsJsonObject();
        ReflectiveRecipeFamilyAdapter.addChance(probabilistic, 0.25);
        assertEquals(0.25, probabilistic.get("chance").getAsDouble());

        JsonObject certain = JsonParser.parseString("{\"kind\":\"item\"}").getAsJsonObject();
        ReflectiveRecipeFamilyAdapter.addChance(certain, 1.0);
        assertFalse(certain.has("chance"));
    }

    @Test
    void pneumaticFamilyAccessorDiscoveryIsCachedByConcreteClass() {
        int before = ReflectivePneumaticRecipeAdapter.resolutionCountForTests();
        FakePneumaticRecipe recipe = new FakePneumaticRecipe();

        assertTrue(ReflectivePneumaticRecipeAdapter.supports(recipe, "pneumaticcraft:pressure_chamber"));
        assertTrue(ReflectivePneumaticRecipeAdapter.supports(recipe, "pneumaticcraft:pressure_chamber"));
        assertEquals(before + 1, ReflectivePneumaticRecipeAdapter.resolutionCountForTests());
        assertFalse(ReflectivePneumaticRecipeAdapter.supports(recipe, "minecraft:crafting"));
    }

    public static final class FakePneumaticRecipe {
        public List<Object> getInputsForDisplay() { return List.of(); }
        public List<Object> getResultsForDisplay() { return List.of(); }
        public float getCraftingPressureForDisplay() { return 2.5F; }
    }

    @Test
    void dimensionIdentifiersAreStableAndSorted() {
        JsonArray rows = RuntimeEvidenceExporter.sortedIds(List.of(
                ResourceLocation.tryParse("twilightforest:twilight_forest"),
                ResourceLocation.tryParse("minecraft:overworld"),
                ResourceLocation.tryParse("creatingspace:mars")));
        assertEquals(
                List.of("creatingspace:mars", "minecraft:overworld", "twilightforest:twilight_forest"),
                rows.asList().stream().map(element -> element.getAsString()).toList());
    }

    @Test
    void detectsTagSelectorsWithoutMisclassifyingExplicitAlternatives() {
        assertEquals("minecraft:planks", RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("{\"tag\":\"minecraft:planks\"}")));
        assertNull(RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("[{\"item\":\"minecraft:oak_planks\"},{\"item\":\"minecraft:spruce_planks\"}]")));
        assertNull(RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("{\"item\":\"minecraft:oak_planks\"}")));
    }

    @Test
    void tradeSamplingSeedsAreStableAndDistinguishSamples() {
        long first = RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 0);
        assertEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 0));
        assertNotEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 1));
        assertNotEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:librarian", "minecraft:plains", 2, 3, 0));
    }

    @Test
    void worldDependentMapTradesAreDeferredBeforeTheirFactoriesRun() {
        assertTrue(RuntimeEvidenceExporter.isWorldDependentTradeListing(
                "com.bettercontent.dimensiondrink.trade.DimensionalFontMapListing"));
        assertTrue(RuntimeEvidenceExporter.isWorldDependentTradeListing(
                "net.minecraft.world.entity.npc.VillagerTrades$TreasureMapForEmeralds"));
        assertFalse(RuntimeEvidenceExporter.isWorldDependentTradeListing(
                "net.minecraft.world.entity.npc.VillagerTrades$ItemsForEmeralds"));
        assertFalse(RuntimeEvidenceExporter.isWorldDependentTradeListing(
                "example.SafeItemListing"));
    }

    @Test
    void serializedIngredientCountsSurviveNormalization() {
        assertEquals(4, RecipeGraphExporter.ingredientCount(
                JsonParser.parseString("{\"tag\":\"forge:ingots/iron\",\"count\":4}"), null));
    }

    @Test
    void completenessRequiresEveryNormalizerAndRuntimeExporterToSucceed() {
        assertTrue(RecipeGraphExporter.isComplete(0, 0, 0));
        assertFalse(RecipeGraphExporter.isComplete(1, 0, 0));
        assertFalse(RecipeGraphExporter.isComplete(0, 1, 0));
        assertFalse(RecipeGraphExporter.isComplete(0, 0, 1));
    }

    @Test
    void semanticAccessorsAreClassifiedWithoutGuessingUnrelatedGetters() {
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.INPUT, ReflectiveRecipeFamilyAdapter.direction("getInputFluid"));
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.OUTPUT, ReflectiveRecipeFamilyAdapter.direction("getOutputWithByproducts"));
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.INPUT, ReflectiveRecipeFamilyAdapter.direction("getFluidIn"));
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.OUTPUT, ReflectiveRecipeFamilyAdapter.direction("getFluidOut"));
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.CATALYST, ReflectiveRecipeFamilyAdapter.direction("getCatalyst"));
        assertEquals(ReflectiveRecipeFamilyAdapter.Direction.UNKNOWN, ReflectiveRecipeFamilyAdapter.direction("getId"));
        assertEquals("pressure", ReflectiveRecipeFamilyAdapter.requirement("getRequiredPressure"));
        assertEquals("heat", ReflectiveRecipeFamilyAdapter.requirement("getTemperature"));
        assertEquals("time", ReflectiveRecipeFamilyAdapter.requirement("getTicks"));
        assertEquals("energy", ReflectiveRecipeFamilyAdapter.requirement("getSourceCost"));
        assertNull(ReflectiveRecipeFamilyAdapter.requirement("getMinimumTier"));
    }

    @Test
    void unavailableOptionalSignaturesAreSkippedInsteadOfCrashingTheDump() {
        assertTrue(ReflectiveRecipeFamilyAdapter.<Method>safeMembers(() -> {
            throw new NoClassDefFoundError("client-only optional recipe display type");
        }).isEmpty());
        assertFalse(ReflectiveRecipeFamilyAdapter.publicMethods(String.class).isEmpty());
        assertEquals("tconstruct:rock#stone", ReflectiveRecipeFamilyAdapter.materialVariantId("MaterialVariant{tconstruct:rock#stone}"));
    }

    @Test
    void semanticAdaptersMayAddTypedRequirementsBeyondTheCoreMachineFields() {
        JsonObject requirements = JsonParser.parseString("{\"energy\":null}").getAsJsonObject();
        RecipeGraphExporter.mergeRequirement(requirements, "max_tool_size", JsonParser.parseString("4"));
        assertEquals(4, requirements.get("max_tool_size").getAsInt());
        RecipeGraphExporter.mergeRequirement(requirements, "max_tool_size", JsonParser.parseString("9"));
        assertEquals(4, requirements.get("max_tool_size").getAsInt());
    }

    @Test
    void contextualFamiliesHaveExplicitMachineNavigableOperationKinds() {
        assertEquals("potion_flask_state_mutation", ReflectiveRecipeFamilyAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionIncreaseLength"));
        assertEquals("potion_flask_state_mutation", ReflectiveRecipeFamilyAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionTransform"));
        assertNull(ReflectiveRecipeFamilyAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionFlaskTransform"));
        assertEquals("material_scaled_melting", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe"));
        assertEquals("conditional_part_recycling", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle"));
        assertEquals("conditional_tool_part_recycling", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle"));
        assertEquals("tool_state_mutation", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe"));
        assertEquals("effect_provider_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "net.mehvahdjukaar.jeed.recipes.EffectProviderRecipe"));
        assertEquals("bee_temperature_tolerance_modifier", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.accbdd.complicated_bees.recipe.TempUnitRecipe"));
        assertEquals("spellbook_tier_upgrade", ReflectiveRecipeFamilyAdapter.operationKind(
                "alexthw.ars_elemental.recipe.NetheriteUpgradeRecipe"));
        assertEquals("matter_cannon_ammo_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "appeng.recipes.mattercannon.MatterCannonAmmo"));
        assertEquals("non_gameplay_client_recipe_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.almostreliable.unified.recipe.ClientRecipeTracker"));
        assertEquals("spirit_item_repair", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.sammy.malum.common.recipe.SpiritRepairRecipe"));
        assertEquals("block_heat_property_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl"));
        assertEquals("fluid_fuel_property_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl"));
        assertEquals("ritual_block_highlight", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe"));
        assertEquals("living_armor_downgrade_mutation", ReflectiveRecipeFamilyAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.RecipeLivingDowngrade"));
        assertEquals("entity_brewing_effect", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.Polarice3.Goety.common.crafting.BrewingRecipe"));
        assertEquals("soul_absorption", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes"));
        assertEquals("dynamic_item_state_crafting", ReflectiveRecipeFamilyAdapter.operationKind(
                "net.minecraft.world.item.crafting.ArmorDyeRecipe"));
        assertEquals("tool_overslime_restoration", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.modifiers.adding.OverslimeCraftingTableRecipe"));
        assertEquals("tool_modifier_extraction", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.tools.recipe.ExtractModifierRecipe"));
        assertEquals("ritual_meteor_world_effect", ReflectiveRecipeFamilyAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.RecipeMeteor"));
        assertEquals("placement_policy_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.aetherteam.aether.recipe.recipes.ban.BlockBanRecipe"));
        assertEquals("item_modifier_application", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.stal111.forbidden_arcanus.common.recipe.ApplyModifierRecipe"));
        assertEquals("armor_tier_upgrade", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.hollingsworth.arsnouveau.api.enchanting_apparatus.ArmorUpgradeRecipe"));
    }

    @Test
    void clientSynchronizationTrackersRemainDistinctFromGameplaySemantics() {
        assertEquals("non_gameplay_client_recipe_metadata", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.almostreliable.unified.recipe.ClientRecipeTracker"));
        assertEquals("tool_part_replacement", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.TinkerStationPartSwapping"));
        assertEquals("gas_reaction", ReflectiveRecipeFamilyAdapter.operationKind(
                "org.valkyrienskies.clockwork.content.logistics.gas.crafter.GasCraftingRecipe"));
        assertEquals("tool_modifier_set_mutation", ReflectiveRecipeFamilyAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.worktable.ModifierSetWorktableRecipe"));
        assertEquals("dynamic_item_state_crafting", ReflectiveRecipeFamilyAdapter.operationKind(
                "net.mehvahdjukaar.supplementaries.common.items.crafting.WeatheredMapRecipe"));
        assertEquals("dynamic_item_state_crafting", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.github.alexthe666.rats.server.recipes.DemonRatSwitchRecipe"));
        assertEquals("item_disassembly", ReflectiveRecipeFamilyAdapter.operationKind(
                "twilightforest.item.recipe.UncraftingRecipe"));
        assertEquals("fluid_brewing", ReflectiveRecipeFamilyAdapter.operationKind(
                "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe"));
        assertEquals("world_state_transform", ReflectiveRecipeFamilyAdapter.operationKind(
                "appeng.recipes.entropy.EntropyRecipe"));
        assertEquals("fan_splashing", ReflectiveRecipeFamilyAdapter.operationKind(
                "com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe"));
    }

    @Test
    void contextualEffectsAreValidOutcomesWithoutInventedStaticOutputs() {
        JsonArray effects = JsonParser.parseString("[{\"kind\":\"add_tool_damage\",\"damage\":5}]").getAsJsonArray();
        assertTrue(RecipeGraphExporter.hasNavigableOutcome(new JsonArray(), new JsonArray(), effects));
        assertFalse(RecipeGraphExporter.hasNavigableOutcome(new JsonArray(), new JsonArray(), new JsonArray()));
    }

    @Test
    void lightingStatePropertiesAreSerializedDeterministically() {
        LinkedHashMap<String, String> unsorted = new LinkedHashMap<>();
        unsorted.put("waterlogged", "false");
        unsorted.put("lit", "true");
        JsonObject properties = LightingExporter.propertiesJson(unsorted);
        assertEquals(List.of("lit", "waterlogged"), properties.keySet().stream().toList());
    }

    @Test
    void portableLightingAcceptsFixedAndBlockDerivedLuminance() {
        LightingExporter.DefinitionResult fixed = LightingExporter.classifyDefinition(
                "test:fixed", "test.jar",
                JsonParser.parseString("{\"item\":\"minecraft:blaze_rod\",\"luminance\":10,\"water_sensitive\":true}").getAsJsonObject(),
                id -> id.equals(ResourceLocation.tryParse("minecraft:blaze_rod")) ? new LightingExporter.ResolvedItem(true, null, 0) : null,
                ignored -> null);
        assertTrue(fixed.accepted());
        assertEquals("fixed", fixed.row().get("luminance_mode").getAsString());
        assertEquals(10, fixed.row().get("light_level").getAsInt());
        assertTrue(fixed.row().get("water_sensitive").getAsBoolean());

        LightingExporter.DefinitionResult block = LightingExporter.classifyDefinition(
                "test:block", "test.jar",
                JsonParser.parseString("{\"item\":\"minecraft:torch\",\"luminance\":\"block\"}").getAsJsonObject(),
                id -> id.equals(ResourceLocation.tryParse("minecraft:torch")) ? new LightingExporter.ResolvedItem(true, "minecraft:torch", 14) : null,
                ignored -> null);
        assertTrue(block.accepted());
        assertEquals("item_block_default_state", block.row().get("luminance_mode").getAsString());
        assertEquals(14, block.row().get("light_level").getAsInt());
    }

    @Test
    void portableLightingSupportsReferencedBlocksAndRejectsInactiveCompatibility() {
        LightingExporter.DefinitionResult referenced = LightingExporter.classifyDefinition(
                "test:lava", "test.jar",
                JsonParser.parseString("{\"item\":\"minecraft:bucket\",\"luminance\":\"minecraft:lava\"}").getAsJsonObject(),
                id -> id.equals(ResourceLocation.tryParse("minecraft:bucket")) ? new LightingExporter.ResolvedItem(true, null, 0) : null,
                id -> id.equals(ResourceLocation.tryParse("minecraft:lava")) ? new LightingExporter.ResolvedBlock(true, "minecraft:lava", 15) : null);
        assertTrue(referenced.accepted());
        assertEquals("minecraft:lava", referenced.row().get("luminance_block").getAsString());
        assertEquals(15, referenced.row().get("light_level").getAsInt());

        LightingExporter.DefinitionResult missing = LightingExporter.classifyDefinition(
                "compat:lamp", "compat.jar",
                JsonParser.parseString("{\"item\":\"absent:lamp\",\"luminance\":15}").getAsJsonObject(),
                ignored -> null,
                ignored -> null);
        assertFalse(missing.accepted());
        assertEquals("missing_item", missing.row().get("reason").getAsString());

        LightingExporter.DefinitionResult unsupported = LightingExporter.classifyDefinition(
                "compat:tag", "compat.jar",
                JsonParser.parseString("{\"match\":{\"items\":\"#compat:lamps\"},\"luminance\":8}").getAsJsonObject(),
                ignored -> null,
                ignored -> null);
        assertFalse(unsupported.accepted());
        assertEquals("unsupported_schema", unsupported.row().get("reason").getAsString());
    }

}
