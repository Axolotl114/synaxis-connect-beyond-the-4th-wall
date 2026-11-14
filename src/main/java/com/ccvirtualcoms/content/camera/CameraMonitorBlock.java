package com.ccvirtualcoms.content.camera;

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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The camera monitor: a thin wall panel that shows the live picture of one camera transmitter.
 *
 * <p>The block itself only renders the metal frame; the actual screen is drawn by
 * {@code CameraMonitorRenderer} from a per-screen {@code DynamicTexture}. The frame is the CC:Tweaked
 * advanced-monitor texture set used directly by resource location, so monitors match CC monitors
 * without copying any of CC's assets into this jar.
 *
 * <p>Adjacent monitors with the same {@link #FACING} merge into one screen. The merge itself is
 * computed by {@link CameraMonitorMultiblock}; every block stores the origin (the min-corner master)
 * of its component plus its own column/row inside that component.
 *
 * <p>Right-clicking with an empty hand opens the configuration screen on the client; the server
 * stays authoritative and only accepts changes through {@code CameraMonitorPayload}.
 */
public class CameraMonitorBlock extends BaseEntityBlock {

    public static final MapCodec<CameraMonitorBlock> CODEC = simpleCodec(CameraMonitorBlock::new);

    /** The direction the screen faces. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Panel thickness in blocks: 2/16 of a block, matching a thin wall-mounted display. */
    public static final double THICKNESS = 2.0D / 16.0D;

    private static final VoxelShape NORTH_SHAPE =
            Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, THICKNESS);
    private static final VoxelShape SOUTH_SHAPE =
            Shapes.box(0.0D, 0.0D, 1.0D - THICKNESS, 1.0D, 1.0D, 1.0D);
    private static final VoxelShape EAST_SHAPE =
            Shapes.box(1.0D - THICKNESS, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    private static final VoxelShape WEST_SHAPE =
            Shapes.box(0.0D, 0.0D, 0.0D, THICKNESS, 1.0D, 1.0D);

    public CameraMonitorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CameraMonitorBlockEntity(pos, state);
    }

    /** The screen faces the player who places it, like a wall-mounted display. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    /**
     * Placement hook.
     *
     * <p>The block entity of the placed block does not exist yet at this point (the chunk creates
     * it right after {@code onPlace}), so this only refreshes the blocks that are already there.
     * The new block entity refreshes the whole component from its own {@code onLoad}.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        CameraMonitorMultiblock.rebuildAt(level, pos);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        CameraMonitorMultiblock.rebuildAt(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!state.is(newState.getBlock())) {
            CameraMonitorMultiblock.rebuildAround(level, pos);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        return stack.isEmpty()
                ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            com.ccvirtualcoms.client.CameraMonitorClientHooks.openScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
