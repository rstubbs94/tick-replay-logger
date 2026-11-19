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
import com.tickreplaylogger.capture.DamageMap;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.ui.playback.PlaybackPanel;
import com.tickreplaylogger.util.DisplayFormat;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

public class DamageSourcePresentationTest
{
	private static final Gson GSON = new Gson();
	private static DamageMap MAP;
	@BeforeClass public static void loadCatalog() { MAP = DamageMap.load(GSON); }
	@AfterClass public static void releaseCatalog() { MAP = null; }

	@Test
	public void anUnknownSourceIsPlainUnknownAndIsNotAQualifiedCandidate()
	{
		for (String source : Arrays.asList("Unknown", "UNKNOWN", "Unknown source", null, " "))
		{
			DamageEvent event = DamageEvent.received(43, DamageEvent.DamageType.UNKNOWN, source, "UNKNOWN", "UNKNOWN");
			String before = GSON.toJson(event);
			assertEquals("Taken 43 Unknown", DisplayFormat.damageLineText(event));
			assertFalse(DisplayFormat.sourceIsCorrelated(event));
			assertTrue(DisplayFormat.damageTooltip(event).contains("from Unknown"));
			assertFalse(DisplayFormat.damageTooltip(event).contains("possible"));
			assertEquals(before, GSON.toJson(event));
		}
	}

	@Test
	public void aNamedSourceHasAPlainCompactLabelAndRetainsItsDetailedEvidence()
	{
		DamageEvent event = DamageEvent.received(12, DamageEvent.DamageType.MAGIC, "Corrupted Hunllef", "TIMED",
			"UNKNOWN");
		String before = GSON.toJson(event);
		assertEquals("Taken 12 Corrupted Hunllef", DisplayFormat.damageLineText(event));
		assertTrue(DisplayFormat.sourceIsCorrelated(event));
		assertTrue(DisplayFormat.damageTooltip(event).contains("possible source: Corrupted Hunllef"));
		assertEquals(before, GSON.toJson(event));
	}

	@Test
	public void liveDamageDetailsKeepRecordedEvidenceWithoutPrayerGuidance() throws Exception
	{
		for (DamageEvent event : Arrays.asList(
			DamageEvent.received(12, DamageEvent.DamageType.MAGIC, "Recorded source", "TIMED", "LAUNCH"),
			DamageEvent.received(8, DamageEvent.DamageType.RANGED, "Recorded source", "CONFIRMED", "IMPACT"),
			DamageEvent.received(3, DamageEvent.DamageType.TYPELESS, "Recorded floor", "ENVIRONMENTAL", "UNKNOWN")))
		{
			TickData tick = TickData.builder().tickNumber(10).currentHp(40).maxHp(99)
				.damageEvents(Collections.singletonList(event)).build();
			String before = GSON.toJson(tick);
			SwingUtilities.invokeAndWait(() ->
			{
				javax.swing.JPanel panel = LiveTickPanel.createTickPanel(tick, 0, null);
				JLabel damage = findLabel(panel, DisplayFormat.damageLineText(event));
				assertNotNull(damage);
				String details = damage.getToolTipText();
				assertTrue(details.contains(String.valueOf(event.getAmount())));
				assertFalse(details.contains("Changing prayer"));
				assertFalse(details.contains("prayers do not block"));
				if (event.getDetermination().equals("LAUNCH"))
				{
					assertTrue(details.contains("Recorded timing annotation: attack tick (launch)"));
					assertTrue(details.contains("source is not confirmed"));
				}
				else if (event.getDetermination().equals("IMPACT"))
					assertTrue(details.contains("Recorded timing annotation: hit tick (impact)"));
				else
					assertTrue(details.contains("Recorded damage type: typeless"));
			});
			assertEquals(before, GSON.toJson(tick));
		}
	}

	@Test
	public void selectingTheFinalTickShowsPlainUnknownWithoutChangingTheRecording() throws Exception
	{
		TickData previous = TickData.builder().tickNumber(99).currentHp(43).maxHp(99)
			.damageEvents(Collections.singletonList(
				DamageEvent.received(12, DamageEvent.DamageType.MAGIC, "Corrupted Hunllef", "TIMED", "UNKNOWN")))
			.build();
		TickData last = TickData.builder().tickNumber(100).currentHp(0).maxHp(99).damageEvents(Collections
			.singletonList(DamageEvent.received(43, DamageEvent.DamageType.UNKNOWN, "Unknown", "UNKNOWN", "UNKNOWN")))
			.build();
		TickRecording recording = TickRecording.builder().killerName("Unknown").killerConfidence("UNKNOWN")
			.location("Recorded death").finalHp(0).ticks(Arrays.asList(previous, last)).build();
		String before = GSON.toJson(recording);
		SwingUtilities.invokeAndWait(() ->
		{
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, GSON);
			panel.loadRecording(recording);
			assertNotNull(findLabel(panel, "Taken 12 Corrupted Hunllef"));
			JList<?> ticks = findList(panel);
			assertNotNull(ticks);
			ticks.setSelectedIndex(1);
			JLabel unknown = findLabel(panel, "Taken 43 Unknown");
			assertNotNull("Selected final-tick damage row", unknown);
			assertFalse(unknown.getToolTipText().contains("possible"));
			assertNull(findLabel(panel, "Taken 43 \u00b7 possible Unknown"));
		});
		assertEquals(before, GSON.toJson(recording));
	}

	private static JLabel findLabel(Container parent, String text)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText()))
				return (JLabel) child;
			if (child instanceof Container)
			{
				JLabel found = findLabel((Container) child, text);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	private static JList<?> findList(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JList)
				return (JList<?>) child;
			if (child instanceof Container)
			{
				JList<?> found = findList((Container) child);
				if (found != null)
					return found;
			}
		}
		return null;
	}
}
