#include "WorldState.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace skycraft::WorldState
{
	namespace
	{
		std::uint16_t ReadVendorMask(RE::Actor* a_actor)
		{
			// GetVendorFaction returns the faction from before it recalculates it, so a merchant's first
			// call comes back empty: read the freshly calculated one then.
			auto* faction = a_actor->GetVendorFaction();
			if (!faction) {
				faction = a_actor->GetActorRuntimeData().vendorFaction;
			}
			if (!faction) {
				return 0;
			}
			const auto&   vendor = faction->vendorData;
			std::uint16_t mask = vendor.vendorValues.buysStolen && !vendor.vendorValues.buysNonStolen ? proto::kVendorStolen : 0;
			auto*         list = vendor.vendorSellBuyList;
			if (!list || vendor.vendorValues.notBuySell) {
				return static_cast<std::uint16_t>(mask | proto::kVendorAny);
			}
			for (auto* form : list->forms) {
				auto* kw = form ? form->As<RE::BGSKeyword>() : nullptr;
				const char* raw = kw ? kw->GetFormEditorID() : nullptr;
				std::string_view id = raw ? raw : "";
				if (id == "VendorItemWeapon") mask |= proto::kVendorWeapon;
				else if (id == "VendorItemArmor" || id == "VendorItemClothing") mask |= proto::kVendorArmor;
				else if (id == "VendorItemOreIngot") mask |= proto::kVendorIngot;
				else if (id == "VendorItemPotion" || id == "VendorItemPoison") mask |= proto::kVendorPotion;
				else if (id == "VendorItemIngredient") mask |= proto::kVendorIngredient;
				else if (id == "VendorItemFood" || id == "VendorItemFoodRaw") mask |= proto::kVendorFood;
				else if (id == "VendorItemArrow") mask |= proto::kVendorArrow;
				else if (id == "VendorItemJewelry" || id == "VendorItemGem") mask |= proto::kVendorJewelry;
				else if (id == "VendorItemBook" || id == "VendorItemSpellTome" || id == "VendorItemScroll" || id == "VendorItemStaff") mask |= proto::kVendorBook;
				else if (id == "VendorItemAnimalHide" || id == "VendorItemAnimalPart") mask |= proto::kVendorHide;
				else if (id == "VendorItemTool" || id == "VendorItemClutter" || id == "VendorItemFireword") mask |= proto::kVendorTool;
			}
			return mask ? mask : static_cast<std::uint16_t>(proto::kVendorAny);
		}
	}

	std::uint16_t VendorMask(RE::Actor* a_actor)
	{
		if (!a_actor) {
			return 0;
		}
		// Runs for every nearby actor every frame. GetVendorFaction recalculates for anyone who isn't a
		// merchant on every call, so look each actor up again only every few seconds (staggered by FormID).
		using Clock = std::chrono::steady_clock;
		struct Cached
		{
			std::uint16_t     mask = 0;
			Clock::time_point next{};
		};
		static std::unordered_map<RE::FormID, Cached> cache;
		const auto  now = Clock::now();
		const auto  id = a_actor->GetFormID();
		auto&       entry = cache[id];
		if (now < entry.next) {
			return entry.mask;
		}
		const std::uint16_t mask = ReadVendorMask(a_actor);
		entry.mask = mask;
		entry.next = now + 2s + std::chrono::milliseconds(id % 1000);
		if (cache.size() > 1024) {
			std::erase_if(cache, [&](const auto& a_entry) { return a_entry.second.next + 30s < now; });
		}
		return mask;
	}

	namespace
	{
		// The town/city/settlement a location is in (else the location's own name) and its PlaceFlags.
		void ReadPlace(RE::BGSLocation* a_location, char (&a_town)[32], std::uint32_t& a_flags)
		{
			a_town[0] = '\0';
			a_flags = 0;
			if (!a_location) {
				return;
			}
			static constexpr std::string_view kDungeonTypes[] = { "LocTypeDungeon", "LocTypeDraugrCrypt", "LocTypeDwarvenAutomatons", "LocTypeFalmerHive",
				"LocTypeBanditCamp", "LocTypeVampireLair", "LocTypeWarlockLair", "LocTypeForswornCamp", "LocTypeAnimalDen", "LocTypeHagravenNest",
				"LocTypeMine", "LocTypeSprigganGrove", "LocTypeDragonPriestLair", "LocTypeGiantCamp" };
			for (const auto type : kDungeonTypes) {
				if (a_location->HasKeywordString(type)) {
					a_flags |= proto::kPlaceDungeon;
					break;
				}
			}
			for (auto* l = a_location; l; l = l->parentLoc) {
				if (l->HasKeywordString("LocTypeCity") || l->HasKeywordString("LocTypeTown") || l->HasKeywordString("LocTypeSettlement")) {
					const char* name = l->GetName();
					strncpy_s(a_town, name ? name : "", _TRUNCATE);
					a_flags |= proto::kPlaceTown;
					return;
				}
			}
			const char* name = a_location->GetName();
			strncpy_s(a_town, name ? name : "", _TRUNCATE);
		}
	}

	namespace
	{
		void CachedPlace(RE::PlayerCharacter* a_player, char (&a_town)[32], std::uint32_t& a_flags)
		{
			a_town[0] = '\0';
			a_flags = 0;
			if (!a_player) {
				return;
			}
			// Runs every frame: only re-read the location's keywords when it changes.
			static RE::BGSLocation* cachedLocation = nullptr;
			static bool             cachedValid = false;
			static char             cachedTown[32] = {};
			static std::uint32_t    cachedFlags = 0;
			auto*                   location = a_player->GetCurrentLocation();
			if (!cachedValid || cachedLocation != location) {
				ReadPlace(location, cachedTown, cachedFlags);
				cachedLocation = location;
				cachedValid = true;
				logger::info("SkyCraft-place: now in '{}' (place flags {:X})", std::string_view(cachedTown), cachedFlags);
			}
			std::memcpy(a_town, cachedTown, sizeof(cachedTown));
			a_flags = cachedFlags;
		}
	}

	void Place(RE::PlayerCharacter* a_player, char (&a_town)[32], std::uint32_t& a_flags)
	{
		CachedPlace(a_player, a_town, a_flags);
		if (a_player && a_player->GetParentCell() && a_player->GetParentCell()->IsInteriorCell()) {
			a_flags |= proto::kPlaceInterior;
		}
	}

	std::uint32_t Carried(RE::PlayerCharacter* a_player)
	{
		// The Elder Scrolls (BOOK records, read from Skyrim.esm and Dawnguard.esm with their EditorIDs and
		// names): quest items, so they never convert and Minecraft can't see them. Looked up by file and
		// local FormID, so the load order doesn't matter; a missing Dawnguard just leaves its three out.
		struct ScrollForm
		{
			RE::FormID       local;
			std::string_view file;
		};
		static constexpr ScrollForm kElderScrolls[] = {
			{ 0x02D513, "Skyrim.esm" },     // DA04ElderScroll, "Elder Scroll" ("Elder Scroll (Dragon)" with Dawnguard)
			{ 0x048782, "Skyrim.esm" },     // DA04ElderScrollFurled
			{ 0x0126DC, "Dawnguard.esm" },  // DLC1ElderScrollDragon, "Elder Scroll (Dragon)"
			{ 0x011A13, "Dawnguard.esm" },  // DLC1ElderScrollSun, "Elder Scroll (Sun)"
			{ 0x0118F9, "Dawnguard.esm" },  // DLC1ElderScrollBlood, "Elder Scroll (Blood)"
		};
		static std::vector<RE::TESBoundObject*> scrolls;
		static bool                             resolved = false;
		static std::uint32_t                    cached = 0;
		static auto                             nextCheck = std::chrono::steady_clock::time_point{};
		if (!a_player) {
			return 0;
		}
		if (!resolved) {
			resolved = true;
			if (auto* data = RE::TESDataHandler::GetSingleton()) {
				for (const auto& s : kElderScrolls) {
					if (auto* book = data->LookupForm<RE::TESObjectBOOK>(s.local, s.file)) {
						scrolls.push_back(book);
					}
				}
			}
			logger::info("SkyCraft-portal: {} Elder Scroll forms to look for in the player's inventory", scrolls.size());
		}
		// The inventory once a second is plenty: the portal at the Time-Wound asks when the player steps in.
		const auto now = std::chrono::steady_clock::now();
		if (now < nextCheck) {
			return cached;
		}
		nextCheck = now + std::chrono::seconds(1);
		std::uint32_t flags = 0;
		if (!scrolls.empty()) {
			const auto inventory = a_player->GetInventory([](RE::TESBoundObject& a_obj) { return std::ranges::find(scrolls, &a_obj) != scrolls.end(); });
			for (const auto& [item, entry] : inventory) {
				if (entry.first > 0) {
					flags |= proto::kCarryElderScroll;
				}
			}
		}
		if (flags != cached) {
			logger::info("SkyCraft-portal: the player {} an Elder Scroll", (flags & proto::kCarryElderScroll) ? "now carries" : "no longer carries");
		}
		cached = flags;
		return cached;
	}

	void PassTime(float a_hours)
	{
		auto* cal = RE::Calendar::GetSingleton();
		if (!cal || !cal->gameHour || !cal->gameDaysPassed || !cal->gameDay || !cal->gameMonth || !cal->gameYear || !(a_hours > 0.0f) || a_hours > 24.0f) {
			return;
		}
		// The globals are what Papyrus's GlobalVariable.SetValue writes too. GameDaysPassed (AI, timers, the
		// day of the week) and the date move together, and the hour is left below 24 so nothing rolls twice.
		const float before = cal->gameHour->value;
		cal->gameDaysPassed->value += a_hours / 24.0f;
		float hour = before + a_hours;
		if (hour >= 24.0f) {
			// Roll the date over like Skyrim's own clock does (Skyrim months have Earth's lengths).
			hour -= 24.0f;
			float day = cal->gameDay->value + 1.0f;
			const int month = static_cast<int>(cal->gameMonth->value);
			if (day > static_cast<float>(RE::Calendar::DAYS_IN_MONTH[std::clamp(month, 0, 11)])) {
				day = 1.0f;
				if (month >= 11) {
					cal->gameMonth->value = 0.0f;
					cal->gameYear->value += 1.0f;
				} else {
					cal->gameMonth->value = static_cast<float>(month + 1);
				}
			}
			cal->gameDay->value = day;
		}
		cal->gameHour->value = hour;
		logger::info("SkyCraft-time: slept in a Minecraft bed, Skyrim time {:.2f} -> {:.2f} (+{:.1f} h)", before, hour, a_hours);
	}
}
