package dev.skycraft.companion;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tamed companions: Minecraft's taming rules on Skyrim's animals, server side. Feeding bones to a Skyrim dog or wolf
 * stand-in tames it one time in three (hearts, or smoke), horse food tames a Skyrim horse by temper, and Skyrim makes a
 * tamed one the player's for real (EV_COMPANION: Skyrim's own animal follower, or the player's own horse). An empty
 * hand on a companion makes it sit (wait) or follow, sneaking for a horse (a plain click rides it, Skyrim's way); its
 * food heals it. Skyrim's existing animal followers and owned horses count as tamed. Which actors are what comes from
 * Skyrim in the actor table's flags; the rules are {@link CompanionRules}. Switch: companions. Log: SkyCraft-companion.
 */
public final class SkyCompanions {
	/** Horses fed but not tamed yet, by form id: their temper (the oldest forgotten past {@link #MAX_TEMPERS}). */
	private static final int MAX_TEMPERS = 64;
	private static final Map<Integer, Integer> TEMPER = new LinkedHashMap<>();
	/** The latest actor record of each Skyrim actor near the player (health, flags, name). */
	private static final Map<Integer, SkyLink.Actor> LATEST = new HashMap<>();
	/** Server tick of the player's last hit on each actor (an angry wolf won't take a bone). */
	private static final Map<Integer, Integer> LAST_HIT = new HashMap<>();
	/** Companions seen alive, by form id: their names, so a death can be told like Minecraft tells a pet's. */
	private static final Map<Integer, String> COMPANIONS = new HashMap<>();
	private static int tick;

	private SkyCompanions() {
	}

	/** The player hit this Skyrim actor's stand-in (SkyCombat). */
	public static void onHit(int formId, int serverTick) {
		LAST_HIT.put(formId, serverTick);
	}

	/** Each fresh read of Skyrim's actor table (SkyCombat, server thread). */
	public static void onActors(MinecraftServer server, List<SkyLink.Actor> actors, int serverTick) {
		tick = serverTick;
		LATEST.clear();
		boolean on = Features.on(Features.Feature.COMPANIONS);
		for (SkyLink.Actor a : actors) {
			LATEST.put(a.formId(), a);
			if (!on || !CompanionRules.companion(a.flags())) {
				continue;
			}
			if (!a.dead()) {
				COMPANIONS.put(a.formId(), CompanionRules.displayName(a.name(), a.flags()));
			} else if (COMPANIONS.remove(a.formId()) != null) {
				// Minecraft tells a tamed animal's owner when it dies; so does this.
				ServerPlayer host = host(server);
				if (host != null) {
					host.sendSystemMessage(Component.literal(a.name() + " died"));
				}
				SkyCraft.LOG.info("SkyCraft-companion: {} ({}) died", a.name(), hex(a.formId()));
			}
		}
		if (COMPANIONS.size() > 128) {
			COMPANIONS.clear(); // only ever the ones near the player; forget far ones rather than grow
		}
		if (serverTick % 200 == 0) {
			LAST_HIT.values().removeIf(t -> serverTick - t > CompanionRules.ANGRY_TICKS);
		}
	}

	/** Skyrim's link went away or the player left the mirror: the records are stale. */
	public static void reset() {
		LATEST.clear();
	}

	/** The stand-in's name for this actor: its Skyrim name, "(tamed)" after a companion's when the feature is on. */
	public static String displayName(SkyLink.Actor a) {
		return CompanionRules.companion(a.flags()) && Features.on(Features.Feature.COMPANIONS) ? CompanionRules.displayName(a.name(), a.flags()) : a.name();
	}

	/**
	 * A right click on a Skyrim actor's stand-in, on either side (SkyrimActorEntity.interact, after the merchants').
	 * Null when the companions feature has nothing to do with it, so the stand-in carries on as before (and with nothing
	 * else to use the click on, Skyrim activates the animal: a dog's dialogue, riding a horse). On the client a click this
	 * takes is SUCCESS, so it neither eats the food in hand nor reaches Skyrim as an activation; the server does the work.
	 */
	public static @Nullable InteractionResult interact(SkyrimActorEntity proxy, Player player, InteractionHand hand) {
		if (!Features.on(Features.Feature.COMPANIONS)) {
			return null;
		}
		int flags = proxy.getSkyFlags();
		ItemStack stack = player.getItemInHand(hand);
		String item = stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		boolean wolfFood = !stack.isEmpty() && stack.is(ItemTags.WOLF_FOOD);
		CompanionRules.Use use = CompanionRules.use(flags, hand == InteractionHand.MAIN_HAND, stack.isEmpty(), player.isSecondaryUseActive(), item, wolfFood);
		if (use == CompanionRules.Use.PASS) {
			return null;
		}
		if (player.level().isClientSide()) {
			// A guest's clicks would reach the host's Skyrim, whose animals these are: a guest's are left alone.
			return dev.skycraft.link.SkySpace.remoteWanted ? null : InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer host) || !dev.skycraft.net.SkyNet.isHost(host) || !SkyLink.active()
			|| !dev.skycraft.SkyDimension.inMirror(host)) {
			return null;
		}
		ServerLevel level = host.level();
		CompanionRules.Kind kind = CompanionRules.kind(flags);
		String name = proxy.skyrimName();
		SkyLink.Actor record = LATEST.get(proxy.formId());
		float health = record != null ? record.healthFrac() : 1.0F;
		switch (use) {
			case TAME_BONE -> {
				Integer hit = LAST_HIT.get(proxy.formId());
				if (CompanionRules.angry(hit != null ? tick - hit : -1)) {
					SkyCraft.LOG.info("SkyCraft-companion: {} is still angry after your hit; it won't take the bone", name);
					return InteractionResult.PASS;
				}
				stack.consume(1, host);
				if (CompanionRules.boneTames(level.getRandom()::nextInt)) {
					tamed(level, host, proxy, kind, name);
				} else {
					particles(level, proxy, ParticleTypes.SMOKE);
					SkyCraft.LOG.info("SkyCraft-companion: {} took the bone but isn't tamed yet", name);
				}
			}
			case FEED_HORSE -> {
				CompanionRules.HorseFood food = CompanionRules.horseFood(item);
				int temper = TEMPER.getOrDefault(proxy.formId(), 0);
				CompanionRules.HorseMeal meal = CompanionRules.feedUntamedHorse(temper, food, CompanionRules.hurt(health), level.getRandom()::nextInt);
				if (!meal.eats()) {
					return InteractionResult.PASS;
				}
				stack.consume(1, host);
				level.playSound(null, proxy.getX(), proxy.getY(), proxy.getZ(), SoundEvents.HORSE_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
				if (CompanionRules.hurt(health) && food.heal() > 0.0F) {
					heal(proxy, kind, CompanionRules.horseHealShare(food));
				}
				TEMPER.remove(proxy.formId());
				if (meal.tamed()) {
					tamed(level, host, proxy, kind, name);
				} else if (meal.tried()) {
					TEMPER.put(proxy.formId(), meal.temper());
					while (TEMPER.size() > MAX_TEMPERS) {
						Iterator<Integer> oldest = TEMPER.keySet().iterator();
						oldest.next();
						oldest.remove();
					}
					// Throws its rider in Minecraft: here it shies away from the hand that fed it.
					particles(level, proxy, ParticleTypes.SMOKE);
					level.playSound(null, proxy.getX(), proxy.getY(), proxy.getZ(), SoundEvents.HORSE_ANGRY, SoundSource.NEUTRAL, 1.0F, 1.0F);
					SkyCraft.LOG.info("SkyCraft-companion: {} ate the {} but isn't tamed yet (temper {} of {})", name, item, meal.temper(), CompanionRules.MAX_TEMPER);
				}
			}
			case HEAL -> {
				if (!CompanionRules.hurt(health)) {
					return InteractionResult.SUCCESS; // nothing eaten at full health, and the food stays in hand
				}
				float share;
				if (kind == CompanionRules.Kind.HORSE) {
					share = CompanionRules.horseHealShare(CompanionRules.horseFood(item));
					level.playSound(null, proxy.getX(), proxy.getY(), proxy.getZ(), SoundEvents.HORSE_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
				} else {
					FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
					share = CompanionRules.wolfHealShare(food != null ? food.nutrition() : 0);
					level.playSound(null, proxy.getX(), proxy.getY(), proxy.getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
				}
				stack.consume(1, host);
				heal(proxy, kind, share);
				SkyCraft.LOG.info("SkyCraft-companion: fed {} the {} ({}% of its health)", name, item, Math.round(share * 100.0F));
			}
			case TOGGLE -> {
				boolean follow = CompanionRules.waiting(flags);
				SkyLink.pushEvent(Proto.EV_COMPANION, proxy.formId(), 0.0F, 0.0F, 0.0F, 0.0F, follow ? Proto.COMPANION_FOLLOW : Proto.COMPANION_WAIT, kind.proto());
				host.sendOverlayMessage(Component.literal(follow ? name + " follows you" : name + " waits here"));
				SkyCraft.LOG.info("SkyCraft-companion: {} told to {}", name, follow ? "follow" : "wait");
			}
			default -> {
				return null;
			}
		}
		return InteractionResult.SUCCESS;
	}

	private static void tamed(ServerLevel level, ServerPlayer host, SkyrimActorEntity proxy, CompanionRules.Kind kind, String name) {
		SkyLink.pushEvent(Proto.EV_COMPANION, proxy.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.COMPANION_TAME, kind.proto());
		particles(level, proxy, ParticleTypes.HEART);
		host.sendOverlayMessage(Component.literal(kind == CompanionRules.Kind.HORSE ? name + " is your horse now (sneak and right click: follow or stay)"
			: name + " is tamed and sits (right click with an empty hand: follow)"));
		SkyCraft.LOG.info("SkyCraft-companion: tamed {} ({}, a {})", name, hex(proxy.formId()), kind.name().toLowerCase(java.util.Locale.ROOT));
	}

	private static void heal(SkyrimActorEntity proxy, CompanionRules.Kind kind, float share) {
		if (share > 0.0F) {
			SkyLink.pushEvent(Proto.EV_COMPANION, proxy.formId(), share, 0.0F, 0.0F, 0.0F, Proto.COMPANION_HEAL, kind.proto());
		}
	}

	/** Seven of them about the animal, as Minecraft's taming hearts and smoke. */
	private static void particles(ServerLevel level, SkyrimActorEntity proxy, ParticleOptions particle) {
		double w = proxy.getBbWidth() * 0.5, h = proxy.getBbHeight();
		level.sendParticles(particle, proxy.getX(), proxy.getY() + h * 0.5 + 0.5, proxy.getZ(), 7, w, h * 0.5, w, 0.02);
	}

	private static @Nullable ServerPlayer host(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(p)) {
				return p;
			}
		}
		return null;
	}

	private static String hex(int formId) {
		return String.format("%08X", formId);
	}
}
