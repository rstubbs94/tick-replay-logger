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

import com.tickreplaylogger.model.DamageEvent;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Bounded controls for large-actor origins and independently observed client cycles. */
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

	@Test
	public void observedCyclesCanMatchDespiteRoundedArrivalTickDisagreement() throws Exception
	{
		// Same relationship as the observed hit: cycle4750, visual end4773,
		// event-derived arrival102 while the captured hit belongs to tick100.
		assertTrue(matches(4750, 4773, 102, 7L, 1));
	}

	@Test
	public void closeTickEstimateCannotOverrideIncompatibleObservedCycles() throws Exception
	{
		assertFalse(matches(4700, 4773, 100, 7L, 1));
	}

	@Test
	public void missingCycleRetainsTheExistingTickWindow() throws Exception
	{
		assertFalse(matches(null, 4773, 102, 7L, 1));
		assertTrue(matches(null, 4773, 101, 7L, 1));
	}

	@Test
	public void matchingCyclesDoNotCrossEpochOrPlane() throws Exception
	{
		assertFalse(matches(4750, 4773, 102, 8L, 1));
		assertFalse(matches(4750, 4773, 102, 7L, 0));
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

	private static boolean matches(Integer observedCycle, int endCycle, int estimatedArrival, long hitEpoch,
		int hitPlane) throws Exception
	{
		TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
		set(plugin, "currentGameTick", 100);
		Class<?> shot = Class.forName(TickReplayLoggerPlugin.class.getName() + "$PendingProjectile");
		Constructor<?> shotConstructor = shot.getDeclaredConstructors()[0];
		shotConstructor.setAccessible(true);
		Object projectile = shotConstructor.newInstance("Observed actor", 1708, 99, estimatedArrival, 2, false, false,
			-1, null, null, endCycle, null, null, false, 7L, 1, true, null, 5L);
		Class<?> hit = Class.forName(TickReplayLoggerPlugin.class.getName() + "$PendingHit");
		Constructor<?> hitConstructor = hit.getDeclaredConstructors()[0];
		hitConstructor.setAccessible(true);
		Object impact = hitConstructor.newInstance(43, HitsplatID.DAMAGE_ME, DamageEvent.DamageType.UNKNOWN,
			Collections.emptyList(), new WorldPoint(1000, 1000, hitPlane), null, Collections.emptyList(), 0, 0,
			observedCycle, 1L, null, null, null, Collections.emptyList(), hitEpoch, hitPlane, null);
		Method method = TickReplayLoggerPlugin.class.getDeclaredMethod("projectileCouldMatch", shot, hit);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, projectile, impact);
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
