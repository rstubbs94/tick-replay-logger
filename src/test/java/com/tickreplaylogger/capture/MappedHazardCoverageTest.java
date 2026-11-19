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
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic API routing/coverage tests, not proof that a floor definition renders in every object kind. */
public class MappedHazardCoverageTest
{
	@Test
	public void everySceneObjectKindIsSampledWithExplicitRadiusAndNoInventedWallFootprint()
	{
		Fixture f = new Fixture();
		f.allKinds();
		WorldSnapshot frame = f.capture(1);
		Set<String> kinds = new HashSet<>();
		for (HazardState h : frame.getHazards())
			kinds.add(h.getKind());
		assertEquals(Set.of("gameObject", "groundObject", "wallObject", "decorativeObject"), kinds);
		Map<String, Object> scope = frame.getHazardCoverage();
		assertEquals("LOCAL_RADIUS", scope.get("mappedObjectsScope"));
		assertEquals(12, scope.get("radiusTiles"));
		assertEquals(50, scope.get("centerSceneX"));
		assertEquals(625, scope.get("visitedTileSlots"));
		assertEquals(1, scope.get("exposedTiles"));
		assertEquals("available", scope.get("mappedObjectsStatus"));
		assertEquals(Boolean.TRUE, scope.get("objectScanCompleted"));
		for (HazardState h : frame.getHazards())
			if (h.getKind().equals("wallObject") || h.getKind().equals("decorativeObject"))
			{
				assertNull(h.getMinSceneX());
				assertNull(h.getMaxSceneY());
			}
		f.local = null;
		WorldSnapshot skipped = f.capture(2);
		assertEquals("skipped", skipped.getHazardCoverage().get("mappedObjectsStatus"));
		assertEquals("LOCAL_POSITION_UNAVAILABLE", skipped.getHazardCoverage().get("mappedObjectsUnavailableReason"));
		assertEquals("partial", skipped.getCaptureCoverage().get("graphicsAndMappedHazards"));
		assertEquals("available", scope.get("mappedObjectsStatus")); // prior frame metadata is immutable
	}

	@Test
	public void aBrokenObjectSlotDoesNotEraseOtherKindsOnTheSameTile()
	{
		Fixture f = new Fixture();
		f.allKinds();
		f.failGround = true;
		WorldSnapshot frame = f.capture(1);
		assertEquals(3, frame.getHazards().size());
		assertEquals("partial", frame.getHazardCoverage().get("mappedObjectsStatus"));
		assertEquals(1, frame.getHazardCoverage().get("mappedObjectReadFailures"));
		assertEquals(Boolean.TRUE, frame.getHazardCoverage().get("objectScanCompleted"));
	}

	@Test
	public void anUnavailableGraphicsCollectionDoesNotEraseMappedObjectsAndUnplacedGraphicsKeepTheirId()
	{
		Fixture f = new Fixture();
		f.allKinds();
		f.failGraphics = true;
		WorldSnapshot failed = f.capture(1);
		assertEquals(4, failed.getHazards().size());
		assertEquals("unavailable", failed.getHazardCoverage().get("graphicsStatus"));
		assertEquals("available", failed.getHazardCoverage().get("mappedObjectsStatus"));
		f.failGraphics = false;
		f.graphics.add(stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? 1579 : null));
		WorldSnapshot unplaced = f.capture(2);
		HazardState graphic = unplaced.getHazards().stream().filter(h -> h.getKind().equals("graphicsObject"))
			.findFirst().get();
		assertEquals(1579, graphic.getId());
		assertEquals(Boolean.FALSE, graphic.getLocalPositionKnown());
		assertEquals(1, unplaced.getHazardCoverage().get("unpositionedGraphics"));
		assertEquals("partial", unplaced.getHazardCoverage().get("graphicsStatus"));
	}

	private static final class Fixture
	{
		final Tile[][][] tiles = new Tile[1][104][104];
		final List<GraphicsObject> graphics = new ArrayList<>();
		LocalPoint local = new LocalPoint(6464, 6464);
		boolean failGround, failGraphics;
		final Scene scene;
		final WorldView view;
		final Player player;
		final Client client;
		final WorldCaptureService capture = new WorldCaptureService();

		Fixture()
		{
			scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
			net.runelite.api.Deque<GraphicsObject> deque = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? graphics.iterator() : null);
			view = stub(WorldView.class, (n, a) ->
			{
				if (n.equals("getGraphicsObjects"))
				{
					if (failGraphics)
						throw new IllegalStateException("collection unavailable");
					return deque;
				}
				if (n.equals("getScene"))
					return scene;
				if (n.equals("getBaseX") || n.equals("getBaseY"))
					return 3200;
				if (n.equals("getSizeX") || n.equals("getSizeY"))
					return 104;
				return null;
			});
			player = stub(Player.class,
				(n, a) -> n.equals("getLocalLocation") ? local : n.equals("getWorldView") ? view : null);
			client = stub(Client.class,
				(n, a) -> n.equals("getLocalPlayer") ? player
					: n.equals("getWorldView") || n.equals("getTopLevelWorldView") ? view
						: n.equals("getScene") ? scene : null);
			capture.setDamageMap(DamageMap.load(new Gson()));
		}

		void allKinds()
		{
			GroundObject ground = object(GroundObject.class);
			WallObject wall = object(WallObject.class);
			DecorativeObject decorative = object(DecorativeObject.class);
			GameObject game = object(GameObject.class);
			tiles[0][50][50] = stub(Tile.class, (n, a) ->
			{
				if (n.equals("getGroundObject"))
				{
					if (failGround)
						throw new IllegalStateException("slot unavailable");
					return ground;
				}
				return n.equals("getWallObject") ? wall
					: n.equals("getDecorativeObject") ? decorative : n.equals("getGameObjects") ? new GameObject[]
				{ game } : null;
			});
		}

		<T> T object(Class<T> kind)
		{
			return stub(kind, (n, a) -> n.equals("getId") ? 36048 : n.equals("getLocalLocation") ? local : null);
		}

		WorldSnapshot capture(int tick)
		{
			return capture.captureTick(client, tick, "room", Collections.emptyList(), null, false, false, false);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> kind, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]
		{ kind }, (p, m, a) ->
		{
			if (m.getName().equals("hashCode"))
				return System.identityHashCode(p);
			if (m.getName().equals("equals"))
				return p == a[0];
			Object v = read.apply(m.getName(), a);
			if (v != null)
				return v;
			Class<?> result = m.getReturnType();
			if (result == int.class)
				return 0;
			if (result == boolean.class)
				return false;
			if (result == long.class)
				return 0L;
			if (result == float.class)
				return 0f;
			if (result == double.class)
				return 0d;
			return null;
		});
	}
}
