#pragma once

// Which Skyrim animals Minecraft's taming rules apply to, by race (Companions). Pure, no game: the race's
// editor ID is all it reads (Skyrim keeps races' editor IDs at run time), so it's unit tested on its own
// (tests/AnimalKindsTest.cpp). Skyrim decides here, once, for both halves: the actor table carries the
// result (proto::kActorKindMask) and every taming request is checked against it.

#include "skycraft_protocol.h"

#include <cstddef>
#include <cstdint>
#include <string_view>

namespace skycraft::AnimalKinds
{
	// ASCII, ignoring case.
	constexpr char Lower(char a_c) { return a_c >= 'A' && a_c <= 'Z' ? static_cast<char>(a_c - 'A' + 'a') : a_c; }

	constexpr bool EqualsNoCase(std::string_view a_a, std::string_view a_b)
	{
		if (a_a.size() != a_b.size()) {
			return false;
		}
		for (std::size_t i = 0; i < a_a.size(); ++i) {
			if (Lower(a_a[i]) != Lower(a_b[i])) {
				return false;
			}
		}
		return true;
	}

	// a_word (lower case) anywhere in a_text, ignoring case.
	constexpr bool Contains(std::string_view a_text, std::string_view a_word)
	{
		if (a_word.empty() || a_word.size() > a_text.size()) {
			return a_word.empty();
		}
		for (std::size_t at = 0; at + a_word.size() <= a_text.size(); ++at) {
			std::size_t i = 0;
			while (i < a_word.size() && Lower(a_text[at + i]) == a_word[i]) {
				++i;
			}
			if (i == a_word.size()) {
				return true;
			}
		}
		return false;
	}

	// The kind of animal a race is, from its editor ID. Skyrim.esm and the DLCs name their races plainly
	// (DogRace, DogCompanionRace, WolfRace, HorseRace, DLC1HuskyBareRace, DLC1DeathHoundCompanionRace, ...)
	// and mods mostly follow suit, so a word is enough:
	//  - werewolves and werebears are people in beast form, never animals;
	//  - "horse" is a horse (Shadowmere and Frost are horses too);
	//  - "dog", "husky" and "hound" are dogs (Meeko, Vigilance, stray dogs, Dawnguard's huskies and death hounds);
	//  - "wolf" is a wolf (ice wolves as well).
	// Anything else (foxes, sabre cats, trolls, summoned familiars by their own race names, ...) is no kind.
	constexpr std::uint32_t FromRace(std::string_view a_editorId)
	{
		if (Contains(a_editorId, "werewolf") || Contains(a_editorId, "werebear")) {
			return proto::kAnimalNone;
		}
		if (Contains(a_editorId, "horse")) {
			return proto::kAnimalHorse;
		}
		if (Contains(a_editorId, "dog") || Contains(a_editorId, "husky") || Contains(a_editorId, "hound")) {
			return proto::kAnimalDog;
		}
		if (Contains(a_editorId, "wolf")) {
			return proto::kAnimalWolf;
		}
		return proto::kAnimalNone;
	}

	constexpr std::string_view Name(std::uint32_t a_kind)
	{
		switch (a_kind) {
		case proto::kAnimalDog:
			return "dog";
		case proto::kAnimalWolf:
			return "wolf";
		case proto::kAnimalHorse:
			return "horse";
		default:
			return "animal";
		}
	}
}
