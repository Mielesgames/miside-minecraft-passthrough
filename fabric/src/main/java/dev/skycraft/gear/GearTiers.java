package dev.skycraft.gear;

import java.util.ArrayList;
import java.util.List;

/**
 * Stats for the Skyrim gear sets, one row per tier, weakest first. Everything that balances the
 * gear lives in this table; {@link SkyGear} turns it into Minecraft items.
 *
 * <p>Pure Java on purpose (no Minecraft classes), so the table is unit tested without the game.
 *
 * <p>For reference, vanilla Minecraft 26.3: iron tools are durability 250, speed 6, damage bonus 2;
 * diamond 1561, 8, 3; netherite 2031, 9, 4. A sword hits for 1 + bonus + 3 (iron 6, netherite 8).
 * Iron armor is durability factor 15, defense 2/5/6/2 (boots, leggings, chest, helmet), no
 * toughness; netherite is 37, 3/6/8/3, toughness 3, knockback resistance 0.1.
 */
public final class GearTiers {
	/** Added to the tier's damage bonus for a sword, and its attack speed modifier. Same as vanilla swords. */
	public static final float SWORD_DAMAGE = 3.0F;
	public static final float SWORD_SPEED = -2.4F;
	/** War axes hit harder and swing slower than swords (vanilla iron axe: 6 and -3.1). */
	public static final float WAR_AXE_DAMAGE = 5.5F;
	public static final float WAR_AXE_SPEED = -3.0F;

	/**
	 * One gear tier.
	 *
	 * @param id             item id prefix, also the equipment asset name ("daedric" gives daedric_sword)
	 * @param name           English name prefix for the lang file
	 * @param durability     weapon durability (uses)
	 * @param speed          mining speed of the war axe
	 * @param attackBonus    weapon damage bonus, added to the sword and war axe base damage
	 * @param enchantability weapon and armor enchantability
	 * @param mines          which vanilla tool tier's blocks it can mine: "iron", "diamond" or "netherite"
	 * @param armorDurability armor durability factor (vanilla multiplies it per piece)
	 * @param boots          armor points for boots
	 * @param greaves        armor points for greaves (the leggings slot)
	 * @param cuirass        armor points for the cuirass (the chestplate slot)
	 * @param helmet         armor points for the helmet
	 * @param toughness      armor toughness per piece
	 * @param knockbackResistance knockback resistance per piece
	 * @param heavy          Skyrim Heavy Armor (true) or Light Armor (false), for skill training
	 * @param equipSound     vanilla armor equip sound to reuse: "iron", "gold", "diamond", "netherite"
	 * @param fireproof      survives fire and lava as a dropped item, like netherite
	 * @param ingot          item that crafts and repairs this tier
	 * @param smithingWorth  Skyrim Smithing experience for crafting one piece
	 */
	public record Tier(
		String id, String name, int durability, float speed, float attackBonus, int enchantability, String mines, int armorDurability, int boots,
		int greaves, int cuirass, int helmet, float toughness, float knockbackResistance, boolean heavy, String equipSound, boolean fireproof,
		String ingot, float smithingWorth
	) {
		public float swordDamage() {
			return 1.0F + this.attackBonus + SWORD_DAMAGE;
		}

		public float warAxeDamage() {
			return 1.0F + this.attackBonus + WAR_AXE_DAMAGE;
		}

		public int totalDefense() {
			return this.boots + this.greaves + this.cuirass + this.helmet;
		}
	}

	/**
	 * Weakest first. Iron is about vanilla iron, ebony about netherite, daedric just above netherite. The iron tier is named
	 * "Skyrim Iron" so its sword, helmet and boots don't share vanilla's names.
	 */
	public static final List<Tier> TIERS = List.of(
		// id, name, durability, speed, attackBonus, enchantability, mines, armorDurability, boots, greaves, cuirass, helmet, toughness,
		// knockbackResistance, heavy, equipSound, fireproof, ingot, smithingWorth
		new Tier("iron", "Skyrim Iron", 250, 6.0F, 2.0F, 14, "iron", 15, 2, 5, 6, 2, 0.0F, 0.0F, true, "iron", false, "minecraft:iron_ingot", 40.0F),
		new Tier("steel", "Steel", 400, 6.5F, 2.4F, 12, "iron", 18, 2, 5, 7, 2, 0.5F, 0.0F, true, "iron", false, "skycraft:steel_ingot", 50.0F),
		new Tier("orcish", "Orcish", 600, 7.0F, 2.8F, 10, "iron", 21, 2, 6, 7, 2, 1.0F, 0.0F, true, "iron", false, "skycraft:orichalcum_ingot", 60.0F),
		new Tier("dwarven", "Dwarven", 850, 7.5F, 3.1F, 12, "diamond", 24, 3, 6, 7, 2, 1.5F, 0.0F, true, "iron", false, "skycraft:dwarven_metal_ingot", 70.0F),
		new Tier("elven", "Elven", 1100, 8.0F, 3.4F, 18, "diamond", 27, 3, 6, 7, 3, 1.5F, 0.0F, false, "gold", false, "skycraft:refined_moonstone", 80.0F),
		new Tier("glass", "Glass", 1350, 8.5F, 3.7F, 16, "diamond", 30, 3, 6, 8, 3, 2.0F, 0.0F, false, "diamond", false, "skycraft:refined_malachite", 100.0F),
		new Tier("ebony", "Ebony", 1800, 9.0F, 4.0F, 14, "netherite", 35, 3, 6, 8, 3, 3.0F, 0.1F, true, "netherite", true, "skycraft:ebony_ingot", 130.0F),
		new Tier("daedric", "Daedric", 2200, 9.5F, 4.5F, 15, "netherite", 40, 3, 7, 8, 3, 3.5F, 0.15F, true, "netherite", true, "skycraft:ebony_ingot", 160.0F)
	);

	/** Gear pieces per tier: item id suffix and English name suffix. */
	public static final List<String> PIECES = List.of("sword", "war_axe", "helmet", "cuirass", "greaves", "boots");
	public static final List<String> PIECE_NAMES = List.of("Sword", "War Axe", "Helmet", "Cuirass", "Greaves", "Boots");
	/**
	 * The vanilla item tag (minecraft:) each piece is in, like vanilla gear: enchanting and the anvil go by the
	 * enchantable/* tags built from these, a sword's sweep needs minecraft:swords, and SkyrimActorEntity picks the
	 * Skyrim impact from swords and axes.
	 */
	public static final List<String> PIECE_TAGS = List.of("swords", "axes", "head_armor", "chest_armor", "leg_armor", "foot_armor");

	/** New crafting materials: item id and English name. */
	public static final List<String> MATERIALS = List.of("steel_ingot", "orichalcum_ingot", "dwarven_metal_ingot", "refined_moonstone", "refined_malachite",
		"ebony_ingot", "daedra_heart", "leather_strips");
	public static final List<String> MATERIAL_NAMES = List.of("Steel Ingot", "Orichalcum Ingot", "Dwarven Metal Ingot", "Refined Moonstone",
		"Refined Malachite", "Ebony Ingot", "Daedra Heart", "Leather Strips");

	private GearTiers() {
	}

	public static String itemId(Tier tier, String piece) {
		return tier.id() + "_" + piece;
	}

	/** Every gear item id (no namespace), tier by tier in {@link #PIECES} order. */
	public static List<String> gearItemIds() {
		List<String> out = new ArrayList<>();
		for (Tier tier : TIERS) {
			for (String piece : PIECES) {
				out.add(itemId(tier, piece));
			}
		}
		return out;
	}

	/** Every item this mod adds (no namespace): materials first, then gear. */
	public static List<String> allItemIds() {
		List<String> out = new ArrayList<>(MATERIALS);
		out.addAll(gearItemIds());
		return out;
	}
}
