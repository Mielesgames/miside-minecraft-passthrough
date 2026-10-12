package dev.skycraft.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft items used as coin when trading with Skyrim merchants. Every price is kept in coins
 * and turned into currency stacks only when an offer is built:
 * <pre>
 * copper_ingot = 1, gold_nugget = 2, gold_ingot = 18, emerald = 24, diamond = 96
 * </pre>
 * A town accepts some of these (see {@link TradeRules}), named by keyword in the towns file:
 * {@code copper}, {@code gold} (nuggets and ingots), {@code emerald}, {@code diamond}.
 * Pure Java on purpose, so it's unit tested without Minecraft.
 */
public final class Currency {
	public static final String COPPER = "minecraft:copper_ingot";
	public static final String NUGGET = "minecraft:gold_nugget";
	public static final String GOLD = "minecraft:gold_ingot";
	public static final String EMERALD = "minecraft:emerald";
	public static final String DIAMOND = "minecraft:diamond";

	/** Every currency item, smallest first. */
	public static final List<String> ITEMS = List.of(COPPER, NUGGET, GOLD, EMERALD, DIAMOND);
	private static final int[] VALUES = { 1, 2, 18, 24, 96 };

	/** Currency keywords of the towns file and the items they allow. */
	public static final Map<String, List<String>> KEYWORDS = Map.of(
		"copper", List.of(COPPER),
		"gold", List.of(NUGGET, GOLD),
		"emerald", List.of(EMERALD),
		"diamond", List.of(DIAMOND)
	);

	public static final int MAX_STACK = 64;

	/** A stack of currency. */
	public record Pay(String item, int count) {
		public int coins() {
			return value(this.item) * this.count;
		}
	}

	private Currency() {
	}

	/** Coin value of one currency item, or 0 if the item is no currency. */
	public static int value(String item) {
		int i = ITEMS.indexOf(item);
		return i < 0 ? 0 : VALUES[i];
	}

	/** Items for a keyword of the towns file, or null for an unknown keyword. */
	public static List<String> itemsFor(String keyword) {
		return KEYWORDS.get(keyword.toLowerCase(Locale.ROOT));
	}

	/** Allowed currency items, largest value first; copper when none are allowed. */
	private static List<String> largestFirst(Set<String> allowed) {
		List<String> out = new ArrayList<>();
		for (int i = ITEMS.size() - 1; i >= 0; i--) {
			if (allowed.contains(ITEMS.get(i))) {
				out.add(ITEMS.get(i));
			}
		}
		if (out.isEmpty()) {
			out.add(COPPER);
		}
		return out;
	}

	/** Coins {@code pay} is worth. */
	public static int total(List<Pay> pay) {
		int sum = 0;
		for (Pay p : pay) {
			sum += p.coins();
		}
		return sum;
	}

	/**
	 * What the player pays for a price in coins: at most two stacks, using the largest allowed
	 * currency that fits and the next smaller one for the rest, rounded up. Never less than the price,
	 * except for prices beyond 64 of the largest currency, which are capped there: check with
	 * {@link #total} before offering such a price.
	 */
	public static List<Pay> cost(int coins, Set<String> allowed) {
		List<String> order = largestFirst(allowed);
		coins = Math.max(1, coins);
		int first = order.size() - 1; // smallest allowed, for prices below every currency
		for (int i = 0; i < order.size(); i++) {
			if (value(order.get(i)) <= coins) {
				first = i;
				break;
			}
		}
		String big = order.get(first);
		int bigValue = value(big);
		int bigCount = Math.min(MAX_STACK, coins / bigValue);
		if (bigCount == 0) {
			return List.of(new Pay(big, 1));
		}
		int rest = coins - bigCount * bigValue;
		if (rest <= 0) {
			return List.of(new Pay(big, bigCount));
		}
		// The rest in the smaller currency that overpays least (the larger one on a tie). One more
		// big coin wins when it overpays no more than that, or when nothing smaller is allowed.
		Pay change = null;
		int waste = bigValue - rest;
		for (int i = first + 1; i < order.size(); i++) {
			int v = value(order.get(i));
			int n = (rest + v - 1) / v;
			if (n <= MAX_STACK && n * v - rest < waste) {
				change = new Pay(order.get(i), n);
				waste = n * v - rest;
			}
		}
		if (change == null) {
			return List.of(new Pay(big, Math.min(MAX_STACK, bigCount + 1)));
		}
		return List.of(new Pay(big, bigCount), change);
	}

	/**
	 * What a merchant pays the player for a price in coins: one stack of the largest allowed currency
	 * worth at least one of, rounded down; one of the smallest allowed currency at the very least.
	 */
	public static Pay payout(int coins, Set<String> allowed) {
		List<String> order = largestFirst(allowed);
		for (String item : order) {
			int count = coins / value(item);
			if (count >= 1) {
				return new Pay(item, Math.min(MAX_STACK, count));
			}
		}
		return new Pay(order.getLast(), 1);
	}
}
