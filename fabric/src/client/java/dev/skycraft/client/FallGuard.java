package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SafeSpot;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyTri;
import dev.skycraft.world.SkyWater;
import dev.skycraft.world.TriCollider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the player out of the void under Skyrim's world. The ground under the land is revealed as
 * blocks only as it's dug next to, so a cell that never was (an older dig, a misjudged one) can
 * still open into nothing; and Skyrim can put the player somewhere solid (inside a rock, under
 * blocks). Two guards:
 * <ul>
 * <li>{@link #arrival}: where Skyrim has just put the player (a load door, a resync, a respawn), if
 * they don't fit there or have nothing to stand on, they go to the nearest open spot with ground.</li>
 * <li>{@link #tick}: a player in the void (inside ground that should be solid, under bedrock, or
 * falling with nothing at all under them) goes back to where they last stood safely (or the nearest
 * open ground over them), and the dug cells around get their neighbours looked at again, so the
 * hole is closed.</li>
 * </ul>
 */
public final class FallGuard {
	private FallGuard() {
	}

	/** Ticks between two rescues at most. */
	private static final int COOLDOWN = 40;
	/**
	 * In an interior (or anywhere without land), falling this far with nothing under is the void
	 * (a real drop that long kills anyway).
	 */
	private static final double FALL_LIMIT = 32.0;
	/** Nothing (Skyrim's geometry or blocks) this far under a falling player: the void. Skyrim sends this far down. */
	private static final int VOID_DEPTH = 3 * SkyCollision.REGION_SIZE;
	/** Arriving this far over the ground is fine (Skyrim's feet can hang over a step). */
	private static final double ARRIVAL_DROP = 3.0;
	private static final double E = 1.0E-4;
	/** How high the player steps up (Minecraft's step height): surfaces below it are ground, not walls. */
	private static final double STEP = 0.6;
	/** Where in the player's height to check for Skyrim's ground or rock around them. */
	private static final double[] BODY = { 0.25, 0.75 };

	/** Where the player last stood safely (forgotten when Skyrim moves them or they change world). */
	private static @Nullable Vec3 lastSafe;
	private static int lastSafeWorld = Integer.MIN_VALUE;
	private static int lastSafeTeleport = Integer.MIN_VALUE;
	private static int ticks;
	private static int lastRescue = -COOLDOWN;
	private static boolean saidNowhere;

	/** Every client tick, after the teleport hold. */
	static void tick(Minecraft minecraft) {
		ticks++;
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		SkyLink.SkyState sky = SkyClient.sky();
		if (player == null || level == null || !SkyClient.linked() || !SkyCollision.active() || SkyClient.holding() || !sky.inGame() || sky.loading()
			|| player.isSpectator() || player.getAbilities().flying || player.isDeadOrDying() || MinecraftView.away()) {
			return;
		}
		int world = SkyDigClient.world();
		if (world != lastSafeWorld || sky.teleportSeq != lastSafeTeleport) {
			lastSafe = null;
			lastSafeWorld = world;
			lastSafeTeleport = sky.teleportSeq;
		}
		Vec3 feet = player.position();
		String why = trouble(level, player, world, feet);
		if (why == null) {
			if (player.onGround() && ticks % 10 == 0) {
				lastSafe = feet;
			}
			return;
		}
		if (ticks - lastRescue < COOLDOWN) {
			return;
		}
		lastRescue = ticks;
		rescue(minecraft, level, player, world, feet, why);
	}

	/** Why the player is in the void, or null if they're fine. */
	private static @Nullable String trouble(ClientLevel level, LocalPlayer player, int world, Vec3 feet) {
		if (feet.y < level.getMinY() + 16) {
			return "the bottom of the world";
		}
		double bodyY = feet.y + player.getBbHeight() * 0.5;
		SkyTri land = SkyDig.landAbove(feet.x, bodyY, feet.z);
		if (land != null) {
			double depth = land.heightAt(feet.x, feet.z) - bodyY;
			if (depth > SkyDig.BEDROCK_DEPTH + 1.0) {
				return String.format("under bedrock (%.0f blocks under the land)", depth);
			}
			// Skyrim's land is a height field with nothing open under it: a player more than a little
			// under it is in a dug cell, or inside ground that was never revealed (a hole).
			BlockPos body = BlockPos.containing(feet.x, bodyY, feet.z);
			if (depth > 2.0 && !SkyDig.isDug(level, world, body) && level.getBlockState(body).isAir()) {
				return String.format("inside the ground (%.0f blocks under the land, never dug)", depth);
			}
			return null;
		}
		// No land over them (an interior): falling a long way with nothing at all below.
		if (player.fallDistance > FALL_LIMIT && !player.isInWater()) {
			int bx = (int) Math.floor(feet.x), by = (int) Math.floor(feet.y), bz = (int) Math.floor(feet.z);
			if (SkyCollision.isKnown(bx, by - VOID_DEPTH, bz) && !SkyCollision.hasSolidBelow(bx, by, bz, VOID_DEPTH) && !blockBelow(level, bx, by, bz, VOID_DEPTH)) {
				return String.format("falling into nothing (%.0f blocks so far)", player.fallDistance);
			}
		}
		return null;
	}

	private static boolean blockBelow(ClientLevel level, int x, int y, int z, int depth) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= depth; dy++) {
			if (!level.getBlockState(p.set(x, y - dy, z)).isAir()) {
				return true;
			}
		}
		return false;
	}

	private static void rescue(Minecraft minecraft, ClientLevel level, LocalPlayer player, int world, Vec3 feet, String why) {
		SafeSpot.World w = world(level, player, world);
		double[] to = null;
		Vec3 from = lastSafe;
		if (from != null && from.distanceToSqr(feet) < 64.0 * 64.0) {
			to = SafeSpot.find(w, from.x, from.y, from.z, 4, 3, 2);
		}
		if (to == null) {
			// Up on the land over them (or the nearest open ground round about).
			SkyTri land = SkyDig.landAbove(feet.x, feet.y + 0.9, feet.z);
			double startY = land != null ? land.heightAt(feet.x, feet.z) : feet.y;
			to = SafeSpot.find(w, feet.x, startY, feet.z, 8, 8, 4);
		}
		if (to == null && from != null && from.distanceToSqr(feet) < 64.0 * 64.0) {
			to = new double[] { from.x, from.y, from.z }; // it was safe when they stood there
		}
		if (to == null) {
			if (!saidNowhere) {
				saidNowhere = true;
				SkyCraft.LOG.warn("SkyCraft: the player is {} at {} but there's no safe ground near to put them on", why, fmt(feet.x, feet.y, feet.z));
			}
			return;
		}
		saidNowhere = false;
		SkyCraft.LOG.info("SkyCraft: the player was {} at {}; put back on safe ground at {}", why, fmt(feet.x, feet.y, feet.z), fmt(to[0], to[1], to[2]));
		moveTo(minecraft, to[0], to[1], to[2]);
		// Close the hole: whatever around here should have been revealed is looked at again.
		SkyDigClient.healAround(BlockPos.containing(to[0], to[1], to[2]), 4);
		if (from != null) {
			SkyDigClient.healAround(BlockPos.containing(from.x, from.y, from.z), 4);
		}
	}

	/**
	 * Where Skyrim put the player (released from the teleport hold), or if that's inside something
	 * solid or over a drop, the nearest open spot with ground under it.
	 */
	static Vec3 arrival(Minecraft minecraft, LocalPlayer player, Vec3 at) {
		ClientLevel level = minecraft.level;
		if (level == null || !SkyCollision.active() || player.isSpectator() || player.getAbilities().flying) {
			return at;
		}
		SafeSpot.World w = world(level, player, SkyDigClient.world());
		boolean free = w.free(at.x, at.y, at.z);
		double water = SkyWater.surfaceAt((int) Math.floor(at.x), (int) Math.floor(at.z));
		if (free && (!Double.isNaN(w.ground(at.x, at.y + E, at.z, ARRIVAL_DROP)) || (!Double.isNaN(water) && water > at.y))) {
			return at;
		}
		double[] to = SafeSpot.find(w, at.x, at.y, at.z, 6, 8, 3);
		if (to == null) {
			SkyCraft.LOG.info("SkyCraft: Skyrim put the player {} at {}, and there's no open ground near; leaving them there", free ? "over a drop" : "inside something solid",
				fmt(at.x, at.y, at.z));
			return at;
		}
		SkyCraft.LOG.info("SkyCraft: Skyrim put the player {} at {}; moved to open ground at {}", free ? "over a drop" : "inside something solid", fmt(at.x, at.y, at.z),
			fmt(to[0], to[1], to[2]));
		return new Vec3(to[0], to[1], to[2]);
	}

	/** Moves the player here now, on the client and (in the host's own world) on the server. */
	static void moveTo(Minecraft minecraft, double x, double y, double z) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		player.setPos(x, y, z);
		player.xo = x;
		player.yo = y;
		player.zo = z;
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleportTo(x, y, z);
					sp.resetFallDistance();
				}
			});
		}
	}

	private static String fmt(double x, double y, double z) {
		return String.format("%.2f %.2f %.2f", x, y, z);
	}

	/**
	 * The world as SafeSpot sees it, for this player: their box is free if no Minecraft block (or
	 * dug hole's wall) is in it, none of Skyrim's surfaces crosses it, and it isn't inside Skyrim's
	 * ground or rock where nothing was dug; ground is the top of a block or of a walkable surface.
	 */
	private static SafeSpot.World world(ClientLevel level, LocalPlayer player, int world) {
		double half = player.getBbWidth() * 0.5, height = player.getBbHeight();
		SkyDig.Probe probe = new SkyDig.Probe();
		List<SkyTri> tris = new ArrayList<>();
		return new SafeSpot.World() {
			@Override
			public boolean free(double x, double y, double z) {
				AABB box = new AABB(x - half + E, y + E, z - half + E, x + half - E, y + height - E, z + half - E);
				if (!level.noCollision(player, box)) {
					return false;
				}
				// Skyrim's surfaces through the box above step height (the ground under the feet can rise
				// into the box's corners on a slope, as when walking there).
				AABB body = new AABB(box.minX, y + STEP, box.minZ, box.maxX, box.maxY, box.maxZ);
				tris.clear();
				SkyCollision.trianglesNear(body, tris);
				for (SkyTri t : tris) {
					if (!t.stairHelper && SafeSpot.triangleHitsBox(t, body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ)) {
						return false;
					}
				}
				probe.around(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
				for (double f : BODY) {
					double py = y + height * f;
					if (!SkyDig.isDug(level, world, BlockPos.containing(x, py, z)) && probe.test(x, py, z) != SkyDig.AIR) {
						return false; // inside Skyrim's ground or rock (or a building)
					}
				}
				return true;
			}

			@Override
			public double ground(double x, double y, double z, double drop) {
				double best = Double.NaN;
				for (VoxelShape shape : level.getBlockCollisions(player, new AABB(x - half, y - drop, z - half, x + half, y, z + half))) {
					if (shape.isEmpty()) {
						continue;
					}
					double top = shape.max(Direction.Axis.Y);
					if (top <= y + 1.0E-6 && top >= y - drop && (Double.isNaN(best) || top > best)) {
						best = top;
					}
				}
				tris.clear();
				SkyCollision.trianglesNear(new AABB(x - half - 0.5, y - drop - 0.5, z - half - 0.5, x + half + 0.5, y + 0.1, z + half + 0.5), tris);
				tris.removeIf(t -> !t.walkable);
				double g = TriCollider.groundAt(tris, x, y, z, 0.0);
				if (!Double.isNaN(g) && g >= y - drop && (Double.isNaN(best) || g > best)) {
					best = g;
				}
				return best;
			}
		};
	}
}
