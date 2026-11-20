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
package com.tickreplaylogger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import com.tickreplaylogger.capture.DamageMap;
import com.tickreplaylogger.capture.ResolverV1;
import com.tickreplaylogger.capture.ResolverV1Recording;
import com.tickreplaylogger.capture.RuntimeCatalog;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual received-hit operators; a reference candidate never becomes a damage-type fact. */
public class CompositeStyleCandidateHandlerTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void correctedCandidateOnlyPreservesFactsOwnershipPositionsAndPrivacy() throws Exception
	{
		try (Fixture before = new Fixture(false, false); Fixture after = new Fixture(true, false))
		{
			JsonObject oldCapture = before.capture(false), newCapture = after.capture(false);
			JsonObject oldHit = received(oldCapture), newHit = received(newCapture);
			retain("qualified-before", oldCapture);
			retain("qualified-after", newCapture);
			assertEquals(Collections.emptyList(), GSON.fromJson(oldHit.get("damageTypeCandidates"), List.class));
			assertEquals(Collections.singletonList("melee"),
				GSON.fromJson(newHit.get("damageTypeCandidates"), List.class));
			assertEquals("unknown", newHit.get("damageType").getAsString());
			assertEquals("UNKNOWN", newHit.get("damageTypeEvidence").getAsString());
			assertEquals(7, newHit.get("amount").getAsInt());
			assertEquals("player", newHit.get("defenderRef").getAsString());
			assertTrue(newHit.has("attackerRef"));
			assertTrue(newHit.get("impactPositionKnown").getAsBoolean());
			assertEquals(3200, newHit.get("impactX").getAsInt());
			assertEquals(3200, newHit.get("impactY").getAsInt());
			assertFalse(newHit.has("prayerEffect"));
			assertEquals("UNKNOWN", newHit.get("determination").getAsString());
			JsonObject other = otherReceived(newCapture);
			assertEquals("OTHER", other.get("ownership").getAsString());
			assertEquals(9, other.get("amount").getAsInt());
			assertFalse(other.has("attackerRef"));
			assertFalse(GSON.toJson(newCapture).contains("Private teammate"));
			// Remove only the intended candidate hint and real-clock samples. All
			// actor references, source evidence, views/planes, coordinates and raw
			// callback fields must otherwise be identical under actual handlers.
			oldHit.remove("damageTypeCandidates");
			newHit.remove("damageTypeCandidates");
			normalizeResolverId(oldCapture,before.resolver.id());
			normalizeResolverId(newCapture,after.resolver.id());
			stripClocks(oldCapture);
			stripClocks(newCapture);
			assertEquals(oldCapture, newCapture);
		}
	}

	@Test
	public void nonNormalContextCannotGainAReferenceCandidate() throws Exception
	{
		try (Fixture fixture = new Fixture(true, true))
		{
			JsonObject capture = fixture.capture(false), hit = received(capture);
			assertEquals(Collections.emptyList(), GSON.fromJson(hit.get("damageTypeCandidates"), List.class));
			assertEquals("unknown", hit.get("damageType").getAsString());
			assertEquals(7, hit.get("amount").getAsInt());
			assertFalse(GSON.toJson(capture).contains("Private teammate"));
			assertEquals("OTHER", otherReceived(capture).get("ownership").getAsString());
			retain("non-normal", capture);
		}
	}

	@Test
	public void ambiguousAttackerCannotBeCreatedFromTheCandidateMap() throws Exception
	{
		try (Fixture fixture = new Fixture(true, false))
		{
			JsonObject capture = fixture.capture(true), hit = received(capture);
			assertEquals("UNKNOWN", hit.get("attribution").getAsString());
			assertFalse(hit.has("attackerRef"));
			assertEquals(Collections.emptyList(), GSON.fromJson(hit.get("damageTypeCandidates"), List.class));
			assertEquals("unknown", hit.get("damageType").getAsString());
			assertEquals(7, hit.get("amount").getAsInt());
			assertFalse(GSON.toJson(capture).contains("Private teammate"));
			retain("ambiguous", capture);
		}
	}

	@Test
	public void rangedMagicReplacesOnlyTheFalseRangedHintWithAMagicCandidate() throws Exception
	{
		assertCandidateChange(6619, 202, "Chaos Fanatic", 1, Collections.singletonList("ranged"),
			Collections.singletonList("magic"));
	}

	@Test
	public void correctedSpinolypCompositeCannotKeepASoleRangedHint() throws Exception
	{
		assertCandidateChange(5947, 76, "Spinolyp", 1, Collections.singletonList("ranged"), Collections.emptyList());
	}

	@Test
	public void correctedKreeCompositeDoesNotChooseAnIndividualHitStyle() throws Exception
	{
		assertCandidateChange(3162, 580, "Kree'arra", 5, Arrays.asList("melee", "ranged"), Collections.emptyList());
	}

	@Test
	public void rangedMagicHintsStillRequireNormalContextAndAnUnambiguousActor() throws Exception
	{
		for (boolean nonNormal : new boolean[]
		{ true, false })
		{
			try (Fixture fixture = new Fixture(true, nonNormal, 6619, 202, "Chaos Fanatic", 1,
				Collections.singletonList("ranged")))
			{
				JsonObject capture = fixture.capture(!nonNormal), hit = received(capture);
				assertEquals(Collections.emptyList(), GSON.fromJson(hit.get("damageTypeCandidates"), List.class));
				assertEquals("unknown", hit.get("damageType").getAsString());
				assertEquals("UNKNOWN", hit.get("damageTypeEvidence").getAsString());
				assertEquals(7, hit.get("amount").getAsInt());
				assertFalse(GSON.toJson(capture).contains("Private teammate"));
				assertEquals("OTHER", otherReceived(capture).get("ownership").getAsString());
				if (!nonNormal)
				{
					assertEquals("UNKNOWN", hit.get("attribution").getAsString());
					assertFalse(hit.has("attackerRef"));
				}
			}
		}
	}

	private static void assertCandidateChange(int npcId, int level, String name, int size, List<String> beforeStyles,
		List<String> expectedCandidates) throws Exception
	{
		try (Fixture before = new Fixture(false, false, npcId, level, name, size, beforeStyles);
			Fixture after = new Fixture(true, false, npcId, level, name, size, beforeStyles))
		{
			JsonObject oldCapture = before.capture(false), newCapture = after.capture(false);
			JsonObject oldHit = received(oldCapture), newHit = received(newCapture);
			retain("ranged-magic-" + npcId + "-before", oldCapture);
			retain("ranged-magic-" + npcId + "-after", newCapture);
			List<String> previousCandidate = beforeStyles.size() == 1 ? beforeStyles : Collections.emptyList();
			assertEquals(previousCandidate, GSON.fromJson(oldHit.get("damageTypeCandidates"), List.class));
			assertEquals(expectedCandidates, GSON.fromJson(newHit.get("damageTypeCandidates"), List.class));
			assertEquals("unknown", newHit.get("damageType").getAsString());
			assertEquals("UNKNOWN", newHit.get("damageTypeEvidence").getAsString());
			assertEquals("UNKNOWN", newHit.get("determination").getAsString());
			assertFalse(newHit.has("prayerEffect"));
			assertEquals(7, newHit.get("amount").getAsInt());
			assertEquals("player", newHit.get("defenderRef").getAsString());
			assertTrue(newHit.has("attackerRef"));
			assertTrue(newHit.get("impactPositionKnown").getAsBoolean());
			assertEquals(3200, newHit.get("impactX").getAsInt());
			assertEquals(3200, newHit.get("impactY").getAsInt());
			assertEquals("OTHER", otherReceived(newCapture).get("ownership").getAsString());
			assertEquals(9, otherReceived(newCapture).get("amount").getAsInt());
			assertFalse(otherReceived(newCapture).has("attackerRef"));
			assertFalse(GSON.toJson(newCapture).contains("Private teammate"));
			oldHit.remove("damageTypeCandidates");
			newHit.remove("damageTypeCandidates");
			normalizeResolverId(oldCapture,before.resolver.id());
			normalizeResolverId(newCapture,after.resolver.id());
			stripClocks(oldCapture);
			stripClocks(newCapture);
			assertEquals(oldCapture, newCapture);
		}
	}

	private static class Fixture implements AutoCloseable
	{
		final DeferredAttributionTest harness = new DeferredAttributionTest();
		final String observedName;
		final ResolverV1 resolver;

		Fixture(boolean corrected, boolean nonNormal) throws Exception
		{
			this(corrected, nonNormal, 423, 93, "Dust devil", 1, Arrays.asList("melee", "ranged"));
		}

		Fixture(boolean corrected, boolean nonNormal, int npcId, int level, String name, int size,
			List<String> beforeStyles) throws Exception
		{
			harness.temporary.create();
			harness.setup();
			observedName = name;
			set(harness, "npcId", npcId);
			set(harness, "npcLevel", level);
			set(harness, "npcObservedName", name);
			set(harness, "npcObservedSize", Integer.valueOf(size));
			set(harness,"npcReportsInteraction",true);
			call(harness,"useProjectileNativeView",new Class<?>[0]);
			TickReplayLoggerPlugin plugin = (TickReplayLoggerPlugin) get(harness, "plugin");
			DamageMap map = (DamageMap) get(plugin, "damageMap");
			List<String> isolatedStyles=null;
			String candidateFile = System.getProperty("tickreplay.test.compositeCandidateMap");
			if (candidateFile != null)
			{
				JsonObject generated = GSON.fromJson(
					new String(Files.readAllBytes(Paths.get(candidateFile)), StandardCharsets.UTF_8), JsonObject.class);
				Map<String, List<String>> styles = GSON.fromJson(generated.getAsJsonObject("npcStyles"),
					new TypeToken<Map<String, List<String>>>()
					{
					}.getType());
				assertNotNull("An explicit isolated map must contain its generated style root", styles);
				isolatedStyles=styles.get(String.valueOf(npcId));
			}
			if (!corrected)
			{
				isolatedStyles=beforeStyles;
			}
			resolver=isolatedStyles==null ? ResolverV1.load(GSON) : candidateResolver(map,npcId,level,name,isolatedStyles);
			TickReplayLoggerConfig original = (TickReplayLoggerConfig) get(plugin, "config");
			TickReplayLoggerConfig privateConfig = (TickReplayLoggerConfig) Proxy
				.newProxyInstance(TickReplayLoggerConfig.class.getClassLoader(), new Class<?>[]
				{ TickReplayLoggerConfig.class }, (proxy, method,
					args) -> method.getName().equals("captureOtherPlayers") ? false : method.invoke(original, args));
			set(plugin, "config", privateConfig);
			Player privatePlayer = (Player) callStatic("stub", new Class<?>[]
			{ Class.class, java.util.function.BiFunction.class }, Player.class,
				(java.util.function.BiFunction<String, Object[], Object>) (methodName,
					args) -> methodName.equals("getName") ? "Private teammate" : null);
			((List<Player>) get(harness, "otherPlayers")).add(privatePlayer);
			if (nonNormal)
				set(harness, "worldTypes", EnumSet.of(WorldType.DEADMAN));
		}

		JsonObject capture(boolean ambiguous) throws Exception
		{
			if (ambiguous)
			{
				NPC second = (NPC) call(harness, "npc", new Class<?>[]
				{ String.class, int.class }, observedName, 2);
				((List<NPC>) get(harness, "npcs")).add(second);
			}
			call(harness, "tick", new Class<?>[0]);
			set(harness,"animation",123);
			call(harness, "hit", new Class<?>[]
			{ int.class, int.class }, HitsplatID.DAMAGE_ME, 7);
			call(harness, "hit", new Class<?>[]
			{ net.runelite.api.Actor.class, int.class, int.class }, get(harness, "boss"), HitsplatID.DAMAGE_OTHER, 9);
			set(harness, "hp", 92);
			call(harness, "tick", new Class<?>[0]);
			DeathRecordingV2 recorded=(DeathRecordingV2)call(harness,"export",new Class<?>[0]);
			return GSON.toJsonTree(new ResolverV1Recording(resolver,GSON,recorded).enrich(recorded)).getAsJsonObject();
		}

		@Override
		public void close()
		{
			harness.temporary.delete();
		}
	}

	private static ResolverV1 candidateResolver(DamageMap map,int npcId,int level,String name,List<String> styles)
	{
		JsonObject document=RuntimeCatalog.load(GSON).section("resolver").deepCopy(),template=null;
		for(JsonElement value:document.getAsJsonArray("resolutionRules"))
		{
			JsonObject rule=value.getAsJsonObject();
			if(rule.has("merge") && "npc_style_hint".equals(rule.getAsJsonObject("merge").get("group").getAsString()))
			{template=rule.deepCopy();break;}
		}
		assertNotNull("The counterfactual uses the same compiled reference-effect grammar",template);
		template.addProperty("id","test:isolated_candidate_style");
		template.add("source",GSON.toJsonTree(Collections.singletonMap("kind","npc")));
		template.getAsJsonObject("source").add("ids",GSON.toJsonTree(Collections.singletonList(npcId)));
		template.getAsJsonObject("source").add("names",GSON.toJsonTree(Collections.singletonList(name)));
		template.getAsJsonObject("source").add("combatLevels",GSON.toJsonTree(Collections.singletonList(level)));
		template.getAsJsonObject("merge").addProperty("priority",100);
		JsonArray candidates=new JsonArray();if(styles.size()==1)candidates.add(styles.get(0));
		JsonObject annotations=new JsonObject();annotations.add("candidateStyles",candidates);
		JsonObject effects=new JsonObject();effects.add("annotations",annotations);template.add("effects",effects);
		document.getAsJsonArray("resolutionRules").add(template);
		return new ResolverV1(document,GSON,map.catalog(),map);
	}

	private static void normalizeResolverId(JsonElement value,String id)
	{
		if(value.isJsonObject())for(Map.Entry<String,JsonElement> field:value.getAsJsonObject().entrySet())
		{
			JsonElement item=field.getValue();
			if(item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())field.setValue(GSON.toJsonTree(item.getAsString().replace(id,"TEST_RESOLVER")));
			else normalizeResolverId(item,id);
		}
		else if(value.isJsonArray())for(int n=0;n<value.getAsJsonArray().size();n++)
		{
			JsonElement item=value.getAsJsonArray().get(n);
			if(item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())value.getAsJsonArray().set(n,GSON.toJsonTree(item.getAsString().replace(id,"TEST_RESOLVER")));
			else normalizeResolverId(item,id);
		}
	}

	private static JsonObject received(JsonObject capture)
	{
		for (JsonElement entry : capture.getAsJsonObject("events").getAsJsonArray("damageEvents"))
			if (entry.getAsJsonObject().get("direction").getAsString().equals("RECEIVED"))
				return entry.getAsJsonObject();
		throw new AssertionError("Real received-hit handler did not retain its hit");
	}

	private static JsonObject otherReceived(JsonObject capture)
	{
		for (JsonElement entry : capture.getAsJsonObject("events").getAsJsonArray("damageEvents"))
			if (entry.getAsJsonObject().get("direction").getAsString().equals("NPC_RECEIVED"))
				return entry.getAsJsonObject();
		throw new AssertionError("Real NPC received-hit handler did not retain its hit");
	}

	private static Object get(Object target, String name) throws Exception
	{
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception
	{
		Method m = target.getClass().getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(target, args);
	}

	private static Object callStatic(String name, Class<?>[] types, Object... args) throws Exception
	{
		Method m = DeferredAttributionTest.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(null, args);
	}

	private static void stripClocks(JsonElement value)
	{
		if (value.isJsonObject())
		{
			value.getAsJsonObject().remove("wallClockMs");
			for (Map.Entry<String, JsonElement> child : value.getAsJsonObject().entrySet())
				stripClocks(child.getValue());
		}
		else if (value.isJsonArray())
			for (JsonElement child : value.getAsJsonArray())
				stripClocks(child);
	}

	private static void retain(String name, JsonObject value) throws Exception
	{
		Path path = Paths.get("build/composite-style-parser-evidence/" + name + ".json");
		Files.createDirectories(path.getParent());
		Files.write(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
	}
}
