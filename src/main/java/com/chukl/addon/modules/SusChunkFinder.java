package dev.krypton.module.KrModules;

import dev.krypton.event.ChunkDataEvent;
import dev.krypton.event.Subscribe;
import dev.krypton.event.WorldRenderEvent;
import dev.krypton.gui.render.ShapeMode;
import dev.krypton.module.Category;
import dev.krypton.module.Module;
import dev.krypton.setting.BooleanSetting;
import dev.krypton.setting.NumberSetting;
import dev.krypton.setting.Setting;
import dev.krypton.util.ColorValue;
import dev.krypton.vq;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class SusChunkFinder extends Module {

   public NumberSetting simulationDistance = new NumberSetting("Simulation Distance", 2.0, 16.0, 4.0, 1.0);
   public NumberSetting sensitivity = (new NumberSetting("Sensitivity", 1.0, 20.0, 3.0, 1.0)).setDescription("Minimum grown things needed to mark chunk as sus");
   public NumberSetting alpha = new NumberSetting("Alpha", 10.0, 255.0, 80.0, 1.0);
   public BooleanSetting kelp = new BooleanSetting("Kelp", true);
   public BooleanSetting caveVines = new BooleanSetting("Cave Vines", true);
   public BooleanSetting vines = new BooleanSetting("Vines", true);
   public BooleanSetting amethyst = new BooleanSetting("Amethyst", true);
   public BooleanSetting bamboo = new BooleanSetting("Bamboo", true);
   public BooleanSetting beeNest = (new BooleanSetting("Bee Nest", true)).setDescription("Bee nests with honey means someone loaded the chunk");
   public BooleanSetting rotatedDeepslate = (new BooleanSetting("Rotated Deepslate", true)).setDescription("Detect rotated deepslate buried between Y 0-60");

   public ConcurrentHashMap<ChunkPos, Integer> chunkHeatmap = new ConcurrentHashMap<>();
   public ConcurrentHashMap<ChunkPos, Integer> trackedChunks = new ConcurrentHashMap<>();
   public Set<ChunkPos> loadedChunkPositions = ConcurrentHashMap.newKeySet();
   public Set<BlockPos> suspiciousDeepslateBlocks = ConcurrentHashMap.newKeySet();
   public ConcurrentHashMap<ChunkPos, Set<BlockPos>> chunkDeepslateMap = new ConcurrentHashMap<>();
   public ConcurrentHashMap<ChunkPos, Integer> chunkGrowthCounts = new ConcurrentHashMap<>();
   public ConcurrentHashMap<ChunkPos, Boolean> chunkHasFullyGrown = new ConcurrentHashMap<>();
   public ExecutorService executor;

   public SusChunkFinder() {
      super("Sus Chunk Finder", "Finds probable base locations using plant/amethyst growth and rotated deepslate", -1, Category.BASE_FINDING);
      this.addSettings(new Setting[]{
         this.simulationDistance, this.sensitivity, this.alpha, this.kelp, 
         this.caveVines, this.vines, this.amethyst, this.bamboo, this.beeNest, this.rotatedDeepslate
      });
   }

   @Override
   public void onEnable() {
      this.executor = Executors.newSingleThreadExecutor();
      this.chunkHeatmap.clear();
      this.trackedChunks.clear();
      this.loadedChunkPositions.clear();
      this.suspiciousDeepslateBlocks.clear();
      this.chunkDeepslateMap.clear();
      this.chunkGrowthCounts.clear();
      this.chunkHasFullyGrown.clear();

      if (this.lr.player != null && this.lr.world != null) {
         for (Object obj : dev.krypton.rq.zo()) {
            WorldChunk chunk = (WorldChunk) obj;
            this.processChunkAsync(chunk);
         }
      }
      super.onEnable();
   }

   @Override
   public void onDisable() {
      super.onDisable();
      if (this.executor != null && !this.executor.isShutdown()) {
         this.executor.shutdownNow();
      }

      this.chunkHeatmap.clear();
      this.trackedChunks.clear();
      this.loadedChunkPositions.clear();
      this.suspiciousDeepslateBlocks.clear();
      this.chunkDeepslateMap.clear();
      this.chunkGrowthCounts.clear();
      this.chunkHasFullyGrown.clear();
   }

   @Subscribe
   public void onChunkData(ChunkDataEvent event) {
      if (this.lr.world != null) {
         WorldChunk chunk = this.lr.world.getChunk(event.packet.getChunkX(), event.packet.getChunkZ());
         this.processChunkAsync(chunk);
      }
   }

   public void processChunkAsync(WorldChunk chunk) {
      if (this.executor != null && !this.executor.isShutdown() && chunk != null) {
         this.executor.submit(() -> this.analyzeChunk(chunk));
      }
   }

   public void analyzeChunk(WorldChunk chunk) {
      if (!this.isEnabled() || this.lr.world == null) return;
      ChunkPos chunkPos = chunk.getPos();
      this.loadedChunkPositions.add(chunkPos);
      this.updateChunkData(chunkPos, chunk);
   }

   public void updateChunkData(ChunkPos chunkPos, WorldChunk chunk) {
      int simDist = this.simulationDistance.getInt();
      int previousGrowth = this.chunkGrowthCounts.getOrDefault(chunkPos, 0);
      boolean previousFullyGrown = Boolean.TRUE.equals(this.chunkHasFullyGrown.get(chunkPos));
      Set<BlockPos> previousDeepslate = this.chunkDeepslateMap.remove(chunkPos);

      if (previousGrowth > 0) {
         for (int dx = -simDist; dx <= simDist; dx++) {
            for (int dz = -simDist; dz <= simDist; dz++) {
               ChunkPos neighbor = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
               this.chunkHeatmap.compute(neighbor, (key, val) -> decrementValue(previousGrowth, key, val));
            }
         }
      }

      if (previousFullyGrown) {
         for (int dx = -simDist; dx <= simDist; dx++) {
            for (int dz = -simDist; dz <= simDist; dz++) {
               this.removeTrackedChunk(new ChunkPos(chunkPos.x + dx, chunkPos.z + dz));
            }
         }
      }

      if (previousDeepslate != null && !previousDeepslate.isEmpty()) {
         this.suspiciousDeepslateBlocks.removeAll(previousDeepslate);
      }

      vq result = this.scanChunkBlocks(chunkPos, chunk);
      this.chunkGrowthCounts.put(chunkPos, result.kbh);
      this.chunkHasFullyGrown.put(chunkPos, result.xzk);

      if (result.xzk) {
         for (int dx = -simDist; dx <= simDist; dx++) {
            for (int dz = -simDist; dz <= simDist; dz++) {
               this.addTrackedChunk(new ChunkPos(chunkPos.x + dx, chunkPos.z + dz));
            }
         }
      }

      if (result.kbh > 0) {
         for (int dx = -simDist; dx <= simDist; dx++) {
            for (int dz = -simDist; dz <= simDist; dz++) {
               ChunkPos neighbor = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
               this.chunkHeatmap.merge(neighbor, result.kbh, Integer::sum);
            }
         }
      }

      if (!result.kpk.isEmpty()) {
         this.chunkDeepslateMap.put(chunkPos, result.kpk);
         this.suspiciousDeepslateBlocks.addAll(result.kpk);
      }
   }

   public void addTrackedChunk(ChunkPos pos) {
      this.trackedChunks.merge(pos, 1, Integer::sum);
   }

   public void removeTrackedChunk(ChunkPos pos) {
      this.trackedChunks.compute(pos, (k, v) -> decrementTracked(k, v));
   }

   public boolean isChunkTracked(ChunkPos pos) {
      return this.trackedChunks.containsKey(pos);
   }

   public vq scanChunkBlocks(ChunkPos chunkPos, WorldChunk chunk) {
      ChunkSection[] sections = chunk.getSectionArray();
      int bottomY = chunk.getBottomSectionCoord();
      int startX = chunkPos.getStartX();
      int startZ = chunkPos.getStartZ();

      int kelpGrown = 0, kelpNotGrown = 0;
      int caveVinesGrown = 0, caveVinesNotGrown = 0;
      int vinesGrown = 0, vinesNotGrown = 0;
      int amethystGrown = 0, amethystNotGrown = 0;
      int bambooGrown = 0, bambooNotGrown = 0;
      int beeNestGrown = 0, beeNestNotGrown = 0;

      boolean checkDonutFolia = this.amethyst.getValue() && this.isDonutFolia();
      boolean foundBuds = false;
      boolean foundClusters = false;
      KeySetView<BlockPos, Boolean> deepslatePosSet = ConcurrentHashMap.newKeySet();

      for (int secIndex = 0; secIndex < sections.length; secIndex++) {
         ChunkSection section = sections[secIndex];
         if (section != null && !section.isEmpty()) {
            int sectionMinY = (bottomY + secIndex) << 4;

            if (checkDonutFolia) {
               if (!foundBuds && section.hasAny(SusChunkFinder::isAmethystBud)) {
                  foundBuds = true;
               }
               if (!foundClusters && section.hasAny(SusChunkFinder::isAmethystCluster)) {
                  foundClusters = true;
               }
            }

            for (int x = 0; x < 16; x++) {
               for (int y = 0; y < 16; y++) {
                  for (int z = 0; z < 16; z++) {
                     BlockState state = section.getBlockState(x, y, z);
                     int blockX = startX + x;
                     int blockY = sectionMinY + y;
                     int blockZ = startZ + z;

                     if (this.kelp.getValue() && state.isOf(Blocks.KELP) && state.contains(Properties.AGE_25)) {
                        int age = state.get(Properties.AGE_25);
                        BlockPos above = new BlockPos(blockX, blockY + 1, blockZ);
                        boolean waterAbove = this.lr.world.getBlockState(above).isOf(Blocks.WATER);
                        if (age != 25 && waterAbove) {
                           kelpGrown++;
                        } else {
                           kelpNotGrown++;
                        }
                     }

                     if (this.caveVines.getValue() && state.isOf(Blocks.CAVE_VINES) && state.contains(Properties.AGE_25)) {
                        int age = state.get(Properties.AGE_25);
                        BlockPos below = new BlockPos(blockX, blockY - 1, blockZ);
                        boolean airBelow = this.lr.world.getBlockState(below).isAir();
                        if (age != 25 && airBelow) {
                           caveVinesGrown++;
                        } else {
                           caveVinesNotGrown++;
                        }
                     }

                     if (this.vines.getValue() && state.isOf(Blocks.VINE)) {
                        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
                        BlockState stateBelow = this.lr.world.getBlockState(pos.down());
                        if (!stateBelow.isOf(Blocks.VINE)) {
                           if (!stateBelow.isAir()) {
                              vinesNotGrown++;
                           } else if (state.get(Properties.NORTH) || state.get(Properties.EAST) || state.get(Properties.SOUTH) || state.get(Properties.WEST)) {
                              vinesGrown++;
                           } else {
                              vinesNotGrown++;
                           }
                        }
                     }

                     if (this.amethyst.getValue() && !checkDonutFolia) {
                        if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                           amethystNotGrown++;
                        } else if ((state.isOf(Blocks.SMALL_AMETHYST_BUD) || state.isOf(Blocks.MEDIUM_AMETHYST_BUD) || state.isOf(Blocks.LARGE_AMETHYST_BUD))
                           && state.contains(Properties.FACING)) {
                           Direction facing = state.get(Properties.FACING);
                           BlockPos parentPos = (new BlockPos(blockX, blockY, blockZ)).offset(facing.getOpposite());
                           if (this.lr.world.getBlockState(parentPos).isOf(Blocks.BUDDING_AMETHYST)) {
                              amethystGrown++;
                           } else {
                              amethystNotGrown++;
                           }
                        }
                     }

                     if (this.bamboo.getValue() && state.isOf(Blocks.BAMBOO) && state.contains(Properties.STAGE)) {
                        BlockPos above = new BlockPos(blockX, blockY + 1, blockZ);
                        BlockState stateAbove = this.lr.world.getBlockState(above);
                        if (!stateAbove.isOf(Blocks.BAMBOO)) {
                           if (state.get(Properties.STAGE) == 1) {
                              bambooNotGrown++;
                           } else if (stateAbove.isAir()) {
                              bambooGrown++;
                           }
                        }
                     }

                     if (this.beeNest.getValue() && state.isOf(Blocks.BEE_NEST) && state.contains(Properties.HONEY_LEVEL)) {
                        if (state.get(Properties.HONEY_LEVEL) == 5) {
                           beeNestNotGrown++;
                        } else {
                           beeNestGrown++;
                        }
                     }

                     if (this.rotatedDeepslate.getValue()
                        && state.isOf(Blocks.DEEPSLATE)
                        && state.contains(Properties.AXIS)
                        && state.get(Properties.AXIS) != Axis.Y
                        && blockY >= 0
                        && blockY <= 60) {

                        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
                        boolean fullySurrounded = true;

                        for (Direction dir : Direction.values()) {
                           if (this.lr.world.getBlockState(pos.offset(dir)).isAir()) {
                              fullySurrounded = false;
                              break;
                           }
                        }

                        if (fullySurrounded) {
                           deepslatePosSet.add(pos);
                        }
                     }
                  }
               }
            }
         }
      }

      if (checkDonutFolia) {
         if (foundBuds) {
            amethystGrown++;
         } else if (foundClusters) {
            amethystNotGrown++;
         }
      }

      int totalGrown = (this.kelp.getValue() ? kelpGrown : 0)
         + (this.caveVines.getValue() ? caveVinesGrown : 0)
         + (this.vines.getValue() ? vinesGrown : 0)
         + (this.amethyst.getValue() ? amethystGrown : 0)
         + (this.bamboo.getValue() ? bambooGrown : 0)
         + (this.beeNest.getValue() ? beeNestGrown : 0);

      int totalNotGrown = (this.kelp.getValue() ? kelpNotGrown : 0)
         + (this.caveVines.getValue() ? caveVinesNotGrown : 0)
         + (this.vines.getValue() ? vinesNotGrown : 0)
         + (this.amethyst.getValue() ? amethystNotGrown : 0)
         + (this.bamboo.getValue() ? bambooNotGrown : 0)
         + (this.beeNest.getValue() ? beeNestNotGrown : 0);

      boolean hasGrown = totalGrown > 0;
      return new vq(totalNotGrown, hasGrown, deepslatePosSet);
   }

   @Subscribe
   public void onRender(WorldRenderEvent event) {
      if (this.lr.world == null) return;

      int alphaVal = this.alpha.getInt();
      int minSensitivity = this.sensitivity.getInt();

      if (!this.chunkHeatmap.isEmpty()) {
         ColorValue redColor = new ColorValue(255, 0, 0, alphaVal);

         for (Entry<ChunkPos, Integer> entry : this.chunkHeatmap.entrySet()) {
            ChunkPos pos = entry.getKey();
            int count = entry.getValue();

            if (count >= minSensitivity && !this.isChunkTracked(pos) && this.lr.world.isChunkLoaded(pos.x, pos.z)) {
               int neighborCount = 0;

               for (int dx = -1; dx <= 1; dx++) {
                  for (int dz = -1; dz <= 1; dz++) {
                     if (dx != 0 || dz != 0) {
                        if (this.loadedChunkPositions.contains(new ChunkPos(pos.x + dx, pos.z + dz))) {
                           neighborCount++;
                        }
                     }
                  }
               }

               if (neighborCount >= 3) {
                  event.renderer.vqo(pos.getStartX(), 63.0, pos.getStartZ(), pos.getStartX() + 16, 63.1, pos.getStartZ() + 16, redColor, redColor, ShapeMode.OUTLINE, 0);
               }
            }
         }
      }

      if (!this.suspiciousDeepslateBlocks.isEmpty()) {
         ColorValue cyanColor = new ColorValue(0, 255, 255, alphaVal);
         this.suspiciousDeepslateBlocks.removeIf(this::isChunkNotLoaded);

         for (BlockPos pos : this.suspiciousDeepslateBlocks) {
            event.renderer.vqo(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, cyanColor, cyanColor, ShapeMode.OUTLINE, 0);
         }
      }
   }

   public boolean isDonutFolia() {
      if (this.lr.getNetworkHandler() == null) return false;
      String brand = this.lr.getNetworkHandler().getBrand();
      return brand != null && brand.contains("DonutFolia");
   }

   public boolean isChunkNotLoaded(BlockPos pos) {
      return !this.lr.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
   }

   public static boolean isAmethystCluster(BlockState state) {
      return state.getBlock() == Blocks.AMETHYST_CLUSTER;
   }

   public static boolean isAmethystBud(BlockState state) {
      return state.getBlock() == Blocks.SMALL_AMETHYST_BUD || state.getBlock() == Blocks.MEDIUM_AMETHYST_BUD || state.getBlock() == Blocks.LARGE_AMETHYST_BUD;
   }

   public static Integer decrementTracked(ChunkPos key, Integer value) {
      if (value == null) return null;
      int newVal = value - 1;
      return newVal <= 0 ? null : newVal;
   }

   public static Integer decrementValue(int amount, ChunkPos key, Integer value) {
      int newVal = (value == null ? 0 : value) - amount;
      return newVal <= 0 ? null : newVal;
   }
}