/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc;

import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.provider.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.network.PacketDistributor;

import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.capabilities.forge.ForgingCapability;
import net.dries007.tfc.common.capabilities.forge.ForgingHandler;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dries007.tfc.common.command.TFCCommands;
import net.dries007.tfc.common.recipes.CollapseRecipe;
import net.dries007.tfc.common.recipes.LandslideRecipe;
import net.dries007.tfc.common.types.MetalItemManager;
import net.dries007.tfc.common.types.MetalManager;
import net.dries007.tfc.common.types.RockManager;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.network.ChunkUnwatchPacket;
import net.dries007.tfc.network.PacketHandler;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.TFCServerTracker;
import net.dries007.tfc.util.support.SupportManager;
import net.dries007.tfc.world.TFCWorldType;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.dries007.tfc.world.chunkdata.ChunkDataCache;
import net.dries007.tfc.world.chunkdata.ChunkDataCapability;
import net.dries007.tfc.world.tracker.WorldTracker;
import net.dries007.tfc.world.tracker.WorldTrackerCapability;
import net.dries007.tfc.world.vein.VeinTypeManager;

import static net.dries007.tfc.TerraFirmaCraft.MOD_ID;

@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeEventHandler
{
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Duplicates logic from {@link ServerWorld#createSpawnPosition(WorldSettings)} as that version only asks the dimension for the sea level...
     */
    @SubscribeEvent
    public static void onCreateWorldSpawn(WorldEvent.CreateSpawnPosition event)
    {
        // Forge why you make everything `IWorld`, it's literally only called from `ServerWorld`...
        if (event.getWorld() instanceof ServerWorld && ((World) event.getWorld()).getWorldType() == TFCWorldType.INSTANCE)
        {
            ServerWorld world = (ServerWorld) event.getWorld();
            event.setCanceled(true);

            BiomeProvider biomeProvider = world.getChunkProvider().getChunkGenerator().getBiomeProvider();
            Random random = new Random(world.getSeed());
            BlockPos pos = biomeProvider.func_225531_a_(0, world.getChunkProvider().getChunkGenerator().getSeaLevel(), 0, 256, biomeProvider.getBiomesToSpawnIn(), random);
            ChunkPos chunkPos = pos == null ? new ChunkPos(0, 0) : new ChunkPos(pos);
            if (pos == null)
            {
                LOGGER.warn("Unable to find spawn biome");
            }

            boolean flag = false;
            for (Block block : BlockTags.VALID_SPAWN.getAllElements())
            {
                if (biomeProvider.getSurfaceBlocks().contains(block.getDefaultState()))
                {
                    flag = true;
                    break;
                }
            }
            // Initial guess at spawn position
            world.getWorldInfo().setSpawn(chunkPos.asBlockPos().add(8, world.getChunkProvider().getChunkGenerator().getGroundHeight(), 8));
            int x = 0;
            int z = 0;
            int xStep = 0;
            int zStep = -1;
            // Step around until we find a valid spawn position / chunk
            for (int tries = 0; tries < 1024; ++tries)
            {
                if (x > -16 && x <= 16 && z > -16 && z <= 16)
                {
                    BlockPos spawnPos = world.dimension.findSpawn(new ChunkPos(chunkPos.x + x, chunkPos.z + z), flag);
                    if (spawnPos != null)
                    {
                        world.getWorldInfo().setSpawn(spawnPos);
                        break;
                    }
                }

                if (x == z || x < 0 && x == -z || x > 0 && x == 1 - z)
                {
                    int temp = xStep;
                    xStep = -zStep;
                    zStep = temp;
                }

                x += xStep;
                z += zStep;
            }

            // Don't create bonus chest
        }
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesChunk(AttachCapabilitiesEvent<Chunk> event)
    {
        if (!event.getObject().isEmpty())
        {
            World world = event.getObject().getWorld();
            ChunkPos chunkPos = event.getObject().getPos();
            ChunkData data;
            if (!Helpers.isRemote(world))
            {
                // Chunk was created on server thread.
                // 1. If this was due to world gen, it won't have any cap data. This is where we clear the world gen cache and attach it to the chunk
                // 2. If this was due to chunk loading, the caps will be deserialized from NBT after this event is posted. Attach empty data here
                data = ChunkDataCache.WORLD_GEN.remove(chunkPos);
                if (data == null)
                {
                    data = new ChunkData(chunkPos);
                }
            }
            else
            {
                // This may happen before or after the chunk is watched and synced to client
                // Default to using the cache. If later the sync packet arrives it will update the same instance in the chunk capability and cache
                data = ChunkDataCache.CLIENT.getOrCreate(chunkPos);
            }
            event.addCapability(ChunkDataCapability.KEY, data);
        }
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event)
    {
        // Send an update packet to the client when watching the chunk
        ChunkPos pos = event.getPos();
        ChunkData chunkData = ChunkData.get(event.getWorld(), pos);
        if (chunkData.getStatus() != ChunkData.Status.EMPTY)
        {
            PacketHandler.send(PacketDistributor.PLAYER.with(event::getPlayer), chunkData.getUpdatePacket());
        }
        else
        {
            // Chunk does not exist yet but it's queue'd for watch. Queue an update packet to be sent on chunk load
            ChunkDataCache.WATCH_QUEUE.enqueueUnloadedChunk(pos, event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event)
    {
        if (!Helpers.isRemote(event.getWorld()) && !(event.getChunk() instanceof EmptyChunk))
        {
            ChunkPos pos = event.getChunk().getPos();
            ChunkData.getCapability(event.getChunk()).ifPresent(data -> {
                ChunkDataCache.SERVER.update(pos, data);
                ChunkDataCache.WATCH_QUEUE.dequeueLoadedChunk(pos, data);
            });
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event)
    {
        // Clear server side chunk data cache
        if (!Helpers.isRemote(event.getWorld()) && !(event.getChunk() instanceof EmptyChunk))
        {
            ChunkDataCache.SERVER.remove(event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event)
    {
        // Send an update packet to the client when un-watching the chunk
        ChunkPos pos = event.getPos();
        PacketHandler.send(PacketDistributor.PLAYER.with(event::getPlayer), new ChunkUnwatchPacket(pos));
        ChunkDataCache.WATCH_QUEUE.dequeueChunk(pos, event.getPlayer());
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesWorld(AttachCapabilitiesEvent<World> event)
    {
        event.addCapability(WorldTrackerCapability.KEY, new WorldTracker());
    }

    @SubscribeEvent
    public static void beforeServerStart(FMLServerAboutToStartEvent event)
    {
        LOGGER.debug("Before Server Start");

        // Initializes json data listeners
        IReloadableResourceManager resourceManager = event.getServer().getResourceManager();
        resourceManager.addReloadListener(RockManager.INSTANCE);
        resourceManager.addReloadListener(MetalManager.INSTANCE);
        resourceManager.addReloadListener(MetalItemManager.INSTANCE);
        resourceManager.addReloadListener(VeinTypeManager.INSTANCE);
        resourceManager.addReloadListener(SupportManager.INSTANCE);

        // Capability json data loader
        resourceManager.addReloadListener(HeatCapability.HeatManager.INSTANCE);

        // Server tracker
        TFCServerTracker.INSTANCE.onServerStart(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarting(FMLServerStartingEvent event)
    {
        // todo: move this to the dedicated command register event on forge update
        LOGGER.debug("Registering TFC Commands");
        TFCCommands.register(event.getCommandDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopped(FMLServerStoppedEvent event)
    {
        TFCServerTracker.INSTANCE.onServerStop();
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event)
    {
        // Check for possible collapse
        IWorld world = event.getWorld();
        BlockPos pos = event.getPos();
        BlockState state = world.getBlockState(pos);

        if (TFCTags.Blocks.CAN_TRIGGER_COLLAPSE.contains(state.getBlock()) && world instanceof World)
        {
            CollapseRecipe.tryTriggerCollapse((World) world, pos);
        }
    }

    @SubscribeEvent
    public static void onNeighborUpdate(BlockEvent.NeighborNotifyEvent event)
    {
        IWorld world = event.getWorld();
        for (Direction direction : event.getNotifiedSides())
        {
            // Check each notified block for a potential gravity block
            BlockPos pos = event.getPos().offset(direction);
            BlockState state = world.getBlockState(pos);
            if (TFCTags.Blocks.CAN_LANDSLIDE.contains(state.getBlock()) && world instanceof World)
            {
                // Here, we just record the position rather than immediately updating as this is called from `setBlockState` so it's preferred to handle it with just a little latency
                ((World) world).getCapability(WorldTrackerCapability.CAPABILITY).ifPresent(cap -> cap.addLandslidePos(pos));
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event)
    {
        IWorld world = event.getWorld();
        if (TFCTags.Blocks.CAN_LANDSLIDE.contains(event.getState().getBlock()) && world instanceof World)
        {
            LandslideRecipe.tryLandslide((World) event.getWorld(), event.getPos(), event.getState());
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
        {
            event.world.getCapability(WorldTrackerCapability.CAPABILITY).ifPresent(cap -> cap.tick(event.world));
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event)
    {
        if (!event.getWorld().isRemote)
        {
            event.getWorld().getCapability(WorldTrackerCapability.CAPABILITY).ifPresent(cap -> cap.addCollapsePositions(new BlockPos(event.getExplosion().getPosition()), event.getAffectedBlocks()));
        }
    }

    @SubscribeEvent
    public static void attachItemCapabilities(AttachCapabilitiesEvent<ItemStack> event)
    {
        ItemStack stack = event.getObject();
        if (!stack.isEmpty())
        {
            // Every item has a forging capability
            event.addCapability(ForgingCapability.KEY, new ForgingHandler(stack));

            // Attach heat capability to the ones defined by data packs
            HeatCapability.HeatManager.CACHE.getAll(stack.getItem())
                .stream()
                .filter(heatWrapper -> heatWrapper.isValid(stack))
                .findFirst()
                .map(HeatCapability.HeatWrapper::getCapability)
                .ifPresent(heat -> event.addCapability(HeatCapability.KEY, heat));
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event)
    {
        if (event.getWorld() instanceof ServerWorld && event.getWorld().getDimension().getType() == DimensionType.OVERWORLD)
        {
            ServerWorld world = (ServerWorld) event.getWorld();
            if (TFCConfig.SERVER.enableVanillaNaturalRegeneration.get())
            {
                // Natural regeneration should be disabled, allows TFC to have custom regeneration
                world.getGameRules().get(GameRules.NATURAL_REGENERATION).set(false, world.getServer());
                LOGGER.info("Updating gamerule naturalRegeneration to false!");
            }
        }
    }
}