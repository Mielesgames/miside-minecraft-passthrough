#include "Loot.h"

#include <algorithm>
#include <cstring>

namespace skycraft::Loot
{
	namespace
	{
		std::string_view TypeName(RE::FormType a_type)
		{
			switch (a_type) {
			case RE::FormType::Weapon:
				return "weapon";
			case RE::FormType::Armor:
				return "armor";
			case RE::FormType::Ammo:
				return "ammo";
			case RE::FormType::AlchemyItem:
				return "potion";
			case RE::FormType::Ingredient:
				return "ingredient";
			case RE::FormType::Misc:
				return "misc";
			case RE::FormType::Book:
				return "book";
			case RE::FormType::Scroll:
				return "scroll";
			case RE::FormType::KeyMaster:
				return "key";
			case RE::FormType::SoulGem:
				return "soulgem";
			case RE::FormType::Light:
				return "light";
			default:
				return "other";
			}
		}

		struct Source
		{
			std::string        text;
			proto::ItemSource  kind = proto::kSrcWorld;
			bool               chest = false;
		};

		// Where the item came from: the world, a corpse, a container or a living actor. Bought items come
		// from the merchant's chest (a container, usually named "Chest") or the merchant (an actor).
		// The chest test reads the English display name.
		Source FromWhere(RE::FormID a_from)
		{
			if (a_from == 0) {
				return { "world", proto::kSrcWorld };
			}
			auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(a_from);
			if (!ref) {
				return { std::format("{:08X}", a_from), proto::kSrcContainer };
			}
			const char* name = ref->GetDisplayFullName();
			std::string label = name && *name ? name : std::format("{:08X}", a_from);
			if (auto* actor = ref->As<RE::Actor>()) {
				const bool dead = actor->IsDead();
				return { std::format("{} of {}", dead ? "corpse" : "actor", label), dead ? proto::kSrcCorpse : proto::kSrcActor };
			}
			const bool chest = label.find("Chest") != std::string::npos || label.find("chest") != std::string::npos || label.find("Strongbox") != std::string::npos ||
			                   label.find("Coffer") != std::string::npos;
			return { "container " + label, proto::kSrcContainer, chest };
		}

		proto::ItemMaterial MaterialFrom(std::string_view a_keyword)
		{
			// WeapMaterialIron, ArmorMaterialSteelPlate, DLC1WeapMaterialDragonbone, ...
			const auto at = a_keyword.find("Material");
			if (at == std::string_view::npos) {
				return proto::kMatNone;
			}
			const auto m = a_keyword.substr(at + 8);
			auto starts = [&](std::string_view p) { return m.substr(0, p.size()) == p; };
			if (starts("Wood")) return proto::kMatWood;
			if (starts("Iron")) return proto::kMatIron;
			if (starts("Steel")) return proto::kMatSteel;
			if (starts("Imperial")) return proto::kMatImperial;
			if (starts("Orcish")) return proto::kMatOrcish;
			if (starts("Dwarven")) return proto::kMatDwarven;
			if (starts("Elven")) return proto::kMatElven;
			if (starts("Glass")) return proto::kMatGlass;
			if (starts("Ebony")) return proto::kMatEbony;
			if (starts("Daedric")) return proto::kMatDaedric;
			if (starts("Dragon")) return proto::kMatDragon;
			if (starts("Silver")) return proto::kMatSilver;
			if (starts("Hide") || starts("Leather") || starts("Studded") || starts("Fur")) return proto::kMatHide;
			if (starts("Draugr")) return proto::kMatDraugr;
			if (starts("Falmer")) return proto::kMatFalmer;
			return proto::kMatNone;
		}

		// Tags and material from the item's keywords; the keyword list as text for the log.
		std::string ReadKeywords(RE::TESForm* a_item, std::uint32_t& a_tags, std::uint8_t& a_material)
		{
			std::string out;
			auto* form = a_item->As<RE::BGSKeywordForm>();
			if (!form) {
				return out;
			}
			for (std::uint32_t i = 0; i < form->numKeywords; ++i) {
				auto* kw = form->keywords[i];
				if (!kw) {
					continue;
				}
				const char*      raw = kw->GetFormEditorID();
				std::string_view id = raw ? raw : "";
				if (id == "VendorItemFood") a_tags |= proto::kTagFood;
				else if (id == "VendorItemFoodRaw") a_tags |= proto::kTagFoodRaw;
				else if (id == "VendorItemAnimalHide") a_tags |= proto::kTagHide;
				else if (id == "VendorItemOreIngot") a_tags |= proto::kTagOreIngot;
				else if (id == "VendorItemPotion") a_tags |= proto::kTagPotion;
				else if (id == "VendorItemPoison") a_tags |= proto::kTagPoison;
				else if (id == "VendorItemIngredient") a_tags |= proto::kTagIngredient;
				else if (id == "ArmorClothing") a_tags |= proto::kTagClothing;
				else if (id == "ArmorJewelry") a_tags |= proto::kTagJewelry;
				else if (id == "VendorItemGem") a_tags |= proto::kTagGem;
				else if (id == "VendorItemClutter") a_tags |= proto::kTagClutter;
				if (a_material == proto::kMatNone) {
					a_material = MaterialFrom(id);
				}
				if (!out.empty()) {
					out += ' ';
				}
				out += id.empty() ? std::format("{:08X}", kw->GetFormID()) : std::string(id);
			}
			return out;
		}

		class ContainerSink final : public RE::BSTEventSink<RE::TESContainerChangedEvent>
		{
		public:
			static ContainerSink* Get()
			{
				static ContainerSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESContainerChangedEvent* a_event, RE::BSTEventSource<RE::TESContainerChangedEvent>*) override
			{
				auto* player = RE::PlayerCharacter::GetSingleton();
				if (!a_event || !player || a_event->newContainer != player->GetFormID() || a_event->itemCount <= 0) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto* item = RE::TESForm::LookupByID(a_event->baseObj);
				if (!item) {
					return RE::BSEventNotifyControl::kContinue;
				}
				proto::ItemEvent ev{};
				ev.formId = item->GetFormID();
				ev.count = a_event->itemCount;
				std::string_view type = TypeName(item->GetFormType());
				std::int32_t     value = 0;
				switch (item->GetFormType()) {
				case RE::FormType::Weapon:
					ev.tags |= proto::kTagWeapon;
					break;
				case RE::FormType::Armor:
					ev.tags |= proto::kTagArmor;
					break;
				case RE::FormType::Ammo:
					ev.tags |= proto::kTagArrow;
					break;
				case RE::FormType::KeyMaster:
					ev.tags |= proto::kTagKey;
					break;
				case RE::FormType::Book:
				case RE::FormType::Scroll:
					ev.tags |= proto::kTagBook;
					break;
				case RE::FormType::SoulGem:
					// Portal keys: the filled base forms (SoulGemGrandFilled and kin) hold their soul on the form.
					// A gem filled by Soul Trap keeps its soul on the inventory stack instead, so it isn't tagged.
					ev.tags |= proto::kTagSoulGem;
					if (const auto* gem = item->As<RE::TESSoulGem>(); gem && gem->GetContainedSoul() != RE::SOUL_LEVEL::kNone) {
						ev.tags |= proto::kTagSoulFilled;
					}
					break;
				default:
					break;
				}
				if (ev.formId == 0xF) {
					ev.tags |= proto::kTagGold;
				}
				if (auto* alch = item->As<RE::AlchemyItem>()) {
					if (alch->IsFood()) {
						type = "food";
					}
					value = alch->GetGoldValue();
				} else if (auto* valueForm = item->As<RE::TESValueForm>()) {
					value = valueForm->value;
				}
				const std::string keywords = ReadKeywords(item, ev.tags, ev.material);
				if (ev.tags & (proto::kTagClothing | proto::kTagJewelry)) {
					ev.tags &= ~proto::kTagArmor;
				}
				const Source from = FromWhere(a_event->oldContainer);
				ev.source = from.kind;
				// Anything that arrives while Skyrim's shop is open was bought (from the merchant or their chest).
				const bool bought = (from.kind == proto::kSrcContainer || from.kind == proto::kSrcActor) && RE::UI::GetSingleton() &&
				                    RE::UI::GetSingleton()->IsMenuOpen(RE::BarterMenu::MENU_NAME);
				if (bought) {
					ev.source = proto::kSrcBought;
				}
				ev.container = a_event->oldContainer;
				ev.chest = from.chest ? 1 : 0;
				if (auto* bound = item->As<RE::TESBoundObject>()) {
					// Quest items stay in Skyrim: Minecraft never converts them (removing one can break its quest).
					const auto inventory = player->GetInventory([bound](RE::TESBoundObject& a_obj) { return &a_obj == bound; });
					const auto it = inventory.find(bound);
					ev.quest = it != inventory.end() && it->second.second && it->second.second->IsQuestObject() ? 1 : 0;
				}
				const char* name = item->GetName();
				strncpy_s(ev.name, name ? name : "", _TRUNCATE);
				logger::info("SkyCraft-loot: got {} x {} [{} {:08X}] value {} from {} | {}", ev.count, name && *name ? name : "?", type, ev.formId, value, from.text,
					keywords);
				Link::Get().PushItem(ev);
				return RE::BSEventNotifyControl::kContinue;
			}
		};
	}

	void Install()
	{
		if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
			events->AddEventSink<RE::TESContainerChangedEvent>(ContainerSink::Get());
			logger::info("SkyCraft-loot: watching the player's inventory");
		}
	}

	void Take(RE::PlayerCharacter* a_player, RE::FormID a_item, std::int32_t a_count)
	{
		auto* item = RE::TESForm::LookupByID<RE::TESBoundObject>(a_item);
		if (!a_player || !item || a_count <= 0) {
			return;
		}
		const auto inventory = a_player->GetInventory([item](RE::TESBoundObject& a_obj) { return &a_obj == item; });
		const auto it = inventory.find(item);
		const std::int32_t have = it != inventory.end() ? it->second.first : 0;
		const std::int32_t take = std::min(a_count, have);
		if (take <= 0) {
			logger::warn("SkyCraft-loot: Minecraft converted {:08X} but the player no longer has it", a_item);
			return;
		}
		// RemoveItem would take a quest item too and could break its quest: it stays (the player keeps both).
		if (it->second.second && it->second.second->IsQuestObject()) {
			logger::warn("SkyCraft-loot: Minecraft converted {} but it's a quest item; left in Skyrim", item->GetName());
			return;
		}
		a_player->RemoveItem(item, take, RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
		logger::info("SkyCraft-loot: converted {} x {} to Minecraft, removed from Skyrim", take, item->GetName());
	}
}
