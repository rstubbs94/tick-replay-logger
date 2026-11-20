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
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainCaptureTest
{
	@Test
	public void lifecycleMembershipStillReadsMutablePaintAndModelValuesEveryTick()
	{
		int[] rgb = { 1 }, vertices = { 128, 256 }, bridges = { 0 };
		SceneTilePaint paint = stub(SceneTilePaint.class, (name, args) -> name.equals("getRBG") ? rgb[0] : null);
		SceneTileModel model = stub(SceneTileModel.class, (name, args) -> name.equals("getVertexX") ? vertices : null);
		Tile tile = stub(Tile.class, (name, args) ->
		{
			if (name.equals("getBridge")) { bridges[0]++; return null; }
			return name.equals("getSceneTilePaint") ? paint : name.equals("getSceneTileModel") ? model : null;
		});
		Tile[][][] tiles = { { { tile } } };
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		WorldView view = stub(WorldView.class, (name, args) -> name.equals("getScene") ? scene : null);
		SceneCaptureTraversal membership = SceneCaptureTraversal.capture(null, view);
		TerrainCapture capture = new TerrainCapture();
		List<TileSurface> before = capture.capture(scene, 0, membership);
		int reads = bridges[0];
		rgb[0] = 2; vertices[0] = 512;
		List<TileSurface> after = capture.capture(scene, 0, membership);
		assertEquals(reads, bridges[0]);
		assertEquals(Integer.valueOf(2), after.get(0).getPaintRgb());
		assertEquals(512, after.get(0).getModelVertexX()[0]);
		assertEquals(Integer.valueOf(1), before.get(0).getPaintRgb());
		assertEquals(128, before.get(0).getModelVertexX()[0]);
	}

	@Test
	public void anUnresolvedExtendedGridCannotSupplyWrongDefinitionIds()
	{
		SceneTilePaint paint = stub(SceneTilePaint.class, (n, a) -> n.equals("getRBG") ? 0x123456 : null);
		Tile tile = stub(Tile.class, (n, a) -> n.equals("getSceneTilePaint") ? paint : null);
		short[][][] unrelated =
		{
			{
				{ 99, 99 },
				{ 99, 99 } } };
		Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? new Tile[][][]
		{
			{
				{ tile } } }
			: n.equals("getUnderlayIds") ? unrelated : null);
		TileSurface surface = new TerrainCapture().capture(scene, 0).get(0);
		assertNull(surface.getUnderlayId());
		assertEquals(Integer.valueOf(0x123456), surface.getPaintRgb());
	}

	@Test
	public void definitionIdsDecodeTheScenePlusOneEncoding()
	{
		SceneTilePaint paint = stub(SceneTilePaint.class, (n, a) -> null);
		Tile tile = stub(Tile.class, (n, a) -> n.equals("getSceneTilePaint") ? paint : null);
		short[][][] underlays =
		{
			{
				{ 8 } } },
			overlays =
			{
				{
					{ 0 } } };
		Scene scene = stub(Scene.class, (n, a) -> n.equals("getTiles") ? new Tile[][][]
		{
			{
				{ tile } } }
			: n.equals("getUnderlayIds") ? underlays : n.equals("getOverlayIds") ? overlays : null);
		TileSurface surface = new TerrainCapture().capture(scene, 0).get(0);
		assertEquals(Integer.valueOf(7), surface.getUnderlayId());
		assertEquals(Integer.valueOf(-1), surface.getOverlayId());
	}

	@Test
	public void nativeGridDimensionsAndFailedReadStateRemainDistinctAndImmutable()
	{
		boolean[] failed =
		{ false };
		Tile[][][] tiles =
		{ new Tile[2][3] };
		Scene scene = stub(Scene.class, (n, a) ->
		{
			if (n.equals("getTiles"))
			{
				if (failed[0])
					throw new IllegalStateException("Unavailable");
				return tiles;
			}
			return null;
		});
		TerrainCapture capture = new TerrainCapture();
		capture.capture(scene, 0);
		Map<String, Object> first = capture.coverage();
		assertEquals(2, first.get("gridWidth"));
		assertEquals(Arrays.asList(3, 3), first.get("columnHeights"));
		assertEquals(true, first.get("samplingCompleted"));
		failed[0] = true;
		try
		{
			capture.capture(scene, 0);
			fail("Expected unavailable API read");
		}
		catch (IllegalStateException expected)
		{
		}
		assertEquals(false, capture.coverage().get("samplingCompleted"));
		assertNull(capture.coverage().get("gridWidth"));
		assertEquals(2, first.get("gridWidth"));
	}

	@Test
	public void changingLoadedTerrainAndDenseActorsFitTheImportContract() throws Exception
	{
		final int[] currentTick =
		{ 0 };
		Tile[][][] tiles = new Tile[1][104][104];
		for (int x = 0; x < 104; x++)
			for (int y = 0; y < 104; y++)
			{
				final boolean changes = x < 3 && y < 18;
				SceneTilePaint paint = stub(SceneTilePaint.class,
					(name, args) -> name.equals("getRBG") ? (changes ? 0x331100 + currentTick[0] : 0x253329) : null);
				tiles[0][x][y] = stub(Tile.class, (name, args) -> name.equals("getSceneTilePaint") ? paint : null);
			}
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		TerrainCapture capture = new TerrainCapture();
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(100, new com.google.gson.Gson());
		List<Long> timings = new ArrayList<>();
		recorder.putScene(SceneData.builder().sceneKey("synthetic-terrain-load").worldViewId(-1).width(104).height(104)
			.plane(0).baseX(3000).baseY(3000).collision(Collections.emptyList()).build());
		for (int tick = 100; tick < 200; tick++)
		{
			currentTick[0] = tick;
			long start = System.nanoTime();
			List<TileSurface> terrain = capture.capture(scene, 0);
			timings.add(System.nanoTime() - start);
			List<NpcState> npcs = new ArrayList<>();
			for (int i = 0; i < 200; i++)
				npcs.add(NpcState.builder().sceneId(i).actorRef("npc:" + i + ":v-1:g" + i).worldViewId(-1).npcId(999991)
					.name("Synthetic target" + i).animationId(-1).poseAnimationId(-1).localPositionKnown(true)
					.localX((10 + i % 80) * 128 + 64).localY((20 + (i + tick) % 60) * 128 + 64).combatLevel(1).build());
			recorder.addSnapshot(WorldSnapshot.builder().tick(tick).wallClockMs(1700000000000L + (tick - 100) * 600L)
				.sceneKey("synthetic-terrain-load").worldViewId(-1).plane(0)
				.player(PlayerState.builder().worldViewId(-1).localPositionKnown(true).localX(6464).localY(6464).hp(99)
					.maxHp(99).prayer(99).maxPrayer(99).build())
				.npcs(npcs).players(Collections.emptyList()).hazards(Collections.emptyList())
				.projectiles(Collections.emptyList()).observedTerrain(terrain).build());
		}
		DeathRecordingV2 exported = recorder.buildExport(DeathRecordingV2.Meta.builder().snapshotTick(199).build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(10816, exported.getTicks().get(0).getTerrainChanges().size());
		assertEquals(54, exported.getTicks().get(1).getTerrainChanges().size());
		String payload = new com.google.gson.Gson().toJson(exported);
		assertTrue("Representative dense scene must fit25MiB importer",
			payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 25 * 1024 * 1024);
		java.nio.file.Path target = java.nio.file.Paths.get("build/test-fixtures/terrain-load.json");
		java.nio.file.Files.createDirectories(target.getParent());
		java.nio.file.Files.write(target, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Collections.sort(timings);
		System.out.println("Terrain capture synthetic proxy timings ms p50=" + timings.get(50) / 1000000.0 + " p95="
			+ timings.get(95) / 1000000.0 + " bytes=" + payload.length());
	}

	@Test
	public void paintChangesAndModelArraysAreImmutableAndUnchangedTilesShareState()
	{
		int[] rgb =
		{ 0x123456 }, vertices =
		{ 128, 256 };
		SceneTilePaint paint = stub(SceneTilePaint.class, (name, args) -> name.equals("getRBG") ? rgb[0] : null);
		SceneTileModel model = stub(SceneTileModel.class, (name, args) -> name.equals("getVertexX") ? vertices : null);
		Tile tile = stub(Tile.class,
			(name, args) -> name.equals("getSceneTilePaint") ? paint : name.equals("getSceneTileModel") ? model : null);
		Tile[][][] tiles =
		{
			{
				{ tile } } };
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		TerrainCapture capture = new TerrainCapture();
		List<TileSurface> first = capture.capture(scene, 0), second = capture.capture(scene, 0);
		assertSame(first, second);
		assertSame(first.get(0), second.get(0));
		rgb[0] = 0xff0000;
		vertices[0] = 512;
		List<TileSurface> third = capture.capture(scene, 0);
		assertNotSame(first, third);
		assertNotSame(first.get(0), third.get(0));
		assertEquals(Integer.valueOf(0x123456), first.get(0).getPaintRgb());
		assertEquals(128, first.get(0).getModelVertexX()[0]);
		assertEquals(Integer.valueOf(0xff0000), third.get(0).getPaintRgb());
		assertEquals(512, third.get(0).getModelVertexX()[0]);
	}

	@Test
	public void reusedTerrainStillReadsEveryPaintAndModelFieldAndOwnsChangedArrays()
	{
		Map<String, Object> paintValues = new HashMap<>(), modelValues = new HashMap<>();
		String[] paintInts =
		{ "getRBG", "getTexture", "getSwColor", "getSeColor", "getNwColor", "getNeColor" };
		String[] modelInts =
		{ "getModelUnderlay", "getModelOverlay", "getShape", "getRotation" };
		String[] modelArrays =
		{ "getVertexX", "getVertexY", "getVertexZ", "getFaceX", "getFaceY", "getFaceZ", "getTriangleColorA",
			"getTriangleColorB", "getTriangleColorC", "getTriangleTextureId" };
		for (String name : paintInts)
			paintValues.put(name, 1);
		for (String name : modelInts)
			modelValues.put(name, 1);
		for (String name : modelArrays)
			modelValues.put(name, new int[]
			{ 1, 2, 3 });
		SceneTilePaint paint = stub(SceneTilePaint.class, (name, args) -> paintValues.get(name));
		SceneTileModel model = stub(SceneTileModel.class, (name, args) -> modelValues.get(name));
		boolean[] paintPresent =
		{ true }, modelPresent =
		{ true };
		Tile tile = stub(Tile.class, (name, args) -> name.equals("getSceneTilePaint") ? (paintPresent[0] ? paint : null)
			: name.equals("getSceneTileModel") ? (modelPresent[0] ? model : null) : null);
		Tile[][][] tiles =
		{
			{
				{ tile } } };
		Scene scene = stub(Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
		TerrainCapture capture = new TerrainCapture();
		List<TileSurface> before = capture.capture(scene, 0);
		for (String name : paintInts)
		{
			paintValues.put(name, 17);
			List<TileSurface> after = capture.capture(scene, 0);
			assertNotSame(name, before, after);
			assertSame(after, capture.capture(scene, 0));
			before = after;
		}
		paintValues.put("isFlat", true);
		List<TileSurface> flat = capture.capture(scene, 0);
		assertNotSame(before, flat);
		before = flat;
		for (String name : modelInts)
		{
			modelValues.put(name, 17);
			List<TileSurface> after = capture.capture(scene, 0);
			assertNotSame(name, before, after);
			assertSame(after, capture.capture(scene, 0));
			before = after;
		}
		for (String name : modelArrays)
		{
			((int[]) modelValues.get(name))[0] = 17;
			List<TileSurface> after = capture.capture(scene, 0);
			assertNotSame(name, before, after);
			assertSame(after, capture.capture(scene, 0));
			before = after;
		}
		assertEquals(17, before.get(0).getModelTextures()[0]);
		((int[]) modelValues.get("getTriangleTextureId"))[0] = 99;
		assertEquals(17, before.get(0).getModelTextures()[0]);
		paintPresent[0] = false;
		List<TileSurface> noPaint = capture.capture(scene, 0);
		assertNull(noPaint.get(0).getPaintRgb());
		assertSame(noPaint, capture.capture(scene, 0));
		modelPresent[0] = false;
		assertTrue(capture.capture(scene, 0).isEmpty());
		paintPresent[0] = true;
		modelPresent[0] = true;
		List<TileSurface> restored = capture.capture(scene, 0);
		assertEquals(99, restored.get(0).getModelTextures()[0]);
	}

	@Test
	public void selectedWindowsRemovalsAndFailuresStartWithIndependentTerrainCheckpoints()
	{
		TileSurface red = TileSurface.builder().key("1:2:surface").x(1).y(2).paintRgb(0xff0000).build();
		List<WorldSnapshot> ticks = Arrays.asList(frame(1, Collections.singletonList(red)),
			frame(2, Collections.emptyList()), frame(3, null), frame(4, Collections.singletonList(red)));
		List<WorldSnapshot> exported = WorldStateExport.compact(ticks);
		assertTrue(exported.get(0).getTerrainBaseline());
		assertEquals(Collections.singletonList(red.getKey()), exported.get(1).getTerrainRemovals());
		assertNull(exported.get(2).getTerrainBaseline());
		assertTrue(exported.get(3).getTerrainBaseline());
		assertTrue(WorldStateExport.compact(ticks.subList(1, 2)).get(0).getTerrainBaseline());
		assertTrue(exported.get(0).getTerrainChanges().size() == 1);
		assertNull(exported.get(0).getObservedTerrain());
		assertNotNull(ticks.get(0).getObservedTerrain());
	}

	private WorldSnapshot frame(int tick, List<TileSurface> terrain)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("s").observedTerrain(terrain).build();
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, m, args) ->
		{
			Object v = values.apply(m.getName(), args);
			if (v != null)
				return v;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return 0;
			return null;
		});
	}
}
