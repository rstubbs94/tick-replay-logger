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
import java.util.function.*;

/** Compact observed state without losing the checkpoint at the selected window's first tick. */
final class WorldStateExport
{
	private WorldStateExport()
	{
	}

	static List<WorldSnapshot> compact(List<WorldSnapshot> ticks)
	{
		Map<String, List<WorldSnapshot>> streams = new LinkedHashMap<>();
		for (WorldSnapshot tick : ticks)
		{
			if (tick.getAdditionalViews() != null)
			{
				for (WorldSnapshot child : tick.getAdditionalViews())
				{
					streams.computeIfAbsent(child.getSceneKey(), key -> new ArrayList<>()).add(child);
				}
			}
		}
		IdentityHashMap<WorldSnapshot, WorldSnapshot> children = new IdentityHashMap<>();
		for (List<WorldSnapshot> stream : streams.values())
		{
			List<WorldSnapshot> compacted = compactNative(stream);
			for (int index = 0; index < stream.size(); index++)
			{
				children.put(stream.get(index), compacted.get(index));
			}
		}
		List<WorldSnapshot> nativeTicks = compactNative(ticks), out = new ArrayList<>();
		for (int index = 0; index < ticks.size(); index++)
		{
			WorldSnapshot tick = ticks.get(index), result = nativeTicks.get(index);
			if (tick.getAdditionalViews() != null)
			{
				List<WorldSnapshot> additional = new ArrayList<>();
				for (WorldSnapshot child : tick.getAdditionalViews())
				{
					additional.add(children.get(child));
				}
				result = result.toBuilder().additionalViews(additional).build();
			}
			out.add(result);
		}
		return out;
	}

	private static List<WorldSnapshot> compactNative(List<WorldSnapshot> ticks)
	{
		List<WorldSnapshot> out = new ArrayList<>();
		StateChanges<Long, GroundItemState> items = new StateChanges<>(GroundItemState::getItemKey);
		StateChanges<Long, ObservedObjectState> objects = new StateChanges<>(ObservedObjectState::getObjectKey);
		StateChanges<String, TileSurface> terrain = new StateChanges<>(TileSurface::getKey);
		WorldSnapshot previous = null;
		for (WorldSnapshot tick : ticks)
		{
			boolean baseline = previous == null || !Objects.equals(previous.getSceneKey(), tick.getSceneKey())
				|| !Objects.equals(previous.getWorldViewId(), tick.getWorldViewId())
				|| previous.getPlane() != tick.getPlane() || previous.getTick() + 1 != tick.getTick();
			WorldSnapshot.WorldSnapshotBuilder frame = tick.toBuilder().additionalViews(null).observedObjects(null)
				.observedGroundItems(null).observedVarps(null).observedCollision(null).observedTerrain(null)
				.observedNumericVariables(null)
				.nativeScene(baseline || previous.getNativeScene() == null ? tick.getNativeScene() : null);
			compactNumeric(frame, tick.getObservedNumericVariables(),
				previous == null ? null : previous.getObservedNumericVariables(), baseline);
			terrain.capture(tick.getObservedTerrain(), baseline);
			if (tick.getObservedTerrain() != null)
			{
				frame.terrainBaseline(terrain.baseline).terrainChanges(terrain.changes)
					.terrainRemovals(terrain.removals);
			}
			objects.capture(tick.getObservedObjects(), baseline);
			if (tick.getObservedObjects() != null)
			{
				frame.objectBaseline(objects.baseline).objectChanges(objects.changes).objectRemovals(objects.removals);
			}
			items.capture(tick.getObservedGroundItems(), baseline);
			if (tick.getObservedGroundItems() != null)
			{
				frame.groundItemBaseline(items.baseline).groundItemChanges(items.changes)
					.groundItemRemovals(items.removals);
			}
			int[] vars = tick.getObservedVarps(), old = previous == null ? null : previous.getObservedVarps();
			if (vars != null)
			{
				if (baseline || old == null || old.length != vars.length)
				{
					frame.varpBaseline(vars);
				}
				else
				{
					Map<Integer, Integer> changes = new LinkedHashMap<>();
					for (int i = 0; i < vars.length; i++)
					{
						if (vars[i] != old[i])
						{
							changes.put(i, vars[i]);
						}
					}
					frame.varpChanges(changes);
				}
			}
			int[][] flags = tick.getObservedCollision(),
				oldFlags = previous == null ? null : previous.getObservedCollision();
			if (flags != null)
			{
				boolean checkpoint = baseline || oldFlags == null || flags.length != oldFlags.length
					|| flags[0].length != oldFlags[0].length;
				List<SceneData.CollisionTile> changes = new ArrayList<>();
				for (int x = 0; x < flags.length; x++)
				{
					for (int y = 0; y < flags[x].length; y++)
					{
						if (checkpoint ? flags[x][y] != 0 : flags[x][y] != oldFlags[x][y])
						{
							changes.add(SceneData.CollisionTile.builder().x(x).y(y).flag(flags[x][y]).build());
						}
					}
				}
				frame.collisionBaseline(checkpoint).collisionWidth(flags.length).collisionHeight(flags[0].length)
					.collisionChanges(changes);
			}
			out.add(frame.build());
			previous = tick;
		}
		return out;
	}

	/** Shared lossless list delta. Missing samples force the next observed sample to be a baseline. */
	private static final class StateChanges<K, T>
	{
		private final Function<T, K> key;
		private Map<K, T> indexed = new LinkedHashMap<>();
		private List<T> previous;
		private boolean baseline;
		private List<T> changes;
		private List<K> removals;

		StateChanges(Function<T, K> key)
		{
			this.key = key;
		}

		void capture(List<T> current, boolean checkpoint)
		{
			baseline = checkpoint || previous == null;
			changes = new ArrayList<>();
			removals = new ArrayList<>();
			if (current != null && (baseline || indexed.size() != current.size() || !current.equals(previous)))
			{
				Map<K, T> next = new LinkedHashMap<>();
				for (T value : current)
				{
					K id = key.apply(value);
					next.put(id, value);
					if (baseline || !value.equals(indexed.get(id)))
					{
						changes.add(value);
					}
				}
				if (!baseline)
				{
					for (K id : indexed.keySet())
					{
						if (!next.containsKey(id))
						{
							removals.add(id);
						}
					}
				}
				indexed = next;
			}
			previous = current;
		}
	}

	private static void compactNumeric(WorldSnapshot.WorldSnapshotBuilder frame, NumericVariableState now,
		NumericVariableState prior, boolean baseline)
	{
		if (now == null)
		{
			return;
		}
		Map<Integer, Integer> current = now.getClientIntegers(), old = prior == null ? null : prior.getClientIntegers();
		if (current != null)
		{
			if (baseline || old == null)
			{
				frame.clientIntegerBaseline(current);
			}
			else
			{
				Map<Integer, Integer> changes = new TreeMap<>();
				List<Integer> removals = new ArrayList<>();
				for (Map.Entry<Integer, Integer> entry : current.entrySet())
				{
					if (!Objects.equals(old.get(entry.getKey()), entry.getValue()))
					{
						changes.put(entry.getKey(), entry.getValue());
					}
				}
				for (Integer key : old.keySet())
				{
					if (!current.containsKey(key))
					{
						removals.add(key);
					}
				}
				frame.clientIntegerChanges(changes).clientIntegerRemovals(removals);
			}
		}
		int[] ints = now.getServerVarps(), oldInts = prior == null ? null : prior.getServerVarps();
		if (ints != null)
		{
			if (baseline || oldInts == null || oldInts.length != ints.length)
			{
				frame.serverVarpBaseline(ints);
			}
			else
			{
				Map<Integer, Integer> changes = new TreeMap<>();
				for (int i = 0; i < ints.length; i++)
				{
					if (ints[i] != oldInts[i])
					{
						changes.put(i, ints[i]);
					}
				}
				frame.serverVarpChanges(changes);
			}
		}
		long[] longs = now.getVarpsLong(), oldLongs = prior == null ? null : prior.getVarpsLong();
		if (longs != null)
		{
			if (baseline || oldLongs == null || oldLongs.length != longs.length)
			{
				frame.varpLongBaseline(decimal(longs));
			}
			else
			{
				frame.varpLongChanges(longChanges(longs, oldLongs));
			}
		}
		longs = now.getServerVarpsLong();
		oldLongs = prior == null ? null : prior.getServerVarpsLong();
		if (longs != null)
		{
			if (baseline || oldLongs == null || oldLongs.length != longs.length)
			{
				frame.serverVarpLongBaseline(decimal(longs));
			}
			else
			{
				frame.serverVarpLongChanges(longChanges(longs, oldLongs));
			}
		}
	}

	private static String[] decimal(long[] values)
	{
		String[] result = new String[values.length];
		for (int i = 0; i < values.length; i++)
		{
			result[i] = Long.toString(values[i]);
		}
		return result;
	}

	private static Map<Integer, String> longChanges(long[] now, long[] old)
	{
		Map<Integer, String> out = new TreeMap<>();
		for (int i = 0; i < now.length; i++)
		{
			if (now[i] != old[i])
			{
				out.put(i, Long.toString(now[i]));
			}
		}
		return out;
	}
}
