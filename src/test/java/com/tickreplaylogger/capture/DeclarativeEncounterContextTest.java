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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ContextBlock;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out identifiers exercise the operators, not another encounter-specific implementation. */
public class DeclarativeEncounterContextTest
{
	private final Gson gson = new Gson();
	private final CombatCatalog catalog = CombatCatalog.referenceView(
		RuntimeCatalog.load(gson).section("references").deepCopy());
	private final Map<Integer, Integer> values = new HashMap<>();
	private boolean failLastSlot;

	private void heldOutRules()
	{
		JsonObject context = catalog.contextBindings();
		context.add("observedVarbits",
			gson.fromJson(
				"{\"HELD_STATE\":991001,\"HELD_LEVEL\":991002,\"HELD_SLOT_A\":991003," + "\"HELD_SLOT_B\":991004}",
				JsonObject.class));
		context.add("regionActivities", new JsonObject());
		context.add("encounterModeRules", new JsonArray());
		context.add("resources", new JsonArray());
		context.add("captureRules",
			gson.fromJson("{\"version\":1,\"activityFallbackRules\":[{\"activity\":\"held_out_arena\","
				+ "\"variable\":\"HELD_STATE\",\"cases\":[{\"values\":[7],\"evidence\":\"HELD_STATE=7\"}]}],"
				+ "\"booleanRules\":[],\"scalarRules\":[{\"activity\":\"held_out_arena\","
				+ "\"target\":\"raidLevel\",\"variable\":\"HELD_LEVEL\",\"minimum\":0}],"
				+ "\"partyRules\":[{\"activity\":\"held_out_arena\",\"target\":\"teamSize\","
				+ "\"variables\":[\"HELD_SLOT_A\",\"HELD_SLOT_B\"],\"baseCount\":1,\"minimum\":1,"
				+ "\"evidence\":\"OBSERVED_HELD_SLOTS\"}],\"unknownEvidence\":[]}", JsonObject.class));
		values.put(991001, 7);
		values.put(991002, 0);
		values.put(991003, 30);
		values.put(991004, 0);
	}

	@Test
	public void heldOutBindingsDoNotReplaceTheSharedBindingsUsedByIndependentCaptures()
	{
		JsonObject reviewed = CombatCatalog.load(gson).contextBindings().deepCopy();
		heldOutRules();
		assertEquals("held_out_arena", capture().getActivity());
		assertEquals(reviewed, CombatCatalog.load(gson).contextBindings());
		assertEquals(reviewed, RuntimeCatalog.load(gson).section("references").getAsJsonObject("contextBindings"));
	}

	@Test
	public void unfamiliarActivityUsesDataAndKeepsKnownZeroAndDeadMember()
	{
		heldOutRules();
		ContextBlock context = capture();
		assertEquals("held_out_arena", context.getActivity());
		assertEquals(Integer.valueOf(0), context.getRaidLevel());
		assertEquals(Integer.valueOf(2), context.getTeamSize());
		assertNull(context.getInvocations());
		failLastSlot = true;
		assertNull(capture().getTeamSize());
	}

	@Test
	public void futureCaptureRuleVersionRetainsRawVariablesWithoutInterpretingDefaults()
	{
		heldOutRules();
		catalog.contextBindings().getAsJsonObject("captureRules").addProperty("version", 2);
		ContextBlock context = capture();
		assertNull(context.getActivity());
		assertNull(context.getRaidLevel());
		assertNull(context.getTeamSize());
		assertEquals(Integer.valueOf(7), context.getObservedVariables().get("HELD_STATE"));
		assertEquals(Integer.valueOf(0), context.getObservedVariables().get("HELD_LEVEL"));
	}

	@Test
	public void heldOutModeAndWorldGuardsUseTheSameInterpreter()
	{
		JsonObject bindings = catalog.contextBindings();
		JsonObject rule = bindings.getAsJsonArray("encounterModeRules").get(0).getAsJsonObject().deepCopy();
		rule.addProperty("key", "synthetic_mode");
		rule.addProperty("activity", "held_out_arena");
		rule.addProperty("symbol", "HELD_LEVEL");
		rule.addProperty("id", 991002);
		rule.addProperty("sampleField", "raidLevel");
		rule.addProperty("minimumRawValue", 0);
		rule.addProperty("unverifiedRegionEvidence", "HELD_REGION_UNVERIFIED");
		rule.add("requiredZeroVariables", gson.fromJson("[\"HELD_MODIFIER\"]", JsonArray.class));
		rule.add("excludedWorldTypeFragments", gson.fromJson("[\"EXPERIMENTAL\"]", JsonArray.class));
		rule.add("cases",
			gson.fromJson("[{\"min\":0,\"max\":10,\"state\":\"low\",\"label\":\"Low\"}]", JsonArray.class));
		JsonArray rules = new JsonArray();
		rules.add(rule);
		bindings.add("encounterModeRules", rules);
		bindings.add("regionActivities",
			gson.fromJson("{\"12850\":{\"activity\":\"held_out_arena\",\"room\":\"Synthetic\"}}", JsonObject.class));
		Map<String, Integer> observed = new HashMap<>();
		observed.put("HELD_LEVEL", 0);
		observed.put("HELD_MODIFIER", 0);
		Map<String, String> evidence = new HashMap<>();
		evidence.put("activity", "OBSERVED_TEMPLATE_REGION:12850");
		ContextBlock context = ContextBlock.builder().activity("held_out_arena").templateRegionId(12850).raidLevel(0)
			.worldTypes(Collections.emptyList()).observedVariables(observed).evidence(evidence).build();
		assertTrue(EncounterModeCapture.interpret(context, catalog).isKnown());
		assertEquals("low", EncounterModeCapture.interpret(context, catalog).getState());
		observed.remove("HELD_MODIFIER");
		assertFalse(EncounterModeCapture.interpret(context, catalog).isKnown());
		observed.put("HELD_MODIFIER", 1);
		assertEquals("SPECIAL_WORLD_CONTEXT", EncounterModeCapture.interpret(context, catalog).getEvidence());
	}

	private ContextBlock capture()
	{
		Player player = stub(Player.class,
			(name, args) -> name.equals("getWorldLocation") ? new WorldPoint(3220, 3220, 0) : null);
		Client client = stub(Client.class, (name, args) ->
		{
			if (name.equals("getLocalPlayer"))
				return player;
			if (name.equals("getWorldType"))
				return EnumSet.noneOf(WorldType.class);
			if (name.equals("getVarbitValue"))
			{
				int id = (Integer) args[0];
				if (failLastSlot && id == 991004)
					throw new IllegalStateException("Unavailable slot");
				return values.getOrDefault(id, 0);
			}
			return null;
		});
		return EncounterContextCapture.capture(client, player, catalog, null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (method.getName().equals("equals"))
				return proxy == args[0];
			Object value = read.apply(method.getName(), args);
			if (value != null)
				return value;
			if (method.getReturnType() == boolean.class)
				return false;
			if (method.getReturnType() == int.class)
				return 0;
			return null;
		});
	}
}
