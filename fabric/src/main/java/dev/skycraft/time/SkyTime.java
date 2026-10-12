package dev.skycraft.time;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Minecraft's clock follows Skyrim's, and a full night in a Minecraft bed passes the night in
 * Skyrim too. Minecraft's own time stays frozen (ADVANCE_TIME is off); this sets it from
 * Skyrim's game hour every second. Host only, server thread.
 */
public final class SkyTime {
	/** Re-set Minecraft's time when it is this many ticks behind Skyrim (about 1.5 Skyrim minutes). */
	private static final int DRIFT_TICKS = 25;
	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
	private static final Set<UUID> SLEPT = new HashSet<>();
	private static int lastSet = -1;

	private SkyTime() {
	}

	/** Minecraft day ticks (0 = 6:00, 6000 = noon, 18000 = midnight) for a Skyrim hour (0..24). */
	public static int ticksFor(float hour) {
		float h = ((hour - 6.0F) % 24.0F + 24.0F) % 24.0F;
		return Math.round(h * 1000.0F) % 24000;
	}

	/** Game hours a full sleep passes: to 6:00 the next morning at night, 8 hours in the day. */
	public static float hoursToSleep(float hour) {
		float h = ((6.0F - hour) % 24.0F + 24.0F) % 24.0F;
		return h > 12.0F || h < 1.0F ? 8.0F : h;
	}

	/** Shortest distance between two day tick values, across midnight. */
	static int drift(int a, int b) {
		int d = Math.abs(a - b) % 24000;
		return Math.min(d, 24000 - d);
	}

	public static void tick(MinecraftServer server, ServerPlayer host) {
		if (Features.on(Features.Feature.BED_SLEEP)) {
			sleep(host);
		}
		if (server.getTickCount() % 20 != 0 || !Features.on(Features.Feature.TIME_SYNC) || !SkyLink.readSkyState(SKY) || !SKY.inGame()) {
			return;
		}
		int target = ticksFor(SKY.gameHour);
		if (lastSet >= 0 && drift(target, lastSet) < DRIFT_TICKS) {
			return;
		}
		var source = server.createCommandSourceStack().withSuppressedOutput().withLevel(host.level());
		server.getCommands().performPrefixedCommand(source, "time set " + target);
		if (lastSet < 0 || drift(target, lastSet) > 1000) {
			SkyCraft.LOG.info("SkyCraft-time: Minecraft time set to {} for Skyrim hour {}", target, String.format("%.2f", SKY.gameHour));
		}
		lastSet = target;
	}

	private static void sleep(ServerPlayer player) {
		if (!player.isSleeping()) {
			SLEPT.remove(player.getUUID());
			return;
		}
		if (!player.isSleepingLongEnough() || !SLEPT.add(player.getUUID())) {
			return;
		}
		float hour = SkyLink.readSkyState(SKY) ? SKY.gameHour : 22.0F;
		float hours = hoursToSleep(hour);
		SkyLink.pushEvent(Proto.EV_SLEEP, 0, hours, 0.0F, 0.0F, 0.0F, 0);
		player.stopSleepInBed(true, true);
		lastSet = -1;
		SkyCraft.LOG.info("SkyCraft-time: slept in a Minecraft bed at Skyrim hour {}, passing {} hours", String.format("%.2f", hour), hours);
	}

	public static void reset() {
		lastSet = -1;
		SLEPT.clear();
	}
}
