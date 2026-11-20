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

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original API footprint controls for weak projectile-origin proximity observations. */
public class ProjectileCorrelationWindowTest
{
	@Test
	public void originInsideLargeActorUsesFootprintRatherThanSouthwestAnchor() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		assertSame(actor, closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), null));
	}

	@Test
	public void overlappingCandidatesAndDistantOriginsRemainUnresolved() throws Exception
	{
		NPC first = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		NPC second = actor(new WorldPoint(1001, 1000, 1), new WorldArea(1001, 1000, 5, 5, 1));
		assertNull(closest(Arrays.asList(first, second), new WorldPoint(1002, 1000, 1), null));
		assertNull(closest(Collections.singletonList(first), new WorldPoint(1008, 1000, 1), null));
	}

	@Test
	public void targetAndOtherPlanesAreNotSourceCandidates() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), actor));
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 0), null));
	}

	@Test
	public void missingFootprintDoesNotInventALargeArea() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), null);
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), null));
	}

	private static NPC actor(WorldPoint point, WorldArea area)
	{
		return stub(NPC.class, (method, args) ->
		{
			switch (method)
			{
			case "getWorldLocation":
				return point;
			case "getWorldArea":
				return area;
			case "getName":
				return "Observed large actor";
			default:
				return null;
			}
		});
	}

	private static NPC closest(List<NPC> actors, WorldPoint origin, Actor target) throws Exception
	{
		TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
		set(plugin, "client", stub(Client.class, (method, args) -> method.equals("getNpcs") ? actors : null));
		Method method = TickReplayLoggerPlugin.class.getDeclaredMethod("findClosestCombatNpcToPoint", WorldPoint.class,
			Actor.class);
		method.setAccessible(true);
		return (NPC) method.invoke(plugin, origin, target);
	}

	private static void set(Object instance, String name, Object value) throws Exception
	{
		Field field = instance.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(instance, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, java.util.function.BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("equals"))
				return proxy == args[0];
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			Object value = read.apply(method.getName(), args);
			if (value != null || !method.getReturnType().isPrimitive())
				return value;
			if (method.getReturnType() == boolean.class)
				return false;
			if (method.getReturnType() == long.class)
				return 0L;
			if (method.getReturnType() == double.class)
				return 0.0;
			if (method.getReturnType() == float.class)
				return 0f;
			return 0;
		});
	}
}
