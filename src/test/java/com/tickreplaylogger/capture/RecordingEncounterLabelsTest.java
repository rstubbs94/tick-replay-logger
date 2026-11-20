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

import com.google.gson.Gson;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.ui.RecordingEntryPanel;
import com.tickreplaylogger.ui.playback.PlaybackPanel;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class RecordingEncounterLabelsTest
{
	private static final Gson GSON = new Gson();
	private static DamageMap MAP;
	@BeforeClass public static void loadCatalog() { MAP = DamageMap.load(GSON); }
	@AfterClass public static void releaseCatalog() { MAP = null; }

	private NpcState boss(int id, String name, int level, int size)
	{
		return NpcState.builder().npcId(id).name(name).combatLevel(level).size(size).normalWorldContextObserved(true).build();
	}

	private DeathRecordingV2 raw(NpcState... npcs)
	{
		DamageRecord fatal = DamageRecord.builder().amount(43).impactTick(100).attackerName("Unknown").attribution("UNKNOWN").build();
		return DeathRecordingV2.builder().schemaVersion(DeathRecordingV2.LATEST_SCHEMA_VERSION).meta(DeathRecordingV2.Meta.builder().referenceCatalogId(MAP.catalog().id()).deathTick(100).build())
			.summary(DeathRecordingV2.Summary.builder().killerName("Unknown").killerConfidence("UNKNOWN")
				.killerBasis("SELECTED_RECORD_SOURCE_ONLY").fatalHit(fatal).fatalHitCandidates(Collections.singletonList(fatal))
				.fatalHitBasis("UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK").build())
			.ticks(Collections.singletonList(WorldSnapshot.builder().tick(100).npcs(Arrays.asList(npcs)).build()))
			.journalTicks(Collections.singletonList(TickData.builder().tickNumber(100).currentHp(0).maxHp(99)
				.damageEvents(Collections.singletonList(DamageEvent.received(43, DamageEvent.DamageType.UNKNOWN, "Unknown", "UNKNOWN", "UNKNOWN"))).build())).build();
	}

	private TickRecording recording(NpcState... npcs)
	{
		return TickRecording.fromPrepared(RecordingEncounterLabels.prepare(raw(npcs), MAP), 1, "Recorded encounter", true);
	}

	@Test public void recordedBossContextDoesNotRewriteAnUnknownFatalSource()
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		String before = GSON.toJson(recording);
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(recording));
		assertEquals("Unknown", recording.getKillerName());
		assertEquals("UNKNOWN", recording.getKillerConfidence());
		assertEquals("SELECTED_RECORD_SOURCE_ONLY", recording.getKillerBasis());
		assertEquals("Unknown", recording.getPreparedExport().getSummary().getFatalHit().getAttackerName());
		assertEquals(before, GSON.toJson(recording));
	}

	@Test public void multipleMissingOrContradictoryIdentitiesCannotSupplyAnEncounterTitle()
	{
		assertEquals("Unknown", RecordingEncounterLabels.title(recording(boss(9036, "Corrupted Hunllef", 894, 5), boss(2042, "Zulrah", 725, 5))));
		assertEquals("Unknown", RecordingEncounterLabels.title(recording(boss(9036, "Different observed NPC", 894, 5))));
		assertEquals("Unknown", RecordingEncounterLabels.title(recording(NpcState.builder().npcId(9036).name("Corrupted Hunllef").combatLevel(894).size(5).build())));
		TickRecording missing = TickRecording.builder().killerName("Unknown").richExportJson("{").build();
		assertEquals("Unknown", RecordingEncounterLabels.title(missing));
	}

	@Test public void explicitRenameAndLiveSnapshotsKeepTheirOriginalTitles()
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		recording.setKillerName("My attempt");
		assertEquals("My attempt", RecordingEncounterLabels.title(recording));
		recording.setKillerName("Unknown"); recording.setCustomLabel(true);
		assertEquals("Unknown", RecordingEncounterLabels.title(recording));
		TickRecording live = recording.toBuilder().customLabel(false).location("Live Snapshot").build();
		assertEquals("Unknown", RecordingEncounterLabels.title(live));
	}

	@Test public void preparationRequiresActualDeathAndReviewedReferenceButNotAFinalHpRead()
	{
		NpcState boss = boss(9036, "Corrupted Hunllef", 894, 5);
		TickRecording late = recording(boss).toBuilder().finalHp(25).finalHpKnown(false).build();
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(late));
		DeathRecordingV2 raw = raw(boss);
		DeathRecordingV2 unspecified = raw.toBuilder().schemaVersion(null).build();
		assertSame(unspecified, RecordingEncounterLabels.prepare(unspecified, MAP));
		for (DeathRecordingV2.Meta meta : Arrays.asList(raw.getMeta().toBuilder().deathTick(null).build(), raw.getMeta().toBuilder().referenceCatalogId("unmatched").build()))
		{
			DeathRecordingV2 unqualified = raw.toBuilder().meta(meta).build();
			assertSame(unqualified, RecordingEncounterLabels.prepare(unqualified, MAP));
			assertEquals("Unknown", RecordingEncounterLabels.title(TickRecording.fromPrepared(unqualified, 1, "Recorded encounter", true)));
		}
	}

	@Test public void additionalViewsArePreparedBeforeWireEncodingWithoutMutatingRawFrames()
	{
		DeathRecordingV2 raw = raw().toBuilder().ticks(Collections.singletonList(WorldSnapshot.builder().tick(100)
			.additionalViews(Collections.singletonList(WorldSnapshot.builder().npcs(Collections.singletonList(boss(9036, "Corrupted Hunllef", 894, 5))).build())).build())).build();
		String before = GSON.toJson(raw);
		DeathRecordingV2 prepared = RecordingEncounterLabels.prepare(raw, MAP);
		assertEquals("Corrupted Hunllef", prepared.getSummary().getBossName());
		assertEquals(before, GSON.toJson(raw));
		DeathRecordingV2 wire = prepared.toBuilder().ticks(Collections.emptyList()).npcStateTable(NpcStateTable.builder().format("shared-blocks-v1").build()).build();
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(TickRecording.fromPrepared(wire, 1, "Recorded encounter", true)));
	}

	@Test public void entryAndPlaybackShowEncounterSeparatelyFromUnknownFatalSource() throws Exception
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		String before = GSON.toJson(recording);
		SwingUtilities.invokeAndWait(() ->
		{
			RecordingEntryPanel entry = new RecordingEntryPanel(recording, 1, null, null, null, null, null, MAP, GSON);
			assertTrue(hasLabel(entry, "#1: Corrupted Hunllef"));
			assertTrue(hasLabel(entry, "Fatal source: Unknown"));
			PlaybackPanel playback = new PlaybackPanel(() -> {}, null, MAP, GSON);
			playback.loadRecording(recording);
			assertTrue(hasLabel(playback, "Corrupted Hunllef"));
			assertTrue(hasLabel(playback, "Fatal: 43 from Unknown"));
		});
		assertEquals(before, GSON.toJson(recording));
	}

	@Test public void payloadReplacementAndWrapperCopiesNeedNoTitleCache()
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(recording.toBuilder().build()));
		recording.setPreparedExport(recording(boss(2042, "Zulrah", 725, 5)).getPreparedExport());
		assertEquals("Zulrah", RecordingEncounterLabels.title(recording));
		recording.setCustomLabel(true);
		assertEquals("Unknown", RecordingEncounterLabels.title(recording));
		recording.setCustomLabel(false);
		assertEquals("Zulrah", RecordingEncounterLabels.title(recording));
	}

	private boolean hasLabel(Container parent, String text)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return true;
			if (child instanceof Container && hasLabel((Container) child, text)) return true;
		}
		return false;
	}
}
