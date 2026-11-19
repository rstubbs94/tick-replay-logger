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
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** NPC observations must survive failures in unrelated display/definition reads. */
public class NpcSnapshotCaptureTest
{
	@Test
	public void everyOptionalNpcReadCanFailWithoutErasingItsOtherObservedFacts()
	{
		for (String method : Arrays.asList("getName", "getCombatLevel", "getModelOverrides", "getCurrentOrientation",
			"getOrientation", "getHealthRatio", "getHealthScale", "getPoseAnimation", "getPoseAnimationFrame",
			"getOverheadText", "getOverheadCycle", "getOverheadArchiveIds", "getOverheadSpriteIds", "getSpotAnims",
			"getInteracting", "isInteracting", "getTransformedComposition", "getComposition"))
		{
			Fixture fixture = new Fixture();
			fixture.fail.add(method);
			WorldSnapshot frame = fixture.frame(1);
			assertEquals(method + " erased the NPC", 1, frame.getNpcs().size());
			NpcState npc = frame.getNpcs().get(0);
			assertEquals(Integer.valueOf(9035), npc.getBaseNpcId());
			assertEquals(6464, npc.getLocalX());
			assertEquals(3200, npc.getWorldX());
			assertNotNull(method, npc.getActorRef());
			assertEquals("partial", frame.getCaptureCoverage().get("npcs"));
			assertTrue(method, frame.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("npcs:")));
		}
	}

	@Test
	public void unavailableWorldPositionDoesNotEraseKnownLocalPositionAndIdentity()
	{
		Fixture fixture = new Fixture();
		fixture.fail.add("getWorldLocation");
		WorldSnapshot frame = fixture.frame(1);
		assertEquals(1, frame.getNpcs().size());
		NpcState npc = frame.getNpcs().get(0);
		assertEquals(Boolean.FALSE, npc.getWorldPositionKnown());
		assertEquals(Boolean.TRUE, npc.getLocalPositionKnown());
		assertEquals(6464, npc.getLocalX());
		assertEquals(Integer.valueOf(9036), Integer.valueOf(npc.getNpcId()));
	}

	@Test
	public void failedAnimationReadIsUnknownAndBreaksEstimatedAttackContinuity()
	{
		Fixture fixture = new Fixture();
		fixture.animation = -1;
		fixture.frame(1);
		fixture.animation = 8419;
		fixture.animationFrame = 0;
		NpcState start = fixture.frame(2).getNpcs().get(0);
		assertEquals("ATTACKING", start.getDerivedAction());
		fixture.fail.add("getAnimation");
		NpcState missing = fixture.frame(3).getNpcs().get(0);
		assertNull(missing.getAnimationId());
		assertNull(missing.getAttackOnsetEvidence());
		assertNull(missing.getAttackCooldownTicksRemaining());
		fixture.fail.clear();
		fixture.animationFrame = 4;
		NpcState recovered = fixture.frame(4).getNpcs().get(0);
		assertNull(recovered.getAttackOnsetEvidence());
		assertEquals(Integer.valueOf(0), (Integer) recovered.getAttackCooldownTicksRemaining());
		fixture.animationFrame = 0;
		assertEquals("OBSERVED_ANIMATION_FRAME_DECREASE", fixture.frame(5).getNpcs().get(0).getAttackOnsetEvidence());
	}

	@Test
	public void failedEffectiveDefinitionCannotBecomeABaseFormOrEraseObservedAnimation()
	{
		Fixture fixture = new Fixture();
		fixture.fail.add("getTransformedComposition");
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertNull(npc.getNpcId());
		assertEquals(Integer.valueOf(9035), npc.getBaseNpcId());
		assertEquals(Integer.valueOf(8419), Integer.valueOf(npc.getAnimationId()));
		assertNull(npc.getObservedPhase());
		assertNull(npc.getAttackOnsetEvidence());
		assertNull(npc.getSize());
	}

	@Test
	public void observationFailureIsNotTheObservedNoAnimationSentinel()
	{
		Fixture fixture = new Fixture();
		fixture.fail.add("getAnimation");
		JsonObject failed = new Gson().toJsonTree(fixture.frame(1).getNpcs().get(0)).getAsJsonObject();
		assertFalse(failed.has("animationId"));
		fixture.fail.clear();
		fixture.animation = -1;
		JsonObject idle = new Gson().toJsonTree(fixture.frame(2).getNpcs().get(0)).getAsJsonObject();
		assertEquals(-1, idle.get("animationId").getAsInt());
	}

	@Test
	public void missingIndexOrViewKeepsAnObjectLifetimeReferenceWithoutInventingAnIndex()
	{
		Fixture fixture = new Fixture();
		fixture.fail.add("getIndex");
		fixture.fail.add("getWorldView");
		NpcState first = fixture.frame(1).getNpcs().get(0), second = fixture.frame(2).getNpcs().get(0);
		assertNull(first.getSceneId());
		assertNull(first.getWorldViewId());
		assertNotNull(first.getActorRef());
		assertEquals(first.getActorRef(), second.getActorRef());
		assertNull(first.getAttackOnsetEvidence());
		ActorIdentity identities = new ActorIdentity();
		NPC other = stub(NPC.class, (name, args) ->
		{
			if (name.equals("getIndex") || name.equals("getWorldView"))
				throw new IllegalStateException("missing");
			return null;
		});
		assertNotEquals(identities.ref(fixture.npc, fixture.local), identities.ref(other, fixture.local));
	}

	@Test
	public void aKnownButUnresolvedTransformDoesNotUseTheBaseFootprintOrPhase()
	{
		Fixture fixture = new Fixture();
		fixture.noEffectiveComposition = true;
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertNull(npc.getNpcId());
		assertEquals(Integer.valueOf(9035), npc.getBaseNpcId());
		assertEquals(Boolean.TRUE, npc.getDefinitionHasTransforms());
		assertArrayEquals(new int[]
		{ 9035, 9036 }, npc.getDefinitionTransformIds());
		assertNull(npc.getSize());
		assertEquals("TRANSFORM_UNAVAILABLE", npc.getDefinitionEvidence());
		assertNull(npc.getObservedPhase());
	}

	@Test
	public void sampledNpcFieldsAreNotRereadIntoAContradictoryState()
	{
		Fixture fixture = new Fixture();
		fixture.frame(1);
		for (String method : Arrays.asList("getCombatLevel", "getAnimation", "getAnimationFrame", "getModelOverrides",
			"getOverheadText", "getOverheadArchiveIds", "getOverheadSpriteIds", "getHealthRatio", "getHealthScale",
			"getInteracting"))
			assertEquals(method, Integer.valueOf(1), fixture.reads.get(method));
	}

	@Test
	public void aMissingBaseAndUnresolvedEffectiveCompositionCannotEstablishAnActiveBaseDefinition()
	{
		Fixture fixture = new Fixture();
		fixture.noEffectiveComposition = true;
		fixture.fail.add("getComposition");
		NpcState observed = fixture.frame(1).getNpcs().get(0);
		assertEquals(Integer.valueOf(9035), observed.getBaseNpcId());
		assertNull(observed.getNpcId());
		assertNull(observed.getSize());
		assertNull(observed.getObservedPhase());
		assertNull(observed.getAttackOnsetEvidence());
	}

	static class Fixture
	{
		final Set<String> fail = new HashSet<>();
		final Map<String, Integer> reads = new HashMap<>();
		int animation = 8419, animationFrame;
		boolean noEffectiveComposition;
		final NPCComposition base = stub(NPCComposition.class,
			(name, args) -> name.equals("getId") ? 9035 : name.equals("getConfigs") ? new int[]
			{ 9035, 9036 } : name.equals("getName") ? "Corrupted Hunllef" : name.equals("getSize") ? 5 : null);
		final NPCComposition effective = stub(NPCComposition.class, (name, args) -> name.equals("getId") ? 9036
			: name.equals("getName") ? "Corrupted Hunllef" : name.equals("getSize") ? 5 : null);
		final Player local = stub(Player.class,
			(name, args) -> name.equals("getWorldLocation") ? new WorldPoint(3200, 3200, 0)
				: name.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
		final NPC npc = stub(NPC.class, (name, args) ->
		{
			reads.merge(name, 1, Integer::sum);
			if (fail.contains(name))
				throw new IllegalStateException("Synthetic unavailable " + name);
			switch (name)
			{
			case "getIndex":
				return 7;
			case "getId":
				return 9035;
			case "getName":
				return "Corrupted Hunllef";
			case "getCombatLevel":
				return 894;
			case "getComposition":
				return base;
			case "getTransformedComposition":
				return noEffectiveComposition ? null : effective;
			case "getWorldLocation":
				return new WorldPoint(3200, 3201, 0);
			case "getLocalLocation":
				return new LocalPoint(6464, 6592);
			case "getAnimation":
				return animation;
			case "getAnimationFrame":
				return animationFrame;
			case "getPoseAnimation":
				return 808;
			case "getPoseAnimationFrame":
				return 2;
			case "getHealthRatio":
				return 10;
			case "getHealthScale":
				return 30;
			case "getOverheadText":
				return "Observed speech";
			case "getOverheadCycle":
				return 20;
			case "getOverheadArchiveIds":
				return new int[]
				{ 440 };
			case "getOverheadSpriteIds":
				return new short[]
				{ 0 };
			case "getInteracting":
				return local;
			case "isInteracting":
				return true;
			default:
				return null;
			}
		});
		final Client client = stub(Client.class,
			(name, args) -> name.equals("getNpcs") ? Collections.singletonList(npc)
				: name.equals("getLocalPlayer") ? local
					: name.equals("getPlayers") ? Collections.singletonList(local)
						: name.equals("getWorldType") ? EnumSet.noneOf(WorldType.class) : null);
		final WorldCaptureService service = new WorldCaptureService();

		Fixture()
		{
			service.setDamageMap(DamageMap.load(new Gson()));
		}

		WorldSnapshot frame(int tick)
		{
			return service.captureTick(client, tick, "npc-room", Collections.emptyList(), null, false, false, false);
		}
	}

	@SuppressWarnings("unchecked")
	static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (method.getName().equals("equals"))
				return proxy == args[0];
			Object value = values.apply(method.getName(), args);
			if (value != null)
				return value;
			Class<?> result = method.getReturnType();
			if (result == int.class)
				return method.getName().equals("getAnimation") || method.getName().equals("getPoseAnimation") ? -1 : 0;
			if (result == boolean.class)
				return false;
			if (result == long.class)
				return 0L;
			if (result == double.class)
				return 0D;
			if (result == float.class)
				return 0F;
			return null;
		});
	}
}
