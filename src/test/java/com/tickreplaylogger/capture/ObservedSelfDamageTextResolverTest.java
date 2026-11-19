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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Current bounded source declarations, with independent adverse outcome and channel controls. */
public class ObservedSelfDamageTextResolverTest
{
	private static final String ORB = "The locator orb is unstable and hurts you as you use it.";
	private static final String BOW = "You attempt to string the bow, but burn yourself in the process. You'll need a Fletching level of 74 to craft a scorching bow.";
	private static CombatCatalog catalog;
	private static JsonObject declarations;

	@BeforeClass
	public static void loadOnlyNeededCurrentPackagedSections() throws Exception
	{
		// Keep the actual producer declarations and hitsplat taxonomy, without retaining the full catalog DOM.
		try (InputStream in = CombatCatalog.class.getResourceAsStream("/com/tickreplaylogger/combat_catalog.json"))
		{
			assertNotNull(in);
			JsonObject packaged = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			assertTrue("Current packaged source declarations must be synchronized", packaged.has("observedSelfDamageTextHints"));
			declarations = packaged.getAsJsonObject("observedSelfDamageTextHints").deepCopy();
			JsonObject tiny = new JsonObject(); tiny.add("hitsplatRules", packaged.getAsJsonObject("hitsplatRules").deepCopy());
			tiny.add("selfDamageInputs", packaged.getAsJsonObject("selfDamageInputs").deepCopy());
			tiny.add("observedSelfDamageTextHints", declarations);
			catalog = new CombatCatalog();
			Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true); field.set(catalog, tiny);
		}
	}

	@AfterClass public static void releasePackagedSections() { catalog = null; declarations = null; }

	@Test public void currentTwoRulesHaveIndependentCeilingsAndNeverInventAnItemInstance()
	{
		assertEquals(2, declarations.getAsJsonArray("rules").size());
		for (String name : Arrays.asList("Locator orb", "Scorching bow"))
		{
			JsonObject row = rule(name);
			int maximum = name.equals("Locator orb") ? 10 : 4;
			assertEquals(maximum, row.get("admissionMaximum").getAsInt());
			ObservedActorActivityResolver.Result result = resolver(message(name.equals("Locator orb") ? ORB : BOW, "GAMEMESSAGE", 5)).resolve(hit(maximum), frame(), catalog);
			assertEquals(1, result.getCandidates().size()); assertTrue(result.isIncomplete());
			Candidate candidate = result.getCandidates().get(0);
			assertEquals(name, candidate.getName()); assertEquals("POSSIBLE", candidate.getStrength());
			assertEquals("input-rule:wiki:" + name, candidate.getFamilyKey());
			assertNull(candidate.getActorRef()); assertNull(candidate.getNpcId());
			assertEquals("native", candidate.getSceneKey()); assertEquals(Integer.valueOf(7), candidate.getWorldViewId());
			assertTrue(candidate.getEvidence().toString().contains("item possession, accepted action, ordering and cause are not established"));
		}
	}

	@Test public void eachActualHpCallbackIsAdmittedAtTheCeilingButNotOneAbove()
	{
		for (String text : Arrays.asList(ORB, BOW))
		{
			int maximum = text.equals(ORB) ? 10 : 4;
			TextFixture resolver = resolver(message(text, "SPAM", 5));
			assertEquals(1, resolver.resolve(hit(maximum), frame(), catalog).getCandidates().size());
			assertTrue(resolver.resolve(hit(maximum + 1), frame(), catalog).getCandidates().isEmpty());
			assertTrue(resolver.resolve(hit(-1), frame(), catalog).getCandidates().isEmpty());
			assertEquals(1, resolver.resolve(hit(0).toBuilder().hitsplatType(12).build(), frame(), catalog).getCandidates().size());
		}
	}

	@Test public void onlyExactResponseCaseSpacingPunctuationAndSystemChannelsMatch()
	{
		for (String channel : Arrays.asList("GAMEMESSAGE", "SPAM"))
			assertEquals(1, resolver(message(ORB, channel, 5)).resolve(hit(10), frame(), catalog).getCandidates().size());
		for (String channel : Arrays.asList("PUBLICCHAT", "PRIVATECHAT", "OVERHEAD_TEXT_CHANGED", "gamemessage"))
			assertTrue(channel, resolver(message(ORB, channel, 5)).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		for (String text : Arrays.asList(ORB.toLowerCase(java.util.Locale.ROOT), ORB + " ", ORB.substring(0, ORB.length() - 1),
			"That tasted a bit dodgy. You feel a bit ill.", "That tasted very dodgy. You feel very ill.", "Ow!"))
			assertTrue(text, resolver(message(text, "GAMEMESSAGE", 5)).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		assertEquals(1, resolver(message("<col=ff0000>" + ORB + "</col>", "GAMEMESSAGE", 5)).resolve(hit(10), frame(), catalog).getCandidates().size());
	}

	@Test public void eitherSameTickCallbackOrderIsAUniqueUncertainAssociation()
	{
		for (long sequence : Arrays.asList(5L, 7L))
		{
			ObservedEvent event = message(ORB, "GAMEMESSAGE", sequence);
			ObservedActorActivityResolver.Result result = resolver(event, event).resolve(hit(10), frame(), catalog);
			assertEquals(1, result.getCandidates().size()); assertTrue(result.isIncomplete());
		}
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).tick(9).build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).type("OVERHEAD_TEXT_CHANGED").build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
	}

	@Test public void originalCallbackContextIsRequiredAndLaterContextCannotSupplyModeFacts()
	{
		// The test converts successful callback facts to their own explicit scalar-read evidence.
		assertEquals(1, resolver(message(ORB, "GAMEMESSAGE", 5)).resolve(hit(10), frame(), catalog).getCandidates().size());
		for (ContextBlock changed : Arrays.asList(
			context().toBuilder().observedVariables(Collections.emptyMap()).build(),
			context().toBuilder().observedVariables(Collections.singletonMap("LEAGUE_TYPE", 1)).build(),
			context().toBuilder().worldTypes(null).build(),
			context().toBuilder().evidence(Collections.emptyMap()).build(),
			context().toBuilder().evidence(evidence("worldTypes", "UNAVAILABLE")).build(),
			context().toBuilder().evidence(evidence("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES", "LEAGUE_TYPE", "UNAVAILABLE")).build()))
			assertTrue(resolver(message(ORB, "GAMEMESSAGE", 5)).withOriginal(changed).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		for (String excluded : Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA"))
		{
			ContextBlock exceptional = context().toBuilder().worldTypes(Collections.singletonList(excluded)).build();
			assertTrue(excluded, resolver(message(ORB, "GAMEMESSAGE", 5)).withOriginal(exceptional).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
			assertEquals(1, resolver(message(ORB, "GAMEMESSAGE", 5)).resolve(hit(10), frame().toBuilder().context(exceptional).build(), catalog).getCandidates().size());
		}
		assertTrue(new ObservedSelfDamageTextResolver(declarations, Collections.singletonList(message(ORB, "GAMEMESSAGE", 5)))
			.resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
	}

	@Test public void unavailableOrContradictoryScopeAndFailedCallbackReadsDoNotBorrowImpactScope()
	{
		TextFixture resolver = resolver(message(ORB, "GAMEMESSAGE", 5));
		for (WorldSnapshot changed : Arrays.asList(frame().toBuilder().nativeContextKnown(false).build(), frame().toBuilder().worldViewId(8).build(),
			frame().toBuilder().sceneKey("foreign").build(), frame().toBuilder().plane(0).build(), frame().toBuilder().tick(11).build()))
			assertTrue(resolver.resolve(hit(10), changed, catalog).getCandidates().isEmpty());
		for (DamageRecord changed : Arrays.asList(hit(10).toBuilder().observationSequence(null).build(), hit(10).toBuilder().observationSequence(0L).build(),
			hit(10).toBuilder().impactWorldViewId(null).build(), hit(10).toBuilder().impactSceneKey(null).build(), hit(10).toBuilder().impactPlane(null).build()))
			assertTrue(resolver.resolve(changed, frame(), catalog).getCandidates().isEmpty());
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).sceneKey("foreign").build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		Map<String, Object> missing = fields(ORB, "GAMEMESSAGE"); missing.put("messagePresent", false);
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).data(missing).build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		Map<String, Object> notReady = fields(ORB, "GAMEMESSAGE"); notReady.put("callbackGameState", "LOGIN_SCREEN");
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).data(notReady).build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
		Map<String, Object> failed = fields(ORB, "GAMEMESSAGE"); failed.put("captureIssues", Collections.singletonList("callbackGameState: unavailable"));
		assertTrue(resolver(messageBuilder(ORB, "GAMEMESSAGE", 5).data(failed).build()).resolve(hit(10), frame(), catalog).getCandidates().isEmpty());
	}

	@Test public void statusMixedUnknownAndNonlocalOutcomesCannotGainAnHpSource()
	{
		TextFixture resolver = resolver(message(ORB, "GAMEMESSAGE", 5));
		for (int type : Arrays.asList(2, 5, 3, 6, 999999))
			assertTrue("Type " + type, resolver.resolve(hit(4).toBuilder().hitsplatType(type).build(), frame(), catalog).getCandidates().isEmpty());
		for (DamageRecord changed : Arrays.asList(hit(4).toBuilder().hitsplatTypeKnown(false).build(),
			hit(4).toBuilder().direction("DEALT").defenderRef("npc:target").build(), hit(4).toBuilder().defenderRef("otherplayer:7").build()))
			assertTrue(resolver.resolve(changed, frame(), catalog).getCandidates().isEmpty());
		assertTrue(resolver.resolve(hit(4).toBuilder().direction("RESOURCE_IMPACT").hitsplatType(3).build(), frame(), catalog).getCandidates().isEmpty());
	}

	@Test public void realMenuProducerAndSystemResponseResolveAsOneSourceWithoutInventingAnItemInstance() throws Exception
	{
		SelfDamageInputs inputs = new SelfDamageInputs();
		inputs.observe(catalog, 22081, "Feel", 10, 4);
		java.util.List<Map<String, Object>> observedInputs = inputs.candidates(10);
		assertEquals(1, observedInputs.size());
		assertEquals("wiki:Locator orb", observedInputs.get(0).get("sourceRef"));
		DamageRecord actualHit = hit(10).toBuilder().attributionCandidates(observedInputs).build();
		WorldSnapshot actualFrame = frame().toBuilder().player(PlayerState.builder().worldViewId(7).build())
			.npcs(Collections.emptyList()).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
		DamageMap map = new DamageMap();
		Field field = DamageMap.class.getDeclaredField("catalog"); field.setAccessible(true); field.set(map, catalog);
		SourceResolution result = new RecordedSourceResolver(Collections.singletonList(actualFrame), Collections.emptyList(), map,
			Arrays.asList(message(ORB, "GAMEMESSAGE", 5), ObservedHpCallbackContextTest.observation(actualHit, context())), Collections.emptyList()).resolve(actualHit);
		assertEquals(1, result.getCandidates().size()); assertTrue(result.isUnidentifiedPossible());
		assertNotEquals("IDENTIFIED", result.getStatus());
		Candidate merged = result.getCandidates().get(0);
		assertEquals("input-rule:wiki:Locator orb", merged.getFamilyKey());
		assertEquals("Locator orb", merged.getName());
		assertNull(merged.getActorRef()); assertNull(merged.getNpcId());
		assertTrue(merged.getEvidence().stream().anyMatch(e -> e.contains("SELF_DAMAGE_INPUT_CANDIDATE")));
		assertTrue(merged.getEvidence().stream().anyMatch(e -> e.startsWith("SELF_SYSTEM_RESPONSE_RULE:")));
		assertEquals(10, actualHit.getAmount());
	}

	@Test public void qualifiedResponseContradictionExcludesOnlyTheActualMenuSourceAndRetainsRawHpAndRival() throws Exception
	{
		for (long responseSequence : Arrays.asList(5L, 7L))
		{
			SelfDamageInputs inputs = new SelfDamageInputs(); inputs.observe(catalog, 22081, "Feel", 10, 4);
			java.util.List<Map<String, Object>> rawInputs = inputs.candidates(10);
			Map<String, Object> rival = new HashMap<>(); rival.put("kind", "ITEM_CONSUMPTION_CANDIDATE");
			rival.put("name", "Other recorded cause"); rival.put("sourceRef", "fixture:rival"); rawInputs.add(rival);
			String rawBefore = new Gson().toJson(rawInputs);
			DamageRecord observed = hit(50).toBuilder().attributionCandidates(rawInputs).build();
			SourceResolution result = recordedResolution(observed, frame(), message(ORB, "GAMEMESSAGE", responseSequence));
			assertEquals(1, result.getCandidates().size()); assertEquals("Other recorded cause", result.getCandidates().get(0).getName());
			assertEquals(1, result.getExcludedCandidates().size());
			Candidate excluded = result.getExcludedCandidates().get(0);
			assertEquals("input-rule:wiki:Locator orb", excluded.getFamilyKey());
			assertTrue(excluded.getReasons().stream().anyMatch(reason -> reason.contains("self_text_hint:Locator orb")
				&& reason.contains("amount=50") && reason.contains("maximum=10") && reason.contains("responseSequence=" + responseSequence)));
			assertEquals(rawBefore, new Gson().toJson(observed.getAttributionCandidates()));
			assertEquals(50, observed.getAmount());
		}
	}

	@Test public void missingResponseOrUnknownContextCannotPutAResponseBoundOnAnUnrelatedMenuCandidate() throws Exception
	{
		SelfDamageInputs inputs = new SelfDamageInputs(); inputs.observe(catalog, 22081, "Feel", 10, 4);
		DamageRecord observed = hit(50).toBuilder().attributionCandidates(inputs.candidates(10)).build();
		SourceResolution missing = recordedResolution(observed, frame());
		SourceResolution unknown = recordedResolution(observed,
			frame().toBuilder().context(context().toBuilder().observedVariables(Collections.emptyMap()).build()).build(),
			message(ORB, "GAMEMESSAGE", 5));
		for (SourceResolution result : Arrays.asList(missing, unknown))
		{
			assertEquals(1, result.getCandidates().size());
			assertEquals("input-rule:wiki:Locator orb", result.getCandidates().get(0).getFamilyKey());
			assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
		}
		Candidate input = Candidate.builder().key("actual-menu").familyKey("input-rule:wiki:Locator orb")
			.sceneKey("native").worldViewId(7).plane(2).build();
		TextFixture resolver = resolver(message(ORB, "GAMEMESSAGE", 5));
		assertTrue(resolver.eligibilityExclusions(observed, frame(), input.toBuilder().familyKey("input-rule:wiki:Unrelated item").build(), catalog).isEmpty());
		assertTrue(resolver.eligibilityExclusions(observed, frame(), input.toBuilder().worldViewId(8).build(), catalog).isEmpty());
		assertTrue(resolver.eligibilityExclusions(observed.toBuilder().hitsplatType(5).build(), frame(), input, catalog).isEmpty());
	}

	@Test public void responseAmountExclusionsAlsoUseTheOriginalCallbackModeInsteadOfTheLaterFrame()
	{
		Candidate input = Candidate.builder().key("actual-menu").familyKey("input-rule:wiki:Locator orb")
			.sceneKey("native").worldViewId(7).plane(2).build();
		TextFixture fixture = resolver(message(ORB, "GAMEMESSAGE", 5));
		ContextBlock exceptional = context().toBuilder().worldTypes(Collections.singletonList("SEASONAL")).build();
		assertFalse(fixture.eligibilityExclusions(hit(50), frame().toBuilder().context(exceptional).build(), input, catalog).isEmpty());
		assertTrue(fixture.withOriginal(exceptional).eligibilityExclusions(hit(50), frame(), input, catalog).isEmpty());
		assertTrue(fixture.withOriginal(null).eligibilityExclusions(hit(50), frame(), input, catalog).isEmpty());
		assertTrue(new ObservedSelfDamageTextResolver(declarations, Collections.singletonList(message(ORB, "GAMEMESSAGE", 5)))
			.eligibilityExclusions(hit(50), frame(), input, catalog).isEmpty());
	}

	private static SourceResolution recordedResolution(DamageRecord hit, WorldSnapshot frame, ObservedEvent... events) throws Exception
	{
		WorldSnapshot actual = frame.toBuilder().player(PlayerState.builder().worldViewId(7).build())
			.npcs(Collections.emptyList()).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
		DamageMap map = new DamageMap();
		Field field = DamageMap.class.getDeclaredField("catalog"); field.setAccessible(true); field.set(map, catalog);
		java.util.List<ObservedEvent> originalEvents = new java.util.ArrayList<>(Arrays.asList(events));
		originalEvents.add(ObservedHpCallbackContextTest.observation(hit, frame.getContext()));
		return new RecordedSourceResolver(Collections.singletonList(actual), Collections.emptyList(), map,
			originalEvents, Collections.emptyList()).resolve(hit);
	}

	private static JsonObject rule(String name)
	{
		for (JsonElement value : declarations.getAsJsonArray("rules")) if (name.equals(value.getAsJsonObject().get("name").getAsString())) return value.getAsJsonObject();
		throw new AssertionError(name);
	}
	private static TextFixture resolver(ObservedEvent... events) { return new TextFixture(events); }
	private static final class TextFixture
	{
		private final ObservedEvent[] events;
		private ContextBlock original = context();
		TextFixture(ObservedEvent[] events) { this.events = events; }
		TextFixture withOriginal(ContextBlock value) { original = value; return this; }
		private ObservedSelfDamageTextResolver actual(DamageRecord observed)
		{
			// Freeze the actual fixture callback independently from the query's potentially changed scope/clock.
			DamageRecord raw = hit(observed.getAmount()).toBuilder().hitsplatType(observed.getHitsplatType()).build();
			java.util.List<ObservedEvent> facts = new java.util.ArrayList<>(Arrays.asList(events));
			facts.add(ObservedHpCallbackContextTest.observation(raw, original));
			return new ObservedSelfDamageTextResolver(declarations, facts);
		}
		ObservedActorActivityResolver.Result resolve(DamageRecord hit, WorldSnapshot frame, CombatCatalog catalog)
		{ return actual(hit).resolve(hit, frame, catalog); }
		java.util.List<String> eligibilityExclusions(DamageRecord hit, WorldSnapshot frame, Candidate candidate, CombatCatalog catalog)
		{ return actual(hit).eligibilityExclusions(hit, frame, candidate, catalog); }
	}
	private static ContextBlock context()
	{ return ContextBlock.builder().worldTypes(Collections.emptyList()).observedVariables(Collections.singletonMap("LEAGUE_TYPE", 0)).evidence(evidence("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES")).build(); }
	private static Map<String, String> evidence(String... values)
	{ Map<String, String> result = new HashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]); return result; }
	private static WorldSnapshot frame()
	{ return WorldSnapshot.builder().tick(10).sceneKey("native").worldViewId(7).plane(2).nativeContextKnown(true).context(context()).build(); }
	private static DamageRecord hit(int amount)
	{ return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).impactSceneKey("native").impactWorldViewId(7).impactPlane(2).observationSequence(6L).observedCycle(300).hitsplatTypeKnown(true).hitsplatType(16).amount(amount).build(); }
	private static Map<String, Object> fields(String text, String channel)
	{ Map<String, Object> fields = new HashMap<>(); fields.put("message", text); fields.put("channel", channel); fields.put("messagePresent", true); fields.put("callbackGameState", "LOGGED_IN"); return fields; }
	private static ObservedEvent message(String text, String channel, long sequence)
	{ return messageBuilder(text, channel, sequence).build(); }
	private static ObservedEvent.ObservedEventBuilder messageBuilder(String text, String channel, long sequence)
	{ return ObservedEvent.builder().type("SYSTEM_MESSAGE").tick(10).sceneKey("native").sequence(sequence).gameCycle(sequence < 6 ? 299 : 301).data(fields(text, channel)); }
}
