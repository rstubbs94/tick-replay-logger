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
package com.tickreplaylogger.model.world;

import java.util.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * The local player's full per-tick state for death reconstruction. Unlike the lightweight
 * {@code TickData} used by the in-plugin journal, this stores absolute + local coordinates and
 * the rich fields needed for local recording analysis. Part of a {@link WorldSnapshot}.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder(toBuilder = true)
public class PlayerState extends ObservedActorState
{

	private final Integer hp;
	private final Integer maxHp;
	private final Integer prayer;
	private final Integer maxPrayer;
	private final Boolean prayerStateKnown; // all flags read and overhead resolved; raw icon availability is independent
	private final Map<String, Boolean> prayerFlagValues; // unread flags are absent, not false
	private final List<String> activePrayers;
	private final String overheadPrayer; // the single active overhead, or null
	private final String overheadIcon; // raw actor icon, independent of prayer varbit flags
	private final Boolean overheadIconKnown;
	private final List<String> reportedActivePrayers;
	private final boolean prayerStateConflict;
	private final String overheadPrayerEvidence;

	// Real (base) vs boosted levels → stat drains/boosts are the delta.
	private final Integer boostedAttack;
	private final Integer boostedStrength;
	private final Integer boostedDefence;
	private final Integer boostedRanged;
	private final Integer boostedMagic;
	private final Map<String, Integer> baseSkillLevels;
	private final Map<String, Integer> boostedSkillLevels;

	private final Integer specEnergy; // 0..100
	private final Integer runEnergy; // 0..100 (legacy whole-percent display)
	private final Integer runEnergyRaw; // Client.getEnergy(), native hundredths of one percent
	private final Integer specEnergyRaw; // SPECIAL_ATTACK_PERCENT/SA_ENERGY varp, native tenths of one percent
	private final Boolean runEnergyKnown;
	private final Boolean specEnergyKnown;

	private final int[] equipmentItemIds; // actual local container, indexed by EquipmentInventorySlot.getSlotIdx()
	private final boolean[] equipmentSlotKnown;
	private final PlayerAppearanceState appearance;
	private final int[] inventoryItemIds;
	private final int[] inventoryQuantities;
	private final boolean[] inventorySlotKnown;
	private final boolean[] inventoryQuantityKnown;
	private final int poisonValue; // raw poison/venom varp, including statuses applied before this window
	private final Boolean poisonValueKnown; // false means the numeric field is an unavailable placeholder

	// Supplied previous world point and current observed world point; these are not movement intent.
	private final Integer fromX;
	private final Integer fromY;
	private final Integer toX;
	private final Integer toY;
}
