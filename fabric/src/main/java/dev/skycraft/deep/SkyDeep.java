package dev.skycraft.deep;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.config.Config;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Monsters in the deep, Minecraft side: in the dark tunnels the player dug into Skyrim's ground,
 * Skyrim creatures come out of the dark (skeevers near the top, skeletons further down, draugr near
 * bedrock). Minecraft knows the dug cells, how deep they are under Skyrim's land, the light and what
 * the player can see, so it picks the spot and asks Skyrim for the creature (EV_SPAWN_CREATURE);
 * Skyrim places a real actor there (so its combat, kill XP and drops all work) and takes it away
 * again when the player leaves it behind. Rules in config/skycraft-deep.txt ({@link DeepRules}).
 * Host only (its Skyrim is on the shared-memory link); server thread only.
 */
public final class SkyDeep {
	private static final String FILE = "skycraft-deep.txt";
	/** Most candidate cells checked in full per look. */
	private static final int CANDIDATES = 48;
	/** A creature's height in blocks (draugr are about as tall as the player). */
	private static final int BODY = 2;
	/** Where on a body (blocks over its feet) a player could see it. */
	private static final double[] SIGHT_HEIGHTS = { 0.3, 1.0, 1.7 };

	private static @Nullable DeepRules rules;
	private static final DeepRules.Pacer PACER = new DeepRules.Pacer();
	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
	private static int aliveSpawned;
	private static int world = Integer.MIN_VALUE;
	private static int teleportSeq;
	private static long lastTick = Long.MIN_VALUE;

	private SkyDeep() {
	}

	/**
	 * Starts over: the link to Skyrim dropped, nobody is playing, or a new server started (its
	 * tick count begins at 0 again, so an old cooldown would hold the first creature back for as
	 * long as the last session ran). The first creature then waits a cooldown again.
	 */
	public static void reset() {
		PACER.reset();
		aliveSpawned = 0;
		world = Integer.MIN_VALUE;
	}

	private static DeepRules rules() {
		DeepRules r = rules;
		if (r == null) {
			List<String> problems = new ArrayList<>();
			r = DeepRules.parse(Config.read(FILE, DeepRules.DEFAULT_TEXT), problems);
			for (String p : problems) {
				SkyCraft.LOG.warn("SkyCraft-deep: {}: {}", FILE, p);
			}
			SkyCraft.LOG.info("SkyCraft-deep: monsters in the deep {} ({} alive at most, every {}-{} s, {} depth bands)", r.enabled() ? "on" : "off",
				r.maxAlive(), r.cooldownMinSeconds(), r.cooldownMaxSeconds(), r.bands().size());
			rules = r;
		}
		return r;
	}

	/** Skyrim's nearby actors (a consistent read of the actor table): counts the live spawned creatures. */
	public static void onActors(List<SkyLink.Actor> actors) {
		int alive = 0;
		for (SkyLink.Actor a : actors) {
			if ((a.flags() & Proto.ACTOR_SPAWNED) != 0 && !a.dead()) {
				alive++;
			}
		}
		aliveSpawned = alive;
	}

	/** Every server tick, for the host player. {@code players}: everyone in the world (nobody sees a creature appear). */
	public static void tick(ServerPlayer host, List<ServerPlayer> players, long tick) {
		DeepRules r = rules();
		if (!r.enabled() || !dev.skycraft.config.Features.on(dev.skycraft.config.Features.Feature.DEEP_CREATURES)) {
			return;
		}
		if (tick < lastTick) {
			reset(); // another server (singleplayer world reopened): its ticks start from 0
		}
		lastTick = tick;
		RandomSource random = host.getRandom();
		if (!PACER.started()) {
			PACER.restart(tick, r.cooldownTicks(random.nextDouble()));
			return;
		}
		if (!PACER.shouldLook(tick)) {
			return;
		}
		PACER.looked(tick);
		// Not while Skyrim shows a menu (a paused game would put the creature down the moment it closes).
		if (!SkyLink.readSkyState(SKY) || !SKY.inGame() || SKY.loading() || SKY.menuOpen()) {
			return;
		}
		if (SKY.worldId != world || SKY.teleportSeq != teleportSeq) {
			// Another Skyrim world, or Skyrim moved the player itself (a new game, loading a save
			// even in the same worldspace, a door, fast travel): the first creature waits a cooldown.
			boolean first = world == Integer.MIN_VALUE;
			world = SKY.worldId;
			teleportSeq = SKY.teleportSeq;
			if (!first) {
				PACER.restart(tick, r.cooldownTicks(random.nextDouble()));
				return;
			}
		}
		ServerLevel level = host.level();
		// Interiors have no land to be under; dug-out caves there stay quiet.
		if ((SKY.place & Proto.PLACE_INTERIOR) != 0 || !SkyCollision.active() || !host.isAlive() || host.isSpectator()
			|| level.getDifficulty() == Difficulty.PEACEFUL || !r.belowCap(aliveSpawned)) {
			return;
		}
		Spot spot = findSpot(level, SKY.worldId, host, players, r, random);
		if (spot == null) {
			return;
		}
		SkyLink.pushEventAt(Proto.EV_SPAWN_CREATURE, spot.kind, spot.pos.getX() + 0.5, spot.pos.getY(), spot.pos.getZ() + 0.5, (float) r.despawnDistance(),
			r.maxAlive(), 0);
		aliveSpawned++; // until the actor table shows it
		PACER.spawned(tick, r.cooldownTicks(random.nextDouble()));
		SkyCraft.LOG.info("SkyCraft-deep: a {} comes out of the dark at {} ({} blocks down, {} blocks from the player)", DeepRules.kindName(spot.kind),
			spot.pos.toShortString(), Math.round(spot.depth), Math.round(Math.sqrt(host.distanceToSqr(spot.pos.getX() + 0.5, spot.pos.getY(), spot.pos.getZ() + 0.5))));
	}

	private record Spot(BlockPos pos, int kind, double depth) {
	}

	/**
	 * A dug cell in range with another dug cell over it (room for a body), picked at random among
	 * all of them, that passes every rule: something solid to stand on, dark, deep enough, unseen.
	 */
	private static @Nullable Spot findSpot(ServerLevel level, int world, ServerPlayer host, List<ServerPlayer> players, DeepRules r, RandomSource random) {
		double px = host.getX(), py = host.getY(), pz = host.getZ();
		int reach = (int) Math.ceil(r.maxDistance());
		long[] picked = new long[CANDIDATES];
		int count = 0;
		int seen = 0;
		double reachSq = (double) reach * reach;
		for (int cx = ((int) Math.floor(px) - reach) >> 4; cx <= ((int) Math.floor(px) + reach) >> 4; cx++) {
			for (int cz = ((int) Math.floor(pz) - reach) >> 4; cz <= ((int) Math.floor(pz) + reach) >> 4; cz++) {
				// Corner chunks of the square wholly outside the circle: nothing in range there.
				double nx = Math.clamp(px, cx << 4, (cx << 4) + 16) - px, nz = Math.clamp(pz, cz << 4, (cz << 4) + 16) - pz;
				if (nx * nx + nz * nz > reachSq) {
					continue;
				}
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				SkyDig.DugColumn column = SkyDig.column(chunk);
				for (SkyDig.DugSection section : column.sections()) {
					int baseY = section.sectionY() << 4;
					if (section.world() != world || section.bits().length != 64 || baseY > py + reach || baseY + 15 < py - reach) {
						continue;
					}
					long[] bits = section.bits();
					for (int k = 0; k < 64; k++) {
						long word = bits[k];
						while (word != 0) {
							int bit = (k << 6) | Long.numberOfTrailingZeros(word);
							word &= word - 1;
							int x = (cx << 4) + (bit & 15), z = (cz << 4) + ((bit >> 4) & 15), y = baseY + (bit >> 8);
							double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
							if (!r.inSpawnRange(dx * dx + dy * dy + dz * dz)) {
								continue;
							}
							// The cell over it dug too (room for a head): in this section's bits unless
							// it's the top layer (then the section above).
							int over = bit + 256;
							if (over < 4096 ? ((bits[over >> 6] >>> (over & 63)) & 1L) == 0 : !column.isDug(world, x, y + 1, z)) {
								continue;
							}
							// Reservoir sampling: every cell in range equally likely, however many there are.
							long key = BlockPos.asLong(x, y, z);
							if (count < CANDIDATES) {
								picked[count++] = key;
							} else {
								int j = random.nextInt(seen + 1);
								if (j < CANDIDATES) {
									picked[j] = key;
								}
							}
							seen++;
						}
					}
				}
			}
		}
		// Check them in random order; the first that passes is the spot.
		for (int left = count; left > 0; left--) {
			int i = random.nextInt(left);
			BlockPos pos = BlockPos.of(picked[i]);
			picked[i] = picked[left - 1];
			Spot spot = check(level, pos, host, players, r, random);
			if (spot != null) {
				return spot;
			}
		}
		return null;
	}

	private static @Nullable Spot check(ServerLevel level, BlockPos feet, ServerPlayer host, List<ServerPlayer> players, DeepRules r, RandomSource random) {
		// Room for a body: nothing solid and no water or lava in the way, and solid ground under it
		// that's a real block (Skyrim's own ground is gone where it was dug).
		for (int i = 0; i < BODY; i++) {
			BlockPos p = feet.above(i);
			BlockState state = level.getBlockState(p);
			if (!state.getCollisionShape(level, p).isEmpty() || !level.getFluidState(p).isEmpty() || !r.darkEnough(level.getBrightness(LightLayer.BLOCK, p))) {
				return null;
			}
		}
		BlockPos floor = feet.below();
		BlockState ground = level.getBlockState(floor);
		if (ground.isAir() || !ground.isFaceSturdy(level, floor, Direction.UP) || !level.getFluidState(floor).isEmpty()) {
			return null;
		}
		// How deep under Skyrim's land (unknown there: no spawn).
		SkyTri land = SkyDig.landAbove(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
		if (land == null) {
			return null;
		}
		double depth = land.heightAt(feet.getX() + 0.5, feet.getZ() + 0.5) - feet.getY();
		int kind = r.kindAt(depth, random.nextInt(1 << 16));
		if (kind == DeepRules.NONE) {
			return null;
		}
		// Nobody sees it appear, and nobody is right next to it.
		for (ServerPlayer player : players) {
			if (player.level() != level || player.isSpectator()) {
				continue;
			}
			double d2 = player.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			if (player == host ? !r.inSpawnRange(d2) : d2 < r.minDistance() * r.minDistance()) {
				return null;
			}
			if (sees(level, player, feet)) {
				return null;
			}
		}
		return new Spot(feet, kind, depth);
	}

	/** Can this player see any of a body standing at {@code feet} (in their view, no block in the way)? */
	private static boolean sees(ServerLevel level, ServerPlayer player, BlockPos feet) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		for (double h : SIGHT_HEIGHTS) {
			Vec3 point = new Vec3(feet.getX() + 0.5, feet.getY() + h, feet.getZ() + 0.5);
			if (!DeepRules.inViewCone(point.x - eye.x, point.y - eye.y, point.z - eye.z, look.x, look.y, look.z)) {
				continue;
			}
			HitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.MISS) {
				return true;
			}
		}
		return false;
	}
}
