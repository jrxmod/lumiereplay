package com.jrxmod.lumiereplay;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import com.jrxmod.lumiereplay.network.ProjectorSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class ProjectorBlock extends BlockWithEntity {

    public static final MapCodec<ProjectorBlock> CODEC = createCodec(ProjectorBlock::new);

    // Horizontal facing property — NORTH / SOUTH / EAST / WEST
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public ProjectorBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    // Registers FACING into the block state system
    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Projector faces the same direction as the player — toward the wall/screen
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
    }

    // Records the placing player's UUID so access control works correctly.
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient() && placer instanceof PlayerEntity player) {
            if (world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector) {
                projector.setOwnerUuid(player.getUuidAsString());
            }
        }
    }

    /**
     * Detects redstone rising and falling edges.
     * Rising edge (signal appears)  — plays the projector if a URL is set.
     * Falling edge (signal removed) — pauses the projector.
     * Uses the projector's own isPlaying flag as the previous-edge memory
     * so no extra NBT field is needed.
     */
    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos,
                               net.minecraft.block.Block sourceBlock,
                               net.minecraft.util.math.BlockPos sourcePos,
                               boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
        if (world.isClient()) return;
        if (!(world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) return;
        if (projector.getVideoUrl().isEmpty()) return;

        boolean powered     = world.isReceivingRedstonePower(pos);
        boolean wasPowered  = projector.isRedstonePowered();

        // Only act on actual signal changes — prevents interfering with GUI playback
        if (powered == wasPowered) return;
        projector.setRedstonePowered(powered);

        // Rising edge — start playback
        if (powered) {
            projector.setPlaying(true);
            broadcastSync(projector, (ServerWorld) world, pos);
            LumierePlay.LOGGER.info("Redstone rising edge — projector play at {}", pos);
        }
        // Falling edge — pause playback
        else {
            projector.setPlaying(false);
            broadcastSync(projector, (ServerWorld) world, pos);
            LumierePlay.LOGGER.info("Redstone falling edge — projector pause at {}", pos);
        }
    }

    private static void broadcastSync(ProjectorBlockEntity projector,
                                      ServerWorld world, BlockPos pos) {
        ProjectorSyncPayload sync = new ProjectorSyncPayload(
            pos,
            projector.getVideoUrl(),
            projector.isPlaying(),
            projector.getVolume(),
            projector.getScreenWidth(),
            projector.getScreenHeight(),
            projector.getAccessMode()
        );
        for (var player : PlayerLookup.tracking(world, pos)) {
            ServerPlayNetworking.send(player, sync);
        }
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProjectorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            LumierePlay.LOGGER.debug("Projector right-clicked at {} facing {}",
                pos, state.get(FACING));
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }
}
