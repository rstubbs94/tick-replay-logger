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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/** Closed, bounded v1 operators. Prose and incomplete declarations never become executable rules. */
public final class MechanismRules
{
	private static final int MAX_RULES = 8192;
	private static final int MAX_SELECTOR_IDS = 4096;
	private static final int MAX_INDEX_BINDINGS = 65536;
	private static final Set<String> REQUIRED = Collections.unmodifiableSet(new HashSet<>(Arrays
		.asList("IMPACT_POSITION_KNOWN", "SOURCE_POSITION_KNOWN", "SAME_NATIVE_SCOPE", "ACTIVE_INTERVAL_OBSERVED")));
	private static final Set<String> ACTOR_REQUIRED = Collections
		.unmodifiableSet(new HashSet<>(Arrays.asList("SOURCE_ACTOR_IDENTITY_KNOWN", "SOURCE_DEFINITION_BOUND",
			"SOURCE_POSITION_KNOWN", "IMPACT_POSITION_KNOWN", "SAME_NATIVE_SCOPE", "CUE_BEFORE_HIT",
			"NORMAL_WORLD_CONTEXT", "NO_MODEL_OVERRIDES_OBSERVED", "QUALIFIED_NPC_VARIANT")));
	public static final String LIFECYCLE_COOCCURRENCE = "SAME_RECORDED_TICK_LIFECYCLE_COOCCURRENCE";
	public static final String RETAINED_LIFECYCLE_WINDOW = "STRICT_RETAINED_LIFECYCLE_CUE_WINDOW";
	public static final int MAX_RETAINED_LIFECYCLE_TICKS = 32;
	private static final Set<String> LIFECYCLE_REQUIRED = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList("SOURCE_ACTOR_IDENTITY_KNOWN", "SOURCE_DEFINITION_BOUND", "SOURCE_POSITION_KNOWN",
			"IMPACT_POSITION_KNOWN", "SAME_NATIVE_SCOPE", "SAME_RECORDED_TICK", "KNOWN_LOCAL_HP_CALLBACK",
			"NORMAL_WORLD_CONTEXT", "NO_MODEL_OVERRIDES_OBSERVED", "QUALIFIED_NPC_VARIANT")));
	private static final Set<String> RETAINED_LIFECYCLE_REQUIRED;
	static
	{
		Set<String> required = new HashSet<>(LIFECYCLE_REQUIRED);
		required.remove("SAME_RECORDED_TICK");
		required.add("CUE_BEFORE_HIT");
		required.add("RETAINED_CUE_WINDOW_COMPLETE");
		RETAINED_LIFECYCLE_REQUIRED = Collections.unmodifiableSet(required);
	}
	private final Map<String, Map<Integer, List<Rule>>> rules;
	private final Map<String, Set<Integer>> incomplete;
	private final boolean unavailable;
	private final List<Rule> historyRules;
	private final int maximumHistoryDelay;
	private final ActorRegistry actors;

	@Value
	public static class Rule
	{
		String key;
		String label;
		String kind;
		String delivery;
		String footprint;
		int radius;
		String interval;
		String contactAnchor;
		int minimumDelayTicks;
		int maximumDelayTicks;
		String familyKey;
		List<String> referenceKeys;
		List<String> sourceRefs;
	}

	@Value
	public static class ActorRule
	{
		String key, label, component;
		List<String> names;
		List<Integer> combatLevels;
		String cueType;
		List<Integer> animationIds;
		String delivery, footprint;
		int radius, minimumDelayTicks, maximumDelayTicks;
		String interval;
		List<Integer> observedHitsplatTypes;
		Integer perHitMaximum;
		List<String> referenceKeys, sourceRefs;
		List<String> requiredObservations;

		public boolean isLifecycleCooccurrence()
		{
			return LIFECYCLE_COOCCURRENCE.equals(interval);
		}

		public boolean isRetainedLifecycleWindow()
		{
			return RETAINED_LIFECYCLE_WINDOW.equals(interval);
		}

		public boolean isStrictLifecycle()
		{
			return isLifecycleCooccurrence() || isRetainedLifecycleWindow();
		}
	}

	private static final class ActorRegistry
	{
		final Map<Integer, List<ActorRule>> byId = new HashMap<>();
		final Set<Integer> incomplete = new HashSet<>();
		boolean unavailable;
		boolean ordered;
		int maximumRetainedDelay;
		boolean retained;
	}

	private MechanismRules(Map<String, Map<Integer, List<Rule>>> rules, Map<String, Set<Integer>> incomplete,
		boolean unavailable, JsonElement actorRows)
	{
		this.rules = rules;
		this.incomplete = incomplete;
		this.unavailable = unavailable;
		this.actors = loadActors(actorRows);
		Map<String, Rule> historical = new java.util.TreeMap<>();
		for (Map<Integer, List<Rule>> kind : rules.values())
			for (List<Rule> selected : kind.values())
				for (Rule rule : selected)
					if ("OBSERVED_CONTACT_HISTORY".equals(rule.getInterval()))
						historical.put(rule.getKey(), rule);
		historyRules = Collections.unmodifiableList(new ArrayList<>(historical.values()));
		int maximum = 0;
		for (Rule rule : historyRules)
			maximum = Math.max(maximum, rule.getMaximumDelayTicks());
		maximumHistoryDelay = maximum;
	}

	public static MechanismRules load(JsonElement value)
	{
		Map<String, Map<Integer, List<Rule>>> rules = new HashMap<>();
		Map<String, Set<Integer>> incomplete = new HashMap<>();
		if (value == null)
			return new MechanismRules(rules, incomplete, false, null);
		if (!value.isJsonObject())
			return new MechanismRules(rules, incomplete, true, null);
		JsonObject root = value.getAsJsonObject();
		if (!(root.keySet().equals(new HashSet<>(Arrays.asList("version", "rules")))
			|| root.keySet().equals(new HashSet<>(Arrays.asList("version", "rules", "actorRules"))))
			|| !Integer.valueOf(1).equals(integer(root.get("version"))) || !root.has("rules")
			|| !root.get("rules").isJsonArray() || root.getAsJsonArray("rules").size() > MAX_RULES)
			return new MechanismRules(rules, incomplete, true, null);
		boolean unavailable = false;
		int bindings = 0;
		Set<String> keys = new HashSet<>(), duplicates = new HashSet<>();
		for (JsonElement element : root.getAsJsonArray("rules"))
		{
			JsonObject row = object(element), selector = object(row.get("selector"));
			String key = text(row.get("key")), kind = text(selector.get("kind"));
			List<Integer> ids = ids(selector.get("ids"));
			if (!Arrays.asList("GRAPHIC", "OBJECT").contains(kind) || ids == null)
			{
				unavailable = true;
				continue;
			}
			if (key != null && !keys.add(key))
				duplicates.add(key);
			JsonObject footprint = object(row.get("footprint"));
			JsonObject interval = object(row.get("interval"));
			String label = text(row.get("label")), delivery = text(row.get("delivery")),
				operator = text(footprint.get("operator"));
			Integer radius = integer(footprint.get("radius"));
			String intervalOperator = text(interval.get("operator"));
			String anchor = text(interval.get("anchor"));
			Integer minimumDelay = integer(interval.get("minDelayTicks")),
				maximumDelay = integer(interval.get("maxDelayTicks"));
			boolean intervalValid = "OBSERVED_ACTIVE".equals(intervalOperator)
				&& interval.keySet().equals(Collections.singleton("operator"))
				|| "OBSERVED_CONTACT_HISTORY".equals(intervalOperator) && "CONTACT".equals(delivery)
					&& interval.keySet()
						.equals(new HashSet<>(Arrays.asList("operator", "anchor", "minDelayTicks", "maxDelayTicks")))
					&& Arrays.asList("CONTACT_SAMPLE", "CONTACT_ONSET").contains(anchor) && minimumDelay != null
					&& maximumDelay != null && minimumDelay >= 0 && minimumDelay <= maximumDelay
					&& maximumDelay <= 5000;
			List<String> observations = strings(row.get("requiredObservations"), false),
				refs = strings(row.get("referenceKeys"), true), sources = strings(row.get("sourceRefs"), false);
			String family = text(row.get("familyKey"));
			boolean valid = key != null && label != null && Arrays.asList("CONTACT", "AREA_IMPACT").contains(delivery)
				&& new HashSet<>(Arrays.asList("key", "label", "selector", "delivery", "footprint", "interval",
					"requiredObservations", "referenceKeys", "sourceRefs", "familyKey", "provenance", "evidence",
					"wholeEntityCoverageComplete")).containsAll(row.keySet())
				&& selector.keySet().equals(new HashSet<>(Arrays.asList("kind", "ids")))
				&& ("TILE".equals(operator) && footprint.keySet().equals(Collections.singleton("operator"))
					|| "CHEBYSHEV_RADIUS".equals(operator) && radius != null && radius >= 0 && radius <= 104
						&& footprint.keySet().equals(new HashSet<>(Arrays.asList("operator", "radius"))))
				&& intervalValid && observations != null && new HashSet<>(observations).equals(REQUIRED) && refs != null
				&& sources != null
				&& (!row.has("familyKey") || family != null && ("OBJECT".equals(kind)
					? family.matches("object-rule:[A-Za-z0-9_.:-]+")
						|| ids.size() == 1 && family.equals("object-definition:" + ids.get(0))
					: ids.size() == 1 && family.equals("graphic-definition:" + ids.get(0))));
			if (!valid)
			{
				incomplete.computeIfAbsent(kind, ignored -> new HashSet<>()).addAll(ids);
				continue;
			}
			Rule rule = new Rule(key, label, kind, delivery, operator, radius == null ? 0 : radius, intervalOperator,
				anchor, minimumDelay == null ? 0 : minimumDelay, maximumDelay == null ? 0 : maximumDelay, family, refs,
				sources);
			if (bindings + ids.size() > MAX_INDEX_BINDINGS)
			{
				unavailable = true;
				continue;
			}
			bindings += ids.size();
			for (Integer id : ids)
				rules.computeIfAbsent(kind, ignored -> new HashMap<>())
					.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
		}
		for (Map.Entry<String, Map<Integer, List<Rule>>> kinds : rules.entrySet())
			for (Map.Entry<Integer, List<Rule>> entry : kinds.getValue().entrySet())
			{
				if (entry.getValue().removeIf(rule -> duplicates.contains(rule.getKey())))
					incomplete.computeIfAbsent(kinds.getKey(), ignored -> new HashSet<>()).add(entry.getKey());
				entry.getValue().sort(Comparator.comparing(Rule::getKey));
				entry.setValue(Collections.unmodifiableList(entry.getValue()));
			}
		return new MechanismRules(rules, incomplete, unavailable, root.get("actorRules"));
	}

	private static ActorRegistry loadActors(JsonElement value)
	{
		ActorRegistry registry = new ActorRegistry();
		if (value == null)
			return registry;
		if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_RULES)
		{
			registry.unavailable = true;
			return registry;
		}
		Set<String> keys = new HashSet<>(), duplicates = new HashSet<>();
		int bindings = 0;
		for (JsonElement element : value.getAsJsonArray())
		{
			JsonObject row = object(element), selector = object(row.get("selector")), cue = object(row.get("cue")),
				footprint = object(row.get("footprint")), interval = object(row.get("interval"));
			List<Integer> selected = ids(selector.get("ids"));
			if (!"NPC".equals(text(selector.get("kind"))) || selected == null)
			{
				registry.unavailable = true;
				continue;
			}
			String key = text(row.get("key")), label = text(row.get("label")), component = text(row.get("component")),
				type = text(cue.get("type")), delivery = text(row.get("delivery")),
				geometry = text(footprint.get("operator"));
			if (key != null && !keys.add(key))
				duplicates.add(key);
			List<String> names = strings(selector.get("names"), false),
				observations = strings(row.get("requiredObservations"), false),
				refs = strings(row.get("referenceKeys"), true), sources = strings(row.get("sourceRefs"), false);
			List<Integer> levels = ids(selector.get("combatLevels"));
			List<Integer> animations = "ANIMATION_CHANGED".equals(type) ? ids(cue.get("animationIds"))
				: Collections.emptyList();
			Integer radius = integer(footprint.get("radius")), min = integer(interval.get("minDelayTicks")),
				max = integer(interval.get("maxDelayTicks"));
			String policy = text(interval.get("operator"));
			boolean weak = LIFECYCLE_COOCCURRENCE.equals(policy);
			boolean retained = RETAINED_LIFECYCLE_WINDOW.equals(policy), strict = weak || retained;
			List<Integer> outcomes = strict ? ids(row.get("observedHitsplatTypes")) : Collections.emptyList();
			Integer perHitMaximum = integer(row.get("perHitMaximum"));
			boolean temporal = strict
				? (weak && interval.keySet().equals(Collections.singleton("operator"))
					&& Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED").contains(type)
					|| retained && "ACTOR_DEATH".equals(type)
						&& interval.keySet()
							.equals(new HashSet<>(Arrays.asList("operator", "minDelayTicks", "maxDelayTicks", "clock")))
						&& "RECORDED_TICKS_ANALYSIS_WINDOW".equals(text(interval.get("clock"))) && min != null
						&& max != null && min >= 0 && min <= max && max <= MAX_RETAINED_LIFECYCLE_TICKS)
					&& "TERMINAL_AREA".equals(delivery)
					&& "ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(text(row.get("observedOutcomeDomain")))
					&& outcomes != null && Arrays.asList(12, 13, 16, 17, 43).containsAll(outcomes)
					&& (!row.has("perHitMaximum")
						|| perHitMaximum != null && perHitMaximum >= 0 && perHitMaximum <= 1000000)
				: "OBSERVED_CUE_WINDOW".equals(policy)
					&& interval.keySet()
						.equals(new HashSet<>(Arrays.asList("operator", "minDelayTicks", "maxDelayTicks")))
					&& min != null && max != null && min >= 0 && min <= max && max <= 5000
					&& !row.has("observedOutcomeDomain") && !row.has("observedHitsplatTypes")
					&& !row.has("perHitMaximum");
			boolean valid = key != null && label != null && component != null && names != null && levels != null
				&& selector.keySet().equals(new HashSet<>(Arrays.asList("kind", "ids", "names", "combatLevels")))
				&& ("ANIMATION_CHANGED".equals(type) && animations != null
					&& cue.keySet().equals(new HashSet<>(Arrays.asList("type", "animationIds")))
					|| ("ACTOR_DEATH".equals(type) || weak && "NPC_DESPAWNED".equals(type))
						&& cue.keySet().equals(Collections.singleton("type")))
				&& Arrays.asList("AREA_IMPACT", "TERMINAL_AREA").contains(delivery)
				&& (!"TERMINAL_AREA".equals(delivery) || "ACTOR_DEATH".equals(type)
					|| weak && "NPC_DESPAWNED".equals(type))
				&& "SOURCE_AT_CUE".equals(text(row.get("origin")))
				&& Arrays.asList("CHEBYSHEV_RADIUS", "SOURCE_BOUNDS_EXPANDED").contains(geometry) && radius != null
				&& radius >= 0 && radius <= 104
				&& (retained
					? footprint.keySet().equals(new HashSet<>(Arrays.asList("operator", "radius", "semantics")))
						&& "POSSIBLE_SOURCE_VICINITY".equals(text(footprint.get("semantics")))
					: footprint.keySet().equals(new HashSet<>(Arrays.asList("operator", "radius"))))
				&& temporal && "OBSERVED_RECIPIENT_IN_FOOTPRINT".equals(text(row.get("recipients")))
				&& "RETAIN_OBSERVED_CUE".equals(text(row.get("lifetime"))) && observations != null
				&& new HashSet<>(observations)
					.equals(retained ? RETAINED_LIFECYCLE_REQUIRED : weak ? LIFECYCLE_REQUIRED : ACTOR_REQUIRED)
				&& refs != null && sources != null
				&& new HashSet<>(Arrays.asList("key", "label", "component", "selector", "cue", "delivery", "origin",
					"footprint", "interval", "recipients", "lifetime", "requiredObservations", "referenceKeys",
					"sourceRefs", "provenance", "evidence", "wholeEntityCoverageComplete", "observedOutcomeDomain",
					"observedHitsplatTypes", "perHitMaximum")).containsAll(row.keySet());
			if (!valid || bindings + selected.size() > MAX_INDEX_BINDINGS)
			{
				registry.incomplete.addAll(selected);
				continue;
			}
			bindings += selected.size();
			registry.ordered |= !strict;
			ActorRule rule = new ActorRule(key, label, component, names, Collections.unmodifiableList(levels), type,
				Collections.unmodifiableList(new ArrayList<>(animations)), delivery, geometry, radius, weak ? 0 : min,
				weak ? 0 : max, policy, Collections.unmodifiableList(new ArrayList<>(outcomes)), perHitMaximum, refs,
				sources, observations);
			for (Integer id : selected)
				registry.byId.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
		}
		for (Map.Entry<Integer, List<ActorRule>> entry : registry.byId.entrySet())
		{
			if (entry.getValue().removeIf(rule -> duplicates.contains(rule.getKey())))
				registry.incomplete.add(entry.getKey());
			entry.getValue().sort(Comparator.comparing(ActorRule::getKey));
			entry.setValue(Collections.unmodifiableList(entry.getValue()));
			for (ActorRule rule : entry.getValue())
				if (rule.isRetainedLifecycleWindow())
				{
					registry.retained = true;
					registry.maximumRetainedDelay = Math.max(registry.maximumRetainedDelay,
						rule.getMaximumDelayTicks());
				}
		}
		return registry;
	}

	public List<ActorRule> actorMatching(int id)
	{
		return actors.byId.getOrDefault(id, Collections.emptyList());
	}

	public boolean actorIncomplete(int id)
	{
		return actors.unavailable || actors.incomplete.contains(id);
	}

	public boolean actorUnavailable()
	{
		return actors.unavailable;
	}

	public boolean hasActorRules()
	{
		return actors.unavailable || !actors.byId.isEmpty() || !actors.incomplete.isEmpty();
	}

	public int maximumRetainedLifecycleDelay()
	{
		return actors.maximumRetainedDelay;
	}

	public boolean hasRetainedLifecycleRules()
	{
		return actors.retained;
	}

	/** Only ordered cue windows require retained callback history; same-tick clues use the completed tail. */
	public boolean hasOrderedActorRules()
	{
		return actors.unavailable || !actors.incomplete.isEmpty() || actors.ordered;
	}

	public List<Rule> matching(String kind, int id)
	{
		return rules.getOrDefault(kind, Collections.emptyMap()).getOrDefault(id, Collections.emptyList());
	}

	public boolean sourceClassified(String kind, int id)
	{
		return !matching(kind, id).isEmpty() && !incomplete(kind, id);
	}

	public boolean incomplete(String kind, int id)
	{
		return unavailable || incomplete.getOrDefault(kind, Collections.emptySet()).contains(id);
	}

	public boolean unavailable()
	{
		return unavailable;
	}

	public List<Rule> historyRules()
	{
		return historyRules;
	}

	public int maximumHistoryDelay()
	{
		return maximumHistoryDelay;
	}

	static Integer integer(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			return null;
		try
		{
			return new BigDecimal(value.getAsString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String text(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
			return null;
		String text = value.getAsString().trim();
		return text.isEmpty() || text.length() > 512 ? null : text;
	}

	private static List<Integer> ids(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > MAX_SELECTOR_IDS)
			return null;
		Set<Integer> values = new LinkedHashSet<>();
		for (JsonElement row : value.getAsJsonArray())
		{
			Integer id = integer(row);
			if (id == null || id < 0 || !values.add(id))
				return null;
		}
		return new ArrayList<>(values);
	}

	private static List<String> strings(JsonElement value, boolean allowEmpty)
	{
		if (value == null || !value.isJsonArray() || !allowEmpty && value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > 256)
			return null;
		Set<String> values = new LinkedHashSet<>();
		for (JsonElement row : value.getAsJsonArray())
		{
			String text = text(row);
			if (text == null || !values.add(text))
				return null;
		}
		return Collections.unmodifiableList(new ArrayList<>(values));
	}
}
