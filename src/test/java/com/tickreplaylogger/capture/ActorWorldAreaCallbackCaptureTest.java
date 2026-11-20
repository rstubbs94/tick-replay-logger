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

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActorWorldAreaCallbackCaptureTest
{
	@SuppressWarnings("unchecked")
	@Test
	public void directAreaIgnoresRenderedCenterAndDefinitionSizeAndKeepsNativeView()
	{
		Map<String, Object> sample = capture(new WorldArea(3210, 3220, 5, 3, 2), false, false);
		assertEquals(Boolean.TRUE, sample.get("worldAreaKnown"));
		assertEquals(Boolean.TRUE, sample.get("worldAreaWorldViewKnown"));
		assertEquals(7, sample.get("worldAreaWorldViewId"));
		Map<String, Object> area = (Map<String, Object>) sample.get("worldArea");
		assertEquals(3210, area.get("minX")); assertEquals(3220, area.get("minY"));
		assertEquals(3214, area.get("maxX")); assertEquals(3222, area.get("maxY"));
		assertEquals(2, area.get("plane")); assertEquals(1, sample.get("size"));
		assertEquals(50000, sample.get("localX"));
		try { area.put("maxX", 0); fail("Area witness must be immutable"); }
		catch (UnsupportedOperationException expected) { }
	}

	@Test
	public void missingAreaNeverUsesAvailableLocationAndSizeAsDefaultBounds()
	{
		Map<String, Object> sample = capture(null, false, false);
		assertEquals(Boolean.FALSE, sample.get("worldAreaKnown"));
		assertNull(sample.get("worldArea"));
		assertEquals("UNAVAILABLE", sample.get("worldAreaEvidence"));
		assertFalse(sample.get("observationIssues") instanceof Map
			&& ((Map<?, ?>) sample.get("observationIssues")).containsKey("worldArea"));
		assertEquals(Boolean.TRUE, sample.get("worldPositionKnown"));
		assertEquals(1, sample.get("size"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void areaGetterFailureDoesNotEraseIndependentNameAnimationOrCoordinates()
	{
		Map<String, Object> sample = capture(null, true, false);
		assertEquals(Boolean.FALSE, sample.get("worldAreaKnown"));
		assertEquals("Held-out actor", sample.get("name"));
		assertEquals(999964, sample.get("animationId"));
		assertEquals(3210, sample.get("worldX"));
		assertEquals("IllegalStateException", ((Map<String, String>) sample.get("observationIssues")).get("worldArea"));
	}

	@Test
	public void unknownNativeViewDoesNotTurnIntoWorldViewZero()
	{
		Map<String, Object> sample = capture(new WorldArea(3210, 3220, 5, 3, 2), false, true);
		assertEquals(Boolean.TRUE, sample.get("worldAreaKnown"));
		assertEquals(Boolean.FALSE, sample.get("worldAreaWorldViewKnown"));
		assertNull(sample.get("worldAreaWorldViewId"));
		assertNotNull(sample.get("worldArea"));
	}

	@Test
	public void invalidDimensionsOrIntegerOverflowDoNotExportPlausibleBounds()
	{
		for (WorldArea area : new WorldArea[] { new WorldArea(3210, 3220, 0, 3, 2),
			new WorldArea(Integer.MAX_VALUE, 3220, 2, 3, 2) })
		{
			Map<String, Object> sample = capture(area, false, false);
			assertEquals(Boolean.FALSE, sample.get("worldAreaKnown"));
			assertNull(sample.get("worldArea"));
		}
	}

	private static Map<String, Object> capture(WorldArea area, boolean areaFails, boolean viewFails)
	{
		WorldView view = stub(WorldView.class, (name, args) -> name.equals("getId") ? 7 : null);
		NPCComposition definition = stub(NPCComposition.class, (name, args) -> name.equals("getId") ? 999964
			: name.equals("getName") ? "Held-out actor" : name.equals("getSize") ? 1 : null);
		NPC actor = stub(NPC.class, (name, args) ->
		{
			if (name.equals("getWorldArea"))
			{
				if (areaFails) throw new IllegalStateException("unavailable");
				return area;
			}
			if (name.equals("getWorldView"))
			{
				if (viewFails) throw new IllegalStateException("unavailable");
				return view;
			}
			switch (name)
			{
				case "getWorldLocation": return new WorldPoint(3210, 3220, 2);
				case "getLocalLocation": return new LocalPoint(50000, 60000);
				case "getName": return "Held-out actor";
				case "getId": case "getAnimation": return 999964;
				case "getCombatLevel": return 42;
				case "getComposition": case "getTransformedComposition": return definition;
				default: return null;
			}
		});
		return ActorCallbackObservation.captureSample(actor, null, null, true, ignored -> "actor:review", null).data();
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, method, args) ->
		{
			Object value = read.apply(method.getName(), args);
			if (value != null) return value;
			if (method.getReturnType() == int.class) return 0;
			if (method.getReturnType() == boolean.class) return false;
			if (method.getReturnType() == long.class) return 0L;
			return null;
		});
	}
}
