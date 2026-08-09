package com.jrxmod.lumiereplay;

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
            BlockEntityType.Builder.create(
                ProjectorBlockEntity::new,
                ModBlocks.PROJECTOR
            ).build(null)
        );

    /**
     * Called during mod initialization to trigger static field loading.
     */
    public static void initialize() {
        LumierePlay.LOGGER.info("Registering Lumiere Play block entities...");
    }
}
