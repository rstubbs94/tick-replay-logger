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

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reviewed base-context bindings must not become mode-independent through an ID shortcut. */
public class NormalContextGuardTest
{
	private final CombatCatalog catalog = CombatCatalog.load(new Gson());

	@Test
	public void nonConditionalIdsStillRequirePositiveNormalContextForReviewedAnimations()
	{
		for (int[] pair : new int[][]
		{
			{ 2042, 5069 },
			{ 2043, 5069 },
			{ 2044, 5069 },
			{ 2045, 1741 },
			{ 2046, 1741 },
			{ 10880, 11101 } })
		{
			assertFalse(catalog.modeSensitive(pair[0]));
			assertEquals("REVIEWED_ANIMATION_BINDING", catalog.animationEvidence(pair[0], pair[1], null, true));
			assertEquals("attack", catalog.animationKind(pair[0], pair[1], null, true));
			assertNotEquals("REVIEWED_ANIMATION_BINDING", catalog.animationEvidence(pair[0], pair[1], null, false));
			assertNotEquals("REVIEWED_ANIMATION_BINDING", catalog.animationEvidence(pair[0], pair[1]));
			assertFalse(catalog.baseVariantEstablished(pair[0], null, false));
		}
	}

	@Test
	public void cadenceAndSoleStyleRequireContextEvenWithoutConditionalIdentityRecords()
	{
		DamageMap map = DamageMap.load(new Gson());
		assertEquals(3, map.attackSpeedForNpc(2042, 725, true));
		assertEquals(0, map.attackSpeedForNpc(2042, 725, false));
		assertEquals(0, map.attackSpeedForNpc(2042));
		assertNotNull(map.soleStyleForNpc(2, 100, true));
		assertNull(map.soleStyleForNpc(2, 100, false));
		assertTrue(catalog.baseVariantEstablished(9036, 894, true));
		assertFalse(catalog.baseVariantEstablished(9036, 999, true));
		assertFalse(catalog.baseVariantEstablished(9036, null, true));
		assertNotNull(catalog.symbol("animation", 5069)); // Raw/cache reference remains available.
	}
}
