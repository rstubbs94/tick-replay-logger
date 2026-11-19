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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;

/** One current native-view census shared by discovery and capture; never reused across ticks. */
final class SceneCaptureTraversal
{
	static final class ObjectRow
	{
		final String kind;
		final TileObject object;

		ObjectRow(String kind, TileObject object)
		{
			this.kind = kind;
			this.object = object;
		}
	}

	static final class PlaneEvidence
	{
		final int plane;
		final String reason;

		PlaneEvidence(int plane, String reason)
		{
			this.plane = plane;
			this.reason = reason;
		}
	}

	final Client client;
	final WorldView view;
	final Tile[][][] arrays;
	final List<Tile> tiles;
	final int bridgeFailures;
	final Map<Integer, Integer> arrayTileCounts;
	final String arrayIssue;
	private Map<Integer, List<ObjectRow>> objects;
	private int objectFailures;
	private Map<Integer, List<Tile>> byPlane;
	private int[] tilePlanes;
	private int tileFailures;
	private final List<PlaneEvidence> planeEvidence = new ArrayList<>();
	private final Map<Integer, Set<String>> planeReasons = new HashMap<>();
	private final List<String> planeIssues = new ArrayList<>();

	private SceneCaptureTraversal(Client client, WorldView view, Tile[][][] arrays, List<Tile> tiles, int failures,
		Map<Integer, Integer> counts, String arrayIssue)
	{
		this.client = client;
		this.view = view;
		this.arrays = arrays;
		this.tiles = tiles;
		this.bridgeFailures = failures;
		this.arrayTileCounts = counts;
		this.arrayIssue = arrayIssue;
	}

	static SceneCaptureTraversal capture(Client client, WorldView view)
	{
		Tile[][][] arrays;
		try
		{
			Scene scene = view == null ? client.getScene() : view.getScene();
			arrays = scene == null ? null : scene.getTiles();
		}
		catch (RuntimeException unavailable)
		{
			return unavailable(client, view, "worldPlanes: scene " + unavailable.getClass().getSimpleName());
		}
		if (arrays == null)
			return unavailable(client, view, "worldPlanes: scene arrays unavailable");
		Set<Tile> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.Deque<Tile> pending = new ArrayDeque<>();
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (int plane = 0; plane < arrays.length; plane++)
		{
			Tile[][] level = arrays[plane];
			if (level == null)
				continue;
			int count = 0;
			for (Tile[] column : level)
				if (column != null)
					for (Tile tile : column)
						if (tile != null)
						{
							count++;
							if (seen.add(tile))
								pending.add(tile);
						}
			counts.put(plane, count);
		}
		List<Tile> tiles = new ArrayList<>(seen.size());
		int failures = 0;
		while (!pending.isEmpty())
		{
			Tile tile = pending.removeFirst();
			tiles.add(tile);
			try
			{
				Tile bridge = tile.getBridge();
				if (bridge != null && seen.add(bridge))
					pending.addLast(bridge);
			}
			catch (RuntimeException unavailable)
			{
				failures++;
			}
		}
		return new SceneCaptureTraversal(client, view, arrays, Collections.unmodifiableList(tiles), failures,
			Collections.unmodifiableMap(counts), null);
	}

	private static SceneCaptureTraversal unavailable(Client client, WorldView view, String reason)
	{
		return new SceneCaptureTraversal(client, view, null, Collections.emptyList(), 1, Collections.emptyMap(),
			reason);
	}

	boolean matches(Client client, WorldView view)
	{
		return this.client == client && this.view == view;
	}

	boolean planeArrayAvailable(int plane)
	{
		return arrays != null && plane >= 0 && plane < arrays.length && arrays[plane] != null;
	}

	List<ObjectRow> objects(int plane)
	{
		if (objects == null)
		{
			objects = new HashMap<>();
			objectFailures = bridgeFailures;
			indexTilePlanes();
			Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (int index = 0; index < tiles.size(); index++)
			{
				Tile tile = tiles.get(index);
				if (tilePlanes[index] != Integer.MIN_VALUE)
					recordPlane(tilePlanes[index], "TILE_REPORTED_PLANE");
				try
				{
					add(seen, "groundObject", tile.getGroundObject());
				}
				catch (RuntimeException unavailable)
				{
					objectFailure("ground object");
				}
				try
				{
					add(seen, "wallObject", tile.getWallObject());
				}
				catch (RuntimeException unavailable)
				{
					objectFailure("wall object");
				}
				try
				{
					add(seen, "decorativeObject", tile.getDecorativeObject());
				}
				catch (RuntimeException unavailable)
				{
					objectFailure("decoration");
				}
				try
				{
					GameObject[] games = tile.getGameObjects();
					if (games != null)
						for (GameObject game : games)
						{
							try
							{
								add(seen, "gameObject", game);
							}
							catch (RuntimeException unavailable)
							{
								objectFailure("game object");
							}
						}
				}
				catch (RuntimeException unavailable)
				{
					objectFailure("game object");
				}
			}
		}
		return objects.getOrDefault(plane, Collections.emptyList());
	}

	private void objectFailure(String kind)
	{
		objectFailures++;
		planeIssues.add("worldPlanes: " + kind + " unavailable");
	}

	private void add(Set<TileObject> seen, String kind, TileObject object)
	{
		if (object == null || seen.contains(object))
			return;
		int plane = object.getPlane();
		// Discovery retains a reported plane even when its object cannot join this native view.
		recordPlane(plane, "OBJECT_REPORTED_PLANE");
		if (view != null)
		{
			try
			{
				if (object.getWorldView() != view)
					return;
			}
			catch (RuntimeException unavailable)
			{
				objectFailures++;
				return;
			}
		}
		seen.add(object);
		objects.computeIfAbsent(plane, ignored -> new ArrayList<>()).add(new ObjectRow(kind, object));
	}

	private void recordPlane(int plane, String reason)
	{
		if (planeReasons.computeIfAbsent(plane, ignored -> new HashSet<>()).add(reason))
			planeEvidence.add(new PlaneEvidence(plane, reason));
	}

	List<PlaneEvidence> planeEvidence()
	{
		objects(0);
		return Collections.unmodifiableList(planeEvidence);
	}

	List<String> planeIssues()
	{
		objects(0);
		List<String> issues = new ArrayList<>(planeIssues);
		if (arrays != null)
			for (int i = 0; i < bridgeFailures; i++)
				issues.add("worldPlanes: bridge unavailable");
		if (tilePlanes != null)
			for (int plane : tilePlanes)
				if (plane == Integer.MIN_VALUE)
					issues.add("worldPlanes: tile plane unavailable");
		return issues;
	}

	int objectFailures()
	{
		return objectFailures;
	}

	private void indexTilePlanes()
	{
		if (byPlane != null)
			return;
		byPlane = new HashMap<>();
		tileFailures = bridgeFailures;
		tilePlanes = new int[tiles.size()];
		for (int index = 0; index < tiles.size(); index++)
		{
			Tile tile = tiles.get(index);
			try
			{
				int plane = tile.getPlane();
				tilePlanes[index] = plane;
				byPlane.computeIfAbsent(plane, ignored -> new ArrayList<>()).add(tile);
			}
			catch (RuntimeException unavailable)
			{
				tilePlanes[index] = Integer.MIN_VALUE;
				tileFailures++;
			}
		}
	}

	List<Tile> tiles(int plane)
	{
		indexTilePlanes();
		return byPlane.getOrDefault(plane, Collections.emptyList());
	}

	int tileFailures()
	{
		return tileFailures;
	}
}
