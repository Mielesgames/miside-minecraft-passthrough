package dev.skycraft.deep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monsters in the deep: when and where Skyrim creatures come out of the dark in tunnels the player
 * dug, and which ones. Skeevers near the top, skeletons further down, draugr near bedrock.
 *
 * <p>Plain text in config/skycraft-deep.txt, one setting per line ({@code key = value}), so it can be
 * edited without a rebuild. Depth bands say which creatures live how deep under the land:
 * <pre>
 * depth 8-24 = skeever
 * depth 48+ = draugr, skeleton
 * </pre>
 * A band with several creatures picks one of them at random. Pure Java on purpose (no Minecraft
 * classes), so it's unit tested without Minecraft.
 */
public final class DeepRules {
	/** Creature kinds, as sent in EV_SPAWN_CREATURE (Proto.SPAWN_*). */
	public static final int NONE = 0;
	public static final int SKEEVER = 1;
	public static final int SKELETON = 2;
	public static final int DRAUGR = 3;

	public static final int TICKS_PER_SECOND = 20;

	/** Creatures living from {@code from} (inclusive) to {@code to} (exclusive) blocks under the land. */
	public record Band(double from, double to, List<Integer> kinds) {
	}

	/** Shipped defaults, written to the config folder on first run. */
	public static final String DEFAULT_TEXT = """
		# SkyCraft monsters in the deep: Skyrim creatures come out of the dark in tunnels you dig.
		# Restart Minecraft after editing.
		# Turn it all off with: enabled = false
		enabled = true
		# Most spawned creatures alive at once.
		max_alive = 3
		# Seconds between spawns (a random time in this range).
		cooldown = 45-90
		# How far from the player (blocks) a creature may appear. Never where the player can see it.
		# Keep the far end under 29: past that Skyrim moves creatures along its navmesh, out of tunnels.
		distance = 12-26
		# Spawned creatures further away than this (blocks) are taken away again.
		despawn_distance = 64
		# Only this deep (blocks under the land's surface) or deeper, and only where the block light is
		# at most max_block_light (0: pitch dark, no torch nearby).
		min_depth = 8
		max_block_light = 0
		# Who lives how deep (blocks under the land; bedrock is 64 down): skeever, skeleton, draugr.
		# Several on one line: one of them at random.
		depth 8-24 = skeever
		depth 24-48 = skeleton
		depth 48+ = draugr
		""";

	private static final int MAX_ALIVE_LIMIT = 16;

	private final boolean enabled;
	private final int maxAlive;
	private final double cooldownMin;
	private final double cooldownMax;
	private final double minDistance;
	private final double maxDistance;
	private final double despawnDistance;
	private final double minDepth;
	private final int maxBlockLight;
	private final List<Band> bands;

	private DeepRules(boolean enabled, int maxAlive, double cooldownMin, double cooldownMax, double minDistance, double maxDistance, double despawnDistance,
		double minDepth, int maxBlockLight, List<Band> bands) {
		this.enabled = enabled;
		this.maxAlive = maxAlive;
		this.cooldownMin = cooldownMin;
		this.cooldownMax = cooldownMax;
		this.minDistance = minDistance;
		this.maxDistance = maxDistance;
		this.despawnDistance = despawnDistance;
		this.minDepth = minDepth;
		this.maxBlockLight = maxBlockLight;
		this.bands = bands;
	}

	public boolean enabled() {
		return this.enabled && this.maxAlive > 0 && !this.bands.isEmpty();
	}

	public int maxAlive() {
		return this.maxAlive;
	}

	public double cooldownMinSeconds() {
		return this.cooldownMin;
	}

	public double cooldownMaxSeconds() {
		return this.cooldownMax;
	}

	public double minDistance() {
		return this.minDistance;
	}

	public double maxDistance() {
		return this.maxDistance;
	}

	public double despawnDistance() {
		return this.despawnDistance;
	}

	public double minDepth() {
		return this.minDepth;
	}

	public int maxBlockLight() {
		return this.maxBlockLight;
	}

	public List<Band> bands() {
		return this.bands;
	}

	/** Parses settings text. Bad lines are skipped (the default stays) and reported to {@code problems}. */
	public static DeepRules parse(String text, List<String> problems) {
		boolean enabled = true;
		int maxAlive = 3;
		double cooldownMin = 45.0, cooldownMax = 90.0;
		double minDistance = 12.0, maxDistance = 26.0;
		double despawnDistance = 64.0;
		double minDepth = 8.0;
		int maxBlockLight = 0;
		List<Band> bands = new ArrayList<>();
		int n = 0;
		for (String raw : text.split("\\R")) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				problems.add("line " + n + ": no '='");
				continue;
			}
			String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
			String value = line.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
			try {
				if (key.startsWith("depth ") || key.startsWith("depth\t")) {
					double[] range = range(key.substring(5).strip(), true);
					List<Integer> kinds = new ArrayList<>();
					for (String name : value.split(",")) {
						String word = name.strip();
						if (word.isEmpty()) {
							continue;
						}
						int kind = kindNamed(word);
						if (kind == NONE) {
							throw new IllegalArgumentException("unknown creature '" + word + "'");
						}
						kinds.add(kind);
					}
					if (kinds.isEmpty()) {
						throw new IllegalArgumentException("needs at least one creature");
					}
					bands.add(new Band(range[0], range[1], List.copyOf(kinds)));
					continue;
				}
				switch (key) {
					case "enabled" -> enabled = bool(value);
					case "max_alive" -> maxAlive = (int) number(value, 0, MAX_ALIVE_LIMIT);
					case "cooldown" -> {
						double[] r = range(value, false);
						if (r[0] < 1.0) {
							throw new IllegalArgumentException("cooldown under 1 second");
						}
						cooldownMin = r[0];
						cooldownMax = r[1];
					}
					case "distance" -> {
						double[] r = range(value, false);
						if (r[0] < 4.0 || r[1] > 96.0) {
							throw new IllegalArgumentException("distance must be within 4-96");
						}
						minDistance = r[0];
						maxDistance = r[1];
					}
					case "despawn_distance" -> despawnDistance = number(value, 16.0, 256.0);
					case "min_depth" -> minDepth = number(value, 0.0, 64.0);
					case "max_block_light" -> maxBlockLight = (int) number(value, 0, 15);
					default -> problems.add("line " + n + ": unknown setting '" + key + "'");
				}
			} catch (IllegalArgumentException e) {
				problems.add("line " + n + ": " + e.getMessage());
			}
		}
		if (despawnDistance < maxDistance + 8.0) {
			problems.add("despawn_distance " + despawnDistance + " is too close to the spawn distance; using " + (maxDistance + 8.0));
			despawnDistance = maxDistance + 8.0;
		}
		return new DeepRules(enabled, maxAlive, cooldownMin, cooldownMax, minDistance, maxDistance, despawnDistance, minDepth, maxBlockLight,
			List.copyOf(bands));
	}

	/** A creature kind by its config name, or NONE. */
	public static int kindNamed(String name) {
		return switch (name.strip().toLowerCase(Locale.ROOT)) {
			case "skeever", "skeevers" -> SKEEVER;
			case "skeleton", "skeletons" -> SKELETON;
			case "draugr" -> DRAUGR;
			default -> NONE;
		};
	}

	public static String kindName(int kind) {
		return switch (kind) {
			case SKEEVER -> "skeever";
			case SKELETON -> "skeleton";
			case DRAUGR -> "draugr";
			default -> "none";
		};
	}

	// ---- decisions --------------------------------------------------------------------------

	/**
	 * Which creature lives this deep under the land (blocks), or NONE: shallower than min_depth or
	 * in no band. {@code pick} is any non-negative random number; it chooses within a band.
	 */
	public int kindAt(double depth, int pick) {
		if (!(depth >= this.minDepth)) {
			return NONE;
		}
		for (Band band : this.bands) {
			if (depth >= band.from() && depth < band.to()) {
				return band.kinds().get(Math.floorMod(pick, band.kinds().size()));
			}
		}
		return NONE;
	}

	/** Ticks to wait after a spawn; {@code unit} is a random number in [0, 1). */
	public long cooldownTicks(double unit) {
		double u = Math.clamp(unit, 0.0, 1.0);
		return Math.round((this.cooldownMin + (this.cooldownMax - this.cooldownMin) * u) * TICKS_PER_SECOND);
	}

	/** Another creature may come while this many spawned ones are alive. */
	public boolean belowCap(int alive) {
		return alive < this.maxAlive;
	}

	/** A spot this far (squared blocks) from the player is near enough, and far enough, to spawn in. */
	public boolean inSpawnRange(double distanceSq) {
		return distanceSq >= this.minDistance * this.minDistance && distanceSq <= this.maxDistance * this.maxDistance;
	}

	/** A spawned creature this far (squared blocks) from the player is taken away. */
	public boolean shouldDespawn(double distanceSq) {
		return distanceSq > this.despawnDistance * this.despawnDistance;
	}

	/** Dark enough: the block light at the spot (0-15). */
	public boolean darkEnough(int blockLight) {
		return blockLight <= this.maxBlockLight;
	}

	/**
	 * Half the angle (degrees) around the look direction that counts as "in view": wider than
	 * Minecraft's usual field of view, so nothing pops in at the edge of the screen.
	 */
	public static final double VIEW_HALF_ANGLE = 75.0;
	private static final double VIEW_COS = Math.cos(Math.toRadians(VIEW_HALF_ANGLE));

	/**
	 * Is a point (dx, dy, dz from the eye) inside the player's view cone around the look direction
	 * (lx, ly, lz, any length)? Whether a wall hides it is the caller's raycast.
	 */
	public static boolean inViewCone(double dx, double dy, double dz, double lx, double ly, double lz) {
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double l = Math.sqrt(lx * lx + ly * ly + lz * lz);
		if (d < 1.0E-6) {
			return true; // on top of the eye: certainly seen
		}
		if (l < 1.0E-6) {
			return false;
		}
		return (dx * lx + dy * ly + dz * lz) / (d * l) >= VIEW_COS;
	}

	// ---- parsing helpers -------------------------------------------------------------------

	private static boolean bool(String v) {
		return switch (v) {
			case "true", "yes", "on", "1" -> true;
			case "false", "no", "off", "0" -> false;
			default -> throw new IllegalArgumentException("'" + v + "' isn't true or false");
		};
	}

	private static double number(String v, double min, double max) {
		double d;
		try {
			d = Double.parseDouble(v);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + v + "' isn't a number");
		}
		if (!(d >= min && d <= max)) {
			throw new IllegalArgumentException(v + " is outside " + fmt(min) + "-" + fmt(max));
		}
		return d;
	}

	/** "a-b", "a" (both the same) or, if {@code openEnded}, "a+" (no end). */
	private static double[] range(String v, boolean openEnded) {
		String s = v.replace(" ", "");
		if (openEnded && s.endsWith("+")) {
			return new double[] { number(s.substring(0, s.length() - 1), 0.0, 1000.0), Double.POSITIVE_INFINITY };
		}
		int dash = s.indexOf('-', 1);
		double a = number(dash < 0 ? s : s.substring(0, dash), 0.0, 1000.0);
		double b = dash < 0 ? a : number(s.substring(dash + 1), 0.0, 1000.0);
		if (b < a || (openEnded && b == a)) {
			throw new IllegalArgumentException("bad range '" + v + "'");
		}
		return new double[] { a, b };
	}

	private static String fmt(double d) {
		return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
	}

	/**
	 * When to look for a spot: a cooldown after every spawn (the first one too, so nothing jumps
	 * out the moment the player arrives), and between failed looks a short wait so a quiet tunnel
	 * isn't searched every tick.
	 */
	public static final class Pacer {
		/** Ticks between looks for a spot while the cooldown is over but none was found. */
		public static final long RETRY_TICKS = TICKS_PER_SECOND;

		private long nextSpawn = Long.MIN_VALUE;
		private long nextLook = Long.MIN_VALUE;

		/** Start (or start over, as after a world change): the first spawn waits a cooldown. */
		public void restart(long now, long cooldownTicks) {
			this.nextSpawn = now + cooldownTicks;
			this.nextLook = this.nextSpawn;
		}

		public boolean started() {
			return this.nextSpawn != Long.MIN_VALUE;
		}

		/** Forgets it all (a new server counts its ticks from 0 again): the next tick starts over. */
		public void reset() {
			this.nextSpawn = Long.MIN_VALUE;
			this.nextLook = Long.MIN_VALUE;
		}

		/** Time to look for a spot this tick. */
		public boolean shouldLook(long now) {
			return this.started() && now >= this.nextSpawn && now >= this.nextLook;
		}

		/** Looked and found nothing (or wasn't allowed to): look again a little later. */
		public void looked(long now) {
			this.nextLook = now + RETRY_TICKS;
		}

		/** A creature was sent for: the next one waits a cooldown. */
		public void spawned(long now, long cooldownTicks) {
			this.nextSpawn = now + cooldownTicks;
			this.nextLook = this.nextSpawn;
		}

		/** Ticks until the next look (0 when due). */
		public long ticksUntilLook(long now) {
			return Math.max(0L, Math.max(this.nextSpawn, this.nextLook) - now);
		}
	}
}
