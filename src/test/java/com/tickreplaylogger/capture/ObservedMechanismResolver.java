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
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lombok.Value;

/** Applies approved primitive geometry/lifetime declarations to observations, never future events. */
public final class ObservedMechanismResolver
{
	private static final int MAX_OBSERVATIONS = 4096;
	private static final int MAX_HISTORY_FRAMES = 20000;
	private static final int MAX_HISTORY_WORK = 65536;

	@Value
	public static class Result
	{
		List<Contribution> contributions;
		boolean unidentifiedPossible;
	}

	@Value
	public static class Contribution
	{
		LegacyConstraintOracle.Hypothesis hypothesis;
		String sourceKind;
		int sourceId;
		boolean definitionBound;
	}

	private ObservedMechanismResolver()
	{
	}

	public static Result resolve(MechanismRules rules, DamageRecord hit, WorldSnapshot impact, SceneData scene)
	{
		return resolve(rules, hit, impact, scene, Collections.emptyList(), Collections.emptyMap());
	}

	public static Result resolve(MechanismRules rules, DamageRecord hit, WorldSnapshot impact, SceneData scene,
		List<WorldSnapshot> history, Map<String, SceneData> scenes)
	{
		List<Contribution> output = new ArrayList<>();
		boolean unidentified = rules.unavailable();
		Set<String> callbackQualified = new HashSet<>();
		if (impact == null)
		{
			if (!rules.historyRules().isEmpty())
				historical(rules, hit, null, history, scenes, output);
			return new Result(output, true);
		}
		int count = 0;
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : hit.getObservedHazardContacts())
			{
				if (count++ >= MAX_OBSERVATIONS || output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
					return new Result(output, true);
				Primitive primitive = callback(contact);
				if (primitive != null)
					unidentified |= evaluate(rules, primitive, hit, impact, output, callbackQualified);
			}
		if (impact.getHazards() != null)
			for (HazardState hazard : impact.getHazards())
			{
				if (count++ >= MAX_OBSERVATIONS || output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
					return new Result(output, true);
				Primitive primitive = snapshot(hazard, impact, scene);
				if (primitive != null)
					unidentified |= evaluate(rules, primitive, hit, impact, output, callbackQualified);
			}
		if (!rules.historyRules().isEmpty())
			unidentified |= historical(rules, hit, impact, history, scenes, output);
		return new Result(Collections.unmodifiableList(output), unidentified);
	}

	/** A contact sample is evidence at its recorded time, never an inferred path or activation timer. */
	private static boolean historical(MechanismRules rules, DamageRecord hit, WorldSnapshot impact,
		List<WorldSnapshot> history, Map<String, SceneData> scenes, List<Contribution> output)
	{
		TreeMap<Integer, WorldSnapshot> frames = new TreeMap<>();
		Set<Integer> ambiguous = new HashSet<>();
		Set<String> relevant = new HashSet<>();
		int count = 0;
		if (hit.getObservedHazardContacts() != null)
			for (Map<String, Object> row : hit.getObservedHazardContacts())
			{
				if (++count > MAX_OBSERVATIONS)
					return true;
				relevantHistorySource(rules, callback(row), hit, relevant);
			}
		count = 0;
		if (impact != null && impact.getHazards() != null)
			for (HazardState row : impact.getHazards())
			{
				if (++count > MAX_OBSERVATIONS)
					return true;
				relevantHistorySource(rules, snapshot(row, impact, scenes.get(impact.getSceneKey())), hit, relevant);
			}
		boolean bounded = false;
		int work = 0;
		count = 0;
		long earliest = (long) hit.getImpactTick() - rules.maximumHistoryDelay() - 1;
		for (WorldSnapshot frame : history == null ? Collections.<WorldSnapshot>emptyList() : history)
		{
			if (++count > MAX_HISTORY_FRAMES)
			{
				bounded = true;
				break;
			}
			if (frame == null || frame.getTick() < earliest || frame.getTick() > hit.getImpactTick()
				|| hit.getImpactSceneKey() != null && frame.getSceneKey() != null
					&& !hit.getImpactSceneKey().equals(frame.getSceneKey())
				|| hit.getImpactWorldViewId() != null && frame.getWorldViewId() != null
					&& !hit.getImpactWorldViewId().equals(frame.getWorldViewId())
				|| hit.getImpactPlane() != null && hit.getImpactPlane() != frame.getPlane())
				continue;
			if (sameHitScope(frame, hit) && frames.putIfAbsent(frame.getTick(), frame) != null)
				ambiguous.add(frame.getTick());
			if (frame.getHazards() == null)
				continue;
			for (HazardState hazard : frame.getHazards())
			{
				if (++work > MAX_HISTORY_WORK)
				{
					bounded = true;
					break;
				}
				Primitive source = snapshot(hazard, frame, scenes.get(frame.getSceneKey()));
				if (source == null || contradictedScope(source, frame))
					continue;
				for (MechanismRules.Rule rule : rules.matching(source.kind, source.id))
					if ("OBSERVED_CONTACT_HISTORY".equals(rule.getInterval()))
						relevant.add(rule.getKey());
			}
			if (bounded)
				break;
		}
		// Unseen rules from other scenes do not make an unrelated hit incomplete.
		if (relevant.isEmpty())
			return bounded;
		boolean unknown = bounded || !Boolean.TRUE.equals(hit.getImpactPositionKnown());
		for (MechanismRules.Rule rule : rules.historyRules())
		{
			if (!relevant.contains(rule.getKey()))
				continue;
			long first = (long) hit.getImpactTick() - rule.getMaximumDelayTicks();
			long last = (long) hit.getImpactTick() - rule.getMinimumDelayTicks();
			boolean onset = "CONTACT_ONSET".equals(rule.getContactAnchor());
			// Track each actual observed location/identity. An overlapping second surface must not
			// restart the first surface's timer, nor disappear into a single aggregate contact flag.
			Set<String> prior = new HashSet<>();
			Set<String> priorDefinitions = new HashSet<>();
			boolean previousComplete = false;
			PlayerState previousPlayer = null;
			for (long tick = first - 1; tick <= last; tick++)
			{
				if (++work > MAX_HISTORY_WORK)
					return true;
				WorldSnapshot frame = tick < Integer.MIN_VALUE || tick > Integer.MAX_VALUE ? null
					: frames.get((int) tick);
				boolean complete = frame != null && !ambiguous.contains((int) tick)
					&& historyCoverage(frame, rule, scenes.get(frame.getSceneKey())) && sampledBeforeHit(frame, hit);
				PlayerState player = frame == null ? null : frame.getPlayer();
				if (!playerKnown(frame))
					complete = false;
				boolean eligible = tick >= first;
				if (!complete && eligible)
					unknown = true;
				if (playerKnown(frame) && previousPlayer != null
					&& Math.max(Math.abs((long) player.getWorldX() - previousPlayer.getWorldX()),
						Math.abs((long) player.getWorldY() - previousPlayer.getWorldY())) > 1
					&& eligible)
				{
					// Running can traverse an unrecorded intermediate tile. Do not fabricate a crossing.
					unknown = true;
					previousComplete = false;
				}
				Set<String> contacts = new HashSet<>();
				Set<String> definitions = new HashSet<>();
				if (frame != null && playerKnown(frame) && sampledBeforeHit(frame, hit) && frame.getHazards() != null)
					for (HazardState hazard : frame.getHazards())
					{
						if (++work > MAX_HISTORY_WORK)
							return true;
						Primitive source = snapshot(hazard, frame, scenes.get(frame.getSceneKey()));
						if (source == null || !rules.matching(source.kind, source.id).contains(rule)
							|| contradictedScope(source, frame))
							continue;
						if (Boolean.FALSE.equals(source.damaging)
							|| Arrays.asList("inactive", "warning").contains(source.state)
							|| Boolean.TRUE.equals(source.finished)
							|| "RESOURCE_REFERENCE_ONLY".equals(source.eligibility)
							|| "NOT_DAMAGE_SURFACE".equals(source.eligibility))
							continue;
						if (source.startCycle != null && Boolean.TRUE.equals(frame.getGameCycleKnown())
							&& source.startCycle > frame.getGameCycle())
							continue;
						boolean sourceScope = source.view != null && source.view.equals(frame.getWorldViewId())
							&& source.plane != null && source.plane == frame.getPlane();
						if (sourceScope && source.x != null && source.y != null
							&& Math.max(Math.abs((long) source.x - player.getWorldX()),
								Math.abs((long) source.y - player.getWorldY())) > rule.getRadius())
							continue;
						boolean qualified = source.x != null && source.y != null && source.view != null
							&& source.view.equals(frame.getWorldViewId()) && source.plane != null
							&& source.plane == frame.getPlane()
							&& ("ELIGIBLE_REFERENCE_SURFACE".equals(source.eligibility)
								|| "GRAPHIC".equals(source.kind) && (source.eligibility == null
									|| "UNCLASSIFIED_GRAPHIC_SURFACE".equals(source.eligibility)))
							&& ("GRAPHIC".equals(source.kind)
								? Boolean.FALSE.equals(source.finished) && source.startCycle != null
									&& Boolean.TRUE.equals(frame.getGameCycleKnown())
									&& source.startCycle <= frame.getGameCycle()
								: "active".equals(source.state) && Boolean.TRUE.equals(source.damaging));
						if (!qualified)
						{
							complete = false;
							if (eligible)
								unknown = true;
							continue;
						}
						String definition = source.kind + ":" + source.id;
						Long observationId = "OBJECT".equals(source.kind) ? hazard.getObjectKey()
							: hazard.getGraphicObservationId();
						boolean stableIdentity = observationId != null;
						String identity = stableIdentity ? definition + ":observation:" + observationId
							: definition + ":" + source.x + ":" + source.y + ":" + source.startCycle;
						contacts.add(identity);
						definitions.add(definition);
						boolean newObservedContact = previousComplete && !prior.contains(identity)
							&& (stableIdentity || !priorDefinitions.contains(definition));
						if (eligible && (!onset || newObservedContact))
						{
							addHistorical(output, rule, source, frame, hit);
							if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
								return true;
						}
						else if (eligible && onset && (!previousComplete || !prior.contains(identity)))
							unknown = true;
					}
				prior = contacts;
				priorDefinitions = definitions;
				previousComplete = complete;
				previousPlayer = playerKnown(frame) ? player : null;
			}
		}
		return unknown;
	}

	private static void relevantHistorySource(MechanismRules rules, Primitive source, DamageRecord hit,
		Set<String> relevant)
	{
		if (source == null
			|| source.scene != null && hit.getImpactSceneKey() != null && !source.scene.equals(hit.getImpactSceneKey())
			|| source.view != null && hit.getImpactWorldViewId() != null
				&& !source.view.equals(hit.getImpactWorldViewId())
			|| source.plane != null && hit.getImpactPlane() != null && !source.plane.equals(hit.getImpactPlane()))
			return;
		for (MechanismRules.Rule rule : rules.matching(source.kind, source.id))
			if ("OBSERVED_CONTACT_HISTORY".equals(rule.getInterval()))
				relevant.add(rule.getKey());
	}

	private static void addHistorical(List<Contribution> output, MechanismRules.Rule rule, Primitive source,
		WorldSnapshot contact, DamageRecord hit)
	{
		String family = rule.getFamilyKey() == null
			? ("GRAPHIC".equals(source.kind) ? "graphic-definition:" : "object-definition:") + source.id
			: rule.getFamilyKey();
		List<String> evidence = new ArrayList<>();
		evidence.add("MECHANISM_RULE:" + rule.getKey() + ";delivery=CONTACT;interval=OBSERVED_CONTACT_HISTORY;anchor="
			+ rule.getContactAnchor() + ";contactTick=" + contact.getTick() + ";impactTick=" + hit.getImpactTick()
			+ ";delayTicks=" + ((long) hit.getImpactTick() - contact.getTick()));
		evidence
			.add(source.evidence + "; recorded co-location only, not an interpolated path or observed server cause");
		for (String key : rule.getReferenceKeys())
			evidence.add("REFERENCE_KEY:" + key);
		SourceResolution.Candidate candidate = SourceResolution.Candidate.builder()
			.key("mechanism-history:" + rule.getKey() + ":" + contact.getTick() + ":" + source.evidence)
			.name(rule.getLabel()).familyKey(family).sceneKey(source.scene).worldViewId(source.view).plane(source.plane)
			.strength("POSSIBLE").evidence(Collections.unmodifiableList(evidence)).build();
		output.add(new Contribution(LegacyConstraintOracle.Hypothesis.builder().candidate(candidate)
			.mechanism("CONTACT").causalAssociationOnly(true).build(), source.kind, source.id, true));
	}

	private static boolean sameHitScope(WorldSnapshot frame, DamageRecord hit)
	{
		return Boolean.TRUE.equals(frame.getNativeContextKnown()) && hit.getImpactSceneKey() != null
			&& hit.getImpactWorldViewId() != null && hit.getImpactPlane() != null
			&& hit.getImpactSceneKey().equals(frame.getSceneKey())
			&& hit.getImpactWorldViewId().equals(frame.getWorldViewId()) && hit.getImpactPlane() == frame.getPlane();
	}

	private static boolean sampledBeforeHit(WorldSnapshot frame, DamageRecord hit)
	{
		if (frame.getTick() < hit.getImpactTick())
			return !Boolean.TRUE.equals(frame.getGameCycleKnown()) || hit.getObservedCycle() == null
				|| frame.getGameCycle() < hit.getObservedCycle();
		return frame.getTick() == hit.getImpactTick() && Boolean.TRUE.equals(frame.getGameCycleKnown())
			&& hit.getObservedCycle() != null && frame.getGameCycle() < hit.getObservedCycle();
	}

	private static boolean playerKnown(WorldSnapshot frame)
	{
		if (frame == null || frame.getPlayer() == null)
			return false;
		PlayerState player = frame.getPlayer();
		return Boolean.TRUE.equals(player.getWorldPositionKnown()) && Boolean.TRUE.equals(player.getPlaneKnown())
			&& player.getPlane() == frame.getPlane() && player.getWorldViewId() != null
			&& player.getWorldViewId().equals(frame.getWorldViewId());
	}

	private static boolean historyCoverage(WorldSnapshot frame, MechanismRules.Rule rule, SceneData scene)
	{
		if (frame.getHazards() == null || !Boolean.TRUE.equals(frame.getNativeContextKnown())
			|| frame.getHazardCoverage() == null)
			return false;
		Map<String, Object> coverage = frame.getHazardCoverage();
		String global = frame.getCaptureCoverage() == null ? null
			: frame.getCaptureCoverage().get("graphicsAndMappedHazards");
		if (!Arrays.asList("available", "partial").contains(global))
			return false;
		if ("GRAPHIC".equals(rule.getKind()))
			return "available".equals(coverage.get("graphicsStatus"))
				&& Integer.valueOf(0).equals(integer(coverage.get("graphicsReadFailures")))
				&& Integer.valueOf(0).equals(integer(coverage.get("unpositionedGraphics")))
				&& "EXPOSED_NATIVE_PLANE_GRAPHICS".equals(coverage.get("graphicsScope"));
		if (!"available".equals(coverage.get("mappedObjectsStatus"))
			|| !Boolean.TRUE.equals(coverage.get("objectScanCompleted"))
			|| !Integer.valueOf(0).equals(integer(coverage.get("mappedObjectReadFailures")))
			|| !Integer.valueOf(0).equals(integer(coverage.get("unavailableColumns"))))
			return false;
		if ("EXPOSED_NATIVE_PLANE".equals(coverage.get("mappedObjectsScope")))
			return true;
		Integer radius = integer(coverage.get("radiusTiles")), x = integer(coverage.get("centerSceneX")),
			y = integer(coverage.get("centerSceneY"));
		return "LOCAL_RADIUS".equals(coverage.get("mappedObjectsScope")) && radius != null && x != null && y != null
			&& scene != null && Objects.equals(scene.getSceneKey(), frame.getSceneKey())
			&& Objects.equals(scene.getWorldViewId(), frame.getWorldViewId()) && scene.getPlane() == frame.getPlane()
			&& playerKnown(frame) && Math.max(Math.abs((long) frame.getPlayer().getWorldX() - scene.getBaseX() - x),
				Math.abs((long) frame.getPlayer().getWorldY() - scene.getBaseY() - y)) + rule.getRadius() <= radius;
	}

	private static boolean evaluate(MechanismRules rules, Primitive source, DamageRecord hit, WorldSnapshot impact,
		List<Contribution> output, Set<String> callbackQualified)
	{
		if (contradictedScope(source, impact) || Boolean.TRUE.equals(source.finished)
			|| source.view != null && hit.getImpactWorldViewId() != null
				&& !source.view.equals(hit.getImpactWorldViewId())
			|| source.plane != null && hit.getImpactPlane() != null && !source.plane.equals(hit.getImpactPlane())
			|| source.scene != null && hit.getImpactSceneKey() != null && !source.scene.equals(hit.getImpactSceneKey())
			|| source.startCycle != null && hit.getObservedCycle() != null
				&& source.startCycle > hit.getObservedCycle())
			return false;
		List<MechanismRules.Rule> matches = rules.matching(source.kind, source.id);
		boolean unknown = rules.incomplete(source.kind, source.id);
		if (matches.isEmpty())
			return unknown;
		if ("RESOURCE_REFERENCE_ONLY".equals(source.eligibility) || "NOT_DAMAGE_SURFACE".equals(source.eligibility))
			return unknown;
		if ("DEFINITION_NOT_QUALIFIED".equals(source.eligibility))
			return true;
		if (Boolean.FALSE.equals(source.damaging) || Arrays.asList("inactive", "warning").contains(source.state))
			return unknown;
		boolean impactKnown = Boolean.TRUE.equals(hit.getImpactPositionKnown());
		for (MechanismRules.Rule rule : matches)
		{
			if (!"OBSERVED_ACTIVE".equals(rule.getInterval()))
				continue;
			String correlation = rule.getKey() + ":" + source.scene + ":" + source.view + ":" + source.plane + ":"
				+ source.x + ":" + source.y;
			if (!source.callback && callbackQualified.contains(correlation))
				continue;
			boolean sourceKnown = source.x != null && source.y != null
				&& (!source.contactPointOnly || "TILE".equals(rule.getFootprint()));
			boolean scopeKnown = source.scene != null && source.view != null && source.plane != null
				&& source.scene.equals(impact.getSceneKey()) && source.view.equals(impact.getWorldViewId())
				&& source.plane == impact.getPlane() && source.scene.equals(hit.getImpactSceneKey())
				&& source.view.equals(hit.getImpactWorldViewId()) && source.plane.equals(hit.getImpactPlane());
			if (scopeKnown && impactKnown && sourceKnown)
			{
				long x = Math.abs((long) source.x - hit.getImpactX()), y = Math.abs((long) source.y - hit.getImpactY());
				if (Math.max(x, y) > rule.getRadius())
					continue;
			}
			boolean definitionBound = "ELIGIBLE_REFERENCE_SURFACE".equals(source.eligibility)
				|| "GRAPHIC".equals(source.kind)
					&& (source.eligibility == null || "UNCLASSIFIED_GRAPHIC_SURFACE".equals(source.eligibility));
			boolean lifetimeKnown = "GRAPHIC".equals(source.kind)
				? Boolean.FALSE.equals(source.finished) && source.startCycle != null && hit.getObservedCycle() != null
				: source.callback && "HITSPLAT_CALLBACK".equals(source.stage) && source.observedCycle != null
					&& source.observedCycle.equals(hit.getObservedCycle()) && "active".equals(source.state)
					&& Boolean.TRUE.equals(source.damaging);
			List<String> evidence = new ArrayList<>();
			evidence.add("MECHANISM_RULE:" + rule.getKey() + ";delivery=" + rule.getDelivery() + ";footprint="
				+ rule.getFootprint() + ";radius=" + rule.getRadius() + ";interval=OBSERVED_ACTIVE");
			evidence.add(source.evidence + "; spatial/time correlation is not an observed server cause");
			for (String key : rule.getReferenceKeys())
				evidence.add("REFERENCE_KEY:" + key);
			if (!impactKnown)
				evidence.add("MECHANISM_CONDITION_UNESTABLISHED:IMPACT_POSITION_KNOWN");
			if (!sourceKnown)
				evidence.add("MECHANISM_CONDITION_UNESTABLISHED:SOURCE_POSITION_KNOWN");
			if (!scopeKnown)
				evidence.add("MECHANISM_CONDITION_UNESTABLISHED:SAME_NATIVE_SCOPE");
			if (!lifetimeKnown)
				evidence.add("MECHANISM_CONDITION_UNESTABLISHED:ACTIVE_INTERVAL_OBSERVED");
			if (!definitionBound)
				evidence.add("MECHANISM_CONDITION_UNESTABLISHED:SOURCE_DEFINITION_BOUND");
			unknown |= !impactKnown || !sourceKnown || !scopeKnown || !lifetimeKnown || !definitionBound;
			if (source.callback && impactKnown && sourceKnown && scopeKnown && lifetimeKnown && definitionBound)
				callbackQualified.add(correlation);
			String family = rule.getFamilyKey() == null
				? ("GRAPHIC".equals(source.kind) ? "graphic-definition:" : "object-definition:") + source.id
				: rule.getFamilyKey();
			SourceResolution.Candidate candidate = SourceResolution.Candidate.builder()
				.key("mechanism:" + rule.getKey() + ":" + source.evidence).name(rule.getLabel()).familyKey(family)
				.sceneKey(source.scene).worldViewId(source.view).plane(source.plane).strength("POSSIBLE")
				.evidence(Collections.unmodifiableList(evidence)).build();
			output.add(new Contribution(LegacyConstraintOracle.Hypothesis.builder().candidate(candidate)
				.mechanism(rule.getDelivery()).causalAssociationOnly(true).build(), source.kind, source.id,
				definitionBound));
			if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
				return true;
		}
		return unknown;
	}

	private static boolean contradictedScope(Primitive source, WorldSnapshot impact)
	{
		return source.view != null && impact.getWorldViewId() != null && !source.view.equals(impact.getWorldViewId())
			|| source.plane != null && source.plane != impact.getPlane()
			|| source.scene != null && impact.getSceneKey() != null && !source.scene.equals(impact.getSceneKey());
	}

	private static final class Primitive
	{
		String kind, scene, state, evidence, eligibility, stage;
		int id;
		Integer view, plane, x, y, startCycle, observedCycle;
		Boolean damaging, finished;
		boolean contactPointOnly;
		boolean callback;
	}

	private static Primitive callback(Map<String, Object> row)
	{
		if (row == null)
			return null;
		String type = text(row.get("kind"));
		boolean graphic = "GRAPHIC_SURFACE".equals(type);
		if (!graphic && !"OBJECT_SURFACE".equals(type))
			return null;
		Integer id = integer(row.get(graphic ? "graphicId" : "objectId"));
		if (id == null || id < 0)
			return null;
		Primitive value = new Primitive();
		value.kind = graphic ? "GRAPHIC" : "OBJECT";
		value.callback = true;
		value.id = id;
		value.scene = text(row.get("sceneKey"));
		value.view = integer(row.get("worldViewId"));
		value.plane = integer(row.get("plane"));
		value.x = integer(row.get("worldX"));
		value.y = integer(row.get("worldY"));
		if (!graphic && (value.x == null || value.y == null))
		{
			value.x = integer(row.get("contactWorldX"));
			value.y = integer(row.get("contactWorldY"));
			value.contactPointOnly = true;
		}
		value.state = text(row.get("state"));
		value.eligibility = text(row.get("sourceEligibility"));
		value.stage = text(row.get("observationStage"));
		value.observedCycle = integer(row.get("observedCycle"));
		value.damaging = bool(row.get("damaging"));
		value.finished = bool(row.get("finished"));
		value.startCycle = integer(row.get("startCycle"));
		value.evidence = "CALLBACK_SURFACE:" + value.kind + ":" + id + ":"
			+ row.get(graphic ? "graphicObservationId" : "objectObservationId") + ":" + row.get("observedCycle");
		return value;
	}

	private static Primitive snapshot(HazardState row, WorldSnapshot frame, SceneData scene)
	{
		if (row == null)
			return null;
		boolean graphic = "graphicsObject".equals(row.getKind());
		if (!graphic
			&& !Arrays.asList("gameObject", "groundObject", "wallObject", "decorativeObject").contains(row.getKind()))
			return null;
		Primitive value = new Primitive();
		value.kind = graphic ? "GRAPHIC" : "OBJECT";
		value.id = row.getId();
		value.scene = frame.getSceneKey();
		value.view = row.getWorldViewId();
		value.plane = row.getPlane();
		if (Boolean.TRUE.equals(row.getWorldPositionKnown()))
		{
			value.x = row.getWorldX();
			value.y = row.getWorldY();
		}
		else if (scene != null && Boolean.TRUE.equals(row.getLocalPositionKnown())
			&& Objects.equals(scene.getSceneKey(), frame.getSceneKey())
			&& Objects.equals(scene.getWorldViewId(), value.view) && scene.getPlane() == value.plane)
		{
			value.x = scene.getBaseX() + (row.getLocalX() >> 7);
			value.y = scene.getBaseY() + (row.getLocalY() >> 7);
		}
		value.state = row.getState();
		value.eligibility = row.getSourceEligibility();
		value.damaging = row.getDamaging();
		value.finished = row.getFinished();
		value.startCycle = row.getStartCycle();
		value.evidence = "SETTLED_SURFACE:" + value.kind + ":" + value.id + ":"
			+ (graphic ? row.getGraphicObservationId() : row.getObjectKey()) + ":" + row.getStartCycle();
		return value;
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new java.math.BigDecimal(value.toString()).intValueExact();
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
