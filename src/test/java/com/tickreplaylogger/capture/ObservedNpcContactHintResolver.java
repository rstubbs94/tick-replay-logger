/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import com.tickreplaylogger.model.world.WorldSnapshot;
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
import net.runelite.api.HitsplatID;

/** Primary-qualified possible body associations; occupied bounds never become a damage footprint. */
public final class ObservedNpcContactHintResolver
{
	private static final int MAX_INPUT = 65536, MAX_RULES = 4096, MAX_IDS = 4096;
	private static final Set<String> FIELDS = set("key", "name", "npcIds", "referenceKeys", "observationKind",
		"temporalRelation", "qualification", "unidentifiedPossible", "geometry", "recipients", "observedOutcomeDomain",
		"observedHitsplatTypes", "unestablishedConditions", "provenance");
	private static final Set<Integer> ORDINARY_TYPES = new HashSet<>(Arrays.asList(HitsplatID.BLOCK_ME,
		HitsplatID.BLOCK_OTHER, HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME));
	private final LegacyCombatCatalog catalog;
	private final Map<Integer, Rule> rules = new HashMap<>();
	private final Set<Integer> incompleteIds = new HashSet<>();
	private final Map<Integer, List<Body>> ticks = new HashMap<>();
	private final Map<Integer, List<WorldSnapshot>> frames;
	private final Set<Integer> truncatedTicks = new HashSet<>();
	private boolean unavailable;

	private static final class Rule
	{
		final String key, name;
		final Set<Integer> types;
		final List<String> references;
		final int minDistance, maxDistance;
		final boolean proximity;

		Rule(String key, String name, Set<Integer> types, List<String> references, int minDistance, int maxDistance,
			boolean proximity)
		{
			this.key = key;
			this.name = name;
			this.types = types;
			this.references = references;
			this.minDistance = minDistance;
			this.maxDistance = maxDistance;
			this.proximity = proximity;
		}
	}

	private static final class Body
	{
		final int id, tick, framePlane;
		final String ref, name, scene;
		final Integer view, areaView, minX, minY, maxX, maxY, areaPlane, cycle;
		final boolean definitionKnown, scopeKnown, areaKnown;

		Body(NpcState npc, WorldSnapshot frame)
		{
			id = npc.getNpcId();
			tick = frame.getTick();
			framePlane = frame.getPlane();
			ref = text(npc.getActorRef());
			name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(npc.getName());
			scene = text(frame.getSceneKey());
			view = frame.getWorldViewId();
			areaView = npc.getWorldAreaWorldViewId();
			cycle = Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null;
			Map<?, ?> area = npc.getWorldArea() == null ? Collections.emptyMap() : npc.getWorldArea();
			minX = integer(area.get("minX"));
			minY = integer(area.get("minY"));
			maxX = integer(area.get("maxX"));
			maxY = integer(area.get("maxY"));
			areaPlane = integer(area.get("plane"));
			definitionKnown = ref != null && !"player".equals(ref) && name != null
				&& Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(npc.getDefinitionEvidence())
				&& Boolean.TRUE.equals(npc.getNormalWorldContextObserved())
				&& Boolean.FALSE.equals(npc.getModelOverridesPresent()) && !failed(npc, "name", "definitionId",
					"definitionContext", "transformedComposition", "modelOverrides");
			scopeKnown = Boolean.TRUE.equals(frame.getNativeContextKnown()) && scene != null && view != null
				&& npc.getWorldViewId() != null && view.equals(npc.getWorldViewId()) && areaView != null
				&& view.equals(areaView) && Boolean.TRUE.equals(npc.getWorldAreaWorldViewKnown()) && areaPlane != null
				&& areaPlane >= 0 && areaPlane <= 3 && areaPlane == framePlane
				&& (!Boolean.TRUE.equals(npc.getPlaneKnown()) || npc.getPlane() == areaPlane)
				&& !failed(npc, "worldView", "worldViewId");
			areaKnown = Boolean.TRUE.equals(npc.getWorldAreaKnown())
				&& "ACTOR_GET_WORLD_AREA".equals(npc.getWorldAreaEvidence()) && minX != null && minY != null
				&& maxX != null && maxY != null && minX <= maxX && minY <= maxY && (long) maxX - minX < 104
				&& (long) maxY - minY < 104 && !failed(npc, "worldArea");
		}
	}

	public ObservedNpcContactHintResolver(JsonObject namespace, Map<Integer, List<WorldSnapshot>> history,
		Object catalog)
	{
		this.catalog = LegacyCombatCatalog.from(catalog);
		frames = history == null ? Collections.emptyMap() : history;
		if (namespace == null || namespace.size() == 0)
			return;
		if (!namespace.keySet().equals(set("version", "rules"))
			|| !Integer.valueOf(1).equals(number(namespace.get("version"))) || namespace.get("rules") == null
			|| !namespace.get("rules").isJsonArray())
		{
			unavailable = true;
			return;
		}
		int count = 0, bindings = 0;
		Set<String> keys = new HashSet<>();
		for (JsonElement value : namespace.getAsJsonArray("rules"))
		{
			if (++count > MAX_RULES)
			{
				unavailable = true;
				break;
			}
			JsonObject row = object(value);
			List<Integer> ids = numbers(row.get("npcIds"));
			List<Integer> types = numbers(row.get("observedHitsplatTypes"));
			List<String> refs = strings(row.get("referenceKeys"), true);
			String key = string(row, "key"), name = string(row, "name");
			JsonObject geometry = object(row.get("geometry"));
			boolean body = geometry.keySet().equals(set("operator", "scope"))
				&& "OBSERVED_API_OCCUPIED_BOUNDS".equals(string(geometry, "operator"))
				&& "POSSIBLE_BODY_CONTACT_ASSOCIATION".equals(string(geometry, "scope"));
			Integer minDistance = body ? Integer.valueOf(0) : number(geometry.get("minChebyshevTiles"));
			Integer maxDistance = body ? Integer.valueOf(0) : number(geometry.get("maxChebyshevTiles"));
			boolean proximity = geometry.keySet()
				.equals(set("operator", "scope", "minChebyshevTiles", "maxChebyshevTiles"))
				&& "OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY".equals(string(geometry, "operator"))
				&& "POSSIBLE_PROXIMITY_ASSOCIATION".equals(string(geometry, "scope")) && minDistance != null
				&& maxDistance != null && minDistance >= 0 && minDistance <= maxDistance && maxDistance <= 16;
			boolean valid = row.keySet().equals(FIELDS) && key != null && name != null && !keys.contains(key)
				&& ids != null && !ids.isEmpty() && types != null && !types.isEmpty()
				&& ORDINARY_TYPES.containsAll(types) && refs != null
				&& "RECORDED_NPC_SNAPSHOT".equals(string(row, "observationKind"))
				&& "SAME_RECORDED_TICK".equals(string(row, "temporalRelation"))
				&& "POSSIBLE_SOURCE_HINT".equals(string(row, "qualification")) && bool(row.get("unidentifiedPossible"))
				&& "OBSERVED_LOCAL_PLAYER".equals(string(row, "recipients"))
				&& "ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(string(row, "observedOutcomeDomain"))
				&& (body || proximity)
				&& Arrays.asList("PHASE_OR_ACTIVATION", "ATTACK_OR_CONTACT_OCCURRENCE")
					.equals(strings(row.get("unestablishedConditions"), false))
				&& bindings + (ids == null ? 0 : ids.size()) <= MAX_INPUT
				&& provenance(object(row.get("provenance")), ids, types);
			if (!valid)
			{
				if (ids == null)
					unavailable = true;
				else
					incompleteIds.addAll(ids);
				continue;
			}
			keys.add(key);
			bindings += ids.size();
			Rule rule = new Rule(key, name, Collections.unmodifiableSet(new HashSet<>(types)),
				Collections.unmodifiableList(refs), minDistance, maxDistance, proximity);
			for (Integer id : ids)
				if (rules.containsKey(id) || incompleteIds.contains(id))
				{
					incompleteIds.add(id);
					rules.remove(id);
				}
				else
					rules.put(id, rule);
		}
		if (rules.isEmpty() && incompleteIds.isEmpty())
			return;
		if (history == null)
		{
			unavailable = true;
			return;
		}
	}

	private List<Body> bodiesAtTick(int tick)
	{
		if (ticks.containsKey(tick))
			return ticks.get(tick);
		if (!frames.containsKey(tick))
			return Collections.emptyList();
		List<Body> bodies = new ArrayList<>();
		int work = 0;
		for (WorldSnapshot frame : frames.getOrDefault(tick, Collections.emptyList()))
		{
			if (frame == null || frame.getTick() != tick)
			{
				truncatedTicks.add(tick);
				continue;
			}
			if (++work > MAX_INPUT)
			{
				truncatedTicks.add(tick);
				break;
			}
			if (frame.getNpcs() == null)
				continue;
			for (NpcState npc : frame.getNpcs())
			{
				if (++work > MAX_INPUT)
				{
					truncatedTicks.add(tick);
					break;
				}
				if (npc != null && npc.getNpcId() != null
					&& (rules.containsKey(npc.getNpcId()) || incompleteIds.contains(npc.getNpcId())))
					bodies.add(new Body(npc, frame));
			}
			if (truncatedTicks.contains(tick))
				break;
		}
		List<Body> frozen = Collections.unmodifiableList(bodies);
		ticks.put(tick, frozen);
		return frozen;
	}

	public ObservedMechanismResolver.Result resolve(DamageRecord hit)
	{
		List<ObservedMechanismResolver.Contribution> output = new ArrayList<>();
		if (hit == null || catalog == null || !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
			|| !Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			|| catalog.hitsplatDamageType(hit.getHitsplatType()) != null)
			return new ObservedMechanismResolver.Result(output, false);
		if (rules.isEmpty() && incompleteIds.isEmpty())
			return new ObservedMechanismResolver.Result(output, unavailable);
		List<Body> bodies = bodiesAtTick(hit.getImpactTick());
		boolean unknown = unavailable || truncatedTicks.contains(hit.getImpactTick());
		Set<List<Object>> seen = new HashSet<>();
		for (Body body : bodies)
		{
			if (different(body.scene, hit.getImpactSceneKey()) || different(body.view, hit.getImpactWorldViewId())
				|| hit.getImpactPlane() != null && body.framePlane != hit.getImpactPlane())
				continue;
			if (incompleteIds.contains(body.id))
			{
				unknown = true;
				continue;
			}
			Rule rule = rules.get(body.id);
			if (rule == null || !rule.types.contains(hit.getHitsplatType()))
				continue;
			if (!body.definitionKnown || !rule.name.equals(body.name) || !body.scopeKnown || !body.areaKnown
				|| hit.getImpactSceneKey() == null || hit.getImpactWorldViewId() == null || hit.getImpactPlane() == null
				|| hit.getObservationSequence() == null || hit.getObservationSequence() <= 0
				|| !Boolean.TRUE.equals(hit.getImpactPositionKnown()) || impactFailed(hit))
			{
				unknown = true;
				continue;
			}
			long dx = Math.max(Math.max((long) body.minX - hit.getImpactX(), (long) hit.getImpactX() - body.maxX), 0L);
			long dy = Math.max(Math.max((long) body.minY - hit.getImpactY(), (long) hit.getImpactY() - body.maxY), 0L);
			long distance = Math.max(dx, dy);
			if (distance < rule.minDistance || distance > rule.maxDistance)
				continue;
			if (!seen.add(Arrays.asList(rule.key, body.ref, body.scene, body.view, body.areaPlane)))
				continue;
			List<String> evidence = new ArrayList<>();
			evidence.add((rule.proximity ? "NPC_PROXIMITY_SOURCE_RULE:" : "NPC_BODY_SOURCE_RULE:") + rule.key);
			evidence.add("SAME_RECORDED_TICK_OCCUPIED_BOUNDS;tick=" + body.tick + ";sampleCycle=" + body.cycle
				+ ";bounds=" + body.minX + "," + body.minY + ".." + body.maxX + "," + body.maxY);
			evidence.add(rule.proximity ? "Observed Chebyshev edge distance=" + distance
				+ ";API occupied bounds are a spatial anchor, not a damage footprint; exact impact-time proximity, activation and cause remain unestablished"
				: "Actual API occupied body and local impact position overlap in one recorded tick; damage extent, phase, activation, attack and causal contact are unestablished");
			for (String reference : rule.references)
				evidence.add("REFERENCE_KEY:" + reference);
			Candidate candidate = Candidate.builder()
				.key((rule.proximity ? "npc-proximity:" : "npc-body:") + rule.key + ":" + body.ref).name(body.name)
				.actorRef(body.ref).npcId(body.id).sceneKey(body.scene).worldViewId(body.view).plane(body.areaPlane)
				.strength("POSSIBLE").evidence(Collections.unmodifiableList(evidence)).build();
			output.add(new ObservedMechanismResolver.Contribution(
				LegacyConstraintOracle.Hypothesis.builder().candidate(candidate)
					.mechanism(rule.proximity ? "NPC_PROXIMITY_ASSOCIATION" : "NPC_OCCUPIED_BODY_CONTACT")
					.damageComponent(rule.key).causalAssociationOnly(true).build(),
				"NPC_BODY", body.id, true));
			unknown = true;
			if (output.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
				return new ObservedMechanismResolver.Result(output, true);
		}
		return new ObservedMechanismResolver.Result(Collections.unmodifiableList(output), unknown);
	}

	private static boolean provenance(JsonObject proof, List<Integer> ids, List<Integer> types)
	{
		if (ids == null || !proof.keySet().equals(set("pages", "assets", "reviewedClaimSha256", "outcomeCategoryProof"))
			|| !hash(string(proof, "reviewedClaimSha256")) || !array(proof.get("pages")) || !array(proof.get("assets")))
			return false;
		for (JsonElement value : proof.getAsJsonArray("pages"))
		{
			JsonObject page = object(value);
			if (!page.keySet().equals(set("page", "bodySha256", "evidence")) || string(page, "page") == null
				|| !hash(string(page, "bodySha256")) || !array(page.get("evidence")))
				return false;
			for (JsonElement spanValue : page.getAsJsonArray("evidence"))
			{
				JsonObject span = object(spanValue);
				Integer start = number(span.get("start")), end = number(span.get("end"));
				if (!span.keySet().equals(set("start", "end", "sha256")) || start == null || end == null || end <= start
					|| !hash(string(span, "sha256")))
					return false;
			}
		}
		Set<Integer> assetIds = new HashSet<>();
		for (JsonElement value : proof.getAsJsonArray("assets"))
		{
			JsonObject asset = object(value);
			Integer id = number(asset.get("id"));
			if (!asset.keySet().equals(set("id", "symbol", "definitionSha256", "variantReferences")) || id == null
				|| !assetIds.add(id) || string(asset, "symbol") == null || !hash(string(asset, "definitionSha256"))
				|| !array(asset.get("variantReferences")))
				return false;
			for (JsonElement refValue : asset.getAsJsonArray("variantReferences"))
			{
				JsonObject reference = object(refValue);
				if (!reference.keySet().equals(set("sourceRef", "sha256")) || string(reference, "sourceRef") == null
					|| !hash(string(reference, "sha256")))
					return false;
			}
		}
		JsonObject outcome = object(proof.get("outcomeCategoryProof"));
		JsonObject symbols = object(outcome.get("symbols"));
		return assetIds.equals(new HashSet<>(ids)) && outcome.keySet().equals(set("path", "sha256", "symbols"))
			&& string(outcome, "path") != null && hash(string(outcome, "sha256"))
			&& symbols.keySet().equals(set("BLOCK_ME", "BLOCK_OTHER", "DAMAGE_ME", "DAMAGE_OTHER", "DAMAGE_MAX_ME"))
			&& Integer.valueOf(HitsplatID.BLOCK_ME).equals(number(symbols.get("BLOCK_ME")))
			&& Integer.valueOf(HitsplatID.BLOCK_OTHER).equals(number(symbols.get("BLOCK_OTHER")))
			&& Integer.valueOf(HitsplatID.DAMAGE_ME).equals(number(symbols.get("DAMAGE_ME")))
			&& Integer.valueOf(HitsplatID.DAMAGE_OTHER).equals(number(symbols.get("DAMAGE_OTHER")))
			&& Integer.valueOf(HitsplatID.DAMAGE_MAX_ME).equals(number(symbols.get("DAMAGE_MAX_ME"))) && types != null
			&& ORDINARY_TYPES.equals(new HashSet<>(types));
	}

	private static boolean impactFailed(DamageRecord hit)
	{
		if (hit.getCaptureIssues() != null)
			for (String issue : hit.getCaptureIssues())
				if (issue != null && (issue.startsWith("impactPositionScope") || issue.startsWith("impactWorldPosition")
					|| issue.startsWith("hitsplatType") || issue.startsWith("hitsplat.type")
					|| issue.startsWith("impact.hitsplatType")))
					return true;
		return false;
	}

	private static boolean failed(NpcState npc, String... fields)
	{
		if (npc.getObservationIssues() != null)
			for (String field : fields)
				if (npc.getObservationIssues().containsKey(field))
					return true;
		return false;
	}

	private static boolean different(Object a, Object b)
	{
		return a != null && b != null && !Objects.equals(a, b);
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject value, String key)
	{
		JsonElement element = value.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? text(element.getAsString())
			: null;
	}

	private static boolean bool(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			&& value.getAsBoolean();
	}

	private static boolean hash(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}

	private static boolean array(JsonElement value)
	{
		return value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0
			&& value.getAsJsonArray().size() <= MAX_IDS;
	}

	private static Integer number(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
			|| !value.getAsString().matches("0|[1-9][0-9]*"))
			return null;
		try
		{
			return Integer.valueOf(value.getAsString());
		}
		catch (NumberFormatException invalid)
		{
			return null;
		}
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (NumberFormatException | ArithmeticException invalid)
		{
			return null;
		}
	}

	private static List<Integer> numbers(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > MAX_IDS)
			return null;
		List<Integer> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray())
		{
			Integer id = number(element);
			if (id == null || result.contains(id))
				return null;
			result.add(id);
		}
		return result;
	}

	private static List<String> strings(JsonElement value, boolean empty)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > MAX_IDS
			|| !empty && value.getAsJsonArray().size() == 0)
			return null;
		List<String> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray())
		{
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
				|| text(element.getAsString()) == null || result.contains(element.getAsString()))
				return null;
			result.add(element.getAsString());
		}
		return result;
	}
}
