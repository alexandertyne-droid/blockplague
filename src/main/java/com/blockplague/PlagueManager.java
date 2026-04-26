package com.blockplague;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

public class PlagueManager {

    private static final PlagueManager INSTANCE = new PlagueManager();

    // The block type the plague spreads as
    private Block plagueBlock = Blocks.OBSIDIAN;

    // Speed: blocks per tick (can be fractional for slow speeds)
    // 1 tick = 1/20 second
    // Min: 1 block/day = 1 / (20 * 60 * 60 * 24) blocks/tick ≈ 5.787e-7
    // Max: 100 blocks/second = 5 blocks/tick
    private double blocksPerTick = 1.0; // default: 1 block/second

    // Is the plague active?
    private boolean active = false;

    // Frontier: blocks queued to be converted (BFS spreading)
    // Using a LinkedHashSet so we can efficiently add/remove without duplicates
    private final Set<BlockPos> frontier = new LinkedHashSet<>();

    // Already-converted positions (so we don't re-add them)
    private final Set<BlockPos> converted = new HashSet<>();

    // Accumulator for fractional block spreading
    private double spreadAccumulator = 0.0;

    // Which world the plague is in (dimension)
    private String worldKey = null;

    // Whether player is in "selection mode" for next block placement
    private final Map<UUID, Boolean> selectingPlayers = new HashMap<>();

    private PlagueManager() {}

    public static PlagueManager getInstance() {
        return INSTANCE;
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    public void setBlock(Block block) {
        this.plagueBlock = block;
    }

    public Block getBlock() {
        return plagueBlock;
    }

    /**
     * Set spread rate. rate is in blocks/second.
     * Range: ~0.0000115 (1/day) to 100 (100/sec)
     */
    public void setRate(double blocksPerSecond) {
        this.blocksPerTick = blocksPerSecond / 20.0;
    }

    public double getRatePerSecond() {
        return blocksPerTick * 20.0;
    }

    public void startPlague(MinecraftServer server, BlockPos origin, String dimensionKey) {
        frontier.clear();
        converted.clear();
        spreadAccumulator = 0.0;
        worldKey = dimensionKey;
        active = true;
        frontier.add(origin);
        BlockPlagueMod.LOGGER.info("Plague started at {} in {}", origin, dimensionKey);
    }

    public void stopPlague() {
        active = false;
        frontier.clear();
        converted.clear();
        spreadAccumulator = 0.0;
        worldKey = null;
        BlockPlagueMod.LOGGER.info("Plague stopped.");
    }

    public boolean isActive() {
        return active;
    }

    public int getFrontierSize() {
        return frontier.size();
    }

    public int getConvertedCount() {
        return converted.size();
    }

    /**
     * Put a player into "select next block" mode.
     * When they place a block, the plague block is set to that block type.
     */
    public void startSelecting(PlayerEntity player) {
        selectingPlayers.put(player.getUuid(), true);
    }

    public boolean isSelecting(PlayerEntity player) {
        return selectingPlayers.getOrDefault(player.getUuid(), false);
    }

    // ── Block placement hook ──────────────────────────────────────────────────

    public ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (world.isClient()) return ActionResult.PASS;
        if (!isSelecting(player)) return ActionResult.PASS;

        // The item in hand should be a block item
        var stack = player.getStackInHand(hand);
        if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem) {
            plagueBlock = blockItem.getBlock();
            selectingPlayers.remove(player.getUuid());
            player.sendMessage(Text.literal(
                "§6[Block Plague] §aPlague block set to: §e" + blockItem.getBlock().getName().getString()
            ), false);
            return ActionResult.PASS; // still allow the block to place normally
        }

        return ActionResult.PASS;
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public void tick(MinecraftServer server) {
        if (!active || frontier.isEmpty()) {
            if (active && frontier.isEmpty()) {
                active = false;
                // Broadcast completion
                server.getPlayerManager().broadcast(
                    Text.literal("§6[Block Plague] §cThe plague has consumed everything!"), false
                );
            }
            return;
        }

        // Find the correct world
        ServerWorld world = getWorld(server);
        if (world == null) return;

        // Accumulate spread amount
        spreadAccumulator += blocksPerTick;

        // Spread as many blocks as we've accumulated (minimum 0)
        int toSpread = (int) spreadAccumulator;
        if (toSpread < 1) return; // not time yet

        spreadAccumulator -= toSpread;

        // Spread up to toSpread blocks from the frontier
        List<BlockPos> toProcess = new ArrayList<>();
        Iterator<BlockPos> it = frontier.iterator();
        int count = 0;
        while (it.hasNext() && count < toSpread) {
            toProcess.add(it.next());
            it.remove();
            count++;
        }

        for (BlockPos pos : toProcess) {
            // Convert this block
            convertBlock(world, pos);

            // Add valid neighbours to frontier
            for (BlockPos neighbour : getNeighbours(pos)) {
                if (!converted.contains(neighbour) && !frontier.contains(neighbour)) {
                    BlockState neighbourState = world.getBlockState(neighbour);
                    // Don't spread into air or already-plague blocks
                    if (!neighbourState.isAir() && neighbourState.getBlock() != plagueBlock) {
                        frontier.add(neighbour);
                    }
                }
            }
        }
    }

    private void convertBlock(ServerWorld world, BlockPos pos) {
        if (converted.contains(pos)) return;
        converted.add(pos);

        BlockState state = world.getBlockState(pos);
        // Don't convert air or the plague block itself
        if (state.isAir() || state.getBlock() == plagueBlock) return;

        // Also spread onto blocks below/above
        world.setBlockState(pos, plagueBlock.getDefaultState(), Block.NOTIFY_ALL);
    }

    private List<BlockPos> getNeighbours(BlockPos pos) {
        return List.of(
            pos.north(), pos.south(), pos.east(), pos.west(),
            pos.up(), pos.down()
        );
    }

    private ServerWorld getWorld(MinecraftServer server) {
        if (worldKey == null) return server.getOverworld();
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(worldKey)) {
                return world;
            }
        }
        return server.getOverworld();
    }
}
