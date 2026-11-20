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

import com.tickreplaylogger.model.world.GroundItemState;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/** Independent per-native-plane channel, reconciled from exposed tiles; no hazard or ownership attribution. */
public final class GroundItemTracker
{
	private static final int MAX_ITEMS = 32768;
	private WeakIdentityIds identities = new WeakIdentityIds();
	private Set<TileItem> lastComplete = Collections.newSetFromMap(new IdentityHashMap<>());
	private String sceneKey;
	private List<GroundItemState> previous;
	private int failures, dropped, tilesRead;
	private boolean available;

	public void reset()
	{
		identities = new WeakIdentityIds();
		lastComplete.clear();
		previous = null;
		sceneKey = null;
	}

	public void beginScope(String key)
	{
		if (!Objects.equals(sceneKey, key))
		{
			reset();
			sceneKey = key;
		}
	}

	private <T> T read(Supplier<T> getter, String field, List<String> issues)
	{
		try
		{
			return getter.get();
		}
		catch (RuntimeException error)
		{
			issues.add(field + ": " + error.getClass().getSimpleName());
			return null;
		}
	}

	public GroundItemState observation(Tile tile, TileItem item, Integer viewId)
	{
		if (item == null)
		{
			return null;
		}
		long key = identities.id(item);
		List<String> issues = new ArrayList<>();
		LocalPoint local = read(tile::getLocalLocation, "position", issues);
		WorldPoint world = read(tile::getWorldLocation, "worldPosition", issues);
		Integer plane = read(tile::getPlane, "plane", issues);
		if (plane == null)
		{
			throw new IllegalStateException("Item plane unavailable");
		}
		GroundItemState state = GroundItemState.builder().itemKey(key).id(read(item::getId, "id", issues))
			.quantity(read(item::getQuantity, "quantity", issues))
			.ownership(read(item::getOwnership, "ownership", issues))
			.privateItem(read(item::isPrivate, "private", issues))
			.visibleAtApiTick(read(item::getVisibleTime, "visibleTime", issues))
			.despawnAtApiTick(read(item::getDespawnTime, "despawnTime", issues)).plane(plane).worldViewId(viewId)
			.localX(local == null ? null : local.getX()).localY(local == null ? null : local.getY())
			.worldX(world == null ? null : world.getX()).worldY(world == null ? null : world.getY())
			.captureIssues(Collections.unmodifiableList(issues)).build();
		failures += issues.size();
		return state;
	}

	public void despawned(TileItem item)
	{
		identities.invalidate(item);
		lastComplete.remove(item);
	}

	public List<GroundItemState> snapshot(Client client, String key, WorldView view, int plane)
	{
		return snapshot(client, key, view, plane, SceneCaptureTraversal.capture(client, view));
	}

	List<GroundItemState> snapshot(Client client, String key, WorldView view, int plane,
		SceneCaptureTraversal traversal)
	{
		if (traversal == null || !traversal.matches(client, view))
		{
			throw new IllegalArgumentException("Mismatched scene traversal");
		}
		beginScope(key);
		failures = 0;
		dropped = 0;
		tilesRead = 0;
		available = false;
		if (traversal.arrays == null)
		{
			failures++;
			return null;
		}
		available = traversal.planeArrayAvailable(plane);
		if (!available)
		{
			failures++;
		}
		List<SceneCaptureTraversal.ItemRow> matching = traversal.items(plane);
		tilesRead = traversal.tiles(plane).size();
		failures += traversal.tileFailures() + traversal.itemFailures(plane);
		Set<TileItem> found = Collections.newSetFromMap(new IdentityHashMap<>());
		List<GroundItemState> out = new ArrayList<>();
		for (SceneCaptureTraversal.ItemRow row : matching)
		{
			if (!found.add(row.item))
			{
				continue;
			}
			available = true;
			if (out.size() >= MAX_ITEMS)
			{
				dropped++;
				continue;
			}
			try
			{
				GroundItemState state = observation(row.tile, row.item, view == null ? null : view.getId());
				if (state != null)
				{
					out.add(state);
				}
			}
			catch (RuntimeException unavailable)
			{
				failures++;
			}
		}
		if (!available)
		{
			return null;
		}
		if (failures == 0 && dropped == 0)
		{
			for (TileItem old : lastComplete)
			{
				if (!found.contains(old))
				{
					identities.invalidate(old);
				}
			}
			lastComplete = found;
		}
		out.sort(Comparator.comparingLong(GroundItemState::getItemKey));
		if (previous != null && previous.equals(out))
		{
			return previous;
		}
		previous = Collections.unmodifiableList(out);
		return previous;
	}

	public Map<String, Object> coverage()
	{
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", "EXPOSED_NATIVE_TILE_REPORTED_PLANE");
		out.put("membershipEvidence", "SCENE_SEED_AND_LIFECYCLE_EVENTS");
		out.put("membershipStatus", !available ? "UNAVAILABLE"
			: failures == 0 && dropped == 0 ? "COMPLETE_EXPOSED_SCENE_SCAN" : "PARTIAL_EXPOSED_SCENE_SCAN");
		out.put("complete", available && failures == 0 && dropped == 0);
		out.put("readFailures", failures);
		out.put("dropped", dropped);
		out.put("tilesRead", tilesRead);
		out.put("timerClock", "CLIENT_API_TICK_COUNT");
		return out;
	}
}
