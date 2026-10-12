package dev.skycraft.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BreachPlannerTest {
	@Test
	void yawTurnsIntoMinecraftFacings() {
		assertEquals(0, BreachPlanner.facingFromYaw(0.0F)); // south
		assertEquals(1, BreachPlanner.facingFromYaw(90.0F)); // west
		assertEquals(2, BreachPlanner.facingFromYaw(180.0F)); // north
		assertEquals(2, BreachPlanner.facingFromYaw(-180.0F));
		assertEquals(3, BreachPlanner.facingFromYaw(-90.0F)); // east
		assertEquals(3, BreachPlanner.facingFromYaw(270.0F));
		assertEquals(0, BreachPlanner.facingFromYaw(44.0F));
		assertEquals(1, BreachPlanner.facingFromYaw(46.0F));
		assertEquals(0, BreachPlanner.facingFromYaw(Float.NaN));
	}

	@Test
	void theSealedDoorIsRightUnderTheEntranceAndTheRoomRunsOutAlongItsFacing() {
		for (float yaw : new float[] { 0.0F, 90.0F, 180.0F, 270.0F, 33.0F, -100.0F }) {
			BreachRoom r = BreachPlanner.place(0x3C, 0x1234, 100, -40, yaw, Proto.THEME_NORDIC, 10, 50);
			assertEquals(100, r.x(BreachRoom.DOOR_U, BreachRoom.LENGTH), "yaw " + yaw);
			assertEquals(-40, r.z(BreachRoom.DOOR_U, BreachRoom.LENGTH), "yaw " + yaw);
			assertEquals(BreachRoom.DOOR, r.kind(100, 50, -40));
			assertEquals(BreachRoom.DOOR, r.kind(100, 51, -40));
			// The middle of the room is out along the door's facing.
			int doorFacing = BreachPlanner.facingFromYaw(yaw);
			int midX = r.x(BreachRoom.WIDTH / 2, BreachRoom.LENGTH / 2), midZ = r.z(BreachRoom.WIDTH / 2, BreachRoom.LENGTH / 2);
			int along = (midX - 100) * BreachRoom.stepX(doorFacing) + (midZ + 40) * BreachRoom.stepZ(doorFacing);
			assertTrue(along >= 3, "yaw " + yaw + ": " + along);
		}
	}

	@Test
	void sameDoorSameRoom() {
		BreachRoom a = BreachPlanner.place(0x3C, 0xABCDEF, 7, 9, 123.0F, Proto.THEME_DWEMER, 30, 0);
		BreachRoom b = BreachPlanner.place(0x3C, 0xABCDEF, 7, 9, 123.0F, Proto.THEME_DWEMER, 30, 0);
		assertEquals(a, b);
		assertEquals(2, a.shell());
		assertEquals(1, BreachPlanner.place(0x3C, 1, 0, 0, 0.0F, Proto.THEME_CAVE, 5, 0).shell());
		assertEquals(Proto.THEME_CAVE, BreachPlanner.place(0x3C, 1, 0, 0, 0.0F, 99, 5, 0).theme());
	}

	@Test
	void deeperForHigherLevels() {
		assertEquals(BreachPlanner.UNKNOWN_DEPTH, BreachPlanner.depthFor(0));
		assertEquals(12, BreachPlanner.depthFor(1));
		assertEquals(12, BreachPlanner.depthFor(5));
		int last = 0;
		for (int level = 1; level <= 100; level++) {
			int d = BreachPlanner.depthFor(level);
			assertTrue(d >= last && d >= BreachPlanner.MIN_DEPTH && d <= BreachPlanner.MAX_DEPTH, level + ": " + d);
			last = d;
		}
		assertTrue(BreachPlanner.depthFor(30) > 20);
		assertEquals(BreachPlanner.MAX_DEPTH, BreachPlanner.depthFor(100));
	}

	@Test
	void floorHeightOnFlatLand() {
		// Level 1, 1 thick: the top of the shell 12 under the land.
		int floor = BreachPlanner.floorY(1, 1, 100.3, 100.3);
		int top = floor + BreachRoom.HEIGHT - 1 + 1;
		assertEquals(88, top);
		// Level 60, 2 thick: 48 down, still clear of bedrock.
		floor = BreachPlanner.floorY(60, 2, 100.0, 100.0);
		top = floor + BreachRoom.HEIGHT - 1 + 2;
		int bottom = floor - 2;
		assertEquals(52, top);
		assertTrue(100.0 - (bottom + 0.5) < BreachPlanner.BEDROCK_DEPTH);
	}

	@Test
	void slopesKeepTheRoomClearOfBedrockOrGiveUp() {
		for (int level : new int[] { 0, 1, 15, 30, 60 }) {
			for (int shell = 1; shell <= 2; shell++) {
				for (double rise = 0.0; rise <= 80.0; rise += 2.5) {
					double low = 100.0, high = 100.0 + rise;
					int floor = BreachPlanner.floorY(level, shell, low, high);
					if (floor == BreachPlanner.NO_ROOM) {
						assertTrue(rise > 30.0, "gave up at rise " + rise);
						continue;
					}
					int top = floor + BreachRoom.HEIGHT - 1 + shell, bottom = floor - shell;
					assertTrue(Math.floor(low) - top >= BreachPlanner.MIN_DEPTH, "too shallow: level " + level + " rise " + rise);
					assertTrue(high - (bottom + 0.5) <= BreachPlanner.BEDROCK_DEPTH - BreachPlanner.BEDROCK_MARGIN, "at bedrock: level " + level + " rise " + rise);
				}
			}
		}
		assertEquals(BreachPlanner.NO_ROOM, BreachPlanner.floorY(1, 1, 100.0, 170.0));
		assertEquals(BreachPlanner.NO_ROOM, BreachPlanner.floorY(1, 1, Double.NaN, 100.0));
	}

	@Test
	void footprintCoversTheOuterBox() {
		BreachRoom r = BreachPlanner.place(0x3C, 5, 10, 10, 90.0F, Proto.THEME_FORT, 25, 40);
		int[][] pts = BreachPlanner.footprint(r);
		assertEquals(9, pts.length);
		Set<Long> seen = new HashSet<>();
		for (int[] p : pts) {
			assertTrue(p[0] >= r.minX() && p[0] <= r.maxX() && p[1] >= r.minZ() && p[1] <= r.maxZ());
			seen.add(((long) p[0] << 32) | (p[1] & 0xFFFFFFFFL));
		}
		assertEquals(9, seen.size());
		// The corners of the outer box are among them.
		assertTrue(seen.contains(((long) r.minX() << 32) | (r.minZ() & 0xFFFFFFFFL)));
		assertTrue(seen.contains(((long) r.maxX() << 32) | (r.maxZ() & 0xFFFFFFFFL)));
	}

	@Test
	void tiersByTheme() {
		assertEquals(BreachPlanner.TIER_IRON, BreachPlanner.tier(Proto.THEME_DWEMER));
		assertEquals(BreachPlanner.TIER_IRON, BreachPlanner.tier(Proto.THEME_LAIR));
		assertEquals(BreachPlanner.TIER_STONE, BreachPlanner.tier(Proto.THEME_NORDIC));
		assertEquals(BreachPlanner.TIER_STONE, BreachPlanner.tier(Proto.THEME_FORT));
		assertEquals(BreachPlanner.TIER_STONE, BreachPlanner.tier(Proto.THEME_FALMER));
		assertEquals(BreachPlanner.TIER_WOOD, BreachPlanner.tier(Proto.THEME_CAVE));
		assertEquals(BreachPlanner.TIER_WOOD, BreachPlanner.tier(Proto.THEME_MINE));
		assertEquals("an iron pickaxe", BreachPlanner.pickaxeName(BreachPlanner.TIER_IRON));
	}

	@Test
	void variantsAreStableAndMixed() {
		assertEquals(BreachPlanner.roll(42, 1, 2, 3), BreachPlanner.roll(42, 1, 2, 3));
		for (int i = 0; i < 1000; i++) {
			int roll = BreachPlanner.roll(i * 7919, i, -i, i * 3);
			assertTrue(roll >= 0 && roll < 100, "roll " + roll);
		}
		BreachRoom r = BreachPlanner.place(0x3C, 0x77, 0, 0, 0.0F, Proto.THEME_NORDIC, 30, 20);
		int[] counts = new int[4];
		for (int x = r.minX(); x <= r.maxX(); x++) {
			for (int y = r.minY(); y <= r.maxY(); y++) {
				for (int z = r.minZ(); z <= r.maxZ(); z++) {
					int kind = r.kind(x, y, z);
					if (kind == BreachRoom.SHELL || kind == BreachRoom.FRAME) {
						int v = r.variant(x, y, z);
						assertEquals(v, r.variant(x, y, z));
						assertEquals(kind == BreachRoom.FRAME, v == BreachPlanner.ACCENT);
						counts[v]++;
					}
				}
			}
		}
		assertTrue(counts[BreachPlanner.MAIN] > counts[BreachPlanner.ALT] && counts[BreachPlanner.ALT] > 0 && counts[BreachPlanner.RARE] > 0, java.util.Arrays.toString(counts));
		assertEquals(8, counts[BreachPlanner.ACCENT]);
		// A mine's ceiling is plank beams.
		assertEquals(BreachPlanner.ALT, BreachPlanner.variant(Proto.THEME_MINE, BreachRoom.SHELL, BreachRoom.HEIGHT, 0));
		assertEquals(BreachPlanner.MAIN, BreachPlanner.variant(Proto.THEME_MINE, BreachRoom.SHELL, 1, 0));
	}

	@Test
	void keysSeparateWorldsAndDoors() {
		assertNotEquals(BreachPlanner.key(1, 2), BreachPlanner.key(2, 1));
		assertNotEquals(BreachPlanner.key(0x3C, 0xFF000001), BreachPlanner.key(0x3C, 0x00000001));
		assertEquals(BreachPlanner.key(0x3C, 0xFF000001), BreachPlanner.key(0x3C, 0xFF000001));
	}
}
