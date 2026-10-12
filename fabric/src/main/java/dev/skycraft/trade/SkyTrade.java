package dev.skycraft.trade;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.config.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

/**
 * A Skyrim merchant's shop as a Minecraft villager trade screen, server side. Right-clicking a
 * merchant's stand-in with an empty hand opens it. What the merchant offers comes from
 * {@code config/skycraft-trades.txt} and {@code config/skycraft-towns.txt} (see {@link TradeRules}),
 * picked by the merchant's form id and priced for the town Skyrim reports and for recent trading
 * there (see {@link Demand}). Each trade can be made 8 times; a use comes back every two minutes.
 * Trading trains Skyrim's Speech skill for the host.
 */
public final class SkyTrade implements Merchant {
	private static final String TRADES_FILE = "skycraft-trades.txt";
	private static final String TOWNS_FILE = "skycraft-towns.txt";
	public static final int MAX_USES = 8;
	/** Two minutes: demand eases one step and every trade gets one use back. */
	public static final int DECAY_TICKS = 2400;
	private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

	private static final Map<Integer, SkyTrade> BY_ACTOR = new HashMap<>();
	private static final Demand DEMAND = new Demand();
	private static final Set<String> WARNED = new HashSet<>();
	private static TradeRules rules;

	/** The merchant's stand-in while its screen is open; null otherwise, so a closed shop holds no entity (or world). */
	private @Nullable SkyrimActorEntity actor;
	private String town = "";
	private MerchantOffers offers = new MerchantOffers();
	private final Map<MerchantOffer, TradeRules.Offer> sources = new IdentityHashMap<>();
	/** Uses per trade, kept across openings so a merchant runs out of stock. */
	private final Map<String, Integer> uses = new HashMap<>();
	private @Nullable Player tradingPlayer;
	/** The screen just closed: the next {@link #isClientSide()} call is MerchantMenu.removed's. */
	private boolean closing;
	private int xp;

	private SkyTrade() {
	}

	/** Opens {@code actor}'s shop for {@code player}. */
	public static void open(ServerPlayer player, SkyrimActorEntity actor) {
		if (!dev.skycraft.config.Features.on(dev.skycraft.config.Features.Feature.MERCHANTS)) {
			return;
		}
		SkyLink.SkyState state = new SkyLink.SkyState();
		String town = SkyLink.readSkyState(state) ? state.town.strip() : "";
		String name = actor.getName().getString();
		SkyTrade trade = BY_ACTOR.computeIfAbsent(actor.formId(), id -> new SkyTrade());
		Player other = trade.tradingPlayer;
		if (other != null && other != player && trade.stillValid(other)) {
			player.sendSystemMessage(Component.literal(name + " is busy with another customer."));
			return;
		}
		trade.town = town;
		List<TradeRules.Offer> list = rules().offersFor(actor.getVendor(), town, actor.formId(), DEMAND);
		trade.rebuild(list, rules().town(town));
		if (trade.offers.isEmpty()) {
			player.sendSystemMessage(Component.literal(name + " has nothing to trade with you."));
			SkyCraft.LOG.info("SkyCraft-trade: {} (vendor mask {}) has no trades in {}", name, actor.getVendor(), town.isEmpty() ? "*" : town);
			return;
		}
		trade.actor = actor;
		trade.setTradingPlayer(player);
		trade.openTradingScreen(player, Component.literal(town.isEmpty() ? name : name + " of " + town), 0);
		SkyCraft.LOG.info("SkyCraft-trade: {} opened {}'s shop in {} ({} offers)", player.getName().getString(), name, town.isEmpty() ? "*" : town,
			trade.offers.size());
	}

	/** Called every server tick. */
	public static void tick(int serverTick) {
		if (serverTick % DECAY_TICKS != 0) {
			return;
		}
		DEMAND.decay();
		for (SkyTrade trade : BY_ACTOR.values()) {
			trade.restock();
		}
	}

	private static TradeRules rules() {
		if (rules != null) {
			return rules;
		}
		List<String> problems = new ArrayList<>();
		rules = TradeRules.parse(Config.read(TRADES_FILE, TradeRules.DEFAULT_TRADES), Config.read(TOWNS_FILE, TradeRules.DEFAULT_TOWNS), problems);
		problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-trade: {}", p));
		SkyCraft.LOG.info("SkyCraft-trade: {} trades and {} towns loaded", rules.trades().size(), rules.towns().size());
		return rules;
	}

	private static String useKey(TradeRules.Offer o) {
		return (o.sell() ? "sell " : "buy ") + o.count() + " " + o.item();
	}

	private void restock() {
		for (Iterator<Map.Entry<String, Integer>> it = this.uses.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Integer> e = it.next();
			if (e.getValue() <= 1) {
				it.remove();
			} else {
				e.setValue(e.getValue() - 1);
			}
		}
	}

	private void rebuild(List<TradeRules.Offer> list, TradeRules.Town where) {
		MerchantOffers out = new MerchantOffers();
		this.sources.clear();
		for (TradeRules.Offer o : list) {
			MerchantOffer offer = toMerchantOffer(o, where, this.uses.getOrDefault(useKey(o), 0));
			if (offer != null) {
				out.add(offer);
				this.sources.put(offer, o);
			}
		}
		this.offers = out;
	}

	private static @Nullable Item item(String id) {
		Identifier key = Identifier.tryParse(id);
		Item item = key != null ? BuiltInRegistries.ITEM.getValue(key) : null;
		if (item == null || item == Items.AIR) {
			if (WARNED.add(id)) {
				SkyCraft.LOG.warn("SkyCraft-trade: unknown item '{}' in {}, trade skipped", id, TRADES_FILE);
			}
			return null;
		}
		return item;
	}

	private static @Nullable MerchantOffer toMerchantOffer(TradeRules.Offer o, TradeRules.Town where, int used) {
		Item goods = item(o.item());
		if (goods == null) {
			return null;
		}
		// A trade can't take or give more than one stack; the price shrinks with the count.
		int count = Math.min(o.count(), new ItemStack(goods).getMaxStackSize());
		int price = TradeRules.priceFor(o, count);
		Set<String> money = TradeRules.currenciesFor(where, o.item());
		if (o.sell()) {
			List<Currency.Pay> pay = Currency.cost(price, money);
			if (Currency.total(pay) < price) {
				// Two stacks of the town's currency can't pay it (a diamond in a copper town): selling it
				// for less would let it be bought cheap here and spent as full-value coin elsewhere.
				if (WARNED.add("price " + where.name() + " " + o.item())) {
					SkyCraft.LOG.info("SkyCraft-trade: {} for {} coins can't be paid with two stacks of {} (towns line '{}'), trade skipped", o.item(), price,
						money, where.name());
				}
				return null;
			}
			Item first = item(pay.getFirst().item());
			if (first == null) {
				return null;
			}
			Optional<ItemCost> second = Optional.empty();
			if (pay.size() > 1) {
				Item change = item(pay.get(1).item());
				if (change == null) {
					return null;
				}
				second = Optional.of(new ItemCost(change, pay.get(1).count()));
			}
			return new MerchantOffer(new ItemCost(first, pay.getFirst().count()), second, new ItemStack(goods, count), used, MAX_USES, 0, 0.0F);
		}
		Currency.Pay pay = Currency.payout(price, money);
		Item coin = item(pay.item());
		if (coin == null) {
			return null;
		}
		return new MerchantOffer(new ItemCost(goods, count), Optional.empty(), new ItemStack(coin, pay.count()), used, MAX_USES, 0, 0.0F);
	}

	@Override
	public void setTradingPlayer(@Nullable Player player) {
		this.tradingPlayer = player;
		this.closing = player == null;
		if (player == null) {
			this.actor = null;
		}
	}

	@Override
	public @Nullable Player getTradingPlayer() {
		return this.tradingPlayer;
	}

	@Override
	public MerchantOffers getOffers() {
		return this.offers;
	}

	@Override
	public void overrideOffers(MerchantOffers offers) {
		this.offers = offers;
	}

	@Override
	public void notifyTrade(MerchantOffer offer) {
		offer.increaseUses();
		TradeRules.Offer source = this.sources.get(offer);
		if (source == null) {
			return;
		}
		this.uses.merge(useKey(source), 1, Integer::sum);
		if (source.sell()) {
			DEMAND.bought(this.town, source.item());
		} else {
			DEMAND.sold(this.town, source.item());
		}
		// What actually changed hands (toMerchantOffer caps the count at a stack and scales the price).
		int count = source.sell() ? offer.getResult().getCount() : offer.getItemCostA().count();
		int price = TradeRules.priceFor(source, count);
		String who = this.tradingPlayer != null ? this.tradingPlayer.getName().getString() : "?";
		String merchant = this.actor != null ? this.actor.getName().getString() : "?";
		SkyCraft.LOG.info("SkyCraft-trade: {} {} {} x {} {} {} for {} coins", who, source.sell() ? "bought" : "sold", count, source.item(),
			source.sell() ? "from" : "to", merchant, price);
		if (this.tradingPlayer instanceof ServerPlayer player && dev.skycraft.net.SkyNet.isHost(player)) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, Proto.SKILL_SPEECH, price * 2.0F, 0.0F, 0.0F, 0.0F, 0);
		}
	}

	@Override
	public void notifyTradeUpdated(ItemStack stack) {
	}

	@Override
	public int getVillagerXp() {
		return this.xp;
	}

	@Override
	public void overrideXp(int xp) {
		this.xp = xp;
	}

	@Override
	public boolean showProgressBar() {
		return false;
	}

	@Override
	public SoundEvent getNotifyTradeSound() {
		return SoundEvents.VILLAGER_YES;
	}

	/**
	 * A SkyTrade only exists on the server, but only MerchantMenu asks, in two places. Shift-clicking
	 * the result plays the trade sound when not client side by casting the merchant to an Entity,
	 * which this isn't: the ClassCastException came after the goods reached the inventory and before
	 * the payment was taken, so every shift-click was a free trade. That sound is a no-op on a server
	 * level anyway, so answer true. Closing the screen hands the payment slots back only on the
	 * server, so the call right after setTradingPlayer(null) answers false.
	 */
	@Override
	public boolean isClientSide() {
		boolean closed = this.closing;
		this.closing = false;
		return !closed;
	}

	@Override
	public boolean stillValid(Player player) {
		SkyrimActorEntity at = this.actor;
		return player == this.tradingPlayer && at != null && !at.isRemoved() && player.distanceToSqr(at) <= MAX_DISTANCE_SQR;
	}
}
