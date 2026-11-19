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
import com.tickreplaylogger.model.world.ContextBlock;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterContextTest
{
	private final CombatCatalog catalog = CombatCatalog.load(new Gson());

	@Test
	public void childInstanceMissingTemplateCannotFallBackToTopLevelRegionAssumptions()
	{
		WorldView view = stub(WorldView.class, (name, args) -> name.equals("isInstance") ? true : null);
		Player player = stub(Player.class, (name, args) -> name.equals("getWorldView") ? view
			: name.equals("getWorldLocation") ? new WorldPoint(3904, 1280, 0) : null);
		Client client = stub(Client.class, (name, args) -> name.equals("getLocalPlayer") ? player
			: name.equals("getWorldType") ? EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN) : null);
		ContextBlock context = EncounterContextCapture.capture(client, player, catalog, null);
		assertTrue(context.isInInstance());
		assertNull(context.getTemplateRegionId());
		assertTrue(context.getWorldTypes().contains("SEASONAL"));
		assertTrue(context.getWorldTypes().contains("DEADMAN"));
		assertNull(context.getChallengeMode()); // Seasonal alone does not identify a particular event.
	}

	@Test
	public void colosseumTemplateRegionWithoutPositiveInstanceObservationDoesNotEstablishPresence()
	{
		ContextBlock context = capture(7216, new HashMap<>());
		assertEquals(Integer.valueOf(7216), context.getTemplateRegionId());
		assertFalse(context.isInInstance());
		assertNull(context.getActivity());
	}

	@Test
	public void aConfiguredRaidLevelOutsideToADoesNotMeanThePlayerIsInARaid()
	{
		Map<Integer, Integer> vars = new HashMap<>();
		vars.put(14380, 300);
		ContextBlock context = capture(12850, vars);
		assertNull(context.getActivity());
		assertNull(context.getRaidLevel());
		assertEquals(Integer.valueOf(300), context.getObservedVariables().get("TOA_CLIENT_RAID_LEVEL"));
	}

	@Test
	public void entryModeZeroAndDeadPartySlotsRemainActualObservedRaidContext()
	{
		Map<Integer, Integer> vars = new HashMap<>();
		vars.put(14380, 0);
		vars.put(14347, 30);
		vars.put(14348, 15);
		ContextBlock context = capture(15700, vars);
		assertEquals("toa", context.getActivity());
		assertEquals("Zebak", context.getRoom());
		assertEquals(Integer.valueOf(0), context.getRaidLevel());
		assertEquals(Integer.valueOf(3), context.getTeamSize());
	}

	@Test
	public void warmthIsASeparateObservedResourceAndZeroIsNotMissing()
	{
		Map<Integer, Integer> vars = new HashMap<>();
		vars.put(11434, 910);
		ContextBlock context = capture(6462, vars);
		assertEquals("wintertodt", context.getActivity());
		assertEquals(910, context.getResources().get(0).getRawValue());
		assertEquals(10, context.getResources().get(0).getDivisor());
		vars.put(11434, 0);
		assertEquals(0, capture(6462, vars).getResources().get(0).getRawValue());
		assertTrue(capture(12850, vars).getResources().isEmpty());
	}

	@Test
	public void chambersActualPartySizeAndScaleAreNotConflated()
	{
		Map<Integer, Integer> vars = new HashMap<>();
		vars.put(5432, 1);
		vars.put(5424, 3);
		vars.put(9540, 5);
		vars.put(6385, 1);
		ContextBlock context = capture(12850, vars);
		assertEquals("cox", context.getActivity());
		assertEquals(Integer.valueOf(3), context.getTeamSize());
		assertEquals(Integer.valueOf(5), context.getScaledTeamSize());
		assertEquals(Integer.valueOf(1), context.getObservedVariables().get("RAIDS_CHALLENGE_MODE"));
	}

	private ContextBlock capture(int region, Map<Integer, Integer> values)
	{
		Player player = stub(Player.class,
			(n, a) -> n.equals("getWorldLocation")
				? new WorldPoint((region >> 8) * 64 + 32, (region & 255) * 64 + 32, 0)
				: null);
		Client client = stub(Client.class, (n, a) -> n.equals("getVarbitValue") ? values.getOrDefault((Integer) a[0], 0)
			: n.equals("getLocalPlayer") ? player : null);
		return EncounterContextCapture.capture(client, player, catalog, null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, m, args) ->
		{
			if (m.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (m.getName().equals("equals"))
				return proxy == args[0];
			Object value = values.apply(m.getName(), args);
			if (value != null)
				return value;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return 0;
			return null;
		});
	}
}
