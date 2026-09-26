package com.bettercontent.runtimedatadumper;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Inert outside the full-pack Debug fixture. */
@Mod.EventBusSubscriber(modid = RecipeGraphMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DebugWorldProbe {
    private static final String MODE = System.getProperty("bc.pack_test.world", "");
    private static final boolean DEBUG_MULTIPLAYER = Boolean.getBoolean("bc.pack_test.debug");
    private static int ticks;
    private static boolean completed;
    private static String lastPosition = "";

    private DebugWorldProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (DEBUG_MULTIPLAYER && client.level != null && client.player != null) {
            var chunk = client.player.chunkPosition();
            String position = client.level.dimension().location() + " " + chunk.x + " " + chunk.z;
            if (!position.equals(lastPosition)) {
                lastPosition = position;
                RecipeGraphMod.LOGGER.info("BC_DEBUG_CLIENT_POSITION dimension={} chunk_x={} chunk_z={}",
                        client.level.dimension().location(), chunk.x, chunk.z);
            }
        }
        if (completed || !(MODE.equals("save") || MODE.equals("verify"))) return;
        if (client.level == null || client.getSingleplayerServer() == null || ++ticks < 200) return;
        completed = true;
        var server = client.getSingleplayerServer();
        RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_LOADED mode={} game_time={}", MODE, server.overworld().getGameTime());
        if (MODE.equals("save")) {
            server.execute(() -> {
                server.saveEverything(false, true, true);
                RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_SAVED game_time={}", server.overworld().getGameTime());
                client.execute(() -> {
                    client.clearLevel();
                    RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_EXITED");
                });
            });
        }
    }
}
