package dev.skycraft.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BreachRoomTest {
	private static int[] count(BreachRoom r) {
		int[] kinds = new int[5];
		for (int x = r.minX() - 1; x <= r.maxX() + 1; x++) {
			for (int y = r.minY() - 1; y <= r.maxY() + 1; y++) {
				for (int z = r.minZ() - 1; z <= r.maxZ() + 1; z++) {
					int kind = r.kind(x, y, z);
					kinds[kind]++;
					assertEquals(kind != BreachRoom.OUTSIDE, r.contains(x, y, z), x + " " + y + " " + z);
				}
			}
		}
		return kinds;
	}

	@Test
	void everyFacingHasTheSameShape() {
		for (int facing = 0; facing < 4; facing++) {
			for (int shell = 1; shell <= 2; shell++) {
				BreachRoom r = new BreachRoom(0x3C, 1, 17, 30, -9, facing, shell, Proto.THEME_CAVE);
				int[] kinds = count(r);
				int outer = (BreachRoom.WIDTH + 2 * shell) * (BreachRoom.HEIGHT + 2 * shell) * (BreachRoom.LENGTH + 2 * shell);
				assertEquals(BreachRoom.WIDTH * BreachRoom.HEIGHT * BreachRoom.LENGTH, kinds[BreachRoom.INTERIOR]);
				assertEquals(4, kinds[BreachRoom.DOOR]);
				assertEquals(8, kinds[BreachRoom.FRAME]);
				assertEquals(outer, kinds[BreachRoom.INTERIOR] + kinds[BreachRoom.SHELL] + kinds[BreachRoom.FRAME] + kinds[BreachRoom.DOOR]);
				assertEquals(BreachRoom.WIDTH + 2 * shell, (facing % 2 == 0 ? r.maxX() - r.minX() : r.maxZ() - r.minZ()) + 1);
				assertEquals(BreachRoom.LENGTH + 2 * shell, (facing % 2 == 0 ? r.maxZ() - r.minZ() : r.maxX() - r.minX()) + 1);
				assertEquals(BreachRoom.HEIGHT + 2 * shell, r.maxY() - r.minY() + 1);
			}
		}
	}

	@Test
	void localAndWorldCoordinatesRoundTrip() {
		for (int facing = 0; facing < 4; facing++) {
			BreachRoom r = new BreachRoom(0x3C, 1, -5, 12, 40, facing, 1, Proto.THEME_DWEMER);
			for (int u = -2; u < 10; u++) {
				for (int w = -2; w < 9; w++) {
					int x = r.x(u, w), z = r.z(u, w);
					assertEquals(u, r.u(x, z));
					assertEquals(w, r.w(x, z));
				}
			}
			// +u is to the right when facing the sealed door, the same way round for every facing
			// (facing north (0, -1), right is east (1, 0)).
			assertEquals(1, r.fx() * r.rz() - r.fz() * r.rx(), "facing " + facing);
		}
	}

	@Test
	void rightIsClockwiseLikeMinecraft() {
		// Direction.getClockWise: north to east, east to south, south to west, west to north.
		assertEquals(3, BreachRoom.clockwise(2)); // north -> east
		assertEquals(0, BreachRoom.clockwise(3)); // east -> south
		assertEquals(1, BreachRoom.clockwise(0)); // south -> west
		assertEquals(2, BreachRoom.clockwise(1)); // west -> north
		assertEquals(0, BreachRoom.stepX(0));
		assertEquals(1, BreachRoom.stepZ(0)); // south is +z
		assertEquals(-1, BreachRoom.stepX(1)); // west is -x
		assertEquals(-1, BreachRoom.stepZ(2)); // north is -z
		assertEquals(1, BreachRoom.stepX(3)); // east is +x
	}

	@Test
	void furnitureSlotsAreInsideAndApart() {
		Set<String> seen = new HashSet<>();
		int[][][] groups = { { BreachRoom.CHEST_SLOT, BreachRoom.LANTERN_SLOT }, BreachRoom.TORCH_SLOTS, BreachRoom.FLOOR_CORNERS, BreachRoom.CEILING_CORNERS };
		for (int[][] group : groups) {
			for (int[] s : group) {
				assertEquals(BreachRoom.INTERIOR, BreachRoom.kindLocal(s[0], s[1], s[2], 1), java.util.Arrays.toString(s));
				assertTrue(seen.add(s[0] + "," + s[1] + "," + s[2]), "twice: " + java.util.Arrays.toString(s));
			}
		}
		// The wall lights hang on the door's frame.
		for (int[] s : BreachRoom.TORCH_SLOTS) {
			assertEquals(BreachRoom.FRAME, BreachRoom.kindLocal(s[0], s[1], s[2] + 1, 1));
		}
		// Floor corner pillars (up to the ceiling) never run into the lantern, chest or wall lights.
		for (int[] c : BreachRoom.FLOOR_CORNERS) {
			for (int v = 0; v < BreachRoom.HEIGHT; v++) {
				for (int[] s : new int[][] { BreachRoom.CHEST_SLOT, BreachRoom.LANTERN_SLOT, BreachRoom.TORCH_SLOTS[0], BreachRoom.TORCH_SLOTS[1] }) {
					assertFalse(c[0] == s[0] && v == s[1] && c[2] == s[2]);
				}
			}
		}
	}

	@Test
	void interiorDistanceAndNearestPoint() {
		BreachRoom r = new BreachRoom(0x3C, 1, 0, 10, 0, 0, 1, Proto.THEME_CAVE); // facing south: u runs west, w south
		int x = r.x(2, 2), z = r.z(2, 2);
		assertTrue(r.inInterior(x + 0.5, 10.1, z + 0.5));
		assertFalse(r.inInterior(x + 0.5, 9.9, z + 0.5)); // in the floor
		assertEquals(0.0, r.distanceTo(x + 0.5, 11.0, z + 0.5));
		double above = r.distanceTo(x + 0.5, r.maxY() + 1 + 5.0, z + 0.5);
		assertEquals(5.0, above, 1.0E-9);
		double[] q = new double[3];
		r.nearestPoint(x + 0.5, r.maxY() + 20.0, z + 0.5, q);
		assertEquals(r.maxY() + 1.0, q[1], 1.0E-9);
		assertEquals(x + 0.5, q[0], 1.0E-9);
	}

	@Test
	void overlapNeedsBothBoxesToMeet() {
		BreachRoom a = new BreachRoom(0x3C, 1, 0, 10, 0, 0, 1, Proto.THEME_CAVE);
		BreachRoom b = new BreachRoom(0x3C, 2, 30, 10, 0, 0, 1, Proto.THEME_CAVE);
		BreachRoom c = new BreachRoom(0x3C, 3, 4, 12, 3, 1, 2, Proto.THEME_CAVE);
		assertFalse(a.overlaps(b, 1));
		assertTrue(a.overlaps(c, 0));
		BreachRoom touching = new BreachRoom(0x3C, 4, a.bx(), a.maxY() + 1 + 1, a.bz(), 0, 1, Proto.THEME_CAVE); // its floor shell just over a's roof
		assertFalse(a.overlaps(touching, 0));
		assertTrue(a.overlaps(touching, 1));
	}
}
