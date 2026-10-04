package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.utils.LineOfSight;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockDataSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.ESPBlockData;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * Highlights block entities (chests, barrels, spawners, furnaces, etc.) below a
 * configurable Y level (default Y15). Each block you add gets its own shape mode,
 * colors and tracer. Blocks you haven't added use the default config if
 * "highlight-unlisted" is on.
 */
public class DeepBlockEntityEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only highlight block entities below this Y level.")
        .defaultValue(15)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Boolean> seeThroughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Draw block entities through walls. When off, only ones in your line of sight are drawn.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> highlightUnlisted = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-unlisted")
        .description("Also highlight block entities you haven't added below, using the default config.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ESPBlockData> defaultBlockConfig = sgGeneral.add(new GenericSetting.Builder<ESPBlockData>()
        .name("default-block-config")
        .description("Default shape, colors and tracer.")
        .defaultValue(new ESPBlockData(
            ShapeMode.Both,
            new SettingColor(255, 170, 0, 255),
            new SettingColor(255, 170, 0, 40),
            true,
            new SettingColor(255, 170, 0, 150)
        ))
        .build()
    );

    private final Setting<Map<Block, ESPBlockData>> blockConfigs = sgGeneral.add(new BlockDataSetting.Builder<ESPBlockData>()
        .name("block-configs")
        .description("Pick blocks and set a custom color/tracer for each one.")
        .defaultData(defaultBlockConfig)
        .build()
    );

    public DeepBlockEntityEsp() {
        super(ChuklAddon.CATEGORY, "deep-block-entity-esp", "Highlights block entities below a set Y level, with per-block colors and tracers.");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        int limit = maxY.get();
        Map<Block, ESPBlockData> configs = blockConfigs.get();

        for (BlockEntity blockEntity : Utils.blockEntities()) {
            BlockPos pos = blockEntity.getPos();
            if (pos.getY() >= limit) continue;
            if (!seeThroughWalls.get() && !LineOfSight.canSeeBlock(pos)) continue;

            Block block = blockEntity.getCachedState().getBlock();
            ESPBlockData data = configs.get(block);

            if (data == null) {
                if (!highlightUnlisted.get()) continue;
                data = defaultBlockConfig.get();
            }

            event.renderer.box(pos, data.sideColor, data.lineColor, data.shapeMode, 0);

            if (data.tracer) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    data.tracerColor
                );
            }
        }
    }
}
