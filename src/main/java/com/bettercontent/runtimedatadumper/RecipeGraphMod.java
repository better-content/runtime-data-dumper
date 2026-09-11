package com.bettercontent.runtimedatadumper;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(RecipeGraphMod.MOD_ID)
public final class RecipeGraphMod {
    public static final String MOD_ID = "runtime_data_dumper";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeGraphMod() {
        MinecraftForge.EVENT_BUS.register(RecipeGraphCommands.class);
    }
}
