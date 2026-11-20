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

import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.function.*;
import lombok.*;

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
	private final SourceResolution fatalSourceResolution;
	private final List<DamageEvent> fatalHitCandidates;
	private final List<Map<String, Object>> unresolvedImpactObservations;
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

	// Opaque best-effort input from intermediate recordings; current captures use preparedExport.
	private volatile String richExportJson;
	// Current captures retain the completed typed payload. JSON is produced only when saving/copying.
	private volatile DeathRecordingV2 preparedExport;

	public boolean hasRichExport()
	{
		return preparedExport != null || richExportJson != null && !richExportJson.isEmpty();
	}

	public Object exportIdentity()
	{
		return preparedExport == null ? richExportJson : preparedExport;
	}

	/** One original frozen window supplies both paused display and deferred retrospective processing. */
	@Getter
	public static final class CapturedWindow
	{
		private final List<TickData> ticks;
		private final Supplier<DeathRecordingV2> completion;

		public CapturedWindow(List<TickData> ticks, Supplier<DeathRecordingV2> completion)
		{
			this.ticks = Collections.unmodifiableList(new ArrayList<>(ticks));
			this.completion = Objects.requireNonNull(completion);
		}
	}

	public static TickRecording fromPrepared(DeathRecordingV2 completed, long timestamp, String location, boolean death)
	{
		List<TickData> ticks = completed.getJournalTicks() == null ? Collections.emptyList()
			: completed.getJournalTicks();
		DeathRecordingV2.Summary summary = completed.getSummary();
		List<DamageEvent> fatal = new ArrayList<>();
		if (summary != null && summary.getFatalHitCandidates() != null)
		{
			for (DamageRecord hit : summary.getFatalHitCandidates())
			{
				fatal.add(DamageEvent.builder().direction(DamageEvent.DamageDirection.RECEIVED).amount(hit.getAmount())
					.damageType(DamageEvent.DamageType.fromReference(hit.getDamageType())).source(hit.getAttackerName())
					.confidence(hit.getAttribution()).determination(hit.getDetermination())
					.observationSequence(hit.getObservationSequence()).sourceResolution(hit.getSourceResolution())
					.build());
			}
		}
		TickData last = ticks.isEmpty() ? null : ticks.get(ticks.size() - 1);
		return builder().timestamp(timestamp).location(location).preparedExport(completed).ticks(ticks)
			.eventsBeforeSnapshot(true).finalHp(death || last == null ? 0 : last.getCurrentHp())
			.finalHpKnown(death || last != null && last.isStateKnown("currentHp"))
			.totalDamageReceived(calculateTotalDamage(ticks))
			.killerName(summary == null || summary.getKillerName() == null ? death ? "Unknown" : "Live Snapshot"
				: summary.getKillerName())
			.killerConfidence(summary == null ? null : summary.getKillerConfidence())
			.killerBasis(summary == null ? null : summary.getKillerBasis())
			.fatalHitBasis(summary == null ? null : summary.getFatalHitBasis())
			.fatalSourceResolution(summary == null ? null : summary.getSourceResolution()).fatalHitCandidates(fatal)
			.unresolvedImpactObservations(summary == null ? null : summary.getUnresolvedImpactObservations())
			.unresolvedImpactObservationCount(summary == null ? 0 : summary.getUnresolvedImpactObservationCount())
			.build();
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
