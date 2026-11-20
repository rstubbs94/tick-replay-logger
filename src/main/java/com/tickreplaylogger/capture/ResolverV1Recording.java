/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.function.*;
import lombok.*;

/** The same detached original evidence window supplies live, saved and exported results. */
public final class ResolverV1Recording
{
	/**
	 * Membership snapshot of already detached capture DTOs. Construction performs no JSON or client work.
	 * DTO/map contents must be frozen by the capture owner before handing the window to a worker.
	 */
	@Getter
	public static final class EvidenceWindow
	{
		private final List<WorldSnapshot> ticks;
		private final List<SceneData> scenes;
		private final List<ObservedEvent> observations;
		private final List<DamageRecord> hits;
		private final List<DeathRecordingV2.Events.CaptureGap> captureGaps;
		private final List<ObservedEventLink> observationLinks;
		private final int firstTick, lastTick;
		private final long cutoffSequence, evidenceRevision;

		public EvidenceWindow(List<WorldSnapshot> ticks, List<SceneData> scenes, List<ObservedEvent> observations,
			List<DamageRecord> hits, List<DeathRecordingV2.Events.CaptureGap> captureGaps, int firstTick, int lastTick,
			long cutoffSequence, long evidenceRevision)
		{
			this(ticks, scenes, observations, hits, captureGaps, null, firstTick, lastTick, cutoffSequence,
				evidenceRevision);
		}

		public EvidenceWindow(List<WorldSnapshot> ticks, List<SceneData> scenes, List<ObservedEvent> observations,
			List<DamageRecord> hits, List<DeathRecordingV2.Events.CaptureGap> captureGaps,
			List<ObservedEventLink> observationLinks, int firstTick, int lastTick, long cutoffSequence,
			long evidenceRevision)
		{
			if (firstTick > lastTick || cutoffSequence < 0)
			{
				throw new IllegalArgumentException("Invalid evidence window boundary");
			}
			this.firstTick = firstTick;
			this.lastTick = lastTick;
			this.cutoffSequence = cutoffSequence;
			this.evidenceRevision = evidenceRevision;
			this.ticks = selected(ticks, tick -> tick != null && containsTick(tick.getTick()));
			this.scenes = selected(scenes, scene -> scene != null);
			this.observations = observations == null ? null
				: selected(observations,
					event -> event != null && containsTick(event.getTick()) && event.getSequence() <= cutoffSequence);
			this.hits = selected(hits, this::contains);
			this.captureGaps = selected(captureGaps, gap -> gap != null && containsTick(gap.getTick()));
			this.observationLinks = selected(observationLinks, link -> link != null && containsTick(link.getTick())
				&& link.getResolvedTick() <= lastTick && link.getObservationSequence() <= cutoffSequence);
		}

		private boolean containsTick(int tick)
		{
			return tick >= firstTick && tick <= lastTick;
		}

		public boolean contains(DamageRecord hit)
		{
			return hit != null && containsTick(hit.getImpactTick())
				&& (hit.getObservationSequence() == null || hit.getObservationSequence() <= cutoffSequence);
		}

		private static <T> List<T> selected(List<T> values, Predicate<T> keep)
		{
			List<T> result = new ArrayList<>();
			if (values != null)
			{
				for (T value : values)
				{
					if (keep.test(value))
					{
						result.add(value);
					}
				}
			}
			return Collections.unmodifiableList(result);
		}

		private static EvidenceWindow from(DeathRecordingV2 recording)
		{
			DeathRecordingV2.Events events = recording.getEvents();
			return new EvidenceWindow(recording.getTicks(), recording.getScenes(),
				events == null ? null : events.getObservations(), events == null ? null : events.getDamageEvents(),
				events == null ? null : events.getCaptureGaps(), events == null ? null : events.getObservationLinks(),
				Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0);
		}

	}

	private final ResolverV1 engine;
	private final RawEvidenceWindow evidence;
	private final Map<DamageRecord, DamageRecord> resolved = new IdentityHashMap<>();
	private boolean evaluated;

	public ResolverV1Recording(ResolverV1 engine, Gson gson, DeathRecordingV2 recording)
	{
		if (recording.getNpcStateTable() != null)
		{
			throw new IllegalArgumentException("Attribution requires the original uncompressed capture");
		}
		this.engine = engine;
		evidence = new RawEvidenceWindow(EvidenceWindow.from(recording), gson);
	}

	private static boolean local(DamageRecord hit)
	{
		return "player".equals(hit.getDefenderRef()) && !"DEALT".equals(hit.getDirection())
			&& !"NPC_RECEIVED".equals(hit.getDirection());
	}

	private void evaluate()
	{
		if (evaluated)
		{
			return;
		}
		List<ResolverEvidence.Hit> inputs = new ArrayList<>();
		for (DamageRecord record : evidence.records)
		{
			if (local(record))
			{
				inputs.add(evidence.hit(record));
			}
		}
		List<ResolutionResult> results = engine.evaluateBatch(inputs);
		for (int i = 0; i < inputs.size(); i++)
		{
			ResolverEvidence.Hit input = inputs.get(i);
			resolved.put(input.record(), SourceEvidenceResolver.project(input, results.get(i), engine.id()));
		}
		evaluated = true;
	}

	public DeathRecordingV2 enrich(DeathRecordingV2 recording)
	{
		if (engine.isAvailable())
		{
			evaluate();
		}
		Map<Long, DamageRecord> bySequence = new LinkedHashMap<>();
		Set<Long> duplicates = new HashSet<>();
		for (DamageRecord hit : resolved.values())
		{
			Long sequence = hit.getObservationSequence();
			if (sequence == null || duplicates.contains(sequence))
			{
				continue;
			}
			if (bySequence.putIfAbsent(sequence, hit) != null)
			{
				bySequence.remove(sequence);
				duplicates.add(sequence);
			}
		}
		return RecordingProjection.apply(recording, bySequence, engine.id());
	}

	public static DeathRecordingV2 resolve(DeathRecordingV2 recording, Gson gson)
	{
		return recording == null || recording.getEvents() == null ? recording
			: new ResolverV1Recording(ResolverV1.load(gson), gson, recording).enrich(recording);
	}

}
