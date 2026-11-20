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

import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.ObservedEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real handlers must retain original neutral callback facts without a ready world frame. */
public class UnreadySceneObservationTest
{
	private DeferredAttributionTest fixture;
	private TickReplayLoggerPlugin plugin;
	private WorldView view;

	@Before
	public void setup() throws Exception
	{
		fixture = new DeferredAttributionTest();
		fixture.temporary.create();
		fixture.setup();
		plugin = (TickReplayLoggerPlugin) get(fixture, "plugin");
		view = (WorldView) get(fixture, "cueView");
	}

	@After
	public void cleanup()
	{
		fixture.temporary.delete();
	}

	@Test
	public void transientUnreadyCallbacksBetweenReadyFramesRetainAllThreeFacts() throws Exception
	{
		tick();
		state(GameState.LOADING);
		emit(object(null), graphic(null));
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(3);
		assertEquals(2, journal().size());
		assertTrue(journal().stream().allMatch(t -> "LOGGED_IN".equals(t.getObservedGameState())));
		assertEquals(Arrays.asList("OBJECT_SPAWNED", "GRAPHICS_OBJECT_CREATED", "OBJECT_DESPAWNED"),
			Arrays.asList(events().get(0).getType(), events().get(1).getType(), events().get(2).getType()));
		assertTrue(events().stream().allMatch(e -> e.getTick() == 2));
		assertTrue(events().get(0).getSequence() < events().get(1).getSequence());
		assertTrue(events().get(1).getSequence() < events().get(2).getSequence());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(export().getTicks().stream()
			.allMatch(t -> Integer.valueOf(0).equals(t.getObjectCoverage().get("observedObjects"))));
	}

	@Test
	public void deliveredLoadingBoundaryRetainsCallbacksAndItsExplicitWorldGap() throws Exception
	{
		tick();
		state(GameState.LOADING);
		emit(object(null), graphic(null));
		tick();
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(3);
		assertEquals(3, journal().size());
		TickData gap = journal().get(1);
		assertEquals("LOADING", gap.getObservedGameState());
		assertFalse(gap.isStateKnown("callbackCoverage"));
		assertFalse(gap.isStateKnown("worldSnapshot"));
		assertTrue(gap.getCaptureIssues().stream().anyMatch(i -> i.contains("LOADING")));
	}

	@Test
	public void knownSessionConnectionLossAndHopUseOriginalPrimitiveSamplesOnly() throws Exception
	{
		tick();
		final int[] reads =
		{ 0, 0, 0, 0 };
		for (GameState s : new GameState[]
		{ GameState.LOADING, GameState.CONNECTION_LOST, GameState.HOPPING })
		{
			state(s);
			GroundObject object = stub(GroundObject.class, (n, a) ->
			{
				if (n.equals("getId"))
				{
					reads[0]++;
					return 999991;
				}
				if (n.equals("getLocalLocation"))
				{
					reads[1]++;
					return new LocalPoint(6400, 6400, view);
				}
				return value(n);
			});
			GraphicsObject graphic = stub(GraphicsObject.class, (n, a) ->
			{
				if (n.equals("getId"))
				{
					reads[2]++;
					return 999992;
				}
				if (n.equals("getLocation"))
				{
					reads[3]++;
					return new LocalPoint(6400, 6400, view);
				}
				return value(n);
			});
			emit(object, graphic);
		}
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(9);
		assertArrayEquals(new int[]
		{ 6, 6, 3, 3 }, reads);
		assertTrue(events().stream().allMatch(e -> e.getData().get("referenceCandidates") == null));
		assertEquals(2, journal().size());
	}

	@Test
	public void failedPrimitiveCannotEraseIndependentUnreadyObjectAndGraphicFields() throws Exception
	{
		tick();
		state(GameState.LOADING);
		GroundObject object = object("getId");
		GraphicsObject graphic = graphic("getLevel");
		emit(object, graphic);
		state(GameState.LOGGED_IN);
		tick();
		List<ObservedEvent> events = events();
		assertEquals(3, events.size());
		Map<String, Object> o = events.get(0).getData(), g = events.get(1).getData();
		assertEquals(Boolean.FALSE, availability(o).get("id"));
		assertEquals(6400, o.get("localX"));
		assertEquals(999992, g.get("id"));
		assertEquals(Boolean.FALSE, availability(g).get("plane"));
		assertEquals(6400, g.get("localX"));
		assertEquals(77, g.get("startCycle"));
		assertTrue(((Map<?, ?>) o.get("observationIssues")).containsKey("id"));
		assertTrue(((Map<?, ?>) g.get("observationIssues")).containsKey("plane"));
		assertUnreadyFacts(3);
	}

	@Test
	public void knownNullUnreadyLocationsRemainDifferentFromFailedLocations() throws Exception
	{
		tick();
		state(GameState.LOADING);
		GroundObject object = stub(GroundObject.class, (n, a) -> n.equals("getLocalLocation") ? null : value(n));
		GraphicsObject graphic = stub(GraphicsObject.class, (n, a) ->
		{
			if (n.equals("getLocation"))
				throw new IllegalStateException("original graphic location unavailable");
			return value(n);
		});
		emit(object, graphic);
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(3);
		Map<String, Object> o = events().get(0).getData(), g = events().get(1).getData();
		assertEquals(Boolean.TRUE, availability(o).get("location"));
		assertTrue(((List<?>) o.get("nullFields")).contains("location"));
		assertEquals(Boolean.FALSE, availability(g).get("location"));
		assertFalse(((Map<?, ?>) o.get("observationIssues")).containsKey("location"));
		assertNull(o.get("localX"));
		assertNull(g.get("localX"));
	}

	@Test
	public void startupRejectedAndFreshAuthenticationSessionsCannotAdmitSceneCallbacks() throws Exception
	{
		for (GameState s : new GameState[]
		{ GameState.STARTING, GameState.LOGIN_SCREEN, GameState.LOGIN_SCREEN_AUTHENTICATOR, GameState.LOGGING_IN,
			GameState.LOADING })
		{
			state(s);
			emit(object(null), graphic(null));
			emitItemsAndInteger();
		}
		assertTrue(events().isEmpty());
		state(GameState.LOGGED_IN);
		tick();
		state(GameState.LOGIN_SCREEN);
		state(GameState.LOADING);
		emit(object(null), graphic(null));
		emitItemsAndInteger();
		assertTrue(events().isEmpty());
	}

	@Test
	public void disabledWorldCaptureRejectsEvenEstablishedUnreadySceneCallbacks() throws Exception
	{
		tick();
		set(plugin, "config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}
		});
		state(GameState.LOADING);
		emit(object(null), graphic(null));
		emitItemsAndInteger();
		assertTrue(events().isEmpty());
	}

	@Test
	public void readyCallbacksKeepTheirExistingObjectShapeAndQualifiedGraphicScope() throws Exception
	{
		tick();
		emit(object(null), graphic(null));
		tick();
		assertEquals(3, events().size());
		assertTrue(
			events().get(0).getData().get("object") instanceof com.tickreplaylogger.model.world.ObservedObjectState);
		assertEquals(999992, events().get(1).getData().get("id"));
		assertEquals(Boolean.TRUE, events().get(1).getData().get("scopeKnown"));
		assertNotNull(events().get(1).getSceneKey());
	}

	@Test
	public void unreadyGroundItemsAndClientIntegerKeepIndependentOriginalValues() throws Exception
	{
		tick();
		state(GameState.LOADING);
		emit(object(null), graphic(null));
		emitItemsAndInteger();
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(7);
		Map<String, Object> quantity = events().get(4).getData(), integer = events().get(6).getData();
		assertEquals(995, quantity.get("id"));
		assertEquals(0, quantity.get("quantity"));
		assertEquals(8, quantity.get("oldQuantity"));
		assertEquals(5, quantity.get("newQuantity"));
		assertEquals(6400, quantity.get("localX"));
		assertNull(quantity.get("item"));
		assertNull(quantity.get("itemKey"));
		assertEquals(123, integer.get("id"));
		assertEquals(0, integer.get("value"));
		assertEquals(Boolean.TRUE, availability(integer).get("value"));
		state(GameState.LOADING);
		emit(object("getId"), graphic("getLevel"));
		set(fixture, "failClientIntegerValue", true);
		plugin.onVarClientIntChanged(new VarClientIntChanged(123));
		tick();
		set(fixture, "failClientIntegerValue", false);
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(11);
		writeCurrentFixture();
	}

	@Test
	public void unreadyGroundItemAndIntegerFailuresKeepOtherPrimitivesAndEventQuantities() throws Exception
	{
		tick();
		state(GameState.LOADING);
		set(fixture, "failClientIntegerValue", true);
		Tile tile = stub(Tile.class, (n, a) ->
		{
			if (n.equals("getLocalLocation"))
				throw new IllegalStateException("original tile location unavailable");
			return value(n);
		});
		TileItem item = stub(TileItem.class, (n, a) ->
		{
			if (n.equals("getQuantity"))
				throw new IllegalStateException("original quantity unavailable");
			return n.equals("getId") ? 995 : null;
		});
		plugin.onItemQuantityChanged(new ItemQuantityChanged(item, tile, 8, 5));
		plugin.onItemSpawned(new ItemSpawned(null, stub(TileItem.class, (n, a) -> n.equals("getId") ? 995 : null)));
		plugin.onVarClientIntChanged(new VarClientIntChanged(123));
		set(fixture, "failClientIntegerValue", false);
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(3);
		Map<String, Object> quantity = events().get(0).getData(), integer = events().get(2).getData();
		assertEquals(995, quantity.get("id"));
		assertEquals(8, quantity.get("oldQuantity"));
		assertEquals(5, quantity.get("newQuantity"));
		assertEquals(Boolean.FALSE, availability(quantity).get("quantity"));
		assertEquals(Boolean.FALSE, availability(quantity).get("location"));
		assertEquals(995, events().get(1).getData().get("id"));
		assertEquals(0, events().get(1).getData().get("quantity"));
		assertEquals(Boolean.FALSE, availability(events().get(1).getData()).get("location"));
		assertEquals(123, integer.get("id"));
		assertNull(integer.get("value"));
		assertEquals(Boolean.FALSE, availability(integer).get("value"));
	}

	@Test
	public void readyGroundItemsAndIntegerKeepTheirExistingRepresentation() throws Exception
	{
		tick();
		emitItemsAndInteger();
		tick();
		assertEquals(4, events().size());
		assertTrue(events().get(0).getData().get("item") instanceof com.tickreplaylogger.model.world.GroundItemState);
		assertEquals(8, events().get(1).getData().get("oldQuantity"));
		assertEquals(5, events().get(1).getData().get("newQuantity"));
		assertEquals(123, events().get(3).getData().get("id"));
		assertEquals(0, events().get(3).getData().get("value"));
	}

	@Test
	public void observedSessionUnknownStateAndFailedStateReadRetainOnlyPrimitiveCallbacks() throws Exception
	{
		tick();
		state(GameState.UNKNOWN);
		emit(object(null), graphic(null));
		emitItemsAndInteger();
		set(fixture, "failGameState", true);
		emit(object(null), graphic(null));
		emitItemsAndInteger();
		set(fixture, "failGameState", false);
		state(GameState.LOGGED_IN);
		tick();
		assertUnreadyFacts(14);
	}

	private void assertUnreadyFacts(int count) throws Exception
	{
		assertEquals(count, events().size());
		for (ObservedEvent e : events())
		{
			assertNull(e.getSceneKey());
			assertNull(e.getActorRef());
			assertNull(e.getRelatedActorRef());
			assertEquals(Boolean.FALSE, e.getData().get("scopeKnown"));
			assertEquals(Boolean.FALSE, e.getData().get("positionScopeKnown"));
			assertEquals(Boolean.FALSE, availability(e.getData()).get("nativeSceneKey"));
			assertEquals("PARTIAL_EVENT_CONTEXT", e.getData().get("captureStatus"));
			assertNull(e.getData().get("object"));
			assertNull(e.getData().get("objectKey"));
		}
	}

	private Object value(String name)
	{
		if (name.equals("getId"))
			return 999992;
		if (name.equals("getWorldView"))
			return view;
		if (name.equals("getLocalLocation") || name.equals("getLocation"))
			return new LocalPoint(6400, 6400, view);
		if (name.equals("getWorldLocation"))
			return new WorldPoint(3200, 3200, 0);
		if (name.equals("getStartCycle"))
			return 77;
		return null;
	}

	private GroundObject object(String fail)
	{
		return stub(GroundObject.class, (n, a) ->
		{
			if (n.equals(fail))
				throw new IllegalStateException("original object field unavailable");
			return n.equals("getId") ? 999991 : value(n);
		});
	}

	private GraphicsObject graphic(String fail)
	{
		return stub(GraphicsObject.class, (n, a) ->
		{
			if (n.equals(fail))
				throw new IllegalStateException("original graphic field unavailable");
			return value(n);
		});
	}

	private void emit(GroundObject o, GraphicsObject g)
	{
		GroundObjectSpawned spawn = new GroundObjectSpawned();
		spawn.setGroundObject(o);
		plugin.onGroundObjectSpawned(spawn);
		plugin.onGraphicsObjectCreated(new GraphicsObjectCreated(g));
		GroundObjectDespawned despawn = new GroundObjectDespawned();
		despawn.setGroundObject(o);
		plugin.onGroundObjectDespawned(despawn);
	}

	private void emitItemsAndInteger()
	{
		Tile tile = stub(Tile.class, (n, a) -> value(n));
		TileItem item = stub(TileItem.class, (n, a) -> n.equals("getId") ? 995 : null);
		plugin.onItemSpawned(new ItemSpawned(tile, item));
		plugin.onItemQuantityChanged(new ItemQuantityChanged(item, tile, 8, 5));
		plugin.onItemDespawned(new ItemDespawned(tile, item));
		plugin.onVarClientIntChanged(new VarClientIntChanged(123));
	}

	private void writeCurrentFixture() throws Exception
	{
		com.tickreplaylogger.capture.DamageMap map = (com.tickreplaylogger.capture.DamageMap) get(plugin, "damageMap");
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId("synthetic-unready-scene-observations")
			.snapshotTick(4).referenceCatalogId(map.catalog().id())
			.captureBuildId(com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(com.tickreplaylogger.capture.CaptureBuild.apiVersion())
			.runeLiteApiArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.clientArtifactSha256())
			.captureSettings((DeathRecordingV2.CaptureSettings) call(plugin, "captureSettings")).build();
		com.google.gson.Gson gson = new com.google.gson.Gson();
		com.google.gson.JsonObject out = gson
			.toJsonTree(export().toBuilder().meta(meta).journalTicks(journal()).build()).getAsJsonObject();
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		String json = gson.toJson(out);
		assertFalse(json.contains("Proxy"));
		assertEquals("1.3", out.get("schemaVersion").getAsString());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/unready-scene-observations.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private void state(GameState state) throws Exception
	{
		set(fixture, "gameState", state);
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		plugin.onGameStateChanged(e);
	}

	private void tick() throws Exception
	{
		call(fixture, "tick");
	}

	private DeathRecordingV2 export() throws Exception
	{
		return (DeathRecordingV2) call(fixture, "export");
	}

	private List<ObservedEvent> events() throws Exception
	{
		return export().getEvents().getObservations();
	}

	@SuppressWarnings("unchecked")
	private List<TickData> journal() throws Exception
	{
		return (List<TickData>) call(get(fixture, "journal"), "getTickHistory");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> availability(Map<String, Object> d)
	{
		return (Map<String, Object>) d.get("fieldAvailability");
	}

	private static Object get(Object o, String name) throws Exception
	{
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void set(Object o, String name, Object value) throws Exception
	{
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
	}

	private static Object call(Object o, String name) throws Exception
	{
		Method m;
		try
		{
			m = o.getClass().getDeclaredMethod(name);
		}
		catch (NoSuchMethodException inherited)
		{
			m = o.getClass().getMethod(name);
		}
		m.setAccessible(true);
		return m.invoke(o);
	}

	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (o, m, a) ->
		{
			if (m.getName().equals("equals"))
				return o == a[0];
			if (m.getName().equals("hashCode"))
				return System.identityHashCode(o);
			Object value = read.apply(m.getName(), a);
			if (value != null)
				return value;
			Class<?> r = m.getReturnType();
			if (r == int.class)
				return 0;
			if (r == long.class)
				return 0L;
			if (r == boolean.class)
				return false;
			return null;
		}));
	}
}
