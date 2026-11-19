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
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.PrayerState;
import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrayerObservationTest
{
	private final Set<Prayer> active = EnumSet.noneOf(Prayer.class), failed = EnumSet.noneOf(Prayer.class);
	private final Map<Prayer, Integer> reads = new EnumMap<>(Prayer.class);
	private int localReads, iconReads;

	private Client client()
	{
		Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]
		{ Player.class }, (p, m, a) ->
		{
			if (m.getName().equals("getOverheadIcon"))
			{
				iconReads++;
				return HeadIcon.RANGED;
			}
			return null;
		});
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]
		{ Client.class }, (p, m, a) ->
		{
			if (m.getName().equals("getLocalPlayer"))
			{
				if (++localReads > 1)
					throw new IllegalStateException("synthetic duplicate lookup");
				return player;
			}
			if (m.getName().equals("isPrayerActive"))
			{
				Prayer prayer = (Prayer) a[0];
				reads.merge(prayer, 1, Integer::sum);
				if (failed.contains(prayer))
					throw new IllegalStateException("synthetic unavailable flag");
				return active.contains(prayer);
			}
			return null;
		});
	}

	private Map<String, Object> icon(String name, boolean known)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("overheadIcon", name);
		result.put("overheadIconKnown", known);
		return result;
	}

	@Test
	public void aDirectSampleReadsOneActorAndEachFlagOnce()
	{
		active.add(Prayer.PROTECT_FROM_MISSILES);
		PrayerObservation sample = new PrayerObservation(client());
		assertEquals(1, localReads);
		assertEquals(1, iconReads);
		assertEquals(Prayer.values().length, reads.size());
		assertTrue(reads.values().stream().allMatch(n -> n == 1));
		assertEquals("RANGED", sample.getIcon());
		assertTrue(sample.isFlagsComplete());
	}

	@Test
	public void aFailedFlagCannotEraseOtherFlagsOrARecordedIcon()
	{
		active.add(Prayer.PROTECT_FROM_MISSILES);
		active.add(Prayer.PIETY);
		failed.add(Prayer.AUGURY);
		PrayerObservation sample = PrayerObservation.fromActorState(client(), icon("RANGED", true));
		assertEquals(0, localReads);
		assertEquals(0, iconReads);
		assertEquals(Prayer.values().length, reads.size());
		assertFalse(sample.getFlags().containsKey("AUGURY"));
		assertEquals(Boolean.FALSE, sample.getFlags().get("PROTECT_FROM_MAGIC"));
		assertTrue(sample.getReported().contains("PIETY"));
		assertEquals("PROTECT_FROM_MISSILES", sample.getOverhead());
		assertFalse(sample.isFlagsComplete());
		assertTrue(sample.isOverheadResolved());
	}

	@Test
	public void allFailedFlagsStillRetainAnIndependentIconWithoutClaimingACompleteList()
	{
		failed.addAll(Arrays.asList(Prayer.values()));
		PrayerState state = PrayerObservation.fromActorState(client(), icon("RANGED", true)).toState();
		assertTrue(state.getFlags().isEmpty());
		assertFalse(state.isFlagsComplete());
		assertTrue(state.isOverheadIconKnown());
		assertEquals("RANGED", state.getOverheadIcon());
		assertEquals("PROTECT_FROM_MISSILES", state.getOverheadPrayer());
		assertEquals(Prayer.values().length, state.getExpectedFlags());
	}

	@Test
	public void unavailableIconDoesNotEraseCompleteFlags()
	{
		active.add(Prayer.PROTECT_FROM_MAGIC);
		PrayerState state = PrayerObservation.fromActorState(client(), icon(null, false)).toState();
		assertTrue(state.isFlagsComplete());
		assertFalse(state.isOverheadIconKnown());
		assertEquals("PRAYER_FLAGS", state.getOverheadEvidence());
		assertEquals("PROTECT_FROM_MAGIC", state.getOverheadPrayer());
	}

	@Test
	public void knownEmptyAndUnavailableIconsRemainDifferent()
	{
		PrayerState empty = PrayerObservation.fromActorState(client(), icon(null, true)).toState();
		PrayerState unknown = PrayerObservation.fromActorState(client(), icon(null, false)).toState();
		assertTrue(empty.isOverheadIconKnown());
		assertFalse(unknown.isOverheadIconKnown());
		assertNull(empty.getOverheadIcon());
		assertNull(unknown.getOverheadIcon());
		assertEquals("NONE", empty.getOverheadEvidence());
		assertTrue(empty.isOverheadPrayerKnown());
	}

	@Test
	public void partialFlagsCannotResolveAnOverheadByEnumOrder()
	{
		active.add(Prayer.PROTECT_FROM_MISSILES);
		failed.add(Prayer.PROTECT_FROM_MAGIC);
		PrayerState state = PrayerObservation.fromActorState(client(), icon(null, false)).toState();
		assertFalse(state.isOverheadPrayerKnown());
		assertNull(state.getOverheadPrayer());
		assertTrue(state.getReportedPrayers().contains("PROTECT_FROM_MISSILES"));
		assertEquals("PARTIAL_PRAYER_FLAGS", state.getOverheadEvidence());
	}

	@Test
	public void aMissingFlagIsNotAConflictButAnObservedFalseFlagIs()
	{
		failed.add(Prayer.PROTECT_FROM_MISSILES);
		assertFalse(PrayerObservation.fromActorState(client(), icon("RANGED", true)).isConflict());
		failed.clear();
		assertTrue(PrayerObservation.fromActorState(client(), icon("RANGED", true)).isConflict());
	}

	@Test
	public void competingFlagsRemainInspectableWithoutChoosingOne()
	{
		active.add(Prayer.PROTECT_FROM_MISSILES);
		active.add(Prayer.PROTECT_FROM_MAGIC);
		PrayerState state = PrayerObservation.fromActorState(client(), icon(null, true)).toState();
		assertTrue(state.isFlagsComplete());
		assertTrue(state.isOverheadConflict());
		assertFalse(state.isOverheadPrayerKnown());
		assertEquals(2, state.getReportedPrayers().size());
		assertNull(state.getOverheadPrayer());
		assertEquals("AMBIGUOUS", state.getOverheadEvidence());
	}

	@Test
	public void unmappedIconsCannotBecomeNoPrayerOrASingleFlagFallback()
	{
		active.add(Prayer.PROTECT_FROM_MAGIC);
		PrayerState state = PrayerObservation.fromActorState(client(), icon("RANGE_MAGE", true)).toState();
		assertEquals("RANGE_MAGE", state.getOverheadIcon());
		assertTrue(state.isOverheadIconKnown());
		assertFalse(state.isOverheadPrayerKnown());
		assertNull(state.getOverheadPrayer());
		assertEquals("UNMAPPED_ACTOR_ICON", state.getOverheadEvidence());
	}

	@Test
	public void sharedStateAndItsCollectionsAreImmutable()
	{
		Map<String, Object> original = icon("RANGED", true);
		active.add(Prayer.PROTECT_FROM_MISSILES);
		PrayerObservation sample = PrayerObservation.fromActorState(client(), original);
		PrayerState state = sample.toState();
		original.put("overheadIcon", "MAGIC");
		active.clear();
		assertSame(state, sample.toState());
		assertEquals("RANGED", state.getOverheadIcon());
		try
		{
			state.getFlags().put("AUGURY", true);
			fail();
		}
		catch (UnsupportedOperationException expected)
		{
		}
		try
		{
			state.getReportedPrayers().clear();
			fail();
		}
		catch (UnsupportedOperationException expected)
		{
		}
		try
		{
			sample.getActive().clear();
			fail();
		}
		catch (UnsupportedOperationException expected)
		{
		}
	}
}
