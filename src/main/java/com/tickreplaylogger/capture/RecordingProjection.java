/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.util.SourceResolutionLabels;
import java.util.*;

/** Projects completed retrospective results without changing original callback measurements. */
final class RecordingProjection
{
	private RecordingProjection()
	{
	}

	static DeathRecordingV2 apply(DeathRecordingV2 recording, Map<Long, DamageRecord> records, String catalogId)
	{
		if (recording.getEvents() == null)
		{
			return recording;
		}
		DeathRecordingV2.Events before = recording.getEvents();
		List<DamageRecord> hits = before.getDamageEvents();
		if (hits != null)
		{
			for (int index = 0; index < before.getDamageEvents().size(); index++)
			{
				DamageRecord original = before.getDamageEvents().get(index), projected = annotations(original, records);
				if (projected != original)
				{
					if (hits == before.getDamageEvents())
					{
						hits = new ArrayList<>(hits);
					}
					hits.set(index, projected);
				}
			}
		}
		List<ObservedEvent> observations = before.getObservations();
		if (observations != null)
		{
			for (int index = 0; index < before.getObservations().size(); index++)
			{
				ObservedEvent event = before.getObservations().get(index);
				DamageRecord projected = event == null ? null : records.get(event.getSequence());
				if (projected != null && "RESOURCE_IMPACT".equals(projected.getDirection()) && event.getData() != null
					&& event.getData().get("attributionContext") != null && projected.getImpactTick() == event.getTick()
					&& Objects.equals(projected.getObservedCycle(), event.getGameCycle()))
				{
					Map<String, Object> fields = new LinkedHashMap<>(event.getData());
					fields.put("attributionContext", projected.toBuilder().sourceResolution(null)
						.attributionCandidates(null).observedHazardContacts(null).build());
					fields.put("sourceResolution", projected.getSourceResolution());
					if (!fields.equals(event.getData()))
					{
						if (observations == before.getObservations())
						{
							observations = new ArrayList<>(observations);
						}
						observations.set(index,
							ObservedEvent.builder().tick(event.getTick()).gameCycle(event.getGameCycle())
								.sequence(event.getSequence()).type(event.getType()).actorRef(event.getActorRef())
								.actorName(event.getActorName()).relatedActorRef(event.getRelatedActorRef())
								.sceneKey(event.getSceneKey()).data(Collections.unmodifiableMap(fields)).build());
					}
				}
			}
		}
		DeathRecordingV2.Summary summary = recording.getSummary();
		if (summary != null)
		{
			List<DamageRecord> alternatives = null;
			List<SourceResolution.Candidate> possible = new ArrayList<>(), excluded = new ArrayList<>();
			if (summary.getFatalHitCandidates() != null)
			{
				alternatives = new ArrayList<>();
				for (DamageRecord candidate : summary.getFatalHitCandidates())
				{
					DamageRecord projected = annotations(candidate, records);
					alternatives.add(projected);
					append(projected == null ? null : projected.getSourceResolution(), possible, excluded);
				}
			}
			DamageRecord fatal = summary.getFatalHit(), projected = annotations(fatal, records);
			if (fatal != null && projected != fatal)
			{
				if (alternatives == null || alternatives.isEmpty())
				{
					append(projected.getSourceResolution(), possible, excluded);
				}
				boolean missing = alternatives != null && alternatives.size() > 1
					&& alternatives.stream()
						.anyMatch(hit -> hit == null || hit.getSourceResolution() == null
							|| hit.getSourceResolution().getCandidates() == null
							|| hit.getSourceResolution().getCandidates().isEmpty());
				if (missing || "MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS".equals(summary.getKillerBasis()))
				{
					projected = projected.toBuilder().attackerName("Unknown").attribution("UNKNOWN")
						.attributionEvidence("MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS").build();
				}
				summary = summary.toBuilder().fatalHit(projected).fatalHitCandidates(alternatives)
					.sourceResolution(SourceEvidenceResolver.project(possible, excluded, true, catalogId,
						"RECORDED_RESOLVER_V1_FATAL_ALTERNATIVES"))
					.build();
			}
		}
		DeathRecordingV2 resolved = recording.toBuilder().summary(summary)
			.events(DeathRecordingV2.Events.builder().damageEvents(hits).effectEvents(before.getEffectEvents())
				.playerActions(before.getPlayerActions()).deaths(before.getDeaths()).observations(observations)
				.observationLinks(before.getObservationLinks()).captureGaps(before.getCaptureGaps()).build())
			.build();
		return resolved.toBuilder().journalTicks(projectJournalTicks(recording.getJournalTicks(), resolved)).build();
	}

	private static DamageRecord annotations(DamageRecord original, Map<Long, DamageRecord> records)
	{
		DamageRecord projected = original == null ? null : records.get(original.getObservationSequence());
		if (projected == null || !original.sameOriginalCallback(projected))
		{
			return original;
		}
		DamageRecord result = applyAnnotations(original, projected);
		return result.equals(original) ? original : result;
	}

	private static void append(SourceResolution resolution, List<SourceResolution.Candidate> possible,
		List<SourceResolution.Candidate> excluded)
	{
		if (resolution != null)
		{
			if (resolution.getCandidates() != null)
			{
				possible.addAll(resolution.getCandidates());
			}
			if (resolution.getExcludedCandidates() != null)
			{
				excluded.addAll(resolution.getExcludedCandidates());
			}
		}
	}

	/** Applies only derived display fields; the original callback and impact samples stay on the record. */
	static DamageRecord applyAnnotations(DamageRecord original, DamageRecord projected)
	{
		return original.toBuilder().sourceResolution(projected.getSourceResolution())
			.attackerName(projected.getAttackerName()).attackerRef(projected.getAttackerRef())
			.attackerNpcId(projected.getAttackerNpcId()).attackerCombatLevel(projected.getAttackerCombatLevel())
			.attribution(projected.getAttribution()).attributionEvidence(projected.getAttributionEvidence())
			.damageType(projected.getDamageType()).damageTypeEvidence(projected.getDamageTypeEvidence())
			.damageTypeCandidates(projected.getDamageTypeCandidates())
			.damageReferenceKey(projected.getDamageReferenceKey())
			.damagePropertyEvidence(projected.getDamagePropertyEvidence())
			.damageTypeRuleKey(projected.getDamageTypeRuleKey()).damageTypeCatalogId(projected.getDamageTypeCatalogId())
			.attackStyleName(projected.getAttackStyleName()).prayerEffect(projected.getPrayerEffect())
			.pvp(projected.isPvp()).determination(projected.getDetermination())
			.determinationEvidence(projected.getDeterminationEvidence())
			.determinationRuleKey(projected.getDeterminationRuleKey()).mapRef(projected.getMapRef())
			.launchTick(projected.getLaunchTick()).launchTickEstimated(projected.getLaunchTickEstimated())
			.launchStateEvidence(projected.getLaunchStateEvidence()).overheadAtLaunch(projected.getOverheadAtLaunch())
			.prayersAtLaunch(projected.getPrayersAtLaunch())
			.launchPrayerStateKnown(projected.getLaunchPrayerStateKnown())
			.launchPositionKnown(projected.getLaunchPositionKnown()).launchX(projected.getLaunchX())
			.launchY(projected.getLaunchY()).build();
	}

	/** Journal and rich recording present the same annotations; neither rewrites the received amount. */
	public static DamageEvent projectJournalDamage(DamageEvent original, DamageRecord projected)
	{
		if (original == null || projected == null || original.getDirection() != DamageEvent.DamageDirection.RECEIVED)
		{
			return original;
		}
		String label = SourceResolutionLabels.label(projected.getSourceResolution());
		DamageEvent.DamageType type = DamageEvent.DamageType.fromReference(projected.getDamageType());
		return original.toBuilder().sourceResolution(projected.getSourceResolution())
			.source(label == null ? projected.getAttackerName() : label)
			.damageType(type == DamageEvent.DamageType.UNKNOWN ? original.getDamageType() : type)
			.confidence(projected.getAttribution()).determination(projected.getDetermination()).build();
	}

	public static String projectResourceNote(String original, DamageRecord projected)
	{
		if (original == null || projected == null || !original.startsWith("Impact " + projected.getAmount() + " ("))
		{
			return original;
		}
		int end = original.indexOf(") from ");
		if (end < 0)
		{
			return original;
		}
		String source = SourceResolutionLabels.label(projected.getSourceResolution());
		if (source == null)
		{
			source = SourceResolutionLabels.usableName(projected.getAttackerName());
		}
		return source == null ? original : original.substring(0, end + ") from ".length()) + source;
	}

	/** Apply already resolved annotations to a frozen journal; this does not run inference again. */
	public static List<TickData> projectJournalTicks(List<TickData> ticks, DeathRecordingV2 resolved)
	{
		if (ticks == null || resolved == null || resolved.getEvents() == null)
		{
			return ticks;
		}
		Map<Long, DamageRecord> records = new LinkedHashMap<>();
		Set<Long> ambiguous = new HashSet<>();
		if (resolved.getEvents().getDamageEvents() != null)
		{
			for (DamageRecord hit : resolved.getEvents().getDamageEvents())
			{
				if (hit != null && "player".equals(hit.getDefenderRef())
					&& !Arrays.asList("DEALT", "NPC_RECEIVED").contains(hit.getDirection())
					&& hit.getObservationSequence() != null)
				{
					if (records.putIfAbsent(hit.getObservationSequence(), hit) != null)
					{
						ambiguous.add(hit.getObservationSequence());
					}
				}
			}
		}
		if (resolved.getEvents().getObservations() != null)
		{
			for (ObservedEvent event : resolved.getEvents().getObservations())
			{
				if (event != null && event.getData() != null
					&& event.getData().get("attributionContext") instanceof DamageRecord)
				{
					DamageRecord context = (DamageRecord) event.getData().get("attributionContext");
					if (event.getData().get("sourceResolution") instanceof SourceResolution)
					{
						context = context.toBuilder()
							.sourceResolution((SourceResolution) event.getData().get("sourceResolution")).build();
					}
					if (context.getObservationSequence() != null
						&& records.putIfAbsent(context.getObservationSequence(), context) != null)
					{
						ambiguous.add(context.getObservationSequence());
					}
				}
			}
		}
		for (Long key : ambiguous)
		{
			records.remove(key);
		}
		List<TickData> journal = ticks;
		for (int index = 0; index < ticks.size(); index++)
		{
			TickData original = ticks.get(index), projected = projectJournalTick(original, records);
			if (projected != original)
			{
				if (journal == ticks)
				{
					journal = new ArrayList<>(ticks);
				}
				journal.set(index, projected);
			}
		}
		return journal;
	}

	/** The live and frozen journals share the same exact callback and resource-note guards. */
	static TickData projectJournalTick(TickData tick, Map<Long, DamageRecord> records)
	{
		if (tick == null || records == null || records.isEmpty())
		{
			return tick;
		}
		List<DamageEvent> events = tick.getDamageEvents();
		if (events != null)
		{
			for (int index = 0; index < tick.getDamageEvents().size(); index++)
			{
				DamageEvent original = tick.getDamageEvents().get(index);
				DamageRecord hit = original == null ? null
					: journalRecord(tick, original.getObservationSequence(), records);
				if (hit == null || hit.getSourceResolution() == null || "RESOURCE_IMPACT".equals(hit.getDirection())
					|| original.getDirection() != DamageEvent.DamageDirection.RECEIVED
					|| original.getAmount() != hit.getAmount())
				{
					continue;
				}
				DamageEvent projected = projectJournalDamage(original, hit);
				if (!projected.equals(original))
				{
					if (events == tick.getDamageEvents())
					{
						events = new ArrayList<>(events);
					}
					events.set(index, projected);
				}
			}
		}
		List<String> notes = tick.getEffectNotes();
		if (notes != null && tick.getEffectNoteSourceSequences() != null)
		{
			for (Map.Entry<Integer, Long> link : tick.getEffectNoteSourceSequences().entrySet())
			{
				DamageRecord hit = journalRecord(tick, link.getValue(), records);
				int index = link.getKey();
				if (hit == null || index < 0 || index >= notes.size())
				{
					continue;
				}
				String before = notes.get(index), after = projectResourceNote(before, hit);
				if (!Objects.equals(before, after))
				{
					if (notes == tick.getEffectNotes())
					{
						notes = new ArrayList<>(notes);
					}
					notes.set(index, after);
				}
			}
		}
		return events == tick.getDamageEvents() && notes == tick.getEffectNotes() ? tick
			: tick.toBuilder().damageEvents(events).effectNotes(notes).build();
	}

	private static DamageRecord journalRecord(TickData tick, Long sequence, Map<Long, DamageRecord> records)
	{
		DamageRecord hit = sequence == null ? null : records.get(sequence);
		return hit != null && Objects.equals(sequence, hit.getObservationSequence())
			&& hit.getImpactTick() == tick.getTickNumber() && (hit.getDirection() == null
				|| "RECEIVED".equals(hit.getDirection()) || "RESOURCE_IMPACT".equals(hit.getDirection())) ? hit : null;
	}

}
