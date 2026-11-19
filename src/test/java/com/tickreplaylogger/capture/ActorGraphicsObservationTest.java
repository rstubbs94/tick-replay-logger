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

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.IterableHashTable;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActorGraphicsObservationTest
{
	@Test
	public void delayedAndDuplicateAnimationsKeepTheirIndependentLifecycleFields()
	{
		ActorSpotAnim first = animation(3281, 900, -1), second = animation(3281, 700, 3);
		ActorGraphicsObservation sample = new ActorGraphicsObservation(actor(Arrays.asList(first, second), 3280));
		assertTrue(sample.isIdsKnown());
		assertArrayEquals(new int[]
		{ 3281, 3280 }, sample.getIds());
		assertEquals(2, sample.getState().getEntries().size());
		assertEquals(Integer.valueOf(900), sample.getState().getEntries().get(0).getStartCycle());
		assertEquals(Integer.valueOf(-1), sample.getState().getEntries().get(0).getFrame());
		assertEquals(Integer.valueOf(700), sample.getState().getEntries().get(1).getStartCycle());
		assertEquals(1, sample.getState().getEntries().get(1).getOrdinal());
	}

	@Test
	public void lifecycleReadFailureDoesNotEraseTheIndependentlyObservedId()
	{
		ActorSpotAnim partial = stub(ActorSpotAnim.class, (name, args) ->
		{
			if (name.equals("getId"))
				return 3281;
			if (name.equals("getStartCycle"))
				throw new IllegalStateException("unavailable start");
			return 7;
		});
		ActorGraphicsObservation sample = new ActorGraphicsObservation(actor(Collections.singletonList(partial), -1));
		assertTrue(sample.isIdsKnown());
		assertTrue(sample.getState().isCollectionKnown());
		assertArrayEquals(new int[]
		{ 3281 }, sample.getIds());
		assertNull(sample.getState().getEntries().get(0).getStartCycle());
		assertEquals(Integer.valueOf(7), sample.getState().getEntries().get(0).getFrame());
		assertEquals("IllegalStateException",
			sample.getState().getEntries().get(0).getObservationIssues().get("startCycle"));
	}

	@Test
	public void aBrokenIteratorRetainsThePrefixWithoutClaimingACompleteCollection()
	{
		ActorSpotAnim first = animation(3281, 5, 2);
		IterableHashTable<ActorSpotAnim> broken = stub(IterableHashTable.class, (name, args) ->
		{
			if (!name.equals("iterator"))
				return null;
			return new Iterator<ActorSpotAnim>()
			{
				boolean delivered;

				public boolean hasNext()
				{
					if (delivered)
						throw new IllegalStateException("unavailable suffix");
					return true;
				}

				public ActorSpotAnim next()
				{
					delivered = true;
					return first;
				}
			};
		});
		Actor source = stub(Actor.class,
			(name, args) -> name.equals("getSpotAnims") ? broken : name.equals("getGraphic") ? -1 : null);
		ActorGraphicsObservation sample = new ActorGraphicsObservation(source);
		assertFalse(sample.isIdsKnown());
		assertFalse(sample.getState().isCollectionKnown());
		assertEquals(1, sample.getState().getEntries().size());
		assertArrayEquals(new int[]
		{ 3281 }, sample.getIds());
		assertEquals("IllegalStateException", sample.getState().getObservationIssues().get("graphicsContents"));
	}

	@Test
	public void nullEntriesDoNotBecomeProofOfAnEmptyGraphicSet()
	{
		ActorGraphicsObservation sample = new ActorGraphicsObservation(actor(Arrays.asList((ActorSpotAnim) null), -1));
		assertTrue(sample.getState().isCollectionKnown());
		assertFalse(sample.isIdsKnown());
		assertFalse(sample.getState().getEntries().get(0).isEntryAvailable());
		assertNull(sample.getIds());
	}

	@Test
	public void anAbsentCollectionAndASuccessfulEmptyCollectionRemainDifferent()
	{
		ActorGraphicsObservation absent = new ActorGraphicsObservation(actor(null, 3280));
		assertFalse(absent.getState().isCollectionKnown());
		assertTrue(absent.getState().isPrimaryGraphicKnown());
		assertNull(absent.getState().getEntries());
		assertArrayEquals(new int[]
		{ 3280 }, absent.getIds());
		ActorGraphicsObservation empty = new ActorGraphicsObservation(actor(Collections.emptyList(), -1));
		assertTrue(empty.isIdsKnown());
		assertTrue(empty.getState().isCollectionKnown());
		assertEquals(0, empty.getState().getEntries().size());
		assertArrayEquals(new int[0], empty.getIds());
	}

	@Test
	public void returnedObservationsCannotBeChangedByTheApiOrTheLegacyArrayCaller()
	{
		int[] id =
		{ 3281 };
		ActorSpotAnim graphic = stub(ActorSpotAnim.class, (name, args) -> name.equals("getId") ? id[0] : 0);
		ActorGraphicsObservation sample = new ActorGraphicsObservation(actor(Collections.singletonList(graphic), -1));
		id[0] = 100;
		int[] ids = sample.getIds();
		ids[0] = 200;
		assertEquals(Integer.valueOf(3281), sample.getState().getEntries().get(0).getId());
		assertArrayEquals(new int[]
		{ 3281 }, sample.getIds());
		try
		{
			sample.getState().getEntries().clear();
			fail("Entries must be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
		}
	}

	@Test
	public void bothApiChannelsAreReadOnceAndFailIndependently()
	{
		int[] reads =
		{ 0, 0 };
		Actor source = stub(Actor.class, (name, args) ->
		{
			if (name.equals("getSpotAnims"))
			{
				reads[0]++;
				throw new IllegalStateException("collection failed");
			}
			if (name.equals("getGraphic"))
			{
				reads[1]++;
				return 3281;
			}
			return null;
		});
		ActorGraphicsObservation sample = new ActorGraphicsObservation(source);
		assertArrayEquals(new int[]
		{ 1, 1 }, reads);
		assertTrue(sample.getState().isPrimaryGraphicKnown());
		assertFalse(sample.getState().isCollectionKnown());
		assertArrayEquals(new int[]
		{ 3281 }, sample.getIds());
	}

	private static Actor actor(List<ActorSpotAnim> entries, int primary)
	{
		IterableHashTable<ActorSpotAnim> table = entries == null ? null
			: stub(IterableHashTable.class, (name, args) -> name.equals("iterator") ? entries.iterator() : null);
		return stub(Actor.class, (name, args) ->
		{
			if (name.equals("getSpotAnims"))
				return table;
			if (name.equals("getGraphic"))
				return primary;
			return null;
		});
	}

	private static ActorSpotAnim animation(int id, int start, int frame)
	{
		return stub(ActorSpotAnim.class, (name, args) ->
		{
			if (name.equals("getId"))
				return id;
			if (name.equals("getStartCycle"))
				return start;
			if (name.equals("getFrame"))
				return frame;
			return 0;
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (method.getName().equals("equals"))
				return proxy == args[0];
			if (method.getName().equals("toString"))
				return type.getSimpleName();
			Object value = values.apply(method.getName(), args);
			if (value != null)
				return value;
			if (method.getReturnType() == int.class)
				return 0;
			if (method.getReturnType() == long.class)
				return 0L;
			if (method.getReturnType() == boolean.class)
				return false;
			return null;
		});
	}
}
