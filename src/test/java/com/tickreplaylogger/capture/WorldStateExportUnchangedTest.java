/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.ObservedObjectState;
import com.tickreplaylogger.model.world.TileSurface;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldStateExportUnchangedTest
{
	@Test
	public void equalIndependentSamplesKeepEmptyDeltasThenRetainLaterFieldChanges()
	{
		WorldSnapshot first = frame(1, tiles(tile("a", 10)), objects(object(1, "first")));
		WorldSnapshot equal = frame(2, tiles(tile("a", 10)), objects(object(1, "first")));
		TileSurface changedTile = tile("a", 20);
		ObservedObjectState changedObject = object(1, "changed");
		List<WorldSnapshot> output = WorldStateExport
			.compact(Arrays.asList(first, equal, frame(3, tiles(changedTile), objects(changedObject))));
		assertEquals(Boolean.TRUE, output.get(0).getTerrainBaseline());
		assertEquals(Boolean.TRUE, output.get(0).getObjectBaseline());
		assertEmptyDeltas(output.get(1));
		assertEquals(Collections.singletonList(changedTile), output.get(2).getTerrainChanges());
		assertEquals(Collections.singletonList(changedObject), output.get(2).getObjectChanges());
		assertEquals(10, first.getObservedTerrain().get(0).getPaintRgb().intValue());
		assertEquals("first", equal.getObservedObjects().get(0).getName());
	}

	@Test
	public void reorderedThenRemovedAndObservedEmptyMembershipRemainExact()
	{
		TileSurface a = tile("a", 10), b = tile("b", 20);
		ObservedObjectState one = object(1, "one"), two = object(2, "two");
		List<WorldSnapshot> output = WorldStateExport
			.compact(Arrays.asList(frame(1, tiles(a, b), objects(one, two)), frame(2, tiles(b, a), objects(two, one)),
				frame(3, tiles(a), objects(one)), frame(4, tiles(), objects()), frame(5, tiles(), objects())));
		assertEmptyDeltas(output.get(1));
		assertEquals(Collections.singletonList("b"), output.get(2).getTerrainRemovals());
		assertEquals(Collections.singletonList(2L), output.get(2).getObjectRemovals());
		assertEquals(Collections.singletonList("a"), output.get(3).getTerrainRemovals());
		assertEquals(Collections.singletonList(1L), output.get(3).getObjectRemovals());
		assertEmptyDeltas(output.get(4));
	}

	@Test
	public void unavailableSampleIsNotAnEmptyOrCarriedForwardSampleAndRecoveryIsCheckpointed()
	{
		WorldSnapshot first = frame(1, tiles(tile("a", 10)), objects(object(1, "one")));
		List<WorldSnapshot> output = WorldStateExport.compact(
			Arrays.asList(first, frame(2, null, null), frame(3, first.getObservedTerrain(), first.getObservedObjects()),
				frame(4, first.getObservedTerrain(), first.getObservedObjects())));
		assertNull(output.get(1).getTerrainChanges());
		assertNull(output.get(1).getObjectChanges());
		assertNull(output.get(1).getTerrainRemovals());
		assertNull(output.get(1).getObjectRemovals());
		assertEquals(Boolean.TRUE, output.get(2).getTerrainBaseline());
		assertEquals(Boolean.TRUE, output.get(2).getObjectBaseline());
		assertEquals(first.getObservedTerrain(), output.get(2).getTerrainChanges());
		assertEquals(first.getObservedObjects(), output.get(2).getObjectChanges());
		assertEmptyDeltas(output.get(3));
	}

	@Test
	public void tickGapNativeScopeChangeAndSelectedWindowStillRequireFullCheckpoints()
	{
		WorldSnapshot first = frame(1, tiles(tile("a", 10)), objects(object(1, "one")));
		List<WorldSnapshot> input = Arrays.asList(first, first.toBuilder().tick(3).build(),
			first.toBuilder().tick(4).worldViewId(9).build(), first.toBuilder().tick(5).plane(1).build(),
			first.toBuilder().tick(6).sceneKey("replacement").build());
		for (WorldSnapshot output : WorldStateExport.compact(input))
		{
			assertEquals(Boolean.TRUE, output.getTerrainBaseline());
			assertEquals(Boolean.TRUE, output.getObjectBaseline());
			assertEquals(first.getObservedTerrain(), output.getTerrainChanges());
			assertEquals(first.getObservedObjects(), output.getObjectChanges());
		}
		assertEquals(Boolean.TRUE, WorldStateExport.compact(input.subList(3, 5)).get(0).getTerrainBaseline());
	}

	@Test
	public void repeatedKeysRetainTheExistingPerKeyComparisonRatherThanDisappearingInFastPath()
	{
		TileSurface before = tile("same", 10), last = tile("same", 20);
		ObservedObjectState one = object(1, "one"), later = object(1, "later");
		List<WorldSnapshot> output = WorldStateExport.compact(Arrays.asList(
			frame(1, tiles(before, last), objects(one, later)), frame(2, tiles(before, last), objects(one, later))));
		assertEquals(Collections.singletonList(before), output.get(1).getTerrainChanges());
		assertEquals(Collections.singletonList(one), output.get(1).getObjectChanges());
	}

	private static void assertEmptyDeltas(WorldSnapshot frame)
	{
		assertEquals(Boolean.FALSE, frame.getTerrainBaseline());
		assertEquals(Boolean.FALSE, frame.getObjectBaseline());
		assertTrue(frame.getTerrainChanges().isEmpty());
		assertTrue(frame.getTerrainRemovals().isEmpty());
		assertTrue(frame.getObjectChanges().isEmpty());
		assertTrue(frame.getObjectRemovals().isEmpty());
	}

	private static WorldSnapshot frame(int tick, List<TileSurface> terrain, List<ObservedObjectState> objects)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("scene").worldViewId(0).plane(0).observedTerrain(terrain)
			.observedObjects(objects).build();
	}

	private static TileSurface tile(String key, int rgb)
	{
		return TileSurface.builder().key(key).paintRgb(rgb).paintCorners(new int[]
		{ rgb, 2, 3, 4 }).build();
	}

	private static ObservedObjectState object(long key, String name)
	{
		return ObservedObjectState.builder().objectKey(key).id(0).name(name).build();
	}

	private static List<TileSurface> tiles(TileSurface... values)
	{
		return Arrays.asList(values);
	}

	private static List<ObservedObjectState> objects(ObservedObjectState... values)
	{
		return Arrays.asList(values);
	}
}
