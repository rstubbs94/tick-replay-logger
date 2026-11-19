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
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.ui.playback.PlaybackPanel;
import com.tickreplaylogger.util.DisplayFormat;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

public class PlaybackPanelLayoutTest
{
	private static com.tickreplaylogger.capture.DamageMap MAP;
	@BeforeClass public static void loadCatalog() { MAP = com.tickreplaylogger.capture.DamageMap.load(new Gson()); }
	@AfterClass public static void releaseCatalog() { MAP = null; }

	@Test
	public void journalOnlyUnresolvedImpactDoesNotPresentAnEarlierKnownHitAsFatal() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			TickData tick = TickData.builder().tickNumber(1).currentHp(0).maxHp(99).damageEvents(
				java.util.Collections.singletonList(DamageEvent.received(5, DamageEvent.DamageType.POISON, "Poison")))
				.build();
			TickRecording recording = TickRecording.builder().killerName("Unknown").location("Synthetic test")
				.ticks(java.util.Collections.singletonList(tick)).finalHp(0)
				.fatalHitBasis("UNRESOLVED_IMPACT_IN_DEATH_TICK").build();
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, new Gson());
			panel.loadRecording(recording);
			assertNotNull(findLabel(panel, "Fatal impact not established"));
		});
	}

	@Test
	public void currentFatalWrapperEvidenceDoesNotRequireRichJsonDecodingOnTheEdt() throws Exception
	{
		TickData tick = TickData.builder().tickNumber(100).currentHp(0).maxHp(99).damageEvents(java.util.Collections
			.singletonList(DamageEvent.received(43, DamageEvent.DamageType.UNKNOWN, "Unknown", "UNKNOWN", "UNKNOWN")))
			.build();
		TickRecording recording = TickRecording.builder().killerName("Unknown").location("Recorded encounter")
			.ticks(java.util.Collections.singletonList(tick)).finalHp(0)
			.fatalHitBasis("UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK")
			.richExportJson("This payload must never be decoded by UI construction").build();
		String before = new Gson().toJson(recording);
		SwingUtilities.invokeAndWait(() ->
		{
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, new Gson());
			panel.loadRecording(recording);
			assertNotNull(findLabel(panel, "Fatal: 43 from Unknown"));
			panel.dispose();
		});
		assertEquals(before, new Gson().toJson(recording));
	}

	@Test
	public void deathCaptionCannotBorrowAnEarlierHitWhenTheFatalImpactWasUnobserved() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			TickRecording original = new Gson().fromJson(
				new InputStreamReader(getClass().getResourceAsStream("/com/tickreplaylogger/hunllef-death-420.json"),
					StandardCharsets.UTF_8),
				TickRecording.class);
			java.util.List<TickData> ticks = new java.util.ArrayList<>(original.getTicks());
			TickData last = ticks.get(ticks.size() - 1);
			ticks.add(last.toBuilder().tickNumber(last.getTickNumber() + 1)
				.damageEvents(java.util.Collections.emptyList()).build());
			TickRecording recording = TickRecording.builder().killerName("Unknown").location("Synthetic test")
				.ticks(ticks).finalHp(0).richExportJson("{\"schemaVersion\":\"1.3\",\"meta\":{\"deathTick\":"
					+ (last.getTickNumber() + 1) + "},\"summary\":{}}")
				.build();
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, new Gson());
			panel.loadRecording(recording);
			assertNotNull(findLabel(panel, "Fatal impact not established"));
			recording.setRichExportJson(null);
			panel.loadRecording(recording);
			assertNotNull(findLabel(panel, "Fatal impact not established"));
		});
	}

	@Test
	@SuppressWarnings("unchecked")
	public void zulrahPrayerOnlyTickNamesTheActionInTheEventRowAndTickDetails() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			TickRecording recording = new Gson().fromJson(
				new InputStreamReader(getClass().getResourceAsStream("/com/tickreplaylogger/zulrah-prayer-15387.json"),
					StandardCharsets.UTF_8),
				TickRecording.class);
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, new Gson());
			panel.loadRecording(recording);
			panel.setSize(242, 900);
			for (int i = 0; i < 4; i++)
				layout(panel);
			JList<TickData> list = (JList<TickData>) findEventList(panel);
			int prayerIndex = -1;
			for (int i = 0; i < list.getModel().getSize(); i++)
			{
				if (list.getModel().getElementAt(i).getTickNumber() == 15387)
					prayerIndex = i;
			}
			assertTrue(prayerIndex >= 0);
			String action = "Activated Protect from Missiles";
			Component row = list.getCellRenderer().getListCellRendererComponent(list,
				list.getModel().getElementAt(prayerIndex), prayerIndex, false, false);
			assertNotNull("A coloured prayer row must name its action",
				findLabel((Container) row, "Prayer on: Missiles"));
			assertEquals(action, ((javax.swing.JComponent) row).getToolTipText());
			assertEquals(28, row.getPreferredSize().height);
			list.setSelectedIndex(prayerIndex);
			assertNotNull("Clicking the row must show the recorded prayer action", findLabel(panel, action));
			list.setSelectedIndex(prayerIndex - 2); // Keep the prayer row purple, as in the supplied screenshot.
			for (int i = 0; i < 4; i++)
				layout(panel);
			checkScrollbars(panel);
			checkSingleLineLabels(panel);
			BufferedImage image = new BufferedImage(242, 900, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = image.createGraphics();
			panel.printAll(graphics);
			graphics.dispose();
			try
			{
				Path output = Path.of("build", "ui-preview", "zulrah-prayer.png");
				Files.createDirectories(output.getParent());
				ImageIO.write(image, "png", output.toFile());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static javax.swing.JLabel findLabel(Container container, String text)
	{
		if (container instanceof javax.swing.JLabel && text.equals(((javax.swing.JLabel) container).getText()))
			return (javax.swing.JLabel) container;
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				javax.swing.JLabel label = findLabel((Container) child, text);
				if (label != null)
					return label;
			}
		}
		return null;
	}

	@Test
	public void replayFitsSidebarAndDisablesEveryHorizontalScrollbar() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			PlaybackPanel panel = new PlaybackPanel(() ->
			{
			}, null, MAP, new Gson());
			TickRecording recording = new Gson().fromJson(
				new InputStreamReader(getClass().getResourceAsStream("/com/tickreplaylogger/hunllef-death-420.json"),
					StandardCharsets.UTF_8),
				TickRecording.class);
			panel.loadRecording(recording);
			panel.setSize(242, 900);
			for (int i = 0; i < 4; i++)
				layout(panel);
			JList<?> eventList = findEventList(panel);
			assertNotNull(eventList);
			eventList.setSelectedIndex(eventList.getModel().getSize() - 1);
			for (int i = 0; i < 4; i++)
				layout(panel);
			checkScrollbars(panel);
			checkSingleLineLabels(panel);
			BufferedImage image = new BufferedImage(242, 900, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = image.createGraphics();
			panel.printAll(graphics);
			graphics.dispose();
			try
			{
				Path output = Path.of("build", "ui-preview", "playback.png");
				Files.createDirectories(output.getParent());
				ImageIO.write(image, "png", output.toFile());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
		});
	}

	private static JList<?> findEventList(Container container)
	{
		if (container instanceof JList)
			return (JList<?>) container;
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				JList<?> list = findEventList((Container) child);
				if (list != null)
					return list;
			}
		}
		return null;
	}

	@Test
	public void suppliedLiveSnapshotsShowUnknownStyleWithoutHeartsOrAFatalSummary() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			for (String name : new String[]
			{ "lindir", "bandos" })
			{
				TickRecording recording = new Gson().fromJson(new InputStreamReader(
					getClass().getResourceAsStream("/com/tickreplaylogger/" + name + "-live-snapshot.json"),
					StandardCharsets.UTF_8), TickRecording.class);
				PlaybackPanel panel = new PlaybackPanel(() ->
				{
				}, null, MAP, new Gson());
				panel.loadRecording(recording);
				panel.setSize(242, 900);
				for (int i = 0; i < 4; i++)
					layout(panel);
				JList<?> list = findEventList(panel);
				int tickNumber = "lindir".equals(name) ? 3336 : 5527;
				for (int i = 0; i < list.getModel().getSize(); i++)
				{
					if (((TickData) list.getModel().getElementAt(i)).getTickNumber() == tickNumber)
						list.setSelectedIndex(i);
				}
				for (int i = 0; i < 4; i++)
					layout(panel);
				checkScrollbars(panel);
				checkLiveDamageLabels(panel);
				BufferedImage image = new BufferedImage(242, 900, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				panel.printAll(graphics);
				graphics.dispose();
				try
				{
					Path output = Path.of("build", "ui-preview", name + ".png");
					Files.createDirectories(output.getParent());
					ImageIO.write(image, "png", output.toFile());
				}
				catch (Exception e)
				{
					throw new AssertionError(e);
				}
			}
		});
	}

	private static void checkLiveDamageLabels(Container container)
	{
		if (container instanceof javax.swing.JLabel)
		{
			javax.swing.JLabel label = (javax.swing.JLabel) container;
			assertFalse("A live snapshot has no fatal hit",
				label.getText() != null && label.getText().startsWith("Fatal:"));
			if (label.getText() != null && label.getText().startsWith("Taken "))
			{
				assertSame("Unrecorded styles use the question-mark icon",
					CombatIcons.iconFor(DamageEvent.DamageType.UNKNOWN), label.getIcon());
				assertTrue(label.getToolTipText().contains("Attack type was not recorded"));
			}
		}
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
				checkLiveDamageLabels((Container) child);
		}
	}

	@Test
	public void timingReportsRecordedAnnotationsWithoutPrayerGuidance()
	{
		String launch = DisplayFormat
			.damageTooltip(DamageEvent.received(19, DamageEvent.DamageType.RANGED, "Hunllef", "TIMED", "LAUNCH"));
		assertTrue(launch.contains("Recorded timing annotation: attack tick (launch)"));
		assertFalse(launch.contains("Changing prayer"));
		assertFalse(launch.contains("timed attribution"));
		assertFalse(launch.contains("pray before"));
		String impact = DisplayFormat.timingExplanation("IMPACT");
		assertTrue(impact.contains("Recorded timing annotation: hit tick (impact)"));
		assertFalse(impact.contains("Changing prayer"));
		assertTrue(DisplayFormat.damageLineText(DamageEvent.dealt(0, DamageEvent.DamageType.UNKNOWN, "Hunllef"))
			.contains("Dealt 0"));
		assertNotNull(CombatIcons.iconFor(DamageEvent.received(1, DamageEvent.DamageType.UNKNOWN, "Floor")));
		DamageEvent environmental = DamageEvent.received(18, DamageEvent.DamageType.UNKNOWN, "Environmental",
			"ENVIRONMENTAL", "UNKNOWN");
		assertEquals(DamageEvent.DamageType.UNKNOWN, DisplayFormat.displayDamageType(environmental));
		assertFalse(DisplayFormat.damageTooltip(environmental).contains("protection prayers do not block"));
		assertTrue(DisplayFormat.damageTooltip(environmental).contains("not recorded"));
		DamageEvent explicitTypeless = DamageEvent.received(18, DamageEvent.DamageType.TYPELESS, "Reviewed floor",
			"ENVIRONMENTAL", "UNKNOWN");
		assertEquals(DamageEvent.DamageType.TYPELESS, DisplayFormat.displayDamageType(explicitTypeless));
		assertTrue(DisplayFormat.damageTooltip(explicitTypeless).contains("Recorded damage type: typeless"));
		assertFalse(DisplayFormat.damageTooltip(explicitTypeless).contains("prayers"));
		assertFalse(DisplayFormat.damageLineText(environmental).contains("<html>"));
	}

	private static void layout(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
				layout((Container) child);
		}
	}

	private static void checkScrollbars(Container container)
	{
		if (container instanceof JScrollPane)
		{
			JScrollPane scroll = (JScrollPane) container;
			assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
			assertFalse(scroll.getHorizontalScrollBar().isVisible());
			assertEquals(scroll.getViewport().getExtentSize().width, scroll.getViewport().getView().getWidth());
		}
		if (container instanceof JList)
		{
			JList<?> list = (JList<?>) container;
			assertTrue(list.getScrollableTracksViewportWidth());
			for (int i = 0; i < list.getModel().getSize(); i++)
			{
				assertEquals("Every tick, including 420, must remain one row", 28, list.getCellBounds(i, i).height);
			}
		}
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
				checkScrollbars((Container) child);
		}
	}

	private static void checkSingleLineLabels(Container container)
	{
		if (container instanceof SingleLineLabel)
		{
			assertFalse(((SingleLineLabel) container).getText().contains("<html>"));
		}
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
				checkSingleLineLabels((Container) child);
		}
	}
}
