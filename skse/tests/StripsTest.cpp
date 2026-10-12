// Tests for Strips.h (pure, no game): build with -DSKYCRAFT_TESTS=ON and run ctest, or directly:
//   g++ -std=c++20 -I skse/src skse/tests/StripsTest.cpp -o strips_test && ./strips_test
#include "Strips.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <map>
#include <utility>
#include <vector>

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

	using Tri = std::array<int, 3>;

	// A set of strips the way the game hands its triangles out: (p[k], p[k+1], p[k+2]) as they
	// come, triangle after triangle; with a_turnBack, those Strips::Turned names get two corners
	// swapped back (what Collision.cpp does).
	std::vector<Tri> Decode(const std::vector<std::vector<std::uint16_t>>& a_strips, bool a_turnBack)
	{
		std::vector<std::uint16_t> lengths;
		for (const auto& s : a_strips) {
			lengths.push_back(std::uint16_t(s.size()));
		}
		std::vector<Tri> out;
		std::uint32_t    index = 0;
		for (const auto& s : a_strips) {
			for (std::size_t k = 0; k + 2 < s.size(); ++k, ++index) {
				Tri t{ s[k], s[k + 1], s[k + 2] };
				if (a_turnBack && Strips::Turned(lengths.data(), std::uint32_t(lengths.size()), index)) {
					std::swap(t[1], t[2]);
				}
				out.push_back(t);
			}
		}
		return out;
	}

	// Directed edges with no partner running the other way, plus edges two triangles run the
	// same way (their windings disagree).
	int Disagreements(const std::vector<Tri>& a_tris)
	{
		std::map<std::pair<int, int>, int> edges;
		for (const auto& t : a_tris) {
			for (int i = 0; i < 3; ++i) {
				++edges[{ t[i], t[(i + 1) % 3] }];
			}
		}
		int bad = 0;
		for (const auto& [e, n] : edges) {
			if (n > 1 || !edges.contains({ e.second, e.first })) {
				++bad;
			}
		}
		return bad;
	}

	// Signed volume (positive: wound to face out).
	double Volume(const std::vector<Tri>& a_tris, const std::vector<std::array<double, 3>>& a_p)
	{
		double v = 0.0;
		for (const auto& t : a_tris) {
			const auto& a = a_p[t[0]];
			const auto& b = a_p[t[1]];
			const auto& c = a_p[t[2]];
			v += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0;
		}
		return v;
	}

	void LocalIndexWalksStripAfterStrip()
	{
		const std::uint16_t one[] = { 5 };  // 3 triangles
		CHECK(Strips::LocalIndex(one, 1, 0) == 0);
		CHECK(Strips::LocalIndex(one, 1, 2) == 2);
		CHECK(Strips::LocalIndex(one, 1, 3) == -1);  // past the last strip

		const std::uint16_t three[] = { 4, 3, 6 };  // 2 + 1 + 4 triangles
		const std::int64_t  expected[] = { 0, 1, 0, 0, 1, 2, 3 };
		for (std::uint32_t i = 0; i < 7; ++i) {
			CHECK(Strips::LocalIndex(three, 3, i) == expected[i]);
		}
		CHECK(Strips::LocalIndex(three, 3, 7) == -1);

		const std::uint16_t withEmpty[] = { 4, 2, 4 };  // a 2 point strip holds nothing
		CHECK(Strips::LocalIndex(withEmpty, 3, 2) == 0);
		CHECK(Strips::LocalIndex(withEmpty, 3, 3) == 1);
	}

	void TurnedIsEverySecondTriangleOfEachStrip()
	{
		const std::uint16_t lengths[] = { 5, 4, 3 };  // 3 + 2 + 1 triangles
		const bool          expected[] = { false, true, false, false, true, false };
		for (std::uint32_t i = 0; i < 6; ++i) {
			CHECK(Strips::Turned(lengths, 3, i) == expected[i]);
		}
		CHECK(!Strips::Turned(lengths, 3, 6));  // past the end: left alone
	}

	void KeysCarryTheSetAndTheTriangle()
	{
		const std::uint32_t key = (3u << 20) | 1234u;
		CHECK(Strips::SetOf(key) == 3);
		CHECK(Strips::TriangleOf(key) == 1234);
	}

	// A unit cube authored as strips (four sides as one strip running round, top and bottom as a
	// strip each), wound to face out once read the usual way. The game's order leaves every
	// second triangle facing in; turned back, the cube is closed and faces out again.
	void CubeFacesOutOnceTurnedBack()
	{
		std::vector<std::array<double, 3>> p;
		for (int i = 0; i < 8; ++i) {
			p.push_back({ double(i & 1), double((i >> 1) & 1), double((i >> 2) & 1) });  // x, y, z
		}
		// Bottom ring z = 0: 0 (0,0) 1 (1,0) 3 (1,1) 2 (0,1); top ring z = 1: the same + 4.
		const std::vector<std::vector<std::uint16_t>> strips = {
			{ 4, 0, 5, 1, 7, 3, 6, 2, 4, 0 },  // the four sides
			{ 4, 5, 6, 7 },                    // top (faces +z)
			{ 0, 2, 1, 3 },                    // bottom (faces -z)
		};
		const auto asTheGameGivesThem = Decode(strips, false);
		const auto turnedBack = Decode(strips, true);
		CHECK(asTheGameGivesThem.size() == 12);
		CHECK(Disagreements(asTheGameGivesThem) > 0);
		CHECK(Disagreements(turnedBack) == 0);
		CHECK(std::abs(Volume(turnedBack, p) - 1.0) < 1e-9);
		// Every triangle turned back faces away from the cube's middle.
		for (const auto& t : turnedBack) {
			const auto& a = p[t[0]];
			const auto& b = p[t[1]];
			const auto& c = p[t[2]];
			const double u[3] = { b[0] - a[0], b[1] - a[1], b[2] - a[2] };
			const double w[3] = { c[0] - a[0], c[1] - a[1], c[2] - a[2] };
			const double n[3] = { u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0] };
			const double out[3] = { (a[0] + b[0] + c[0]) / 3 - 0.5, (a[1] + b[1] + c[1]) / 3 - 0.5, (a[2] + b[2] + c[2]) / 3 - 0.5 };
			CHECK(n[0] * out[0] + n[1] * out[1] + n[2] * out[2] > 0.0);
		}
	}
}

int main()
{
	LocalIndexWalksStripAfterStrip();
	TurnedIsEverySecondTriangleOfEachStrip();
	KeysCarryTheSetAndTheTriangle();
	CubeFacesOutOnceTurnedBack();
	if (failures) {
		std::printf("%d check(s) failed\n", failures);
		return 1;
	}
	std::printf("Strips: all checks passed\n");
	return 0;
}
