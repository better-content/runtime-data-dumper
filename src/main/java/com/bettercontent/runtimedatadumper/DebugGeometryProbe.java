package com.bettercontent.runtimedatadumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Read-only census of the chunks already loaded around a Debug client. */
final class DebugGeometryProbe {
    private static final int RADIUS_CHUNKS = 1;

    private DebugGeometryProbe() {}

    static Path capture(ServerPlayer player, String id) throws IOException {
        if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("invalid geometry probe id");
        var level = player.serverLevel();
        var center = player.chunkPosition();
        Map<String, Long> counts = new TreeMap<>();
        int sampled = 0;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                if (chunk == null) throw new IllegalStateException("chunk not loaded: " + (center.x + dx) + "," + (center.z + dz));
                sampled++;
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null) continue;
                    section.getStates().count((state, count) -> {
                        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        counts.merge(block, (long) count, Long::sum);
                    });
                }
            }
        }
        var result = new JsonObject();
        result.addProperty("schema", "bc.geometry_probe.v1");
        result.addProperty("id", id);
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("center_chunk_x", center.x);
        result.addProperty("center_chunk_z", center.z);
        result.addProperty("chunk_count", sampled);
        var blocks = new JsonObject();
        counts.forEach(blocks::addProperty);
        result.add("blocks", blocks);
        Path output = level.getServer().getServerDirectory().toPath().resolve("generated/runtime-dumps/geometry/" + id + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        return output;
    }
}
