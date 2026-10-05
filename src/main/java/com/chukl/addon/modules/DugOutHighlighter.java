package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.utils.LineOfSight;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds big rectangular excavated rooms ("dug out" areas) in loaded chunks and
 * draws a box around each one. Natural caves are irregular, so they rarely
 * contain a clean air box with solid walls that meets the limits.
 *
 * Base detection: a dug out area that contains player-placed blocks (chests,
 * barrels, furnaces, beds, shulker boxes...) is marked as a base and drawn in
 * a different color.
 */
public class DugOutHighlighter extends Module {
    private static final int MAX_DIM = 48;

    /** Blocks that are rarely found in natural caves and suggest a player lives or stores items here. */
    private static final Set<Block> BASE_BLOCKS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
        Blocks.BREWING_STAND, Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.BEACON, Blocks.LANTERN, Blocks.SOUL_LANTERN
    );

    private static boolean isBaseBlock(Block block) {
        return BASE_BLOCKS.contains(block)
            || block instanceof BedBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof AbstractSignBlock;
    }

    private record Area(Box box, int baseBlocks) {}

    private final SettingGroup sgScan = settings.createGroup("Scan");
    private final SettingGroup sgBase = settings.createGroup("Base Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- Scan ---

    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Chunks around you to scan.")
        .defaultValue(4)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> minY = sgScan.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y level to scan.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> maxY = sgScan.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y level to scan.")
        .defaultValue(50)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<Integer> minVolume = sgScan.add(new IntSetting.Builder()
        .name("min-volume")
        .description("Minimum number of air blocks in the box.")
        .defaultValue(200)
        .range(20, 20000)
        .sliderRange(20, 3000)
        .build()
    );

    private final Setting<Integer> minSize = sgScan.add(new IntSetting.Builder()
        .name("min-width-length")
        .description("Minimum width and length of the box.")
        .defaultValue(4)
        .range(2, 32)
        .sliderRange(2, 16)
        .build()
    );

    private final Setting<Integer> minHeight = sgScan.add(new IntSetting.Builder()
        .name("min-height")
        .description("Minimum height of the box.")
        .defaultValue(3)
        .range(2, 16)
        .sliderRange(2, 10)
        .build()
    );

    private final Setting<Integer> minWallSolidity = sgScan.add(new IntSetting.Builder()
        .name("min-wall-solidity")
        .description("How solid the walls, floor and ceiling around a box must be (%). Higher = only clean player-made rooms. Natural caves have ragged walls.")
        .defaultValue(85)
        .range(0, 100)
        .sliderRange(50, 100)
        .build()
    );

    private final Setting<Integer> scanInterval = sgScan.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Seconds between scans.")
        .defaultValue(3)
        .range(1, 30)
        .sliderRange(1, 15)
        .build()
    );

    // --- Base detection ---

    private final Setting<Boolean> detectBases = sgBase.add(new BoolSetting.Builder()
        .name("detect-bases")
        .description("Mark dug out areas that contain player-placed blocks (chests, barrels, furnaces, beds, shulker boxes...) as bases.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minBaseBlocks = sgBase.add(new IntSetting.Builder()
        .name("min-base-blocks")
        .description("How many player-placed blocks an area needs before it counts as a base.")
        .defaultValue(2)
        .range(1, 50)
        .sliderRange(1, 20)
        .visible(detectBases::get)
        .build()
    );

    private final Setting<Boolean> onlyBases = sgBase.add(new BoolSetting.Builder()
        .name("only-bases")
        .description("Only draw areas detected as bases. Empty dug out rooms are hidden.")
        .defaultValue(false)
        .visible(detectBases::get)
        .build()
    );

    private final Setting<SettingColor> baseSideColor = sgBase.add(new ColorSetting.Builder()
        .name("base-side-color")
        .description("Side color for areas detected as bases.")
        .defaultValue(new SettingColor(255, 60, 60, 40))
        .visible(detectBases::get)
        .build()
    );

    private final Setting<SettingColor> baseLineColor = sgBase.add(new ColorSetting.Builder()
        .name("base-line-color")
        .description("Outline color for areas detected as bases.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(detectBases::get)
        .build()
    );

    // --- Render ---

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Max distance in blocks to draw boxes.")
        .defaultValue(160)
        .range(16, 512)
        .sliderRange(16, 320)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Color of the box sides.")
        .defaultValue(new SettingColor(0, 200, 255, 30))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the box outline.")
        .defaultValue(new SettingColor(0, 200, 255, 255))
        .build()
    );

    private final Setting<Boolean> seeThroughWalls = sgRender.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Draw dug out areas through walls. When off, only areas in your line of sight are drawn.")
        .defaultValue(true)
        .build()
    );

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile List<Area> areas = List.of();
    private ExecutorService executor;
    private int timer;

    public DugOutHighlighter() {
        super(ChuklAddon.CATEGORY, "dug-out-highlighter", "Highlights big dug out (excavated) areas underground and marks the ones that look like bases.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DugOutHighlighter-Scan");
            t.setDaemon(true);
            return t;
        });
        areas = List.of();
        scanning.set(false);
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        areas = List.of();
        scanning.set(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;
        if (--timer > 0) return;
        timer = scanInterval.get() * 20;

        if (!scanning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;
        int radius = scanRadius.get();
        int lowY = Math.min(minY.get(), maxY.get());
        int highY = Math.max(minY.get(), maxY.get());
        int volume = minVolume.get();
        int size = minSize.get();
        int height = minHeight.get();
        double solidity = minWallSolidity.get() / 100.0;
        boolean bases = detectBases.get();

        executor.execute(() -> {
            try {
                Scanner scanner = new Scanner(world, lowY, highY, volume, size, height, solidity, bases);
                areas = scanner.run(chunkX, chunkZ, radius);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Dug Out Highlighter scan failed", t);
            } finally {
                scanning.set(false);
            }
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        List<Area> list = areas;
        if (list.isEmpty() || mc.player == null) return;

        double maxDistSq = (double) renderDistance.get() * renderDistance.get();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        boolean bases = detectBases.get();
        int baseThreshold = minBaseBlocks.get();

        for (Area area : list) {
            Box box = area.box();
            boolean isBase = bases && area.baseBlocks() >= baseThreshold;

            if (bases && onlyBases.get() && !isBase) continue;

            double cx = (box.minX + box.maxX) / 2.0;
            double cy = (box.minY + box.maxY) / 2.0;
            double cz = (box.minZ + box.maxZ) / 2.0;

            double dx = cx - px, dy = cy - py, dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            if (!seeThroughWalls.get() && !LineOfSight.canSeeBox(box)) continue;

            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                isBase ? baseSideColor.get() : sideColor.get(),
                isBase ? baseLineColor.get() : lineColor.get(),
                shapeMode.get(), 0
            );
        }
    }

    /** Runs on the background thread. Finds axis-aligned boxes of air that sit on a solid floor. */
    private static final class Scanner {
        private final ClientWorld world;
        private final BlockPos.Mutable pos = new BlockPos.Mutable();
        private final int minY, maxY, minVolume, minSize, minHeight;
        private final double minSolidity;
        private final boolean detectBases;

        Scanner(ClientWorld world, int minY, int maxY, int minVolume, int minSize, int minHeight,
                double minSolidity, boolean detectBases) {
            this.world = world;
            this.minY = minY;
            this.maxY = maxY;
            this.minVolume = minVolume;
            this.minSize = minSize;
            this.minHeight = minHeight;
            this.minSolidity = minSolidity;
            this.detectBases = detectBases;
        }

        /** Air check that treats unloaded chunks and anything outside the Y band as solid. */
        private boolean air(int x, int y, int z) {
            if (y < minY || y > maxY) return false;
            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return false;
            return world.getBlockState(pos.set(x, y, z)).isAir();
        }

        /** Same as air() but skips the chunk check, for blocks inside a chunk already known to be loaded. */
        private boolean airLoaded(int x, int y, int z) {
            return world.getBlockState(pos.set(x, y, z)).isAir();
        }

        /** Fraction of blocks hugging the outside of the box (floor, ceiling, 4 walls) that are not air. */
        private double wallSolidity(int x, int y, int z, int w, int d, int h) {
            int solid = 0, total = 0;

            for (int i = 0; i < w; i++) {
                for (int j = 0; j < d; j++) {
                    total += 2;
                    if (!air(x + i, y - 1, z + j)) solid++;
                    if (!air(x + i, y + h, z + j)) solid++;
                }
            }
            for (int k = 0; k < h; k++) {
                for (int j = 0; j < d; j++) {
                    total += 2;
                    if (!air(x - 1, y + k, z + j)) solid++;
                    if (!air(x + w, y + k, z + j)) solid++;
                }
            }
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < h; k++) {
                    total += 2;
                    if (!air(x + i, y + k, z - 1)) solid++;
                    if (!air(x + i, y + k, z + d)) solid++;
                }
            }

            return (double) solid / total;
        }

        /** Counts player-placed blocks (chests, furnaces, beds...) inside the box and its 1-block shell. */
        private int countBaseBlocks(int[] b) {
            int count = 0;

            for (int x = b[0] - 1; x <= b[3]; x++) {
                for (int z = b[2] - 1; z <= b[5]; z++) {
                    if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;

                    for (int y = b[1] - 1; y <= b[4]; y++) {
                        Block block = world.getBlockState(pos.set(x, y, z)).getBlock();
                        if (isBaseBlock(block)) count++;
                    }
                }
            }

            return count;
        }

        private static boolean covered(List<int[]> boxes, int x, int y, int z) {
            for (int[] b : boxes) {
                if (x >= b[0] && x < b[3] && y >= b[1] && y < b[4] && z >= b[2] && z < b[5]) return true;
            }
            return false;
        }

        List<Area> run(int centerChunkX, int centerChunkZ, int radius) {
            List<int[]> boxes = new ArrayList<>();

            for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
                for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                    if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                    int bx = cx << 4, bz = cz << 4;

                    for (int x = bx; x < bx + 16; x++) {
                        for (int z = bz; z < bz + 16; z++) {
                            for (int y = minY; y <= maxY; y++) {
                                if (!airLoaded(x, y, z)) continue;
                                // Lower corner of a room: solid floor, solid wall at -x and -z
                                if (air(x, y - 1, z)) continue;
                                if (air(x - 1, y, z) || air(x, y, z - 1)) continue;
                                if (covered(boxes, x, y, z)) continue;

                                // Expand along +x
                                int w = 1;
                                while (w < MAX_DIM && air(x + w, y, z)) w++;

                                // Expand along +z while the whole row is clear
                                int d = 1;
                                expandZ:
                                while (d < MAX_DIM) {
                                    for (int i = 0; i < w; i++) {
                                        if (!air(x + i, y, z + d)) break expandZ;
                                    }
                                    d++;
                                }

                                // Expand upward while the whole layer is clear
                                int h = 1;
                                expandY:
                                while (h < MAX_DIM && y + h <= maxY) {
                                    for (int i = 0; i < w; i++) {
                                        for (int j = 0; j < d; j++) {
                                            if (!air(x + i, y + h, z + j)) break expandY;
                                        }
                                    }
                                    h++;
                                }

                                if (w >= minSize && d >= minSize && h >= minHeight && w * d * h >= minVolume
                                    && wallSolidity(x, y, z, w, d, h) >= minSolidity) {
                                    boxes.add(new int[]{x, y, z, x + w, y + h, z + d});
                                }
                            }
                        }
                    }
                }
            }

            List<Area> result = new ArrayList<>(boxes.size());
            for (int[] b : boxes) {
                int baseBlocks = detectBases ? countBaseBlocks(b) : 0;
                result.add(new Area(new Box(b[0], b[1], b[2], b[3], b[4], b[5]), baseBlocks));
            }
            return result;
        }
    }
}
