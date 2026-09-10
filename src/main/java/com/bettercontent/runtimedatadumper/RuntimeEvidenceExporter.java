package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootDataId;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RuntimeEvidenceExporter {
    private static final int TRADE_SAMPLE_COUNT = 16;

    private RuntimeEvidenceExporter() {}

    static JsonObject loot(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonObject tables = new JsonObject();
        JsonArray issues = new JsonArray();
        LootDataManager manager = server.getLootData();
        List<ResourceLocation> keys = new ArrayList<>(manager.getKeys(LootDataType.TABLE));
        keys.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation key : keys) {
            try {
                LootTable table = manager.getElement(new LootDataId<>(LootDataType.TABLE, key));
                tables.add(key.toString(), LootDataType.TABLE.parser().toJsonTree(table));
            } catch (Throwable error) {
                issues.add(issue(key.toString(), error));
            }
        }
        out.addProperty("registered_table_count", keys.size());
        out.addProperty("table_count", tables.size());
        out.addProperty("error_count", issues.size());
        out.addProperty("complete", issues.isEmpty() && tables.size() == keys.size());
        out.addProperty("evidence_mode", "live_effective_loot_table_serialization");
        out.addProperty("limitation", "Loaded table definitions do not prove that a chest, entity, structure, fishing, ritual, or other loot context occurs in reachable gameplay.");
        out.add("tables", tables);
        out.add("issues", issues);
        return out;
    }

    static JsonObject trades(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonArray villagers = new JsonArray();
        JsonArray wanderer = new JsonArray();
        JsonArray deferred = new JsonArray();
        JsonArray issues = new JsonArray();
        TradeCounters counters = new TradeCounters();

        Villager entity = EntityType.VILLAGER.create(server.overworld());
        if (entity == null) {
            issues.add("could not construct villager sample entity");
        } else {
            VillagerTrades.TRADES.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> registryId(BuiltInRegistries.VILLAGER_PROFESSION, entry.getKey())))
                    .forEach(entry -> exportProfession(entry.getKey(), entry.getValue(), entity, villagers, deferred, issues, counters));
            entity.discard();
        }

        WanderingTrader wanderingEntity = EntityType.WANDERING_TRADER.create(server.overworld());
        if (wanderingEntity == null) {
            issues.add("could not construct wandering trader sample entity");
        } else {
            VillagerTrades.WANDERING_TRADER_TRADES.int2ObjectEntrySet().stream()
                    .sorted(Comparator.comparingInt(it -> it.getIntKey()))
                    .forEach(entry -> exportListings(
                            "minecraft:wandering_trader", "minecraft:wandering_trader", entry.getIntKey(),
                            entry.getValue(), wanderingEntity, wanderer, deferred, issues, counters));
            wanderingEntity.discard();
        }

        out.addProperty("sampling", "deterministic_16_seeds_per_listing");
        out.addProperty("evidence_mode", "deterministic_listing_sample");
        out.addProperty("complete", false);
        out.addProperty("sample_contract_complete", issues.isEmpty());
        out.addProperty("listing_context_count", counters.listingContexts);
        out.addProperty("sample_attempt_count", counters.sampleAttempts);
        out.addProperty("null_offer_count", counters.nullOffers);
        out.addProperty("deferred_listing_count", deferred.size());
        out.addProperty("limitation", "Representative offers are sampled from live local listing functions; world-dependent map listings are recorded but not executed because they may synchronously locate structures or generate chunks.");
        out.addProperty("villager_offer_count", villagers.size());
        out.addProperty("wandering_offer_count", wanderer.size());
        out.addProperty("error_count", issues.size());
        out.add("villager_offers", villagers);
        out.add("wandering_offers", wanderer);
        out.add("deferred_listings", deferred);
        out.add("issues", issues);
        return out;
    }

    private static void exportProfession(
            VillagerProfession profession,
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<VillagerTrades.ItemListing[]> levels,
            Villager entity,
            JsonArray output,
            JsonArray deferred,
            JsonArray issues,
            TradeCounters counters
    ) {
        String professionId = registryId(BuiltInRegistries.VILLAGER_PROFESSION, profession);
        List<VillagerType> types = BuiltInRegistries.VILLAGER_TYPE.stream()
                .sorted(Comparator.comparing(type -> registryId(BuiltInRegistries.VILLAGER_TYPE, type)))
                .toList();
        levels.int2ObjectEntrySet().stream().sorted(Comparator.comparingInt(it -> it.getIntKey())).forEach(level -> {
            for (VillagerType type : types) {
                String typeId = registryId(BuiltInRegistries.VILLAGER_TYPE, type);
                entity.setVillagerData(new VillagerData(type, profession, level.getIntKey()));
                exportListings(professionId, typeId, level.getIntKey(), level.getValue(), entity, output, deferred, issues, counters);
            }
        });
    }

    private static void exportListings(
            String profession,
            String villagerType,
            int level,
            VillagerTrades.ItemListing[] listings,
            Entity entity,
            JsonArray output,
            JsonArray deferred,
            JsonArray issues,
            TradeCounters counters
    ) {
        for (int listingIndex = 0; listingIndex < listings.length; listingIndex++) {
            counters.listingContexts++;
            VillagerTrades.ItemListing listing = listings[listingIndex];
            if (isWorldDependentTradeListing(listing.getClass().getName())) {
                deferred.add(deferredListing(profession, villagerType, level, listingIndex, listing));
                continue;
            }
            Map<String, JsonObject> distinct = new LinkedHashMap<>();
            for (int sample = 0; sample < TRADE_SAMPLE_COUNT; sample++) {
                counters.sampleAttempts++;
                try {
                    long seed = tradeSeed(profession, villagerType, level, listingIndex, sample);
                    MerchantOffer offer = listing.getOffer(entity, RandomSource.create(seed));
                    if (offer == null) {
                        counters.nullOffers++;
                        continue;
                    }
                    JsonObject row = offerJson(profession, villagerType, level, listingIndex, listing, offer, sample);
                    distinct.putIfAbsent(offer.createTag().toString(), row);
                } catch (Exception error) {
                    issues.add(issue(profession + "/" + villagerType + "/" + level + "/" + listingIndex, error));
                    break;
                }
            }
            distinct.values().forEach(output::add);
        }
    }

    static boolean isWorldDependentTradeListing(String className) {
        String normalized = className.toLowerCase(Locale.ROOT);
        return normalized.contains("map") && (
                normalized.contains("listing")
                        || normalized.contains("trade")
                        || normalized.contains("offer")
                        || normalized.contains("treasure")
        );
    }

    private static JsonObject deferredListing(
            String profession,
            String villagerType,
            int level,
            int listingIndex,
            VillagerTrades.ItemListing listing
    ) {
        JsonObject row = new JsonObject();
        row.addProperty("profession", profession);
        row.addProperty("villager_type", villagerType);
        row.addProperty("level", level);
        row.addProperty("listing_index", listingIndex);
        row.addProperty("listing_class", listing.getClass().getName());
        row.addProperty("reason", "world_dependent_map_generation");
        return row;
    }

    private static JsonObject offerJson(
            String profession,
            String villagerType,
            int level,
            int listingIndex,
            VillagerTrades.ItemListing listing,
            MerchantOffer offer,
            int sample
    ) {
        JsonObject row = new JsonObject();
        row.addProperty("profession", profession);
        row.addProperty("villager_type", villagerType);
        row.addProperty("level", level);
        row.addProperty("listing_index", listingIndex);
        row.addProperty("listing_class", listing.getClass().getName());
        row.addProperty("representative_sample", sample);
        row.add("cost_a", stack(offer.getBaseCostA()));
        row.add("cost_b", stack(offer.getCostB()));
        row.add("result", stack(offer.getResult()));
        row.addProperty("max_uses", offer.getMaxUses());
        row.addProperty("villager_xp", offer.getXp());
        row.addProperty("price_multiplier", offer.getPriceMultiplier());
        row.addProperty("reward_exp", offer.shouldRewardExp());
        row.addProperty("offer_nbt", offer.createTag().toString());
        return row;
    }

    static JsonObject worldgen(RegistryAccess access) {
        JsonObject out = new JsonObject();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        JsonArray issues = new JsonArray();
        JsonObject counts = new JsonObject();
        addRegistry(out, counts, "configured_features", encodeRegistry(access, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC, ops, issues));
        addRegistry(out, counts, "placed_features", encodeRegistry(access, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC, ops, issues));
        addRegistry(out, counts, "biomes", encodeRegistry(access, Registries.BIOME, Biome.DIRECT_CODEC, ops, issues));
        addRegistry(out, counts, "structures", encodeRegistry(access, Registries.STRUCTURE, Structure.DIRECT_CODEC, ops, issues));
        addRegistry(out, counts, "biome_modifiers", encodeRegistry(access, ForgeRegistries.Keys.BIOME_MODIFIERS, BiomeModifier.DIRECT_CODEC, ops, issues));
        out.add("registry_counts", counts);
        out.addProperty("error_count", issues.size());
        out.addProperty("complete", issues.isEmpty());
        out.addProperty("evidence_mode", "live_worldgen_registry_serialization");
        out.addProperty("limitation", "Registry presence and codec state do not prove placement frequency, spatial distribution, biome attachment, or occurrence in an existing world.");
        out.add("issues", issues);
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static JsonObject dimensions(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonArray issues = new JsonArray();
        JsonArray loaded = sortedIds(server.levelKeys().stream()
                .map(ResourceKey::location)
                .toList());

        ResourceLocation registryId = ResourceLocation.tryParse("creatingspace:rocket_accessible_dimension");
        ResourceKey registryKey = ResourceKey.createRegistryKey(registryId);
        JsonArray rocketAccessible = new JsonArray();
        try {
            Object candidate = server.registryAccess().registry(registryKey).orElse(null);
            if (candidate instanceof Registry<?> registry) {
                registry.keySet().stream()
                        .map(Object::toString)
                        .sorted()
                        .forEach(rocketAccessible::add);
            } else {
                if (net.minecraftforge.fml.ModList.get().isLoaded("creatingspace")) {
                    issues.add("loaded Creating Space has no " + registryId + " registry");
                }
            }
        } catch (Throwable error) {
            issues.add(issue(registryId.toString(), error));
        }

        out.addProperty("registry", registryId.toString());
        out.addProperty("loaded_dimension_count", loaded.size());
        out.addProperty("rocket_accessible_dimension_count", rocketAccessible.size());
        out.addProperty("error_count", issues.size());
        out.addProperty("complete", issues.isEmpty());
        out.addProperty("evidence_mode", "live_server_levels_and_dynamic_registry_keys");
        out.addProperty("limitation", "Registry membership proves configured destinations, not that terrain generation, arrival safety, or rocket travel succeeds.");
        out.add("loaded_dimensions", loaded);
        out.add("rocket_accessible_dimensions", rocketAccessible);
        out.add("issues", issues);
        return out;
    }

    static JsonArray sortedIds(Collection<ResourceLocation> ids) {
        JsonArray rows = new JsonArray();
        ids.stream().map(ResourceLocation::toString).sorted().forEach(rows::add);
        return rows;
    }

    private static void addRegistry(JsonObject out, JsonObject counts, String name, EncodedRegistry encoded) {
        out.add(name, encoded.rows());
        JsonObject count = new JsonObject();
        count.addProperty("registered", encoded.registered());
        count.addProperty("encoded", encoded.rows().size());
        counts.add(name, count);
    }

    private static <T> EncodedRegistry encodeRegistry(
            RegistryAccess access,
            ResourceKey<? extends Registry<T>> key,
            Codec<T> codec,
            RegistryOps<JsonElement> ops,
            JsonArray issues
    ) {
        JsonObject rows = new JsonObject();
        Registry<T> registry;
        try {
            registry = access.registryOrThrow(key);
        } catch (Exception error) {
            issues.add(issue(key.location().toString(), error));
            return new EncodedRegistry(rows, 0);
        }
        registry.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().location().toString())).forEach(entry -> {
            String id = entry.getKey().location().toString();
            try {
                JsonObject row = new JsonObject();
                row.addProperty("java_class", entry.getValue().getClass().getName());
                JsonElement encoded = codec.encodeStart(ops, entry.getValue())
                        .getOrThrow(false, message -> { throw new IllegalStateException(message); });
                row.add("value", encoded);
                rows.add(id, row);
            } catch (Throwable error) {
                JsonObject fallback = ReflectiveFabricBiomeModifierAdapter.encodeIfEmpty(entry.getValue());
                if (fallback != null) {
                    rows.add(id, fallback);
                } else {
                    issues.add(issue(key.location() + "/" + id, error));
                }
            }
        });
        return new EncodedRegistry(rows, registry.size());
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", "item");
        out.addProperty("id", registryId(ForgeRegistries.ITEMS, stack.getItem()));
        out.addProperty("count", stack.getCount());
        if (stack.hasTag()) out.addProperty("nbt", stack.getTag().toString());
        return out;
    }

    static long tradeSeed(String profession, String type, int level, int index, int sample) {
        long seed = 1125899906842597L;
        seed = seed * 31 + profession.hashCode();
        seed = seed * 31 + type.hashCode();
        seed = seed * 31 + level;
        seed = seed * 31 + index;
        return seed * 31 + sample;
    }

    private static final class TradeCounters {
        int listingContexts;
        int sampleAttempts;
        int nullOffers;
    }

    private record EncodedRegistry(JsonObject rows, int registered) {}

    private static JsonObject issue(String id, Throwable error) {
        JsonObject issue = new JsonObject();
        issue.addProperty("id", id);
        issue.addProperty("error", error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        return issue;
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key == null ? "UNKNOWN" : key.toString();
    }

    private static <T> String registryId(net.minecraftforge.registries.IForgeRegistry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key == null ? "UNKNOWN" : key.toString();
    }
}
