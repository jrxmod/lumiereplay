package com.jrxmod.lumiereplay;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ModItemGroups {

    // Creative menu tab for all Lumiere Play items
    public static final RegistryKey<ItemGroup> LUMIEREPLAY_GROUP = RegistryKey.of(
        RegistryKeys.ITEM_GROUP,
        Identifier.of(LumierePlay.MOD_ID, "general")
    );

    /**
     * Registers the creative inventory tab and populates it with mod items.
     */
    public static void initialize() {
        Registry.register(Registries.ITEM_GROUP, LUMIEREPLAY_GROUP,
            FabricItemGroup.builder()
                .icon(() -> new ItemStack(ModBlocks.PROJECTOR))
                .displayName(Text.translatable("itemGroup.lumiereplay.general"))
                .entries((context, entries) -> {
                    entries.add(ModBlocks.PROJECTOR);
                })
                .build()
        );

        LumierePlay.LOGGER.info("Registering Lumiere Play item groups...");
    }
}
