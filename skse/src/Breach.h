#pragma once

#include "Link.h"

// Breach doors. Every dungeon near the player gets a hidden antechamber underground below its
// entrance, built by Minecraft from proto::BreachTable: the exterior load doors around the player
// whose other side is a dungeon interior. When the player opens an antechamber's sealed door,
// Minecraft sends proto::kEvEnterDungeon with that load door; Skyrim puts the dungeon on the map as
// discovered and moves the player inside, exactly where the door leads, locked or not.
namespace skycraft::Breach
{
	// Main thread, every frame (Minecraft driving the player or not): about once a second, publishes
	// the dungeon entrances in the loaded cells around the player (none in interiors), and notices
	// when a breach move has arrived.
	void Update(RE::PlayerCharacter* a_player, float a_delta);
	// Main thread: Minecraft asks to break into the dungeon behind this exterior load door. Only a
	// door published in the last few seconds is taken; the move itself runs as an SKSE task.
	void Enter(RE::PlayerCharacter* a_player, RE::FormID a_doorRef);
	// Skyrim is moving the player into a dungeon for a breach: Minecraft must not drive them until
	// they've arrived (or the move gave up). Main thread.
	bool Moving();
	// A game was loaded or started: forgets the doors seen, the doors published and any move in
	// flight, and empties the table. Main thread.
	void Reset();
}
