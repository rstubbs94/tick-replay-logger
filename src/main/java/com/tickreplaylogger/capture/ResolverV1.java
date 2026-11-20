/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Retrospective interpretation of original recorded receipts and declarative mechanics. */
public final class ResolverV1
{
	public static final int MAX_CUES = 2048, MAX_RESULTS = 1024;
	private static volatile ResolverV1 bundled;
	private final Map<String, List<JsonObject>> index = new HashMap<>();
	private final Map<String, JsonObject> declarations = new HashMap<>();
	private final JsonObject document;
	private final CombatCatalog references;
	private final Gson gson;
	private final String id;

	public ResolverV1(JsonObject document, Gson gson, CombatCatalog references, DamageMap capture)
	{
		this.document = document == null ? new JsonObject() : document;
		this.gson = gson;
		this.references = references;
		id = "resolver-v1:sha256:" + digest(this.document.toString().getBytes(StandardCharsets.UTF_8));
		for (JsonElement value : array(this.document.get("resolutionRules")))
		{
			JsonObject rule = object(value), selector = object(rule.get("source"));
			declarations.put(text(rule, "id"), rule);
			JsonArray ids = array(selector.get("ids"));
			if (ids.size() == 0)
			{
				add("*", rule);
			}
			for (JsonElement sourceId : ids)
			{
				add(text(selector, "kind") + ":" + integer(sourceId), rule);
			}
			if ("SYSTEM_RESPONSE".equals(text(rule, "evidenceMode")))
			{
				add("message", rule);
			}
		}
	}

	private void add(String key, JsonObject rule)
	{
		index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rule);
	}

	public static ResolverV1 load(Gson gson)
	{
		ResolverV1 loaded = bundled;
		if (loaded == null)
		{
			synchronized (ResolverV1.class)
			{
				loaded = bundled;
				if (loaded == null)
				{
					RuntimeCatalog catalog = RuntimeCatalog.load(gson);
					CombatCatalog references = CombatCatalog.referenceView(catalog.section("references"));
					loaded = new ResolverV1(catalog.section("resolver"), gson, references,
						DamageMap.load(gson, references));
					bundled = loaded;
				}
			}
		}
		return loaded;
	}

	public boolean isAvailable()
	{
		return document.get("resolutionRules") instanceof JsonArray;
	}

	public String id()
	{
		return id;
	}

	public JsonObject matchedRule(String key)
	{
		return declarations.get(key);
	}

	ResolutionResult evaluate(Hit hit)
	{
		List<Facts> facts = new ArrayList<>();
		for (Source source : hit.sources())
		{
			if (facts.size() > MAX_CUES)
			{
				break;
			}
			facts.add(new RawFacts(hit, source));
			if (source.channel() == Channel.FRAME_NPC
				&& rules(source).stream().anyMatch(rule -> "retaliation".equals(text(rule, "pattern"))))
			{
				for (Source trigger : hit.sources())
				{
					if (facts.size() <= MAX_CUES && validTrigger(trigger, hit))
					{
						facts.add(new RawFacts(hit, source, trigger));
					}
				}
			}
		}
		return evaluate(hit, facts);
	}

	private static boolean validTrigger(Source source, Hit hit)
	{
		String direction = text(source.raw(), "direction");
		return source.channel() == Channel.OUTGOING_TRIGGER && hit.callback() != null && source.receipt() != null
			&& ("DEALT".equals(direction)
				|| "NPC_RECEIVED".equals(direction) && "LOCAL_PLAYER".equals(text(source.raw(), "ownership")))
			&& number(source.raw().get("amount")) != null && number(source.raw().get("amount")).signum() > 0
			&& Objects.equals(source.receipt().clock.tick, hit.record().getImpactTick())
			&& OriginalSourceProof.scope(source.receipt().scope, hit.callback().scope) == Truth.TRUE;
	}

	static boolean nativeConflict(Hit hit, Source source, boolean ownedApi)
	{
		Scope impact = new Scope(hit.record().getImpactSceneKey(), hit.record().getImpactWorldViewId(),
			hit.record().getImpactPlane(), hit.record().getImpactContextEpoch());
		if (hit.callback() != null && OriginalSourceProof.scope(hit.callback().scope, impact) == Truth.FALSE)
		{
			return true;
		}
		Scope actual = source.identity().scope, recipient = hit.callback() == null ? null : hit.callback().scope;
		if (OriginalSourceProof.scope(actual, recipient) != Truth.FALSE)
		{
			return false;
		}
		return !ownedApi || actual != null && recipient != null
			&& (actual.sceneKey != null && recipient.sceneKey != null && !actual.sceneKey.equals(recipient.sceneKey)
				|| actual.epoch != null && recipient.epoch != null && !actual.epoch.equals(recipient.epoch));
	}

	ResolutionResult evaluate(Hit hit, Iterable<? extends Facts> inputs)
	{
		ResolutionResult result = new ResolutionResult();
		Facts hitFacts = new RawFacts(hit, null);
		result.hitAnnotations = annotations(hitFacts, null, selectedRules(hitFacts));
		if (!isAvailable() || hit.record() == null)
		{
			return result;
		}
		int inspected = 0;
		for (Facts facts : inputs)
		{
			Source source = facts.source();
			if (++inspected > MAX_CUES || result.candidates.size() >= MAX_RESULTS)
			{
				result.truncated = true;
				break;
			}
			List<JsonObject> applicable = selectedRules(facts);
			if (source.channel() == Channel.HP_ACTOR_POSE
				&& (facts.familyOfHit() == null || "ordinary".equals(facts.familyOfHit()))
				&& Boolean.TRUE.equals(bool(facts.value("cue", "staticReferenceEligible"))))
			{
				JsonArray hints = array(result.hitAnnotations.get("candidateStyles"));
				for (JsonElement hint : array(annotations(facts, null, applicable).get("candidateStyles")))
				{
					if (!contains(hints, hint))
					{
						hints.add(hint);
					}
				}
				if (hints.size() > 0)
				{
					result.hitAnnotations.add("candidateStyles", hints);
				}
			}
			List<ResolutionCandidate> rows = new ArrayList<>();
			JsonObject activity = null;
			boolean ordinaryBound = false;
			for (JsonObject rule : applicable)
			{
				if (!signals(rule, facts))
				{
					continue;
				}
				if ("activity".equals(text(rule, "role")) && hit.record().getAmount() > 0 && facts.activityEligible()
					&& "verified".equals(text(rule, "authority")) && facts.test(rule.get("when")) == Truth.TRUE)
				{
					activity = rule;
				}
				if (Boolean.TRUE.equals(bool(rule.get("profile"))) && "verified".equals(text(rule, "signalAuthority"))
					&& object(rule.get("signals")).size() > 0 && facts.owned() && facts.nativeScope() == Truth.TRUE
					&& !Boolean.FALSE.equals(bool(facts.value("cue", "componentBound")))
					&& facts.test(rule.get("when")) == Truth.TRUE)
				{
					ordinaryBound = true;
				}
			}
			for (JsonObject rule : applicable)
			{
				if (accepts(rule, facts))
				{
					ResolutionCandidate row = component(rule, facts, ordinaryBound);
					if (row != null)
					{
						rows.add(row);
					}
				}
			}
			if ((source.channel() == Channel.STATUS || source.channel() == Channel.TYPED_STATUS) && rows.size() > 1)
			{
				ResolutionCandidate category = rows.get(0);
				for (int alias = 1; alias < rows.size(); alias++)
				{
					category.evidence.add("RESOLVER_V1:" + rows.get(alias).ruleId);
				}
				rows = new ArrayList<>(Collections.singletonList(category));
			}
			if (rows.isEmpty() && (facts.nativeConflict() && source.channel() != Channel.NPC_EMITTER
				|| source.channel() == Channel.TYPED_STATUS
					&& Arrays.asList("poison", "venom", "burn", "bleed").contains(facts.familyOfHit())))
			{
				rows.add(ResolutionCandidate.hint(
					facts.nativeConflict() ? "observed:native_context_conflict" : "observed:typed_status", source));
			}
			if (activity != null)
			{
				rows.clear();
				ResolutionCandidate row = ResolutionCandidate.observed(text(activity, "id"), source);
				row.rule = activity;
				row.identityQualified = facts.nativeScope() == Truth.TRUE;
				exclude(row, "NON_DAMAGING_ACTIVITY");
				row.evidence.add("ACTIVITY_CLASSIFICATION");
				rows.add(row);
			}
			else if (source.channel() != Channel.NPC_EMITTER && facts.fallback()
				&& rows.stream()
					.noneMatch(row -> Boolean.TRUE.equals(row.componentIdentified)
						|| source.channel() != Channel.PROJECTILE && facts.ordinary() && row.ordinary
							&& "EXCLUDED".equals(row.status)
						|| "POSSIBLE".equals(row.status) && Boolean.TRUE.equals(row.identityQualified)
							&& row.rule != null && (row.ordinary && facts.ordinary()
								|| Objects.equals(text(row.rule, "pattern"), facts.pattern()))))
			{
				ResolutionCandidate fallback = ResolutionCandidate.hint("observed:" + facts.pattern(), source);
				rows.add(fallback);
			}
			for (ResolutionCandidate row : rows)
			{
				inputAlias(facts, row);
				if (OriginalSourceProof.api(source) && !OriginalSourceProof.historyStable(source, true))
				{
					row.evidence.add("ORIGINAL_API_SOURCE_HISTORY_UNSTABLE");
				}
				if (source.channel() == Channel.TYPED_STATUS)
				{
					Identity identity = source.identity();
					String family = facts.familyOfHit(), label = text(result.hitAnnotations, "attackerName");
					if (label == null)
					{
						label = identity.name != null ? identity.name
							: family == null ? null
								: family.substring(0, 1).toUpperCase(Locale.ROOT) + family.substring(1);
					}
					row.familyKey = "observed-hit-kind:" + family;
					row.identityOverride = new Identity("status", null, null, row.familyKey, label, null,
						identity.scope);
					row.sourceOnly = true;
					row.componentQualified = false;
					row.componentIdentified = false;
				}
				if (facts.nativeConflict())
				{
					exclude(row, "RECORDED_NATIVE_CONTEXT_CONFLICT");
				}
				if (!row.sourceOnly)
				{
					limits(applicable, facts, row);
				}
				outcomes(applicable, facts, row);
				if (strings(row.reasons).stream().anyMatch(reason -> reason.startsWith("MAXIMUM_EXCEEDED:"))
					&& !contains(row.reasons, value("MAXIMUM_EXCEEDED")))
				{
					row.reasons.add("MAXIMUM_EXCEEDED");
				}
				if ("POSSIBLE".equals(row.status))
				{
					row.derivedAnnotations = annotations(facts, row, applicable);
					facts.scalar(row);
				}
				result.candidates.add(row);
			}
		}
		reconcile(result.candidates, hit);
		return result;
	}

	List<ResolutionResult> evaluateBatch(List<? extends Hit> hits)
	{
		List<ResolutionResult> results = new ArrayList<>();
		for (Hit hit : hits)
		{
			results.add(evaluate(hit));
		}
		reconcileBatch(hits, results);
		return results;
	}

	static void reconcileBatch(List<? extends Hit> hits, List<ResolutionResult> results)
	{
		Map<String, List<DamageRecord>> uses = new HashMap<>();
		for (int i = 0; i < hits.size(); i++)
		{
			ResolutionResult result = results.get(i);
			Set<String> physical = new HashSet<>();
			for (ResolutionCandidate row : result.candidates)
			{
				if ("POSSIBLE".equals(row.status) && row.source.channel() == Channel.PROJECTILE
					&& physical.add(row.source.key()))
				{
					List<DamageRecord> callbacks = uses.computeIfAbsent(row.source.key(), ignored -> new ArrayList<>());
					DamageRecord callback = hits.get(i).record();
					if (callbacks.stream().noneMatch(existing -> existing.sameNativeCallback(callback)))
					{
						callbacks.add(callback);
					}
				}
			}
		}
		for (ResolutionResult result : results)
		{
			for (ResolutionCandidate row : result.candidates)
			{
				if (uses.getOrDefault(row.source.key(), Collections.emptyList()).size() > 1)
				{
					result.scalarAssociation = "AMBIGUOUS";
					row.scalarEligible = false;
					row.confidence = "UNKNOWN";
					row.evidence.add("SHARED_OBSERVED_PROJECTILE_ASSIGNMENT");
					row.evidence.add("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED");
				}
			}
		}
	}

	private List<JsonObject> rules(Source source)
	{
		Identity identity = source.identity();
		LinkedHashSet<JsonObject> result = new LinkedHashSet<>(index.getOrDefault("*", Collections.emptyList()));
		result.addAll(index.getOrDefault(identity.kind + ":" + identity.id, Collections.emptyList()));
		Integer projectile = integer(source.raw().get("projectileId"));
		if (projectile != null)
		{
			result.addAll(index.getOrDefault("projectile:" + projectile, Collections.emptyList()));
		}
		if (source.channel() == Channel.MESSAGE)
		{
			result.addAll(index.getOrDefault("message", Collections.emptyList()));
		}
		return new ArrayList<>(result);
	}

	private List<JsonObject> selectedRules(Facts facts)
	{
		List<JsonObject> selected = new ArrayList<>();
		for (JsonObject rule : facts.source() == null ? index.getOrDefault("*", Collections.emptyList())
			: rules(facts.source()))
		{
			if (selected(rule, facts))
			{
				selected.add(rule);
			}
		}
		return selected;
	}

	private ResolutionCandidate component(JsonObject rule, Facts facts, boolean ordinaryBound)
	{
		if ("INTERACTION_RESPONSE".equals(text(rule, "evidenceMode")))
		{
			return interaction(rule, facts);
		}
		Source source = facts.source();
		boolean profile = Boolean.TRUE.equals(bool(rule.get("profile")));
		String role = text(rule, "role");
		Truth when = facts.test(rule.get("when"));
		if ("retaliation".equals(text(rule, "pattern")) && (when != Truth.TRUE || facts.nativeScope() != Truth.TRUE))
		{
			return null;
		}
		if ("presence".equals(role) && (!Boolean.TRUE.equals(bool(facts.value("cue", "nativePresenceObserved")))
			|| source.identity().id == null || source.identity().actorRef == null
			|| source.identity().actorRef.trim().isEmpty() || facts.nativeScope() != Truth.TRUE || when != Truth.TRUE
			|| !"verified".equals(text(rule, "authority"))))
		{
			return null;
		}
		boolean linked = signals(rule, facts) && object(rule.get("signals")).size() > 0
			&& (!profile || facts.owned() && !Boolean.FALSE.equals(bool(facts.value("cue", "componentBound")))
				&& "verified".equals(text(rule, "signalAuthority")));
		if (!profile && "component".equals(role) && ordinaryBound && !linked)
		{
			return null;
		}
		if (profile && (!facts.direct() || OriginalSourceProof.expiredCarrier(source, facts.hit())
			|| !facts.ordinary() && !linked || ordinaryBound && !linked))
		{
			return null;
		}
		if ("evidence".equals(role) && (!signals(rule, facts) || !evidence(rule, facts)))
		{
			return null;
		}
		Truth geometry = rule.has("geometry") ? facts.geometry(object(rule.get("geometry"))) : Truth.UNKNOWN;
		if (source.channel() == Channel.FRAME_NPC && "contact_hazard".equals(text(rule, "pattern"))
			&& geometry != Truth.TRUE)
		{
			return null;
		}
		boolean weak = facts.weak() || "presence".equals(role)
			|| source.channel() == Channel.HP_CONTACT && facts.contact() != Truth.TRUE;
		if (facts.contactRejected() || source.channel() == Channel.HP_CONTACT && facts.contact() == Truth.FALSE
			|| source.channel() == Channel.STATUS && !facts.statusEnvelope()
			|| Arrays.asList(Channel.ACTOR_CALLBACK, Channel.HP_ACTOR_POSE, Channel.FRAME_NPC, Channel.NPC_EMITTER,
				Channel.HP_CONTACT).contains(source.channel()) && facts.sourceActive() == Truth.FALSE
			|| weak && geometry == Truth.FALSE)
		{
			return null;
		}
		ResolutionCandidate row = weak || "evidence".equals(role) ? ResolutionCandidate.hint(text(rule, "id"), source)
			: ResolutionCandidate.observed(text(rule, "id"), source);
		row.rule = rule;
		row.ordinary = profile;
		row.sourceOnly = weak;
		if ("presence".equals(role))
		{
			row.evidence.add("PASSIVE_SOURCE_PRESENCE_ONLY");
		}
		row.evidenceMode = text(rule, "evidenceMode");
		row.label = text(rule, "label");
		row.references = rule.has("references") ? array(rule.get("references")) : null;
		row.familyKey = text(rule, "familyKey") != null ? text(rule, "familyKey")
			: facts.family() != null ? facts.family() : row.familyKey;
		boolean component = !weak && !Boolean.FALSE.equals(bool(facts.value("cue", "componentBound")))
			&& (linked || Boolean.TRUE.equals(bool(facts.value("cue", "componentBound"))) || profile && facts.ordinary()
				|| source.channel() == Channel.HP_CONTACT || source.channel() == Channel.STATUS
				|| source.channel() == Channel.TYPED_STATUS);
		row.identityQualified = facts.nativeScope() == Truth.TRUE;
		row.componentIdentified = component && when == Truth.TRUE && Boolean.TRUE.equals(row.identityQualified);
		row.componentQualified = Boolean.TRUE.equals(row.componentIdentified);
		Truth timing = timing(object(rule.get("timing")), facts);
		if (OriginalSourceProof.expiredCarrier(source, facts.hit())
			&& (timing != Truth.TRUE || !Arrays.asList("verified", "observed", "correlation_window")
				.contains(text(object(rule.get("timing")), "authority"))))
		{
			exclude(row, "CARRIER_LIFETIME_ENDED_WITHOUT_DECLARED_DELAY");
		}
		if (timing == Truth.FALSE && !"reference_only".equals(text(object(rule.get("timing")), "authority")))
		{
			exclude(row, "TIMING_MISMATCH");
			row.componentQualified = false;
		}
		if (when == Truth.FALSE)
		{
			exclude(row, "CONDITION_MISMATCH");
		}
		if (geometry == Truth.FALSE && "verified".equals(text(object(rule.get("geometry")), "authority"))
			&& Boolean.TRUE.equals(row.componentQualified))
		{
			exclude(row, "GEOMETRY_MISMATCH");
		}
		if (profile && Arrays.asList("poison", "venom", "burn", "bleed").contains(facts.familyOfHit()))
		{
			exclude(row, "ORDINARY_FAMILY_MISMATCH");
		}
		if ((profile || "direct_target".equals(text(rule, "recipients"))) && known(facts.value("cue", "targetRef"))
			&& !equal(facts.value("cue", "targetRef"), value(facts.hit().record().getDefenderRef())))
		{
			exclude(row, "RECIPIENT_MISMATCH");
		}
		JsonObject outcome = object(rule.get("outcome"));
		if ((Boolean.TRUE.equals(row.componentIdentified) || weak && when == Truth.TRUE)
			&& signature(outcome, facts) == Truth.FALSE)
		{
			exclude(row, "SIGNATURE_MISMATCH");
		}
		if ("evidence".equals(role))
		{
			row.evidence.add(text(rule, "evidencePrefix") + text(rule, "id"));
			row.causalAssociationOnly = true;
			if (number(rule.get("perHitMaximum")) != null && known(facts.value("hit", "hitsplatType"))
				&& OriginalSourceProof.originalPosition(facts.hit()) && facts.normalContext(object(
					rule.get(rule.has("maximumContextPredicate") ? "maximumContextPredicate" : "contextPredicate"))))
			{
				row.applyMaximum(rule.get("perHitMaximum"), facts.hit(), text(rule, "id"));
			}
			bindReference(rule, facts, row);
		}
		if (facts.numeric() && known(facts.value("hit", "hitsplatType")) && facts.familyOfHit() != null
			&& (!profile || "ordinary".equals(facts.familyOfHit()))
			&& (!profile || Boolean.TRUE.equals(bool(facts.value("observations", "QUALIFIED_NPC_VARIANT")))
				|| object(rule.get("source")).has("names") && object(rule.get("source")).has("combatLevels"))
			&& Boolean.TRUE.equals(row.componentQualified))
		{
			JsonObject owner = profile ? rule : outcome;
			applyMaximum(owner.get("maximum"), facts, row);
			for (JsonElement extra : array(owner.get("additionalMaximums")))
			{
				applyMaximum(extra, facts, row);
			}
		}
		row.referenceAnnotations = new JsonObject();
		for (Map.Entry<String, JsonElement> reference : object(rule.get("referenceAnnotations")).entrySet())
		{
			row.referenceAnnotations.add(reference.getKey(), reference.getValue());
		}
		for (String key : Arrays.asList("styles", "attackStyles", "calculationTick", "outcome"))
		{
			if (rule.has(key))
			{
				row.referenceAnnotations.add(key, rule.get(key));
			}
		}
		row.hitMultiplicity = rule.get("hitMultiplicity");
		if (source.channel() == Channel.HP_CONTACT && Boolean.TRUE.equals(row.componentQualified)
			&& facts.hit().sources().stream()
				.anyMatch(other -> (other.channel() == Channel.FRAME_SURFACE || other.channel() == Channel.FRAME_NPC
					&& OriginalSourceProof.geometry(other, facts.hit(), object(rule.get("geometry"))) == Truth.TRUE)
					&& !Boolean.FALSE.equals(bool(other.raw().get("damaging")))
					&& !Boolean.TRUE.equals(bool(other.raw().get("finished")))
					&& !Arrays.asList("warning", "inactive").contains(text(other.raw(), "state"))
					&& OriginalSourceProof.samePhysical(source, other) == Truth.TRUE))
		{
			row.sourceAssociation = "ORIGINAL_CONTACT_AGREEMENT";
		}
		return row;
	}

	private boolean selected(JsonObject rule, Facts facts)
	{
		JsonObject selector = object(rule.get("source"));
		if (selector.size() == 0)
		{
			return true;
		}
		if (facts.source() == null)
		{
			return false;
		}
		Identity source = facts.source().identity();
		String kind = text(selector, "kind");
		if (facts.source().channel() == Channel.MESSAGE
			&& Arrays.asList("SYSTEM_RESPONSE", "INTERACTION_RESPONSE").contains(text(rule, "evidenceMode")))
		{
			return true;
		}
		JsonElement id = value(source.id);
		if ("projectile".equals(kind) && facts.source().channel() == Channel.PROJECTILE)
		{
			if (!"projectile".equals(source.kind))
			{
				JsonElement signal = facts.signal("projectileIds");
				id = signal instanceof JsonArray && array(signal).size() == 1 ? array(signal).get(0) : signal;
			}
		}
		else if (kind != null && !kind.equals(source.kind))
		{
			return false;
		}
		if (selector.has("ids") && !contains(selector.get("ids"), id))
		{
			return false;
		}
		for (String field : Arrays.asList("names", "combatLevels"))
		{
			JsonElement actual = value("names".equals(field) ? source.name : source.combatLevel);
			boolean matches = "names".equals(field) && source.name != null
				? strings(selector.get(field)).stream()
					.anyMatch(expected -> normalize(expected).equals(normalize(source.name)))
				: contains(selector.get(field), actual);
			if (selector.has(field) && (!known(actual) || !matches))
			{
				return false;
			}
		}
		if (selector.has("statusFamily"))
		{
			String family = text(selector, "statusFamily");
			return Objects.equals(family, facts.familyOfHit()) && (facts.source().channel() == Channel.TYPED_STATUS
				|| Objects.equals(family, text(facts.raw(), "state")) && (references == null
					|| references.localStatusCanRepresentDamage(text(facts.raw(), "key"), family)));
		}
		return true;
	}

	private boolean accepts(JsonObject rule, Facts facts)
	{
		String role = text(rule, "role"), mode = text(rule, "evidenceMode"), pattern = text(rule, "pattern");
		Channel channel = facts.source().channel();
		if ("evidence".equals(role))
		{
			if (channel == Channel.MESSAGE && "INTERACTION_RESPONSE".equals(mode))
			{
				return true;
			}
			return Objects.equals(mode, facts.mode())
				|| "ENDPOINT_COOCCURRENCE".equals(mode) && channel == Channel.PROJECTILE
				|| "ORIGINAL_CONTACT".equals(mode)
					&& Arrays.asList(Channel.FRAME_NPC, Channel.FRAME_SURFACE, Channel.GRAPHIC_BIRTH).contains(channel);
		}
		if ("presence".equals(role))
		{
			return channel == Channel.NPC_EMITTER || channel == Channel.FRAME_NPC;
		}
		if (!"component".equals(role))
		{
			return false;
		}
		if (channel == Channel.NPC_EMITTER)
		{
			return false;
		}
		return Boolean.TRUE.equals(bool(rule.get("profile"))) || facts.acceptsPattern(pattern);
	}

	private boolean evidence(JsonObject rule, Facts facts)
	{
		if (facts.test(rule.get("admissionWhen")) != Truth.TRUE || rule.has("admittedHitsplatTypes")
			&& !contains(rule.get("admittedHitsplatTypes"), value(facts.hit().record().getHitsplatType())))
		{
			return false;
		}
		BigDecimal admissionMaximum = number(rule.get("admissionMaximum"));
		if (admissionMaximum != null
			&& BigDecimal.valueOf(facts.hit().record().getAmount()).compareTo(admissionMaximum) > 0)
		{
			return false;
		}
		for (Map.Entry<String, JsonElement> binding : object(rule.get("witnessBindings")).entrySet())
		{
			if (!equal(facts.source().raw().get(binding.getKey()), binding.getValue()))
			{
				return false;
			}
		}
		if (Boolean.TRUE.equals(bool(rule.get("originalHpPositionRequired")))
			&& !OriginalSourceProof.originalPosition(facts.hit()))
		{
			return false;
		}
		if (rule.has("contextPredicate") && !facts.normalContext(object(rule.get("contextPredicate"))))
		{
			return false;
		}
		if (Boolean.TRUE.equals(bool(rule.get("retainedWindowRequired"))) && !facts.hit().complete())
		{
			return false;
		}
		JsonObject window = object(object(rule.get("declaration")).get("analysisWindow"));
		if ("CLIENT_END_CYCLE_COOCCURRENCE".equals(text(rule, "clockPolicy")))
		{
			Integer end = integer(facts.source().raw().get("endCycle")),
				cycle = facts.hit().record().getObservedCycle();
			Integer min = integer(window.get("minOffsetCycles")), max = integer(window.get("maxOffsetCycles"));
			if (end == null || cycle == null || min == null || max == null || cycle - (long) end < min
				|| cycle - (long) end > max || !facts.hit().complete() || !facts.owner(false, false))
			{
				return false;
			}
		}
		return Boolean.FALSE.equals(bool(rule.get("geometryRequired"))) || !rule.has("geometry")
			|| facts.geometry(object(rule.get("geometry"))) == Truth.TRUE;
	}

	private ResolutionCandidate interaction(JsonObject rule, Facts facts)
	{
		Source message = facts.source();
		JsonObject response = object(rule.get("response")), request = object(rule.get("request"));
		JsonObject admission = object(rule.get("admission"));
		String text = text(message.raw(), "message"), regex = text(response, "messageRegex");
		if (text == null || regex == null || !contains(response.get("channels"), message.raw().get("channel"))
			|| !contains(admission.get("hitsplatTypeIds"), value(facts.hit().record().getHitsplatType()))
			|| message.receipt() == null || message.receipt().clock.tick == null
			|| message.receipt().clock.tick != facts.hit().record().getImpactTick()
			|| facts.nativeScope() != Truth.TRUE)
		{
			return null;
		}
		java.util.regex.Matcher match = java.util.regex.Pattern.compile(regex).matcher(text.replaceAll("<[^>]*>", ""));
		if (!match.matches())
		{
			return null;
		}
		long callbacks = facts.hit().peers().stream()
			.filter(peer -> peer.getImpactTick() == facts.hit().record().getImpactTick() && peer.getAmount() > 0
				&& !"DEALT".equals(peer.getDirection()) && !"NPC_RECEIVED".equals(peer.getDirection())
				&& Objects.equals(peer.getDefenderRef(), facts.hit().record().getDefenderRef()))
			.count();
		if (callbacks != integer(admission.get("positiveCallbacksPerTick")))
		{
			return null;
		}
		ResolutionCandidate row = ResolutionCandidate.hint(text(rule, "id"), message);
		Source identified = null;
		Integer age = integer(request.get("maximumAgeTicks"));
		for (Source input : facts.hit().sources())
		{
			if (input.channel() != Channel.INPUT || input.receipt() == null || input.receipt().clock.tick == null
				|| age == null || facts.hit().record().getImpactTick() - input.receipt().clock.tick < 0
				|| facts.hit().record().getImpactTick() - input.receipt().clock.tick > age
				|| !Objects.equals(text(request, "operation"), text(input.raw(), "option")))
			{
				continue;
			}
			JsonObject target = object(input.raw().get("relatedActorState"));
			if (text(target, "name") == null || !"NPC".equals(text(target, "actorKind")))
			{
				continue;
			}
			JsonObject local = object(input.raw().get("actorState"));
			Integer x = integer(local.get("worldX")), y = integer(local.get("worldY"));
			Integer targetX = integer(target.get("worldX")), targetY = integer(target.get("worldY"));
			Integer distance = integer(request.get("maximumDistanceTiles"));
			if (OriginalSourceProof.scope(input.receipt().scope, facts.hit().callback().scope) != Truth.TRUE
				|| !Boolean.TRUE.equals(bool(local.get("worldPositionKnown")))
				|| !Boolean.TRUE.equals(bool(target.get("worldPositionKnown"))) || x == null || y == null
				|| targetX == null || targetY == null || distance == null
				|| Math.max(Math.abs((long) x - targetX), Math.abs((long) y - targetY)) > distance)
			{
				continue;
			}
			if (identified != null)
			{
				identified = null;
				break;
			}
			identified = input;
		}
		row.label = text(rule, "fallbackLabel");
		if (identified != null)
		{
			JsonObject actor = object(identified.raw().get("relatedActorState"));
			row.label = text(actor, "name") + text(rule, "labelSuffix");
			row.identityOverride = new Identity("npc", integer(actor.get("npcId")),
				identified.receipt().event == null ? null : identified.receipt().event.getRelatedActorRef(),
				identified.key(), row.label, integer(actor.get("combatLevel")), identified.receipt().scope);
			row.familyKey = "interaction:" + identified.key();
		}
		row.rule = rule;
		row.evidenceMode = "INTERACTION_RESPONSE";
		row.componentQualified = true;
		row.referenceAnnotations = object(rule.get("referenceAnnotations"));
		row.sourceAssociation = text(rule, "sourceAssociation");
		row.evidence.add("SERVER_FAILURE_RESPONSE_AND_UNIQUE_OBSERVED_HP_CALLBACK");
		return row;
	}

	private void bindReference(JsonObject rule, Facts facts, ResolutionCandidate row)
	{
		if (facts.source().channel() == Channel.MESSAGE)
		{
			String label = text(rule, "label");
			row.identityOverride = new Identity("item", null, null, "response:" + row.ruleId, label, null,
				facts.source().identity().scope);
			row.familyKey = "input-rule:wiki:" + label;
		}
		if (facts.source().channel() == Channel.INVENTORY)
		{
			row.familyKey = "item-possession-rule:" + text(object(rule.get("declaration")), "key");
		}
	}

	private void inputAlias(Facts facts, ResolutionCandidate row)
	{
		Source source = facts.source();
		if (references == null || !"item".equals(source.identity().kind)
			|| !Arrays.asList(Channel.INPUT, Channel.CONSUMPTION, Channel.RESOURCE_INPUT).contains(source.channel()))
		{
			return;
		}
		JsonObject witness = source.raw(), declaration = null;
		for (JsonElement entry : references.selfDamageInputs())
		{
			JsonObject candidate = object(entry);
			if (contains(candidate.get("itemIds"), value(source.identity().id))
				&& Objects.equals("wiki:" + text(candidate, "page"), text(witness, "sourceRef"))
				&& Objects.equals(text(candidate, "pageHash"), text(witness, "pageHash")))
			{
				declaration = candidate;
				break;
			}
		}
		if (declaration == null)
		{
			return;
		}
		row.familyKey = "input-rule:" + text(witness, "sourceRef");
		row.evidenceMode = facts.mode();
		row.causalAssociationOnly = true;
		boolean originalInput = source.receipt() != null && number(witness.get("inputSequence")) != null
			&& equal(witness.get("inputSequence"), value(source.receipt().clock.sequence)) && facts.before(false)
			&& facts.nativeScope() == Truth.TRUE;
		row.sourceAssociation = originalInput ? "SELF_DAMAGE_INPUT_CORRELATION" : null;
		for (Source consumed : facts.hit().sources())
		{
			JsonObject decrease = consumed.raw();
			BigDecimal before = number(decrease.get("quantityBefore")), after = number(decrease.get("quantityAfter"));
			if (consumed.channel() != Channel.CONSUMPTION || before == null || after == null
				|| before.compareTo(after) <= 0
				|| !"OBSERVED_INVENTORY_DECREASE_NOT_CAUSAL_PROOF".equals(text(decrease, "evidence"))
				|| number(witness.get("inputSequence")) == null || number(witness.get("inputSequence")).signum() <= 0
				|| !equal(witness.get("inputSequence"), decrease.get("inputSequence"))
				|| !equal(witness.get("itemId"), decrease.get("itemId"))
				|| !Objects.equals(text(witness, "sourceRef"), text(decrease, "sourceRef"))
				|| !Objects.equals(text(witness, "pageHash"), text(decrease, "pageHash"))
				|| OriginalSourceProof.scope(source.identity().scope, consumed.identity().scope) != Truth.TRUE)
			{
				continue;
			}
			Identity identity = source.identity();
			row.identityOverride = new Identity(identity.kind, identity.id, identity.actorRef, identity.observationKey,
				consumed.identity().name, identity.combatLevel, identity.scope);
			row.sourceAssociation = originalInput ? "ITEM_CONSUMPTION_CORRELATION" : null;
		}
	}

	private void limits(List<JsonObject> rules, Facts facts, ResolutionCandidate row)
	{
		BoundCases cases = new BoundCases();
		for (JsonObject rule : rules)
		{
			String role = text(rule, "role");
			if (!Arrays.asList("qualified_limit", "source_bound", "input_limit").contains(role))
			{
				continue;
			}
			boolean independent = !"qualified_limit".equals(role)
				|| "independent_guard".equals(text(rule, "composition"));
			boolean applies = facts.numeric() && known(facts.value("hit", "hitsplatType"))
				&& facts.familyOfHit() != null && facts.test(rule.get("when")) == Truth.TRUE
				&& "verified".equals(text(rule, "authority")) && array(rule.get("staticMissing")).size() == 0;
			if ("ALL_DIRECT_HP_COMPONENTS".equals(text(rule, "exclusionScope")))
			{
				applies &= facts.source().identity().actorRef != null
					&& !facts.source().identity().actorRef.trim().isEmpty() && facts.nativeScope() == Truth.TRUE;
			}
			if ("source_bound".equals(role))
			{
				applies = facts.numeric() && (facts.familyOfHit() == null || "ordinary".equals(facts.familyOfHit()))
					&& facts.test(rule.get("when")) == Truth.TRUE && facts.owned() && facts.targeted()
					&& facts.before(true) && facts.source().identity().actorRef != null
					&& "verified".equals(text(object(rule.get("maximum")), "authority"))
					&& facts.test(object(rule.get("maximum")).get("when")) == Truth.TRUE
					&& signature(object(rule.get("outcome")), facts) == Truth.TRUE;
			}
			if ("input_limit".equals(role))
			{
				applies = facts.input(rule) && known(facts.value("hit", "hitsplatType"))
					&& facts.normalContext(object(rule.get("contextPredicate"))) && facts.nativeScope() == Truth.TRUE;
			}
			BigDecimal maximum = maximum(rule.get("maximum"), facts);
			boolean exceeds = applies && maximum != null
				&& BigDecimal.valueOf(facts.hit().record().getAmount()).compareTo(maximum) > 0;
			cases.add(independent, applies, maximum, BigDecimal.valueOf(facts.hit().record().getAmount()));
			if (applies && maximum != null)
			{
				row.evidence.add("QUALIFIED_MAXIMUM:" + text(rule, "id"));
				BigDecimal existing = number(row.maximum);
				BigDecimal ceiling = existing == null ? maximum : existing.min(maximum);
				if ("source_bound".equals(role))
				{
					row.maximum = value(ceiling);
					row.identityQualified = true;
					row.evidence.add("SOURCE_BOUND:" + text(rule, "id"));
				}
				if (independent && exceeds)
				{
					row.applyMaximum(value(ceiling), facts.hit(), text(rule, "id"));
					row.exclusionScope = "source_bound".equals(role) ? "ALL_DIRECT_HP_COMPONENTS"
						: text(rule, "exclusionScope");
				}
			}
		}
		if (cases.alternativesExclude() && (row.ordinary || !Boolean.TRUE.equals(row.componentQualified)))
		{
			exclude(row, "MAXIMUM_EXCEEDED:QUALIFIED_ALTERNATIVES");
			row.exclusionScope = "ORDINARY_ATTACK";
			row.legacyApiReferenceConflictEligible = OriginalSourceProof.api(facts.source()) && facts.targeted()
				&& facts.nativeScope() == Truth.TRUE;
		}
	}

	/** Alternative cases all have to fail; one independent necessary guard is sufficient. */
	static final class BoundCases
	{
		boolean alternative, all = true, independent;

		void add(boolean guard, boolean qualified, BigDecimal maximum, BigDecimal amount)
		{
			boolean exceeds = qualified && maximum != null && maximum.signum() >= 0 && amount.compareTo(maximum) > 0;
			if (guard)
			{
				independent |= exceeds;
			}
			else
			{
				alternative = true;
				all &= exceeds;
			}
		}

		boolean alternativesExclude()
		{
			return alternative && all;
		}

		boolean excluded()
		{
			return independent || alternativesExclude();
		}
	}

	private void outcomes(List<JsonObject> rules, Facts facts, ResolutionCandidate row)
	{
		if (!known(facts.value("hit", "hitsplatType")))
		{
			return;
		}
		for (JsonObject rule : rules)
		{
			if (!"outcome".equals(text(rule, "role")) || !Boolean.TRUE.equals(bool(rule.get("complete"))))
			{
				continue;
			}
			boolean ordinary = "NPC_ORDINARY_ATTACK".equals(text(object(rule.get("selector")), "kind"));
			JsonObject selector = object(rule.get("selector"));
			if (ordinary && !row.ordinary || !ordinary && !signals(selector, facts)
				|| "NPC_PROJECTILE_COMPONENT".equals(text(selector, "kind")) && !facts.owner(false, true)
				|| "ITEM".equals(text(selector, "kind")) && !facts.input(rule))
			{
				continue;
			}
			String list = facts.hit().record().getAmount() > 0 ? "positiveHitsplatTypes" : "zeroHitsplatTypes";
			if (rule.has(list) && !contains(rule.get(list), facts.value("hit", "hitsplatType")))
			{
				exclude(row, "HITSPLAT_TYPE_MISMATCH:" + text(rule, "id"));
			}
		}
	}

	private JsonObject annotations(Facts facts, ResolutionCandidate row, List<JsonObject> selected)
	{
		List<JsonObject> admitted = new ArrayList<>();
		for (JsonObject rule : selected)
		{
			if ("annotation".equals(text(rule, "role"))
				&& Objects.equals(facts.source() == null ? "hit" : "cue", text(rule, "domain")) && signals(rule, facts)
				&& facts.test(rule.get("when"), row) == Truth.TRUE)
			{
				admitted.add(rule);
			}
		}
		boolean referenceHints = facts.source() != null
			&& (Boolean.TRUE.equals(bool(facts.value("cue", "staticReferenceEligible")))
				|| Boolean.TRUE.equals(bool(facts.value("cue", "hazardReferenceEligible"))));
		return SourceAnnotations.fold(admitted, row, referenceHints);
	}

	private static void reconcile(List<ResolutionCandidate> rows, Hit hit)
	{
		for (ResolutionCandidate weak : rows)
		{
			if (weak.source.channel() == Channel.PROJECTILE_ORIGIN)
			{
				for (ResolutionCandidate classified : rows)
				{
					if (classified.source.channel() == Channel.PROJECTILE
						&& contains(classified.reasons, value("NON_DAMAGING_ACTIVITY"))
						&& known(weak.source.raw().get("observationId"))
						&& equal(weak.source.raw().get("observationId"), classified.source.raw().get("observationId"))
						&& equal(weak.source.raw().get("projectileId"), classified.source.raw().get("projectileId")))
					{
						exclude(weak, "NON_DAMAGING_ACTIVITY");
						weak.evidence.add("ACTIVITY_CLASSIFICATION:" + classified.ruleId);
					}
				}
			}
			if (!"POSSIBLE".equals(weak.status) || Boolean.TRUE.equals(weak.componentQualified)
				|| weak.sourceOnly && !"presence".equals(text(object(weak.rule), "role")))
			{
				continue;
			}
			for (ResolutionCandidate guard : rows)
			{
				boolean ordinaryPose = weak.source.channel() == Channel.HP_ACTOR_POSE
					&& (weak.ordinary || "observed:direct_attack".equals(weak.ruleId))
					&& guard.source.channel() == Channel.ACTOR_CALLBACK && guard.ordinary
					&& known(guard.source.actor().get("animationId"))
					&& equal(guard.source.actor().get("animationId"), weak.source.actor().get("animationId"))
					&& contains(guard.reasons, value("MAXIMUM_EXCEEDED"))
					&& OriginalSourceProof.staticReference(weak.source, hit);
				if ("EXCLUDED".equals(guard.status)
					&& ("ALL_DIRECT_HP_COMPONENTS".equals(guard.exclusionScope) || ordinaryPose)
					&& OriginalSourceProof.identity(guard.source.identity(), weak.source.identity()) == Truth.TRUE
					&& Objects.equals(guard.source.identity().combatLevel, weak.source.identity().combatLevel)
					&& normalize(guard.source.identity().name).equals(normalize(weak.source.identity().name)))
				{
					exclude(weak, ordinaryPose ? "QUALIFIED_ORIGINAL_ORDINARY_COMPONENT_EXCLUSION"
						: "QUALIFIED_WHOLE_SOURCE_EXCLUSION");
				}
			}
		}
	}

	private void applyMaximum(JsonElement limit, Facts facts, ResolutionCandidate row)
	{
		JsonObject declaration = object(limit);
		if (!"verified".equals(text(declaration, "authority")) || facts.test(declaration.get("when")) != Truth.TRUE)
		{
			return;
		}
		BigDecimal maximum = maximum(limit, facts);
		if (maximum != null)
		{
			row.applyMaximum(value(maximum), facts.hit(), row.ruleId);
		}
	}

	private static BigDecimal maximum(JsonElement limit, Facts facts)
	{
		BigDecimal fixed = number(limit);
		if (fixed != null)
		{
			return fixed.signum() < 0 ? null : fixed;
		}
		JsonObject rule = object(limit);
		if ("fixed_max".equals(text(rule, "kind")))
		{
			return number(rule.get("value"));
		}
		if (!"linear_max".equals(text(rule, "kind")))
		{
			return null;
		}
		BigDecimal input = number(facts.value("impact", text(rule, "input")));
		if ("observed_trigger_damage".equals(text(rule, "input")))
		{
			input = number(facts.value("source_event", "observed_trigger_damage"));
		}
		BigDecimal numerator = number(rule.get("numerator")), denominator = number(rule.get("denominator")),
			offset = number(rule.get("offset"));
		if (input == null || input.signum() < 0 || numerator == null || denominator == null || denominator.signum() <= 0
			|| offset == null)
		{
			return null;
		}
		BigDecimal result = input.multiply(numerator).divide(denominator, 16, RoundingMode.HALF_EVEN).add(offset);
		String rounding = text(rule, "rounding");
		return "floor".equals(rounding) ? result.setScale(0, RoundingMode.FLOOR)
			: "ceil".equals(rounding) ? result.setScale(0, RoundingMode.CEILING)
				: "none".equals(rounding) ? result : null;
	}

	private static Truth timing(JsonObject rule, Facts facts)
	{
		if ("unknown".equals(text(rule, "authority")))
		{
			return Truth.UNKNOWN;
		}
		String clock = text(rule, "clock"), anchor = text(rule, "anchor");
		String field = "client_cycle".equals(clock) ? "cycle" : "game_tick".equals(clock) ? "tick" : null;
		BigDecimal begin = field == null ? null : number(facts.value("cue", field));
		if ("projectile_arrival".equals(anchor))
		{
			begin = number(facts.value("cue", "cycle".equals(field) ? "arrival_cycle" : "arrival_tick"));
		}
		BigDecimal end = field == null ? null : number(facts.value("hit", field));
		BigDecimal min = number(rule.get("minimumOffset")), max = number(rule.get("maximumOffset"));
		if (begin == null || end == null || begin.signum() < 0 || end.signum() < 0 || min == null || max == null
			|| min.compareTo(max) > 0)
		{
			return Truth.UNKNOWN;
		}
		BigDecimal offset = end.subtract(begin);
		return truth(offset.compareTo(min) >= 0 && offset.compareTo(max) <= 0);
	}

	private static Truth signature(JsonObject outcome, Facts facts)
	{
		if (!Boolean.TRUE.equals(bool(outcome.get("signatureComplete")))
			|| !"verified".equals(text(outcome, "signatureAuthority")))
		{
			return Truth.UNKNOWN;
		}
		Truth result = Truth.TRUE;
		if (outcome.has("damageFamilies"))
		{
			result = truth(contains(outcome.get("damageFamilies"), value(facts.familyOfHit())));
		}
		if (outcome.has("hitsplatTypeIds"))
		{
			result = result.and(known(facts.value("hit", "hitsplatType"))
				? truth(contains(outcome.get("hitsplatTypeIds"), facts.value("hit", "hitsplatType")))
				: Truth.UNKNOWN);
		}
		return result;
	}

	private static boolean signals(JsonObject rule, Facts facts)
	{
		JsonObject fields = rule.has("signals") ? object(rule.get("signals")) : rule;
		Collection<String> keys = rule.has("signals") ? fields.keySet()
			: Arrays.asList("animationIds", "projectileIds", "graphicIds", "objectIds", "messages", "channels",
				"operations", "interactionOptions", "lifecycleEvents");
		for (String key : keys)
		{
			if (!fields.has(key))
			{
				continue;
			}
			JsonElement observed = facts.signal(key), required = fields.get(key);
			boolean match = false;
			if (observed instanceof JsonArray)
			{
				for (JsonElement candidate : array(observed))
				{
					match |= contains(required, candidate);
				}
			}
			else
			{
				match = contains(required, observed);
			}
			if (!match)
			{
				return false;
			}
		}
		return true;
	}

	private static void exclude(ResolutionCandidate row, String reason)
	{
		row.status = "EXCLUDED";
		row.reasons.add(reason);
		row.scalarEligible = false;
		row.confidence = "UNKNOWN";
	}

	/** A finite matcher view. The runtime implementation reads only original observations. */
	interface Facts
	{
		Hit hit();

		Source source();

		Identity identity();

		Truth nativeScope();

		boolean owned();

		boolean numeric();

		boolean stable();

		String family();

		String mechanism();

		JsonObject actor();

		JsonObject first();

		JsonObject raw();

		Truth test(JsonElement predicate);

		Truth test(JsonElement predicate, ResolutionCandidate row);

		Truth geometry(JsonObject declaration);

		Truth contact();

		Truth sourceActive();

		boolean statusEnvelope();

		boolean owner(boolean nativeSource, boolean noOverrides);

		void scalar(ResolutionCandidate row);

		boolean before(boolean strict);

		boolean sampleBefore();

		boolean targeted();

		boolean direct();

		boolean acceptsPattern(String pattern);

		boolean activityEligible();

		boolean ordinary();

		boolean weak();

		boolean nativeConflict();

		boolean fallback();

		boolean contactRejected();

		String familyOfHit();

		String pattern();

		String mode();

		JsonElement signal(String key);

		boolean normalContext(JsonObject predicate);

		boolean input(JsonObject rule);

		JsonElement value(String domain, String fact);
	}

	final class RawFacts implements Facts
	{
		final Hit hit;
		final Source source;
		final Identity identity;
		final Truth nativeScope;
		final boolean owned, numeric, stable;
		final String family, mechanism;
		final JsonObject actor, first, raw;
		final Source trigger;
		final Integer observedType;

		RawFacts(Hit hit, Source source)
		{
			this(hit, source, source != null && validTrigger(source, hit) ? source : null);
		}

		RawFacts(Hit hit, Source source, Source trigger)
		{
			this.hit = hit;
			this.source = source;
			this.trigger = trigger;
			observedType = observedType(hit);
			identity = source == null ? null : source.identity();
			raw = source == null ? new JsonObject() : source.raw();
			actor = source == null ? new JsonObject() : source.actor();
			first = source == null ? new JsonObject() : source.first();
			nativeScope = source == null || hit.callback() == null ? Truth.UNKNOWN
				: OriginalSourceProof.scope(identity.scope, hit.callback().scope);
			stable = source != null
				&& (source.channel() != Channel.PROJECTILE || OriginalSourceProof.stableOwner(source));
			owned = source != null && (source.channel() == Channel.ACTOR_CALLBACK
				|| source.channel() == Channel.PROJECTILE && OriginalSourceProof.api(source));
			numeric = source != null && source.channel() != Channel.HP_ACTOR_POSE
				&& OriginalSourceProof.numericReference(source, hit) && before(false) && !weak();
			family = source == null || references == null || identity.id == null || !"npc".equals(identity.kind) ? null
				: references.attributionFamily(identity.id, identity.name, identity.combatLevel, numeric);
			String referenceMechanism = source == null || references == null || identity.id == null ? null
				: references.outcomeMechanism(identity.id, integer(actor.get("animationId")),
					integer(raw.get("projectileId")), identity.combatLevel, numeric);
			mechanism = referenceMechanism == null && owned && numeric ? "NPC_DIRECT_SOURCE" : referenceMechanism;
		}

		public Hit hit()
		{
			return hit;
		}

		public Source source()
		{
			return source;
		}

		public Identity identity()
		{
			return identity;
		}

		public Truth nativeScope()
		{
			return nativeScope;
		}

		public boolean owned()
		{
			return owned;
		}

		public boolean numeric()
		{
			return numeric;
		}

		public boolean stable()
		{
			return stable;
		}

		public String family()
		{
			return family;
		}

		public String mechanism()
		{
			return mechanism;
		}

		public JsonObject actor()
		{
			return actor;
		}

		public JsonObject first()
		{
			return first;
		}

		public JsonObject raw()
		{
			return raw;
		}

		public Truth geometry(JsonObject declaration)
		{
			return OriginalSourceProof.geometry(source, hit, declaration);
		}

		public Truth contact()
		{
			return OriginalSourceProof.contact(source, hit);
		}

		public Truth sourceActive()
		{
			return OriginalSourceProof.sourceActive(source, hit);
		}

		public boolean statusEnvelope()
		{
			return OriginalSourceProof.statusEnvelope(source, hit);
		}

		public boolean owner(boolean nativeSource, boolean noOverrides)
		{
			return OriginalSourceProof.owner(source, hit, nativeSource, noOverrides);
		}

		public void scalar(ResolutionCandidate row)
		{
			OriginalSourceProof.scalar(source, hit, row);
		}

		public Truth test(JsonElement predicate)
		{
			return test(predicate, null);
		}

		public Truth test(JsonElement predicate, ResolutionCandidate row)
		{
			return conditions(predicate,
				(at, fact) -> "row".equals(at) && row != null
					? "label".equals(fact) ? ResolverV1.value(row.label) : null
					: "cue".equals(at) && "hazardReferenceEligible".equals(fact) ? ResolverV1.value(row != null
						&& Boolean.TRUE.equals(row.componentQualified) && source.channel() == Channel.HP_CONTACT)
						: value(at, fact));
		}

		public boolean before(boolean strict)
		{
			return source != null && source.receipt() != null && hit.callback() != null
				&& OriginalSourceProof.before(source.receipt().clock, hit.callback().clock, strict);
		}

		public boolean sampleBefore()
		{
			return source != null && source.sample() != null && hit.callback() != null
				&& OriginalSourceProof.before(source.sample().clock, hit.callback().clock, false);
		}

		public boolean activityEligible()
		{
			return source.channel() != Channel.PROJECTILE ? owned
				: source.sample() != null && hit.callback() != null
					&& OriginalSourceProof.scope(source.sample().scope, hit.callback().scope) == Truth.TRUE
					&& sampleBefore()
					&& (owned ? OriginalSourceProof.historyStable(source, true)
						: object(raw.get("sourceHistory")).entrySet().stream()
							.noneMatch(field -> Boolean.TRUE.equals(bool(field.getValue()))));
		}

		public boolean acceptsPattern(String pattern)
		{
			return Objects.equals(pattern, pattern())
				|| "area_impact".equals(pattern) && Arrays.asList(Channel.PROJECTILE, Channel.ACTOR_CALLBACK,
					Channel.HP_CONTACT, Channel.FRAME_SURFACE, Channel.GRAPHIC_BIRTH).contains(source.channel())
				|| "contact_hazard".equals(pattern) && Arrays
					.asList(Channel.FRAME_NPC, Channel.FRAME_SURFACE, Channel.GRAPHIC_BIRTH).contains(source.channel());
		}

		public boolean targeted()
		{
			String recipient = hit.record().getDefenderRef();
			return recipient != null && (recipient.equals(text(raw, "targetRef"))
				|| recipient.equals(text(object(actor.get("interaction")), "actorRef")));
		}

		public boolean direct()
		{
			if (source == null)
			{
				return false;
			}
			if (source.channel() == Channel.ACTOR_CALLBACK || source.channel() == Channel.HP_ACTOR_POSE)
			{
				Integer animation = integer(actor.get("animationId"));
				return animation != null && animation > 0 && targeted() && before(false) && source.receipt() != null
					&& source.receipt().clock.tick != null
					&& source.receipt().clock.tick >= (long) hit.record().getImpactTick() - 1
					&& (source.channel() == Channel.HP_ACTOR_POSE || source.receipt().event != null
						&& "ANIMATION_CHANGED".equals(source.receipt().event.getType()) && !endedActivity());
			}
			return source.channel() == Channel.PROJECTILE || source.channel() == Channel.PROJECTILE_ORIGIN;
		}

		private boolean endedActivity()
		{
			return hit.sources().stream()
				.anyMatch(later -> later.channel() == Channel.ACTOR_CALLBACK && later.receipt() != null
					&& later.receipt().event != null && "ANIMATION_CHANGED".equals(later.receipt().event.getType())
					&& later.receipt().clock.tick != null && later.receipt().clock.tick < hit.record().getImpactTick()
					&& OriginalSourceProof.identity(source.identity(), later.identity()) == Truth.TRUE
					&& integer(later.actor().get("animationId")) != null
					&& integer(later.actor().get("animationId")) <= 0
					&& OriginalSourceProof.before(source.receipt().clock, later.receipt().clock, true));
		}

		public boolean ordinary()
		{
			return "ORDINARY_ATTACK".equals(mechanism) || identity != null && identity.id != null
				&& index.getOrDefault("npc:" + identity.id, Collections.emptyList()).stream().noneMatch(
					rule -> "component".equals(text(rule, "role")) && !Boolean.TRUE.equals(bool(rule.get("profile"))));
		}

		public boolean weak()
		{
			return source != null && Arrays.asList(Channel.FRAME_NPC, Channel.FRAME_SURFACE, Channel.NPC_EMITTER,
				Channel.PROJECTILE_ORIGIN, Channel.INVENTORY, Channel.RESOURCE_INPUT).contains(source.channel());
		}

		public boolean nativeConflict()
		{
			return ResolverV1.nativeConflict(hit, source,
				source.channel() == Channel.PROJECTILE && OriginalSourceProof.api(source));
		}

		public boolean fallback()
		{
			return !nativeConflict() && source != null && sourceKey(identity) != null
				&& !OriginalSourceProof.expiredCarrier(source, hit)
				&& (source.channel() != Channel.PROJECTILE || OriginalSourceProof.api(source))
				&& (!Arrays.asList(Channel.ACTOR_CALLBACK, Channel.HP_ACTOR_POSE).contains(source.channel())
					|| OriginalSourceProof.sourceActive(source, hit) != Truth.FALSE)
				&& (!raw.has("targetRef") || Objects.equals(text(raw, "targetRef"), hit.record().getDefenderRef()))
				&& (unavailableHpActivity() || direct() && (source.channel() != Channel.HP_ACTOR_POSE || targeted())
					|| source.channel() == Channel.PROJECTILE_ORIGIN);
		}

		private boolean unavailableHpActivity()
		{
			return source.channel() == Channel.HP_ACTOR_POSE && targeted() && integer(actor.get("animationId")) == null
				&& integer(raw.get("animationId")) == null
				&& Arrays.asList("NPC_ACTIVITY_OBSERVATION", "PLAYER_ACTIVITY_OBSERVATION").contains(text(raw, "kind"))
				&& "HITSPLAT_CALLBACK".equals(text(raw, "observationStage"))
				&& "NATIVE_ACTOR_COLLECTION_MEMBERSHIP".equals(text(raw, "sourceCollectionEvidence"))
				&& source.receipt() != null && hit.callback() != null && source.receipt().clock.sequence != null
				&& source.receipt().clock.sequence > 0
				&& Objects.equals(source.receipt().clock.sequence, hit.callback().clock.sequence)
				&& Objects.equals(source.receipt().clock.tick, hit.callback().clock.tick)
				&& Objects.equals(source.receipt().clock.cycle, hit.callback().clock.cycle);
		}

		public boolean contactRejected()
		{
			return OriginalSourceProof.observedBirthAfterImpact(source, hit)
				|| Boolean.FALSE.equals(bool(raw.get("damaging"))) || Boolean.TRUE.equals(bool(raw.get("finished")))
				|| Arrays.asList("inactive", "warning").contains(text(raw, "state"))
				|| Arrays.asList("NOT_DAMAGE_SURFACE", "RESOURCE_REFERENCE_ONLY", "DEFINITION_NOT_QUALIFIED",
					"UNCLASSIFIED_GRAPHIC_SURFACE").contains(text(raw, "sourceEligibility"))
				|| number(raw.get("startCycle")) != null && hit.record().getObservedCycle() != null
					&& number(raw.get("startCycle")).compareTo(BigDecimal.valueOf(hit.record().getObservedCycle())) > 0;
		}

		public String familyOfHit()
		{
			if (observedType == null)
			{
				return null;
			}
			String definite = references == null ? null : references.hitsplatDamageType(observedType);
			if (Arrays.asList("poison", "venom", "burn", "bleed").contains(definite))
			{
				return definite;
			}
			List<String> families = references == null ? Collections.emptyList()
				: references.possibleStatusFamilies(observedType);
			return families.size() == 1 ? families.get(0) : "ordinary";
		}

		public String pattern()
		{
			if (trigger != null)
			{
				return "retaliation";
			}
			switch (source.channel())
			{
			case PROJECTILE:
				return "projectile_impact";
			case HP_CONTACT:
			case FRAME_NPC:
			case FRAME_SURFACE:
				return "contact_hazard";
			case STATUS:
			case TYPED_STATUS:
				return "status_tick";
			case INPUT:
			case CONSUMPTION:
			case RESOURCE_INPUT:
			case MESSAGE:
				return "interaction_damage";
			case OUTGOING_TRIGGER:
				return null;
			case GRAPHIC_BIRTH:
				return "area_impact";
			case ACTOR_LIFECYCLE:
				return "delayed_effect";
			default:
				return "direct_attack";
			}
		}

		public String mode()
		{
			switch (source.channel())
			{
			case PROJECTILE:
				return "API_PROJECTILE";
			case PROJECTILE_ORIGIN:
			case ACTOR_CALLBACK:
			case HP_ACTOR_POSE:
				return "ACTOR_ACTIVITY";
			case HP_CONTACT:
			case FRAME_NPC:
			case FRAME_SURFACE:
			case GRAPHIC_BIRTH:
				return "ORIGINAL_CONTACT";
			case ACTOR_LIFECYCLE:
				return "RETAINED_LIFECYCLE";
			case INPUT:
				return "INPUT_REQUEST";
			case CONSUMPTION:
				return "CONSUMPTION";
			case RESOURCE_INPUT:
				return "RESOURCE_INPUT";
			case INVENTORY:
				return "INVENTORY_POSSESSION";
			case MESSAGE:
				return "SYSTEM_RESPONSE";
			case STATUS:
				return "STATUS_ENVELOPE";
			default:
				return null;
			}
		}

		public JsonElement signal(String key)
		{
			if (source == null)
			{
				return null;
			}
			switch (key)
			{
			case "animationIds":
				return actor.get("animationId");
			case "projectileIds":
				return raw.get("projectileId");
			case "graphicIds":
				return ResolverV1
					.value("graphic".equals(identity.kind) ? identity.id : integer(actor.get("graphicId")));
			case "objectIds":
				return ResolverV1.value("object".equals(identity.kind) ? identity.id : null);
			case "messages":
				return ResolverV1
					.value(text(raw, "message") == null ? null : text(raw, "message").replaceAll("<[^>]*>", ""));
			case "channels":
				return raw.get("channel");
			case "operations":
			case "interactionOptions":
				return option();
			case "lifecycleEvents":
				String event = source.receipt() == null || source.receipt().event == null ? null
					: source.receipt().event.getType();
				return ResolverV1.value("GRAPHICS_OBJECT_CREATED".equals(event) ? "graphic_spawn"
					: "NPC_DESPAWNED".equals(event) ? "npc_despawn"
						: "ACTOR_DEATH".equals(event) ? "actor_death"
							: "NPC_SPAWNED".equals(event) ? "npc_spawn" : event);
			default:
				return null;
			}
		}

		public boolean normalContext(JsonObject predicate)
		{
			if (!OriginalSourceProof.originalPosition(hit))
			{
				return false;
			}
			JsonObject context = hit.callback() == null ? new JsonObject()
				: object(hit.callback().raw.get("numericContext"));
			return ObservedHpCallbackContext.normal(gson.fromJson(context, Map.class),
				strings(predicate.get("requiredZeroVariables")), strings(predicate.get("excludedWorldTypeFragments")));
		}

		public boolean input(JsonObject rule)
		{
			JsonObject selector = object(rule.get("selector"));
			return source != null && Arrays.asList(Channel.INPUT, Channel.CONSUMPTION).contains(source.channel())
				&& (!selector.has("inputSourceRef")
					|| Objects.equals(text(selector, "inputSourceRef"), text(raw, "sourceRef")))
				&& (!selector.has("pageHash") || Objects.equals(text(selector, "pageHash"), text(raw, "pageHash")))
				&& contains(selector.get("operations"), option());
		}

		public JsonElement value(String at, String fact)
		{
			if (fact == null)
			{
				return null;
			}
			if ("hit".equals(at))
			{
				DamageRecord r = hit.record();
				switch (fact)
				{
				case "amount":
					return ResolverV1.value(r.getAmount());
				case "tick":
					return ResolverV1.value(r.getImpactTick());
				case "cycle":
					return ResolverV1.value(r.getObservedCycle());
				case "sequence":
					return ResolverV1.value(r.getObservationSequence());
				case "hitsplatType":
					return ResolverV1.value(observedType);
				case "family":
					return ResolverV1.value(familyOfHit());
				case "recipientRef":
					return ResolverV1.value(r.getDefenderRef());
				case "position.x":
					return Boolean.TRUE.equals(r.getImpactPositionKnown()) ? ResolverV1.value(r.getImpactX()) : null;
				case "position.y":
					return Boolean.TRUE.equals(r.getImpactPositionKnown()) ? ResolverV1.value(r.getImpactY()) : null;
				case "scope.sceneKey":
					return ResolverV1.value(r.getImpactSceneKey());
				case "scope.worldViewId":
					return ResolverV1.value(r.getImpactWorldViewId());
				case "scope.plane":
					return ResolverV1.value(r.getImpactPlane());
				case "originalHpCallback":
					return hit.callback() == null ? null : hit.callback().raw;
				default:
					return hit.callback() == null ? null : path(hit.callback().raw, fact);
				}
			}
			if (source == null)
			{
				return null;
			}
			if ("source".equals(at))
			{
				switch (fact)
				{
				case "id":
					return ResolverV1.value(identity.id);
				case "kind":
					return ResolverV1.value(identity.kind);
				case "name":
					return ResolverV1.value(identity.name);
				case "combatLevel":
					return ResolverV1.value(identity.combatLevel);
				case "actorRef":
					return ResolverV1.value(identity.actorRef);
				case "familyKey":
					return ResolverV1.value(family);
				case "scope.sceneKey":
					return ResolverV1.value(identity.scope == null ? null : identity.scope.sceneKey);
				case "scope.worldViewId":
					return ResolverV1.value(identity.scope == null ? null : identity.scope.worldViewId);
				case "scope.plane":
					return ResolverV1.value(identity.scope == null ? null : identity.scope.plane);
				default:
					return null;
				}
			}
			if ("source_event".equals(at))
			{
				JsonObject original = first.size() > 0 ? first : actor;
				switch (fact)
				{
				case "world_mode":
					return Boolean.TRUE.equals(bool(original
						.get(first.size() > 0 ? "sourceNormalWorldContextObserved" : "normalWorldContextObserved")))
							? ResolverV1.value("NORMAL")
							: null;
				case "source_model_overrides":
					return original.get(first.size() > 0 ? "sourceModelOverridesPresent" : "modelOverridesPresent");
				case "encounter_activity":
					return source.frame() == null || source.frame().getContext() == null ? null
						: ResolverV1.value(source.frame().getContext().getActivity());
				case "encounter_mode":
					return mode(source.frame());
				case "trigger_target_npc_id":
					return trigger == null ? null : ResolverV1.value(trigger.identity().id);
				case "trigger_attack_style":
					return trigger == null || references == null
						|| !Boolean.TRUE.equals(bool(trigger.raw().get("hitsplatTypeKnown")))
						|| integer(trigger.raw().get("hitsplatType")) == null ? null
							: ResolverV1.value(
								references.hitsplatNpcDamageReductionStyle(integer(trigger.raw().get("hitsplatType"))));
				case "observed_trigger_damage":
					return trigger == null ? null : trigger.raw().get("amount");
				default:
					return path(original, fact);
				}
			}
			if ("cue".equals(at))
			{
				if (fact.startsWith("signals."))
				{
					JsonElement observed = signal(fact.substring(8));
					if (!known(observed) || observed instanceof JsonArray)
					{
						return observed;
					}
					JsonArray values = new JsonArray();
					values.add(observed);
					return values;
				}
				if (fact.startsWith("originalSourceObservation."))
				{
					return path(first, fact.substring("originalSourceObservation.".length()));
				}
				if (fact.startsWith("witness."))
				{
					return path(raw, fact.substring(8));
				}
				if (fact.startsWith("originalActorState."))
				{
					return path(actor, fact.substring(19));
				}
				switch (fact)
				{
				case "tick":
					return ResolverV1.value(source.receipt() == null ? null : source.receipt().clock.tick);
				case "cycle":
					return ResolverV1.value(source.receipt() == null ? null : source.receipt().clock.cycle);
				case "arrival_cycle":
					return raw.get("endCycle");
				case "arrival_tick":
					return raw.get("estimatedImpactTick");
				case "sequence":
					return ResolverV1.value(source.receipt() == null ? null : source.receipt().clock.sequence);
				case "mechanism":
					return ResolverV1.value(mechanism);
				case "damageComponent":
					return direct() ? ResolverV1.value("SINGLE_HIT") : null;
				case "worldMode":
					return numeric ? ResolverV1.value("NORMAL") : null;
				case "targetRef":
					return raw.has("targetRef") ? raw.get("targetRef")
						: object(actor.get("interaction")).get("actorRef");
				case "componentBound":
					return weak() || source.channel() == Channel.HP_ACTOR_POSE || !before(false)
						|| source.channel() == Channel.PROJECTILE && !stable ? ResolverV1.value(false) : null;
				case "actorCallbackObserved":
					return ResolverV1.value(source.channel() == Channel.ACTOR_CALLBACK);
				case "actorAnimationObserved":
					return ResolverV1
						.value((source.channel() == Channel.ACTOR_CALLBACK || source.channel() == Channel.HP_ACTOR_POSE)
							&& integer(actor.get("animationId")) != null && integer(actor.get("animationId")) > 0);
				case "nativePresenceObserved":
					return ResolverV1
						.value(identity.actorRef != null && identity.id != null && "npc".equals(identity.kind)
							&& (source.channel() == Channel.NPC_EMITTER
								&& "HITSPLAT_CALLBACK".equals(text(raw, "observationStage"))
								&& "DECLARED_SOURCE_ASSOCIATION".equals(text(raw, "sourceEligibility"))
								&& "NPC_WORLD_VIEW_READ".equals(text(raw, "scopeEvidence"))
								&& Objects.equals(integer(raw.get("observedCycle")), hit.record().getObservedCycle())
								|| source.channel() == Channel.FRAME_NPC && source.receipt() != null
									&& Objects.equals(source.receipt().clock.tick, hit.record().getImpactTick())
									&& before(true)));
				case "staticReferenceEligible":
					return ResolverV1.value(OriginalSourceProof.staticReference(source, hit) && identity.id != null
						&& (references == null || references.observedNameCompatible(identity.id, identity.name, true)));
				case "symbolReferenceEligible":
					return ResolverV1.value(stable && before(false) && integer(raw.get("projectileId")) != null);
				case "animationReferenceEligible":
					return ResolverV1.value(
						"player".equals(identity.kind) && before(false) && integer(actor.get("animationId")) != null);
				case "hazardReferenceEligible":
					return ResolverV1.value(source.channel() == Channel.HP_CONTACT && nativeScope == Truth.TRUE);
				case "position.x":
					return actor.has("worldX") ? actor.get("worldX") : raw.get("worldX");
				case "position.y":
					return actor.has("worldY") ? actor.get("worldY") : raw.get("worldY");
				default:
					return path(raw, fact);
				}
			}
			if ("interaction".equals(at) && "interaction_option".equals(fact))
			{
				return option();
			}
			if ("impact".equals(at) && "source_active".equals(fact))
			{
				Truth active = sourceActive();
				return known(raw.get("damaging")) ? raw.get("damaging")
					: active == Truth.UNKNOWN ? null : ResolverV1.value(active == Truth.TRUE);
			}
			if ("attack".equals(at) || "impact".equals(at))
			{
				WorldSnapshot original = "attack".equals(at) ? source.frame() : hit.impact();
				if ("encounter_mode".equals(fact))
				{
					return mode(original);
				}
				if ("source_state".equals(fact))
				{
					return raw.get("state");
				}
				PlayerState player = original == null ? null : original.getPlayer();
				if (player != null && "player_prayers".equals(fact)
					&& Boolean.TRUE.equals(player.getPrayerStateKnown()))
				{
					return gson.toJsonTree(player.getActivePrayers());
				}
				if (player != null && "player_equipment_ids".equals(fact) && player.getEquipmentItemIds() != null
					&& player.getEquipmentSlotKnown() != null
					&& player.getEquipmentItemIds().length == player.getEquipmentSlotKnown().length)
				{
					for (boolean known : player.getEquipmentSlotKnown())
					{
						if (!known)
						{
							return null;
						}
					}
					return gson.toJsonTree(player.getEquipmentItemIds());
				}
			}
			if ("observations".equals(at))
			{
				boolean variant = numeric && references != null && identity.id != null && family != null
					&& references.attributionBaseProfile(identity.id, identity.combatLevel, family);
				switch (fact)
				{
				case "NORMAL_WORLD_CONTEXT":
				case "STANDARD_DAMAGE_MODIFIERS":
					return ResolverV1.value(numeric);
				case "NO_MODEL_OVERRIDES_OBSERVED":
					return ResolverV1.value(numeric);
				case "QUALIFIED_NPC_VARIANT":
					return ResolverV1.value(variant);
				case "SINGLE_HIT_COMPONENT":
					return ResolverV1
						.value(hit.callback() != null && Boolean.TRUE.equals(hit.record().getHitsplatTypeKnown())
							&& "ordinary".equals(familyOfHit()));
				case "NPC_DIRECT_SOURCE_HYPOTHESIS":
					return ResolverV1.value(owned && numeric && identity.actorRef != null);
				case "ORDINARY_ATTACK_COMPONENT":
				case "ORDINARY_ATTACK_CUE":
					return ResolverV1.value(owned && variant && "ORDINARY_ATTACK".equals(mechanism));
				case "BASE_PROFILE_ENVELOPE_APPLICABLE":
					return ResolverV1.value(variant && "ORDINARY_ATTACK".equals(mechanism) && hit.impact() != null
						&& references.baseProfileContextKnown(hit.impact().getContext(),
							Arrays.asList("toa", "cox", "tob", "colosseum")));
				default:
					return null;
				}
			}
			if ("numericFacts".equals(at))
			{
				if ("source.sample.combatLevel".equals(fact))
				{
					return numeric ? ResolverV1.value(identity.combatLevel) : null;
				}
				if ("source.apiProjectileCorrelated".equals(fact))
				{
					return numeric && targeted() && OriginalSourceProof.api(source) ? ResolverV1.value(1) : null;
				}
				if (fact.startsWith("context.activity.") && source.frame() != null
					&& source.frame().getContext() != null)
				{
					String activity = source.frame().getContext().getActivity();
					return activity == null ? null : ResolverV1.value(activity.equals(fact.substring(17)) ? 1 : 0);
				}
			}
			JsonObject context = hit.callback() == null ? new JsonObject()
				: object(hit.callback().raw.get("numericContext"));
			return path(context, fact);
		}

		private JsonElement mode(WorldSnapshot frame)
		{
			ContextBlock context = frame == null ? null : frame.getContext();
			String evidence = context == null || context.getEvidence() == null ? null
				: context.getEvidence().get("challengeMode");
			return context != null && Arrays.asList("NORMAL", "HARD", "QUEST").contains(context.getChallengeMode())
				&& evidence != null && evidence.startsWith("OBSERVED_ACTIVE_") && !evidence.contains("UNESTABLISHED")
				&& !evidence.contains("UNAVAILABLE") && !evidence.contains("SELECTED")
					? ResolverV1.value(context.getChallengeMode())
					: null;
		}

		private JsonElement option()
		{
			return known(raw.get("option")) ? raw.get("option")
				: source.receipt() == null ? null : source.receipt().raw.get("option");
		}
	}

	static Truth conditions(JsonElement expression, BiFunction<String, String, JsonElement> facts)
	{
		return conditions(expression, facts, 0, new int[1]);
	}

	private static Integer observedType(Hit hit)
	{
		DamageRecord record = hit.record();
		if (Boolean.FALSE.equals(record.getHitsplatTypeKnown()))
		{
			return null;
		}
		if (Boolean.TRUE.equals(record.getHitsplatTypeKnown()))
		{
			return record.getHitsplatType();
		}
		Receipt callback = hit.callback();
		return callback != null && callback.clock != null && Objects.equals(callback.clock.tick, record.getImpactTick())
			&& Objects.equals(callback.clock.cycle, record.getObservedCycle())
			&& Objects.equals(callback.clock.sequence, record.getObservationSequence())
			&& Objects.equals(integer(callback.raw.get("amount")), record.getAmount())
			&& Objects.equals(integer(callback.raw.get("hitsplatType")), record.getHitsplatType())
			&& OriginalSourceProof.clean(callback.raw, "captureIssues", "hitsplatType", "hitsplat.type")
				? record.getHitsplatType()
				: null;
	}

	private static Truth conditions(JsonElement expression, BiFunction<String, String, JsonElement> facts, int depth,
		int[] count)
	{
		if (depth > 12 || ++count[0] > MAX_CUES)
		{
			return Truth.UNKNOWN;
		}
		if (!known(expression))
		{
			return Truth.TRUE;
		}
		if (expression instanceof JsonArray || expression instanceof JsonObject && !object(expression).has("at"))
		{
			JsonObject group = object(expression);
			boolean any = group.has("any");
			if (!(expression instanceof JsonArray) && (group.size() != 1 || !any && !group.has("all")))
			{
				return Truth.UNKNOWN;
			}
			JsonArray children = expression instanceof JsonArray ? array(expression)
				: array(group.get(any ? "any" : "all"));
			Truth result = any ? Truth.FALSE : Truth.TRUE;
			for (JsonElement child : children)
			{
				Truth evaluated = conditions(child, facts, depth + 1, count);
				result = any ? result.or(evaluated) : result.and(evaluated);
			}
			return result;
		}
		JsonObject condition = object(expression);
		String op = text(condition, "op"), at = text(condition, "at"), name = text(condition, "fact");
		if (name == null || name.length() > 256 || name.split("\\.").length > 12)
		{
			return Truth.UNKNOWN;
		}
		JsonElement actual = facts.apply(at, name), expected = condition.get("value");
		if (condition.has("valueFrom"))
		{
			JsonObject from = object(condition.get("valueFrom"));
			expected = facts.apply(text(from, "at"), text(from, "fact"));
		}
		if ("not_true".equals(op) || "not_false".equals(op))
		{
			return !known(actual) ? Truth.TRUE
				: bool(actual) == null ? Truth.UNKNOWN : truth("not_true".equals(op) != bool(actual));
		}
		if ("nullable_string_equals".equals(op))
		{
			return !known(actual) && !known(expected) ? Truth.TRUE
				: primitive(actual) == null || primitive(expected) == null ? Truth.UNKNOWN
					: truth(equal(actual, expected));
		}
		if ("no_issue".equals(op))
		{
			if (!(expected instanceof JsonArray)
				|| known(actual) && !(actual instanceof JsonArray) && !(actual instanceof JsonObject))
			{
				return Truth.UNKNOWN;
			}
			if (array(expected).size() == 0 || array(expected).size() > MAX_CUES || array(actual).size() > MAX_CUES
				|| actual instanceof JsonObject && object(actual).size() > MAX_CUES)
			{
				return Truth.UNKNOWN;
			}
			for (JsonElement prefix : array(expected))
			{
				if (primitive(prefix) == null || prefix.getAsString().isEmpty() || prefix.getAsString().length() > 128)
				{
					return Truth.UNKNOWN;
				}
			}
			for (JsonElement issue : array(actual))
			{
				if (primitive(issue) == null)
				{
					return Truth.UNKNOWN;
				}
			}
			JsonObject owner = new JsonObject();
			owner.add("issues", actual);
			return truth(OriginalSourceProof.clean(owner, "issues", strings(expected).toArray(new String[0])));
		}
		if (!known(actual))
		{
			return Truth.UNKNOWN;
		}
		if ("type_is".equals(op))
		{
			String type = primitive(expected);
			if (!Arrays.asList("number", "integer", "boolean", "string", "nonempty_object").contains(type))
			{
				return Truth.UNKNOWN;
			}
			return truth("number".equals(type) ? number(actual) != null
				: "integer".equals(type) ? integer(actual) != null
					: "boolean".equals(type) ? bool(actual) != null
						: "string".equals(type) ? primitive(actual) != null
							: actual instanceof JsonObject && object(actual).size() > 0);
		}
		if (!known(expected))
		{
			return Truth.UNKNOWN;
		}
		if ("equals".equals(op))
		{
			return truth(equal(actual, expected));
		}
		if ("one_of".equals(op))
		{
			return truth(contains(expected, actual));
		}
		if ("contains_any".equals(op) || "contains_all".equals(op))
		{
			if (!(actual instanceof JsonArray))
			{
				return Truth.UNKNOWN;
			}
			boolean all = "contains_all".equals(op), result = all;
			for (JsonElement value : array(expected))
			{
				result = all ? result && contains(actual, value) : result || contains(actual, value);
			}
			return truth(result);
		}
		BigDecimal a = number(actual), b = number(expected);
		if (a == null || b == null)
		{
			return Truth.UNKNOWN;
		}
		int order = a.compareTo(b);
		return "gt".equals(op) ? truth(order > 0)
			: "gte".equals(op) ? truth(order >= 0)
				: "lt".equals(op) ? truth(order < 0) : "lte".equals(op) ? truth(order <= 0) : Truth.UNKNOWN;
	}

	static JsonElement path(JsonObject root, String path)
	{
		if (path == null || path.length() > 256 || path.split("\\.").length > 12)
		{
			return null;
		}
		if (root.has(path))
		{
			return root.get(path);
		}
		JsonElement value = root;
		for (String field : path.split("\\."))
		{
			value = value instanceof JsonObject ? object(value).get(field) : null;
		}
		return value;
	}

	static boolean contains(JsonElement array, JsonElement value)
	{
		if (!known(value))
		{
			return false;
		}
		for (JsonElement entry : ResolverEvidence.array(array))
		{
			if (equal(entry, value))
			{
				return true;
			}
		}
		return false;
	}

	static JsonElement value(Object value)
	{
		return value == null ? null
			: value instanceof Boolean ? new JsonPrimitive((Boolean) value)
				: value instanceof Number ? new JsonPrimitive((Number) value) : new JsonPrimitive(value.toString());
	}

	static List<String> strings(JsonElement values)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement value : array(values))
		{
			if (primitive(value) != null)
			{
				result.add(value.getAsString());
			}
		}
		return result;
	}

	private static String primitive(JsonElement value)
	{
		return known(value) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	static String normalize(String value)
	{
		return value == null ? "" : value.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
	}

	private static Truth truth(boolean value)
	{
		return value ? Truth.TRUE : Truth.FALSE;
	}

	static String digest(byte[] bytes)
	{
		try
		{
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder result = new StringBuilder();
			for (byte value : digest)
			{
				result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
			}
			return result.toString();
		}
		catch (java.security.NoSuchAlgorithmException unavailable)
		{
			throw new IllegalStateException(unavailable);
		}
	}

	static JsonObject object(JsonElement value)
	{
		return ResolverEvidence.object(value);
	}

	static JsonArray array(JsonElement value)
	{
		return ResolverEvidence.array(value);
	}

	static String text(JsonObject object, String key)
	{
		return ResolverEvidence.text(object, key);
	}

	static boolean known(JsonElement value)
	{
		return ResolverEvidence.known(value);
	}

	static Boolean bool(JsonElement value)
	{
		return ResolverEvidence.bool(value);
	}

	static BigDecimal number(JsonElement value)
	{
		return ResolverEvidence.number(value);
	}

	static Integer integer(JsonElement value)
	{
		return ResolverEvidence.integer(value);
	}

	static boolean equal(JsonElement a, JsonElement b)
	{
		return ResolverEvidence.equal(a, b);
	}
}
