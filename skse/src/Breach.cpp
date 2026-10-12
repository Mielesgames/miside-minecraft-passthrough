#include "Breach.h"

namespace skycraft::Breach
{
	namespace
	{
		constexpr float       kRefreshSeconds = 1.0f;
		constexpr float       kRecentSeconds = 5.0f;        // Minecraft may only name a door published this recently
		constexpr float       kMoveTimeoutSeconds = 5.0f;   // a move that never starts stops holding Minecraft back
		constexpr float       kMaxEnterBlocks = 128.0f;     // the player is in the antechamber, under the door
		constexpr std::size_t kMaxCachedDoors = 1024;
		constexpr int         kMaxLocationDepth = 8;

		// Location types that make a place a dungeon, in the order they pick its theme: a Nordic crypt
		// full of bandits is still Nordic, a Dwarven ruin overrun by Falmer is still Dwemer.
		struct LocType
		{
			std::string_view keyword;
			std::uint8_t     theme;  // proto::BreachTheme
		};
		constexpr std::array kLocTypes{
			LocType{ "LocTypeDraugrCrypt", proto::kThemeNordic },
			LocType{ "LocTypeNordicRuin", proto::kThemeNordic },  // not in Skyrim.esm; a mod may add it
			LocType{ "LocTypeDragonPriestLair", proto::kThemeNordic },
			LocType{ "LocTypeDwarvenAutomatons", proto::kThemeDwemer },
			LocType{ "LocTypeFalmerHive", proto::kThemeFalmer },
			LocType{ "LocTypeMine", proto::kThemeMine },
			LocType{ "LocTypeBanditCamp", proto::kThemeFort },
			LocType{ "LocTypeMilitaryFort", proto::kThemeFort },
			LocType{ "LocTypeForswornCamp", proto::kThemeFort },
			LocType{ "LocTypeGiantCamp", proto::kThemeFort },
			LocType{ "LocTypeVampireLair", proto::kThemeLair },
			LocType{ "LocTypeWarlockLair", proto::kThemeLair },
			LocType{ "LocTypeHagravenNest", proto::kThemeLair },
			LocType{ "LocTypeDungeon", proto::kThemeCave },
			LocType{ "LocTypeAnimalDen", proto::kThemeCave },
			LocType{ "LocTypeSprigganGrove", proto::kThemeCave },
		};
		constexpr std::array<std::string_view, 7> kThemeNames{ "cave", "Nordic", "Dwemer", "Falmer", "mine", "fort", "lair" };

		// What an exterior door leads into, worked out once per door: its link and the dungeon's
		// keywords don't change. Doors that aren't dungeon entrances are kept too (dungeon false), so
		// the town gates and house doors around the player aren't looked at again every second.
		struct Door
		{
			bool             dungeon{ false };
			RE::BGSLocation* location{ nullptr };  // the dungeon (forms live as long as the game)
			RE::FormID       marker{ 0 };          // its map marker reference, 0 if none was found
			std::uint8_t     theme{ proto::kThemeCave };
			std::uint16_t    level{ 0 };
			char             name[28]{};
		};

		// The other side of a load door.
		struct Destination
		{
			RE::NiPointer<RE::TESObjectREFR> inside;    // the door on the inside
			RE::TESObjectCELL*               cell{ nullptr };
			RE::NiPoint3                     position;  // where going through the door puts the player
			RE::NiPoint3                     rotation;
		};

		struct Candidate
		{
			float              distSq;
			RE::TESObjectREFR* ref;  // a door in an attached cell, used before this call returns
		};

		struct Move
		{
			bool       active{ false };
			RE::FormID cell{ 0 };  // the interior cell the player is on the way to
			float      age{ 0.0f };
		};

		std::unordered_map<RE::FormID, Door>  doors;      // by exterior door reference
		std::unordered_map<RE::FormID, float> published;  // door reference -> clock when last in the table
		std::vector<RE::FormID>               lastSet;    // the doors in the table last time (sorted), for the log
		const void*                           lastPlace = nullptr;
		float                                 clock = 0.0f;  // seconds of frames seen (a paused game doesn't count)
		float                                 refreshTimer = 0.0f;
		Move                                  move;

		// Where an exterior load door leads, if it leads into an interior.
		bool Resolve(RE::TESObjectREFR* a_door, Destination& a_out)
		{
			const auto* base = a_door->GetBaseObject();
			if (!base || base->GetFormType() != RE::FormType::Door) {
				return false;
			}
			const auto* teleport = a_door->extraList.GetByType<RE::ExtraTeleport>();
			const auto* data = teleport ? teleport->teleportData : nullptr;
			if (!data) {
				return false;
			}
			auto inside = data->linkedDoor.get();
			if (!inside) {
				return false;
			}
			auto* cell = inside->GetParentCell();
			if (!cell) {
				cell = inside->GetSaveParentCell();
			}
			if (!cell || !cell->IsInteriorCell()) {
				return false;
			}
			a_out.inside = std::move(inside);
			a_out.cell = cell;
			a_out.position = data->position;
			a_out.rotation = data->rotation;
			return true;
		}

		// A location's map marker: its world location marker when that is one, else the reference it
		// marks as its MapMarkerRefType.
		RE::FormID MarkerOf(RE::BGSLocation* a_location)
		{
			if (auto ref = a_location->worldLocMarker.get(); ref && ref->extraList.HasType<RE::ExtraMapMarker>()) {
				return ref->GetFormID();
			}
			for (const auto& special : a_location->specialRefs) {
				const char* type = special.type ? special.type->GetFormEditorID() : nullptr;
				if (!type || std::string_view(type) != "MapMarkerRefType") {
					continue;
				}
				auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(special.refData.refID);
				if (ref && ref->extraList.HasType<RE::ExtraMapMarker>()) {
					return ref->GetFormID();
				}
			}
			return 0;
		}

		RE::MapMarkerData* MarkerData(RE::FormID a_marker)
		{
			auto* ref = a_marker ? RE::TESForm::LookupByID<RE::TESObjectREFR>(a_marker) : nullptr;
			auto* extra = ref ? ref->extraList.GetByType<RE::ExtraMapMarker>() : nullptr;
			return extra ? extra->mapData : nullptr;
		}

		// Is the door's other side a dungeon, and which, how it looks, how tough and what it's called.
		Door Classify(const Destination& a_dest)
		{
			Door door;
			RE::BGSLocation* location = a_dest.cell->GetLocation();
			if (!location) {
				location = a_dest.inside->GetEditorLocation();
			}
			// The dungeon is the first location up the chain (this one, then its parents) with a dungeon type.
			RE::BGSLocation* dungeon = nullptr;
			int              depth = 0;
			for (auto* l = location; l && !dungeon && depth < kMaxLocationDepth; l = l->parentLoc, ++depth) {
				for (const auto& type : kLocTypes) {
					if (l->HasKeywordString(type.keyword)) {
						dungeon = l;
						door.theme = type.theme;
						break;
					}
				}
			}
			if (!dungeon) {
				return door;
			}
			door.dungeon = true;
			door.location = dungeon;
			// Its map marker: the inner location's own, else its parents' up to the dungeon.
			depth = 0;
			for (auto* l = location; l && depth < kMaxLocationDepth; l = l->parentLoc, ++depth) {
				door.marker = MarkerOf(l);
				if (door.marker || l == dungeon) {
					break;
				}
			}
			if (auto* zone = a_dest.cell->extraList.GetEncounterZone()) {
				door.level = static_cast<std::uint16_t>(std::max<int>(zone->data.minLevel, 0));
			}
			const char* name = dungeon->GetName();
			if (!name || !*name) {
				name = a_dest.cell->GetName();
			}
			strncpy_s(door.name, name ? name : "", _TRUNCATE);
			return door;
		}

		const Door* Lookup(RE::TESObjectREFR* a_door)
		{
			const auto id = a_door->GetFormID();
			auto       it = doors.find(id);
			if (it == doors.end()) {
				if (doors.size() >= kMaxCachedDoors) {
					doors.clear();
				}
				Destination dest;
				it = doors.emplace(id, Resolve(a_door, dest) ? Classify(dest) : Door{}).first;
			}
			return &it->second;
		}

		std::uint8_t FlagsOf(const Door& a_door)
		{
			std::uint8_t flags = 0;
			if (const auto* data = MarkerData(a_door.marker); data && data->flags.all(RE::MapMarkerData::Flag::kCanTravelTo)) {
				flags |= proto::kBreachDiscovered;
			}
			if (a_door.location && a_door.location->IsCleared()) {
				flags |= proto::kBreachCleared;
			}
			return flags;
		}

		void LogChanges(const std::vector<proto::BreachRecord>& a_records, const McVec& a_player)
		{
			std::vector<RE::FormID> ids;
			ids.reserve(a_records.size());
			for (const auto& r : a_records) {
				ids.push_back(r.doorRef);
			}
			std::ranges::sort(ids);
			if (ids == lastSet) {
				return;
			}
			lastSet = std::move(ids);
			if (a_records.empty()) {
				logger::info("SkyCraft-breach: no dungeon entrances near the player");
				return;
			}
			constexpr std::size_t kNamed = 6;  // the nearest few by name, the rest counted
			std::string           line;
			for (std::size_t i = 0; i < a_records.size() && i < kNamed; ++i) {
				const auto&  r = a_records[i];
				const double dx = r.x - a_player.x, dy = r.y - a_player.y, dz = r.z - a_player.z;
				line += std::format("{}{} ({}, {:.0f} blocks{}{})", i ? ", " : "", std::string_view(r.name), kThemeNames[r.theme % kThemeNames.size()],
					std::sqrt(dx * dx + dy * dy + dz * dz), (r.flags & proto::kBreachDiscovered) ? ", discovered" : "",
					(r.flags & proto::kBreachCleared) ? ", cleared" : "");
			}
			if (a_records.size() > kNamed) {
				line += std::format(" and {} more", a_records.size() - kNamed);
			}
			logger::info("SkyCraft-breach: {} dungeon entrance{} near the player: {}", a_records.size(), a_records.size() == 1 ? "" : "s", line);
		}

		// The nearest dungeon entrances in the attached cells around the player, into the table.
		void Publish(RE::PlayerCharacter* a_player, RE::TESObjectCELL* a_cell)
		{
			static std::vector<Candidate>           nearby;
			static std::vector<proto::BreachRecord> records;
			nearby.clear();
			records.clear();
			auto*      world = a_player->GetWorldspace();
			const auto origin = a_player->GetPosition();
			if (a_cell && a_cell->IsExteriorCell() && world) {
				if (auto* tes = RE::TES::GetSingleton()) {
					// Only a form type check per reference here (this runs under each cell's lock).
					tes->ForEachReference([&](RE::TESObjectREFR* a_ref) {
						const auto* base = a_ref->GetBaseObject();
						if (base && base->GetFormType() == RE::FormType::Door && !a_ref->IsDisabled() && !a_ref->IsDeleted()) {
							nearby.push_back({ origin.GetSquaredDistance(a_ref->GetPosition()), a_ref });
						}
						return RE::BSContainer::ForEachResult::kContinue;
					});
				}
			}
			std::ranges::sort(nearby, {}, &Candidate::distSq);
			for (const auto& candidate : nearby) {
				if (records.size() >= proto::kMaxBreaches) {
					break;
				}
				const Door* door = Lookup(candidate.ref);
				if (!door->dungeon) {
					continue;
				}
				proto::BreachRecord r{};
				r.doorRef = candidate.ref->GetFormID();
				r.location = door->location ? door->location->GetFormID() : 0;
				const auto mc = SkyToMc(candidate.ref->GetPosition());
				r.x = static_cast<float>(mc.x);
				r.y = static_cast<float>(mc.y);
				r.z = static_cast<float>(mc.z);
				r.yaw = HeadingToMcYaw(candidate.ref->GetAngleZ());
				r.theme = door->theme;
				r.flags = FlagsOf(*door);
				r.level = door->level;
				std::memcpy(r.name, door->name, sizeof(r.name));
				records.push_back(r);
				published[r.doorRef] = clock;
			}
			Link::Get().WriteBreaches(records.data(), static_cast<std::uint32_t>(records.size()), world ? world->GetFormID() : 0);
			LogChanges(records, SkyToMc(origin));
			std::erase_if(published, [](const auto& a_entry) { return clock - a_entry.second > kRecentSeconds * 4.0f; });
		}

		// The marker goes on the map as discovered, as if the player had walked up to it.
		void Discover(const Door& a_door, RE::PlayerCharacter* a_player)
		{
			auto* marker = a_door.marker ? RE::TESForm::LookupByID<RE::TESObjectREFR>(a_door.marker) : nullptr;
			auto* extra = marker ? marker->extraList.GetByType<RE::ExtraMapMarker>() : nullptr;
			auto* data = extra ? extra->mapData : nullptr;
			if (!data) {
				logger::info("SkyCraft-breach: {} has no map marker to discover", std::string_view(a_door.name));
				return;
			}
			using Flag = RE::MapMarkerData::Flag;
			if (data->flags.all(Flag::kVisible, Flag::kCanTravelTo)) {
				return;
			}
			data->flags.set(Flag::kVisible, Flag::kCanTravelTo);
			// The save keeps a reference's extra data (the marker's flags among it) only once it's
			// marked changed in game.
			marker->AddChange(RE::TESObjectREFR::ChangeFlags::kGameOnlyExtra);
			// What discovering it on foot tells whoever listens (other mods, stats).
			if (auto* source = RE::LocationDiscovery::GetEventSource()) {
				auto*                                 world = a_player->GetWorldspace();
				const char*                           worldId = world ? world->GetFormEditorID() : nullptr;
				const RE::LocationDiscovery::Event    event{ data, worldId ? worldId : "" };
				source->SendEvent(&event);
			}
			logger::info("SkyCraft-breach: {} discovered on the map (marker {:08X})", std::string_view(a_door.name), a_door.marker);
		}

		// SKSE task (main thread, after the frame's player update, so Minecraft's per-frame placement
		// of the player can't undo it): the player goes where the door leads, through Papyrus
		// ObjectReference.MoveTo's own function (TESObjectREFR::MoveTo_Impl, private in CommonLib),
		// to the door's own arrival spot. Skyrim loads the interior behind a loading screen.
		void MovePlayerThrough(RE::FormID a_doorRef)
		{
			if (!move.active) {
				return;  // a game was loaded in between
			}
			auto*       player = RE::PlayerCharacter::GetSingleton();
			auto*       door = RE::TESForm::LookupByID<RE::TESObjectREFR>(a_doorRef);
			Destination dest;
			if (!player || !door || !Resolve(door, dest)) {
				logger::warn("SkyCraft-breach: door {:08X} lost its other side before the move; staying put", a_doorRef);
				move = {};
				return;
			}
			using MoveToFn = void(RE::TESObjectREFR*, const RE::ObjectRefHandle&, RE::TESObjectCELL*, RE::TESWorldSpace*, const RE::NiPoint3&, const RE::NiPoint3&);
			static REL::Relocation<MoveToFn> moveTo{ RELOCATION_ID(56227, 56626) };
			const RE::ObjectRefHandle        target = dest.inside->GetHandle();
			moveTo(player, target, dest.cell, dest.inside->GetWorldspace(), dest.position, dest.rotation);
		}
	}

	void Update(RE::PlayerCharacter* a_player, float a_delta)
	{
		const float dt = std::max(a_delta, 0.0f);
		clock += dt;
		auto* cell = a_player->GetParentCell();
		if (move.active) {
			if (cell && cell->IsInteriorCell()) {
				if (a_player->Is3DLoaded()) {
					if (cell->GetFormID() == move.cell) {
						logger::info("SkyCraft-breach: the player is inside; Minecraft takes over again");
					} else {
						logger::warn("SkyCraft-breach: the player ended up in cell {:08X}, not {:08X}; Minecraft takes over again", cell->GetFormID(), move.cell);
					}
					move = {};
				}
			} else if (cell && (move.age += dt) > kMoveTimeoutSeconds) {
				// Still outside after this long (frames outside only: a loading screen doesn't count).
				logger::warn("SkyCraft-breach: the player never left for cell {:08X}; Minecraft takes over again", move.cell);
				move = {};
			}
		}
		if (!cell) {
			return;  // between places (loading): the table stands until the player is somewhere
		}
		// The interior cell, or the worldspace outside: arriving somewhere new refreshes the table at once.
		const void* place = cell->IsInteriorCell() ? static_cast<const void*>(cell) : static_cast<const void*>(a_player->GetWorldspace());
		refreshTimer -= dt;
		if (place == lastPlace && refreshTimer > 0.0f) {
			return;
		}
		lastPlace = place;
		refreshTimer = kRefreshSeconds;
		Publish(a_player, cell);
	}

	void Enter(RE::PlayerCharacter* a_player, RE::FormID a_doorRef)
	{
		if (move.active) {
			logger::info("SkyCraft-breach: already on the way into a dungeon; door {:08X} ignored", a_doorRef);
			return;
		}
		const auto seen = published.find(a_doorRef);
		if (seen == published.end() || clock - seen->second > kRecentSeconds) {
			logger::warn("SkyCraft-breach: Minecraft named door {:08X}, which isn't a dungeon entrance Skyrim published lately; ignored", a_doorRef);
			return;
		}
		auto* here = a_player->GetParentCell();
		if (!here || here->IsInteriorCell() || a_player->IsDead() || a_player->IsOnMount()) {
			logger::info("SkyCraft-breach: the player can't break in now (indoors, dead or riding); door {:08X} ignored", a_doorRef);
			return;
		}
		auto*       door = RE::TESForm::LookupByID<RE::TESObjectREFR>(a_doorRef);
		Destination dest;
		if (!door || door->IsDisabled() || door->IsDeleted() || !Resolve(door, dest)) {
			logger::warn("SkyCraft-breach: door {:08X} no longer leads into an interior; ignored", a_doorRef);
			return;
		}
		const auto  doorPos = door->GetPosition();
		const auto  playerPos = a_player->GetPosition();
		const float across = std::hypot(doorPos.x - playerPos.x, doorPos.y - playerPos.y) / static_cast<float>(proto::kUnitsPerBlock);
		if (across > kMaxEnterBlocks) {
			logger::warn("SkyCraft-breach: door {:08X} is {:.0f} blocks from the player; ignored", a_doorRef, across);
			return;
		}
		const Door* info = Lookup(door);
		if (!info->dungeon) {
			logger::warn("SkyCraft-breach: door {:08X} doesn't lead into a dungeon; ignored", a_doorRef);
			return;
		}
		Discover(*info, a_player);
		logger::info("SkyCraft-breach: breaking into {} (door {:08X}{} to {:08X} in cell {:08X})", std::string_view(info->name), a_doorRef,
			door->IsLocked() ? ", locked" : "", dest.inside->GetFormID(), dest.cell->GetFormID());
		move = { true, dest.cell->GetFormID(), 0.0f };
		published.erase(a_doorRef);  // the same event twice can't move the player twice
		if (const auto* tasks = SKSE::GetTaskInterface()) {
			tasks->AddTask([a_doorRef]() { MovePlayerThrough(a_doorRef); });
		} else {
			MovePlayerThrough(a_doorRef);
		}
	}

	bool Moving()
	{
		return move.active;
	}

	void Reset()
	{
		doors.clear();
		published.clear();
		lastSet.clear();
		lastPlace = nullptr;
		refreshTimer = 0.0f;
		move = {};
		Link::Get().WriteBreaches(nullptr, 0, 0);
	}
}
