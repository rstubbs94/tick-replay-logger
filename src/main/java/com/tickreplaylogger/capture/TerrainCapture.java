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

import com.tickreplaylogger.model.world.TileSurface;
import java.util.*;
import java.util.List;
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
		nativeGridAvailable = false;
		clearCoverage();
	}

	public boolean isNativeGridAvailable()
	{
		return nativeGridAvailable;
	}

	public List<TileSurface> capture(Scene scene, int plane)
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
		}
		List<TileSurface> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int visited = 0;
		short[][][] underlays = scene.getUnderlayIds(), overlays = scene.getOverlayIds();
		byte[][][] shapes = scene.getTileShapes();
		if (tiles != null && tiles.length > 1024)
		{
			throw new IllegalStateException("Terrain width exceeds supported bounds");
		}
		for (int x = 0; tiles != null && x < tiles.length; x++)
		{
			if (tiles[x] != null)
			{
				if (tiles[x].length > 1024)
				{
					throw new IllegalStateException("Terrain height exceeds supported bounds");
				}
				for (int y = 0; y < tiles[x].length; y++)
				{
					if (++visited > 32768)
					{
						throw new IllegalStateException("Terrain scope exceeds supported tile count");
					}
					Tile tile = tiles[x][y];
					if (tile == null)
					{
						continue;
					}
					add(tile, x, y, tiles.length, tiles[x].length, false, underlays, overlays, shapes, out, seen);
					Tile bridge = tile.getBridge();
					if (bridge != null && bridge != tile)
					{
						add(bridge, x, y, tiles.length, tiles[x].length, true, underlays, overlays, shapes, out, seen);
					}
				}
			}
		}
		// A bridge link can be the only API route to a tile on another reported plane.
		// Retain its source path, while treating it as canonical for that plane when no array tile exists.
		Set<Tile> chain = Collections.newSetFromMap(new IdentityHashMap<>(4));
		for (int sourcePlane = 0; sourcePlane < all.length; sourcePlane++)
		{
			Tile[][] source = all[sourcePlane];
			if (source == null)
			{
				continue;
			}
			if (source.length > 1024)
			{
				throw new IllegalStateException("Linked terrain width exceeds bounds");
			}
			int inspected = 0;
			for (int x = 0; x < source.length; x++)
			{
				if (source[x] != null)
				{
					if (source[x].length > 1024)
					{
						throw new IllegalStateException("Linked terrain height exceeds bounds");
					}
					for (int y = 0; y < source[x].length; y++)
					{
						if (++inspected > 32768)
						{
							throw new IllegalStateException("Linked terrain scope exceeds bounds");
						}
						if (tiles != null && x < tiles.length && tiles[x] != null && y < tiles[x].length
							&& tiles[x][y] != null)
						{
							continue;
						}
						Tile candidate = source[x][y];
						if (candidate == null)
						{
							continue;
						}
						boolean linked = false;
						chain.clear();
						while (candidate != null && chain.add(candidate))
						{
							if (candidate.getPlane() == plane)
							{
								add(candidate, x, y, source.length, source[x].length, false, underlays, overlays,
									shapes, out, seen, sourcePlane, linked, "LINKED_CANONICAL_PLANE_TILE");
								break;
							}
							candidate = candidate.getBridge();
							linked = true;
						}
					}
				}
			}
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
