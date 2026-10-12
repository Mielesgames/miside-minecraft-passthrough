package dev.skycraft.loot;

/**
 * Minecraft experience for a Skyrim kill. A Minecraft zombie gives 5 XP; a level 1 Skyrim bandit
 * gives about the same, a level 10 wolf about 8, a level 40 dragon priest the cap.
 */
public final class KillXp {
	public static final int MAX = 60;

	private KillXp() {
	}

	/** XP for killing an actor of this Skyrim level (0 = unknown, treated as level 1). */
	public static int forLevel(int skyrimLevel) {
		int level = Math.max(1, skyrimLevel);
		return Math.min(MAX, 4 + (int) Math.round(level * 1.4));
	}
}
