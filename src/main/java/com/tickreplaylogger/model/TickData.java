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

import lombok.Builder;
import lombok.Data;
import java.util.*;
import java.util.List;

/**
 * Represents all data captured for a single game tick.
 * This is the fundamental unit of recording - everything is organized by tick.
 */
@Data
@Builder(toBuilder = true)
public class TickData
{
	// Tick identification
	private final int tickNumber;
	private final long timestamp;
	private String observedGameState;
	private Integer apiTickCount; // Sampled before Hooks increments it after GameTick delivery.
	private Integer gameCycle; // Client-cycle clock, independent of replay tickNumber.

	// Player state snapshot at end of tick
	private int currentHp;
	private int maxHp;
	private int currentPrayer;
	private int maxPrayer;
	// Primitive resource placeholders are not observations when their field is listed here.
	@Builder.Default
	private List<String> unavailableStateFields = new ArrayList<>();
	@Builder.Default
	private List<String> captureIssues = new ArrayList<>();

	public boolean isStateKnown(String field)
	{
		return unavailableStateFields == null || !unavailableStateFields.contains(field);
	}

	// Actions that occurred during this tick
	@Builder.Default
	private List<PlayerAction> playerActions = new ArrayList<>();

	// Damage events during this tick
	@Builder.Default
	private List<DamageEvent> damageEvents = new ArrayList<>();

	// Effect, state and graphic observations; a reference does not prove an applied effect.
	// Mirrors rich EffectEvent records for the journal view.
	@Builder.Default
	private List<String> effectNotes = new ArrayList<>();
	// Optional exact linkage for journal mirrors; the note index is not a callback identity.
	@Builder.Default
	private Map<Integer, Long> effectNoteSourceSequences = new LinkedHashMap<>();

	// Movement data for this tick
	private MovementData movement;

	// Active prayers at the end of this tick
	@Builder.Default
	private List<String> activePrayers = new ArrayList<>();
	private Boolean prayerStateKnown; // false: the read failed; an empty list does not mean no active prayers

	// Telemetry: milliseconds between game-tick callbacks reaching the client (normally ~600ms)
	private long tickDurationMs;

	// Telemetry: network ping in milliseconds (-1 if unavailable)
	private int pingMs;

	// Telemetry: failed recent ping attempts, 0-100 percent (legacy JSON field name)
	private int packetLossPercent;

	public void addPlayerAction(PlayerAction action)
	{
		if (playerActions == null)
		{
			playerActions = new ArrayList<>();
		}
		playerActions.add(action);
	}

	public void addDamageEvent(DamageEvent event)
	{
		if (damageEvents == null)
		{
			damageEvents = new ArrayList<>();
		}
		damageEvents.add(event);
	}

	public void addEffectNote(String note)
	{
		if (effectNotes == null)
		{
			effectNotes = new ArrayList<>();
		}
		effectNotes.add(note);
	}

	public void addEffectNote(String note, Long observationSequence)
	{
		int index = effectNotes == null ? 0 : effectNotes.size();
		addEffectNote(note);
		if (observationSequence == null || observationSequence <= 0 || index > 4095
			|| effectNoteSourceSequences != null && effectNoteSourceSequences.size() >= 256)
		{
			return;
		}
		if (!(effectNoteSourceSequences instanceof LinkedHashMap))
		{
			effectNoteSourceSequences = new LinkedHashMap<>(
				effectNoteSourceSequences == null ? Collections.emptyMap() : effectNoteSourceSequences);
		}
		effectNoteSourceSequences.put(index, observationSequence);
	}
}
