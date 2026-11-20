/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable callback cue index. Removal never creates a cue or replaces its original position. */
public final class ObservedActorMechanismResolver
{
	private static final int MAX_INPUT = 65536;
	private static final int MAX_CUES = 4096;
	private final MechanismRules rules;
	private final List<Cue> cues = new ArrayList<>();
	private final List<Cue> unclassifiedCues = new ArrayList<>();
	private final List<Seen> seen = new ArrayList<>();
	private final Map<Integer, List<WorldSnapshot>> frames = new HashMap<>();
	private final Set<Integer> gapTicks = new HashSet<>();
	private final boolean eventsKnown;
	private final LegacyObservedHpCallbackContext hpContexts;
	private final boolean retainedWindowComplete;
	private boolean truncated;

	private static final class Seen
	{
		final int id, tick, plane;
		final String scene;
		final Integer view;

		Seen(int id, WorldSnapshot frame)
		{
			this.id = id;
			tick = frame.getTick();
			plane = frame.getPlane();
			scene = frame.getSceneKey();
			view = frame.getWorldViewId();
		}
	}

	private static final class Cue
	{
		final ObservedEvent event;
		final int id;
		final Integer level, view, plane, x, y, minX, minY, maxX, maxY, areaPlane, animation;
		final String name, definition;
		final Boolean normal, overrides;
		final Set<String> issueKeys;
		final boolean positionKnown, areaKnown, actorKnown, readsKnown, scopeReadsKnown, weakReadsKnown,
			weakPointReadsKnown, weakAreaReadsKnown;

		Cue(ObservedEvent event, int id)
		{
			this.event = event;
			this.id = id;
			Map<String, Object> data = event.getData();
			level = integer(data.get("combatLevel"));
			view = integer(data.get("worldViewId"));
			plane = Boolean.TRUE.equals(data.get("planeKnown")) ? integer(data.get("plane")) : null;
			x = integer(data.get("worldX"));
			y = integer(data.get("worldY"));
			Map<?, ?> area = data.get("worldArea") instanceof Map ? (Map<?, ?>) data.get("worldArea")
				: Collections.emptyMap();
			minX = integer(area.get("minX"));
			minY = integer(area.get("minY"));
			maxX = integer(area.get("maxX"));
			maxY = integer(area.get("maxY"));
			areaPlane = integer(area.get("plane"));
			areaKnown = Boolean.TRUE.equals(data.get("worldAreaKnown"))
				&& Boolean.TRUE.equals(data.get("worldAreaWorldViewKnown")) && view != null
				&& view.equals(integer(data.get("worldAreaWorldViewId"))) && areaPlane != null
				&& (plane == null || plane.equals(areaPlane)) && minX != null && minY != null && maxX != null
				&& maxY != null && minX <= maxX && minY <= maxY && (long) maxX - minX < 104 && (long) maxY - minY < 104;
			animation = integer(data.get("animationId"));
			name = text(data.get("name"));
			definition = text(data.get("definitionEvidence"));
			normal = bool(data.get("normalWorldContextObserved"));
			overrides = bool(data.get("modelOverridesPresent"));
			positionKnown = Boolean.TRUE.equals(data.get("worldPositionKnown"))
				&& Boolean.TRUE.equals(data.get("planeKnown")) && x != null && y != null
				&& !Boolean.FALSE.equals(data.get("scopeKnown"));
			actorKnown = "NPC".equals(data.get("actorKind")) && text(event.getActorRef()) != null;
			Object failures = data.get("observationIssues");
			Set<String> keys = new HashSet<>();
			if (failures instanceof Map)
				for (Object key : ((Map<?, ?>) failures).keySet())
					if (key instanceof String)
						keys.add((String) key);
			issueKeys = Collections.unmodifiableSet(keys);
			boolean known = true;
			if (failures instanceof Map)
				for (String key : Arrays.asList("name", "combatLevel", "definitionId", "transformedComposition",
					"definitionContext", "modelOverrides"))
					known &= !((Map<?, ?>) failures).containsKey(key);
			readsKnown = known;
			scopeReadsKnown = !(failures instanceof Map) || !((Map<?, ?>) failures).containsKey("worldView")
				&& !((Map<?, ?>) failures).containsKey("worldViewId");
			weakReadsKnown = (failures == null || failures instanceof Map)
				&& !Boolean.FALSE.equals(data.get("scopeKnown"));
			weakPointReadsKnown = !geometryIssue(failures, false);
			weakAreaReadsKnown = !geometryIssue(failures, true);
		}
	}

	public ObservedActorMechanismResolver(MechanismRules rules, List<WorldSnapshot> history, List<ObservedEvent> events,
		List<DeathRecordingV2.Events.CaptureGap> gaps)
	{
		this(rules, history, events, gaps, new LegacyObservedHpCallbackContext(events));
	}

	public ObservedActorMechanismResolver(MechanismRules rules, List<WorldSnapshot> history, List<ObservedEvent> events,
		List<DeathRecordingV2.Events.CaptureGap> gaps, LegacyObservedHpCallbackContext hpContexts)
	{
		this(rules, history, events, gaps, hpContexts, true);
	}

	public ObservedActorMechanismResolver(MechanismRules rules, List<WorldSnapshot> history, List<ObservedEvent> events,
		List<DeathRecordingV2.Events.CaptureGap> gaps, LegacyObservedHpCallbackContext hpContexts,
		boolean retainedWindowComplete)
	{
		this.rules = rules;
		this.hpContexts = hpContexts;
		this.retainedWindowComplete = retainedWindowComplete;
		eventsKnown = events != null && gaps != null;
		if (!rules.hasActorRules())
			return;
		int count = 0;
		if (events != null)
			for (ObservedEvent event : events)
			{
				if (++count > MAX_INPUT)
				{
					truncated = true;
					break;
				}
				if (event == null || event.getData() == null)
				{
					truncated = true;
					continue;
				}
				if (!Arrays.asList("ANIMATION_CHANGED", "ACTOR_DEATH", "NPC_DESPAWNED").contains(event.getType()))
					continue;
				Integer id = integer(event.getData().get("npcId"));
				boolean unclassified = id == null && "NPC".equals(event.getData().get("actorKind"));
				if (!unclassified && (id == null || rules.actorMatching(id).isEmpty() && !rules.actorIncomplete(id)))
					continue;
				if (cues.size() + unclassifiedCues.size() >= MAX_CUES)
				{
					truncated = true;
					break;
				}
				// Freeze fields: exported event containers may be subsequently reused by callers.
				ObservedEvent frozen = ObservedEvent.builder().tick(event.getTick()).gameCycle(event.getGameCycle())
					.sequence(event.getSequence()).type(event.getType()).actorRef(event.getActorRef())
					.actorName(event.getActorName()).sceneKey(event.getSceneKey())
					.data(Collections.unmodifiableMap(new HashMap<>(event.getData()))).build();
				if (unclassified)
					unclassifiedCues.add(new Cue(frozen, -1));
				else
					cues.add(new Cue(frozen, id));
			}
		count = 0;
		if (rules.hasOrderedActorRules() && history != null)
			for (WorldSnapshot frame : history)
			{
				if (++count > MAX_INPUT)
				{
					truncated = true;
					break;
				}
				if (frame == null)
				{
					truncated = true;
					continue;
				}
				frames.computeIfAbsent(frame.getTick(), ignored -> new ArrayList<>()).add(frame);
				if (frame.getNpcs() == null)
					continue;
				for (NpcState npc : frame.getNpcs())
				{
					if (++count > MAX_INPUT)
					{
						truncated = true;
						break;
					}
					if (npc != null && (npc.getWorldViewId() != null && frame.getWorldViewId() != null
						&& !npc.getWorldViewId().equals(frame.getWorldViewId())
						|| Boolean.TRUE.equals(npc.getPlaneKnown()) && npc.getPlane() != frame.getPlane()))
						continue;
					if (npc != null && npc.getNpcId() != null
						&& (!rules.actorMatching(npc.getNpcId()).isEmpty() || rules.actorIncomplete(npc.getNpcId())))
						seen.add(new Seen(npc.getNpcId(), frame));
				}
				if (truncated)
					break;
			}
		count = 0;
		if (gaps != null)
			for (DeathRecordingV2.Events.CaptureGap gap : gaps)
			{
				if (++count > MAX_INPUT)
				{
					truncated = true;
					break;
				}
				if (gap == null)
				{
					truncated = true;
					continue;
				}
				if ("rawCallbacks".equals(gap.getChannel()) && gap.getOmittedObservations() > 0)
					gapTicks.add(gap.getTick());
			}
	}

	public ObservedMechanismResolver.Result resolve(DamageRecord hit)
	{
		List<ObservedMechanismResolver.Contribution> output = new ArrayList<>();
		if (!rules.hasActorRules())
			return new ObservedMechanismResolver.Result(output, false);
		boolean unknown = rules.actorUnavailable();
		Set<MechanismRules.ActorRule> relevant = new LinkedHashSet<>();
		for (Seen npc : seen)
			if (npc.tick <= hit.getImpactTick() && possiblySameScope(npc.scene, npc.view, npc.plane, hit))
			{
				unknown |= rules.actorIncomplete(npc.id);
				for (MechanismRules.ActorRule rule : rules.actorMatching(npc.id))
					if (!rule.isStrictLifecycle()
						&& (long) hit.getImpactTick() - npc.tick <= rule.getMaximumDelayTicks())
						relevant.add(rule);
			}
		ObservedMechanismResolver.Result lifecycle = lifecycle(hit);
		output.addAll(lifecycle.getContributions());
		unknown |= lifecycle.isUnidentifiedPossible();
		if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
			return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), true);
		ObservedMechanismResolver.Result retained = lifecycle(hit, true);
		for (ObservedMechanismResolver.Contribution contribution : retained.getContributions())
		{
			output.add(contribution);
			if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
				return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), true);
		}
		unknown |= retained.isUnidentifiedPossible();
		if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
			return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), true);
		int work = 0;
		for (Cue cue : cues)
		{
			if (!possiblySameScope(cue.event.getSceneKey(), cue.scopeReadsKnown ? cue.view : null, cue.plane, hit)
				|| cue.event.getTick() > hit.getImpactTick())
				continue;
			unknown |= rules.actorIncomplete(cue.id);
			for (MechanismRules.ActorRule rule : rules.actorMatching(cue.id))
			{
				if (rule.isStrictLifecycle())
					continue;
				if (++work > MAX_INPUT)
					return new ObservedMechanismResolver.Result(output, true);
				long delay = (long) hit.getImpactTick() - cue.event.getTick();
				if (delay > rule.getMaximumDelayTicks())
					continue;
				relevant.add(rule);
				if (delay < rule.getMinimumDelayTicks() || !rule.getCueType().equals(cue.event.getType()))
					continue;
				if ("ANIMATION_CHANGED".equals(rule.getCueType()) && cue.animation != null
					&& !rule.getAnimationIds().contains(cue.animation))
					continue;
				if (cue.name != null && !rule.getNames().contains(cue.name)
					|| cue.level != null && !rule.getCombatLevels().contains(cue.level)
					|| Boolean.FALSE.equals(cue.normal) || Boolean.TRUE.equals(cue.overrides))
					continue;
				boolean definition = cue.name != null && cue.level != null && cue.readsKnown
					&& Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(cue.definition)
					&& Boolean.TRUE.equals(cue.normal) && Boolean.FALSE.equals(cue.overrides);
				boolean bounds = "SOURCE_BOUNDS_EXPANDED".equals(rule.getFootprint());
				Integer sourcePlane = bounds ? cue.areaPlane : cue.plane;
				boolean scope = cue.scopeReadsKnown && cue.event.getSceneKey() != null && cue.view != null
					&& sourcePlane != null && cue.event.getSceneKey().equals(hit.getImpactSceneKey())
					&& cue.view.equals(hit.getImpactWorldViewId()) && sourcePlane.equals(hit.getImpactPlane());
				boolean geometry = scope && Boolean.TRUE.equals(hit.getImpactPositionKnown())
					&& (bounds ? cue.areaKnown : cue.positionKnown);
				if (geometry && outside(rule, cue, hit))
					continue;
				int order = cueOrder(cue, hit);
				if (order > 0)
					continue;
				boolean cueKnown = cue.actorKnown && cue.event.getSequence() > 0 && order < 0
					&& (!"ANIMATION_CHANGED".equals(rule.getCueType()) || cue.animation != null);
				unknown |= !definition || !scope || !geometry || !cueKnown;
				List<String> evidence = new ArrayList<>();
				evidence.add("ACTOR_MECHANISM_RULE:" + rule.getKey() + ";component=" + rule.getComponent()
					+ ";delivery=" + rule.getDelivery());
				evidence.add("OBSERVED_CUE:" + cue.event.getType() + ";sequence=" + cue.event.getSequence() + ";tick="
					+ cue.event.getTick() + ";cycle=" + cue.event.getGameCycle() + ";delayTicks=" + delay
					+ ";origin=SOURCE_AT_CUE");
				evidence.add(
					"Recorded cue and footprint correlation, not an observed server cause; no local-target prerequisite");
				if (!definition)
					evidence.add("ACTOR_MECHANISM_CONDITION_UNESTABLISHED:SOURCE_DEFINITION_BOUND");
				if (!scope)
					evidence.add("ACTOR_MECHANISM_CONDITION_UNESTABLISHED:SAME_NATIVE_SCOPE");
				if (!geometry)
					evidence.add("ACTOR_MECHANISM_CONDITION_UNESTABLISHED:RECORDED_GEOMETRY");
				if (!cueKnown)
					evidence.add("ACTOR_MECHANISM_CONDITION_UNESTABLISHED:CUE_BEFORE_HIT");
				for (String reference : rule.getReferenceKeys())
					evidence.add("REFERENCE_KEY:" + reference);
				SourceResolution.Candidate candidate = SourceResolution.Candidate.builder()
					.key("actor-mechanism:" + rule.getKey() + ":" + cue.event.getSequence()).name(rule.getLabel())
					.actorRef(cue.event.getActorRef()).npcId(cue.id)
					.familyKey(cue.actorKnown ? null : "npc-definition:" + cue.id).sceneKey(cue.event.getSceneKey())
					.worldViewId(cue.view).plane(sourcePlane).strength("POSSIBLE")
					.evidence(Collections.unmodifiableList(evidence)).build();
				LegacyConstraintOracle.Hypothesis hypothesis = LegacyConstraintOracle.Hypothesis.builder()
					.candidate(candidate).mechanism(rule.getDelivery()).damageComponent(rule.getComponent())
					.causalAssociationOnly(true).build();
				output.add(new ObservedMechanismResolver.Contribution(hypothesis, "NPC", cue.id, definition));
				if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
					return new ObservedMechanismResolver.Result(output, true);
			}
		}
		if (!relevant.isEmpty())
		{
			unknown |= truncated || !eventsKnown;
			for (MechanismRules.ActorRule rule : relevant)
			{
				for (Cue cue : unclassifiedCues)
				{
					if (++work > MAX_INPUT)
						return new ObservedMechanismResolver.Result(output, true);
					long delay = (long) hit.getImpactTick() - cue.event.getTick();
					if (delay >= rule.getMinimumDelayTicks() && delay <= rule.getMaximumDelayTicks()
						&& rule.getCueType().equals(cue.event.getType()) && cueOrder(cue, hit) <= 0
						&& possiblySameScope(cue.event.getSceneKey(), cue.scopeReadsKnown ? cue.view : null, cue.plane,
							hit)
						&& (!"ANIMATION_CHANGED".equals(rule.getCueType()) || cue.animation == null
							|| rule.getAnimationIds().contains(cue.animation)))
						unknown = true;
				}
				for (long tick = (long) hit.getImpactTick()
					- rule.getMaximumDelayTicks(); tick <= (long) hit.getImpactTick()
						- rule.getMinimumDelayTicks(); tick++)
				{
					if (++work > MAX_INPUT)
						return new ObservedMechanismResolver.Result(output, true);
					if (tick < Integer.MIN_VALUE || tick > Integer.MAX_VALUE || gapTicks.contains((int) tick)
						|| !frameCovered((int) tick, hit))
						unknown = true;
				}
			}
		}
		else if (truncated)
			unknown = true;
		return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), unknown);
	}

	/** Explicit weak policy: callbacks share a recorded tick, never an inferred server event order. */
	private ObservedMechanismResolver.Result lifecycle(DamageRecord hit)
	{
		return lifecycle(hit, false);
	}

	/** A retained window is an explicit analysis policy, not a server delay or a future timer. */
	private ObservedMechanismResolver.Result lifecycle(DamageRecord hit, boolean retained)
	{
		List<ObservedMechanismResolver.Contribution> output = new ArrayList<>();
		if (retained && !rules.hasRetainedLifecycleRules())
			return new ObservedMechanismResolver.Result(output, false);
		if (hit == null || !"RECEIVED".equals(hit.getDirection()) || !"player".equals(hit.getDefenderRef())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) || hit.getAmount() < 0
			|| hit.getObservationSequence() == null || hit.getObservationSequence() <= 0 || hit.getImpactTick() < 0
			|| hit.getObservedCycle() == null || hit.getObservedCycle() < 0 || hpContexts == null
			|| !hpContexts.positionMatches(hit) || !LegacyObservedHpCallbackContext.normal(hpContexts.forHit(hit),
				Collections.singletonList("LEAGUE_TYPE"), Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA")))
			return new ObservedMechanismResolver.Result(output, false);
		if (retained && (!retainedWindowComplete || truncated || !eventsKnown))
			return new ObservedMechanismResolver.Result(output, true);
		if (retained)
			for (int offset = 0; offset <= rules.maximumRetainedLifecycleDelay(); offset++)
				if (gapTicks.contains(hit.getImpactTick() - offset))
					return new ObservedMechanismResolver.Result(output, true);
		Map<String, SourceResolution.Candidate> grouped = new java.util.LinkedHashMap<>();
		Map<String, MechanismRules.ActorRule> components = new HashMap<>();
		Map<String, Integer> definitions = new HashMap<>();
		Map<String, Integer> maxima = new HashMap<>();
		Set<Long> sequences = new HashSet<>(), duplicateSequences = new HashSet<>();
		for (Cue cue : cues)
			if (!sequences.add(cue.event.getSequence()))
				duplicateSequences.add(cue.event.getSequence());
		int work = 0;
		boolean unestablished = false;
		for (Cue cue : cues)
		{
			if (!retained && cue.event.getTick() != hit.getImpactTick())
				continue;
			for (MechanismRules.ActorRule rule : rules.actorMatching(cue.id))
			{
				if (++work > MAX_INPUT)
					return new ObservedMechanismResolver.Result(output, true);
				if (!(retained ? rule.isRetainedLifecycleWindow() : rule.isLifecycleCooccurrence())
					|| !rule.getCueType().equals(cue.event.getType())
					|| !rule.getObservedHitsplatTypes().contains(hit.getHitsplatType())
					|| !possiblyQualifiedLifecycle(rule, cue, hit))
					continue;
				long delay = (long) hit.getImpactTick() - cue.event.getTick();
				if (retained)
				{
					if (cue.event.getTick() < 0 || delay < rule.getMinimumDelayTicks()
						|| delay > rule.getMaximumDelayTicks() || cueOrder(cue, hit) != -1)
						continue;
				}
				if (cue.event.getSequence() <= 0 || duplicateSequences.contains(cue.event.getSequence())
					|| cue.event.getSequence() == hit.getObservationSequence() || cue.event.getGameCycle() == null
					|| cue.event.getGameCycle() < 0 || !cue.actorKnown || "player".equals(cue.event.getActorRef())
					|| !cue.readsKnown || !cue.scopeReadsKnown || !cue.weakReadsKnown
					|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(cue.definition)
					|| cue.name == null || cue.level == null || !Boolean.TRUE.equals(cue.normal)
					|| !Boolean.FALSE.equals(cue.overrides))
				{
					unestablished = true;
					continue;
				}
				boolean bounds = "SOURCE_BOUNDS_EXPANDED".equals(rule.getFootprint());
				Integer plane = bounds ? cue.areaPlane : cue.plane;
				if (cue.view == null || cue.view < 0 || plane == null || plane < 0 || plane > 3
					|| cue.event.getSceneKey() == null || cue.event.getSceneKey().trim().isEmpty()
					|| !cue.event.getSceneKey().equals(hit.getImpactSceneKey())
					|| !cue.view.equals(hit.getImpactWorldViewId()) || !plane.equals(hit.getImpactPlane())
					|| !(bounds ? cue.areaKnown : cue.positionKnown)
					|| !(bounds ? cue.weakAreaReadsKnown : cue.weakPointReadsKnown))
				{
					unestablished = true;
					continue;
				}
				if (outside(rule, cue, hit))
					continue;
				String key = (retained ? "actor-retained-lifecycle:" : "actor-lifecycle:") + rule.getComponent() + ":"
					+ cue.id + ":" + cue.event.getActorRef() + ":" + cue.event.getSceneKey() + ":" + cue.view + ":"
					+ plane + ":" + cue.event.getTick();
				SourceResolution.Candidate previous = grouped.get(key);
				List<String> evidence = new ArrayList<>(
					previous == null ? Collections.emptyList() : previous.getEvidence());
				evidence.add((retained ? "RETAINED_LIFECYCLE_WINDOW_RULE:" : "LIFECYCLE_COOCCURRENCE_RULE:")
					+ rule.getKey() + ";component=" + rule.getComponent());
				if (rule.getPerHitMaximum() != null)
					evidence
						.add("REVIEWED_TERMINAL_PER_CALLBACK_MAXIMUM:" + rule.getKey() + ":" + rule.getPerHitMaximum());
				evidence.add("RECORDED_LIFECYCLE:" + cue.event.getType() + ";sequence=" + cue.event.getSequence()
					+ ";tick=" + cue.event.getTick() + ";cycle=" + cue.event.getGameCycle() + ";sourcePoint=" + cue.x
					+ "," + cue.y + ";sourceBounds=" + cue.minX + "," + cue.minY + "," + cue.maxX + "," + cue.maxY);
				evidence.add("ORIGINAL_HP_CALLBACK:sequence=" + hit.getObservationSequence() + ";cycle="
					+ hit.getObservedCycle());
				evidence.add(retained ? "Recorded-tick analysis window " + rule.getMinimumDelayTicks() + ".."
					+ rule.getMaximumDelayTicks() + ";delayTicks=" + delay
					+ "; possible source vicinity at original cue, not an observed server footprint or impact time. Activation and HP cause remain unestablished."
					: "Same recorded tick only; source geometry is observed at lifecycle callback, not necessarily at HP callback."
						+ " Removal reason, activation, server order and HP cause remain unestablished.");
				for (String reference : rule.getReferenceKeys())
					evidence.add("REFERENCE_KEY:" + reference);
				grouped.put(key,
					SourceResolution.Candidate.builder().key(key).name(rule.getLabel())
						.actorRef(cue.event.getActorRef()).npcId(cue.id).sceneKey(cue.event.getSceneKey())
						.worldViewId(cue.view).plane(plane).strength("POSSIBLE")
						.evidence(Collections.unmodifiableList(evidence)).build());
				components.put(key, rule);
				definitions.put(key, cue.id);
				Integer earlierMaximum = maxima.get(key), maximum = rule.getPerHitMaximum();
				maxima.put(key, previous == null ? maximum
					: earlierMaximum == null || maximum == null ? null : Math.max(earlierMaximum, maximum));
				if (grouped.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
					break;
			}
			if (grouped.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
				break;
		}
		for (Map.Entry<String, SourceResolution.Candidate> entry : grouped.entrySet())
		{
			MechanismRules.ActorRule rule = components.get(entry.getKey());
			LegacyConstraintOracle.Hypothesis.HypothesisBuilder hypothesis = LegacyConstraintOracle.Hypothesis.builder()
				.candidate(entry.getValue()).mechanism("TERMINAL_AREA").damageComponent(rule.getComponent())
				.worldMode("NORMAL").causalAssociationOnly(true);
			Integer maximum = maxima.get(entry.getKey());
			if (maximum != null)
			{
				Map<String, Boolean> observed = new HashMap<>();
				for (String observation : rule.getRequiredObservations())
					observed.put(observation, true);
				hypothesis.observations(Collections.unmodifiableMap(observed))
					.constraints(Collections.singletonList(AttributionConstraint.builder()
						.key("lifecycle-per-callback:" + entry.getKey()).maximum(maximum).qualification("HARD_BOUND")
						.composition("ALTERNATIVE_CASE").numericScope("PER_CALLBACK_MAXIMUM").mechanism("TERMINAL_AREA")
						.damageComponent(rule.getComponent()).worldMode("NORMAL")
						.requiredObservations(rule.getRequiredObservations()).build()));
			}
			output.add(new ObservedMechanismResolver.Contribution(hypothesis.build(), "NPC",
				definitions.get(entry.getKey()), true));
		}
		return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output),
			unestablished || truncated || !eventsKnown || gapTicks.contains(hit.getImpactTick()));
	}

	private static boolean possiblyQualifiedLifecycle(MechanismRules.ActorRule rule, Cue cue, DamageRecord hit)
	{
		Integer view = cue.scopeReadsKnown && cue.weakReadsKnown && cue.view != null && cue.view >= 0 ? cue.view : null;
		boolean bounds = "SOURCE_BOUNDS_EXPANDED".equals(rule.getFootprint());
		Integer plane = bounds ? cue.areaKnown && cue.weakAreaReadsKnown ? cue.areaPlane : null
			: cue.positionKnown && cue.weakPointReadsKnown ? cue.plane : null;
		if (plane != null && (plane < 0 || plane > 3))
			plane = null;
		return possiblySameScope(cue.event.getSceneKey(), view, plane, hit)
			&& (cue.name == null || cue.issueKeys.contains("name")
				|| rule.getNames().contains(com.tickreplaylogger.util.SourceResolutionLabels.usableName(cue.name)))
			&& (cue.level == null || cue.issueKeys.contains("combatLevel")
				|| rule.getCombatLevels().contains(cue.level))
			&& (!Boolean.FALSE.equals(cue.normal) || contextIssue(cue))
			&& (!Boolean.TRUE.equals(cue.overrides) || cue.issueKeys.contains("modelOverrides"));
	}

	private static boolean contextIssue(Cue cue)
	{
		for (String key : cue.issueKeys)
			if (key.startsWith("normalWorld") || key.startsWith("worldTypes") || key.startsWith("LEAGUE_TYPE")
				|| key.startsWith("definitionContext") || key.startsWith("modelOverrides")
				|| key.startsWith("callback"))
				return true;
		return false;
	}

	private static boolean geometryIssue(Object value, boolean bounds)
	{
		if (value == null)
			return false;
		if (!(value instanceof Map))
			return true;
		for (Object field : ((Map<?, ?>) value).keySet())
		{
			if (!(field instanceof String))
				return true;
			String key = (String) field;
			if (key.startsWith("worldView") || key.startsWith("definitionContext") || key.startsWith("callback")
				|| key.startsWith("normalWorld") || key.startsWith("worldTypes") || key.startsWith("LEAGUE_TYPE"))
				return true;
			if (bounds ? key.startsWith("worldArea") : key.startsWith("worldPosition") || key.startsWith("plane"))
				return true;
		}
		return false;
	}

	private boolean frameCovered(int tick, DamageRecord hit)
	{
		WorldSnapshot found = null;
		for (WorldSnapshot frame : frames.getOrDefault(tick, Collections.emptyList()))
			if (Objects.equals(frame.getSceneKey(), hit.getImpactSceneKey())
				&& Objects.equals(frame.getWorldViewId(), hit.getImpactWorldViewId()) && hit.getImpactPlane() != null
				&& frame.getPlane() == hit.getImpactPlane())
			{
				if (found != null)
					return false;
				found = frame;
			}
		return found != null && hit.getImpactSceneKey() != null && hit.getImpactWorldViewId() != null
			&& Boolean.TRUE.equals(found.getNativeContextKnown()) && found.getNpcs() != null
			&& found.getCaptureCoverage() != null && "available".equals(found.getCaptureCoverage().get("npcs"));
	}

	private static int cueOrder(Cue cue, DamageRecord hit)
	{
		Integer cycle = cue.event.getGameCycle();
		Long hitSequence = hit.getObservationSequence();
		if (cue.event.getTick() < hit.getImpactTick())
			return cycle != null && hit.getObservedCycle() != null && cycle > hit.getObservedCycle()
				|| hitSequence != null && cue.event.getSequence() >= hitSequence ? 0 : -1;
		if (hitSequence != null && cue.event.getSequence() > 0)
		{
			if (cue.event.getSequence() >= hitSequence)
				return 1;
			return cycle != null && hit.getObservedCycle() != null && cycle > hit.getObservedCycle() ? 0 : -1;
		}
		return cycle != null && hit.getObservedCycle() != null && cycle < hit.getObservedCycle() ? -1 : 0;
	}

	private static boolean outside(MechanismRules.ActorRule rule, Cue cue, DamageRecord hit)
	{
		boolean bounds = "SOURCE_BOUNDS_EXPANDED".equals(rule.getFootprint());
		long minX = (long) (bounds ? cue.minX : cue.x) - rule.getRadius(),
			minY = (long) (bounds ? cue.minY : cue.y) - rule.getRadius(),
			maxX = (long) (bounds ? cue.maxX : cue.x) + rule.getRadius(),
			maxY = (long) (bounds ? cue.maxY : cue.y) + rule.getRadius();
		return hit.getImpactX() < minX || hit.getImpactX() > maxX || hit.getImpactY() < minY || hit.getImpactY() > maxY;
	}

	private static boolean possiblySameScope(String scene, Integer view, Integer plane, DamageRecord hit)
	{
		return !(scene != null && hit.getImpactSceneKey() != null && !scene.equals(hit.getImpactSceneKey())
			|| view != null && hit.getImpactWorldViewId() != null && !view.equals(hit.getImpactWorldViewId())
			|| plane != null && hit.getImpactPlane() != null && !plane.equals(hit.getImpactPlane()));
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
	}

	private static Boolean bool(Object value)
	{
		return value instanceof Boolean ? (Boolean) value : null;
	}
}
