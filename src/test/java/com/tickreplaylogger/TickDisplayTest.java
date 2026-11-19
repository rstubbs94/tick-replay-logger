/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger;

import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.util.TickDisplay;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TickDisplayTest
{
	@Test
	public void resourceAvailabilityChangesRemainVisibleEvenWhenBothStoredNumbersAreZero()
	{
		TickData failed = tick(1, 0, 99, null);
		failed.getUnavailableStateFields().add("currentHp");
		TickData observed = tick(2, 0, 99, null);
		assertEquals(2, TickDisplay.build(Arrays.asList(failed, observed), true).size());
		assertEquals("?/99", com.tickreplaylogger.util.DisplayFormat.resourceText(failed, true));
		assertEquals("0/99", com.tickreplaylogger.util.DisplayFormat.resourceText(observed, true));
	}

	private static TickData tick(int n, int hp, int prayer, List<String> prayers)
	{
		return TickData.builder().tickNumber(n).currentHp(hp).maxHp(99).currentPrayer(prayer).maxPrayer(99)
			.activePrayers(prayers != null ? new ArrayList<>(prayers) : new ArrayList<>()).build();
	}

	private static TickData idle(int n)
	{
		return tick(n, 99, 99, null);
	}

	@Test
	public void collapseOffKeepsEveryTick()
	{
		List<TickData> ticks = Arrays.asList(idle(1), idle(2), idle(3));
		List<TickDisplay.Row> rows = TickDisplay.build(ticks, false);
		assertEquals(3, rows.size());
		for (TickDisplay.Row r : rows)
		{
			assertFalse(r.isGap());
		}
		assertEquals(0, rows.get(0).index);
		assertEquals(2, rows.get(2).index);
	}

	@Test
	public void identicalTicksCollapseToFirstPlusGap()
	{
		List<TickData> ticks = Arrays.asList(idle(1), idle(2), idle(3), idle(4));
		List<TickDisplay.Row> rows = TickDisplay.build(ticks, true);
		assertEquals(2, rows.size());
		assertFalse(rows.get(0).isGap());
		assertEquals(0, rows.get(0).index);
		assertTrue(rows.get(1).isGap());
		assertEquals(3, rows.get(1).gapCount);
	}

	@Test
	public void hpChangeShowsTickWithGapBefore()
	{
		List<TickData> ticks = Arrays.asList(idle(1), idle(2), idle(3), tick(4, 80, 99, null));
		List<TickDisplay.Row> rows = TickDisplay.build(ticks, true);
		assertEquals(3, rows.size());
		assertFalse(rows.get(0).isGap());
		assertTrue(rows.get(1).isGap());
		assertEquals(2, rows.get(1).gapCount);
		assertFalse(rows.get(2).isGap());
		assertEquals(3, rows.get(2).index);
	}

	@Test
	public void prayerPointsAndActivePrayersTriggerShow()
	{
		// prayer points changed
		assertTrue(TickDisplay.isChanged(tick(2, 99, 90, null), idle(1)));

		// same active prayers in different order => NOT a change
		List<String> p1 = Arrays.asList("Rigour", "Protect from Magic");
		List<String> p2 = Arrays.asList("Protect from Magic", "Rigour");
		assertFalse(TickDisplay.isChanged(tick(2, 99, 99, p2), tick(1, 99, 99, p1)));

		// different active prayers => change
		assertTrue(TickDisplay.isChanged(tick(2, 99, 99, Arrays.asList("Rigour")), tick(1, 99, 99, p1)));
	}

	@Test
	public void telemetryDifferencesAreIgnored()
	{
		TickData a = TickData.builder().tickNumber(1).currentHp(99).maxHp(99).currentPrayer(99).maxPrayer(99).pingMs(20)
			.tickDurationMs(600).build();
		TickData b = TickData.builder().tickNumber(2).currentHp(99).maxHp(99).currentPrayer(99).maxPrayer(99)
			.pingMs(180).tickDurationMs(720).packetLossPercent(30).build();
		assertFalse("telemetry must not count as a change", TickDisplay.isChanged(b, a));
	}

	@Test
	public void missingPrayerSamplesShowAvailabilityWithoutInventingPrayerSwitches()
	{
		TickData before = tick(1, 99, 99, Arrays.asList("Protect from Magic"));
		TickData unavailable = idle(2);
		unavailable.setPrayerStateKnown(false);
		TickData recovered = idle(3);
		recovered.setPrayerStateKnown(true);
		assertEquals(Arrays.asList("Prayer state unavailable"),
			com.tickreplaylogger.util.DisplayFormat.prayerChanges(unavailable, before));
		assertEquals(Arrays.asList("Prayer state available; none active"),
			com.tickreplaylogger.util.DisplayFormat.prayerChanges(recovered, unavailable));
		assertTrue(TickDisplay.isChanged(recovered, unavailable));
	}

	@Test
	public void anyEventTriggersShow()
	{
		TickData withAction = TickData.builder().tickNumber(2).currentHp(99).maxHp(99).currentPrayer(99).maxPrayer(99)
			.playerActions(new ArrayList<>(Arrays.asList(PlayerAction.ate("Shark")))).build();
		assertTrue(TickDisplay.isChanged(withAction, idle(1)));
	}

	@Test
	public void trailingGapEmitted()
	{
		List<TickData> ticks = Arrays.asList(tick(1, 80, 99, null), idle(2), idle(3));
		List<TickDisplay.Row> rows = TickDisplay.build(ticks, true);
		assertEquals(3, rows.size());
		assertEquals(0, rows.get(0).index);
		assertEquals(1, rows.get(1).index);
		assertTrue(rows.get(2).isGap());
		assertEquals(1, rows.get(2).gapCount);
	}

	@Test
	public void environmentalConfidenceDoesNotInventTypelessDamage()
	{
		com.tickreplaylogger.model.DamageEvent unknown = com.tickreplaylogger.model.DamageEvent.received(5,
			com.tickreplaylogger.model.DamageEvent.DamageType.UNKNOWN, "Environmental", "ENVIRONMENTAL");
		assertEquals(com.tickreplaylogger.model.DamageEvent.DamageType.UNKNOWN,
			com.tickreplaylogger.util.DisplayFormat.displayDamageType(unknown));
		com.tickreplaylogger.model.DamageEvent explicit = com.tickreplaylogger.model.DamageEvent.received(5,
			com.tickreplaylogger.model.DamageEvent.DamageType.TYPELESS, "Reviewed floor", "ENVIRONMENTAL");
		assertEquals(com.tickreplaylogger.model.DamageEvent.DamageType.TYPELESS,
			com.tickreplaylogger.util.DisplayFormat.displayDamageType(explicit));
	}
}
