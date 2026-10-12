#pragma once

#include <cstdint>

// Triangle strips as Skyrim's hkNiTriStripsShape (the Havok shape behind old NIF strip collision,
// bhkNiTriStripsShape) numbers them: a shape key is set << 20 | triangle, and a set's triangles
// run strip after strip, a strip of n points holding the n - 2 triangles (p[k], p[k+1], p[k+2]).
// Read off the game's own lookup (1.7.104: hkNiTriStripsShape::getChildShape, 0x1410844A0). Pure,
// no game types (skse/tests/StripsTest.cpp).
namespace skycraft::Strips
{
	inline constexpr std::uint32_t kSetShift = 20;
	inline constexpr std::uint32_t kTriangleMask = (1u << kSetShift) - 1;

	inline std::uint32_t SetOf(std::uint32_t a_key) { return a_key >> kSetShift; }
	inline std::uint32_t TriangleOf(std::uint32_t a_key) { return a_key & kTriangleMask; }

	// Where triangle a_index of a set sits in its own strip (0: the strip's first), or -1 past the
	// last strip. Strips under 3 points wrap round like the game's (its count is unsigned).
	inline std::int64_t LocalIndex(const std::uint16_t* a_lengths, std::uint32_t a_strips, std::uint32_t a_index)
	{
		for (std::uint32_t i = 0; i < a_strips; ++i) {
			const std::uint32_t inStrip = std::uint32_t(a_lengths[i]) - 2u;
			if (a_index < inStrip) {
				return a_index;
			}
			a_index -= inStrip;
		}
		return -1;
	}

	// Whether the game hands this triangle out turned round. A strip's points alternate sides,
	// so (p[k], p[k+1], p[k+2]) with k odd runs against the strip's first triangle; strip readers
	// swap two corners of those back, and the game's shape keeps them as they come. Swapping two
	// corners of exactly these makes the set wound the way it was authored.
	inline bool Turned(const std::uint16_t* a_lengths, std::uint32_t a_strips, std::uint32_t a_index)
	{
		const std::int64_t local = LocalIndex(a_lengths, a_strips, a_index);
		return local >= 0 && (local & 1) != 0;
	}
}
