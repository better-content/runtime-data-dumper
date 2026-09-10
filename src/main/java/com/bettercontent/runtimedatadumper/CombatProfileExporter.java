package com.bettercontent.runtimedatadumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.registries.ForgeRegistries;

final class CombatProfileExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double BOSS_HEALTH_THRESHOLD = 100.0;

    private CombatProfileExporter() {}

    static CombatDumpResult dump(MinecraftServer server) {
        Path output = server.getServerDirectory().toPath().resolve("generated/runtime-dumps/combat-profile.json").normalize();
        List<CombatArmorCanon.Sample> samples = new ArrayList<>();
        JsonArray issues = new JsonArray();

        ForgeRegistries.ENTITY_TYPES.getEntries().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                .forEach(entry -> sample(server, entry.getKey().location(), entry.getValue(), samples, issues));

        try {
            CombatArmorCanon.Representatives canon = CombatArmorCanon.representatives(samples);
            JsonObject root = new JsonObject();
            root.addProperty("schema", "bc.runtime_combat_profile.v1");
            root.addProperty("generated_at", Instant.now().toString());
            root.addProperty("boss_health_exclusion_threshold", BOSS_HEALTH_THRESHOLD);
            root.addProperty("hostility_classifier", "monster_category_or_enemy_interface");

            JsonObject weights = new JsonObject();
            weights.addProperty("trash", 0.60);
            weights.addProperty("elite", 0.30);
            weights.addProperty("boss", 0.10);
            root.add("edps_weights", weights);

            JsonObject representatives = new JsonObject();
            representatives.addProperty("trash", canon.trash());
            representatives.addProperty("elite", canon.elite());
            representatives.addProperty("boss", canon.boss());
            representatives.addProperty("trash_elite_boundary", canon.trashEliteBoundary());
            representatives.addProperty("elite_boss_boundary", canon.eliteBossBoundary());
            representatives.addProperty("canonical_toughness", 0.0);
            root.add("armor_canon", representatives);

            JsonArray entities = new JsonArray();
            samples.stream().sorted(Comparator.comparing(CombatArmorCanon.Sample::entityId)).forEach(sample -> {
                JsonObject row = new JsonObject();
                row.addProperty("entity", sample.entityId());
                row.addProperty("armor", sample.armor());
                row.addProperty("armor_toughness", sample.armorToughness());
                row.addProperty("max_health", sample.maxHealth());
                row.addProperty("excluded_boss", sample.excludedBoss());
                entities.add(row);
            });
            root.add("hostile_samples", entities);
            root.add("issues", issues);

            Files.createDirectories(output.getParent());
            Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            int bosses = (int)samples.stream().filter(CombatArmorCanon.Sample::excludedBoss).count();
            return new CombatDumpResult(true, samples.size(), bosses, issues.size(), output.toString(), "ok");
        } catch (IOException | RuntimeException error) {
            RecipeGraphMod.LOGGER.error("Combat profile export failed", error);
            return CombatDumpResult.failure(output.toString(), error.getMessage());
        }
    }

    private static void sample(
            MinecraftServer server,
            ResourceLocation id,
            net.minecraft.world.entity.EntityType<?> type,
            List<CombatArmorCanon.Sample> samples,
            JsonArray issues
    ) {
        Entity entity = null;
        try {
            entity = type.create(server.overworld());
            if (entity instanceof LivingEntity living
                    && (type.getCategory() == MobCategory.MONSTER || living instanceof Enemy)) {
                double health = living.getAttributeValue(Attributes.MAX_HEALTH);
                samples.add(new CombatArmorCanon.Sample(
                        id.toString(),
                        living.getAttributeValue(Attributes.ARMOR),
                        living.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                        health,
                        health >= BOSS_HEALTH_THRESHOLD
                ));
            }
        } catch (RuntimeException error) {
            JsonObject issue = new JsonObject();
            issue.addProperty("entity", id.toString());
            issue.addProperty("message", error.toString());
            issues.add(issue);
        } finally {
            if (entity != null) {
                entity.discard();
            }
        }
    }
}
