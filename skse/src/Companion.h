#pragma once

#include "Link.h"

// Tamed companions: Minecraft's taming rules on Skyrim's animals. Minecraft rolls the taming (a bone for a
// dog or wolf, horse food for a horse) and sends proto::kEvCompanion; this makes the animal the player's
// with Skyrim's own means: a dog or wolf becomes Skyrim's animal follower the way a stray dog joins (the
// vanilla DialogueFollowerScript.SetAnimal on the DialogueFollower quest: relationship, teammate, the
// animal alias whose packages follow the player, through load doors too), and waits (Minecraft's sit)
// on the WaitingForPlayer actor value those packages read; a horse becomes the player's own, as a bought
// one is. Skyrim keeps one animal follower, so the newest one ordered to follow takes the slot and the
// one before it stays behind, still the player's. What Minecraft tamed is kept in the co-save (record
// 'CMPN'), so it stays tamed when that game is loaded again. Skyrim's own animal followers, teammates
// that are dogs, wolves or horses, and horses the player owns count as companions too. Log: SkyCraft-companion.
namespace skycraft::Companion
{
	// The actor table's companion bits for this actor: proto::kActorCompanion, kActorCompanionWaiting and its
	// AnimalKind in kActorKindMask. Main thread.
	std::uint32_t Flags(RE::Actor* a_actor, RE::PlayerCharacter* a_player);
	// Main thread: Minecraft's proto::kEvCompanion (tame, follow, wait, heal).
	void OnEvent(RE::PlayerCharacter* a_player, const proto::McEvent& a_event);
	// Main thread, every frame: which animal holds Skyrim's animal follower slot; forgets tamed ones that died.
	void Update(RE::PlayerCharacter* a_player, float a_delta);
	// A game was loaded or started: the loaded game's companions (from its co-save; none for a new game) from
	// here on. Main thread.
	void Reset();

	// Co-save (CoSave.cpp): record type, writing it, reading one (its body), forgetting what a load read.
	inline constexpr std::uint32_t kSaveRecord = 0x434D504E;  // 'CMPN'
	void Save(SKSE::SerializationInterface* a_intfc);
	void Load(SKSE::SerializationInterface* a_intfc, std::uint32_t a_version);
	void Revert();
}
