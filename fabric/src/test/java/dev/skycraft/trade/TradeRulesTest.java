package dev.skycraft.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradeRulesTest {
	private static TradeRules defaults() {
		List<String> problems = new ArrayList<>();
		TradeRules rules = TradeRules.defaults(problems);
		assertTrue(problems.isEmpty(), problems.toString());
		return rules;
	}

	private static Map<String, Integer> basePrices(TradeRules rules, String category) {
		Map<String, Integer> out = new HashMap<>();
		for (TradeRules.Trade t : rules.trades()) {
			if (t.category().equals(category)) {
				out.put((t.sell() ? "sell " : "buy ") + t.count() + " " + t.item(), t.price());
			}
		}
		return out;
	}

	@Test
	void defaultsParseCleanly() {
		TradeRules rules = defaults();
		assertTrue(rules.trades().size() >= 50, "trades " + rules.trades().size());
		assertEquals(18, rules.towns().size());
		Set<String> categories = new HashSet<>();
		for (TradeRules.Trade t : rules.trades()) {
			categories.add(t.category());
		}
		assertEquals(Set.copyOf(TradeRules.CATEGORIES), categories);
	}

	@Test
	void defaultItemsAreVanillaIds() {
		for (TradeRules.Trade t : defaults().trades()) {
			assertTrue(t.item().matches("minecraft:[a-z_]+"), t.item());
			assertTrue(t.count() >= 1 && t.count() <= 64, t.toString());
			assertTrue(t.price() >= 1, t.toString());
		}
	}

	@Test
	void defaultBuyPricesStayBelowSellPrices() {
		// Per item: what merchants pay is less than what they charge, so a merchant can't be farmed.
		Map<String, Double> sellEach = new HashMap<>();
		for (TradeRules.Trade t : defaults().trades()) {
			if (t.sell()) {
				sellEach.merge(t.item(), (double) t.price() / t.count(), Math::min);
			}
		}
		for (TradeRules.Trade t : defaults().trades()) {
			Double sell = sellEach.get(t.item());
			if (!t.sell() && sell != null) {
				assertTrue((double) t.price() / t.count() < sell, t.toString());
			}
		}
	}

	@Test
	void defaultsNeverBuyCurrencyOrSellItCheap() {
		TradeRules rules = defaults();
		for (TradeRules.Trade t : rules.trades()) {
			if (!t.sell()) {
				assertEquals(0, Currency.value(t.item()), t.toString());
			} else if (Currency.value(t.item()) > 0) {
				// Nobody sells currency to merchants, so its price never drops below the cheapest town's.
				double cheapest = 1.0;
				for (TradeRules.Town town : rules.towns().values()) {
					cheapest = Math.min(cheapest, town.multiplier(t.category()));
				}
				assertTrue(t.price() * cheapest / t.count() > Currency.value(t.item()), t.toString());
			}
		}
	}

	@Test
	void currencyIsOnlyOfferedWhereTheTownCanPayItsFullPrice() {
		// SkyTrade skips a sell offer whose cost can't cover its price. Without that, Rorikstead
		// (copper only) sold a 200-coin diamond for 64 copper ingots, the most two stacks allow.
		TradeRules rules = defaults();
		int offered = 0;
		for (TradeRules.Town town : rules.towns().values()) {
			for (TradeRules.Trade t : rules.trades()) {
				if (!t.sell() || Currency.value(t.item()) == 0) {
					continue;
				}
				int price = Math.max(1, (int) Math.round(t.price() * town.multiplier(t.category())));
				List<Currency.Pay> pay = Currency.cost(price, TradeRules.currenciesFor(town, t.item()));
				if (Currency.total(pay) >= price) {
					offered++;
					assertTrue((double) Currency.total(pay) / t.count() > Currency.value(t.item()), town.name() + " " + t + " " + pay);
				}
			}
		}
		assertTrue(offered > 0);
		TradeRules.Town rorikstead = rules.town("Rorikstead");
		assertTrue(Currency.total(Currency.cost(200, TradeRules.currenciesFor(rorikstead, Currency.DIAMOND))) < 200);
	}

	@Test
	void categoriesFromMask() {
		Set<String> any = TradeRules.categoriesFor(Proto.VENDOR_ANY);
		assertEquals(11, any.size());
		assertFalse(any.contains("stolen"));
		assertEquals(12, TradeRules.categoriesFor(Proto.VENDOR_ANY | Proto.VENDOR_STOLEN).size());
		assertEquals(Set.of("stolen"), TradeRules.categoriesFor(Proto.VENDOR_STOLEN));
		assertEquals(Set.of("weapon", "armor"), TradeRules.categoriesFor(Proto.VENDOR_WEAPON | Proto.VENDOR_ARMOR));
		assertEquals(Set.of("food"), TradeRules.categoriesFor(Proto.VENDOR_FOOD));
		assertTrue(TradeRules.categoriesFor(0).isEmpty());
	}

	@Test
	void offersOnlyFromMaskCategories() {
		TradeRules rules = defaults();
		for (long seed = 0; seed < 50; seed++) {
			for (TradeRules.Offer o : rules.offersFor(Proto.VENDOR_WEAPON | Proto.VENDOR_ARMOR, "Whiterun", seed, null)) {
				assertTrue(o.category().equals("weapon") || o.category().equals("armor"), o.toString());
			}
			for (TradeRules.Offer o : rules.offersFor(Proto.VENDOR_ANY, "Whiterun", seed, null)) {
				assertNotEquals("stolen", o.category());
			}
		}
		assertTrue(rules.offersFor(0, "Whiterun", 1, null).isEmpty());
	}

	@Test
	void smallShopsShowAllTheirTrades() {
		TradeRules rules = defaults();
		int arrows = basePrices(rules, "arrow").size();
		assertEquals(arrows, rules.offersFor(Proto.VENDOR_ARROW, "Whiterun", 7, null).size());
	}

	@Test
	void atMostTwelveOffers() {
		TradeRules rules = defaults();
		for (long seed = 0; seed < 50; seed++) {
			assertEquals(TradeRules.MAX_OFFERS, rules.offersFor(Proto.VENDOR_ANY | Proto.VENDOR_STOLEN, "Riften", seed, null).size());
		}
	}

	@Test
	void sellsBeforeBuys() {
		TradeRules rules = defaults();
		for (long seed = 0; seed < 50; seed++) {
			boolean seenBuy = false;
			for (TradeRules.Offer o : rules.offersFor(Proto.VENDOR_ANY, "Solitude", seed, null)) {
				if (!o.sell()) {
					seenBuy = true;
				} else {
					assertFalse(seenBuy, "a sell after a buy for seed " + seed);
				}
			}
		}
	}

	@Test
	void deterministicBySeed() {
		TradeRules rules = defaults();
		assertEquals(rules.offersFor(Proto.VENDOR_ANY, "Whiterun", 0x0001A66B, null), rules.offersFor(Proto.VENDOR_ANY, "Whiterun", 0x0001A66B, null));
		boolean differs = false;
		List<TradeRules.Offer> first = rules.offersFor(Proto.VENDOR_ANY, "Whiterun", 1, null);
		for (long seed = 2; seed < 20 && !differs; seed++) {
			differs = !first.equals(rules.offersFor(Proto.VENDOR_ANY, "Whiterun", seed, null));
		}
		assertTrue(differs, "every seed picked the same offers");
	}

	@Test
	void townMultipliers() {
		TradeRules rules = defaults();
		// Whiterun: food 0.7, so cheap to buy and pays less
		Map<String, Integer> food = basePrices(rules, "food");
		for (TradeRules.Offer o : rules.offersFor(Proto.VENDOR_FOOD, "Whiterun", 3, null)) {
			int base = food.get((o.sell() ? "sell " : "buy ") + o.count() + " " + o.item());
			assertEquals(Math.max(1, Math.round(base * 0.7)), o.price(), o.toString());
		}
		assertEquals(0.7, rules.town("Whiterun").multiplier("food"), 1e-9);
		assertEquals(1.0, rules.town("Whiterun").multiplier("book"), 1e-9);
		assertEquals(1.5, rules.town("Markarth").multiplier("jewelry"), 1e-9);
		assertEquals(0.6, rules.town("riften").multiplier("stolen"), 1e-9);
		assertEquals(0.6, rules.town("Winterhold").multiplier("book"), 1e-9);
	}

	@Test
	void townCurrencies() {
		TradeRules rules = defaults();
		assertEquals(Set.of(Currency.COPPER), rules.town("Rorikstead").currencies());
		assertEquals(Set.of(Currency.COPPER, Currency.NUGGET, Currency.GOLD), rules.town("Whiterun").currencies());
		assertTrue(rules.town("Solitude").currencies().contains(Currency.DIAMOND));
		assertTrue(rules.town("Markarth").currencies().contains(Currency.EMERALD));
		assertEquals(Set.of(Currency.NUGGET, Currency.GOLD, Currency.EMERALD), TradeRules.currenciesFor(rules.town("Solitude"), Currency.DIAMOND));
	}

	@Test
	void townsMatchIgnoringCaseAndFallBack() {
		TradeRules rules = defaults();
		assertEquals("Shor's Stone", rules.town("SHOR'S STONE").name());
		assertEquals("Dragon Bridge", rules.town("  dragon bridge ").name());
		assertEquals("*", rules.town("Helgen").name());
		assertEquals("*", rules.town("").name());
		assertEquals("*", rules.town(null).name());
		// Unknown towns price at the base price.
		Map<String, Integer> food = basePrices(rules, "food");
		for (TradeRules.Offer o : rules.offersFor(Proto.VENDOR_FOOD, "Helgen", 3, null)) {
			assertEquals((int) food.get((o.sell() ? "sell " : "buy ") + o.count() + " " + o.item()), o.price(), o.toString());
		}
	}

	@Test
	void noStarLineUsesCopperAndGold() {
		List<String> problems = new ArrayList<>();
		TradeRules rules = TradeRules.parse("food sell 6 bread 6\n", "Whiterun = copper\n", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals("minecraft:bread", rules.trades().getFirst().item());
		assertEquals(Set.of(Currency.COPPER, Currency.NUGGET, Currency.GOLD), rules.town("Riften").currencies());
	}

	@Test
	void demandMovesPrices() {
		TradeRules rules = TradeRules.parse("food sell 6 minecraft:bread 20\nfood buy 20 minecraft:wheat 10\n", "* = copper\n", new ArrayList<>());
		Demand demand = new Demand();
		for (int i = 0; i < 4; i++) {
			demand.bought("Whiterun", "minecraft:bread");
			demand.sold("Whiterun", "minecraft:wheat");
		}
		List<TradeRules.Offer> offers = rules.offersFor(Proto.VENDOR_FOOD, "Whiterun", 1, demand);
		assertEquals(2, offers.size());
		assertEquals(24, offers.get(0).price()); // bread 20 * 1.2
		assertEquals(8, offers.get(1).price()); // wheat 10 * 0.8
		// Another town's prices are untouched.
		assertEquals(20, rules.offersFor(Proto.VENDOR_FOOD, "Riften", 1, demand).getFirst().price());
	}

	@Test
	void priceShrinksWithACappedCount() {
		// 32 ender pearls can't fit one trade (they stack to 16): half the pearls, half the price.
		TradeRules.Offer sellPearls = new TradeRules.Offer(true, "minecraft:ender_pearl", 32, 10, "potion");
		TradeRules.Offer buyPearls = new TradeRules.Offer(false, "minecraft:ender_pearl", 32, 10, "potion");
		assertEquals(5, TradeRules.priceFor(sellPearls, 16));
		assertEquals(5, TradeRules.priceFor(buyPearls, 16));
		assertEquals(10, TradeRules.priceFor(sellPearls, 32));
		// Rounding favors the merchant: up when it sells, down when it buys, never below 1.
		TradeRules.Offer sellSwords = new TradeRules.Offer(true, "minecraft:iron_sword", 3, 10, "weapon");
		TradeRules.Offer buySwords = new TradeRules.Offer(false, "minecraft:iron_sword", 3, 10, "weapon");
		assertEquals(4, TradeRules.priceFor(sellSwords, 1));
		assertEquals(3, TradeRules.priceFor(buySwords, 1));
		assertEquals(1, TradeRules.priceFor(new TradeRules.Offer(false, "minecraft:ender_pearl", 32, 1, "potion"), 16));
	}

	@Test
	void absurdPricesAreRejected() {
		List<String> problems = new ArrayList<>();
		// 2e9 coins times a town multiplier used to overflow into a 1-coin price.
		TradeRules rules = TradeRules.parse("weapon sell 1 minecraft:iron_sword 2000000000\nweapon sell 1 minecraft:bow " + TradeRules.MAX_PRICE + "\n",
			"* = copper ; weapon 1.4\n", problems);
		assertEquals(1, problems.size(), problems.toString());
		assertEquals(1, rules.trades().size());
		assertEquals(140000, rules.offersFor(Proto.VENDOR_WEAPON, "Whiterun", 1, new Demand()).getFirst().price());
	}

	@Test
	void reportsBadLinesOnceAndKeepsGoodOnes() {
		List<String> problems = new ArrayList<>();
		TradeRules rules = TradeRules.parse("""
			# comment
			weapon sell 1 minecraft:iron_sword 40
			weapon sell 1 minecraft:bow
			gems sell 1 minecraft:diamond 100
			food trade 1 minecraft:bread 5
			food sell x minecraft:bread 5
			food sell 65 minecraft:bread 5
			food sell 1 minecraft:bread 0
			food buy 4 minecraft:wheat 2
			""", """
			Whiterun = copper gold ; food 0.7
			Riften copper
			Solitude = septims
			Markarth = gold ; jewels 1.5
			Morthal = copper ; food -1
			Dawnstar = ; food 1.2
			whiterun = copper
			* = copper ; food 1.1, tool 0.9
			""", problems);
		assertEquals(2, rules.trades().size());
		assertEquals(12, problems.size(), problems.toString());
		assertEquals(2, rules.towns().size());
		assertEquals(1.1, rules.town("Riften").multiplier("food"), 1e-9);
		assertEquals(0.7, rules.town("Whiterun").multiplier("food"), 1e-9);
	}

	@Test
	void obsidianForPortalsIsSoldDearAndNeverBought() {
		TradeRules rules = defaults();
		Set<String> sellers = new HashSet<>();
		for (TradeRules.Trade t : rules.trades()) {
			if (!t.item().equals("minecraft:obsidian")) {
				continue;
			}
			assertTrue(t.sell(), "merchants never buy obsidian: " + t);
			sellers.add(t.category());
			// Ten blocks make a frame: a portal costs hundreds of coins even where it's cheapest.
			for (TradeRules.Town town : rules.towns().values()) {
				assertTrue(t.price() * town.multiplier(t.category()) / t.count() >= 20.0, town.name() + " " + t);
			}
		}
		assertEquals(Set.of("ingot", "tool"), sellers);
		// Markarth's smiths (ingots 0.7) sell it for less than a general store elsewhere.
		Map<String, Integer> ingot = basePrices(rules, "ingot");
		double markarth = ingot.get("sell 2 minecraft:obsidian") * rules.town("Markarth").multiplier("ingot") / 2.0;
		assertTrue(markarth < basePrices(rules, "tool").get("sell 1 minecraft:obsidian"), "Markarth " + markarth);
	}
}
