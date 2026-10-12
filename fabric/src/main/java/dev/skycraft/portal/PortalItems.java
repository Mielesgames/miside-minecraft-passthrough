package dev.skycraft.portal;

import dev.skycraft.SkyCraft;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * The portal network's items, registered like SkyGear's (vanilla 26.3 Items.registerItem: the
 * Item.Properties gets its id first). Models in assets/skycraft/items, textures from
 * tools/gen_portal_textures.py.
 */
public final class PortalItems {
	/** skycraft:filled_soul_gem, from a filled Greater, Grand or Black Skyrim soul gem (skycraft-items.txt). */
	public static Item FILLED_SOUL_GEM;
	/** skycraft:attuned_soul_gem: a filled gem attuned to one portal (CUSTOM_DATA remembers which). */
	public static Item ATTUNED_SOUL_GEM;
	/** skycraft:tonal_key: the Dwemer's key to Tamriel Wilds (crafted: dwarven metal, redstone, amethyst). */
	public static Item TONAL_KEY;
	/** skycraft:seamstone: from the Far Lands' strata; opens any lit portal onto any known place. Never spent. */
	public static Item SEAMSTONE;

	private PortalItems() {
	}

	static void init() {
		FILLED_SOUL_GEM = register(PortalStore.FILLED_GEM_ID, p -> new SoulGemItem(p.stacksTo(16).rarity(Rarity.UNCOMMON), false));
		ATTUNED_SOUL_GEM = register(PortalStore.ATTUNED_GEM_ID,
			p -> new SoulGemItem(p.stacksTo(1).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true), true));
		TONAL_KEY = register(PortalStore.TONAL_KEY_ID, p -> new PortalKeyItem(p.stacksTo(16).rarity(Rarity.RARE),
			List.of("Dwemer tonal architecture", "Use on a lit portal in Skyrim to open it onto Tamriel Wilds")));
		SEAMSTONE = register(PortalStore.SEAMSTONE_ID, p -> new PortalKeyItem(p.stacksTo(16).rarity(Rarity.EPIC).fireResistant(),
			List.of("From past the Far Lands", "Use on any lit portal to choose where it opens")));
		SkyCraft.LOG.info("SkyCraft-portal: registered the soul gems, the tonal key and Seamstone");
	}

	private static Item register(String name, Function<Item.Properties, Item> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
	}
}
