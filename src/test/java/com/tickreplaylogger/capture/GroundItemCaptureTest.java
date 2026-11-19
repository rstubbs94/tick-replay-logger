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
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import java.nio.file.*;

public class GroundItemCaptureTest
{
	int quantity = 3;
	boolean failQuantity, failList;
	List<TileItem> items = new ArrayList<>();
	Tile[][][] grid;
	TileItem item = stub(TileItem.class, (n, a) ->
	{
		switch (n)
		{
		case "getId":
			return 995;
		case "getQuantity":
			if (failQuantity)
				throw new IllegalStateException();
			return quantity;
		case "getOwnership":
			return 1;
		case "getVisibleTime":
			return 150;
		case "getDespawnTime":
			return 250;
		default:
			return null;
		}
	});
	Tile tile = stub(Tile.class, (n, a) ->
	{
		switch (n)
		{
		case "getGroundItems":
			if (failList)
				throw new IllegalStateException();
			return items;
		case "getLocalLocation":
			return new LocalPoint(192, 192, WorldView.TOPLEVEL);
		case "getWorldLocation":
			return new WorldPoint(3201, 3201, 0);
		default:
			return null;
		}
	});
	Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? grid : null);
	WorldView view = stub(WorldView.class, (n, a) ->
	{
		switch (n)
		{
		case "getId":
			return WorldView.TOPLEVEL;
		case "getScene":
			return scene;
		case "getSizeX":
		case "getSizeY":
			return 3;
		case "getBaseX":
		case "getBaseY":
			return 3200;
		default:
			return null;
		}
	});
	Player player = stub(Player.class, (n, a) ->
	{
		switch (n)
		{
		case "getWorldView":
			return view;
		case "getWorldLocation":
			return new WorldPoint(3200, 3200, 0);
		case "getLocalLocation":
			return new LocalPoint(64, 64, WorldView.TOPLEVEL);
		default:
			return null;
		}
	});
	Client client = stub(Client.class, (n, a) ->
	{
		switch (n)
		{
		case "getTopLevelWorldView":
		case "getWorldView":
			return view;
		case "getLocalPlayer":
			return player;
		case "getScene":
			return scene;
		case "getTickCount":
			return 100;
		case "getBoostedSkillLevel":
		case "getRealSkillLevel":
			return 99;
		default:
			return null;
		}
	});

	void setup()
	{
		grid = new Tile[1][3][3];
		grid[0][1][1] = tile;
		items.add(item);
	}

	@Test
	public void identitiesQuantitiesUnavailableRecoveryAndSelectedWindowSurviveExport() throws Exception
	{
		setup();
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		recorder.putScene(service.captureScene(client, "items", 1, false));
		WorldSnapshot a = service.captureTick(client, 1, "items", Collections.emptyList(), null, false, false);
		recorder.addSnapshot(a);
		assertEquals(1, a.getObservedGroundItems().size());
		assertEquals(100, a.getApiTickCount().intValue());
		WorldSnapshot same = service.captureTick(client, 2, "items", Collections.emptyList(), null, false, false);
		assertSame(a.getObservedGroundItems(), same.getObservedGroundItems());
		quantity = 7;
		WorldSnapshot b = service.captureTick(client, 2, "items", Collections.emptyList(), null, false, false);
		recorder.addSnapshot(b);
		assertEquals(3, a.getObservedGroundItems().get(0).getQuantity().intValue());
		assertEquals(a.getObservedGroundItems().get(0).getItemKey(), b.getObservedGroundItems().get(0).getItemKey());
		Tile[][][] original = grid;
		grid = null;
		WorldSnapshot missing = service.captureTick(client, 3, "items", Collections.emptyList(), null, false, false);
		recorder.addSnapshot(missing);
		assertNull(missing.getObservedGroundItems());
		grid = original;
		items.clear();
		recorder.addSnapshot(service.captureTick(client, 4, "items", Collections.emptyList(), null, false, false));
		items.add(item);
		recorder.addSnapshot(service.captureTick(client, 5, "items", Collections.emptyList(), null, false, false));
		DeathRecordingV2 exported = recorder.buildExport(DeathRecordingV2.Meta.builder().build(), null);
		assertTrue(exported.getTicks().get(0).getGroundItemBaseline());
		assertEquals(7, exported.getTicks().get(1).getGroundItemChanges().get(0).getQuantity().intValue());
		assertTrue(exported.getTicks().get(3).getGroundItemBaseline());
		assertTrue(exported.getTicks().get(3).getGroundItemChanges().isEmpty());
		List<WorldSnapshot> selected = WorldStateExport.compact(Arrays.asList(b, missing));
		assertTrue(selected.get(0).getGroundItemBaseline());
		assertEquals(7, selected.get(0).getGroundItemChanges().get(0).getQuantity().intValue());
		com.google.gson.JsonObject fixture = new Gson().toJsonTree(exported).getAsJsonObject();
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		Path path = Paths.get("build/test-fixtures/ground-items.json");
		Files.createDirectories(path.getParent());
		Files.write(path, new Gson().toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void duplicateMembershipAndOptionalFailuresRetainTheObservedItem()
	{
		setup();
		items.add(item);
		failQuantity = true;
		GroundItemTracker tracker = new GroundItemTracker();
		List<GroundItemState> out = tracker.snapshot(client, "a", view, 0);
		assertEquals(1, out.size());
		assertEquals(995, out.get(0).getId().intValue());
		assertNull(out.get(0).getQuantity());
		assertFalse(out.get(0).getCaptureIssues().isEmpty());
		assertEquals(false, tracker.coverage().get("complete"));
		failQuantity = false;
		assertEquals(3, tracker.snapshot(client, "a", view, 0).get(0).getQuantity().intValue());
		assertEquals(true, tracker.coverage().get("complete"));
	}

	@Test
	public void unavailablePlaneIsNotAnEmptyMembershipAndBridgeSamplesStayOnTheirReportedPlane()
	{
		setup();
		GroundItemTracker tracker = new GroundItemTracker();
		assertNull(tracker.snapshot(client, "a", view, 1));
		assertEquals(false, tracker.coverage().get("complete"));
		assertTrue(WorldViews.containsTile(view, tile));
		Tile stale = stub(Tile.class,
			(n, a) -> n.equals("getLocalLocation") ? new LocalPoint(192, 192, WorldView.TOPLEVEL) : null);
		assertFalse(WorldViews.containsTile(view, stale));
	}

	@Test
	public void linkedBridgeSamplesUseReportedPlaneAndNeverLeakIntoAnotherView()
	{
		setup();
		Tile bridge = stub(Tile.class,
			(n, a) -> n.equals("getPlane") ? 1
				: n.equals("getGroundItems") ? items
					: n.equals("getLocalLocation") ? new LocalPoint(192, 192, WorldView.TOPLEVEL) : null);
		Tile top = stub(Tile.class,
			(n, a) -> n.equals("getBridge") ? bridge : n.equals("getGroundItems") ? Collections.emptyList() : null);
		grid[0][1][1] = top;
		GroundItemTracker tracker = new GroundItemTracker();
		assertTrue(tracker.snapshot(client, "a", view, 0).isEmpty());
		assertEquals(1, tracker.snapshot(client, "b", view, 1).size());
		assertEquals(false, tracker.coverage().get("complete"));
		assertTrue(WorldViews.containsTile(view, bridge));
		WorldView child = stub(WorldView.class, (n, a) -> n.equals("getId") ? 7 : n.equals("getScene") ? scene : null);
		assertFalse(WorldViews.containsTile(child, bridge));
	}

	@Test
	public void populatedUnreadableLayersAreNotEmptyAndFailureRecoveryKeepsNeighbours()
	{
		setup();
		Tile unreadable = stub(Tile.class, (n, a) -> n.equals("getGroundItems") ? null
			: n.equals("getItemLayer") ? stub(ItemLayer.class, (k, b) -> null) : null);
		grid[0][2][2] = unreadable;
		GroundItemTracker tracker = new GroundItemTracker();
		assertEquals(1, tracker.snapshot(client, "a", view, 0).size());
		assertEquals(false, tracker.coverage().get("complete"));
		grid[0][2][2] = null;
		assertEquals(true, tracker.snapshot(client, "a", view, 0).size() == 1
			&& Boolean.TRUE.equals(tracker.coverage().get("complete")));
	}

	@Test
	public void callbackIdentitySurvivesTheFirstSnapshotAndDespawnStartsANewLifetime()
	{
		setup();
		GroundItemTracker tracker = new GroundItemTracker();
		tracker.beginScope("a");
		long first = tracker.observation(tile, item, WorldView.TOPLEVEL).getItemKey();
		assertEquals(first, tracker.snapshot(client, "a", view, 0).get(0).getItemKey().longValue());
		tracker.despawned(item);
		assertNotEquals(first, tracker.observation(tile, item, WorldView.TOPLEVEL).getItemKey().longValue());
	}

	@SuppressWarnings("unchecked")
	static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			String n = m.getName();
			if (n.equals("hashCode"))
				return System.identityHashCode(p);
			if (n.equals("equals"))
				return p == a[0];
			Object v = values.apply(n, a);
			if (v != null)
				return v;
			Class<?> t = m.getReturnType();
			if (t == int.class)
				return 0;
			if (t == boolean.class)
				return false;
			if (t == long.class)
				return 0L;
			return null;
		});
	}
}
