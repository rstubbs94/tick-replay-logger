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
import java.lang.reflect.Proxy;
import net.runelite.api.*;
import net.runelite.api.kit.KitType;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerAppearanceTest
{
	private Player player(int[] encoded, int[] colors, int transform, boolean failWeapon)
	{
		PlayerComposition composition = (PlayerComposition) Proxy
			.newProxyInstance(PlayerComposition.class.getClassLoader(), new Class<?>[]
			{ PlayerComposition.class }, (p, m, args) ->
			{
				switch (m.getName())
				{
				case "getEquipmentIds":
					return encoded;
				case "getColors":
					return colors;
				case "getTransformedNpcId":
					return transform;
				case "getEquipmentId":
					if (args[0] == KitType.WEAPON)
					{
						if (failWeapon)
							throw new IllegalStateException("unavailable");
						return 4151;
					}
					return -1;
				default:
					return null;
				}
			});
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]
		{ Player.class }, (p, m, args) -> m.getName().equals("getPlayerComposition") ? composition : null);
	}

	@Test
	public void itemIdsAreDecodedWithoutConfusingBodyKitsAndHiddenEquipmentSlots()
	{
		int[] encoded = new int[12], colors =
		{ 1, 2, 3, 4, 5 };
		encoded[3] = PlayerComposition.ITEM_OFFSET + 4151;
		encoded[0] = PlayerComposition.KIT_OFFSET + 7;
		PlayerAppearanceState state = PlayerAppearance.capture(player(encoded, colors, -1, false));
		assertEquals(4151, state.getEquipmentItemIds()[3]);
		assertEquals(-1, state.getEquipmentItemIds()[0]);
		assertTrue(state.getEquipmentSlotKnown()[3]);
		assertFalse(state.getEquipmentSlotKnown()[6]);
		assertFalse(state.getEquipmentSlotKnown()[12]);
		assertFalse(state.getEquipmentSlotKnown()[13]);
		assertEquals("PLAYER_COMPOSITION_VISIBLE_SLOTS", state.getEvidence());
		encoded[3] = 0;
		colors[0] = 9;
		assertEquals(PlayerComposition.ITEM_OFFSET + 4151, state.getEncodedEquipmentIds()[3]);
		assertEquals(1, state.getBodyColors()[0]);
	}

	@Test
	public void npcTransformsDoNotPresentCachedAppearanceGearAsObservedEquipment()
	{
		int[] encoded = new int[12];
		encoded[3] = PlayerComposition.ITEM_OFFSET + 4151;
		PlayerAppearanceState state = PlayerAppearance.capture(player(encoded, null, 2042, false));
		assertNull(state.getEquipmentItemIds());
		assertFalse(state.getEquipmentSlotKnown()[3]);
		assertNotNull(state.getEncodedEquipmentIds());
		assertEquals(Integer.valueOf(2042), state.getNpcTransformId());
	}

	@Test
	public void unavailableSlotsRetainRawEvidenceAndDoNotClaimAnEmptyWeapon()
	{
		int[] encoded = new int[12];
		encoded[3] = PlayerComposition.ITEM_OFFSET + 4151;
		PlayerAppearanceState state = PlayerAppearance.capture(player(encoded, null, -1, true));
		assertEquals("PLAYER_COMPOSITION_PARTIAL", state.getEvidence());
		assertFalse(state.getEquipmentSlotKnown()[3]);
		assertNotNull(state.getEncodedEquipmentIds());
	}
}
