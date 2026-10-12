package dev.skycraft.portal;

import dev.skycraft.link.Proto;

/**
 * The Time-Wound at the summit of the Throat of the World, where the story's last crack opens (pure). A lit
 * portal there, with an Elder Scroll in the Skyrim player's inventory (SkyState.carried; the scrolls are
 * quest items and never convert), opens one way onto Home's arrival by Steve's camp.
 *
 * <p>Where: Skyrim.esm's MQ206TimeWoundRef (0x000430A3, base MQ206TimeWound 0x000430A4, "Time-Wound") stands
 * in Tamriel (0x3C) at 56044, -47760, 36055 Skyrim units, read from the game data; its trigger
 * (MQ206TimeWoundTriggerRef 0x00064769) is at 56043.6, -47759.6, 36166.1. In Minecraft coordinates
 * (x / 70, z / 70 up, -y / 70) that is about 800.6, 515.1, 682.3.
 */
public final class TimeWound {
	/** Tamriel's worldspace (SkyState.worldId outside). */
	public static final int TAMRIEL = 0x0000003C;
	private static final double UNITS_PER_BLOCK = 70.0;
	/** MQ206TimeWoundRef in Skyrim units. */
	public static final double SKY_X = 56044.0, SKY_Y = -47760.0, SKY_Z = 36055.0;
	/** The same in Minecraft coordinates. */
	public static final double X = SKY_X / UNITS_PER_BLOCK, Y = SKY_Z / UNITS_PER_BLOCK, Z = -SKY_Y / UNITS_PER_BLOCK;
	/** A portal whose middle is this close (blocks, flat) counts as at the Time-Wound... */
	public static final double REACH = 16.0;
	/** ...and this close up or down (the summit is small; the wound hangs over the Word Wall's ledge). */
	public static final double REACH_UP = 16.0;

	/** What a lit portal at the summit comes to. */
	public enum Opening {
		/** At the Time-Wound with an Elder Scroll: opens one way onto Home. */
		OPENS,
		/** At the Time-Wound, but no Elder Scroll in Skyrim's inventory. */
		NO_SCROLL,
		/** Not at the Time-Wound (another world, or too far). */
		NOT_HERE,
		/** At the Time-Wound, but the portal is bound already. */
		ALREADY_LEADS,
	}

	private TimeWound() {
	}

	/** Whether a point (MC coordinates) in this Skyrim world is at the Time-Wound. */
	public static boolean near(int world, double x, double y, double z) {
		if (world != TAMRIEL) {
			return false;
		}
		double dx = x - X, dz = z - Z;
		return dx * dx + dz * dz <= REACH * REACH && Math.abs(y - Y) <= REACH_UP;
	}

	/** A lit portal with this middle, in this Skyrim world, with Skyrim's carried flags (SkyState.carried). */
	public static Opening check(int world, double[] center, int carried, boolean portalLeads) {
		if (!near(world, center[0], center[1], center[2])) {
			return Opening.NOT_HERE;
		}
		if (portalLeads) {
			return Opening.ALREADY_LEADS;
		}
		return (carried & Proto.CARRY_ELDER_SCROLL) != 0 ? Opening.OPENS : Opening.NO_SCROLL;
	}
}
