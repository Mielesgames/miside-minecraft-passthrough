// Tests for AnimalKinds.h (pure, no game): build with -DSKYCRAFT_TESTS=ON and run ctest, or directly:
//   g++ -std=c++20 -I skse/src -I protocol skse/tests/AnimalKindsTest.cpp -o animal_kinds_test && ./animal_kinds_test
#include "AnimalKinds.h"

#include <cstdio>

namespace
{
	int failures = 0;

	void Check(bool a_ok, const char* a_what, int a_line)
	{
		if (!a_ok) {
			std::printf("FAILED line %d: %s\n", a_line, a_what);
			++failures;
		}
	}
#define CHECK(x) Check((x), #x, __LINE__)

	using namespace skycraft;
	using AnimalKinds::FromRace;

	// The races of Skyrim.esm, Dawnguard and Dragonborn the companions feature meets.
	void VanillaRaces()
	{
		CHECK(FromRace("DogRace") == proto::kAnimalDog);
		CHECK(FromRace("DogCompanionRace") == proto::kAnimalDog);
		CHECK(FromRace("DLC1HuskyBareRace") == proto::kAnimalDog);
		CHECK(FromRace("DLC1HuskyArmoredCompanionRace") == proto::kAnimalDog);
		CHECK(FromRace("DLC1DeathHoundRace") == proto::kAnimalDog);
		CHECK(FromRace("DLC1DeathHoundCompanionRace") == proto::kAnimalDog);
		CHECK(FromRace("WolfRace") == proto::kAnimalWolf);
		CHECK(FromRace("IceWolfRace") == proto::kAnimalWolf);
		CHECK(FromRace("HorseRace") == proto::kAnimalHorse);
		CHECK(FromRace("DLC1SoulCairnHorseRace") == proto::kAnimalHorse);
	}

	void NotAnimalsToTame()
	{
		// People in beast form.
		CHECK(FromRace("WerewolfBeastRace") == proto::kAnimalNone);
		CHECK(FromRace("DLC2WerebearBeastRace") == proto::kAnimalNone);
		// Animals Minecraft doesn't tame this way.
		CHECK(FromRace("FoxRace") == proto::kAnimalNone);
		CHECK(FromRace("SabreCatRace") == proto::kAnimalNone);
		CHECK(FromRace("TrollRace") == proto::kAnimalNone);
		CHECK(FromRace("HorkerRace") == proto::kAnimalNone);
		CHECK(FromRace("SkeeverRace") == proto::kAnimalNone);
		CHECK(FromRace("DragonRace") == proto::kAnimalNone);
		// People.
		CHECK(FromRace("NordRace") == proto::kAnimalNone);
		CHECK(FromRace("KhajiitRace") == proto::kAnimalNone);
		// Nothing.
		CHECK(FromRace("") == proto::kAnimalNone);
	}

	void IgnoresCase()
	{
		CHECK(FromRace("dograce") == proto::kAnimalDog);
		CHECK(FromRace("WOLFRACE") == proto::kAnimalWolf);
		CHECK(FromRace("myModHORSERace") == proto::kAnimalHorse);
		CHECK(FromRace("WEREWOLFBEASTRACE") == proto::kAnimalNone);
		CHECK(AnimalKinds::EqualsNoCase("DialogueFollower", "dialoguefollower"));
		CHECK(AnimalKinds::EqualsNoCase("Animal", "ANIMAL"));
		CHECK(!AnimalKinds::EqualsNoCase("Animal", "Animals"));
		CHECK(!AnimalKinds::EqualsNoCase("Follower", "Animal!!"));
	}

	void Names()
	{
		CHECK(AnimalKinds::Name(proto::kAnimalDog) == "dog");
		CHECK(AnimalKinds::Name(proto::kAnimalWolf) == "wolf");
		CHECK(AnimalKinds::Name(proto::kAnimalHorse) == "horse");
		CHECK(AnimalKinds::Name(proto::kAnimalNone) == "animal");
	}

	// The kind travels in the actor table's flags beside the other bits, which it mustn't touch.
	void FitsTheActorFlags()
	{
		for (std::uint32_t kind : { proto::kAnimalNone, proto::kAnimalDog, proto::kAnimalWolf, proto::kAnimalHorse }) {
			const std::uint32_t bits = kind << proto::kActorKindShift;
			CHECK((bits & ~proto::kActorKindMask) == 0);
			CHECK((bits & (proto::kActorHostile | proto::kActorDead | proto::kActorEssential | proto::kActorInCombat | proto::kActorSpawned |
							  proto::kActorCompanion | proto::kActorCompanionWaiting)) == 0);
			CHECK(((bits & proto::kActorKindMask) >> proto::kActorKindShift) == kind);
		}
	}

	static_assert(AnimalKinds::FromRace("DogRace") == proto::kAnimalDog, "usable at compile time");
}

int main()
{
	VanillaRaces();
	NotAnimalsToTame();
	IgnoresCase();
	Names();
	FitsTheActorFlags();
	if (failures) {
		std::printf("%d check(s) failed\n", failures);
		return 1;
	}
	std::printf("AnimalKinds: all checks passed\n");
	return 0;
}
