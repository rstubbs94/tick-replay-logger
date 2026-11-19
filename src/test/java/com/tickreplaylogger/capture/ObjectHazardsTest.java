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
import com.tickreplaylogger.model.world.HazardState;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectHazardsTest
{
	private final DamageMap map = DamageMap.load(new Gson());
	private int effectiveId = 36047;
	private boolean missingTransform;
	private final LocalPoint location = new LocalPoint(6464, 6464);

	private ObjectComposition definition()
	{
		return stub(ObjectComposition.class, (name, args) ->
		{
			if (name.equals("getImpostorIds"))
				return new int[]
				{ 36046, 36047, 36048 };
			if (name.equals("getImpostor"))
				return missingTransform ? null
					: stub(ObjectComposition.class, (n, a) -> n.equals("getId") ? effectiveId : null);
			return null;
		});
	}

	private GroundObject floor()
	{
		return stub(GroundObject.class,
			(name, args) -> name.equals("getId") ? 36046 : name.equals("getLocalLocation") ? location : null);
	}

	private Client client(Scene scene)
	{
		Deque<GraphicsObject> graphics = stub(Deque.class,
			(name, args) -> name.equals("iterator") ? Collections.emptyIterator() : null);
		return stub(Client.class, (name, args) ->
		{
			switch (name)
			{
			case "getWorldView":
			case "getTopLevelWorldView":
				return stub(WorldView.class, (n, a) -> n.equals("getBaseX") || n.equals("getBaseY") ? 3200
					: n.equals("getScene") ? scene : n.equals("getGraphicsObjects") ? graphics : null);
			case "getObjectDefinition":
				return definition();
			case "getScene":
				return scene;
			case "getGraphicsObjects":
				return graphics;
			case "getBaseX":
			case "getBaseY":
				return 3200;
			default:
				return null;
			}
		});
	}

	@Test
	public void resolvesObservedFloorStatesAndNeverTreatsWarningsAsDamaging()
	{
		Client client = client(null);
		for (int id : new int[]
		{ 36046, 36047, 36048 })
		{
			effectiveId = id;
			ObjectHazards.Resolved r = ObjectHazards.resolve(client, floor(), map);
			assertEquals(id, r.getId());
			assertEquals(36046, r.getBaseId());
			assertEquals("Corrupted Hunllef floor", r.getName());
			assertEquals("TRANSFORMED_OBJECT_DEFINITION", r.getEvidence());
			assertEquals(id == 36048, r.isDamageCandidate());
		}
		missingTransform = true;
		ObjectHazards.Resolved unknown = ObjectHazards.resolve(client, floor(), map);
		assertEquals("unknown", unknown.getState());
		assertNull(unknown.getDamaging());
		assertFalse(unknown.isDamageCandidate());
	}

	@Test
	public void graphicAndObjectNamespacesCannotBorrowEachOthersNames()
	{
		assertEquals("VFX_DRAKAN_BLOOD_COMBO_LEFT_BACK", map.hazardName("graphicsObject", 3904));
		assertNull(map.hazardName("gameObject", 3904));
		assertNull(map.hazardName("graphicsObject", 36048));
		assertEquals("PRIF_GAUNTLET_FLOOR_TILE_01_HIT_HM", map.hazardName("groundObject", 36048));
		assertEquals(map.hazardName("groundObject", 36048), map.hazardName("wallObject", 36048));
		assertEquals(map.hazardName("groundObject", 36048), map.hazardName("decorativeObject", 36048));
		assertNull(map.hazardName("wallObject", 3904));
		assertEquals("active", map.objectHazardRule(36151).getState());
		assertFalse(map.objectHazardRule(36150).getDamaging());
	}

	@Test
	public void capturesTheEffectiveDefinitionAndExactGroundTileBoundsEachTick()
	{
		Tile[][][] tiles = new Tile[1][104][104];
		tiles[0][50][50] = stub(Tile.class, (name, args) -> name.equals("getGroundObject") ? floor() : null);
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		Client client = client(scene);
		Player player = stub(Player.class, (name, args) -> name.equals("getLocalLocation") ? location : null);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		List<HazardState> warning = capture.captureHazards(client, player);
		assertEquals(1, warning.size());
		assertEquals("warning", warning.get(0).getState());
		assertEquals(Boolean.FALSE, warning.get(0).getDamaging());
		assertEquals(Integer.valueOf(50), warning.get(0).getMinSceneX());
		assertEquals(Integer.valueOf(50), warning.get(0).getMaxSceneY());
		effectiveId = 36048;
		HazardState active = capture.captureHazards(client, player).get(0);
		assertEquals(36048, active.getId());
		assertEquals(Integer.valueOf(36046), active.getBaseId());
		assertEquals("active", active.getState());
		assertEquals(Boolean.TRUE, active.getDamaging());
		assertTrue(new Gson().toJson(active).contains("TRANSFORMED_OBJECT_DEFINITION"));
		assertEquals("warning", warning.get(0).getState()); // preceding frame was not mutated
	}

	@Test
	public void brokenObjectAndWorldConversionCannotEraseNeighbouringHazards()
	{
		Tile[][][] tiles = new Tile[1][104][];
		tiles[0][50] = new Tile[104];
		GroundObject broken = stub(GroundObject.class, (name, args) ->
		{
			if (name.equals("getId"))
				throw new IllegalStateException("unavailable definition");
			return name.equals("getLocalLocation") ? location : null;
		});
		GameObject placed = stub(GameObject.class,
			(name, args) -> name.equals("getId") ? 36048 : name.equals("getLocalLocation") ? location : null);
		GameObject unplaced = stub(GameObject.class, (name, args) -> name.equals("getId") ? 36048 : null);
		tiles[0][50][50] = stub(Tile.class,
			(name, args) -> name.equals("getGroundObject") ? broken : name.equals("getGameObjects") ? new GameObject[]
			{ placed, unplaced } : null);
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		Deque<GraphicsObject> graphics = stub(Deque.class,
			(name, args) -> name.equals("iterator") ? Collections.emptyIterator() : null);
		WorldView view = stub(WorldView.class, (name, args) ->
		{
			if (name.equals("getBaseX"))
				throw new IllegalStateException("world coordinates unavailable");
			return name.equals("getScene") ? scene : name.equals("getGraphicsObjects") ? graphics : null;
		});
		Player player = stub(Player.class,
			(name, args) -> name.equals("getLocalLocation") ? location : name.equals("getWorldView") ? view : null);
		Client client = stub(Client.class,
			(name, args) -> name.equals("getLocalPlayer") ? player : name.equals("getTopLevelWorldView") ? view : null);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		com.tickreplaylogger.model.world.WorldSnapshot frame = capture.captureTick(client, 1, "room",
			Collections.emptyList(), null, false, false, false);
		assertEquals(2, frame.getHazards().size());
		HazardState known = frame.getHazards().get(0), unknown = frame.getHazards().get(1);
		assertEquals(Boolean.TRUE, known.getLocalPositionKnown());
		assertEquals(Boolean.FALSE, known.getWorldPositionKnown());
		assertEquals(location.getX(), known.getLocalX());
		assertEquals(Boolean.FALSE, unknown.getLocalPositionKnown());
		assertEquals(Boolean.FALSE, unknown.getWorldPositionKnown());
		assertNull(unknown.getMinSceneX());
		assertEquals(36048, unknown.getId());
		assertEquals("partial", frame.getCaptureCoverage().get("graphicsAndMappedHazards"));
		assertTrue(
			frame.getCaptureIssues().stream().anyMatch(issue -> issue.contains("world coordinates unavailable")));
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (method.getName().equals("equals"))
				return proxy == args[0];
			Object value = values.apply(method.getName(), args);
			if (value != null)
				return value;
			Class<?> result = method.getReturnType();
			if (result == int.class)
				return 0;
			if (result == boolean.class)
				return false;
			if (result == long.class)
				return 0L;
			return null;
		});
	}
}
