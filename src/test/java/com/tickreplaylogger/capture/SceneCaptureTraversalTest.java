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

import com.tickreplaylogger.model.world.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneCaptureTraversalTest
{
	@Test
	public void discoveryAndCaptureShareOneCurrentCensusWithoutChangingPlaneEvidence()
	{
		Fixture f = new Fixture();
		WorldViews.Planes expected = WorldViews.planes(f.view, false);
		f.bridges = 0;
		f.slots = 0;
		f.tilePlaneReads = 0;
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		WorldViews.Planes actual = WorldViews.planes(f.view, false, shared);
		assertEquals(expected.planes, actual.planes);
		assertEquals(expected.issues, actual.issues);
		for (int plane : expected.planes)
			assertEquals(expected.coverage(plane), actual.coverage(plane));
		assertEquals(2, f.tilePlaneReads);
		for (int plane = 0; plane < 3; plane++)
		{
			new ObjectStateTracker().snapshot(f.client, "scene", f.view, plane, shared);
			new GroundItemTracker().snapshot(f.client, "scene", f.view, plane, shared);
		}
		assertEquals("Discovery must not trigger a second bridge census", 2, f.bridges);
		assertEquals("Discovery must not reread the four object slots", 8, f.slots);
		assertEquals("One tile-plane census plus the ground item's own current metadata read", 3, f.tilePlaneReads);
	}

	@Test
	public void sharedDiscoveryRetainsCurrentFailuresAndDoesNotCacheMissingMembership()
	{
		Fixture f = new Fixture();
		f.failObject = true;
		SceneCaptureTraversal partial = SceneCaptureTraversal.capture(f.client, f.view);
		WorldViews.Planes failed = WorldViews.planes(f.view, false, partial);
		assertTrue(failed.issues.contains("worldPlanes: ground object unavailable"));
		assertTrue(failed.planes.contains(1));
		assertEquals(1, new GroundItemTracker().snapshot(f.client, "scene", f.view, 1, partial).size());
		f.failObject = false;
		SceneCaptureTraversal recovered = SceneCaptureTraversal.capture(f.client, f.view);
		assertTrue(WorldViews.planes(f.view, false, recovered).issues.isEmpty());
		assertEquals(1, recovered.objects(2).size());
		f.arrays = new Tile[3][1][1];
		SceneCaptureTraversal empty = SceneCaptureTraversal.capture(f.client, f.view);
		assertTrue(empty.objects(2).isEmpty());
		assertFalse(((List<?>) WorldViews.planes(f.view, false, empty).coverage(2).get("discoveryEvidence"))
			.contains("OBJECT_REPORTED_PLANE"));
		f.arrays = null;
		WorldViews.Planes missing = WorldViews.planes(f.view, false, SceneCaptureTraversal.capture(f.client, f.view));
		assertTrue(missing.issues.contains("worldPlanes: scene arrays unavailable"));
	}

	@Test
	public void aSharedCensusCannotBorrowAnotherViewsMembershipOrPlaneDiscovery()
	{
		Fixture f = new Fixture();
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		WorldView foreign = NpcSnapshotCaptureTest.stub(WorldView.class, (n, a) -> null);
		try
		{
			WorldViews.planes(foreign, false, shared);
			fail("Foreign plane census accepted");
		}
		catch (IllegalArgumentException expected)
		{
		}
	}

	@Test
	public void sharedAndStandaloneChannelsKeepEveryReportedPlaneAndBridgeObservation()
	{
		Fixture f = new Fixture();
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		for (int plane = 0; plane < 3; plane++)
		{
			ObjectStateTracker expectedObjects = new ObjectStateTracker(), actualObjects = new ObjectStateTracker();
			GroundItemTracker expectedItems = new GroundItemTracker(), actualItems = new GroundItemTracker();
			assertEquals(expectedObjects.snapshot(f.client, "scene", f.view, plane),
				actualObjects.snapshot(f.client, "scene", f.view, plane, shared));
			assertEquals(expectedItems.snapshot(f.client, "scene", f.view, plane),
				actualItems.snapshot(f.client, "scene", f.view, plane, shared));
		}
		assertEquals(1, shared.objects(2).size()); // Object reported plane differs from its array/tile plane.
		assertEquals(1, shared.tiles(1).size()); // Bridge-only native tile.
		assertEquals(1, new ObjectStateTracker().snapshot(f.client, "scene", f.view, 2, shared).size());
		assertEquals(1, new GroundItemTracker().snapshot(f.client, "scene", f.view, 1, shared).size());
	}

	@Test
	public void sharedReadsSlotsAndBridgeOnceAcrossAllPlanesAndChannels()
	{
		Fixture f = new Fixture();
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		for (int plane = 0; plane < 3; plane++)
		{
			new ObjectStateTracker().snapshot(f.client, "scene", f.view, plane, shared);
			new GroundItemTracker().snapshot(f.client, "scene", f.view, plane, shared);
		}
		assertEquals("Two unique tiles, despite plane arrays and a bridge", 2, f.bridges);
		assertEquals("Each of four object-slot getters is read once per tile", 8, f.slots);
	}

	@Test
	public void objectSlotFailureDoesNotBlankTheIndependentGroundItemChannel()
	{
		Fixture f = new Fixture();
		f.failObject = true;
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		ObjectStateTracker objects = new ObjectStateTracker();
		GroundItemTracker items = new GroundItemTracker();
		assertNotNull(objects.snapshot(f.client, "scene", f.view, 0, shared));
		assertEquals(Boolean.FALSE, objects.coverage().get("complete"));
		assertEquals(1, items.snapshot(f.client, "scene", f.view, 1, shared).size());
		assertEquals(Boolean.TRUE, items.coverage().get("complete"));
	}

	@Test
	public void eachNewCaptureSeesQuantityConfigAndMembershipChangesWithoutCarryForward()
	{
		Fixture f = new Fixture();
		ObjectStateTracker objects = new ObjectStateTracker();
		GroundItemTracker items = new GroundItemTracker();
		SceneCaptureTraversal first = SceneCaptureTraversal.capture(f.client, f.view);
		ObservedObjectState before = objects.snapshot(f.client, "scene", f.view, 2, first).get(0);
		GroundItemState item = items.snapshot(f.client, "scene", f.view, 1, first).get(0);
		f.config = 7;
		f.quantity = 9;
		SceneCaptureTraversal second = SceneCaptureTraversal.capture(f.client, f.view);
		ObservedObjectState after = objects.snapshot(f.client, "scene", f.view, 2, second).get(0);
		GroundItemState changed = items.snapshot(f.client, "scene", f.view, 1, second).get(0);
		assertEquals(before.getObjectKey(), after.getObjectKey());
		assertEquals(Integer.valueOf(7), after.getConfig());
		assertEquals(Integer.valueOf(0), before.getConfig());
		assertEquals(item.getItemKey(), changed.getItemKey());
		assertEquals(Integer.valueOf(9), changed.getQuantity());
		assertEquals(Integer.valueOf(1), item.getQuantity());
		f.arrays = new Tile[3][1][1];
		SceneCaptureTraversal empty = SceneCaptureTraversal.capture(f.client, f.view);
		assertTrue(objects.snapshot(f.client, "scene", f.view, 2, empty).isEmpty());
		assertTrue(items.snapshot(f.client, "scene", f.view, 1, empty).isEmpty());
	}

	@Test
	public void missingSceneAndBridgeReadFailuresStayUnavailableOrPartial()
	{
		Fixture f = new Fixture();
		f.failBridge = true;
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		ObjectStateTracker objects = new ObjectStateTracker();
		GroundItemTracker items = new GroundItemTracker();
		assertNotNull(objects.snapshot(f.client, "scene", f.view, 2, shared));
		assertEquals(Boolean.FALSE, objects.coverage().get("complete"));
		assertNotNull(items.snapshot(f.client, "scene", f.view, 0, shared));
		assertEquals(Boolean.FALSE, items.coverage().get("complete"));
		f.arrays = null;
		SceneCaptureTraversal missing = SceneCaptureTraversal.capture(f.client, f.view);
		assertNull(objects.snapshot(f.client, "scene", f.view, 2, missing));
		assertNull(items.snapshot(f.client, "scene", f.view, 0, missing));
	}

	@Test
	public void aTraversalCannotBeBorrowedByAnotherNativeView()
	{
		Fixture f = new Fixture();
		SceneCaptureTraversal shared = SceneCaptureTraversal.capture(f.client, f.view);
		WorldView foreign = NpcSnapshotCaptureTest.stub(WorldView.class, (n, a) -> null);
		try
		{
			new ObjectStateTracker().snapshot(f.client, "scene", foreign, 0, shared);
			fail("Wrong native view accepted");
		}
		catch (IllegalArgumentException expected)
		{
		}
		try
		{
			new GroundItemTracker().snapshot(f.client, "scene", foreign, 0, shared);
			fail("Wrong native view accepted");
		}
		catch (IllegalArgumentException expected)
		{
		}
	}

	private static final class Fixture
	{
		int bridges, slots, tilePlaneReads, config, quantity = 1;
		boolean failObject, failBridge;
		Tile[][][] arrays = new Tile[3][1][1];
		final WorldView view = NpcSnapshotCaptureTest.stub(WorldView.class,
			(n, a) -> n.equals("getId") ? 0 : n.equals("getScene") ? scene() : null);
		final ObjectComposition definition = NpcSnapshotCaptureTest.stub(ObjectComposition.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return 999991;
			case "getName":
				return "Unmapped object";
			case "getVarbitId":
			case "getVarPlayerId":
				return -1;
			default:
				return null;
			}
		});
		final GroundObject object = NpcSnapshotCaptureTest.stub(GroundObject.class, (n, a) ->
		{
			switch (n)
			{
			case "getPlane":
				return 2;
			case "getWorldView":
				return view;
			case "getId":
				return 999991;
			case "getConfig":
				return config;
			default:
				return null;
			}
		});
		final TileItem item = NpcSnapshotCaptureTest.stub(TileItem.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return 995;
			case "getQuantity":
				return quantity;
			default:
				return null;
			}
		});
		final Tile bridge = tile(1), base = tile(0);
		final Client client = NpcSnapshotCaptureTest.stub(Client.class,
			(n, a) -> n.equals("getObjectDefinition") ? definition : n.equals("getScene") ? scene() : null);

		Fixture()
		{
			arrays[0][0][0] = base;
		}

		Scene scene()
		{
			return NpcSnapshotCaptureTest.stub(Scene.class, (n, a) -> n.equals("getTiles") ? arrays : null);
		}

		Tile tile(int plane)
		{
			return NpcSnapshotCaptureTest.stub(Tile.class, (n, a) ->
			{
				if (n.equals("getBridge"))
				{
					bridges++;
					if (failBridge)
						throw new IllegalStateException("unavailable bridge");
					return plane == 0 ? bridge : null;
				}
				if (Arrays.asList("getGroundObject", "getWallObject", "getDecorativeObject", "getGameObjects")
					.contains(n))
				{
					slots++;
					if (failObject && n.equals("getGroundObject"))
						throw new IllegalStateException("unavailable object slot");
					return plane == 0 && n.equals("getGroundObject") ? object : null;
				}
				if (n.equals("getPlane"))
				{
					tilePlaneReads++;
					return plane;
				}
				if (n.equals("getGroundItems"))
					return plane == 1 ? Collections.singletonList(item) : Collections.emptyList();
				if (n.equals("getLocalLocation"))
					return new LocalPoint(64, 64);
				if (n.equals("getWorldLocation"))
					return new WorldPoint(3200, 3200, plane);
				return null;
			});
		}
	}
}
