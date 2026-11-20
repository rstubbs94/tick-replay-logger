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
					DamageRecord original = event.getData().get("attributionContext") instanceof DamageRecord
						? (DamageRecord) event.getData().get("attributionContext")
						: null;
					if (original == null || !original.sameNativeCallback(projected))
					{
						continue;
					}
					fields.put("attributionContext",
						applyAnnotations(original, projected).toBuilder().sourceResolution(null).build());
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
		DeathRecordingV2.Summary summary = deathSummary(recording, hits, catalogId);
		int dealt = hits == null ? 0
			: hits.stream().filter(hit -> hit != null && "DEALT".equals(hit.getDirection()))
				.mapToInt(DamageRecord::getAmount).sum();
		summary = (summary == null ? DeathRecordingV2.Summary.builder() : summary.toBuilder())
			.totalDamageDealtInBuffer(dealt).build();

		DeathRecordingV2 resolved = recording.toBuilder().summary(summary)
			.events(DeathRecordingV2.Events.builder().damageEvents(hits).effectEvents(before.getEffectEvents())
				.playerActions(before.getPlayerActions()).deaths(before.getDeaths()).observations(observations)
				.observationLinks(before.getObservationLinks()).captureGaps(before.getCaptureGaps()).build())
			.build();
		return resolved.toBuilder().journalTicks(journal(recording.getJournalTicks(), resolved.getEvents())).build();
	}

	/** Callback order selects an inspection record, never a hidden lethal server roll. */
	private static DeathRecordingV2.Summary deathSummary(DeathRecordingV2 recording, List<DamageRecord> hits,
		String catalogId)
	{
		DeathRecordingV2.Summary original = recording.getSummary();
		if (recording.getMeta() == null || recording.getMeta().getDeathTick() == null)
		{
			return original;
		}
		int tick = recording.getMeta().getDeathTick();
		List<DamageRecord> alternatives = new ArrayList<>();
		List<SourceResolution.Candidate> possible = new ArrayList<>(), excluded = new ArrayList<>();
		Set<String> sources = new LinkedHashSet<>();
		boolean unlinked = false;
		if (hits != null)
		{
			for (DamageRecord hit : hits)
			{
				if (hit == null || hit.getImpactTick() != tick || hit.getAmount() <= 0
					|| !"player".equals(hit.getDefenderRef()) || !"RECEIVED".equals(hit.getDirection()))
				{
					continue;
				}
				alternatives.add(hit);
				append(hit.getSourceResolution(), possible, excluded);
				if (hit.getAttackerRef() != null && !hit.getAttackerRef().trim().isEmpty())
				{
					sources.add("actor:" + hit.getAttackerRef());
				}
				else if ("STATUS_HITSPLAT".equals(hit.getAttributionEvidence()))
				{
					sources.add("status:" + hit.getDamageType() + ":" + hit.getAttackerName());
				}
				else
				{
					unlinked = true;
				}
			}
		}
		DamageRecord last = alternatives.isEmpty() ? null : alternatives.get(alternatives.size() - 1);
		boolean unresolved = original != null && original.getUnresolvedImpactObservationCount() > 0
			|| unresolvedAfter(recording.getEvents(), tick, last);
		boolean mixed = alternatives.size() > 1 && (unlinked || sources.size() > 1);
		DamageRecord fatal = unresolved || alternatives.isEmpty() ? null : alternatives.get(alternatives.size() - 1);
		String basis = unresolved ? "UNRESOLVED_IMPACT_IN_DEATH_TICK"
			: fatal == null ? "NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK"
				: mixed ? "MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS" : "SELECTED_RECORD_SOURCE_ONLY";
		return (original == null ? DeathRecordingV2.Summary.builder() : original.toBuilder())
			.killerName(fatal == null || mixed || fatal.getAttackerName() == null ? "Unknown" : fatal.getAttackerName())
			.killerRef(fatal == null || mixed ? null : fatal.getAttackerRef())
			.killerConfidence(
				fatal == null || mixed || fatal.getAttribution() == null ? "UNKNOWN" : fatal.getAttribution())
			.killerBasis(basis).fatalHit(fatal).fatalHitCandidates(alternatives)
			.fatalHitBasis(unresolved || fatal == null ? basis
				: alternatives.size() > 1 ? "LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK"
					: "UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK")
			.sourceResolution(SourceEvidenceResolver.project(possible, excluded, true, catalogId,
				"RECORDED_RESOLVER_V1_FATAL_ALTERNATIVES"))
			.reactable(fatal == null || alternatives.size() > 1 ? null
				: "IMPACT".equals(fatal.getDetermination()) ? Boolean.TRUE
					: "LAUNCH".equals(fatal.getDetermination()) ? Boolean.FALSE : null)
			.build();
	}

	/** A truncated journal or later unfinalized HP-capable callback cannot identify an earlier killer. */
	private static boolean unresolvedAfter(DeathRecordingV2.Events events, int tick, DamageRecord last)
	{
		if (events.getCaptureGaps() != null)
		{
			for (DeathRecordingV2.Events.CaptureGap gap : events.getCaptureGaps())
			{
				if (gap != null && gap.getTick() == tick && gap.getOmittedObservations() > 0
					&& ("rawCallbacks".equals(gap.getChannel()) || "observationLinks".equals(gap.getChannel())))
				{
					return true;
				}
			}
		}
		if (events.getObservations() == null)
		{
			return false;
		}
		for (ObservedEvent event : events.getObservations())
		{
			if (event == null || event.getTick() != tick || !"HITSPLAT".equals(event.getType())
				|| event.getData() == null || !localRecipient(event, events.getObservationLinks()))
			{
				continue;
			}
			Object amount = event.getData().get("amount"), surface = event.getData().get("resourceSurface");
			if (!(amount instanceof Number) || ((Number) amount).intValue() <= 0 || !Arrays.asList("HP", "SKILL_OR_HP",
				"HP_OR_COMBAT_RESOURCE", "UNCLASSIFIED", "SANITY", "STACKS", "CONTEXTUAL_RESOURCE").contains(surface))
			{
				continue;
			}
			if (last == null || last.getObservationSequence() == null
				|| event.getSequence() > last.getObservationSequence())
			{
				return true;
			}
		}
		return false;
	}

	private static boolean localRecipient(ObservedEvent event, List<ObservedEventLink> links)
	{
		if (event.getActorRef() != null)
		{
			return "player".equals(event.getActorRef());
		}
		boolean linked = false;
		if (links != null)
		{
			for (ObservedEventLink link : links)
			{
				if (link == null || link.getObservationSequence() != event.getSequence()
					|| link.getTick() != event.getTick() || link.getResolvedTick() != event.getTick()
					|| !"HITSPLAT".equals(link.getObservationType())
					|| !"SETTLED_LOCAL_REFERENCE_MATCH".equals(link.getEvidence()))
				{
					continue;
				}
				if (!"player".equals(link.getActorRef()))
				{
					return false;
				}
				linked = true;
			}
		}
		Object actor = event.getData().get("actorState");
		return linked || "PLAYER".equals(event.getData().get("receiverKind")) && actor instanceof Map
			&& "LOCAL_PLAYER".equals(((Map<?, ?>) actor).get("actorKind"));
	}

	private static DamageRecord annotations(DamageRecord original, Map<Long, DamageRecord> records)
	{
		DamageRecord projected = original == null ? null : records.get(original.getObservationSequence());
		if (projected == null || !original.sameNativeCallback(projected))
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

	/** The same renderer supplies raw Live rows and completed capture rows from structured events. */
	static List<TickData> journal(List<TickData> states, DeathRecordingV2.Events events)
	{
		if (states == null || events == null)
		{
			return states;
		}
		Map<Integer, JournalRow> rows = new LinkedHashMap<>();
		for (TickData state : states)
		{
			if (state != null)
			{
				rows.put(state.getTickNumber(), new JournalRow(state));
			}
		}
		for (DamageRecord hit : values(events.getDamageEvents()))
		{
			JournalRow row = hit == null ? null : rows.get(hit.getImpactTick());
			if (row == null)
			{
				continue;
			}
			DamageEvent.DamageType type = DamageEvent.DamageType.fromReference(hit.getDamageType());
			DamageEvent event;
			if ("DEALT".equals(hit.getDirection()))
			{
				event = DamageEvent.dealt(hit.getAmount(), type, hit.getDefenderName());
			}
			else if (hit.getDirection() == null || "RECEIVED".equals(hit.getDirection()))
			{
				event = DamageEvent.received(hit.getAmount(), type, label(hit, hit.getSourceResolution()),
					hit.getAttribution(), hit.getDetermination()).toBuilder()
					.sourceResolution(hit.getSourceResolution()).build();
			}
			else
			{
				continue;
			}
			row.hits.add(event.toBuilder().observationSequence(hit.getObservationSequence()).build());
		}
		for (DeathRecordingV2.Events.PlayerActionRecord action : values(events.getPlayerActions()))
		{
			JournalRow row = action == null ? null : rows.get(action.getTick());
			if (row == null || action.getType() == null)
			{
				continue;
			}
			try
			{
				row.actions.add(PlayerAction.builder().type(PlayerAction.ActionType.valueOf(action.getType()))
					.description(action.getDetail()).target(action.getTarget()).itemName(action.getItemName()).build());
			}
			catch (IllegalArgumentException unrecognized)
			{
				// The complete original action remains in the recording's event channel.
			}
		}
		for (EffectEvent effect : values(events.getEffectEvents()))
		{
			JournalRow row = effect == null ? null : rows.get(effect.getStartTick());
			if (row != null && effect.getJournalNote() != null)
			{
				row.notes.add(effect.getJournalNote());
			}
		}
		for (ObservedEvent event : values(events.getObservations()))
		{
			JournalRow row = event == null ? null : rows.get(event.getTick());
			if (row == null || event.getData() == null
				|| !(event.getData().get("attributionContext") instanceof DamageRecord))
			{
				continue;
			}
			DamageRecord impact = (DamageRecord) event.getData().get("attributionContext");
			if (!"RESOURCE_IMPACT".equals(impact.getDirection()) || impact.getImpactTick() != event.getTick()
				|| !Objects.equals(impact.getObservationSequence(), event.getSequence())
				|| !Objects.equals(impact.getObservedCycle(), event.getGameCycle()))
			{
				continue;
			}
			Object rawSurfaces = event.getData().get("possibleOutcomeSurfaces");
			Collection<?> surfaces = rawSurfaces instanceof Collection ? (Collection<?>) rawSurfaces
				: Collections.emptyList();
			String outcome = surfaces.contains("PRAYER") ? "HP/prayer"
				: surfaces.contains("SKILL") ? "HP/stat"
					: surfaces.contains("COMBAT_RESOURCE") ? "HP/resource" : "HP/unknown";
			SourceResolution resolution = event.getData().get("sourceResolution") instanceof SourceResolution
				? (SourceResolution) event.getData().get("sourceResolution")
				: impact.getSourceResolution();
			row.links.put(row.notes.size(), event.getSequence());
			row.notes.add("Impact " + impact.getAmount() + " (" + outcome + ") from " + label(impact, resolution));
		}
		List<TickData> result = new ArrayList<>();
		for (TickData state : states)
		{
			JournalRow row = state == null ? null : rows.get(state.getTickNumber());
			result.add(row == null ? state
				: row.state.toBuilder().damageEvents(row.hits).playerActions(row.actions).effectNotes(row.notes)
					.effectNoteSourceSequences(Collections.unmodifiableMap(row.links)).build());
		}
		return result;
	}

	private static String label(DamageRecord hit, SourceResolution resolution)
	{
		String label = SourceResolutionLabels.label(resolution);
		if (label == null)
		{
			label = SourceResolutionLabels.usableName(hit.getAttackerName());
		}
		return label == null ? "Unknown" : label;
	}

	private static <T> List<T> values(List<T> values)
	{
		return values == null ? Collections.emptyList() : values;
	}

	@lombok.RequiredArgsConstructor
	private static final class JournalRow
	{
		final TickData state;
		final List<DamageEvent> hits = new ArrayList<>();
		final List<PlayerAction> actions = new ArrayList<>();
		final List<String> notes = new ArrayList<>();
		final Map<Integer, Long> links = new LinkedHashMap<>();
	}
}
