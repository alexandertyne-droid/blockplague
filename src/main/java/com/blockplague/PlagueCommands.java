package com.blockplague;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.command.CommandManager;
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

                // /plague start [x y z]  — start plague at given pos or player pos
                .then(literal("start")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        BlockPos pos = BlockPos.ofFloored(source.getPosition());
                        String dimKey = source.getWorld().getRegistryKey().getValue().toString();
                        PlagueManager.getInstance().startPlague(source.getServer(), pos, dimKey);
                        source.sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §aPlague started at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                            " spreading §e" + PlagueManager.getInstance().getBlock().getName().getString()
                        ), true);
                        return 1;
                    })
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
                            String dimKey = source.getWorld().getRegistryKey().getValue().toString();
                            PlagueManager.getInstance().startPlague(source.getServer(), pos, dimKey);
                            source.sendFeedback(() -> Text.literal(
                                "§6[Block Plague] §aPlague started at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                                " spreading §e" + PlagueManager.getInstance().getBlock().getName().getString()
                            ), true);
                            return 1;
                        })
                    )
                )

                // /plague stop
                .then(literal("stop")
                    .executes(ctx -> {
                        PlagueManager.getInstance().stopPlague();
                        ctx.getSource().sendFeedback(() -> Text.literal("§6[Block Plague] §cPlague stopped."), true);
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

                // /plague setrate <rate> <unit>
                // Units: blocks_per_second, blocks_per_minute, blocks_per_hour, blocks_per_day
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
                        // Default unit: blocks_per_second
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
                        var source = ctx.getSource();
                        source.sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §7Status:\n" +
                            "  §fActive: §" + (mgr.isActive() ? "a" : "c") + mgr.isActive() + "\n" +
                            "  §fBlock: §e" + mgr.getBlock().getName().getString() + "\n" +
                            "  §fRate: §b" + formatRate(mgr.getRatePerSecond()) + "\n" +
                            "  §fConverted: §d" + mgr.getConvertedCount() + " blocks\n" +
                            "  §fFrontier: §d" + mgr.getFrontierSize() + " blocks queued"
                        ), false);
                        return 1;
                    })
                )

                // /plague help
                .then(literal("help")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "§6[Block Plague] §fCommands:\n" +
                            "  §e/plague start §7[x y z] §f- Start plague at position (or your feet)\n" +
                            "  §e/plague stop §f- Stop and clear the plague\n" +
                            "  §e/plague setblock <block> §f- Set the spreading block type\n" +
                            "  §e/plague select §f- Place a block to select it as the plague block\n" +
                            "  §e/plague setrate <rate> [unit] §f- Set spread rate\n" +
                            "    §7Units: blocks_per_second (default), blocks_per_minute,\n" +
                            "           blocks_per_hour, blocks_per_day\n" +
                            "    §7Range: 1 block/day up to 100 blocks/second\n" +
                            "  §e/plague status §f- Show current settings\n" +
                            "  §e/plague help §f- Show this message"
                        ), false);
                        return 1;
                    })
                )
        );
    }

    private static int setRate(ServerCommandSource source, double rate, String unit) {
        double bps; // blocks per second
        String unitLabel;

        switch (unit.toLowerCase()) {
            case "blocks_per_second" -> {
                bps = rate;
                unitLabel = rate + " blocks/second";
            }
            case "blocks_per_minute" -> {
                bps = rate / 60.0;
                unitLabel = rate + " blocks/minute";
            }
            case "blocks_per_hour" -> {
                bps = rate / 3600.0;
                unitLabel = rate + " blocks/hour";
            }
            case "blocks_per_day" -> {
                bps = rate / 86400.0;
                unitLabel = rate + " blocks/day";
            }
            default -> {
                source.sendError(Text.literal(
                    "Unknown unit '" + unit + "'. Use: blocks_per_second, blocks_per_minute, blocks_per_hour, blocks_per_day"
                ));
                return 0;
            }
        }

        // Enforce limits: max 100 blocks/sec, min 1 block/day
        double minBps = 1.0 / 86400.0;
        double maxBps = 100.0;

        if (bps > maxBps) {
            source.sendError(Text.literal("Rate too high! Maximum is 100 blocks/second."));
            return 0;
        }
        if (bps < minBps * 0.999) { // small tolerance
            source.sendError(Text.literal("Rate too low! Minimum is 1 block/day."));
            return 0;
        }

        PlagueManager.getInstance().setRate(bps);
        final String label = unitLabel;
        source.sendFeedback(() -> Text.literal(
            "§6[Block Plague] §aSpread rate set to §e" + label + " §a(§b" + formatRate(bps) + "§a)"
        ), true);
        return 1;
    }

    private static String formatRate(double bps) {
        if (bps >= 1.0) {
            return String.format("%.2f blocks/sec", bps);
        } else if (bps * 60 >= 1.0) {
            return String.format("%.2f blocks/min", bps * 60);
        } else if (bps * 3600 >= 1.0) {
            return String.format("%.2f blocks/hr", bps * 3600);
        } else {
            return String.format("%.2f blocks/day", bps * 86400);
        }
    }
}
