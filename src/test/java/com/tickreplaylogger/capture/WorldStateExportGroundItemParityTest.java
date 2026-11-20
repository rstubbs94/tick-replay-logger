/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldStateExportGroundItemParityTest
{
	@Test
	public void itemDeltasRetainRepeatedRowsRemovalOrderAndMissingSampleRecovery()
	{
		GroundItemState first = item(1, 1), last = item(1, 2), two = item(2, 3), three = item(3, 4);
		List<GroundItemState> repeated = Arrays.asList(first, last, two, three);
		List<WorldSnapshot> output = WorldStateExport.compact(Arrays.asList(frame(1, repeated), frame(2, repeated),
			frame(3, Arrays.asList(three, two, last)), frame(4, Collections.singletonList(last)), frame(5, null),
			frame(6, Collections.singletonList(two)), frame(7, Collections.emptyList()), frame(8, Collections.emptyList())));

		assertEquals(repeated, output.get(0).getGroundItemChanges());
		assertEquals(Collections.singletonList(first), output.get(1).getGroundItemChanges());
		assertTrue(output.get(2).getGroundItemChanges().isEmpty());
		assertEquals(Arrays.asList(3L, 2L), output.get(3).getGroundItemRemovals());
		assertNull(output.get(4).getGroundItemBaseline());
		assertNull(output.get(4).getGroundItemChanges());
		assertNull(output.get(4).getGroundItemRemovals());
		assertEquals(Boolean.TRUE, output.get(5).getGroundItemBaseline());
		assertEquals(Collections.singletonList(two), output.get(5).getGroundItemChanges());
		assertTrue(output.get(5).getGroundItemRemovals().isEmpty());
		assertEquals(Collections.singletonList(2L), output.get(6).getGroundItemRemovals());
		assertTrue(output.get(7).getGroundItemChanges().isEmpty());
		assertTrue(output.get(7).getGroundItemRemovals().isEmpty());
		assertEquals(Arrays.asList(first, last, two, three), repeated);
	}

	@Test
	public void missingChannelsCheckpointIndependently()
	{
		GroundItemState item = item(1, 1);
		TileSurface terrain = TileSurface.builder().key("tile").paintRgb(10).build();
		ObservedObjectState object = ObservedObjectState.builder().objectKey(1L).id(7).build();
		WorldSnapshot first = frame(1, Collections.singletonList(item)).toBuilder()
			.observedTerrain(Collections.singletonList(terrain)).observedObjects(Collections.singletonList(object)).build();
		WorldSnapshot second = first.toBuilder().tick(2).observedTerrain(null).observedObjects(Collections.emptyList()).build();
		WorldSnapshot third = first.toBuilder().tick(3).observedGroundItems(null).observedObjects(Collections.emptyList()).build();
		WorldSnapshot fourth = third.toBuilder().tick(4).observedGroundItems(Collections.singletonList(item)).build();
		List<WorldSnapshot> output = WorldStateExport.compact(Arrays.asList(first, second, third, fourth));

		assertNull(output.get(1).getTerrainBaseline());
		assertEquals(Collections.singletonList(1L), output.get(1).getObjectRemovals());
		assertEquals(Boolean.FALSE, output.get(1).getGroundItemBaseline());
		assertEquals(Boolean.TRUE, output.get(2).getTerrainBaseline());
		assertEquals(Boolean.FALSE, output.get(2).getObjectBaseline());
		assertNull(output.get(2).getGroundItemBaseline());
		assertEquals(Boolean.FALSE, output.get(3).getTerrainBaseline());
		assertEquals(Boolean.FALSE, output.get(3).getObjectBaseline());
		assertEquals(Boolean.TRUE, output.get(3).getGroundItemBaseline());
		assertEquals(Collections.singletonList(item), output.get(3).getGroundItemChanges());
	}

	private static GroundItemState item(long key, int quantity)
	{
		return GroundItemState.builder().itemKey(key).id(995).quantity(quantity).build();
	}

	private static WorldSnapshot frame(int tick, List<GroundItemState> items)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("scene").worldViewId(0).plane(0).observedGroundItems(items).build();
	}
}
