package com.blockplague;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class PlagueCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {

        dispatcher.register(
            literal("plague")
                .requires(src -> src.hasPermissionLevel(2))

                // /plague start [x y z]
                .then(literal("start")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        BlockPos pos = BlockPos.ofFloored(source.getPosition());
                        String dim = source.getWorld().getRegistryKey().getValue().toString();
                        PlagueManager.getInstance().startPlague(source.getServer(), pos, dim);
                        source.sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §aPlague #" + PlagueManager.getInstance().getInstanceCount() +
                            " started at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                            " spreading §e" + PlagueManager.getInstance().getBlock().getName().getString()
                        ), true);
                        return 1;
                    })
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
                            String dim = source.getWorld().getRegistryKey().getValue().toString();
                            PlagueManager.getInstance().startPlague(source.getServer(), pos, dim);
                            source.sendFeedback(() -> Text.literal(
                                "§6[Block Plague] §aPlague #" + PlagueManager.getInstance().getInstanceCount() +
                                " started at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                                " spreading §e" + PlagueManager.getInstance().getBlock().getName().getString()
                            ), true);
                            return 1;
                        })
                    )
                )

                // /plague stop  — stops the most recent plague
                .then(literal("stop")
                    .executes(ctx -> {
                        PlagueManager.getInstance().stopLast();
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §cMost recent plague stopped. " +
                            PlagueManager.getInstance().getInstanceCount() + " remaining."
                        ), true);
                        return 1;
                    })
                )

                // /plague stopall
                .then(literal("stopall")
                    .executes(ctx -> {
                        int count = PlagueManager.getInstance().getInstanceCount();
                        PlagueManager.getInstance().stopAll();
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §cAll " + count + " plagues stopped."
                        ), true);
                        return 1;
                    })
                )

                // /plague setblock <block>
                .then(literal("setblock")
                    .then(argument("block", BlockStateArgumentType.blockState(registryAccess))
                        .executes(ctx -> {
                            BlockStateArgument blockArg = BlockStateArgumentType.getBlockState(ctx, "block");
                            var block = blockArg.getBlockState().getBlock();
                            PlagueManager.getInstance().setBlock(block);
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                "§6[Block Plague] §aPlague block set to §e" + block.getName().getString()
                            ), false);
                            return 1;
                        })
                    )
                )

                // /plague setrate <rate> [unit]
                .then(literal("setrate")
                    .then(argument("rate", DoubleArgumentType.doubleArg(0.0))
                        .then(argument("unit", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                builder.suggest("blocks_per_second");
                                builder.suggest("blocks_per_minute");
                                builder.suggest("blocks_per_hour");
                                builder.suggest("blocks_per_day");
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                double rate = DoubleArgumentType.getDouble(ctx, "rate");
                                String unit = StringArgumentType.getString(ctx, "unit");
                                return setRate(ctx.getSource(), rate, unit);
                            })
                        )
                        .executes(ctx -> {
                            double rate = DoubleArgumentType.getDouble(ctx, "rate");
                            return setRate(ctx.getSource(), rate, "blocks_per_second");
                        })
                    )
                )

                // /plague status
                .then(literal("status")
                    .executes(ctx -> {
                        var mgr = PlagueManager.getInstance();
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §7Status:\n" +
                            "  §fActive plagues: §a" + mgr.getInstanceCount() + "\n" +
                            "  §fBlock: §e" + mgr.getBlock().getName().getString() + "\n" +
                            "  §fRate: §b" + formatRate(mgr.getRatePerSecond()) + "\n" +
                            "  §fTotal converted: §d" + mgr.getTotalConverted() + "\n" +
                            "  §fTotal frontier: §d" + mgr.getTotalFrontier()
                        ), false);
                        return 1;
                    })
                )

                // /plague help
                .then(literal("help")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §fCommands:\n" +
                            "  §e/plague start §7[x y z] §f- Start a new plague (multiple can run at once!)\n" +
                            "  §e/plague stop §f- Stop the most recently started plague\n" +
                            "  §e/plague stopall §f- Stop ALL active plagues\n" +
                            "  §e/plague setblock <block> §f- Set the spreading block type\n" +
                            "  §e/plague setrate <rate> [unit] §f- Set spread rate\n" +
                            "    §7Units: blocks_per_second (default), blocks_per_minute,\n" +
                            "           blocks_per_hour, blocks_per_day\n" +
                            "    §7Range: 1/day up to 10,000/second\n" +
                            "  §e/plague status §f- Show all active plagues info\n" +
                            "  §e/plague help §f- Show this message\n" +
                            "§7Behaviours: tendrils, air tendrils, spikes, wide spikes,\n" +
                            "  tree patterns, jumps, rare webs, mob engulfing"
                        ), false);
                        return 1;
                    })
                )
        );
    }

    private static int setRate(ServerCommandSource source, double rate, String unit) {
        double bps;
        String label;

        switch (unit.toLowerCase()) {
            case "blocks_per_second" -> { bps = rate; label = rate + " blocks/second"; }
            case "blocks_per_minute" -> { bps = rate / 60.0; label = rate + " blocks/minute"; }
            case "blocks_per_hour"   -> { bps = rate / 3600.0; label = rate + " blocks/hour"; }
            case "blocks_per_day"    -> { bps = rate / 86400.0; label = rate + " blocks/day"; }
            default -> {
                source.sendError(Text.literal("Unknown unit. Use: blocks_per_second, blocks_per_minute, blocks_per_hour, blocks_per_day"));
                return 0;
            }
        }

        double minBps = 1.0 / 86400.0;
        double maxBps = 10000.0;

        if (bps > maxBps) { source.sendError(Text.literal("Max rate is 10,000 blocks/second.")); return 0; }
        if (bps < minBps * 0.999) { source.sendError(Text.literal("Min rate is 1 block/day.")); return 0; }

        PlagueManager.getInstance().setRate(bps);
        final String finalLabel = label;
        source.sendFeedback(() -> Text.literal(
            "§6[Block Plague] §aRate set to §e" + finalLabel + " §a(§b" + formatRate(bps) + "§a)"
        ), true);
        return 1;
    }

    static String formatRate(double bps) {
        if (bps >= 1.0)        return String.format("%.1f blocks/sec", bps);
        if (bps * 60 >= 1.0)   return String.format("%.2f blocks/min", bps * 60);
        if (bps * 3600 >= 1.0) return String.format("%.2f blocks/hr", bps * 3600);
        return                        String.format("%.2f blocks/day", bps * 86400);
    }
}
