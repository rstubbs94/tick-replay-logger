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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.ui.RecordingEntryPanel;
import com.tickreplaylogger.ui.playback.PlaybackPanel;
import java.awt.Component;
import java.awt.Container;
import java.util.Collections;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

public class RecordingEncounterLabelsTest
{
	private static final Gson GSON = new Gson();
	private static DamageMap MAP;
	@BeforeClass public static void loadCatalog() { MAP = DamageMap.load(GSON); }
	@AfterClass public static void releaseCatalog() { MAP = null; }

	private NpcState boss(int id, String name, int level, int size)
	{
		return NpcState.builder().npcId(id).name(name).combatLevel(level).size(size).normalWorldContextObserved(true)
			.build();
	}

	private TickRecording recording(NpcState... npcs)
	{
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", "1.3");
		JsonObject meta = new JsonObject();
		meta.addProperty("referenceCatalogId", MAP.catalog().id());
		meta.addProperty("deathTick", 100);
		root.add("meta", meta);
		JsonObject summary = new JsonObject();
		summary.addProperty("killerName", "Unknown");
		summary.addProperty("killerConfidence", "UNKNOWN");
		summary.addProperty("fatalHitBasis", "UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK");
		JsonObject fatal = new JsonObject();
		fatal.addProperty("amount", 43);
		fatal.addProperty("impactTick", 100);
		fatal.addProperty("attackerName", "Unknown");
		fatal.addProperty("attribution", "UNKNOWN");
		fatal.addProperty("attributionEvidence", "HISTORY_WITHOUT_CURRENT_HIT_EVIDENCE");
		summary.add("fatalHit", fatal);
		root.add("summary", summary);
		JsonObject frame = new JsonObject();
		frame.addProperty("tick", 100);
		frame.add("npcs", GSON.toJsonTree(npcs));
		JsonArray ticks = new JsonArray();
		ticks.add(frame);
		root.add("ticks", ticks);
		TickData journal = TickData.builder().tickNumber(100).currentHp(0).maxHp(99).damageEvents(Collections
			.singletonList(DamageEvent.received(43, DamageEvent.DamageType.UNKNOWN, "Unknown", "UNKNOWN", "UNKNOWN")))
			.build();
		return TickRecording.builder().killerName("Unknown").killerConfidence("UNKNOWN")
			.killerBasis("SELECTED_RECORD_SOURCE_ONLY").fatalHitBasis("UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK")
			.ticks(Collections.singletonList(journal)).finalHp(0).location("Recorded encounter")
			.richExportJson(GSON.toJson(root)).build();
	}

	@Test
	public void recordedBossContextDoesNotRewriteAnUnknownFatalSource()
	{
		TickRecording r = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		String before = GSON.toJson(r);
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(r, MAP, GSON));
		assertEquals("Unknown", r.getKillerName());
		assertEquals("UNKNOWN", r.getKillerConfidence());
		assertEquals("SELECTED_RECORD_SOURCE_ONLY", r.getKillerBasis());
		assertEquals(before, GSON.toJson(r));
	}

	@Test
	public void multipleBossesMissingOrContradictoryIdentityCannotSupplyAnEncounterTitle()
	{
		assertEquals("Unknown", RecordingEncounterLabels
			.title(recording(boss(9036, "Corrupted Hunllef", 894, 5), boss(2042, "Zulrah", 725, 5)), MAP, GSON));
		assertEquals("Unknown",
			RecordingEncounterLabels.title(recording(boss(9036, "Different observed NPC", 894, 5)), MAP, GSON));
		NpcState unavailable = NpcState.builder().npcId(9036).name("Corrupted Hunllef").combatLevel(894).size(5)
			.build();
		assertEquals("Unknown", RecordingEncounterLabels.title(recording(unavailable), MAP, GSON));
		TickRecording missing = recording();
		missing.setRichExportJson("{");
		assertEquals("Unknown", RecordingEncounterLabels.title(missing, MAP, GSON));
	}

	@Test
	public void explicitRenameAndLiveSnapshotsKeepTheirOriginalTitles()
	{
		TickRecording r = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		r.setKillerName("My attempt");
		assertEquals("My attempt", RecordingEncounterLabels.title(r, MAP, GSON));
		r.setKillerName("Unknown");
		r.setCustomLabel(true);
		assertEquals("Unknown", RecordingEncounterLabels.title(r, MAP, GSON));
		TickRecording live = TickRecording.builder().killerName("Unknown").location("Live Snapshot").finalHp(0)
			.richExportJson(r.getRichExportJson()).build();
		assertEquals("Unknown", RecordingEncounterLabels.title(live, MAP, GSON));
	}

	@Test
	public void observedDeathMarkerDoesNotRequireAnAvailableOrZeroFinalHp()
	{
		TickRecording original = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		TickRecording lateHp = TickRecording.builder().killerName("Unknown").finalHp(25).finalHpKnown(false)
			.location("Recorded encounter").richExportJson(original.getRichExportJson()).build();
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(lateHp, MAP, GSON));
		JsonObject withoutDeath = GSON.fromJson(lateHp.getRichExportJson(), JsonObject.class);
		withoutDeath.getAsJsonObject("meta").remove("deathTick");
		lateHp.setRichExportJson(GSON.toJson(withoutDeath));
		assertEquals("Unknown", RecordingEncounterLabels.title(lateHp, MAP, GSON));
	}

	@Test
	public void currentSharedRowsAndAdditionalViewsUseTheSameGuardedContext()
	{
		TickRecording r = recording();
		JsonObject root = GSON.fromJson(r.getRichExportJson(), JsonObject.class);
		JsonObject frame = root.getAsJsonArray("ticks").get(0).getAsJsonObject();
		frame.remove("npcs");
		JsonObject nativeFrame = new JsonObject();
		JsonArray refs = new JsonArray();
		refs.add(0);
		nativeFrame.add("npcStateRefs", refs);
		JsonArray views = new JsonArray();
		views.add(nativeFrame);
		frame.add("additionalViews", views);
		JsonObject table = new JsonObject();
		table.addProperty("format", "shared-blocks-v1");
		table.addProperty("blockWidth", 8);
		table.add("fields", GSON.toJsonTree(new String[]
		{ "npcId", "name", "combatLevel", "size", "normalWorldContextObserved" }));
		table.add("values", GSON.toJsonTree(new Object[]
		{ 9036, "Corrupted Hunllef", 894, 5, true }));
		table.add("blocks", GSON.toJsonTree(new int[][]
		{
			{ 0, 1, 2, 3, 4 } }));
		table.add("rows", GSON.toJsonTree(new int[][]
		{
			{ 0 } }));
		root.add("npcStateTable", table);
		r.setRichExportJson(GSON.toJson(root));
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.title(r, MAP, GSON));
		refs.set(0, new com.google.gson.JsonPrimitive(9));
		r.setRichExportJson(GSON.toJson(root));
		assertEquals("Unknown", RecordingEncounterLabels.title(r, MAP, GSON));
		root.getAsJsonObject("meta").addProperty("referenceCatalogId", "unmatched");
		r.setRichExportJson(GSON.toJson(root));
		assertEquals("Unknown", RecordingEncounterLabels.title(r, MAP, GSON));
	}

	@Test
	public void entryAndPlaybackShowEncounterSeparatelyFromUnknownFatalSource() throws Exception
	{
		TickRecording r = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		String before = GSON.toJson(r);
		RecordingEncounterLabels.prepareTitle(r, MAP, GSON);
		SwingUtilities.invokeAndWait(() ->
		{
			RecordingEntryPanel entry = new RecordingEntryPanel(r, 1, null, null, null, null, null, MAP, GSON);
			assertTrue(hasLabel(entry, "#1: Corrupted Hunllef"));
			assertTrue(hasLabel(entry, "Fatal source: Unknown"));
			PlaybackPanel playback = new PlaybackPanel(() ->
			{
			}, null, MAP, GSON);
			playback.loadRecording(r);
			assertTrue(hasLabel(playback, "Corrupted Hunllef"));
			assertTrue(hasLabel(playback, "Fatal: 43 from Unknown"));
		});
		assertEquals(before, GSON.toJson(r));
	}

	@Test
	public void preparedTitlesSurviveWrapperCopiesWithoutChangingExportOrEquality()
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		TickRecording unprepared = recording.toBuilder().build();
		String before = GSON.toJson(recording);
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.prepareTitle(recording, MAP, GSON));
		TickRecording copied = recording.toBuilder().build();
		assertSame(recording.preparedEncounterTitle(), copied.preparedEncounterTitle());
		assertFalse(RecordingEncounterLabels.needsPreparation(copied, MAP));
		assertEquals("Corrupted Hunllef", RecordingEncounterLabels.preparedTitle(copied, MAP));
		assertEquals(unprepared, recording);
		assertEquals(before, GSON.toJson(recording));
		assertEquals(before, GSON.toJson(copied));
	}

	@Test
	public void payloadReplacementAndExplicitRenameInvalidatePreparedTitles()
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		RecordingEncounterLabels.prepareTitle(recording, MAP, GSON);
		recording.setRichExportJson(recording(boss(2042, "Zulrah", 725, 5)).getRichExportJson());
		assertEquals("Unknown", RecordingEncounterLabels.preparedTitle(recording, MAP));
		assertTrue(RecordingEncounterLabels.needsPreparation(recording, MAP));
		assertEquals("Zulrah", RecordingEncounterLabels.prepareTitle(recording, MAP, GSON));
		recording.setKillerName("My renamed attempt");
		recording.setCustomLabel(true);
		assertEquals("My renamed attempt", RecordingEncounterLabels.preparedTitle(recording, MAP));
		assertFalse(RecordingEncounterLabels.needsPreparation(recording, MAP));
		recording.setKillerName("Unknown");
		assertEquals("Unknown", RecordingEncounterLabels.prepareTitle(recording, MAP, GSON));
		recording.setCustomLabel(false);
		assertEquals("Zulrah", RecordingEncounterLabels.prepareTitle(recording, MAP, GSON));
		assertEquals("Unknown", RecordingEncounterLabels.preparedTitle(recording, null));
	}

	@Test
	public void directUiConstructionNeverPreparesAnUncachedRichPayload() throws Exception
	{
		TickRecording recording = recording(boss(9036, "Corrupted Hunllef", 894, 5));
		String before = GSON.toJson(recording);
		SwingUtilities.invokeAndWait(() ->
		{
			assertEquals("Unknown", RecordingEncounterLabels.title(recording, MAP, GSON));
			RecordingEntryPanel entry = new RecordingEntryPanel(recording, 1, null, null, null, null, null, MAP, GSON);
			assertTrue(hasLabel(entry, "#1: Unknown"));
			PlaybackPanel playback = new PlaybackPanel(() ->
			{
			}, null, MAP, GSON);
			playback.loadRecording(recording);
			assertTrue(hasLabel(playback, "Unknown"));
			assertNull(recording.preparedEncounterTitle());
		});
		assertEquals(before, GSON.toJson(recording));
	}

	private boolean hasLabel(Container parent, String text)
	{
		for (Component component : parent.getComponents())
		{
			if (component instanceof JLabel && text.equals(((JLabel) component).getText()))
				return true;
			if (component instanceof Container && hasLabel((Container) component, text))
				return true;
		}
		return false;
	}
}
