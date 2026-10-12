package dev.skycraft.portal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The keys that open a portal in the Skyrim mirror onto one of the Seed family's worlds (pure; docs/LORE.md).
 * A Daedra heart opens onto the Nether, where the Seed family borders Oblivion; the Dwemer's tonal key opens
 * onto Tamriel Wilds, where the Dwemer went. Used on a lit, unbound portal in the mirror, a key builds the
 * matching partner portal at that world's arrival point and binds the two as a normal locked pair; the key
 * is spent. Home has no key: the Time-Wound's crack opens onto it one way, for the bearer of an Elder Scroll
 * ({@link TimeWound}).
 *
 * <p>Dimension ids here match dev.skycraft.worlds.Worlds (SkyPortals checks at start).
 */
public final class PortalKeys {
	public static final String NETHER = "minecraft:the_nether";
	public static final String TAMRIEL_WILDS = "skycraft:tamriel_wilds";
	public static final String HOME = "skycraft:home";

	public static final String DAEDRA_HEART = "skycraft:daedra_heart";
	public static final String TONAL_KEY = "skycraft:" + PortalStore.TONAL_KEY_ID;
	public static final String SEAMSTONE = "skycraft:" + PortalStore.SEAMSTONE_ID;

	/** A key: the item, the world it opens onto, and what the player is told when it's spent. */
	public record Key(String item, String dimension, String spent) {
	}

	public static final List<Key> KEYS = List.of(
		new Key(DAEDRA_HEART, NETHER, "The Daedra heart burns away."),
		new Key(TONAL_KEY, TAMRIEL_WILDS, "The tonal key rings one long note and falls silent."));

	/** What using a key on a lit portal comes to. */
	public enum Use {
		/** Build the partner portal and bind the pair. */
		OPEN,
		/** The portals switch is off. */
		OFF,
		/** The Minecraft view is off: other worlds would act like the mirror, so nothing opens onto them. */
		VIEW_OFF,
		/** Only portals in the Skyrim mirror take a key. */
		NOT_MIRROR,
		/** Only the host's Skyrim feels the cracks. */
		NOT_HOST,
		/** Skyrim isn't in a game right now (loading, a menu, not linked). */
		SKYRIM_AWAY,
		/** This world has no such dimension (the worlds data pack is missing). */
		NO_DIMENSION,
		/** The portal is bound already, or opens one way. */
		ALREADY_LEADS,
	}

	private PortalKeys() {
	}

	/** The key this item is, or null. */
	public static @Nullable Key key(String itemId) {
		for (Key k : KEYS) {
			if (k.item().equals(itemId)) {
				return k;
			}
		}
		return null;
	}

	/** The checks in the order the player is told about them. {@code portalLeads} is only asked once the portal is known. */
	public static Use check(boolean on, boolean viewOn, boolean inMirror, boolean host, boolean skyrimHere, boolean dimensionExists, boolean portalLeads) {
		if (!on) {
			return Use.OFF;
		}
		if (!inMirror) {
			return Use.NOT_MIRROR;
		}
		if (!viewOn) {
			return Use.VIEW_OFF;
		}
		if (!host) {
			return Use.NOT_HOST;
		}
		if (!skyrimHere) {
			return Use.SKYRIM_AWAY;
		}
		if (!dimensionExists) {
			return Use.NO_DIMENSION;
		}
		return portalLeads ? Use.ALREADY_LEADS : Use.OPEN;
	}

	/** A world's name in a sentence ("onto the Nether"). */
	public static String worldName(String dimension) {
		return switch (dimension) {
			case NETHER -> "the Nether";
			case TAMRIEL_WILDS -> "Tamriel Wilds";
			case HOME -> "Home";
			default -> dimension;
		};
	}

	/** The place name a portal in that world is listed under. */
	public static String placeName(String dimension) {
		return switch (dimension) {
			case NETHER -> "The Nether";
			case TAMRIEL_WILDS -> "Tamriel Wilds";
			case HOME -> "Home";
			default -> dimension;
		};
	}

	/** Where a traveller arriving there stands, for the destination list. */
	public static String arrivalName(String dimension) {
		return switch (dimension) {
			case NETHER -> "The Nether";
			case TAMRIEL_WILDS -> "Tamriel Wilds: the Dwemer tower";
			case HOME -> "Home: Steve's camp";
			default -> dimension;
		};
	}
}
