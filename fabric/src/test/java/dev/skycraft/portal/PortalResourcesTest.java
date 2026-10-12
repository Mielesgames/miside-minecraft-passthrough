package dev.skycraft.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The network's items (soul gems, the tonal key, Seamstone) have their item definition, model, texture and
 * name, the tonal key its recipe, and the mixins that turn vanilla Nether travel into the network and light
 * portals in every world are in the mixin config. Reads the mod's resources from the test classpath (no
 * Minecraft). Textures come from tools/gen_portal_textures.py.
 */
class PortalResourcesTest {
	private static String text(String path) {
		try (InputStream in = PortalResourcesTest.class.getResourceAsStream("/" + path)) {
			assertNotNull(in, "missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(path, e);
		}
	}

	@Test
	void everyGemHasModelTextureAndName() {
		String lang = text("assets/skycraft/lang/en_us.json");
		for (String id : PortalStore.ITEM_IDS) {
			assertTrue(text("assets/skycraft/items/" + id + ".json").contains("\"skycraft:item/" + id + "\""), id + " item definition");
			assertTrue(text("assets/skycraft/models/item/" + id + ".json").contains("\"skycraft:item/" + id + "\""), id + " model texture");
			assertFalse(text("assets/skycraft/textures/item/" + id + ".png").isEmpty(), id + " texture");
			assertTrue(lang.contains("\"item.skycraft." + id + "\""), id + " name");
		}
	}

	@Test
	void theNetherPortalMixinIsRegistered() {
		assertTrue(text("skycraft.mixins.json").contains("\"NetherPortalBlockMixin\""));
		assertTrue(text("skycraft.mixins.json").contains("\"BaseFireBlockMixin\""));
	}

	@Test
	void theTonalKeyIsCraftedFromDwemerMetalRedstoneAndAmethyst() {
		String recipe = text("data/skycraft/recipe/tonal_key.json");
		assertTrue(recipe.contains("\"skycraft:dwarven_metal_ingot\""));
		assertTrue(recipe.contains("\"minecraft:redstone\""));
		assertTrue(recipe.contains("\"minecraft:amethyst_shard\""));
		assertTrue(recipe.contains("\"id\": \"skycraft:tonal_key\""));
		assertTrue(text("assets/skycraft/lang/en_us.json").contains("\"item.skycraft.seamstone\": \"Seamstone\""));
	}
}
