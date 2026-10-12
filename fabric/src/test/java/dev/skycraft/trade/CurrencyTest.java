package dev.skycraft.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurrencyTest {
	private static final Set<String> COPPER_GOLD = Set.of(Currency.COPPER, Currency.NUGGET, Currency.GOLD);
	private static final Set<String> ALL = Set.copyOf(Currency.ITEMS);

	private static int total(List<Currency.Pay> pay) {
		return pay.stream().mapToInt(Currency.Pay::coins).sum();
	}

	@Test
	void values() {
		assertEquals(1, Currency.value("minecraft:copper_ingot"));
		assertEquals(2, Currency.value("minecraft:gold_nugget"));
		assertEquals(18, Currency.value("minecraft:gold_ingot"));
		assertEquals(24, Currency.value("minecraft:emerald"));
		assertEquals(96, Currency.value("minecraft:diamond"));
		assertEquals(0, Currency.value("minecraft:bread"));
	}

	@Test
	void keywords() {
		assertEquals(List.of(Currency.NUGGET, Currency.GOLD), Currency.itemsFor("Gold"));
		assertEquals(List.of(Currency.COPPER), Currency.itemsFor("copper"));
		assertNull(Currency.itemsFor("septim"));
	}

	@Test
	void exactPricesUseLargestCurrency() {
		assertEquals(List.of(new Currency.Pay(Currency.GOLD, 2)), Currency.cost(36, COPPER_GOLD));
		assertEquals(List.of(new Currency.Pay(Currency.DIAMOND, 1)), Currency.cost(96, ALL));
		assertEquals(List.of(new Currency.Pay(Currency.COPPER, 7)), Currency.cost(7, Set.of(Currency.COPPER)));
	}

	@Test
	void remainderGoesToSmallerCurrency() {
		// 40 = 2 gold ingots + 4 coins = 2 nuggets
		assertEquals(List.of(new Currency.Pay(Currency.GOLD, 2), new Currency.Pay(Currency.NUGGET, 2)), Currency.cost(40, COPPER_GOLD));
		// 17 = 8 nuggets + 1 copper
		assertEquals(List.of(new Currency.Pay(Currency.NUGGET, 8), new Currency.Pay(Currency.COPPER, 1)), Currency.cost(17, COPPER_GOLD));
	}

	@Test
	void remainderRoundsUp() {
		// Gold only: 41 = 2 ingots + 5 coins, rounded up to 3 nuggets
		List<Currency.Pay> pay = Currency.cost(41, Set.of(Currency.NUGGET, Currency.GOLD));
		assertEquals(List.of(new Currency.Pay(Currency.GOLD, 2), new Currency.Pay(Currency.NUGGET, 3)), pay);
		// Emerald only: 30 rounds up to 2 emeralds
		assertEquals(List.of(new Currency.Pay(Currency.EMERALD, 2)), Currency.cost(30, Set.of(Currency.EMERALD)));
		// Below every allowed currency: one of the smallest
		assertEquals(List.of(new Currency.Pay(Currency.NUGGET, 1)), Currency.cost(1, Set.of(Currency.NUGGET, Currency.GOLD)));
	}

	@Test
	void costIsNeverBelowPriceAndAtMostTwoStacks() {
		List<Set<String>> sets = List.of(ALL, COPPER_GOLD, Set.of(Currency.COPPER), Set.of(Currency.EMERALD, Currency.DIAMOND),
			Set.of(Currency.NUGGET, Currency.GOLD, Currency.EMERALD, Currency.DIAMOND), Set.of(Currency.COPPER, Currency.EMERALD));
		for (Set<String> allowed : sets) {
			int largest = allowed.stream().mapToInt(Currency::value).max().orElse(1);
			for (int coins = 1; coins <= 1500; coins++) {
				List<Currency.Pay> pay = Currency.cost(coins, allowed);
				assertTrue(pay.size() >= 1 && pay.size() <= 2, coins + " " + pay);
				for (Currency.Pay p : pay) {
					assertTrue(p.count() >= 1 && p.count() <= 64, coins + " " + pay);
					assertTrue(allowed.contains(p.item()), coins + " " + pay);
				}
				if (coins <= 64 * largest) {
					// Only prices past a full stack of the largest currency are capped.
					assertTrue(total(pay) >= coins, coins + " " + pay);
				}
				// Rounding never costs more than one of the largest currency used.
				assertTrue(total(pay) - coins < Currency.value(pay.getFirst().item()) || pay.getFirst().count() == 1, coins + " " + pay);
			}
		}
	}

	@Test
	void totalShowsWhenAPriceIsCapped() {
		assertEquals(40, Currency.total(List.of(new Currency.Pay(Currency.GOLD, 2), new Currency.Pay(Currency.NUGGET, 2))));
		// A copper town can't pay for a 200-coin diamond: the cost is capped at 64 copper, so
		// SkyTrade must not offer it (it would be bought for 64 and spent as 96 elsewhere).
		List<Currency.Pay> diamondInCopper = Currency.cost(200, Set.of(Currency.COPPER));
		assertEquals(List.of(new Currency.Pay(Currency.COPPER, 64)), diamondInCopper);
		assertTrue(Currency.total(diamondInCopper) < 200);
		assertTrue(Currency.total(Currency.cost(200, ALL)) >= 200);
	}

	@Test
	void nothingAllowedMeansCopper() {
		assertEquals(List.of(new Currency.Pay(Currency.COPPER, 5)), Currency.cost(5, Set.of()));
		assertEquals(new Currency.Pay(Currency.COPPER, 5), Currency.payout(5, Set.of("minecraft:bread")));
	}

	@Test
	void payoutRoundsDownInOneStack() {
		assertEquals(new Currency.Pay(Currency.GOLD, 2), Currency.payout(40, COPPER_GOLD));
		assertEquals(new Currency.Pay(Currency.NUGGET, 8), Currency.payout(17, COPPER_GOLD));
		assertEquals(new Currency.Pay(Currency.COPPER, 1), Currency.payout(1, COPPER_GOLD));
		assertEquals(new Currency.Pay(Currency.EMERALD, 1), Currency.payout(30, Set.of(Currency.EMERALD, Currency.DIAMOND)));
	}

	@Test
	void payoutIsAtLeastOneOfTheSmallest() {
		assertEquals(new Currency.Pay(Currency.EMERALD, 1), Currency.payout(3, Set.of(Currency.EMERALD, Currency.DIAMOND)));
		assertEquals(new Currency.Pay(Currency.NUGGET, 1), Currency.payout(0, Set.of(Currency.NUGGET, Currency.GOLD)));
	}

	@Test
	void payoutCapsAtAStack() {
		assertEquals(new Currency.Pay(Currency.COPPER, 64), Currency.payout(500, Set.of(Currency.COPPER)));
		for (int coins = 1; coins <= 2000; coins++) {
			Currency.Pay p = Currency.payout(coins, ALL);
			assertTrue(p.count() >= 1 && p.count() <= 64, coins + " " + p);
			assertTrue(p.coins() <= coins, coins + " " + p);
		}
	}
}
