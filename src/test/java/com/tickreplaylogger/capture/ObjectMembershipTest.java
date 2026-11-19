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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Missing lifecycle callbacks are intentional; these tests exercise settled API membership. */
public class ObjectMembershipTest
{
	@Test
	public void actorTimingInvalidationCannotInventNewSceneObjectLifetimes()
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		ObjectStateTracker tracker = service.objects();
		List<ObservedObjectState> first = tracker.snapshot(f.client, "room", null, 0);
		long key = first.get(0).getObjectKey();
		service.invalidateActorTiming();
		List<ObservedObjectState> recovered = tracker.snapshot(f.client, "room", null, 0);
		assertEquals(key, recovered.get(0).getObjectKey());
		assertSame(first, recovered);
	}

	@Test
	public void missedSpawnsAndDespawnsAreReconciledWithoutMutatingEarlierSnapshots()
	{
		Fixture f = new Fixture();
		List<ObservedObjectState> first = f.snapshot();
		long original = first.get(0).getObjectKey();
		assertSame(first, f.snapshot());
		f.ground = null;
		assertEquals(1, f.snapshot().size()); // wall remains; no despawn callback
		assertEquals(2, first.size());
		assertEquals(original, first.get(0).getObjectKey());
		f.ground = f.ground();
		List<ObservedObjectState> returned = f.snapshot();
		assertEquals(2, returned.size());
		assertNotEquals(original,
			returned.stream().filter(o -> o.getKind().equals("groundObject")).findFirst().get().getObjectKey());
		assertEquals(Boolean.TRUE, f.tracker.coverage().get("complete"));
	}

	@Test
	public void partialScansRetainOnlyReobservedStatesAndRecoverySeparatesCurrentFromHistoricalGaps() throws Exception
	{
		Fixture f = new Fixture();
		List<WorldSnapshot> frames = new ArrayList<>();
		List<ObservedObjectState> first = f.snapshot();
		long original = first.get(0).getObjectKey();
		frames.add(f.frame(10, first));
		f.failGround = true;
		List<ObservedObjectState> partial = f.snapshot();
		assertEquals(1, partial.size());
		assertEquals("PARTIAL_EXPOSED_SCENE_SCAN", f.tracker.coverage().get("membershipStatus"));
		assertEquals(1, f.tracker.coverage().get("currentFailures"));
		frames.add(f.frame(11, partial));
		f.failGround = false;
		f.ground = null;
		List<ObservedObjectState> absent = f.snapshot();
		assertEquals(1, absent.size());
		assertEquals(Boolean.TRUE, f.tracker.coverage().get("complete"));
		assertEquals(0, f.tracker.coverage().get("currentFailures"));
		assertTrue((int) f.tracker.coverage().get("failures") > 0);
		frames.add(f.frame(12, absent));
		f.ground = f.ground();
		frames.add(f.frame(13, f.snapshot()));
		List<WorldSnapshot> compact = WorldStateExport.compact(frames);
		assertEquals(Collections.singletonList(original), compact.get(1).getObjectRemovals());
		assertEquals(1, compact.get(3).getObjectChanges().size());
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		recorder
			.putScene(SceneData.builder().sceneKey("room").baseX(0).baseY(0).width(104).height(104).plane(0).build());
		for (WorldSnapshot frame : frames)
			recorder.addSnapshot(frame);
		DeathRecordingV2 export = recorder.buildExport(DeathRecordingV2.Meta.builder().snapshotTick(13).build(), null);
		Path target = Paths.get("build/test-fixtures/object-membership.json");
		Files.createDirectories(target.getParent());
		Files.write(target, new Gson().toJson(export).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void unavailableScenesDoNotBecomeEmptyRoomsAndRecoveryKeepsSurvivingIdentity()
	{
		Fixture f = new Fixture();
		List<ObservedObjectState> first = f.snapshot();
		long key = first.get(0).getObjectKey();
		f.sceneAvailable = false;
		assertNull(f.snapshot());
		assertEquals("UNAVAILABLE", f.tracker.coverage().get("membershipStatus"));
		f.sceneAvailable = true;
		assertEquals(key, f.snapshot().get(0).getObjectKey());
		assertEquals(Boolean.TRUE, f.tracker.coverage().get("complete"));
	}

	@Test
	public void aCallbackAloneCannotNegateAnObjectStillPresentInTheSettledScene()
	{
		Fixture f = new Fixture();
		f.snapshot();
		f.tracker.despawned(f.ground);
		assertEquals(2, f.snapshot().size()); // record the callback separately; the settled array still contains it
	}

	@Test
	public void syntheticReconciliationCostIsMeasuredWithoutClaimingLiveClientLatency() throws Exception
	{
		Tile[][][] tiles = new Tile[4][104][104];
		for (int x = 0; x < 50; x++)
			for (int y = 0; y < 50; y++)
			{
				LocalPoint point = new LocalPoint(x * 128 + 64, y * 128 + 64);
				GroundObject ground = stub(GroundObject.class, (n, a) ->
				{
					if (n.equals("getId"))
						return 999991;
					if (n.equals("getLocalLocation"))
						return point;
					return null;
				});
				tiles[0][x][y] = stub(Tile.class, (n, a) -> n.equals("getGroundObject") ? ground : null);
			}
		Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
		ObjectComposition definition = stub(ObjectComposition.class, (n, a) ->
		{
			if (n.equals("getId"))
				return 999991;
			if (n.equals("getName"))
				return "Synthetic ground";
			if (n.equals("getVarbitId") || n.equals("getVarPlayerId"))
				return -1;
			return null;
		});
		Client client = stub(Client.class,
			(n, a) -> n.equals("getScene") ? scene : n.equals("getObjectDefinition") ? definition : null);
		ObjectStateTracker[] trackers =
		{ new ObjectStateTracker(), new ObjectStateTracker(), new ObjectStateTracker(), new ObjectStateTracker() };
		List<ObservedObjectState> prior = null;
		List<Double> milliseconds = new ArrayList<>();
		for (int iteration = 0; iteration < 55; iteration++)
		{
			long started = System.nanoTime();
			for (int plane = 0; plane < 4; plane++)
			{
				List<ObservedObjectState> objects = trackers[plane].snapshot(client, "plane" + plane, null, plane);
				assertEquals(plane == 0 ? 2500 : 0, objects.size());
				if (plane == 0)
				{
					if (prior != null)
						assertSame(prior, objects);
					prior = objects;
				}
			}
			if (iteration >= 5)
				milliseconds.add((System.nanoTime() - started) / 1_000_000.0);
		}
		Collections.sort(milliseconds);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("scope",
			"Synthetic API proxies: four 104x104 plane arrays, 2500 ground objects on plane0, four reported-plane trackers per measured iteration; not a live client or whole-plugin latency measurement.");
		result.put("warmupIterations", 5);
		result.put("measuredIterations", milliseconds.size());
		result.put("p50Milliseconds", milliseconds.get(milliseconds.size() / 2));
		result.put("p95Milliseconds", milliseconds.get((int) Math.ceil(milliseconds.size() * 0.95) - 1));
		result.put("maxMilliseconds", milliseconds.get(milliseconds.size() - 1));
		result.put("currentStateListSharingVerified", true);
		result.put("liveConformanceEstablished", false);
		Path target = Paths.get("build/test-fixtures/object-reconciliation-cost.json");
		Files.createDirectories(target.getParent());
		Files.write(target, new Gson().toJson(result).getBytes(StandardCharsets.UTF_8));
	}

	private static final class Fixture
	{
		final ObjectStateTracker tracker = new ObjectStateTracker();
		GroundObject ground = ground();
		final WallObject wall = object(WallObject.class, 999992);
		boolean failGround, sceneAvailable = true;
		final Client client;

		Fixture()
		{
			Tile tile = stub(Tile.class, (n, a) ->
			{
				if (n.equals("getGroundObject"))
				{
					if (failGround)
						throw new IllegalStateException("slot unavailable");
					return ground;
				}
				return n.equals("getWallObject") ? wall : null;
			});
			Tile[][][] tiles = new Tile[1][104][104];
			tiles[0][50][50] = tile;
			Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
			client = stub(Client.class, (n, a) ->
			{
				if (n.equals("getScene"))
					return sceneAvailable ? scene : null;
				if (n.equals("getObjectDefinition"))
				{
					int id = (int) a[0];
					return stub(ObjectComposition.class, (k, b) ->
					{
						if (k.equals("getId"))
							return id;
						if (k.equals("getName"))
							return id == 999991 ? "Recorded ground" : "Recorded wall";
						return null;
					});
				}
				return null;
			});
		}

		GroundObject ground()
		{
			return object(GroundObject.class, 999991);
		}

		<T> T object(Class<T> kind, int id)
		{
			return stub(kind, (n, a) ->
			{
				if (n.equals("getId"))
					return id;
				if (n.equals("getLocalLocation"))
					return new LocalPoint(6464, 6464);
				return null;
			});
		}

		List<ObservedObjectState> snapshot()
		{
			return tracker.snapshot(client, "room", null, 0);
		}

		WorldSnapshot frame(int tick, List<ObservedObjectState> objects)
		{
			return WorldSnapshot.builder().tick(tick).sceneKey("room").observedObjects(objects)
				.objectCoverage(tracker.coverage())
				.captureCoverage(Collections.singletonMap("sceneObjects",
					Boolean.TRUE.equals(tracker.coverage().get("complete")) ? "available" : "partial"))
				.player(
					PlayerState.builder().localPositionKnown(true).localX(6400).localY(6400).hp(99).maxHp(99).build())
				.npcs(Collections.emptyList()).players(Collections.emptyList()).hazards(Collections.emptyList())
				.projectiles(Collections.emptyList()).build();
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
			Object value = read.apply(m.getName(), a);
			if (value != null)
				return value;
			Class<?> type = m.getReturnType();
			if (type == int.class)
				return 0;
			if (type == boolean.class)
				return false;
			if (type == long.class)
				return 0L;
			return null;
		});
	}
}
