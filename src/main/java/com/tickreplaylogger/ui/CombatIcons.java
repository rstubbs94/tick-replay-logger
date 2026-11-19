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
import com.tickreplaylogger.util.DisplayFormat;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;

/**
 * Shared, lazily-loaded combat-style icons so every view (live timeline, playback, recording
 * timeline) shows the same thing: sword/bow/wand for known styles, diamond-T for typeless,
 * question mark for unknown style. Hearts are reserved for HP/healing, never damage-type fallbacks.
 */
public final class CombatIcons
{
	private static final int SIZE = 14;

	private static final AtomicReference<ImageIcon> melee = new AtomicReference<>(bundledIcon("attack"));
	private static final AtomicReference<ImageIcon> ranged = new AtomicReference<>(bundledIcon("ranged"));
	private static final AtomicReference<ImageIcon> magic = new AtomicReference<>(bundledIcon("magic"));
	private static final ImageIcon unknown = unknownIcon();
	private static final ImageIcon typeless = typelessIcon();
	private static final ImageIcon burn = bundledIcon("firemaking");
	private static final ImageIcon bleed = bleedIcon();
	private static volatile boolean requested = false;

	private CombatIcons()
	{
	}

	/** RuneLite ships these resources, so damage rows have icons before async sprites arrive. */
	private static ImageIcon bundledIcon(String skill)
	{
		try (InputStream in = CombatIcons.class.getResourceAsStream("/skill_icons_small/" + skill + ".png"))
		{
			return in == null ? null : scale(ImageIO.read(in));
		}
		catch (IOException e)
		{
			return null;
		}
	}

	private static ImageIcon unknownIcon()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(DisplayFormat.UNKNOWN);
		graphics.drawRoundRect(0, 0, 13, 13, 4, 4);
		graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
		graphics.drawString("?", 3, 11);
		graphics.dispose();
		return new ImageIcon(image);
	}

	private static ImageIcon typelessIcon()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(DisplayFormat.TYPELESS);
		g.drawPolygon(new int[]
		{ 7, 13, 7, 1 }, new int[]
		{ 0, 7, 13, 7 }, 4);
		g.drawLine(4, 4, 10, 4);
		g.drawLine(7, 4, 7, 10);
		g.dispose();
		return new ImageIcon(image);
	}

	private static ImageIcon bleedIcon()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		java.awt.geom.Path2D drop = new java.awt.geom.Path2D.Double();
		drop.moveTo(7, 1);
		drop.curveTo(6, 4, 2, 7, 2, 9);
		drop.curveTo(2, 15, 12, 15, 12, 9);
		drop.curveTo(12, 7, 8, 4, 7, 1);
		g.setColor(DisplayFormat.BLEED);
		g.fill(drop);
		g.dispose();
		return new ImageIcon(image);
	}

	/** Request the skill sprites once; safe to call repeatedly. */
	public static void load(SpriteManager spriteManager)
	{
		if (requested || spriteManager == null)
		{
			return;
		}
		requested = true;
		spriteManager.getSpriteAsync(SpriteID.SKILL_ATTACK, 0, img ->
		{
			if (img != null)
			{
				melee.set(scale(img));
			}
		});
		spriteManager.getSpriteAsync(SpriteID.SKILL_RANGED, 0, img ->
		{
			if (img != null)
			{
				ranged.set(scale(img));
			}
		});
		spriteManager.getSpriteAsync(SpriteID.SKILL_MAGIC, 0, img ->
		{
			if (img != null)
			{
				magic.set(scale(img));
			}
		});
	}

	/** The damage type determines the icon; Taken/Dealt and text colour show direction. */
	public static ImageIcon iconFor(DamageEvent damage)
	{
		DamageEvent.DamageType type = DisplayFormat.displayDamageType(damage);
		if (type == DamageEvent.DamageType.TYPELESS)
		{
			return typeless;
		}
		return iconFor(type);
	}

	/** The icon representing a damage type: a skill sprite for the three styles, else a coloured dot. */
	public static ImageIcon iconFor(DamageEvent.DamageType type)
	{
		if (type == null || type == DamageEvent.DamageType.UNKNOWN)
		{
			return unknown;
		}
		if (type != null)
		{
			switch (type)
			{
			case TYPELESS:
				return typeless;
			case BURN:
				return burn == null ? DisplayFormat.dotIcon(type) : burn;
			case BLEED:
				return bleed;
			case MELEE:
			{
				ImageIcon i = melee.get();
				if (i != null)
				{
					return i;
				}
				break;
			}
			case RANGED:
			{
				ImageIcon i = ranged.get();
				if (i != null)
				{
					return i;
				}
				break;
			}
			case MAGIC:
			{
				ImageIcon i = magic.get();
				if (i != null)
				{
					return i;
				}
				break;
			}
			default:
				break;
			}
		}
		return DisplayFormat.dotIcon(type);
	}

	private static ImageIcon scale(BufferedImage img)
	{
		if (img == null)
		{
			return null;
		}
		return new ImageIcon(img.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH));
	}
}
