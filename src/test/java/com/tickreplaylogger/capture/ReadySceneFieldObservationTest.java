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

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.DeferredAttributionTest;
import com.tickreplaylogger.TickReplayLoggerPlugin;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Optional failures never turn a currently enumerated entity into an absent entity. */
public class ReadySceneFieldObservationTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void optionalObjectFailuresKeepCurrentFieldsAndNeighbours()
	{
		for (String field : Arrays.asList("config", "definition", "name", "orientation"))
		{
			Fixture f = new Fixture();
			ObservedObjectState first = f.object();
			long key = first.getObjectKey();
			f.phase = 2;
			f.fault = field;
			f.reads.clear();
			List<ObservedObjectState> objects = f.objects();
			assertEquals(field, 2, objects.size());
			ObservedObjectState now = objects.stream().filter(o -> o.getBaseId() == 999931).findFirst().get();
			assertEquals(key, now.getObjectKey());
			assertEquals(Integer.valueOf(6528), now.getLocalX());
			assertEquals(Integer.valueOf(3251), now.getWorldX());
			JsonObject raw = json(now);
			assertFalse(raw.getAsJsonObject("fieldAvailability").get(field).getAsBoolean());
			assertTrue(raw.getAsJsonObject("observationIssues").has(field));
			assertEquals(Integer.valueOf(1), f.reads.get(field));
			if (field.equals("name"))
				assertNull(now.getName());
			if (field.equals("config"))
				assertNull(now.getConfig());
			if (field.equals("definition"))
			{
				assertNull(now.getId());
				assertNull(now.getName());
				assertEquals("DEFINITION_UNAVAILABLE", now.getDefinitionEvidence());
			}
			assertEquals("COMPLETE_EXPOSED_SCENE_SCAN", f.tracker.coverage().get("membershipStatus"));
			assertEquals(1, f.tracker.coverage().get("currentMetadataFailures"));
			assertEquals(Boolean.FALSE, f.tracker.coverage().get("complete"));
			assertEquals("Stable object", first.getName());
			assertEquals(Integer.valueOf(6400), first.getLocalX());
		}
	}

	@Test
	public void metadataFailureDoesNotEmitRemovalAndRecoveryKeepsIdentity()
	{
		Fixture f = new Fixture();
		List<WorldSnapshot> frames = new ArrayList<>();
		frames.add(f.frame(1));
		long key = frames.get(0).getObservedObjects().stream().filter(o -> o.getBaseId() == 999931).findFirst().get()
			.getObjectKey();
		f.phase = 2;
		f.fault = "name";
		frames.add(f.frame(2));
		f.phase = 3;
		f.fault = null;
		frames.add(f.frame(3));
		List<WorldSnapshot> compact = WorldStateExport.compact(frames);
		assertTrue(compact.get(1).getObjectRemovals().isEmpty());
		assertEquals(1, compact.get(1).getObjectChanges().size());
		assertEquals(key, compact.get(1).getObjectChanges().get(0).getObjectKey());
		assertNull(compact.get(1).getObjectChanges().get(0).getName());
		assertTrue(compact.get(2).getObjectRemovals().isEmpty());
		assertEquals(key, compact.get(2).getObjectChanges().get(0).getObjectKey());
		assertEquals("Stable object", compact.get(2).getObjectChanges().get(0).getName());
		assertEquals(Boolean.TRUE, f.tracker.coverage().get("complete"));
		assertEquals(0, f.tracker.coverage().get("currentMetadataFailures"));
		JsonObject partial = json(compact.get(1));
		assertTrue(partial.getAsJsonArray("objectChanges").get(0).getAsJsonObject().getAsJsonObject("observationIssues")
			.has("name"));
	}

	@Test
	public void failedPositionsDoNotBorrowEarlierCoordinatesAndKnownNullStaysDistinct()
	{
		Fixture f = new Fixture();
		f.object();
		f.phase = 2;
		f.fault = "localLocation";
		ObservedObjectState partial = f.object();
		assertNull(partial.getLocalX());
		assertNull(partial.getLocalY());
		assertEquals(Integer.valueOf(3251), partial.getWorldX());
		assertTrue(json(partial).getAsJsonObject("observationIssues").has("localLocation"));
		f.fault = "worldLocation";
		partial = f.object();
		assertNull(partial.getWorldX());
		assertNull(partial.getWorldY());
		assertEquals(Integer.valueOf(6528), partial.getLocalX());
		f.fault = "config";
		f.nullName = true;
		partial = f.object();
		JsonObject raw = json(partial);
		assertTrue(raw.getAsJsonObject("fieldAvailability").get("name").getAsBoolean());
		assertTrue(raw.getAsJsonArray("nullFields").toString().contains("\"name\""));
		assertFalse(raw.getAsJsonObject("observationIssues").has("name"));
		assertNull(partial.getName());
	}

	@Test
	public void successfulObjectWireShapeAndUnchangedSnapshotSharingStayStable()
	{
		Fixture f = new Fixture();
		List<ObservedObjectState> a = f.objects(), b = f.objects();
		assertSame(a, b);
		JsonObject raw = json(a.stream().filter(o -> o.getBaseId() == 999931).findFirst().get());
		assertFalse(raw.has("observationIssues"));
		assertFalse(raw.has("fieldAvailability"));
		assertFalse(raw.has("nullFields"));
		assertEquals(77, raw.get("config").getAsInt());
		assertEquals(0, raw.get("plane").getAsInt());
	}

	@Test
	public void optionalGraphicFailuresKeepCurrentIdentityPositionAndIndependentFields()
	{
		for (String field : Arrays.asList("animationFrame", "animation", "z", "finished", "startCycle"))
		{
			Fixture f = new Fixture();
			HazardState first = f.graphic();
			f.phase = 2;
			f.fault = field;
			f.reads.clear();
			WorldSnapshot frame = f.capture(2);
			HazardState now = frame.getHazards().stream().filter(h -> h.getKind().equals("graphicsObject")).findFirst()
				.get();
			assertEquals(field, 953, now.getId());
			assertEquals(6528, now.getLocalX());
			assertEquals(3251, now.getWorldX());
			JsonObject raw = json(now);
			assertTrue(raw.getAsJsonObject("observationIssues").has(field));
			assertFalse(raw.getAsJsonObject("fieldAvailability").get(field).getAsBoolean());
			assertFalse(raw.has(field.equals("animation") ? "animationId" : field));
			assertEquals(Integer.valueOf(1), f.reads.get(field));
			assertEquals("partial", frame.getHazardCoverage().get("graphicsStatus"));
			assertEquals(6400, first.getLocalX());
			assertEquals(Integer.valueOf(4), first.getAnimationFrame());
		}
	}

	@Test
	public void missingGraphicLocationKeepsIdAndDoesNotFillPreviousPosition()
	{
		Fixture f = new Fixture();
		f.graphic();
		f.phase = 2;
		f.fault = "graphicLocation";
		HazardState partial = f.graphic();
		assertEquals(953, partial.getId());
		assertEquals(Boolean.FALSE, partial.getLocalPositionKnown());
		assertEquals(Boolean.FALSE, partial.getWorldPositionKnown());
		assertEquals(Integer.valueOf(4), partial.getAnimationFrame());
		assertTrue(json(partial).getAsJsonObject("observationIssues").has("location"));
		f.fault = "animationFrame";
		f.nullGraphicLocation = true;
		JsonObject raw = json(f.graphic());
		assertTrue(raw.getAsJsonObject("fieldAvailability").get("location").getAsBoolean());
		assertFalse(raw.getAsJsonObject("observationIssues").has("location"));
	}

	@Test
	public void mappedDefinitionFailureRetainsUnqualifiedCurrentObjectWithoutDamagePromotion()
	{
		Fixture f = new Fixture();
		f.targetId = 36047;
		assertFalse(f.capture(1).getHazards().isEmpty());
		f.phase = 2;
		f.fault = "definition";
		WorldSnapshot frame = f.capture(2);
		HazardState object = frame.getHazards().stream().filter(h -> h.getKind().equals("gameObject")).findFirst()
			.get();
		assertEquals(36047, object.getId());
		assertEquals(6528, object.getLocalX());
		assertEquals("unknown", object.getState());
		assertNull(object.getDamaging());
		assertEquals("STATE_UNAVAILABLE", object.getSourceEligibility());
		assertTrue(json(object).getAsJsonObject("observationIssues").has("definition"));
		assertEquals("partial", frame.getHazardCoverage().get("mappedObjectsStatus"));
	}

	@Test
	public void readyLifecycleCallbackExportsPartialObjectWithoutErasingCurrentCoordinates() throws Exception
	{
		DeferredAttributionTest fixture = new DeferredAttributionTest();
		fixture.temporary.create();
		try
		{
			fixture.setup();
			invoke(fixture, "tick");
			TickReplayLoggerPlugin plugin = (TickReplayLoggerPlugin) field(fixture, "plugin");
			WorldView view = (WorldView) field(fixture, "cueView");
			final int[] reads =
			{ 0 };
			GroundObject object = stub(GroundObject.class, (n, a) ->
			{
				if (n.equals("getId"))
					return 999931;
				if (n.equals("getConfig"))
				{
					reads[0]++;
					throw new IllegalStateException("original config unavailable");
				}
				if (n.equals("getWorldView"))
					return view;
				if (n.equals("getLocalLocation"))
					return new LocalPoint(6400, 6528, view);
				if (n.equals("getWorldLocation"))
					return new WorldPoint(3200, 3201, 0);
				return null;
			});
			net.runelite.api.events.GroundObjectSpawned event = new net.runelite.api.events.GroundObjectSpawned();
			event.setGroundObject(object);
			plugin.onGroundObjectSpawned(event);
			invoke(fixture, "tick");
			DeathRecordingV2 export = (DeathRecordingV2) invoke(fixture, "export");
			ObservedEvent observed = export.getEvents().getObservations().stream()
				.filter(e -> "OBJECT_SPAWNED".equals(e.getType())).findFirst().get();
			JsonObject data = json(observed).getAsJsonObject("data");
			JsonObject partial = data.getAsJsonObject("object");
			assertNotNull("Current callback payload: " + data, partial);
			assertEquals(999931, partial.get("baseId").getAsInt());
			assertEquals(6400, partial.get("localX").getAsInt());
			assertEquals(3201, partial.get("worldY").getAsInt());
			assertFalse(partial.has("config"));
			assertTrue(partial.getAsJsonObject("observationIssues").has("config"));
			assertEquals("PARTIAL_OBJECT_OBSERVATION", data.get("captureStatus").getAsString());
			assertEquals(1, reads[0]);
			assertEquals(1, export.getEvents().getObservations().stream()
				.filter(e -> "OBJECT_SPAWNED".equals(e.getType())).count());
		}
		finally
		{
			fixture.temporary.delete();
		}
	}

	private static Object field(Object target, String name) throws Exception
	{
		java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static Object invoke(Object target, String name) throws Exception
	{
		java.lang.reflect.Method m = target.getClass().getDeclaredMethod(name);
		m.setAccessible(true);
		return m.invoke(target);
	}

	private static JsonObject json(Object value)
	{
		return GSON.toJsonTree(value).getAsJsonObject();
	}

	private static final class Fixture
	{
		int phase = 1, targetId = 999931;
		String fault;
		boolean nullName, nullGraphicLocation;
		final Map<String, Integer> reads = new HashMap<>();
		final ObjectStateTracker tracker = new ObjectStateTracker();
		final WorldCaptureService service = new WorldCaptureService();
		final Client client;
		final WorldView view;
		final Player player;
		final GameObject target;
		final WallObject neighbour;
		final GraphicsObject graphic;

		Fixture()
		{
			final WorldView[] holder =
			{ null };
			ObjectComposition definition = stub(ObjectComposition.class, (n, a) ->
			{
				if (n.equals("getId"))
					return targetId;
				if (n.equals("getName"))
					return read("name", nullName ? null : "Stable object");
				if (n.equals("getVarbitId") || n.equals("getVarPlayerId"))
					return -1;
				return null;
			});
			target = stub(GameObject.class, (n, a) ->
			{
				if (n.equals("getId"))
					return targetId;
				if (n.equals("getPlane"))
					return 0;
				if (n.equals("getWorldView"))
					return holder[0];
				if (n.equals("getLocalLocation"))
					return read("localLocation", new LocalPoint(phase == 1 ? 6400 : 6528, 6400));
				if (n.equals("getWorldLocation"))
					return read("worldLocation", new WorldPoint(phase == 1 ? 3250 : 3251, 3250, 0));
				if (n.equals("getConfig"))
					return read("config", 77);
				if (n.equals("getOrientation"))
					return read("orientation", 0);
				return null;
			});
			neighbour = stub(WallObject.class, (n, a) ->
			{
				if (n.equals("getId"))
					return 999932;
				if (n.equals("getWorldView"))
					return holder[0];
				return null;
			});
			graphic = stub(GraphicsObject.class, (n, a) ->
			{
				if (n.equals("getId"))
					return 953;
				if (n.equals("getLocation"))
					return read("graphicLocation",
						nullGraphicLocation ? null : new LocalPoint(phase == 1 ? 6400 : 6528, 6400));
				if (n.equals("getWorldView"))
					return holder[0];
				if (n.equals("getStartCycle"))
					return read("startCycle", 77);
				if (n.equals("getAnimationFrame"))
					return read("animationFrame", 4);
				if (n.equals("getAnimation"))
					return read("animation", null);
				if (n.equals("getZ"))
					return read("z", 0);
				if (n.equals("finished"))
					return read("finished", false);
				return null;
			});
			net.runelite.api.Deque<GraphicsObject> graphics = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? Collections.singleton(graphic).iterator() : null);
			Tile tile = stub(Tile.class, (n, a) -> n.equals("getGameObjects") ? new GameObject[]
			{ target } : n.equals("getWallObject") ? neighbour : null);
			Tile[][][] tiles = new Tile[1][104][104];
			tiles[0][50][50] = tile;
			Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
			view = stub(WorldView.class, (n, a) ->
			{
				if (n.equals("getGraphicsObjects"))
					return graphics;
				if (n.equals("getScene"))
					return scene;
				if (n.equals("getBaseX") || n.equals("getBaseY"))
					return 3200;
				if (n.equals("getSizeX") || n.equals("getSizeY"))
					return 104;
				return null;
			});
			holder[0] = view;
			player = stub(Player.class, (n, a) -> n.equals("getWorldView") ? view
				: n.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
			client = stub(Client.class, (n, a) ->
			{
				if (n.equals("getObjectDefinition"))
					return (int) a[0] == targetId ? read("definition", definition)
						: stub(ObjectComposition.class,
							(k, b) -> k.equals("getId") ? 999932
								: k.equals("getName") ? "Neighbour"
									: k.equals("getVarbitId") || k.equals("getVarPlayerId") ? -1 : null);
				return n.equals("getScene") ? scene
					: n.equals("getWorldView") || n.equals("getTopLevelWorldView") ? view
						: n.equals("getLocalPlayer") ? player : null;
			});
			service.setDamageMap(DamageMap.load(GSON));
		}

		Object read(String field, Object value)
		{
			reads.merge(field, 1, Integer::sum);
			if (phase == 2 && Objects.equals(field, fault))
				throw new IllegalStateException("original " + field + " unavailable");
			return value;
		}

		List<ObservedObjectState> objects()
		{
			return tracker.snapshot(client, "room", view, 0);
		}

		ObservedObjectState object()
		{
			return objects().stream().filter(o -> o.getBaseId() == targetId).findFirst().get();
		}

		WorldSnapshot frame(int tick)
		{
			return WorldSnapshot.builder().tick(tick).sceneKey("room").observedObjects(objects())
				.objectCoverage(tracker.coverage()).build();
		}

		WorldSnapshot capture(int tick)
		{
			return service.captureTick(client, tick, "room", Collections.emptyList(), null, false, false, false);
		}

		HazardState graphic()
		{
			return capture(phase).getHazards().stream().filter(h -> h.getKind().equals("graphicsObject")).findFirst()
				.get();
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			if (m.getName().equals("hashCode"))
				return System.identityHashCode(p);
			if (m.getName().equals("equals"))
				return p == a[0];
			Object value = read.apply(m.getName(), a);
			if (value != null)
				return value;
			Class<?> result = m.getReturnType();
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
