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

import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedStatusState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ProjectileSourceCandidate;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure DTO adapter shared by capture and offline analysis. It reads no client APIs, predicts no
 * future event, and never replaces an observed source-actor field with a proximity hypothesis.
 */
public final class RecordedSourceResolver
{
	private final List<WorldSnapshot> frames = new ArrayList<>();
	private final Map<String, SceneData> scenes = new HashMap<>();
	private final Map<Long, ProjectileSample> firstProjectiles = new LinkedHashMap<>();
	private final Map<Integer, List<WorldSnapshot>> nativeFramesByTick = new HashMap<>();
	private final Map<Long, ActorSourceSample> projectileActorSamples = new HashMap<>();
	private final LegacyDamageMap map;
	private final LegacyCombatCatalog catalog;
	private final ObservedActorMechanismResolver actorMechanisms;
	private final ObservedActorActivityResolver actorActivity;
	private final ObservedNpcContactHintResolver npcContactHints;
	private final ObservedGraphicCueResolver graphicCues;
	private final ObservedSelfDamageTextResolver selfResponses;
	private final LegacyObservedHpCallbackContext hpCallbackContexts;
	private final ObservedProjectileEndpointResolver projectileEndpoints;

	private static final class ProjectileSample
	{
		final ProjectileState projectile;
		final WorldSnapshot frame;

		ProjectileSample(ProjectileState projectile, WorldSnapshot frame)
		{
			this.projectile = projectile;
			this.frame = frame;
		}
	}

	private static final class ActorSourceSample
	{
		final WorldSnapshot frame;
		final NpcState npc;

		ActorSourceSample(WorldSnapshot frame, NpcState npc)
		{
			this.frame = frame;
			this.npc = npc;
		}
	}

	public RecordedSourceResolver(List<WorldSnapshot> history, List<SceneData> sceneData, DamageMap map)
	{
		this(history, sceneData, map, null, null);
	}

	public RecordedSourceResolver(List<WorldSnapshot> history, List<SceneData> sceneData, DamageMap map,
		List<ObservedEvent> observations, List<DeathRecordingV2.Events.CaptureGap> gaps)
	{
		this(history, sceneData, map, observations, gaps, true);
	}

	public RecordedSourceResolver(List<WorldSnapshot> history, List<SceneData> sceneData, DamageMap map,
		List<ObservedEvent> observations, List<DeathRecordingV2.Events.CaptureGap> gaps,
		boolean projectileBatchComplete)
	{
		this(history, sceneData, map, observations, gaps, projectileBatchComplete, true);
	}

	public RecordedSourceResolver(List<WorldSnapshot> history, List<SceneData> sceneData, DamageMap map,
		List<ObservedEvent> observations, List<DeathRecordingV2.Events.CaptureGap> gaps,
		boolean projectileBatchComplete, boolean retainedLifecycleWindowComplete)
	{
		this.map = map == null ? null : LegacyDamageMap.from(map);
		this.catalog = map == null ? null : LegacyCombatCatalog.from(map.catalog());
		if (sceneData != null)
			for (SceneData scene : sceneData)
				if (scene != null)
					scenes.put(scene.getSceneKey(), scene);
		if (history != null)
			collect(history);
		hpCallbackContexts = new LegacyObservedHpCallbackContext(observations);
		actorMechanisms = new ObservedActorMechanismResolver(
			catalog == null ? MechanismRules.load(null) : catalog.mechanismRules(), frames, observations, gaps,
			hpCallbackContexts, retainedLifecycleWindowComplete);
		actorActivity = new ObservedActorActivityResolver(observations, catalog);
		npcContactHints = new ObservedNpcContactHintResolver(catalog == null ? null : catalog.observedNpcContactHints(),
			nativeFramesByTick, catalog);
		graphicCues = new ObservedGraphicCueResolver(catalog == null ? null : catalog.observedGraphicCues(),
			observations, scenes);
		projectileEndpoints = new ObservedProjectileEndpointResolver(
			catalog == null ? new ObservedProjectileEndpointResolver.Rules(null) : catalog.projectileEndpointHints(),
			observations, catalog, projectileBatchComplete && (gaps == null || gaps.isEmpty()),
			this::endpointOriginalSource);
		selfResponses = new ObservedSelfDamageTextResolver(
			catalog == null ? null : catalog.observedSelfDamageTextHints(), observations, hpCallbackContexts);
	}

	private void collect(List<WorldSnapshot> history)
	{
		for (WorldSnapshot frame : history)
		{
			if (frame == null)
				continue;
			frames.add(frame);
			nativeFramesByTick.computeIfAbsent(frame.getTick(), ignored -> new ArrayList<>()).add(frame);
			if (frame.getNativeScene() != null)
				scenes.put(frame.getSceneKey(), frame.getNativeScene());
			if (frame.getProjectiles() != null)
				for (ProjectileState projectile : frame.getProjectiles())
					if (projectile != null && projectile.getObservationId() != null)
						firstProjectiles.putIfAbsent(projectile.getObservationId(),
							new ProjectileSample(projectile, frame));
			if (frame.getAdditionalViews() != null)
				collect(frame.getAdditionalViews());
		}
	}

	public SourceResolution resolve(DamageRecord hit)
	{
		List<LegacyConstraintOracle.Hypothesis> hypotheses = new ArrayList<>();
		WorldSnapshot impact = impactFrame(hit);
		boolean unidentified = impact == null || sourceContextUnavailable(hit, impact);
		List<Map<String, Object>> retained = hit.getAttributionCandidates();
		int witness = 0;
		if (retained != null)
			for (Map<String, Object> raw : retained)
			{
				if (hypotheses.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
				{
					unidentified = true;
					break;
				}
				witness++;
				if (raw == null)
				{
					unidentified = true;
					continue;
				}
				if (Boolean.TRUE.equals(raw.get("sourceSamplingUnavailable")) || sourceHistoryUnestablished(raw)
					|| "TIMING_UNAVAILABLE".equals(raw.get("matchBasis")))
					unidentified = true;
				String kind = text(raw.get("kind"));
				if (Arrays.asList("OBSERVATION_BATCH_WITHOUT_UNIQUE_ATTACK_LINK",
					"PROJECTILE_MAY_HAVE_ALREADY_IMPACTED", "OUTCOME_SURFACE_UNRESOLVED").contains(kind))
				{
					unidentified = true;
					continue;
				}
				if ("INVENTORY_SOURCE_CONTEXT_UNAVAILABLE".equals(kind))
				{
					unidentified = true;
					continue;
				}
				if ("ITEM_POSSESSION_CANDIDATE".equals(kind))
				{
					LegacyConstraintOracle.Hypothesis possession = catalog == null ? null
						: catalog.inventorySourceHints().hypothesis(raw, hit);
					if (possession == null)
						unidentified = true;
					else
						hypotheses.add(possession);
					continue;
				}
				if (kind == null || kind.startsWith("RECENT_ATTACKER") || kind.startsWith("ACTIVE_"))
					continue;
				if (kind.equals("HAZARD_ON_IMPACT_TILE"))
				{
					// The structured callback/settled adapters below retain identities when available.
					// A name-only retained contact must not disappear merely because a rival has an actor ref.
					String name = text(raw.get("name"));
					if (!unknown(name) && !hasHazardWitness(hit, impact, name))
					{
						if (hasUnqualifiedSurfaceWitness(hit, impact, name)
							|| governedSurfaceWitness(hit, impact, name))
						{
							unidentified = true;
							continue;
						}
						hypotheses
							.add(plain(candidate("retained-hazard:" + hit.getObservationSequence() + ":" + witness,
								name, null, null, "RETAINED_SPATIAL_CONTACT; definition and original scope unavailable",
								"POSSIBLE")));
						unidentified = true;
					}
					continue;
				}
				if (kind.contains("PROJECTILE") && number(raw.get("projectileObservationId")) != null)
				{
					Long id = number(raw.get("projectileObservationId"));
					List<Candidate> candidates = projectileCandidates(id, raw, impact);
					ProjectileSample original = firstProjectiles.get(id);
					if (original != null && (projectileHistoryUnestablished(original)
						|| originalProximity(original) == null && originalActorSample(original) == null))
						unidentified = true;
					for (Candidate candidate : candidates)
						hypotheses.add(hypothesis(candidate, projectileNpc(id, candidate), raw, hit, impact, true));
					if (candidates.isEmpty())
						unidentified = true;
					Object latestValue = raw.get("latestSourceObservation");
					if (sourceHistoryUnestablished(raw) && latestValue instanceof Map)
					{
						Map<?, ?> latest = (Map<?, ?>) latestValue;
						String laterRef = text(latest.get("sourceRef"));
						String laterName = identityName(text(latest.get("sourceName")),
							integer(latest.get("sourceNpcId")), null, false);
						if (laterRef != null && laterName != null)
							hypotheses.add(
								plain(candidate("later-projectile-source:" + id + ":" + laterRef, laterName, null, null,
									"LATER_RECORDED_SOURCE_ACTOR; not the original launch observation", "POSSIBLE")
										.toBuilder().actorRef(laterRef).npcId(integer(latest.get("sourceNpcId")))
										.build()));
					}
					continue;
				}
				if (kind.contains("PROJECTILE") && !kind.contains("ANIMATION"))
				{
					String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(text(raw.get("name")));
					if (name == null)
						unidentified = true;
					else
						hypotheses.add(plain(candidate(
							"retained-projectile:" + hit.getObservationSequence() + ":" + witness, name, null, null,
							"RETAINED_NAMED_PROJECTILE_HYPOTHESIS; observation identity and source profile unavailable",
							"POSSIBLE")).toBuilder().causalAssociationOnly(true).build());
					continue;
				}
				if (kind.contains("ANIMATION") || kind.equals("ENGAGED_NPC_CANDIDATE"))
				{
					String ref = text(raw.get("actorRef"));
					NpcState npc = findNpc(impact, ref);
					Candidate candidate = actorCandidate(npc, impact, text(raw.get("name")), ref,
						integer(raw.get("npcId")), kind, "POSSIBLE");
					if (candidate != null)
						hypotheses
							.add(
								hypothesis(
									candidate.toBuilder()
										.key("retained:" + hit.getObservationSequence() + ":" + witness).build(),
									npc, raw, hit, impact, false));
					else
						unidentified = true;
				}
				else if (kind.contains("SELF_DAMAGE") || kind.equals("ITEM_CONSUMPTION_CANDIDATE")
					|| kind.equals("PICKPOCKET_FAILURE_CORRELATION"))
				{
					String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(text(raw.get("name")));
					String source = text(raw.get("sourceRef"));
					if (source != null && source.trim().isEmpty())
						source = null;
					Integer itemId = integer(raw.get("itemId"));
					if (itemId != null && itemId < 0)
						itemId = null;
					String family = source != null ? "input-rule:" + source
						: itemId == null ? null : "item-definition:" + itemId;
					String key = family != null ? family
						: "input-witness:" + hit.getObservationSequence() + ":" + witness;
					if (name == null)
						unidentified = true;
					else
					{
						Candidate input = candidate(key, name, impact, family, kind, "POSSIBLE");
						LegacyConstraintOracle.Hypothesis hypothesis = plain(input).toBuilder()
							.eligibilityExclusions(selfResponses.eligibilityExclusions(hit, impact, input, catalog))
							.build();
						if ("SELF_DAMAGE_INPUT_CANDIDATE".equals(kind))
						{
							// A compatible input is still an input correlation, never proof of execution.
							unidentified = true;
							hypothesis = outcomeCompatibility(hypothesis, hit, "ITEM", itemId, Collections.emptyMap(),
								"SELF_INPUT", raw);
							if (catalog != null)
								hypothesis = catalog.selfInputConstraints().apply(hypothesis, hit, impact, raw,
									(reference, operations) -> qualifiedSelfInput(raw, hit, reference, operations),
									hpCallbackContexts.forHit(hit));
						}
						hypotheses.add(hypothesis);
					}
				}
			}
		addCallbackHazards(hit, impact, hypotheses);
		addSettledHazards(hit, impact, hypotheses);
		if (catalog != null)
		{
			ObservedMechanismResolver.Result mechanisms = ObservedMechanismResolver.resolve(catalog.mechanismRules(),
				hit, impact, impact == null ? null : scenes.get(impact.getSceneKey()), frames, scenes);
			for (ObservedMechanismResolver.Contribution contribution : mechanisms.getContributions())
				hypotheses.add(outcomeCompatibility(contribution.getHypothesis(), hit, contribution.getSourceKind(),
					contribution.getSourceId(),
					Collections.singletonMap("SOURCE_DEFINITION_BOUND", contribution.isDefinitionBound()),
					"CONTACT".equals(contribution.getHypothesis().getMechanism()) ? "ENVIRONMENT_CONTACT"
						: "ENVIRONMENT_AREA_IMPACT"));
			unidentified |= mechanisms.isUnidentifiedPossible();
			ObservedMechanismResolver.Result actorResult = actorMechanisms.resolve(hit);
			for (ObservedMechanismResolver.Contribution contribution : actorResult.getContributions())
				hypotheses.add(outcomeCompatibility(contribution.getHypothesis(), hit, contribution.getSourceKind(),
					contribution.getSourceId(),
					Collections.singletonMap("SOURCE_DEFINITION_BOUND", contribution.isDefinitionBound()),
					contribution.getHypothesis().getMechanism()));
			unidentified |= actorResult.isUnidentifiedPossible();
			ObservedMechanismResolver.Result graphicResult = graphicCues.resolve(hit, catalog);
			for (ObservedMechanismResolver.Contribution contribution : graphicResult.getContributions())
				hypotheses
					.add(outcomeCompatibility(contribution.getHypothesis(), hit, "GRAPHIC", contribution.getSourceId(),
						Collections.singletonMap("SOURCE_DEFINITION_BOUND", true), "ENVIRONMENT_AREA_IMPACT"));
			unidentified |= graphicResult.isUnidentifiedPossible();
		}
		ObservedActorActivityResolver.Result self = selfResponses.resolve(hit, impact, catalog);
		unidentified |= self.isIncomplete();
		for (Candidate candidate : self.getCandidates())
		{
			if (hypotheses.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
			{
				unidentified = true;
				break;
			}
			hypotheses.add(plain(candidate));
		}
		ObservedActorActivityResolver.Result activity = actorActivity.resolve(hit);
		ObservedMechanismResolver.Result bodyContacts = npcContactHints.resolve(hit);
		for (ObservedMechanismResolver.Contribution contribution : bodyContacts.getContributions())
		{
			if (hypotheses.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
			{
				unidentified = true;
				break;
			}
			hypotheses.add(contribution.getHypothesis());
		}
		unidentified |= bodyContacts.isUnidentifiedPossible();
		ObservedMechanismResolver.Result endpoints = projectileEndpoints.resolve(hit);
		for (ObservedMechanismResolver.Contribution contribution : endpoints.getContributions())
		{
			if (hypotheses.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
			{
				unidentified = true;
				break;
			}
			hypotheses.add(contribution.getHypothesis());
		}
		unidentified |= endpoints.isUnidentifiedPossible();
		unidentified |= activity.isIncomplete();
		for (Candidate candidate : activity.getCandidates())
		{
			unidentified = true; // Unclassified activity never closes the possible-source list.
			if (hypotheses.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
				break;
			hypotheses
				.add(activityHypothesis(candidate, activity.getSourceSamples().get(candidate.getKey()), hit, impact));
		}

		String statusDamageType = observedDamageType(hit);
		java.util.Set<String> statusFamilies = new java.util.LinkedHashSet<>();
		if (Arrays.asList("poison", "venom", "burn", "bleed").contains(statusDamageType))
			statusFamilies.add(statusDamageType);
		if (catalog != null && hitsplatObserved(hit))
			statusFamilies.addAll(catalog.possibleStatusFamilies(hit.getHitsplatType()));
		for (String family : statusFamilies)
		{
			// Settled state cannot order a cure/activation relative to an earlier hit callback.
			addStatusHints(family, impact, hypotheses);
			WorldSnapshot previous = previousFrame(impact);
			addStatusHints(family, previous, hypotheses);
			// Known inactive is distinct from a missing read. Either active envelope state is a possibility,
			// never proof of callback ordering or this hit's cause.
			unidentified |= !statusCoverageKnown(family, impact) || !statusCoverageKnown(family, previous);
		}

		if (hypotheses.isEmpty() && !unknown(hit.getAttackerName())
			&& !"STATUS_HITSPLAT".equals(hit.getAttributionEvidence())
			&& (!governedSurfaceWitness(hit, impact, hit.getAttackerName())
				|| findNpc(impact, hit.getAttackerRef()) != null))
		{
			NpcState npc = findNpc(impact, hit.getAttackerRef());
			Candidate candidate = actorCandidate(npc, impact, hit.getAttackerName(), hit.getAttackerRef(),
				hit.getAttackerNpcId(), hit.getAttributionEvidence(), "POSSIBLE");
			if (candidate != null)
				hypotheses.add(hypothesis(candidate, npc, Collections.emptyMap(), hit, impact, false));
		}
		return LegacyConstraintOracle.resolve(hypotheses, hit.getAmount(), unidentified,
			catalog == null ? null : catalog.id());
	}

	private boolean sourceContextUnavailable(DamageRecord hit, WorldSnapshot impact)
	{
		if (impact != null && impact.getCaptureCoverage() != null)
			for (Map.Entry<String, String> channel : impact.getCaptureCoverage().entrySet())
				if (("npcs".equals(channel.getKey()) || "projectiles".equals(channel.getKey())
					|| "graphicsAndMappedHazards".equals(channel.getKey())) && coverageIncomplete(channel.getValue()))
					return true;
		if (impact != null && impact.getHazardCoverage() != null)
		{
			for (String key : Arrays.asList("graphicsStatus", "mappedObjectsStatus"))
				if (impact.getHazardCoverage().containsKey(key)
					&& coverageIncomplete(text(impact.getHazardCoverage().get(key))))
					return true;
			if (Boolean.FALSE.equals(impact.getHazardCoverage().get("objectScanCompleted")))
				return true;
			for (String key : Arrays.asList("graphicsReadFailures", "mappedObjectReadFailures"))
			{
				Long failures = number(impact.getHazardCoverage().get(key));
				if (failures != null && failures > 0)
					return true;
			}
		}
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : hit.getObservedHazardContacts())
				if (contact != null && !Boolean.FALSE.equals(contact.get("damaging"))
					&& !Boolean.TRUE.equals(contact.get("finished")) && contactTimingAllows(contact, hit)
					&& contactScopeNotContradicted(contact, impact))
				{
					if ("UNCLASSIFIED_GRAPHIC_SURFACE".equals(text(contact.get("sourceEligibility")))
						&& !mechanismSourceClassified("GRAPHIC", integer(contact.get("graphicId")))
						|| Arrays.asList("DEFINITION_NOT_QUALIFIED", "STATE_UNAVAILABLE", "CONDITION_NOT_OBSERVED")
							.contains(text(contact.get("sourceEligibility")))
						|| !sameContactScope(contact, impact))
						return true;
				}
		if (impact != null && impact.getHazards() != null && Boolean.TRUE.equals(hit.getImpactPositionKnown()))
			for (HazardState hazard : impact.getHazards())
				if (hazard != null && !Boolean.TRUE.equals(hazard.getFinished()) && hazardTimingAllows(hazard, hit)
					&& hazard.getPlane() == impact.getPlane()
					&& Objects.equals(hazard.getWorldViewId(), impact.getWorldViewId())
					&& (!requiresRecordedFootprint(hazard) || ordinaryObservedHp(hit))
					&& (hazardContact(hazard, hit, impact) || requiresRecordedFootprint(hazard)
						&& !recordedFootprint(hazard, scenes.get(impact.getSceneKey()), impact.getSceneKey())))
				{
					if (requiresRecordedFootprint(hazard)
						&& !recordedFootprint(hazard, scenes.get(impact.getSceneKey()), impact.getSceneKey()))
						return true;
					if (Arrays.asList("CONDITION_NOT_OBSERVED", "STATE_UNAVAILABLE", "DEFINITION_NOT_QUALIFIED")
						.contains(hazard.getSourceEligibility())
						|| "graphicsObject".equals(hazard.getKind()) && hazard.getDamaging() == null
							&& !mechanismSourceClassified("GRAPHIC", hazard.getId()))
						return true;
				}
		if (hit.getCaptureIssues() != null)
			for (String issue : hit.getCaptureIssues())
				if (issue != null && (issue.startsWith("impactPositionScope") || issue.startsWith("impactWorldPosition")
					|| issue.startsWith("projectiles:") || issue.startsWith("npcCandidates")
					|| issue.startsWith("impactHazards") || issue.startsWith("hazard")
					|| issue.startsWith("graphicsAndMappedHazards")))
					return true;
		return false;
	}

	private boolean mechanismSourceClassified(String kind, Integer id)
	{
		return catalog != null && id != null && catalog.mechanismRules().sourceClassified(kind, id);
	}

	private static boolean coverageIncomplete(String value)
	{
		if (value == null)
			return true;
		String status = value.toLowerCase(java.util.Locale.ROOT);
		return status.contains("unavailable") || status.equals("partial") || status.equals("failed")
			|| status.equals("skipped") || status.equals("disabled");
	}

	private static boolean contactTimingAllows(Map<String, Object> contact, DamageRecord hit)
	{
		Integer start = integer(contact.get("startCycle"));
		return start == null || hit.getObservedCycle() == null || start <= hit.getObservedCycle();
	}

	private static boolean sameContactScope(Map<String, Object> contact, WorldSnapshot impact)
	{
		return impact != null && text(contact.get("sceneKey")) != null && impact.getWorldViewId() != null
			&& Objects.equals(integer(contact.get("worldViewId")), impact.getWorldViewId())
			&& Objects.equals(integer(contact.get("plane")), impact.getPlane())
			&& Objects.equals(text(contact.get("sceneKey")), impact.getSceneKey());
	}

	private static boolean contactScopeNotContradicted(Map<String, Object> contact, WorldSnapshot impact)
	{
		if (impact == null)
			return true;
		Integer view = integer(contact.get("worldViewId")), plane = integer(contact.get("plane"));
		String scene = text(contact.get("sceneKey"));
		return (view == null || impact.getWorldViewId() == null || Objects.equals(view, impact.getWorldViewId()))
			&& (plane == null || plane == impact.getPlane()) && (scene == null || scene.equals(impact.getSceneKey()));
	}

	private boolean hasUnqualifiedSurfaceWitness(DamageRecord hit, WorldSnapshot impact, String name)
	{
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : hit.getObservedHazardContacts())
				if (contact != null && name.equals(text(contact.get("name")))
					&& "DEFINITION_NOT_QUALIFIED".equals(text(contact.get("sourceEligibility")))
					&& !Boolean.TRUE.equals(contact.get("finished")) && contactTimingAllows(contact, hit)
					&& contactScopeNotContradicted(contact, impact))
					return true;
		return false;
	}

	private boolean hasHazardWitness(DamageRecord hit, WorldSnapshot impact, String name)
	{
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : hit.getObservedHazardContacts())
				if (contact != null && name.equals(text(contact.get("name")))
					&& !Boolean.FALSE.equals(contact.get("damaging")) && !Boolean.TRUE.equals(contact.get("finished"))
					&& contactTimingAllows(contact, hit) && contactScopeNotContradicted(contact, impact)
					&& !Arrays.asList("NOT_DAMAGE_SURFACE", "RESOURCE_REFERENCE_ONLY", "UNCLASSIFIED_GRAPHIC_SURFACE",
						"DEFINITION_NOT_QUALIFIED").contains(text(contact.get("sourceEligibility"))))
					return true;
		if (impact != null && impact.getHazards() != null)
			for (HazardState hazard : impact.getHazards())
				if (hazard != null && name.equals(hazard.getMappedName()) && Boolean.TRUE.equals(hazard.getDamaging())
					&& !Boolean.TRUE.equals(hazard.getFinished()) && hazardTimingAllows(hazard, hit)
					&& hazard.getPlane() == impact.getPlane()
					&& Objects.equals(hazard.getWorldViewId(), impact.getWorldViewId())
					&& hazardContact(hazard, hit, impact))
					return true;
		return false;
	}

	private static boolean sourceHistoryUnestablished(Map<String, Object> raw)
	{
		Object value = raw.get("sourceHistory");
		if (value instanceof com.tickreplaylogger.model.world.ProjectileSourceHistory)
		{
			com.tickreplaylogger.model.world.ProjectileSourceHistory h = (com.tickreplaylogger.model.world.ProjectileSourceHistory) value;
			return h.isActorChanged() || h.isReferenceChanged() || h.isDefinitionChanged() || h.isScopeChanged()
				|| h.isObservationGap() || h.isProjectileDefinitionChanged();
		}
		if (value instanceof Map)
		{
			Map<?, ?> history = (Map<?, ?>) value;
			for (String key : Arrays.asList("actorChanged", "referenceChanged", "definitionChanged", "scopeChanged",
				"observationGap", "projectileDefinitionChanged"))
				if (Boolean.TRUE.equals(history.get(key))
					|| history.containsKey(key) && !(history.get(key) instanceof Boolean))
					return true;
		}
		return value != null && !(value instanceof Map)
			&& !(value instanceof com.tickreplaylogger.model.world.ProjectileSourceHistory);
	}

	private WorldSnapshot previousFrame(WorldSnapshot impact)
	{
		if (impact == null)
			return null;
		WorldSnapshot previous = null;
		for (WorldSnapshot frame : frames)
			if (frame.getTick() < impact.getTick() && frame.getTick() >= impact.getTick() - 1
				&& Objects.equals(frame.getSceneKey(), impact.getSceneKey())
				&& Objects.equals(frame.getWorldViewId(), impact.getWorldViewId())
				&& frame.getPlane() == impact.getPlane())
				previous = frame;
		return previous;
	}

	private boolean statusCoverageKnown(String damageType, WorldSnapshot frame)
	{
		if (frame != null && frame.getLocalStatuses() != null && catalog != null)
			for (ObservedStatusState status : frame.getLocalStatuses())
				if (status != null && status.isValueObserved() && status.getState() != null
					&& !"unknown".equals(status.getState()) && status.getActive() != null
					&& catalog.localStatusCanRepresentDamage(status.getKey(), damageType))
					return true;
		return false;
	}

	private boolean addStatusHints(String damageType, WorldSnapshot frame,
		List<LegacyConstraintOracle.Hypothesis> hypotheses)
	{
		boolean active = false;
		if (frame != null && frame.getLocalStatuses() != null)
			for (ObservedStatusState status : frame.getLocalStatuses())
				if (status != null && status.isValueObserved() && catalog != null
					&& catalog.localStatusCanRepresentDamage(status.getKey(), damageType)
					&& damageType.equals(status.getState()) && Boolean.TRUE.equals(status.getActive()))
				{
					active = true;
					hypotheses.add(plain(candidate("status:" + status.getRuleKey() + ":" + status.getState(),
						status.getStateLabel() == null ? status.getLabel() : status.getStateLabel(), frame,
						"status:" + status.getRuleKey() + ":" + status.getState(),
						"STATUS_TRANSITION_ENVELOPE; callback ordering and hit inflictor unresolved", "POSSIBLE")));
				}
		return active;
	}

	private WorldSnapshot impactFrame(DamageRecord hit)
	{
		WorldSnapshot selected = null;
		for (WorldSnapshot frame : frames)
		{
			if (frame.getTick() != hit.getImpactTick() || frame.getPlayer() == null)
				continue;
			if (hit.getImpactSceneKey() != null && !hit.getImpactSceneKey().equals(frame.getSceneKey())
				|| hit.getImpactWorldViewId() != null && !hit.getImpactWorldViewId().equals(frame.getWorldViewId())
				|| hit.getImpactPlane() != null && hit.getImpactPlane() != frame.getPlane())
				continue;
			if (Boolean.FALSE.equals(frame.getNativeContextKnown()))
				continue;
			if (selected != null)
				return null;
			selected = frame;
		}
		if (hit.getImpactSceneKey() == null && ("SCENE_CHANGED_BEFORE_ATTRIBUTION".equals(hit.getAttributionEvidence())
			|| "IMPACT_NATIVE_SCOPE_UNAVAILABLE".equals(hit.getAttributionEvidence())))
			return null;
		return selected;
	}

	private List<Candidate> projectileCandidates(long id, Map<String, Object> raw, WorldSnapshot impact)
	{
		ProjectileSample sample = firstProjectiles.get(id);
		if (sample != null)
		{
			ProjectileState projectile = sample.projectile;
			boolean targetAndTiming = (Boolean.TRUE.equals(projectile.getTargetsPlayer())
				|| "player".equals(projectile.getTargetRef())) && !"TIMING_UNAVAILABLE".equals(raw.get("matchBasis"))
				&& (number(raw.get("arrivalGapCycles")) != null || number(raw.get("arrivalGapTicks")) != null);
			boolean historyStable = !sourceHistoryUnestablished(raw) && !projectileHistoryUnestablished(sample)
				&& !Boolean.TRUE.equals(raw.get("sourceSamplingUnavailable"));
			boolean originViewKnown = originalSourceView(sample) != null;
			String correlationStrength = targetAndTiming && historyStable && originViewKnown ? "STRONG" : "POSSIBLE";
			ProjectileSourceCandidate proximity = originalProximity(sample);
			Integer originalView = originalSourceView(sample);
			Integer originalPlane = originalSourcePlane(sample);
			String proximityStrength = targetAndTiming && historyStable && originalView != null ? "STRONG" : "POSSIBLE";
			if (proximity != null && (originalView != null && proximity.getWorldViewId() != null
				&& !originalView.equals(proximity.getWorldViewId())
				|| originalPlane != null && proximity.getPlane() != null && !originalPlane.equals(proximity.getPlane())
				|| !hasRetainedProximity(sample) && projectile.getOriginPlane() != null && proximity.getPlane() != null
					&& !projectile.getOriginPlane().equals(proximity.getPlane())))
				return Collections.emptyList();
			if (proximity != null && (!unknown(proximity.getName()) || proximity.getNpcId() != null))
				return Collections.singletonList(Candidate.builder().key("projectile-proximity:" + id)
					.name(identityName(proximity.getName(), proximity.getNpcId(), proximity.getCombatLevel(),
						Boolean.TRUE.equals(proximity.getNormalWorldContextObserved())))
					.actorRef(proximity.getActorRef()).npcId(proximity.getNpcId()).familyKey(proximity.getFamilyKey())
					.sceneKey(proximity.getSceneKey()).worldViewId(proximity.getWorldViewId())
					.plane(proximity.getPlane()).strength(proximityStrength)
					.evidence(Arrays.asList("PROJECTILE_TIMING_CORRELATION",
						"RECORDED_PROXIMITY_CANDIDATE; not API source actor",
						originalView != null ? "ORIGIN_NATIVE_VIEW_RECORDED" : "ORIGIN_NATIVE_VIEW_UNAVAILABLE",
						historyStable ? "RETAINED_SOURCE_HISTORY_STABLE" : "RETAINED_SOURCE_HISTORY_UNESTABLISHED"))
					.build());
			if (originalSourceRef(sample) != null)
			{
				ActorSourceSample source = originalActorSample(sample);
				Candidate direct = source == null ? null
					: actorCandidate(source.npc, source.frame, originalSourceName(sample), originalSourceRef(sample),
						originalSourceNpcId(sample),
						"PROJECTILE_RECORDED_SOURCE_ACTOR_AND_TIMING; original source-native sample; hit causality remains correlated",
						correlationStrength);
				if (direct != null)
				{
					if (source.npc != null && originalNpcModel(sample, source.npc) == null)
					{
						List<String> evidence = new ArrayList<>(direct.getEvidence());
						evidence.add(
							"ORIGINAL_SOURCE_MODEL_UNQUALIFIED; original definition/name/level/context unavailable or inconsistent");
						direct = direct.toBuilder().familyKey(null).evidence(evidence).build();
					}
					return Collections.singletonList(direct);
				}
				String name = identityName(originalSourceName(sample), originalSourceNpcId(sample), null, false);
				if (!unknown(name))
					return Collections.singletonList(candidate("projectile:" + id, name, null, null,
						"PROJECTILE_ORIGINAL_SOURCE_HYPOTHESIS; source-native actor sample unavailable or ambiguous",
						"POSSIBLE").toBuilder().npcId(originalSourceNpcId(sample))
							.worldViewId(originalSourceView(sample)).plane(originalSourcePlane(sample)).build());
			}
			boolean original = projectile.getSourceHistory() == null
				? sample.frame.getTick() == projectile.getSpawnTick()
				: projectile.getSourceHistory().getFirstObservedSource() != null && projectile.getSourceHistory()
					.getFirstObservedSource().getObservedTick() == sample.frame.getTick();
			if (!hasRetainedProximity(sample) && original && projectile.getSourceHistory() != null
				&& !projectile.getSourceHistory().isAttributionUnestablished() && projectile.getStartWorldX() != null
				&& projectile.getStartWorldY() != null && projectile.getOriginPlane() != null
				&& projectile.getOriginPlane() == sample.frame.getPlane() && (projectile.getSourceWorldViewId() == null
					|| Objects.equals(projectile.getSourceWorldViewId(), sample.frame.getWorldViewId())))
			{
				List<NpcState> nearest = nearest(sample.frame, projectile);
				List<Candidate> candidates = new ArrayList<>();
				for (NpcState npc : nearest)
				{
					Candidate candidate = actorCandidate(npc, sample.frame, npc.getName(), npc.getActorRef(),
						npc.getNpcId(), "PROJECTILE_ORIGINAL_FRAME_FOOTPRINT_PROXIMITY; not API source actor",
						nearest.size() == 1 ? correlationStrength : "POSSIBLE");
					if (candidate != null)
						candidates.add(candidate);
				}
				if (!candidates.isEmpty())
					return candidates;
			}
		}
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		String name = first == null ? text(raw.get("name")) : first.getSourceName();
		if (sample != null && first == null && sample.projectile.getProximitySourceCandidate() != null
			&& originalProximity(sample) == null)
			return Collections.emptyList();
		return unknown(name) ? Collections.emptyList()
			: Collections.singletonList(candidate("projectile:" + id, name, null, null,
				"PROJECTILE_NAMED_HYPOTHESIS; source identity unavailable", "POSSIBLE"));
	}

	private static boolean projectileHistoryUnestablished(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory history = sample.projectile.getSourceHistory();
		return history != null
			&& (history.isAttributionUnestablished() || history.isReferenceChanged() || history.isDefinitionChanged());
	}

	private static String originalSourceRef(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first != null ? first.getSourceRef()
			: sample.projectile.getSourceHistory() == null ? sample.projectile.getSourceRef() : null;
	}

	private static String originalSourceName(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first == null ? sample.projectile.getSourceName() : first.getSourceName();
	}

	private static Integer originalSourceNpcId(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first == null ? sample.projectile.getSourceNpcId() : first.getSourceNpcId();
	}

	/** Only an original positive API actor witness can join an independent native-frame actor sample. */
	private ActorSourceSample originalActorSample(ProjectileSample sample)
	{
		Long id = sample.projectile.getObservationId();
		if (projectileActorSamples.containsKey(id))
			return projectileActorSamples.get(id);
		ActorSourceSample selected = findOriginalActorSample(sample);
		projectileActorSamples.put(id, selected);
		return selected;
	}

	private Candidate endpointOriginalSource(Long observation, Map<?, ?> source)
	{
		Integer tick = integer(source.get("observedTick")), cycle = integer(source.get("observedCycle")),
			view = integer(source.get("sourceWorldViewId")), plane = integer(source.get("sourcePlane")),
			id = integer(source.get("sourceNpcId"));
		String ref = text(source.get("sourceRef")),
			name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(text(source.get("sourceObservedName")));
		if (tick == null || cycle == null || view == null || plane == null || id == null || ref == null || name == null)
			return null;
		Candidate selected = null;
		for (WorldSnapshot frame : nativeFramesByTick.getOrDefault(tick, Collections.emptyList()))
		{
			if (!Boolean.TRUE.equals(frame.getNativeContextKnown()) || !Boolean.TRUE.equals(frame.getGameCycleKnown())
				|| frame.getGameCycle() != cycle || !view.equals(frame.getWorldViewId()) || frame.getPlane() != plane
				|| frame.getSceneKey() == null || frame.getNpcs() == null)
				continue;
			for (NpcState npc : frame.getNpcs())
				if (npc != null && ref.equals(npc.getActorRef()))
				{
					if (selected != null || !id.equals(npc.getNpcId())
						|| !name.equals(com.tickreplaylogger.util.SourceResolutionLabels.usableName(npc.getName()))
						|| !view.equals(npc.getWorldViewId()) || !Boolean.TRUE.equals(npc.getPlaneKnown())
						|| npc.getPlane() != plane)
						return null;
					selected = Candidate.builder().key("projectile-first-api-source:" + observation).actorRef(ref)
						.npcId(id).name(name).sceneKey(frame.getSceneKey()).worldViewId(view).plane(plane)
						.strength("POSSIBLE").build();
				}
		}
		return selected;
	}

	private ActorSourceSample findOriginalActorSample(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		ProjectileState state = sample.projectile;
		// sourceActorKnown reports a successful read, including a successful null result.
		Boolean known = first == null ? state.getSourceActorKnown() : first.getSourceActorKnown();
		String evidence = first == null ? state.getSourceEvidence() : first.getSourceEvidence();
		String ref = originalSourceRef(sample);
		String actorKind = first == null ? null : first.getActorKind();
		Integer tick = first == null ? state.getObservedTick() : first.getObservedTick();
		Integer view = originalSourceView(sample), plane = originalSourcePlane(sample);
		if (!Boolean.TRUE.equals(known) || !"ACTOR".equals(evidence) || ref == null || ref.trim().isEmpty()
			|| tick == null || view == null || plane == null || state.getSourceHistory() != null && first == null
			|| first == null && tick != state.getSpawnTick())
			return null;
		ActorSourceSample selected = null;
		for (WorldSnapshot frame : nativeFramesByTick.getOrDefault(tick, Collections.emptyList()))
		{
			if (!Boolean.TRUE.equals(frame.getNativeContextKnown()) || frame.getSceneKey() == null
				|| frame.getSceneKey().trim().isEmpty() || !view.equals(frame.getWorldViewId())
				|| plane != frame.getPlane())
				continue;
			NpcState npc = null;
			if (frame.getNpcs() != null)
				for (NpcState actor : frame.getNpcs())
					if (actor != null && ref.equals(actor.getActorRef()))
					{
						if (npc != null || !view.equals(actor.getWorldViewId())
							|| !Boolean.TRUE.equals(actor.getPlaneKnown()) || plane != actor.getPlane()
							|| actor.getObservationIssues() != null
								&& (actor.getObservationIssues().containsKey("worldView")
									|| actor.getObservationIssues().containsKey("worldViewId")))
							return null;
						npc = actor;
					}
			com.tickreplaylogger.model.world.OtherPlayerState other = findOtherPlayer(frame, ref);
			if (other == null && frame.getPlayers() != null)
				for (com.tickreplaylogger.model.world.OtherPlayerState actor : frame.getPlayers())
					if (actor != null && ref.equals(actor.getActorRef()))
						return null;
			if ((originalSourceNpcId(sample) != null || "NPC".equals(actorKind)) && other != null
				|| "OTHER_PLAYER".equals(actorKind) && npc != null)
				return null;
			if (npc != null && other != null || selected != null && (npc != null || other != null))
				return null;
			if (npc != null || other != null)
				selected = new ActorSourceSample(frame, npc);
		}
		return selected;
	}

	private static com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation firstSource(
		ProjectileSample sample)
	{
		return sample == null || sample.projectile.getSourceHistory() == null ? null
			: sample.projectile.getSourceHistory().getFirstObservedSource();
	}

	private static boolean hasRetainedProximity(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first != null && Boolean.TRUE.equals(first.getProximitySampleRetained());
	}

	private static ProjectileSourceCandidate originalProximity(ProjectileSample sample)
	{
		if (sample == null)
			return null;
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		if (first != null)
			return Boolean.TRUE.equals(first.getProximitySampleRetained()) ? first.getProximityCandidate() : null;
		ProjectileState state = sample.projectile;
		return state.getObservedTick() != null && state.getObservedTick() == state.getSpawnTick()
			? state.getProximitySourceCandidate()
			: null;
	}

	private static Integer originalSourceView(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first == null ? sample.projectile.getSourceWorldViewId() : first.getSourceWorldViewId();
	}

	private static Integer originalSourcePlane(ProjectileSample sample)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		return first == null ? sample.projectile.getSourcePlane() : first.getSourcePlane();
	}

	private NpcState projectileNpc(long id, Candidate candidate)
	{
		ProjectileSample sample = firstProjectiles.get(id);
		if (sample == null)
			return null;
		ProjectileSourceCandidate original = originalProximity(sample);
		if (original != null && Objects.equals(original.getActorRef(), candidate.getActorRef())
			&& Objects.equals(original.getNpcId(), candidate.getNpcId()))
			return NpcState.builder().actorRef(original.getActorRef()).npcId(original.getNpcId())
				.name(original.getName()).combatLevel(original.getCombatLevel()).animationId(original.getAnimationId())
				.modelOverridesPresent(original.getModelOverridesPresent())
				.normalWorldContextObserved(original.getNormalWorldContextObserved())
				.worldViewId(original.getWorldViewId()).plane(original.getPlane() == null ? -1 : original.getPlane())
				.planeKnown(original.getPlane() != null).build();
		ActorSourceSample actual = originalActorSample(sample);
		NpcState observed = actual == null || !Objects.equals(originalSourceRef(sample), candidate.getActorRef()) ? null
			: actual.npc;
		return observed != null && Objects.equals(observed.getNpcId(), candidate.getNpcId())
			? originalNpcModel(sample, observed)
			: null;
	}

	/** Actor identity does not establish that a later settled definition/model was the original source model. */
	private static NpcState originalNpcModel(ProjectileSample sample, NpcState observed)
	{
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		ProjectileState state = sample.projectile;
		Integer id = originalSourceNpcId(sample);
		Integer level = first == null ? state.getSourceCombatLevel() : first.getSourceCombatLevel();
		Boolean nameKnown = first == null ? state.getSourceNameKnown() : first.getSourceNameKnown();
		String name = first == null ? state.getSourceObservedName() : first.getSourceObservedName();
		Boolean normal = first == null ? state.getSourceNormalWorldContextObserved()
			: first.getSourceNormalWorldContextObserved();
		String definition = first == null ? state.getSourceDefinitionEvidence() : first.getSourceDefinitionEvidence();
		Integer base = first == null ? state.getSourceBaseNpcId() : first.getSourceBaseNpcId();
		Boolean transforms = first == null ? state.getSourceDefinitionHasTransforms()
			: first.getSourceDefinitionHasTransforms();
		if (id == null || !id.equals(observed.getNpcId()) || level == null || !level.equals(observed.getCombatLevel())
			|| !Boolean.TRUE.equals(nameKnown) || name == null || observed.getName() == null
			|| !name.replaceAll("<[^>]*>", "").trim().equals(observed.getName()) || !Boolean.TRUE.equals(normal)
			|| !Boolean.TRUE.equals(observed.getNormalWorldContextObserved())
			|| !Boolean.FALSE.equals(observed.getModelOverridesPresent())
			|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(definition)
			|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(observed.getDefinitionEvidence())
			|| base != null && !base.equals(observed.getBaseNpcId())
			|| transforms != null && !transforms.equals(observed.getDefinitionHasTransforms()))
			return null;
		return observed;
	}

	private List<NpcState> nearest(WorldSnapshot frame, ProjectileState projectile)
	{
		List<NpcState> result = new ArrayList<>();
		int best = 2;
		if (frame.getNpcs() == null)
			return result;
		for (NpcState npc : frame.getNpcs())
		{
			if (npc == null || !Boolean.TRUE.equals(npc.getWorldPositionKnown())
				|| !Boolean.TRUE.equals(npc.getPlaneKnown())
				|| !Objects.equals(npc.getWorldViewId(), frame.getWorldViewId()) || npc.getPlane() != frame.getPlane()
				|| npc.getSize() == null || npc.getSize() < 1 || npc.getSize() > 64 || npc.getCombatLevel() == null
				|| npc.getCombatLevel() <= 0
				|| !unknown(projectile.getSourceName()) && !projectile.getSourceName().equals(npc.getName()))
				continue;
			int dx = Math.max(0, Math.max(npc.getWorldX() - projectile.getStartWorldX(),
				projectile.getStartWorldX() - (npc.getWorldX() + npc.getSize() - 1)));
			int dy = Math.max(0, Math.max(npc.getWorldY() - projectile.getStartWorldY(),
				projectile.getStartWorldY() - (npc.getWorldY() + npc.getSize() - 1)));
			int distance = Math.max(dx, dy);
			if (distance < best)
			{
				best = distance;
				result.clear();
			}
			if (distance == best && distance < 2)
				result.add(npc);
		}
		return result;
	}

	private Candidate actorCandidate(NpcState npc, WorldSnapshot frame, String name, String actorRef, Integer npcId,
		String evidence, String strength)
	{
		com.tickreplaylogger.model.world.OtherPlayerState other = npc == null && npcId == null
			? findOtherPlayer(frame, actorRef)
			: null;
		if (other != null && !unknown(other.getName())
			&& (other.getObservationIssues() == null || !other.getObservationIssues().containsKey("name")))
			name = other.getName();
		if (npc == null && npcId == null && other == null)
			strength = "POSSIBLE";
		name = identityName(name, npcId, npc == null ? null : npc.getCombatLevel(),
			npc != null && Boolean.TRUE.equals(npc.getNormalWorldContextObserved()));
		if (unknown(name))
			return null;
		String family = npc == null || catalog == null || npc.getNpcId() == null
			|| !Objects.equals(npcId, npc.getNpcId()) || !catalog.observedNameCompatible(npc.getNpcId(), name, true)
				? null
				: catalog.attributionFamily(npc.getNpcId(), npc.getName(), npc.getCombatLevel(),
					Boolean.TRUE.equals(npc.getNormalWorldContextObserved()));
		return candidate("actor:" + (actorRef == null ? "unresolved:" + name : actorRef), name, frame, family, evidence,
			strength).toBuilder().actorRef(npc == null && other == null ? null : actorRef).npcId(npcId).build();
	}

	private static com.tickreplaylogger.model.world.OtherPlayerState findOtherPlayer(WorldSnapshot frame, String ref)
	{
		if (frame == null || ref == null || ref.trim().isEmpty() || frame.getPlayers() == null
			|| !Boolean.TRUE.equals(frame.getNativeContextKnown()) || frame.getSceneKey() == null
			|| frame.getSceneKey().trim().isEmpty() || frame.getWorldViewId() == null)
			return null;
		com.tickreplaylogger.model.world.OtherPlayerState selected = null;
		for (com.tickreplaylogger.model.world.OtherPlayerState player : frame.getPlayers())
			if (player != null && ref.equals(player.getActorRef()))
			{
				if (selected != null || !Objects.equals(player.getWorldViewId(), frame.getWorldViewId())
					|| !Boolean.TRUE.equals(player.getPlaneKnown()) || player.getPlane() != frame.getPlane()
					|| player.getObservationIssues() != null && (player.getObservationIssues().containsKey("worldView")
						|| player.getObservationIssues().containsKey("worldViewId")
						|| player.getObservationIssues().containsKey("worldPosition")))
					return null;
				selected = player;
			}
		return selected;
	}

	private String identityName(String observed, Integer npcId, Integer level, boolean normal)
	{
		String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(observed);
		if (name != null)
			return name;
		String reference = npcId == null || catalog == null ? null
			: catalog.attributionIdentityName(npcId, level, normal);
		return reference != null ? reference : npcId == null ? null : "NPC #" + npcId;
	}

	private LegacyConstraintOracle.Hypothesis hypothesis(Candidate candidate, NpcState npc, Map<String, Object> raw,
		DamageRecord hit, WorldSnapshot impact, boolean originalProjectile)
	{
		Map<String, Boolean> observations = new HashMap<>();
		boolean qualified = candidate.getFamilyKey() != null && npc != null && npc.getNpcId() != null
			&& Objects.equals(candidate.getNpcId(), npc.getNpcId()) && catalog != null
			&& catalog.attributionBaseProfile(npc.getNpcId(), npc.getCombatLevel(), candidate.getFamilyKey());
		boolean normal = npc != null && Boolean.TRUE.equals(npc.getNormalWorldContextObserved());
		boolean noOverrides = npc != null && Boolean.FALSE.equals(npc.getModelOverridesPresent());
		// Projectile history has no original source-animation sample. A settled pose is a different clock.
		Integer animation = npc == null || originalProjectile ? null : integer(raw.get("animationId"));
		Integer projectileId = integer(raw.get("projectileId"));
		if (originalProjectile && projectileId == null)
		{
			Long observationId = number(raw.get("projectileObservationId"));
			ProjectileSample sample = observationId == null ? null : firstProjectiles.get(observationId);
			projectileId = sample == null ? null : sample.projectile.getProjectileId();
		}
		String outcomeMechanism = catalog == null || npc == null || npc.getNpcId() == null ? null
			: catalog.outcomeMechanism(npc.getNpcId(), animation, projectileId, npc.getCombatLevel(), normal);
		boolean cue = catalog != null && npc != null && npc.getNpcId() != null
			&& (animation != null
				&& (catalog.reviewedAttackCue(npc.getNpcId(), animation, npc.getCombatLevel(), normal)
					|| map.attackRuleForNpc(npc.getNpcId(), animation, npc.getCombatLevel(), normal) != null)
				|| originalProjectile && projectileId != null && "ORDINARY_ATTACK".equals(outcomeMechanism));
		boolean singleCallback = hit.getObservationSequence() != null && hitsplatObserved(hit)
			&& (hit.getDirection() == null || "RECEIVED".equals(hit.getDirection())) && map != null
			&& Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()));
		observations.put("QUALIFIED_NPC_VARIANT", qualified);
		observations.put("NORMAL_WORLD_CONTEXT", normal);
		observations.put("NO_MODEL_OVERRIDES_OBSERVED", noOverrides);
		observations.put("ORDINARY_ATTACK_CUE", cue);
		observations.put("ORDINARY_ATTACK_COMPONENT",
			qualified && normal && noOverrides && "ORDINARY_ATTACK".equals(outcomeMechanism)
				|| Boolean.TRUE.equals(raw.get("ordinaryAttackComponentKnown")));
		observations.put("SINGLE_HIT_COMPONENT",
			singleCallback || Boolean.TRUE.equals(raw.get("singleHitComponentKnown")));
		observations.put("NPC_DIRECT_SOURCE_HYPOTHESIS",
			"RECEIVED".equals(hit.getDirection()) && "player".equals(hit.getDefenderRef()) && npc != null
				&& npc.getNpcId() != null && candidate.getActorRef() != null && candidate.getWorldViewId() != null
				&& candidate.getSceneKey() != null && candidate.getPlane() != null
				&& Boolean.TRUE.equals(npc.getPlaneKnown()) && candidate.getActorRef().equals(npc.getActorRef())
				&& Objects.equals(candidate.getNpcId(), npc.getNpcId())
				&& candidate.getWorldViewId().equals(npc.getWorldViewId()) && candidate.getPlane() == npc.getPlane());
		observations.put("STANDARD_DAMAGE_MODIFIERS", Boolean.TRUE.equals(raw.get("standardDamageModifiersKnown")));
		List<AttributionConstraint> constraints = catalog == null || candidate.getNpcId() == null
			? Collections.emptyList()
			: catalog.attributionConstraints(candidate.getNpcId());
		boolean baseEnvelope = constraints.stream()
			.anyMatch(c -> "BASE_PROFILE_ENVELOPE".equals(c.getEnvelopeOperator())) && qualified && normal
			&& noOverrides && cue && singleCallback;
		boolean exceptional = outcomeMechanism != null && !"ORDINARY_ATTACK".equals(outcomeMechanism)
			|| Boolean.TRUE.equals(raw.get("knownExceptionalContext"))
			|| Boolean.TRUE.equals(raw.get("scalingObserved")) || sourceHistoryUnestablished(raw)
			|| Boolean.TRUE.equals(raw.get("sourceSamplingUnavailable"));
		if (originalProjectile)
		{
			Long observationId = number(raw.get("projectileObservationId"));
			ProjectileSample original = observationId == null ? null : firstProjectiles.get(observationId);
			if (original == null || originalSourceView(original) == null)
				baseEnvelope = false;
		}
		for (AttributionConstraint constraint : constraints)
		{
			if ("INDEPENDENT_COMPONENT_GUARD".equals(constraint.getComposition()))
				continue;
			if (!"BASE_PROFILE_ENVELOPE".equals(constraint.getEnvelopeOperator())
				|| !catalog.baseProfileContextKnown(impact == null ? null : impact.getContext(),
					constraint.getBlockedActivities()))
				baseEnvelope = false;
			if (impact != null && impact.getContext() != null)
			{
				com.tickreplaylogger.model.world.ContextBlock context = impact.getContext();
				if (constraint.getBlockedActivities() != null
					&& constraint.getBlockedActivities().contains(context.getActivity()))
					exceptional = true;
				if (context.getRaidLevel() != null || context.getScaledTeamSize() != null)
					exceptional = true;
			}
		}
		observations.put("BASE_PROFILE_ENVELOPE_APPLICABLE", baseEnvelope && !exceptional);
		boolean apiSourceCorrelation = false;
		if (originalProjectile && "STRONG".equals(candidate.getStrength()))
		{
			Long id = number(raw.get("projectileObservationId"));
			ProjectileSample original = id == null ? null : firstProjectiles.get(id);
			apiSourceCorrelation = original != null && originalActorSample(original) != null
				&& candidate.getActorRef() != null && candidate.getActorRef().equals(originalSourceRef(original));
		}
		LegacyConstraintOracle.Hypothesis result = LegacyConstraintOracle.Hypothesis.builder().candidate(candidate)
			.constraints(constraints).observations(observations).mechanism("ORDINARY_ATTACK")
			.observedAttackStyle(qualifiedAttackStyle(npc, animation, projectileId, normal))
			.observedHitsplatType(hitsplatObserved(hit) ? hit.getHitsplatType() : null)
			.numericObservations(numericFacts(constraints, npc, hit, impact, apiSourceCorrelation))
			.damageComponent("SINGLE_HIT").worldMode(normal ? "NORMAL" : null)
			.observedApiSourceCorrelation(apiSourceCorrelation)
			// Activity, ownership, HP capability and timing do not uniquely link an impact callback.
			.causalAssociationOnly(true).build();
		boolean ordinary = qualified && normal && noOverrides && !sourceHistoryUnestablished(raw)
			&& !Boolean.TRUE.equals(raw.get("sourceSamplingUnavailable")) && "ORDINARY_ATTACK".equals(outcomeMechanism);
		result = outcomeCompatibility(result, hit, "NPC_ORDINARY_ATTACK", candidate.getNpcId(),
			Collections.singletonMap("QUALIFIED_ORDINARY_COMPONENT", ordinary));
		if (!originalProjectile)
			return result;
		Long observation = number(raw.get("projectileObservationId"));
		ProjectileSample sourceSample = observation == null ? null : firstProjectiles.get(observation);
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sourceSample);
		Map<String, Object> selector = new HashMap<>();
		selector.put("projectileId", sourceSample == null ? null : sourceSample.projectile.getProjectileId());
		selector.put("observedName", first == null ? null : first.getSourceObservedName());
		return outcomeCompatibility(result, hit, "NPC_PROJECTILE_COMPONENT", candidate.getNpcId(),
			Collections.singletonMap("QUALIFIED_ORIGINAL_PROJECTILE_COMPONENT",
				qualifiedOriginalProjectileComponent(candidate, raw, hit, sourceSample)),
			"PROJECTILE_COMPONENT", selector);
	}

	/** A component exclusion needs original API identity, not proximity, current pose or an ordinary cap. */
	private boolean qualifiedOriginalProjectileComponent(Candidate candidate, Map<String, Object> raw, DamageRecord hit,
		ProjectileSample sample)
	{
		if (sample == null || sample.projectile.getSourceHistory() == null
			|| sample.projectile.getSourceHistory().getVersion() != 1 || sourceHistoryUnestablished(raw)
			|| projectileHistoryUnestablished(sample) || Boolean.TRUE.equals(raw.get("sourceSamplingUnavailable")))
			return false;
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = firstSource(sample);
		ActorSourceSample actual = originalActorSample(sample);
		if (first == null || actual == null || actual.npc == null || !Boolean.TRUE.equals(first.getSourceActorKnown())
			|| !"ACTOR".equals(first.getSourceEvidence()) || !"NPC".equals(first.getActorKind())
			|| !Boolean.TRUE.equals(first.getSourceNameKnown())
			|| !Boolean.TRUE.equals(first.getSourceNormalWorldContextObserved())
			|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(first.getSourceDefinitionEvidence())
			|| first.getProjectileId() == null || !first.getProjectileId().equals(sample.projectile.getProjectileId())
			|| first.getSourceNpcId() == null || !first.getSourceNpcId().equals(candidate.getNpcId())
			|| !first.getSourceNpcId().equals(actual.npc.getNpcId()) || first.getSourceRef() == null
			|| !first.getSourceRef().equals(candidate.getActorRef())
			|| !first.getSourceRef().equals(actual.npc.getActorRef()) || first.getSourceObservedName() == null
			|| !first.getSourceObservedName().equals(actual.npc.getName()) || first.getSourceWorldViewId() == null
			|| first.getSourceWorldViewId() < 0 || !first.getSourceWorldViewId().equals(candidate.getWorldViewId())
			|| first.getSourcePlane() == null || first.getSourcePlane() < 0 || first.getSourcePlane() > 3
			|| !first.getSourcePlane().equals(candidate.getPlane())
			|| !Objects.equals(actual.frame.getSceneKey(), candidate.getSceneKey()) || first.getObservedTick() == null
			|| first.getObservedTick() < 0 || hit.getImpactTick() < 0 || first.getObservedTick() > hit.getImpactTick()
			|| first.getObservedCycle() == null || first.getObservedCycle() < 0 || hit.getObservedCycle() == null
			|| hit.getObservedCycle() < 0 || first.getObservedCycle() > hit.getObservedCycle())
			return false;
		Integer retainedProjectile = integer(raw.get("projectileId"));
		if (retainedProjectile != null && !retainedProjectile.equals(first.getProjectileId()))
			return false;
		if (first.getObservationIssues() != null)
			for (String field : first.getObservationIssues().keySet())
				if (field.startsWith("source.")
					|| Arrays.asList("sourceActor", "sourcePlane", "projectileId").contains(field))
					return false;
		return !Boolean.TRUE.equals(actual.npc.getModelOverridesPresent());
	}

	private String qualifiedAttackStyle(NpcState npc, Integer animation, Integer projectile, boolean normal)
	{
		if (npc == null || npc.getNpcId() == null || catalog == null || map == null)
			return null;
		String style = projectile == null ? null
			: catalog.projectileDamageType(npc.getNpcId(), projectile, npc.getCombatLevel(), normal);
		if (style == null && projectile == null && animation != null)
		{
			PreRuleIrDamageMap.AttackRule rule = map.attackRuleForNpc(npc.getNpcId(), animation, npc.getCombatLevel(), normal);
			if (rule != null && !rule.isRequiresProjectile())
				style = rule.getDamageType();
		}
		if (style == null)
			return null;
		String normalized = style.toUpperCase(java.util.Locale.ROOT);
		return Arrays.asList("MELEE", "RANGED", "MAGIC").contains(normalized) ? normalized : null;
	}

	/** A source-wide guard may constrain a direct activity possibility without identifying an ordinary attack. */
	private LegacyConstraintOracle.Hypothesis activityHypothesis(Candidate candidate, NpcState source, DamageRecord hit,
		WorldSnapshot impact)
	{
		if (catalog == null || source == null || source.getNpcId() == null || source.getName() == null
			|| source.getCombatLevel() == null || !Boolean.TRUE.equals(source.getNormalWorldContextObserved())
			|| !Boolean.FALSE.equals(source.getModelOverridesPresent())
			|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(source.getDefinitionEvidence())
			|| !Objects.equals(source.getActorRef(), candidate.getActorRef())
			|| !Objects.equals(source.getNpcId(), candidate.getNpcId())
			|| !Objects.equals(source.getWorldViewId(), candidate.getWorldViewId())
			|| !Boolean.TRUE.equals(source.getPlaneKnown()) || !Objects.equals(source.getPlane(), candidate.getPlane()))
			return plain(candidate);
		if (source.getObservationIssues() != null)
			for (String field : Arrays.asList("name", "combatLevel", "definitionId", "definitionContext",
				"transformedComposition", "modelOverrides", "callbackModel"))
				if (source.getObservationIssues().containsKey(field))
					return plain(candidate);
		List<AttributionConstraint> constraints = new ArrayList<>();
		for (AttributionConstraint constraint : catalog.attributionConstraints(source.getNpcId()))
			if ("QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(constraint.getBoundBasis()))
				constraints.add(constraint);
		if (constraints.isEmpty())
			return plain(candidate);
		String family = catalog.attributionFamily(source.getNpcId(), source.getName(), source.getCombatLevel(), true);
		if (family == null || !catalog.attributionBaseProfile(source.getNpcId(), source.getCombatLevel(), family))
			return plain(candidate);
		Map<String, Boolean> observed = new HashMap<>();
		observed.put("QUALIFIED_NPC_VARIANT", true);
		observed.put("NORMAL_WORLD_CONTEXT", true);
		observed.put("NO_MODEL_OVERRIDES_OBSERVED", true);
		observed.put("NPC_DIRECT_SOURCE_HYPOTHESIS", true);
		observed.put("SINGLE_HIT_COMPONENT", hit.getObservationSequence() != null && hit.getObservationSequence() > 0
			&& Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) && hitsplatObserved(hit));
		return LegacyConstraintOracle.Hypothesis.builder().candidate(candidate.toBuilder().familyKey(family).build())
			.constraints(Collections.unmodifiableList(constraints)).observations(Collections.unmodifiableMap(observed))
			.numericObservations(numericFacts(constraints, source, hit, impact, false))
			.observedHitsplatType(hit.getHitsplatType()).mechanism("NPC_DIRECT_SOURCE").damageComponent("SINGLE_HIT")
			.worldMode("NORMAL").causalAssociationOnly(true).build();
	}

	/** Fact names describe their observation clock. Missing reads are never filled with zero. */
	private static Map<String, Number> numericFacts(List<AttributionConstraint> constraints, NpcState npc,
		DamageRecord hit, WorldSnapshot impact, boolean apiSourceCorrelation)
	{
		java.util.Set<String> requested = new java.util.LinkedHashSet<>();
		for (AttributionConstraint constraint : constraints)
		{
			if (constraint.getMaximumExpression() != null)
				requested.addAll(constraint.getMaximumExpression().referencedFacts());
			if (constraint.getConditions() != null)
				requested.addAll(constraint.getConditions().referencedFacts());
		}
		if (requested.isEmpty())
			return Collections.emptyMap();
		Map<String, Number> values = new HashMap<>();
		com.tickreplaylogger.model.world.ContextBlock context = impact == null ? null : impact.getContext();
		for (String fact : requested)
		{
			Number value = null;
			if (fact.startsWith("context.activity.") && context != null && context.getActivity() != null)
				value = context.getActivity().equals(fact.substring("context.activity.".length())) ? 1 : 0;
			else if (fact.startsWith("snapshot.impact.context.variable.") && context != null
				&& context.getObservedVariables() != null)
				value = context.getObservedVariables()
					.get(fact.substring("snapshot.impact.context.variable.".length()));
			else if ("snapshot.impact.context.raidLevel".equals(fact) && context != null)
				value = context.getRaidLevel();
			else if ("snapshot.impact.context.teamSize".equals(fact) && context != null)
				value = context.getTeamSize();
			else if ("snapshot.impact.context.scaledTeamSize".equals(fact) && context != null)
				value = context.getScaledTeamSize();
			else if ("source.sample.combatLevel".equals(fact) && npc != null)
				value = npc.getCombatLevel();
			else if ("source.apiProjectileCorrelated".equals(fact) && apiSourceCorrelation)
				value = 1;
			else if ("hit.tick".equals(fact))
				value = hit.getImpactTick();
			else if ("hit.observedCycle".equals(fact))
				value = hit.getObservedCycle();
			if (value != null)
				values.put(fact, value);
		}
		return Collections.unmodifiableMap(values);
	}

	private void addCallbackHazards(DamageRecord hit, WorldSnapshot impact,
		List<LegacyConstraintOracle.Hypothesis> output)
	{
		if (hit.getObservedHazardContacts() == null)
			return;
		for (Map<String, Object> contact : hit.getObservedHazardContacts())
		{
			if (contact == null || Boolean.FALSE.equals(contact.get("damaging"))
				|| "NOT_DAMAGE_SURFACE".equals(text(contact.get("sourceEligibility")))
				|| "RESOURCE_REFERENCE_ONLY".equals(text(contact.get("sourceEligibility")))
				|| "DEFINITION_NOT_QUALIFIED".equals(text(contact.get("sourceEligibility")))
				|| "UNCLASSIFIED_GRAPHIC_SURFACE".equals(text(contact.get("sourceEligibility")))
				|| Boolean.TRUE.equals(contact.get("finished")) || !contactTimingAllows(contact, hit)
				|| !contactScopeNotContradicted(contact, impact))
				continue;
			String name = text(contact.get("name"));
			Integer objectId = integer(contact.get("objectId")), npcId = integer(contact.get("npcId")),
				graphicId = integer(contact.get("graphicId"));
			DamageMap.ObjectHazardRule rule = objectId == null || map == null ? null : map.objectHazardRule(objectId);
			if (requiresNativeObjectContact(objectId) && !callbackObjectContactQualified(contact, hit, impact))
				continue;
			if (requiresRecordedFootprint(objectId)
				&& (!ordinaryObservedHp(hit) || !"gameObject".equals(contact.get("objectKind"))))
				continue;
			if (rule != null && "CONDITIONAL_HP_TILE".equals(rule.getSurfaceOperator())
				&& (!Integer.valueOf(hit.getImpactX()).equals(integer(contact.get("contactWorldX")))
					|| !Integer.valueOf(hit.getImpactY()).equals(integer(contact.get("contactWorldY")))))
				continue;
			if (!SurfaceContactAdmission.accepts(rule, hit, catalog, hpCallbackContexts)
				|| SurfaceContactAdmission.declared(rule)
					&& !qualifiedConditionalDefinition(text(contact.get("sourceEligibility"))))
				continue;
			String ref = text(contact.get("actorRef"));
			if (unknown(name))
				continue;
			String family = graphicId != null ? "graphic-definition:" + graphicId
				: objectId != null ? objectFamily(objectId) : npcId != null ? "npc-contact:" + npcId : null;
			boolean sameScope = sameContactScope(contact, impact);
			LegacyConstraintOracle.Hypothesis hypothesis = plain(candidate(
				"callback-contact:" + family + ":"
					+ (graphicId == null ? contact.get("objectObservationId") : contact.get("graphicObservationId"))
					+ ":" + output.size(),
				name, sameScope ? impact : null, family, "CALLBACK_SPATIAL_CONTACT; co-location is not causality",
				"POSSIBLE").toBuilder().actorRef(ref).npcId(npcId).worldViewId(integer(contact.get("worldViewId")))
					.plane(integer(contact.get("plane"))).build());
			hypothesis = objectId == null && graphicId == null ? hypothesis
				: outcomeCompatibility(hypothesis, hit, graphicId == null ? "OBJECT" : "GRAPHIC",
					graphicId == null ? objectId : graphicId, Collections.singletonMap("SOURCE_DEFINITION_BOUND",
						sourceDefinitionBound(text(contact.get("sourceEligibility")))));
			output.add(SurfaceContactAdmission.constrain(hypothesis, rule, hit, hpCallbackContexts,
				qualifiedConditionalDefinition(text(contact.get("sourceEligibility")))));
		}
	}

	private void addSettledHazards(DamageRecord hit, WorldSnapshot impact,
		List<LegacyConstraintOracle.Hypothesis> output)
	{
		if (impact == null || !Boolean.TRUE.equals(hit.getImpactPositionKnown()) || impact.getHazards() == null
			|| hit.getCaptureIssues() != null && hit.getCaptureIssues().stream()
				.anyMatch(issue -> issue != null && issue.startsWith("impactPositionScope")))
			return;
		for (HazardState hazard : impact.getHazards())
		{
			boolean conditional = hazard != null
				&& Arrays.asList("CONDITION_NOT_OBSERVED", "STATE_UNAVAILABLE").contains(hazard.getSourceEligibility());
			if (hazard == null || (!Boolean.TRUE.equals(hazard.getDamaging()) && !conditional)
				|| Boolean.FALSE.equals(hazard.getDamaging()) || unknown(hazard.getMappedName())
				|| "DEFINITION_NOT_QUALIFIED".equals(hazard.getSourceEligibility())
				|| Boolean.TRUE.equals(hazard.getFinished()) || !hazardTimingAllows(hazard, hit)
				|| hazard.getPlane() != impact.getPlane()
				|| !Objects.equals(hazard.getWorldViewId(), impact.getWorldViewId())
				|| "RESOURCE_REFERENCE_ONLY".equals(hazard.getSourceEligibility()))
				continue;
			if (hazardContact(hazard, hit, impact))
			{
				String family = "graphicsObject".equals(hazard.getKind()) ? "graphic-definition:" + hazard.getId()
					: objectFamily(hazard.getId());
				LegacyConstraintOracle.Hypothesis hypothesis = plain(candidate(
					"settled-contact:" + family + ":" + hazard.getObjectKey(), hazard.getMappedName(), impact, family,
					conditional ? "SETTLED_RECORDED_FOOTPRINT_CONTACT; activation or damage conditions unestablished"
						: "SETTLED_RECORDED_FOOTPRINT_CONTACT; creation/activation order and causality unresolved",
					"POSSIBLE"));
				hypothesis = outcomeCompatibility(hypothesis, hit,
					"graphicsObject".equals(hazard.getKind()) ? "GRAPHIC" : "OBJECT", hazard.getId(), Collections
						.singletonMap("SOURCE_DEFINITION_BOUND", sourceDefinitionBound(hazard.getSourceEligibility())));
				DamageMap.ObjectHazardRule rule = objectSurface(hazard) && map != null
					? map.objectHazardRule(hazard.getId())
					: null;
				output.add(SurfaceContactAdmission.constrain(hypothesis, rule, hit, hpCallbackContexts,
					qualifiedConditionalDefinition(hazard.getSourceEligibility())));
			}
		}
	}

	private static boolean hazardTimingAllows(HazardState hazard, DamageRecord hit)
	{
		return hazard.getStartCycle() == null || hit.getObservedCycle() == null
			|| hazard.getStartCycle() <= hit.getObservedCycle();
	}

	private boolean hazardContact(HazardState hazard, DamageRecord hit, WorldSnapshot impact)
	{
		if (impact == null || !Boolean.TRUE.equals(hit.getImpactPositionKnown()))
			return false;
		SceneData scene = scenes.get(impact.getSceneKey());
		boolean footprint = requiresRecordedFootprint(hazard);
		DamageMap.ObjectHazardRule rule = objectSurface(hazard) && map != null ? map.objectHazardRule(hazard.getId())
			: null;
		if (objectSurface(hazard) && requiresNativeObjectContact(hazard.getId())
			&& !recordedObjectScope(hazard, scene, impact, hit))
			return false;
		if (!SurfaceContactAdmission.accepts(rule, hit, catalog, hpCallbackContexts)
			|| SurfaceContactAdmission.declared(rule) && !qualifiedConditionalDefinition(hazard.getSourceEligibility()))
			return false;
		if (footprint && !ordinaryObservedHp(hit))
			return false;
		if (footprint && !recordedFootprint(hazard, scene, impact.getSceneKey()))
			return false;
		if (rule != null && "CONDITIONAL_HP_TILE".equals(rule.getSurfaceOperator())
			&& Boolean.TRUE.equals(hazard.getLocalPositionKnown())
			&& (hazard.getMinSceneX() != null || hazard.getMinSceneY() != null || hazard.getMaxSceneX() != null
				|| hazard.getMaxSceneY() != null)
			&& (scene == null || scene.getWidth() <= 0 || scene.getWidth() > 104 || scene.getHeight() <= 0
				|| scene.getHeight() > 104 || hazard.getMinSceneX() == null || hazard.getMinSceneY() == null
				|| hazard.getMaxSceneX() == null || hazard.getMaxSceneY() == null
				|| !hazard.getMinSceneX().equals(hazard.getMaxSceneX())
				|| !hazard.getMinSceneY().equals(hazard.getMaxSceneY()) || hazard.getMinSceneX() < 0
				|| hazard.getMinSceneY() < 0 || hazard.getMaxSceneX() >= scene.getWidth()
				|| hazard.getMaxSceneY() >= scene.getHeight()))
			return false;
		if (scene != null && (Boolean.TRUE.equals(hazard.getLocalPositionKnown()) || footprint)
			&& hazard.getMinSceneX() != null && hazard.getMaxSceneX() != null && hazard.getMinSceneY() != null
			&& hazard.getMaxSceneY() != null)
		{
			long x = (long) hit.getImpactX() - scene.getBaseX(), y = (long) hit.getImpactY() - scene.getBaseY();
			return x >= hazard.getMinSceneX() && x <= hazard.getMaxSceneX() && y >= hazard.getMinSceneY()
				&& y <= hazard.getMaxSceneY();
		}
		return Boolean.TRUE.equals(hazard.getWorldPositionKnown()) && hit.getImpactX() == hazard.getWorldX()
			&& hit.getImpactY() == hazard.getWorldY();
	}

	private static boolean objectSurface(HazardState hazard)
	{
		return hazard != null
			&& Arrays.asList("gameObject", "groundObject", "wallObject", "decorativeObject").contains(hazard.getKind());
	}

	private boolean requiresNativeObjectContact(Integer id)
	{
		DamageMap.ObjectHazardRule rule = map == null || id == null ? null : map.objectHazardRule(id);
		return rule != null && Arrays.asList("CONDITIONAL_HP_TILE", "CONDITIONAL_HP_OBJECT_FOOTPRINT")
			.contains(rule.getSurfaceOperator());
	}

	private static boolean qualifiedConditionalDefinition(String eligibility)
	{
		return "CONDITION_NOT_OBSERVED".equals(eligibility) || "ELIGIBLE_REFERENCE_SURFACE".equals(eligibility);
	}

	private static boolean hitNativeScope(DamageRecord hit, WorldSnapshot impact)
	{
		return impact != null && Boolean.TRUE.equals(hit.getImpactPositionKnown()) && hit.getImpactSceneKey() != null
			&& !hit.getImpactSceneKey().trim().isEmpty() && hit.getImpactSceneKey().equals(impact.getSceneKey())
			&& hit.getImpactWorldViewId() != null && hit.getImpactWorldViewId() >= 0
			&& hit.getImpactWorldViewId().equals(impact.getWorldViewId()) && hit.getImpactPlane() != null
			&& hit.getImpactPlane() >= 0 && hit.getImpactPlane() <= 3 && hit.getImpactPlane() == impact.getPlane();
	}

	private static boolean callbackObjectContactQualified(Map<String, Object> contact, DamageRecord hit,
		WorldSnapshot impact)
	{
		return hitNativeScope(hit, impact) && sameContactScope(contact, impact)
			&& Arrays.asList("gameObject", "groundObject", "wallObject", "decorativeObject").contains(
				text(contact.get("objectKind")))
			&& "NATIVE_SCENE_TILE_MEMBERSHIP".equals(contact.get("scopeEvidence"))
			&& "SERVER_REPORTED_TILE".equals(contact.get("defenderPositionEvidence"))
			&& Boolean.TRUE.equals(contact.get("objectNativeScopeKnown"))
			&& hit.getImpactWorldViewId().equals(integer(contact.get("objectWorldViewId")))
			&& hit.getImpactPlane().equals(integer(contact.get("objectPlane")));
	}

	private static boolean recordedObjectScope(HazardState hazard, SceneData scene, WorldSnapshot impact,
		DamageRecord hit)
	{
		return hitNativeScope(hit, impact) && scene != null && hit.getImpactSceneKey().equals(scene.getSceneKey())
			&& hit.getImpactWorldViewId().equals(scene.getWorldViewId()) && scene.getPlane() == impact.getPlane()
			&& Boolean.TRUE.equals(hazard.getObjectNativeScopeKnown())
			&& hit.getImpactWorldViewId().equals(hazard.getObjectWorldViewId())
			&& hit.getImpactPlane().equals(hazard.getObjectPlane());
	}

	/** A retained label cannot bypass an exact structured source's native/outcome admission. */
	private boolean governedSurfaceWitness(DamageRecord hit, WorldSnapshot impact, String name)
	{
		if (name == null)
			return false;
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : hit.getObservedHazardContacts())
				if (contact != null && name.equals(text(contact.get("name")))
					&& requiresNativeObjectContact(integer(contact.get("objectId"))))
					return true;
		if (impact != null && impact.getHazards() != null)
			for (HazardState hazard : impact.getHazards())
				if (objectSurface(hazard) && name.equals(hazard.getMappedName())
					&& requiresNativeObjectContact(hazard.getId()))
					return true;
		return false;
	}

	private boolean requiresRecordedFootprint(Integer id)
	{
		DamageMap.ObjectHazardRule rule = map == null || id == null ? null : map.objectHazardRule(id);
		return rule != null && "CONDITIONAL_HP_OBJECT_FOOTPRINT".equals(rule.getSurfaceOperator());
	}

	private boolean requiresRecordedFootprint(HazardState hazard)
	{
		return hazard != null
			&& Arrays.asList("gameObject", "groundObject", "wallObject", "decorativeObject").contains(hazard.getKind())
			&& requiresRecordedFootprint(Integer.valueOf(hazard.getId()));
	}

	private boolean ordinaryObservedHp(DamageRecord hit)
	{
		return catalog != null && Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) && hitsplatObserved(hit)
			&& Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			&& catalog.hitsplatDamageType(hit.getHitsplatType()) == null;
	}

	private static boolean recordedFootprint(HazardState hazard, SceneData scene, String sceneKey)
	{
		return "gameObject".equals(hazard.getKind()) && scene != null && sceneKey != null
			&& sceneKey.equals(scene.getSceneKey()) && scene.getWorldViewId() != null
			&& scene.getWorldViewId().equals(hazard.getWorldViewId()) && scene.getPlane() == hazard.getPlane()
			&& Boolean.TRUE.equals(hazard.getObjectNativeScopeKnown())
			&& scene.getWorldViewId().equals(hazard.getObjectWorldViewId())
			&& Integer.valueOf(scene.getPlane()).equals(hazard.getObjectPlane()) && scene.getWidth() > 0
			&& scene.getHeight() > 0 && scene.getWidth() <= 104 && scene.getHeight() <= 104
			&& hazard.getMinSceneX() != null && hazard.getMaxSceneX() != null && hazard.getMinSceneY() != null
			&& hazard.getMaxSceneY() != null && hazard.getMinSceneX() >= 0 && hazard.getMinSceneY() >= 0
			&& hazard.getMinSceneX() <= hazard.getMaxSceneX() && hazard.getMinSceneY() <= hazard.getMaxSceneY()
			&& hazard.getMaxSceneX() < scene.getWidth() && hazard.getMaxSceneY() < scene.getHeight();
	}

	private static boolean sourceDefinitionBound(String value)
	{
		return "ELIGIBLE_REFERENCE_SURFACE".equals(value);
	}

	private boolean hitsplatObserved(DamageRecord hit)
	{
		if (Boolean.FALSE.equals(hit.getHitsplatTypeKnown()) || hit.getHitsplatType() < 0
			|| hit.getHitsplatType() == 0 && !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()))
			return false;
		if (hit.getCaptureIssues() != null)
			for (String issue : hit.getCaptureIssues())
				if (issue != null && (issue.startsWith("hitsplatType") || issue.startsWith("hitsplat.type")
					|| issue.startsWith("impact.hitsplatType")))
					return false;
		return true;
	}

	private String observedDamageType(DamageRecord hit)
	{
		if (catalog != null && hitsplatObserved(hit) && catalog.outcomeHitsplatKnown(hit.getHitsplatType()))
			return catalog.hitsplatDamageType(hit.getHitsplatType());
		return hit.getDamageType();
	}

	private LegacyConstraintOracle.Hypothesis outcomeCompatibility(LegacyConstraintOracle.Hypothesis hypothesis,
		DamageRecord hit, String kind, Integer sourceId, Map<String, Boolean> observed)
	{
		return outcomeCompatibility(hypothesis, hit, kind, sourceId, observed,
			"NPC_ORDINARY_ATTACK".equals(kind) ? "ORDINARY_ATTACK" : "ENVIRONMENT_CONTACT");
	}

	private LegacyConstraintOracle.Hypothesis outcomeCompatibility(LegacyConstraintOracle.Hypothesis hypothesis,
		DamageRecord hit, String kind, Integer sourceId, Map<String, Boolean> observed, String mechanism)
	{
		return outcomeCompatibility(hypothesis, hit, kind, sourceId, observed, mechanism, null);
	}

	private LegacyConstraintOracle.Hypothesis outcomeCompatibility(LegacyConstraintOracle.Hypothesis hypothesis,
		DamageRecord hit, String kind, Integer sourceId, Map<String, Boolean> observed, String mechanism,
		Map<String, Object> input)
	{
		if (catalog == null)
			return hypothesis;
		List<DamageOutcomeRule> rules = new ArrayList<>();
		for (DamageOutcomeRule rule : catalog.damageOutcomeRules(kind, sourceId))
			if (Objects.equals(mechanism, rule.getMechanism())
				&& (!"ITEM".equals(kind) || inputSelectorMatches(input, rule))
				&& (!"NPC_PROJECTILE_COMPONENT".equals(kind)
					|| input != null && rule.getProjectileIds().contains(integer(input.get("projectileId")))
						&& rule.getObservedNames().contains(text(input.get("observedName")))))
				rules.add(rule);
		if (rules.isEmpty())
			return hypothesis;
		List<String> evidence = hypothesis.getEligibilityEvidence() == null ? new ArrayList<>()
			: new ArrayList<>(hypothesis.getEligibilityEvidence());
		List<String> exclusions = new ArrayList<>();
		int type = hit.getHitsplatType();
		String surface = catalog.hitsplatSurface(type);
		if (!hitsplatObserved(hit) || !catalog.outcomeHitsplatKnown(type) || hit.getAmount() < 0 || surface == null
			|| "UNKNOWN".equals(surface) || "CONTEXTUAL_RESOURCE".equals(surface) || surface.contains("_OR_")
			|| "NPC_PROJECTILE_COMPONENT".equals(kind) && !Arrays.asList("HP", "BLOCK").contains(surface))
		{
			evidence
				.add("HITSPLAT_SIGNATURE_UNESTABLISHED: observed type/category unavailable or ambiguous; type=" + type);
			return hypothesis.toBuilder().eligibilityEvidence(evidence).build();
		}
		boolean allComplete = true, matched = false;
		for (DamageOutcomeRule rule : rules)
		{
			List<String> missing = new ArrayList<>();
			for (String required : rule.getRequiredObservations())
				if (!("ITEM".equals(kind) && "QUALIFIED_SELF_INPUT".equals(required)
					? qualifiedSelfInput(input, hit, rule)
					: Boolean.TRUE.equals(observed.get(required))))
					missing.add(required);
			if (!rule.isComplete() || !missing.isEmpty())
			{
				allComplete = false;
				evidence.add("HITSPLAT_SIGNATURE_UNESTABLISHED:" + rule.getKey() + ":" + String.join(",", missing));
				continue;
			}
			List<Integer> allowed = hit.getAmount() == 0 ? rule.getZeroHitsplatTypes()
				: rule.getPositiveHitsplatTypes();
			if (allowed == null)
			{
				allComplete = false;
				evidence.add("HITSPLAT_SIGNATURE_UNESTABLISHED:" + rule.getKey() + ":ZERO_OUTCOME_DOMAIN_UNAVAILABLE");
				continue;
			}
			boolean compatible = allowed.contains(type);
			evidence.add("HITSPLAT_SIGNATURE:" + rule.getKey() + ":mechanism=" + rule.getMechanism() + ";observed="
				+ type + ";amount=" + hit.getAmount() + ";allowed=" + allowed);
			if (compatible)
				matched = true;
			else
				exclusions.add("HITSPLAT_TYPE_MISMATCH:" + rule.getKey() + ":observed=" + type + ";amount="
					+ hit.getAmount() + ";allowed=" + allowed);
		}
		List<String> combined = hypothesis.getEligibilityExclusions() == null ? new ArrayList<>()
			: new ArrayList<>(hypothesis.getEligibilityExclusions());
		if (allComplete && !matched)
			combined.addAll(exclusions);
		return hypothesis.toBuilder().eligibilityEvidence(evidence).eligibilityExclusions(combined).build();
	}

	private boolean qualifiedSelfInput(Map<String, Object> input, DamageRecord hit, DamageOutcomeRule outcome)
	{
		return qualifiedSelfInput(input, hit, outcome.getInputSourceRef(), outcome.getInputOperations());
	}

	private boolean qualifiedSelfInput(Map<String, Object> input, DamageRecord hit, String sourceRef,
		List<String> operations)
	{
		if (input == null || catalog == null || sourceRef == null || operations == null || operations.isEmpty()
			|| !"SELF_DAMAGE_INPUT_CANDIDATE".equals(text(input.get("kind"))) || !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
			|| !"INPUT_CORRELATION_NOT_EXECUTION_PROOF".equals(text(input.get("evidence")))
			|| !Objects.equals(sourceRef, text(input.get("sourceRef"))))
			return false;
		Long itemNumber = exactInputNumber(input.get("itemId")), tickNumber = exactInputNumber(input.get("inputTick"));
		Integer item = itemNumber == null || itemNumber < 0 || itemNumber > Integer.MAX_VALUE ? null
			: itemNumber.intValue();
		Integer tick = tickNumber == null || tickNumber < 0 || tickNumber > Integer.MAX_VALUE ? null
			: tickNumber.intValue();
		Long sequence = exactInputNumber(input.get("inputSequence"));
		String option = text(input.get("option")), hash = text(input.get("pageHash"));
		if (item == null || item <= 0 || tick == null || tick < 0 || tick > hit.getImpactTick() || sequence == null
			|| sequence <= 0 || hit.getObservationSequence() == null || sequence >= hit.getObservationSequence()
			|| option == null || hash == null || !hash.matches("[0-9a-f]{64}")
			|| operations.stream().noneMatch(value -> value != null && value.equalsIgnoreCase(option)))
			return false;
		int scanned = 0;
		for (com.google.gson.JsonElement value : catalog.selfDamageInputs())
		{
			if (++scanned > 4096)
				return false;
			try
			{
				com.google.gson.JsonObject rule = value.getAsJsonObject();
				if (rule.has("observationKind") || !rule.has("page") || !rule.has("pageHash")
					|| !sourceRef.equals("wiki:" + rule.get("page").getAsString())
					|| !hash.equals(rule.get("pageHash").getAsString()))
					continue;
				int window = rule.get("candidateWindowTicks").getAsInt();
				if (window < 0 || window > 5000 || (long) hit.getImpactTick() - tick > window)
					continue;
				com.google.gson.JsonElement ids = rule.has("candidateItemIds") ? rule.get("candidateItemIds")
					: rule.get("itemIds");
				boolean itemMatches = false, operationMatches = false;
				for (com.google.gson.JsonElement id : ids.getAsJsonArray())
					if (id.getAsInt() == item)
						itemMatches = true;
				for (com.google.gson.JsonElement operation : rule.getAsJsonArray("candidateOperations"))
					if (operation.getAsString().equalsIgnoreCase(option))
						operationMatches = true;
				if (itemMatches && operationMatches)
					return true;
			}
			catch (RuntimeException unavailable)
			{
				/* Missing input context cannot exclude an outcome. */ }
		}
		return false;
	}

	private static boolean inputSelectorMatches(Map<String, Object> input, DamageOutcomeRule rule)
	{
		String option = input == null ? null : text(input.get("option"));
		return option != null && Objects.equals(rule.getInputSourceRef(), text(input.get("sourceRef")))
			&& rule.getInputOperations() != null
			&& rule.getInputOperations().stream().anyMatch(value -> value.equalsIgnoreCase(option));
	}

	private static Long exactInputNumber(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new java.math.BigDecimal(value.toString()).longValueExact();
		}
		catch (NumberFormatException | ArithmeticException unavailable)
		{
			return null;
		}
	}

	private String objectFamily(int id)
	{
		DamageMap.ObjectHazardRule rule = map == null ? null : map.objectHazardRule(id);
		return rule == null || rule.getKey() == null ? "object-definition:" + id : "object-rule:" + rule.getKey();
	}

	private static Candidate candidate(String key, String name, WorldSnapshot frame, String family, String evidence,
		String strength)
	{
		return Candidate.builder().key(key).name(name).familyKey(family)
			.sceneKey(frame == null ? null : frame.getSceneKey())
			.worldViewId(frame == null ? null : frame.getWorldViewId()).plane(frame == null ? null : frame.getPlane())
			.strength(strength)
			.evidence(Collections.singletonList(evidence == null ? "RETAINED_SOURCE_HYPOTHESIS" : evidence)).build();
	}

	private static LegacyConstraintOracle.Hypothesis plain(Candidate candidate)
	{
		return LegacyConstraintOracle.Hypothesis.builder().candidate(candidate).causalAssociationOnly(true).build();
	}

	private static NpcState findNpc(WorldSnapshot frame, String ref)
	{
		if (frame != null && ref != null && frame.getNpcs() != null)
			for (NpcState npc : frame.getNpcs())
				if (npc != null && ref.equals(npc.getActorRef()))
					return npc;
		return null;
	}

	private static String text(Object value)
	{
		return value instanceof String ? (String) value : null;
	}

	private static Long number(Object value)
	{
		return value instanceof Number ? ((Number) value).longValue() : null;
	}

	private static Integer integer(Object value)
	{
		return value instanceof Number ? ((Number) value).intValue() : null;
	}

	private static boolean unknown(String value)
	{
		return com.tickreplaylogger.util.SourceResolutionLabels.usableName(value) == null;
	}
}
