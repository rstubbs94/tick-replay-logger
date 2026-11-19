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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiFunction;
import net.runelite.api.HitsplatID;

/** Endpoint/client-clock coincidence only; no arrival, attack or hitsplat assignment is inferred. */
public final class ObservedProjectileEndpointResolver
{
	private static final int LIMIT = 4096, INPUT_LIMIT = 65536;
	private static final Set<String> CALLBACKS = set("PROJECTILE_FIRST_SEEN", "PROJECTILE_TARGET_CHANGED",
		"PROJECTILE_RELATIONSHIP_CHANGED");
	private static final Set<Integer> ORDINARY = new HashSet<>(Arrays.asList(HitsplatID.BLOCK_ME,
		HitsplatID.BLOCK_OTHER, HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME));

	public static final class Rules
	{
		private final Map<Integer, List<Rule>> index = new HashMap<>();
		private boolean unavailable;

		public Set<Integer> projectileIds()
		{
			return Collections.unmodifiableSet(index.keySet());
		}

		public Rules(JsonObject namespace)
		{
			if (namespace == null || namespace.size() == 0)
				return;
			if (!namespace.keySet().equals(set("version", "rules"))
				|| !Integer.valueOf(1).equals(integer(namespace.get("version")))
				|| !array(namespace.get("rules"), true))
			{
				unavailable = true;
				return;
			}
			Set<String> keys = new HashSet<>();
			int count = 0, bindings = 0;
			for (JsonElement element : namespace.getAsJsonArray("rules"))
			{
				JsonObject row = object(element), window = object(row.get("analysisWindow")),
					geometry = object(row.get("geometry"));
				List<Integer> sources = numbers(row.get("sourceNpcIds")),
					projectiles = numbers(row.get("projectileIds")), types = numbers(row.get("observedHitsplatTypes"));
				List<String> names = strings(row.get("sourceNames"), false),
					refs = strings(row.get("referenceKeys"), true);
				Integer minimum = integer(window.get("minOffsetCycles")),
					maximum = integer(window.get("maxOffsetCycles"));
				String key = string(row.get("key"));
				boolean valid = row.keySet()
					.equals(set("key", "name", "referenceKeys", "sourceNpcIds", "sourceNames", "projectileIds",
						"observationKind", "temporalRelation", "analysisWindow", "geometry", "qualification",
						"unidentifiedPossible", "recipients", "observedOutcomeDomain", "observedHitsplatTypes",
						"provenance"))
					&& key != null && keys.add(key) && string(row.get("name")) != null && sources != null
					&& projectiles != null && names != null && refs != null && types != null
					&& ORDINARY.equals(new HashSet<>(types))
					&& "API_PROJECTILE_CALLBACK".equals(string(row.get("observationKind")))
					&& "CLIENT_END_CYCLE_COOCCURRENCE".equals(string(row.get("temporalRelation")))
					&& window.keySet().equals(set("minOffsetCycles", "maxOffsetCycles", "qualification"))
					&& minimum != null && maximum != null && minimum >= -30 && maximum <= 30 && minimum <= maximum
					&& "ANALYSIS_POLICY_NOT_SERVER_TIMING".equals(string(window.get("qualification")))
					&& geometry.keySet().equals(set("operator", "scope"))
					&& "EXACT_RECORDED_DESTINATION_TILE".equals(string(geometry.get("operator")))
					&& "POSSIBLE_ENDPOINT_ASSOCIATION".equals(string(geometry.get("scope")))
					&& "POSSIBLE_SOURCE_HINT".equals(string(row.get("qualification")))
					&& Boolean.TRUE.equals(bool(row.get("unidentifiedPossible")))
					&& "OBSERVED_LOCAL_PLAYER".equals(string(row.get("recipients")))
					&& "ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(string(row.get("observedOutcomeDomain")))
					&& object(row.get("provenance")).size() > 0 && ++count <= LIMIT
					&& (long) bindings + sources.size() + projectiles.size() + names.size() + refs.size() <= LIMIT;
				if (!valid)
				{
					unavailable = true;
					continue;
				}
				Rule rule = new Rule(key, sources, names, minimum, maximum);
				bindings += sources.size() + projectiles.size() + names.size() + refs.size();
				for (Integer id : projectiles)
					index.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
			}
			index.replaceAll((id, rules) -> Collections.unmodifiableList(rules));
		}
	}

	private static final class Rule
	{
		final String key;
		final List<Integer> sources;
		final List<String> names;
		final int minimum, maximum;

		Rule(String key, List<Integer> sources, List<String> names, int minimum, int maximum)
		{
			this.key = key;
			this.sources = sources;
			this.names = names;
			this.minimum = minimum;
			this.maximum = maximum;
		}
	}

	private static final class Cue
	{
		final ObservedEvent event;
		final Long observation;
		final Integer projectile, end, cycle, destinationView, destinationPlane, x, y;
		final Map<?, ?> state, history, source;

		Cue(ObservedEvent event)
		{
			this.event = event;
			state = frozen(fields(event.getData().get("projectile")));
			history = frozen(fields(state.get("sourceHistory")));
			Map<?, ?> firstActor = fields(history.get("firstActorSource"));
			source = frozen(firstActor.isEmpty() ? fields(history.get("firstObservedSource")) : firstActor);
			observation = longNumber(state.get("observationId"));
			projectile = integer(state.get("projectileId"));
			end = integer(state.get("endCycle"));
			cycle = event.getGameCycle();
			destinationView = integer(state.get("targetWorldViewId"));
			destinationPlane = integer(state.get("destinationPlane"));
			x = integer(state.get("endWorldX"));
			y = integer(state.get("endWorldY"));
		}
	}

	private final Rules rules;
	private final LegacyCombatCatalog catalog;
	private final Map<Long, List<Cue>> instances = new LinkedHashMap<>();
	private boolean incomplete;
	private final BiFunction<Long, Map<?, ?>, Candidate> originalSourceLookup;

	public ObservedProjectileEndpointResolver(JsonObject namespace, List<ObservedEvent> observations,
		Object catalog, boolean complete)
	{
		this(new Rules(namespace), observations, catalog, complete, (BiFunction<Long, Map<?, ?>, Candidate>) null);
	}

	public ObservedProjectileEndpointResolver(Rules rules, List<ObservedEvent> observations, Object catalog,
		boolean complete)
	{
		this(rules, observations, catalog, complete, (BiFunction<Long, Map<?, ?>, Candidate>) null);
	}

	public ObservedProjectileEndpointResolver(Rules rules, List<ObservedEvent> observations, Object catalog,
		boolean complete, Function<Long, Candidate> originalSourceLookup)
	{
		this(rules, observations, catalog, complete,
			originalSourceLookup == null ? null : (id, source) -> originalSourceLookup.apply(id));
	}

	public ObservedProjectileEndpointResolver(Rules rules, List<ObservedEvent> observations, Object catalog,
		boolean complete, BiFunction<Long, Map<?, ?>, Candidate> originalSourceLookup)
	{
		this.rules = rules;
		this.catalog = LegacyCombatCatalog.from(catalog);
		this.originalSourceLookup = originalSourceLookup;
		incomplete = !complete || rules.unavailable;
		if (rules.index.isEmpty())
			return;
		if (observations == null)
		{
			incomplete = true;
			return;
		}
		int visited = 0, cues = 0;
		for (ObservedEvent event : observations)
		{
			if (++visited > INPUT_LIMIT)
			{
				incomplete = true;
				break;
			}
			if (event == null || !CALLBACKS.contains(event.getType()) || event.getData() == null)
				continue;
			if (++cues > LIMIT)
			{
				incomplete = true;
				break;
			}
			Cue cue = new Cue(event);
			if (cue.observation == null || cue.observation <= 0)
			{
				incomplete = true;
				continue;
			}
			instances.computeIfAbsent(cue.observation, ignored -> new ArrayList<>()).add(cue);
		}
		instances.values()
			.forEach(cuesForInstance -> cuesForInstance.sort(Comparator.comparingLong(cue -> cue.event.getSequence())));
	}

	public ObservedMechanismResolver.Result resolve(DamageRecord hit)
	{
		List<ObservedMechanismResolver.Contribution> out = new ArrayList<>();
		if (hit == null || catalog == null || !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) || !ORDINARY.contains(hit.getHitsplatType())
			|| catalog.hitsplatDamageType(hit.getHitsplatType()) != null
			|| !Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType())))
			return new ObservedMechanismResolver.Result(out, false);
		boolean unknown = incomplete;
		if (incomplete)
			return new ObservedMechanismResolver.Result(out, true);
		if (hit.getCaptureIssues() != null)
			for (String issue : hit.getCaptureIssues())
				if (issue != null && (issue.startsWith("hitsplatType") || issue.startsWith("hitsplat.type")
					|| issue.startsWith("impact.hitsplatType") || issue.startsWith("impactWorldPosition")
					|| issue.startsWith("impactPositionScope")))
					return new ObservedMechanismResolver.Result(out, true);
		if (hit.getObservationSequence() == null || hit.getObservationSequence() <= 0 || hit.getObservedCycle() == null
			|| hit.getObservedCycle() < 0 || hit.getImpactSceneKey() == null || hit.getImpactWorldViewId() == null
			|| hit.getImpactPlane() == null || !Boolean.TRUE.equals(hit.getImpactPositionKnown()))
			return new ObservedMechanismResolver.Result(out, !rules.index.isEmpty());
		for (List<Cue> timeline : instances.values())
		{
			Cue latest = null, original = null;
			boolean duplicate = false;
			long previous = -1;
			for (Cue cue : timeline)
			{
				if (cue.event.getSequence() <= 0 || cue.event.getSequence() >= hit.getObservationSequence()
					|| cue.event.getTick() > hit.getImpactTick())
					continue;
				if (cue.event.getSequence() == previous)
					duplicate = true;
				previous = cue.event.getSequence();
				latest = cue;
				if ("ACTOR".equals(cue.source.get("sourceEvidence")) && "NPC".equals(cue.source.get("actorKind"))
					&& Boolean.TRUE.equals(cue.source.get("sourceActorKnown"))
					&& Objects.equals(integer(cue.source.get("observedTick")), cue.event.getTick())
					&& Objects.equals(integer(cue.source.get("observedCycle")), cue.cycle))
					original = original == null ? cue : original;
			}
			if (latest == null)
				continue;
			List<Rule> matched = rules.index.getOrDefault(latest.projectile, Collections.emptyList());
			if (matched.isEmpty())
				continue;
			Map<?, ?> source = original == null ? latest.source : original.source;
			String originalScene = original == null ? null : original.event.getSceneKey();
			if (original == null && originalSourceLookup != null && "TICK_SAMPLE".equals(source.get("sampleEvidence")))
			{
				Candidate sample;
				try
				{
					sample = originalSourceLookup.apply(latest.observation, source);
				}
				catch (RuntimeException unavailable)
				{
					sample = null;
				}
				if (sample != null && Objects.equals(sample.getActorRef(), source.get("sourceRef"))
					&& Objects.equals(sample.getNpcId(), integer(source.get("sourceNpcId")))
					&& Objects.equals(sample.getWorldViewId(), integer(source.get("sourceWorldViewId")))
					&& Objects.equals(sample.getPlane(), integer(source.get("sourcePlane")))
					&& Objects.equals(com.tickreplaylogger.util.SourceResolutionLabels.usableName(sample.getName()),
						com.tickreplaylogger.util.SourceResolutionLabels
							.usableName(text(source.get("sourceObservedName")))))
					originalScene = sample.getSceneKey();
			}
			if (duplicate || !qualified(latest, source, originalScene, hit))
			{
				unknown = true;
				continue;
			}
			Integer sourceId = integer(source.get("sourceNpcId"));
			String name = com.tickreplaylogger.util.SourceResolutionLabels
				.usableName(text(source.get("sourceObservedName")));
			for (Rule rule : matched)
			{
				if (!rule.sources.contains(sourceId) || !rule.names.contains(name))
					continue;
				long gap = (long) hit.getObservedCycle() - latest.end;
				if (gap < rule.minimum || gap > rule.maximum || latest.x != hit.getImpactX()
					|| latest.y != hit.getImpactY())
					continue;
				String ref = text(source.get("sourceRef"));
				Candidate candidate = Candidate.builder()
					.key("projectile-endpoint:" + rule.key + ":" + latest.observation).name(name).actorRef(ref)
					.npcId(sourceId).sceneKey(originalScene).worldViewId(integer(source.get("sourceWorldViewId")))
					.plane(integer(source.get("sourcePlane"))).strength("POSSIBLE")
					.evidence(Arrays.asList("PROJECTILE_ENDPOINT_SOURCE_RULE:" + rule.key,
						"RECORDED_PRE_HIT_DESTINATION_TILE;observation=" + latest.observation + ";sequence="
							+ latest.event.getSequence() + ";tile=" + latest.x + "," + latest.y,
						"CLIENT_END_CYCLE_COOCCURRENCE;gap=" + gap + ";analysisPolicyWindow=" + rule.minimum + ".."
							+ rule.maximum,
						fields(latest.history.get("firstActorSource")).isEmpty() ? "FIRST_RECORDED_API_SOURCE_WITNESS"
							: "FIRST_AVAILABLE_API_SOURCE_OBSERVED_LATER; hidden launch owner is unestablished",
						"Analysis-policy coincidence only; arrival, cause, attack style, damage bound and unique hit assignment are unestablished"))
					.build();
				out.add(new ObservedMechanismResolver.Contribution(LegacyConstraintOracle.Hypothesis.builder()
					.candidate(candidate).mechanism("PROJECTILE_ENDPOINT_COOCCURRENCE").damageComponent(rule.key)
					.causalAssociationOnly(true).build(), "NPC_PROJECTILE_ENDPOINT", sourceId, true));
				unknown = true;
				if (out.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
					return new ObservedMechanismResolver.Result(out, true);
			}
		}
		return new ObservedMechanismResolver.Result(Collections.unmodifiableList(out), unknown);
	}

	private static boolean qualified(Cue latest, Map<?, ?> source, String originalScene, DamageRecord hit)
	{
		if (latest.cycle == null || latest.cycle < 0 || latest.cycle > hit.getObservedCycle() || latest.end == null
			|| latest.end < 0 || !Objects.equals(integer(latest.state.get("observedCycle")), latest.cycle)
			|| !Boolean.TRUE.equals(latest.state.get("destinationKnown")) || latest.x == null || latest.y == null
			|| latest.destinationView == null || latest.destinationPlane == null
			|| !Objects.equals(latest.destinationView, hit.getImpactWorldViewId())
			|| !Objects.equals(latest.destinationPlane, hit.getImpactPlane())
			|| !Objects.equals(originalScene, hit.getImpactSceneKey())
			|| !Objects.equals(latest.event.getSceneKey(), hit.getImpactSceneKey())
			|| !Objects.equals(integer(source.get("sourceWorldViewId")), latest.destinationView)
			|| !Objects.equals(integer(source.get("sourcePlane")), latest.destinationPlane)
			|| contradicts(latest.state.get("targetPlane"), latest.destinationPlane)
			|| contradicts(latest.state.get("targetActorWorldViewId"), latest.destinationView)
			|| contradicts(latest.state.get("targetLocalWorldViewId"), latest.destinationView)
			|| !Integer.valueOf(1).equals(integer(latest.history.get("version")))
			|| !"NPC".equals(source.get("actorKind")) || !"ACTOR".equals(source.get("sourceEvidence"))
			|| !Boolean.TRUE.equals(source.get("sourceActorKnown"))
			|| !Boolean.TRUE.equals(source.get("sourceNameKnown"))
			|| !Boolean.TRUE.equals(source.get("sourceNormalWorldContextObserved"))
			|| text(source.get("sourceRef")) == null
			|| !Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(source.get("sourceDefinitionEvidence"))
			|| !Objects.equals(longNumber(source.get("observationId")), latest.observation)
			|| !Objects.equals(integer(source.get("projectileId")), latest.projectile)
			|| integer(source.get("observedTick")) == null
			|| integer(source.get("observedTick")) > latest.event.getTick()
			|| integer(source.get("observedCycle")) == null || integer(source.get("observedCycle")) > latest.cycle
			|| !sameFirstSource(source, latest.source))
			return false;
		String destinationEvidence = text(latest.state.get("destinationEvidence"));
		Map<?, ?> issues = fields(latest.state.get("observationIssues"));
		if ("PROJECTILE_API_TARGET_POINT".equals(destinationEvidence))
		{
			if (issues.containsKey("targetPoint"))
				return false;
		}
		else if ("TARGET_LOCAL_COORDINATE_CONVERSION".equals(destinationEvidence))
		{
			if (integer(latest.state.get("targetLocalX")) == null || integer(latest.state.get("targetLocalY")) == null
				|| integer(latest.state.get("targetLocalWorldViewId")) == null
				|| integer(latest.state.get("targetPlane")) == null || issues.containsKey("targetPointConversion")
				|| issues.containsKey("targetPlane") || !Arrays.asList("MOVED_CALLBACK_TARGET", "PROJECTILE_API_TARGET")
					.contains(latest.state.get("targetPositionEvidence")))
				return false;
		}
		else
			return false;
		if (text(latest.state.get("sourceRef")) != null
			&& (!Objects.equals(source.get("sourceRef"), latest.state.get("sourceRef"))
				|| latest.state.get("sourceWorldViewId") == null || latest.state.get("sourcePlane") == null
				|| contradicts(latest.state.get("sourceNpcId"), integer(source.get("sourceNpcId")))
				|| contradicts(latest.state.get("sourceWorldViewId"), integer(source.get("sourceWorldViewId")))
				|| contradicts(latest.state.get("sourcePlane"), integer(source.get("sourcePlane")))))
			return false;
		if (Boolean.TRUE.equals(latest.state.get("sourceNameKnown")) && !Objects.equals(
			com.tickreplaylogger.util.SourceResolutionLabels.usableName(text(source.get("sourceObservedName"))),
			com.tickreplaylogger.util.SourceResolutionLabels.usableName(text(latest.state.get("sourceObservedName")))))
			return false;
		for (String flag : Arrays.asList("actorChanged", "referenceChanged", "definitionChanged", "scopeChanged",
			"observationGap", "projectileDefinitionChanged"))
			if (!Boolean.FALSE.equals(latest.history.get(flag)))
				return false;
		for (String field : Arrays.asList("sourceActor", "source.name", "source.actorRef", "source.worldView",
			"source.worldViewId", "sourcePlane", "projectileId", "endCycle"))
			if (fields(latest.state.get("observationIssues")).containsKey(field)
				|| fields(source.get("observationIssues")).containsKey(field))
				return false;
		return true;
	}

	private static boolean sameFirstSource(Map<?, ?> first, Map<?, ?> later)
	{
		for (String key : Arrays.asList("observationId", "projectileId", "observedTick", "observedCycle",
			"contextEpoch", "sourceRef", "sourceNpcId", "sourceWorldViewId", "sourcePlane", "sourceObservedName"))
			if (!Objects.equals(first.get(key), later.get(key)))
				return false;
		return true;
	}

	private static boolean contradicts(Object value, Integer expected)
	{
		return value != null && !Objects.equals(integer(value), expected);
	}

	public static Long observationId(ObservedEvent event)
	{
		if (event == null || !CALLBACKS.contains(event.getType()) || event.getData() == null)
			return null;
		Object projectile = event.getData().get("projectile");
		return projectile instanceof ProjectileState ? ((ProjectileState) projectile).getObservationId()
			: longNumber(fields(projectile).get("observationId"));
	}

	public static Integer projectileId(ObservedEvent event)
	{
		if (event == null || !CALLBACKS.contains(event.getType()) || event.getData() == null)
			return null;
		Object projectile = event.getData().get("projectile");
		return projectile instanceof ProjectileState ? ((ProjectileState) projectile).getProjectileId()
			: integer(fields(projectile).get("projectileId"));
	}

	private static Map<?, ?> fields(Object value)
	{
		if (value instanceof Map)
			return (Map<?, ?>) value;
		Map<String, Object> out = new LinkedHashMap<>();
		if (value instanceof ProjectileState)
		{
			ProjectileState p = (ProjectileState) value;
			out.put("observationId", p.getObservationId());
			out.put("projectileId", p.getProjectileId());
			out.put("sourceHistory", p.getSourceHistory());
			out.put("observedCycle", p.getObservedCycle());
			out.put("endCycle", p.getEndCycle());
			out.put("destinationKnown", p.getDestinationKnown());
			out.put("targetWorldViewId", p.getTargetWorldViewId());
			out.put("destinationPlane", p.getDestinationPlane());
			out.put("targetPlane", p.getTargetPlane());
			out.put("targetActorWorldViewId", p.getTargetActorWorldViewId());
			out.put("targetLocalWorldViewId", p.getTargetLocalWorldViewId());
			out.put("endWorldX", p.getEndWorldX());
			out.put("endWorldY", p.getEndWorldY());
			out.put("observationIssues", p.getObservationIssues());
			out.put("destinationEvidence", p.getDestinationEvidence());
			out.put("targetPositionEvidence", p.getTargetPositionEvidence());
			out.put("targetLocalX", p.getTargetLocalX());
			out.put("targetLocalY", p.getTargetLocalY());
			out.put("sourceRef", p.getSourceRef());
			out.put("sourceNpcId", p.getSourceNpcId());
			out.put("sourceWorldViewId", p.getSourceWorldViewId());
			out.put("sourcePlane", p.getSourcePlane());
			out.put("sourceObservedName", p.getSourceObservedName());
			out.put("sourceNameKnown", p.getSourceNameKnown());
		}
		else if (value instanceof ProjectileSourceHistory)
		{
			ProjectileSourceHistory h = (ProjectileSourceHistory) value;
			out.put("version", h.getVersion());
			out.put("firstObservedSource", h.getFirstObservedSource());
			out.put("firstActorSource", h.getFirstActorSource());
			out.put("actorChanged", h.isActorChanged());
			out.put("referenceChanged", h.isReferenceChanged());
			out.put("definitionChanged", h.isDefinitionChanged());
			out.put("scopeChanged", h.isScopeChanged());
			out.put("observationGap", h.isObservationGap());
			out.put("projectileDefinitionChanged", h.isProjectileDefinitionChanged());
		}
		else if (value instanceof ProjectileSourceHistory.SourceObservation)
		{
			ProjectileSourceHistory.SourceObservation s = (ProjectileSourceHistory.SourceObservation) value;
			out.put("observationId", s.getObservationId());
			out.put("projectileId", s.getProjectileId());
			out.put("observedTick", s.getObservedTick());
			out.put("observedCycle", s.getObservedCycle());
			out.put("contextEpoch", s.getContextEpoch());
			out.put("sampleEvidence", s.getSampleEvidence());
			out.put("actorKind", s.getActorKind());
			out.put("sourceEvidence", s.getSourceEvidence());
			out.put("sourceActorKnown", s.getSourceActorKnown());
			out.put("sourceNameKnown", s.getSourceNameKnown());
			out.put("sourceNormalWorldContextObserved", s.getSourceNormalWorldContextObserved());
			out.put("sourceRef", s.getSourceRef());
			out.put("sourceNpcId", s.getSourceNpcId());
			out.put("sourceObservedName", s.getSourceObservedName());
			out.put("sourceWorldViewId", s.getSourceWorldViewId());
			out.put("sourcePlane", s.getSourcePlane());
			out.put("sourceDefinitionEvidence", s.getSourceDefinitionEvidence());
			out.put("observationIssues", s.getObservationIssues());
		}
		return out;
	}

	private static Map<?, ?> frozen(Map<?, ?> input)
	{
		Map<Object, Object> copy = new LinkedHashMap<>(input);
		Object issues = copy.get("observationIssues");
		if (issues instanceof Map)
			copy.put("observationIssues", Collections.unmodifiableMap(new LinkedHashMap<>((Map<?, ?>) issues)));
		return Collections.unmodifiableMap(copy);
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null;
	}

	private static String string(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? text(value.getAsString())
			: null;
	}

	private static Boolean bool(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
			: null;
	}

	private static Long longNumber(Object value)
	{
		if (value instanceof JsonElement)
		{
			JsonElement e = (JsonElement) value;
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
				return null;
			value = e.getAsString();
		}
		else if (!(value instanceof Number))
			return null;
		try
		{
			return new BigDecimal(value.toString()).longValueExact();
		}
		catch (NumberFormatException | ArithmeticException invalid)
		{
			return null;
		}
	}

	private static Integer integer(Object value)
	{
		Long number = longNumber(value);
		return number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? null : number.intValue();
	}

	private static boolean array(JsonElement value, boolean empty)
	{
		return value != null && value.isJsonArray() && value.getAsJsonArray().size() <= LIMIT
			&& (empty || value.getAsJsonArray().size() > 0);
	}

	private static List<Integer> numbers(JsonElement value)
	{
		if (!array(value, false))
			return null;
		List<Integer> out = new ArrayList<>();
		for (JsonElement e : value.getAsJsonArray())
		{
			Integer n = integer(e);
			if (n == null || n < 0 || out.contains(n))
				return null;
			out.add(n);
		}
		return Collections.unmodifiableList(out);
	}

	private static List<String> strings(JsonElement value, boolean empty)
	{
		if (!array(value, empty))
			return null;
		List<String> out = new ArrayList<>();
		for (JsonElement e : value.getAsJsonArray())
		{
			String s = string(e);
			if (s == null || out.contains(s))
				return null;
			out.add(s);
		}
		return Collections.unmodifiableList(out);
	}
}
