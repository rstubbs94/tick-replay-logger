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
package com.tickreplaylogger.ui.playback;

import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.MovementData;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.capture.DamageMap;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Main playback panel for replaying tick recordings tick-by-tick.
 */
public class PlaybackPanel extends JPanel
{
	private static final int TICK_MS = 600; // OSRS tick duration

	// Colors - consistent with LiveTickPanel and RecordingEntryPanel
	private static final Color DAMAGE_RECEIVED_COLOR = new Color(255, 100, 100);
	private static final Color DAMAGE_DEALT_COLOR = new Color(100, 255, 100);
	private static final Color ACTION_COLOR = new Color(255, 200, 100);
	private static final Color PRAYER_COLOR = new Color(150, 150, 255);
	private static final Color MOVEMENT_COLOR = new Color(100, 200, 255);
	private static final Color EFFECT_COLOR = new Color(210, 140, 255);
	private static final Color TELEMETRY_COLOR = new Color(160, 160, 160);
	private static final Color TELEMETRY_LAG_COLOR = new Color(255, 100, 100);
	private static final Color ROW_NORMAL_1 = ColorScheme.DARK_GRAY_COLOR;
	private static final Color ROW_NORMAL_2 = new Color(45, 45, 45);
	private static final Color ROW_SIGNIFICANT_DAMAGE = new Color(80, 30, 30); // Red - damage taken
	private static final Color ROW_SIGNIFICANT_DEALT = new Color(30, 50, 80); // Blue - damage dealt
	private static final Color ROW_SIGNIFICANT_HEAL = new Color(30, 60, 30); // Green - healing
	private static final Color ROW_SIGNIFICANT_PRAYER = new Color(50, 30, 70); // Purple - prayer actions
	private static final Color ROW_SIGNIFICANT_EFFECT = new Color(60, 30, 75); // Violet - combat effects
	private static final Color ROW_DEATH = new Color(100, 0, 0);
	private static final Color ROW_SELECTED = new Color(70, 70, 80);
	private static final Color TICK_BADGE_DEFAULT = new Color(45, 45, 50);
	private static final Color TICK_BADGE_ALT = new Color(38, 38, 42);
	private static final int TICK_BADGE_WIDTH = 46;

	private static final int ICON_SIZE = 18;

	private final Runnable onBackCallback;
	private final SpriteManager spriteManager;
	private final DamageMap damageMap;
	private final com.google.gson.Gson gson;
	private TickRecording recording;
	private List<TickData> ticks;

	// Cached icons
	private ImageIcon iconHitpoints;
	private ImageIcon iconPrayer;
	private ImageIcon iconAttack;
	private ImageIcon iconAgility;

	// Playback state
	private int currentTickIndex = 0;
	private boolean isPlaying = false;
	private float playbackSpeed = 1.0f;
	private Timer playbackTimer;

	// UI Components
	private JLabel titleLabel;
	private JLabel locationLabel;
	private JLabel deathSummaryLabel;
	private JLabel deathTimingLabel;
	private JButton playPauseBtn;
	private JLabel tickCounterLabel;
	private JLabel statusLabel;
	private JLabel telemetryLabel;
	private JComboBox<String> speedCombo;
	private StatBoxPanel hpBar;
	private StatBoxPanel prayerBar;
	private JLabel damageTakenLabel;
	private JLabel damageDealtLabel;
	private JLabel prayersLabel;
	private JPanel currentTickPanel;
	private JList<TickData> eventLogList;
	private DefaultListModel<TickData> eventLogModel;

	// Unicode icons for playback controls
	private static final String ICON_FIRST = "\u23EE"; // ⏮
	private static final String ICON_PREV = "\u25C0"; // ◀
	private static final String ICON_PLAY = "\u25B6"; // ▶
	private static final String ICON_PAUSE = "\u23F8"; // ⏸
	private static final String ICON_NEXT = "\u25B6"; // ▶
	private static final String ICON_LAST = "\u23ED"; // ⏭

	// Status colors
	private static final Color STATUS_ATTACKING = new Color(255, 150, 100);
	private static final Color STATUS_IN_COMBAT = new Color(255, 200, 100);
	private static final Color STATUS_MOVING = new Color(100, 200, 255);
	private static final Color STATUS_IDLE = ColorScheme.LIGHT_GRAY_COLOR;

	// Combat detection - ticks to look back for "In Combat" status
	private static final int COMBAT_LOOKBACK_TICKS = 5;

	public PlaybackPanel(Runnable onBackCallback, SpriteManager spriteManager, DamageMap damageMap,
		com.google.gson.Gson gson)
	{
		this.onBackCallback = onBackCallback;
		this.spriteManager = spriteManager;
		this.damageMap = damageMap;
		this.gson = gson;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		loadSprites();
		buildUI();
		setupKeyboardShortcuts();
	}

	private void loadSprites()
	{
		if (spriteManager == null)
		{
			return;
		}

		spriteManager.getSpriteAsync(SpriteID.SKILL_HITPOINTS, 0, img ->
		{
			iconHitpoints = scaleIcon(img);
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_PRAYER, 0, img ->
		{
			iconPrayer = scaleIcon(img);
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_ATTACK, 0, img ->
		{
			iconAttack = scaleIcon(img);
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_AGILITY, 0, img ->
		{
			iconAgility = scaleIcon(img);
		});

		com.tickreplaylogger.ui.CombatIcons.load(spriteManager);
	}

	private ImageIcon scaleIcon(BufferedImage img)
	{
		if (img == null)
		{
			return null;
		}
		Image scaled = img.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH);
		return new ImageIcon(scaled);
	}

	private void buildUI()
	{
		// === Header ===
		JPanel headerPanel = new JPanel();
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(8, 10, 8, 10));

		// Back button row
		JPanel backRow = new JPanel(new BorderLayout());
		backRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		backRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton backBtn = new JButton("Back");
		backBtn.setMargin(new Insets(2, 10, 2, 10));
		backBtn.setFocusPainted(false);
		backBtn.setToolTipText("Back to recordings");
		backBtn.addActionListener(e ->
		{
			stopPlayback();
			onBackCallback.run();
		});
		backRow.add(backBtn, BorderLayout.WEST);
		headerPanel.add(backRow);

		// Title row
		titleLabel = new JLabel("Recording Name");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.add(titleLabel);

		// Location row
		locationLabel = new JLabel("Location");
		locationLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		locationLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		locationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.add(locationLabel);

		// Death summary: the fatal hit's source, confidence and reactability.
		deathSummaryLabel = new com.tickreplaylogger.ui.SingleLineLabel(" ");
		deathSummaryLabel.setForeground(DAMAGE_RECEIVED_COLOR);
		deathSummaryLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		deathSummaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.add(deathSummaryLabel);
		deathTimingLabel = new com.tickreplaylogger.ui.SingleLineLabel(" ");
		deathTimingLabel.setForeground(DAMAGE_RECEIVED_COLOR);
		deathTimingLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		deathTimingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.add(deathTimingLabel);

		add(headerPanel, BorderLayout.NORTH);

		// === Main Content ===
		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

		// Control bar
		JPanel controlBar = createControlBar();
		controlBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		mainPanel.add(controlBar);
		mainPanel.add(Box.createVerticalStrut(8));

		// Stats section - fixed height
		JPanel statsPanel = new JPanel();
		statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(4, 8, 4, 8));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		hpBar = new StatBoxPanel("HP", true);
		hpBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		statsPanel.add(hpBar);

		prayerBar = new StatBoxPanel("PR", false);
		prayerBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		statsPanel.add(prayerBar);

		// Damage counters row
		JPanel damageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
		damageRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		damageRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		damageTakenLabel = new JLabel("Taken: 0");
		damageTakenLabel.setForeground(DAMAGE_RECEIVED_COLOR);
		damageTakenLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		damageRow.add(damageTakenLabel);

		damageDealtLabel = new JLabel("Dealt: 0");
		damageDealtLabel.setForeground(DAMAGE_DEALT_COLOR);
		damageDealtLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		damageRow.add(damageDealtLabel);

		statsPanel.add(damageRow);
		statsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, statsPanel.getPreferredSize().height + 10));

		mainPanel.add(statsPanel);

		// Active prayers - fixed height for ~5 entries
		JPanel prayersSection = new JPanel(new BorderLayout());
		prayersSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		prayersSection.setBorder(new EmptyBorder(4, 8, 4, 8));
		prayersSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		prayersSection.setPreferredSize(new Dimension(0, 90));
		prayersSection.setMinimumSize(new Dimension(0, 90));
		prayersSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));

		JLabel prayersTitleLabel = new JLabel("Active Prayers:");
		prayersTitleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		prayersTitleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		prayersSection.add(prayersTitleLabel, BorderLayout.NORTH);

		prayersLabel = new JLabel("None");
		prayersLabel.setForeground(PRAYER_COLOR);
		prayersLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		prayersLabel.setVerticalAlignment(SwingConstants.TOP);
		prayersSection.add(prayersLabel, BorderLayout.CENTER);

		mainPanel.add(prayersSection);

		// Current tick events - fixed height for ~5 entries
		JPanel currentTickSection = new JPanel(new BorderLayout());
		currentTickSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		currentTickSection.setBorder(new EmptyBorder(4, 8, 4, 8));
		currentTickSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		currentTickSection.setPreferredSize(new Dimension(0, 100));
		currentTickSection.setMinimumSize(new Dimension(0, 100));
		currentTickSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

		JLabel currentTickTitle = new JLabel("This Tick:");
		currentTickTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		currentTickTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		currentTickSection.add(currentTickTitle, BorderLayout.NORTH);

		currentTickPanel = new JPanel();
		currentTickPanel.setLayout(new BoxLayout(currentTickPanel, BoxLayout.Y_AXIS));
		currentTickPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		currentTickSection.add(currentTickPanel, BorderLayout.CENTER);

		mainPanel.add(currentTickSection);

		// Event log title
		JLabel eventLogTitle = new JLabel("Event Log (click to jump)");
		eventLogTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		eventLogTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		eventLogTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		mainPanel.add(eventLogTitle);

		// Event log list
		eventLogModel = new DefaultListModel<>();
		eventLogList = new JList<TickData>(eventLogModel)
		{
			@Override
			public boolean getScrollableTracksViewportWidth()
			{
				return true;
			}

			@Override
			public void setBounds(int x, int y, int width, int height)
			{
				super.setBounds(x, y, width, height);
				if (width > 0 && width != getFixedCellWidth())
					setFixedCellWidth(width);
			}
		};
		eventLogList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		eventLogList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		eventLogList.setCellRenderer(new EventLogCellRenderer());
		eventLogList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				int index = eventLogList.getSelectedIndex();
				if (index >= 0 && index != currentTickIndex)
				{
					jumpToTick(index);
				}
			}
		});

		JScrollPane eventLogScroll = new JScrollPane(eventLogList);
		eventLogScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		eventLogScroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		eventLogScroll.setBorder(null);
		eventLogScroll.setAlignmentX(Component.LEFT_ALIGNMENT);

		mainPanel.add(eventLogScroll);

		add(mainPanel, BorderLayout.CENTER);
	}

	private JPanel createControlBar()
	{
		JPanel controlBar = new JPanel();
		controlBar.setLayout(new BoxLayout(controlBar, BoxLayout.Y_AXIS));
		controlBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controlBar.setBorder(new EmptyBorder(6, 8, 6, 8));

		// Row 1: Transport buttons with icons
		JPanel transportRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		transportRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		transportRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		transportRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		JButton firstBtn = createTransportButton(ICON_FIRST, "First tick");
		firstBtn.addActionListener(e -> jumpToTick(0));
		transportRow.add(firstBtn);

		JButton prevBtn = createTransportButton(ICON_PREV, "Previous tick (Left)");
		prevBtn.addActionListener(e -> prevTick());
		transportRow.add(prevBtn);

		playPauseBtn = createTransportButton(ICON_PLAY, "Play/Pause (Space)");
		playPauseBtn.setPreferredSize(new Dimension(40, 26));
		playPauseBtn.addActionListener(e -> togglePlayback());
		transportRow.add(playPauseBtn);

		JButton nextBtn = createTransportButton(ICON_NEXT, "Next tick (Right)");
		nextBtn.addActionListener(e -> nextTick());
		transportRow.add(nextBtn);

		JButton lastBtn = createTransportButton(ICON_LAST, "Last tick (death)");
		lastBtn.addActionListener(e -> jumpToTick(ticks != null ? ticks.size() - 1 : 0));
		transportRow.add(lastBtn);

		controlBar.add(transportRow);
		controlBar.add(Box.createVerticalStrut(4));

		// Row 2: Speed selector + tick counter + status
		JPanel infoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		infoRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		infoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		infoRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		speedCombo = new JComboBox<>(new String[]
		{ "0.5x", "1x", "2x" });
		speedCombo.setSelectedIndex(1); // Default 1x
		speedCombo.setPreferredSize(new Dimension(55, 22));
		speedCombo.addActionListener(e -> updatePlaybackSpeed());
		infoRow.add(speedCombo);

		tickCounterLabel = new JLabel("Tick 0/0");
		tickCounterLabel.setForeground(Color.WHITE);
		tickCounterLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		infoRow.add(tickCounterLabel);

		// Status indicator
		statusLabel = new JLabel("Idle");
		statusLabel.setForeground(STATUS_IDLE);
		statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		infoRow.add(statusLabel);

		// Telemetry indicator
		telemetryLabel = new JLabel("");
		telemetryLabel.setForeground(TELEMETRY_COLOR);
		telemetryLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		infoRow.add(telemetryLabel);

		controlBar.add(infoRow);

		return controlBar;
	}

	private JButton createTransportButton(String icon, String tooltip)
	{
		JButton btn = new JButton(icon);
		btn.setFont(new Font("Dialog", Font.PLAIN, 14));
		btn.setMargin(new Insets(2, 2, 2, 2));
		btn.setPreferredSize(new Dimension(32, 26));
		btn.setToolTipText(tooltip);
		btn.setFocusPainted(false);
		return btn;
	}

	private void setupKeyboardShortcuts()
	{
		// Space = play/pause
		getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "playPause");
		getActionMap().put("playPause", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				togglePlayback();
			}
		});

		// Left arrow = prev tick
		getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prevTick");
		getActionMap().put("prevTick", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				prevTick();
			}
		});

		// Right arrow = next tick
		getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextTick");
		getActionMap().put("nextTick", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				nextTick();
			}
		});
	}

	/**
	 * Load a tick recording for playback.
	 */
	public void loadRecording(TickRecording recording)
	{
		stopPlayback();

		this.recording = recording;
		this.ticks = recording.getTicks();
		this.currentTickIndex = 0;

		// Reset stat bars for fresh change tracking
		hpBar.reset();
		prayerBar.reset();

		// Update header
		String displayName = com.tickreplaylogger.capture.RecordingEncounterLabels.preparedTitle(recording, damageMap);
		titleLabel.setText(com.tickreplaylogger.util.DisplayFormat.humanize(displayName));
		titleLabel.setToolTipText(java.util.Objects.equals(displayName, recording.getKillerName()) ? null
			: "Observed encounter; fatal source: "
				+ com.tickreplaylogger.util.DisplayFormat.fatalSourceName(recording));
		locationLabel.setText(recording.getLocation() + " - " + recording.getTimeSinceRecording());
		deathSummaryLabel.setText(buildDeathSummary());

		// Populate event log
		eventLogModel.clear();
		for (TickData tick : ticks)
		{
			eventLogModel.addElement(tick);
		}

		// Update display
		updateDisplay();
	}

	private void togglePlayback()
	{
		if (isPlaying)
		{
			stopPlayback();
		}
		else
		{
			startPlayback();
		}
	}

	private void startPlayback()
	{
		if (ticks == null || ticks.isEmpty())
		{
			return;
		}

		// If at end, restart from beginning
		if (currentTickIndex >= ticks.size() - 1)
		{
			currentTickIndex = 0;
			hpBar.reset();
			prayerBar.reset();
			updateDisplay();
		}

		int delay = (int) (TICK_MS / playbackSpeed);
		playbackTimer = new Timer(delay, e -> advanceTick());
		playbackTimer.start();
		isPlaying = true;
		playPauseBtn.setText(ICON_PAUSE);
	}

	/** Release playback work and references when the plugin is disabled. Called on the EDT. */
	public void dispose()
	{
		stopPlayback();
		ticks = java.util.Collections.emptyList();
		recording = null;
		eventLogModel.clear();
		removeAll();
	}

	private void stopPlayback()
	{
		if (playbackTimer != null)
		{
			playbackTimer.stop();
			playbackTimer = null;
		}
		isPlaying = false;
		playPauseBtn.setText(ICON_PLAY);
	}

	private void advanceTick()
	{
		if (currentTickIndex < ticks.size() - 1)
		{
			currentTickIndex++;
			updateDisplay();
		}
		else
		{
			stopPlayback();
		}
	}

	private void prevTick()
	{
		if (currentTickIndex > 0)
		{
			currentTickIndex--;
			updateDisplay();
		}
	}

	private void nextTick()
	{
		if (ticks != null && currentTickIndex < ticks.size() - 1)
		{
			currentTickIndex++;
			updateDisplay();
		}
	}

	private void jumpToTick(int index)
	{
		if (ticks == null || index < 0 || index >= ticks.size())
		{
			return;
		}
		currentTickIndex = index;
		updateDisplay();
	}

	private void updatePlaybackSpeed()
	{
		String selected = (String) speedCombo.getSelectedItem();
		if (selected != null)
		{
			switch (selected)
			{
			case "0.5x":
				playbackSpeed = 0.5f;
				break;
			case "2x":
				playbackSpeed = 2.0f;
				break;
			default:
				playbackSpeed = 1.0f;
			}

			// Update timer if playing
			if (isPlaying && playbackTimer != null)
			{
				playbackTimer.setDelay((int) (TICK_MS / playbackSpeed));
			}
		}
	}

	private void updateDisplay()
	{
		if (ticks == null || ticks.isEmpty())
		{
			return;
		}

		TickData tick = ticks.get(currentTickIndex);

		// Update stat bars
		hpBar.setObservedValue(tick.isStateKnown("currentHp") ? tick.getCurrentHp() : null,
			tick.isStateKnown("maxHp") ? tick.getMaxHp() : null);
		prayerBar.setObservedValue(tick.isStateKnown("currentPrayer") ? tick.getCurrentPrayer() : null,
			tick.isStateKnown("maxPrayer") ? tick.getMaxPrayer() : null);

		// Update cumulative damage counters
		int[] damageStats = calculateCumulativeDamage(currentTickIndex);
		damageTakenLabel.setText("Taken: " + damageStats[0]);
		damageDealtLabel.setText("Dealt: " + damageStats[1]);

		// Update active prayers as vertical list
		List<String> prayers = tick.getActivePrayers();
		if (Boolean.FALSE.equals(tick.getPrayerStateKnown()))
		{
			prayersLabel.setText("Unavailable at this tick");
		}
		else if (prayers == null || prayers.isEmpty())
		{
			prayersLabel.setText("None");
		}
		else
		{
			// Use HTML for vertical list
			StringBuilder html = new StringBuilder("<html>");
			for (String prayer : prayers)
			{
				html.append(prayer).append("<br>");
			}
			html.append("</html>");
			prayersLabel.setText(html.toString());
		}

		// Update current tick events
		updateCurrentTickPanel(tick);

		// Update event log selection
		eventLogList.setSelectedIndex(currentTickIndex);
		eventLogList.ensureIndexIsVisible(currentTickIndex);

		// Update tick counter
		tickCounterLabel.setText("Tick " + (currentTickIndex + 1) + "/" + ticks.size());

		// Update telemetry
		updateTelemetryLabel(tick);

		// Update status indicator
		updateStatus(tick);
	}

	private void updateStatus(TickData tick)
	{
		// Check if actively attacking this tick
		boolean isAttackingNow = tick.getDamageEvents().stream()
			.anyMatch(dmg -> dmg.getDirection() == DamageEvent.DamageDirection.DEALT);

		// Check if in combat (attacked or was attacked in recent ticks)
		boolean isInCombat = isInCombatRecently();

		MovementData movement = tick.getMovement();
		boolean isMoving = movement != null && movement.hasMovement();

		// Priority: Attacking > In Combat > Moving > Idle
		if (isAttackingNow)
		{
			statusLabel.setText("Attacking");
			statusLabel.setForeground(STATUS_ATTACKING);
		}
		else if (isInCombat)
		{
			statusLabel.setText("In Combat");
			statusLabel.setForeground(STATUS_IN_COMBAT);
		}
		else if (isMoving)
		{
			statusLabel.setText("Moving");
			statusLabel.setForeground(STATUS_MOVING);
		}
		else
		{
			statusLabel.setText("Idle");
			statusLabel.setForeground(STATUS_IDLE);
		}
	}

	private void updateTelemetryLabel(TickData tick)
	{
		String text = buildTelemetryText(tick);
		if (text != null)
		{
			boolean isLagSpike = tick.getTickDurationMs() > 700 || tick.getPingMs() > 200
				|| tick.getPacketLossPercent() > 0;
			telemetryLabel.setText(text);
			telemetryLabel.setForeground(isLagSpike ? TELEMETRY_LAG_COLOR : TELEMETRY_COLOR);
		}
		else
		{
			telemetryLabel.setText("");
		}
	}

	private static String buildTelemetryText(TickData tick)
	{
		long tickMs = tick.getTickDurationMs();
		int pingMs = tick.getPingMs();
		int lossPercent = tick.getPacketLossPercent();

		if (tickMs <= 0 && pingMs <= 0 && lossPercent <= 0)
		{
			return null;
		}

		StringBuilder sb = new StringBuilder();
		if (tickMs > 0)
		{
			sb.append(tickMs).append("ms tick");
		}
		if (pingMs > 0)
		{
			if (sb.length() > 0)
			{
				sb.append(" | ");
			}
			sb.append(pingMs).append("ms ping");
		}
		if (lossPercent > 0)
		{
			if (sb.length() > 0)
			{
				sb.append(" | ");
			}
			sb.append(lossPercent).append("% failed");
		}
		return sb.toString();
	}

	/**
	 * Check if player has been in combat recently (dealt or received damage in last N ticks)
	 */
	private boolean isInCombatRecently()
	{
		int startIndex = Math.max(0, currentTickIndex - COMBAT_LOOKBACK_TICKS);
		for (int i = startIndex; i <= currentTickIndex; i++)
		{
			TickData t = ticks.get(i);
			// If any damage events (dealt or received), we're in combat
			if (!t.getDamageEvents().isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	private void updateCurrentTickPanel(TickData tick)
	{
		currentTickPanel.removeAll();

		// Damage events — icon = type (sword/bow/wand/dot), colour = direction (red in / green out).
		for (DamageEvent dmg : tick.getDamageEvents())
		{
			JLabel label = createIconLabel(com.tickreplaylogger.util.DisplayFormat.damageLineText(dmg),
				com.tickreplaylogger.ui.CombatIcons.iconFor(dmg),
				com.tickreplaylogger.util.DisplayFormat.damageDirectionColor(dmg),
				com.tickreplaylogger.util.DisplayFormat.damageTooltip(dmg));
			currentTickPanel.add(label);
		}

		// Player actions (skip PRAYER_DRAINED as it's unreliable - often triggered by prayer switching)
		for (PlayerAction action : tick.getPlayerActions())
		{
			// Skip PRAYER_DRAINED since it incorrectly fires when switching overhead prayers
			if (action.getType() == PlayerAction.ActionType.PRAYER_DRAINED)
			{
				continue;
			}
			ImageIcon icon = getActionIcon(action);
			Color color = getActionColor(action);
			JLabel label = createIconLabel(com.tickreplaylogger.util.DisplayFormat.actionText(action), icon, color,
				action.getDescription());
			currentTickPanel.add(label);
		}

		// Combat effects (freeze/teleblock/stun/knockback/drain/disarm/poison)
		if (tick.getEffectNotes() != null)
		{
			for (String note : tick.getEffectNotes())
			{
				JLabel label = createIconLabel(note, iconPrayer, EFFECT_COLOR);
				currentTickPanel.add(label);
			}
		}

		// Movement
		MovementData movement = tick.getMovement();
		if (movement != null && movement.hasMovement())
		{
			JLabel label = createIconLabel(movement.getDescription(), iconAgility, MOVEMENT_COLOR);
			currentTickPanel.add(label);
		}

		// Telemetry
		String telText = buildTelemetryText(tick);
		if (telText != null)
		{
			boolean isLagSpike = tick.getTickDurationMs() > 700 || tick.getPingMs() > 200
				|| tick.getPacketLossPercent() > 0;
			JLabel telLabel = new JLabel(telText);
			telLabel.setForeground(isLagSpike ? TELEMETRY_LAG_COLOR : TELEMETRY_COLOR);
			telLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			telLabel.setBorder(new EmptyBorder(1, 20, 1, 0));
			telLabel.setToolTipText("Client tick interval / Latest ping sample / Failed ping attempts");
			currentTickPanel.add(telLabel);
		}

		// Empty state
		if (currentTickPanel.getComponentCount() == 0)
		{
			JLabel emptyLabel = new JLabel("(no events)");
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
			currentTickPanel.add(emptyLabel);
		}

		currentTickPanel.revalidate();
		currentTickPanel.repaint();
	}

	private JLabel createIconLabel(String text, ImageIcon icon, Color color)
	{
		return createIconLabel(text, icon, color, text);
	}

	private JLabel createIconLabel(String text, ImageIcon icon, Color color, String tooltip)
	{
		JLabel label = new com.tickreplaylogger.ui.SingleLineLabel(text);
		label.setForeground(color);
		label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		label.setToolTipText(tooltip);
		if (icon != null)
		{
			label.setIcon(icon);
			label.setIconTextGap(6);
			label.setBorder(new EmptyBorder(1, 4, 1, 0));
		}
		else
		{
			label.setBorder(new EmptyBorder(1, 20, 1, 0));
		}
		return label;
	}

	private ImageIcon getActionIcon(PlayerAction action)
	{
		String desc = action.getDescription().toLowerCase();
		if (desc.startsWith("ate") || desc.startsWith("drank"))
		{
			return iconHitpoints;
		}
		else if (desc.contains("prayer") || desc.startsWith("activated") || desc.startsWith("deactivated")
			|| desc.contains("disabled"))
		{
			return iconPrayer;
		}
		else if (desc.startsWith("equipped") || desc.startsWith("unequipped"))
		{
			return iconAttack;
		}
		else if (desc.startsWith("attacked") || desc.startsWith("special"))
		{
			return iconAttack;
		}
		return null;
	}

	private Color getActionColor(PlayerAction action)
	{
		PlayerAction.ActionType type = action.getType();
		if (type == PlayerAction.ActionType.PRAYER_ACTIVATED || type == PlayerAction.ActionType.PRAYER_DEACTIVATED)
		{
			return PRAYER_COLOR;
		}
		return ACTION_COLOR;
	}

	/**
	 * A fatal caption requires final-tick evidence; earlier hits cannot explain an unobserved death.
	 */
	private String buildDeathSummary()
	{
		deathTimingLabel.setText(" ");
		deathTimingLabel.setToolTipText(null);
		deathSummaryLabel.setToolTipText(null);
		if ("Live Snapshot".equals(recording.getLocation()))
			return " ";
		if (ticks == null || ticks.isEmpty())
		{
			return " ";
		}
		if ("UNRESOLVED_IMPACT_IN_DEATH_TICK".equals(recording.getFatalHitBasis()))
			return "Fatal impact not established";
		int fatalTick = ticks.get(ticks.size() - 1).getTickNumber();
		String prefix = "Last received hit: ";
		String fatalBasis = recording.getFatalHitBasis();
		if ("NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK".equals(fatalBasis)
			|| recording.getUnresolvedImpactObservationCount() > 0)
			return "Fatal impact not established";
		if (fatalBasis != null)
		{
			prefix = "UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK".equals(fatalBasis) ? "Fatal: " : "Last hit: ";
		}
		else if (recording.getRichExportJson() != null)
		{
			// No prepared wrapper evidence: keep the raw recording, but do not scan rich data on the EDT.
			return "Fatal impact not established";
		}

		for (int i = ticks.size() - 1; i >= 0; i--)
		{
			if (ticks.get(i).getTickNumber() != fatalTick)
				continue;
			List<DamageEvent> events = ticks.get(i).getDamageEvents();
			for (int j = events.size() - 1; j >= 0; j--)
			{
				DamageEvent d = events.get(j);
				if (d.getDirection() != DamageEvent.DamageDirection.RECEIVED || d.getAmount() <= 0)
				{
					continue;
				}
				StringBuilder sb = new StringBuilder(prefix);
				sb.append(d.getAmount()).append(" from ")
					.append(com.tickreplaylogger.util.DisplayFormat.damageSourceName(d));
				String det = d.getDetermination();
				boolean typeless = com.tickreplaylogger.util.DisplayFormat
					.displayDamageType(d) == DamageEvent.DamageType.TYPELESS;
				deathTimingLabel
					.setText(typeless ? "Typeless" : com.tickreplaylogger.util.DisplayFormat.timingLabel(det));
				deathSummaryLabel.setToolTipText(com.tickreplaylogger.util.DisplayFormat.damageTooltip(d));
				deathTimingLabel.setToolTipText(com.tickreplaylogger.util.DisplayFormat.damageTooltip(d));
				return sb.toString();
			}
		}
		return "Fatal impact not established";
	}

	/**
	 * Calculate cumulative damage taken and dealt up to the given tick index.
	 * @return int[2] where [0] = damage taken, [1] = damage dealt
	 */
	private int[] calculateCumulativeDamage(int upToIndex)
	{
		int taken = 0;
		int dealt = 0;
		for (int i = 0; i <= upToIndex && i < ticks.size(); i++)
		{
			for (DamageEvent dmg : ticks.get(i).getDamageEvents())
			{
				if (dmg.getDirection() == DamageEvent.DamageDirection.RECEIVED)
				{
					taken += dmg.getAmount();
				}
				else if (dmg.getDirection() == DamageEvent.DamageDirection.DEALT)
				{
					dealt += dmg.getAmount();
				}
			}
		}
		return new int[]
		{ taken, dealt };
	}

	/**
	 * Custom cell renderer for the event log list with angled multi-color backgrounds.
	 */
	private class EventLogCellRenderer implements ListCellRenderer<TickData>
	{
		@Override
		public Component getListCellRendererComponent(JList<? extends TickData> list, TickData tick, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			String summary = buildTickSummary(tick, true);
			String tooltip = buildTickSummary(tick, false);
			boolean isDeathTick = (index == ticks.size() - 1);
			boolean isCurrent = (index == currentTickIndex);

			// Collect event colors for this tick
			java.util.List<Color> eventColors = new java.util.ArrayList<>();

			if (hasDamageReceived(tick))
			{
				eventColors.add(ROW_SIGNIFICANT_DAMAGE);
			}
			if (hasDamageDealt(tick))
			{
				eventColors.add(ROW_SIGNIFICANT_DEALT);
			}
			if (hasHealing(tick))
			{
				eventColors.add(ROW_SIGNIFICANT_HEAL);
			}
			if (hasPrayerAction(tick))
			{
				eventColors.add(ROW_SIGNIFICANT_PRAYER);
			}
			if (tick.getEffectNotes() != null && !tick.getEffectNotes().isEmpty())
			{
				eventColors.add(ROW_SIGNIFICANT_EFFECT);
			}

			// Create custom panel with angled stripes
			AngledStripePanel panel = new AngledStripePanel(eventColors, index);
			panel.setToolTipText(tooltip);
			panel.setDeathTick(isDeathTick);
			panel.setCurrentTick(isCurrent);

			// Add label for text
			JLabel label = new com.tickreplaylogger.ui.SingleLineLabel(summary.isEmpty() ? " " : summary);
			label.setToolTipText(tooltip);
			label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
			label.setForeground(isDeathTick ? DAMAGE_RECEIVED_COLOR : Color.WHITE);
			label.setBorder(new EmptyBorder(5, 6, 5, 6));

			panel.setLayout(new BorderLayout());
			JLabel tickBadge = new JLabel(String.valueOf(tick.getTickNumber()), SwingConstants.CENTER);
			tickBadge.setOpaque(true);
			Color badgeColor;
			if (isDeathTick)
			{
				badgeColor = ROW_SIGNIFICANT_DAMAGE;
			}
			else if (isCurrent)
			{
				badgeColor = ROW_SELECTED;
			}
			else
			{
				badgeColor = (index % 2 == 0) ? TICK_BADGE_DEFAULT : TICK_BADGE_ALT;
			}
			tickBadge.setBackground(badgeColor);
			tickBadge.setForeground(Color.WHITE);
			tickBadge.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
			tickBadge.setPreferredSize(new Dimension(TICK_BADGE_WIDTH, 28));
			panel.add(tickBadge, BorderLayout.WEST);
			panel.add(label, BorderLayout.CENTER);
			int rowWidth = list.getWidth() > 0 ? list.getWidth() : 200;
			panel.setPreferredSize(new Dimension(rowWidth, 28));
			panel.setSize(panel.getPreferredSize());
			panel.doLayout();

			return panel;
		}

		private String buildTickSummary(TickData tick, boolean compact)
		{
			List<String> fragments = new ArrayList<>();

			// Summarize damage events, tagging the main source/target so the jump-list reads clearly
			// ("-35 Snakeling" / "+112 Zulrah") instead of a bare "-35 dmg".
			int dmgReceived = 0;
			int dmgDealt = 0;
			int topRecv = -1;
			int topDealt = -1;
			String recvSource = null;
			String dealtTarget = null;
			for (DamageEvent dmg : tick.getDamageEvents())
			{
				if (dmg.getDirection() == DamageEvent.DamageDirection.RECEIVED)
				{
					dmgReceived += dmg.getAmount();
					if (dmg.getAmount() > topRecv)
					{
						topRecv = dmg.getAmount();
						recvSource = com.tickreplaylogger.util.DisplayFormat.damageSourceName(dmg);
					}
				}
				else if (dmg.getDirection() == DamageEvent.DamageDirection.DEALT)
				{
					dmgDealt += dmg.getAmount();
					if (dmg.getAmount() > topDealt)
					{
						topDealt = dmg.getAmount();
						dealtTarget = dmg.getSource();
					}
				}
			}
			if (dmgReceived > 0)
			{
				fragments.add("-" + dmgReceived
					+ (recvSource != null ? " " + com.tickreplaylogger.util.DisplayFormat.humanize(recvSource) : ""));
			}
			if (dmgDealt > 0)
			{
				fragments.add("+" + dmgDealt
					+ (dealtTarget != null ? " " + com.tickreplaylogger.util.DisplayFormat.humanize(dealtTarget) : ""));
			}

			// Name recorded actions as well as colouring the row. Prayer-only ticks must not be blank.
			for (PlayerAction action : tick.getPlayerActions())
			{
				if (action.getType() == PlayerAction.ActionType.PRAYER_DRAINED)
					continue;
				String text = compact ? com.tickreplaylogger.util.DisplayFormat.actionText(action)
					: action.getDescription();
				if (compact && action.getTarget() != null
					&& (action.getType() == PlayerAction.ActionType.PRAYER_ACTIVATED
						|| action.getType() == PlayerAction.ActionType.PRAYER_DEACTIVATED))
				{
					String prayer = action.getTarget().replaceFirst("(?i)^Protect from ", "");
					text = (action.getType() == PlayerAction.ActionType.PRAYER_ACTIVATED ? "Prayer on: "
						: "Prayer off: ") + prayer;
				}
				if (text != null && !text.trim().isEmpty() && !fragments.contains(text))
				{
					fragments.add(text);
				}
			}

			// Combat effects
			if (tick.getEffectNotes() != null)
			{
				fragments.addAll(tick.getEffectNotes());
			}

			MovementData movement = tick.getMovement();
			if (movement != null && movement.hasMovement())
			{
				int tiles = movement.getTilesCompleted();
				String tileLabel = tiles == 1 ? "tile" : "tiles";
				fragments.add("Moved " + tiles + " " + tileLabel);
			}

			// Telemetry info (only show notable values in summary)
			long tickMs = tick.getTickDurationMs();
			int pingMs = tick.getPingMs();
			int lossPercent = tick.getPacketLossPercent();
			if (tickMs > 700 || pingMs > 200 || lossPercent > 0)
			{
				StringBuilder tel = new StringBuilder();
				if (tickMs > 700)
				{
					tel.append(tickMs).append("ms tick");
				}
				if (pingMs > 200)
				{
					if (tel.length() > 0)
					{
						tel.append(", ");
					}
					tel.append(pingMs).append("ms ping");
				}
				if (lossPercent > 0)
				{
					if (tel.length() > 0)
					{
						tel.append(", ");
					}
					tel.append(lossPercent).append("% failed");
				}
				fragments.add(tel.toString());
			}

			// Show dash if no events
			if (fragments.isEmpty())
			{
				return "";
			}

			return String.join(", ", fragments);
		}

		private boolean hasDamageReceived(TickData tick)
		{
			for (DamageEvent dmg : tick.getDamageEvents())
			{
				if (dmg.getDirection() == DamageEvent.DamageDirection.RECEIVED && dmg.getAmount() > 0)
				{
					return true;
				}
			}
			return false;
		}

		private boolean hasDamageDealt(TickData tick)
		{
			for (DamageEvent dmg : tick.getDamageEvents())
			{
				if (dmg.getDirection() == DamageEvent.DamageDirection.DEALT && dmg.getAmount() > 0)
				{
					return true;
				}
			}
			return false;
		}

		private boolean hasHealing(TickData tick)
		{
			for (PlayerAction action : tick.getPlayerActions())
			{
				if (action.getType() == PlayerAction.ActionType.EAT_FOOD
					|| action.getType() == PlayerAction.ActionType.DRINK_POTION)
				{
					return true;
				}
			}
			return false;
		}

		private boolean hasPrayerAction(TickData tick)
		{
			for (PlayerAction action : tick.getPlayerActions())
			{
				if (action.getType() == PlayerAction.ActionType.PRAYER_ACTIVATED
					|| action.getType() == PlayerAction.ActionType.PRAYER_DEACTIVATED)
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Custom panel that paints angled color stripes for multiple event types.
	 */
	private class AngledStripePanel extends JPanel
	{
		private final java.util.List<Color> colors;
		private final int rowIndex;
		private boolean isDeathTick = false;
		private boolean isCurrentTick = false;

		public AngledStripePanel(java.util.List<Color> colors, int rowIndex)
		{
			this.colors = colors;
			this.rowIndex = rowIndex;
			setOpaque(true);
		}

		public void setDeathTick(boolean deathTick)
		{
			this.isDeathTick = deathTick;
		}

		public void setCurrentTick(boolean currentTick)
		{
			this.isCurrentTick = currentTick;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2d = (Graphics2D) g.create();
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int w = getWidth();
			int h = getHeight();

			// Death tick gets solid red background
			if (isDeathTick)
			{
				g2d.setColor(ROW_DEATH);
				g2d.fillRect(0, 0, w, h);
				g2d.dispose();
				return;
			}

			// Current tick gets selection highlight
			if (isCurrentTick)
			{
				g2d.setColor(ROW_SELECTED);
				g2d.fillRect(0, 0, w, h);
				g2d.dispose();
				return;
			}

			// No events - use alternating background
			if (colors.isEmpty())
			{
				Color bgColor = (rowIndex % 2 == 0) ? ROW_NORMAL_1 : ROW_NORMAL_2;
				g2d.setColor(bgColor);
				g2d.fillRect(0, 0, w, h);
				g2d.dispose();
				return;
			}

			// Single event - solid color
			if (colors.size() == 1)
			{
				g2d.setColor(colors.get(0));
				g2d.fillRect(0, 0, w, h);
				g2d.dispose();
				return;
			}

			// Multiple events - angled stripes
			int numColors = colors.size();
			int slant = 15; // Pixels to slant the dividers

			for (int i = 0; i < numColors; i++)
			{
				g2d.setColor(colors.get(i));

				// Calculate the x positions for this stripe
				int leftStart = (w * i) / numColors;
				int rightStart = (w * (i + 1)) / numColors;

				// Create angled polygon
				int[] xPoints, yPoints;
				if (i == 0)
				{
					// First stripe - left edge is straight
					xPoints = new int[]
					{ 0, rightStart + slant, rightStart, 0 };
					yPoints = new int[]
					{ 0, 0, h, h };
				}
				else if (i == numColors - 1)
				{
					// Last stripe - right edge is straight
					xPoints = new int[]
					{ leftStart + slant, w, w, leftStart };
					yPoints = new int[]
					{ 0, 0, h, h };
				}
				else
				{
					// Middle stripes - both edges angled
					xPoints = new int[]
					{ leftStart + slant, rightStart + slant, rightStart, leftStart };
					yPoints = new int[]
					{ 0, 0, h, h };
				}

				g2d.fillPolygon(xPoints, yPoints, 4);
			}

			g2d.dispose();
		}
	}
}
