package com.bettercontent.runtimedatadumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeGraphExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GRAPH_SCHEMA = "bc.recipe_graph.v2";
    private static final String REGISTRY_SCHEMA = "bc.registries.v2";
    private static final String TAG_SCHEMA = "bc.tags.v2";
    private static final String MOD_SCHEMA = "bc.mods.v2";
    private static final String LOOT_SCHEMA = "bc.loot.v1";
    private static final String TRADE_SCHEMA = "bc.trades.v1";
    private static final String WORLDGEN_SCHEMA = "bc.worldgen.v1";
    private static final String DIMENSIONS_SCHEMA = "bc.dimensions.v1";
    private static final String LIGHTING_SCHEMA = "bc.lighting.v1";

    private RecipeGraphExporter() {}

    public static DumpResult dump(MinecraftServer server) {
        Path output = server.getServerDirectory().toPath().resolve("generated/runtime-dumps").normalize();
        String generatedAt = Instant.now().toString();
        String snapshotId = shortHash(generatedAt + "\n" + server.getWorldData().getLevelName());
        try {
            Files.createDirectories(output);
            List<Recipe<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
            recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));

            JsonArray recipeRows = new JsonArray();
            Map<String, int[]> coverage = new LinkedHashMap<>();
            int partial = 0;
            int errors = 0;
            for (Recipe<?> recipe : recipes) {
                RecipeExport exported = exportRecipe(recipe, server.registryAccess());
                recipeRows.add(exported.row());
                int[] counts = coverage.computeIfAbsent(exported.type(), ignored -> new int[3]);
                counts[0]++;
                if (exported.partial()) {
                    partial++;
                    counts[1]++;
                }
                if (exported.error()) {
                    errors++;
                    counts[2]++;
                }
            }

            JsonObject graph = envelope(GRAPH_SCHEMA, snapshotId, generatedAt, server);
            graph.addProperty("recipe_count", recipes.size());
            graph.addProperty("normalized_recipe_count", recipes.size() - partial);
            graph.addProperty("partial_count", partial);
            graph.addProperty("error_count", errors);
            graph.addProperty("complete", isComplete(partial, errors, 0));
            graph.addProperty("evidence_mode", "live_recipe_manager_exact_when_complete");
            graph.addProperty("limitation", "Rows with parsed=false or normalization=partial are retained diagnostics, not complete machine-navigable edges.");
            graph.add("coverage", coverageJson(coverage));
            graph.add("recipes", recipeRows);

            JsonObject registries = envelope(REGISTRY_SCHEMA, snapshotId, generatedAt, server);
            registries.add("items", registryRows(BuiltInRegistries.ITEM));
            registries.add("blocks", registryRows(BuiltInRegistries.BLOCK));
            registries.add("fluids", registryRows(BuiltInRegistries.FLUID));
            registries.add("entities", registryRows(BuiltInRegistries.ENTITY_TYPE));
            markExactSnapshot(registries, "live_registry_snapshot");

            JsonObject tags = envelope(TAG_SCHEMA, snapshotId, generatedAt, server);
            RegistryAccess access = server.registryAccess();
            tags.add("item_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.ITEM)));
            tags.add("block_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)));
            tags.add("fluid_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.FLUID)));
            tags.add("entity_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE)));
            markExactSnapshot(tags, "live_tag_snapshot");

            JsonObject mods = envelope(MOD_SCHEMA, snapshotId, generatedAt, server);
            JsonObject modRows = new JsonObject();
            ModList.get().getMods().stream().sorted(Comparator.comparing(info -> info.getModId())).forEach(info -> {
                JsonObject row = new JsonObject();
                row.addProperty("display_name", info.getDisplayName());
                row.addProperty("version", info.getVersion().toString());
                modRows.add(info.getModId(), row);
            });
            mods.add("mods", modRows);
            markExactSnapshot(mods, "live_loaded_mod_snapshot");

            JsonObject loot = envelope(LOOT_SCHEMA, snapshotId, generatedAt, server);
            copyInto(loot, RuntimeEvidenceExporter.loot(server));

            JsonObject trades = envelope(TRADE_SCHEMA, snapshotId, generatedAt, server);
            copyInto(trades, RuntimeEvidenceExporter.trades(server));

            JsonObject worldgen = envelope(WORLDGEN_SCHEMA, snapshotId, generatedAt, server);
            copyInto(worldgen, RuntimeEvidenceExporter.worldgen(server.registryAccess()));

            JsonObject dimensions = envelope(DIMENSIONS_SCHEMA, snapshotId, generatedAt, server);
            copyInto(dimensions, RuntimeEvidenceExporter.dimensions(server));

            JsonObject lighting = envelope(LIGHTING_SCHEMA, snapshotId, generatedAt, server);
            copyInto(lighting, LightingExporter.export());

            writeAtomic(output.resolve("recipes.json"), graph);
            writeAtomic(output.resolve("registries.json"), registries);
            writeAtomic(output.resolve("tags.json"), tags);
            writeAtomic(output.resolve("mods.json"), mods);
            writeAtomic(output.resolve("loot.json"), loot);
            writeAtomic(output.resolve("trades.json"), trades);
            writeAtomic(output.resolve("worldgen.json"), worldgen);
            writeAtomic(output.resolve("dimensions.json"), dimensions);
            writeAtomic(output.resolve("lighting.json"), lighting);

            int runtimeEvidenceErrors = loot.get("error_count").getAsInt()
                    + trades.get("error_count").getAsInt()
                    + worldgen.get("error_count").getAsInt()
                    + dimensions.get("error_count").getAsInt()
                    + lighting.get("error_count").getAsInt();
            boolean complete = isComplete(partial, errors, runtimeEvidenceErrors)
                    && loot.get("complete").getAsBoolean()
                    && trades.get("sample_contract_complete").getAsBoolean()
                    && worldgen.get("complete").getAsBoolean()
                    && dimensions.get("complete").getAsBoolean()
                    && lighting.get("complete").getAsBoolean();
            JsonObject completion = envelope("bc.runtime_dump_completion.v3", snapshotId, generatedAt, server);
            completion.addProperty("recipe_count", recipes.size());
            completion.addProperty("partial_count", partial);
            completion.addProperty("error_count", errors);
            completion.addProperty("loot_table_count", loot.get("table_count").getAsInt());
            completion.addProperty("trade_offer_count", trades.get("villager_offer_count").getAsInt() + trades.get("wandering_offer_count").getAsInt());
            completion.addProperty("runtime_evidence_error_count", runtimeEvidenceErrors);
            completion.addProperty("complete", complete);
            completion.addProperty("evidence_state", complete ? "complete" : "incomplete");
            JsonObject surfaces = new JsonObject();
            surfaces.add("recipes", surface("exact", graph.get("complete").getAsBoolean(), false));
            surfaces.add("registries", surface("exact", true, false));
            surfaces.add("tags", surface("exact", true, false));
            surfaces.add("mods", surface("exact", true, false));
            surfaces.add("loot", surface("exact_loaded_tables", loot.get("complete").getAsBoolean(), false));
            surfaces.add("trades", surface("deterministic_sample", trades.get("sample_contract_complete").getAsBoolean(), true));
            surfaces.add("worldgen", surface("exact_registry_serialization", worldgen.get("complete").getAsBoolean(), false));
            surfaces.add("dimensions", surface("live_server_levels_and_dynamic_registry_keys", dimensions.get("complete").getAsBoolean(), false));
            surfaces.add("lighting", surface("live_registry_and_loaded_mod_resource_scan", lighting.get("complete").getAsBoolean(), false));
            completion.add("surfaces", surfaces);
            JsonArray files = new JsonArray();
            for (String name : List.of("recipes.json", "registries.json", "tags.json", "mods.json", "loot.json", "trades.json", "worldgen.json", "dimensions.json", "lighting.json")) {
                files.add(name);
            }
            completion.add("files", files);
            writeAtomic(output.resolve("snapshot.json"), completion);
            return new DumpResult(true, complete, snapshotId, recipes.size(), partial, errors, output.toString(), complete ? "ok" : "incomplete");
        } catch (Exception error) {
            return DumpResult.failure(error.getClass().getSimpleName() + ": " + error.getMessage(), output.toString());
        }
    }

    private static void copyInto(JsonObject target, JsonObject source) {
        source.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue()));
    }

    private static void markExactSnapshot(JsonObject target, String mode) {
        target.addProperty("evidence_mode", mode);
        target.addProperty("complete", true);
        target.addProperty("error_count", 0);
    }

    private static JsonObject surface(String mode, boolean completeForContract, boolean sampled) {
        JsonObject out = new JsonObject();
        out.addProperty("mode", mode);
        out.addProperty("complete_for_contract", completeForContract);
        out.addProperty("sampled", sampled);
        return out;
    }

    static boolean isComplete(int partial, int errors, int runtimeEvidenceErrors) {
        return partial == 0 && errors == 0 && runtimeEvidenceErrors == 0;
    }

    private static RecipeExport exportRecipe(Recipe<?> recipe, RegistryAccess access) {
        JsonObject row = new JsonObject();
        String serializer = id(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        String type = typeKey == null ? serializer : typeKey.toString();
        row.addProperty("id", recipe.getId().toString());
        row.addProperty("type", type);
        row.addProperty("serializer", serializer);
        row.addProperty("recipe_class", recipe.getClass().getName());
        row.addProperty("serializer_class", recipe.getSerializer().getClass().getName());
        row.addProperty("special", recipe.isSpecial());
        row.addProperty("incomplete", recipe.isIncomplete());

        JsonArray issues = new JsonArray();
        JsonArray groups = new JsonArray();
        JsonArray flatInputs = new JsonArray();
        JsonArray outputGroups = new JsonArray();
        boolean partial = false;
        String adapter = null;
        try {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ReflectivePneumaticRecipeAdapter.supports(recipe, type)) {
                ingredients = ReflectivePneumaticRecipeAdapter.inputs(recipe);
                adapter = "pneumaticcraft:pressure_chamber_display_api";
            }
            int slot = 0;
            for (Ingredient ingredient : ingredients) {
                JsonObject group = new JsonObject();
                group.addProperty("slot", slot++);
                JsonElement ingredientJson = null;
                try {
                    ingredientJson = ingredient.toJson();
                    group.add("ingredient", ingredientJson);
                } catch (Exception error) {
                    group.addProperty("ingredient_error", describe(error));
                    partial = true;
                }
                JsonArray alternatives = new JsonArray();
                String tagId = ingredientTag(ingredientJson);
                if (tagId != null) {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("kind", "tag");
                    tag.addProperty("id", tagId);
                    tag.addProperty("count", ingredientCount(ingredientJson, ingredient));
                    flatInputs.add(tag);
                    group.addProperty("membership", "generated/runtime-dumps/tags.json#item_tags/" + tagId);
                } else {
                    try {
                        for (ItemStack stack : ingredient.getItems()) {
                            JsonObject alternative = stackJson(stack, "item");
                            alternatives.add(alternative);
                            flatInputs.add(alternative.deepCopy());
                        }
                    } catch (Exception error) {
                        group.addProperty("alternatives_error", describe(error));
                        partial = true;
                    }
                }
                group.add("alternatives", alternatives);
                groups.add(group);
            }
        } catch (Exception error) {
            issues.add("ingredients: " + describe(error));
            partial = true;
        }
        row.add("input_groups", groups);
        row.add("inputs", flatInputs);

        JsonArray outputs = new JsonArray();
        try {
            if (adapter != null) {
                int slot = 0;
                for (List<ItemStack> alternatives : ReflectivePneumaticRecipeAdapter.outputs(recipe)) {
                    JsonObject group = new JsonObject();
                    group.addProperty("slot", slot++);
                    JsonArray rows = new JsonArray();
                    for (ItemStack stack : alternatives) {
                        if (stack.isEmpty()) continue;
                        JsonObject stackRow = stackJson(stack, "item");
                        rows.add(stackRow);
                        outputs.add(stackRow.deepCopy());
                    }
                    group.add("alternatives", rows);
                    outputGroups.add(group);
                }
            } else {
                ItemStack result = recipe.getResultItem(access);
                if (!result.isEmpty()) {
                    JsonObject stackRow = stackJson(result, "item");
                    outputs.add(stackRow);
                    JsonObject group = new JsonObject();
                    group.addProperty("slot", 0);
                    JsonArray rows = new JsonArray();
                    rows.add(stackRow.deepCopy());
                    group.add("alternatives", rows);
                    outputGroups.add(group);
                }
            }
        } catch (Exception error) {
            issues.add("primary_output: " + describe(error));
            partial = true;
        }

        JsonArray catalysts = new JsonArray();
        JsonArray fluidsIn = new JsonArray();
        JsonArray fluidsOut = new JsonArray();
        JsonArray effects = new JsonArray();
        String operationKind = "item_transform";

        JsonObject requirements = new JsonObject();
        requirements.add("energy", null);
        requirements.add("time", null);
        requirements.add("heat", null);
        requirements.add("pressure", null);
        if (adapter != null) {
            try {
                requirements.addProperty("pressure", ReflectivePneumaticRecipeAdapter.pressure(recipe));
            } catch (Exception error) {
                issues.add("pressure: " + describe(error));
                partial = true;
            }
        }

        ReflectiveRecipeFamilyAdapter.Result semantics = ReflectiveRecipeFamilyAdapter.inspect(recipe);
        if (groups.isEmpty()) {
            append(groups, semantics.inputGroups());
            append(flatInputs, semantics.inputs());
        }
        if (outputs.isEmpty() && !semantics.outputs().isEmpty()) {
            append(outputs, semantics.outputs());
            append(outputGroups, semantics.outputGroups());
        }
        append(catalysts, semantics.catalysts());
        append(fluidsIn, semantics.fluidsIn());
        append(fluidsOut, semantics.fluidsOut());
        append(effects, semantics.effects());
        if (semantics.operationKind() != null) operationKind = semantics.operationKind();
        semantics.requirements().entrySet().forEach(entry -> {
            mergeRequirement(requirements, entry.getKey(), entry.getValue());
        });
        if (semantics.hasSemantics()) {
            adapter = adapter == null ? "public_recipe_semantics_v2" : adapter + "+public_recipe_semantics_v2";
            row.add("normalization_evidence", semantics.evidence());
        }
        if (!semantics.contextualComplete()) {
            issues.add("contextual adapter could not prove every typed effect");
            partial = true;
        }

        if (!hasNavigableOutcome(outputs, fluidsOut, effects)) {
            issues.add("no static output or contextual effect");
            partial = true;
        }
        row.addProperty("operation_kind", operationKind);
        row.add("outputs", outputs);
        row.add("output_groups", outputGroups);
        row.add("catalysts", catalysts);
        row.add("fluids_in", fluidsIn);
        row.add("fluids_out", fluidsOut);
        row.add("effects", effects);
        row.add("requirements", requirements);
        JsonArray machines = new JsonArray();
        JsonObject machine = new JsonObject();
        machine.addProperty("kind", "recipe_type");
        machine.addProperty("id", type);
        machines.add(machine);
        row.add("machines", machines);

        boolean payloadError = false;
        JsonObject payload = new JsonObject();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeNetworkPayload(recipe, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            payload.addProperty("encoding", "minecraft_recipe_serializer_network_v1");
            payload.addProperty("byte_length", bytes.length);
            payload.addProperty("sha256", hash(bytes));
            payload.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
        } catch (Exception error) {
            payload.addProperty("error", describe(error));
            issues.add("serializer_payload: " + describe(error));
            payloadError = true;
            partial = true;
        } finally {
            buffer.release();
        }
        if (groups.isEmpty() && flatInputs.isEmpty() && fluidsIn.isEmpty() && !hasNavigableOutcome(outputs, fluidsOut, effects)) {
            issues.add("no normalized inputs, outputs, or contextual effects");
            partial = true;
        }
        row.add("serializer_payload", payload);
        row.add("issues", issues);
        if (adapter != null) row.addProperty("normalization_adapter", adapter);
        row.addProperty("normalization", payloadError ? "error" : partial ? "partial" : adapter != null ? "adapter" : "standard");
        row.addProperty("parsed", !partial);
        return new RecipeExport(row, type, partial, payloadError);
    }

    private static void append(JsonArray target, JsonArray source) {
        source.forEach(target::add);
    }

    static void mergeRequirement(JsonObject requirements, String key, JsonElement value) {
        JsonElement existing = requirements.get(key);
        if (existing == null || existing.isJsonNull()) requirements.add(key, value);
    }

    static boolean hasNavigableOutcome(JsonArray outputs, JsonArray fluidsOut, JsonArray effects) {
        return !outputs.isEmpty() || !fluidsOut.isEmpty() || !effects.isEmpty();
    }

    static int ingredientCount(JsonElement ingredient, Ingredient fallback) {
        if (ingredient != null && ingredient.isJsonObject()) {
            JsonElement count = ingredient.getAsJsonObject().get("count");
            if (count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
                return Math.max(1, count.getAsInt());
            }
        }
        int maximum = 1;
        try {
            for (ItemStack stack : fallback.getItems()) maximum = Math.max(maximum, stack.getCount());
        } catch (Exception ignored) {
            // The surrounding normalizer reports ingredient expansion failures.
        }
        return maximum;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeNetworkPayload(Recipe<?> recipe, FriendlyByteBuf buffer) {
        ((RecipeSerializer) recipe.getSerializer()).toNetwork(buffer, recipe);
    }

    private static JsonObject stackJson(ItemStack stack, String kind) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind);
        out.addProperty("id", id(ForgeRegistries.ITEMS.getKey(stack.getItem())));
        out.addProperty("count", stack.getCount());
        if (stack.hasTag()) out.addProperty("nbt", stack.getTag().toString());
        return out;
    }

    static String ingredientTag(JsonElement ingredient) {
        if (ingredient == null || !ingredient.isJsonObject()) return null;
        JsonObject object = ingredient.getAsJsonObject();
        JsonElement tag = object.get("tag");
        return tag != null && tag.isJsonPrimitive() ? tag.getAsString() : null;
    }

    private static <T> JsonObject registryRows(Registry<T> registry) {
        JsonObject rows = new JsonObject();
        registry.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(key -> {
            T value = registry.get(key);
            JsonObject row = new JsonObject();
            row.addProperty("namespace", key.getNamespace());
            if (value != null) row.addProperty("java_class", value.getClass().getName());
            if (value instanceof Item item) {
                row.addProperty("description_id", item.getDescriptionId());
                row.addProperty("max_stack_size", item.getMaxStackSize());
                row.addProperty("max_damage", item.getMaxDamage());
            } else if (value instanceof Block block) {
                row.addProperty("description_id", block.getDescriptionId());
                Item blockItem = block.asItem();
                if (blockItem != net.minecraft.world.item.Items.AIR) {
                    row.addProperty("item_id", id(ForgeRegistries.ITEMS.getKey(blockItem)));
                }
                row.addProperty("loot_table", block.getLootTable().toString());
            } else if (value instanceof EntityType<?> entityType) {
                row.addProperty("loot_table", entityType.getDefaultLootTable().toString());
            }
            rows.add(key.toString(), row);
        });
        return rows;
    }

    private static <T> JsonObject tagRows(Registry<T> registry) {
        JsonObject rows = new JsonObject();
        registry.getTagNames().sorted(Comparator.comparing(tag -> tag.location().toString())).forEach(tag -> {
            JsonArray values = new JsonArray();
            registry.getTag(tag).ifPresent(named -> named.stream()
                    .map(holder -> registry.getKey(holder.value()))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(key -> values.add(key.toString())));
            rows.add(tag.location().toString(), values);
        });
        return rows;
    }

    private static JsonObject coverageJson(Map<String, int[]> coverage) {
        JsonObject out = new JsonObject();
        coverage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject row = new JsonObject();
            row.addProperty("total", entry.getValue()[0]);
            row.addProperty("partial", entry.getValue()[1]);
            row.addProperty("errors", entry.getValue()[2]);
            out.add(entry.getKey(), row);
        });
        return out;
    }

    private static JsonObject envelope(String schema, String snapshotId, String generatedAt, MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("schema", schema);
        out.addProperty("snapshot_id", snapshotId);
        out.addProperty("generated_at", generatedAt);
        out.addProperty("world", server.getWorldData().getLevelName());
        out.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        out.addProperty("forge", FMLLoader.versionInfo().forgeVersion());
        out.addProperty("loader", "forge");
        out.addProperty("generated_by", RecipeGraphMod.MOD_ID + ":/runtimedata dump");
        return out;
    }

    private static void writeAtomic(Path target, JsonElement value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String id(ResourceLocation id) {
        return id == null ? "UNKNOWN" : id.toString();
    }

    private static String shortHash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RecipeExport(JsonObject row, String type, boolean partial, boolean error) {}
}
