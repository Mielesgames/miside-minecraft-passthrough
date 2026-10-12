package dev.skycraft.portal;

import dev.skycraft.worlds.FarLands;
import dev.skycraft.worlds.SteveCamp;
import java.util.List;

/**
 * The story's ending (pure; docs/LORE.md): in Home, the Far Lands' broken strata past the edge (the crack in
 * front of Steve's camp too) hold Seamstone. Picking up the first one is the ending moment: a title, a line
 * under it, a few lines in chat, and a lit portal opening in the crack. From then on Seamstone breaks the
 * pairing lock: held to any lit portal it opens it onto any place the player has been (Destinations).
 *
 * <p>The words marked PLACEHOLDER are for the author's own; the rest is how to use the stone.
 */
public final class Ending {
	public static final String TITLE = "Past the Far Lands";
	/** PLACEHOLDER: a short line under the title, in the author's words. */
	public static final String SUBTITLE = "Sygmensional travel";
	/** Chat lines after the title. The first is a PLACEHOLDER for the author's words. */
	public static final List<String> LINES = List.of(
		"[PLACEHOLDER: the ending, in your own words]",
		"Hold the Seamstone and right click any lit portal to choose where it opens.");
	/** Title timing, in ticks: fade in, stay, fade out. */
	public static final int FADE_IN = 30, STAY = 120, FADE_OUT = 40;

	private Ending() {
	}

	/** Where the crack's portal opens at the ending: in the mouth of the crack, facing back west toward the camp. */
	public static PortalPlan seamPortal() {
		return PortalPlan.behind(FarLands.EDGE, SteveCamp.FLOOR_Y + 1, SteveCamp.ORIGIN_Z, 90.0f);
	}

	/** The crack portal's place name. */
	public static final String SEAM_PORTAL_PLACE = "The Far Lands";

	/**
	 * Whether breaking a block drops a Seamstone: in Home, past the Far Lands' edge on either axis, a block of
	 * the strata (stone or ground: anything a pickaxe or shovel mines), for a player who has none on them.
	 */
	public static boolean dropsSeamstone(boolean inHome, int x, int z, boolean strata, boolean carriesOne) {
		return inHome && strata && !carriesOne && FarLands.isFar(x, z);
	}
}
