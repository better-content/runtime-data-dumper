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
    private static int ticks;
    private static boolean completed;

    private DebugWorldProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || completed || !(MODE.equals("save") || MODE.equals("verify"))) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.getSingleplayerServer() == null || ++ticks < 200) return;
        completed = true;
        RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_LOADED mode={} game_time={}", MODE, client.getSingleplayerServer().overworld().getGameTime());
        if (MODE.equals("save")) {
            client.getSingleplayerServer().saveEverything(false, true, true);
            RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_SAVED game_time={}", client.getSingleplayerServer().overworld().getGameTime());
            client.clearLevel();
            RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_EXITED");
        }
    }
}
