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
package com.tickreplaylogger.model;

import lombok.*;

/**
 * Represents a player action that occurred during a tick.
 * This includes eating, drinking, prayer switches, gear switches, and attacks.
 */
@Data
@Builder
public class PlayerAction
{
	private final ActionType type;
	private final String description;
	private final String target; // For attacks: NPC name. For gear: item name. For prayers: prayer name.
	private final String itemName; // For eat/drink/equip actions

	public enum ActionType
	{
		EAT_FOOD, DRINK_POTION, PRAYER_ACTIVATED, PRAYER_DEACTIVATED, PRAYER_DRAINED, // Prayer disabled by running out of points or NPC attack
		GEAR_EQUIPPED, GEAR_UNEQUIPPED, ATTACK, PICKPOCKET, SPECIAL_ATTACK
	}

	/**
	 * Create a food eating action
	 */
	public static PlayerAction ate(String foodName)
	{
		return PlayerAction.builder().type(ActionType.EAT_FOOD).itemName(foodName).description("Ate " + foodName)
			.build();
	}

	/**
	 * Create a potion drinking action
	 */
	public static PlayerAction drank(String potionName)
	{
		return PlayerAction.builder().type(ActionType.DRINK_POTION).itemName(potionName)
			.description("Drank " + potionName).build();
	}

	/**
	 * Create a prayer activation action
	 */
	public static PlayerAction prayerOn(String prayerName)
	{
		return PlayerAction.builder().type(ActionType.PRAYER_ACTIVATED).target(prayerName)
			.description("Activated " + prayerName).build();
	}

	/**
	 * Create a prayer deactivation action
	 */
	public static PlayerAction prayerOff(String prayerName)
	{
		return PlayerAction.builder().type(ActionType.PRAYER_DEACTIVATED).target(prayerName)
			.description("Deactivated " + prayerName).build();
	}

	/**
	 * Create a prayer drained/disabled action (e.g., ran out of prayer points, Vorkath purple)
	 */
	public static PlayerAction prayerDrained(String prayerName, String reason)
	{
		String desc = reason != null && !reason.isEmpty() ? prayerName + " disabled (" + reason + ")"
			: prayerName + " disabled";
		return PlayerAction.builder().type(ActionType.PRAYER_DRAINED).target(prayerName).description(desc).build();
	}

	/**
	 * Create a gear equip action
	 */
	public static PlayerAction equipped(String itemName)
	{
		return PlayerAction.builder().type(ActionType.GEAR_EQUIPPED).itemName(itemName)
			.description("Equipped " + itemName).build();
	}

	/**
	 * Create a gear unequip action
	 */
	public static PlayerAction unequipped(String itemName)
	{
		return PlayerAction.builder().type(ActionType.GEAR_UNEQUIPPED).itemName(itemName)
			.description("Unequipped " + itemName).build();
	}

	/**
	 * Create an attack action
	 */
	public static PlayerAction attacked(String targetName, String weaponName)
	{
		return PlayerAction.builder().type(ActionType.ATTACK).target(targetName).itemName(weaponName)
			.description("Attacked " + targetName + " (" + weaponName + ")").build();
	}

}
