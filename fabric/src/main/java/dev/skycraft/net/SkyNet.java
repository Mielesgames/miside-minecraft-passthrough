package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: every player has their own Skyrim, talking to their own Minecraft client. The host's
 * Skyrim reaches the host's integrated server through shared memory; a guest's Skyrim reaches the
 * host's server through these packets instead.
 */
public final class SkyNet {
	private SkyNet() {
	}

	/** Guest -> server: the guest's Skyrim hit them (as proto::InputEvent kInHurt). */
	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> guest: the guest died in Minecraft, so their Skyrim player dies too. */
	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC = StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> client: where the host's world keeps each Skyrim world's blocks (SkySpace), as
	 * {@code worlds[i]} has space {@code spaces[i]}. All of them on joining, then again whenever one is
	 * added. A guest's client places its own Skyrim's worlds by it.
	 */
	public record Spaces(List<Integer> worlds, List<Integer> spaces) implements CustomPacketPayload {
		public static final Type<Spaces> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "spaces"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Spaces> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT.apply(ByteBufCodecs.list(1 << 16)), Spaces::worlds,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(1 << 16)), Spaces::spaces,
			Spaces::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: a guest's Skyrim is in a world the host's table doesn't have yet; give it a space. */
	public record SpaceAsk(int world) implements CustomPacketPayload {
		public static final Type<SpaceAsk> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "space_ask"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SpaceAsk> CODEC = StreamCodec.composite(ByteBufCodecs.INT, SpaceAsk::world, SpaceAsk::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The host's world's spaces, for sending. */
	public static Spaces spaces() {
		var table = dev.skycraft.link.SkySpace.table();
		List<Integer> worlds = new java.util.ArrayList<>(), spaces = new java.util.ArrayList<>();
		if (table != null) {
			table.all().forEach((world, space) -> {
				worlds.add(world);
				spaces.add(space);
			});
		}
		return new Spaces(worlds, spaces);
	}

	/** Every player gets the spaces again (one was added). Server thread. */
	public static void sendSpaces(net.minecraft.server.MinecraftServer server) {
		Spaces payload = spaces();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, Spaces.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Spaces.TYPE, Spaces.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SpaceAsk.TYPE, SpaceAsk.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SpaceAsk.TYPE, (payload, context) -> context.server().execute(() -> {
			if (payload.world() != 0 && dev.skycraft.link.SkySpace.table() != null) {
				dev.skycraft.link.SkySpace.space(payload.world(), null); // a new one tells everyone (SkySpace onChange)
				if (ServerPlayNetworking.canSend(context.player(), Spaces.TYPE)) {
					ServerPlayNetworking.send(context.player(), spaces()); // and an old one this guest missed
				}
			}
		}));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.getPlayer(), Spaces.TYPE)) {
				ServerPlayNetworking.send(handler.getPlayer(), spaces());
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(Hurt.TYPE, Hurt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				if (dev.skycraft.SkyDimension.inMirror(player)) {  // Skyrim's ground is only in the mirror
					SkyDig.open(player, payload.world(), payload.pos(), payload.material());
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> {
				if (dev.skycraft.SkyDimension.inMirror(player)) {
					SkyDig.reveal(player, payload.world(), payload.cells(), materials);
				}
			});
		});
		PayloadTypeRegistry.clientboundPlay().register(Died.TYPE, Died.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Hurt.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// A hit's worth of damage, whatever the guest's client claims (friends only, but still).
			float damage = Math.max(0.0F, Math.min(payload.skyrimDamage(), 10000.0F));
			context.server().execute(() -> SkyCombat.hurtPlayer(player, payload.kind(), damage, payload.attackerFormId(), payload.flags()));
		});
	}

	/** True if this player plays on this machine (their Skyrim is on the shared-memory link). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.nameAndId());
	}
}
