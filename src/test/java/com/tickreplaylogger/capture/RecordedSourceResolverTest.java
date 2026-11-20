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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.ObservedStatusState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordedSourceResolverTest
{
	private static final Gson GSON = new Gson();

	private static WorldSnapshot frame(int tick, List<HazardState> hazards, List<ObservedStatusState> statuses)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("scene-a").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldX(102).worldY(203).build()).hazards(hazards).localStatuses(statuses)
			.context(qualifiedContext()).build();
	}

	private static HazardState hazard(boolean positioned, int start)
	{
		return HazardState.builder().kind("gameObject").id(900010).mappedName("Fume").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").worldViewId(0).plane(0).worldX(103).worldY(204)
			.localPositionKnown(positioned).worldPositionKnown(positioned).minSceneX(2).maxSceneX(4).minSceneY(3)
			.maxSceneY(5).startCycle(start).build();
	}

	private static DamageRecord hit(String type)
	{
		return DamageRecord.builder().impactTick(2).observationSequence(7L).observedCycle(100).impactSceneKey("scene-a")
			.impactWorldViewId(0).impactPlane(0).impactX(102).impactY(203).impactPositionKnown(true).amount(8)
			.damageType(type).attackerName("Unknown").build();
	}

	private static RecordedSourceResolver resolver(List<WorldSnapshot> frames, DamageMap map)
	{
		return new RecordedSourceResolver(frames, Collections.singletonList(
			SceneData.builder().sceneKey("scene-a").worldViewId(0).plane(0).baseX(100).baseY(200).build()), map);
	}

	private static com.tickreplaylogger.model.world.ContextBlock qualifiedContext()
	{
		return com.tickreplaylogger.model.world.ContextBlock.builder().templateRegionId(65535).inInstance(true)
			.observedVariables(Collections.singletonMap("TEST_SCALING", 0)).build();
	}

	private static ObservedStatusState status(boolean active)
	{
		return ObservedStatusState.builder().key("poison_venom_client_state").ruleKey("poison_venom_client_state")
			.state(active ? "venom" : "inactive").stateLabel(active ? "Venom" : "Inactive").active(active)
			.valueObserved(true).build();
	}

	@Test
	public void conditionalSettledContactWithoutCallbackWitnessRetainsItsNameAndUnobservedConditions()
	{
		HazardState conditional = HazardState.builder().kind("groundObject").id(900010).mappedName("Guarded floor")
			.sourceEligibility("CONDITION_NOT_OBSERVED").worldPositionKnown(true).worldX(102).worldY(203)
			.worldViewId(0).plane(0).state("conditional").build();
		DamageRecord original = hit("unknown");
		SourceResolution result = resolver(Collections.singletonList(frame(2, Collections.singletonList(conditional), null)), null)
			.resolve(original);
		assertEquals(1, result.getCandidates().size());
		assertEquals("Guarded floor", result.getCandidates().get(0).getName());
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue(result.isUnidentifiedPossible());
		assertEquals(8, original.getAmount());
		assertNull(original.getSourceResolution());
	}

	@Test
	public void definitionConflictCannotSilentlyDisappearOrAcquireAnUnqualifiedSurfaceName()
	{
		HazardState conflict = HazardState.builder().kind("groundObject").id(900010).mappedName("Unqualified reference")
			.sourceEligibility("DEFINITION_NOT_QUALIFIED").worldPositionKnown(true).worldX(102).worldY(203)
			.worldViewId(0).plane(0).state("unknown").build();
		SourceResolution result = resolver(Collections.singletonList(frame(2, Collections.singletonList(conflict), null)), null)
			.resolve(hit("unknown"));
		assertTrue(result.getCandidates().isEmpty());
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void sameTickCureCannotEraseEarlierStatusAndContactDoesNotEstablishCausality()
	{
		DamageMap map = LegacyCombatCatalog.isolate(DamageMap.load(GSON));
		assertTrue("Aligned rule required for this positive status control",
			map.catalog().localStatusCanRepresentDamage("poison_venom_client_state", "venom"));
		SourceResolution r = resolver(
			Arrays.asList(frame(1, Collections.emptyList(), Collections.singletonList(status(true))),
				frame(2, Collections.singletonList(hazard(true, 0)), Collections.singletonList(status(false)))),
			map).resolve(hit("venom"));
		assertEquals("ALTERNATIVES", r.getStatus());
		assertEquals(2, r.getCandidates().size());
		assertTrue(r.getCandidates().stream().anyMatch(c -> "Venom".equals(c.getName())));
		assertTrue(r.getCandidates().stream().anyMatch(c -> "Fume".equals(c.getName())));
	}

	@Test
	public void settledInactiveOrSameTickActivationCannotProveEarlierHitState()
	{
		DamageMap map = LegacyCombatCatalog.isolate(DamageMap.load(GSON));
		SourceResolution inactive = resolver(Collections.singletonList(
			frame(2, Collections.singletonList(hazard(true, 0)), Collections.singletonList(status(false)))), map)
			.resolve(hit("venom"));
		assertTrue(inactive.isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", inactive.getStatus());
		SourceResolution activation = resolver(
			Arrays.asList(frame(1, Collections.emptyList(), Collections.singletonList(status(false))),
				frame(2, Collections.emptyList(), Collections.singletonList(status(true)))),
			map).resolve(hit("venom"));
		assertEquals("Venom", activation.getCandidates().get(0).getName());
		assertTrue("An active status envelope does not order this impact's cause", activation.isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", activation.getStatus());
		assertTrue(activation.getCandidates().get(0).getEvidence().get(0).contains("ordering"));
	}

	@Test
	public void repeatedKnownInactiveStatusDoesNotInventAnUnavailableReadOrRecurringDamage()
	{
		DamageMap map = LegacyCombatCatalog.isolate(DamageMap.load(GSON));
		SourceResolution r = resolver(
			Arrays.asList(frame(1, Collections.emptyList(), Collections.singletonList(status(false))),
				frame(2, Collections.singletonList(hazard(true, 0)), Collections.singletonList(status(false)))),
			map).resolve(hit("venom"));
		assertTrue("A known floor contact is still a source association", r.isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", r.getStatus());
		assertEquals(1, r.getCandidates().size());
		assertEquals("Fume", r.getCandidates().get(0).getName());
	}

	@Test
	public void failedPositionAndCreationAfterImpactCannotBorrowSettledCoordinates()
	{
		SourceResolution unpositioned = resolver(
			Collections.singletonList(frame(2, Collections.singletonList(hazard(false, 0)), null)), null)
			.resolve(hit("unknown"));
		assertTrue(unpositioned.getCandidates().isEmpty());
		SourceResolution future = resolver(
			Collections.singletonList(frame(2, Collections.singletonList(hazard(true, 101)), null)), null)
			.resolve(hit("unknown"));
		assertTrue(future.getCandidates().isEmpty());
		SourceResolution current = resolver(
			Collections.singletonList(frame(2, Collections.singletonList(hazard(true, 99)), null)), null)
			.resolve(hit("unknown"));
		assertEquals("Fume", current.getCandidates().get(0).getName());
	}

	@Test
	public void optionalVisualFailureDoesNotWipeNamedEvidenceButRelevantSamplingGapRemains()
	{
		Map<String, Object> candidate = new HashMap<>();
		candidate.put("kind", "NPC_ANIMATION_CORRELATION");
		candidate.put("name", "Unfamiliar entity");
		candidate.put("actorRef", "unavailable-ref");
		DamageRecord raw = hit("unknown").toBuilder().attributionCandidates(Collections.singletonList(candidate))
			.captureIssues(Collections.singletonList("defender.graphics: unavailable")).build();
		SourceResolution kept = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(raw);
		assertEquals("ALTERNATIVES", kept.getStatus());
		assertTrue("A retained name without an actor sample cannot establish hit causality", kept.isUnidentifiedPossible());
		assertTrue(kept.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		assertEquals("NONE", kept.getIdentityLevel());
		assertNull(kept.getCandidates().get(0).getActorRef());
		candidate.put("sourceSamplingUnavailable", true);
		SourceResolution partial = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(raw);
		assertEquals("ALTERNATIVES", partial.getStatus());
		assertTrue(partial.isUnidentifiedPossible());
		assertEquals(8, raw.getAmount());
		assertEquals("Unknown", raw.getAttackerName());
	}

	@Test
	public void selfResourceConsumptionAndFailureMessagesRemainAssociationsAfterSingletonConsensus()
	{
		for (String kind : Arrays.asList("SELF_DAMAGE_RESOURCE_CANDIDATE", "ITEM_CONSUMPTION_CANDIDATE",
			"PICKPOCKET_FAILURE_CORRELATION", "SELF_DAMAGE_INPUT_CANDIDATE"))
		{
			Map<String, Object> witness = new HashMap<>();
			witness.put("kind", kind); witness.put("name", "Observed source");
			DamageRecord original = hit("unknown").toBuilder().direction("RECEIVED").defenderRef("player")
				.attributionCandidates(Collections.singletonList(witness)).build();
			SourceResolution result = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null).resolve(original);
			assertEquals(kind, "ALTERNATIVES", result.getStatus());
			assertTrue(kind, result.isUnidentifiedPossible());
			assertEquals(1, result.getCandidates().size());
			assertEquals("Observed source", result.getCandidates().get(0).getName());
			assertTrue(result.getCandidates().get(0).getEvidence().stream()
				.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
			assertEquals(8, original.getAmount());
			assertEquals(witness, original.getAttributionCandidates().get(0));
		}
	}

	@Test
	public void qualifiedAnimationAndCompatibleMaximumDoNotBecomeAnObservedHpCause() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		com.tickreplaylogger.model.world.NpcState npc = com.tickreplaylogger.model.world.NpcState.builder()
			.npcId(900001).actorRef("npc:unfamiliar:v0:g1").name("Unfamiliar entity").combatLevel(80)
			.worldViewId(0).plane(0).planeKnown(true).normalWorldContextObserved(true).modelOverridesPresent(false)
			.animationId(777777).build();
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "NPC_ANIMATION_CORRELATION"); raw.put("name", npc.getName());
		raw.put("actorRef", npc.getActorRef()); raw.put("npcId", npc.getNpcId());
		raw.put("animationId", 777777); raw.put("standardDamageModifiersKnown", true);
		DamageRecord hit = hit("unknown").toBuilder().direction("RECEIVED").defenderRef("player")
			.hitsplatTypeKnown(true).hitsplatType(16).attributionCandidates(Collections.singletonList(raw)).build();
		SourceResolution result = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null).toBuilder()
			.npcs(Collections.singletonList(npc)).build()), map).resolve(hit);
		assertEquals("ALTERNATIVES", result.getStatus()); assertTrue(result.isUnidentifiedPossible());
		assertEquals(1, result.getCandidates().size());
		assertEquals(npc.getActorRef(), result.getCandidates().get(0).getActorRef());
		assertEquals("wiki:Unfamiliar_entity#infobox-1", result.getCandidates().get(0).getFamilyKey());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("QUALIFIED_MAXIMUM:unfamiliar-base:")));
		assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
		assertEquals(8, hit.getAmount());
	}

	@Test
	public void foreignCallbackContactCannotCauseTheCurrentLocalHit()
	{
		Map<String, Object> contact = new HashMap<>();
		contact.put("name", "Foreign surface");
		contact.put("objectId", 900020);
		contact.put("worldViewId", 8);
		contact.put("plane", 1);
		SourceResolution r = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(hit("unknown").toBuilder().observedHazardContacts(Collections.singletonList(contact)).build());
		assertEquals("UNIDENTIFIED", r.getStatus());
		assertTrue(r.getCandidates().isEmpty());
		assertTrue(r.isUnidentifiedPossible());
	}

	private static DamageMap toyEnvelopeMap() throws Exception
	{
		LegacyDamageMap map = LegacyDamageMap.from(LegacyCombatCatalog.isolate(DamageMap.load(GSON)));
		java.lang.reflect.Field rootField = CombatCatalog.class.getDeclaredField("root");
		rootField.setAccessible(true);
		rootField.set(map.catalog(), ((JsonObject) rootField.get(map.catalog())).deepCopy());
		com.google.gson.JsonObject root = (com.google.gson.JsonObject) rootField.get(map.catalog());
		root.getAsJsonObject("npcIdentities").add("900001", GSON.fromJson(
			"{\"candidates\":[{\"name\":\"Unfamiliar entity\",\"source_ref\":\"wiki:Unfamiliar_entity#infobox-1\",\"combat_level\":\"80\"}]}",
			com.google.gson.JsonObject.class));
		root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides").add("900001",
			GSON.fromJson("{\"animations\":{\"777777\":{\"kind\":\"attack\",\"outcomeMechanism\":\"ORDINARY_ATTACK\"}}}", com.google.gson.JsonObject.class));
		root.getAsJsonObject("reviewedBindings").getAsJsonArray("projectileStyleRules").add(GSON.fromJson(
			"{\"npcIds\":[900001],\"styles\":{\"777778\":\"ranged\"},\"outcomeMechanism\":\"ORDINARY_ATTACK\"}",
			com.google.gson.JsonObject.class));
		map.attackRules(Collections.singletonMap("900001", Collections.singletonMap("777777",
			GSON.fromJson("{\"damageType\":\"melee\"}", PreRuleIrDamageMap.AttackRule.class))));
		com.google.gson.JsonObject namespace = new com.google.gson.JsonObject();
		namespace.addProperty("version", 1);
		com.google.gson.JsonObject rule = new com.google.gson.JsonObject();
		rule.addProperty("key", "unfamiliar-base");
		rule.addProperty("familyKey", "wiki:Unfamiliar_entity#infobox-1");
		rule.addProperty("maximum", 13);
		rule.addProperty("qualification", "HARD_BOUND");
		rule.add("attackStyles", GSON.toJsonTree(Arrays.asList("MELEE", "RANGED")));
		rule.add("selector", GSON.fromJson(
			"{\"npcIds\":[900001],\"mechanism\":\"ORDINARY_ATTACK\",\"damageComponent\":\"SINGLE_HIT\",\"worldMode\":\"NORMAL\"}",
			com.google.gson.JsonObject.class));
		rule.add("envelopePolicy", GSON.fromJson(
			"{\"operator\":\"BASE_PROFILE_ENVELOPE\",\"evidence\":\"SOURCE_RELATIVE_ORDINARY_BASE_PROFILE\",\"knownExceptionalContext\":\"BLOCK_BASE_BRANCH_RETAIN_ALTERNATIVES\",\"blockedActivities\":[\"scaled-test\"]}",
			com.google.gson.JsonObject.class));
		rule.add("requiredObservations",
			GSON.toJsonTree(Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT",
				"NO_MODEL_OVERRIDES_OBSERVED", "ORDINARY_ATTACK_COMPONENT", "ORDINARY_ATTACK_CUE",
				"SINGLE_HIT_COMPONENT", "BASE_PROFILE_ENVELOPE_APPLICABLE")));
		com.google.gson.JsonArray rules = new com.google.gson.JsonArray();
		rules.add(rule);
		namespace.add("rules", rules);
		root.add("attributionConstraints", namespace);
		root.getAsJsonObject("contextBindings").getAsJsonObject("regionActivities").remove("65535");
		root.getAsJsonObject("contextBindings").getAsJsonObject("captureRules").getAsJsonArray("activityFallbackRules")
			.add(GSON.fromJson(
				"{\"activity\":\"scaled-test\",\"variable\":\"TEST_SCALING\",\"cases\":[{\"values\":[1]}]}",
				com.google.gson.JsonObject.class));
		java.lang.reflect.Field index = LegacyCombatCatalog.class.getDeclaredField("attributionConstraintIndex");
		index.setAccessible(true);
		index.set(LegacyCombatCatalog.from(map.catalog()), null);
		return map;
	}

	@Test
	public void unfamiliarBaseProfileCapIsActiveButOverridesUnknownCueAndScalingKeepAlternatives() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		com.tickreplaylogger.model.world.NpcState npc = com.tickreplaylogger.model.world.NpcState.builder()
			.npcId(900001).actorRef("npc:unfamiliar:v0:g1").name("Unfamiliar entity").combatLevel(80).worldViewId(0)
			.plane(0).normalWorldContextObserved(true).modelOverridesPresent(false).animationId(777777).build();
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "NPC_ANIMATION_CORRELATION");
		raw.put("name", npc.getName());
		raw.put("actorRef", npc.getActorRef());
		raw.put("npcId", npc.getNpcId());
		raw.put("animationId", 777777);
		raw.put("standardDamageModifiersKnown", true);
		DamageRecord h = hit("unknown").toBuilder().amount(16).hitsplatType(16).defenderRef("player")
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot base = frame(2, Collections.emptyList(), null).toBuilder().npcs(Collections.singletonList(npc))
			.build();
		SourceResolution excluded = resolver(Collections.singletonList(base), map).resolve(h);
		assertTrue(excluded.getCandidates().isEmpty());
		assertEquals(1, excluded.getExcludedCandidates().size());
		assertTrue(excluded.getExcludedCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("BASE_PROFILE_ENVELOPE:")));
		raw.remove("standardDamageModifiersKnown");
		SourceResolution unknownModifiers = resolver(Collections.singletonList(base), map).resolve(h);
		assertEquals(1, unknownModifiers.getCandidates().size());
		assertTrue(unknownModifiers.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("STANDARD_DAMAGE_MODIFIERS")));
		raw.put("standardDamageModifiersKnown", true);
		raw.put("projectileId", 777779);
		SourceResolution unknownStyle = resolver(Collections.singletonList(base), map).resolve(h);
		assertEquals(1, unknownStyle.getCandidates().size());
		assertTrue(unknownStyle.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("BASE_PROFILE_ATTACK_STYLE_NOT_QUALIFIED")));
		raw.remove("projectileId");
		for (com.tickreplaylogger.model.world.NpcState partial : Arrays.asList(npcVariant(true, 80),
			npcVariant(null, 80), npcVariant(false, null), npcVariant(false, 90)))
		{
			SourceResolution retained = resolver(
				Collections.singletonList(base.toBuilder().npcs(Collections.singletonList(partial)).build()), map)
				.resolve(h);
			assertEquals(1, retained.getCandidates().size());
			assertNull(retained.getExcludedCandidates());
		}
		raw.put("animationId", 888888);
		assertEquals(1, resolver(Collections.singletonList(base), map).resolve(h).getCandidates().size());
		raw.put("animationId", 777777);
		WorldSnapshot scaled = base.toBuilder()
			.context(com.tickreplaylogger.model.world.ContextBlock.builder().activity("scaled-test").build()).build();
		assertEquals(1, resolver(Collections.singletonList(scaled), map).resolve(h).getCandidates().size());

		for (com.tickreplaylogger.model.world.ContextBlock context : Arrays.asList(null,
			qualifiedContext().toBuilder().templateRegionId(null).build(),
			qualifiedContext().toBuilder().inInstance(null).build(),
			qualifiedContext().toBuilder().observedVariables(Collections.emptyMap()).build(),
			qualifiedContext().toBuilder().observedVariables(Collections.singletonMap("TEST_SCALING", 1)).build()))
			assertEquals(1, resolver(Collections.singletonList(base.toBuilder().context(context).build()), map)
				.resolve(h).getCandidates().size());
		assertEquals(16, h.getAmount());
		assertEquals("Unknown", h.getAttackerName());
	}

	private static com.tickreplaylogger.model.world.NpcState npcVariant(Boolean override, Integer level)
	{
		return com.tickreplaylogger.model.world.NpcState.builder().npcId(900001).actorRef("npc:unfamiliar:v0:g1")
			.name("Unfamiliar entity").combatLevel(level).worldViewId(0).plane(0).planeKnown(true).normalWorldContextObserved(true)
			.modelOverridesPresent(override).animationId(777777).baseNpcId(900001)
			.definitionEvidence("NPC_API_ID").definitionHasTransforms(false).build();
	}

	@Test
	public void malformedConstraintVersionOrMixedIdsCannotPartiallyAdmitAReference() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		com.google.gson.JsonObject root = (com.google.gson.JsonObject) field.get(map.catalog());
		root.getAsJsonObject("attributionConstraints").add("version", new com.google.gson.JsonObject());
		assertTrue(LegacyCombatCatalog.from(map.catalog()).attributionConstraints(900001).isEmpty());
		map = toyEnvelopeMap();
		root = (com.google.gson.JsonObject) field.get(map.catalog());
		root.getAsJsonObject("attributionConstraints").getAsJsonArray("rules").get(0).getAsJsonObject()
			.getAsJsonObject("selector").getAsJsonArray("npcIds").add("malformed");
		assertTrue(LegacyCombatCatalog.from(map.catalog()).attributionConstraints(900001).isEmpty());
	}

	@Test
	public void projectileUsesOriginalCapturedProfileRatherThanTransformedImpactActor() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		com.tickreplaylogger.model.world.ProjectileSourceCandidate origin = com.tickreplaylogger.model.world.ProjectileSourceCandidate
			.builder().actorRef("npc:unfamiliar:v0:g1").name("Unfamiliar entity").npcId(900001).combatLevel(80)
			.animationId(777777).normalWorldContextObserved(true).modelOverridesPresent(false)
			.familyKey("wiki:Unfamiliar_entity#infobox-1").worldViewId(0).plane(0).sceneKey("scene-a").build();
		com.tickreplaylogger.model.world.ProjectileState projectile = com.tickreplaylogger.model.world.ProjectileState
			.builder().observationId(99L).projectileId(777778).spawnTick(1).observedTick(1).sourceActorKnown(true)
			.sourceRef(origin.getActorRef()).sourceName(origin.getName()).sourceNpcId(900001).sourceWorldViewId(0)
			.sourceNameKnown(true).sourceObservedName(origin.getName()).sourceCombatLevel(80).sourceNormalWorldContextObserved(true)
			.sourceBaseNpcId(900001).sourceDefinitionEvidence("NPC_API_ID").sourceDefinitionHasTransforms(false)
			.sourcePlane(0).sourceEvidence("ACTOR").targetsPlayer(true).build();
		com.tickreplaylogger.model.world.NpcState transformed = com.tickreplaylogger.model.world.NpcState.builder()
			.actorRef(origin.getActorRef()).npcId(900002).name("Transformed entity").combatLevel(200).worldViewId(0)
			.plane(0).normalWorldContextObserved(true).modelOverridesPresent(false).animationId(888888).build();
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION");
		raw.put("name", origin.getName());
		raw.put("projectileObservationId", 99L);
		raw.put("standardDamageModifiersKnown", true);
		raw.put("arrivalGapCycles", 0);
		raw.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW");
		DamageRecord h = hit("unknown").toBuilder().amount(16).hitsplatType(16).defenderRef("player")
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot launch = frame(1, Collections.emptyList(), null).toBuilder()
			.projectiles(Collections.singletonList(projectile)).npcs(Collections.singletonList(npcVariant(false, 80)))
			.build();
		WorldSnapshot impact = frame(2, Collections.emptyList(), null).toBuilder()
			.npcs(Collections.singletonList(transformed)).build();
		SourceResolution r = resolver(Arrays.asList(launch, impact), map).resolve(h);
		assertEquals(1, r.getCandidates().size());
		assertEquals(Integer.valueOf(900001), r.getCandidates().get(0).getNpcId());
		assertTrue(r.isUnidentifiedPossible());
		assertEquals("POSSIBLE", r.getCandidates().get(0).getStrength());
		assertTrue(r.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("QUALIFIED_MAXIMUM:unfamiliar-base:16 > 13")));
		assertEquals(origin.getActorRef(), projectile.getSourceRef());
		assertNull(projectile.getProximitySourceCandidate());
		com.tickreplaylogger.model.world.ProjectileState missing = projectile.toBuilder().proximitySourceCandidate(null)
			.sourceRef(origin.getActorRef()).sourceName(origin.getName()).sourceNpcId(900001).build();
		SourceResolution unknown = resolver(Arrays.asList(
			launch.toBuilder().projectiles(Collections.singletonList(missing)).npcs(Collections.emptyList()).build(),
			impact), map).resolve(h);
		assertEquals(1, unknown.getCandidates().size());
		assertNull(unknown.getExcludedCandidates());
	}

	@Test
	public void unknownOriginViewKeepsProximityPossibleAndKnownScopeMismatchRejectsIt() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		com.tickreplaylogger.model.world.ProjectileSourceCandidate near = com.tickreplaylogger.model.world.ProjectileSourceCandidate
			.builder().actorRef("npc:unfamiliar:v0:g1").npcId(900001).name("Unfamiliar entity").combatLevel(80)
			.animationId(777777).normalWorldContextObserved(true).modelOverridesPresent(false)
			.familyKey("wiki:Unfamiliar_entity#infobox-1").sceneKey("scene-a").worldViewId(0).plane(0).build();
		com.tickreplaylogger.model.world.ProjectileState projectile = com.tickreplaylogger.model.world.ProjectileState
			.builder().observationId(100L).spawnTick(1).observedTick(1).sourceActorKnown(true).sourceRef(null)
			.sourcePlane(0).targetsPlayer(true).proximitySourceCandidate(near).build();
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION");
		raw.put("projectileObservationId", 100L);
		raw.put("name", near.getName());
		raw.put("arrivalGapCycles", 0);
		DamageRecord h = hit("unknown").toBuilder().amount(16).hitsplatType(16)
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot launch = frame(1, Collections.emptyList(), null).toBuilder()
			.projectiles(Collections.singletonList(projectile)).build();
		WorldSnapshot impact = frame(2, Collections.emptyList(), null);
		SourceResolution possible = resolver(Arrays.asList(launch, impact), map).resolve(h);
		assertEquals(1, possible.getCandidates().size());
		assertEquals("POSSIBLE", possible.getCandidates().get(0).getStrength());
		assertTrue("Proximity with unavailable origin scope is not a unique HP cause", possible.isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", possible.getStatus());
		assertTrue(possible.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		assertNull(possible.getExcludedCandidates());
		assertTrue(possible.getCandidates().get(0).getEvidence().contains("ORIGIN_NATIVE_VIEW_UNAVAILABLE"));
		SourceResolution foreign = resolver(Arrays.asList(
			launch.toBuilder()
				.projectiles(Collections.singletonList(projectile.toBuilder().sourceWorldViewId(8).build())).build(),
			impact), map).resolve(h);
		assertTrue(foreign.getCandidates().isEmpty());
		assertTrue(foreign.isUnidentifiedPossible());
		SourceResolution wrongPlane = resolver(
			Arrays.asList(launch.toBuilder()
				.projectiles(Collections.singletonList(projectile.toBuilder().sourcePlane(1).build())).build(), impact),
			map).resolve(h);
		assertTrue(wrongPlane.getCandidates().isEmpty());
		assertNull(projectile.getSourceRef());
	}

	@Test
	public void latestSourceHistoryGapPreventsStaleStrongIdentityAndHardBound() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		com.tickreplaylogger.model.world.ProjectileState projectile = com.tickreplaylogger.model.world.ProjectileState
			.builder().observationId(101L).projectileId(777778).spawnTick(1).observedTick(1).sourceActorKnown(true)
			.sourceRef("npc:unfamiliar:v0:g1").sourceName("Unfamiliar entity").sourceNpcId(900001).sourceWorldViewId(0)
			.sourceNameKnown(true).sourceObservedName("Unfamiliar entity").sourceCombatLevel(80).sourceNormalWorldContextObserved(true)
			.sourceBaseNpcId(900001).sourceDefinitionEvidence("NPC_API_ID").sourceDefinitionHasTransforms(false)
			.sourcePlane(0).sourceEvidence("ACTOR").targetsPlayer(true).build();
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION");
		raw.put("projectileObservationId", 101L);
		raw.put("standardDamageModifiersKnown", true);
		raw.put("name", "Unfamiliar entity");
		raw.put("arrivalGapCycles", 0);
		DamageRecord h = hit("unknown").toBuilder().amount(16).hitsplatType(16)
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot launch = frame(1, Collections.emptyList(), null).toBuilder()
			.projectiles(Collections.singletonList(projectile)).npcs(Collections.singletonList(npcVariant(false, 80)))
			.build();
		WorldSnapshot impact = frame(2, Collections.emptyList(), null);
		SourceResolution stable = resolver(Arrays.asList(launch, impact), map).resolve(h);
		assertEquals(1, stable.getCandidates().size());
		assertTrue(stable.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("REFERENCE_PROFILE_CONFLICT:")));
		raw.put("sourceHistory", Collections.singletonMap("observationGap", true));
		Map<String, Object> later = new HashMap<>();
		later.put("sourceRef", "npc:later:v0:g2");
		later.put("sourceName", "Other entity");
		later.put("sourceNpcId", 900002);
		raw.put("latestSourceObservation", later);
		SourceResolution changed = resolver(Arrays.asList(launch, impact), map).resolve(h);
		assertEquals("ALTERNATIVES", changed.getStatus());
		assertEquals(2, changed.getCandidates().size());
		assertTrue(changed.isUnidentifiedPossible());
		assertNull(changed.getExcludedCandidates());
		assertTrue(changed.getCandidates().stream().allMatch(c -> "POSSIBLE".equals(c.getStrength())));
		assertEquals("npc:unfamiliar:v0:g1", projectile.getSourceRef());
		assertEquals(16, h.getAmount());
		assertEquals("Unknown", h.getAttackerName());
	}

	@Test
	public void originalProximitySampleCannotBorrowAPreviousPrimarySceneKey() throws Exception
	{
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][1][1];
		net.runelite.api.Scene scene = proxy(net.runelite.api.Scene.class, (object, method,
			args) -> "getTiles".equals(method.getName()) ? tiles : primitiveDefault(method.getReturnType()));
		net.runelite.api.WorldView view = proxy(net.runelite.api.WorldView.class, (object, method, args) ->
		{
			switch (method.getName())
			{
			case "getScene":
				return scene;
			case "getSizeX":
			case "getSizeY":
				return 1;
			case "getBaseX":
				return 100;
			case "getBaseY":
				return 200;
			default:
				return primitiveDefault(method.getReturnType());
			}
		});
		net.runelite.api.NPCComposition definition = proxy(net.runelite.api.NPCComposition.class,
			(object, method, args) ->
			{
				if ("getId".equals(method.getName()))
					return 900001;
				if ("getName".equals(method.getName()))
					return "Unfamiliar entity";
				return primitiveDefault(method.getReturnType());
			});
		net.runelite.api.NPC npc = proxy(net.runelite.api.NPC.class, (object, method, args) ->
		{
			switch (method.getName())
			{
			case "getWorldView":
				return view;
			case "getWorldLocation":
				return new net.runelite.api.coords.WorldPoint(100, 200, 0);
			case "getComposition":
			case "getTransformedComposition":
				return definition;
			case "getId":
				return 900001;
			case "getName":
				return "Unfamiliar entity";
			case "getCombatLevel":
				return 80;
			default:
				return primitiveDefault(method.getReturnType());
			}
		});
		com.tickreplaylogger.TickReplayLoggerPlugin plugin = new com.tickreplaylogger.TickReplayLoggerPlugin();
		java.lang.reflect.Field key = plugin.getClass().getDeclaredField("currentSceneKey");
		key.setAccessible(true);
		key.set(plugin, "old-scene");
		java.lang.reflect.Field signature = plugin.getClass().getDeclaredField("sceneScopeSignature");
		signature.setAccessible(true);
		signature.set(plugin, "old-scope");
		java.lang.reflect.Method sample = plugin.getClass().getDeclaredMethod("captureProximitySource",
			net.runelite.api.NPC.class, net.runelite.api.Player.class);
		sample.setAccessible(true);
		com.tickreplaylogger.model.world.ProjectileSourceCandidate stale = (com.tickreplaylogger.model.world.ProjectileSourceCandidate) sample
			.invoke(plugin, npc, null);
		assertNull(stale.getSceneKey());
		assertEquals(Integer.valueOf(900001), stale.getNpcId());
		assertEquals("Unfamiliar entity", stale.getName());
		signature.set(plugin, WorldViews.scope(view, 0, 0));
		key.set(plugin, "current-scene");
		com.tickreplaylogger.model.world.ProjectileSourceCandidate matching = (com.tickreplaylogger.model.world.ProjectileSourceCandidate) sample
			.invoke(plugin, npc, null);
		assertEquals("current-scene", matching.getSceneKey());
		assertEquals(Integer.valueOf(0), matching.getWorldViewId());
	}

	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
	{
		return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, handler));
	}

	private static Object primitiveDefault(Class<?> type)
	{
		if (type == boolean.class)
			return false;
		if (type == int.class)
			return 0;
		if (type == long.class)
			return 0L;
		if (type == double.class)
			return 0D;
		if (type == float.class)
			return 0F;
		if (type == short.class)
			return (short) 0;
		if (type == byte.class)
			return (byte) 0;
		if (type == char.class)
			return (char) 0;
		return null;
	}

	@Test
	public void namedProjectileWithoutObservationIdRemainsAnAlternativeToInput()
	{
		Map<String, Object> item = new HashMap<>();
		item.put("kind", "ITEM_CONSUMPTION_CANDIDATE");
		item.put("name", "Unfamiliar input");
		item.put("itemId", 900101);
		Map<String, Object> projectile = new HashMap<>();
		projectile.put("kind", "PROJECTILE_CORRELATION");
		projectile.put("name", "Unfamiliar entity");
		projectile.put("projectileId", 700001);
		SourceResolution r = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(hit("unknown").toBuilder().attributionCandidates(Arrays.asList(item, projectile)).build());
		assertEquals("ALTERNATIVES", r.getStatus());
		assertEquals(2, r.getCandidates().size());
		SourceResolution.Candidate named = r.getCandidates().stream()
			.filter(c -> "Unfamiliar entity".equals(c.getName())).findFirst().get();
		assertEquals("POSSIBLE", named.getStrength());
		assertNull(named.getActorRef());
		assertNull(named.getNpcId());
		assertNull(named.getFamilyKey());
		assertNull(named.getSceneKey());
		assertNull(named.getWorldViewId());
		assertNull(named.getPlane());
		assertNull(r.getExcludedCandidates());
		assertTrue("A named projectile without observation/source identity cannot close the source list", r.isUnidentifiedPossible());
		assertTrue(named.getEvidence().stream().anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
	}

	@Test
	public void unnamedProjectileWithoutObservationIdRetainsARelevantGapBesideInput()
	{
		Map<String, Object> item = new HashMap<>();
		item.put("kind", "ITEM_CONSUMPTION_CANDIDATE");
		item.put("name", "Unfamiliar input");
		item.put("itemId", 900101);
		Map<String, Object> projectile = new HashMap<>();
		projectile.put("kind", "PROJECTILE_CORRELATION");
		projectile.put("name", "Unknown");
		projectile.put("projectileId", 700001);
		SourceResolution r = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(hit("unknown").toBuilder().attributionCandidates(Arrays.asList(item, projectile)).build());
		assertEquals("ALTERNATIVES", r.getStatus());
		assertEquals(1, r.getCandidates().size());
		assertTrue(r.isUnidentifiedPossible());
		assertEquals("Unfamiliar input", r.getCandidates().get(0).getName());
	}

	@Test
	public void distinctInputWitnessesWithoutRuleOrItemIdentityCannotCollapseIntoOneSource()
	{
		Map<String, Object> first = new HashMap<>();
		first.put("kind", "ITEM_CONSUMPTION_CANDIDATE");
		first.put("name", "Input A");
		Map<String, Object> second = new HashMap<>();
		second.put("kind", "PICKPOCKET_FAILURE_CORRELATION");
		second.put("name", "Input B");
		SourceResolution r = resolver(Collections.singletonList(frame(2, Collections.emptyList(), null)), null)
			.resolve(hit("unknown").toBuilder().attributionCandidates(Arrays.asList(first, second)).build());
		assertEquals("ALTERNATIVES", r.getStatus());
		assertEquals(2, r.getCandidates().size());
		assertNotEquals(r.getCandidates().get(0).getKey(), r.getCandidates().get(1).getKey());
		assertTrue(r.getCandidates().stream().allMatch(c -> c.getActorRef() == null && c.getFamilyKey() == null));
	}

	@Test
	public void explicitNonOrdinaryOutcomeCannotBorrowAnOrdinaryNumericEnvelope() throws Exception
	{
		DamageMap map = toyEnvelopeMap();
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		((com.google.gson.JsonObject) field.get(map.catalog())).getAsJsonObject("reviewedBindings")
			.getAsJsonObject("npcOverrides").getAsJsonObject("900001").getAsJsonObject("animations")
			.getAsJsonObject("777777").addProperty("outcomeMechanism", "ENVIRONMENT_CONTACT");
		Map<String, Object> raw = new HashMap<>();
		raw.put("kind", "NPC_ANIMATION_CORRELATION");
		raw.put("name", "Unfamiliar entity");
		raw.put("actorRef", "npc:unfamiliar:v0:g1");
		raw.put("npcId", 900001);
		raw.put("animationId", 777777);
		DamageRecord h = hit("unknown").toBuilder().amount(16).hitsplatType(16)
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot frame = frame(2, Collections.emptyList(), null).toBuilder()
			.npcs(Collections.singletonList(npcVariant(false, 80))).build();
		SourceResolution r = resolver(Collections.singletonList(frame), map).resolve(h);
		assertEquals(1, r.getCandidates().size());
		assertNull(r.getExcludedCandidates());
		assertTrue(r.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.contains("REFERENCE_MAXIMUM:")));
	}

}
