#include "Companion.h"

#include "AnimalKinds.h"

namespace skycraft::Companion
{
	namespace
	{
		constexpr std::uint32_t kSaveVersion = 1;
		constexpr std::size_t   kMaxTamed = 64;              // the oldest is forgotten past this (co-save and memory)
		constexpr float         kSlotCheckSeconds = 0.25f;   // how often the animal follower slot is looked at
		constexpr float         kPruneSeconds = 5.0f;        // how often tamed ones that died are forgotten
		constexpr float         kReachBlocks = 64.0f;        // Minecraft clicks a stand-in in reach; further is a stale request
		constexpr RE::FormID    kPlayerFaction = 0x00000DB1;  // Skyrim.esm PlayerFaction

		// One the player tamed through Minecraft (or one that held the animal follower slot when a newer one
		// took it): its kind is kept to notice a FormID Skyrim reused for another actor (created references).
		struct Tamed
		{
			RE::FormID    formId{ 0 };
			std::uint32_t kind{ proto::kAnimalNone };
		};
		std::vector<Tamed> tamed;   // the current game's
		std::vector<Tamed> loaded;  // from the co-save being loaded; taken on by Reset()

		RE::FormID slotHolder = 0;  // the actor in Skyrim's animal follower slot (0: none)
		float      slotTimer = 0.0f;
		float      pruneTimer = 0.0f;

		// Races live as long as the game: their kind is worked out once.
		std::unordered_map<const RE::TESRace*, std::uint32_t> kinds;

		// Skyrim.esm's DialogueFollower quest (its script, DialogueFollowerScript, is how dogs join the player),
		// found by its editor ID the first time it's needed; and the ID of its animal alias.
		RE::TESQuest*                followerQuest = nullptr;
		bool                         questLooked = false;
		std::optional<std::uint32_t> animalAlias;

		RE::TESQuest* FollowerQuest()
		{
			if (questLooked) {
				return followerQuest;
			}
			auto* data = RE::TESDataHandler::GetSingleton();
			if (!data) {
				return nullptr;  // before the data loaded: asked again later
			}
			questLooked = true;
			for (auto* quest : data->GetFormArray<RE::TESQuest>()) {
				const char* id = quest ? quest->GetFormEditorID() : nullptr;
				if (id && AnimalKinds::EqualsNoCase(id, "DialogueFollower")) {
					followerQuest = quest;
					break;
				}
			}
			if (!followerQuest) {
				logger::warn("SkyCraft-companion: Skyrim's DialogueFollower quest isn't loaded; tamed dogs and wolves won't follow, and Skyrim's own animal followers aren't recognised");
				return nullptr;
			}
			for (auto* alias : followerQuest->aliases) {
				const char* name = alias ? alias->aliasName.c_str() : nullptr;
				if (name && AnimalKinds::EqualsNoCase(name, "Animal")) {
					animalAlias = alias->aliasID;
					break;
				}
			}
			logger::info("SkyCraft-companion: Skyrim's follower quest is {:08X}; its animal slot is {}", followerQuest->GetFormID(),
				animalAlias ? "the alias named Animal" : "whichever of its aliases holds a creature (no alias named Animal)");
			return followerQuest;
		}

		// Who holds Skyrim's animal follower slot right now (0: nobody).
		RE::FormID FindSlotHolder()
		{
			auto* quest = FollowerQuest();
			if (!quest || !quest->IsRunning()) {
				return 0;
			}
			if (animalAlias) {
				const auto ref = quest->GetAliasedRef(*animalAlias).get();
				return ref ? ref->GetFormID() : 0;
			}
			// No alias named Animal (a follower mod renamed it): the alias that holds a creature, not a person.
			for (auto* alias : quest->aliases) {
				if (!alias) {
					continue;
				}
				const auto ref = quest->GetAliasedRef(alias->aliasID).get();
				auto*      actor = ref ? ref->As<RE::Actor>() : nullptr;
				if (actor && !actor->HasKeywordString("ActorTypeNPC")) {
					return actor->GetFormID();
				}
			}
			return 0;
		}

		std::uint32_t KindOf(RE::Actor* a_actor)
		{
			const RE::TESRace* race = a_actor->GetRace();
			if (!race) {
				return proto::kAnimalNone;
			}
			if (const auto it = kinds.find(race); it != kinds.end()) {
				return it->second;
			}
			const char*         id = race->GetFormEditorID();
			const std::uint32_t kind = AnimalKinds::FromRace(id ? id : "");
			kinds.emplace(race, kind);
			if (kind != proto::kAnimalNone) {
				logger::info("SkyCraft-companion: race {} ({:08X}) is tamed like a {}", id ? id : "?", race->GetFormID(), AnimalKinds::Name(kind));
			}
			return kind;
		}

		std::string_view NameOf(RE::Actor* a_actor)
		{
			const char* name = a_actor->GetDisplayFullName();
			return name && *name ? std::string_view{ name } : std::string_view{ "animal" };
		}

		bool IsOurs(RE::FormID a_id)
		{
			return a_id != 0 && std::ranges::any_of(tamed, [a_id](const Tamed& a_t) { return a_t.formId == a_id; });
		}

		bool OwnedByPlayer(RE::Actor* a_actor, RE::PlayerCharacter* a_player)
		{
			const RE::TESForm* owner = a_actor->GetOwner();
			if (!owner) {
				return false;
			}
			const RE::TESForm* base = a_player->GetActorBase();
			return owner == base || owner->GetFormID() == kPlayerFaction;
		}

		bool Waiting(RE::Actor* a_actor)
		{
			return a_actor->AsActorValueOwner()->GetActorValue(RE::ActorValue::kWaitingForPlayer) != 0.0f;
		}

		struct State
		{
			std::uint32_t kind{ proto::kAnimalNone };
			bool          inSlot{ false };
			bool          ours{ false };
			bool          teammate{ false };
			bool          owned{ false };

			[[nodiscard]] bool IsCompanion() const { return inSlot || (kind != proto::kAnimalNone && (ours || teammate || owned)); }
		};

		State StateOf(RE::Actor* a_actor, RE::PlayerCharacter* a_player)
		{
			State s;
			// Summoned creatures (a familiar, a conjured wolf) are the caster's for a while, never tamed.
			if (a_actor->GetCommandingActor()) {
				return s;
			}
			const auto id = a_actor->GetFormID();
			s.kind = KindOf(a_actor);
			s.inSlot = id == slotHolder && id != 0;
			if (s.kind == proto::kAnimalNone && !s.inSlot) {
				return s;
			}
			s.ours = IsOurs(id);
			s.teammate = a_actor->IsPlayerTeammate();
			s.owned = s.kind == proto::kAnimalHorse && a_player && OwnedByPlayer(a_actor, a_player);
			return s;
		}

		void Remember(RE::FormID a_id, std::uint32_t a_kind)
		{
			if (a_id == 0 || IsOurs(a_id)) {
				return;
			}
			if (tamed.size() >= kMaxTamed) {
				logger::info("SkyCraft-companion: {} tamed animals remembered; forgetting the oldest ({:08X})", tamed.size(), tamed.front().formId);
				tamed.erase(tamed.begin());
			}
			tamed.push_back({ a_id, a_kind });
		}

		// Skyrim's follower packages read WaitingForPlayer: 1 waits where it is (sandboxing about the spot), 0
		// follows. The same thing the vanilla AnimalWait / AnimalFollow do, without their three-day timer: a
		// sitting Minecraft wolf waits for as long as it takes.
		void SetWaiting(RE::Actor* a_actor, bool a_wait)
		{
			a_actor->AsActorValueOwner()->SetActorValue(RE::ActorValue::kWaitingForPlayer, a_wait ? 1.0f : 0.0f);
			a_actor->EvaluatePackage(false, false);
		}

		// Ends the animal's fight with the player and its friends, and keeps it from picking new ones with
		// people it merely doesn't know: wild wolves attack whoever they meet, a follower mustn't.
		void Calm(RE::Actor* a_actor)
		{
			if (auto* lists = RE::ProcessLists::GetSingleton()) {
				lists->StopCombatAndAlarmOnActor(a_actor, false);
			}
			a_actor->StopCombat();
			auto* av = a_actor->AsActorValueOwner();
			if (av->GetActorValue(RE::ActorValue::kAggression) > 1.0f) {
				av->SetActorValue(RE::ActorValue::kAggression, 1.0f);  // Aggressive: only enemies, never neutral folk
			}
		}

		// Skyrim's animal follower slot, the vanilla way: DialogueFollowerScript.SetAnimal(the actor) on the
		// DialogueFollower quest (relationship rank Ally with the player, teammate, the animal alias and its
		// follow and wait packages, PlayerAnimalCount). Queued on Papyrus; it runs within a frame or so.
		bool TakeSlot(RE::Actor* a_actor)
		{
			auto* quest = FollowerQuest();
			auto* vm = RE::BSScript::Internal::VirtualMachine::GetSingleton();
			if (!quest || !vm) {
				return false;
			}
			auto* policy = vm->GetObjectHandlePolicy();
			if (!policy) {
				return false;
			}
			const RE::VMHandle handle = policy->GetHandleForObject(RE::TESQuest::FORMTYPE, quest);
			if (handle == policy->EmptyHandle()) {
				return false;
			}
			RE::BSTSmartPointer<RE::BSScript::IStackCallbackFunctor> callback;
			// Handed to the VM, as CommonLib's own event senders hand theirs over (never deleted here).
			auto* args = RE::MakeFunctionArguments(static_cast<RE::TESObjectREFR*>(a_actor));
			return vm->DispatchMethodCall(handle, "DialogueFollowerScript", "SetAnimal", args, callback);
		}

		// The animal takes the slot; a dog, wolf or horse it takes it from stays the player's, left behind
		// (another creature, a troll say, is simply no longer following: Minecraft has no rules for it).
		void Follow(RE::Actor* a_actor)
		{
			const auto id = a_actor->GetFormID();
			if (id == slotHolder) {
				return;
			}
			const auto before = slotHolder;
			if (!TakeSlot(a_actor)) {
				logger::warn("SkyCraft-companion: couldn't make {} ({:08X}) Skyrim's animal follower (DialogueFollowerScript.SetAnimal not reached)", NameOf(a_actor), id);
				return;
			}
			if (before != 0) {
				if (auto* previous = RE::TESForm::LookupByID<RE::Actor>(before); previous && !previous->IsDead()) {
					const auto kind = KindOf(previous);
					if (kind != proto::kAnimalNone) {
						Remember(before, kind);
					}
					logger::info("SkyCraft-companion: {} ({:08X}) takes Skyrim's animal follower slot from {} ({:08X}){}", NameOf(a_actor), id, NameOf(previous), before,
						kind != proto::kAnimalNone ? ", who stays the player's, left behind" : "");
				}
			}
			slotHolder = id;  // SetAnimal fills the alias a moment later; the next slot check confirms it
		}

		void Tame(RE::PlayerCharacter* a_player, RE::Actor* a_actor, const State& a_state, std::uint32_t a_seen)
		{
			const auto id = a_actor->GetFormID();
			if (a_state.kind == proto::kAnimalNone || a_state.kind != a_seen) {
				logger::warn("SkyCraft-companion: Minecraft tamed {} ({:08X}) as a {}, but Skyrim sees a {}; refused", NameOf(a_actor), id, AnimalKinds::Name(a_seen),
					AnimalKinds::Name(a_state.kind));
				return;
			}
			if (a_state.IsCompanion()) {
				logger::info("SkyCraft-companion: {} ({:08X}) is the player's already", NameOf(a_actor), id);
				return;
			}
			if (a_actor->IsEssential()) {
				logger::info("SkyCraft-companion: {} ({:08X}) is essential (a quest's); it can't be tamed", NameOf(a_actor), id);
				return;
			}
			RE::NiPointer<RE::Actor> rider;
			if (a_actor->GetMountedBy(rider) && rider) {
				logger::info("SkyCraft-companion: {} ({:08X}) has a rider; it can't be tamed", NameOf(a_actor), id);
				return;
			}
			Calm(a_actor);
			if (a_state.kind == proto::kAnimalHorse) {
				// The player's own, as a bought horse is: theirs to ride, not to steal. It keeps to its own ways
				// (a Minecraft horse doesn't follow either; a stable's horse stays by its stable) until it's told to
				// follow.
				a_actor->SetOwner(a_player->GetActorBase());
				Remember(id, a_state.kind);
				logger::info("SkyCraft-companion: {} ({:08X}) is tamed: the player's horse now", NameOf(a_actor), id);
				return;
			}
			// Dogs and wolves: Skyrim's animal follower, sitting as a freshly tamed Minecraft wolf does.
			SetWaiting(a_actor, true);
			Remember(id, a_state.kind);
			Follow(a_actor);
			logger::info("SkyCraft-companion: {} ({:08X}, a {}) is tamed: Skyrim's animal follower now, waiting", NameOf(a_actor), id, AnimalKinds::Name(a_state.kind));
		}

		void Order(RE::Actor* a_actor, const State& a_state, bool a_follow)
		{
			const auto id = a_actor->GetFormID();
			if (a_follow && !a_state.inSlot) {
				// Ours, a horse of the player's, or a creature teammate out of the slot (one that held it before):
				// it takes the slot. Not an essential one: that's a quest's follower, led by its own quest (Barbas).
				if (a_state.ours || a_state.owned || (a_state.teammate && !a_actor->IsEssential())) {
					Follow(a_actor);
				}
			}
			SetWaiting(a_actor, !a_follow);
			logger::info("SkyCraft-companion: {} ({:08X}) {}", NameOf(a_actor), id, a_follow ? "follows the player" : "waits");
		}

		void Heal(RE::Actor* a_actor, float a_share)
		{
			if (!(a_share > 0.0f)) {
				return;
			}
			const float max = a_actor->GetActorValueMax(RE::ActorValue::kHealth);
			auto*       av = a_actor->AsActorValueOwner();
			const float before = av->GetActorValue(RE::ActorValue::kHealth);
			av->RestoreActorValue(RE::ActorValue::kHealth, std::min(a_share, 1.0f) * max);
			logger::info("SkyCraft-companion: fed {} ({:08X}): health {:.0f} -> {:.0f} of {:.0f}", NameOf(a_actor), a_actor->GetFormID(), before,
				av->GetActorValue(RE::ActorValue::kHealth), max);
		}

		// Tamed ones that died are forgotten, and so are entries whose FormID Skyrim has since given to an
		// actor of another kind (created references' IDs are reused). One not loaded right now is kept.
		void Prune()
		{
			std::erase_if(tamed, [](const Tamed& a_t) {
				auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_t.formId);
				if (!actor) {
					return false;
				}
				if (actor->IsDead()) {
					logger::info("SkyCraft-companion: {} ({:08X}) died", NameOf(actor), a_t.formId);
					return true;
				}
				return KindOf(actor) != a_t.kind;
			});
		}
	}

	std::uint32_t Flags(RE::Actor* a_actor, RE::PlayerCharacter* a_player)
	{
		if (!a_actor) {
			return 0;
		}
		const State s = StateOf(a_actor, a_player);
		std::uint32_t flags = (s.kind << proto::kActorKindShift) & proto::kActorKindMask;
		if (!s.IsCompanion()) {
			return flags;
		}
		flags |= proto::kActorCompanion;
		// Following: in the slot and not told to wait; a quest's or a mod's creature follower out of the slot
		// follows by its own packages, which also read WaitingForPlayer. Ours and owned horses out of the slot
		// stay behind.
		bool following = false;
		if (s.inSlot) {
			following = !Waiting(a_actor);
		} else if (s.teammate && !s.ours && !s.owned) {
			following = !Waiting(a_actor);
		}
		if (!following) {
			flags |= proto::kActorCompanionWaiting;
		}
		return flags;
	}

	void OnEvent(RE::PlayerCharacter* a_player, const proto::McEvent& a_event)
	{
		auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_event.formId);
		if (!actor || actor == a_player) {
			logger::warn("SkyCraft-companion: Minecraft asked about {:08X}, which isn't an actor here", a_event.formId);
			return;
		}
		if (actor->IsDead() || actor->IsDisabled() || actor->IsDeleted() || !actor->Is3DLoaded()) {
			logger::info("SkyCraft-companion: {} ({:08X}) isn't there to tame or order (dead, disabled or unloaded)", NameOf(actor), a_event.formId);
			return;
		}
		const float away = actor->GetPosition().GetDistance(a_player->GetPosition()) / static_cast<float>(proto::kUnitsPerBlock);
		if (away > kReachBlocks) {
			logger::info("SkyCraft-companion: {} ({:08X}) is {:.0f} blocks away; request dropped", NameOf(actor), a_event.formId, away);
			return;
		}
		const State s = StateOf(actor, a_player);
		if (a_event.flags == proto::kCompanionTame) {
			Tame(a_player, actor, s, a_event.weapon);
			return;
		}
		if (!s.IsCompanion()) {
			logger::info("SkyCraft-companion: {} ({:08X}) isn't the player's; nothing done", NameOf(actor), a_event.formId);
			return;
		}
		switch (a_event.flags) {
		case proto::kCompanionFollow:
			Order(actor, s, true);
			break;
		case proto::kCompanionWait:
			Order(actor, s, false);
			break;
		case proto::kCompanionHeal:
			Heal(actor, a_event.a);
			break;
		default:
			logger::warn("SkyCraft-companion: unknown companion action {} for {:08X}", a_event.flags, a_event.formId);
			break;
		}
	}

	void Update(RE::PlayerCharacter*, float a_delta)
	{
		slotTimer -= a_delta;
		if (slotTimer <= 0.0f) {
			slotTimer = kSlotCheckSeconds;
			const auto now = FindSlotHolder();
			if (now != slotHolder) {
				auto* actor = now ? RE::TESForm::LookupByID<RE::Actor>(now) : nullptr;
				logger::info("SkyCraft-companion: Skyrim's animal follower is {}", actor ? std::format("{} ({:08X})", NameOf(actor), now) : std::string("nobody"));
				slotHolder = now;
			}
		}
		pruneTimer -= a_delta;
		if (pruneTimer <= 0.0f) {
			pruneTimer = kPruneSeconds;
			Prune();
		}
	}

	void Reset()
	{
		tamed.clear();
		for (const auto& t : loaded) {
			if (tamed.size() < kMaxTamed && !IsOurs(t.formId)) {
				tamed.push_back(t);
			}
		}
		loaded.clear();
		slotHolder = 0;
		slotTimer = 0.0f;
		pruneTimer = kPruneSeconds;
		if (!tamed.empty()) {
			logger::info("SkyCraft-companion: the loaded game has {} animals tamed through Minecraft", tamed.size());
		}
	}

	// ---- the co-save (main thread: SKSE saves and loads there; CoSave.cpp calls these) ------------
	// Record 'CMPN' version 1: count, then per animal its FormID and AnimalKind.
	void Save(SKSE::SerializationInterface* a_intfc)
	{
		if (!a_intfc->OpenRecord(kSaveRecord, kSaveVersion)) {
			logger::warn("SkyCraft-companion: couldn't write the tamed animals to the co-save");
			return;
		}
		const auto count = static_cast<std::uint32_t>(std::min(tamed.size(), kMaxTamed));
		a_intfc->WriteRecordData(count);
		for (std::uint32_t i = 0; i < count; ++i) {
			a_intfc->WriteRecordData(tamed[i].formId);
			a_intfc->WriteRecordData(tamed[i].kind);
		}
	}

	void Load(SKSE::SerializationInterface* a_intfc, std::uint32_t a_version)
	{
		if (a_version != kSaveVersion) {
			logger::warn("SkyCraft-companion: the co-save's tamed animals are version {} (this SkyCraft reads {}); skipped", a_version, kSaveVersion);
			return;
		}
		std::uint32_t count = 0;
		if (a_intfc->ReadRecordData(count) != sizeof(count)) {
			return;
		}
		for (std::uint32_t i = 0; i < count && i < kMaxTamed; ++i) {
			RE::FormID    id = 0;
			std::uint32_t kind = proto::kAnimalNone;
			if (a_intfc->ReadRecordData(id) != sizeof(id) || a_intfc->ReadRecordData(kind) != sizeof(kind)) {
				break;
			}
			RE::FormID now = 0;
			if (a_intfc->ResolveFormID(id, now) && now != 0 && kind != proto::kAnimalNone && kind <= proto::kAnimalHorse) {
				loaded.push_back({ now, kind });
			}
		}
	}

	void Revert()
	{
		loaded.clear();
	}
}
