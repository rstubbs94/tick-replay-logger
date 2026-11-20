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
package com.tickreplaylogger.model;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TickDataTest
{
	@Test
	public void effectNotesAccumulate()
	{
		TickData tick = TickData.builder().tickNumber(1).timestamp(0L).build();
		assertTrue("effect notes default empty", tick.getEffectNotes().isEmpty());
		tick.getEffectNotes().add("Frozen");
		tick.getEffectNotes().add("Knocked back 2 tiles");
		assertEquals(2, tick.getEffectNotes().size());
		assertEquals("Frozen", tick.getEffectNotes().get(0));
		assertEquals("Knocked back 2 tiles", tick.getEffectNotes().get(1));
	}

	@Test
	public void damageAndEffectsAreIndependentLists()
	{
		TickData tick = TickData.builder().tickNumber(2).timestamp(0L).build();
		tick.getDamageEvents().add(DamageEvent.received(10, DamageEvent.DamageType.MAGIC, "Zebak", "CONFIRMED", "IMPACT"));
		tick.getEffectNotes().add("Teleblocked");
		assertEquals(1, tick.getDamageEvents().size());
		assertEquals(1, tick.getEffectNotes().size());
	}
}
