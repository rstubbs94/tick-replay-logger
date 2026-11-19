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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Recorded graphic co-occurrence hints. Creation is neither rendering nor an observed server damage cause. */
public final class ObservedGraphicCueResolver
{
	private static final int MAX_INPUT = 65536, MAX_RULES = 4096, MAX_IDS = 4096;
	private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("key", "graphicIds", "name", "referenceKeys",
		"observationKind", "temporalRelation", "qualification", "unidentifiedPossible", "geometry", "provenance",
		"recipients", "observedOutcomeDomain", "observedHitsplatTypes"));
	private final Map<Integer, List<Rule>> rules = new HashMap<>();
	private final Map<Integer, List<Cue>> ticks = new HashMap<>();
	private final Map<Integer, List<PartialCue>> partialTicks = new HashMap<>();
	private boolean unavailable;

	private static final class PartialCue
	{
		final String scene;
		final Integer view, plane;

		PartialCue(ObservedEvent event)
		{
			Map<String, Object> data = event.getData();
			String nativeScene = string(data.get("nativeSceneKey"));
			scene = Boolean.TRUE.equals(data.get("scopeKnown")) && !failed(data, "nativeSceneKey")
				? nativeScene == null ? event.getSceneKey() : nativeScene
				: null;
			view = failed(data, "worldView", "worldViewId") ? null : integer(data.get("worldViewId"));
			Integer observedPlane = failed(data, "plane") ? null : integer(data.get("plane"));
			plane = observedPlane != null && observedPlane >= 0 && observedPlane <= 3 ? observedPlane : null;
		}

		boolean mayMatch(DamageRecord hit)
		{
			return (scene == null || hit.getImpactSceneKey() == null || scene.equals(hit.getImpactSceneKey()))
				&& (view == null || hit.getImpactWorldViewId() == null || view.equals(hit.getImpactWorldViewId()))
				&& (plane == null || hit.getImpactPlane() == null || plane.equals(hit.getImpactPlane()));
		}
	}

	private static final class Rule
	{
		final String key, name;
		final int radius;
		final List<String> references;
		final List<Integer> observedTypes;

		Rule(String key, String name, int radius, List<String> references, List<Integer> observedTypes)
		{
			this.key = key;
			this.name = name;
			this.radius = radius;
			this.references = references;
			this.observedTypes = observedTypes;
		}
	}

	private static final class Cue
	{
		final int id, tick, view, plane, x, y;
		final long identity, sequence;
		final String scene;

		Cue(int id, ObservedEvent event, int view, int plane, int x, int y, long identity, String scene)
		{
			this.id = id;
			tick = event.getTick();
			this.view = view;
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.identity = identity;
			sequence = event.getSequence();
			this.scene = scene;
		}
	}

	public ObservedGraphicCueResolver(JsonElement declarations, List<ObservedEvent> events,
		Map<String, SceneData> scenes)
	{
		if (declarations == null || declarations.isJsonObject() && declarations.getAsJsonObject().size() == 0)
			return;
		if (!declarations.isJsonObject())
		{
			unavailable = true;
			return;
		}
		JsonObject root = declarations.getAsJsonObject();
		if (!root.keySet().equals(new HashSet<>(Arrays.asList("version", "rules")))
			|| !Integer.valueOf(1).equals(integer(root.get("version"))) || !root.has("rules")
			|| !root.get("rules").isJsonArray() || root.getAsJsonArray("rules").size() > MAX_RULES)
		{
			unavailable = true;
			return;
		}
		Set<String> keys = new HashSet<>();
		int bindings = 0;
		for (JsonElement value : root.getAsJsonArray("rules"))
		{
			if (!value.isJsonObject())
			{
				unavailable = true;
				continue;
			}
			JsonObject row = value.getAsJsonObject();
			String key = text(row.get("key")), name = text(row.get("name"));
			JsonObject geometry = row.has("geometry") && row.get("geometry").isJsonObject()
				? row.getAsJsonObject("geometry")
				: new JsonObject();
			String operator = text(geometry.get("operator"));
			Integer radius = "TILE".equals(operator) ? 0 : integer(geometry.get("radius"));
			boolean footprint = "TILE".equals(operator)
				&& geometry.keySet().equals(new HashSet<>(Arrays.asList("operator", "scope")))
				|| "CHEBYSHEV_RADIUS".equals(operator) && radius != null && radius >= 0 && radius <= 104
					&& geometry.keySet().equals(new HashSet<>(Arrays.asList("operator", "radius", "scope")));
			List<Integer> ids = integers(row.get("graphicIds"));
			List<String> references = strings(row.get("referenceKeys"));
			List<Integer> observedTypes = integers(row.get("observedHitsplatTypes"));
			if (!row.keySet().equals(FIELDS) || key == null || name == null || !keys.add(key) || ids == null
				|| references == null || !footprint || !"POSSIBLE_ENVELOPE".equals(text(geometry.get("scope")))
				|| !"GRAPHICS_OBJECT_CREATED".equals(text(row.get("observationKind")))
				|| !"SAME_RECORDED_TICK_CO_OCCURRENCE".equals(text(row.get("temporalRelation")))
				|| !"POSSIBLE_SOURCE_HINT".equals(text(row.get("qualification")))
				|| !"OBSERVED_LOCAL_PLAYER".equals(text(row.get("recipients")))
				|| !"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(text(row.get("observedOutcomeDomain")))
				|| observedTypes == null || !Boolean.TRUE.equals(bool(row.get("unidentifiedPossible")))
				|| bindings + ids.size() > MAX_INPUT)
			{
				unavailable = true;
				continue;
			}
			Rule rule = new Rule(key, name, radius, Collections.unmodifiableList(references),
				Collections.unmodifiableList(observedTypes));
			bindings += ids.size();
			for (Integer id : ids)
				rules.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
		}
		if (rules.isEmpty())
			return;
		if (events == null || scenes == null)
		{
			unavailable = true;
			return;
		}
		int count = 0;
		Set<String> seen = new HashSet<>();
		for (ObservedEvent event : events)
		{
			if (++count > MAX_INPUT)
			{
				unavailable = true;
				break;
			}
			if (event == null || !"GRAPHICS_OBJECT_CREATED".equals(event.getType()) || event.getData() == null)
				continue;
			Map<String, Object> data = event.getData();
			Integer id = integer(data.get("id"));
			if (id == null)
			{
				partialTicks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>()).add(new PartialCue(event));
				continue;
			}
			if (!rules.containsKey(id))
				continue;
			Integer view = integer(data.get("worldViewId")), localView = integer(data.get("localWorldViewId")),
				plane = integer(data.get("plane")), lx = integer(data.get("localX")), ly = integer(data.get("localY"));
			Long identity = positiveLong(data.get("graphicObservationId"));
			String scene = string(data.get("nativeSceneKey"));
			if (scene == null)
				scene = event.getSceneKey(); // Primary native view uses the event's recorded scope.
			SceneData nativeScene = scene == null ? null : scenes.get(scene);
			if (!Boolean.TRUE.equals(data.get("scopeKnown")) || !Boolean.TRUE.equals(data.get("positionScopeKnown"))
				|| nativeScene == null || view == null || !view.equals(localView)
				|| !view.equals(nativeScene.getWorldViewId()) || plane == null || plane < 0 || plane > 3
				|| plane != nativeScene.getPlane() || identity == null || lx == null || ly == null || lx < 0 || ly < 0
				|| nativeScene.getWidth() <= 0 || nativeScene.getWidth() > 104 || nativeScene.getHeight() <= 0
				|| nativeScene.getHeight() > 104 || lx / 128 >= nativeScene.getWidth()
				|| ly / 128 >= nativeScene.getHeight() || failed(data, "id", "graphicObservationId", "worldView",
					"worldViewId", "plane", "location", "localX", "localY", "localWorldViewId", "nativeSceneKey"))
			{
				partialTicks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>()).add(new PartialCue(event));
				continue;
			}
			String identityKey = event.getTick() + ":" + scene + ":" + view + ":" + plane + ":" + identity;
			if (!seen.add(identityKey))
				continue;
			long x = (long) nativeScene.getBaseX() + lx / 128, y = (long) nativeScene.getBaseY() + ly / 128;
			if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE)
			{
				partialTicks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>()).add(new PartialCue(event));
				continue;
			}
			ticks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>())
				.add(new Cue(id, event, view, plane, (int) x, (int) y, identity, scene));
		}
	}

	public ObservedMechanismResolver.Result resolve(DamageRecord hit, Object referenceData)
	{
		LegacyCombatCatalog catalog=LegacyCombatCatalog.from(referenceData);
		List<ObservedMechanismResolver.Contribution> output = new ArrayList<>();
		if (hit == null || catalog == null || !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
			|| !Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			|| catalog.hitsplatDamageType(hit.getHitsplatType()) != null)
			return new ObservedMechanismResolver.Result(output, false);
		boolean unknown = unavailable;
		for (PartialCue partial : partialTicks.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
			unknown |= partial.mayMatch(hit);
		for (Cue cue : ticks.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
		{
			if (!Objects.equals(cue.scene, hit.getImpactSceneKey())
				|| !Objects.equals(cue.view, hit.getImpactWorldViewId())
				|| !Objects.equals(cue.plane, hit.getImpactPlane())
				|| !Boolean.TRUE.equals(hit.getImpactPositionKnown()))
				continue;
			for (Rule rule : rules.get(cue.id))
			{
				if (!rule.observedTypes.contains(hit.getHitsplatType()))
					continue;
				if (Math.abs((long) hit.getImpactX() - cue.x) > rule.radius
					|| Math.abs((long) hit.getImpactY() - cue.y) > rule.radius)
					continue;
				List<String> evidence = new ArrayList<>();
				evidence.add("GRAPHIC_CUE_RULE:" + rule.key);
				evidence.add("SAME_RECORDED_TICK_CO_OCCURRENCE;graphicId=" + cue.id + ";graphicObservationId="
					+ cue.identity + ";sequence=" + cue.sequence + ";tick=" + cue.tick);
				evidence
					.add("POSSIBLE_ENVELOPE:CHEBYSHEV_RADIUS=" + rule.radius + ";sourceTile=" + cue.x + "," + cue.y);
				evidence.add(
					"Graphic creation and hit callbacks share a recorded tick; rendering, activation, callback ordering, owner and server cause are not established");
				for (String reference : rule.references)
					evidence.add("REFERENCE_KEY:" + reference);
				SourceResolution.Candidate candidate = SourceResolution.Candidate.builder()
					.key("graphic-cue:" + rule.key + ":" + cue.identity).name(rule.name)
					.familyKey("graphic-definition:" + cue.id).sceneKey(cue.scene).worldViewId(cue.view)
					.plane(cue.plane).strength("POSSIBLE").evidence(Collections.unmodifiableList(evidence)).build();
				output.add(new ObservedMechanismResolver.Contribution(LegacyConstraintOracle.Hypothesis.builder()
					.candidate(candidate).mechanism("ENVIRONMENT_AREA_IMPACT").damageComponent(rule.key)
					.causalAssociationOnly(true).build(), "GRAPHIC", cue.id, true));
				unknown = true; // A cue never closes the list of possible causes.
				if (output.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
					return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), true);
			}
		}
		return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), unknown);
	}

	private static boolean failed(Map<String, Object> data, String... fields)
	{
		Object issues = data.get("observationIssues");
		if (!(issues instanceof Map))
			return issues != null;
		for (String field : fields)
			if (((Map<?, ?>) issues).containsKey(field))
				return true;
		return false;
	}

	private static Integer integer(Object value)
	{
		try
		{
			if (value instanceof JsonElement)
			{
				JsonElement json = (JsonElement) value;
				if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber())
					return null;
				return new BigDecimal(json.getAsString()).intValueExact();
			}
			return value instanceof Number ? new BigDecimal(value.toString()).intValueExact() : null;
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static Long positiveLong(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			long result = new BigDecimal(value.toString()).longValueExact();
			return result > 0 ? result : null;
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static String text(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? string(value.getAsString())
			: null;
	}

	private static String string(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null;
	}

	private static Boolean bool(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
			: null;
	}

	private static List<Integer> integers(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > MAX_IDS)
			return null;
		List<Integer> result = new ArrayList<>();
		for (JsonElement entry : value.getAsJsonArray())
		{
			Integer id = integer(entry);
			if (id == null || id < 0 || result.contains(id))
				return null;
			result.add(id);
		}
		return result;
	}

	private static List<String> strings(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 256)
			return null;
		List<String> result = new ArrayList<>();
		for (JsonElement entry : value.getAsJsonArray())
		{
			String ref = text(entry);
			if (ref == null || result.contains(ref))
				return null;
			result.add(ref);
		}
		return result;
	}
}
