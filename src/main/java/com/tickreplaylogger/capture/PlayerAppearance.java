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

import com.tickreplaylogger.model.world.PlayerAppearanceState;
import java.util.Arrays;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;

/** Decodes appearance with the API; raw kit/item-offset codes are never mislabeled as item IDs. */
public final class PlayerAppearance
{
	private PlayerAppearance()
	{
	}

	private static final KitType[] VISIBLE =
	{ KitType.HEAD, KitType.CAPE, KitType.AMULET, KitType.WEAPON, KitType.TORSO, KitType.SHIELD, KitType.LEGS,
		KitType.HANDS, KitType.BOOTS };

	public static PlayerAppearanceState capture(Player player)
	{
		PlayerAppearanceState.PlayerAppearanceStateBuilder result = PlayerAppearanceState.builder()
			.evidence("PLAYER_COMPOSITION_UNAVAILABLE");
		PlayerComposition composition;
		try
		{
			composition = player == null ? null : player.getPlayerComposition();
		}
		catch (RuntimeException unavailable)
		{
			return result.build();
		}
		if (composition == null)
		{
			return result.build();
		}
		result.itemOffset(PlayerComposition.ITEM_OFFSET).kitOffset(PlayerComposition.KIT_OFFSET);
		int[] encoded = null;
		boolean partial = false;
		try
		{
			int[] ids = composition.getEquipmentIds();
			if (ids != null)
			{
				encoded = ids.clone();
			}
		}
		catch (RuntimeException unavailable)
		{
			partial = true;
		}
		result.encodedEquipmentIds(encoded);
		try
		{
			int[] colors = composition.getColors();
			result.bodyColors(colors == null ? null : colors.clone());
		}
		catch (RuntimeException unavailable)
		{
			partial = true;
		}
		Integer transform = null;
		try
		{
			transform = composition.getTransformedNpcId();
			result.npcTransformId(transform);
		}
		catch (RuntimeException unavailable)
		{
			partial = true;
		}
		boolean[] known = new boolean[14];
		result.equipmentSlotKnown(known);
		if (transform == null)
		{
			return result.evidence("PLAYER_COMPOSITION_TRANSFORM_UNAVAILABLE").build();
		}
		if (transform >= 0)
		{
			return result.evidence("PLAYER_COMPOSITION_NPC_TRANSFORM").build();
		}
		if (encoded == null)
		{
			return result.build();
		}
		int[] items = new int[14];
		Arrays.fill(items, -1);
		for (KitType slot : VISIBLE)
		{
			int index = slot.getIndex();
			if (index < 0 || index >= encoded.length || index >= items.length)
			{
				partial = true;
				continue;
			}
			try
			{
				int item = composition.getEquipmentId(slot);
				if (encoded[index] >= PlayerComposition.ITEM_OFFSET && item < 0)
				{
					partial = true;
					continue;
				}
				items[index] = item >= 0 ? item : -1;
				known[index] = true;
			}
			catch (RuntimeException unavailable)
			{
				partial = true;
			}
		}
		return result.equipmentItemIds(items)
			.evidence(partial ? "PLAYER_COMPOSITION_PARTIAL" : "PLAYER_COMPOSITION_VISIBLE_SLOTS").build();
	}
}
