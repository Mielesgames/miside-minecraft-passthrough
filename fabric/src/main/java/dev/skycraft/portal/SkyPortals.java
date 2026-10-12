package dev.skycraft.portal;

import dev.skycraft.SkyCraft;
import dev.skycraft.SkyDimension;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import dev.skycraft.portal.PortalStore.Arrival;
import dev.skycraft.portal.PortalStore.Portal;
import dev.skycraft.portal.PortalStore.Target;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.worlds.WorldArrivals;
import dev.skycraft.worlds.Worlds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The portal network, Minecraft side. Portals are cracks between places that resemble each other:
 * an obsidian frame lit in the Skyrim mirror world makes real Nether portal blocks (vanilla lights
 * them there, since the mirror is the overworld dimension), but stepping in goes nowhere until the
 * portal is bound to exactly one other portal, for good. A filled soul gem used on a lit portal
 * attunes to it (the gem becomes an attuned soul gem that remembers it); the attuned gem used on a
 * second lit portal binds the two and is spent.
 *
 * <p>Keys open a portal in the mirror onto one of the Seed family's worlds (PortalKeys): a Daedra heart onto
 * the Nether, the tonal key onto Tamriel Wilds. The key builds the partner portal at that world's arrival
 * point (PortalBuilder) and binds the pair; it's spent. The partner leads back to the portal in Skyrim. A
 * portal lit at the Time-Wound, with an Elder Scroll in Skyrim's inventory (SkyState.carried), opens one way
 * onto Home (TimeWound). In Home the Far Lands' strata drop Seamstone; the first one picked up is the ending
 * (Ending), and from then on Seamstone used on any lit portal opens it onto any place the player has been
 * (Destinations, SeamMenu): sygmensional travel.
 *
 * <p>Vanilla Nether travel never happens: mixin.NetherPortalBlockMixin hands {@link #reach} the moment
 * Minecraft's usual portal wait is over, in every dimension, and returns no destination. A portal outside
 * the network leads nowhere. How a trip goes:
 * <ul>
 * <li>Skyrim place to Skyrim place: the host's portal sends EV_PORTAL_TRAVEL and Skyrim moves its player to
 * the partner behind a loading screen, like a load door (Travel.cpp); Minecraft follows Skyrim's world
 * change as it always does (the teleport resync). Portals a few blocks apart in the same Skyrim world are a
 * plain Minecraft teleport instead.
 * <li>Into another world (the Nether, Tamriel Wilds, Home): Minecraft teleports the player there; the
 * Minecraft view takes over (Skyrim parks its player where they stood, View.cpp).
 * <li>Between other worlds: a Minecraft teleport; Skyrim stays parked.
 * <li>Back to a Skyrim place from another world: if Skyrim's parked player is in that portal's Skyrim world
 * and near it, Minecraft teleports the player into the mirror in front of it and Skyrim's player follows
 * (the view's handover: Minecraft leads). Otherwise Skyrim goes first: EV_PORTAL_TRAVEL moves the parked
 * player to the portal (another worldspace, an interior, or far across the same one, behind a loading
 * screen), and once SkyState shows them there Minecraft teleports its player into the mirror at the same
 * spot. Skyrim then sees its player came back in another world than the one it parked them in and leads
 * the handover, putting Minecraft's player where its own already stands.
 * </ul>
 *
 * <p>The network is kept in the world's {@code skycraft-portals.txt} (PortalStore), keyed by Skyrim
 * world and Minecraft position in the mirror (each Skyrim world's blocks sit in a space of their own,
 * SkySpace, so a mirror position already names its world) and by dimension elsewhere. Breaking a frame (or the portal going out any other way) takes it
 * out of the network and unbinds its partner. Everything is under the {@code portals} switch. Server thread
 * only. Log tag {@code SkyCraft-portal}.
 */
public final class SkyPortals {
	private static final String FILE = "skycraft-portals.txt";
	/** Same Skyrim world and closer than this: Minecraft moves the player itself (no loading screen). */
	static final double LOCAL_HOP = 16.0;
	/** Back from another world: Skyrim's parked player this close (blocks, flat) to the portal just follows. */
	static final double FOLLOW_RANGE = 64.0;
	/** Skyrim's player this close (blocks, flat) to the spot counts as arrived. */
	private static final double ARRIVED = 8.0;
	/** One trip at most this often (a loading screen follows each). */
	private static final long TRIP_GAP_NANOS = 3_000_000_000L;
	/** Skyrim not moving the player within this long means it refused. */
	private static final long PENDING_NANOS = 8_000_000_000L;
	/** Skyrim not arriving within this long (loading screens included) on the way back from another world. */
	private static final long RETURN_NANOS = 45_000_000_000L;
	/** A place chosen with the Seamstone stays chosen this long. */
	private static final long SEAM_NANOS = 300_000_000_000L;
	/** Portals this close to a player (blocks, flat) are checked now and then for having gone out. */
	private static final double CHECK_RANGE = 64.0;
	private static final int HINT_REPEAT_TICKS = 60;
	/** Places tried round an arrival point for a key's partner portal. */
	private static final int PARTNER_SITES = 9;

	private static final String KEY_ID = "skycraft_portal";
	private static final String KEY_WORLD = "skycraft_portal_world";
	private static final String KEY_X = "skycraft_portal_x";
	private static final String KEY_Y = "skycraft_portal_y";
	private static final String KEY_Z = "skycraft_portal_z";
	static final String KEY_PLACE = "skycraft_portal_place";

	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
	private static @Nullable MinecraftServer storeServer;
	private static @Nullable PortalStore store;
	private static long lastTrip;
	private static boolean trippedOnce;
	/** The portal rectangle the host stands in (its min corner, axis and dimension), 0 when none; and ticks since its hint. */
	private static long hintShape;
	private static int hintAge;

	/** A trip the host asked Skyrim for that hasn't started yet (Skyrim can refuse silently). */
	private record Pending(int from, int to, String place, UUID player, long since, int worldId, int teleportSeq, int epoch) {
	}

	private static @Nullable Pending pending;

	/** On the way back from another world: Skyrim moves its parked player first, then Minecraft follows. */
	private record Return(UUID player, int to, int world, double[] exit, String place, long since) {
	}

	private static @Nullable Return returning;

	/** A portal the Seamstone opened onto a chosen place, for one trip. */
	private record Seam(String dimension, int world, Shape shape, Target target, String name, long since) {
	}

	private static final Map<UUID, Seam> SEAMS = new HashMap<>();

	/** A place picked in the Seamstone's chest, applied on the next tick (SeamMenu). */
	private record Choice(UUID player, String dimension, int world, Shape shape, Target target) {
	}

	private static final ArrayDeque<Choice> CHOICES = new ArrayDeque<>();

	/** A lit portal's rectangle of portal blocks, as found in the world. */
	record Shape(int x, int y, int z, int axis, int width, int height) {
	}

	private SkyPortals() {
	}

	public static void init() {
		PortalItems.init();
		ServerTickEvents.END_SERVER_TICK.register(SkyPortals::tick);
		PlayerBlockBreakEvents.AFTER.register(SkyPortals::afterBreak);
		UseBlockCallback.EVENT.register(SkyPortals::useOnPortal);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			store = null;
			storeServer = null;
			pending = null;
			returning = null;
			hintShape = 0;
			SEAMS.clear();
			CHOICES.clear();
		});
		if (!Worlds.NETHER.identifier().toString().equals(PortalKeys.NETHER) || !Worlds.TAMRIEL_WILDS.identifier().toString().equals(PortalKeys.TAMRIEL_WILDS)
			|| !Worlds.HOME.identifier().toString().equals(PortalKeys.HOME)) {
			SkyCraft.LOG.warn("SkyCraft-portal: PortalKeys' dimension ids don't match Worlds'; keys and the ending won't find their worlds");
		}
	}

	private static boolean on() {
		return Features.on(Features.Feature.PORTALS);
	}

	/**
	 * Whether Nether portals in this level belong to the network: in every level. In the Skyrim mirror they
	 * lead between Skyrim places; elsewhere nowhere unless the network says so, never by vanilla's rules.
	 */
	public static boolean intercepts(Level level) {
		return true;
	}

	/** Whether obsidian frames light in this level although vanilla wouldn't (Tamriel Wilds and Home). Any thread. */
	public static boolean lightsPortals(Level level) {
		ResourceKey<Level> d = level.dimension();
		return (d.equals(Worlds.TAMRIEL_WILDS) || d.equals(Worlds.HOME)) && on();
	}

	/** Whether this level is the Skyrim mirror (Minecraft's overworld dimension). */
	static boolean isMirror(Level level) {
		return level.dimension() == Level.OVERWORLD;
	}

	/** The store's name for a level: {@link PortalStore#MIRROR} for the mirror, else the dimension id. */
	static String dimensionOf(Level level) {
		return isMirror(level) ? PortalStore.MIRROR : level.dimension().identifier().toString();
	}

	/** The loaded level for a store dimension name, or null (a world without it, or a bad id). */
	private static @Nullable ServerLevel levelOf(MinecraftServer server, String dimension) {
		if (dimension.isEmpty()) {
			return server.getLevel(Level.OVERWORLD);
		}
		Identifier id = Identifier.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	// ---- store --------------------------------------------------------------------------------

	private static PortalStore store(MinecraftServer server) {
		PortalStore s = store;
		if (s == null || storeServer != server) {
			s = load(server);
			store = s;
			storeServer = server;
		}
		return s;
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE).normalize();
	}

	private static PortalStore load(MinecraftServer server) {
		Path path = path(server);
		if (!Files.exists(path)) {
			return new PortalStore();
		}
		try {
			List<String> problems = new ArrayList<>();
			PortalStore s = PortalStore.parse(Files.readAllLines(path, StandardCharsets.UTF_8), problems);
			problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-portal: {} {}", path, p));
			SkyCraft.LOG.info("SkyCraft-portal: {} portals known in this world", s.size());
			return s;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-portal: couldn't read {}: {}", path, e.toString());
			return new PortalStore();
		}
	}

	private static void save(MinecraftServer server, PortalStore s) {
		Path path = path(server);
		Path tmp = path.resolveSibling(FILE + ".tmp");
		try {
			Files.write(tmp, s.lines(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-portal: couldn't write {}: {}", path, e.toString());
		}
	}

	// ---- shapes and names ---------------------------------------------------------------------

	/** The lit portal this block belongs to, or null if it isn't a portal block. */
	static @Nullable Shape shapeAt(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.NETHER_PORTAL)) {
			return null;
		}
		Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
		BlockUtil.FoundRectangle r = BlockUtil.getLargestRectangleAround(pos, axis, PortalStore.MAX_SIZE, Direction.Axis.Y, PortalStore.MAX_SIZE,
			p -> level.getBlockState(p) == state);
		return new Shape(r.minCorner.getX(), r.minCorner.getY(), r.minCorner.getZ(), axis == Direction.Axis.X ? PortalStore.AXIS_X : PortalStore.AXIS_Z,
			r.axis1Size, r.axis2Size);
	}

	/** The network's record of exactly this lit portal in this Skyrim world, or null. */
	private static @Nullable Portal recordOf(PortalStore s, int world, Shape shape) {
		Portal p = s.at(world, shape.x(), shape.y(), shape.z());
		return p != null && p.sameShape(shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height()) ? p : null;
	}

	/** The network's record of exactly this lit portal in another dimension, or null. */
	private static @Nullable Portal recordIn(PortalStore s, String dimension, Shape shape) {
		Portal p = s.in(dimension, shape.x(), shape.y(), shape.z());
		return p != null && p.sameShape(shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height()) ? p : null;
	}

	/** The record of this lit portal where it is: the mirror (for Skyrim's current world, SKY) or another dimension. */
	private static @Nullable Portal recordHere(PortalStore s, Level level, Shape shape) {
		return isMirror(level) ? recordOf(s, SKY.worldId, shape) : recordIn(s, dimensionOf(level), shape);
	}

	/**
	 * Whether all of a portal's blocks are still lit portal blocks of its axis. Null when not known
	 * without loading its chunks ({@code load} false and they aren't loaded); with {@code load} true its
	 * chunks are loaded to look.
	 */
	private static @Nullable Boolean intact(ServerLevel level, Portal p, boolean load) {
		BlockPos a = new BlockPos(p.x(), p.y(), p.z()), b = new BlockPos(p.maxX(), p.maxY(), p.maxZ());
		if (!load && (!level.isLoaded(a) || !level.isLoaded(b))) {
			return null;
		}
		Direction.Axis axis = p.axis() == PortalStore.AXIS_X ? Direction.Axis.X : Direction.Axis.Z;
		for (BlockPos q : BlockPos.betweenClosed(a, b)) {
			BlockState s = level.getBlockState(q);
			if (!s.is(Blocks.NETHER_PORTAL) || s.getValue(NetherPortalBlock.AXIS) != axis) {
				return false;
			}
		}
		return true;
	}

	static String placeName(Portal p) {
		if (!p.place().isEmpty()) {
			return p.place();
		}
		if (!p.inMirror()) {
			return PortalKeys.worldName(p.dimension());
		}
		return p.interior() ? "an unnamed place indoors" : "the wilds";
	}

	private static String where(Portal p) {
		String world = p.inMirror() ? String.format("%s %08X", p.interior() ? "cell" : "world", p.world()) : p.dimension();
		return String.format("portal %d (%s, %s, %d %d %d, %s %dx%d)", p.id(), placeName(p), world, p.x(), p.y(), p.z(), p.axis() == PortalStore.AXIS_X ? "X" : "Z",
			p.width(), p.height());
	}

	/** What a target is called in a sentence. */
	private static String targetName(PortalStore s, Target t) {
		if (t instanceof Target.ToPortal(int id)) {
			Portal p = s.get(id);
			return p != null ? placeName(p) : "a portal that's gone";
		}
		return PortalKeys.worldName(((Target.ToArrival) t).dimension());
	}

	/** Skyrim's player is somewhere Minecraft can tie a portal to (in game, not loading). Fills SKY. */
	private static boolean skyrimHere() {
		return SkyLink.readSkyState(SKY) && SKY.inGame() && !SKY.loading() && SKY.worldId != 0;
	}

	/**
	 * The record of this lit mirror portal for the host's Skyrim world, made now if it's new. A record of the
	 * same blocks that no longer matches them (the frame was rebuilt) is dropped first. Null, with the
	 * reason told, when the portal was opened in another Skyrim world.
	 */
	private static @Nullable Portal register(PortalStore s, Shape shape, ServerPlayer player) {
		Portal mine = recordOf(s, SKY.worldId, shape);
		if (mine != null) {
			return mine;
		}
		Portal other = s.anyAt(shape.x(), shape.y(), shape.z());
		if (other != null && other.sameShape(shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height())) {
			player.sendOverlayMessage(Component.literal("This portal opened in " + placeName(other) + ". It doesn't answer here."));
			return null;
		}
		// Records whose blocks this portal now fills are gone (the frame was rebuilt another size).
		for (Portal p : s.overlapping(shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height())) {
			s.remove(p.id());
			SkyCraft.LOG.info("SkyCraft-portal: {} was rebuilt differently; dropped from the network", where(p));
		}
		int flags = (SKY.place & Proto.PLACE_INTERIOR) != 0 ? PortalStore.INTERIOR : 0;
		int side = PortalStore.sideOf(shape.axis(), shape.x(), shape.z(), player.getX(), player.getZ());
		Portal p = s.add(SKY.worldId, flags, shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height(), side, SKY.town);
		SkyCraft.LOG.info("SkyCraft-portal: new {}", where(p));
		return p;
	}

	/** The record of this lit portal in another dimension, made now if it's new (records it overlaps are dropped). */
	private static Portal registerIn(PortalStore s, String dimension, Shape shape, ServerPlayer player) {
		Portal mine = recordIn(s, dimension, shape);
		if (mine != null) {
			return mine;
		}
		for (Portal p : s.overlapping(dimension, shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height())) {
			s.remove(p.id());
			SkyCraft.LOG.info("SkyCraft-portal: {} was rebuilt differently; dropped from the network", where(p));
		}
		int side = PortalStore.sideOf(shape.axis(), shape.x(), shape.z(), player.getX(), player.getZ());
		Portal p = s.addIn(dimension, shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height(), side, PortalKeys.placeName(dimension));
		SkyCraft.LOG.info("SkyCraft-portal: new {}", where(p));
		return p;
	}

	/** {@link #register} or {@link #registerIn}, for where the portal is. */
	private static @Nullable Portal registerHere(PortalStore s, Level level, Shape shape, ServerPlayer player) {
		return isMirror(level) ? register(s, shape, player) : registerIn(s, dimensionOf(level), shape, player);
	}

	/** What a portal is bound to, for "already bound to ...". */
	private static String leadName(PortalStore s, Portal p) {
		Target t = s.lead(p);
		return t != null ? targetName(s, t) : "another";
	}

	// ---- soul gems ----------------------------------------------------------------------------

	private static ItemStack attunedGem(Portal p) {
		ItemStack stack = new ItemStack(PortalItems.ATTUNED_SOUL_GEM);
		CompoundTag tag = new CompoundTag();
		tag.putInt(KEY_ID, p.id());
		tag.putInt(KEY_WORLD, p.world());
		tag.putInt(KEY_X, p.x());
		tag.putInt(KEY_Y, p.y());
		tag.putInt(KEY_Z, p.z());
		tag.putString(KEY_PLACE, placeName(p));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** The portal an attuned gem remembers, if it is still that portal (same id, world and place). */
	private static @Nullable Portal attunedTo(PortalStore s, ItemStack gem) {
		CustomData data = gem.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag();
		Portal p = s.get(tag.getIntOr(KEY_ID, 0));
		if (p == null || !p.inMirror() || p.world() != tag.getIntOr(KEY_WORLD, 0) || p.x() != tag.getIntOr(KEY_X, Integer.MIN_VALUE)
			|| p.y() != tag.getIntOr(KEY_Y, Integer.MIN_VALUE) || p.z() != tag.getIntOr(KEY_Z, Integer.MIN_VALUE)) {
			return null;
		}
		return p;
	}

	/** One of the held gems becomes {@code into} (the rest of the stack stays in hand). */
	private static void swapOne(ServerPlayer player, InteractionHand hand, ItemStack held, ItemStack into) {
		if (held.getCount() <= 1) {
			player.setItemInHand(hand, into);
			return;
		}
		held.shrink(1);
		player.getInventory().add(into);
		if (!into.isEmpty()) {
			ItemEntity dropped = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), into);
			dropped.setDefaultPickUpDelay();
			player.level().addFreshEntity(dropped);
		}
	}

	private static void sparkle(ServerLevel level, Portal p, boolean bound) {
		double[] c = p.center();
		level.sendParticles(bound ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL, c[0], c[1], c[2], bound ? 80 : 40, 0.4, p.height() / 3.0, 0.4, 0.2);
		level.playSound(null, BlockPos.containing(c[0], c[1], c[2]), bound ? SoundEvents.BEACON_ACTIVATE : SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS,
			1.0F, bound ? 0.8F : 1.2F);
	}

	/**
	 * A soul gem used on a lit portal (SoulGemItem, server side): a filled gem attunes to it, an attuned
	 * gem binds it to the portal the gem remembers. Only the host's Skyrim can feel the cracks, and only
	 * portals in the mirror take a gem.
	 */
	static InteractionResult useGem(UseOnContext context, boolean attunedGem) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		Shape shape = shapeAt(level, context.getClickedPos());
		if (shape == null) {
			return InteractionResult.PASS;
		}
		if (!on()) {
			player.sendOverlayMessage(Component.literal("Portals are switched off (portals in skycraft-features.txt)."));
			return InteractionResult.FAIL;
		}
		if (!isMirror(level)) {
			player.sendOverlayMessage(Component.literal("The soul gem stays dark. Only a crack in Skyrim's mirror takes it."));
			return InteractionResult.FAIL;
		}
		if (!SkyNet.isHost(player)) {
			player.sendOverlayMessage(Component.literal("The portal answers only to the host's Skyrim."));
			return InteractionResult.FAIL;
		}
		if (!skyrimHere()) {
			player.sendOverlayMessage(Component.literal("The portal flickers. Try again in a moment."));
			return InteractionResult.FAIL;
		}
		MinecraftServer server = level.getServer();
		PortalStore s = store(server);
		InteractionHand hand = context.getHand();
		ItemStack held = context.getItemInHand();
		int side = PortalStore.sideOf(shape.axis(), shape.x(), shape.z(), player.getX(), player.getZ());
		if (!attunedGem) {
			Portal p = register(s, shape, player);
			if (p == null) {
				save(server, s);
				return InteractionResult.FAIL;
			}
			if (s.leads(p)) {
				player.sendOverlayMessage(Component.literal("This portal is already bound to " + leadName(s, p) + "."));
				save(server, s);
				return InteractionResult.FAIL;
			}
			p = p.withSide(side);
			s.put(p);
			save(server, s);
			swapOne(player, hand, held, attunedGem(p));
			sparkle(level, p, false);
			player.sendOverlayMessage(Component.literal("The soul gem is attuned to this portal. Use it on a second lit portal to bind the two."));
			SkyCraft.LOG.info("SkyCraft-portal: {} attuned a soul gem to {}", player.getName().getString(), where(p));
			return InteractionResult.SUCCESS;
		}
		Portal a = attunedTo(s, held);
		if (a == null) {
			swapOne(player, hand, held, new ItemStack(PortalItems.FILLED_SOUL_GEM));
			player.sendOverlayMessage(Component.literal("The portal this gem was attuned to is gone. Its soul is still in the gem."));
			SkyCraft.LOG.info("SkyCraft-portal: {} used a soul gem attuned to a portal that's gone; it's a filled gem again", player.getName().getString());
			return InteractionResult.SUCCESS;
		}
		if (s.leads(a)) {
			swapOne(player, hand, held, new ItemStack(PortalItems.FILLED_SOUL_GEM));
			player.sendOverlayMessage(Component.literal("The portal at " + placeName(a) + " is bound already. The gem's soul is free again."));
			return InteractionResult.SUCCESS;
		}
		Portal q = register(s, shape, player);
		if (q == null) {
			save(server, s);
			return InteractionResult.FAIL;
		}
		if (q.id() == a.id()) {
			player.sendOverlayMessage(Component.literal("The gem is attuned to this very portal. Use it on another one."));
			save(server, s);
			return InteractionResult.FAIL;
		}
		if (s.leads(q)) {
			player.sendOverlayMessage(Component.literal("This portal is already bound to " + leadName(s, q) + "."));
			save(server, s);
			return InteractionResult.FAIL;
		}
		if (!Boolean.TRUE.equals(intact(level, a, true))) {
			s.remove(a.id());
			save(server, s);
			swapOne(player, hand, held, new ItemStack(PortalItems.FILLED_SOUL_GEM));
			player.sendOverlayMessage(Component.literal("The portal at " + placeName(a) + " has gone out. The gem's soul is free again."));
			SkyCraft.LOG.info("SkyCraft-portal: {} went out before it was bound; dropped", where(a));
			return InteractionResult.SUCCESS;
		}
		q = q.withSide(side);
		s.put(q);
		PortalStore.Bind result = s.bind(a.id(), q.id());
		save(server, s);
		if (result != PortalStore.Bind.BOUND) {
			player.sendOverlayMessage(Component.literal("The portals won't bind."));
			SkyCraft.LOG.warn("SkyCraft-portal: binding {} and {} failed: {}", where(a), where(q), result);
			return InteractionResult.FAIL;
		}
		held.shrink(1);
		sparkle(level, q, true);
		player.sendOverlayMessage(Component.literal("Bound for good: this portal and the one at " + placeName(a) + " open onto each other."));
		SkyCraft.LOG.info("SkyCraft-portal: {} bound {} and {}", player.getName().getString(), where(a), where(q));
		return InteractionResult.SUCCESS;
	}

	// ---- keys and the Seamstone -----------------------------------------------------------------

	/**
	 * Fabric's UseBlockCallback: a key (Daedra heart, tonal key) or Seamstone used on a lit portal. The
	 * client only knows it's a portal and takes the click (so it isn't passed on to Skyrim as an activation);
	 * the server decides. Anything else passes.
	 */
	private static InteractionResult useOnPortal(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (player.isSpectator()) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty()) {
			return InteractionResult.PASS;
		}
		String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		PortalKeys.Key key = PortalKeys.key(id);
		boolean seamstone = id.equals(PortalKeys.SEAMSTONE);
		if ((key == null && !seamstone) || !level.getBlockState(hit.getBlockPos()).is(Blocks.NETHER_PORTAL)) {
			return InteractionResult.PASS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		return seamstone ? useSeamstone(serverLevel, sp, hit.getBlockPos()) : useKey(serverLevel, sp, held, hit.getBlockPos(), key);
	}

	/** A key used on a lit portal: builds the partner in the key's world and binds the pair (PortalKeys). */
	private static InteractionResult useKey(ServerLevel level, ServerPlayer player, ItemStack held, BlockPos pos, PortalKeys.Key key) {
		Shape shape = shapeAt(level, pos);
		if (shape == null) {
			return InteractionResult.PASS;
		}
		MinecraftServer server = level.getServer();
		ServerLevel to = levelOf(server, key.dimension());
		boolean here = isMirror(level) && SkyNet.isHost(player) && skyrimHere();
		PortalKeys.Use use = PortalKeys.check(on(), SkyDimension.viewEnabled(), isMirror(level), SkyNet.isHost(player), here, to != null, false);
		String world = PortalKeys.worldName(key.dimension());
		if (use != PortalKeys.Use.OPEN) {
			player.sendOverlayMessage(Component.literal(switch (use) {
				case OFF -> "Portals are switched off (portals in skycraft-features.txt).";
				case NOT_MIRROR -> "Nothing stirs. Only a crack in Skyrim's mirror takes a key.";
				case VIEW_OFF -> "The crack won't open onto " + world + " while the Minecraft view is off (minecraft_view in skycraft-features.txt).";
				case NOT_HOST -> "The portal answers only to the host's Skyrim.";
				case SKYRIM_AWAY -> "The portal flickers. Try again in a moment.";
				case NO_DIMENSION -> "This world has no " + world + " (the worlds data in the mod adds it).";
				default -> "This portal is already bound.";
			}));
			return InteractionResult.FAIL;
		}
		PortalStore s = store(server);
		Portal p = register(s, shape, player);
		if (p == null) {
			save(server, s);
			return InteractionResult.FAIL;
		}
		if (s.leads(p)) {
			player.sendOverlayMessage(Component.literal("This portal is already bound to " + leadName(s, p) + "."));
			save(server, s);
			return InteractionResult.FAIL;
		}
		p = p.withSide(PortalStore.sideOf(shape.axis(), shape.x(), shape.z(), player.getX(), player.getZ()));
		s.put(p);
		Portal q = buildPartner(s, to, key.dimension());
		if (q == null) {
			save(server, s);
			player.sendOverlayMessage(Component.literal("The crack won't open onto " + world + " right now."));
			return InteractionResult.FAIL;
		}
		PortalStore.Bind result = s.bind(p.id(), q.id());
		save(server, s);
		if (result != PortalStore.Bind.BOUND) {
			player.sendOverlayMessage(Component.literal("The portals won't bind."));
			SkyCraft.LOG.warn("SkyCraft-portal: binding {} and {} failed: {}", where(p), where(q), result);
			return InteractionResult.FAIL;
		}
		held.consume(1, player);
		sparkle(level, p, true);
		sparkle(to, q, true);
		player.sendOverlayMessage(Component.literal(key.spent() + " This portal opens onto " + world + " now."));
		SkyCraft.LOG.info("SkyCraft-portal: {} used {} on {}: bound to the new {}", player.getName().getString(), key.item(), where(p), where(q));
		return InteractionResult.SUCCESS;
	}

	/**
	 * Builds a new partner portal in {@code to} at its arrival point (or beside the portals already there)
	 * and records it, unbound. Null if no site would light.
	 */
	private static @Nullable Portal buildPartner(PortalStore s, ServerLevel to, String dimension) {
		Arrival a = arrivalOf(s, to, dimension);
		PortalPlan base = PortalPlan.behind((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()), a.yaw());
		for (PortalPlan plan : base.sites(PARTNER_SITES)) {
			if (!plan.freeIn(s, dimension) || PortalBuilder.occupied(to, plan)) {
				continue;
			}
			Shape built = PortalBuilder.build(to, plan);
			if (built == null) {
				continue;
			}
			Portal q = s.addIn(dimension, built.x(), built.y(), built.z(), built.axis(), built.width(), built.height(), plan.side(),
				PortalKeys.placeName(dimension));
			SkyCraft.LOG.info("SkyCraft-portal: built {}", where(q));
			return q;
		}
		SkyCraft.LOG.warn("SkyCraft-portal: no site for a portal in {} round {} {} {}", dimension, a.x(), a.y(), a.z());
		return null;
	}

	/**
	 * Where travellers arrive in a dimension: the recorded spot, else found now and recorded (the Nether's is
	 * searched for, PortalBuilder.netherArrival; Tamriel Wilds and Home come from Worlds).
	 */
	private static Arrival arrivalOf(PortalStore s, ServerLevel level, String dimension) {
		Arrival a = s.arrival(dimension);
		if (a != null) {
			return a;
		}
		if (level.dimension() == Level.NETHER) {
			double[] n = PortalBuilder.netherArrival(level);
			a = new Arrival(n[0], n[1], n[2], (float) n[3]);
		} else {
			Optional<Worlds.Arrival> w = Worlds.arrival(level.dimension());
			if (w.isPresent()) {
				Vec3 v = WorldArrivals.resolve(level, w.get());
				a = new Arrival(v.x, v.y, v.z, w.get().yaw());
			} else {
				Vec3 v = WorldArrivals.resolve(level, new Worlds.Arrival(level.dimension(), 0.5, 0.0, 0.5, 0.0f, 0.0f, Worlds.Placement.SURFACE));
				a = new Arrival(v.x, v.y, v.z, 0.0f);
			}
		}
		s.setArrival(dimension, a);
		save(level.getServer(), s);
		SkyCraft.LOG.info("SkyCraft-portal: arrival point in {}: {} {} {} facing {}", dimension, String.format("%.1f", a.x()), String.format("%.1f", a.y()),
			String.format("%.1f", a.z()), (int) a.yaw());
		return a;
	}

	/** Seamstone used on a lit portal: opens the chest of places the player has been (Destinations). */
	private static InteractionResult useSeamstone(ServerLevel level, ServerPlayer player, BlockPos pos) {
		Shape shape = shapeAt(level, pos);
		if (shape == null) {
			return InteractionResult.PASS;
		}
		if (!on()) {
			player.sendOverlayMessage(Component.literal("Portals are switched off (portals in skycraft-features.txt)."));
			return InteractionResult.FAIL;
		}
		if (!SkyNet.isHost(player)) {
			player.sendOverlayMessage(Component.literal("The portal answers only to the host's Skyrim."));
			return InteractionResult.FAIL;
		}
		if (!skyrimHere()) {
			player.sendOverlayMessage(Component.literal("The portal flickers. Try again in a moment."));
			return InteractionResult.FAIL;
		}
		MinecraftServer server = level.getServer();
		PortalStore s = store(server);
		Portal here = registerHere(s, level, shape, player);
		save(server, s);
		if (here == null && isMirror(level)) {
			return InteractionResult.FAIL; // opened in another Skyrim world (told)
		}
		List<Destinations.Entry> entries = Destinations.list(s, here);
		if (entries.isEmpty()) {
			player.sendOverlayMessage(Component.literal("The Seamstone hums, but knows no other place yet."));
			return InteractionResult.FAIL;
		}
		String dimension = dimensionOf(level);
		int world = isMirror(level) ? SKY.worldId : 0;
		SeamMenu.open(player, entries, (sp, target) -> CHOICES.add(new Choice(sp.getUUID(), dimension, world, shape, target)));
		SkyCraft.LOG.info("SkyCraft-portal: {} holds the Seamstone to {}: {} places to choose from", player.getName().getString(),
			here != null ? where(here) : "a portal", entries.size());
		return InteractionResult.SUCCESS;
	}

	/** Places picked in the Seamstone's chest: the chest closes, and that portal opens onto the place for one trip. */
	private static void applyChoices(MinecraftServer server) {
		Choice c;
		while ((c = CHOICES.poll()) != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(c.player());
			if (player == null) {
				continue;
			}
			if (player.containerMenu instanceof SeamMenu) {
				player.closeContainer();
			}
			PortalStore s = store(server);
			String name = targetName(s, c.target());
			SEAMS.put(c.player(), new Seam(c.dimension(), c.world(), c.shape(), c.target(), name, System.nanoTime()));
			player.sendOverlayMessage(Component.literal("The crack opens onto " + name + ". Step in."));
			ServerLevel level = levelOf(server, c.dimension());
			if (level != null) {
				Shape sh = c.shape();
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, sh.x() + 0.5, sh.y() + sh.height() / 2.0, sh.z() + 0.5, 60, 0.5, sh.height() / 3.0, 0.5, 0.15);
				level.playSound(null, new BlockPos(sh.x(), sh.y(), sh.z()), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 0.6F);
			}
			SkyCraft.LOG.info("SkyCraft-portal: {} opened the portal at {} {} {} in {} onto {}", player.getName().getString(), c.shape().x(), c.shape().y(),
				c.shape().z(), c.dimension().isEmpty() ? String.format("the mirror (%08X)", c.world()) : c.dimension(), name);
		}
	}

	/** The place the Seamstone opened this portal onto for this player, if it still holds. */
	private static @Nullable Seam seamFor(ServerPlayer player, String dimension, Shape shape) {
		Seam seam = SEAMS.get(player.getUUID());
		if (seam == null) {
			return null;
		}
		if (System.nanoTime() - seam.since() > SEAM_NANOS) {
			SEAMS.remove(player.getUUID());
			return null;
		}
		boolean same = seam.dimension().equals(dimension) && seam.shape().equals(shape) && (!dimension.isEmpty() || seam.world() == SKY.worldId);
		return same ? seam : null;
	}

	// ---- travel -------------------------------------------------------------------------------

	/**
	 * An entity has stood in a portal for Minecraft's usual wait (NetherPortalBlockMixin; vanilla travel is
	 * off everywhere). The host goes where the portal leads (a Seamstone's choice first); nothing else goes
	 * anywhere.
	 */
	public static void reach(ServerLevel level, Entity entity, BlockPos entry) {
		if (!(entity instanceof ServerPlayer player) || !on() || player.isSpectator()) {
			return;
		}
		if (!SkyNet.isHost(player)) {
			player.sendOverlayMessage(Component.literal("The portal answers only to the host's Skyrim."));
			return;
		}
		long now = System.nanoTime();
		if (pending != null || returning != null || (trippedOnce && now - lastTrip < TRIP_GAP_NANOS)) {
			return;
		}
		if (!skyrimHere() || SKY.menuOpen()) {
			return;
		}
		Shape shape = shapeAt(level, entry);
		if (shape == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		PortalStore s = store(server);
		Portal from = recordHere(s, level, shape);
		if (from == null) {
			// Not stood in long enough for a hint (creative mode's portal wait is a tick): it joins now.
			from = registerHere(s, level, shape, player);
			save(server, s);
		}
		if (from != null && isMirror(level)) {
			atTimeWound(level, s, from, player, false);
		}
		Seam seam = seamFor(player, dimensionOf(level), shape);
		Target target = seam != null ? seam.target() : from != null ? s.lead(from) : null;
		if (target == null) {
			if (from != null && from.bound()) {
				s.put(from.withPartner(0)); // its partner's record is missing
				save(server, s);
			}
			player.sendOverlayMessage(Component.literal(isMirror(level) ? "This portal is not bound. Bind it with filled soul gems, or open it with a key."
				: "This crack leads nowhere."));
			return;
		}
		if (seam != null) {
			SEAMS.remove(player.getUUID());
		}
		goTo(server, level, player, from, target);
	}

	/** Takes the host from a portal in {@code level} to a target. */
	private static void goTo(MinecraftServer server, ServerLevel level, ServerPlayer player, @Nullable Portal from, Target target) {
		PortalStore s = store(server);
		String fromName = from != null ? where(from) : "an unlisted portal in " + (isMirror(level) ? "the mirror" : dimensionOf(level));
		if (target instanceof Target.ToArrival(String dimension)) {
			ServerLevel to = levelOf(server, dimension);
			if (to == null) {
				player.sendOverlayMessage(Component.literal("This world has no " + PortalKeys.worldName(dimension) + "."));
				return;
			}
			if (!SkyDimension.viewEnabled()) {
				player.sendOverlayMessage(Component.literal("The crack won't open onto " + PortalKeys.worldName(dimension)
					+ " while the Minecraft view is off (minecraft_view in skycraft-features.txt)."));
				return;
			}
			Arrival a = arrivalOf(s, to, dimension);
			PortalBuilder.pad(to, (int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z())); // only if it isn't safe already
			goDimension(server, player, to, a.x(), a.y(), a.z(), a.yaw(), PortalKeys.worldName(dimension), fromName);
			return;
		}
		Portal to = s.get(((Target.ToPortal) target).id());
		ServerLevel toLevel = to != null ? levelOf(server, to.dimension()) : null;
		if (to == null || toLevel == null) {
			player.sendOverlayMessage(Component.literal("That portal is gone."));
			return;
		}
		if (!Boolean.TRUE.equals(intact(toLevel, to, true))) {
			s.remove(to.id());
			save(server, s);
			player.sendOverlayMessage(Component.literal("The portal at " + placeName(to) + " has gone out."));
			SkyCraft.LOG.info("SkyCraft-portal: {} leads to {}, which has gone out; dropped", fromName, where(to));
			return;
		}
		double[] exit = to.exit();
		if (!to.inMirror()) {
			goDimension(server, player, toLevel, exit[0], exit[1], exit[2], (float) exit[3], placeName(to), fromName);
		} else if (isMirror(player.level())) {
			goMirror(level, player, from, to, exit, fromName);
		} else {
			goBack(server, player, to, exit, fromName);
		}
	}

	/** Mirror to mirror: a short hop, or Skyrim moves its player and Minecraft follows. */
	private static void goMirror(ServerLevel level, ServerPlayer player, @Nullable Portal from, Portal to, double[] exit, String fromName) {
		long now = System.nanoTime();
		lastTrip = now;
		trippedOnce = true;
		double dx = exit[0] - player.getX(), dy = exit[1] - player.getY(), dz = exit[2] - player.getZ();
		if (to.world() == SKY.worldId && dx * dx + dy * dy + dz * dz < LOCAL_HOP * LOCAL_HOP) {
			// A short hop in the same Skyrim world: Minecraft moves the player, Skyrim's follows as usual.
			player.teleportTo(level, exit[0], exit[1], exit[2], Set.<Relative>of(), (float) exit[3], player.getXRot(), true);
			player.setPortalCooldown();
			level.playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.4F, 1.4F);
			SkyCraft.LOG.info("SkyCraft-portal: {} hopped from {} to {}", player.getName().getString(), fromName, where(to));
			return;
		}
		int flags = to.interior() ? Proto.PORTAL_INTERIOR : 0;
		SkyLink.pushEventAt(Proto.EV_PORTAL_TRAVEL, to.world(), exit[0], exit[1], exit[2], (float) exit[3], flags, SKY.worldId); // in to's world's terms
		pending = new Pending(from != null ? from.id() : 0, to.id(), placeName(to), player.getUUID(), now, SKY.worldId, SKY.teleportSeq, SkyCollision.epoch());
		player.connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, BlockPos.ZERO, 0, false));
		player.sendOverlayMessage(Component.literal("The portal pulls you toward " + placeName(to) + "..."));
		SkyCraft.LOG.info("SkyCraft-portal: {} steps through {} to {}: Skyrim moves them to {} {} {} facing {}", player.getName().getString(), fromName, where(to),
			String.format("%.2f", exit[0]), String.format("%.2f", exit[1]), String.format("%.2f", exit[2]), (int) exit[3]);
	}

	/** Into another world (or between other worlds): a Minecraft teleport; the Minecraft view takes over. */
	private static void goDimension(MinecraftServer server, ServerPlayer player, ServerLevel to, double x, double y, double z, float yaw, String name,
		String fromName) {
		if (!SkyDimension.viewEnabled()) {
			player.sendOverlayMessage(Component.literal("The crack won't open onto " + name + " while the Minecraft view is off (minecraft_view in skycraft-features.txt)."));
			return;
		}
		lastTrip = System.nanoTime();
		trippedOnce = true;
		player.teleportTo(to, x, y, z, Set.<Relative>of(), yaw, player.getXRot(), true);
		player.setPortalCooldown();
		player.connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, BlockPos.ZERO, 0, false));
		PortalStore s = store(server);
		if (s.visit(to.dimension().identifier().toString())) {
			save(server, s);
		}
		SkyCraft.LOG.info("SkyCraft-portal: {} steps through {} into {} at {} {} {} ({})", player.getName().getString(), fromName, to.dimension().identifier(),
			String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z), name);
	}

	/**
	 * From another world back to a portal in the mirror. Skyrim's parked player in that Skyrim world and near
	 * it: Minecraft goes, Skyrim follows. Else Skyrim goes first (EV_PORTAL_TRAVEL) and Minecraft follows once
	 * SkyState shows Skyrim's player there ({@link #checkReturn}).
	 */
	private static void goBack(MinecraftServer server, ServerPlayer player, Portal to, double[] exit, String fromName) {
		ServerLevel mirror = server.getLevel(Level.OVERWORLD);
		if (mirror == null) {
			return;
		}
		long now = System.nanoTime();
		lastTrip = now;
		trippedOnce = true;
		double dx = SKY.x - exit[0], dz = SKY.z - exit[2];
		if (to.world() == SKY.worldId && dx * dx + dz * dz < FOLLOW_RANGE * FOLLOW_RANGE) {
			enterMirror(mirror, player, exit);
			SkyCraft.LOG.info("SkyCraft-portal: {} steps through {} back to {}; Skyrim's player ({} blocks away) follows", player.getName().getString(), fromName,
				where(to), (int) Math.sqrt(dx * dx + dz * dz));
			return;
		}
		int flags = to.interior() ? Proto.PORTAL_INTERIOR : 0;
		SkyLink.pushEventAt(Proto.EV_PORTAL_TRAVEL, to.world(), exit[0], exit[1], exit[2], (float) exit[3], flags, SKY.worldId); // in to's world's terms
		returning = new Return(player.getUUID(), to.id(), to.world(), exit, placeName(to), now);
		player.sendOverlayMessage(Component.literal("The crack pulls Skyrim toward " + placeName(to) + "..."));
		SkyCraft.LOG.info("SkyCraft-portal: {} steps through {} back to {}: Skyrim moves its parked player from {} first", player.getName().getString(), fromName,
			where(to), String.format("%08X", SKY.worldId));
	}

	private static void enterMirror(ServerLevel mirror, ServerPlayer player, double[] exit) {
		player.teleportTo(mirror, exit[0], exit[1], exit[2], Set.<Relative>of(), (float) exit[3], player.getXRot(), true);
		player.setPortalCooldown();
		player.connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, BlockPos.ZERO, 0, false));
	}

	/**
	 * Did Skyrim take the host? Any sign of a move counts (a loading screen, another world or cell, a
	 * teleport, Skyrim clearing its world for Minecraft); none within PENDING_NANOS means it refused.
	 */
	private static void checkPending(MinecraftServer server) {
		Pending p = pending;
		if (p == null) {
			return;
		}
		boolean moved = SkyCollision.epoch() != p.epoch();
		if (SkyLink.readSkyState(SKY)) {
			moved |= SKY.loading() || SKY.worldId != p.worldId() || SKY.teleportSeq != p.teleportSeq();
		}
		ServerPlayer player = server.getPlayerList().getPlayer(p.player());
		if (moved) {
			pending = null;
			if (player != null) {
				player.setPortalCooldown();
			}
			SkyCraft.LOG.info("SkyCraft-portal: Skyrim is taking the player through portal {} to portal {} ({})", p.from(), p.to(), p.place());
			return;
		}
		if (System.nanoTime() - p.since() < PENDING_NANOS) {
			return;
		}
		pending = null;
		if (player != null) {
			player.sendOverlayMessage(Component.literal("The portal won't take you right now. Step out and try again."));
		}
		SkyCraft.LOG.info("SkyCraft-portal: Skyrim didn't move the player through portal {} to portal {}: it refused the request", p.from(), p.to());
	}

	/** On the way back from another world: once Skyrim's player stands at the portal, Minecraft's follows. */
	private static void checkReturn(MinecraftServer server) {
		Return r = returning;
		if (r == null) {
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(r.player());
		if (player == null || isMirror(player.level())) {
			returning = null; // gone, or back some other way
			return;
		}
		if (SkyLink.readSkyState(SKY) && SKY.inGame() && !SKY.loading() && SKY.worldId == r.world()) {
			double dx = SKY.x - r.exit()[0], dz = SKY.z - r.exit()[2];
			if (dx * dx + dz * dz < ARRIVED * ARRIVED) {
				returning = null;
				ServerLevel mirror = server.getLevel(Level.OVERWORLD);
				if (mirror != null) {
					enterMirror(mirror, player, r.exit());
					SkyCraft.LOG.info("SkyCraft-portal: Skyrim's player is at portal {} ({}); Minecraft's follows into the mirror", r.to(), r.place());
				}
				return;
			}
		}
		if (System.nanoTime() - r.since() > RETURN_NANOS) {
			returning = null;
			player.sendOverlayMessage(Component.literal("Skyrim never reached " + r.place() + ". Step out and try again."));
			SkyCraft.LOG.info("SkyCraft-portal: Skyrim's player never reached portal {} ({}) (in {} now); the way back is off", r.to(), r.place(),
				String.format("%08X", SKY.worldId));
		}
	}

	// ---- every tick ---------------------------------------------------------------------------

	private static void tick(MinecraftServer server) {
		if (!CHOICES.isEmpty()) {
			applyChoices(server);
		}
		if (!SkyLink.active()) {
			pending = null;
			returning = null;
			hintShape = 0;
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !on()) {
			return;
		}
		int t = server.getTickCount();
		if (pending != null && t % 5 == 0) {
			checkPending(server);
		}
		if (returning != null && t % 5 == 0) {
			checkReturn(server);
		}
		if (t % 5 == 0) {
			for (ServerPlayer p : players) {
				if (SkyNet.isHost(p)) {
					hint(p.level(), p);
				}
			}
		}
		if (t % 20 == 0) {
			visitsAndEndings(server, players);
		}
		if (t % 40 == 0) {
			checkLit(server, players);
		}
	}

	/** The portal block the player is standing in (feet or head), or null. */
	private static @Nullable BlockPos portalBlockIn(ServerLevel level, ServerPlayer player) {
		var box = player.getBoundingBox().deflate(1.0E-3);
		BlockPos from = BlockPos.containing(box.minX, box.minY, box.minZ), to = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static boolean carriesSeamstone(Player player) {
		return player.getInventory().contains(stack -> stack.is(PortalItems.SEAMSTONE));
	}

	/**
	 * Standing in a portal: it joins the network (a place the player has stood at a portal, for the
	 * Seamstone); at the Time-Wound with an Elder Scroll it opens onto Home; then where it leads, once, or
	 * (leading nowhere) how to open it, every few seconds.
	 */
	private static void hint(ServerLevel level, ServerPlayer host) {
		BlockPos in = host.isSpectator() ? null : portalBlockIn(level, host);
		Shape shape = in != null ? shapeAt(level, in) : null;
		if (shape == null) {
			hintShape = 0;
			return;
		}
		long key = BlockPos.asLong(shape.x(), shape.y(), shape.z()) ^ ((long) shape.axis() << 63) ^ ((long) level.dimension().identifier().hashCode() << 20);
		boolean fresh = key != hintShape;
		hintAge = fresh ? 0 : hintAge + 5;
		hintShape = key;
		if (pending != null || returning != null || !skyrimHere()) {
			return;
		}
		MinecraftServer server = level.getServer();
		PortalStore s = store(server);
		boolean mirror = isMirror(level);
		Portal from = recordHere(s, level, shape);
		if (from == null && fresh) {
			from = registerHere(s, level, shape, host);
			save(server, s);
		}
		if (mirror && from != null) {
			boolean tell = fresh || hintAge >= HINT_REPEAT_TICKS;
			TimeWound.Opening opening = atTimeWound(level, s, from, host, tell);
			if (opening == TimeWound.Opening.OPENS || opening == TimeWound.Opening.NO_SCROLL) {
				if (tell) {
					hintAge = 0;
				}
				return; // told
			}
		}
		Seam seam = seamFor(host, dimensionOf(level), shape);
		Target to = seam != null ? seam.target() : from != null ? s.lead(from) : null;
		if (to != null) {
			if (fresh) {
				host.sendOverlayMessage(Component.literal("This portal opens onto " + targetName(s, to) + "."));
			}
			return;
		}
		if (!fresh && hintAge < HINT_REPEAT_TICKS) {
			return;
		}
		hintAge = 0;
		Portal other = mirror && from == null ? s.anyAt(shape.x(), shape.y(), shape.z()) : null;
		if (other != null && other.sameShape(shape.x(), shape.y(), shape.z(), shape.axis(), shape.width(), shape.height())) {
			host.sendOverlayMessage(Component.literal("This portal opened in " + placeName(other) + ". It doesn't answer here."));
		} else if (carriesSeamstone(host)) {
			host.sendOverlayMessage(Component.literal("Hold the Seamstone to this portal to choose where it opens."));
		} else if (mirror) {
			host.sendOverlayMessage(Component.literal("This portal is not bound. Bind it with filled soul gems, or open it with a key."));
		} else {
			host.sendOverlayMessage(Component.literal("This crack leads nowhere."));
		}
	}

	/**
	 * A lit portal at the Time-Wound (Tamriel, the Throat of the World's summit) opens one way onto Home when
	 * Skyrim's player carries an Elder Scroll (SkyState.carried); without one the player is told what it wants
	 * ({@code tell}). Returns what the check found (OPENS only when it opened just now).
	 */
	private static TimeWound.Opening atTimeWound(ServerLevel level, PortalStore s, Portal p, ServerPlayer host, boolean tell) {
		TimeWound.Opening opening = TimeWound.check(SKY.worldId, p.center(), SKY.carried, s.leads(p));
		if (opening == TimeWound.Opening.OPENS && s.openOneWay(p.id(), PortalKeys.HOME) == PortalStore.Bind.BOUND) {
			save(level.getServer(), s);
			sparkle(level, p, true);
			host.sendOverlayMessage(Component.literal("The Elder Scroll stirs in your pack. Through the Time-Wound the crack opens onto Home."));
			SkyCraft.LOG.info("SkyCraft-portal: {} at the Time-Wound with an Elder Scroll: opens one way onto {}", where(p), PortalKeys.HOME);
		} else if (opening == TimeWound.Opening.NO_SCROLL && tell) {
			host.sendOverlayMessage(Component.literal("The Time-Wound pulls at this portal. It wants something to read it by: an Elder Scroll."));
		}
		return opening;
	}

	/** The worlds the host has been to (for the Seamstone's list), and anyone's first Seamstone (the ending). */
	private static void visitsAndEndings(MinecraftServer server, List<ServerPlayer> players) {
		PortalStore s = store(server);
		boolean changed = false;
		for (ServerPlayer p : players) {
			if (SkyNet.isHost(p) && !isMirror(p.level())) {
				changed |= s.visit(dimensionOf(p.level()));
			}
			if (!p.isSpectator() && !s.sawEnding(p.getUUID()) && carriesSeamstone(p)) {
				s.seeEnding(p.getUUID());
				changed = true;
				ending(server, s, p);
			}
		}
		if (changed) {
			save(server, s);
		}
	}

	/** The first Seamstone: past the Far Lands. A title, a few lines, and the crack opens (Ending). */
	private static void ending(MinecraftServer server, PortalStore s, ServerPlayer player) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(Ending.FADE_IN, Ending.STAY, Ending.FADE_OUT));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(Ending.SUBTITLE)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Ending.TITLE)));
		for (String line : Ending.LINES) {
			player.sendSystemMessage(Component.literal(line));
		}
		player.level().playSound(null, player.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.6F, 1.3F);
		SkyCraft.LOG.info("SkyCraft-portal: {} picked up their first Seamstone: the ending", player.getName().getString());
		openTheCrack(server, s);
	}

	/** At the ending a lit portal opens in the mouth of the crack, so there is always a way out of Home. */
	private static void openTheCrack(MinecraftServer server, PortalStore s) {
		ServerLevel home = levelOf(server, PortalKeys.HOME);
		if (home == null) {
			return;
		}
		PortalPlan plan = Ending.seamPortal();
		int[] in = plan.at(0, 0, 0);
		for (Portal p : s.within(PortalKeys.HOME, in[0], in[1], in[2], in[0], in[1], in[2], 0)) {
			if (Boolean.TRUE.equals(intact(home, p, true))) {
				return; // already open
			}
		}
		if (PortalBuilder.occupied(home, plan)) {
			return; // a portal the player lit there themselves
		}
		Shape built = PortalBuilder.build(home, plan);
		if (built == null) {
			return;
		}
		for (Portal old : s.overlapping(PortalKeys.HOME, built.x(), built.y(), built.z(), built.axis(), built.width(), built.height())) {
			s.remove(old.id());
		}
		Portal p = s.addIn(PortalKeys.HOME, built.x(), built.y(), built.z(), built.axis(), built.width(), built.height(), plan.side(), Ending.SEAM_PORTAL_PLACE);
		sparkle(home, p, true);
		SkyCraft.LOG.info("SkyCraft-portal: the crack opened: {}", where(p));
	}

	/** Portals near anyone that have gone out (an explosion, a piston, a missed break) leave the network. */
	private static void checkLit(MinecraftServer server, List<ServerPlayer> players) {
		PortalStore s = store(server);
		if (s.size() == 0) {
			return;
		}
		Map<String, ServerLevel> levels = new HashMap<>();
		List<Portal> gone = new ArrayList<>();
		for (Portal p : s.all()) {
			ServerLevel level = levels.computeIfAbsent(p.dimension(), d -> levelOf(server, d));
			if (level == null) {
				continue;
			}
			double[] c = p.center();
			boolean near = false;
			for (ServerPlayer pl : players) {
				double dx = pl.getX() - c[0], dz = pl.getZ() - c[2];
				near |= pl.level() == level && dx * dx + dz * dz < CHECK_RANGE * CHECK_RANGE;
			}
			if (near && Boolean.FALSE.equals(intact(level, p, false))) {
				gone.add(p);
			}
		}
		for (Portal p : gone) {
			s.remove(p.id());
			SkyCraft.LOG.info("SkyCraft-portal: {} has gone out; dropped from the network{}", where(p), p.bound() ? ", its partner " + p.partner() + " unbound" : "");
		}
		if (!gone.isEmpty()) {
			save(server, s);
		}
	}

	/**
	 * A block broken: a frame block or portal block takes the portals it held that went out out of the
	 * network; in Home past the Far Lands' edge, the strata give up a Seamstone (Ending).
	 */
	private static void afterBreak(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (on() && level.dimension().equals(Worlds.HOME) && !player.isSpectator()) {
			boolean strata = state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.is(BlockTags.MINEABLE_WITH_SHOVEL);
			if (Ending.dropsSeamstone(true, pos.getX(), pos.getZ(), strata, carriesSeamstone(player))) {
				Block.popResource(level, pos, new ItemStack(PortalItems.SEAMSTONE));
				SkyCraft.LOG.info("SkyCraft-portal: the Far Lands gave {} a Seamstone at {}", player.getName().getString(), pos.toShortString());
			}
		}
		if (!(state.is(Blocks.OBSIDIAN) || state.is(Blocks.NETHER_PORTAL))) {
			return;
		}
		PortalStore s = store(serverLevel.getServer());
		boolean changed = false;
		for (Portal p : s.touching(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ())) {
			if (Boolean.TRUE.equals(intact(serverLevel, p, false))) {
				continue;
			}
			s.remove(p.id());
			changed = true;
			player.sendOverlayMessage(Component.literal("The portal at " + placeName(p) + " collapses" + (p.bound() ? "; its bond is broken." : ".")));
			SkyCraft.LOG.info("SkyCraft-portal: {} broke the frame of {}; dropped from the network{}", player.getName().getString(), where(p),
				p.bound() ? ", its partner " + p.partner() + " unbound" : "");
		}
		if (changed) {
			save(serverLevel.getServer(), s);
		}
	}
}
