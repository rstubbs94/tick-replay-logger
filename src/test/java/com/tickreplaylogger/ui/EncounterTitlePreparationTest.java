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
package com.tickreplaylogger.ui;

import com.google.gson.Gson;
import com.tickreplaylogger.TickReplayLoggerConfig;
import com.tickreplaylogger.capture.*;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.util.RecordingStorage;
import java.awt.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class EncounterTitlePreparationTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void currentUiReadsPreparedTitlesWithoutAnyNpcFrameWork() throws Exception
	{
		Gson gson = new Gson(); DamageMap map = DamageMap.load(gson);
		AtomicBoolean armed = new AtomicBoolean(); AtomicInteger reads = new AtomicInteger();
		TickRecording recording = recording(map);
		java.util.List<WorldSnapshot> original = recording.getPreparedExport().getTicks();
		recording.setPreparedExport(recording.getPreparedExport().toBuilder().ticks(new AbstractList<WorldSnapshot>()
		{
			public int size() { return original.size(); }
			public WorldSnapshot get(int index)
			{
				reads.incrementAndGet();
				if (armed.get()) throw new AssertionError("UI must not revisit original NPC frames");
				return original.get(index);
			}
		}).build());
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			storage.addDeath(recording).get(5, TimeUnit.SECONDS);
			reads.set(0); armed.set(true);
			SwingUtilities.invokeAndWait(() ->
			{
				TickReplayLoggerPanel panel = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig() {}, map);
				assertTrue(hasLabel(panel, "#1: Corrupted Hunllef"));
				panel.refresh(); panel.refresh();
				assertTrue(hasLabel(panel, "#1: Corrupted Hunllef"));
				assertEquals(0, reads.get());
				panel.dispose();
			});
		}
	}

	@Test public void explicitRenameIsImmediateAndCannotBeReplacedByAsyncTitlePreparation() throws Exception
	{
		Gson gson = new Gson(); DamageMap map = DamageMap.load(gson);
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			TickRecording original = recording(map);
			storage.addDeath(original).get(5, TimeUnit.SECONDS);
			TickReplayLoggerPanel[] panel = new TickReplayLoggerPanel[1];
			SwingUtilities.invokeAndWait(() -> panel[0] = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig() {}, map));
			storage.renameRecording(original, "Latest custom title").get(5, TimeUnit.SECONDS);
			SwingUtilities.invokeAndWait(() ->
			{
				panel[0].refresh();
				assertTrue(hasLabel(panel[0], "#1: Latest custom title"));
				assertFalse(hasLabel(panel[0], "#1: Corrupted Hunllef"));
				panel[0].dispose();
			});
			SwingUtilities.invokeAndWait(() -> {});
		}
	}

	private RecordingStorage storage(Gson gson) throws Exception
	{
		return new RecordingStorage(3, gson, net.runelite.client.util.Filepath.Unchecked.getRooted(temporary.newFolder().toPath()));
	}

	private TickRecording recording(DamageMap map)
	{
		DeathRecordingV2 raw = DeathRecordingV2.builder().schemaVersion(DeathRecordingV2.LATEST_SCHEMA_VERSION).meta(DeathRecordingV2.Meta.builder().deathTick(1).referenceCatalogId(map.catalog().id()).build())
			.summary(DeathRecordingV2.Summary.builder().killerName("Unknown").build()).journalTicks(Collections.emptyList())
			.ticks(Collections.singletonList(WorldSnapshot.builder().tick(1).npcs(Collections.singletonList(NpcState.builder()
				.npcId(9036).name("Corrupted Hunllef").combatLevel(894).size(5).normalWorldContextObserved(true).build())).build())).build();
		return TickRecording.fromPrepared(RecordingEncounterLabels.prepare(raw, map), 123, "Recorded encounter", true);
	}

	private static boolean hasLabel(Container parent, String text)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return true;
			if (child instanceof Container && hasLabel((Container) child, text)) return true;
		}
		return false;
	}
}
