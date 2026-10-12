package dev.skycraft.gear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every item {@link SkyGear} registers has its model, texture, name, recipe and vanilla item tag, and
 * every tier its armor layers and repair tag. Reads the mod's resources from the test classpath (no Minecraft).
 * Regenerate with tools/gen_gear_data.py and tools/gen_gear_textures.py.
 */
class GearResourcesTest {
	private static final List<String> MATERIAL_RECIPES = List.of("steel_ingot", "leather_strips", "orichalcum_ingot", "dwarven_metal_ingot", "ebony_ingot",
		"refined_moonstone_from_smelting", "refined_moonstone_from_blasting", "refined_malachite_from_smelting", "refined_malachite_from_blasting");

	private static String text(String path) {
		try (InputStream in = GearResourcesTest.class.getResourceAsStream("/" + path)) {
			assertNotNull(in, "missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(path, e);
		}
	}

	private static void exists(String path) {
		text(path);
	}

	@Test
	void everyItemHasModelTextureAndName() {
		String lang = text("assets/skycraft/lang/en_us.json");
		for (String id : GearTiers.allItemIds()) {
			String def = text("assets/skycraft/items/" + id + ".json");
			assertTrue(def.contains("\"skycraft:item/" + id + "\""), id + " item definition");
			String model = text("assets/skycraft/models/item/" + id + ".json");
			assertTrue(model.contains("\"skycraft:item/" + id + "\""), id + " model texture");
			exists("assets/skycraft/textures/item/" + id + ".png");
			assertTrue(lang.contains("\"item.skycraft." + id + "\""), id + " name");
		}
		assertTrue(lang.contains("\"itemGroup.skycraft.gear\""));
	}

	@Test
	void gearNamesFollowTheTierAndNoneIsAVanillaName() {
		String lang = text("assets/skycraft/lang/en_us.json");
		Set<String> vanilla = Set.of("Iron Sword", "Iron Axe", "Iron Helmet", "Iron Chestplate", "Iron Leggings", "Iron Boots");
		for (GearTiers.Tier tier : GearTiers.TIERS) {
			for (int i = 0; i < GearTiers.PIECES.size(); i++) {
				String id = GearTiers.itemId(tier, GearTiers.PIECES.get(i));
				String name = tier.name() + " " + GearTiers.PIECE_NAMES.get(i);
				assertTrue(lang.contains("\"item.skycraft." + id + "\": \"" + name + "\""), id + " should be named " + name);
				assertFalse(vanilla.contains(name), id + " has a vanilla item's name: " + name);
			}
		}
		assertTrue(lang.contains("\"item.skycraft.iron_sword\": \"Skyrim Iron Sword\""));
	}

	@Test
	void everyGearPieceIsInItsVanillaTag() {
		assertEquals(GearTiers.PIECES.size(), GearTiers.PIECE_TAGS.size());
		for (int i = 0; i < GearTiers.PIECES.size(); i++) {
			String tag = text("data/minecraft/tags/item/" + GearTiers.PIECE_TAGS.get(i) + ".json");
			assertFalse(tag.contains("\"replace\": true"), GearTiers.PIECE_TAGS.get(i) + " must not replace vanilla's tag");
			for (GearTiers.Tier tier : GearTiers.TIERS) {
				String id = GearTiers.itemId(tier, GearTiers.PIECES.get(i));
				assertTrue(tag.contains("\"skycraft:" + id + "\""), id + " in minecraft:" + GearTiers.PIECE_TAGS.get(i));
			}
		}
	}

	@Test
	void everyTierHasArmorLayersAndRepairTag() {
		for (GearTiers.Tier tier : GearTiers.TIERS) {
			String equipment = text("assets/skycraft/equipment/" + tier.id() + ".json");
			assertTrue(equipment.contains("\"humanoid\"") && equipment.contains("\"humanoid_leggings\""), tier.id());
			assertTrue(equipment.contains("\"skycraft:" + tier.id() + "\""), tier.id());
			exists("assets/skycraft/textures/entity/equipment/humanoid/" + tier.id() + ".png");
			exists("assets/skycraft/textures/entity/equipment/humanoid_leggings/" + tier.id() + ".png");
			assertTrue(text("data/skycraft/tags/item/" + tier.id() + "_repair_materials.json").contains("\"" + tier.ingot() + "\""), tier.id() + " repair tag");
		}
	}

	@Test
	void everyGearItemHasARecipeWithKnownIngredients() {
		List<String> recipes = new ArrayList<>(GearTiers.gearItemIds());
		recipes.addAll(MATERIAL_RECIPES);
		Pattern ref = Pattern.compile("\"skycraft:([a-z0-9_/]+)\"");
		for (String name : recipes) {
			String json = text("data/skycraft/recipe/" + name + ".json");
			Matcher m = ref.matcher(json);
			while (m.find()) {
				assertTrue(GearTiers.allItemIds().contains(m.group(1)), name + " uses unknown skycraft:" + m.group(1));
			}
		}
		for (GearTiers.Tier tier : GearTiers.TIERS) {
			String sword = text("data/skycraft/recipe/" + tier.id() + "_sword.json");
			assertTrue(sword.contains("\"" + tier.ingot() + "\"") && sword.contains("\"skycraft:leather_strips\""), tier.id() + " sword recipe");
		}
		assertTrue(text("data/skycraft/recipe/daedric_cuirass.json").contains("\"skycraft:daedra_heart\""));
	}
}
