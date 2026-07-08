package com.jrxmod.lumiereplay;

import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {

    // Projector block - renders a virtual screen and stores playback state
    public static final ProjectorBlock PROJECTOR = register(
        "projector",
        new ProjectorBlock(FabricBlockSettings.create().strength(3.5f, 4.0f).requiresTool().luminance(state -> 0))
    );

    /**
     * Registers a block and its corresponding BlockItem under the mod namespace.
     */
    private static <T extends Block> T register(String path, T block) {
        Identifier id = Identifier.of(LumierePlay.MOD_ID, path);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        return block;
    }

    /**
     * Called during mod initialization to trigger static field loading.
     */
    public static void initialize() {
        LumierePlay.LOGGER.info("Registering Lumiere Play blocks...");
    }
}
