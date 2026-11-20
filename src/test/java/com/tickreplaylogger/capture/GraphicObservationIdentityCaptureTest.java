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

import com.google.gson.Gson;
import com.tickreplaylogger.TickReplayLoggerPlugin;
import com.tickreplaylogger.model.world.HazardState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class GraphicObservationIdentityCaptureTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void snapshotAndCallbackShareActualGraphicIdentityAcrossRepeatedSamples() throws Exception
	{
		Fixture fixture = new Fixture();
		HazardState first = fixture.capture.captureHazards(fixture.client, fixture.player).get(0);
		HazardState second = fixture.capture.captureHazards(fixture.client, fixture.player).get(0);
		assertNotNull(first.getGraphicObservationId());
		assertEquals(first.getGraphicObservationId(), second.getGraphicObservationId());
		assertNull(first.getObjectKey());
		List<Map<String, Object>> contacts = new ArrayList<>();
		Method callback = TickReplayLoggerPlugin.class.getDeclaredMethod("observeHazardsOnTile", LocalPoint.class, int.class,
			List.class, boolean.class); callback.setAccessible(true);
		callback.invoke(fixture.plugin, fixture.tile, 0, contacts, true);
		assertEquals(1, contacts.size());
		assertEquals(first.getGraphicObservationId(), contacts.get(0).get("graphicObservationId"));
		assertEquals(first.getGraphicObservationId(), GSON.fromJson(GSON.toJson(first), HazardState.class).getGraphicObservationId());
	}

	@Test
	public void overlappingAndReplacementGraphicsKeepSeparateIdentitiesEvenWithIdenticalDefinitionsAndPositions() throws Exception
	{
		Fixture fixture = new Fixture();
		Long old = fixture.capture.captureHazards(fixture.client, fixture.player).get(0).getGraphicObservationId();
		fixture.graphics.add(fixture.graphic());
		List<HazardState> overlapping = fixture.capture.captureHazards(fixture.client, fixture.player);
		assertEquals(2, overlapping.size());
		assertEquals(old, overlapping.get(0).getGraphicObservationId());
		assertNotEquals(old, overlapping.get(1).getGraphicObservationId());
		fixture.graphics.remove(0);
		WorldCaptureService anotherScopeService = new WorldCaptureService(fixture.identities);
		anotherScopeService.setDamageMap(fixture.map);
		assertEquals(overlapping.get(1).getGraphicObservationId(),
			anotherScopeService.captureHazards(fixture.client, fixture.player).get(0).getGraphicObservationId());
		fixture.graphics.clear(); fixture.graphics.add(fixture.graphic());
		assertNotEquals(old, fixture.capture.captureHazards(fixture.client, fixture.player).get(0).getGraphicObservationId());
	}

	private static final class Fixture
	{
		final TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
		final LocalPoint tile = new LocalPoint(192, 192);
		final WorldView[] view = { null };
		final List<GraphicsObject> graphics = new ArrayList<>();
		final ObjectHazards.ObservationIdentities identities;
		final WorldCaptureService capture;
		final DamageMap map = DamageMap.load(GSON);
		final Player player;
		final Client client;

		Fixture() throws Exception
		{
			Field ids = TickReplayLoggerPlugin.class.getDeclaredField("surfaceObservationIds"); ids.setAccessible(true);
			identities = (ObjectHazards.ObservationIdentities) ids.get(plugin);
			net.runelite.api.Deque<GraphicsObject> deque = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? graphics.iterator() : null);
			view[0] = stub(WorldView.class, (n, a) -> n.equals("getGraphicsObjects") ? deque
				: n.equals("getBaseX") || n.equals("getBaseY") ? 100 : null);
			player = stub(Player.class, (n, a) -> n.equals("getWorldView") ? view[0] : n.equals("getLocalLocation") ? tile : null);
			client = stub(Client.class, (n, a) -> n.equals("getLocalPlayer") ? player
				: n.equals("getTopLevelWorldView") || n.equals("getWorldView") ? view[0] : n.equals("getGameCycle") ? 100 : null);
			graphics.add(graphic());
			capture = new WorldCaptureService(identities); capture.setDamageMap(map);
			set(plugin, "client", client); set(plugin, "damageMap", map);
		}

		GraphicsObject graphic()
		{
			return stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? 999963
				: n.equals("getLocation") ? tile : n.equals("getWorldView") ? view[0] : n.equals("getStartCycle") ? 90 : null);
		}
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) ->
		{
			Object value = read.apply(m.getName(), a);
			if (value != null) return value;
			if (m.getReturnType() == int.class) return 0;
			if (m.getReturnType() == long.class) return 0L;
			if (m.getReturnType() == boolean.class) return false;
			return null;
		});
	}
}
