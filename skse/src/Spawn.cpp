#include "Spawn.h"

#include "Dig.h"
#include "Game.h"

#include <random>

namespace skycraft::Spawn
{
	namespace
	{
		// ---- who lives down there ---------------------------------------------------------------
		// Actor bases (NPC_) in Skyrim.esm, so the load index is always 00. Like a leveled list:
		// from each kind, the strongest one the player's level has reached (now and then the one
		// before it). Names and levels as on UESP (Skyrim:Skeever, Skyrim:Skeleton, Skyrim:Draugr).
		struct Creature
		{
			std::uint32_t    kind;      // proto::SpawnKind
			std::uint16_t    minLevel;  // the player's level from which it turns up (its own level)
			RE::FormID       base;
			std::string_view name;
		};
		constexpr std::array kCreatures{
			Creature{ proto::kSpawnSkeever, 1, 0x00023AB7, "Skeever" },             // level 1
			Creature{ proto::kSpawnSkeleton, 1, 0x0002D1DE, "Skeleton" },           // level 1 (Nordic ruins, graveyards)
			Creature{ proto::kSpawnDraugr, 1, 0x0003B547, "Draugr" },               // level 1
			Creature{ proto::kSpawnDraugr, 6, 0x0003B548, "Restless Draugr" },      // level 6
			Creature{ proto::kSpawnDraugr, 13, 0x0003B549, "Draugr Wight" },        // level 13
			Creature{ proto::kSpawnDraugr, 21, 0x0003B54A, "Draugr Scourge" },      // level 21
			Creature{ proto::kSpawnDraugr, 30, 0x0003B54B, "Draugr Deathlord" },    // level 30
		};

		// Never right next to the player, whatever Minecraft says: the closest skycraft-deep.txt allows
		// (4 blocks), less a little for Skyrim's player trailing Minecraft's.
		constexpr float       kMinSpawnBlocks = 3.5f;
		constexpr float       kDefaultDespawnBlocks = 64.0f;
		constexpr std::uint32_t kDefaultCap = 3;
		constexpr std::size_t kMaxTracked = 12;           // corpses beyond this are taken away first
		constexpr float       kLootBlocks = 8.0f;         // ...but not one this close: the player may be looting it
		constexpr float       kCheckSeconds = 0.5f;
		constexpr float       kLift = 10.0f;              // Skyrim units: put down just above the floor
		// Taking one away is two steps (see TakeAway): Skyrim gets this long to disable it before it
		// is no longer tracked (and then isn't deleted by us).
		constexpr float       kLeaveTimeout = 10.0f;
		// Diagnostics: where a new creature is 1 s and 5 s after it came out, and whether it rose
		// more than kRiseBlocks above its spot (watched for kWatchSeconds).
		constexpr std::array  kProbeSeconds{ 1.0f, 5.0f };
		constexpr float       kRiseBlocks = 3.0f;
		constexpr float       kWatchSeconds = 30.0f;
		constexpr std::uint8_t kRoseLogged = 1u << kProbeSeconds.size();

		// SKSE co-save (CoSave.cpp registers it): which of a saved game's actors came out of the dark, so
		// a loaded game still takes them away (and counts them) like any other. Record kSaveRecord ('DEEP'):
		// count, then per creature its FormID and despawn distance.
		constexpr std::uint32_t kSaveVersion = 1;
		constexpr std::uint32_t kMaxSaved = 64;

		// Where someone is, as far as "left for somewhere else" goes: the interior cell, or the
		// worldspace outside (walking from one exterior cell to the next is the distance rule's job).
		struct Place
		{
			RE::TESObjectCELL*  interior{ nullptr };
			RE::TESWorldSpace*  world{ nullptr };

			bool operator==(const Place&) const = default;
		};

		Place PlaceOf(RE::TESObjectREFR* a_ref)
		{
			auto* cell = a_ref->GetParentCell();
			if (cell && cell->IsInteriorCell()) {
				return { cell, nullptr };
			}
			return { nullptr, a_ref->GetWorldspace() };
		}

		struct Spawned
		{
			RE::ObjectRefHandle ref;
			RE::FormID          formId{ 0 };
			float               despawnBlocks{ kDefaultDespawnBlocks };
			std::string_view    name;
			// Being taken away: Disable() was called; deleted once Skyrim has actually disabled it.
			bool                leaving{ false };
			float               leavingFor{ 0.0f };
			// Diagnostics: where it came out (Minecraft coords) and seconds since (negative: not
			// watched, e.g. one from a saved game); which probes are logged (bit per probe, kRoseLogged).
			McVec               spot{};
			float               age{ -1.0f };
			std::uint8_t        logged{ 0 };
		};
		std::vector<Spawned> spawned;
		float                checkTimer = 0.0f;
		// From the co-save of the game being loaded (FormID, despawn distance); taken on by Reset().
		std::vector<std::pair<RE::FormID, float>> loaded;

		std::mt19937& Rng()
		{
			static std::mt19937 rng{ std::random_device{}() };
			return rng;
		}

		const Creature* Pick(std::uint32_t a_kind, std::uint16_t a_level)
		{
			const Creature* best = nullptr;
			const Creature* second = nullptr;
			for (const auto& c : kCreatures) {
				if (c.kind != a_kind || c.minLevel > a_level) {
					continue;
				}
				if (!best || c.minLevel > best->minLevel) {
					second = best;
					best = &c;
				} else if (!second || c.minLevel > second->minLevel) {
					second = &c;
				}
			}
			if (best && second && std::uniform_int_distribution<int>(0, 2)(Rng()) == 0) {
				return second;
			}
			return best;
		}

		// The table's name for a placed creature (by its actor base), for the log.
		std::string_view NameOf(RE::TESObjectREFR* a_ref)
		{
			const auto* base = a_ref->GetBaseObject();
			for (const auto& c : kCreatures) {
				if (base && base->GetFormID() == c.base) {
					return c.name;
				}
			}
			return std::string_view{ "creature" };
		}

		// Taking a creature away, step one of two, in the order a Papyrus script does it (Disable,
		// which waits until the actor is disabled, then Delete). Actor::Disable doesn't disable on the
		// spot: it queues a task (TaskQueueInterface) that Skyrim runs later in the frame, and that
		// task tears down the actor's AI process and builds it a new movement controller, which looks
		// its actor up by FormID. Marked for deletion right after Disable(), the actor had lost its
		// FormID (Skyrim's garbage collector sets it to 0) by the time the task ran, so the new
		// controller had no actor and Skyrim crashed (1.7.104: TESObjectREFR::Disable, then
		// IMovementMotionDrivenControl reading address 0x1f8). So nothing is deleted here: Finish()
		// does that once IsDisabled() shows the task has run.
		void TakeAway(Spawned& a_s, RE::TESObjectREFR* a_ref, const char* a_why)
		{
			logger::info("SkyCraft-deep: {} {:08X} taken away ({})", a_s.name, a_ref->GetFormID(), a_why);
			a_s.leaving = true;
			a_s.leavingFor = 0.0f;
			a_s.age = -1.0f;  // gone from the world: nothing to watch
			if (!a_ref->IsDisabled()) {
				a_ref->Disable();
			}
		}

		// Step two, on a later check: Skyrim has disabled it (no AI process, no 3D, nothing queued
		// for it any more), so it can be marked for deletion; Skyrim removes it with its cell. Returns
		// true once it's no longer ours to track.
		bool Finish(Spawned& a_s, RE::TESObjectREFR* a_ref)
		{
			if (a_ref->IsDisabled()) {
				a_ref->SetDelete(true);
				return true;
			}
			a_s.leavingFor += kCheckSeconds;
			if (a_s.leavingFor > kLeaveTimeout) {
				// Never deleted while Skyrim may still have the disable to do; left to Skyrim instead.
				logger::warn("SkyCraft-deep: {} {:08X} still isn't disabled {:.0f}s after it was taken away; no longer tracked", a_s.name, a_s.formId, a_s.leavingFor);
				return true;
			}
			return false;
		}

		bool Alive(RE::TESObjectREFR* a_ref)
		{
			auto* actor = a_ref->As<RE::Actor>();
			return actor && !actor->IsDead();
		}

		// Forgets what no longer exists; returns how many are still alive (not being taken away).
		std::uint32_t Prune()
		{
			std::uint32_t alive = 0;
			for (auto it = spawned.begin(); it != spawned.end();) {
				auto ref = it->ref.get();
				if (!ref || ref->IsDeleted() || ref->IsMarkedForDeletion()) {
					it = spawned.erase(it);
					continue;
				}
				alive += !it->leaving && !ref->IsDisabled() && Alive(ref.get()) ? 1u : 0u;
				++it;
			}
			return alive;
		}

		// Born where it comes out: the creature is created at the tunnel spot itself, by the function
		// Papyrus PlaceAtMe and CommonLib's PlaceObjectAtMe call (TESDataHandler::CreateReferenceAtLocation),
		// only with the spot instead of the caller's position. It used to be placed at the player and
		// then moved with SetPosition, which (1.7.104, TESObjectREFR::SetPosition) writes the
		// reference's position data and nothing else: an actor moves with its Havok character
		// controller, so one that was already set up at the player could come back up there.
		RE::NiPointer<RE::TESObjectREFR> PlaceAt(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_base, const RE::NiPoint3& a_pos, const RE::NiPoint3& a_angle)
		{
			auto* data = RE::TESDataHandler::GetSingleton();
			if (!data) {
				return {};
			}
			// The loaded exterior cell the spot is in (it may be the one next to the player's).
			auto* tes = RE::TES::GetSingleton();
			auto* cell = tes ? tes->GetCell(a_pos) : nullptr;
			if (!cell || cell->IsInteriorCell()) {
				cell = a_player->GetParentCell();
			}
			const auto handle = data->CreateReferenceAtLocation(a_base, a_pos, a_angle, cell, a_player->GetWorldspace(), nullptr, nullptr, RE::ObjectRefHandle(), false, true);
			return handle.get();
		}

		std::string_view ProcessName(RE::TESObjectREFR* a_ref)
		{
			auto* actor = a_ref->As<RE::Actor>();
			switch (actor ? actor->GetProcessLevel() : RE::PROCESS_TYPE::kNone) {
			case RE::PROCESS_TYPE::kHigh:
				return "high process"sv;
			case RE::PROCESS_TYPE::kMiddleHigh:
				return "middle high process"sv;
			case RE::PROCESS_TYPE::kMiddleLow:
				return "middle low process"sv;
			case RE::PROCESS_TYPE::kLow:
				return "low process"sv;
			default:
				return "no AI process"sv;
			}
		}

		// Main thread, every frame: where the creatures that just came out really are, so a test in
		// game shows whether they stay down in their tunnel (1 s and 5 s after, and any rise of more
		// than kRiseBlocks in the first kWatchSeconds). The distance from the player matters: on
		// 1.7.104 an actor standing on the ground further than fNotVisibleNavmeshMoveDist (2048
		// units, about 29 blocks; fVisibleNavmeshMoveDist, 4096, while in view) from the camera is
		// moved along the navmesh instead of by Havok, which may put an underground one on the land.
		void Watch(const McVec& a_player, float a_delta)
		{
			for (auto& s : spawned) {
				if (s.age < 0.0f || s.leaving) {
					continue;
				}
				auto ref = s.ref.get();
				if (!ref) {
					continue;
				}
				s.age += a_delta;
				const auto   mc = SkyToMc(ref->GetPosition());
				const double up = mc.y - s.spot.y;
				const double across = std::hypot(mc.x - s.spot.x, mc.z - s.spot.z);
				const double fromPlayer = std::sqrt((mc.x - a_player.x) * (mc.x - a_player.x) + (mc.y - a_player.y) * (mc.y - a_player.y) + (mc.z - a_player.z) * (mc.z - a_player.z));
				if (up > kRiseBlocks && !(s.logged & kRoseLogged)) {
					s.logged |= kRoseLogged;
					logger::warn("SkyCraft-deep: {} {:08X} rose {:.1f} blocks above where it came out, {:.1f}s after: now at {:.1f} {:.1f} {:.1f} (came out at {:.1f} {:.1f} {:.1f}), {:.1f} blocks across, {:.0f} blocks from the player ({}{})",
						s.name, s.formId, up, s.age, mc.x, mc.y, mc.z, s.spot.x, s.spot.y, s.spot.z, across, fromPlayer, ref->Is3DLoaded() ? "3D loaded, " : "no 3D, ", ProcessName(ref.get()));
				}
				for (std::size_t i = 0; i < kProbeSeconds.size(); ++i) {
					const auto bit = static_cast<std::uint8_t>(1u << i);
					if (s.age >= kProbeSeconds[i] && !(s.logged & bit)) {
						s.logged |= bit;
						logger::info("SkyCraft-deep: {} {:08X} {:.0f}s after coming out: at {:.1f} {:.1f} {:.1f}, {:+.1f} blocks from its spot's height, {:.1f} blocks across, {:.0f} blocks from the player ({}{})",
							s.name, s.formId, kProbeSeconds[i], mc.x, mc.y, mc.z, up, across, fromPlayer, ref->Is3DLoaded() ? "3D loaded, " : "no 3D, ", ProcessName(ref.get()));
					}
				}
				if (s.age >= kWatchSeconds) {
					s.age = -1.0f;
				}
			}
		}
	}

	// ---- the co-save (main thread: SKSE saves and loads there; CoSave.cpp calls these) ------------
	void Save(SKSE::SerializationInterface* a_intfc)
	{
		if (!a_intfc->OpenRecord(kSaveRecord, kSaveVersion)) {
			logger::warn("SkyCraft-deep: couldn't write the spawned creatures to the co-save");
			return;
		}
		const auto count = static_cast<std::uint32_t>(std::min<std::size_t>(spawned.size(), kMaxSaved));
		a_intfc->WriteRecordData(count);
		for (std::uint32_t i = 0; i < count; ++i) {
			a_intfc->WriteRecordData(spawned[i].formId);
			a_intfc->WriteRecordData(spawned[i].despawnBlocks);
		}
	}

	void Load(SKSE::SerializationInterface* a_intfc, std::uint32_t a_version)
	{
		if (a_version != kSaveVersion) {
			return;
		}
		std::uint32_t count = 0;
		if (a_intfc->ReadRecordData(count) != sizeof(count)) {
			return;
		}
		for (std::uint32_t i = 0; i < count && i < kMaxSaved; ++i) {
			RE::FormID id = 0;
			float      despawn = kDefaultDespawnBlocks;
			if (a_intfc->ReadRecordData(id) != sizeof(id) || a_intfc->ReadRecordData(despawn) != sizeof(despawn)) {
				break;
			}
			RE::FormID now = 0;
			if (a_intfc->ResolveFormID(id, now) && now != 0) {
				loaded.emplace_back(now, std::isfinite(despawn) && despawn > 0.0f ? despawn : kDefaultDespawnBlocks);
			}
		}
	}

	void Revert()
	{
		loaded.clear();
	}

	void OnEvent(RE::PlayerCharacter* a_player, const proto::McEvent& a_event)
	{
		const std::uint32_t kind = a_event.formId;
		if (kind < proto::kSpawnSkeever || kind > proto::kSpawnDraugr) {
			logger::warn("SkyCraft-deep: Minecraft asked for creature kind {}, which isn't one", kind);
			return;
		}
		auto* cell = a_player->GetParentCell();
		if (!cell || cell->IsInteriorCell() || !a_player->GetWorldspace() || a_player->IsDead()) {
			return;  // dug tunnels are under the land outside
		}
		const std::uint32_t cap = a_event.flags > 0 ? std::min<std::uint32_t>(a_event.flags, 16) : kDefaultCap;
		const std::uint32_t alive = Prune();
		if (alive >= cap) {
			logger::info("SkyCraft-deep: {} spawned creatures alive already (at most {}); no more", alive, cap);
			return;
		}
		const float despawn = a_event.d > 0.0f && std::isfinite(a_event.d) ? a_event.d : kDefaultDespawnBlocks;
		if (!std::isfinite(a_event.a) || !std::isfinite(a_event.b) || !std::isfinite(a_event.c)) {
			return;
		}
		const auto  playerMc = SkyToMc(a_player->GetPosition());
		const float dx = static_cast<float>(a_event.a - playerMc.x), dy = static_cast<float>(a_event.b - playerMc.y),
					dz = static_cast<float>(a_event.c - playerMc.z);
		const float away = std::sqrt(dx * dx + dy * dy + dz * dz);
		if (away < kMinSpawnBlocks || away > despawn) {
			logger::warn("SkyCraft-deep: spot {:.1f} blocks from the player is out of range; no spawn", away);
			return;
		}
		// The floor must be a Minecraft block Skyrim knows about here: under the land there's nothing
		// else to stand on, and a creature put down over nothing would fall out of the world.
		const auto bx = static_cast<std::int32_t>(std::floor(a_event.a));
		const auto by = static_cast<std::int32_t>(std::floor(a_event.b + 0.01f));
		const auto bz = static_cast<std::int32_t>(std::floor(a_event.c));
		if (!NpcBlocks::SolidAt(bx, by - 1, bz) || !Dig::IsDug(bx, by, bz)) {
			logger::info("SkyCraft-deep: Skyrim doesn't know the floor of the tunnel at {} {} {} yet; no spawn", bx, by, bz);
			return;
		}
		const auto* creature = Pick(kind, a_player->GetLevel());
		if (!creature) {
			return;
		}
		auto* base = RE::TESForm::LookupByID<RE::TESBoundObject>(creature->base);
		if (!base) {
			logger::error("SkyCraft-deep: {} (actor base {:08X}) isn't in the game; is Skyrim.esm changed?", creature->name, creature->base);
			return;
		}
		auto pos = McToSky(a_event.a, a_event.b, a_event.c);
		pos.z += kLift;
		// Facing the player (Skyrim heading: 0 = north, clockwise).
		const auto playerPos = a_player->GetPosition();
		const auto ref = PlaceAt(a_player, base, pos, { 0.0f, 0.0f, std::atan2(playerPos.x - pos.x, playerPos.y - pos.y) });
		if (!ref) {
			logger::warn("SkyCraft-deep: couldn't place a {}", creature->name);
			return;
		}

		// Corpses pile up otherwise: past the limit, the oldest dead one goes (not one right by the
		// player, who may be looting it).
		if (std::ranges::count_if(spawned, [](const Spawned& a_s) { return !a_s.leaving; }) >= static_cast<std::ptrdiff_t>(kMaxTracked)) {
			for (auto& s : spawned) {
				auto old = s.leaving ? RE::NiPointer<RE::TESObjectREFR>() : s.ref.get();
				if (old && !Alive(old.get()) && old->GetPosition().GetDistance(playerPos) > kLootBlocks * static_cast<float>(proto::kUnitsPerBlock)) {
					TakeAway(s, old.get(), "too many bodies");
					break;
				}
			}
		}
		Spawned s{ ref->GetHandle(), ref->GetFormID(), despawn, creature->name };
		s.spot = { a_event.a, a_event.b, a_event.c };
		s.age = 0.0f;
		const auto at = SkyToMc(ref->GetPosition());
		logger::info("SkyCraft-deep: a {} ({:08X}, ref {:08X}) comes out of the dark at {:.1f} {:.1f} {:.1f}, {:.0f} blocks from the player; {} of {} alive (placed at {:.1f} {:.1f} {:.1f} in cell {:08X}, {})",
			creature->name, creature->base, s.formId, a_event.a, a_event.b, a_event.c, away, alive + 1, cap, at.x, at.y, at.z,
			ref->GetParentCell() ? ref->GetParentCell()->GetFormID() : 0u, ref->Is3DLoaded() ? "3D loaded at once" : "3D still loading");
		spawned.push_back(s);
	}

	void Update(RE::PlayerCharacter* a_player, float a_delta)
	{
		if (spawned.empty()) {
			return;
		}
		const auto playerPos = a_player->GetPosition();
		Watch(SkyToMc(playerPos), a_delta);
		checkTimer -= a_delta;
		if (checkTimer > 0.0f) {
			return;
		}
		checkTimer = kCheckSeconds;
		if (!a_player->GetParentCell()) {
			return;  // between cells (loading)
		}
		const Place here = PlaceOf(a_player);
		for (auto it = spawned.begin(); it != spawned.end();) {
			auto ref = it->ref.get();
			if (!ref || ref->IsDeleted() || ref->IsMarkedForDeletion()) {
				it = spawned.erase(it);
				continue;
			}
			// Being taken away (or disabled by someone else): deleted once Skyrim has disabled it.
			if (it->leaving || ref->IsDisabled()) {
				it = Finish(*it, ref.get()) ? spawned.erase(it) : it + 1;
				continue;
			}
			// The player's now (a Dead Thrall, Command Dead): not ours to take away any more.
			if (auto* actor = ref->As<RE::Actor>(); actor && actor->IsPlayerTeammate()) {
				logger::info("SkyCraft-deep: {} {:08X} is the player's now; no longer tracked", it->name, it->formId);
				it = spawned.erase(it);
				continue;
			}
			// Where the creature is now, not where it came out: one that followed the player
			// through a door stays. (No cell: being moved; the distance rule still holds.) The
			// distance doesn't line up with an AI process change: Skyrim changes an exterior actor's
			// process level as its cell attaches or detaches, and the attached cells reach at least
			// two cells (8192 units, about 117 blocks) past the player's with the default
			// uGridsToLoad of 5. Taking one away is safe in any state anyway (TakeAway).
			const char* why = nullptr;
			if (ref->GetParentCell() && !(PlaceOf(ref.get()) == here)) {
				why = "the player left";
			} else if (ref->GetPosition().GetDistance(playerPos) > it->despawnBlocks * static_cast<float>(proto::kUnitsPerBlock)) {
				why = "far from the player";
			}
			if (why) {
				TakeAway(*it, ref.get(), why);
			}
			++it;
		}
	}

	bool IsOurs(const RE::Actor* a_actor)
	{
		if (!a_actor || spawned.empty()) {
			return false;
		}
		const auto id = a_actor->GetFormID();
		return std::ranges::any_of(spawned, [id](const Spawned& a_s) { return a_s.formId == id; });
	}

	void Reset()
	{
		if (!spawned.empty()) {
			logger::info("SkyCraft-deep: a game was loaded; forgetting {} spawned creatures", spawned.size());
		}
		spawned.clear();
		checkTimer = 0.0f;
		// The loaded game's own creatures (from its co-save; none for a new game).
		for (const auto& [id, despawn] : loaded) {
			auto* actor = RE::TESForm::LookupByID<RE::Actor>(id);
			if (!actor || actor->IsPlayerRef() || actor->IsDeleted()) {
				continue;
			}
			RE::TESObjectREFR* ref = actor;
			spawned.push_back({ ref->GetHandle(), id, despawn, NameOf(ref) });
		}
		if (!spawned.empty()) {
			logger::info("SkyCraft-deep: the loaded game has {} creatures that came out of the dark", spawned.size());
		}
		loaded.clear();
	}
}
