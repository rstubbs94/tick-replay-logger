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
import com.tickreplaylogger.model.MovementData;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class for creating tick panels in the live timeline view.
 */
public class LiveTickPanel
{
	private static final Color DAMAGE_RECEIVED_COLOR = new Color(255, 100, 100);
	private static final Color DAMAGE_DEALT_COLOR = new Color(100, 255, 100);
	private static final Color ACTION_COLOR = new Color(255, 200, 100);
	private static final Color PRAYER_COLOR = new Color(150, 150, 255);
	private static final Color MOVEMENT_COLOR = new Color(100, 200, 255);
	private static final Color EFFECT_COLOR = new Color(210, 140, 255);
	private static final Color HP_LOW_COLOR = new Color(255, 80, 80);
	private static final Color HP_MED_COLOR = new Color(255, 180, 80);
	private static final Color HP_HIGH_COLOR = new Color(80, 200, 80);
	private static final Color TELEMETRY_COLOR = new Color(160, 160, 160);
	private static final Color TELEMETRY_LAG_COLOR = new Color(255, 100, 100);

	// Alternating row colors
	private static final Color ROW_COLOR_1 = ColorScheme.DARK_GRAY_COLOR;
	private static final Color ROW_COLOR_2 = new Color(45, 45, 45);

	private static final int ICON_SIZE = 18;

	// Cached icons - shared across all panels
	private static final AtomicReference<ImageIcon> iconHitpoints = new AtomicReference<>();
	private static final AtomicReference<ImageIcon> iconPrayer = new AtomicReference<>();
	private static final AtomicReference<ImageIcon> iconAttack = new AtomicReference<>();
	private static final AtomicReference<ImageIcon> iconAgility = new AtomicReference<>();

	private static volatile boolean iconsLoaded = false;

	/**
	 * Load sprite icons if not already loaded.
	 */
	public static void loadSprites(SpriteManager spriteManager)
	{
		if (spriteManager == null)
			return;
		if (iconsLoaded)
		{
			return;
		}

		spriteManager.getSpriteAsync(SpriteID.SKILL_HITPOINTS, 0, img ->
		{
			iconHitpoints.set(scaleIcon(img));
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_PRAYER, 0, img ->
		{
			iconPrayer.set(scaleIcon(img));
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_ATTACK, 0, img ->
		{
			iconAttack.set(scaleIcon(img));
		});

		spriteManager.getSpriteAsync(SpriteID.SKILL_AGILITY, 0, img ->
		{
			iconAgility.set(scaleIcon(img));
		});

		CombatIcons.load(spriteManager);
		iconsLoaded = true;
	}

	private static ImageIcon scaleIcon(BufferedImage img)
	{
		if (img == null)
		{
			return null;
		}
		Image scaled = img.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH);
		return new ImageIcon(scaled);
	}

	/**
	 * Create a panel displaying a single tick's data for the live view.
	 *
	 * @param tick         The tick data to display
	 * @param rowIndex     The row index for alternating colors
	 * @param spriteManager The sprite manager for loading icons
	 * @return A JPanel containing the tick display
	 */
	public static JPanel createTickPanel(TickData tick, int rowIndex, SpriteManager spriteManager)
	{
		// Ensure sprites are loaded
		loadSprites(spriteManager);

		JPanel panel = new JPanel(new GridBagLayout());

		// Alternating row colors
		Color bgColor = (rowIndex % 2 == 0) ? ROW_COLOR_1 : ROW_COLOR_2;
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

		// Header: tick number, prayer, and HP
		String tickLabel = "#" + tick.getTickNumber();
		String hpText = com.tickreplaylogger.util.DisplayFormat.resourceText(tick, true);
		String prayerText = com.tickreplaylogger.util.DisplayFormat.resourceText(tick, false);

		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(bgColor);

		JLabel tickLbl = new JLabel(tickLabel);
		tickLbl.setForeground(Color.WHITE);
		tickLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		headerPanel.add(tickLbl, BorderLayout.WEST);

		// Right side panel for prayer and HP
		JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		statsPanel.setBackground(bgColor);

		// Prayer points label
		JLabel prayerLbl = new JLabel(prayerText);
		prayerLbl.setForeground(PRAYER_COLOR);
		prayerLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		if (iconPrayer.get() != null)
		{
			prayerLbl.setIcon(iconPrayer.get());
			prayerLbl.setHorizontalTextPosition(SwingConstants.LEFT);
		}
		statsPanel.add(prayerLbl);

		// HP label
		JLabel hpLbl = new JLabel(hpText);
		hpLbl.setForeground(hpColor);
		hpLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		if (iconHitpoints.get() != null)
		{
			hpLbl.setIcon(iconHitpoints.get());
			hpLbl.setHorizontalTextPosition(SwingConstants.LEFT);
		}
		statsPanel.add(hpLbl);

		headerPanel.add(statsPanel, BorderLayout.EAST);

		panel.add(headerPanel, gbc);
		gbc.gridy++;

		// Content
		boolean hasContent = false;

		// Active prayers - each on its own line
		if (Boolean.FALSE.equals(tick.getPrayerStateKnown()))
		{
			panel.add(createIconLabel("Prayer state unavailable", iconPrayer.get(), PRAYER_COLOR), gbc);
			gbc.gridy++;
			hasContent = true;
		}
		for (String prayer : Boolean.FALSE.equals(tick.getPrayerStateKnown())
			? java.util.Collections.<String>emptyList()
			: tick.getActivePrayers())
		{
			JLabel label = createIconLabel(prayer, iconPrayer.get(), PRAYER_COLOR);
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
				JLabel label = createIconLabel(moveDesc, iconAgility.get(), MOVEMENT_COLOR);
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
				CombatIcons.iconFor(damage), com.tickreplaylogger.util.DisplayFormat.damageDirectionColor(damage),
				com.tickreplaylogger.util.DisplayFormat.damageTooltip(damage));
			panel.add(label, gbc);
			gbc.gridy++;
			hasContent = true;
		}

		// Combat effects (freeze/teleblock/stun/knockback/drain/disarm/poison)
		if (tick.getEffectNotes() != null)
		{
			for (String note : tick.getEffectNotes())
			{
				JLabel label = createIconLabel(note, iconPrayer.get(), EFFECT_COLOR);
				panel.add(label, gbc);
				gbc.gridy++;
				hasContent = true;
			}
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

	/**
	 * Create a compact "N ticks unchanged" separator row, used when the collapse-unchanged
	 * display option is enabled. Shared by the live and recording timeline views.
	 */
	public static JPanel createGapPanel(int count)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 6, 2, 6));

		String text = "⋮  " + count + (count == 1 ? " tick unchanged" : " ticks unchanged");
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
		panel.add(label, BorderLayout.CENTER);
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

	private static ImageIcon getActionIcon(PlayerAction action)
	{
		String desc = action.getDescription().toLowerCase();
		if (desc.startsWith("ate") || desc.startsWith("drank"))
		{
			return iconHitpoints.get();
		}
		else if (desc.contains("prayer") || desc.startsWith("activated") || desc.startsWith("deactivated")
			|| desc.contains("disabled"))
		{
			return iconPrayer.get();
		}
		else if (desc.startsWith("equipped") || desc.startsWith("unequipped"))
		{
			return iconAttack.get();
		}
		else if (desc.startsWith("attacked") || desc.startsWith("special"))
		{
			return iconAttack.get();
		}
		return null;
	}

	private static JLabel createIconLabel(String text, ImageIcon icon, Color color)
	{
		return createIconLabel(text, icon, color, text);
	}

	private static JLabel createIconLabel(String text, ImageIcon icon, Color color, String tooltip)
	{
		JLabel label = new SingleLineLabel(text);
		label.setForeground(color);
		label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
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
}
