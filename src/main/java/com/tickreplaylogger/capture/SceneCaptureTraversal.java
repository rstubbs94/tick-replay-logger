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

import java.util.*;
import java.util.List;
import net.runelite.api.*;

/** Native scene membership, seeded once and refreshed only for lifecycle-event tiles. */
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
	final Scene scene;
	final List<TileSlot> slots;
	final List<Tile> tiles;
	private final IdentityHashMap<Tile, Integer> tileIndices = new IdentityHashMap<>();
	final int bridgeFailures;
	final Map<Integer, Integer> arrayTileCounts;
	final String arrayIssue;
	private Map<Integer, List<ObjectRow>> objects;
	private Map<Integer, List<ItemRow>> itemsByPlane = new HashMap<>();
	private final IdentityHashMap<Tile, Tile> bridges;
	private final IdentityHashMap<Tile, List<ObjectRow>> tileObjects = new IdentityHashMap<>();
	private final IdentityHashMap<Tile, Map<String, String>> objectIssues = new IdentityHashMap<>();
	private final IdentityHashMap<Tile, List<TileItem>> tileItems = new IdentityHashMap<>();
	private final Set<Tile> itemIssues = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Tile> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
	private int objectFailures;
	private Map<Integer, List<Tile>> byPlane;
	private int[] tilePlanes;
	private int tileFailures;
	private final List<PlaneEvidence> planeEvidence = new ArrayList<>();
	private final Map<Integer, Set<String>> planeReasons = new HashMap<>();
	private final List<String> planeIssues = new ArrayList<>();

	private SceneCaptureTraversal(Client client, WorldView view, Scene scene, Tile[][][] arrays, List<TileSlot> slots,
		List<Tile> tiles, int failures, Map<Integer, Integer> counts, String arrayIssue,
		IdentityHashMap<Tile, Tile> bridges)
	{
		this.client = client;
		this.view = view;
		this.arrays = arrays;
		this.scene = scene;
		this.slots = slots;
		this.tiles = tiles;
		for (int index = 0; index < tiles.size(); index++)
		{
			tileIndices.put(tiles.get(index), index);
		}
		this.bridgeFailures = failures;
		this.arrayTileCounts = counts;
		this.arrayIssue = arrayIssue;
		this.bridges = bridges;
	}

	static SceneCaptureTraversal capture(Client client, WorldView view)
	{
		Tile[][][] arrays;
		Scene scene;
		try
		{
			scene = view == null ? client.getScene() : view.getScene();
			arrays = scene == null ? null : scene.getTiles();
		}
		catch (RuntimeException unavailable)
		{
			return unavailable(client, view, "worldPlanes: scene " + unavailable.getClass().getSimpleName());
		}
		if (arrays == null)
		{
			return unavailable(client, view, "worldPlanes: scene arrays unavailable");
		}
		Set<Tile> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.Deque<Tile> pending = new ArrayDeque<>();
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		List<TileSlot> slots = new ArrayList<>();
		for (int plane = 0; plane < arrays.length; plane++)
		{
			Tile[][] level = arrays[plane];
			if (level == null)
			{
				continue;
			}
			int count = 0;
			for (int x = 0; x < level.length; x++)
			{
				Tile[] column = level[x];
				if (column != null)
				{
					for (int y = 0; y < column.length; y++)
					{
						Tile tile = column[y];
						if (tile != null)
						{
							count++;
							slots.add(new TileSlot(tile, plane, x, y));
							if (seen.add(tile))
							{
								pending.add(tile);
							}
						}
					}
				}
			}
			counts.put(plane, count);
		}
		List<Tile> tiles = new ArrayList<>(seen.size());
		IdentityHashMap<Tile, Tile> bridges = new IdentityHashMap<>();
		int failures = 0;
		while (!pending.isEmpty())
		{
			Tile tile = pending.removeFirst();
			tiles.add(tile);
			try
			{
				Tile bridge = tile.getBridge();
				bridges.put(tile, bridge);
				if (bridge != null && seen.add(bridge))
				{
					pending.addLast(bridge);
				}
			}
			catch (RuntimeException unavailable)
			{
				failures++;
			}
		}
		return new SceneCaptureTraversal(client, view, scene, arrays, Collections.unmodifiableList(slots),
			Collections.unmodifiableList(tiles), failures, Collections.unmodifiableMap(counts), null, bridges);
	}

	private static SceneCaptureTraversal unavailable(Client client, WorldView view, String reason)
	{
		return new SceneCaptureTraversal(client, view, null, null, Collections.emptyList(), Collections.emptyList(), 1,
			Collections.emptyMap(), reason, new IdentityHashMap<>());
	}

	boolean matches(Client client, WorldView view)
	{
		return this.client == client && this.view == view;
	}

	boolean planeArrayAvailable(int plane)
	{
		return arrays != null && plane >= 0 && plane < arrays.length && arrays[plane] != null;
	}

	static final class TileSlot
	{
		final Tile tile;
		final int plane, x, y;

		TileSlot(Tile tile, int plane, int x, int y)
		{
			this.tile = tile;
			this.plane = plane;
			this.x = x;
			this.y = y;
		}
	}

	static final class ItemRow
	{
		final Tile tile;
		final TileItem item;

		ItemRow(Tile tile, TileItem item)
		{
			this.tile = tile;
			this.item = item;
		}
	}

	boolean current(Client client, WorldView view)
	{
		try
		{
			Scene current = view == null ? client.getScene() : view.getScene();
			return arrays != null && bridgeFailures == 0 && tileFailures == bridgeFailures && this.scene == current
				&& current != null && current.getTiles() == arrays;
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	boolean changed(Tile tile)
	{
		if (!tileIndices.containsKey(tile))
		{
			return false;
		}
		dirty.add(tile);
		return true;
	}

	private void refresh()
	{
		if (dirty.isEmpty())
		{
			return;
		}
		for (Tile tile : dirty)
		{
			tileObjects.remove(tile);
			objectIssues.remove(tile);
			tileItems.remove(tile);
			itemIssues.remove(tile);
		}
		dirty.clear();
		objects = null;
		itemsByPlane.clear();
	}

	List<ObjectRow> objects(int plane)
	{
		refresh();
		if (objects == null)
		{
			objects = new HashMap<>();
			objectFailures = bridgeFailures;
			indexTilePlanes();
			planeEvidence.clear();
			planeReasons.clear();
			planeIssues.clear();
			Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (int index = 0; index < tiles.size(); index++)
			{
				Tile tile = tiles.get(index);
				if (tilePlanes[index] != Integer.MIN_VALUE)
				{
					recordPlane(tilePlanes[index], "TILE_REPORTED_PLANE");
				}
				for (ObjectRow row : objectRows(tile))
				{
					try
					{
						add(seen, row.kind, row.object);
					}
					catch (RuntimeException unavailable)
					{
						objectFailure("game object");
					}
				}
				Map<String, String> issues = objectIssues.get(tile);
				if (issues != null)
				{
					for (String kind : issues.keySet())
					{
						objectFailure(kind);
					}
				}
			}
		}
		return objects.getOrDefault(plane, Collections.emptyList());
	}

	List<ObjectRow> objectRows(Tile tile)
	{
		refresh();
		List<ObjectRow> rows = tileObjects.get(tile);
		if (rows != null)
		{
			return rows;
		}
		rows = new ArrayList<>();
		readObject(tile, rows, "groundObject", "ground object", tile::getGroundObject);
		readObject(tile, rows, "wallObject", "wall object", tile::getWallObject);
		readObject(tile, rows, "decorativeObject", "decoration", tile::getDecorativeObject);
		try
		{
			GameObject[] games = tile.getGameObjects();
			if (games != null)
			{
				for (GameObject game : games)
				{
					if (game != null)
					{
						rows.add(new ObjectRow("gameObject", game));
					}
				}
			}
		}
		catch (RuntimeException unavailable)
		{
			objectIssues.computeIfAbsent(tile, key -> new LinkedHashMap<>()).put("game object",
				unavailable.getClass().getSimpleName());
		}
		tileObjects.put(tile, rows);
		return rows;
	}

	private void readObject(Tile tile, List<ObjectRow> rows, String kind, String issue,
		java.util.function.Supplier<TileObject> read)
	{
		try
		{
			TileObject object = read.get();
			if (object != null)
			{
				rows.add(new ObjectRow(kind, object));
			}
		}
		catch (RuntimeException unavailable)
		{
			objectIssues.computeIfAbsent(tile, key -> new LinkedHashMap<>()).put(issue,
				unavailable.getClass().getSimpleName());
		}
	}

	List<ItemRow> items(int plane)
	{
		refresh();
		List<ItemRow> rows = itemsByPlane.get(plane);
		if (rows != null)
		{
			return rows;
		}
		rows = new ArrayList<>();
		for (Tile tile : tiles(plane))
		{
			List<TileItem> items = tileItems.get(tile);
			if (items == null)
			{
				items = new ArrayList<>();
				try
				{
					List<TileItem> current = tile.getGroundItems();
					if (current != null)
					{
						items.addAll(current);
					}
					else if (tile.getItemLayer() != null)
					{
						itemIssues.add(tile);
					}
				}
				catch (RuntimeException unavailable)
				{
					itemIssues.add(tile);
				}
				tileItems.put(tile, items);
			}
			for (TileItem item : items)
			{
				if (item != null)
				{
					rows.add(new ItemRow(tile, item));
				}
			}
		}
		itemsByPlane.put(plane, rows);
		return rows;
	}

	Tile bridge(Tile tile)
	{
		return bridges.get(tile);
	}

	int reportedPlane(Tile tile)
	{
		indexTilePlanes();
		Integer index = tileIndices.get(tile);
		return index == null ? Integer.MIN_VALUE : tilePlanes[index];
	}

	Map<String, String> objectErrors(Tile tile)
	{
		return objectIssues.getOrDefault(tile, Collections.emptyMap());
	}

	int itemFailures(int plane)
	{
		int failures = 0;
		for (Tile tile : tiles(plane))
		{
			if (itemIssues.contains(tile))
			{
				failures++;
			}
		}
		return failures;
	}

	void retryFailures()
	{
		if (objectFailures > bridgeFailures)
		{
			objects = null;
		}
		dirty.addAll(objectIssues.keySet());
		dirty.addAll(itemIssues);
		refresh();
	}

	private void objectFailure(String kind)
	{
		objectFailures++;
		planeIssues.add("worldPlanes: " + kind + " unavailable");
	}

	private void add(Set<TileObject> seen, String kind, TileObject object)
	{
		if (object == null || seen.contains(object))
		{
			return;
		}
		int plane = object.getPlane();
		// Discovery retains a reported plane even when its object cannot join this native view.
		recordPlane(plane, "OBJECT_REPORTED_PLANE");
		if (view != null)
		{
			try
			{
				if (object.getWorldView() != view)
				{
					return;
				}
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
		{
			planeEvidence.add(new PlaneEvidence(plane, reason));
		}
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
		{
			for (int i = 0; i < bridgeFailures; i++)
			{
				issues.add("worldPlanes: bridge unavailable");
			}
		}
		if (tilePlanes != null)
		{
			for (int plane : tilePlanes)
			{
				if (plane == Integer.MIN_VALUE)
				{
					issues.add("worldPlanes: tile plane unavailable");
				}
			}
		}
		return issues;
	}

	int objectFailures()
	{
		return objectFailures;
	}

	private void indexTilePlanes()
	{
		if (byPlane != null)
		{
			return;
		}
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
