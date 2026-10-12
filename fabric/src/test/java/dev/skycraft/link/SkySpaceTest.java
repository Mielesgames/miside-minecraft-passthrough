package dev.skycraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkySpaceTest {
	private static final int OROTHEIM = 0x00015233;
	private static final int DRUNKEN_HUNTSMAN = 0x00013814;

	@Test
	void withNoTableEverythingIsAtTheOrigin() {
		SkySpace.close();
		SkySpace.remoteWanted = false;
		assertEquals(0, SkySpace.space(OROTHEIM, "Orotheim"));
		assertEquals(0, SkySpace.originX(OROTHEIM));
		assertTrue(SkySpace.known(OROTHEIM));
		assertTrue(SkySpace.inWorld(OROTHEIM, -57.0, -9.0));
		assertEquals(0, SkySpace.worldAt(-57.0, -9.0));
	}

	@Test
	void theHostGivesSpacesKeepsThemInTheWorldAndTellsGuests() throws IOException {
		Path dir = Files.createTempDirectory("skycraft-spaces");
		try {
			int[] told = { 0 };
			SkySpace.setOnChange(() -> told[0]++);
			SkySpace.open(dir);
			int version = SkySpace.version();
			assertEquals(0, SkySpace.space(WorldSpaces.TAMRIEL, "Whiterun"));
			assertEquals(0, told[0], "Tamriel has its space from the start");
			int o = SkySpace.space(OROTHEIM, "Orotheim");
			assertEquals(1, o);
			assertEquals(1, told[0]);
			assertTrue(SkySpace.version() != version);
			assertEquals(1, SkySpace.space(OROTHEIM, "Orotheim"));
			assertEquals(1, told[0], "nothing new");
			assertEquals(WorldSpaces.SPACING, SkySpace.originX(OROTHEIM));
			assertEquals(OROTHEIM, SkySpace.worldAt(WorldSpaces.SPACING - 57.0, -9.0));
			assertTrue(SkySpace.inWorld(OROTHEIM, WorldSpaces.SPACING - 57.0, -9.0));
			assertFalse(SkySpace.inWorld(OROTHEIM, -57.0, -9.0), "the shared space round the origin is Tamriel's now");
			assertEquals(2, SkySpace.space(DRUNKEN_HUNTSMAN, ""));
			List<String> lines = Files.readAllLines(dir.resolve(SkySpace.FILE), StandardCharsets.UTF_8);
			assertTrue(lines.stream().anyMatch(l -> l.startsWith("00015233 1 ")), lines.toString());
			assertTrue(lines.stream().anyMatch(l -> l.startsWith("00013814 2 ")), lines.toString());
			SkySpace.close();
			assertEquals(0, SkySpace.originX(OROTHEIM), "closed: back to the origin");
			SkySpace.open(dir);
			assertEquals(1, SkySpace.space(OROTHEIM, null), "kept with the world");
			assertEquals(2, SkySpace.space(DRUNKEN_HUNTSMAN, "The Drunken Huntsman"));
			assertEquals("The Drunken Huntsman", SkySpace.table().place(DRUNKEN_HUNTSMAN), "a name found later is kept");
		} finally {
			SkySpace.close();
			SkySpace.setOnChange(() -> {
			});
			for (Path p : Files.list(dir).toList()) {
				Files.delete(p);
			}
			Files.delete(dir);
		}
	}

	@Test
	void aGuestWaitsForTheHostsAnswer() {
		SkySpace.close();
		SkySpace.forgetRemote();
		List<Integer> asked = new ArrayList<>();
		SkySpace.setAsker(asked::add);
		SkySpace.remoteWanted = true;
		try {
			assertEquals(-1, SkySpace.space(OROTHEIM, "Orotheim"));
			assertFalse(SkySpace.known(OROTHEIM));
			assertEquals(List.of(OROTHEIM), asked);
			assertEquals(0, SkySpace.originX(OROTHEIM), "the origin while waiting");
			assertEquals(1, asked.size(), "not asked again at once");
			assertEquals(0, SkySpace.space(0, null), "no world: the origin");
			assertEquals(0, SkySpace.space(WorldSpaces.TAMRIEL, null), "Tamriel is at the origin in every world: no need to ask");
			assertEquals(1, asked.size());
			Map<Integer, Integer> host = new LinkedHashMap<>();
			host.put(WorldSpaces.TAMRIEL, 0);
			host.put(OROTHEIM, 7);
			int version = SkySpace.version();
			SkySpace.received(host);
			assertTrue(SkySpace.version() != version);
			assertEquals(7, SkySpace.space(OROTHEIM, null));
			assertEquals(WorldSpaces.originX(7), SkySpace.originX(OROTHEIM));
			assertEquals(0, SkySpace.space(WorldSpaces.TAMRIEL, null));
			assertEquals(OROTHEIM, SkySpace.worldAt(WorldSpaces.originX(7), WorldSpaces.originZ(7)));
		} finally {
			SkySpace.remoteWanted = false;
			SkySpace.forgetRemote();
			SkySpace.setAsker(world -> {
			});
		}
	}
}
