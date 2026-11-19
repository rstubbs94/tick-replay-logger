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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises capture families, irrespective of encounter/name/known hazard membership. */
public class GenericCaptureTest
{
	private final Gson gson = new Gson();
	private final CombatCatalog catalog = CombatCatalog.load(gson);

	@Test
	public void everyObjectKindSurvivesUnknownDefinitionsAndLateSceneAvailability()
	{
		Scene[] scene =
		{ null };
		GroundObject ground = placed(GroundObject.class, 999991, 50);
		GameObject game = placed(GameObject.class, 999992, 51);
		WallObject wall = placed(WallObject.class, 999993, 52);
		DecorativeObject decor = placed(DecorativeObject.class, 999994, 53);
		boolean[] gamePresent =
		{ true };
		Tile tile = stub(Tile.class, (n, a) ->
		{
			switch (n)
			{
			case "getGroundObject":
				return ground;
			case "getGameObjects":
				return gamePresent[0] ? new GameObject[]
				{ game, game } : new GameObject[0];
			case "getWallObject":
				return wall;
			case "getDecorativeObject":
				return decor;
			default:
				return null;
			}
		});
		Client client = stub(Client.class, (n, a) -> n.equals("getScene") ? scene[0] : null);
		ObjectStateTracker tracker = new ObjectStateTracker();
		ObservedObjectState callback = tracker.observation(client, "groundObject", ground);
		assertEquals("DEFINITION_UNAVAILABLE", callback.getDefinitionEvidence());
		assertNull(tracker.snapshot(client, "room")); // Callback facts do not establish current membership in an unavailable scene.
		assertEquals(false, tracker.coverage().get("complete"));
		scene[0] = stub(Scene.class, (n, a) -> n.equals("getTiles") ? new Tile[][][]
		{
			{
				{ tile } } }
			: null);
		List<ObservedObjectState> baseline = tracker.snapshot(client, "room");
		assertEquals(4, baseline.size());
		assertSame(baseline, tracker.snapshot(client, "room"));
		assertEquals(callback.getObjectKey(), baseline.get(0).getObjectKey());
		assertEquals(new HashSet<>(Arrays.asList("groundObject", "gameObject", "wallObject", "decorativeObject")),
			new HashSet<>(Arrays.asList(baseline.get(0).getKind(), baseline.get(1).getKind(), baseline.get(2).getKind(),
				baseline.get(3).getKind())));
		assertEquals(true, tracker.coverage().get("complete"));
		gamePresent[0] = false;
		tracker.despawned(game);
		assertEquals(3, tracker.snapshot(client, "room").size());
	}

	@Test
	public void transformedObjectsRetainControllerAndObserveMissingThenChangedDefinition()
	{
		ObjectComposition[] effective =
		{ null };
		ObjectComposition base = stub(ObjectComposition.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return 999991;
			case "getImpostorIds":
				return new int[]
				{ -1, 36048 };
			case "getImpostor":
				return effective[0];
			case "getVarbitId":
				return 123;
			case "getVarPlayerId":
				return -1;
			default:
				return null;
			}
		});
		GroundObject ground = placed(GroundObject.class, 999991, 50);
		Tile tile = stub(Tile.class, (n, a) -> n.equals("getGroundObject") ? ground : null);
		Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? new Tile[][][]
		{
			{
				{ tile } } }
			: null);
		Client client = stub(Client.class, (n, a) ->
		{
			if (n.equals("getScene"))
				return scene;
			if (n.equals("getObjectDefinition"))
				return base;
			if (n.equals("getVarbitValue"))
				return 7;
			return null;
		});
		ObjectStateTracker tracker = new ObjectStateTracker();
		tracker.spawned("groundObject", ground);
		ObservedObjectState unresolved = tracker.snapshot(client, "room").get(0);
		assertNull(unresolved.getId());
		assertEquals("TRANSFORM_UNAVAILABLE", unresolved.getDefinitionEvidence());
		effective[0] = stub(ObjectComposition.class,
			(n, a) -> n.equals("getId") ? 36048 : n.equals("getName") ? "Observed floor" : null);
		ObservedObjectState resolved = tracker.snapshot(client, "room").get(0);
		assertEquals(unresolved.getObjectKey(), resolved.getObjectKey());
		assertEquals(Integer.valueOf(36048), resolved.getId());
		assertEquals(Integer.valueOf(7), resolved.getControlValue());
	}

	@Test
	public void selectedWindowIsSelfContainedAfterTrimAndRestartsCheckpointAfterFailedChannel()
	{
		ObservedObjectState first = object(1, 999991), second = object(2, 999992);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		recorder.addSnapshot(frame(1, Arrays.asList(first), new int[]
		{ 0, 4 }));
		recorder.addSnapshot(frame(2, Arrays.asList(first, second), new int[]
		{ 0, 5 }));
		recorder.addSnapshot(frame(3, Arrays.asList(second), new int[]
		{ 0, 6 }));
		DeathRecordingV2 export = recorder.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(2, export.getTicks().get(0).getTick());
		assertEquals(Boolean.TRUE, export.getTicks().get(0).getObjectBaseline());
		assertEquals(2, export.getTicks().get(0).getObjectChanges().size());
		assertArrayEquals(new int[]
		{ 0, 5 }, export.getTicks().get(0).getVarpBaseline());
		assertEquals(Arrays.asList(1L), export.getTicks().get(1).getObjectRemovals());
		assertEquals(Integer.valueOf(6), export.getTicks().get(1).getVarpChanges().get(1));
		List<WorldSnapshot> failure = WorldStateExport.compact(Arrays.asList(frame(1, Arrays.asList(first), null),
			frame(2, null, null), frame(3, Arrays.asList(first), null)));
		assertNull(failure.get(1).getObjectChanges());
		assertEquals(Boolean.TRUE, failure.get(2).getObjectBaseline());
		assertEquals(1, failure.get(2).getObjectChanges().size());
		assertNotNull(recorder.snapshotAtTick(2).getObservedObjects()); // Export must not mutate buffered facts.
	}

	@Test
	public void collisionCheckpointsTrackWallRemovalAndRecoverAfterMissingState()
	{
		WorldSnapshot first = frame(1, Collections.emptyList(), null).toBuilder().observedCollision(new int[][]
		{
			{ 0, 128 },
			{ 256, 0 } }).build();
		WorldSnapshot second = frame(2, Collections.emptyList(), null).toBuilder().observedCollision(new int[][]
		{
			{ 0, 0 },
			{ 256, 8 } }).build();
		List<WorldSnapshot> result = WorldStateExport.compact(Arrays.asList(first, second));
		assertEquals(Boolean.TRUE, result.get(0).getCollisionBaseline());
		assertEquals(2, result.get(0).getCollisionChanges().size());
		assertEquals(Boolean.FALSE, result.get(1).getCollisionBaseline());
		assertEquals(2, result.get(1).getCollisionChanges().size());
		assertEquals(0, result.get(1).getCollisionChanges().get(0).getFlag());
		assertEquals(8, result.get(1).getCollisionChanges().get(1).getFlag());
		assertEquals(2, WorldStateExport.compact(Arrays.asList(second)).get(0).getCollisionChanges().size());
		WorldSnapshot missing = frame(3, null, null);
		WorldSnapshot returned = second.toBuilder().tick(4).build();
		assertEquals(Boolean.TRUE,
			WorldStateExport.compact(Arrays.asList(first, second, missing, returned)).get(3).getCollisionBaseline());
		assertNotNull(first.getObservedCollision());
		assertNull(result.get(0).getObservedCollision());
	}

	@Test
	public void collisionCaptureCopiesTheLiveGridAndHonorsItsCaptureSetting()
	{
		int[][] flags =
		{
			{ 0, 128 },
			{ 256, 0 } };
		CollisionData map = stub(CollisionData.class, (n, a) -> n.equals("getFlags") ? flags : null);
		Player player = stub(Player.class, (n, a) -> null);
		Client client = stub(Client.class,
			(n, a) -> n.equals("getLocalPlayer") ? player : n.equals("getCollisionMaps") ? new CollisionData[]
			{ map }
				: n.equals("getNpcs") ? Collections.emptyList()
					: n.equals("getPlayers") ? Collections.emptyList() : null);
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshot captured = service.captureTick(client, 1, "room", Collections.emptyList(), null, false, true);
		flags[0][1] = 0;
		assertEquals(128, captured.getObservedCollision()[0][1]);
		WorldSnapshot disabled = service.captureTick(client, 2, "room", Collections.emptyList(), null, false, false);
		assertNull(disabled.getObservedCollision());
		assertEquals("disabled", disabled.getCaptureCoverage().get("collision"));
	}

	@Test
	public void actorSnapshotsKeepCurrentFacingSeparateFromTargetAndRetainAnimationFrames()
	{
		Actor[] localTarget =
		{ null };
		Player local = stub(Player.class, (n, a) ->
		{
			switch (n)
			{
			case "getInteracting":
				return localTarget[0];
			case "isInteracting":
				return localTarget[0] != null;
			case "getOrientation":
				return 1536;
			case "getCurrentOrientation":
				return 512;
			case "getAnimation":
				return 1234;
			case "getAnimationFrame":
				return 3;
			case "getPoseAnimation":
				return 4321;
			case "getPoseAnimationFrame":
				return 7;
			default:
				return null;
			}
		});
		Player other = stub(Player.class, (n, a) ->
		{
			switch (n)
			{
			case "getOrientation":
				return 0;
			case "getCurrentOrientation":
				return 1024;
			case "getAnimation":
				return 5678;
			case "getAnimationFrame":
				return 4;
			case "getPoseAnimation":
				return 8765;
			case "getPoseAnimationFrame":
				return 8;
			case "getHealthRatio":
				return 11;
			case "getHealthScale":
				return 30;
			case "getOverheadText":
				return "Recorded teammate speech";
			case "getOverheadCycle":
				return 42;
			default:
				return null;
			}
		});
		boolean[] active =
		{ true };
		NPC npc = stub(NPC.class, (n, a) ->
		{
			switch (n)
			{
			case "getInteracting":
				return other;
			case "isInteracting":
				return true;
			case "getId":
				return 999881;
			case "getOrientation":
				return 1024;
			case "getCurrentOrientation":
				return 768;
			case "getAnimation":
				return active[0] ? 2222 : -1;
			case "getAnimationFrame":
				return 5;
			case "getPoseAnimation":
				return active[0] ? 3333 : -1;
			case "getPoseAnimationFrame":
				return 2;
			default:
				return null;
			}
		});
		localTarget[0] = npc;
		Client client = stub(Client.class,
			(n, a) -> n.equals("getLocalPlayer") ? local
				: n.equals("getNpcs") ? Collections.singletonList(npc)
					: n.equals("getPlayers") ? Arrays.asList(local, other) : null);
		WorldCaptureService service = new WorldCaptureService();
		WorldSnapshot first = service.captureTick(client, 1, "room", Collections.emptyList(), null, true, false, false);
		assertEquals(Integer.valueOf(1536), first.getPlayer().getOrientation());
		assertEquals(Integer.valueOf(512), first.getPlayer().getCurrentOrientation());
		assertEquals(Integer.valueOf(3), first.getPlayer().getAnimationFrame());
		assertEquals(Integer.valueOf(7), first.getPlayer().getPoseAnimationFrame());
		assertEquals(Integer.valueOf(768), first.getNpcs().get(0).getCurrentOrientation());
		assertEquals(Integer.valueOf(5), first.getNpcs().get(0).getAnimationFrame());
		assertEquals(Integer.valueOf(2), first.getNpcs().get(0).getPoseAnimationFrame());
		assertEquals(Integer.valueOf(1024), first.getPlayers().get(0).getCurrentOrientation());
		assertEquals(Integer.valueOf(4), first.getPlayers().get(0).getAnimationFrame());
		assertEquals(Integer.valueOf(8), first.getPlayers().get(0).getPoseAnimationFrame());
		assertEquals(Integer.valueOf(11), first.getPlayers().get(0).getHealthRatio());
		assertEquals(Integer.valueOf(30), first.getPlayers().get(0).getHealthScale());
		assertEquals("Recorded teammate speech", first.getPlayers().get(0).getOverheadText());
		assertEquals(Integer.valueOf(42), first.getPlayers().get(0).getOverheadCycle());
		assertEquals(first.getPlayers().get(0).getActorRef(), first.getNpcs().get(0).getInteraction().getActorRef());
		assertEquals(first.getNpcs().get(0).getActorRef(), first.getPlayer().getInteraction().getActorRef());
		WorldSnapshot privateView = service.captureTick(client, 2, "room", Collections.emptyList(), null, false, false,
			false);
		assertTrue(privateView.getPlayers().isEmpty());
		assertEquals("OTHER_PLAYER_CAPTURE_DISABLED", privateView.getNpcs().get(0).getInteraction().getEvidence());
		assertNull(privateView.getNpcs().get(0).getInteraction().getActorRef());
		active[0] = false;
		NpcState stopped = service.captureTick(client, 2, "room", Collections.emptyList(), null, true, false, false)
			.getNpcs().get(0);
		assertNull(stopped.getAnimationFrame());
		assertNull(stopped.getPoseAnimationFrame());
	}

	@Test
	public void everyReviewedPhaseRuleRequiresItsOwnObservedCue()
	{
		int tick = 20;
		for (JsonElement element : catalog.phaseRules())
		{
			JsonObject rule = element.getAsJsonObject();
			int id = rule.getAsJsonArray("npcIds").get(0).getAsInt();
			NPC npc = npc(id);
			String operator = rule.get("operator").getAsString();
			JsonElement value = rule.getAsJsonArray("values").get(0);
			ObservedMechanics mechanics = new ObservedMechanics();
			mechanics.observe(catalog, npc, id, "overheadEquals", "unrelated speech", -1, Collections.emptyList(), tick,
				"test");
			assertNull(mechanics.phase(npc));
			mechanics.observe(catalog, npc, id, operator,
				operator.equals("overheadEquals") ? value.getAsString() : null,
				operator.equals("animationIn") ? value.getAsInt() : -1,
				operator.equals("npcPresent") ? Arrays.asList(npc(value.getAsInt())) : Collections.emptyList(), tick,
				"test observed cue");
			assertNotNull(rule.toString(), mechanics.phase(npc));
			assertEquals(rule.get("phase").getAsString(), mechanics.phase(npc).getLabel());
			assertEquals(tick, mechanics.phase(npc).getObservedTick());
			mechanics.validateIdentity(catalog, npc, id, true);
			assertNotNull(mechanics.phase(npc));
			mechanics.validateIdentity(catalog, npc, id, false);
			assertNull(mechanics.phase(npc));
			mechanics.observe(catalog, npc, id, operator,
				operator.equals("overheadEquals") ? value.getAsString() : null,
				operator.equals("animationIn") ? value.getAsInt() : -1,
				operator.equals("npcPresent") ? Arrays.asList(npc(value.getAsInt())) : Collections.emptyList(), tick,
				"test observed cue");
			mechanics.validateIdentity(catalog, npc, -99999, true);
			assertNull(mechanics.phase(npc));
			mechanics.prune(Collections.emptyList());
			assertNull(mechanics.phase(npc));
		}
	}

	@Test
	public void phaseCueIdentityExcludesFloorButIncludesNativeSceneAndBase()
	{
		int[] plane =
		{ 0 }, base =
		{ 3200 };
		Scene[] scene =
		{ stub(Scene.class, (n, a) -> null) };
		WorldView view = stub(WorldView.class, (n, a) -> n.equals("getPlane") ? plane[0]
			: n.equals("getBaseX") ? base[0] : n.equals("getScene") ? scene[0] : null);
		NPC npc = stub(NPC.class, (n, a) -> n.equals("getWorldView") ? view : null);
		ObservedMechanics mechanics = new ObservedMechanics();
		mechanics.observe(catalog, npc, 11278, "overheadEquals", "Fill my soul with smoke!", -1,
			Collections.emptyList(), 1, "speech");
		mechanics.observe(catalog, npc, 11278, "overheadEquals", "Fill my soul with smoke!", -1,
			Collections.emptyList(), 3, "speech");
		assertEquals(1, mechanics.phase(npc).getObservedTick());
		assertEquals(3, mechanics.phase(npc).getLastObservedTick());
		plane[0] = 1;
		mechanics.validateIdentity(catalog, npc, 11278, true);
		assertNotNull(mechanics.phase(npc));
		base[0]++;
		mechanics.validateIdentity(catalog, npc, 11278, true);
		assertNull(mechanics.phase(npc));
		mechanics.observe(catalog, npc, 11278, "overheadEquals", "Fill my soul with smoke!", -1,
			Collections.emptyList(), 4, "speech");
		scene[0] = stub(Scene.class, (n, a) -> null);
		mechanics.validateIdentity(catalog, npc, 11278, true);
		assertNull(mechanics.phase(npc));
	}

	@Test
	public void ownershipDoesNotDefineHpAndBossWideTimingCannotFlattenConditionalChecks()
	{
		for (int id : new int[]
		{ 0, 3, 4, 53, 54, 55, 60, 71, 72, 73 })
			assertNotEquals("HP", catalog.hitsplatSurface(id));
		for (int id : new int[]
		{ 5, 16, 17, 43, 65, 67, 74 })
			assertEquals("HP", catalog.hitsplatSurface(id));
		assertEquals("UNCLASSIFIED", catalog.hitsplatSurface(999999));
		DamageMap map = DamageMap.load(gson);
		PreRuleIrDamageMap legacy = PreRuleIrDamageMap.load(new Gson(), LegacyCombatCatalog.from(map.catalog()));
		assertEquals("UNKNOWN", legacy.determinationForAttack(-1, -1, null, true));
		assertEquals("transition", catalog.animationKind(9035, 8754));
	}

	@Test
	public void everyHitsplatConstantInTheInstalledApiHasAnExplicitResourceRule() throws Exception
	{
		for (java.lang.reflect.Field field : HitsplatID.class.getDeclaredFields())
			if (field.getType() == int.class && java.lang.reflect.Modifier.isStatic(field.getModifiers()))
				assertNotEquals(field.getName(), "UNCLASSIFIED", catalog.hitsplatSurface(field.getInt(null)));
	}

	@Test
	public void aNameHintIsNotAReviewedDamagingGraphic()
	{
		assertEquals("Maiden blood pool", catalog.damagingGraphicName(1579));
		assertNull(catalog.damagingGraphicName(3904)); // Unrelated blood VFX is still captured, never causal proof.
		assertNull(catalog.damagingGraphicName(999999));
	}

	@Test
	public void loadedGraphicsAreNotDroppedBecauseThePlayerOrGraphicIsOutsideTheOldRadius()
	{
		Player player = stub(Player.class, (n, a) -> null);
		List<GraphicsObject> graphics = Arrays
			.asList(
				stub(GraphicsObject.class,
					(n, a) -> n.equals("getId") ? 999991
						: n.equals("getLocation") ? new LocalPoint(12000, 12000) : null),
				stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? 999992
					: n.equals("getLevel") ? 1 : n.equals("getLocation") ? new LocalPoint(12000, 12000) : null));
		net.runelite.api.Deque<GraphicsObject> deque = stub(net.runelite.api.Deque.class,
			(n, a) -> n.equals("iterator") ? graphics.iterator() : null);
		Client client = stub(Client.class, (n, a) -> n.equals("getGraphicsObjects") ? deque : null);
		List<HazardState> observed = new WorldCaptureService().captureHazards(client, player);
		assertEquals(1, observed.size());
		assertEquals(999991, observed.get(0).getId());
		assertEquals(12000, observed.get(0).getLocalX());
	}

	@Test
	public void unresolvedResourceImpactPreventsBlamingEarlierKnownDamage()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(3, new com.google.gson.Gson());
		DamageRecord earlier = DamageRecord.builder().impactTick(2).observationSequence(4L).amount(1).build();
		Map<String, Object> data = new HashMap<>();
		data.put("amount", 10);
		data.put("resourceSurface", "SKILL_OR_HP");
		recorder.addObservation(
			ObservedEvent.builder().tick(2).sequence(5L).type("HITSPLAT").actorRef("player").data(data).build());
		assertTrue(recorder.hasUnresolvedPlayerImpact(2, earlier));
		assertFalse(recorder.hasUnresolvedPlayerImpact(3, earlier));
	}

	@Test
	public void pluginExportFixtureKeepsDenseBaselinesSmallAndReplayable() throws Exception
	{
		List<ObservedObjectState> dense = new ArrayList<>();
		for (int i = 0; i < 2500; i++)
			dense
				.add(object(i + 1, 999991).toBuilder().localX((i % 50) * 128 + 64).localY((i / 50) * 128 + 64).build());
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(100, new com.google.gson.Gson());
		for (int tick = 10; tick < 110; tick++)
			recorder.addSnapshot(frame(tick, dense, new int[]
			{ 0, tick }));
		DeathRecordingV2 recording = recorder.buildExport(
			DeathRecordingV2.Meta.builder().referenceCatalogId(catalog.id()).build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(2500, recording.getTicks().get(0).getObjectChanges().size());
		for (int i = 1; i < 100; i++)
			assertTrue(recording.getTicks().get(i).getObjectChanges().isEmpty());
		String payload = gson.toJson(recording);
		assertTrue(payload.length() < 2000000);
		Path target = Paths.get("build/test-fixtures/generic-scene.json");
		Files.createDirectories(target.getParent());
		Files.write(target, payload.getBytes(StandardCharsets.UTF_8));
	}

	private ObservedObjectState object(long key, int id)
	{
		return ObservedObjectState.builder().objectKey(key).kind("groundObject").baseId(id).id(id).localX(6592)
			.localY(6592).definitionEvidence("OBJECT_DEFINITION").build();
	}

	private WorldSnapshot frame(int tick, List<ObservedObjectState> objects, int[] vars)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("generated-scene").observedObjects(objects)
			.observedVarps(vars)
			.player(PlayerState.builder().localPositionKnown(true).localX(3200).localY(3200).hp(99).maxHp(99).build())
			.npcs(Collections.emptyList()).players(Collections.emptyList()).hazards(Collections.emptyList())
			.projectiles(Collections.emptyList()).build();
	}

	private NPC npc(int id)
	{
		return stub(NPC.class,
			(n, a) -> n.equals("getId") ? id : n.equals("getWorldLocation") ? new WorldPoint(3000, 3000, 0) : null);
	}

	private <T extends TileObject> T placed(Class<T> type, int id, int tile)
	{
		return stub(type, (n, a) -> n.equals("getId") ? id
			: n.equals("getLocalLocation") ? new LocalPoint(tile * 128 + 64, tile * 128 + 64) : null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			String n = method.getName();
			if (n.equals("hashCode"))
				return System.identityHashCode(proxy);
			if (n.equals("equals"))
				return proxy == args[0];
			Object value = values.apply(n, args);
			if (value != null)
				return value;
			Class<?> result = method.getReturnType();
			if (result == boolean.class)
				return false;
			if (result == int.class)
				return n.equals("getAnimation") || n.equals("getGraphic") || n.equals("getVarbitId")
					|| n.equals("getVarPlayerId") ? -1 : 0;
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
