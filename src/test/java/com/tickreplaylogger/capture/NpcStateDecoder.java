/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;

/** Test-only inverse of the wire encoder; production resolves raw captures before encoding. */
public final class NpcStateDecoder
{
	private static final int WIDTH = 8;
	private final Gson gson;
	private final NpcStateTable table;
	private final Map<Integer, NpcState> cache = new HashMap<>();

	public NpcStateDecoder(NpcStateTable table, Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		if (table == null || !"shared-blocks-v1".equals(table.getFormat()) || table.getBlockWidth() != WIDTH
			|| table.getFields() == null || table.getFields().isEmpty() || table.getFields().size() > 128
			|| new HashSet<>(table.getFields()).size() != table.getFields().size() || table.getValues() == null
			|| table.getBlocks() == null || table.getRows() == null)
		{
			throw new IllegalArgumentException("Invalid NPC state table");
		}
		this.table = table;
	}

	public List<NpcState> read(WorldSnapshot frame)
	{
		if (frame.getNpcs() != null || frame.getNpcStateRefs() == null)
		{
			return frame.getNpcs();
		}
		List<NpcState> output = new ArrayList<>();
		for (Integer ref : frame.getNpcStateRefs())
		{
			if (ref == null || ref < -1 || ref >= table.getRows().size())
			{
				throw new IllegalArgumentException("Invalid NPC row");
			}
			if (ref == -1)
			{
				output.add(null);
				continue;
			}
			NpcState decoded = cache.get(ref);
			if (decoded == null)
			{
				int[] row = table.getRows().get(ref);
				JsonObject value = new JsonObject();
				if (row == null || row.length != (table.getFields().size() + WIDTH - 1) / WIDTH)
				{
					throw new IllegalArgumentException("Invalid NPC row width");
				}
				for (int index = 0; index < row.length; index++)
				{
					if (row[index] < 0 || row[index] >= table.getBlocks().size())
					{
						throw new IllegalArgumentException("Invalid NPC block");
					}
					int[] block = table.getBlocks().get(row[index]);
					if (block == null || block.length != Math.min(WIDTH, table.getFields().size() - index * WIDTH))
					{
						throw new IllegalArgumentException("Invalid NPC block width");
					}
					for (int offset = 0; offset < block.length; offset++)
					{
						int entry = block[offset];
						if (entry < -1 || entry >= table.getValues().size())
						{
							throw new IllegalArgumentException("Invalid NPC value");
						}
						if (entry != -1)
						{
							value.add(table.getFields().get(index * WIDTH + offset), table.getValues().get(entry));
						}
					}
				}
				decoded = gson.fromJson(value, NpcState.class);
				cache.put(ref, decoded);
			}
			output.add(decoded);
		}
		return output;
	}
}
