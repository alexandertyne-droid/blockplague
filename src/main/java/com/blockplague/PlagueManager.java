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
    static final Random RNG = new Random();

    private final List<PlagueInstance> instances = new ArrayList<>();
    private Block plagueBlock = Blocks.OBSIDIAN;
    private double blocksPerTick = 1.0;

    private PlagueManager() {}
    public static PlagueManager getInstance() { return INSTANCE; }

    public void setBlock(Block b) { this.plagueBlock = b; }
    public Block getBlock() { return plagueBlock; }
    public void setRate(double bps) {
        this.blocksPerTick = bps / 20.0;
        for (PlagueInstance i : instances) i.blocksPerTick = blocksPerTick;
    }
    public double getRatePerSecond() { return blocksPerTick * 20.0; }
    public int getInstanceCount() { return instances.size(); }
    public int getTotalConverted() { return instances.stream().mapToInt(i -> i.placed.size()).sum(); }
    public int getTotalFrontier() { return instances.stream().mapToInt(i -> i.veins.size()).sum(); }
    public boolean isAnyActive() { return !instances.isEmpty(); }

    public void startPlague(MinecraftServer server, BlockPos origin, String dim) {
        PlagueInstance inst = new PlagueInstance(plagueBlock, blocksPerTick, dim);
        ServerWorld world = getWorld(server, dim);
        if (world != null) inst.seed(world, origin);
        instances.add(inst);
        BlockPlagueMod.LOGGER.info("Plague #{} started at {}", instances.size(), origin);
    }

    public void stopAll() { instances.clear(); }
    public void stopLast() { if (!instances.isEmpty()) instances.remove(instances.size()-1); }

    public void tick(MinecraftServer server) {
        Iterator<PlagueInstance> it = instances.iterator();
        while (it.hasNext()) {
            PlagueInstance inst = it.next();
            ServerWorld world = getWorld(server, inst.dim);
            if (world == null) continue;
            if (inst.tick(world)) {
                it.remove();
                server.getPlayerManager().broadcast(Text.literal(
                    "§6[Block Plague] §cA plague of §e" + inst.block.getName().getString() + " §chas consumed everything!"), false);
            }
        }
    }

    private ServerWorld getWorld(MinecraftServer server, String key) {
        if (key == null) return server.getOverworld();
        for (ServerWorld w : server.getWorlds())
            if (w.getRegistryKey().getValue().toString().equals(key)) return w;
        return server.getOverworld();
    }

    // =========================================================================
    // PlagueInstance
    // =========================================================================
    static class PlagueInstance {
        final Block block;
        double blocksPerTick;
        final String dim;

        // Every placed block position
        final HashSet<BlockPos> placed = new HashSet<>();
        // Active veins — this is the ONLY spreading mechanism
        final List<Vein> veins = new ArrayList<>();

        double acc = 0;
        int age = 0;

        PlagueInstance(Block block, double bpt, String dim) {
            this.block = block;
            this.blocksPerTick = bpt;
            this.dim = dim;
        }

        void seed(ServerWorld world, BlockPos origin) {
            // Place a tiny initial cluster
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = origin.add(x, 0, z);
                    if (!world.getBlockState(p).isAir()) { place(world, p); placed.add(p); }
                }
            // Immediately burst many veins outward in all directions
            int initialVeins = 12 + RNG.nextInt(12);
            for (int i = 0; i < initialVeins; i++) {
                double angle = (2 * Math.PI * i) / initialVeins + RNG.nextDouble() * 0.5;
                int dx = (int) Math.round(Math.cos(angle));
                int dz = (int) Math.round(Math.sin(angle));
                int dy = RNG.nextFloat() < 0.15f ? (RNG.nextBoolean() ? 1 : -1) : 0;
                veins.add(new Vein(origin, dx, dy, dz, 30 + RNG.nextInt(60), 1.0));
            }
        }

        boolean tick(ServerWorld world) {
            age++;
            if (veins.isEmpty()) return true;

            acc += blocksPerTick;
            int budget = (int) acc;
            if (budget < 1) return false;
            acc -= budget;

            // Each vein gets a roughly equal share, but with randomness
            // so some veins sprint and others crawl — making it feel alive
            int veinCount = veins.size();
            Iterator<Vein> it = veins.iterator();
            List<Vein> toAdd = new ArrayList<>();

            int i = 0;
            while (it.hasNext()) {
                Vein v = it.next();
                // Random per-vein budget: some get more, some less
                float veinShare = (float) budget / veinCount;
                int vBudget = Math.max(1, (int)(veinShare * (0.2f + RNG.nextFloat() * 1.8f)));

                boolean dead = v.advance(world, this, vBudget, toAdd);
                if (dead) it.remove();
                i++;
            }

            veins.addAll(toAdd);

            // Periodically pulse: burst new veins from random existing placed blocks
            // This makes it feel like it breathes and re-activates
            if (age % 15 == 0 && !placed.isEmpty() && RNG.nextFloat() < 0.7f) {
                // Pick a random placed block near the edge
                BlockPos[] placedArr = placed.toArray(new BlockPos[0]);
                BlockPos origin = placedArr[RNG.nextInt(placedArr.length)];
                int newVeins = 1 + RNG.nextInt(4);
                for (int v = 0; v < newVeins; v++) {
                    int[] d = randomHorizDir();
                    veins.add(new Vein(origin, d[0], d[1], d[2], 10 + RNG.nextInt(30), 0.7));
                }
            }

            return false;
        }

        void place(ServerWorld world, BlockPos pos) {
            if (!world.getBlockState(pos).isAir()) {
                world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
            }
            placed.add(pos);
            // Mob engulf
            if (RNG.nextFloat() < 0.15f) {
                Box box = new Box(pos).expand(1.5);
                world.getEntitiesByClass(LivingEntity.class, box, e -> !(e instanceof PlayerEntity))
                     .forEach(e -> {
                         e.damage(world.getDamageSources().magic(), 3f);
                     });
            }
        }

        static int[] randomHorizDir() {
            int dx = RNG.nextInt(3) - 1;
            int dz = RNG.nextInt(3) - 1;
            int dy = RNG.nextFloat() < 0.2f ? (RNG.nextBoolean() ? 1 : -1) : 0;
            if (dx == 0 && dz == 0) { if (RNG.nextBoolean()) dx = 1; else dz = 1; }
            return new int[]{dx, dy, dz};
        }
    }

    // =========================================================================
    // Vein — the core unit of spreading. Everything is a vein.
    // =========================================================================
    static class Vein {
        BlockPos head;
        // Direction as floats for smooth curves
        double ddx, ddy, ddz;
        int length; // remaining steps
        double vitality; // 0-1, decays over time, affects behaviour

        // Sub-type affects behaviour
        enum Type { TENDRIL, CREEPER, SPIKE, ROOT }
        Type type;

        // Steps since last branch
        int stepsSinceBranch = 0;

        Vein(BlockPos origin, int dx, int dy, int dz, int length, double vitality) {
            this.head = origin;
            this.ddx = dx; this.ddy = dy; this.ddz = dz;
            this.length = length;
            this.vitality = vitality;
            // Assign type randomly — mostly tendrils
            float r = RNG.nextFloat();
            if (r < 0.55f) type = Type.TENDRIL;
            else if (r < 0.78f) type = Type.CREEPER;
            else if (r < 0.90f) type = Type.SPIKE;
            else type = Type.ROOT;
        }

        /**
         * Advance this vein. Returns true if dead.
         * toAdd: new child veins to spawn this tick.
         */
        boolean advance(ServerWorld world, PlagueInstance inst, int budget, List<Vein> toAdd) {
            if (length <= 0) return true;

            for (int step = 0; step < budget && length > 0; step++) {
                // === Move head ===
                // Convert direction floats to integer step
                int sx = (int) Math.round(ddx);
                int sy = (int) Math.round(ddy);
                int sdz = (int) Math.round(ddz);
                // Clamp to -1..1
                sx = Math.max(-1, Math.min(1, sx));
                sy = Math.max(-1, Math.min(1, sy));
                sdz = Math.max(-1, Math.min(1, sdz));
                if (sx == 0 && sy == 0 && sdz == 0) sx = 1;

                head = head.add(sx, sy, sdz);
                length--;
                stepsSinceBranch++;
                vitality -= 0.003 + RNG.nextDouble() * 0.005;

                // === Place at head ===
                if (!inst.placed.contains(head)) {
                    boolean isAir = world.getBlockState(head).isAir();

                    if (type == Type.SPIKE) {
                        // Spikes go through air
                        inst.placed.add(head);
                        inst.place(world, head);
                        if (isAir) world.setBlockState(head, inst.block.getDefaultState(), Block.NOTIFY_ALL);
                    } else if (type == Type.CREEPER) {
                        // Creepers hug terrain — if air, look below
                        if (isAir) {
                            BlockPos below = head.down();
                            if (!inst.placed.contains(below) && !world.getBlockState(below).isAir()) {
                                head = below;
                                inst.placed.add(head);
                                inst.place(world, head);
                            }
                        } else {
                            inst.placed.add(head);
                            inst.place(world, head);
                        }
                    } else {
                        // TENDRIL / ROOT: only solid blocks
                        if (!isAir) {
                            inst.placed.add(head);
                            inst.place(world, head);
                        }
                        // If hit air, try to find ground below (roots dig down)
                        else if (type == Type.ROOT) {
                            for (int d = 1; d <= 4; d++) {
                                BlockPos below = head.down(d);
                                if (!world.getBlockState(below).isAir() && !inst.placed.contains(below)) {
                                    head = below;
                                    inst.placed.add(head);
                                    inst.place(world, head);
                                    break;
                                }
                            }
                        }
                    }
                }

                // === Wander — this is what makes it look alive ===
                switch (type) {
                    case TENDRIL -> {
                        // Tendrils curve smoothly with some random wobble
                        ddx += (RNG.nextDouble() - 0.5) * 0.6;
                        ddz += (RNG.nextDouble() - 0.5) * 0.6;
                        // Slight gravity — tends downward on slopes
                        ddy += (RNG.nextDouble() - 0.55) * 0.3;
                        ddy = Math.max(-1, Math.min(0.5, ddy));
                        // Normalize horizontal to prevent drift
                        double hlen = Math.sqrt(ddx*ddx + ddz*ddz);
                        if (hlen > 1.5) { ddx /= hlen; ddz /= hlen; }
                    }
                    case CREEPER -> {
                        // Creepers snake side to side a lot
                        ddx += (RNG.nextDouble() - 0.5) * 1.0;
                        ddz += (RNG.nextDouble() - 0.5) * 1.0;
                        ddy = 0; // stays flat
                    }
                    case SPIKE -> {
                        // Spikes mostly go up with slight sway
                        ddy = 1;
                        ddx += (RNG.nextDouble() - 0.5) * 0.3;
                        ddz += (RNG.nextDouble() - 0.5) * 0.3;
                    }
                    case ROOT -> {
                        // Roots spread wide and shallow
                        ddx += (RNG.nextDouble() - 0.5) * 0.8;
                        ddz += (RNG.nextDouble() - 0.5) * 0.8;
                        ddy -= 0.1; // slightly downward
                    }
                }

                // === Branching ===
                float branchChance = switch (type) {
                    case TENDRIL -> 0.06f;
                    case CREEPER -> 0.10f;
                    case SPIKE   -> 0.04f;
                    case ROOT    -> 0.08f;
                };
                // More likely to branch when young and vital
                branchChance *= (float) vitality;

                if (stepsSinceBranch > 3 && RNG.nextFloat() < branchChance && length > 4) {
                    stepsSinceBranch = 0;
                    int[] bd = PlagueInstance.randomHorizDir();
                    int branchLen = (int)(length * (0.3 + RNG.nextDouble() * 0.5));
                    toAdd.add(new Vein(head, bd[0], bd[1], bd[2], branchLen, vitality * 0.8));

                    // Occasionally spawn a spike from branch point
                    if (type == Type.TENDRIL && RNG.nextFloat() < 0.2f) {
                        toAdd.add(new Vein(head, 0, 1, 0, 3 + RNG.nextInt(8), vitality * 0.6));
                    }
                }

                // === Occasional fungal eruption from vein ===
                if (RNG.nextFloat() < 0.015f && type != Type.SPIKE) {
                    spawnFungalCluster(world, inst, head);
                }

                // === Jump — vein teleports ahead leaving a gap ===
                if (RNG.nextFloat() < 0.008f && length > 10) {
                    int jumpDist = 5 + RNG.nextInt(10);
                    head = head.add(
                        (int)Math.round(ddx) * jumpDist,
                        0,
                        (int)Math.round(ddz) * jumpDist
                    );
                }

                if (vitality <= 0) return true;
            }

            return length <= 0 || vitality <= 0;
        }

        void spawnFungalCluster(ServerWorld world, PlagueInstance inst, BlockPos base) {
            // Lumpy mound: irregular height map around base
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    // Distance from center — further = lower max height
                    float dist = (float) Math.sqrt(x*x + z*z);
                    if (dist > 2.2f) continue;
                    int maxH = (int)(2.5f - dist) + RNG.nextInt(2);
                    for (int y = 0; y <= maxH; y++) {
                        if (RNG.nextFloat() < 0.3f) continue; // random holes = organic look
                        BlockPos p = base.add(x, y, z);
                        if (!inst.placed.contains(p)) {
                            inst.placed.add(p);
                            inst.place(world, p);
                        }
                    }
                }
            }
            // Thin spike from top of mound
            if (RNG.nextFloat() < 0.5f) {
                int spikeH = 2 + RNG.nextInt(5);
                BlockPos cur = base.up(2);
                for (int s = 0; s < spikeH; s++) {
                    cur = cur.up();
                    if (!inst.placed.contains(cur)) {
                        inst.placed.add(cur);
                        world.setBlockState(cur, inst.block.getDefaultState(), Block.NOTIFY_ALL);
                    }
                    if (RNG.nextFloat() < 0.2f) cur = cur.add(RNG.nextInt(3)-1, 0, RNG.nextInt(3)-1);
                }
            }
        }
    }
}
