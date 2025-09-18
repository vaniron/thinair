package fuzs.thinair.handler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import fuzs.thinair.ThinAir;
import fuzs.thinair.api.v1.AirQualityLevel;
import fuzs.thinair.capability.AirBubblePositionsCapability;
import fuzs.thinair.init.ModRegistry;
import fuzs.thinair.network.ClientboundChunkAirQualityMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class AirBubbleTracker {
    private static final Set<ChunkPos> CHUNKS_TO_SCAN = Sets.newHashSet();
    private static final List<Map.Entry<ChunkPos, BlockPos>> CHUNK_SCANNING_PROGRESS = Lists.newLinkedList();
    private static final int MAX_BLOCKS_PER_TICK = 4096; // Limit blocks scanned per tick to reduce TPS impact
    private static final Map<ChunkPos, Map<BlockPos, AirQualityLevel>> PENDING_UPDATES = Maps.newHashMap(); // Batch updates for network efficiency

    public static void onBlockStateChange(ServerLevel level, BlockPos pos, BlockState oldBlockState, BlockState newBlockState) {
        if (oldBlockState.is(newBlockState.getBlock())) return; // Skip if block type hasn't changed

        ChunkPos chunkPos = new ChunkPos(pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) return; // Avoid processing if chunk is not loaded

        Optional<AirBubblePositionsCapability> optional = ModRegistry.AIR_BUBBLE_POSITIONS_CAPABILITY.maybeGet(chunk);
        optional.ifPresent(capability -> {
            Map<BlockPos, AirQualityLevel> updates = PENDING_UPDATES.computeIfAbsent(chunkPos, k -> Maps.newHashMap());
            boolean markDirty = false;

            AirQualityLevel oldQuality = AirQualityLevel.getAirQualityFromBlock(oldBlockState);
            if (oldQuality != null) {
                AirQualityLevel removed = capability.getAirBubblePositions().remove(pos);
                if (removed != null) {
                    updates.put(pos, removed); // Queue for removal
                    markDirty = true;
                }
            }

            AirQualityLevel newQuality = AirQualityLevel.getAirQualityFromBlock(newBlockState);
            if (newQuality != null) {
                capability.getAirBubblePositions().put(pos, newQuality);
                updates.put(pos, newQuality); // Queue for addition
                markDirty = true;
            }

            if (markDirty) {
                chunk.setUnsaved(true);
            }
        });
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        if (CHUNKS_TO_SCAN.add(chunkPos)) { // Only add if not already present
            CHUNK_SCANNING_PROGRESS.add(Map.entry(chunkPos, getChunkStartingPosition(chunk)));
        }
    }

    public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        CHUNKS_TO_SCAN.remove(chunkPos);
        CHUNK_SCANNING_PROGRESS.removeIf(entry -> entry.getKey().equals(chunkPos));
        PENDING_UPDATES.remove(chunkPos); // Clear pending updates
    }

    public static void onChunkWatch(ServerPlayer player, LevelChunk chunk, ServerLevel level) {
        ModRegistry.AIR_BUBBLE_POSITIONS_CAPABILITY.maybeGet(chunk).ifPresent(capability -> {
            ThinAir.NETWORK.sendTo(player, new ClientboundChunkAirQualityMessage(chunk.getPos(), capability.getAirBubblePositions(), ClientboundChunkAirQualityMessage.Mode.REPLACE));
        });
    }

    public static void onLevelUnload(MinecraftServer server, LevelAccessor level) {
        CHUNKS_TO_SCAN.clear();
        CHUNK_SCANNING_PROGRESS.clear();
        PENDING_UPDATES.clear();
    }

    public static void onEndLevelTick(MinecraftServer server, ServerLevel level) {
        if (CHUNK_SCANNING_PROGRESS.isEmpty()) return;

        // Process only one chunk per tick to avoid TPS spikes
        ListIterator<Map.Entry<ChunkPos, BlockPos>> iterator = CHUNK_SCANNING_PROGRESS.listIterator();
        if (!iterator.hasNext()) return;
        Map.Entry<ChunkPos, BlockPos> entry = iterator.next();
        ChunkPos chunkPos = entry.getKey();

        if (!CHUNKS_TO_SCAN.contains(chunkPos)) {
            iterator.remove();
            return;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) return; // Skip if chunk is not loaded

        Optional<AirBubblePositionsCapability> optional = ModRegistry.AIR_BUBBLE_POSITIONS_CAPABILITY.maybeGet(chunk);
        if (!optional.isPresent()) {
            iterator.remove();
            CHUNKS_TO_SCAN.remove(chunkPos);
            return;
        }

        AirBubblePositionsCapability capability = optional.get();
        Map<BlockPos, AirQualityLevel> airBubblePositions = Maps.newHashMap();
        BlockPos nextPos = collectAirQualityPositions(chunk, entry.getValue(), airBubblePositions);

        if (entry.getValue().equals(getChunkStartingPosition(chunk))) {
            capability.getAirBubblePositions().clear();
            capability.getAirBubblePositions().putAll(airBubblePositions);
            PENDING_UPDATES.put(chunkPos, airBubblePositions); // Queue full replace
            chunk.setUnsaved(true);
        } else if (!airBubblePositions.isEmpty()) {
            capability.getAirBubblePositions().putAll(airBubblePositions);
            PENDING_UPDATES.computeIfAbsent(chunkPos, k -> Maps.newHashMap()).putAll(airBubblePositions);
            chunk.setUnsaved(true);
        }

        if (nextPos != null) {
            iterator.set(Map.entry(chunkPos, nextPos));
        } else {
            iterator.remove();
            CHUNKS_TO_SCAN.remove(chunkPos);
        }

        // Send batched network updates
        PENDING_UPDATES.forEach((pos, updates) -> {
            if (!updates.isEmpty()) {
                ThinAir.NETWORK.sendToAllTracking(chunk, new ClientboundChunkAirQualityMessage(pos, updates, ClientboundChunkAirQualityMessage.Mode.REPLACE));
                updates.clear(); // Clear after sending
            }
        });
    }

    private static BlockPos getChunkStartingPosition(LevelChunk chunk) {
        int posX = chunk.getPos().getMinBlockX();
        int posY = chunk.getMinBuildHeight();
        int posZ = chunk.getPos().getMinBlockZ();
        return new BlockPos(posX, posY, posZ);
    }

    @Nullable
    private static BlockPos collectAirQualityPositions(LevelChunk chunk, BlockPos startingPosition, Map<BlockPos, AirQualityLevel> airBubbleEntries) {
        int minX = chunk.getPos().getMinBlockX();
        int minY = chunk.getMinBuildHeight();
        int minZ = chunk.getPos().getMinBlockZ();
        int startX = startingPosition.getX() - minX;
        int startY = startingPosition.getY();
        int startZ = startingPosition.getZ() - minZ;
        int iterations = 0;

        for (int dx = startX; dx < 16; dx++, startX = 0) {
            for (int dz = startZ; dz < 16; dz++, startZ = 0) {
                int posX = minX + dx;
                int posZ = minZ + dz;
                int maxY = chunk.getLevel().getHeight(Heightmap.Types.WORLD_SURFACE, posX, posZ);
                for (int posY = startY; posY < maxY; posY++, startY = minY, iterations++) {
                    if (iterations >= MAX_BLOCKS_PER_TICK) { // Limit iterations per tick
                        return new BlockPos(minX + dx, posY, minZ + dz);
                    }
                    BlockPos blockPos = new BlockPos(posX, posY, posZ);
                    BlockState blockState = chunk.getBlockState(blockPos);
                    AirQualityLevel airQualityLevel = AirQualityLevel.getAirQualityFromBlock(blockState);
                    if (airQualityLevel != null) {
                        airBubbleEntries.put(blockPos, airQualityLevel);
                    }
                }
            }
        }
        return null;
    }
}