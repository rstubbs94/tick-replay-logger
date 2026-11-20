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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent negative controls for the original HP callback clock and native recipient binding. */
public class ObservedHpCallbackContextTest
{
	static ContextBlock normal()
	{
		return ContextBlock.builder().worldTypes(Collections.emptyList())
			.observedVariables(Collections.singletonMap("LEAGUE_TYPE", 0))
			.evidence(Collections.singletonMap("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES")).build();
	}
	static DamageRecord hit()
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(5)
			.observationSequence(20L).observedCycle(100).impactSceneKey("native").impactWorldViewId(0).impactPlane(0)
			.hitsplatTypeKnown(true).hitsplatType(16).amount(5).build();
	}
	static ObservedEvent observation(DamageRecord hit, ContextBlock context)
	{
		Map<String, Object> actor = new LinkedHashMap<>();
		actor.put("actorKind", "LOCAL_PLAYER"); actor.put("worldPositionKnown", true); actor.put("planeKnown", true);
		actor.put("worldViewId", hit.getImpactWorldViewId()); actor.put("plane", hit.getImpactPlane());
		Map<String, Object> numeric = new LinkedHashMap<>(), evidence = new LinkedHashMap<>();
		numeric.put("observationKind", "STATIC_WORLD_AT_HP_CALLBACK"); numeric.put("localRecipientKnown", true);
		numeric.put("observedTick", hit.getImpactTick()); numeric.put("observedCycle", hit.getObservedCycle());
		if (context != null)
		{
			numeric.put("worldTypes", context.getWorldTypes());
			numeric.put("leagueType", context.getObservedVariables() == null ? null : context.getObservedVariables().get("LEAGUE_TYPE"));
			if (context.getEvidence() != null)
			{
				evidence.put("worldTypes", context.getEvidence().get("worldTypes"));
				if (context.getObservedVariables() != null && context.getObservedVariables().containsKey("LEAGUE_TYPE")
					&& !"UNAVAILABLE".equals(context.getEvidence().get("LEAGUE_TYPE"))) evidence.put("LEAGUE_TYPE", "OBSERVED_CLIENT_VARBIT");
			}
		}
		numeric.put("evidence", evidence);
		Map<String, Object> data = new LinkedHashMap<>(); data.put("actorState", actor); data.put("numericContext", numeric);
		data.put("receiverKind", "PLAYER"); data.put("amount", hit.getAmount()); data.put("hitsplatType", hit.getHitsplatType());
		return ObservedEvent.builder().type("HITSPLAT").actorRef("player").tick(hit.getImpactTick()).gameCycle(hit.getObservedCycle())
			.sequence(hit.getObservationSequence()).sceneKey(hit.getImpactSceneKey()).data(data).build();
	}
	private static boolean normal(LegacyObservedHpCallbackContext contexts, DamageRecord hit)
	{ return LegacyObservedHpCallbackContext.normal(contexts.forHit(hit), Collections.singletonList("LEAGUE_TYPE"), Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA")); }
	@SuppressWarnings("unchecked") private static Map<String, Object> nested(ObservedEvent event, String field)
	{ return (Map<String, Object>) event.getData().get(field); }

	@Test public void originalSequenceClockNativeRecipientAndObservedValueMustAllMatch()
	{
		DamageRecord hit = hit(); ObservedEvent original = observation(hit, normal());
		LegacyObservedHpCallbackContext contexts = new LegacyObservedHpCallbackContext(Collections.singletonList(original));
		assertTrue(normal(contexts, hit));
		for (DamageRecord changed : Arrays.asList(hit.toBuilder().observationSequence(21L).build(), hit.toBuilder().impactTick(6).build(),
			hit.toBuilder().observedCycle(101).build(), hit.toBuilder().impactSceneKey("other").build(), hit.toBuilder().impactWorldViewId(1).build(),
			hit.toBuilder().impactPlane(1).build(), hit.toBuilder().defenderRef("player:other").build(), hit.toBuilder().amount(6).build(),
			hit.toBuilder().hitsplatType(65).build(), hit.toBuilder().hitsplatTypeKnown(false).build())) assertFalse(normal(contexts, changed));
	}

	@Test public void unavailableDuplicateAndContradictoryOriginalMetadataCannotQualify()
	{
		assertFalse(normal(new LegacyObservedHpCallbackContext(null), hit()));
		ObservedEvent valid = observation(hit(), normal());
		assertFalse(normal(new LegacyObservedHpCallbackContext(Arrays.asList(valid, valid)), hit()));
		assertFalse(normal(new LegacyObservedHpCallbackContext(Arrays.asList(valid, valid, valid)), hit()));
		for (int change = 0; change < 11; change++)
		{
			ObservedEvent event = observation(hit(), normal());
			if (change == 0) event.getData().remove("numericContext");
			if (change == 1) nested(event, "numericContext").put("observedCycle", 99);
			if (change == 2) nested(event, "numericContext").put("observedTick", 4);
			if (change == 3) nested(event, "numericContext").put("localRecipientKnown", false);
			if (change == 4) nested(event, "actorState").put("actorKind", "OTHER_PLAYER");
			if (change == 5) nested(event, "actorState").put("worldViewId", null);
			if (change == 6) nested(event, "actorState").put("worldPositionKnown", false);
			if (change == 7) nested(event, "actorState").put("observationIssues", Collections.singletonMap("worldView", "UNAVAILABLE"));
			if (change == 8) event.getData().put("captureIssues", Collections.singletonList("observationContext: UNAVAILABLE"));
			if (change == 9) nested(event, "numericContext").put("worldTypes", Collections.singletonList(1));
			if (change == 10) nested(event, "actorState").put("plane", -1);
			assertFalse("Change " + change, normal(new LegacyObservedHpCallbackContext(Collections.singletonList(event)), hit()));
		}
	}

	@Test public void independentWorldFactsSurviveUnrelatedNameReadFailureAndAreFrozen()
	{
		ObservedEvent event = observation(hit(), normal()); List<String> names = new ArrayList<>();
		nested(event, "numericContext").put("worldTypes", names);
		nested(event, "actorState").put("observationIssues", Collections.singletonMap("name", "UNAVAILABLE"));
		event.getData().put("captureIssues", Collections.singletonList("observationActorName: UNAVAILABLE"));
		LegacyObservedHpCallbackContext index = new LegacyObservedHpCallbackContext(Collections.singletonList(event));
		names.add("SEASONAL"); nested(event, "numericContext").put("leagueType", 1);
		assertTrue(normal(index, hit()));
		try { index.forHit(hit()).put("leagueType", 1); fail("Frozen callback context must be immutable"); }
		catch (UnsupportedOperationException expected) { }
		assertFalse(LegacyObservedHpCallbackContext.normal(index.forHit(hit()), Collections.singletonList("UNOBSERVED_FACT"), Collections.singletonList("BETA")));
	}

}
