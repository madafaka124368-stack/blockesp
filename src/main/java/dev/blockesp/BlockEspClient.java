package dev.blockesp;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

public class BlockEspClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockEsp.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("blockesp")
                // /blockesp -> master switch
                .executes(ctx -> {
                    BlockEsp.enabled = !BlockEsp.enabled;
                    ctx.getSource().sendFeedback(Component.literal("Block ESP: " + onOff(BlockEsp.enabled)));
                    return 1;
                })
                // /blockesp chests | barrels | hoppers -> toggle that type
                .then(ClientCommandManager.literal("chests")
                    .executes(ctx -> {
                        BlockEsp.chests = !BlockEsp.chests;
                        ctx.getSource().sendFeedback(Component.literal("Chests (gold): " + onOff(BlockEsp.chests)));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("barrels")
                    .executes(ctx -> {
                        BlockEsp.barrels = !BlockEsp.barrels;
                        ctx.getSource().sendFeedback(Component.literal("Barrels (cyan): " + onOff(BlockEsp.barrels)));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("hoppers")
                    .executes(ctx -> {
                        BlockEsp.hoppers = !BlockEsp.hoppers;
                        ctx.getSource().sendFeedback(Component.literal("Hoppers (purple): " + onOff(BlockEsp.hoppers)));
                        return 1;
                    }))
                // /blockesp distance <blocks>
                .then(ClientCommandManager.literal("distance")
                    .then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(8, 700))
                        .executes(ctx -> {
                            BlockEsp.rangeBlocks = IntegerArgumentType.getInteger(ctx, "blocks");
                            ctx.getSource().sendFeedback(Component.literal("Block ESP max distance: " + BlockEsp.rangeBlocks + " blocks"));
                            return 1;
                        })))
                // /blockesp status
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal(
                            "Block ESP " + onOff(BlockEsp.enabled)
                                + " | chests " + onOff(BlockEsp.chests)
                                + " | barrels " + onOff(BlockEsp.barrels)
                                + " | hoppers " + onOff(BlockEsp.hoppers)
                                + " | distance " + BlockEsp.rangeBlocks + " blocks"));
                        return 1;
                    }))));
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }
}
