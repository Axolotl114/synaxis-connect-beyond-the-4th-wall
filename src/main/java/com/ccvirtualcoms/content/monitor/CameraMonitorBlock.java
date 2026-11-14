package com.ccvirtualcoms.content.monitor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Wall mounted camera monitor; adjacent monitors merge into one screen. */
public class CameraMonitorBlock extends BaseEntityBlock {

    public static final MapCodec<CameraMonitorBlock> CODEC = simpleCodec(CameraMonitorBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            SHAPES[direction.get2DDataValue()] = switch (direction) {
                case SOUTH -> Block.box(0.0D, 0.0D, 14.0D, 16.0D, 16.0D, 16.0D);
                case NORTH -> Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 2.0D);
                case EAST -> Block.box(14.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
                case WEST -> Block.box(0.0D, 0.0D, 0.0D, 2.0D, 16.0D, 16.0D);
                default -> Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
            };
        }
    }

    public CameraMonitorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }

    @Override
    protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                           BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        markScreenDirty(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        markScreenDirty(level, pos);
    }

    @Override
    protected void neighborChanged(BlockState state, net.minecraft.world.level.Level level,
                                   BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        markScreenDirty(level, pos);
    }

    private static void markScreenDirty(net.minecraft.world.level.Level level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos target = pos.offset(dx, dy, dz);
                    if (level.getBlockEntity(target)
                            instanceof CameraMonitorBlockEntity monitor) {
                        monitor.markScreenDirty();
                    }
                }
            }
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CameraMonitorBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        return stack.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            com.ccvirtualcoms.client.CameraClientHooks.openMonitorScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
