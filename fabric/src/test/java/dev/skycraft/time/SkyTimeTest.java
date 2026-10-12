package dev.skycraft.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SkyTimeTest {
	@Test
	void skyrimHoursMapToMinecraftTicks() {
		assertEquals(0, SkyTime.ticksFor(6.0F));
		assertEquals(6000, SkyTime.ticksFor(12.0F));
		assertEquals(12000, SkyTime.ticksFor(18.0F));
		assertEquals(18000, SkyTime.ticksFor(0.0F));
		assertEquals(23500, SkyTime.ticksFor(5.5F));
		assertEquals(0, SkyTime.ticksFor(30.0F));
	}

	@Test
	void sleepingRunsToMorning() {
		assertEquals(8.0F, SkyTime.hoursToSleep(22.0F), 1e-4);
		assertEquals(4.0F, SkyTime.hoursToSleep(2.0F), 1e-4);
		assertEquals(12.0F, SkyTime.hoursToSleep(18.0F), 1e-4);
		assertEquals(8.0F, SkyTime.hoursToSleep(14.0F), 1e-4);
		assertEquals(8.0F, SkyTime.hoursToSleep(5.5F), 1e-4);
	}

	@Test
	void driftWrapsAroundMidnight() {
		assertEquals(100, SkyTime.drift(23950, 50));
		assertEquals(100, SkyTime.drift(50, 23950));
		assertEquals(0, SkyTime.drift(6000, 6000));
	}
}
