package dev.skycraft.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import org.junit.jupiter.api.Test;

/** Which key opens which world, the order a key's checks are told in, and the Time-Wound's rule. */
class PortalKeysTest {
	@Test
	void eachKeyOpensItsWorld() {
		assertEquals(PortalKeys.NETHER, PortalKeys.key("skycraft:daedra_heart").dimension());
		assertEquals(PortalKeys.TAMRIEL_WILDS, PortalKeys.key("skycraft:tonal_key").dimension());
		assertNull(PortalKeys.key("skycraft:seamstone")); // not a key: it opens any portal onto anywhere
		assertNull(PortalKeys.key("skycraft:filled_soul_gem"));
		assertNull(PortalKeys.key("minecraft:obsidian"));
		// Home has no key (the Time-Wound and an Elder Scroll open it).
		assertTrue(PortalKeys.KEYS.stream().noneMatch(k -> k.dimension().equals(PortalKeys.HOME)));
		assertEquals("the Nether", PortalKeys.worldName(PortalKeys.NETHER));
		assertEquals("Tamriel Wilds: the Dwemer tower", PortalKeys.arrivalName(PortalKeys.TAMRIEL_WILDS));
	}

	@Test
	void checksComeInOrder() {
		assertEquals(PortalKeys.Use.OPEN, PortalKeys.check(true, true, true, true, true, true, false));
		assertEquals(PortalKeys.Use.OFF, PortalKeys.check(false, false, false, false, false, false, true));
		assertEquals(PortalKeys.Use.NOT_MIRROR, PortalKeys.check(true, false, false, true, true, true, false));
		assertEquals(PortalKeys.Use.VIEW_OFF, PortalKeys.check(true, false, true, true, true, true, false));
		assertEquals(PortalKeys.Use.NOT_HOST, PortalKeys.check(true, true, true, false, true, true, false));
		assertEquals(PortalKeys.Use.SKYRIM_AWAY, PortalKeys.check(true, true, true, true, false, true, false));
		assertEquals(PortalKeys.Use.NO_DIMENSION, PortalKeys.check(true, true, true, true, true, false, false));
		assertEquals(PortalKeys.Use.ALREADY_LEADS, PortalKeys.check(true, true, true, true, true, true, true));
	}

	@Test
	void theTimeWoundIsAtTheSummit() {
		// MQ206TimeWoundRef: 56044, -47760, 36055 Skyrim units in Tamriel.
		assertEquals(800.63, TimeWound.X, 0.01);
		assertEquals(515.07, TimeWound.Y, 0.01);
		assertEquals(682.29, TimeWound.Z, 0.01);
		assertTrue(TimeWound.near(TimeWound.TAMRIEL, 800.6, 513.0, 682.3));
		assertTrue(TimeWound.near(TimeWound.TAMRIEL, 800.6 + 11.0, 513.0, 682.3 + 11.0));
		assertFalse(TimeWound.near(TimeWound.TAMRIEL, 800.6 + 12.0, 513.0, 682.3 + 12.0)); // 17 blocks off
		assertFalse(TimeWound.near(TimeWound.TAMRIEL, 800.6, 480.0, 682.3)); // the mountain's flank below
		assertFalse(TimeWound.near(0x0016BB4, 800.6, 513.0, 682.3)); // another world at the same coordinates
		double[] here = { 801.0, 514.5, 683.0 };
		assertEquals(TimeWound.Opening.OPENS, TimeWound.check(TimeWound.TAMRIEL, here, Proto.CARRY_ELDER_SCROLL, false));
		assertEquals(TimeWound.Opening.NO_SCROLL, TimeWound.check(TimeWound.TAMRIEL, here, 0, false));
		assertEquals(TimeWound.Opening.ALREADY_LEADS, TimeWound.check(TimeWound.TAMRIEL, here, Proto.CARRY_ELDER_SCROLL, true));
		assertEquals(TimeWound.Opening.NOT_HERE, TimeWound.check(TimeWound.TAMRIEL, new double[] { 0.0, 64.0, 0.0 }, Proto.CARRY_ELDER_SCROLL, false));
	}
}
