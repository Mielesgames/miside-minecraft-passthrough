package dev.skycraft.link;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The one place Minecraft moves positions between Skyrim's worlds and the mirror (see WorldSpaces).
 * Everything on the link with Skyrim is in the current Skyrim world's own terms (Skyrim's position / 70:
 * Skyrim knows nothing of spaces); everything inside Minecraft is in the mirror's, where each Skyrim
 * world has a space of its own. SkyLink converts on the way in ({@link #originX} / {@link #originZ} of
 * the world Skyrim names) and on the way out ({@link #local}: whatever space a position is in), so the
 * rest of the mod never thinks about it: collision, digging, blocks, breach rooms, portals, actors and
 * the player all land in the right space by themselves.
 *
 * <p>The table lives with the Minecraft world (its blocks are there): the host's server opens it from
 * {@code skycraft-spaces.txt} when the world starts ({@link #open}) and gives a world its space the
 * first time anything asks. A guest's client has no world of its own: it keeps a copy the host's
 * server sends (SkyNet) and asks the server for a world it doesn't know yet ({@link #setAsker}); until
 * the answer comes {@link #known} is false and the world is at the origin. With no table at all (the
 * title screen) every world is at the origin, as before spaces. Any thread.
 */
public final class SkySpace {
	public static final String FILE = "skycraft-spaces.txt";

	private static volatile @Nullable WorldSpaces local;
	private static volatile @Nullable Path localFile;
	private static volatile @Nullable WorldSpaces remote;
	private static volatile IntConsumer asker = world -> {
	};
	private static volatile Runnable onChange = () -> {
	};
	/** Bumps on every change of any table: positions worked out before are stale. */
	private static volatile int version;

	private SkySpace() {
	}

	// ---- the table -----------------------------------------------------------------------------

	/** The host's server started a world: its table, from {@code worldRoot}/skycraft-spaces.txt (new if none). */
	public static synchronized WorldSpaces open(Path worldRoot) {
		Path path = worldRoot.resolve(FILE).normalize();
		WorldSpaces t = new WorldSpaces();
		if (Files.exists(path)) {
			try {
				List<String> problems = new ArrayList<>();
				t = WorldSpaces.parse(Files.readAllLines(path, StandardCharsets.UTF_8), problems);
				problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-space: {} {}", path, p));
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft-space: couldn't read {}: {}", path, e.toString());
			}
		}
		local = t;
		localFile = path;
		remote = null;
		version++;
		SkyCraft.LOG.info("SkyCraft-space: {} Skyrim worlds have their own space in this world ({})", t.size(), path.getFileName());
		return t;
	}

	/** The host's world closed. */
	public static synchronized void close() {
		local = null;
		localFile = null;
		remote = null;
		version++;
	}

	/** The open table (host), or null. */
	public static @Nullable WorldSpaces table() {
		return local;
	}

	/** Writes the host's table now. */
	public static synchronized void save() {
		WorldSpaces t = local;
		Path path = localFile;
		if (t == null || path == null) {
			return;
		}
		Path tmp = path.resolveSibling(FILE + ".tmp");
		try {
			Files.write(tmp, t.lines(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-space: couldn't write {}: {} (the spaces hold for this session)", path, e.toString());
		}
	}

	/** Server side: called (any thread) after a world got a space, to tell the guests. */
	public static void setOnChange(Runnable run) {
		onChange = run;
	}

	/** Guest client: how to ask the host's server for a world's space. */
	public static void setAsker(IntConsumer ask) {
		asker = ask;
	}

	/** Guest client: the host's spaces, as its server sent them (ignored on the host, which owns them). */
	public static void received(Map<Integer, Integer> spaces) {
		if (local != null) {
			return;
		}
		boolean changed;
		synchronized (SkySpace.class) {
			WorldSpaces t = remote;
			if (t == null) {
				t = new WorldSpaces();
				remote = t;
			}
			changed = t.merge(spaces);
			if (changed) {
				version++;
			}
		}
		if (changed) {
			SkyCraft.LOG.info("SkyCraft-space: the host's world has {} Skyrim worlds with a space", spaces.size());
		}
	}

	/** Guest client: left the host's world. */
	public static synchronized void forgetRemote() {
		ASKED.clear();
		if (remote != null) {
			remote = null;
			version++;
		}
	}

	/** Changes whenever any world's space may have changed. */
	public static int version() {
		return version;
	}

	private static @Nullable WorldSpaces current() {
		WorldSpaces t = local;
		return t != null ? t : remote;
	}

	// ---- Skyrim world -> mirror --------------------------------------------------------------------

	/**
	 * The world's space (given it now on the host if it has none yet; asked for on a guest), or -1 while
	 * a guest waits for the answer. 0 (the origin) with no table, or for world 0 (no world).
	 */
	public static int space(int world, @Nullable String place) {
		if (world == 0 || world == WorldSpaces.TAMRIEL) {
			return 0; // Tamriel's is the origin in every table
		}
		WorldSpaces t = local;
		if (t != null) {
			int have = t.spaceOf(world);
			if (have >= 0 && (place == null || place.isEmpty() || t.named(world))) {
				return have;
			}
			boolean[] changed = { false };
			int space = t.allocate(world, place, changed);
			if (changed[0]) {
				save();
				if (have < 0) {
					version++;
					SkyCraft.LOG.info("SkyCraft-space: Skyrim world {} ({}) gets space {}: its blocks are at {} {}", String.format("%08X", world),
						place == null || place.isEmpty() ? "unnamed" : place, space, WorldSpaces.originX(space), WorldSpaces.originZ(space));
					onChange.run();
				}
			}
			return space;
		}
		WorldSpaces r = remote;
		int have = r != null ? r.spaceOf(world) : -1;
		if (have >= 0 || !remoteWanted) {
			return Math.max(have, 0);
		}
		return ask(world);
	}

	/** Set by a guest's client while it plays in someone else's world: spaces come from the host. */
	public static volatile boolean remoteWanted;

	/** A guest gives up waiting for the host's answer after this long (an older host): the world is at the origin. */
	private static final long ASK_TIMEOUT_NANOS = 5_000_000_000L;
	private static final long ASK_EVERY_NANOS = 1_000_000_000L;
	private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> ASKED = new java.util.concurrent.ConcurrentHashMap<>();

	/** A guest doesn't know the world's space: asks the host's server (now and then), -1 until it answers. */
	private static int ask(int world) {
		long now = System.nanoTime();
		long[] times = ASKED.computeIfAbsent(world, w -> new long[] { now, now - ASK_EVERY_NANOS });
		if (now - times[0] > ASK_TIMEOUT_NANOS) {
			if (times[1] != Long.MIN_VALUE) {
				times[1] = Long.MIN_VALUE;
				SkyCraft.LOG.warn("SkyCraft-space: the host never said where Skyrim world {} is; using the origin", String.format("%08X", world));
			}
			return 0;
		}
		if (now - times[1] >= ASK_EVERY_NANOS) {
			times[1] = now;
			asker.accept(world);
		}
		return -1;
	}

	/** Whether the world's space is settled (a guest waiting on the host's answer isn't). */
	public static boolean known(int world) {
		return space(world, null) >= 0;
	}

	/** Minecraft x of the world's space's origin (0 while unknown). */
	public static int originX(int world) {
		return WorldSpaces.originX(space(world, null));
	}

	/** Minecraft z of the world's space's origin (0 while unknown). */
	public static int originZ(int world) {
		return WorldSpaces.originZ(space(world, null));
	}

	// ---- mirror -> Skyrim world --------------------------------------------------------------------

	/** A Minecraft x or z in the terms of the Skyrim world whose space it's in (Skyrim's position / 70). */
	public static double local(double v) {
		return WorldSpaces.local(v);
	}

	public static int local(int v) {
		return WorldSpaces.local(v);
	}

	/** A section x or z in the terms of its space's world. */
	public static int localSection(int s) {
		return WorldSpaces.localSection(s);
	}

	/** The space a Minecraft position is in. */
	public static int spaceAt(double x, double z) {
		return WorldSpaces.spaceAt(x, z);
	}

	/** The Skyrim world whose space a Minecraft position is in, or 0 (nobody's, or no table). */
	public static int worldAt(double x, double z) {
		WorldSpaces t = current();
		return t != null ? t.worldAt(x, z) : 0;
	}

	/** Whether a Minecraft position is in this Skyrim world's space (always, with no table or an unknown world). */
	public static boolean inWorld(int world, double x, double z) {
		int s = space(world, null);
		return s < 0 || WorldSpaces.spaceAt(x, z) == s;
	}
}
