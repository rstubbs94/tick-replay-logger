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

/** Immutable terrain snapshots; no object-name, boss or hazard whitelist. */
public final class TerrainCapture
{
	private final Map<String, TileSurface> previous = new HashMap<>();
	private final String[][] coordinateKeys = new String[1024][];
	private Scene previousScene;
	private int previousPlane = -1;
	private boolean nativeGridAvailable;
	private List<TileSurface> previousSnapshot;
	private SceneCaptureTraversal membership;
	private List<SurfaceSample> samples;
	private Map<String, Object> coverage = new LinkedHashMap<>();

	public void clearCoverage()
	{
		coverage = new LinkedHashMap<>();
		coverage.put("samplingCompleted", false);
	}

	public Map<String, Object> coverage()
	{
		return Collections.unmodifiableMap(coverage);
	}

	public void reset()
	{
		previous.clear();
		Arrays.fill(coordinateKeys, null);
		previousScene = null;
		previousPlane = -1;
		previousSnapshot = null;
		membership = null;
		samples = null;
		nativeGridAvailable = false;
		clearCoverage();
	}

	public boolean isNativeGridAvailable()
	{
		return nativeGridAvailable;
	}

	public List<TileSurface> capture(Scene scene, int plane)
	{
		return capture(scene, plane, null);
	}

	List<TileSurface> capture(Scene scene, int plane, SceneCaptureTraversal traversal)
	{
		nativeGridAvailable = false;
		clearCoverage();
		coverage.put("scope", "SCENE_TILE_ARRAY_AND_BRIDGE_LINKS");
		coverage.put("selectedPlane", plane);
		coverage.put("nativeGridAvailable", false);
		if (scene == null || scene.getTiles() == null || plane < 0)
		{
			return null;
		}
		Tile[][][] all = scene.getTiles();
		Tile[][] tiles = plane < all.length ? all[plane] : null;
		nativeGridAvailable = tiles != null;
		coverage.put("nativeGridAvailable", nativeGridAvailable);
		coverage.put("gridWidth", tiles == null ? null : tiles.length);
		List<Integer> heights = null;
		if (tiles != null)
		{
			heights = new ArrayList<>();
			for (Tile[] column : tiles)
			{
				heights.add(column == null ? null : column.length);
			}
		}
		coverage.put("columnHeights", heights == null ? null : Collections.unmodifiableList(heights));
		if (previousScene != scene || previousPlane != plane)
		{
			previous.clear();
			Arrays.fill(coordinateKeys, null);
			previousSnapshot = null;
			previousScene = scene;
			previousPlane = plane;
			samples = null;
		}
		List<TileSurface> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		short[][][] underlays = scene.getUnderlayIds(), overlays = scene.getOverlayIds();
		byte[][][] shapes = scene.getTileShapes();
		if (samples == null || traversal == null || membership != traversal || previousPlane != plane)
		{
			samples = select(scene, plane, all, tiles, traversal);
			membership = traversal;
		}
		for (SurfaceSample sample : samples)
		{
			add(sample.tile, sample.x, sample.y, sample.width, sample.height, sample.bridge, underlays, overlays,
				shapes, out, seen, sample.sourcePlane, sample.viaBridge, sample.evidence);
		}
		coverage.put("samplingCompleted", true);
		if (!nativeGridAvailable && out.isEmpty())
		{
			return null;
		}
		previous.keySet().retainAll(seen);
		if (previousSnapshot != null && out.equals(previousSnapshot))
		{
			return previousSnapshot;
		}
		previousSnapshot = Collections.unmodifiableList(out);
		return previousSnapshot;
	}

	private static final class SurfaceSample
	{
		final Tile tile;
		final int x, y, width, height, sourcePlane;
		final boolean bridge, viaBridge;
		final String evidence;

		SurfaceSample(Tile tile, int x, int y, int width, int height, boolean bridge, int sourcePlane,
			boolean viaBridge, String evidence)
		{
			this.tile = tile;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.bridge = bridge;
			this.sourcePlane = sourcePlane;
			this.viaBridge = viaBridge;
			this.evidence = evidence;
		}
	}

	private List<SurfaceSample> select(Scene scene, int plane, Tile[][][] all, Tile[][] selected,
		SceneCaptureTraversal traversal)
	{
		List<SurfaceSample> result = new ArrayList<>();
		for (int sourcePlane = 0; sourcePlane < all.length; sourcePlane++)
		{
			Tile[][] grid = all[sourcePlane];
			if (grid == null)
			{
				continue;
			}
			int count = 0;
			if (grid.length > 1024)
			{
				throw new IllegalStateException("Terrain width exceeds supported bounds");
			}
			for (Tile[] column : grid)
			{
				if (column != null)
				{
					count += column.length;
					if (column.length > 1024 || count > 32768)
					{
						throw new IllegalStateException("Terrain scope exceeds supported bounds");
					}
				}
			}
		}
		// Standalone callers seed their own membership; runtime supplies its lifecycle-maintained census.
		List<SceneCaptureTraversal.TileSlot> slots;
		if (traversal == null)
		{
			slots = new ArrayList<>();
			for (int p = 0; p < all.length; p++)
			{
				if (all[p] != null)
				{
					for (int x = 0; x < all[p].length; x++)
					{
						if (all[p][x] != null)
						{
							for (int y = 0; y < all[p][x].length; y++)
							{
								if (all[p][x][y] != null)
								{
									slots.add(new SceneCaptureTraversal.TileSlot(all[p][x][y], p, x, y));
								}
							}
						}
					}
				}
			}
		}
		else
		{
			slots = traversal.slots;
		}
		if (traversal != null && traversal.bridgeFailures > 0)
		{
			throw new IllegalStateException("Terrain bridge membership unavailable");
		}
		for (SceneCaptureTraversal.TileSlot slot : slots)
		{
			int x = slot.x, y = slot.y, p = slot.plane;
			Tile[][] grid = all[p];
			if (p != plane)
			{
				continue;
			}
			result.add(new SurfaceSample(slot.tile, x, y, grid.length, grid[x].length, false, plane, false,
				"SCENE_ARRAY_TILE"));
			Tile bridge = traversal == null ? slot.tile.getBridge() : traversal.bridge(slot.tile);
			if (bridge != null && bridge != slot.tile)
			{
				result.add(
					new SurfaceSample(bridge, x, y, grid.length, grid[x].length, true, plane, true, "BRIDGE_UNDERLAY"));
			}
		}
		Set<Tile> chain = Collections.newSetFromMap(new IdentityHashMap<>());
		for (SceneCaptureTraversal.TileSlot slot : slots)
		{
			int x = slot.x, y = slot.y, p = slot.plane;
			if (selected != null && x < selected.length && selected[x] != null && y < selected[x].length
				&& selected[x][y] != null)
			{
				continue;
			}
			Tile candidate = slot.tile;
			boolean linked = false;
			chain.clear();
			while (candidate != null && chain.add(candidate))
			{
				if ((traversal == null ? candidate.getPlane() : traversal.reportedPlane(candidate)) == plane)
				{
					result.add(new SurfaceSample(candidate, x, y, all[p].length, all[p][x].length, false, p, linked,
						"LINKED_CANONICAL_PLANE_TILE"));
					break;
				}
				candidate = traversal == null ? candidate.getBridge() : traversal.bridge(candidate);
				linked = true;
			}
		}
		return result;
	}

	private void add(Tile tile, int x, int y, int width, int height, boolean bridge, short[][][] underlays,
		short[][][] overlays, byte[][][] shapes, List<TileSurface> out, Set<String> seen)
	{
		add(tile, x, y, width, height, bridge, underlays, overlays, shapes, out, seen, previousPlane, bridge,
			bridge ? "BRIDGE_UNDERLAY" : "SCENE_ARRAY_TILE");
	}

	private void add(Tile tile, int x, int y, int width, int height, boolean bridge, short[][][] underlays,
		short[][][] overlays, byte[][][] shapes, List<TileSurface> out, Set<String> seen, int sourcePlane,
		boolean viaBridge, String evidence)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();
		int plane = tile.getPlane();
		// Extended grids may use a different origin. Without an established transform,
		// preserve paint/model samples and leave definition IDs unavailable.
		Integer underlay = at(underlays, plane, x, y, width, height),
			overlay = at(overlays, plane, x, y, width, height), shape = at(shapes, plane, x, y, width, height);
		if (paint == null && model == null && (underlay == null || underlay == 0) && (overlay == null || overlay == 0))
		{
			return;
		}
		String key = coordinateKey(x, y, bridge);
		if (seen.contains(key))
		{
			return;
		}
		TileSurface old = previous.get(key);
		// API models/paints can mutate in place. Compare their current values to our owned
		// snapshot before allocating a builder and copying every geometry/color array.
		if (matches(old, tile, plane, bridge, sourcePlane, viaBridge, evidence, underlay, overlay, shape, paint, model))
		{
			seen.add(key);
			out.add(old);
			return;
		}
		TileSurface next = TileSurface.builder().key(key).x(x).y(y).plane(plane).renderLevel(tile.getRenderLevel())
			.bridge(bridge).sampledSceneArrayPlane(sourcePlane).observedViaBridge(viaBridge).sampleEvidence(evidence)
			// Scene ID grids use zero for absent and definition ID + 1 otherwise.
			.underlayId(definitionId(underlay)).overlayId(definitionId(overlay)).tileShape(shape)
			.paintRgb(paint == null ? null : paint.getRBG()).paintTexture(paint == null ? null : paint.getTexture())
			.paintCorners(paint == null ? null : new int[]
			{ paint.getSwColor(), paint.getSeColor(), paint.getNwColor(), paint.getNeColor() })
			.paintFlat(paint == null ? null : paint.isFlat())
			.modelUnderlay(model == null ? null : model.getModelUnderlay())
			.modelOverlay(model == null ? null : model.getModelOverlay())
			.modelShape(model == null ? null : model.getShape())
			.modelRotation(model == null ? null : model.getRotation())
			.modelVertexX(model == null ? null : copy(model.getVertexX()))
			.modelVertexY(model == null ? null : copy(model.getVertexY()))
			.modelVertexZ(model == null ? null : copy(model.getVertexZ()))
			.modelFaceX(model == null ? null : copy(model.getFaceX()))
			.modelFaceY(model == null ? null : copy(model.getFaceY()))
			.modelFaceZ(model == null ? null : copy(model.getFaceZ()))
			.modelColorA(model == null ? null : copy(model.getTriangleColorA()))
			.modelColorB(model == null ? null : copy(model.getTriangleColorB()))
			.modelColorC(model == null ? null : copy(model.getTriangleColorC()))
			.modelTextures(model == null ? null : copy(model.getTriangleTextureId())).build();
		previous.put(key, next);
		seen.add(key);
		out.add(next);
	}

	private static boolean matches(TileSurface old, Tile tile, int plane, boolean bridge, int sourcePlane,
		boolean viaBridge, String evidence, Integer underlay, Integer overlay, Integer shape, SceneTilePaint paint,
		SceneTileModel model)
	{
		if (old == null || old.getPlane() != plane || old.getRenderLevel() != tile.getRenderLevel()
			|| old.isBridge() != bridge || !same(old.getSampledSceneArrayPlane(), sourcePlane)
			|| !Objects.equals(old.getObservedViaBridge(), viaBridge)
			|| !Objects.equals(old.getSampleEvidence(), evidence)
			|| !Objects.equals(old.getUnderlayId(), definitionId(underlay))
			|| !Objects.equals(old.getOverlayId(), definitionId(overlay)) || !Objects.equals(old.getTileShape(), shape))
		{
			return false;
		}
		if (paint == null)
		{
			if (old.getPaintRgb() != null)
			{
				return false;
			}
		}
		else
		{
			int[] corners = old.getPaintCorners();
			if (!same(old.getPaintRgb(), paint.getRBG()) || !same(old.getPaintTexture(), paint.getTexture())
				|| !Objects.equals(old.getPaintFlat(), paint.isFlat()) || corners == null || corners.length != 4
				|| corners[0] != paint.getSwColor() || corners[1] != paint.getSeColor()
				|| corners[2] != paint.getNwColor() || corners[3] != paint.getNeColor())
			{
				return false;
			}
		}
		if (model == null)
		{
			return old.getModelUnderlay() == null;
		}
		return same(old.getModelUnderlay(), model.getModelUnderlay())
			&& same(old.getModelOverlay(), model.getModelOverlay()) && same(old.getModelShape(), model.getShape())
			&& same(old.getModelRotation(), model.getRotation())
			&& Arrays.equals(old.getModelVertexX(), model.getVertexX())
			&& Arrays.equals(old.getModelVertexY(), model.getVertexY())
			&& Arrays.equals(old.getModelVertexZ(), model.getVertexZ())
			&& Arrays.equals(old.getModelFaceX(), model.getFaceX())
			&& Arrays.equals(old.getModelFaceY(), model.getFaceY())
			&& Arrays.equals(old.getModelFaceZ(), model.getFaceZ())
			&& Arrays.equals(old.getModelColorA(), model.getTriangleColorA())
			&& Arrays.equals(old.getModelColorB(), model.getTriangleColorB())
			&& Arrays.equals(old.getModelColorC(), model.getTriangleColorC())
			&& Arrays.equals(old.getModelTextures(), model.getTriangleTextureId());
	}

	private static boolean same(Integer previous, int current)
	{
		return previous != null && previous == current;
	}

	/** Coordinates are bounded by the validated grid; cache labels, never live terrain values. */
	private String coordinateKey(int x, int y, boolean bridge)
	{
		int slot = 2 * y + (bridge ? 1 : 0);
		String[] column = coordinateKeys[x];
		if (column == null || slot >= column.length)
		{
			column = column == null ? new String[slot + 1]
				: Arrays.copyOf(column, Math.max(slot + 1, Math.min(2048, column.length * 2)));
			coordinateKeys[x] = column;
		}
		String key = column[slot];
		if (key == null)
		{
			column[slot] = key = x + ":" + y + ":" + (bridge ? "bridge" : "surface");
		}
		return key;
	}

	private static int[] copy(int[] value)
	{
		return value == null ? null : value.clone();
	}

	private static Integer definitionId(Integer encoded)
	{
		return encoded == null ? null : encoded - 1;
	}

	private static Integer at(short[][][] grid, int z, int x, int y, int width, int height)
	{
		return grid != null && z >= 0 && z < grid.length && grid[z] != null && grid[z].length == width
			&& x < grid[z].length && grid[z][x] != null && grid[z][x].length == height && y < grid[z][x].length
				? grid[z][x][y] & 0xffff
				: null;
	}

	private static Integer at(byte[][][] grid, int z, int x, int y, int width, int height)
	{
		return grid != null && z >= 0 && z < grid.length && grid[z] != null && grid[z].length == width
			&& x < grid[z].length && grid[z][x] != null && grid[z][x].length == height && y < grid[z][x].length
				? grid[z][x][y] & 0xff
				: null;
	}
}
