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

import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.*;
import java.util.*;

/** Encounter context is prepared once from raw capture, independently of a fatal-source verdict. */
public final class RecordingEncounterLabels
{
	private RecordingEncounterLabels()
	{
	}

	public static DeathRecordingV2 prepare(DeathRecordingV2 recording, DamageMap map)
	{
		if (recording.getMeta() == null || recording.getMeta().getDeathTick() == null
			|| !DeathRecordingV2.LATEST_SCHEMA_VERSION.equals(recording.getSchemaVersion()) || map == null
			|| map.catalog() == null || !map.catalog().id().equals(recording.getMeta().getReferenceCatalogId()))
		{
			return recording;
		}
		Set<String> names = new LinkedHashSet<>();
		append(recording.getTicks(), map, names);
		return recording.toBuilder().summary(
			(recording.getSummary() == null ? DeathRecordingV2.Summary.builder() : recording.getSummary().toBuilder())
				.bossName(names.size() == 1 ? names.iterator().next() : null).build())
			.build();
	}

	private static void append(List<WorldSnapshot> frames, DamageMap map, Set<String> names)
	{
		if (frames == null)
		{
			return;
		}
		for (WorldSnapshot frame : frames)
		{
			if (frame == null)
			{
				continue;
			}
			if (frame.getNpcs() != null)
			{
				for (NpcState npc : frame.getNpcs())
				{
					String name = map.catalog().observedBossName(npc);
					if (name != null)
					{
						names.add(name);
					}
				}
			}
			append(frame.getAdditionalViews(), map, names);
		}
	}

	public static String title(TickRecording recording)
	{
		String original = recording.getKillerName();
		DeathRecordingV2 completed = recording.getPreparedExport();
		if (original == null || Boolean.TRUE.equals(recording.getCustomLabel())
			|| !(original.equals("Unknown") || original.startsWith("Unknown ("))
			|| "Live Snapshot".equals(recording.getLocation()) || completed == null || completed.getMeta() == null
			|| completed.getMeta().getDeathTick() == null || completed.getSummary() == null
			|| completed.getSummary().getBossName() == null)
		{
			return original;
		}
		return completed.getSummary().getBossName();
	}
}
