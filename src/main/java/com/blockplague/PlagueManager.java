package com.blockplague;

import net.minecraft.block.Block;
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

    public void setBlock(Block b) { plagueBlock = b; }
    public Block getBlock() { return plagueBlock; }
    public void setRate(double bps) {
        blocksPerTick = bps / 20.0;
        for (PlagueInstance i : instances) i.blocksPerTick = blocksPerTick;
    }
    public double getRatePerSecond() { return blocksPerTick * 20.0; }
    public int getInstanceCount() { return instances.size(); }
    public int getTotalConverted() { return instances.stream().mapToInt(i -> i.placed.size()).sum(); }
    public int getTotalFrontier() { return instances.stream().mapToInt(i -> i.arms.size()).sum(); }
    public boolean isAnyActive() { return !instances.isEmpty(); }

    public void startPlague(MinecraftServer server, BlockPos origin, String dim) {
        PlagueInstance inst = new PlagueInstance(plagueBlock, blocksPerTick, dim);
        ServerWorld world = getWorld(server, dim);
        if (world != null) inst.seed(world, origin);
        instances.add(inst);
        BlockPlagueMod.LOGGER.info("Plague #{} started at {}", instances.size(), origin);
    }

    public void stopAll() { instances.clear(); }
    public void stopLast() { if (!instances.isEmpty()) instances.remove(instances.size() - 1); }

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
    static class PlagueInstance {
        final Block block;
        double blocksPerTick;
        final String dim;
        final HashSet<BlockPos> placed = new HashSet<>();
        final List<Arm> arms = new ArrayList<>();
        double acc = 0;
        int age = 0;

        PlagueInstance(Block block, double bpt, String dim) {
            this.block = block;
            this.blocksPerTick = bpt;
            this.dim = dim;
        }

        void seed(ServerWorld world, BlockPos origin) {
            // Place a bulbous origin node
            placeNode(world, origin, 4);

            // Burst 6-10 thick primary arms outward
            int armCount = 6 + RNG.nextInt(5);
            for (int i = 0; i < armCount; i++) {
                double angle = (2 * Math.PI * i) / armCount + RNG.nextDouble() * 0.6;
                double dx = Math.cos(angle);
                double dz = Math.sin(angle);
                // Primary arms: thick (radius 3-4), long
                int thickness = 3 + RNG.nextInt(2);
                int length = 25 + RNG.nextInt(35);
                arms.add(new Arm(origin, dx, 0, dz, length, thickness, block, Arm.Kind.PRIMARY));
            }
        }

        boolean tick(ServerWorld world) {
            age++;
            if (arms.isEmpty()) return true;

            acc += blocksPerTick;
            int budget = (int) acc;
            if (budget < 1) return false;
            acc -= budget;

            List<Arm> newArms = new ArrayList<>();
            int perArm = Math.max(1, budget / Math.max(1, arms.size()));

            Iterator<Arm> it = arms.iterator();
            while (it.hasNext()) {
                Arm arm = it.next();
                // Randomize per-arm speed so some race and some creep
                int share = Math.max(1, (int)(perArm * (0.2f + RNG.nextFloat() * 1.8f)));
                if (arm.advance(world, this, share, newArms)) it.remove();
            }
            arms.addAll(newArms);

            // Re-pulse: every ~20 ticks sprout new tendrils from existing arms
            // Makes it look like it's constantly growing new hairs
            if (age % 20 == 0 && !placed.isEmpty() && RNG.nextFloat() < 0.6f) {
                BlockPos[] arr = placed.toArray(new BlockPos[0]);
                BlockPos src = arr[RNG.nextInt(arr.length)];
                // Sprout 1-3 thin tendrils
                int sprouts = 1 + RNG.nextInt(3);
                for (int i = 0; i < sprouts; i++) {
                    double angle = RNG.nextDouble() * Math.PI * 2;
                    arms.add(new Arm(src,
                        Math.cos(angle), (RNG.nextDouble() - 0.5) * 0.3, Math.sin(angle),
                        8 + RNG.nextInt(15), 1, block, Arm.Kind.TENDRIL));
                }
            }

            return false;
        }

        /** Place a spherical node/bulge at a position — used at origins and branch points */
        void placeNode(ServerWorld world, BlockPos center, int radius) {
            for (int x = -radius; x <= radius; x++)
                for (int y = -radius; y <= radius; y++)
                    for (int z = -radius; z <= radius; z++) {
                        double dist = Math.sqrt(x*x + y*y + z*z);
                        if (dist > radius) continue;
                        // Irregular surface: vary by noise
                        if (dist > radius - 1 && RNG.nextFloat() < 0.3f) continue;
                        placeBlock(world, center.add(x, y, z));
                    }
        }

        void placeBlock(ServerWorld world, BlockPos pos) {
            if (placed.contains(pos)) return;
            placed.add(pos);
            if (!world.getBlockState(pos).isAir()) {
                world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
            }
            // Mob damage
            if (RNG.nextFloat() < 0.05f) {
                try {
                    Box box = new Box(pos).expand(1.5);
                    for (LivingEntity mob : world.getEntitiesByClass(LivingEntity.class, box, e -> !(e instanceof PlayerEntity))) {
                        try { mob.damage(world, world.getDamageSources().magic(), 3f); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    // =========================================================================
    // Arm — the core spreading unit. Three kinds:
    //   PRIMARY: thick (r3-4), long, curves gently, tapers, spawns SECONDARY arms and TENDRILS
    //   SECONDARY: medium (r2), medium length, curves more, spawns TENDRILS
    //   TENDRIL: thin (r1), short, curves aggressively, curls at the end
    // =========================================================================
    static class Arm {
        enum Kind { PRIMARY, SECONDARY, TENDRIL }

        // Float position for smooth movement
        double px, py, pz;
        // Float direction (normalized)
        double dx, dy, dz;
        // Current thickness (radius), tapers as arm extends
        double thickness;
        final double startThickness;
        // Remaining length in blocks
        int length;
        final int startLength;
        final Block block;
        final Kind kind;

        // Smooth noise state
        double noiseT = 0;
        double noiseTY = 0;
        double curlPhase; // for curl at end of tendrils

        // Steps since last branch
        int stepsSinceBranch = 0;
        boolean hasCurled = false;

        Arm(BlockPos origin, double dx, double dy, double dz, int length, int thickness, Block block, Kind kind) {
            this.px = origin.getX() + 0.5;
            this.py = origin.getY() + 0.5;
            this.pz = origin.getZ() + 0.5;
            double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (len < 0.001) len = 1;
            this.dx = dx / len; this.dy = dy / len; this.dz = dz / len;
            this.length = length;
            this.startLength = length;
            this.thickness = thickness;
            this.startThickness = thickness;
            this.block = block;
            this.kind = kind;
            this.noiseT = RNG.nextDouble() * 50;
            this.noiseTY = RNG.nextDouble() * 50;
            this.curlPhase = RNG.nextDouble() * Math.PI * 2;
        }

        boolean advance(ServerWorld world, PlagueInstance inst, int budget, List<Arm> newArms) {
            if (length <= 0) return true;

            for (int step = 0; step < budget && length > 0; step++) {
                // Move
                px += dx; py += dy; pz += dz;
                length--;
                stepsSinceBranch++;

                // Taper: thickness decreases as the arm gets longer
                double progress = 1.0 - (double)length / startLength; // 0 at start, 1 at end
                thickness = startThickness * (1.0 - progress * 0.7); // taper to 30% of start
                thickness = Math.max(1, thickness);

                // Place cross-section
                placeSection(world, inst);

                // === Direction updates by kind ===
                switch (kind) {
                    case PRIMARY -> {
                        // Gentle smooth curve — like a thick arm sweeping
                        noiseT += 0.08 + RNG.nextDouble() * 0.04;
                        noiseTY += 0.05;
                        dx += Math.sin(noiseT) * 0.07;
                        dz += Math.cos(noiseT * 0.8) * 0.07;
                        // Slight downward gravity
                        dy += (RNG.nextDouble() - 0.6) * 0.04;
                        dy = Math.max(-0.5, Math.min(0.3, dy));
                    }
                    case SECONDARY -> {
                        // More curvature than primary
                        noiseT += 0.12 + RNG.nextDouble() * 0.06;
                        dx += Math.sin(noiseT) * 0.12;
                        dz += Math.cos(noiseT * 0.9) * 0.12;
                        dy += (RNG.nextDouble() - 0.55) * 0.06;
                        dy = Math.max(-0.6, Math.min(0.4, dy));
                    }
                    case TENDRIL -> {
                        // Highly curved and wiggly
                        noiseT += 0.18 + RNG.nextDouble() * 0.1;
                        dx += Math.sin(noiseT) * 0.2;
                        dz += Math.cos(noiseT * 1.1) * 0.2;
                        dy += (RNG.nextDouble() - 0.5) * 0.1;
                        dy = Math.max(-0.8, Math.min(0.8, dy));

                        // Curl at the end: when 30% length remaining, start curling
                        if (length < startLength * 0.3 && !hasCurled) {
                            curlPhase += 0.4;
                            dx += Math.sin(curlPhase) * 0.4;
                            dz += Math.cos(curlPhase) * 0.4;
                        }
                    }
                }

                // Normalize
                double speed = Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (speed > 0.001) { dx /= speed; dy /= speed; dz /= speed; }

                // === Branching ===
                if (kind == Kind.PRIMARY && stepsSinceBranch >= 6 && length > 8) {
                    float r = RNG.nextFloat();

                    // Spawn secondary arm (medium thickness)
                    if (r < 0.08f) {
                        stepsSinceBranch = 0;
                        BlockPos node = BlockPos.ofFloored(px, py, pz);
                        // Place a small node bulge at branch point
                        inst.placeNode(world, node, 2);
                        // Secondary arm branches at an angle
                        double perpX = -dz + (RNG.nextDouble() - 0.5) * 0.4;
                        double perpZ = dx + (RNG.nextDouble() - 0.5) * 0.4;
                        double perpY = (RNG.nextDouble() - 0.5) * 0.3;
                        int secLen = (int)(length * (0.5 + RNG.nextDouble() * 0.4));
                        int secThick = 2;
                        newArms.add(new Arm(node, perpX, perpY, perpZ, secLen, secThick, block, Kind.SECONDARY));
                        // Occasionally branch both ways
                        if (RNG.nextFloat() < 0.4f) {
                            newArms.add(new Arm(node, -perpX, perpY, -perpZ, (int)(secLen * 0.7), secThick, block, Kind.SECONDARY));
                        }
                    }

                    // Spawn thin tendril
                    if (r < 0.18f) {
                        BlockPos node = BlockPos.ofFloored(px, py, pz);
                        double angle = RNG.nextDouble() * Math.PI * 2;
                        double tdy = (RNG.nextDouble() - 0.5) * 0.5;
                        int tLen = 8 + RNG.nextInt(18);
                        newArms.add(new Arm(node, Math.cos(angle), tdy, Math.sin(angle), tLen, 1, block, Kind.TENDRIL));
                    }
                }

                if (kind == Kind.SECONDARY && stepsSinceBranch >= 4 && length > 5 && RNG.nextFloat() < 0.12f) {
                    stepsSinceBranch = 0;
                    BlockPos node = BlockPos.ofFloored(px, py, pz);
                    double angle = RNG.nextDouble() * Math.PI * 2;
                    newArms.add(new Arm(node, Math.cos(angle), (RNG.nextDouble()-0.5)*0.6, Math.sin(angle),
                        5 + RNG.nextInt(12), 1, block, Kind.TENDRIL));
                }

                // Fungal mound from primary arm
                if (kind == Kind.PRIMARY && RNG.nextFloat() < 0.008f) {
                    spawnFungalMound(world, inst, BlockPos.ofFloored(px, py, pz));
                }
            }

            return length <= 0;
        }

        /** Place a thick cross-section at the current head position */
        void placeSection(ServerWorld world, PlagueInstance inst) {
            int cx = (int) Math.floor(px);
            int cy = (int) Math.floor(py);
            int cz = (int) Math.floor(pz);
            int r = (int) Math.ceil(thickness) - 1;

            if (r <= 0) {
                inst.placeBlock(world, BlockPos.of(cx, cy, cz));
                return;
            }

            // Place a sphere cross-section
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r; oy++) {
                    for (int oz = -r; oz <= r; oz++) {
                        double dist = Math.sqrt(ox*ox + oy*oy + oz*oz);
                        if (dist > thickness) continue;
                        // Organic surface roughness
                        if (dist > thickness - 0.8 && RNG.nextFloat() < 0.25f) continue;
                        inst.placeBlock(world, BlockPos.of(cx + ox, cy + oy, cz + oz));
                    }
                }
            }
        }

        void spawnFungalMound(ServerWorld world, PlagueInstance inst, BlockPos base) {
            int radius = 2 + RNG.nextInt(2);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    float dist = (float) Math.sqrt(x*x + z*z);
                    if (dist > radius) continue;
                    int maxH = (int)(radius - dist) + RNG.nextInt(2);
                    for (int h = 0; h <= maxH; h++) {
                        if (RNG.nextFloat() < 0.2f) continue;
                        inst.placeBlock(world, base.add(x, h, z));
                    }
                }
            }
            // Thin spike from top
            if (RNG.nextFloat() < 0.6f) {
                int spikeH = 3 + RNG.nextInt(7);
                double leanX = (RNG.nextDouble() - 0.5) * 0.4;
                double leanZ = (RNG.nextDouble() - 0.5) * 0.4;
                for (int h = 1; h <= spikeH; h++) {
                    BlockPos p = base.add((int)(leanX * h), h + radius - 1, (int)(leanZ * h));
                    inst.placeBlock(world, p);
                    world.setBlockState(p, block.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }
}
