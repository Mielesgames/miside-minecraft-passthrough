package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * A hiding spot of the host game (MiSide: a locker, a stroller, under a table) is a seat
 * to Minecraft's player: an armor stand nobody sees, at the spot. Getting off it (Shift) is how the
 * player leaves the hiding spot; the host watches for that.
 */
public final class HidingSeat {
	private static ArmorStand seat;

	private HidingSeat() {
	}

	public static void set(ServerPlayer player, boolean hide, double x, double y, double z) {
		if (seat != null) {
			// Removing the seat puts its rider down.
			seat.discard();
			seat = null;
		}
		if (!hide) {
			return;
		}
		ArmorStand fresh = new ArmorStand(player.level(), x, y, z);
		fresh.setInvisible(true);
		fresh.setNoGravity(true);
		fresh.setSilent(true);
		if (!player.level().addFreshEntity(fresh)) {
			return;
		}
		seat = fresh;
		boolean seated = player.startRiding(fresh, true, false);
		if (seated) {
			// A rider sits on top of an armor stand, almost two blocks up: on a sofa that was the roof
			// (29 Oct). The stand goes down so that the player's feet hang half a block under the seat.
			double off = player.getY() - (y - 0.5);
			fresh.setPos(x, y - off, z);
			fresh.positionRider(player);
		}
		SkyCraft.LOG.info("MiSideCraft: hiding seat at {}, {}, {}: {}", x, y, z, seated ? "the player sits" : "the player could not sit");
	}
}
