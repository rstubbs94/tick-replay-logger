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
package com.tickreplaylogger.util;

import com.tickreplaylogger.model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.ImageIcon;

/**
 * Shared presentation helpers for the UI: turns the raw, coded names we capture (gameval constants
 * like {@code MAIDEN_LINGERING_BLOOD} or {@code araxxor_cleave}) into friendly labels, and maps a
 * damage type to a consistent colour. The raw values stay untouched in the recording/export; this is
 * display only.
 */
public final class DisplayFormat
{
	private DisplayFormat()
	{
	}

	public static String resourceText(TickData tick, boolean hp)
	{
		String current = hp ? "currentHp" : "currentPrayer", maximum = hp ? "maxHp" : "maxPrayer";
		return (tick.isStateKnown(current) ? String.valueOf(hp ? tick.getCurrentHp() : tick.getCurrentPrayer()) : "?")
			+ "/" + (tick.isStateKnown(maximum) ? String.valueOf(hp ? tick.getMaxHp() : tick.getMaxPrayer()) : "?");
	}

	// Damage-type palette (readable on RuneLite's dark panel).
	public static final Color MELEE = new Color(255, 86, 86); // red
	public static final Color RANGED = new Color(94, 196, 92); // green
	public static final Color MAGIC = new Color(94, 148, 255); // blue
	public static final Color POISON = new Color(120, 200, 70); // light green
	public static final Color VENOM = new Color(40, 135, 40); // dark green
	public static final Color BURN = new Color(235, 155, 65);
	public static final Color BLEED = new Color(215, 75, 100);
	public static final Color TYPELESS = new Color(235, 235, 235); // near-white
	public static final Color UNKNOWN = new Color(170, 170, 170); // neutral grey

	/** Colour for a damage type. */
	public static Color colorFor(DamageEvent.DamageType type)
	{
		if (type == null)
		{
			return UNKNOWN;
		}
		switch (type)
		{
		case MELEE:
			return MELEE;
		case RANGED:
			return RANGED;
		case MAGIC:
			return MAGIC;
		case POISON:
			return POISON;
		case VENOM:
			return VENOM;
		case BURN:
			return BURN;
		case BLEED:
			return BLEED;
		case TYPELESS:
			return TYPELESS;
		default:
			return UNKNOWN;
		}
	}

	/**
	 * Friendly label for a captured source/attack/hazard name. Coded names (ALL_CAPS or containing
	 * underscores) become sentence case with spaces — {@code MAIDEN_LINGERING_BLOOD} →
	 * "Maiden lingering blood", {@code araxxor_cleave} → "Araxxor cleave", {@code BLOODSPLATTER2} →
	 * "Bloodsplatter 2". Names that are already human ({@code "Corrupted Hunllef"},
	 * {@code "Environmental"}) are returned unchanged.
	 */
	public static String humanize(String name)
	{
		if (name == null || name.isEmpty())
		{
			return name;
		}
		boolean coded = name.indexOf('_') >= 0 || name.equals(name.toUpperCase());
		if (!coded)
		{
			return name;
		}
		String t = name.replace('_', ' ').trim().toLowerCase();
		// Put a space between a letter and an adjoining digit: "bloodsplatter2" -> "bloodsplatter 2".
		t = t.replaceAll("([a-z])([0-9])", "$1 $2");
		if (t.isEmpty())
		{
			return name;
		}
		return Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	// Direction colours (OSRS-native): red = damage you took, green = damage you dealt.
	public static final Color RECEIVED = new Color(255, 96, 96);
	public static final Color DEALT = new Color(120, 230, 120);

	/**
	 * One plain-text damage row. Direction is carried by Taken/Dealt and colour; the icon shows
	 * the damage type. Swing ellipsizes long text and the tooltip retains the full detail.
	 */
	public static String damageLineText(DamageEvent d)
	{
		boolean dealt = d.getDirection() == DamageEvent.DamageDirection.DEALT;
		String direction = dealt ? "Dealt " : "Taken ";
		return direction + d.getAmount() + " " + damageSourceName(d);
	}

	public static String damageSourceName(DamageEvent d)
	{
		String resolved = d.getDirection() == DamageEvent.DamageDirection.RECEIVED
			? SourceResolutionLabels.label(d.getSourceResolution())
			: null;
		if (resolved != null)
		{
			return resolved;
		}
		String source = d.getSource();
		return source == null || source.trim().isEmpty() || "Unknown".equalsIgnoreCase(source.trim())
			|| "Unknown source".equalsIgnoreCase(source.trim()) || "Unknown target".equalsIgnoreCase(source.trim())
				? "Unknown"
				: humanize(source);
	}

	public static String fatalSourceName(com.tickreplaylogger.model.TickRecording recording)
	{
		String resolved = SourceResolutionLabels.label(recording.getFatalSourceResolution());
		return resolved == null ? humanize(recording.getKillerName()) : resolved;
	}

	public static boolean sourceIsCorrelated(DamageEvent d)
	{
		if (d.getDirection() != DamageEvent.DamageDirection.RECEIVED || "Unknown".equals(damageSourceName(d)))
		{
			return false;
		}
		if (d.getSourceResolution() != null)
		{
			return true;
		}
		DamageEvent.DamageType type = d.getDamageType();
		boolean status = type == DamageEvent.DamageType.POISON || type == DamageEvent.DamageType.VENOM
			|| "Bleed".equals(d.getSource()) || "Burn".equals(d.getSource());
		return !"CONFIRMED".equals(d.getConfidence()) && !(status && "ENVIRONMENTAL".equals(d.getConfidence()));
	}

	public static Color damageDirectionColor(DamageEvent d)
	{
		return d.getDirection() == DamageEvent.DamageDirection.DEALT ? DEALT : RECEIVED;
	}

	/** Compatibility for older journal labels; newly captured events use the bundled rule. */
	public static DamageEvent.DamageType displayDamageType(DamageEvent d)
	{
		return d.getDamageType() == null ? DamageEvent.DamageType.UNKNOWN : d.getDamageType();
	}

	// Cache of generated type dot-icons so we do not redraw them every row.
	private static final Map<DamageEvent.DamageType, ImageIcon> DOT_CACHE = new EnumMap<>(DamageEvent.DamageType.class);

	/**
	 * A small round colour swatch for a damage type, used as a row icon when we have no skill sprite for
	 * it (poison/venom/typeless/unknown). Colour follows {@link #colorFor}. Cached per type.
	 */
	public static synchronized ImageIcon dotIcon(DamageEvent.DamageType type)
	{
		DamageEvent.DamageType key = type == null ? DamageEvent.DamageType.UNKNOWN : type;
		ImageIcon cached = DOT_CACHE.get(key);
		if (cached != null)
		{
			return cached;
		}
		int size = 14;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(colorFor(key));
		g.fillOval(2, 2, size - 4, size - 4);
		g.dispose();
		ImageIcon icon = new ImageIcon(img);
		DOT_CACHE.put(key, icon);
		return icon;
	}

	/** Full, plain-text detail for a damage event — used as the tooltip behind {@link #damageLineText}. */
	public static String damageTooltip(DamageEvent d)
	{
		DamageEvent.DamageType type = displayDamageType(d);
		StringBuilder sb = new StringBuilder();
		sb.append(d.getDirection() == DamageEvent.DamageDirection.DEALT ? "Dealt " : "Received ").append(d.getAmount())
			.append(" damage");
		if (type != DamageEvent.DamageType.UNKNOWN)
		{
			sb.append(" (").append(type.name().toLowerCase()).append(")");
		}
		sb.append(d.getDirection() == DamageEvent.DamageDirection.DEALT ? " to "
			: sourceIsCorrelated(d) ? "; possible source: " : " from ").append(damageSourceName(d));
		if (type == DamageEvent.DamageType.TYPELESS)
		{
			sb.append(". Recorded damage type: typeless.");
		}
		else if (d.getDirection() == DamageEvent.DamageDirection.RECEIVED)
		{
			if (sourceIsCorrelated(d))
			{
				sb.append(". Timing describes the possible attack; this source is not confirmed");
			}
			sb.append(". ").append(timingExplanation(d.getDetermination()));
		}
		if (type == DamageEvent.DamageType.UNKNOWN)
		{
			sb.append(" Attack type was not recorded.");
		}
		return sb.toString();
	}

	public static String timingLabel(String determination)
	{
		return "IMPACT".equals(determination) ? "Hit tick"
			: "LAUNCH".equals(determination) ? "Attack tick" : "Timing unknown";
	}

	public static String timingExplanation(String determination)
	{
		if ("IMPACT".equals(determination))
		{
			return "Recorded timing annotation: hit tick (impact).";
		}
		if ("LAUNCH".equals(determination))
		{
			return "Recorded timing annotation: attack tick (launch).";
		}
		return "No damage timing annotation was established.";
	}

	/** Compact attack text; the original target/weapon/description remain in the recording. */
	public static String actionText(PlayerAction action)
	{
		String text = action.getDescription();
		if (action.getType() == PlayerAction.ActionType.ATTACK && action.getTarget() != null)
		{
			text = "Attacked " + action.getTarget().replaceFirst("(?i)\\s*\\(level[- ]\\d+\\)\\s*$", "");
		}
		return text;
	}

	/** Initial prayer state, followed only by observed changes against the preceding raw tick. */
	public static List<String> prayerChanges(TickData tick, TickData previous)
	{
		if (Boolean.FALSE.equals(tick.getPrayerStateKnown()))
		{
			return Collections.singletonList("Prayer state unavailable");
		}
		List<String> active = tick.getActivePrayers() == null ? Collections.emptyList() : tick.getActivePrayers();
		if (previous != null && Boolean.FALSE.equals(previous.getPrayerStateKnown()))
		{
			return Collections.singletonList(
				active.isEmpty() ? "Prayer state available; none active" : "Active: " + String.join(", ", active));
		}
		if (previous == null)
		{
			return active.isEmpty() ? Collections.emptyList()
				: Collections.singletonList("Active: " + String.join(", ", active));
		}
		List<String> before = previous.getActivePrayers() == null ? Collections.emptyList()
			: previous.getActivePrayers();
		List<String> changes = new ArrayList<>();
		for (String prayer : active)
		{
			if (!before.contains(prayer))
			{
				changes.add("On: " + prayer);
			}
		}
		for (String prayer : before)
		{
			if (!active.contains(prayer))
			{
				changes.add("Off: " + prayer);
			}
		}
		return changes;
	}

}
