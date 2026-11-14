package com.ccvirtualcoms.content;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlock;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlock;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All registry objects owned by this mod.
 *
 * <p>Kept in a dedicated class so that the main mod class stays focused on event wiring and so the
 * block entity can reference its own type without a circular initialisation hazard.
 */
public final class CCVirtualComsRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CCVirtualComs.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CCVirtualComs.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CCVirtualComs.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CCVirtualComs.MOD_ID);

    /** The virtual serial port circuit board. */
    public static final DeferredBlock<VirtualComsBlock> VIRTUAL_COMS_BLOCK = BLOCKS.register(
            "virtual_coms_board",
            () -> new VirtualComsBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .sound(net.minecraft.world.level.block.SoundType.METAL)
                    .noOcclusion()
                    .isRedstoneConductor((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)));

    public static final DeferredItem<BlockItem> VIRTUAL_COMS_ITEM = ITEMS.register(
            "virtual_coms_board",
            () -> new BlockItem(VIRTUAL_COMS_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VirtualComsBlockEntity>> VIRTUAL_COMS_BE =
            BLOCK_ENTITY_TYPES.register(
                    "virtual_coms_board",
                    () -> BlockEntityType.Builder
                            .of(VirtualComsBlockEntity::new, VIRTUAL_COMS_BLOCK.get())
                            .build(null));

    /** The camera transmitter: streams its view to an external virtual camera device. */
    public static final DeferredBlock<CameraTransmitterBlock> CAMERA_TRANSMITTER_BLOCK = BLOCKS.register(
            "camera_transmitter",
            () -> new CameraTransmitterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.METAL)));

    public static final DeferredBlock<CameraMonitorBlock> CAMERA_MONITOR_BLOCK = BLOCKS.register(
            "camera_monitor",
            () -> new CameraMonitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.METAL)));

    public static final DeferredItem<BlockItem> CAMERA_MONITOR_ITEM = ITEMS.register(
            "camera_monitor",
            () -> new BlockItem(CAMERA_MONITOR_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CameraMonitorBlockEntity>>
            CAMERA_MONITOR_BE = BLOCK_ENTITY_TYPES.register(
                    "camera_monitor",
                    () -> BlockEntityType.Builder
                            .of(CameraMonitorBlockEntity::new, CAMERA_MONITOR_BLOCK.get())
                            .build(null));

    public static final DeferredItem<BlockItem> CAMERA_TRANSMITTER_ITEM = ITEMS.register(
            "camera_transmitter",
            () -> new BlockItem(CAMERA_TRANSMITTER_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CameraTransmitterBlockEntity>>
            CAMERA_TRANSMITTER_BE = BLOCK_ENTITY_TYPES.register(
                    "camera_transmitter",
                    () -> BlockEntityType.Builder
                            .of(CameraTransmitterBlockEntity::new, CAMERA_TRANSMITTER_BLOCK.get())
                            .build(null));

    /** Dedicated creative tab holding every item this mod adds. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup." + CCVirtualComs.MOD_ID))
                    .icon(() -> VIRTUAL_COMS_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(VIRTUAL_COMS_ITEM.get());
                        output.accept(CAMERA_TRANSMITTER_ITEM.get());
                        output.accept(CAMERA_MONITOR_ITEM.get());
                    })
                    .build());

    private CCVirtualComsRegistry() {
    }
}
