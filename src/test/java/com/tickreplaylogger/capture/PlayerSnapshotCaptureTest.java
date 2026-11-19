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
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerSnapshotCaptureTest
{
	@Test
	public void localEquipmentKeepsLiteralApiSlotIndexesIncludingLegsRingAndAmmo()
	{
		Fixture f = new Fixture();
		PlayerState player = f.frame(true).getPlayer();
		assertNotNull(player);
		assertEquals(7, EquipmentInventorySlot.LEGS.getSlotIdx());
		assertEquals(12, EquipmentInventorySlot.RING.getSlotIdx());
		assertEquals(13, EquipmentInventorySlot.AMMO.getSlotIdx());
		assertEquals(14, player.getEquipmentItemIds().length);
		for (int slot : new int[]
		{ 0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13 })
			assertEquals("Slot " + slot, 1000 + slot, player.getEquipmentItemIds()[slot]);
	}

	@Test
	public void optionalLocalActorReadsCannotEraseResourcesOrOtherPositionFacts()
	{
		for (String method : Arrays.asList("getWorldLocation", "getLocalLocation", "getWorldView", "getCombatLevel",
			"getOrientation", "getCurrentOrientation", "getAnimation", "getAnimationFrame", "getPoseAnimation",
			"getPoseAnimationFrame", "getOverheadText", "getOverheadCycle", "getOverheadIcon", "getGraphic",
			"getSpotAnims", "getInteracting", "isInteracting"))
		{
			Fixture f = new Fixture();
			f.localFailures.add(method);
			WorldSnapshot frame = f.frame(true);
			PlayerState player = frame.getPlayer();
			assertNotNull(method + " erased the local player", player);
			assertEquals(Integer.valueOf(99), (Integer) player.getHp());
			assertEquals(Integer.valueOf(42), (Integer) player.getPrayer());
			if (!method.equals("getLocalLocation"))
				assertEquals(6464, player.getLocalX());
			assertEquals(method, "partial", frame.getCaptureCoverage().get("player"));
			assertEquals(method + ": " + frame.getCaptureIssues(), 2, frame.getPlayers().size());
		}
	}

	@Test
	public void oneFailedSkillReadRetainsOtherSkillsAndDoesNotBecomeZeroHp()
	{
		Fixture f = new Fixture();
		f.failedBoosted.add(Skill.HITPOINTS);
		f.failedBase.add(Skill.DEFENCE);
		PlayerState player = f.frame(true).getPlayer();
		assertNotNull(player);
		assertNull(player.getHp());
		assertEquals(Integer.valueOf(42), player.getBoostedSkillLevels().get("PRAYER"));
		assertFalse(player.getBoostedSkillLevels().containsKey("HITPOINTS"));
		assertFalse(player.getBaseSkillLevels().containsKey("DEFENCE"));
		assertEquals(6464, player.getLocalX());
		f.failedBoosted.clear();
		f.failedBase.clear();
		f.hp = 0;
		assertEquals(Integer.valueOf(0), (Integer) f.frame(true).getPlayer().getHp());
	}

	@Test
	public void otherPlayerFieldFailuresRetainTheirIndependentSamplesAndTheRestOfTheParty()
	{
		for (String method : Arrays.asList("getName", "getId", "getWorldView", "getWorldLocation", "getLocalLocation",
			"getCombatLevel", "getOrientation", "getCurrentOrientation", "getAnimation", "getAnimationFrame",
			"getPoseAnimation", "getPoseAnimationFrame", "getOverheadText", "getOverheadCycle", "getOverheadIcon",
			"getGraphic", "getSpotAnims", "getInteracting", "isInteracting", "getHealthRatio", "getHealthScale",
			"getTeam"))
		{
			Fixture f = new Fixture();
			f.otherFailures.add(method);
			WorldSnapshot frame = f.frame(true);
			assertEquals(method + " erased a teammate", 2, frame.getPlayers().size());
			assertEquals(method, "partial", frame.getCaptureCoverage().get("otherPlayers"));
			OtherPlayerState row = frame.getPlayers().get(0);
			assertNotNull(row.getActorRef());
			if (!method.equals("getLocalLocation"))
				assertEquals(6720, row.getLocalX());
		}
	}

	@Test
	public void unreadPrayerFlagsKeepKnownPositivesAndCannotBecomeInactiveFlags()
	{
		Fixture f = new Fixture();
		f.failedPrayers.add(Prayer.PROTECT_FROM_MAGIC);
		f.localFailures.add("getOverheadIcon");
		PlayerState player = f.frame(true).getPlayer();
		assertNotNull(player);
		JsonObject raw = new Gson().toJsonTree(player).getAsJsonObject();
		assertTrue(player.getActivePrayers().contains("AUGURY"));
		assertFalse(raw.get("prayerStateKnown").getAsBoolean());
		assertTrue(raw.getAsJsonObject("prayerFlagValues").get("AUGURY").getAsBoolean());
		assertFalse(raw.getAsJsonObject("prayerFlagValues").has("PROTECT_FROM_MAGIC"));
		assertEquals(Boolean.FALSE, player.getOverheadIconKnown());
	}

	@Test
	public void aFailedEquipmentSlotKeepsKnownSlotsWithoutClaimingAnEmptySlot()
	{
		Fixture f = new Fixture();
		f.failedEquipmentSlots.add(9);
		PlayerState player = f.frame(true).getPlayer();
		assertNotNull(player);
		JsonObject raw = new Gson().toJsonTree(player).getAsJsonObject();
		JsonArray known = raw.getAsJsonArray("equipmentSlotKnown");
		assertFalse(known.get(9).getAsBoolean());
		assertTrue(known.get(7).getAsBoolean());
		assertTrue(known.get(12).getAsBoolean());
		assertEquals(1007, player.getEquipmentItemIds()[7]);
		assertEquals(1012, player.getEquipmentItemIds()[12]);
	}

	@Test
	public void localSkillAndContainerObservationsAreNotReadTwiceIntoInconsistentSamples()
	{
		Fixture f = new Fixture();
		f.frame(true);
		for (Skill skill : Skill.values())
			if (skill != Skill.OVERALL)
			{
				assertEquals(skill.name(), Integer.valueOf(1), f.readCounts.get("base:" + skill));
				assertEquals(skill.name(), Integer.valueOf(1), f.readCounts.get("boosted:" + skill));
			}
		assertEquals(Integer.valueOf(1), f.readCounts.get("inventory:0"));
		assertEquals(Integer.valueOf(1), f.readCounts.get("equipment:13"));
	}

	@Test
	public void disablingOtherPlayerCaptureDoesNotReadTheirNamesOrSnapshots()
	{
		Fixture f = new Fixture();
		WorldSnapshot frame = f.frame(false);
		assertTrue(frame.getPlayers().isEmpty());
		assertEquals(0, f.otherReadCount);
	}

	@Test
	public void unavailableNativeViewRetainsActorsWithoutAssigningAnOldOrTopLevelMap()
	{
		Fixture f = new Fixture();
		f.localFailures.add("getWorldView");
		WorldSnapshot frame = f.frame(true);
		assertEquals(Boolean.FALSE, frame.getNativeContextKnown());
		assertNull(frame.getSceneKey());
		assertNull(frame.getWorldViewId());
		assertNotNull(frame.getPlayer());
		assertEquals(2, frame.getPlayers().size());
		assertEquals(6464, frame.getPlayer().getLocalX());
		assertEquals("available", frame.getCaptureCoverage().get("npcs"));
		assertEquals("TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT",
			frame.getCaptureCoverage().get("npcsScope"));
		assertEquals("TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT",
			frame.getCaptureCoverage().get("otherPlayersScope"));
	}

	@Test
	public void incompletePrayerFlagsCannotResolveASingleOverheadOrProveAConflict()
	{
		Fixture f = new Fixture();
		f.activePrayers.add(Prayer.PROTECT_FROM_MAGIC);
		f.failedPrayers.add(Prayer.PROTECT_FROM_MELEE);
		PlayerState player = f.frame(true).getPlayer();
		assertNull(player.getOverheadPrayer());
		assertFalse(player.isPrayerStateConflict());
		assertTrue(player.getReportedActivePrayers().contains("PROTECT_FROM_MAGIC"));
		assertEquals(Boolean.FALSE, player.getPrayerStateKnown());
		assertEquals("PARTIAL_PRAYER_FLAGS", player.getOverheadPrayerEvidence());
	}

	static class Fixture
	{
		final Set<String> localFailures = new HashSet<>(), otherFailures = new HashSet<>();
		final Set<Skill> failedBase = EnumSet.noneOf(Skill.class), failedBoosted = EnumSet.noneOf(Skill.class);
		final Set<Prayer> failedPrayers = EnumSet.noneOf(Prayer.class);
		final Set<Prayer> activePrayers = EnumSet.of(Prayer.AUGURY);
		final Set<Integer> failedEquipmentSlots = new HashSet<>();
		final Map<String, Integer> readCounts = new HashMap<>();
		int hp = 99, otherReadCount;
		final Player local = actor(1, "Local synthetic", localFailures, true),
			other = actor(2, "Teammate synthetic", otherFailures, false),
			second = actor(3, "Second synthetic", Collections.emptySet(), false);
		final ItemContainer equipment = stub(ItemContainer.class, (name, args) ->
		{
			if (name.equals("getItem"))
			{
				int slot = (Integer) args[0];
				readCounts.merge("equipment:" + slot, 1, Integer::sum);
				if (failedEquipmentSlots.contains(slot))
					throw new IllegalStateException("slot");
				return new Item(1000 + slot, 1);
			}
			return null;
		});
		final ItemContainer inventory = stub(ItemContainer.class, (name, args) ->
		{
			if (name.equals("getItem"))
			{
				int slot = (Integer) args[0];
				readCounts.merge("inventory:" + slot, 1, Integer::sum);
				return slot == 0 ? new Item(385, 3) : null;
			}
			return null;
		});
		final Client client = stub(Client.class, (name, args) ->
		{
			switch (name)
			{
			case "getLocalPlayer":
				return local;
			case "getPlayers":
				return Arrays.asList(local, other, second);
			case "getNpcs":
				return Collections.emptyList();
			case "getRealSkillLevel":
			{
				Skill skill = (Skill) args[0];
				readCounts.merge("base:" + skill, 1, Integer::sum);
				if (failedBase.contains(skill))
					throw new IllegalStateException("base");
				return 99;
			}
			case "getBoostedSkillLevel":
			{
				Skill skill = (Skill) args[0];
				readCounts.merge("boosted:" + skill, 1, Integer::sum);
				if (failedBoosted.contains(skill))
					throw new IllegalStateException("boosted");
				return skill == Skill.HITPOINTS ? hp : skill == Skill.PRAYER ? 42 : 99;
			}
			case "isPrayerActive":
				if (failedPrayers.contains((Prayer) args[0]))
					throw new IllegalStateException("prayer");
				return activePrayers.contains((Prayer) args[0]);
			case "getItemContainer":
				return args[0] == InventoryID.EQUIPMENT ? equipment
					: args[0] == InventoryID.INVENTORY ? inventory : null;
			case "getWorldType":
				return EnumSet.noneOf(WorldType.class);
			default:
				return null;
			}
		});
		final WorldCaptureService service = new WorldCaptureService();

		WorldSnapshot frame(boolean includeOthers)
		{
			return service.captureTick(client, 1, "player-room", Collections.emptyList(), null, includeOthers, false,
				false);
		}

		Player actor(int id, String label, Set<String> failures, boolean own)
		{
			return stub(Player.class, (name, args) ->
			{
				if (!own)
					otherReadCount++;
				if (failures.contains(name))
					throw new IllegalStateException("synthetic " + name);
				switch (name)
				{
				case "getId":
					return id;
				case "getName":
					return label;
				case "getCombatLevel":
					return 126;
				case "getWorldLocation":
					return new WorldPoint(3200 + id, 3200, 0);
				case "getLocalLocation":
					return new LocalPoint(own ? 6464 : 6720, 6464);
				case "getAnimation":
					return 1234;
				case "getAnimationFrame":
					return 2;
				case "getPoseAnimation":
					return 808;
				case "getPoseAnimationFrame":
					return 1;
				case "getOverheadText":
					return "Synthetic text";
				case "getOverheadCycle":
					return 20;
				case "getHealthRatio":
					return 10;
				case "getHealthScale":
					return 30;
				default:
					return null;
				}
			});
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
				return method.getName().equals("getGraphic") ? -1 : 0;
			if (result == boolean.class)
				return false;
			if (result == long.class)
				return 0L;
			if (result == float.class)
				return 0F;
			if (result == double.class)
				return 0D;
			return null;
		});
	}
}
