package com.bettercontent.runtimedatadumper;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class RecipeGraphCommands {
    private RecipeGraphCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("runtimedata")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dump").executes(context -> {
                    var result = RecipeGraphExporter.dump(context.getSource().getServer());
                    if (result.success()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Recipe graph " + result.snapshotId() + ": " + result.recipeCount()
                                        + " recipes, " + result.partialCount() + " partial, "
                                        + result.errorCount() + " errors, "
                                        + (result.complete() ? "complete" : "INCOMPLETE")
                                        + " -> " + result.outputDirectory()), true);
                        if (!result.complete()) {
                            context.getSource().sendFailure(Component.literal(
                                    "Runtime evidence is incomplete and must not be promoted; inspect snapshot.json and recipe issues."));
                        }
                        return result.complete() ? 1 : 0;
                    }
                    context.getSource().sendFailure(Component.literal("Recipe graph dump failed: " + result.message()));
                    return 0;
                }))
                .then(Commands.literal("combat").executes(context -> {
                    var result = CombatProfileExporter.dump(context.getSource().getServer());
                    if (result.success()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Combat profile: " + result.sampledEntities() + " hostile samples, "
                                        + result.excludedBosses() + " boss exclusions, " + result.errors()
                                        + " sampling issues -> " + result.output()), true);
                        return 1;
                    }
                    context.getSource().sendFailure(Component.literal("Combat profile dump failed: " + result.message()));
                    return 0;
                }));
        if (Boolean.getBoolean("bc.pack_test.debug")) {
            root.then(Commands.literal("geometry")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(context -> {
                        String id = StringArgumentType.getString(context, "id");
                        try {
                            var output = DebugGeometryProbe.capture(context.getSource().getPlayerOrException(), id);
                            context.getSource().sendSuccess(() -> Component.literal("BC_GEOMETRY_PROBE id=" + id + " path=" + output), true);
                            return 1;
                        } catch (Exception failure) {
                            context.getSource().sendFailure(Component.literal("BC_GEOMETRY_PROBE_FAILED id=" + id + " reason=" + failure.getMessage()));
                            return 0;
                        }
                    })));
        }
        event.getDispatcher().register(root);
    }
}
