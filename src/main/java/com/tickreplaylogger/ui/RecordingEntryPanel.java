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

import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.MovementData;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.capture.DamageMap;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel displaying a single recording entry with expandable tick timeline.
 */
public class RecordingEntryPanel extends JPanel
{
	private static final Color DAMAGE_RECEIVED_COLOR = new Color(255, 100, 100);
	private static final Color DAMAGE_DEALT_COLOR = new Color(100, 255, 100);
	private static final Color ACTION_COLOR = new Color(255, 200, 100);
	private static final Color PRAYER_COLOR = new Color(150, 150, 255);
	private static final Color MOVEMENT_COLOR = new Color(100, 200, 255);
	private static final Color HP_CHANGE_COLOR = new Color(200, 100, 200);
	private static final Color HP_LOW_COLOR = new Color(255, 80, 80);
	private static final Color HP_MED_COLOR = new Color(255, 180, 80);
	private static final Color HP_HIGH_COLOR = new Color(80, 200, 80);
	private static final Color TELEMETRY_COLOR = new Color(160, 160, 160);
	private static final Color TELEMETRY_LAG_COLOR = new Color(255, 100, 100);

	// Alternating row colors
	private static final Color ROW_COLOR_1 = ColorScheme.DARK_GRAY_COLOR;
	private static final Color ROW_COLOR_2 = new Color(45, 45, 45);
	private static final Color DEATH_ROW_COLOR = new Color(60, 30, 30);

	private static final int ICON_SIZE = 18;

	private final TickRecording recording;
	private final DamageMap damageMap;
	private final com.google.gson.Gson gson;
	private final List<TickData> displayTicks;
	private final JPanel ticksPanel;
	private final SpriteManager spriteManager;
	private final Consumer<TickRecording> saveCallback;
	private final Consumer<TickRecording> renameCallback;
	private final boolean isSaved;
	private boolean expanded = false;
	private boolean collapseUnchanged = false;

	// Cached icons
	private ImageIcon iconHitpoints;
	private ImageIcon iconPrayer;
	private ImageIcon iconAttack;
	private ImageIcon iconSkull;
	private ImageIcon iconHeart;
	private ImageIcon iconAgility;

	/**
	 * Constructor for recordings (without save callback).
	 */
	public RecordingEntryPanel(TickRecording recording, int recordingNumber, Consumer<TickRecording> exportCallback,
		Consumer<TickRecording> deleteCallback, Consumer<TickRecording> playbackCallback,
		Consumer<TickRecording> renameCallback, SpriteManager spriteManager, DamageMap damageMap,
		com.google.gson.Gson gson)
	{
		this(recording, recordingNumber, exportCallback, deleteCallback, playbackCallback, null, renameCallback,
			spriteManager, false, damageMap, gson);
	}

	/**
	 * Constructor with save callback and saved flag.
	 */
	public RecordingEntryPanel(TickRecording recording, int recordingNumber, Consumer<TickRecording> exportCallback,
		Consumer<TickRecording> deleteCallback, Consumer<TickRecording> playbackCallback,
		Consumer<TickRecording> saveCallback, Consumer<TickRecording> renameCallback, SpriteManager spriteManager,
		boolean isSaved, DamageMap damageMap, com.google.gson.Gson gson)
	{
		this.recording = recording;
		this.damageMap = damageMap;
		this.gson = gson;
		this.displayTicks = recording.getTicks();
		this.spriteManager = spriteManager;
		this.saveCallback = saveCallback;
		this.renameCallback = renameCallback;
		this.isSaved = isSaved;

		loadSprites();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		// Header panel (always visible)
		JPanel headerPanel = createHeaderPanel(recordingNumber, exportCallback, deleteCallback, playbackCallback);
		add(headerPanel, BorderLayout.NORTH);

		// Ticks panel (expandable) - use GridBagLayout to respect width
		ticksPanel = new JPanel(new GridBagLayout());
		ticksPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		ticksPanel.setVisible(false);
		add(ticksPanel, BorderLayout.CENTER);
	}

	private void loadSprites()
	{
		if (spriteManager == null)
			return;
		// Load sprites from the game using SpriteManager
		// These are the native OSRS skill icons
		spriteManager.getSpriteAsync(SpriteID.SKILL_HITPOINTS, 0, img ->
		{
			iconHitpoints = scaleIcon(img);
			iconHeart = iconHitpoints; // Reuse for heart
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

		// Use the TAB_COMBAT for skull (combat-related deaths)
		spriteManager.getSpriteAsync(SpriteID.TAB_COMBAT, 0, img ->
		{
			iconSkull = scaleIcon(img);
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

	@Override
	public Dimension getMaximumSize()
	{
		// Constrain width to preferred width, allow any height
		Dimension pref = getPreferredSize();
		return new Dimension(Integer.MAX_VALUE, pref.height);
	}

	private JPanel createHeaderPanel(int recordingNumber, Consumer<TickRecording> exportCallback,
		Consumer<TickRecording> deleteCallback, Consumer<TickRecording> playbackCallback)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 5, 0));

		// Row 1: Title bar
		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		String displayName = com.tickreplaylogger.capture.RecordingEncounterLabels.preparedTitle(recording, damageMap);
		boolean encounterTitle = !java.util.Objects.equals(displayName, recording.getKillerName());
		JLabel titleLabel = new JLabel(
			"#" + recordingNumber + ": " + com.tickreplaylogger.util.DisplayFormat.humanize(displayName));
		if (encounterTitle)
			titleLabel.setToolTipText("Observed encounter; not a fatal-source assignment");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		if (iconSkull != null)
		{
			titleLabel.setIcon(iconSkull);
			titleLabel.setIconTextGap(6);
		}
		titleRow.add(titleLabel, BorderLayout.WEST);
		header.add(titleRow);

		// Row 2: Details + button column
		JPanel detailRow = new JPanel(new BorderLayout());
		detailRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailRow.setBorder(new EmptyBorder(4, 0, 0, 0));

		JPanel detailColumn = new JPanel();
		detailColumn.setLayout(new BoxLayout(detailColumn, BoxLayout.Y_AXIS));
		detailColumn.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel detailLabel = new JLabel(recording.getTimeSinceRecording() + " - " + recording.getLocation());
		detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detailLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		detailColumn.add(detailLabel);
		if (encounterTitle)
		{
			JLabel fatalSource = new JLabel(
				"Fatal source: " + com.tickreplaylogger.util.DisplayFormat.fatalSourceName(recording));
			fatalSource.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			fatalSource.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
			detailColumn.add(fatalSource);
		}

		JLabel summaryLabel = new JLabel(
			recording.getTicks().size() + " ticks, " + recording.getTotalDamageReceived() + " dmg taken");
		summaryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summaryLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		detailColumn.add(summaryLabel);

		JPanel actionColumn = new JPanel();
		actionColumn.setLayout(new BoxLayout(actionColumn, BoxLayout.Y_AXIS));
		actionColumn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionColumn.setBorder(new EmptyBorder(0, 12, 0, 0));

		Consumer<JButton> styleButton = btn ->
		{
			btn.setMargin(new Insets(1, 4, 1, 4));
			btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
			Dimension size = new Dimension(90, 20);
			btn.setMaximumSize(size);
			btn.setMinimumSize(size);
			btn.setPreferredSize(size);
			btn.setAlignmentX(Component.CENTER_ALIGNMENT);
			btn.setFocusPainted(false);
			btn.setBackground(new Color(56, 56, 56));
			btn.setForeground(Color.WHITE);
			btn.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));
			btn.setOpaque(true);
		};

		if (saveCallback != null && !isSaved)
		{
			JButton saveBtn = new JButton("Save");
			saveBtn.setToolTipText("Save this recording (won't auto-delete)");
			saveBtn.addActionListener(e -> saveCallback.accept(recording));
			styleButton.accept(saveBtn);
			saveBtn.setBackground(new Color(50, 80, 50));
			saveBtn.setForeground(new Color(170, 220, 170));
			actionColumn.add(saveBtn);
			actionColumn.add(Box.createVerticalStrut(4));
		}

		JButton renameBtn = new JButton("Rename");
		if (renameCallback != null)
		{
			renameBtn.setToolTipText("Rename this recording");
			renameBtn.addActionListener(e -> renameCallback.accept(recording));
		}
		else
		{
			renameBtn.setEnabled(false);
		}
		styleButton.accept(renameBtn);
		actionColumn.add(renameBtn);
		actionColumn.add(Box.createVerticalStrut(4));

		JButton deleteBtn = new JButton("Delete");
		deleteBtn.setToolTipText("Delete this recording");
		deleteBtn.addActionListener(e -> deleteCallback.accept(recording));
		styleButton.accept(deleteBtn);
		actionColumn.add(deleteBtn);
		actionColumn.add(Box.createVerticalStrut(4));

		JButton exportBtn = new JButton("Copy");
		exportBtn
			.setToolTipText("Copy the full recording JSON (scenes, per-tick world state, events) to the clipboard");
		exportBtn.addActionListener(e -> exportCallback.accept(recording));
		styleButton.accept(exportBtn);
		actionColumn.add(exportBtn);

		detailRow.add(detailColumn, BorderLayout.CENTER);
		detailRow.add(actionColumn, BorderLayout.EAST);
		header.add(detailRow);

		header.add(Box.createVerticalStrut(6));

		// Row 4: Action buttons - Play and Expand on their own row
		JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		actionRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Prominent playback button
		JButton playbackBtn = new JButton("▶ Replay");
		playbackBtn.setMargin(new Insets(4, 12, 4, 12));
		playbackBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		playbackBtn.setBackground(new Color(50, 100, 50));
		playbackBtn.setForeground(new Color(150, 255, 150));
		playbackBtn.setToolTipText("Replay this recording tick-by-tick");
		playbackBtn.addActionListener(e -> playbackCallback.accept(recording));
		playbackBtn.setFocusPainted(false);
		actionRow.add(playbackBtn);

		JButton expandBtn = new JButton("▼ Timeline");
		expandBtn.setMargin(new Insets(4, 8, 4, 8));
		expandBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		expandBtn.setToolTipText("Expand tick timeline");
		expandBtn.addActionListener(e -> toggleExpanded(expandBtn));
		actionRow.add(expandBtn);

		header.add(actionRow);

		// Click non-button areas to expand
		titleRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		titleRow.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				toggleExpanded(expandBtn);
			}
		});

		return header;
	}

	private void toggleExpanded(JButton expandBtn)
	{
		expanded = !expanded;
		expandBtn.setText(expanded ? "▲ Timeline" : "▼ Timeline");

		if (expanded && ticksPanel.getComponentCount() == 0)
		{
			buildTicksPanel();
		}

		ticksPanel.setVisible(expanded);
		revalidate();
		repaint();
	}

	/**
	 * Enable/disable collapsing of unchanged ticks in this entry's timeline. Applies the next
	 * time the timeline is (re)built, i.e. when expanded.
	 */
	public void setCollapseUnchanged(boolean collapseUnchanged)
	{
		this.collapseUnchanged = collapseUnchanged;
	}

	private void buildTicksPanel()
	{
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.NORTH;
		gbc.insets = new Insets(1, 0, 1, 0);

		List<TickData> ticks = displayTicks;
		int totalTicks = ticks.size();
		int rowIndex = 0;

		// Build display rows (optionally collapsing unchanged ticks), then show newest-first
		List<com.tickreplaylogger.util.TickDisplay.Row> rows = com.tickreplaylogger.util.TickDisplay.build(ticks,
			collapseUnchanged);

		for (int r = rows.size() - 1; r >= 0; r--)
		{
			com.tickreplaylogger.util.TickDisplay.Row row = rows.get(r);
			JPanel rowPanel;
			if (row.isGap())
			{
				rowPanel = LiveTickPanel.createGapPanel(row.gapCount);
			}
			else
			{
				int i = row.index;
				int ticksBeforeDeath = totalTicks - 1 - i;
				boolean isDeathTick = (i == totalTicks - 1);
				// Next tick's HP (chronologically) for the delta display
				int nextTickHp = (i < totalTicks - 1) ? ticks.get(i + 1).getCurrentHp() : 0;
				rowPanel = createTickPanel(row.tick, ticksBeforeDeath, isDeathTick, nextTickHp, rowIndex);
			}
			ticksPanel.add(rowPanel, gbc);
			gbc.gridy++;
			rowIndex++;
		}

		// Add filler at bottom to push content up
		gbc.weighty = 1.0;
		ticksPanel.add(Box.createVerticalGlue(), gbc);
	}

	private JPanel createTickPanel(TickData tick, int ticksBeforeDeath, boolean isDeathTick, int nextTickHp,
		int rowIndex)
	{
		JPanel panel = new JPanel(new GridBagLayout());

		// Alternating row colors
		Color bgColor;
		if (isDeathTick)
		{
			bgColor = DEATH_ROW_COLOR;
		}
		else
		{
			bgColor = (rowIndex % 2 == 0) ? ROW_COLOR_1 : ROW_COLOR_2;
		}
		panel.setBackground(bgColor);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		// Calculate HP color
		float hpPercent = tick.getMaxHp() > 0 ? (float) tick.getCurrentHp() / tick.getMaxHp() : 0;
		Color hpColor = tick.isStateKnown("currentHp") && tick.isStateKnown("maxHp")
			? (hpPercent > 0.5 ? HP_HIGH_COLOR : (hpPercent > 0.25 ? HP_MED_COLOR : HP_LOW_COLOR))
			: Color.GRAY;

		// Header: tick label, prayer, and HP on same line
		// Show tick number without the '-' prefix
		String tickLabel = isDeathTick ? "DEATH" : String.valueOf(ticksBeforeDeath);
		String hpText = com.tickreplaylogger.util.DisplayFormat.resourceText(tick, true);
		String prayerText = com.tickreplaylogger.util.DisplayFormat.resourceText(tick, false);

		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(bgColor);

		JLabel tickLbl = new JLabel(tickLabel);
		tickLbl.setForeground(isDeathTick ? HP_LOW_COLOR : Color.WHITE);
		tickLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		headerPanel.add(tickLbl, BorderLayout.WEST);

		// Right side panel for prayer and HP
		JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		statsPanel.setBackground(bgColor);

		// Prayer points label
		JLabel prayerLbl = new JLabel(prayerText);
		prayerLbl.setForeground(PRAYER_COLOR);
		prayerLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		if (iconPrayer != null)
		{
			prayerLbl.setIcon(iconPrayer);
			prayerLbl.setHorizontalTextPosition(SwingConstants.LEFT);
		}
		statsPanel.add(prayerLbl);

		// HP label
		JLabel hpLbl = new JLabel(hpText);
		hpLbl.setForeground(hpColor);
		hpLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		if (iconHitpoints != null)
		{
			hpLbl.setIcon(iconHitpoints);
			hpLbl.setHorizontalTextPosition(SwingConstants.LEFT);
		}
		statsPanel.add(hpLbl);

		headerPanel.add(statsPanel, BorderLayout.EAST);

		panel.add(headerPanel, gbc);
		gbc.gridy++;

		// Content
		boolean hasContent = false;

		// Show HP change from previous tick to this tick
		// Get previous tick's HP (i-1 in array = earlier in game time)
		int prevTickIndex = displayTicks.size() - 2 - ticksBeforeDeath;
		int prevTickHp = (prevTickIndex >= 0 && prevTickIndex < displayTicks.size())
			? displayTicks.get(prevTickIndex).getCurrentHp()
			: tick.getCurrentHp();

		if (!isDeathTick && tick.isStateKnown("currentHp")
			&& (prevTickIndex < 0 || displayTicks.get(prevTickIndex).isStateKnown("currentHp")))
		{
			int hpChange = tick.getCurrentHp() - prevTickHp;

			// Deferred capture puts hits and resulting HP on the same tick. Older journal files
			// appended hits after the snapshot, so their preceding tick explains this HP change.
			int trackedDamageReceived = 0;
			TickData damageTick = recording.isEventsBeforeSnapshot() ? tick
				: prevTickIndex >= 0 ? displayTicks.get(prevTickIndex) : null;
			if (damageTick != null)
			{
				for (DamageEvent dmg : damageTick.getDamageEvents())
				{
					if (dmg.getDirection() == DamageEvent.DamageDirection.RECEIVED)
					{
						trackedDamageReceived += dmg.getAmount();
					}
				}
			}

			if (hpChange > 0)
			{
				// HP increased = healing (from food eaten previous tick, or passive healing)
				JLabel healLabel = createIconLabel("+" + hpChange + " HP", iconHeart, HP_HIGH_COLOR);
				panel.add(healLabel, gbc);
				gbc.gridy++;
				hasContent = true;
			}
			else if (hpChange < 0)
			{
				// HP decreased = damage taken
				int actualDamage = -hpChange; // Make positive
				int untrackedDamage = actualDamage - trackedDamageReceived;
				if (untrackedDamage > 0)
				{
					JLabel dmgLabel = createIconLabel("-" + untrackedDamage + " HP (untracked)", iconHeart,
						HP_CHANGE_COLOR);
					panel.add(dmgLabel, gbc);
					gbc.gridy++;
					hasContent = true;
				}
			}
		}

		// Compare raw chronological neighbors, even when display rows are reversed/collapsed.
		TickData previous = prevTickIndex >= 0 ? displayTicks.get(prevTickIndex) : null;
		prayerLbl
			.setToolTipText(Boolean.FALSE.equals(tick.getPrayerStateKnown()) ? "Prayer state unavailable at this tick"
				: "Active prayers: " + String.join(", ", tick.getActivePrayers()));
		for (String prayer : com.tickreplaylogger.util.DisplayFormat.prayerChanges(tick, previous))
		{
			JLabel label = createIconLabel(prayer, iconPrayer, PRAYER_COLOR);
			panel.add(label, gbc);
			gbc.gridy++;
			hasContent = true;
		}

		// Movement
		MovementData movement = tick.getMovement();
		if (movement != null && movement.hasMovement())
		{
			String moveDesc = movement.getDescription();
			if (moveDesc != null)
			{
				JLabel label = createIconLabel(moveDesc, iconAgility, MOVEMENT_COLOR);
				panel.add(label, gbc);
				gbc.gridy++;
				hasContent = true;
			}
		}

		// Player actions
		for (PlayerAction action : tick.getPlayerActions())
		{
			ImageIcon icon = getActionIcon(action);
			JLabel label = createIconLabel(com.tickreplaylogger.util.DisplayFormat.actionText(action), icon,
				ACTION_COLOR, action.getDescription());
			panel.add(label, gbc);
			gbc.gridy++;
			hasContent = true;
		}

		// Damage events — icon = type (sword/bow/wand/dot), colour = direction (red in / green out).
		for (DamageEvent damage : tick.getDamageEvents())
		{
			JLabel label = createIconLabel(com.tickreplaylogger.util.DisplayFormat.damageLineText(damage),
				com.tickreplaylogger.ui.CombatIcons.iconFor(damage),
				com.tickreplaylogger.util.DisplayFormat.damageDirectionColor(damage),
				com.tickreplaylogger.util.DisplayFormat.damageTooltip(damage));
			panel.add(label, gbc);
			gbc.gridy++;
			hasContent = true;
		}

		// Telemetry (tick duration and ping)
		String telemetryText = buildTelemetryText(tick);
		if (telemetryText != null)
		{
			boolean isLagSpike = tick.getTickDurationMs() > 700 || tick.getPingMs() > 200
				|| tick.getPacketLossPercent() > 0;
			Color telColor = isLagSpike ? TELEMETRY_LAG_COLOR : TELEMETRY_COLOR;
			JLabel telLabel = new JLabel(telemetryText);
			telLabel.setForeground(telColor);
			telLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			telLabel.setBorder(new EmptyBorder(1, 20, 1, 0));
			telLabel.setToolTipText("Client tick interval / Latest ping sample / Failed ping attempts");
			panel.add(telLabel, gbc);
			gbc.gridy++;
			hasContent = true;
		}

		// If no content, show dash
		if (!hasContent)
		{
			JLabel emptyLabel = new JLabel("-");
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
			emptyLabel.setBorder(new EmptyBorder(1, 20, 1, 0));
			panel.add(emptyLabel, gbc);
		}

		return panel;
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

	private ImageIcon getActionIcon(PlayerAction action)
	{
		String desc = action.getDescription().toLowerCase();
		if (desc.startsWith("ate") || desc.startsWith("drank"))
		{
			return iconHitpoints; // Use HP icon for consumables
		}
		else if (desc.contains("prayer") || desc.startsWith("activated") || desc.startsWith("deactivated"))
		{
			return iconPrayer;
		}
		else if (desc.startsWith("equipped") || desc.startsWith("unequipped"))
		{
			return iconAttack; // Use attack icon for gear
		}
		else if (desc.startsWith("attacked"))
		{
			return iconAttack;
		}
		return null;
	}

	private JLabel createIconLabel(String text, ImageIcon icon, Color color)
	{
		return createIconLabel(text, icon, color, text);
	}

	private JLabel createIconLabel(String text, ImageIcon icon, Color color, String tooltip)
	{
		JLabel label = new SingleLineLabel(text);
		label.setForeground(color);
		label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		label.setToolTipText(tooltip); // Show full text on hover
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
}
