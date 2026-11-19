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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedInventorySourceHintsTest
{
	private static final Gson GSON=new Gson();
	private static final int ID=999812;
	private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	public static JsonObject namespace(int id)
	{
		JsonObject namespace=GSON.fromJson("{\"version\":1,\"rules\":[{\"key\":\"heldout:possession\",\"name\":\"Held item\",\"itemIds\":[],\"referenceKeys\":[],\"sourceRef\":\"wiki:Held item#infobox-item-1\",\"pageHash\":\""+HASH+"\",\"observationKind\":\"LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK\",\"mechanism\":\"ITEM_POSSESSION\",\"quantityPolicy\":\"PRESENCE_ONLY_NO_MULTIPLIER\",\"qualification\":\"POSSIBLE_SOURCE_HINT\",\"unidentifiedPossible\":true,\"observedOutcomeDomain\":\"ORDINARY_HP_CALLBACK_ONLY\",\"observedHitsplatTypes\":[16,17,43]}]}",JsonObject.class);
		JsonObject row=namespace.getAsJsonArray("rules").get(0).getAsJsonObject(); row.add("itemIds",GSON.toJsonTree(Collections.singletonList(id)));
		JsonObject proof=ObservedNpcContactHintResolverTest.namespace(id,"Held item").getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("provenance").deepCopy();
		proof.remove("assets"); proof.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("page","Held item");
		proof.add("items",GSON.fromJson("[{\"id\":"+id+",\"namespace\":\"item\",\"page\":\"Held item\",\"templateOrdinal\":1,\"infoboxSha256\":\""+HASH+"\"}]",com.google.gson.JsonArray.class));
		JsonObject symbols=proof.getAsJsonObject("outcomeCategoryProof").getAsJsonObject("symbols"); symbols.remove("BLOCK_ME"); symbols.remove("BLOCK_OTHER");
		row.add("provenance",proof); return namespace;
	}

	public static <T> T stub(Class<T> type,BiFunction<String,Object[],Object> values)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
			if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
			if (method.getName().equals("equals")) return proxy==args[0];
			Object result=values.apply(method.getName(),args); if (result!=null) return result;
			if (method.getReturnType()==boolean.class) return false;
			if (method.getReturnType()==int.class) return 0;
			if (method.getReturnType()==long.class) return 0L;
			return null;
		}));
	}
	private static ItemContainer container(Item... items) { return stub(ItemContainer.class,(n,a)->n.equals("getItems")?items:null); }
	private static Client client(ItemContainer container) { return stub(Client.class,(n,a)->n.equals("getItemContainer")?container:null); }
	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(5).observedCycle(100).observationSequence(20L).direction("RECEIVED")
			.defenderRef("player").hitsplatType(16).hitsplatTypeKnown(true).amount(1)
			.impactSceneKey("native").impactWorldViewId(0).impactPlane(0).build();
	}

	@Test public void heldOutItemUsesRealPresenceOnlyAndPreservesOriginalCallbackClockAndScope()
	{
		LegacyInventorySourceHints hints=new LegacyInventorySourceHints(namespace(ID));
		Map<String,Object> observed=hints.capture(client(container(new Item(ID,1))),5,100);
		List<Map<String,Object>> raw=hints.witnesses(observed,20);
		assertEquals("OBSERVED",observed.get("status")); assertEquals(1,raw.size());
		LegacyConstraintOracle.Hypothesis result=hints.hypothesis(raw.get(0),hit()); assertNotNull(result);
		assertEquals("ITEM_POSSESSION",result.getMechanism()); assertTrue(result.isCausalAssociationOnly());
		assertEquals("item-possession-rule:heldout:possession",result.getCandidate().getFamilyKey());
		assertEquals("native",result.getCandidate().getSceneKey()); assertEquals(Integer.valueOf(0),result.getCandidate().getWorldViewId());
		assertNull(result.getCandidate().getActorRef()); assertNull(result.getCandidate().getNpcId());
		assertNull(result.getConstraints()); assertNull(result.getNumericObservations());
		assertNotNull("Possession does not silently gain a numeric cap",hints.hypothesis(raw.get(0),hit().toBuilder().amount(9).build()));
		for (DamageRecord wrong:Arrays.asList(hit().toBuilder().impactTick(6).build(),hit().toBuilder().observedCycle(101).build(),
			hit().toBuilder().observedCycle(null).build(),hit().toBuilder().observationSequence(21L).build(),hit().toBuilder().defenderRef("other").build(),
			hit().toBuilder().hitsplatTypeKnown(false).build(),hit().toBuilder().hitsplatType(5).build(),hit().toBuilder().hitsplatType(12).amount(0).build()))
			assertNull(hints.hypothesis(raw.get(0),wrong));
	}

	@Test public void stackQuantityAndDuplicateSlotsDoNotMultiplyCandidateOrHpAndObservationIsFrozen()
	{
		LegacyInventorySourceHints hints=new LegacyInventorySourceHints(namespace(ID)); Item[] actual={new Item(ID,100),new Item(ID,2)};
		Map<String,Object> observed=hints.capture(client(container(actual)),5,100); actual[0]=new Item(1,999); actual[1]=null;
		List<Map<String,Object>> raw=hints.witnesses(observed,20); assertEquals(1,raw.size());
		assertEquals(100,raw.get(0).get("quantity")); assertNotNull(hints.hypothesis(raw.get(0),hit()));
		assertTrue(hints.witnesses(hints.capture(client(container(new Item(1,1))),5,100),20).isEmpty());
		try { observed.put("observedTick",9); fail("immutable callback facts"); } catch (UnsupportedOperationException expected) { }
	}

	@Test public void unavailableIsNotEmptyAndUnknownClockOrIdentityCannotBecomePresence()
	{
		LegacyInventorySourceHints hints=new LegacyInventorySourceHints(namespace(ID));
		Map<String,Object> missing=hints.capture(client(null),5,100);
		assertEquals("UNAVAILABLE",missing.get("status")); assertEquals("INVENTORY_SOURCE_CONTEXT_UNAVAILABLE",hints.witnesses(missing,20).get(0).get("kind"));
		assertTrue(hints.witnesses(hints.capture(client(container()),5,100),20).isEmpty());
		assertNull(hints.hypothesis(hints.witnesses(hints.capture(client(container(new Item(ID,1))),5,null),20).get(0),hit()));
		Map<String,Object> raw=new LinkedHashMap<>(hints.witnesses(hints.capture(client(container(new Item(ID,1))),5,100),20).get(0));
		raw.put("pageHash",HASH.replace('a','b')); assertNull(hints.hypothesis(raw,hit()));
		raw.put("pageHash",HASH); raw.put("quantity",0); assertNull(hints.hypothesis(raw,hit()));
	}

	@Test public void malformedSemanticFieldsOrDuplicateItemNamespacesCannotActivate()
	{
		for (int variant=0;variant<5;variant++)
		{
			JsonObject ns=namespace(ID),row=ns.getAsJsonArray("rules").get(0).getAsJsonObject();
			if (variant==0) row.addProperty("timerTicks",3);
			if (variant==1) row.addProperty("mechanism","SELF_INPUT");
			if (variant==2) row.getAsJsonArray("observedHitsplatTypes").add(5);
			if (variant==3) row.getAsJsonObject("provenance").getAsJsonArray("items").get(0).getAsJsonObject().addProperty("namespace","object");
			if (variant==4) { JsonObject duplicate=row.deepCopy(); duplicate.addProperty("key","contradictory:rule"); ns.getAsJsonArray("rules").add(duplicate); }
			LegacyInventorySourceHints hints=new LegacyInventorySourceHints(ns);
			assertTrue("variant "+variant,hints.witnesses(hints.capture(client(container(new Item(ID,1))),5,100),20).stream()
				.noneMatch(w->"ITEM_POSSESSION_CANDIDATE".equals(w.get("kind"))));
		}
	}

	private static JsonObject boundedNamespace()
	{
		JsonObject namespace=namespace(ID),row=namespace.getAsJsonArray("rules").get(0).getAsJsonObject();
		row.add("componentBound",GSON.fromJson("{\"maximum\":2,\"numericScope\":\"PER_CALLBACK_MAXIMUM\",\"contextPredicate\":{\"requireObservedWorldTypes\":true,\"requiredZeroVariables\":[\"LEAGUE_TYPE\"],\"excludedWorldTypeFragments\":[\"SEASONAL\",\"DEADMAN\",\"TOURNAMENT\",\"BETA\"]},\"maximumWitness\":{\"page\":\"Held item\",\"pageHash\":\""+HASH+"\",\"start\":20,\"end\":21,\"text\":\"2\",\"quantity\":\"ITEM_POSSESSION_HP_PER_CALLBACK_MAXIMUM\"}}",JsonObject.class));
		return namespace;
	}
	private static Client normalClient(ItemContainer container)
	{
		return stub(Client.class,(n,a)->n.equals("getItemContainer")?container:n.equals("getWorldType")?java.util.EnumSet.noneOf(WorldType.class)
			:n.equals("getVarbitValue")?Integer.valueOf(0):null);
	}

	@Test public void optionalHeldOutItemBoundFiltersOnlyItsComponentWithoutInferringCausality()
	{
		LegacyInventorySourceHints hints=new LegacyInventorySourceHints(boundedNamespace());
		Map<String,Object> observed=hints.capture(normalClient(container(new Item(ID,100),new Item(ID,1))),5,100);
		Map<String,Object> raw=hints.witnesses(observed,20).get(0);
		LegacyConstraintOracle.Hypothesis allowed=hints.hypothesis(raw,hit().toBuilder().amount(2).build());
		assertEquals(Integer.valueOf(2),allowed.getConstraints().get(0).getMaximum());
		assertFalse(allowed.getObservations().containsKey("STANDARD_DAMAGE_MODIFIERS"));
		assertFalse(allowed.getObservations().containsKey("QUALIFIED_NPC_VARIANT"));
		assertTrue(LegacyConstraintOracle.resolve(Collections.singletonList(allowed),2,false,"fixture").isUnidentifiedPossible());
		LegacyConstraintOracle.Hypothesis rival=LegacyConstraintOracle.Hypothesis.builder()
			.candidate(com.tickreplaylogger.model.world.SourceResolution.Candidate.builder().key("rival").name("Other observed source").build()).build();
		com.tickreplaylogger.model.world.SourceResolution result=LegacyConstraintOracle.resolve(Arrays.asList(
			hints.hypothesis(raw,hit().toBuilder().amount(9).build()),rival),9,true,"fixture");
		assertEquals(1,result.getExcludedCandidates().size()); assertEquals("Held item",result.getExcludedCandidates().get(0).getName());
		assertEquals(1,result.getCandidates().size()); assertEquals("Other observed source",result.getCandidates().get(0).getName());
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test public void missingExceptionalOrChangedOriginalModeCannotBorrowNormalFactsAndBadBoundStaysUncapped()
	{
		for (int variant=0;variant<7;variant++)
		{
			final int selected=variant; JsonObject namespace=boundedNamespace();
			if (variant==6) namespace.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("componentBound").addProperty("maximum",9);
			LegacyInventorySourceHints hints=new LegacyInventorySourceHints(namespace);
			Client client=stub(Client.class,(n,a)-> {
				if (n.equals("getItemContainer")) return container(new Item(ID,1));
				if (n.equals("getWorldType")) return selected==0?null:selected==1?java.util.EnumSet.of(WorldType.SEASONAL):java.util.EnumSet.noneOf(WorldType.class);
				if (n.equals("getVarbitValue")) { if (selected==2) throw new IllegalStateException("unavailable"); return selected==3?1:0; }
				return null;
			});
			Map<String,Object> raw=new LinkedHashMap<>(hints.witnesses(hints.capture(client,5,100),20).stream()
				.filter(w->"ITEM_POSSESSION_CANDIDATE".equals(w.get("kind"))).findFirst().get());
			if (variant==4) raw.remove("numericContext");
			if (variant==5) { Map<String,Object> changed=new LinkedHashMap<>((Map<String,Object>)raw.get("numericContext")); changed.put("observedCycle",101); raw.put("numericContext",changed); }
			LegacyConstraintOracle.Hypothesis result=hints.hypothesis(raw,hit().toBuilder().amount(9).build());
			assertNotNull("Presence remains factual, case "+variant,result); assertNull("No qualified numeric context, case "+variant,result.getConstraints());
		}
	}
}
