package dev.krypton.module.KrModules;

import dev.krypton.event.Subscribe;
import dev.krypton.event.WorldRenderEvent;
import dev.krypton.gui.render.ShapeMode;
import dev.krypton.mixin.ChunkLightProviderAccessor;
import dev.krypton.mixin.ChunkToNibbleArrayMapAccessor;
import dev.krypton.mixin.LightStorageAccessor;
import dev.krypton.module.Category;
import dev.krypton.module.Module;
import dev.krypton.util.ColorValue;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.ChunkLightProvider;

public class LightFinder extends Module {

   public LightFinder() {
      super("Light Finder", "Renders all light sections for base finding", -1, Category.BASE_FINDING);
   }

   @Subscribe
   public void onRender(WorldRenderEvent event) {
      if (this.lr.world == null || this.lr.player == null) {
         return;
      }

      try {
         ObjectIterator<Entry<ChunkNibbleArray>> iterator = ((ChunkToNibbleArrayMapAccessor) ((LightStorageAccessor) (
               (ChunkLightProviderAccessor) ((ChunkLightProvider) this.lr.world.getLightingProvider().get(LightType.BLOCK))
            ).getLightStorage()).getStorage())
            .getArrays()
            .long2ObjectEntrySet()
            .iterator();

         while (iterator.hasNext()) {
            Entry<ChunkNibbleArray> entry = iterator.next();
            long key = entry.getLongKey();
            ChunkNibbleArray array = entry.getValue();

            if (array == null || array.isArrayUninitialized()) {
               continue;
            }

            ChunkSectionPos sectionPos = ChunkSectionPos.from(key);
            int minX = sectionPos.getMinX();
            int minY = sectionPos.getMinY();
            int minZ = sectionPos.getMinZ();

            for (int x = 0; x < 16; x++) {
               for (int y = 0; y < 16; y++) {
                  for (int z = 0; z < 16; z++) {
                     int lightLevel = array.get(x, y, z);
                     if (lightLevel != 0) {
                        float brightness = lightLevel / 15.0F;
                        ColorValue color = new ColorValue(brightness, brightness, 0.2F, 0.3F);
                        BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);

                        if (pos.getY() < 0) {
                           event.renderer.vqo(
                              pos.getX(), pos.getY(), pos.getZ(),
                              pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                              color, color, ShapeMode.OUTLINE, 0
                           );
                        }
                     }
                  }
               }
            }
         }
      } catch (Exception e) {
         e.printStackTrace();
      }
   }
}