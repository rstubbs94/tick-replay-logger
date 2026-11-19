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

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import java.util.*;
import java.util.List;

/** Resolve native coordinate frames explicitly; client convenience getters are top-level only. */
public final class WorldViews
{
	private WorldViews()
	{
	}

	private static final WeakIdentityIds identities = new WeakIdentityIds();

	public static void invalidate(WorldView view)
	{
		identities.invalidate(view);
	}

	public static WorldView active(Client client)
	{
		Player player = client.getLocalPlayer();
		WorldView view = player == null ? null : player.getWorldView();
		return view == null ? client.getTopLevelWorldView() : view;
	}

	public static String scope(WorldView view)
	{
		return view == null ? null : scope(view, view.getPlane());
	}

	public static String scope(WorldView view, int plane)
	{
		if (view == null)
		{
			return null;
		}
		return scope(view, plane, view.getId());
	}

	public static String scope(WorldView view, int plane, int observedViewId)
	{
		if (view == null)
		{
			return null;
		}
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		Object grid = tiles != null && plane >= 0 && plane < tiles.length ? tiles[plane] : null;
		return observedViewId + ":" + identities.id(view) + ":" + identities.id(scene) + ":" + identities.id(grid) + ":"
			+ view.getBaseX() + ":" + view.getBaseY() + ":" + plane + ":" + view.getSizeX() + ":" + view.getSizeY()
			+ ":" + Arrays.deepToString(view.getInstanceTemplateChunks());
	}

	public static Integer settledDistance(WorldPoint previous, WorldPoint current, String priorScope,
		String currentScope)
	{
		if (previous == null || current == null || priorScope == null || !Objects.equals(priorScope, currentScope)
			|| previous.getPlane() != current.getPlane())
		{
			return null;
		}
		return previous.distanceTo(current);
	}

	public static WorldPoint template(Player player)
	{
		WorldView view = player.getWorldView();
		LocalPoint local = player.getLocalLocation();
		if (view == null || local == null)
		{
			return null;
		}
		return view.isInstance() ? WorldPoint.fromLocalInstance(view.getScene(), local, view.getPlane())
			: WorldPoint.fromLocal(view, local.getX(), local.getY(), view.getPlane());
	}

	/** A numeric LocalPoint view ID alone cannot establish tile membership after a reload. */
	public static boolean containsTile(WorldView view, Tile tile)
	{
		if (view == null || tile == null)
		{
			return false;
		}
		LocalPoint point = tile.getLocalLocation();
		if (point == null || point.getWorldView() != view.getId())
		{
			return false;
		}
		Scene scene = view.getScene();
		Tile[][][] all = scene == null ? null : scene.getTiles();
		if (all == null)
		{
			return false;
		}
		int x = point.getSceneX(), y = point.getSceneY();
		if (x < 0 || y < 0)
		{
			return false;
		}
		for (Tile[][] plane : all)
		{
			if (plane != null && x < plane.length && plane[x] != null && y < plane[x].length)
			{
				Set<Tile> seen = Collections.newSetFromMap(new IdentityHashMap<>());
				for (Tile candidate = plane[x][y]; candidate != null
					&& seen.add(candidate); candidate = candidate.getBridge())
				{
					if (candidate == tile)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Enumerate actual API-owned views, using identity to avoid cycles and reused numeric slots. */
	public static final class Discovery
	{
		public final List<WorldView> views = new ArrayList<>();
		public final List<String> issues = new ArrayList<>();
	}

	public static final class Planes
	{
		public final SortedSet<Integer> planes = new TreeSet<>();
		public final List<String> issues = new ArrayList<>();
		private final Map<Integer, Set<String>> evidence = new TreeMap<>();
		private final Map<Integer, Integer> tileCounts = new TreeMap<>();

		public void add(int plane, String reason)
		{
			if (plane < 0)
			{
				issues.add("worldPlanes: invalid reported plane " + plane);
				return;
			}
			planes.add(plane);
			evidence.computeIfAbsent(plane, key -> new LinkedHashSet<>()).add(reason);
		}

		public Map<String, Object> coverage(int plane)
		{
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("plane", plane);
			out.put("discoveryEvidence", new ArrayList<>(evidence.getOrDefault(plane, Collections.emptySet())));
			if (tileCounts.containsKey(plane))
			{
				out.put("sceneArrayNonNullTiles", tileCounts.get(plane));
			}
			out.put("actorScope", "API_REPORTED_LOCATION_PLANES; unknown planes retained once, not placed");
			return out;
		}
	}

	/** Readable arrays are observations, including an empty grid; they do not establish safe terrain. */
	public static Planes planes(WorldView view, boolean includePlayers)
	{
		return planes(view, includePlayers, SceneCaptureTraversal.capture(null, view));
	}

	/** Discovery and snapshot channels consume the same current census, not a second scene scan. */
	static Planes planes(WorldView view, boolean includePlayers, SceneCaptureTraversal traversal)
	{
		if (traversal == null || traversal.view != view)
		{
			throw new IllegalArgumentException("Mismatched plane traversal");
		}
		Planes out = new Planes();
		out.add(view.getPlane(), "VIEW_CURRENT_PLANE");
		if (traversal.arrayIssue != null)
		{
			out.issues.add(traversal.arrayIssue);
		}
		for (Map.Entry<Integer, Integer> entry : traversal.arrayTileCounts.entrySet())
		{
			out.add(entry.getKey(), "SCENE_TILE_ARRAY");
			out.tileCounts.put(entry.getKey(), entry.getValue());
		}
		for (SceneCaptureTraversal.PlaneEvidence evidence : traversal.planeEvidence())
		{
			out.add(evidence.plane, evidence.reason);
		}
		out.issues.addAll(traversal.planeIssues());
		try
		{
			CollisionData[] collision = view.getCollisionMaps();
			if (collision != null)
			{
				for (int plane = 0; plane < collision.length; plane++)
				{
					if (collision[plane] != null)
					{
						out.add(plane, "COLLISION_CHANNEL");
					}
				}
			}
		}
		catch (RuntimeException error)
		{
			out.issues.add("worldPlanes: collision " + error.getClass().getSimpleName());
		}
		try
		{
			if (view.getGraphicsObjects() != null)
			{
				for (GraphicsObject graphic : view.getGraphicsObjects())
				{
					if (graphic != null)
					{
						try
						{
							out.add(graphic.getLevel(), "GRAPHICS_LEVEL");
						}
						catch (RuntimeException error)
						{
							out.issues.add("worldPlanes: graphics level unavailable");
						}
					}
				}
			}
		}
		catch (RuntimeException error)
		{
			out.issues.add("worldPlanes: graphics " + error.getClass().getSimpleName());
		}
		try
		{
			if (view.npcs() != null)
			{
				for (NPC npc : view.npcs())
				{
					if (npc != null)
					{
						try
						{
							WorldPoint point = npc.getWorldLocation();
							if (point != null)
							{
								out.add(point.getPlane(), "NPC_REPORTED_LOCATION");
							}
						}
						catch (RuntimeException error)
						{
							out.issues.add("worldPlanes: NPC plane unavailable");
						}
					}
				}
			}
		}
		catch (RuntimeException error)
		{
			out.issues.add("worldPlanes: NPCs " + error.getClass().getSimpleName());
		}
		if (includePlayers)
		{
			try
			{
				if (view.players() != null)
				{
					for (Player player : view.players())
					{
						if (player != null)
						{
							try
							{
								WorldPoint point = player.getWorldLocation();
								if (point != null)
								{
									out.add(point.getPlane(), "PLAYER_REPORTED_LOCATION");
								}
							}
							catch (RuntimeException error)
							{
								out.issues.add("worldPlanes: player plane unavailable");
							}
						}
					}
				}
			}
			catch (RuntimeException error)
			{
				out.issues.add("worldPlanes: players " + error.getClass().getSimpleName());
			}
		}
		return out;
	}

	public static Discovery discover(Client client)
	{
		Discovery result = new Discovery();
		Set<WorldView> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.Deque<WorldView> pending = new ArrayDeque<>();
		WorldView active = null, top = null;
		try
		{
			active = active(client);
		}
		catch (RuntimeException error)
		{
			result.issues.add("worldViews: active view unavailable");
		}
		try
		{
			top = client.getTopLevelWorldView();
		}
		catch (RuntimeException error)
		{
			result.issues.add("worldViews: top-level getter failed");
		}
		if (top != null)
		{
			pending.add(top);
		}
		else
		{
			result.issues.add("worldViews: top-level view unavailable");
		}
		if (active != null)
		{
			pending.add(active);
		}
		while (!pending.isEmpty())
		{
			WorldView view = pending.removeFirst();
			if (!seen.add(view))
			{
				continue;
			}
			result.views.add(view);
			try
			{
				IndexedObjectSet<? extends WorldView> children = view.worldViews();
				if (children == null)
				{
					result.issues.add("worldViews: child enumeration unavailable for view " + view.getId());
				}
				else
				{
					for (WorldView child : children)
					{
						if (child != null && !seen.contains(child))
						{
							pending.addLast(child);
						}
					}
				}
			}
			catch (RuntimeException error)
			{
				result.issues.add("worldViews: " + error.getClass().getSimpleName());
			}
			// A failed child-view iterator must not hide the separately exposed carrier relation.
			try
			{
				IndexedObjectSet<? extends WorldEntity> entities = view.worldEntities();
				if (entities == null)
				{
					result.issues.add("worldViews: carrier enumeration unavailable for view " + view.getId());
				}
				else
				{
					for (WorldEntity entity : entities)
					{
						if (entity != null)
						{
							try
							{
								WorldView child = entity.getWorldView();
								if (child != null && !seen.contains(child))
								{
									pending.addLast(child);
								}
							}
							catch (RuntimeException error)
							{
								result.issues.add("worldViews: carrier " + error.getClass().getSimpleName());
							}
						}
					}
				}
			}
			catch (RuntimeException error)
			{
				result.issues.add("worldViews: " + error.getClass().getSimpleName());
			}
		}
		return result;
	}
}
