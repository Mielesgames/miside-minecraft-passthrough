#include "Travel.h"

#include "Breach.h"

namespace skycraft::Travel
{
	namespace
	{
		constexpr float  kMinGapSeconds = 3.0f;         // one portal trip at most this often (a loading screen each)
		constexpr float  kArriveTimeoutSeconds = 8.0f;  // frames outside loading screens without arriving: stop waiting
		constexpr double kMaxAcrossBlocks = 20000.0;    // farther out than any Skyrim world reaches (Tamriel: about 3700)
		constexpr double kMaxUpBlocks = 2048.0;         // the Minecraft world's height either way
		constexpr float  kLiftUnits = 4.0f;             // set the feet down a hair above the spot, never in the ground
		constexpr float  kArrivedUnits = 8.0f * static_cast<float>(proto::kUnitsPerBlock);  // this close (flat) is there
		constexpr float  kTwoPi = 6.28318531f;

		struct Trip
		{
			bool         active{ false };
			RE::FormID   world{ 0 };  // the destination: a worldspace, or an interior cell when interior
			bool         interior{ false };
			RE::NiPoint3 to{};        // Skyrim units
			float        heading{ 0.0f };
			float        age{ 0.0f };  // seconds of frames outside loading screens since the request
		};

		Trip  trip;
		float clock = 0.0f;  // seconds of frames seen (a paused game doesn't count)
		float lastTrip = -1.0e9f;

		// The player's world as Game.cpp names it in SkyState::worldId: the interior cell, or the worldspace outside.
		RE::FormID WorldOf(RE::PlayerCharacter* a_player)
		{
			auto* cell = a_player->GetParentCell();
			if (!cell) {
				return 0;
			}
			if (cell->IsInteriorCell()) {
				return cell->GetFormID();
			}
			auto* world = a_player->GetWorldspace();
			return world ? world->GetFormID() : cell->GetFormID();
		}

		std::string NameOf(RE::TESForm* a_form)
		{
			const char* name = a_form ? a_form->GetName() : nullptr;
			return name && *name ? std::string(name) : std::format("{:08X}", a_form ? a_form->GetFormID() : 0u);
		}

		// SKSE task (main thread, after the frame's player update, so Minecraft's per-frame placement of
		// the player can't undo it). Papyrus ObjectReference.MoveTo's own function, with no target
		// reference: for the player, MoveTo_Impl (AE 56626, disassembled in 1.7.104) only fills a
		// PLAYER_TARGET_LOC (worldspace if one is given, else the cell if it is an interior; the
		// position; the rotation's Z as the heading) and queues it on the player (AE 40442 copies it to
		// PlayerCharacter+0x650). The game loads the place behind a loading screen, like a door.
		void MovePlayer()
		{
			if (!trip.active) {
				return;  // a game was loaded in between
			}
			auto*              player = RE::PlayerCharacter::GetSingleton();
			RE::TESObjectCELL* cell = trip.interior ? RE::TESForm::LookupByID<RE::TESObjectCELL>(trip.world) : nullptr;
			RE::TESWorldSpace* world = trip.interior ? nullptr : RE::TESForm::LookupByID<RE::TESWorldSpace>(trip.world);
			if (!player || (!cell && !world)) {
				logger::warn("SkyCraft-portal: {:08X} vanished before the move; staying put", trip.world);
				trip = {};
				return;
			}
			using MoveToFn = void(RE::TESObjectREFR*, const RE::ObjectRefHandle&, RE::TESObjectCELL*, RE::TESWorldSpace*, const RE::NiPoint3&, const RE::NiPoint3&);
			static REL::Relocation<MoveToFn> moveTo{ RELOCATION_ID(56227, 56626) };
			const RE::ObjectRefHandle        nobody{};
			const RE::NiPoint3               rotation{ 0.0f, 0.0f, trip.heading };
			moveTo(player, nobody, cell, world, trip.to, rotation);
			logger::info("SkyCraft-portal: moving the player to {} {:08X} at {:.0f} {:.0f} {:.0f} (units), heading {:.0f} degrees", trip.interior ? "cell" : "worldspace",
				trip.world, trip.to.x, trip.to.y, trip.to.z, trip.heading * 57.2957795f);
		}
	}

	void Request(RE::PlayerCharacter* a_player, const proto::McEvent& a_event)
	{
		const bool       interior = (a_event.flags & proto::kPortalInterior) != 0;
		const RE::FormID destination = a_event.formId;
		if (trip.active) {
			logger::info("SkyCraft-portal: already on the way through a portal; request for {:08X} ignored", destination);
			return;
		}
		if (clock - lastTrip < kMinGapSeconds) {
			logger::info("SkyCraft-portal: a portal trip {:.1f} s ago; request for {:08X} ignored", clock - lastTrip, destination);
			return;
		}
		const double x = a_event.a, y = a_event.b, z = a_event.c;
		if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) || !std::isfinite(a_event.d) || std::abs(x) > kMaxAcrossBlocks || std::abs(z) > kMaxAcrossBlocks ||
			std::abs(y) > kMaxUpBlocks) {
			logger::warn("SkyCraft-portal: request for {:08X} has no sensible spot ({} {} {}); ignored", destination, x, y, z);
			return;
		}
		const RE::FormID here = WorldOf(a_player);
		if (here == 0 || here != a_event.weapon) {
			logger::info("SkyCraft-portal: request was sent from {:08X} but the player is in {:08X} now; stale, ignored", a_event.weapon, here);
			return;
		}
		auto* ui = RE::UI::GetSingleton();
		if (a_player->IsDead() || a_player->IsOnMount() || a_player->IsInKillMove() || a_player->AsActorState()->GetSitSleepState() != RE::SIT_SLEEP_STATE::kNormal ||
			Breach::Moving() || (ui && ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME))) {
			logger::info("SkyCraft-portal: the player can't travel now (dead, riding, in a kill move, in furniture, loading or breaking into a dungeon); ignored");
			return;
		}
		RE::TESForm* form = nullptr;
		if (interior) {
			auto* cell = RE::TESForm::LookupByID<RE::TESObjectCELL>(destination);
			if (!cell || !cell->IsInteriorCell()) {
				logger::warn("SkyCraft-portal: {:08X} isn't an interior cell; ignored", destination);
				return;
			}
			form = cell;
		} else {
			auto* world = RE::TESForm::LookupByID<RE::TESWorldSpace>(destination);
			if (!world) {
				logger::warn("SkyCraft-portal: {:08X} isn't a worldspace; ignored", destination);
				return;
			}
			form = world;
		}
		float heading = std::fmod(McYawToHeading(a_event.d), kTwoPi);
		if (heading < 0.0f) {
			heading += kTwoPi;
		}
		auto to = McToSky(x, y, z);
		to.z += kLiftUnits;
		trip = { true, destination, interior, to, heading, 0.0f };
		lastTrip = clock;
		logger::info("SkyCraft-portal: through the portal to {} ({} {:08X}) at MC {:.2f} {:.2f} {:.2f}, from {:08X}", NameOf(form), interior ? "cell" : "worldspace",
			destination, x, y, z, here);
		if (const auto* tasks = SKSE::GetTaskInterface()) {
			tasks->AddTask([]() { MovePlayer(); });
		} else {
			MovePlayer();
		}
	}

	void Update(RE::PlayerCharacter* a_player, float a_delta)
	{
		const float dt = std::max(a_delta, 0.0f);
		clock += dt;
		if (!trip.active) {
			return;
		}
		auto*      cell = a_player->GetParentCell();
		auto*      ui = RE::UI::GetSingleton();
		const bool loading = !cell || !a_player->Is3DLoaded() || (ui && ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME));
		if (loading) {
			return;  // a loading screen doesn't count toward the timeout
		}
		const auto  here = a_player->GetPosition();
		const float across = std::hypot(here.x - trip.to.x, here.y - trip.to.y);
		const auto  world = WorldOf(a_player);
		if (world == trip.world && across < kArrivedUnits) {
			logger::info("SkyCraft-portal: arrived in {:08X}, {:.1f} blocks from the spot; Minecraft follows Skyrim's world change", world,
				across / static_cast<float>(proto::kUnitsPerBlock));
			trip = {};
			return;
		}
		if ((trip.age += dt) > kArriveTimeoutSeconds) {
			logger::warn("SkyCraft-portal: the player never arrived in {:08X} (now in {:08X}, {:.0f} blocks from the spot); giving up on the trip", trip.world, world,
				across / static_cast<float>(proto::kUnitsPerBlock));
			trip = {};
		}
	}

	bool Moving()
	{
		return trip.active;
	}

	void Reset()
	{
		trip = {};
		lastTrip = -1.0e9f;
	}
}
