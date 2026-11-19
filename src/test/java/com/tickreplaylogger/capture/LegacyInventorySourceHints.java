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
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.HitsplatID;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;

/** Bounded original-callback item presence facts; possession does not prove an item caused HP loss. */
public final class LegacyInventorySourceHints
{
	private static final int MAX_IDS = 4096, MAX_RULES = 4096, SLOTS = 28;
	private static final Set<Integer> POSITIVE_HP = new HashSet<>(
		Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME));
	private static final Set<String> FIELDS = set("key", "name", "itemIds", "referenceKeys", "sourceRef", "pageHash",
		"observationKind", "mechanism", "quantityPolicy", "qualification", "unidentifiedPossible",
		"observedOutcomeDomain", "observedHitsplatTypes", "provenance");
	private final Map<String, Rule> rules = new HashMap<>();
	private final Map<Integer, List<Rule>> byItem = new HashMap<>();
	private final Set<Integer> itemIds = new HashSet<>(), types = new HashSet<>();
	private boolean unavailable;

	private static final class Rule
	{
		final String key, name, sourceRef, pageHash;
		final Set<Integer> ids, types;
		final List<String> refs;
		final Integer maximum;
		final List<String> excludedWorldTypes;

		Rule(String key, String name, String sourceRef, String pageHash, List<Integer> ids, List<Integer> types,
			List<String> refs, Integer maximum, List<String> excludedWorldTypes)
		{
			this.key = key;
			this.name = name;
			this.sourceRef = sourceRef;
			this.pageHash = pageHash;
			this.ids = Collections.unmodifiableSet(new HashSet<>(ids));
			this.types = Collections.unmodifiableSet(new HashSet<>(types));
			this.refs = Collections.unmodifiableList(new ArrayList<>(refs));
			this.maximum = maximum;
			this.excludedWorldTypes = excludedWorldTypes;
		}
	}

	public LegacyInventorySourceHints(JsonObject namespace)
	{
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
		Set<Integer> rejectedIds = new HashSet<>();
		Set<String> seenKeys = new HashSet<>();
		for (JsonElement value : namespace.getAsJsonArray("rules"))
		{
			if (++count > MAX_RULES)
			{
				unavailable = true;
				break;
			}
			JsonObject row = object(value);
			String key = string(row, "key"), name = string(row, "name"), source = string(row, "sourceRef"),
				hash = string(row, "pageHash");
			List<Integer> ids = numbers(row.get("itemIds")), outcomes = numbers(row.get("observedHitsplatTypes"));
			List<String> refs = strings(row.get("referenceKeys"), true);
			Set<String> optionalFields = new HashSet<>(FIELDS);
			optionalFields.add("componentBound");
			boolean valid = (row.keySet().equals(FIELDS) || row.keySet().equals(optionalFields)) && key != null
				&& !seenKeys.contains(key) && name != null && source != null && source.startsWith("wiki:") && hash(hash)
				&& ids != null && !ids.isEmpty() && outcomes != null && !outcomes.isEmpty()
				&& POSITIVE_HP.containsAll(outcomes) && refs != null
				&& "LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK".equals(string(row, "observationKind"))
				&& "ITEM_POSSESSION".equals(string(row, "mechanism"))
				&& "PRESENCE_ONLY_NO_MULTIPLIER".equals(string(row, "quantityPolicy"))
				&& "POSSIBLE_SOURCE_HINT".equals(string(row, "qualification")) && bool(row.get("unidentifiedPossible"))
				&& "ORDINARY_HP_CALLBACK_ONLY".equals(string(row, "observedOutcomeDomain"))
				&& bindings + (ids == null ? 0 : ids.size()) <= MAX_IDS
				&& provenance(object(row.get("provenance")), ids, source, hash, outcomes);
			if (!valid)
			{
				unavailable = true;
				removeRule(rules.get(key));
				if (key != null)
					seenKeys.add(key);
				if (ids != null)
					for (Integer id : ids)
					{
						rejectedIds.add(id);
						for (Rule prior : new ArrayList<>(byItem.getOrDefault(id, Collections.emptyList())))
							removeRule(prior);
					}
				continue;
			}
			seenKeys.add(key);
			boolean overlap = !Collections.disjoint(rejectedIds, ids);
			for (Integer id : ids)
				if (byItem.containsKey(id))
				{
					overlap = true;
					for (Rule prior : new ArrayList<>(byItem.get(id)))
					{
						rejectedIds.addAll(prior.ids);
						removeRule(prior);
					}
				}
			if (overlap)
			{
				rejectedIds.addAll(ids);
				unavailable = true;
				continue;
			}
			JsonObject bound = object(row.get("componentBound"));
			boolean bounded = row.has("componentBound") && validBound(bound, source, hash);
			if (row.has("componentBound") && !bounded)
				unavailable = true; // A malformed bound cannot remove factual presence.
			Rule rule = new Rule(key, name, source, hash, ids, outcomes, refs,
				bounded ? number(bound.get("maximum")) : null,
				bounded ? strings(object(bound.get("contextPredicate")).get("excludedWorldTypeFragments"), false)
					: Collections.emptyList());
			rules.put(key, rule);
			for (Integer id : ids)
				byItem.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
			itemIds.addAll(ids);
			types.addAll(outcomes);
			bindings += ids.size();
		}
	}

	private void removeRule(Rule rule)
	{
		if (rule == null)
			return;
		rules.remove(rule.key);
		for (Integer id : rule.ids)
		{
			List<Rule> indexed = byItem.get(id);
			if (indexed != null)
			{
				indexed.remove(rule);
				if (indexed.isEmpty())
					byItem.remove(id);
			}
		}
	}

	public boolean accepts(int type, int amount)
	{
		return amount > 0 && POSITIVE_HP.contains(type) && (types.contains(type) || unavailable);
	}

	/** Called only after actual callback receiver==local-player and received/rich settings have been checked. */
	public Map<String, Object> capture(Client client, int tick, Integer cycle)
	{
		Map<String, Object> observed = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		List<Map<String, Object>> slots = new ArrayList<>();
		boolean known = false, needsNumericContext = false;
		try
		{
			ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
			if (container == null)
				issues.put("container", "UNAVAILABLE");
			else
			{
				Item[] items = container.getItems();
				if (items == null)
					issues.put("items", "UNAVAILABLE");
				else
				{
					known = true;
					if (items.length > SLOTS)
						issues.put("items", "TRUNCATED_TO_INVENTORY_CAPACITY");
					for (int slot = 0; slot < Math.min(items.length, SLOTS); slot++)
					{
						Item item = items[slot];
						if (item == null)
							continue; // Public API empty slot.
						Integer id = null, quantity = null;
						try
						{
							id = item.getId();
						}
						catch (RuntimeException failed)
						{
							issues.put("slot." + slot + ".itemId", failed.getClass().getSimpleName());
						}
						try
						{
							quantity = item.getQuantity();
						}
						catch (RuntimeException failed)
						{
							issues.put("slot." + slot + ".quantity", failed.getClass().getSimpleName());
						}
						if (id != null && !itemIds.contains(id))
							continue;
						if (id != null && byItem.getOrDefault(id, Collections.emptyList()).stream()
							.anyMatch(rule -> rule.maximum != null))
							needsNumericContext = true;
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("slot", slot);
						entry.put("itemId", id);
						entry.put("quantity", quantity);
						entry.put("itemIdKnown", id != null);
						entry.put("quantityKnown", quantity != null);
						slots.add(Collections.unmodifiableMap(entry));
					}
				}
			}
		}
		catch (RuntimeException failed)
		{
			issues.put("container", failed.getClass().getSimpleName());
		}
		observed.put("version", 1);
		observed.put("observationKind", "LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK");
		observed.put("status", !known ? "UNAVAILABLE" : issues.isEmpty() ? "OBSERVED" : "PARTIAL");
		observed.put("containerId", InventoryID.INVENTORY.getId());
		observed.put("containerKnown", known);
		observed.put("localRecipientKnown", true);
		observed.put("observedTick", tick);
		observed.put("observedCycle", cycle);
		observed.put("slotPolicy", "REVIEWED_SOURCE_IDS_AND_FAILED_READS");
		observed.put("slotCapacity", SLOTS);
		observed.put("slots", Collections.unmodifiableList(slots));
		observed.put("fieldIssues", Collections.unmodifiableMap(issues));
		if (needsNumericContext)
			observed.put("numericContext", captureNumericContext(client, tick, cycle));
		return Collections.unmodifiableMap(observed);
	}

	/** Copies derived links only; no action, consumption, timer, or HP amount is fabricated. */
	public List<Map<String, Object>> witnesses(Map<String, Object> observation, long sequence)
	{
		if (observation == null)
			return Collections.emptyList();
		List<Map<String, Object>> result = new ArrayList<>();
		if (unavailable || !"OBSERVED".equals(observation.get("status")))
			result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
		Object rawSlots = observation.get("slots");
		if (!(rawSlots instanceof List))
			return result;
		Set<String> seen = new HashSet<>();
		int work = 0;
		for (Object rawSlot : (List<?>) rawSlots)
		{
			if (++work > SLOTS)
			{
				result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
				break;
			}
			if (!(rawSlot instanceof Map))
				continue;
			Map<?, ?> slot = (Map<?, ?>) rawSlot;
			Integer id = integer(slot.get("itemId")), quantity = integer(slot.get("quantity"));
			if (!Boolean.TRUE.equals(slot.get("itemIdKnown")) || !Boolean.TRUE.equals(slot.get("quantityKnown"))
				|| id == null || quantity == null || quantity <= 0)
				continue;
			for (Rule rule : byItem.getOrDefault(id, Collections.emptyList()))
				if (seen.add(rule.key))
				{
					if (result.size() >= LegacyConstraintOracle.MAX_HYPOTHESES - 1)
					{
						result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
						return Collections.unmodifiableList(result);
					}
					Map<String, Object> witness = new LinkedHashMap<>();
					witness.put("kind", "ITEM_POSSESSION_CANDIDATE");
					witness.put("ruleKey", rule.key);
					witness.put("name", rule.name);
					witness.put("sourceRef", rule.sourceRef);
					witness.put("pageHash", rule.pageHash);
					witness.put("itemId", id);
					witness.put("quantity", quantity);
					witness.put("slot", slot.get("slot"));
					witness.put("observedTick", observation.get("observedTick"));
					witness.put("observedCycle", observation.get("observedCycle"));
					witness.put("observationSequence", sequence);
					witness.put("containerKnown", observation.get("containerKnown"));
					witness.put("localRecipientKnown", observation.get("localRecipientKnown"));
					if (observation.containsKey("numericContext"))
						witness.put("numericContext", observation.get("numericContext"));
					result.add(Collections.unmodifiableMap(witness));
				}
		}
		return Collections.unmodifiableList(result);
	}

	public LegacyConstraintOracle.Hypothesis hypothesis(Map<String, Object> raw, DamageRecord hit)
	{
		if (raw == null)
			return null;
		Rule rule = rules.get(text(raw.get("ruleKey")));
		Integer id = integer(raw.get("itemId")), quantity = integer(raw.get("quantity"));
		Integer tick = integer(raw.get("observedTick")), cycle = integer(raw.get("observedCycle"));
		Integer slot = integer(raw.get("slot"));
		Long sequence = longInteger(raw.get("observationSequence"));
		if (!"ITEM_POSSESSION_CANDIDATE".equals(raw.get("kind")) || rule == null || hit == null
			|| !"RECEIVED".equals(hit.getDirection()) || !"player".equals(hit.getDefenderRef())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) || !accepts(hit.getHitsplatType(), hit.getAmount())
			|| !rule.types.contains(hit.getHitsplatType()) || id == null || !rule.ids.contains(id) || quantity == null
			|| quantity <= 0 || slot == null || slot < 0 || slot >= SLOTS
			|| !Boolean.TRUE.equals(raw.get("containerKnown")) || !Boolean.TRUE.equals(raw.get("localRecipientKnown"))
			|| !rule.sourceRef.equals(raw.get("sourceRef")) || !rule.pageHash.equals(raw.get("pageHash"))
			|| !rule.name.equals(raw.get("name")) || tick == null || tick < 0 || tick != hit.getImpactTick()
			|| cycle == null || cycle < 0 || !cycle.equals(hit.getObservedCycle()) || sequence == null || sequence <= 0
			|| !sequence.equals(hit.getObservationSequence()))
			return null;
		List<String> evidence = new ArrayList<>();
		evidence.add("ITEM_POSSESSION_SOURCE_RULE:" + rule.key);
		evidence.add("LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK;tick=" + tick + ";cycle=" + cycle + ";sequence="
			+ sequence + ";itemId=" + id + ";slot=" + slot + ";quantity=" + quantity);
		evidence.add(
			"Observed possession only; periodic occurrence, cause and execution are unestablished; quantity does not multiply HP");
		evidence.add("SOURCE_REFERENCE:" + rule.sourceRef);
		evidence.add("SOURCE_PAGE_HASH:" + rule.pageHash);
		for (String reference : rule.refs)
			evidence.add("REFERENCE_KEY:" + reference);
		String family = "item-possession-rule:" + rule.key;
		Candidate candidate = Candidate.builder().key(family).familyKey(family).name(rule.name).strength("POSSIBLE")
			.sceneKey(hit.getImpactSceneKey()).worldViewId(hit.getImpactWorldViewId()).plane(hit.getImpactPlane())
			.evidence(Collections.unmodifiableList(evidence)).build();
		LegacyConstraintOracle.Hypothesis.HypothesisBuilder hypothesis = LegacyConstraintOracle.Hypothesis.builder()
			.candidate(candidate).mechanism("ITEM_POSSESSION").damageComponent(rule.key).causalAssociationOnly(true);
		if (rule.maximum != null && normalContext(map(raw.get("numericContext")), rule, tick, cycle))
		{
			List<String> required = Arrays.asList("QUALIFIED_CALLBACK_ITEM_POSSESSION", "NORMAL_WORLD_CONTEXT",
				"KNOWN_ORDINARY_HP_CALLBACK");
			Map<String, Boolean> observations = new LinkedHashMap<>();
			for (String fact : required)
				observations.put(fact, true);
			hypothesis.worldMode("NORMAL").observations(Collections.unmodifiableMap(observations)).constraints(
				Collections.singletonList(AttributionConstraint.builder().key(rule.key + ":possession-hp-maximum")
					.familyKey(family).maximum(rule.maximum).qualification("HARD_BOUND").composition("ALTERNATIVE_CASE")
					.numericScope("PER_CALLBACK_MAXIMUM").mechanism("ITEM_POSSESSION").damageComponent(rule.key)
					.worldMode("NORMAL").requiredObservations(required).build()));
		}
		return hypothesis.build();
	}

	private static boolean validBound(JsonObject bound, String source, String hash)
	{
		JsonObject context = object(bound.get("contextPredicate")), witness = object(bound.get("maximumWitness"));
		Integer maximum = number(bound.get("maximum")), start = number(witness.get("start")),
			end = number(witness.get("end"));
		List<String> zeros = strings(context.get("requiredZeroVariables"), false),
			excluded = strings(context.get("excludedWorldTypeFragments"), false);
		return bound.keySet().equals(set("maximum", "numericScope", "contextPredicate", "maximumWitness"))
			&& maximum != null && maximum > 0 && maximum <= 1000000
			&& "PER_CALLBACK_MAXIMUM".equals(string(bound, "numericScope"))
			&& context.keySet()
				.equals(set("requireObservedWorldTypes", "requiredZeroVariables", "excludedWorldTypeFragments"))
			&& bool(context.get("requireObservedWorldTypes")) && Collections.singletonList("LEAGUE_TYPE").equals(zeros)
			&& excluded != null && excluded.containsAll(Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA"))
			&& excluded.stream().allMatch(v -> v.matches("[A-Z][A-Z0-9_]{0,63}"))
			&& witness.keySet().equals(set("page", "pageHash", "start", "end", "text", "quantity"))
			&& Objects.equals(hash, string(witness, "pageHash"))
			&& source.startsWith("wiki:" + string(witness, "page") + "#infobox-item-") && start != null && end != null
			&& start >= 0 && end > start && Objects.equals(String.valueOf(maximum), string(witness, "text"))
			&& "ITEM_POSSESSION_HP_PER_CALLBACK_MAXIMUM".equals(string(witness, "quantity"));
	}

	/** Minimal static facts; the caller must have established this actual local HP callback. */
	public static Map<String, Object> captureNumericContext(Client client, int tick, Integer cycle)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> evidence = new LinkedHashMap<>();
		List<String> worldTypes = null;
		Integer league = null;
		try
		{
			Set<WorldType> observed = client.getWorldType();
			if (observed != null)
			{
				worldTypes = new ArrayList<>();
				for (WorldType type : observed)
					worldTypes.add(type.name());
				evidence.put("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES");
			}
		}
		catch (RuntimeException missing)
		{
			evidence.put("worldTypes", "UNAVAILABLE");
		}
		try
		{
			league = client.getVarbitValue(VarbitID.LEAGUE_TYPE);
			evidence.put("LEAGUE_TYPE", "OBSERVED_CLIENT_VARBIT");
		}
		catch (RuntimeException missing)
		{
			evidence.put("LEAGUE_TYPE", "UNAVAILABLE");
		}
		result.put("observationKind", "STATIC_WORLD_AT_HP_CALLBACK");
		result.put("observedTick", tick);
		result.put("observedCycle", cycle);
		result.put("localRecipientKnown", true);
		result.put("worldTypes", worldTypes == null ? null : Collections.unmodifiableList(worldTypes));
		result.put("leagueType", league);
		result.put("evidence", Collections.unmodifiableMap(evidence));
		return Collections.unmodifiableMap(result);
	}

	private static boolean normalContext(Map<String, Object> context, Rule rule, Integer tick, Integer cycle)
	{
		Map<String, Object> evidence = map(context.get("evidence"));
		if (!"STATIC_WORLD_AT_HP_CALLBACK".equals(context.get("observationKind"))
			|| !Objects.equals(tick, integer(context.get("observedTick")))
			|| !Objects.equals(cycle, integer(context.get("observedCycle")))
			|| !"OBSERVED_CLIENT_WORLD_TYPES".equals(evidence.get("worldTypes"))
			|| !"OBSERVED_CLIENT_VARBIT".equals(evidence.get("LEAGUE_TYPE"))
			|| !Integer.valueOf(0).equals(integer(context.get("leagueType")))
			|| !(context.get("worldTypes") instanceof List))
			return false;
		for (Object value : (List<?>) context.get("worldTypes"))
		{
			if (!(value instanceof String))
				return false;
			for (String excluded : rule.excludedWorldTypes)
				if (((String) value).contains(excluded))
					return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value)
	{
		return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
	}

	private static Long longInteger(Object value)
	{
		if (!(value instanceof Number))
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

	private static boolean provenance(JsonObject proof, List<Integer> ids, String sourceRef, String pageHash,
		List<Integer> types)
	{
		if (ids == null || !proof.keySet().equals(set("pages", "items", "reviewedClaimSha256", "outcomeCategoryProof"))
			|| !hash(string(proof, "reviewedClaimSha256")) || !array(proof.get("pages")) || !array(proof.get("items")))
			return false;
		Map<String, String> pages = new HashMap<>();
		boolean matchingHash = false;
		for (JsonElement value : proof.getAsJsonArray("pages"))
		{
			JsonObject page = object(value);
			if (!page.keySet().equals(set("page", "bodySha256", "evidence")) || string(page, "page") == null
				|| !hash(string(page, "bodySha256")) || !array(page.get("evidence")))
				return false;
			pages.put(string(page, "page"), string(page, "bodySha256"));
			matchingHash |= Objects.equals(pageHash, string(page, "bodySha256"));
			for (JsonElement rawSpan : page.getAsJsonArray("evidence"))
			{
				JsonObject span = object(rawSpan);
				Integer start = number(span.get("start")), end = number(span.get("end"));
				if (!span.keySet().equals(set("start", "end", "sha256")) || start == null || end == null || end <= start
					|| !hash(string(span, "sha256")))
					return false;
			}
		}
		Set<Integer> itemIds = new HashSet<>();
		for (JsonElement value : proof.getAsJsonArray("items"))
		{
			JsonObject item = object(value);
			Integer id = number(item.get("id")), ordinal = number(item.get("templateOrdinal"));
			if (!item.keySet().equals(set("id", "namespace", "page", "templateOrdinal", "infoboxSha256")) || id == null
				|| !itemIds.add(id) || !"item".equals(string(item, "namespace"))
				|| !Objects.equals(pageHash, pages.get(string(item, "page"))) || ordinal == null || ordinal < 1
				|| !Objects.equals(sourceRef, "wiki:" + string(item, "page") + "#infobox-item-" + ordinal)
				|| !hash(string(item, "infoboxSha256")))
				return false;
		}
		JsonObject outcome = object(proof.get("outcomeCategoryProof")), symbols = object(outcome.get("symbols"));
		return matchingHash && itemIds.equals(new HashSet<>(ids))
			&& outcome.keySet().equals(set("path", "sha256", "symbols")) && string(outcome, "path") != null
			&& hash(string(outcome, "sha256"))
			&& symbols.keySet().equals(set("DAMAGE_ME", "DAMAGE_OTHER", "DAMAGE_MAX_ME"))
			&& Integer.valueOf(HitsplatID.DAMAGE_ME).equals(number(symbols.get("DAMAGE_ME")))
			&& Integer.valueOf(HitsplatID.DAMAGE_OTHER).equals(number(symbols.get("DAMAGE_OTHER")))
			&& Integer.valueOf(HitsplatID.DAMAGE_MAX_ME).equals(number(symbols.get("DAMAGE_MAX_ME"))) && types != null
			&& POSITIVE_HP.equals(new HashSet<>(types));
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
