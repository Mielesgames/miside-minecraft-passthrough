package dev.skycraft.portal;

import dev.skycraft.portal.PortalStore.Portal;
import dev.skycraft.portal.PortalStore.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The places a Seamstone can open a portal onto (pure; SeamMenu shows them as a chest of icons):
 * the arrival point of every world the player has been to (Home, Tamriel Wilds, the Nether), then every
 * portal of the network, in the order they were found: Skyrim places where the player stood at a portal or
 * bound one, and the portals in the other worlds. The portal being used isn't listed (it can't open onto
 * itself). At most {@link #MAX} (a double chest).
 */
public final class Destinations {
	public static final int MAX = 54;
	/** Arrival points, in the order they're listed. */
	public static final List<String> ARRIVALS = List.of(PortalKeys.HOME, PortalKeys.TAMRIEL_WILDS, PortalKeys.NETHER);

	/** One place: where it leads, its name and a second line, and the item shown for it. */
	public record Entry(Target target, String label, String detail, String icon) {
	}

	private Destinations() {
	}

	/** Everything the Seamstone can open {@code here} onto (null: a portal not in the network yet). */
	public static List<Entry> list(PortalStore store, @Nullable Portal here) {
		List<Entry> out = new ArrayList<>();
		for (String dim : ARRIVALS) {
			if (store.visited(dim)) {
				out.add(new Entry(new Target.ToArrival(dim), PortalKeys.arrivalName(dim), "Where travellers arrive", icon(dim, false)));
			}
		}
		for (Portal p : store.all()) {
			if (out.size() >= MAX) {
				break;
			}
			if (here != null && p.id() == here.id()) {
				continue;
			}
			String detail = (p.inMirror() ? (p.interior() ? "Skyrim, indoors" : "Skyrim") : PortalKeys.placeName(p.dimension())) + ", portal " + p.id()
				+ String.format(Locale.ROOT, " at %d %d %d", p.x(), p.y(), p.z());
			if (here != null && here.partner() == p.id()) {
				detail += " (bound to this one)";
			}
			out.add(new Entry(new Target.ToPortal(p.id()), label(p), detail, icon(p.dimension(), p.interior())));
		}
		return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : out;
	}

	/** A portal's name in the list: its place, capitalised. */
	public static String label(Portal p) {
		String name = !p.place().isEmpty() ? p.place() : p.inMirror() ? (p.interior() ? "an unnamed place indoors" : "the wilds") : PortalKeys.placeName(p.dimension());
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** The item shown for a place in a world ({@link PortalStore#MIRROR} for Skyrim). */
	public static String icon(String dimension, boolean interior) {
		return switch (dimension) {
			case PortalStore.MIRROR -> interior ? "minecraft:lantern" : "minecraft:obsidian";
			case PortalKeys.NETHER -> "minecraft:netherrack";
			case PortalKeys.TAMRIEL_WILDS -> "minecraft:waxed_cut_copper";
			case PortalKeys.HOME -> "minecraft:grass_block";
			default -> "minecraft:crying_obsidian";
		};
	}

	/** Chest rows to show {@code count} places (1 to 6). */
	public static int rows(int count) {
		return Math.max(1, Math.min(6, (count + 8) / 9));
	}
}
