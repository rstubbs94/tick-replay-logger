/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OriginalSourceViewTest
{
	private final Gson gson = new Gson();

	@Test
	public void explicitPlayerNamespaceCannotBecomeAnNpcFromAConflictingDefinitionNumber()
	{
		for (String kind : new String[] { "PLAYER", "OTHER_PLAYER", "LOCAL_PLAYER" })
		{
			JsonObject raw = gson.fromJson("{actorKind:'" + kind + "',npcId:9036,name:'Observed player',worldViewId:0,plane:0,planeKnown:true}", JsonObject.class);
			String original = gson.toJson(raw);
			JsonObject source = OriginalSourceView.actor(raw, "player:observed", "room").source();
			if ("LOCAL_PLAYER".equals(kind)) assertEquals(0, source.size());
			else { assertEquals("player", source.get("kind").getAsString()); assertFalse(source.has("id")); }
			assertEquals(original, gson.toJson(raw));
		}
	}

	@Test
	public void anExactHpWitnessKeepsItsActualReferenceWhenItsActorFieldsAreUnavailable()
	{
		JsonObject witness = gson.fromJson("{kind:'NPC_ACTIVITY_OBSERVATION',actorRef:'npc:original',name:'Observed NPC',"
			+ "sceneKey:'room',worldViewId:0,plane:0,observedTick:10,observedCycle:300,impactSequence:20,actorState:{}}", JsonObject.class);
		String original = gson.toJson(witness);
		OriginalSourceView view = OriginalSourceView.hpActor(witness, 20, 1);
		assertEquals("npc:original", view.source().get("actorRef").getAsString());
		assertEquals("npc", view.source().get("kind").getAsString());
		assertFalse(view.source().has("id"));
		assertFalse(view.source().has("observationKey"));
		assertEquals(0, view.actor().size());
		assertEquals(Long.valueOf(20), view.sourceSequence());
		assertEquals(original, gson.toJson(witness));
		JsonObject cue = new JsonObject(); cue.add("source", view.source()); cue.add("witness", witness);
		cue.addProperty("observationKind", "HP_CALLBACK_ACTOR_SAMPLE");
		JsonObject proof = OriginalSourceProof.evaluate(cue, gson.fromJson("{tick:10,cycle:300,sequence:20}", JsonObject.class));
		assertFalse(proof.get("numeric_reference").getAsBoolean());
		assertFalse(proof.get("static_reference").getAsBoolean());
		assertFalse(proof.get("scalar_actor").getAsBoolean());
	}
}
