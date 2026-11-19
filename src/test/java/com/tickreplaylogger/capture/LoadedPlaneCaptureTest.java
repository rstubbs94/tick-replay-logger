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

public class LoadedPlaneCaptureTest
{
	@Test
	public void phaseCuesFollowActorIdentityAcrossPrimaryPlaneChangesButNotLogicalReloads()
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(new Gson()));
		String[] speech =
		{ "Fill my soul with smoke!" };
		NPC nex = stub(NPC.class,
			(n, a) -> n.equals("getWorldView") ? f.view
				: n.equals("getIndex") ? 20
					: n.equals("getId") ? 11278
						: n.equals("getName") ? "Nex"
							: n.equals("getComposition") || n.equals("getTransformedComposition") ? definition(11278)
								: n.equals("getCombatLevel") ? 1001
									: n.equals("getOverheadText") ? speech[0]
										: n.equals("getLocalLocation") ? new LocalPoint(128, 128)
											: n.equals("getWorldLocation") ? new WorldPoint(3201, 6001, 0) : null);
		f.loadedNpcs.add(nex);
		WorldSnapshot first = service.captureTick(f.client, 10, "upper", Collections.emptyList(), null, false, false,
			false);
		NpcState prior = plane(first, 0).getNpcs().stream().filter(npc -> npc.getNpcId() == 11278).findFirst().get();
		assertEquals("Smoke", prior.getObservedPhase());
		assertEquals(Integer.valueOf(10), prior.getPhaseLastObservedTick());
		speech[0] = null;
		f.currentPlane = 0;
		service.resetTiming();
		WorldSnapshot lower = service.captureTick(f.client, 11, "lower", Collections.emptyList(), null, false, false,
			false);
		NpcState retained = lower.getNpcs().stream().filter(npc -> npc.getNpcId() == 11278).findFirst().get();
		assertEquals("Smoke", retained.getObservedPhase());
		assertEquals(Integer.valueOf(10), retained.getPhaseObservedTick());
		assertEquals(Integer.valueOf(10), retained.getPhaseLastObservedTick());
		service.sceneLoading();
		WorldSnapshot reloaded = service.captureTick(f.client, 12, "reloaded", Collections.emptyList(), null, false,
			false, false);
		assertNull(
			reloaded.getNpcs().stream().filter(npc -> npc.getNpcId() == 11278).findFirst().get().getObservedPhase());
		assertEquals("Smoke", prior.getObservedPhase());
	}

	@Test
	public void unavailableEquipmentCannotBecomeAnObservedEmptyLoadout()
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshot unknown = service.captureTick(f.client, 1, "primary", Collections.emptyList(), null, false, true,
			true);
		assertNull(unknown.getPlayer().getEquipmentItemIds());
		f.equipment = stub(ItemContainer.class, (n, a) -> null);
		WorldSnapshot empty = service.captureTick(f.client, 2, "primary", Collections.emptyList(), null, false, true,
			true);
		assertEquals(EquipmentInventorySlot.values().length, empty.getPlayer().getEquipmentItemIds().length);
		for (int id : empty.getPlayer().getEquipmentItemIds())
			assertEquals(-1, id);
		assertNull(unknown.getPlayer().getEquipmentItemIds());
	}

	@Test
	public void logicalReloadInvalidatesAuxiliaryLifetimesEvenWhenApiContainersAreReused()
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshot first = service.captureTick(f.client, 1, "primary", Collections.emptyList(), null, false, true,
			true);
		String before = plane(first, 0).getSceneKey();
		service.sceneLoading();
		f.object[0] = f.ground(999989);
		WorldSnapshot next = service.captureTick(f.client, 2, "reloaded", Collections.emptyList(), null, false, true,
			true);
		assertNotEquals(before, plane(next, 0).getSceneKey());
		assertEquals(999989, plane(next, 0).getObservedObjects().get(0).getBaseId());
	}

	@Test
	public void collectsOffPlaneActorsGraphicsCollisionAndBridgeOnlyGeometryWithoutDuplication() throws Exception
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		ObjectStateTracker tracker = service.objects(f.client, f.view, 0);
		ObservedObjectState callback = tracker.observation(f.client, "groundObject", f.object[0]);
		WorldSnapshot first = service.captureTick(f.client, 10, "primary", f.projectiles(), null, false, true, true);
		WorldSnapshot lower = plane(first, 0), empty = plane(first, 2);
		assertEquals("LOCAL_RADIUS", first.getHazardCoverage().get("mappedObjectsScope"));
		assertEquals("EXPOSED_NATIVE_PLANE", lower.getHazardCoverage().get("mappedObjectsScope"));
		assertEquals(1, first.getPlane());
		assertEquals(2, first.getNpcs().size());
		assertEquals(Integer.valueOf(999981), first.getNpcs().get(0).getNpcId());
		assertEquals(Boolean.FALSE, first.getNpcs().get(1).getPlaneKnown());
		assertEquals(1, lower.getNpcs().size());
		assertEquals(Integer.valueOf(999982), lower.getNpcs().get(0).getNpcId());
		assertTrue(empty.getNpcs().isEmpty());
		assertEquals(999991, lower.getHazards().get(0).getId());
		assertEquals(999992, first.getHazards().get(0).getId());
		assertEquals(callback.getObjectKey(), lower.getObservedObjects().get(0).getObjectKey());
		assertEquals(Integer.valueOf(0), lower.getObjectCoverage().get("plane"));
		assertEquals(1, lower.getObservedTerrain().size());
		TileSurface floor = lower.getObservedTerrain().get(0);
		assertEquals(0, floor.getPlane());
		assertEquals(Integer.valueOf(1), floor.getSampledSceneArrayPlane());
		assertEquals(Boolean.TRUE, floor.getObservedViaBridge());
		assertFalse(floor.isBridge());
		assertEquals("LINKED_CANONICAL_PLANE_TILE", floor.getSampleEvidence());
		assertEquals("partial", lower.getCaptureCoverage().get("terrain"));
		assertTrue(empty.getObservedTerrain().isEmpty());
		assertEquals(0, empty.getPlaneCoverage().get("sceneArrayNonNullTiles"));
		assertEquals(1, lower.getProjectiles().size());
		assertEquals(1, first.getProjectiles().size());
		assertEquals(1, empty.getProjectiles().size());
		assertNull(lower.getPlayer());
		assertNull(lower.getObservedVarps());
		assertNull(lower.getWorldEntities());
		WorldSnapshot second = service.captureTick(f.client, 11, "primary", f.projectiles(), null, false, true, true);
		assertSame(lower.getObservedCollision(), plane(second, 0).getObservedCollision());
		f.flags[0][0][1] = 0;
		WorldSnapshot third = service.captureTick(f.client, 12, "primary", f.projectiles(), null, false, true, true);
		assertEquals(128, lower.getObservedCollision()[0][1]);
		assertEquals(0, plane(third, 0).getObservedCollision()[0][1]);
		assertSame(lower.getObservedCollision()[1], plane(third, 0).getObservedCollision()[1]);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		recorder.putScene(service.captureScene(f.client, "primary", 1, true));
		recorder.addSnapshot(first);
		recorder.addSnapshot(second);
		DeathRecordingV2 export = recorder.buildExport(DeathRecordingV2.Meta.builder().snapshotTick(11).build(), null);
		assertEquals(Boolean.TRUE, plane(export.getTicks().get(0), 0).getTerrainBaseline());
		assertTrue(plane(export.getTicks().get(1), 0).getTerrainChanges().isEmpty());
		Path path = Paths.get("build/test-fixtures/native-planes.json");
		Files.createDirectories(path.getParent());
		Files.write(path, new Gson().toJson(export).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void becomingPrimaryCannotLeaveAStaleSecondaryObjectCacheOnReturn()
	{
		Fixture f = new Fixture();
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshot first = service.captureTick(f.client, 1, "upper", Collections.emptyList(), null, false, true,
			true);
		String lowerKey = plane(first, 0).getSceneKey();
		f.currentPlane = 0;
		service.captureTick(f.client, 2, "lower", Collections.emptyList(), null, false, true, true);
		f.object[0] = f.ground(999989);
		f.currentPlane = 1;
		WorldSnapshot returned = service.captureTick(f.client, 3, "upper-again", Collections.emptyList(), null, false,
			true, true);
		assertNotEquals(lowerKey, plane(returned, 0).getSceneKey());
		assertEquals(999989, plane(returned, 0).getObservedObjects().get(0).getBaseId());
	}

	private static WorldSnapshot plane(WorldSnapshot snapshot, int plane)
	{
		return snapshot.getAdditionalViews().stream().filter(view -> view.getPlane() == plane).findFirst()
			.orElseThrow(() -> new AssertionError("Missing plane " + plane));
	}

	private static final class Fixture
	{
		int currentPlane = 1;
		final WorldView view;
		final Client client;
		final GroundObject[] object = new GroundObject[1];
		ItemContainer equipment;
		final List<NPC> loadedNpcs = new ArrayList<>();
		final int[][][] flags =
		{
			{
				{ 0, 128 },
				{ 0, 0 } },
			{
				{ 256, 0 },
				{ 0, 0 } },
			{
				{ 0, 0 },
				{ 0, 0 } } };

		Fixture()
		{
			WorldView[] holder = new WorldView[1];
			Player local = stub(Player.class,
				(n, a) -> n.equals("getWorldView") ? holder[0]
					: n.equals("getWorldLocation") ? new WorldPoint(3201, 6001, currentPlane)
						: n.equals("getLocalLocation") ? new LocalPoint(128, 128) : null);
			NPC upper = npc(holder, 7, 999981, 1), lower = npc(holder, 8, 999982, 0),
				unknown = npc(holder, 9, 999983, -1);
			loadedNpcs.addAll(Arrays.asList(upper, lower, unknown));
			SceneTilePaint paint0 = stub(SceneTilePaint.class, (n, a) -> n.equals("getRBG") ? 0x885522 : null);
			SceneTilePaint paint1 = stub(SceneTilePaint.class, (n, a) -> n.equals("getRBG") ? 0x225588 : null);
			Tile below = stub(Tile.class,
				(n, a) -> n.equals("getGroundObject") ? object[0] : n.equals("getSceneTilePaint") ? paint0 : null);
			Tile above = stub(Tile.class, (n, a) -> n.equals("getPlane") || n.equals("getRenderLevel") ? 1
				: n.equals("getBridge") ? below : n.equals("getSceneTilePaint") ? paint1 : null);
			Tile[][][] tiles =
			{ null, new Tile[2][2], new Tile[2][2] };
			tiles[1][1][1] = above;
			Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
			List<GraphicsObject> graphics = Arrays.asList(graphic(holder, 999991, 0), graphic(holder, 999992, 1));
			net.runelite.api.Deque<GraphicsObject> graphicList = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? graphics.iterator() : null);
			CollisionData[] collision = new CollisionData[3];
			for (int p = 0; p < 3; p++)
			{
				final int plane = p;
				collision[p] = stub(CollisionData.class, (n, a) -> n.equals("getFlags") ? flags[plane] : null);
			}
			holder[0] = stub(WorldView.class, (n, a) ->
			{
				switch (n)
				{
				case "getPlane":
					return currentPlane;
				case "getBaseX":
					return 3200;
				case "getBaseY":
					return 6000;
				case "getSizeX":
				case "getSizeY":
					return 2;
				case "getScene":
					return scene;
				case "getCollisionMaps":
					return collision;
				case "getGraphicsObjects":
					return graphicList;
				case "npcs":
					return indexed(loadedNpcs);
				case "players":
					return indexed(Collections.singletonList(local));
				case "worldViews":
				case "worldEntities":
					return indexed(Collections.emptyList());
				default:
					return null;
				}
			});
			view = holder[0];
			object[0] = ground(999988);
			client = stub(Client.class,
				(n, a) -> n.equals("getWorldType") ? EnumSet.noneOf(WorldType.class)
					: n.equals("getLocalPlayer") ? local
						: n.equals("getTopLevelWorldView") ? view
							: n.equals("getItemContainer") && a[0] == InventoryID.EQUIPMENT ? equipment
								: n.equals("getBoostedSkillLevel") || n.equals("getRealSkillLevel") ? 99 : null);
		}

		GroundObject ground(int id)
		{
			return stub(GroundObject.class,
				(n, a) -> n.equals("getWorldView") ? view
					: n.equals("getId") ? id
						: n.equals("getLocalLocation") ? new LocalPoint(128, 128)
							: n.equals("getWorldLocation") ? new WorldPoint(3201, 6001, 0) : null);
		}

		List<ProjectileState> projectiles()
		{
			return Arrays.asList(
				ProjectileState.builder().projectileId(999994).observationId(1L).worldViewId(0).sourceWorldViewId(0)
					.sourcePlane(1).plane(1).targetWorldViewId(0).targetPlane(0).localX(128D).localY(128D)
					.targetLocalX(192).targetLocalY(64).coordinateEvidence("SOURCE_ACTOR_WORLD_VIEW")
					.positionEvidence("TICK_SAMPLE").build(),
				ProjectileState.builder().projectileId(999995).observationId(2L).sourcePlane(0).plane(0)
					.targetWorldViewId(0).targetPlane(2).localX(900D).localY(900D).targetLocalX(64).targetLocalY(64)
					.coordinateEvidence("UNRESOLVED").positionEvidence("TICK_SAMPLE").build());
		}
	}

	private static NPCComposition definition(int id)
	{
		return stub(NPCComposition.class, (n, a) -> n.equals("getId") ? id : n.equals("getSize") ? 1 : null);
	}

	private static NPC npc(WorldView[] view, int index, int id, int plane)
	{
		return stub(NPC.class, (n, a) -> n.equals("getWorldView") ? view[0]
			: n.equals("getIndex") ? index
				: n.equals("getId") ? id
					: n.equals("getComposition") || n.equals("getTransformedComposition") ? definition(id)
						: n.equals("getLocalLocation") ? new LocalPoint(128, 128)
							: n.equals("getWorldLocation") && plane >= 0 ? new WorldPoint(3201, 6001, plane) : null);
	}

	private static GraphicsObject graphic(WorldView[] view, int id, int plane)
	{
		return stub(GraphicsObject.class,
			(n, a) -> n.equals("getWorldView") ? view[0]
				: n.equals("getId") ? id
					: n.equals("getLevel") ? plane : n.equals("getLocation") ? new LocalPoint(128, 128) : null);
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
