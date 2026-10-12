package dev.skycraft.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SteveCampTest {
	private static List<SteveCamp.Placement> pieces(SteveCamp.Piece piece) {
		return SteveCamp.layout().stream().filter(p -> p.piece() == piece).toList();
	}

	private static SteveCamp.Placement at(int dx, int dy, int dz) {
		return SteveCamp.layout().stream().filter(p -> p.dx() == dx && p.dy() == dy && p.dz() == dz).findFirst().orElse(null);
	}

	@Test
	void campSitsNearThePromisedSpot() {
		assertEquals(12_550_700, SteveCamp.ORIGIN_X);
		assertEquals(0, SteveCamp.ORIGIN_Z);
		assertTrue(SteveCamp.ORIGIN_X + SteveCamp.maxDx() < FarLands.EDGE, "camp must stop short of the wall");
	}

	@Test
	void everyBlockIsPlacedOnce() {
		Set<String> seen = new HashSet<>();
		for (SteveCamp.Placement p : SteveCamp.layout()) {
			assertTrue(seen.add(p.dx() + "," + p.dy() + "," + p.dz()), "placed twice: " + p);
		}
	}

	@Test
	void campFitsInItsAnchorChunkAndNeighbours() {
		int ax = SteveCamp.anchorChunkX();
		int az = SteveCamp.anchorChunkZ();
		for (SteveCamp.Placement p : SteveCamp.layout()) {
			int cx = Math.floorDiv(SteveCamp.ORIGIN_X + p.dx(), 16);
			int cz = Math.floorDiv(SteveCamp.ORIGIN_Z + p.dz(), 16);
			assertTrue(Math.abs(cx - ax) <= 1 && Math.abs(cz - az) <= 1, "outside decoration reach: " + p);
		}
	}

	@Test
	void hutHasWallsRoofAndAnOpenDoorFacingTheWall() {
		assertEquals(SteveCamp.Piece.DIRT, at(-2, 1, 0).piece());
		assertEquals(SteveCamp.Piece.DIRT, at(0, 3, 0).piece());
		assertEquals(SteveCamp.Piece.AIR, at(2, 0, 0).piece());
		assertEquals(SteveCamp.Piece.AIR, at(2, 1, 0).piece());
		assertEquals(SteveCamp.Piece.DIRT, at(2, 2, 0).piece());
		// A clear walk from the door to the bed side of the room.
		assertEquals(SteveCamp.Piece.AIR, at(0, 0, 0).piece());
		assertEquals(SteveCamp.Piece.AIR, at(-1, 0, 0).piece());
	}

	@Test
	void bedIsWhole() {
		SteveCamp.Placement foot = pieces(SteveCamp.Piece.BED_FOOT).get(0);
		SteveCamp.Placement head = pieces(SteveCamp.Piece.BED_HEAD).get(0);
		assertEquals(foot.facing(), head.facing());
		int dx = switch (foot.facing()) {
			case EAST -> 1;
			case WEST -> -1;
			default -> 0;
		};
		int dz = switch (foot.facing()) {
			case SOUTH -> 1;
			case NORTH -> -1;
			default -> 0;
		};
		assertEquals(foot.dx() + dx, head.dx());
		assertEquals(foot.dz() + dz, head.dz());
		assertEquals(foot.dy(), head.dy());
	}

	@Test
	void furnitureIsInside() {
		for (SteveCamp.Piece piece : new SteveCamp.Piece[] { SteveCamp.Piece.CHEST, SteveCamp.Piece.LECTERN, SteveCamp.Piece.BED_HEAD,
			SteveCamp.Piece.BED_FOOT, SteveCamp.Piece.CRAFTING_TABLE, SteveCamp.Piece.FURNACE }) {
			SteveCamp.Placement p = pieces(piece).get(0);
			assertTrue(Math.abs(p.dx()) <= 1 && Math.abs(p.dz()) <= 1 && p.dy() == 0, piece + " outside the hut");
		}
	}

	@Test
	void signsCountTheDaysWalked() {
		List<SteveCamp.Placement> signs = pieces(SteveCamp.Piece.SIGN);
		assertEquals(SteveCamp.SIGN_COUNT, signs.size());
		for (int i = 0; i < signs.size(); i++) {
			SteveCamp.Placement s = signs.get(i);
			assertEquals(i, s.sign());
			assertTrue(SteveCamp.signLines(i).contains("Day " + (SteveCamp.FIRST_DAY + i)));
			assertEquals(4, SteveCamp.signLines(i).size());
			if (i > 0) {
				assertTrue(s.dx() > signs.get(i - 1).dx(), "signs march east");
			}
		}
		// Walking 12.55 million blocks at 4.317 blocks a second, 20 minute days: the last sign's count.
		long days = Math.round(SteveCamp.ORIGIN_X / (4.317 * 1200));
		assertEquals(days, SteveCamp.FIRST_DAY + SteveCamp.SIGN_COUNT - 1);
	}

	@Test
	void journalIsAPlaceholder() {
		assertEquals("Journal", SteveCamp.JOURNAL_TITLE);
		assertFalse(SteveCamp.JOURNAL_PAGES.isEmpty());
		for (String page : SteveCamp.JOURNAL_PAGES) {
			assertTrue(page.contains("PLACEHOLDER"), "unmarked page: " + page);
			assertTrue(page.length() <= 1024);
		}
	}

	@Test
	void chestHoldsOldItems() {
		assertTrue(SteveCamp.CHEST.size() <= 27);
		for (SteveCamp.Stack s : SteveCamp.CHEST) {
			assertTrue(s.item().startsWith("minecraft:"));
			assertTrue(s.count() >= 1 && s.count() <= 64);
		}
	}

	@Test
	void arrivalStandsOnTheTrailFacingTheWall() {
		assertEquals(-90.0f, SteveCamp.ARRIVAL_YAW, 0.0);
		assertEquals(SteveCamp.FLOOR_Y + 1.0, SteveCamp.ARRIVAL_Y, 0.0);
		int bx = (int) Math.floor(SteveCamp.ARRIVAL_X) - SteveCamp.ORIGIN_X;
		int bz = (int) Math.floor(SteveCamp.ARRIVAL_Z) - SteveCamp.ORIGIN_Z;
		assertEquals(0, bz);
		assertTrue(bx > 2, "arrival is outside the hut");
		assertEquals(null, at(bx, 0, bz));
		assertEquals(null, at(bx, 1, bz));
		assertTrue(HomeTerrain.onTrail(SteveCamp.ORIGIN_X + bx, SteveCamp.ORIGIN_Z));
	}
}
