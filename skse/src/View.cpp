#include "Game.h"

#include <SimpleIni.h>

// Minecraft view. While Minecraft's player is in a dimension other than the Skyrim mirror (the
// Nether, Tamriel Wilds, Home, ...), McState carries proto::kMcOtherDimension and the overlay frames
// are Minecraft's whole picture (proto::kOverlayOpaque), drawn full screen by Overlay.cpp. Skyrim
// keeps running underneath (its frames pace Minecraft and its heartbeat keeps the link alive), but
// stops taking anything from Minecraft's position, look or camera, and parks its player:
//
// - kept where they stood: held there every frame (no fall, no drift, no fall damage), wherever that
//   was (on Minecraft blocks, deep in a dug tunnel), and picked up again wherever Skyrim itself moves
//   them (a load, a script);
// - out of harm's way: Skyrim's god mode (only if it wasn't on already; lifted again after, or just
//   before Minecraft's death kills the Skyrim player, see BeforeKill), and every second everyone
//   fighting or alarmed by the player stops (ProcessLists::StopCombatAndAlarmOnActor);
// - their controls swallowed as while Minecraft drives (Game.cpp, Input.cpp), keys and mouse going to
//   Minecraft (raw mouse motion as proto::kInLook);
// - Skyrim's HUD hidden, and its world not drawn while the frame covers it ([MinecraftView]
//   bHideSkyrimWorld in SkyCraft.ini): the scene graph's root is culled, so Skyrim's GPU work is
//   mostly its menus.
//
// Parking in place was chosen over moving the player to a quiet interior: no loading screens either
// way, no world change (collision epochs, dug ground, breach and spawn bookkeeping all keyed by it
// would reset), nothing to undo if Skyrim or Minecraft goes away mid trip, and the way back is a plain
// move to wherever Minecraft comes back into the mirror.
//
// On the way back Minecraft's position leads: Skyrim's player is moved to where Minecraft's comes back
// into the mirror and held there until Minecraft drives again (it waits for Skyrim's ground there
// first). Unless Skyrim's player can't follow: dead (Minecraft died over there, so Skyrim's player
// died too and Skyrim reloads), in another Skyrim world than the one they were parked in, or
// Minecraft gone; then Skyrim leads and teleports Minecraft as after a load.
namespace skycraft
{
	namespace
	{
		constexpr float kMovedUnits = 300.0f;    // Skyrim moved the parked player itself (as Game.cpp's threshold)
		constexpr float kHoldSlackUnits = 2.0f;  // put back only when this far off
		constexpr float kFarUnits = 64.0f * float(proto::kUnitsPerBlock);
		constexpr float kHandoverSeconds = 15.0f;  // hold at the way back at most this long for Minecraft to drive
		constexpr float kStatsSeconds = 10.0f;

		std::atomic<bool> active{ false };
		std::atomic<bool> lastFrameOpaque{ false };

		bool          parked = false;
		RE::NiPoint3  parkPos{};
		bool          parkPosValid = false;
		std::uint32_t parkWorld = 0;
		bool          worldChanged = false;
		bool          ourGodMode = false;
		float         calmTimer = 0.0f;
		float         hudTimer = 0.0f;
		bool          hudSaved = false;
		bool          hudWasVisible = true;
		bool          worldCulled = false;
		float         parkedSeconds = 0.0f;

		bool         handing = false;
		RE::NiPoint3 handPos{};
		float        handSeconds = 0.0f;

		// Frames shown (Overlay, render thread), logged every 10 s while parked.
		std::mutex    statsLock;
		int           uploads = 0;
		double        uploadMs = 0.0, uploadMaxMs = 0.0;
		std::uint32_t uploadW = 0, uploadH = 0;
		float         statsTimer = kStatsSeconds;

		bool HideSkyrimWorld()
		{
			static const bool on = [] {
				CSimpleIniA ini;
				ini.SetUnicode();
				ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
				return ini.GetBoolValue("MinecraftView", "bHideSkyrimWorld", true);
			}();
			return on;
		}

		constexpr const char* kHudRoot = "_root.HUDMovieBaseInstance._visible";

		void ShowHud(bool a_show)
		{
			if (a_show && !hudSaved) {
				return;  // never hidden
			}
			auto* ui = RE::UI::GetSingleton();
			if (!ui) {
				return;
			}
			auto hud = ui->GetMenu(RE::HUDMenu::MENU_NAME);
			if (!hud || !hud->uiMovie) {
				return;
			}
			if (!a_show && !hudSaved) {
				RE::GFxValue was;
				hudWasVisible = !hud->uiMovie->GetVariable(&was, kHudRoot) || !was.IsBool() || was.GetBool();
				hudSaved = true;
			}
			hud->uiMovie->SetVariable(kHudRoot, RE::GFxValue(a_show ? hudWasVisible : false));
			if (a_show) {
				hudSaved = false;
			}
		}

		void CullWorld(bool a_cull)
		{
			if (a_cull == worldCulled) {
				return;
			}
			auto* root = RE::Main::WorldRootNode();
			if (!root) {
				return;
			}
			root->SetAppCulled(a_cull);
			worldCulled = a_cull;
			logger::info("Minecraft view: Skyrim's world {}", a_cull ? "not drawn (Minecraft's frame covers it)" : "drawn again");
		}

		// Everyone fighting the player, or alarmed by them, stops.
		void Calm(RE::PlayerCharacter* a_player)
		{
			if (auto* lists = RE::ProcessLists::GetSingleton()) {
				lists->StopCombatAndAlarmOnActor(a_player, false);
			}
		}

		// Keeps the player at a_pos with no speed and no fall building up (as Game.cpp's puppet does).
		void Hold(RE::PlayerCharacter* a_player, const RE::NiPoint3& a_pos)
		{
			if (a_player->GetPosition().GetDistance(a_pos) > kHoldSlackUnits) {
				a_player->SetPosition(a_pos, true);
			}
			if (auto* controller = a_player->GetCharController()) {
				controller->SetLinearVelocityImpl(RE::hkVector4(0.0f, 0.0f, 0.0f, 0.0f));
				controller->fallStartHeight = a_pos.z;
				controller->fallTime = 0.0f;
			}
		}

		void LiftGodMode(RE::PlayerCharacter* a_player, const char* a_why)
		{
			if (!ourGodMode) {
				return;
			}
			ourGodMode = false;
			if (RE::PlayerCharacter::IsGodMode()) {
				a_player->SetGodMode(false);
				logger::info("Minecraft view: Skyrim's god mode off ({})", a_why);
			}
		}

		void Park(RE::PlayerCharacter* a_player, std::uint32_t a_worldId, const proto::McState& a_mc)
		{
			parked = true;
			parkWorld = a_worldId;
			parkPosValid = false;
			worldChanged = false;
			handing = false;
			calmTimer = 0.0f;
			hudTimer = 0.0f;
			parkedSeconds = 0.0f;
			statsTimer = kStatsSeconds;
			if (!a_player->IsDead() && !RE::PlayerCharacter::IsGodMode()) {
				a_player->SetGodMode(true);
				ourGodMode = true;
			}
			Calm(a_player);
			const auto at = a_player->GetPosition();
			logger::info("Minecraft view: Minecraft's player is in another dimension (at {:.1f} {:.1f} {:.1f} there); Skyrim's player parked at {:.0f} {:.0f} {:.0f} in {:08X}{}",
				a_mc.x, a_mc.y, a_mc.z, at.x, at.y, at.z, a_worldId, ourGodMode ? ", god mode on" : "");
		}

		View::Result Unpark(RE::PlayerCharacter* a_player, const proto::McState* a_mc, bool a_loading, std::uint32_t a_worldId)
		{
			View::Result r{};
			parked = false;
			LiftGodMode(a_player, "back in the mirror");
			ShowHud(true);
			CullWorld(false);
			const bool mcThere = a_mc && (a_mc->flags & proto::kMcInWorld);
			const char* skyrimLeads = !mcThere                   ? "Minecraft isn't in a world" :
			                          a_player->IsDead()         ? "Skyrim's player is dead" :
			                          a_loading                  ? "Skyrim is loading" :
			                          worldChanged || a_worldId != parkWorld ? "Skyrim's player is in another world now" :
			                                                       nullptr;
			if (skyrimLeads) {
				r.change = View::Change::kBackSkyrimLeads;
				logger::info("Minecraft view: back after {:.1f} s; {}, so Skyrim puts Minecraft's player where its own is", parkedSeconds, skyrimLeads);
				return r;
			}
			const auto to = McToSky(a_mc->x, a_mc->y, a_mc->z);
			const float moved = parkPosValid ? parkPos.GetDistance(to) : a_player->GetPosition().GetDistance(to);
			a_player->SetPosition(to, true);
			a_player->data.angle.z = McYawToHeading(a_mc->yaw);
			Hold(a_player, to);
			handing = true;
			handPos = to;
			handSeconds = 0.0f;
			r.change = View::Change::kBackMinecraftLeads;
			r.handover = to;
			r.farAway = moved > kFarUnits;
			logger::info("Minecraft view: back after {:.1f} s; Skyrim's player follows Minecraft's to {:.0f} {:.0f} {:.0f} ({:.0f} units from where they waited)",
				parkedSeconds, to.x, to.y, to.z, moved);
			return r;
		}

		void LogStats(float a_delta)
		{
			statsTimer -= a_delta;
			if (statsTimer > 0.0f) {
				return;
			}
			std::lock_guard lock(statsLock);
			if (uploads > 0) {
				logger::info("Minecraft view: {} Minecraft frames shown in {:.0f} s ({:.1f} a second), {}x{}, upload {:.2f} ms on average, {:.2f} ms at most",
					uploads, kStatsSeconds - statsTimer, uploads / std::max(0.001f, kStatsSeconds - statsTimer), uploadW, uploadH, uploadMs / uploads, uploadMaxMs);
			}
			uploads = 0;
			uploadMs = uploadMaxMs = 0.0;
			statsTimer = kStatsSeconds;
		}
	}

	namespace View
	{
		Result Update(RE::PlayerCharacter* a_player, const proto::McState* a_mc, bool a_mcAlive, bool a_loading, bool a_menu, std::uint32_t a_worldId, bool a_puppeting,
			float a_delta)
		{
			// A torn read of Minecraft's state (it was writing) leaves things as they were for a frame.
			const bool away = a_mc ? (a_mc->flags & proto::kMcInWorld) && (a_mc->flags & proto::kMcOtherDimension) : parked && a_mcAlive;
			Result     r{};
			if (away && !parked) {
				Park(a_player, a_worldId, *a_mc);
				r.change = Change::kParked;
			} else if (!away && parked) {
				r = Unpark(a_player, a_mc, a_loading, a_worldId);
			}

			if (parked) {
				parkedSeconds += a_delta;
				// Minecraft turns its own player now: Skyrim's look follows it, so the way back starts
				// from where Minecraft looks (SkyState yaw/pitch).
				if (a_mc) {
					auto& st = State();
					st.yaw = a_mc->yaw;
					st.pitch = a_mc->pitch;
				}
				if (a_worldId != parkWorld) {
					worldChanged = true;
				}
				if (a_loading || !a_player->Is3DLoaded()) {
					parkPosValid = false;  // picked up again where Skyrim puts them
				} else if (!a_player->IsDead()) {
					const auto here = a_player->GetPosition();
					if (!parkPosValid || here.GetDistance(parkPos) > kMovedUnits) {
						if (parkPosValid) {
							logger::info("Minecraft view: Skyrim moved its parked player ({:.0f} units); they wait there now", here.GetDistance(parkPos));
						}
						parkPos = here;
						parkPosValid = true;
					}
					Hold(a_player, parkPos);
					calmTimer -= a_delta;
					if (calmTimer <= 0.0f) {
						calmTimer = 1.0f;
						Calm(a_player);
					}
				}
				hudTimer -= a_delta;
				if (hudTimer <= 0.0f) {
					hudTimer = 0.5f;
					ShowHud(false);  // again and again: Skyrim's HUD shows itself after menus and loads
				}
				CullWorld(HideSkyrimWorld() && lastFrameOpaque.load() && !a_menu && !a_loading);
				LogStats(a_delta);
			} else if (handing) {
				handSeconds += a_delta;
				if (a_puppeting || a_loading || a_player->IsDead() || handSeconds > kHandoverSeconds) {
					handing = false;
				} else {
					Hold(a_player, handPos);  // until Minecraft drives (it waits for Skyrim's ground there first)
				}
			}
			active = parked;
			r.parked = parked;
			return r;
		}

		bool Active()
		{
			return active.load();
		}

		void BeforeKill(RE::PlayerCharacter* a_player)
		{
			LiftGodMode(a_player, "Minecraft's player died: so does Skyrim's");
		}

		void NoteUpload(bool a_opaque, double a_ms, std::uint32_t a_w, std::uint32_t a_h)
		{
			lastFrameOpaque = a_opaque;
			if (!a_opaque) {
				return;
			}
			std::lock_guard lock(statsLock);
			++uploads;
			uploadMs += a_ms;
			uploadMaxMs = std::max(uploadMaxMs, a_ms);
			uploadW = a_w;
			uploadH = a_h;
		}
	}
}
