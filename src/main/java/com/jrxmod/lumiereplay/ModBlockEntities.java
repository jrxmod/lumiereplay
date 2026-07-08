package com.jrxmod.lumiereplay;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {

    // BlockEntityType for the projector - links the block to its entity
    public static final BlockEntityType<ProjectorBlockEntity> PROJECTOR =
        Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(LumierePlay.MOD_ID, "projector"),
            FabricBlockEntityTypeBuilder.create(
                ProjectorBlockEntity::new,
                ModBlocks.PROJECTOR
            ).build()
        );

    /**
     * Called during mod initialization to trigger static field loading.
     */
    public static void initialize() {
        LumierePlay.LOGGER.info("Registering Lumiere Play block entities...");
    }
}
