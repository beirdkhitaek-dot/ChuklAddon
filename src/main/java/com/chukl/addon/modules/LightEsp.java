package com.chukl.addon.modules; // <-- change to your addon's modules package

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * LightESP - highlights light sources (torches, lanterns, glowstone...) or lit spaces.
 * Written from scratch for ChuklAddon. Lightweight: scans a few chunks per tick on the
 * main thread, skips empty chunk sections, no thread pool.
 */
public class LightEsp extends Module {
    public enum Mode { Sources, LitSpaces }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerf = settings.createGroup("Performance");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Sources = blocks that emit light. LitSpaces = any block position with block light at or above the level.")
        .defaultValue(Mode.Sources).build());

    private final Setting<Integer> minLight = sgGeneral.add(new IntSetting.Builder()
        .name("min-light-level").description("Minimum light level to highlight.")
        .defaultValue(12).min(1).max(15).sliderMax(15).build());

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius").description("Radius in chunks around you to scan.")
        .defaultValue(6).min(1).max(16).sliderMax(16).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y to scan.")
        .defaultValue(-64).min(-64).max(320).sliderRange(-64, 320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y to scan.")
        .defaultValue(320).min(-64).max(320).sliderRange(-64, 320).build());

    private final Setting<Boolean> autoToggleY = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-toggle-y").description("Only render while you are above the toggle Y level.")
        .defaultValue(false).build());

    private final Setting<Integer> toggleY = sgGeneral.add(new IntSetting.Builder()
        .name("toggle-y-level").description("Render only when your Y is at or above this.")
        .defaultValue(0).min(-64).max(320).sliderRange(-64, 320).visible(autoToggleY::get).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(255, 200, 0, 40)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(255, 200, 0, 255)).build());

    private final Setting<Integer> maxBoxes = sgRender.add(new IntSetting.Builder()
        .name("max-boxes").description("Cap on boxes drawn per frame (protects low-end devices).")
        .defaultValue(1500).min(100).max(10000).sliderRange(100, 5000).build());

    private final Setting<Integer> chunksPerTick = sgPerf.add(new IntSetting.Builder()
        .name("chunks-per-tick").description("Chunks scanned each tick. Lower = smoother.")
        .defaultValue(1).min(1).max(8).sliderMax(8).build());

    private final Setting<Integer> rescanDelay = sgPerf.add(new IntSetting.Builder()
        .name("rescan-delay").description("Ticks between full rescans (20 = 1 second).")
        .defaultValue(100).min(20).max(1200).sliderRange(20, 600).build());

    private final Long2ObjectOpenHashMap<List<BlockPos>> found = new Long2ObjectOpenHashMap<>();
    private final ArrayList<ChunkPos> queue = new ArrayList<>();
    private int queueIndex = 0;
    private int ticksSinceRescan = 0;
    private ChunkPos lastCenter;

    // Pass your addon's category here, e.g. ChuklAddon.RENDER_CATEGORY
    public LightEsp(Category category) {
        super(category, "light-esp", "Highlights light sources or lit spaces.");
    }

    @Override
    public void onActivate() {
        found.clear();
        queue.clear();
        queueIndex = 0;
        ticksSinceRescan = Integer.MAX_VALUE / 2; // force an immediate scan
        lastCenter = null;
    }

    @Override
    public void onDeactivate() {
        found.clear();
        queue.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        ChunkPos center = mc.player.getChunkPos();
        ticksSinceRescan++;

        boolean moved = lastCenter == null || center.x != lastCenter.x || center.z != lastCenter.z;
        if (queueIndex >= queue.size() && (ticksSinceRescan >= rescanDelay.get() || moved)) {
            buildQueue(center);
            lastCenter = center;
            ticksSinceRescan = 0;
        }

        int budget = chunksPerTick.get();
        while (budget-- > 0 && queueIndex < queue.size()) {
            ChunkPos cp = queue.get(queueIndex++);
            scanChunk(cp);
        }
    }

    private void buildQueue(ChunkPos center) {
        queue.clear();
        queueIndex = 0;
        int r = chunkRadius.get();

        // Closest chunks first so nearby lights show up quickly.
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    queue.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }
        }

        // Drop data for chunks that are now out of range.
        found.keySet().removeIf(key -> {
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            return Math.abs(cx - center.x) > r || Math.abs(cz - center.z) > r;
        });
    }

    private void scanChunk(ChunkPos cp) {
        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
        long key = cp.toLong();
        if (chunk == null) {
            found.remove(key);
            return;
        }

        int bottom = mc.world.getBottomY();
        int lo = Math.max(minY.get(), bottom);
        int hi = Math.min(maxY.get(), mc.world.getTopYInclusive());
        int min = minLight.get();
        boolean sources = mode.get() == Mode.Sources;

        List<BlockPos> list = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null) continue;
            int sectionY = bottom + (si << 4);
            if (sectionY + 15 < lo || sectionY > hi) continue;

            // In Sources mode an empty (all air) section can't contain emitters.
            if (sources && section.isEmpty()) continue;

            for (int y = 0; y < 16; y++) {
                int wy = sectionY + y;
                if (wy < lo || wy > hi) continue;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (sources) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.getLuminance() >= min) {
                                list.add(new BlockPos(cp.getStartX() + x, wy, cp.getStartZ() + z));
                            }
                        } else {
                            pos.set(cp.getStartX() + x, wy, cp.getStartZ() + z);
                            if (mc.world.getLightLevel(LightType.BLOCK, pos) >= min) {
                                list.add(pos.toImmutable());
                            }
                        }
                    }
                }
            }
        }

        if (list.isEmpty()) found.remove(key);
        else found.put(key, list);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || found.isEmpty()) return;
        if (autoToggleY.get() && mc.player.getY() < toggleY.get()) return;

        int drawn = 0;
        int cap = maxBoxes.get();
        for (List<BlockPos> list : found.values()) {
            for (int i = 0; i < list.size(); i++) {
                if (drawn++ >= cap) return;
                event.renderer.box(list.get(i), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (List<BlockPos> l : found.values()) n += l.size();
        return Integer.toString(n);
    }
}
