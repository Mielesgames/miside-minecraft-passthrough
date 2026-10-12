#pragma once

#include "Link.h"

// Portal travel. Minecraft's portal network (fabric portal/SkyPortals) binds lit Nether portals in
// pairs; when the player has stood in a bound one for Minecraft's portal wait, Minecraft sends
// proto::kEvPortalTravel with the partner's Skyrim world (a worldspace, or an interior cell) and the
// spot in front of it. Skyrim moves the player there the way a load door or Papyrus MoveTo does
// (TESObjectREFR::MoveTo_Impl queues the player's target location; the game loads it behind a loading
// screen), with no reference needed at the other end. Minecraft then follows Skyrim's world change
// like any other (Game.cpp's teleport resync). Log tag SkyCraft-portal.
namespace skycraft::Travel
{
	// Main thread: Minecraft asks to move the player through a portal. Checked (destination form,
	// coordinates, the world the request came from, rate, the player's state) before the move is queued
	// as an SKSE task.
	void Request(RE::PlayerCharacter* a_player, const proto::McEvent& a_event);
	// Main thread, every frame: notices when a portal trip has arrived (or never will).
	void Update(RE::PlayerCharacter* a_player, float a_delta);
	// A portal trip is on its way (requested, not yet arrived). Main thread. Nothing reads it yet: a
	// Skyrim takeover hook in Game.cpp can, should a frame between the request and the loading screen
	// ever let Minecraft pull the player back.
	bool Moving();
	// A game was loaded or started: forgets any trip in flight. Main thread.
	void Reset();
}
