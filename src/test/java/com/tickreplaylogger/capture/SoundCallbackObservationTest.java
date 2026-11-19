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
import java.util.Map;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoundCallbackObservationTest
{
	@Test
	public void copiesObservedValuesWithoutConsumingOrFollowingLaterMutations()
	{
		SoundEffectPlayed event = new SoundEffectPlayed(null);
		event.setSoundId(8123);
		event.setDelay(7);
		Map<String, Object> sample = SoundCallbackObservation.capture(event, null, true, true, a -> null);
		event.setSoundId(99);
		event.setDelay(0);
		event.consume();
		assertEquals(8123, sample.get("soundId"));
		assertEquals(7, sample.get("reportedDelay"));
		assertEquals(false, sample.get("consumedWhenObserved"));
		assertEquals(false, sample.get("sourcePresent"));
		assertEquals(false, sample.get("playbackConfirmed"));
		assertEquals(false, sample.get("scopeKnown"));
	}

	@Test
	public void aConsumedAreaEventRetainsRawPositionWithoutInventingItsScope()
	{
		AreaSoundEffectPlayed event = new AreaSoundEffectPlayed(null);
		event.setSoundId(12);
		event.setDelay(-3);
		event.setSceneX(0);
		event.setSceneY(104);
		event.setRange(15);
		event.consume();
		Map<String, Object> sample = SoundCallbackObservation.capture(event, null, true, true, a -> null);
		assertEquals(true, sample.get("consumedWhenObserved"));
		assertEquals(-3, sample.get("reportedDelay"));
		assertEquals(0, sample.get("sceneX"));
		assertEquals(104, sample.get("sceneY"));
		assertEquals(15, sample.get("reportedRange"));
		assertEquals(false, sample.get("positionScopeKnown"));
		assertEquals(false, sample.get("scopeKnown"));
		assertTrue(event.isConsumed());
		assertEquals(12, event.getSoundId());
		assertEquals(-3, event.getDelay());
	}

	@Test
	public void sourceFailureDoesNotEraseIndependentSoundValues()
	{
		SoundEffectPlayed event = new SoundEffectPlayed(null)
		{
			@Override
			public Actor getSource()
			{
				throw new IllegalStateException("synthetic unavailable source");
			}
		};
		event.setSoundId(111);
		event.setDelay(4);
		Map<String, Object> sample = SoundCallbackObservation.capture(event, null, true, true, a -> null);
		assertEquals(111, sample.get("soundId"));
		assertEquals(4, sample.get("reportedDelay"));
		assertNull(sample.get("sourcePresent"));
		assertTrue(((Map<?, ?>) sample.get("observationIssues")).containsKey("source"));
	}

	@Test
	public void failuresAreIndependentAndEveryEventGetterIsSampledOnce()
	{
		int[] reads = new int[6];
		AreaSoundEffectPlayed event = new AreaSoundEffectPlayed(null)
		{
			@Override
			public int getSoundId()
			{
				assertEquals(1, ++reads[0]);
				throw new IllegalStateException();
			}

			@Override
			public int getDelay()
			{
				assertEquals(1, ++reads[1]);
				return 3;
			}

			@Override
			public boolean isConsumed()
			{
				assertEquals(1, ++reads[2]);
				throw new IllegalStateException();
			}

			@Override
			public int getSceneX()
			{
				assertEquals(1, ++reads[3]);
				throw new IllegalStateException();
			}

			@Override
			public int getSceneY()
			{
				assertEquals(1, ++reads[4]);
				return 9;
			}

			@Override
			public int getRange()
			{
				assertEquals(1, ++reads[5]);
				return 2;
			}
		};
		Map<String, Object> sample = SoundCallbackObservation.capture(event, null, true, true, a -> null);
		assertNull(sample.get("soundId"));
		assertEquals(3, sample.get("reportedDelay"));
		assertEquals(9, sample.get("sceneY"));
		assertEquals(2, sample.get("reportedRange"));
		assertEquals(3, ((Map<?, ?>) sample.get("observationIssues")).size());
		assertArrayEquals(new int[]
		{ 1, 1, 1, 1, 1, 1 }, reads);
	}

	@Test
	public void queuedSourceCannotCreateOrResurrectAnActorLifetime()
	{
		ActorIdentity refs = new ActorIdentity();
		NPC actor = npc(false);
		assertNull(refs.existingRef(actor, null));
		Map<String, Object> first = SoundCallbackObservation.capture(new SoundEffectPlayed(actor), null, true, true,
			a -> refs.existingRef(a, null));
		assertNull(((Map<?, ?>) first.get("reportedSource")).get("actorRef"));
		assertNull(refs.existingRef(actor, null));
		String prior = refs.ref(actor, null);
		assertEquals(prior, refs.existingRef(actor, null));
		refs.forget(actor);
		Map<String, Object> later = SoundCallbackObservation.capture(new SoundEffectPlayed(actor), null, true, true,
			a -> refs.existingRef(a, null));
		assertNull(((Map<?, ?>) later.get("reportedSource")).get("actorRef"));
		assertNull(refs.existingRef(actor, null));
		assertNotEquals(prior, refs.ref(actor, null));
	}

	@Test
	public void unavailableSourceFieldsCannotEraseOtherSourceCoordinates()
	{
		Map<String, Object> sample = SoundCallbackObservation.capture(new SoundEffectPlayed(npc(true)), null, true,
			true, a -> "old-reference");
		Map<?, ?> source = (Map<?, ?>) sample.get("reportedSource");
		assertEquals("old-reference", source.get("actorRef"));
		assertNull(source.get("worldX"));
		assertNull(source.get("name"));
		assertEquals(128, source.get("localX"));
		assertEquals(256, source.get("localY"));
		assertEquals(8124, source.get("reportedNpcId"));
	}

	@Test
	public void privateOrUnavailableActorContextIsNeverRead()
	{
		Player actor = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]
		{ Player.class }, (p, m, a) ->
		{
			throw new AssertionError("Private player metadata was read: " + m.getName());
		});
		SoundEffectPlayed event = new SoundEffectPlayed(actor);
		event.setSoundId(33);
		Map<String, Object> privateSample = SoundCallbackObservation.capture(event, null, true, false, a ->
		{
			throw new AssertionError();
		});
		assertEquals(true, privateSample.get("sourceDetailsWithheld"));
		assertFalse(privateSample.containsKey("reportedSource"));
		assertEquals(33, privateSample.get("soundId"));
		Map<String, Object> unready = SoundCallbackObservation.capture(event, null, false, true, a ->
		{
			throw new AssertionError();
		});
		assertEquals(true, unready.get("sourceContextUnavailable"));
		assertFalse(unready.containsKey("reportedSource"));
	}

	private NPC npc(boolean failOptional)
	{
		return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]
		{ NPC.class }, (p, m, a) ->
		{
			switch (m.getName())
			{
			case "getName":
				if (failOptional)
					throw new IllegalStateException();
				return "Synthetic source";
			case "getId":
				return 8124;
			case "getWorldLocation":
				if (failOptional)
					throw new IllegalStateException();
				return new WorldPoint(4000, 5000, 2);
			case "getLocalLocation":
				return new LocalPoint(128, 256);
			default:
				return m.getReturnType() == int.class ? 0 : m.getReturnType() == boolean.class ? false : null;
			}
		});
	}
}
