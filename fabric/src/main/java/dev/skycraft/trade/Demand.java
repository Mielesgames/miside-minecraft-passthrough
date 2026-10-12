package dev.skycraft.trade;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Supply and demand per town and item. Every purchase from a merchant raises that item's price in
 * that town by 5%, every sale to a merchant lowers it by 5% (what merchants there pay drops too),
 * within 50% to 150% of the base price. {@link #decay()} eases every item one step back toward its
 * base price. Pure Java on purpose, so it's unit tested without Minecraft.
 */
public final class Demand {
	public static final double STEP = 0.05;
	public static final double MIN_FACTOR = 0.5;
	public static final double MAX_FACTOR = 1.5;
	private static final int MAX_STEPS = (int) Math.round((MAX_FACTOR - 1.0) / STEP);

	private final Map<String, Integer> steps = new HashMap<>();

	private static String key(String town, String item) {
		String t = town == null || town.isBlank() ? "*" : town.strip().toLowerCase(Locale.ROOT);
		return t + "|" + item;
	}

	private void add(String town, String item, int delta) {
		String key = key(town, item);
		this.steps.merge(key, delta, (a, b) -> Math.max(-MAX_STEPS, Math.min(MAX_STEPS, a + b)));
		this.steps.remove(key, 0);
	}

	/** The player bought {@code item} from a merchant in {@code town}. */
	public void bought(String town, String item) {
		this.add(town, item, 1);
	}

	/** The player sold {@code item} to a merchant in {@code town}. */
	public void sold(String town, String item) {
		this.add(town, item, -1);
	}

	/** Price factor for {@code item} in {@code town}, 0.5 to 1.5. */
	public double factor(String town, String item) {
		int s = this.steps.getOrDefault(key(town, item), 0);
		return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, 1.0 + s * STEP));
	}

	/** {@code coins} after this item's factor, at least 1. */
	public int apply(int coins, String town, String item) {
		return Math.max(1, (int) Math.round(coins * this.factor(town, item)));
	}

	/** Moves every item one step back toward its base price. */
	public void decay() {
		for (Iterator<Map.Entry<String, Integer>> it = this.steps.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Integer> e = it.next();
			int s = e.getValue();
			s -= Integer.signum(s);
			if (s == 0) {
				it.remove();
			} else {
				e.setValue(s);
			}
		}
	}

	public boolean isEmpty() {
		return this.steps.isEmpty();
	}
}
