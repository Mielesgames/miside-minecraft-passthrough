package dev.skycraft.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemandTest {
	private static final String BREAD = "minecraft:bread";

	@Test
	void startsAtBasePrice() {
		Demand d = new Demand();
		assertEquals(1.0, d.factor("Whiterun", BREAD), 1e-9);
		assertEquals(40, d.apply(40, "Whiterun", BREAD));
		assertTrue(d.isEmpty());
	}

	@Test
	void buyingRaisesAndSellingLowers() {
		Demand d = new Demand();
		d.bought("Whiterun", BREAD);
		d.bought("Whiterun", BREAD);
		assertEquals(1.10, d.factor("Whiterun", BREAD), 1e-9);
		assertEquals(44, d.apply(40, "Whiterun", BREAD));
		d.sold("Whiterun", BREAD);
		d.sold("Whiterun", BREAD);
		d.sold("Whiterun", BREAD);
		assertEquals(0.95, d.factor("Whiterun", BREAD), 1e-9);
	}

	@Test
	void perTownAndItemIgnoringTownCase() {
		Demand d = new Demand();
		d.bought("Whiterun", BREAD);
		assertEquals(1.05, d.factor("WHITERUN", BREAD), 1e-9);
		assertEquals(1.0, d.factor("Riften", BREAD), 1e-9);
		assertEquals(1.0, d.factor("Whiterun", "minecraft:apple"), 1e-9);
	}

	@Test
	void clampedBetweenHalfAndOneAndAHalf() {
		Demand d = new Demand();
		for (int i = 0; i < 40; i++) {
			d.bought("Riften", BREAD);
			d.sold("Solitude", BREAD);
		}
		assertEquals(1.5, d.factor("Riften", BREAD), 1e-9);
		assertEquals(0.5, d.factor("Solitude", BREAD), 1e-9);
		// Clamped counters come back as soon as trading turns.
		d.sold("Riften", BREAD);
		assertEquals(1.45, d.factor("Riften", BREAD), 1e-9);
	}

	@Test
	void priceIsAtLeastOneCoin() {
		Demand d = new Demand();
		for (int i = 0; i < 20; i++) {
			d.sold("Riften", BREAD);
		}
		assertEquals(1, d.apply(1, "Riften", BREAD));
	}

	@Test
	void decayStepsBackToBase() {
		Demand d = new Demand();
		d.bought("Whiterun", BREAD);
		d.bought("Whiterun", BREAD);
		d.sold("Riften", BREAD);
		d.decay();
		assertEquals(1.05, d.factor("Whiterun", BREAD), 1e-9);
		assertEquals(1.0, d.factor("Riften", BREAD), 1e-9);
		d.decay();
		assertEquals(1.0, d.factor("Whiterun", BREAD), 1e-9);
		assertTrue(d.isEmpty());
	}
}
