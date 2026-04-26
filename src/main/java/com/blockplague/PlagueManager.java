package com.blockplague;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

public class PlagueManager {

    private static final PlagueManager INSTANCE = new PlagueManager();
    private static final Random RANDOM = new Random();

    private final List<PlagueInstance> instances = new ArrayList<>();
    private Block plagueBlock = Blocks.OBSIDIAN;
    private double blocksPerTick = 1.0;

    private PlagueManager() {}

    public static PlagueManager getInstance() { return INSTANCE; }

    public void setBlock(Block block) { this.plagueBlock = block; }
    public Block getBlock() { return plagueBlock; }

    public void setRate(double blocksPerSecond) {
        this.blocksPerTick = blocksPerSecond / 20.0;
        for (PlagueInstance inst : instances) inst.blocksPerTick = this.blocksPerTick;
    }

    public double getRatePerSecond() { return blocksPerTick * 20.0; }
    public int getInstanceCount() { return instances.size(); }
    public int getTotalConverted() { return instances.stream().mapToInt(i -> i.converted.size()).sum(); }
    public int getTotalFrontier() { return instances.stream().mapToInt(i -> i.frontier.size()).sum(); }

    public void startPlague(MinecraftServer server, BlockPos origin, String dimensionKey) {
        PlagueInstance inst = new PlagueInstance(plagueBlock, blocksPerTick, dimensionKey);
        ServerWorld world = getWorld(server, dimensionKey);
        if (world != null) inst.seed(world, origin);
        instances.add(inst);
        BlockPlagueMod.LOGGER.info("Plague #{} started at {} with {} in {}",
            instances.size(), origin, plagueBlock.getName().getString(), dimensionKey);
    }

    public void stopAll() {
        instances.clear();
        BlockPlagueMod.LOGGER.info("All plagues stopped.");
    }

    public void stopLast() {
        if (!instances.isEmpty()) instances.remove(instances.size() - 1);
    }

    public boolean isAnyActive() { return !instances.isEmpty(); }

    public void tick(MinecraftServer server) {
        if (instances.isEmpty()) return;
        Iterator<PlagueInstance> it = instances.iterator();
        while (it.hasNext()) {
            PlagueInstance inst = it.next();
            ServerWorld world = getWorld(server, inst.dimensionKey);
            if (world == null) continue;
            if (inst.tick(world)) {
                it.remove();
                server.getPlayerManager().broadcast(Text.literal(
                    "§6[Block Plague] §cA plague of §e" + inst.block.getName().getString() +
                    " §chas consumed everything!"), false);
            }
        }
    }

    private ServerWorld getWorld(MinecraftServer server, String key) {
        if (key == null) return server.getOverworld();
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(key)) return w;
        }
        return server.getOverworld();
    }

    // =========================================================================
    // PlagueInstance — one spreading plague
    // =========================================================================

    static class PlagueInstance {

        final Block block;
        double blocksPerTick;
        final String dimensionKey;

        final LinkedHashSet<BlockPos> frontier = new LinkedHashSet<>();
        final HashSet<BlockPos> converted = new HashSet<>();
        double accumulator = 0.0;

        // Cooldowns in ticks
        int tendrilCooldown = 0;
        int spikeCooldown = 0;
        int jumpCooldown = 0;
        int webCooldown = 0;

        PlagueInstance(Block block, double blocksPerTick, String dimensionKey) {
            this.block = block;
            this.blocksPerTick = blocksPerTick;
            this.dimensionKey = dimensionKey;
        }

        void seed(ServerWorld world, BlockPos origin) {
            converted.add(origin);
            if (!world.getBlockState(origin).isAir()) {
                world.setBlockState(origin, block.getDefaultState(), Block.NOTIFY_ALL);
            }
            for (BlockPos n : neighbours(origin)) {
                if (!converted.contains(n)) frontier.add(n);
            }
        }

        boolean tick(ServerWorld world) {
            if (tendrilCooldown > 0) tendrilCooldown--;
            if (spikeCooldown > 0) spikeCooldown--;
            if (jumpCooldown > 0) jumpCooldown--;
            if (webCooldown > 0) webCooldown--;

            if (frontier.isEmpty()) return true;

            accumulator += blocksPerTick;
            int toSpread = (int) accumulator;
            if (toSpread < 1) return false;
            accumulator -= toSpread;

            List<BlockPos> batch = new ArrayList<>();
            Iterator<BlockPos> it = frontier.iterator();
            int count = 0;
            while (it.hasNext() && count < toSpread) {
                batch.add(it.next());
                it.remove();
                count++;
            }

            for (BlockPos pos : batch) {
                if (converted.contains(pos)) continue;
                converted.add(pos);

                BlockState state = world.getBlockState(pos);
                if (!state.isAir()) {
                    world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
                    attackMobs(world, pos);
                }

                // Normal BFS spread — 85% chance per neighbour for organic look
                for (BlockPos n : neighbours(pos)) {
                    if (!converted.contains(n) && !frontier.contains(n)) {
                        if (RANDOM.nextFloat() < 0.85f) frontier.add(n);
                    }
                }

                // === Special behaviours ===

                // Tendril through solid terrain
                if (tendrilCooldown == 0 && RANDOM.nextFloat() < 0.04f) {
                    spawnTendril(world, pos, false);
                    tendrilCooldown = 3 + RANDOM.nextInt(8);
                }

                // Tendril through air
                if (tendrilCooldown == 0 && RANDOM.nextFloat() < 0.02f) {
                    spawnTendril(world, pos, true);
                    tendrilCooldown = 5 + RANDOM.nextInt(10);
                }

                // Spike upward
                if (spikeCooldown == 0 && RANDOM.nextFloat() < 0.06f) {
                    spawnSpike(world, pos, false);
                    spikeCooldown = 2 + RANDOM.nextInt(6);
                }

                // Wide spike
                if (spikeCooldown == 0 && RANDOM.nextFloat() < 0.03f) {
                    spawnSpike(world, pos, true);
                    spikeCooldown = 4 + RANDOM.nextInt(8);
                }

                // Jump to a distant location
                if (jumpCooldown == 0 && RANDOM.nextFloat() < 0.008f) {
                    spawnJump(world, pos);
                    jumpCooldown = 20 + RANDOM.nextInt(40);
                }

                // Rare web burst
                if (webCooldown == 0 && RANDOM.nextFloat() < 0.002f) {
                    spawnWeb(world, pos);
                    webCooldown = 60 + RANDOM.nextInt(100);
                }
            }

            // Tree branching — occasional
            if (!frontier.isEmpty() && RANDOM.nextFloat() < 0.05f) {
                BlockPos treeBase = frontier.iterator().next();
                spawnTree(world, treeBase);
            }

            return false;
        }

        private void spawnTendril(ServerWorld world, BlockPos origin, boolean throughAir) {
            int length = 8 + RANDOM.nextInt(20);
            int dx = RANDOM.nextInt(3) - 1;
            int dy = RANDOM.nextInt(3) - 1;
            int dz = RANDOM.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0) dx = 1;

            BlockPos cur = origin;
            for (int i = 0; i < length; i++) {
                cur = cur.add(dx, dy, dz);
                if (RANDOM.nextFloat() < 0.3f) cur = cur.add(RANDOM.nextInt(3) - 1, 0, RANDOM.nextInt(3) - 1);
                if (converted.contains(cur)) continue;

                BlockState state = world.getBlockState(cur);
                if (throughAir || !state.isAir()) {
                    placeAndQueue(world, cur, throughAir);
                }
            }
        }

        private void spawnSpike(ServerWorld world, BlockPos base, boolean wide) {
            if (wide) {
                // Wide base cluster
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        if (RANDOM.nextFloat() < 0.6f) {
                            BlockPos p = base.add(x, 0, z);
                            if (!converted.contains(p)) placeAndQueue(world, p, true);
                        }
                    }
                }
                // Multiple sub-spikes
                int subCount = 2 + RANDOM.nextInt(3);
                for (int s = 0; s < subCount; s++) {
                    BlockPos offset = base.add(RANDOM.nextInt(3) - 1, 0, RANDOM.nextInt(3) - 1);
                    shootSpikeUp(world, offset);
                }
            } else {
                shootSpikeUp(world, base);
            }
        }

        private void shootSpikeUp(ServerWorld world, BlockPos base) {
            int height = 3 + RANDOM.nextInt(10);
            BlockPos cur = base;
            for (int i = 0; i < height; i++) {
                cur = cur.up();
                if (converted.contains(cur)) continue;
                placeAndQueue(world, cur, true); // spikes go through air
                if (RANDOM.nextFloat() < 0.2f) {
                    cur = switch (RANDOM.nextInt(4)) {
                        case 0 -> cur.north();
                        case 1 -> cur.south();
                        case 2 -> cur.east();
                        default -> cur.west();
                    };
                }
            }
        }

        private void spawnJump(ServerWorld world, BlockPos origin) {
            int dist = 15 + RANDOM.nextInt(30);
            int dx = (RANDOM.nextBoolean() ? 1 : -1) * (5 + RANDOM.nextInt(dist));
            int dz = (RANDOM.nextBoolean() ? 1 : -1) * (5 + RANDOM.nextInt(dist));
            BlockPos target = origin.add(dx, RANDOM.nextInt(20) - 10, dz);

            for (int y = 10; y >= -10; y--) {
                BlockPos check = target.add(0, y, 0);
                if (!world.getBlockState(check).isAir() && !converted.contains(check)) {
                    for (int x2 = -1; x2 <= 1; x2++) {
                        for (int z2 = -1; z2 <= 1; z2++) {
                            if (RANDOM.nextFloat() < 0.7f) {
                                BlockPos p = check.add(x2, 0, z2);
                                if (!converted.contains(p)) placeAndQueue(world, p, false);
                            }
                        }
                    }
                    return;
                }
            }
        }

        private void spawnWeb(ServerWorld world, BlockPos origin) {
            int arms = 6 + RANDOM.nextInt(8);
            for (int i = 0; i < arms; i++) spawnTendril(world, origin, false);
            // A couple air tendrils
            spawnTendril(world, origin, true);
            spawnTendril(world, origin, true);
        }

        private void spawnTree(ServerWorld world, BlockPos base) {
            int trunkHeight = 3 + RANDOM.nextInt(6);
            BlockPos trunk = base;
            List<BlockPos> branchPoints = new ArrayList<>();
            for (int i = 0; i < trunkHeight; i++) {
                trunk = trunk.up();
                if (!converted.contains(trunk)) {
                    placeAndQueue(world, trunk, true);
                    if (RANDOM.nextFloat() < 0.4f) branchPoints.add(trunk);
                }
            }
            for (BlockPos bp : branchPoints) {
                int len = 2 + RANDOM.nextInt(5);
                int bdx = RANDOM.nextInt(3) - 1;
                int bdz = RANDOM.nextInt(3) - 1;
                if (bdx == 0 && bdz == 0) bdx = 1;
                BlockPos cur = bp;
                for (int j = 0; j < len; j++) {
                    cur = cur.add(bdx, RANDOM.nextFloat() < 0.3f ? 1 : 0, bdz);
                    if (!converted.contains(cur)) placeAndQueue(world, cur, true);
                }
            }
        }

        private void placeAndQueue(ServerWorld world, BlockPos pos, boolean forcePlace) {
            if (converted.contains(pos)) return;
            converted.add(pos);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() || forcePlace) {
                world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
                attackMobs(world, pos);
            }
            for (BlockPos n : neighbours(pos)) {
                if (!converted.contains(n) && !frontier.contains(n)) frontier.add(n);
            }
        }

        private void attackMobs(ServerWorld world, BlockPos pos) {
            if (RANDOM.nextFloat() > 0.3f) return;
            Box box = new Box(pos).expand(1.5);
            List<LivingEntity> mobs = world.getEntitiesByClass(LivingEntity.class, box,
                e -> !(e instanceof PlayerEntity));
            for (LivingEntity mob : mobs) {
                mob.damage(world.getDamageSources().magic(), 4.0f);
                if (RANDOM.nextFloat() < 0.4f) {
                    BlockPos feet = BlockPos.ofFloored(mob.getPos());
                    if (!converted.contains(feet)) placeAndQueue(world, feet, false);
                }
            }
        }

        static List<BlockPos> neighbours(BlockPos pos) {
            return List.of(
                pos.north(), pos.south(), pos.east(), pos.west(), pos.up(), pos.down()
            );
        }
    }
}
