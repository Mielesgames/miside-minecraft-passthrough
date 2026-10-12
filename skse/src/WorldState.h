#pragma once

#include "Link.h"

// What Minecraft needs to know about where the player is and who's around them, and the one
// Minecraft request that changes Skyrim's world clock. Main thread only.
namespace skycraft::WorldState
{
	// What a merchant trades in (proto::VendorBuys), 0 if the actor isn't one. Cached per actor.
	std::uint16_t VendorMask(RE::Actor* a_actor);
	// The town/city/settlement the player is in (else their location's name, else "") and PlaceFlags.
	void Place(RE::PlayerCharacter* a_player, char (&a_town)[32], std::uint32_t& a_flags);
	// Quest items Minecraft asks about (proto::CarriedFlags for SkyState::carried): an Elder Scroll in the
	// player's inventory. Rechecked once a second.
	std::uint32_t Carried(RE::PlayerCharacter* a_player);
	// Pass game hours in Skyrim (the player slept in a Minecraft bed).
	void PassTime(float a_hours);
}
