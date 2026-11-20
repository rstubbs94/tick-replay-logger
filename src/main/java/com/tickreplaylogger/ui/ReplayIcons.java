/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.ui;

import com.tickreplaylogger.model.PlayerAction;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;

/** The same recorded-stat and action icons are shared by every replay view. */
@Getter
@Accessors(fluent = true)
public final class ReplayIcons
{
	private static final ReplayIcons INSTANCE = new ReplayIcons();
	private static boolean requested;
	private volatile ImageIcon hitpoints, prayer, attack, agility, skull;

	private ReplayIcons()
	{
	}

	public static synchronized ReplayIcons load(SpriteManager manager)
	{
		if (!requested && manager != null)
		{
			manager.getSpriteAsync(SpriteID.SKILL_HITPOINTS, 0, image -> INSTANCE.hitpoints = scale(image));
			manager.getSpriteAsync(SpriteID.SKILL_PRAYER, 0, image -> INSTANCE.prayer = scale(image));
			manager.getSpriteAsync(SpriteID.SKILL_ATTACK, 0, image -> INSTANCE.attack = scale(image));
			manager.getSpriteAsync(SpriteID.SKILL_AGILITY, 0, image -> INSTANCE.agility = scale(image));
			manager.getSpriteAsync(SpriteID.TAB_COMBAT, 0, image -> INSTANCE.skull = scale(image));
			CombatIcons.load(manager);
			requested = true;
		}
		return INSTANCE;
	}

	public ImageIcon action(PlayerAction action)
	{
		String desc = action.getDescription().toLowerCase();
		if (desc.startsWith("ate") || desc.startsWith("drank"))
		{
			return hitpoints();
		}
		else if (desc.contains("prayer") || desc.startsWith("activated") || desc.startsWith("deactivated")
			|| desc.contains("disabled"))
		{
			return prayer();
		}
		else if (desc.startsWith("equipped") || desc.startsWith("unequipped"))
		{
			return attack();
		}
		else if (desc.startsWith("attacked") || desc.startsWith("special"))
		{
			return attack();
		}
		return null;
	}

	private static ImageIcon scale(BufferedImage image)
	{
		return image == null ? null : new ImageIcon(image.getScaledInstance(18, 18, Image.SCALE_SMOOTH));
	}
}
