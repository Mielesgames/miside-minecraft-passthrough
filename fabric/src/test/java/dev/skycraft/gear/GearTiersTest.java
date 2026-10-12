package dev.skycraft.gear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class GearTiersTest {
	private static final List<GearTiers.Tier> T = GearTiers.TIERS;

	@Test
	void tiersAreInSkyrimOrder() {
		assertEquals(List.of("iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric"), T.stream().map(GearTiers.Tier::id).toList());
	}

	@Test
	void everyTierIsStrictlyBetterThanTheOneBefore() {
		for (int i = 1; i < T.size(); i++) {
			GearTiers.Tier a = T.get(i - 1), b = T.get(i);
			String at = a.id() + " -> " + b.id();
			assertTrue(b.swordDamage() > a.swordDamage(), "sword damage " + at);
			assertTrue(b.warAxeDamage() > a.warAxeDamage(), "war axe damage " + at);
			assertTrue(b.durability() > a.durability(), "weapon durability " + at);
			assertTrue(b.speed() > a.speed(), "axe speed " + at);
			assertTrue(b.armorDurability() > a.armorDurability(), "armor durability " + at);
			assertTrue(b.totalDefense() >= a.totalDefense(), "armor points " + at);
			assertTrue(b.totalDefense() + b.toughness() > a.totalDefense() + a.toughness(), "armor points plus toughness " + at);
			assertTrue(b.smithingWorth() > a.smithingWorth(), "smithing worth " + at);
		}
	}

	@Test
	void ironIsAboutVanillaIronAndDaedricJustAboveNetherite() {
		GearTiers.Tier iron = T.getFirst(), daedric = T.getLast();
		assertEquals(6.0F, iron.swordDamage(), 1.0E-4F);  // vanilla iron sword
		assertEquals(250, iron.durability());
		assertEquals(15, iron.totalDefense());  // vanilla iron armor
		float netheriteSword = 8.0F, netheriteDefense = 20.0F, netheriteToughness = 3.0F;
		assertTrue(daedric.swordDamage() > netheriteSword && daedric.swordDamage() < netheriteSword + 1.0F, "daedric sword " + daedric.swordDamage());
		assertTrue(daedric.totalDefense() + daedric.toughness() > netheriteDefense + netheriteToughness);
		assertTrue(daedric.durability() > 2031);
	}

	@Test
	void statsAreSane() {
		for (GearTiers.Tier t : T) {
			assertTrue(t.helmet() > 0 && t.boots() > 0 && t.cuirass() >= t.greaves() && t.greaves() >= t.helmet(), t.id() + " armor points");
			assertTrue(t.enchantability() > 0, t.id());
			assertTrue(List.of("iron", "diamond", "netherite").contains(t.mines()), t.id() + " mines");
			assertTrue(List.of("iron", "gold", "diamond", "netherite").contains(t.equipSound()), t.id() + " sound");
			assertTrue(t.ingot().startsWith("minecraft:") || GearTiers.MATERIALS.contains(t.ingot().substring("skycraft:".length())), t.id() + " ingot");
			assertTrue(t.knockbackResistance() >= 0.0F && t.knockbackResistance() < 0.5F, t.id());
		}
	}

	@Test
	void itemIdsAreUniqueAndNamed() {
		List<String> ids = GearTiers.allItemIds();
		assertEquals(GearTiers.MATERIALS.size() + T.size() * GearTiers.PIECES.size(), ids.size());
		assertEquals(ids.size(), new HashSet<>(ids).size());
		assertEquals(GearTiers.PIECES.size(), GearTiers.PIECE_NAMES.size());
		assertEquals(GearTiers.MATERIALS.size(), GearTiers.MATERIAL_NAMES.size());
		for (String id : ids) {
			assertTrue(id.matches("[a-z0-9_]+"), id);
		}
	}
}
