/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedVariantProfileTest
{
	private static CombatCatalog catalog(String rows) throws Exception
	{
		CombatCatalog catalog = new CombatCatalog();
		Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		field.set(catalog, new JsonParser().parse("{\"npcIdentities\":{\"100\":{\"candidates\":" + rows + "}}}").getAsJsonObject());
		return catalog;
	}

	@Test
	public void positiveObservedLevelCanSelectOneOfSeveralKnownLevelsWithoutQualifyingOtherLevels() throws Exception
	{
		CombatCatalog catalog = catalog("[{\"source_ref\":\"fixture:family\",\"combat_level\":\"10\"},"
			+ "{\"source_ref\":\"fixture:family\",\"combat_level\":\"20\"}]");
		assertTrue(catalog.attributionBaseProfile(100, 10, "fixture:family"));
		assertTrue(catalog.attributionBaseProfile(100, 20, "fixture:family"));
		assertFalse(catalog.attributionBaseProfile(100, 15, "fixture:family"));
		assertFalse(catalog.attributionBaseProfile(100, null, "fixture:family"));
		assertFalse(catalog.attributionBaseProfile(100, 10, "fixture:unrelated"));
	}

	@Test
	public void unknownCompatibleLevelPreventsAReferenceFromBeingClaimedComplete() throws Exception
	{
		CombatCatalog catalog = catalog("[{\"source_ref\":\"fixture:family\",\"combat_level\":\"10\"},"
			+ "{\"source_ref\":\"fixture:family\",\"combat_level\":\"Variable\"}]");
		assertFalse(catalog.attributionBaseProfile(100, 10, "fixture:family"));
		assertFalse(catalog.attributionBaseProfile(101, 10, "fixture:family"));
	}
}
