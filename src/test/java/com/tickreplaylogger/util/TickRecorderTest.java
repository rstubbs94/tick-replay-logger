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
package com.tickreplaylogger.util;

import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import org.junit.Test;
import static org.junit.Assert.*;

public class TickRecorderTest
{
	@Test
	public void persistedInvalidCapacityValuesCannotAllocateUnboundedBuffersOrDivideByZero()
	{
		TickRecorder recorder = new TickRecorder(Integer.MAX_VALUE);
		recorder.resize(0);
		for (int i = 0; i < 3; i++)
		{
			recorder.startNewTick();
			recorder.finalizeTick();
		}
		assertEquals(1, recorder.getTickHistory().size());
		assertEquals(3, recorder.getTickHistory().get(0).getTickNumber());
	}

	@Test
	public void callbacksBeforeFirstTickAreIncludedAndLaterCallbacksCannotMutateHistory()
	{
		TickRecorder recorder = new TickRecorder(10);
		recorder.addPlayerAction(PlayerAction.ate("Shark"));
		recorder.addDamageEvent(DamageEvent.received(5, DamageEvent.DamageType.POISON, "Poison"));
		recorder.startNewTick();
		recorder.addEffectNote("Poisoned");
		recorder.finalizeTick();
		TickData first = recorder.getTickHistory().get(0);
		recorder.addPlayerAction(PlayerAction.ate("Manta ray"));
		recorder.addDamageEvent(DamageEvent.received(7, DamageEvent.DamageType.UNKNOWN, "Boss"));
		assertEquals(1, first.getPlayerActions().size());
		assertEquals(1, first.getDamageEvents().size());
		recorder.startNewTick();
		recorder.finalizeTick();
		assertEquals(7, recorder.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
		assertEquals("Manta ray", recorder.getTickHistory().get(1).getPlayerActions().get(0).getItemName());
		assertEquals("Poisoned", first.getEffectNotes().get(0));
	}

	@Test
	public void resetDropsCallbacksWaitingForNextTick()
	{
		TickRecorder recorder = new TickRecorder(10);
		recorder.addPlayerAction(PlayerAction.ate("Shark"));
		recorder.addDamageEvent(DamageEvent.received(5, DamageEvent.DamageType.POISON, "Poison"));
		recorder.addEffectNote("Poisoned");
		recorder.resetTickCounter();
		recorder.startNewTick();
		recorder.finalizeTick();
		TickData first = recorder.getTickHistory().get(0);
		assertEquals(1, first.getTickNumber());
		assertTrue(first.getPlayerActions().isEmpty());
		assertTrue(first.getDamageEvents().isEmpty());
		assertTrue(first.getEffectNotes().isEmpty());
	}

	@Test
	public void effectMirrorLinksUseExactNoteIndicesAndOriginalSequencesAcrossTickFreeze()
	{
		TickRecorder recorder = new TickRecorder(10);
		recorder.addEffectNote("Unlinked ordinary note");
		recorder.addEffectNote("Impact 3 (HP/stat) from Unknown", 71L);
		recorder.startNewTick();
		recorder.addEffectNote("Identical display text is not proof of a mirror");
		recorder.addEffectNote("Impact 3 (HP/stat) from Unknown", 72L);
		recorder.finalizeTick();
		TickData first = recorder.getTickHistory().get(0);
		assertEquals(Long.valueOf(71), first.getEffectNoteSourceSequences().get(1));
		assertEquals(Long.valueOf(72), first.getEffectNoteSourceSequences().get(3));
		assertFalse(first.getEffectNoteSourceSequences().containsKey(0));
		assertFalse(first.getEffectNoteSourceSequences().containsKey(2));
		try { first.getEffectNoteSourceSequences().put(0, 99L); fail("Finalized mirror map must be immutable"); }
		catch (UnsupportedOperationException expected) { }
		recorder.addEffectNote("Next tick", 73L);
		recorder.startNewTick(); recorder.finalizeTick();
		assertEquals(2, first.getEffectNoteSourceSequences().size());
		assertEquals(Long.valueOf(73), recorder.getTickHistory().get(1).getEffectNoteSourceSequences().get(0));
	}

	@Test
	public void invalidMirrorSequencesDoNotCreateLinksOrSurviveReset()
	{
		TickRecorder recorder = new TickRecorder(10);
		recorder.addEffectNote("Unresolved callback", -1L);
		recorder.addEffectNote("Unknown callback", 0L);
		recorder.addEffectNote("Valid pending callback", 90L);
		recorder.resetTickCounter(); recorder.startNewTick(); recorder.finalizeTick();
		assertTrue(recorder.getTickHistory().get(0).getEffectNotes().isEmpty());
		assertTrue(recorder.getTickHistory().get(0).getEffectNoteSourceSequences().isEmpty());
	}
}
