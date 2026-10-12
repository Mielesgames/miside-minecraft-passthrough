package dev.skycraft.gear;

import dev.skycraft.SkyCraft;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim gear sets the Minecraft player can craft: per tier (iron to daedric, see {@link GearTiers})
 * a sword, a war axe and four armor pieces, plus the Skyrim crafting materials they need.
 *
 * <p>Registration follows vanilla 26.3 (Items.registerItem): the Item.Properties gets its id first,
 * then sword/axe/humanoidArmor fill in durability, attributes, repair and enchantability from a
 * ToolMaterial or ArmorMaterial. Item models are in assets/skycraft/items, armor layers in
 * assets/skycraft/equipment, recipes in data/skycraft/recipe, repair tags in data/skycraft/tags/item.
 */
public final class SkyGear {
	/** Every item this class registered, by id (no namespace), in creative tab order. */
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
	private static final Map<Item, GearTiers.Tier> TIER_OF = new HashMap<>();
	private static final Map<Item, Boolean> IS_ARMOR = new HashMap<>();

	private SkyGear() {
	}

	public static void init() {
		for (String id : GearTiers.MATERIALS) {
			register(id, p -> p);
		}
		for (GearTiers.Tier tier : GearTiers.TIERS) {
			registerTier(tier);
		}
		Item icon = ITEMS.get(GearTiers.itemId(GearTiers.TIERS.getLast(), "sword"));
		CreativeModeTab tab = FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.skycraft.gear"))
			.icon(() -> new ItemStack(icon))
			.displayItems((parameters, output) -> {
				for (Item item : ITEMS.values()) {
					output.accept(item);
				}
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("gear")), tab);
		SkyCraft.LOG.info("SkyCraft: registered {} Skyrim gear items", ITEMS.size());
	}

	private static void registerTier(GearTiers.Tier tier) {
		TagKey<Item> repair = TagKey.create(Registries.ITEM, id(tier.id() + "_repair_materials"));
		ToolMaterial tool = new ToolMaterial(incorrectFor(tier.mines()), tier.durability(), tier.speed(), tier.attackBonus(), tier.enchantability(), repair);
		ResourceKey<EquipmentAsset> asset = ResourceKey.create(EquipmentAssets.ROOT_ID, id(tier.id()));
		Map<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
		defense.put(ArmorType.BOOTS, tier.boots());
		defense.put(ArmorType.LEGGINGS, tier.greaves());
		defense.put(ArmorType.CHESTPLATE, tier.cuirass());
		defense.put(ArmorType.HELMET, tier.helmet());
		defense.put(ArmorType.BODY, tier.cuirass());
		ArmorMaterial armor = new ArmorMaterial(tier.armorDurability(), defense, tier.enchantability(), equipSound(tier.equipSound()), tier.toughness(),
			tier.knockbackResistance(), repair, asset);

		UnaryOperator<Item.Properties> extra = tier.fireproof() ? Item.Properties::fireResistant : p -> p;
		gear(tier, "sword", false, p -> extra.apply(p.sword(tool, GearTiers.SWORD_DAMAGE, GearTiers.SWORD_SPEED)));
		gear(tier, "war_axe", false, p -> extra.apply(p.axe(tool, GearTiers.WAR_AXE_DAMAGE, GearTiers.WAR_AXE_SPEED)));
		gear(tier, "helmet", true, p -> extra.apply(p.humanoidArmor(armor, ArmorType.HELMET)));
		gear(tier, "cuirass", true, p -> extra.apply(p.humanoidArmor(armor, ArmorType.CHESTPLATE)));
		gear(tier, "greaves", true, p -> extra.apply(p.humanoidArmor(armor, ArmorType.LEGGINGS)));
		gear(tier, "boots", true, p -> extra.apply(p.humanoidArmor(armor, ArmorType.BOOTS)));
	}

	private static void gear(GearTiers.Tier tier, String piece, boolean isArmor, UnaryOperator<Item.Properties> properties) {
		Item item = register(GearTiers.itemId(tier, piece), properties);
		TIER_OF.put(item, tier);
		IS_ARMOR.put(item, isArmor);
	}

	private static Item register(String name, UnaryOperator<Item.Properties> properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, new Item(properties.apply(new Item.Properties().setId(key))));
		ITEMS.put(name, item);
		return item;
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, path);
	}

	private static TagKey<Block> incorrectFor(String mines) {
		return switch (mines) {
			case "netherite" -> BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
			case "diamond" -> BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
			default -> BlockTags.INCORRECT_FOR_IRON_TOOL;
		};
	}

	private static Holder<SoundEvent> equipSound(String sound) {
		return switch (sound) {
			case "gold" -> SoundEvents.ARMOR_EQUIP_GOLD;
			case "diamond" -> SoundEvents.ARMOR_EQUIP_DIAMOND;
			case "netherite" -> SoundEvents.ARMOR_EQUIP_NETHERITE;
			default -> SoundEvents.ARMOR_EQUIP_IRON;
		};
	}

	/** The gear tier of one of this mod's swords, war axes or armor pieces, or null. */
	public static GearTiers.@Nullable Tier tier(Item item) {
		return TIER_OF.get(item);
	}

	/** Skyrim Heavy Armor (true) or Light Armor (false) for this mod's armor, null for anything else. */
	public static @Nullable Boolean heavyArmor(Item item) {
		GearTiers.Tier tier = TIER_OF.get(item);
		return tier != null && Boolean.TRUE.equals(IS_ARMOR.get(item)) ? tier.heavy() : null;
	}
}
