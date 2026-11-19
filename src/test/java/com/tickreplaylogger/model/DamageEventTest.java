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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DamageEventTest
{
	@Test
	public void receivedDescriptionShowsConfidenceAndReactability()
	{
		DamageEvent impact = DamageEvent.received(30, DamageEvent.DamageType.MAGIC, "Zebak", "CONFIRMED", "IMPACT");
		String d = impact.getDescription();
		assertTrue(d.contains("Received 30 damage from Zebak"));
		assertFalse("internal confidence is not user-facing wording", d.contains("confirmed"));
		assertTrue(d.contains("[Hit tick]"));
	}

	@Test
	public void launchReadsAsPrayBefore()
	{
		DamageEvent launch = DamageEvent.received(12, DamageEvent.DamageType.RANGED, "TzTok-Jad", "TIMED", "LAUNCH");
		assertTrue(launch.getDescription().contains("[Attack tick]"));
	}

	@Test
	public void unknownConfidenceAndReactabilityAreHidden()
	{
		DamageEvent plain = DamageEvent.received(5, DamageEvent.DamageType.MELEE, "Goblin", "UNKNOWN", "UNKNOWN");
		String d = plain.getDescription();
		assertEquals("Received 5 damage from Goblin", d);
		assertFalse(d.contains("["));
	}

	@Test
	public void poisonDescriptionIsTypeLabelled()
	{
		DamageEvent poison = DamageEvent.received(4, DamageEvent.DamageType.POISON, "Poison");
		assertEquals("Received 4 poison damage", poison.getDescription());
	}

	@Test
	public void legacyReceivedOverloadsStillWork()
	{
		// Two- and three-arg factories must keep working (no determination/confidence).
		assertEquals("Received 7 damage from Rat",
			DamageEvent.received(7, DamageEvent.DamageType.MELEE, "Rat").getDescription());
		assertEquals("Received 7 damage from Rat",
			DamageEvent.received(7, DamageEvent.DamageType.MELEE, "Rat", "PROXIMITY").getDescription());
	}
}
