#pragma once

#include "Link.h"

// Monsters in the deep. Minecraft picks a dark spot in a tunnel the player dug (deep enough, out of
// sight) and sends proto::kEvSpawnCreature; this creates a real Skyrim creature right there (a
// skeever, skeleton or draugr, by the player's level), so Skyrim's own combat, kill XP and drops
// apply. It keeps track of what it placed: at most the cap Minecraft sends are alive at once, and
// they are taken away once the player is far from them or has left for another worldspace or
// interior: disabled first, and deleted only once Skyrim has finished disabling them.
namespace skycraft::Spawn
{
	// What was placed is kept in SKSE's co-save (CoSave.cpp), so a loaded game still knows which of its
	// actors came out of the dark: the record type, writing it, reading one (its body), forgetting what
	// a load read. Main thread.
	inline constexpr std::uint32_t kSaveRecord = 0x44454550;  // 'DEEP'
	void Save(SKSE::SerializationInterface* a_intfc);
	void Load(SKSE::SerializationInterface* a_intfc, std::uint32_t a_version);
	void Revert();
	// Main thread: Minecraft asks for a creature (only while Minecraft drives the player).
	void OnEvent(RE::PlayerCharacter* a_player, const proto::McEvent& a_event);
	// Main thread, every frame: takes away creatures left behind.
	void Update(RE::PlayerCharacter* a_player, float a_delta);
	// One of the creatures placed here (for the actor table's kActorSpawned). Main thread.
	bool IsOurs(const RE::Actor* a_actor);
	// A game was loaded or started: whatever was tracked belongs to the old one; the loaded game's
	// own creatures (from its co-save) are tracked from here on. Main thread.
	void Reset();
}
