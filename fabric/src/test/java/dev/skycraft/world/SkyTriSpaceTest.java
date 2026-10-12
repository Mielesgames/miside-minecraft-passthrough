package dev.skycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import dev.skycraft.link.WorldSpaces;
import org.junit.jupiter.api.Test;

/** Skyrim's triangles moved into a world's space (SkyCollision) are the same triangles, exactly. */
class SkyTriSpaceTest {
	private static final float[] SLOPE = { -57.25F, 1.5F, -9.125F, -55.0F, 2.75F, -9.0F, -56.5F, 1.0F, -7.75F };

	@Test
	void movedIntoASpaceItIsTheSameTriangle() {
		int flags = Proto.TRI_DIGGABLE | Proto.TRI_TERRAIN | Proto.DIG_GRAVEL << Proto.TRI_MATERIAL_SHIFT;
		SkyTri here = new SkyTri(SLOPE, 0, flags);
		for (int space : new int[] { 1, 2, 9, 4000 }) {
			int dx = WorldSpaces.originX(space), dz = WorldSpaces.originZ(space);
			SkyTri there = new SkyTri(SLOPE, 0, flags, dx, dz);
			SkyTri moved = here.shifted(dx, dz);
			for (SkyTri t : new SkyTri[] { there, moved }) {
				assertEquals(here.ax + dx, t.ax, 0.0);
				assertEquals(here.cz + dz, t.cz, 0.0);
				assertEquals(here.ay, t.ay, 0.0);
				assertEquals(here.nx, t.nx, 0.0);
				assertEquals(here.ny, t.ny, 0.0);
				assertEquals(here.nz, t.nz, 0.0);
				assertEquals(here.minX + dx, t.minX, 0.0);
				assertEquals(here.maxZ + dz, t.maxZ, 0.0);
				assertEquals(here.material, t.material);
				assertTrue(t.diggable && t.terrain && !t.stairHelper && t.walkable == here.walkable);
				// Heights over the same spot match (to rounding: -56.4 itself isn't exact in binary).
				assertEquals(here.heightAt(-56.4, -8.6), t.heightAt(-56.4 + dx, -8.6 + dz), 1e-9);
			}
			assertEquals(here.ax, moved.shifted(-dx, -dz).ax, 0.0);
		}
		SkyTri stair = new SkyTri(SLOPE, 0, Proto.TRI_STAIR_HELPER).shifted(WorldSpaces.SPACING, 0);
		assertTrue(stair.stairHelper && stair.walkable && !stair.diggable);
	}
}
