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

import java.util.Objects;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Represents a complete tick recording - a snapshot of ticks (e.g., leading up to death or saved manually).
 */
@Data
@Builder(toBuilder = true)
public class TickRecording
{
	// When the recording was created
	private final long timestamp;

	// Context label (e.g., killer name for deaths, or custom label for saved recordings)
	private volatile String killerName;
	private volatile Boolean customLabel; // Explicit rename, including the literal "Unknown"; display context must not replace it.
	private final String killerConfidence;
	private final String killerBasis;
	private final String fatalHitBasis;
	private final com.tickreplaylogger.model.world.SourceResolution fatalSourceResolution;
	private final List<DamageEvent> fatalHitCandidates;
	private final List<java.util.Map<String, Object>> unresolvedImpactObservations;
	private final int unresolvedImpactObservationCount;

	// Where the recording was made (region/location name if available)
	private final String location;

	// The tick data (in chronological order, oldest first)
	private final List<TickData> ticks;

	// New journal events precede their end-of-tick state snapshot. Missing/false in older recordings
	// whose events were appended after that tick's snapshot; preserves their HP-delta display.
	private final boolean eventsBeforeSnapshot;

	// Final HP (0 for deaths, current HP for live snapshots)
	private final int finalHp;
	private final Boolean finalHpKnown; // false: primitive placeholder from an unavailable live-snapshot read

	// Total damage received in the recorded ticks
	private final int totalDamageReceived;

	// The full rich versioned export (DeathRecordingV2) for this death, serialized as JSON. This is the
	// complete payload — scenes/collision, per-tick world snapshots, events, summary — that the "Copy"
	// action emits for local recording analysis. Null for older/plain recordings; copy then falls
	// back to the journal JSON.
	private volatile String richExportJson;

	@lombok.Getter(lombok.AccessLevel.NONE)
	@lombok.Setter(lombok.AccessLevel.NONE)
	@lombok.ToString.Exclude
	private transient volatile PreparedEncounterTitle preparedEncounterTitle;

	/** Immutable display-only cache. Payload matching uses identity, never its large hash/equality. */
	public static final class PreparedEncounterTitle
	{
		private final String payload;
		private final String originalName;
		private final Boolean customLabel;
		private final String catalogId;
		private final String title;

		public PreparedEncounterTitle(String payload, String originalName, Boolean customLabel, String catalogId,
			String title)
		{
			this.payload = payload;
			this.originalName = originalName;
			this.customLabel = customLabel;
			this.catalogId = catalogId;
			this.title = title;
		}

		public boolean matches(TickRecording recording, String catalogId)
		{
			return payload == recording.richExportJson && Objects.equals(originalName, recording.killerName)
				&& Objects.equals(customLabel, recording.customLabel) && Objects.equals(this.catalogId, catalogId);
		}

		public String title()
		{
			return title;
		}
	}

	public PreparedEncounterTitle preparedEncounterTitle()
	{
		return preparedEncounterTitle;
	}

	public void rememberEncounterTitle(PreparedEncounterTitle prepared)
	{
		preparedEncounterTitle = prepared;
	}

	public void setRichExportJson(String payload)
	{
		preparedEncounterTitle = null;
		richExportJson = payload;
	}

	public void setKillerName(String name)
	{
		preparedEncounterTitle = null;
		killerName = name;
	}

	public void setCustomLabel(Boolean custom)
	{
		preparedEncounterTitle = null;
		customLabel = custom;
	}

	/**
	 * Get human-readable time since recording (e.g., "3 minutes ago")
	 */
	public String getTimeSinceRecording()
	{
		long now = System.currentTimeMillis();
		long diff = now - timestamp;

		long seconds = diff / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;

		if (hours > 0)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		else if (minutes > 0)
		{
			return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
		}
		else
		{
			return "Just now";
		}
	}

	/**
	 * Get a summary string for display in the recording list
	 */
	public String getSummary()
	{
		return killerName + " - " + getTimeSinceRecording();
	}

	/**
	 * Calculate total damage received from the ticks
	 */
	public static int calculateTotalDamage(List<TickData> ticks)
	{
		int total = 0;
		for (TickData tick : ticks)
		{
			if (tick.getDamageEvents() != null)
			{
				for (DamageEvent event : tick.getDamageEvents())
				{
					if (event.getDirection() == DamageEvent.DamageDirection.RECEIVED)
					{
						total += event.getAmount();
					}
				}
			}
		}
		return total;
	}
}
