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
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoadedWorldViewsTest
{
	@Test
	public void equalTemplateHashesCannotAliasDifferentNativeScopes()
	{
		int[][][] first =
		{
			{
				{ 0, 31 } } },
			second =
			{
				{
					{ 1, 0 } } };
		assertEquals(Arrays.deepHashCode(first), Arrays.deepHashCode(second));
		int[][][][] chunks =
		{ first };
		WorldView view = stub(WorldView.class, (n, a) -> n.equals("getInstanceTemplateChunks") ? chunks[0] : null);
		String prior = WorldViews.scope(view);
		assertEquals(prior, WorldViews.scope(view));
		chunks[0] = second;
		assertNotEquals(prior, WorldViews.scope(view));
		String changed = WorldViews.scope(view);
		WorldViews.invalidate(view);
		assertNotEquals(changed, WorldViews.scope(view));
	}

	@Test
	public void explicitViewReloadRefreshesChildLifetimesWithReusedApiContainers()
	{
		Fixture f = new Fixture();
		WorldCaptureService capture = new WorldCaptureService();
		WorldSnapshot first = capture.captureTick(f.client, 1, "primary", Collections.emptyList(), null, false, false,
			false);
		WorldSnapshot child = first.getAdditionalViews().get(0);
		String parentActor = first.getNpcs().get(0).getActorRef();
		capture.worldViewLifecycle(f.client, f.views[1]);
		WorldSnapshot next = capture.captureTick(f.client, 2, "primary", Collections.emptyList(), null, false, false,
			false);
		WorldSnapshot reloaded = next.getAdditionalViews().get(0);
		assertNotEquals(child.getSceneKey(), reloaded.getSceneKey());
		assertNotEquals(child.getNpcs().get(0).getActorRef(), reloaded.getNpcs().get(0).getActorRef());
		assertEquals(parentActor, next.getNpcs().get(0).getActorRef());
		assertEquals(1, child.getObservedObjects().size());
		assertEquals(1, reloaded.getObservedObjects().size());
	}

	@Test
	public void unavailableNativeGeometryDoesNotCreateInvalidMetadataOrDiscardActors()
	{
		Fixture f = new Fixture();
		f.emptyChildScene = true;
		WorldSnapshot frame = new WorldCaptureService().captureTick(f.client, 1, "primary", Collections.emptyList(),
			null, false, true, true);
		assertEquals(1, frame.getAdditionalViews().size());
		WorldSnapshot child = frame.getAdditionalViews().get(0);
		assertNull(child.getNativeScene());
		assertEquals(1, child.getNpcs().size());
		assertEquals("unavailable", child.getCaptureCoverage().get("sceneMetadata"));
	}

	@Test
	public void nativeFrameChangesCannotBecomeMovementBetweenUnrelatedOrigins()
	{
		Fixture f = new Fixture();
		String scope = WorldViews.scope(f.views[0]);
		WorldPoint from = new WorldPoint(3201, 6001, 0), to = new WorldPoint(3203, 6001, 0);
		assertEquals(Integer.valueOf(2), WorldViews.settledDistance(from, to, scope, scope));
		assertNull(WorldViews.settledDistance(from, to, scope, WorldViews.scope(f.views[1])));
		assertNull(WorldViews.settledDistance(from, new WorldPoint(3203, 6001, 1), scope, scope));
		assertNull(WorldViews.settledDistance(from, to, null, scope));
	}

	@Test
	public void anUnreadableAdditionalSceneCannotDiscardThePrimarySnapshot()
	{
		Fixture f = new Fixture();
		f.failChildScene = true;
		WorldSnapshot frame = new WorldCaptureService().captureTick(f.client, 1, "primary", Collections.emptyList(),
			null, false, true, true);
		assertEquals(1, frame.getNpcs().size());
		assertTrue(frame.getAdditionalViews().isEmpty());
		assertEquals("PARTIAL_LOADED_NATIVE_PLANE_CHANNELS", frame.getCaptureCoverage().get("worldViews"));
		assertTrue(frame.getCaptureIssues().stream().anyMatch(issue -> issue.contains("native view 2")));
	}

	@Test
	public void capturesLoadedViewsIndependentlyAndPreservesNativeObjectCallbacks() throws Exception
	{
		Fixture f = new Fixture();
		WorldCaptureService capture = new WorldCaptureService();
		ObjectStateTracker tracker = capture.objects(f.client, f.views[1]);
		ObservedObjectState callback = tracker.observation(f.client, "groundObject", f.object);
		WorldSnapshot first = capture.captureTick(f.client, 10, "primary", Collections.emptyList(), null, false, true,
			true);
		WorldSnapshot child = first.getAdditionalViews().get(0);
		assertEquals("ALL_DISCOVERED_NATIVE_PLANE_CHANNELS", first.getCaptureCoverage().get("worldViews"));
		assertEquals(1, first.getAdditionalViews().size());
		assertEquals(Integer.valueOf(2), child.getWorldViewId());
		assertEquals(Integer.valueOf(999981), first.getNpcs().get(0).getNpcId());
		assertEquals(Integer.valueOf(999982), child.getNpcs().get(0).getNpcId());
		assertEquals(first.getNpcs().get(0).getSceneId(), child.getNpcs().get(0).getSceneId());
		assertNotEquals(first.getNpcs().get(0).getActorRef(), child.getNpcs().get(0).getActorRef());
		assertEquals(callback.getObjectKey(), child.getObservedObjects().get(0).getObjectKey());
		assertEquals(5000, child.getNativeScene().getBaseX());
		assertEquals(128, child.getObservedObjects().get(0).getLocalX().intValue());
		assertEquals(128, child.getObservedCollision()[0][1]);
		assertNull(child.getPlayer());
		assertNull(child.getObservedVarps());
		assertEquals("disabled", child.getCaptureCoverage().get("otherPlayers"));
		WorldEntityState pose = first.getWorldEntities().get(0);
		assertEquals(Integer.valueOf(512), pose.getMainLocalX());
		assertEquals(Integer.valueOf(768), pose.getTargetMainLocalY());
		assertEquals(Integer.valueOf(1024), pose.getOrientation());
		assertEquals(Integer.valueOf(2), pose.getWorldViewId());
		WorldSnapshot second = capture.captureTick(f.client, 11, "primary", Collections.emptyList(), null, false, true,
			true);
		assertEquals(pose.getEntityKey(), second.getWorldEntities().get(0).getEntityKey());
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		recorder.addSnapshot(first);
		recorder.addSnapshot(second);
		DeathRecordingV2 exported = recorder.buildExport(DeathRecordingV2.Meta.builder().snapshotTick(11).build(),
			null);
		WorldSnapshot a = exported.getTicks().get(0).getAdditionalViews().get(0),
			b = exported.getTicks().get(1).getAdditionalViews().get(0);
		assertNotNull(a.getNativeScene());
		assertEquals(Boolean.TRUE, a.getObjectBaseline());
		assertNull(b.getNativeScene());
		assertEquals(Boolean.FALSE, b.getObjectBaseline());
		assertTrue(b.getObjectChanges().isEmpty());
		assertEquals(Boolean.TRUE,
			recorder.buildExport(null, null, 11, 11).getTicks().get(0).getAdditionalViews().get(0).getObjectBaseline());
		assertNotNull(
			recorder.buildExport(null, null, 11, 11).getTicks().get(0).getAdditionalViews().get(0).getNativeScene());
		Path path = Paths.get("build/test-fixtures/native-views.json");
		Files.createDirectories(path.getParent());
		Files.write(path, new Gson().toJson(exported).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void discoveryUsesCarrierFallbackAndKeepsFailedChannelsExplicit()
	{
		Fixture f = new Fixture();
		f.failEnumeration = true;
		f.failChildCollision = true;
		WorldSnapshot first = new WorldCaptureService().captureTick(f.client, 10, "primary", Collections.emptyList(),
			null, false, true, true);
		assertEquals(1, first.getAdditionalViews().size());
		assertEquals("PARTIAL_LOADED_NATIVE_PLANE_CHANNELS", first.getCaptureCoverage().get("worldViews"));
		assertEquals(1, first.getNpcs().size());
		assertNull(first.getAdditionalViews().get(0).getObservedCollision());
		assertEquals("failed", first.getAdditionalViews().get(0).getCaptureCoverage().get("collision"));
	}

	@Test
	public void viewDisappearanceOrFailureForcesFreshExportCheckpoints()
	{
		SceneData scene = SceneData.builder().sceneKey("child").worldViewId(2).build();
		ObservedObjectState object = ObservedObjectState.builder().objectKey(1).localX(128).localY(128).build();
		WorldSnapshot child = WorldSnapshot.builder().tick(1).sceneKey("child").worldViewId(2).nativeScene(scene)
			.observedObjects(Collections.singletonList(object)).build();
		WorldSnapshot first = WorldSnapshot.builder().tick(1).sceneKey("root")
			.additionalViews(Collections.singletonList(child)).build();
		WorldSnapshot missing = WorldSnapshot.builder().tick(2).sceneKey("root")
			.additionalViews(Collections.emptyList()).build();
		WorldSnapshot returned = first.toBuilder().tick(3)
			.additionalViews(Collections.singletonList(child.toBuilder().tick(3).build())).build();
		List<WorldSnapshot> out = WorldStateExport.compact(Arrays.asList(first, missing, returned));
		assertEquals(Boolean.TRUE, out.get(2).getAdditionalViews().get(0).getObjectBaseline());
		assertNotNull(out.get(2).getAdditionalViews().get(0).getNativeScene());
		assertNotNull(first.getAdditionalViews().get(0).getObservedObjects());
	}

	private static class Fixture
	{
		final WorldView[] views = new WorldView[2];
		final Player local;
		final GroundObject object;
		final Client client;
		boolean failEnumeration, failChildCollision, failChildScene, emptyChildScene;

		Fixture()
		{
			object = stub(GroundObject.class, (n, a) -> n.equals("getWorldView") ? views[1]
				: n.equals("getId") ? 999983 : n.equals("getLocalLocation") ? new LocalPoint(128, 128) : null);
			local = stub(Player.class,
				(n, a) -> n.equals("getWorldView") ? views[0]
					: n.equals("getLocalLocation") ? new LocalPoint(128, 128)
						: n.equals("getWorldLocation") ? new WorldPoint(3201, 3201, 0) : null);
			NPC[] npcs = new NPC[2];
			for (int i = 0; i < 2; i++)
			{
				final int index = i;
				NPCComposition definition = stub(NPCComposition.class,
					(n, a) -> n.equals("getId") ? 999981 + index : n.equals("getSize") ? 1 : null);
				npcs[i] = stub(NPC.class,
					(n, a) -> n.equals("getWorldView") ? views[index]
						: n.equals("getId") ? 999981 + index
							: n.equals("getComposition") || n.equals("getTransformedComposition") ? definition
								: n.equals("getIndex") ? 7
									: n.equals("getLocalLocation") ? new LocalPoint(128, 128)
										: n.equals("getWorldLocation")
											? new WorldPoint(index == 0 ? 3201 : 5001, 6001, 0)
											: null);
			}
			WorldEntity carrier = stub(WorldEntity.class,
				(n, a) -> n.equals("getWorldView") ? views[1]
					: n.equals("getLocalLocation") ? new LocalPoint(512, 640)
						: n.equals("getTargetLocation") ? new LocalPoint(640, 768)
							: n.equals("getOrientation") ? 1024 : null);
			net.runelite.api.Deque<GraphicsObject> graphics = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? Collections.emptyIterator() : null);
			for (int i = 0; i < 2; i++)
			{
				final int index = i;
				Tile[][][] tiles = new Tile[1][2][2];
				if (i == 1)
					tiles[0][1][1] = stub(Tile.class, (n, a) -> n.equals("getGroundObject") ? object : null);
				Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
				CollisionData collision = stub(CollisionData.class, (n, a) -> n.equals("getFlags") ? new int[][]
				{
					{ 0, 128 },
					{ 0, 0 } } : null);
				views[i] = stub(WorldView.class, (n, a) ->
				{
					switch (n)
					{
					case "getId":
						return index == 0 ? 0 : 2;
					case "getBaseX":
						return index == 0 ? 3200 : 5000;
					case "getBaseY":
						return 6000;
					case "getSizeX":
					case "getSizeY":
						return index == 1 && emptyChildScene ? 0 : 2;
					case "getScene":
						if (index == 1 && failChildScene)
							throw new IllegalStateException("scene unavailable");
						return index == 1 && emptyChildScene ? null : scene;
					case "getCollisionMaps":
						if (index == 1 && failChildCollision)
							throw new IllegalStateException("unavailable");
						return new CollisionData[]
						{ collision };
					case "getGraphicsObjects":
						return graphics;
					case "npcs":
						return indexed(Collections.singletonList(npcs[index]));
					case "players":
						return indexed(index == 0 ? Collections.singletonList(local) : Collections.emptyList());
					case "worldViews":
						if (index == 0 && failEnumeration)
							throw new IllegalStateException("iterator unavailable");
						return indexed(Collections.singletonList(views[index == 0 ? 1 : 0]));
					case "worldEntities":
						return indexed(index == 0 ? Collections.singletonList(carrier) : Collections.emptyList());
					default:
						return null;
					}
				});
			}
			client = stub(Client.class,
				(n, a) -> n.equals("getLocalPlayer") ? local : n.equals("getTopLevelWorldView") ? views[0] : null);
		}
	}

	private static <T> IndexedObjectSet<T> indexed(List<T> values)
	{
		return stub(IndexedObjectSet.class,
			(n, a) -> n.equals("iterator") ? values.iterator() : n.equals("size") ? values.size() : null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			String name = method.getName();
			if (name.equals("equals"))
				return proxy == args[0];
			if (name.equals("hashCode"))
				return System.identityHashCode(proxy);
			Object value = values.apply(name, args);
			if (value != null)
				return value;
			Class<?> result = method.getReturnType();
			if (result == int.class)
				return name.equals("getAnimation") || name.equals("getGraphic") || name.equals("getVarbitId")
					|| name.equals("getVarPlayerId") ? -1 : 0;
			if (result == boolean.class)
				return false;
			if (result == long.class)
				return 0L;
			if (result == double.class)
				return 0D;
			if (result == float.class)
				return 0F;
			return null;
		});
	}
}
