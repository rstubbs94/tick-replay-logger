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

import com.google.gson.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.model.world.SourceResolution.*;
import com.tickreplaylogger.util.*;
import java.math.*;
import java.util.*;
import java.util.function.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Groups already evaluated source alternatives for consistent retrospective presentation. */
public final class SourceEvidenceResolver
{
	public static final int MAX_HYPOTHESES = 256;

	private SourceEvidenceResolver()
	{
	}

	/** All derived display fields come from the same admitted components and original receipts. */
	static DamageRecord project(Hit hit, ResolutionResult result, String catalogId)
	{
		DamageRecord original = hit.record();
		SourceResolution resolution = project(result, catalogId);
		DamageRecord.DamageRecordBuilder display = original.toBuilder().sourceResolution(resolution)
			.attackerName("Unknown").attackerRef(null).attackerNpcId(null).attackerCombatLevel(null)
			.attribution("UNKNOWN").attributionEvidence("UNRESOLVED_OBSERVATIONS").damageType("unknown")
			.damageTypeEvidence("UNKNOWN").damageTypeRuleKey(null).damageTypeCatalogId(null).damageReferenceKey(null)
			.damagePropertyEvidence(null)
			.damageTypeCandidates(original.getDamageTypeCandidates() == null ? null : Collections.emptyList())
			.attackStyleName(null).prayerEffect(null).pvp(false).determination("UNKNOWN").determinationEvidence(null)
			.determinationRuleKey(null).mapRef(null).launchTick(original.getImpactTick()).launchTickEstimated(true)
			.launchStateEvidence("UNAVAILABLE").launchPositionKnown(false).launchPrayerStateKnown(false)
			.prayersAtLaunch(null).overheadAtLaunch(null).launchX(0).launchY(0);
		JsonObject observed = object(result.hitAnnotations);
		String observedType = text(observed, "damageType");
		if (observedType != null)
		{
			display.damageType(observedType).damageTypeEvidence(text(observed, "damageTypeEvidence"))
				.attackerName(text(observed, "attackerName")).attribution("ENVIRONMENTAL")
				.attributionEvidence("STATUS_HITSPLAT");
		}
		display.prayerEffect(text(observed, "prayerEffect")).damageReferenceKey(text(observed, "damageReferenceKey"))
			.damagePropertyEvidence(text(observed, "damagePropertyEvidence"));
		List<ResolutionCandidate> rows = new ArrayList<>();
		Set<String> qualified = new LinkedHashSet<>();
		for (ResolutionCandidate row : result.candidates)
		{
			if ("POSSIBLE".equals(row.status))
			{
				rows.add(row);
				if (Boolean.TRUE.equals(row.componentQualified))
				{
					qualified.add(row.source.key());
				}
			}
		}
		List<ResolutionCandidate> admitted = new ArrayList<>(rows);
		rows.removeIf(row -> !Boolean.TRUE.equals(row.componentQualified)
			&& (qualified.contains(row.source.key()) || referenceOnly(row.source) && admitted.stream()
				.anyMatch(other -> !referenceOnly(other.source) && sameObservedReference(row.source, other.source))));
		if (rows.stream().anyMatch(row -> row.source.channel() != Channel.TYPED_STATUS))
		{
			rows.removeIf(row -> row.source.channel() == Channel.TYPED_STATUS);
		}
		Set<String> hints = new LinkedHashSet<>(strings(observed.get("candidateStyles")));
		for (ResolutionCandidate row : rows)
		{
			hints.addAll(strings(object(row.derivedAnnotations).get("candidateStyles")));
		}
		if (!hints.isEmpty() && resolution.getCandidates().size() <= 1)
		{
			display.damageTypeCandidates(new ArrayList<>(hints));
		}
		if (rows.isEmpty() || resolution.getCandidates().isEmpty())
		{
			return display.build();
		}
		boolean typed = Arrays.asList("poison", "venom", "burn", "bleed").contains(observedType);
		String confidence = consensus(rows,
			row -> Boolean.TRUE.equals(row.scalarEligible) ? row.confidence : "UNKNOWN");
		boolean scalar = resolution.getCandidates().size() == 1 && !"AMBIGUOUS".equals(result.scalarAssociation)
			&& confidence != null && !"UNKNOWN".equals(confidence);
		long relationships = rows.stream().map(row -> supportChannel(row.source)).distinct().count();
		if (!typed)
		{
			if (scalar)
			{
				display.attackerName(SourceResolutionLabels.label(resolution)).attribution(confidence)
					.pvp(rows.stream().allMatch(row -> "player".equals(row.source.identity().kind)));
				Set<String> evidence = new LinkedHashSet<>();
				rows.stream().map(row -> row.attributionEvidence).filter(Objects::nonNull).forEach(evidence::add);
				if (evidence.size() == 1)
				{
					display.attributionEvidence(evidence.iterator().next());
				}
			}
			else if ("AMBIGUOUS".equals(result.scalarAssociation) || resolution.getCandidates().size() > 1)
			{
				display.attributionEvidence(
					resolution.getCandidates().size() > 1 || relationships > 1 ? "MULTIPLE_POSSIBLE_DAMAGE_SOURCES"
						: "SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED");
			}
			String actor = consensus(rows, row -> row.source.identity().actorRef);
			if (actor != null && relationships > 1 && !"AMBIGUOUS".equals(result.scalarAssociation))
			{
				display.attackerName(SourceResolutionLabels.label(resolution))
					.attributionEvidence("SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES");
			}
			if (resolution.getCandidates().size() == 1
				&& rows.stream().anyMatch(row -> row.source.channel() != Channel.PROJECTILE_ORIGIN))
			{
				Candidate source = resolution.getCandidates().get(0);
				display.attackerRef(source.getActorRef()).attackerNpcId(source.getNpcId());
			}
		}
		String type = annotation(rows, "damageType"), timing = annotation(rows, "determination");
		// A reference to the activity observed in the HP callback can describe its type
		// without certifying an attacker. An unassigned in-flight projectile cannot.
		boolean typeSupported = !"AMBIGUOUS".equals(result.scalarAssociation) && rows.stream()
			.allMatch(row -> row.source.channel() == Channel.HP_ACTOR_POSE || Boolean.TRUE.equals(row.scalarEligible));
		if (!typed && typeSupported && type != null && !type.contains("|"))
		{
			String evidence = annotation(rows, "damageTypeEvidence");
			display.damageType(type.toLowerCase(Locale.ROOT))
				.damageTypeEvidence(evidence == null ? "SOURCE_REFERENCE" : evidence);
		}
		if (!typed && scalar && timing != null && !"UNKNOWN".equals(timing) && !"typeless".equalsIgnoreCase(type))
		{
			display.determination(timing).determinationEvidence("REVIEWED_ATTACK_CONTEXT")
				.determinationRuleKey(annotation(rows, "determinationRuleKey"));
		}
		if (!typed && typeSupported)
		{
			display.attackStyleName(annotation(rows, "attackStyleName"));
		}
		display.damageTypeRuleKey(annotation(rows, "damageTypeRuleKey"))
			.damageTypeCatalogId(annotation(rows, "damageTypeCatalogId"));
		Integer level = consensus(rows, row -> row.source.identity().combatLevel);
		display.attackerCombatLevel(level);
		Set<String> maps = new LinkedHashSet<>();
		for (ResolutionCandidate row : rows)
		{
			String map = text(object(row.derivedAnnotations), "mapRef");
			if (map != null)
			{
				maps.add(map);
			}
		}
		if (maps.size() == 1)
		{
			display.mapRef(maps.iterator().next());
		}
		if (text(observed, "prayerEffect") == null && "NONE_REVIEWED".equals(annotation(rows, "prayerEffect")))
		{
			display.prayerEffect("NONE_REVIEWED").damagePropertyEvidence("SOURCE_REFERENCE");
		}
		if (scalar)
		{
			Integer launch = consensus(rows, row -> row.source.launch() != null ? row.source.launch().clock.tick
				: row.source.receipt() == null ? null : row.source.receipt().clock.tick);
			if (launch != null && launch >= 0 && launch <= original.getImpactTick())
			{
				launch(display, hit, rows, launch);
			}
		}
		return display.build();
	}

	private static Channel supportChannel(Source source)
	{
		return source.channel() == Channel.HP_ACTOR_POSE ? Channel.ACTOR_CALLBACK
			: source.channel() == Channel.PROJECTILE_ORIGIN ? Channel.PROJECTILE : source.channel();
	}

	private static String annotation(List<ResolutionCandidate> rows, String field)
	{
		return consensus(rows, row -> text(object(row.derivedAnnotations), field));
	}

	private static <T> T consensus(List<ResolutionCandidate> rows, Function<ResolutionCandidate, T> field)
	{
		if (rows.isEmpty())
		{
			return null;
		}
		T first = field.apply(rows.get(0));
		return first != null && rows.stream().allMatch(row -> first.equals(field.apply(row))) ? first : null;
	}

	private static JsonObject launchObservation(List<ResolutionCandidate> rows)
	{
		Receipt launch = rows.get(0).source.launch();
		if (launch == null)
		{
			return null;
		}
		JsonObject first = launch.raw;
		for (ResolutionCandidate row : rows)
		{
			Receipt receipt = row.source.launch();
			JsonObject raw = receipt == null ? new JsonObject() : receipt.raw;
			if (!raw.has("originalProjectileObservation"))
			{
				return null;
			}
			for (String field : Arrays.asList("firstSeenTick", "prayersAtLaunch", "overheadAtLaunch",
				"launchPrayerStateKnown", "launchPositionKnown", "launchX", "launchY", "launchPlane", "launchSceneKey",
				"launchWorldViewId"))
			{
				if (!Objects.equals(first.get(field), raw.get(field)))
				{
					return null;
				}
			}
		}
		return first;
	}

	private static void launch(DamageRecord.DamageRecordBuilder display, Hit hit, List<ResolutionCandidate> rows,
		int tick)
	{
		DamageRecord original = hit.record();
		display.launchTick(tick).launchTickEstimated(true);
		JsonObject launch = launchObservation(rows);
		if (launch != null)
		{
			JsonObject projectile = object(launch.get("originalProjectileObservation"));
			Integer cycle = integer(projectile.get("observedCycle"));
			if (!Integer.valueOf(tick).equals(integer(launch.get("firstSeenTick"))) || cycle == null
				|| original.getObservedCycle() == null || cycle > original.getObservedCycle())
			{
				return;
			}
			display.launchStateEvidence("FIRST_PROJECTILE_OBSERVATION")
				.prayersAtLaunch(
					launch.get("prayersAtLaunch") instanceof JsonArray ? strings(launch.get("prayersAtLaunch")) : null)
				.overheadAtLaunch(text(launch, "overheadAtLaunch"))
				.launchPrayerStateKnown(bool(launch.get("launchPrayerStateKnown"))).launchPositionKnown(false);
			if (Boolean.TRUE.equals(bool(launch.get("launchPositionKnown")))
				&& Objects.equals(text(launch, "launchSceneKey"), original.getImpactSceneKey())
				&& Objects.equals(integer(launch.get("launchWorldViewId")), original.getImpactWorldViewId())
				&& Objects.equals(integer(launch.get("launchPlane")), original.getImpactPlane())
				&& integer(launch.get("launchX")) != null && integer(launch.get("launchY")) != null)
			{
				display.launchX(integer(launch.get("launchX"))).launchY(integer(launch.get("launchY")))
					.launchPositionKnown(true);
			}
			return;
		}
		Receipt originalReceipt = rows.get(0).source.launch() == null ? rows.get(0).source.receipt()
			: rows.get(0).source.launch();
		for (ResolutionCandidate row : rows)
		{
			Receipt receipt = row.source.launch() == null ? row.source.receipt() : row.source.launch();
			if (originalReceipt == null || receipt == null
				|| !Objects.equals(originalReceipt.clock.tick, receipt.clock.tick)
				|| !Objects.equals(originalReceipt.clock.cycle, receipt.clock.cycle)
				|| OriginalSourceProof.scope(originalReceipt.scope, receipt.scope) != Truth.TRUE)
			{
				return;
			}
		}
		WorldSnapshot frame = originalReceipt == null ? null
			: hit.exactFrame(originalReceipt.clock, originalReceipt.scope);
		if (frame != null && tick != original.getImpactTick() && frame.getTick() == tick && frame.getPlayer() != null
			&& Objects.equals(original.getImpactContextEpoch(), original.getFinalizationContextEpoch())
			&& Objects.equals(frame.getSceneKey(), original.getImpactSceneKey())
			&& Objects.equals(frame.getWorldViewId(), original.getImpactWorldViewId())
			&& Objects.equals(frame.getPlane(), original.getImpactPlane()))
		{
			PlayerState player = frame.getPlayer();
			display.launchStateEvidence("SNAPSHOT").overheadAtLaunch(player.getOverheadPrayer())
				.prayersAtLaunch(player.getActivePrayers()).launchPrayerStateKnown(player.getPrayerStateKnown())
				.launchX(player.getWorldX()).launchY(player.getWorldY())
				.launchPositionKnown(player.getWorldPositionKnown());
		}
	}

	/** One conversion from interpreted components to the exported source grouping. */
	static SourceResolution project(ResolutionResult result, String catalogId)
	{
		List<Candidate> candidates = new ArrayList<>(), excluded = new ArrayList<>(), conflicts = new ArrayList<>();
		for (ResolutionCandidate row : result.candidates)
		{
			ResolutionCandidate representative = representative(row, result.candidates);
			Identity identity = representative.identityOverride == null ? representative.source.identity()
				: representative.identityOverride;
			Scope scope = identity.scope;
			String contactName = representative.source.channel() == Channel.HP_CONTACT
				&& Boolean.TRUE.equals(representative.componentQualified)
					? SourceResolutionLabels.usableName(text(representative.source.raw(), "name"))
					: null;
			String name = contactName == null ? SourceResolutionLabels.usableName(identity.name) : contactName;
			if (name == null && !"npc".equals(identity.kind))
			{
				name = SourceResolutionLabels.usableName(row.label);
			}
			if (name == null)
			{
				name = identity.id == null ? "player".equals(identity.kind) ? "Recorded player" : "Recorded NPC"
					: identity.kind + " #" + identity.id;
			}
			List<String> evidence = strings(row.evidence);
			evidence.add("RESOLVER_V1:" + row.ruleId);
			evidence.add("RESOLVER_V1_RULESET:" + catalogId);
			if (row.maximum != null)
			{
				evidence.add("COMPONENT_MAXIMUM:" + row.maximum);
			}
			boolean scoped = scope != null && scope.sceneKey != null && scope.worldViewId != null
				&& scope.plane != null;
			String key = scoped ? row.key
				: "witness:" + representative.source.key() + "|" + representative.sourceKey + "|" + identity.id;
			Candidate candidate = Candidate.builder().key(key).name(name).actorRef(identity.actorRef)
				.npcId("npc".equals(identity.kind) ? identity.id : null).familyKey(representative.familyKey)
				.sceneKey(scope == null ? null : scope.sceneKey).worldViewId(scope == null ? null : scope.worldViewId)
				.plane(scope == null ? null : scope.plane)
				.strength("STRONG".equals(row.candidateStrength) ? "STRONG" : "POSSIBLE").evidence(evidence)
				.reasons(strings(row.reasons)).exclusionScope(row.exclusionScope).build();
			if ("EXCLUDED".equals(row.status))
			{
				excluded.add(candidate);
				if (Boolean.TRUE.equals(row.legacyApiReferenceConflictEligible)
					&& !"ALL_DIRECT_HP_COMPONENTS".equals(row.exclusionScope))
				{
					conflicts.add(candidate);
				}
			}
			else
			{
				candidates.add(candidate);
			}
		}
		if (candidates.isEmpty())
		{
			for (Candidate conflict : conflicts)
			{
				List<String> evidence = copy(conflict.getEvidence());
				evidence.add(
					"REFERENCE_PROFILE_CONFLICT: observed API source actor, target and arrival correlation retained; ordinary profile exceeded");
				for (String reason : copy(conflict.getReasons()))
				{
					evidence.add("ORDINARY_COMPONENT_EXCLUDED:" + reason);
				}
				candidates.add(conflict.toBuilder().strength("POSSIBLE").evidence(evidence).reasons(null)
					.exclusionScope(null).build());
				excluded.remove(conflict);
			}
		}
		List<String> familyEvidence = new ArrayList<>();
		if (candidates.stream().anyMatch(candidate -> !familyOnly(candidate)))
		{
			for (Candidate candidate : candidates)
			{
				if (familyOnly(candidate))
				{
					familyEvidence.add(
						"OBSERVED_HIT_FAMILY:" + candidate.getFamilyKey().substring("observed-hit-kind:".length()));
				}
			}
			candidates.removeIf(SourceEvidenceResolver::familyOnly);
		}
		for (Candidate candidate : candidates)
		{
			candidate.getEvidence().addAll(familyEvidence);
			candidate.getEvidence().add(
				"HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
		}
		return project(candidates, excluded, result.unidentifiedPossible, catalogId, "RECORDED_RESOLVER_V1_COMPONENTS");
	}

	/** Presentation aliases share recorded identities, without qualifying their missing native facts. */
	private static ResolutionCandidate representative(ResolutionCandidate row, List<ResolutionCandidate> rows)
	{
		ResolutionCandidate best = row;
		if (!"POSSIBLE".equals(row.status))
		{
			return best;
		}
		for (ResolutionCandidate other : rows)
		{
			if (!"POSSIBLE".equals(other.status) || !sameObservedReference(row.source, other.source))
			{
				continue;
			}
			int rank = (referenceOnly(best.source) ? 2 : 0) + (Boolean.TRUE.equals(best.componentQualified) ? 0 : 1);
			int otherRank = (referenceOnly(other.source) ? 2 : 0)
				+ (Boolean.TRUE.equals(other.componentQualified) ? 0 : 1);
			if (otherRank < rank || otherRank == rank && other.key.compareTo(best.key) < 0)
			{
				best = other;
			}
		}
		return best;
	}

	private static boolean referenceOnly(Source source)
	{
		return source.channel() == Channel.FRAME_NPC || source.channel() == Channel.FRAME_SURFACE;
	}

	private static boolean sameObservedReference(Source first, Source second)
	{
		Identity a = first.identity(), b = second.identity();
		if (!Objects.equals(a.kind, b.kind) || !Objects.equals(a.id, b.id)
			|| OriginalSourceProof.scope(a.scope, b.scope) == Truth.FALSE)
		{
			return false;
		}
		if (first.key().equals(second.key())
			|| (referenceOnly(first) || referenceOnly(second)) && a.actorRef != null && a.actorRef.equals(b.actorRef))
		{
			return true;
		}
		String field = "graphic".equals(a.kind) ? "graphicObservationId" : "objectObservationId";
		BigDecimal id = number(first.raw().get(field)), other = number(second.raw().get(field));
		return id != null && other != null && id.signum() > 0 && id.compareTo(other) == 0;
	}

	private static boolean familyOnly(Candidate candidate)
	{
		return candidate.getActorRef() == null && candidate.getNpcId() == null && candidate.getFamilyKey() != null
			&& candidate.getFamilyKey().startsWith("observed-hit-kind:") && candidate.getEvidence() != null
			&& candidate.getEvidence().contains("OBSERVED_HIT_FAMILY_ONLY");
	}

	private static List<String> strings(JsonElement value)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement element : array(value))
		{
			if (element.isJsonPrimitive())
			{
				result.add(element.getAsString());
			}
		}
		return result;
	}

	/** Canonical presentation projection. All eligibility and numeric decisions have already been evaluated. */
	public static SourceResolution project(List<Candidate> evaluated, List<Candidate> exclusions,
		boolean unidentifiedPossible, String catalogId, String method)
	{
		Map<String, List<Candidate>> retained = new LinkedHashMap<>();
		int count = 0;
		List<Candidate> ordered = evaluated == null ? new ArrayList<>() : new ArrayList<>(evaluated);
		ordered.sort(Comparator
			.comparing(candidate -> candidate == null || candidate.getKey() == null ? "" : candidate.getKey()));
		for (Candidate original : ordered)
		{
			if (++count > MAX_HYPOTHESES)
			{
				unidentifiedPossible = true;
				break;
			}
			if (original == null || blank(original.getKey()))
			{
				unidentifiedPossible = true;
				continue;
			}
			String name = SourceResolutionLabels.usableName(original.getName());
			if (name == null && original.getNpcId() != null)
			{
				name = "NPC #" + original.getNpcId();
			}
			if (name == null && !blank(original.getActorRef()) && scoped(original))
			{
				name = "Recorded actor";
			}
			if (name == null)
			{
				unidentifiedPossible = true;
				continue;
			}
			Candidate candidate = original.toBuilder().name(name).build();
			retained.computeIfAbsent(actorKey(candidate), ignored -> new ArrayList<>()).add(candidate);
		}
		List<Candidate> excluded = exclusions == null ? new ArrayList<>() : new ArrayList<>(exclusions);
		List<Candidate> actors = new ArrayList<>();
		for (List<Candidate> values : retained.values())
		{
			actors.add(merge(values, false));
		}
		Map<String, List<Candidate>> families = new LinkedHashMap<>();
		for (Candidate candidate : actors)
		{
			String key = !blank(candidate.getFamilyKey()) && scoped(candidate)
				? scope(candidate) + "family:" + candidate.getFamilyKey()
				: "single:" + candidate.getKey();
			families.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
		}
		List<Candidate> candidates = new ArrayList<>();
		for (List<Candidate> values : families.values())
		{
			candidates.add(merge(values, values.size() > 1));
		}
		Comparator<Candidate> canonicalOrder = Comparator
			.comparingInt((Candidate c) -> "STRONG".equals(c.getStrength()) ? 0 : 1).thenComparing(Candidate::getKey);
		candidates.sort(canonicalOrder);
		excluded = distinctExclusions(excluded, candidates);
		excluded.sort(canonicalOrder);
		String level = candidates.isEmpty() ? "NONE"
			: candidates.stream().allMatch(value -> !blank(value.getActorRef()) && scoped(value)) ? "ACTOR"
				: candidates.stream().allMatch(value -> !blank(value.getFamilyKey())) ? "FAMILY" : "NONE";
		return SourceResolution.builder().version(1)
			.status(candidates.isEmpty() ? "UNIDENTIFIED"
				: candidates.size() == 1 && !unidentifiedPossible ? "IDENTIFIED" : "ALTERNATIVES")
			.identityLevel(level).candidates(Collections.unmodifiableList(candidates))
			.excludedCandidates(excluded.isEmpty() ? null : Collections.unmodifiableList(excluded))
			.unidentifiedPossible(unidentifiedPossible || candidates.isEmpty()).catalogId(catalogId).method(method)
			.build();
	}

	private static List<Candidate> distinctExclusions(List<Candidate> input, List<Candidate> retained)
	{
		Map<List<Object>, Candidate> result = new LinkedHashMap<>();
		Set<String> keys = new LinkedHashSet<>();
		retained.forEach(candidate -> keys.add(candidate.getKey()));
		for (Candidate candidate : input)
		{
			List<Object> identity = Arrays.asList(candidate.getKey(), candidate.getName(), candidate.getActorRef(),
				candidate.getNpcId(), candidate.getFamilyKey(), candidate.getSceneKey(), candidate.getWorldViewId(),
				candidate.getPlane());
			Candidate previous = result.get(identity);
			if (previous != null)
			{
				List<String> evidence = copy(previous.getEvidence()), reasons = copy(previous.getReasons());
				evidence.addAll(copy(candidate.getEvidence()));
				reasons.addAll(copy(candidate.getReasons()));
				result.put(identity, previous.toBuilder().evidence(immutable(evidence)).reasons(immutable(reasons))
					.exclusionScope(mergeExclusionScope(previous.getExclusionScope(), candidate.getExclusionScope()))
					.build());
			}
			else
			{
				String key = candidate.getKey();
				int collision = 0;
				while (!keys.add(key))
				{
					key = candidate.getKey() + ":excluded-witness:" + ++collision;
				}
				result.put(identity,
					Objects.equals(key, candidate.getKey()) ? candidate : candidate.toBuilder().key(key).build());
			}
		}
		return new ArrayList<>(result.values());
	}

	private static String mergeExclusionScope(String first, String second)
	{
		return "ALL_DIRECT_HP_COMPONENTS".equals(first) || "ALL_DIRECT_HP_COMPONENTS".equals(second)
			? "ALL_DIRECT_HP_COMPONENTS"
			: "ORDINARY_ATTACK".equals(first) || "ORDINARY_ATTACK".equals(second) ? "ORDINARY_ATTACK" : null;
	}

	private static Candidate merge(List<Candidate> values, boolean family)
	{
		Candidate first = values.get(0);
		Set<String> evidence = new LinkedHashSet<>(), names = new LinkedHashSet<>();
		boolean strong = false;
		Integer npcId = first.getNpcId();
		values.sort(Comparator.comparing(Candidate::getKey).thenComparing(Candidate::getName));
		first = values.get(0);
		for (Candidate candidate : values)
		{
			evidence.addAll(copy(candidate.getEvidence()));
			names.add(candidate.getName());
			strong |= "STRONG".equals(candidate.getStrength());
			if (!Objects.equals(npcId, candidate.getNpcId()))
			{
				npcId = null;
			}
			if (family && !blank(candidate.getActorRef()))
			{
				evidence.add("ALTERNATIVE_ACTOR:" + candidate.getActorRef());
			}
		}
		if (family)
		{
			evidence.add("SOURCE_FAMILY_CONSENSUS; exact actor and attack remain unresolved");
		}
		else if (values.size() > 1)
		{
			evidence.add("SOURCE_IDENTITY_CONSENSUS; exact attack remains unresolved");
		}
		return first.toBuilder().key(family ? scope(first) + "family:" + first.getFamilyKey() : actorKey(first))
			.name(String.join(" / ", names)).actorRef(family ? null : first.getActorRef()).npcId(npcId)
			.strength(strong ? "STRONG" : "POSSIBLE").evidence(immutable(evidence)).reasons(null).exclusionScope(null)
			.build();
	}

	private static String actorKey(Candidate candidate)
	{
		return !blank(candidate.getActorRef()) && scoped(candidate)
			? scope(candidate) + "actor:" + candidate.getActorRef()
			: "hypothesis:" + candidate.getKey();
	}

	private static boolean scoped(Candidate candidate)
	{
		return !blank(candidate.getSceneKey()) && candidate.getWorldViewId() != null && candidate.getPlane() != null;
	}

	private static String scope(Candidate candidate)
	{
		return candidate.getSceneKey().length() + ":" + candidate.getSceneKey() + ":" + candidate.getWorldViewId() + ":"
			+ candidate.getPlane() + ":";
	}

	private static boolean blank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static List<String> copy(List<String> values)
	{
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}

	private static List<String> immutable(Collection<String> values)
	{
		return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(values)));
	}
}
