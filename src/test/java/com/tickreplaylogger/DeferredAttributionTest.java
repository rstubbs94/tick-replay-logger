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
import com.tickreplaylogger.capture.DamageMap;
import com.tickreplaylogger.capture.WorldCaptureService;
import com.tickreplaylogger.capture.InspectableWorldSnapshotRecorder;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.util.RecordingStorage;
import com.tickreplaylogger.util.TickRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Actor;
import net.runelite.api.Deque;
import net.runelite.api.GameState;
import net.runelite.api.HeadIcon;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.StatChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exercises the real event handlers with controllable RuneLite interface implementations. */
public class DeferredAttributionTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();
	private final Gson gson = new Gson();
	private TickReplayLoggerPlugin plugin;
	private TickRecorder journal;
	private InspectableWorldSnapshotRecorder world;
	private RecordingStorage storage;
	private Player player;
	private NPC boss;
	private final List<NPC> npcs = new ArrayList<>();
	private final List<GraphicsObject> graphicsObjects = new ArrayList<>();
	private int npcId = -1;
	private int npcLevel = 100;
	private Integer npcObservedSize;
	private boolean npcUnresolvedTransform;
	private int[] npcTransformIds;
	private Integer npcObservedEffectiveId;
	private boolean npcNameNull;
	private String npcObservedName;
	private String npcObservedSpeech;
	private boolean npcModelOverride;
	private boolean npcReportsInteraction;
	private int animation = -1;
	private int npcAnimationFrame;
	private LocalPoint npcLocalPosition;
	private int npcPoseAnimation;
	private int[] npcOverheadArchives;
	private short[] npcOverheadSprites;
	private int playerAnimation = -1;
	private int hp = 99, prayerPoints = 99, runEnergyValue;
	private boolean failRunEnergy, failSpecialEnergy;
	private Prayer prayer;
	private final java.util.Set<Prayer> extraPrayers = new java.util.HashSet<>();
	private HeadIcon headIcon;
	private boolean changingPlayerHeadIcon, failRedundantLocalPlayerRead;
	private int playerHeadIconReads, localPlayerReadCount, callbackLocalViewReads;
	private boolean changingCallbackLocalView;
	private int gameCycle = 500;
	private ItemContainer inventory;
	private ItemContainer equipment;
	private java.util.Map<Integer, Object> clientVariables;
	private int[] serverVarps;
	private int[] clientVarps;
	private boolean failClientVarps;
	private boolean rejectRepeatedClientArrayReads;
	private int clientVarpArrayReads, clientLongVarpArrayReads;
	private final Map<Integer, net.runelite.api.VarbitComposition> varbitCompositions = new java.util.HashMap<>();
	private final Map<Integer, Integer> varbitObservedValues = new java.util.HashMap<>();
	private long[] longVarps;
	private long[] serverLongVarps;
	private int poisonVarp;
	private int specialVarp = 1000;
	private int pvpArea;
	private java.util.EnumSet<net.runelite.api.WorldType> worldTypes = java.util.EnumSet
		.noneOf(net.runelite.api.WorldType.class);
	private boolean trackPrayers;
	private boolean failPlayerDisplayMetadata, failNpcDefinitionMetadata;
	private boolean failPlayerWorldPosition, failNpcWorldPosition, failNpcAnimation, failPrayerFlags;
	private final java.util.Set<String> failedNpcSnapshotReads = new java.util.HashSet<>();
	private final java.util.Set<String> failedPlayerSnapshotReads = new java.util.HashSet<>();
	private final java.util.Set<Prayer> failedPrayerReads = java.util.EnumSet.noneOf(Prayer.class);
	private final java.util.Set<Skill> failedBoostedSkills = java.util.EnumSet.noneOf(Skill.class),
		failedBaseSkills = java.util.EnumSet.noneOf(Skill.class);
	private boolean failPlayerName, failWorld, failPoisonVarp, enableEffects, failLocalPlayer, missingLocalPlayer;
	private boolean failClientIntegerValue;
	private int playerGraphic = -1, teleblockValue;
	private int playerGraphicStartCycle, playerGraphicFrame;
	private int playerPrimaryGraphicReads, playerGraphicCollectionReads;
	private boolean changingPlayerPrimaryGraphic;
	private boolean failPlayerGraphic, failGraphicsCollection, failTeleblock;
	private final List<Player> otherPlayers = new ArrayList<>();
	private WorldPoint position = new WorldPoint(3200, 3200, 0);
	private LocalPoint localPosition = new LocalPoint(6400, 6400, net.runelite.api.WorldView.TOPLEVEL);
	private GameState gameState = GameState.LOGGED_IN;
	private boolean failGameState, failApiTickCount, failGameCycle;
	private int apiTickCount, localPlayerFailuresRemaining, gameStateReads, gameCycleReads;
	private net.runelite.api.Scene hazardScene;
	private net.runelite.api.WorldView cueView;
	private net.runelite.api.WorldView projectileNativeView;
	private java.util.List<Projectile> listedProjectiles;
	private net.runelite.api.ObjectComposition floorDefinition;

	private List<TickRecording> storedDeaths()
	{
		try
		{
			storage.flush().get(20, java.util.concurrent.TimeUnit.SECONDS);
			return storage.getDeaths();
		}
		catch (Exception error)
		{
			throw new AssertionError("Asynchronous death storage failed", error);
		}
	}

	@org.junit.After
	public void closeStorage() throws Exception
	{
		if (storage != null)
		{
			storage.flush().get(20, java.util.concurrent.TimeUnit.SECONDS);
			storage.close();
		}
	}

	@Before
	public void setup() throws Exception
	{
		player = stub(Player.class, (name, args) ->
		{
			if (failedPlayerSnapshotReads.contains(name))
				throw new IllegalStateException("synthetic unavailable player " + name);
			switch (name)
			{
			case "getName":
				if (failPlayerName)
					throw new IllegalStateException("name unavailable");
				return "Test player";
			case "getWorldLocation":
				if (failPlayerWorldPosition)
					throw new IllegalStateException("position unavailable");
				return position;
			case "getLocalLocation":
				return localPosition;
			case "getWorldView":
				if (changingCallbackLocalView && ++callbackLocalViewReads > 1)
					throw new IllegalStateException("later local view unavailable");
				return projectileNativeView == null ? cueView : projectileNativeView;
			case "getAnimation":
				return playerAnimation;
			case "getGraphic":
				playerPrimaryGraphicReads++;
				if (failPlayerGraphic)
					throw new IllegalStateException("primary graphic unavailable");
				return changingPlayerPrimaryGraphic ? (playerPrimaryGraphicReads == 1 ? 3281 : 3280) : playerGraphic;
			case "getSpotAnims":
				playerGraphicCollectionReads++;
				if (failGraphicsCollection)
					throw new IllegalStateException("graphics collection unavailable");
				List<net.runelite.api.ActorSpotAnim> active = new ArrayList<>();
				if (playerGraphic >= 0)
					active.add(stub(net.runelite.api.ActorSpotAnim.class, (n, a) ->
					{
						if (n.equals("getId"))
							return playerGraphic;
						if (n.equals("getStartCycle"))
							return playerGraphicStartCycle;
						if (n.equals("getFrame"))
							return playerGraphicFrame;
						return null;
					}));
				return stub(net.runelite.api.IterableHashTable.class,
					(n, a) -> n.equals("iterator") ? active.iterator() : null);
			case "getOverheadIcon":
				playerHeadIconReads++;
				return changingPlayerHeadIcon ? (playerHeadIconReads == 1 ? HeadIcon.RANGED : HeadIcon.MAGIC)
					: headIcon;
			case "getCurrentOrientation":
				if (failPlayerDisplayMetadata)
					throw new IllegalStateException("display metadata unavailable");
				return 0;
			default:
				return null;
			}
		});
		boss = npc("Test boss", 1);
		npcs.add(boss);
		Deque<GraphicsObject> graphics = stub(Deque.class,
			(name, args) -> "iterator".equals(name) ? graphicsObjects.iterator() : null);
		Deque<Projectile> visibleProjectiles = stub(Deque.class,
			(name, args) -> "iterator".equals(name) ? listedProjectiles.iterator() : null);
		Client client = stub(Client.class, (name, args) ->
		{
			switch (name)
			{
			case "getGameState":
				gameStateReads++;
				if (failGameState)
					throw new IllegalStateException("game state unavailable");
				return gameState;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			case "getTopLevelWorldView":
				return projectileNativeView == null ? cueView : projectileNativeView;
			case "getProjectiles":
				return listedProjectiles == null ? null : visibleProjectiles;
			case "getScene":
				return hazardScene;
			case "getObjectDefinition":
				return floorDefinition;
			case "getWorldView":
				return stub(net.runelite.api.WorldView.class,
					(n, a) -> n.equals("getBaseX") || n.equals("getBaseY") ? 3150 : null);
			case "getLocalPlayer":
				localPlayerReadCount++;
				if (failRedundantLocalPlayerRead && localPlayerReadCount > 1)
					throw new IllegalStateException("redundant local identity read unavailable");
				if (localPlayerFailuresRemaining > 0)
				{
					localPlayerFailuresRemaining--;
					throw new IllegalStateException("transient local identity unavailable");
				}
				if (failLocalPlayer)
					throw new IllegalStateException("local player unavailable");
				return missingLocalPlayer ? null : player;
			case "getNpcs":
				return npcs;
			case "getPlayers":
				List<Player> observedPlayers = new ArrayList<>(otherPlayers);
				observedPlayers.add(player);
				return observedPlayers;
			case "getVarbitValue":
				if (((Integer) args[0]) == 4163)
				{
					if (failTeleblock)
						throw new IllegalStateException("teleblock unavailable");
					return teleblockValue;
				}
				return ((Integer) args[0]) == net.runelite.api.Varbits.PVP_SPEC_ORB ? pvpArea
					: varbitObservedValues.getOrDefault((Integer) args[0], 0);
			case "getVarbit":
				return varbitCompositions.get((Integer) args[0]);
			case "getGraphicsObjects":
				return graphics;
			case "getWorld":
				if (failWorld)
					throw new IllegalStateException("world unavailable");
				return 301;
			case "getWorldType":
				return worldTypes;
			case "getGameCycle":
				gameCycleReads++;
				if (failGameCycle)
					throw new IllegalStateException("game cycle unavailable");
				return gameCycle;
			case "getTickCount":
				if (failApiTickCount)
					throw new IllegalStateException("API tick count unavailable");
				return apiTickCount;
			case "getVarcMap":
				return clientVariables;
			case "getServerVarps":
				return serverVarps;
			case "getVarps":
				clientVarpArrayReads++;
				if (failClientVarps || rejectRepeatedClientArrayReads && clientVarpArrayReads > 1)
					throw new IllegalStateException("synthetic client varps unavailable");
				return clientVarps;
			case "getVarpsLong":
				clientLongVarpArrayReads++;
				if (rejectRepeatedClientArrayReads && clientLongVarpArrayReads > 1)
					throw new IllegalStateException("synthetic client long varps unavailable");
				return longVarps;
			case "getServerVarpsLong":
				return serverLongVarps;
			case "getEnergy":
				if (failRunEnergy)
					throw new IllegalStateException("run unavailable");
				return runEnergyValue;
			case "getVarpValue":
				if (failSpecialEnergy
					&& java.util.Objects.equals(args[0], net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT))
					throw new IllegalStateException("special unavailable");
				if (failPoisonVarp && java.util.Objects.equals(args[0], net.runelite.api.VarPlayer.POISON))
					throw new IllegalStateException("poison unavailable");
				return java.util.Objects.equals(args[0], net.runelite.api.VarPlayer.POISON) ? poisonVarp
					: java.util.Objects.equals(args[0], net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) ? specialVarp
						: 0;
			case "getItemContainer":
				return args[0] == net.runelite.api.InventoryID.INVENTORY ? inventory
					: args[0] == net.runelite.api.InventoryID.EQUIPMENT ? equipment : null;
			case "getItemDefinition":
				return stub(net.runelite.api.ItemComposition.class,
					(n, a) -> n.equals("getName") ? "Test equipped item" : null);
			case "getMapRegions":
				return new int[0];
			case "getRealSkillLevel":
				if (failedBaseSkills.contains(args[0]))
					throw new IllegalStateException("base skill unavailable");
				return 99;
			case "getBoostedSkillLevel":
				if (failedBoostedSkills.contains(args[0]))
					throw new IllegalStateException("boosted skill unavailable");
				return args[0] == Skill.HITPOINTS ? hp : args[0] == Skill.PRAYER ? prayerPoints : 99;
			case "isPrayerActive":
				if (failPrayerFlags || failedPrayerReads.contains(args[0]))
					throw new IllegalStateException("prayer flags unavailable");
				return args[0] == prayer || extraPrayers.contains(args[0]);
			case "getVarcIntValue":
				if (failClientIntegerValue)
					throw new IllegalStateException("client integer unavailable");
				return 0;
			default:
				return null;
			}
		});
		TickReplayLoggerConfig config = new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}

			@Override
			public boolean captureEffects()
			{
				return enableEffects;
			}

			@Override
			public boolean trackPrayers()
			{
				return trackPrayers;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		};
		world = new InspectableWorldSnapshotRecorder(100, new com.google.gson.Gson());
		journal = world;
		storage = new RecordingStorage(3, gson,
			net.runelite.client.util.Filepath.Unchecked.getRooted(temporary.newFolder().toPath()));
		DamageMap map = DamageMap.load(gson);
		plugin = new TickReplayLoggerPlugin();
		WorldCaptureService capture = new WorldCaptureService((com.tickreplaylogger.capture.ObjectHazards.ObservationIdentities)
			remainingField(plugin, "surfaceObservationIds"));
		capture.setDamageMap(map);
		inject("client", client);
		inject("clientThread", new net.runelite.client.callback.ClientThread()
		{
			@Override
			public void invokeLater(Runnable task)
			{
				task.run();
			}
		});
		inject("config", config);
		inject("gson", gson);
		inject("worldRecorder", world);
		inject("recordingStorage", storage);
		inject("captureService", capture);
		inject("damageMap", map);
		// Positive current-API fixtures observe a native view; explicit null/failure tests override it.
		useProjectileNativeView();
		cueView = projectileNativeView;
		projectileNativeView = null;
	}

	@Test
	public void continuousCaptureKeepsRawFactsAndRunsAttributionOnlyForAnExplicitCapture() throws Exception
	{
		java.util.concurrent.atomic.AtomicInteger resolverNpcReads = new java.util.concurrent.atomic.AtomicInteger();
		Gson captureGson = new com.google.gson.GsonBuilder().registerTypeAdapter(
			com.tickreplaylogger.model.world.NpcState.class,
			(com.google.gson.JsonSerializer<com.tickreplaylogger.model.world.NpcState>) (value, type, context) ->
			{
				resolverNpcReads.incrementAndGet();
				return new Gson().toJsonTree(value);
			}).create();
		world = new InspectableWorldSnapshotRecorder(100, captureGson);
		journal = world;
		inject("worldRecorder", world);
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals("Collecting ticks must not serialize NPC evidence for the resolver", 0, resolverNpcReads.get());
		DamageRecord original = world.lastDamage();
		assertEquals(7, original.getAmount());
		assertNull("Live capture retains the original unresolved hit", original.getSourceResolution());
		String before = gson.toJson(original);
		DeathRecordingV2 completed = export();
		assertTrue("An explicit capture runs the real evidence reader", resolverNpcReads.get() > 0);
		assertNotNull(completed.getEvents().getDamageEvents().get(0).getSourceResolution());
		assertEquals("Completed interpretation cannot mutate live raw facts", before, gson.toJson(world.lastDamage()));
		assertNull(world.lastDamage().getSourceResolution());
	}

	@Test
	public void expiredProjectileDropsLiveHandlesWithoutErasingOriginalHistory() throws Exception
	{
		useProjectileNativeView();
		int[] remaining = {60};
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> remaining[0]);
		listedProjectiles = new ArrayList<>(Collections.singletonList(shot));
		moveSourceShot(shot);
		tick();
		assertTrue(((Map<?, ?>) remainingField(plugin, "projectileLaunchObservations")).containsKey(shot));
		remaining[0] = 0;
		tick();
		for (String field : java.util.Arrays.asList("projectileIds", "projectileSpawnTicks", "projectileTargets",
			"projectileCallbackStates", "projectileLaunchObservations"))
			assertFalse(field, ((Map<?, ?>) remainingField(plugin, field)).containsKey(shot));
		Map<?, ?> retained = (Map<?, ?>) remainingField(plugin, "retainedProjectiles");
		assertEquals("Removal retains the raw sample for its original grace window", 1, retained.size());
		com.tickreplaylogger.model.world.ObservedEvent original = export().getEvents().getObservations().stream()
			.filter(event -> "PROJECTILE_FIRST_SEEN".equals(event.getType())).findFirst().get();
		com.tickreplaylogger.model.world.ProjectileState preserved = (com.tickreplaylogger.model.world.ProjectileState) original.getData().get("projectile");
		assertNotNull(preserved.getSourceHistory().getFirstObservedSource());
		assertEquals(Integer.valueOf(1), preserved.getSourceHistory().getFirstObservedSource().getObservedTick());
		assertEquals(preserved.getObservationId(), ((com.tickreplaylogger.model.world.ProjectileState)
			remainingField(retained.values().iterator().next(), "latestObservation")).getObservationId());
		for (int i = 0; i < 8; i++)
		{
			tick();
		}
		assertTrue("Removed samples leave the cache after their original retention window", retained.isEmpty());
	}

	@Test
	public void minimalCaptureKeepsOriginalSourceWitnessesForEveryHitWithoutRichFrames() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		npcId = 987654;
		npcReportsInteraction = true;
		useProjectileNativeView();
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();
		DeathRecordingV2 result = export();
		assertTrue(result.getTicks().isEmpty());
		assertEquals(2, result.getEvents().getDamageEvents().size());
		assertEquals(2, journal.getTickHistory().get(0).getDamageEvents().size());
		for (DamageRecord damage : result.getEvents().getDamageEvents())
		{
			assertNotNull(damage.getSourceResolution());
			assertTrue(damage.getSourceResolution().getCandidates().stream()
				.anyMatch(source -> "Test boss".equals(source.getName())));
			assertTrue(damage.getAttributionCandidates().stream()
				.anyMatch(witness -> "NPC_ACTIVITY_OBSERVATION".equals(witness.get("kind"))));
		}
		assertEquals(16, result.getEvents().getDamageEvents().stream().mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void aLaterSettledAnimationCannotProveTheEarlierHitsAttack()
	{
		npcReportsInteraction = true;
		tick();
		TickData previous = journal.getTickHistory().get(0);
		hit(HitsplatID.DAMAGE_ME, 12);
		assertTrue(previous.getDamageEvents().isEmpty());
		assertNull(capturedLastDamage());
		animation = 123;
		hp = 87;
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals("UNKNOWN", damage.getAttribution());
		assertTrue(damage.getSourceResolution().isUnidentifiedPossible());
		assertTrue("Later pose is still available as a recorded observation, not original attack proof",
			world.snapshotAtTick(2).getGameCycle() > damage.getObservedCycle());
		assertTrue(world.snapshotAtTick(2).getNpcs().stream().anyMatch(npc -> Integer.valueOf(123).equals(npc.getAnimationId())));
		assertEquals(2, damage.getImpactTick());
		assertEquals(Boolean.TRUE, damage.getImpactPositionKnown());
		assertEquals(Boolean.FALSE, damage.getLaunchPositionKnown());
		assertEquals(12, journal.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
		assertTrue(previous.getDamageEvents().isEmpty());
		tick();
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
		assertEquals(1, export().getEvents().getDamageEvents().size());
	}

	@Test
	public void anOriginalAnimationCallbackBeforeHpKeepsItsTimingSupport()
	{
		useProjectileNativeView(); npcReportsInteraction = true;
		npcId = 990001; npcLevel = 101; npcObservedSize = 1; npcObservedName = "Uncatalogued NPC";
		npcLocalPosition = new LocalPoint(6464, 6336); animation = -1; tick();
		animation = 990002;
		net.runelite.api.events.AnimationChanged changed = new net.runelite.api.events.AnimationChanged();
		changed.setActor(boss); plugin.onAnimationChanged(changed);
		hit(HitsplatID.DAMAGE_ME, 7); tick();
		DamageRecord observed = capturedLastDamage();
		assertEquals("Uncatalogued NPC", observed.getAttackerName());
		assertEquals("TIMED", observed.getAttribution());
		assertRecordedAnimationReceipt(observed, 990002);
	}

	@Test
	public void aSameTickAnimationCallbackAfterHpCannotSupplyItsAttackTiming()
	{
		useProjectileNativeView(); npcReportsInteraction = true;
		npcId = 990001; npcLevel = 101; npcObservedSize = 1; npcObservedName = "Uncatalogued NPC";
		npcLocalPosition = new LocalPoint(6464, 6336); animation = -1; tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		animation = 990002;
		net.runelite.api.events.AnimationChanged changed = new net.runelite.api.events.AnimationChanged();
		changed.setActor(boss); plugin.onAnimationChanged(changed); tick();
		DamageRecord observed = capturedLastDamage();
		assertEquals("UNKNOWN", observed.getAttribution());
		assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
		assertTrue(export().getEvents().getObservations().stream().anyMatch(event -> "ANIMATION_CHANGED".equals(event.getType())
			&& event.getTick() == observed.getImpactTick() && event.getSequence() > observed.getObservationSequence()
			&& event.getData().get("animationId") instanceof Number
			&& ((Number) event.getData().get("animationId")).intValue() == 990002));
	}

	@Test
	public void genericCaptureKeepsTheWorldTickWithoutCollisionOrObjectDefinitions() throws Exception
	{
		Field clientField = TickReplayLoggerPlugin.class.getDeclaredField("client");
		clientField.setAccessible(true);
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(gson));
		assertNotNull(
			service.captureTick((Client) clientField.get(plugin), 1, "scene", Collections.emptyList(), null, true));
	}

	@Test
	public void failedSettledResourcesRetainMixedHitsAndRecoverWithoutRetryingAmounts() throws Exception
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.POISON, 4);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		failedBoostedSkills.addAll(java.util.Arrays.asList(Skill.HITPOINTS, Skill.PRAYER));
		failedBaseSkills.addAll(failedBoostedSkills);
		tick();
		TickData partial = journal.getTickHistory().get(1);
		for (String field : java.util.Arrays.asList("currentHp", "maxHp", "currentPrayer", "maxPrayer"))
			assertFalse(partial.isStateKnown(field));
		assertEquals(4, partial.getCaptureIssues().size());
		assertEquals(2, partial.getDamageEvents().size());
		assertEquals(3, export().getEvents().getDamageEvents().size());
		com.tickreplaylogger.model.world.PlayerState playerSample = export().getTicks().get(1).getPlayer();
		assertNotNull(playerSample);
		assertNull(playerSample.getHp());
		assertNull(playerSample.getMaxHp());
		assertNull(playerSample.getPrayer());
		assertNull(playerSample.getMaxPrayer());
		assertEquals(4, playerSample.getObservationIssues().size());
		assertEquals(6400, playerSample.getLocalX());
		failedBoostedSkills.clear();
		failedBaseSkills.clear();
		hp = 88;
		tick();
		assertEquals(3, journal.getTickHistory().size());
		assertEquals(3, export().getTicks().size());
		assertTrue(journal.getTickHistory().get(2).isStateKnown("currentHp"));
		assertEquals(88, journal.getTickHistory().get(2).getCurrentHp());
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
		assertEquals(3, export().getEvents().getDamageEvents().size());
		com.google.gson.JsonObject fixture = gson
			.toJsonTree(export().toBuilder().journalTicks(journal.getTickHistory()).build()).getAsJsonObject();
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path output = java.nio.file.Paths.get("build/test-fixtures/settled-state-failures.json");
		java.nio.file.Files.createDirectories(output.getParent());
		java.nio.file.Files.write(output, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void failedHpReadDoesNotHideIndependentlyObservedPrayerOrMaximum()
	{
		hit(HitsplatID.DAMAGE_ME, 7);
		failedBoostedSkills.add(Skill.HITPOINTS);
		tick();
		TickData partial = journal.getTickHistory().get(0);
		assertFalse(partial.isStateKnown("currentHp"));
		assertTrue(partial.isStateKnown("maxHp"));
		assertTrue(partial.isStateKnown("currentPrayer"));
		assertEquals(99, partial.getCurrentPrayer());
		assertEquals("?/99", com.tickreplaylogger.util.DisplayFormat.resourceText(partial, true));
		assertEquals("99/99", com.tickreplaylogger.util.DisplayFormat.resourceText(partial, false));
		assertEquals(7, capturedLastDamage().getAmount());
	}

	@Test
	public void failedLocalPlayerReadKeepsTheTickHitsAndRecoveryWithoutReplayingDamage()
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.POISON, 4);
		failLocalPlayer = true;
		tick();
		assertEquals(2, journal.getTickHistory().size());
		TickData partial = journal.getTickHistory().get(1);
		assertFalse(partial.isStateKnown("localPlayer"));
		assertEquals(2, partial.getDamageEvents().size());
		assertEquals(2, capturedLastDamage().getImpactTick());
		assertEquals(2, export().getEvents().getDamageEvents().size());
		assertEquals("Unknown", export().getEvents().getDamageEvents().get(0).getAttackerName());
		failLocalPlayer = false;
		position = new WorldPoint(3205, 3200, 0);
		tick();
		assertEquals(3, journal.getTickHistory().size());
		assertNull(journal.getTickHistory().get(2).getMovement());
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
		assertEquals(2, export().getEvents().getDamageEvents().size());
	}

	@Test
	public void deathAndImmediateRevivalDoNotWaitForBlockedExportStorage() throws Exception
	{
		java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.ExecutorService clientCallbacks = java.util.concurrent.Executors.newSingleThreadExecutor();
		storage.ready().get(5, java.util.concurrent.TimeUnit.SECONDS);
		storage.addSavedRecording(() ->
		{
			entered.countDown();
			try
			{
				if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
					throw new AssertionError("Worker not released");
			}
			catch (InterruptedException failure)
			{
				throw new AssertionError(failure);
			}
			return TickRecording.builder().timestamp(1).killerName("Blocked worker fixture")
				.ticks(java.util.Collections.emptyList()).build();
		});
		try
		{
			assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
			tick();
			hit(HitsplatID.DAMAGE_ME, 32);
			hp = 0;
			plugin.onActorDeath(new ActorDeath(player));
			clientCallbacks.submit(() -> tick()).get(2, java.util.concurrent.TimeUnit.SECONDS);
			hp = 99;
			clientCallbacks.submit(() -> tick()).get(2, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals(99, journal.getTickHistory().get(journal.getTickHistory().size() - 1).getCurrentHp());
			assertTrue("Storage is still blocked, but game callbacks have completed", storage.getDeaths().isEmpty());
		}
		finally
		{
			release.countDown();
			clientCallbacks.shutdownNow();
		}
		TickRecording recorded = storedDeaths().get(0);
		DeathRecordingV2 rich = gson.fromJson(preparedJson(recorded), DeathRecordingV2.class);
		assertEquals(Integer.valueOf(2), rich.getMeta().getDeathTick());
		assertEquals(2, recorded.getTicks().get(recorded.getTicks().size() - 1).getTickNumber());
		assertEquals(0, recorded.getTicks().get(recorded.getTicks().size() - 1).getCurrentHp());
		assertEquals(3, journal.getTickHistory().get(journal.getTickHistory().size() - 1).getTickNumber());
	}

	@Test
	public void nullLocalPlayerKeepsADeathAndOtherOwnedNpcDamageOnItsOriginalTick() throws Exception
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		plugin.onActorDeath(new ActorDeath(player));
		missingLocalPlayer = true;
		tick();
		assertEquals(1, storedDeaths().size());
		TickRecording death = storedDeaths().get(0);
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals(Integer.valueOf(2), rich.getMeta().getDeathTick());
		assertEquals(2, rich.getJournalTicks().size());
		assertEquals(2, rich.getEvents().getDamageEvents().size());
		assertEquals(1, rich.getEvents().getDeaths().size());
		assertTrue(rich.getEvents().getDamageEvents().stream().allMatch(hit -> hit.getImpactTick() == 2));
		assertTrue(rich.getEvents().getDamageEvents().stream()
			.anyMatch(hit -> "OTHER".equals(hit.getOwnership()) && hit.getAmount() == 9));
	}

	@Test
	public void richExportRetainsJournalGapsRecoveryAndFinalDeathFixture() throws Exception
	{
		hp = 11;
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		hp = 4;
		failLocalPlayer = true;
		tick();
		failLocalPlayer = false;
		position = new WorldPoint(3205, 3200, 0);
		tick();
		hit(HitsplatID.POISON, 4);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		missingLocalPlayer = true;
		tick();
		assertEquals(1, storedDeaths().size());
		com.google.gson.JsonObject fixture = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-journal-world-gaps");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		assertEquals(2, fixture.getAsJsonArray("ticks").size());
		assertEquals(4, fixture.getAsJsonArray("journalTicks").size());
		assertEquals(3, fixture.getAsJsonObject("events").getAsJsonArray("damageEvents").size());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/journal-world-gaps.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void allMissingSnapshotsStillExportIndependentObservedEvents() throws Exception
	{
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		hit(HitsplatID.POISON, 4);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		missingLocalPlayer = true;
		tick();
		DeathRecordingV2 live = gson.fromJson(captureLiveJson(journal.getTickHistory()),
			DeathRecordingV2.class);
		assertTrue(live.getTicks().isEmpty());
		assertEquals(1, live.getJournalTicks().size());
		assertEquals(2, live.getEvents().getDamageEvents().size());
		DeathRecordingV2 death = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertTrue(death.getTicks().isEmpty());
		assertEquals(Integer.valueOf(1), death.getMeta().getDeathTick());
		assertEquals(2, death.getEvents().getDamageEvents().size());
	}

	@Test
	public void wholeWorldSnapshotFailureIsReportedWithoutDroppingTheSettledJournal() throws Exception
	{
		boolean[] throwRead =
		{ true };
		WorldCaptureService broken = new WorldCaptureService()
		{
			@Override
			public com.tickreplaylogger.model.world.WorldSnapshot captureTick(Client client, int tick, String key,
				List<com.tickreplaylogger.model.world.ProjectileState> projectiles, WorldPoint previous, boolean other,
				boolean collision, boolean terrain)
			{
				if (throwRead[0])
					throw new IllegalStateException("synthetic whole-world failure");
				return null;
			}
		};
		inject("captureService", broken);
		for (int i = 0; i < 2; i++)
		{
			hit(HitsplatID.POISON, 4);
			tick();
			TickData row = journal.getTickHistory().get(i);
			assertFalse(row.isStateKnown("worldSnapshot"));
			assertTrue(row.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("worldSnapshot:")));
			assertEquals(1, row.getDamageEvents().size());
			throwRead[0] = false;
		}
		DeathRecordingV2 rich = gson.fromJson(captureLiveJson(journal.getTickHistory()),
			DeathRecordingV2.class);
		assertTrue(rich.getTicks().isEmpty());
		assertEquals(2, rich.getJournalTicks().size());
		assertEquals(2, rich.getEvents().getDamageEvents().size());
	}

	@Test
	public void graphicReferencesAndTeleblockStateDoNotInventAppliedEffectsOrDamageFixture() throws Exception
	{
		enableEffects = true;
		int[] graphics =
		{ -1, 181, 181, 377, 391, 1049, 369, 369, 369, 1300, -1 };
		int[] teleblock =
		{ 0, 101, 100, 500, 2, 0, -1, 101, 101, 101, 0 };
		for (int i = 0; i < graphics.length; i++)
		{
			playerGraphic = graphics[i];
			teleblockValue = teleblock[i];
			failPlayerGraphic = i == 7;
			failGraphicsCollection = i == 7;
			failTeleblock = i == 9;
			tick();
		}
		DeathRecordingV2 raw = export();
		assertEquals(3, raw.getEvents().getEffectEvents().size());
		int[] eventTicks =
		{ 2, 7, 9 }, ids =
		{ 181, 369, 369 };
		for (int i = 0; i < 3; i++)
		{
			com.tickreplaylogger.model.world.EffectEvent effect = raw.getEvents().getEffectEvents().get(i);
			assertEquals(eventTicks[i], effect.getStartTick());
			assertEquals(Integer.valueOf(ids[i]), effect.getObservedGraphicId());
			assertEquals("player", effect.getTargetRef());
			assertEquals("graphic_reference", effect.getType());
			assertEquals("UNKNOWN", effect.getAttribution());
			assertNull(effect.getDurationTicks());
			assertNull(effect.getSourceRef());
			assertNull(effect.getSourceName());
		}
		assertTrue(raw.getEvents().getDamageEvents().isEmpty());
		assertEquals("unknown", raw.getTicks().get(6).getLocalStatuses().get(5).getState());
		assertEquals("teleblock", raw.getTicks().get(7).getLocalStatuses().get(5).getState());
		assertFalse(raw.getTicks().get(9).getLocalStatuses().get(5).isValueObserved());
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId("synthetic-effect-reference")
			.snapshotTick(11).referenceCatalogId(DamageMap.load(gson).catalog().id())
			.captureBuildId(com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(com.tickreplaylogger.capture.CaptureBuild.apiVersion())
			.runeLiteClientArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.clientArtifactSha256()).build();
		com.google.gson.JsonObject fixture = gson
			.toJsonTree(raw.toBuilder().meta(meta).journalTicks(journal.getTickHistory()).build()).getAsJsonObject();
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/effect-reference.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void everyPinnedGraphicReferenceIncludesBindAndExcludesOtherBarrages()
	{
		enableEffects = true;
		for (int id : new int[]
		{ 179, 180, 181, 361, 363, 367, 369 })
		{
			playerGraphic = id;
			tick();
		}
		assertEquals(7, export().getEvents().getEffectEvents().size());
		for (int id : new int[]
		{ 377, 383, 391, 1049, 177, 178, 1300 })
		{
			playerGraphic = id;
			tick();
		}
		assertEquals(7, export().getEvents().getEffectEvents().size());
		NPC otherTarget = stub(NPC.class,
			(n, a) -> n.equals("getGraphic") ? 181
				: n.equals("getWorldLocation") ? position
					: n.equals("getLocalLocation") ? localPosition : n.equals("getName") ? "Other target" : null);
		net.runelite.api.events.GraphicChanged graphicEvent = new net.runelite.api.events.GraphicChanged();
		graphicEvent.setActor(otherTarget);
		plugin.onGraphicChanged(graphicEvent);
		tick();
		assertEquals(7, export().getEvents().getEffectEvents().size());
		assertTrue(export().getEvents().getObservations().stream()
			.anyMatch(event -> "ACTOR_GRAPHICS_CHANGED".equals(event.getType())));
	}

	@Test
	public void sameGraphicInANewSceneEpochIsANewSightingWithoutClaimingNewApplication() throws Exception
	{
		enableEffects = true;
		playerGraphic = 181;
		tick();
		tick();
		assertEquals(1, export().getEvents().getEffectEvents().size());
		inject("loginPending", false);
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);
		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		tick();
		assertEquals(2, export().getEvents().getEffectEvents().size());
		assertEquals(3, export().getEvents().getEffectEvents().get(1).getStartTick());
		assertNull(export().getEvents().getEffectEvents().get(1).getDurationTicks());
	}

	@Test
	public void independentGraphicReadsRetainPositiveReferencesAndDoNotPretendFailedSamplesAreEmpty()
	{
		enableEffects = true;
		playerGraphic = 181;
		failGraphicsCollection = true;
		tick();
		assertEquals(1, export().getEvents().getEffectEvents().size());
		assertFalse(journal.getTickHistory().get(0).isStateKnown("effectGraphics"));
		failGraphicsCollection = false;
		failPlayerGraphic = true;
		tick();
		assertEquals(1, export().getEvents().getEffectEvents().size());
		failGraphicsCollection = true;
		tick();
		assertEquals(1, export().getEvents().getEffectEvents().size());
		failGraphicsCollection = false;
		failPlayerGraphic = false;
		tick();
		assertEquals(2, export().getEvents().getEffectEvents().size());
		assertEquals(4, export().getEvents().getEffectEvents().get(1).getStartTick());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void observedLoadingBoundaryRetainsTheOriginalBatchInsteadOfMergingWithRecovery() throws Exception
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		gameState = GameState.LOADING;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		tick();
		assertEquals(2, journal.getTickHistory().size());
		TickData gap = journal.getTickHistory().get(1);
		assertEquals("LOADING", gap.getObservedGameState());
		assertEquals(Integer.valueOf(1), gap.getApiTickCount());
		assertFalse(gap.isStateKnown("currentHp"));
		assertFalse(gap.isStateKnown("movement"));
		assertFalse(gap.isStateKnown("worldSnapshot"));
		assertEquals(7, gap.getDamageEvents().get(0).getAmount());
		assertEquals(2, export().getEvents().getDamageEvents().size());
		assertTrue(export().getEvents().getDamageEvents().stream().allMatch(hit -> hit.getImpactTick() == 2));
		gameState = GameState.LOGGED_IN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		position = new WorldPoint(3205, 3200, 0);
		tick();
		assertEquals(3, journal.getTickHistory().size());
		assertNull(journal.getTickHistory().get(2).getMovement());
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
	}

	@Test
	public void unavailableEntryStateWithAKnownSessionRetainsDeathWithoutUsingCachedWorldState()
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 4);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		failGameState = true;
		tick();
		assertEquals(2, journal.getTickHistory().size());
		assertEquals(1, storedDeaths().size());
		assertFalse(journal.getTickHistory().get(1).isStateKnown("gameState"));
		assertFalse(journal.getTickHistory().get(1).isStateKnown("currentHp"));
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(Integer.valueOf(2), rich.getMeta().getDeathTick());
		assertEquals(2, rich.getEvents().getDamageEvents().get(0).getImpactTick());
		assertEquals("Unknown", rich.getEvents().getDamageEvents().get(0).getAttackerName());
	}

	@Test
	public void anUnestablishedSessionCannotInventTickRowsFromUnavailableOrLoadingState()
	{
		failGameState = true;
		tick();
		assertTrue(journal.getTickHistory().isEmpty());
		failGameState = false;
		gameState = GameState.LOADING;
		tick();
		assertTrue(journal.getTickHistory().isEmpty());
		gameState = GameState.LOGIN_SCREEN;
		tick();
		assertTrue(journal.getTickHistory().isEmpty());
	}

	@Test
	public void connectionStateCallbacksDoNotInventTicksAndObservedBoundariesBreakEffectBaselines()
	{
		enableEffects = true;
		poisonVarp = 0;
		tick();
		gameState = GameState.CONNECTION_LOST;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		assertEquals(1, journal.getTickHistory().size());
		tick();
		assertEquals(2, journal.getTickHistory().size());
		assertEquals("CONNECTION_LOST", journal.getTickHistory().get(1).getObservedGameState());
		assertFalse(journal.getTickHistory().get(1).isStateKnown("currentPrayer"));
		gameState = GameState.LOGGED_IN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		poisonVarp = 1;
		tick();
		assertTrue(export().getEvents().getEffectEvents().stream().noneMatch(effect -> "dot".equals(effect.getType())));
	}

	@Test
	public void failedBoundaryClocksDoNotBecomeZeroOrEraseIndependentlyObservedHits()
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		failApiTickCount = true;
		failGameCycle = true;
		tick();
		TickData row = journal.getTickHistory().get(1);
		assertNull(row.getApiTickCount());
		assertNull(row.getGameCycle());
		assertFalse(row.isStateKnown("apiTickCount"));
		assertFalse(row.isStateKnown("gameCycle"));
		assertEquals("LOGGED_IN", row.getObservedGameState());
		assertEquals(7, row.getDamageEvents().get(0).getAmount());
		assertTrue(row.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("boundaryGameCycle:")));
		failApiTickCount = false;
		failGameCycle = false;
		tick();
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
	}

	@Test
	public void variableCallbackPrimitiveAndLayoutSurviveUnavailableLocalIdentity()
	{
		tick();
		varbitCompositions.put(3844,
			stub(net.runelite.api.VarbitComposition.class, (name, args) -> name.equals("getIndex") ? 1044
				: name.equals("getLeastSignificantBit") ? 7 : name.equals("getMostSignificantBit") ? 8 : null));
		net.runelite.api.events.VarbitChanged event = new net.runelite.api.events.VarbitChanged();
		event.setVarpId(-1);
		event.setVarbitId(3844);
		event.setValue(3);
		failLocalPlayer = true;
		plugin.onVarbitChanged(event);
		failLocalPlayer = false;
		tick();
		com.tickreplaylogger.model.world.ObservedEvent observed = export().getEvents().getObservations().stream()
			.filter(v -> v.getType().equals("GAME_VARIABLE_CHANGED")).findFirst().orElse(null);
		assertNotNull(observed);
		assertEquals(3, observed.getData().get("value"));
		assertEquals(3844, observed.getData().get("varbitId"));
		com.google.gson.JsonObject layout = gson.toJsonTree(observed.getData().get("varbitLayout")).getAsJsonObject();
		assertEquals(1044, layout.get("varpIndex").getAsInt());
		assertTrue(layout.get("layoutKnown").getAsBoolean());
		assertNull(observed.getActorRef());
		assertEquals(2, observed.getTick());
	}

	@Test
	public void globalSpecialEnergyObservationsDoNotSkipAnIntermediateCallbackWithMissingIdentity()
	{
		tick();
		net.runelite.api.events.VarbitChanged event = new net.runelite.api.events.VarbitChanged();
		event.setVarpId(300);
		event.setVarbitId(-1);
		event.setValue(1000);
		plugin.onVarbitChanged(event);
		failLocalPlayer = true;
		specialVarp = 750;
		event.setValue(750);
		plugin.onVarbitChanged(event);
		failLocalPlayer = false;
		specialVarp = 500;
		event.setValue(500);
		plugin.onVarbitChanged(event);
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> changes = new ArrayList<>();
		for (com.tickreplaylogger.model.world.ObservedEvent row : export().getEvents().getObservations())
			if (row.getType().equals("SPECIAL_ENERGY_CHANGED"))
				changes.add(row);
		assertEquals(2, changes.size());
		assertEquals(750, changes.get(0).getData().get("current"));
		assertEquals(1000, changes.get(0).getData().get("previous"));
		assertNull(changes.get(0).getActorRef());
		assertEquals(500, changes.get(1).getData().get("current"));
		assertEquals(750, changes.get(1).getData().get("previous"));
	}

	@Test
	public void settledVarbitLayoutRecordsActualClientFieldsInsteadOfAssumingTheBundle()
	{
		varbitCompositions.put(14380,
			stub(net.runelite.api.VarbitComposition.class, (name, args) -> name.equals("getIndex") ? 1044
				: name.equals("getLeastSignificantBit") ? 7 : name.equals("getMostSignificantBit") ? 8 : null));
		tick();
		com.google.gson.JsonObject raw = gson.toJsonTree(export().getTicks().get(0)).getAsJsonObject();
		assertTrue("Observed client layouts must be in the export", raw.has("varbitLayouts"));
		com.google.gson.JsonObject layout = raw.getAsJsonObject("varbitLayouts").getAsJsonObject("14380");
		assertNotNull(layout);
		assertEquals(1044, layout.get("varpIndex").getAsInt());
		assertEquals(7, layout.get("leastSignificantBit").getAsInt());
		assertEquals(8, layout.get("mostSignificantBit").getAsInt());
		assertTrue(layout.get("layoutKnown").getAsBoolean());
		assertEquals("CLIENT_VARBIT_COMPOSITION", layout.get("evidence").getAsString());
	}

	@Test
	public void partialVarbitLayoutDoesNotFabricateZeroOrDiscardIndependentFields()
	{
		varbitCompositions.put(14380, stub(net.runelite.api.VarbitComposition.class, (name, args) ->
		{
			if (name.equals("getMostSignificantBit"))
				throw new IllegalStateException("synthetic field unavailable");
			return name.equals("getIndex") ? 3592 : name.equals("getLeastSignificantBit") ? 12 : null;
		}));
		tick();
		com.google.gson.JsonObject raw = gson.toJsonTree(export().getTicks().get(0)).getAsJsonObject();
		assertTrue(raw.has("varbitLayouts"));
		com.google.gson.JsonObject layout = raw.getAsJsonObject("varbitLayouts").getAsJsonObject("14380");
		assertNotNull(layout);
		assertEquals(3592, layout.get("varpIndex").getAsInt());
		assertEquals(12, layout.get("leastSignificantBit").getAsInt());
		assertFalse(layout.has("mostSignificantBit"));
		assertFalse(layout.get("layoutKnown").getAsBoolean());
		assertNotNull(export().getTicks().get(0).getPlayer());
	}

	@Test
	public void varbitLayoutsExportActualMatchingConflictingPartialAndCallbackFields() throws Exception
	{
		clientVarps = new int[6500];
		serverVarps = new int[6500];
		clientVarps[300] = specialVarp;
		serverVarps[300] = specialVarp;
		varbitCompositions.put(14380,
			stub(net.runelite.api.VarbitComposition.class, (name, args) -> name.equals("getIndex") ? 3592
				: name.equals("getLeastSignificantBit") ? 12 : name.equals("getMostSignificantBit") ? 22 : null));
		varbitObservedValues.put(14380, 64);
		clientVarps[3592] = 64 << 12;
		serverVarps[3592] = 128 << 12;
		tick();
		varbitCompositions.put(3844,
			stub(net.runelite.api.VarbitComposition.class, (name, args) -> name.equals("getIndex") ? 1044
				: name.equals("getLeastSignificantBit") ? 7 : name.equals("getMostSignificantBit") ? 8 : null));
		clientVarps[1044] = 3 << 7;
		serverVarps[1044] = 1 << 7;
		net.runelite.api.events.VarbitChanged changed = new net.runelite.api.events.VarbitChanged();
		changed.setVarpId(-1);
		changed.setVarbitId(3844);
		changed.setValue(3);
		changed.setLongValue(3);
		plugin.onVarbitChanged(changed);
		tick();
		varbitCompositions.put(14380, varbitCompositions.get(3844));
		varbitObservedValues.put(14380, 2);
		clientVarps[1044] = 2 << 7;
		serverVarps[1044] = 0;
		tick();
		varbitCompositions.put(14380, stub(net.runelite.api.VarbitComposition.class, (name, args) ->
		{
			if (name.equals("getMostSignificantBit"))
				throw new IllegalStateException("synthetic partial layout");
			return name.equals("getIndex") ? 3592 : name.equals("getLeastSignificantBit") ? 12 : null;
		}));
		tick();
		varbitCompositions.put(14380,
			stub(net.runelite.api.VarbitComposition.class, (name, args) -> name.equals("getIndex") ? 3592
				: name.equals("getLeastSignificantBit") ? 12 : name.equals("getMostSignificantBit") ? 22 : null));
		varbitObservedValues.put(14380, 64);
		failClientVarps = true;
		tick();
		failClientVarps = false;
		clientVarps[3592] = 149 << 12;
		serverVarps[3592] = 150 << 12;
		varbitObservedValues.put(14380, 149);
		hit(HitsplatID.POISON, 99);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 recording = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(6, recording.getTicks().size());
		assertEquals(Integer.valueOf(6), recording.getMeta().getDeathTick());
		assertEquals(Integer.valueOf(3592), recording.getTicks().get(0).getVarbitLayouts().get(14380).getVarpIndex());
		assertEquals(Integer.valueOf(1044), recording.getTicks().get(2).getVarbitLayouts().get(14380).getVarpIndex());
		assertFalse(recording.getTicks().get(3).getVarbitLayouts().get(14380).isLayoutKnown());
		assertEquals("failed", recording.getTicks().get(4).getCaptureCoverage().get("variables"));
		assertEquals("available", recording.getTicks().get(4).getCaptureCoverage().get("serverVariables"));
		assertEquals(99, recording.getEvents().getDamageEvents().stream()
			.filter(v -> "RECEIVED".equals(v.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, recording.getEvents().getDamageEvents().stream().filter(v -> "OTHER".equals(v.getOwnership()))
			.mapToInt(DamageRecord::getAmount).sum());
		com.google.gson.JsonObject fixture = gson.toJsonTree(recording).getAsJsonObject();
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-varbit-layouts");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path output = java.nio.file.Paths.get("build/test-fixtures/varbit-layouts.json");
		java.nio.file.Files.createDirectories(output.getParent());
		java.nio.file.Files.write(output, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void failedSnapshotClockRetainsIndependentlyObservedActors()
	{
		tick();
		failGameCycle = true;
		failApiTickCount = true;
		hit(HitsplatID.POISON, 7);
		tick();
		com.tickreplaylogger.model.world.WorldSnapshot frame = export().getTicks().stream()
			.filter(v -> v.getTick() == 2).findFirst().orElse(null);
		assertNotNull("An unavailable clock must not erase the observed world", frame);
		assertNotNull(frame.getPlayer());
		assertEquals(1, frame.getNpcs().size());
		assertEquals(Boolean.FALSE, frame.getGameCycleKnown());
		assertNull(frame.getApiTickCount());
		assertEquals("failed", frame.getCaptureCoverage().get("gameCycle"));
		assertEquals(7, export().getEvents().getDamageEvents().get(0).getAmount());
	}

	@Test
	public void rootNativeScopeFailureRetainsUnscopedActorFactsAndBreaksTiming()
	{
		tick();
		failedPlayerSnapshotReads.add("getWorldView");
		hit(HitsplatID.POISON, 7);
		tick();
		com.tickreplaylogger.model.world.WorldSnapshot frame = export().getTicks().stream()
			.filter(v -> v.getTick() == 2).findFirst().orElse(null);
		assertNotNull("Primary scope preparation must not bypass independent capture", frame);
		assertEquals(Boolean.FALSE, frame.getNativeContextKnown());
		assertNull(frame.getSceneKey());
		assertNull(frame.getWorldViewId());
		assertNotNull(frame.getPlayer());
		assertEquals(1, frame.getNpcs().size());
		assertEquals("Test boss", frame.getNpcs().get(0).getName());
		assertNull(frame.getNpcs().get(0).getDerivedAction());
		assertNull(frame.getNpcs().get(0).getAttackCooldownTicksRemaining());
		assertNull(frame.getNpcs().get(0).getNormalWorldContextObserved());
		assertEquals("TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT",
			frame.getCaptureCoverage().get("npcsScope"));
		assertFalse(journal.getTickHistory().get(1).isStateKnown("nativeContext"));
		failedPlayerSnapshotReads.clear();
		tick();
		assertNull(journal.getTickHistory().get(2).getMovement());
		assertEquals(1, export().getEvents().getDamageEvents().size());
	}

	@Test
	public void sceneMetadataFailureDoesNotEraseAvailableSettledActors() throws Exception
	{
		WorldCaptureService capture = new WorldCaptureService()
		{
			@Override
			public com.tickreplaylogger.model.world.SceneData captureScene(Client client, String key, int sequence,
				boolean collision)
			{
				throw new IllegalStateException("synthetic metadata failure");
			}
		};
		capture.setDamageMap(DamageMap.load(gson));
		inject("captureService", capture);
		tick();
		com.tickreplaylogger.model.world.WorldSnapshot frame = export().getTicks().stream().findFirst().orElse(null);
		assertNotNull("Optional scene metadata cannot erase raw actor facts", frame);
		assertNotNull(frame.getPlayer());
		assertEquals(1, frame.getNpcs().size());
		assertEquals("unavailable", frame.getCaptureCoverage().get("sceneMetadata"));
		assertTrue(frame.getCaptureIssues().stream().anyMatch(v -> v.startsWith("sceneMetadata:")));
		assertFalse(journal.getTickHistory().get(0).isStateKnown("sceneMetadata"));
	}

	@Test
	public void cleanupFailureAfterAStoredSnapshotDoesNotInvalidateThatSnapshot() throws Exception
	{
		projectile(boss, 100);
		tick();
		// A projectile lifetime read is a different channel from already collected world facts.
		Projectile broken = stub(Projectile.class, (name, args) ->
		{
			if (name.equals("getRemainingCycles"))
				throw new IllegalStateException("synthetic cleanup failure");
			return null;
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(broken);
		plugin.onProjectileMoved(moved);
		tick();
		assertTrue(journal.getTickHistory().get(1).isStateKnown("worldSnapshot"));
		assertFalse(journal.getTickHistory().get(1).isStateKnown("projectileCleanup"));
		assertTrue(export().getTicks().stream().anyMatch(v -> v.getTick() == 2));
	}

	@Test
	public void rootSnapshotFailuresExportUnscopedFactsClockAvailabilityAndDeath() throws Exception
	{
		boolean[] metadataUnavailable =
		{ false };
		WorldCaptureService capture = new WorldCaptureService()
		{
			@Override
			public com.tickreplaylogger.model.world.SceneData captureScene(Client client, String key, int sequence,
				boolean collision)
			{
				if (metadataUnavailable[0])
					throw new IllegalStateException("synthetic metadata failure");
				return super.captureScene(client, key, sequence, collision);
			}
		};
		capture.setDamageMap(DamageMap.load(gson));
		inject("captureService", capture);
		otherPlayers.add(stub(Player.class,
			(name, args) -> name.equals("getId") ? 2
				: name.equals("getName") ? "Synthetic teammate"
					: name.equals("getWorldLocation") ? new WorldPoint(3201, 3200, 0)
						: name.equals("getLocalLocation") ? new LocalPoint(6528, 6400) : null));
		tick();
		failedPlayerSnapshotReads.add("getWorldView");
		hit(HitsplatID.POISON, 7);
		hp = 92;
		tick();
		failedPlayerSnapshotReads.clear();
		failGameCycle = true;
		tick();
		failGameCycle = false;
		metadataUnavailable[0] = true;
		inject("needSceneCapture", true);
		tick();
		metadataUnavailable[0] = false;
		failedPlayerSnapshotReads.add("getWorldView");
		failGameCycle = true;
		hit(HitsplatID.POISON, 92);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 exported = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(5, exported.getTicks().size());
		assertEquals(5, exported.getJournalTicks().size());
		assertEquals(Integer.valueOf(5), exported.getMeta().getDeathTick());
		for (int index : new int[]
		{ 1, 4 })
		{
			com.tickreplaylogger.model.world.WorldSnapshot frame = exported.getTicks().get(index);
			assertEquals(Boolean.FALSE, frame.getNativeContextKnown());
			assertNull(frame.getSceneKey());
			assertNull(frame.getWorldViewId());
			assertEquals(1, frame.getNpcs().size());
			assertEquals(1, frame.getPlayers().size());
			assertNotNull(frame.getPlayer());
			assertNull(frame.getNpcs().get(0).getDerivedAction());
			assertNull(frame.getNpcs().get(0).getObservedPhase());
		}
		assertEquals(Boolean.FALSE, exported.getTicks().get(2).getGameCycleKnown());
		assertEquals(Boolean.FALSE, exported.getTicks().get(4).getGameCycleKnown());
		assertEquals("unavailable", exported.getTicks().get(3).getCaptureCoverage().get("sceneMetadata"));
		assertEquals(99, exported.getEvents().getDamageEvents().stream()
			.filter(v -> "RECEIVED".equals(v.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, exported.getEvents().getDamageEvents().stream().filter(v -> "OTHER".equals(v.getOwnership()))
			.mapToInt(DamageRecord::getAmount).sum());
		com.google.gson.JsonObject fixture = gson.toJsonTree(exported).getAsJsonObject();
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-root-snapshot");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path output = java.nio.file.Paths.get("build/test-fixtures/root-snapshot.json");
		java.nio.file.Files.createDirectories(output.getParent());
		java.nio.file.Files.write(output, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void freshSessionCannotReusePriorIdentityWhenItsFirstBoundaryStateReadFails()
	{
		tick();
		GameStateChanged event = new GameStateChanged();
		gameState = GameState.LOGIN_SCREEN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		failGameState = true;
		tick();
		assertTrue(journal.getTickHistory().isEmpty());
		failGameState = false;
		gameState = GameState.LOGGED_IN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		failGameState = true;
		tick();
		DeathRecordingV2 rich = gson.fromJson(captureLiveJson(journal.getTickHistory()),
			DeathRecordingV2.class);
		assertNull(rich.getMeta().getRsn());
		assertEquals(0, rich.getMeta().getWorld());
		assertEquals(1, rich.getJournalTicks().size());
		assertEquals(1, rich.getJournalTicks().get(0).getTickNumber());
		assertTrue(rich.getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void unavailableEntryContextCannotReuseAnEarlierProjectileOnRecovery()
	{
		projectile(npc("Old archer", 2), 100);
		tick();
		long launchEpoch = world.snapshotAtTick(1).getProjectiles().get(0).getSourceHistory().getContextEpoch();
		failGameState = true;
		tick();
		failGameState = false;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.noneMatch(candidate -> "Old archer".equals(candidate.getName())));
		assertRecordedEpochs(launchEpoch, false);
	}

	@Test
	public void boundaryContextFixtureRetainsLoadingUnknownStateRecoveryAndDeath() throws Exception
	{
		hp = 15;
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		hp = 8;
		gameState = GameState.LOADING;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		tick();
		gameState = GameState.LOGGED_IN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		position = new WorldPoint(3205, 3200, 0);
		tick();
		hit(HitsplatID.POISON, 8);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		failGameState = true;
		tick();
		assertEquals(1, storedDeaths().size());
		com.google.gson.JsonObject fixture = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-boundary-context");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		assertEquals(4, fixture.getAsJsonArray("journalTicks").size());
		assertEquals(2, fixture.getAsJsonArray("ticks").size());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/boundary-context.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void callbackClockFailureCannotDiscardPlayerOrSharedNpcHits()
	{
		tick();
		failGameCycle = true;
		hit(HitsplatID.POISON, 5);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		failGameCycle = false;
		tick();
		assertEquals(5, journal.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
		List<DamageRecord> damage = export().getEvents().getDamageEvents();
		assertEquals(2, damage.size());
		for (DamageRecord value : damage)
		{
			assertNull(value.getObservedCycle());
			assertTrue(value.getCaptureIssues().stream().anyMatch(issue -> issue.contains("GameCycle")));
		}
		assertEquals(2,
			export().getEvents().getObservations().stream()
				.filter(o -> "HITSPLAT".equals(o.getType()) && !gson.toJsonTree(o).getAsJsonObject().has("gameCycle"))
				.count());
	}

	@Test
	public void deathMetadataFailureCannotDiscardAnObservedLocalDeath()
	{
		tick();
		hit(HitsplatID.POISON, 5);
		hp = 0;
		failGameCycle = true;
		failPlayerName = true;
		failPlayerWorldPosition = true;
		failWorld = true;
		plugin.onActorDeath(new ActorDeath(player));
		failGameCycle = false;
		failPlayerName = false;
		failPlayerWorldPosition = false;
		failWorld = false;
		tick();
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(Integer.valueOf(2), rich.getMeta().getDeathTick());
		assertNull(rich.getMeta().getRsn());
		assertEquals(0, rich.getMeta().getWorld());
		assertEquals(5, rich.getSummary().getFatalHit().getAmount());
		assertNull(rich.getEvents().getDeaths().get(0).getGameCycle());
	}

	@Test
	public void callbackClockFailureCannotEraseAnUnresolvedResourceImpact()
	{
		tick();
		failGameCycle = true;
		hit(HitsplatID.DISEASE, 4);
		plugin.onActorDeath(new ActorDeath(player));
		failGameCycle = false;
		hp = 0;
		tick();
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(1, rich.getSummary().getUnresolvedImpactObservationCount());
		assertNull(rich.getSummary().getFatalHit());
		assertEquals(4, ((Number) rich.getSummary().getUnresolvedImpactObservations().get(0).get("amount")).intValue());
		assertFalse(rich.getSummary().getUnresolvedImpactObservations().get(0).containsKey("gameCycle"));
	}

	@Test
	public void npcHitsDoNotRequireAnAvailableLocalPlayerRead()
	{
		tick();
		failLocalPlayer = true;
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		failLocalPlayer = false;
		tick();
		assertEquals(9, capturedLastDamage().getAmount());
		assertEquals("NPC_RECEIVED", capturedLastDamage().getDirection());
		assertEquals("OTHER", capturedLastDamage().getOwnership());
	}

	@Test
	public void failedCallbackLocalIdentityCanBeResolvedOnlyByTheSameBoundaryActorMatch()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(player));
		failLocalPlayer = false;
		hp = 0;
		tick();
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(5, rich.getSummary().getFatalHit().getAmount());
		assertEquals(Integer.valueOf(2), rich.getMeta().getDeathTick());
		assertTrue(rich.getSummary().getFatalHit().getCaptureIssues().stream()
			.anyMatch(issue -> issue.contains("LocalPlayer")));
		assertNotNull(rich.getSummary().getFatalHit().getObservedCycle());
	}

	@Test
	public void unresolvedCallbackIdentityCannotBeAssignedToAnotherPlayerOnALaterTick()
	{
		tick();
		Player other = stub(Player.class, (n, a) -> n.equals("getName") ? "Other" : null);
		failLocalPlayer = true;
		hit(other, HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(other));
		failLocalPlayer = false;
		tick();
		tick();
		assertTrue(storedDeaths().isEmpty());
		assertTrue(journal.getTickHistory().stream().allMatch(row -> row.getDamageEvents().isEmpty()));
		assertTrue(export().getEvents().getObservations().stream().anyMatch(o -> "HITSPLAT".equals(o.getType())));
	}

	@Test
	public void knownSessionCallbackStateFailureRetainsPrimitivesWithoutCachedWorldState()
	{
		tick();
		failGameState = true;
		hit(HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(player));
		failGameState = false;
		hp = 0;
		tick();
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(5, rich.getSummary().getFatalHit().getAmount());
		assertFalse(Boolean.TRUE.equals(rich.getSummary().getFatalHit().getImpactPositionKnown()));
	}

	@Test
	public void privateUnknownCallbackIdentityRetainsNoOtherPlayerMetadata() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		tick();
		Player other = stub(Player.class,
			(n, a) -> n.equals("getName") ? "Private other" : n.equals("getWorldLocation") ? position : null);
		failLocalPlayer = true;
		hit(other, HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(other));
		failLocalPlayer = false;
		tick();
		String json = gson.toJson(export());
		assertFalse(json.contains("Private other"));
		assertTrue(export().getEvents().getObservations().stream().filter(o -> o.getTick() == 2).allMatch(
			o -> o.getActorName() == null && o.getActorRef() == null && !o.getData().containsKey("actorState")));
		assertTrue(export().getEvents().getObservations().stream().anyMatch(
			o -> "HITSPLAT".equals(o.getType()) && o.getActorRef() == null && !o.getData().containsKey("actorState")));
		assertTrue(storedDeaths().isEmpty());
		assertTrue(journal.getTickHistory().get(1).getDamageEvents().isEmpty());
	}

	@Test
	public void deferredIncomingHitsRetainTheirOriginalCallbackOrder()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.POISON, 7);
		failLocalPlayer = false;
		hit(HitsplatID.POISON, 5);
		tick();
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(7, hits.get(0).getAmount());
		assertEquals(5, hits.get(1).getAmount());
		assertTrue(hits.get(0).getObservationSequence() < hits.get(1).getObservationSequence());
	}

	@Test
	public void unreadyBoundaryCannotCarryUnassignedIntakeToLaterIdentity()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertFalse(journal.getTickHistory().get(1).isStateKnown("callbackIdentity"));
		failLocalPlayer = false;
		tick();
		assertTrue(storedDeaths().isEmpty());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(export().getEvents().getObservations().stream().anyMatch(o -> "ACTOR_DEATH".equals(o.getType())));
	}

	@Test
	public void freshSessionResetClearsUnassignedIntake()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.POISON, 5);
		plugin.onActorDeath(new ActorDeath(player));
		failLocalPlayer = false;
		GameStateChanged event = new GameStateChanged();
		gameState = GameState.LOGIN_SCREEN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		gameState = GameState.LOGGED_IN;
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
		tick();
		assertTrue(storedDeaths().isEmpty());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(export().getEvents().getObservations().isEmpty());
	}

	@Test
	public void callbackIntakeFixtureRetainsPartialClocksIdentityAndFatalMetadata() throws Exception
	{
		hp = 30;
		tick();
		failGameCycle = true;
		hit(HitsplatID.POISON, 5);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		failGameCycle = false;
		hp = 25;
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		failLocalPlayer = false;
		hp = 18;
		tick();
		failGameState = true;
		hit(HitsplatID.DISEASE, 2);
		hit(HitsplatID.DAMAGE_ME, 3);
		failGameState = false;
		hp = 15;
		tick();
		tick();
		failGameCycle = true;
		hit(HitsplatID.POISON, 15);
		failPlayerName = true;
		failPlayerWorldPosition = true;
		failWorld = true;
		plugin.onActorDeath(new ActorDeath(player));
		failGameCycle = false;
		failPlayerName = false;
		failPlayerWorldPosition = false;
		failWorld = false;
		hp = 0;
		tick();
		assertEquals(1, storedDeaths().size());
		com.google.gson.JsonObject fixture = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-callback-intake");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		assertEquals(6, fixture.getAsJsonArray("journalTicks").size());
		assertEquals(30, fixture.getAsJsonObject("summary").get("totalDamageTakenInBuffer").getAsInt());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/callback-intake.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void transientLocalReadRecoveryCannotSilentlyChangeTheCallbackIdentityEvidence()
	{
		tick();
		localPlayerFailuresRemaining = 1;
		hit(HitsplatID.POISON, 5);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = export().getEvents().getObservations().stream()
			.filter(o -> "HITSPLAT".equals(o.getType())).findFirst().get();
		assertNull(raw.getActorRef());
		assertEquals("SETTLED_LOCAL_REFERENCE_MATCH", capturedLastDamage().getDefenderIdentityEvidence());
		assertEquals(5, capturedLastDamage().getAmount());
	}

	@Test
	public void timedProtectionChangesCannotInventHitsOrAppliedEffectEvents()
	{
		enableEffects = true;
		for (int value : new int[]
		{ -39, -38, -1, 0 })
		{
			poisonVarp = value;
			tick();
		}
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(export().getEvents().getEffectEvents().isEmpty());
		assertTrue(journal.getTickHistory().stream().allMatch(row -> row.getDamageEvents().isEmpty()));
		List<com.tickreplaylogger.model.world.WorldSnapshot> frames = export().getTicks();
		assertEquals("poison_venom_protection", frames.get(0).getLocalStatuses().stream()
			.filter(row -> "poison_protection_client_state".equals(row.getKey())).findFirst().get().getState());
		assertEquals("inactive", frames.get(3).getLocalStatuses().stream()
			.filter(row -> "poison_protection_client_state".equals(row.getKey())).findFirst().get().getState());
	}

	@Test
	public void resourceStateFixtureRetainsHealingNativeEnergyFailuresAndDeath() throws Exception
	{
		hp = 80;
		prayerPoints = 80;
		runEnergyValue = 10000;
		specialVarp = 1000;
		tick();
		hit(HitsplatID.DAMAGE_ME, 10);
		hit(HitsplatID.HEAL, 20);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hp = 90;
		prayerPoints = 79;
		runEnergyValue = 9999;
		specialVarp = 999;
		tick();
		prayerPoints = 78;
		runEnergyValue = 9998;
		specialVarp = 998;
		tick();
		prayerPoints = 77;
		failRunEnergy = true;
		failSpecialEnergy = true;
		tick();
		prayerPoints = 76;
		failRunEnergy = false;
		failSpecialEnergy = false;
		runEnergyValue = 9850;
		specialVarp = 950;
		tick();
		hit(HitsplatID.POISON, 90);
		hp = 0;
		prayerPoints = 75;
		runEnergyValue = 9849;
		specialVarp = 949;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertEquals(1, storedDeaths().size());
		com.google.gson.JsonObject fixture = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-resource-state");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		assertEquals(6, fixture.getAsJsonArray("ticks").size());
		assertEquals(100, fixture.getAsJsonObject("summary").get("totalDamageTakenInBuffer").getAsInt());
		assertFalse(fixture.getAsJsonArray("ticks").get(3).getAsJsonObject().getAsJsonObject("player")
			.get("runEnergyKnown").getAsBoolean());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/resource-state.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void sameBoundaryLocalMatchRetainsHealingAndPrayerRecipientLinksWithoutChangingRawCallbacks()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.HEAL, 20);
		hit(HitsplatID.CORRUPTION, 5);
		failLocalPlayer = false;
		tick();
		com.google.gson.JsonObject events = gson.toJsonTree(export().getEvents()).getAsJsonObject();
		assertTrue(events.has("observationLinks"));
		com.google.gson.JsonArray links = events.getAsJsonArray("observationLinks");
		assertEquals(2, links.size());
		for (com.google.gson.JsonElement value : links)
		{
			com.google.gson.JsonObject link = value.getAsJsonObject();
			assertEquals("player", link.get("actorRef").getAsString());
			assertEquals(2, link.get("tick").getAsInt());
			assertEquals(2, link.get("resolvedTick").getAsInt());
			assertEquals("HITSPLAT", link.get("observationType").getAsString());
		}
		assertTrue(export().getEvents().getObservations().stream().filter(o -> "HITSPLAT".equals(o.getType()))
			.allMatch(o -> o.getActorRef() == null));
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void sameBoundaryDeathIdentityLinkKeepsTheOriginalUnknownRecipientObservation()
	{
		tick();
		failLocalPlayer = true;
		plugin.onActorDeath(new ActorDeath(player));
		failLocalPlayer = false;
		hp = 0;
		tick();
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		com.google.gson.JsonObject events = gson.toJsonTree(rich.getEvents()).getAsJsonObject();
		assertTrue(events.has("observationLinks"));
		assertEquals("ACTOR_DEATH",
			events.getAsJsonArray("observationLinks").get(0).getAsJsonObject().get("observationType").getAsString());
		assertNull(rich.getEvents().getObservations().stream().filter(o -> "ACTOR_DEATH".equals(o.getType()))
			.findFirst().get().getActorRef());
	}

	@Test
	public void rejectedGameTickBoundaryCannotCarryAnUnassignedRecipientToTheNextDelivery()
	{
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.HEAL, 20);
		failLocalPlayer = false;
		gameState = GameState.LOGIN_SCREEN;
		tick();
		gameState = GameState.LOGGED_IN;
		tick();
		com.google.gson.JsonObject events = gson.toJsonTree(export().getEvents()).getAsJsonObject();
		assertTrue(events.has("observationLinks"));
		assertEquals(0, events.getAsJsonArray("observationLinks").size());
	}

	@Test
	public void observationLinkFixtureRetainsResourceRecipientsNpcDeathAndFatalOrdering() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean captureEffects()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		hp = 50;
		tick();
		failLocalPlayer = true;
		failGameCycle = true;
		hit(HitsplatID.HEAL, 20);
		hit(HitsplatID.CORRUPTION, 5);
		failLocalPlayer = false;
		failGameCycle = false;
		hp = 70;
		prayerPoints = 94;
		tick();
		failLocalPlayer = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		failLocalPlayer = false;
		hp = 63;
		tick();
		plugin.onActorDeath(new ActorDeath(boss));
		tick();
		failLocalPlayer = true;
		plugin.onActorDeath(new ActorDeath(player));
		hit(HitsplatID.POISON, 63);
		failLocalPlayer = false;
		hp = 0;
		tick();
		assertEquals(1, storedDeaths().size());
		com.google.gson.JsonObject fixture = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		fixture.getAsJsonObject("meta").addProperty("recordingId", "synthetic-observation-links");
		fixture.getAsJsonObject("meta").addProperty("exportedAtMs", 0);
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		assertEquals(5, fixture.getAsJsonObject("events").getAsJsonArray("observationLinks").size());
		assertEquals(70, fixture.getAsJsonObject("summary").get("totalDamageTakenInBuffer").getAsInt());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/observation-links.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void failedSettledHpDoesNotLoseAnObservedDeath()
	{
		hit(HitsplatID.DAMAGE_ME, 7);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		failedBoostedSkills.add(Skill.HITPOINTS);
		tick();
		assertEquals(1, storedDeaths().size());
		TickRecording death = storedDeaths().get(0);
		assertEquals(Boolean.TRUE, death.getFinalHpKnown());
		assertFalse(death.getTicks().get(0).isStateKnown("currentHp"));
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals(Integer.valueOf(1), rich.getMeta().getDeathTick());
		assertEquals(7, rich.getSummary().getFatalHit().getAmount());
	}

	@Test
	public void failedSettledMovementKeepsTheTickAndCannotBridgeAcrossTheGap()
	{
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		failPlayerWorldPosition = true;
		tick();
		assertEquals(7, journal.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
		assertFalse(journal.getTickHistory().get(1).isStateKnown("movement"));
		failPlayerWorldPosition = false;
		position = new WorldPoint(3205, 3200, 0);
		tick();
		assertNull(journal.getTickHistory().get(2).getMovement());
		position = new WorldPoint(3206, 3200, 0);
		tick();
		assertEquals(1, journal.getTickHistory().get(3).getMovement().getTilesCompleted());
	}

	@Test
	public void failedMetadataAndEffectReadsCannotPreventFinalizationOrReplayOldOnsets()
	{
		enableEffects = true;
		poisonVarp = 0;
		tick();
		hit(HitsplatID.POISON, 4);
		failedBoostedSkills.add(Skill.ATTACK);
		failPoisonVarp = true;
		failPlayerName = true;
		failWorld = true;
		tick();
		TickData partial = journal.getTickHistory().get(1);
		assertEquals(4, partial.getDamageEvents().get(0).getAmount());
		assertTrue(partial.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("statEffects:")));
		assertTrue(partial.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("statusEffects:")));
		assertTrue(partial.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("settledPlayerName:")));
		assertTrue(partial.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("settledWorld:")));
		failedBoostedSkills.clear();
		failPoisonVarp = false;
		failPlayerName = false;
		failWorld = false;
		poisonVarp = 1_000_001;
		tick();
		assertEquals(3, journal.getTickHistory().size());
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
		assertTrue(export().getEvents().getEffectEvents().stream()
			.noneMatch(effect -> "dot".equals(effect.getType()) || "stat_change".equals(effect.getType())));
	}

	@Test
	public void optionalActorDisplayFailureCannotEraseTheIncomingHit()
	{
		failPlayerDisplayMetadata = true;
		hp = 92;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(7, capturedLastDamage().getAmount());
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
		Map<String, Object> raw = export().getEvents().getObservations().stream()
			.filter(event -> "HITSPLAT".equals(event.getType())).findFirst().get().getData();
		assertEquals("PARTIAL_ACTOR_METADATA", ((Map<?, ?>) raw.get("actorState")).get("captureStatus"));
	}

	@Test
	public void failedCallbackCoordinatesKeepTheHitWithoutInventingAPositionOrSource()
	{
		animation = 123;
		failPlayerWorldPosition = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		failPlayerWorldPosition = false;
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals(7, damage.getAmount());
		assertEquals("Unknown", damage.getAttackerName());
		assertUnresolvedSource(damage);
		assertEquals(Boolean.FALSE, damage.getImpactPositionKnown());
		assertEquals(Boolean.FALSE, damage.getLaunchPositionKnown());
		assertTrue(damage.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("impactWorldPosition:")));
		Map<String, Object> raw = export().getEvents().getObservations().stream()
			.filter(event -> "HITSPLAT".equals(event.getType())).findFirst().get().getData();
		assertEquals("PARTIAL_EVENT_CONTEXT", raw.get("captureStatus"));
		assertEquals(7, raw.get("amount"));
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
	}

	@Test
	public void missingNpcDefenderCoordinatesDoNotDropOwnedOrOtherOwnedHits()
	{
		failNpcWorldPosition = true;
		hit(boss, HitsplatID.DAMAGE_ME, 12);
		hit(boss, HitsplatID.DAMAGE_OTHER, 9);
		tick();
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(2, hits.size());
		assertEquals(12, hits.get(0).getAmount());
		assertEquals("LOCAL_PLAYER", hits.get(0).getOwnership());
		assertEquals(9, hits.get(1).getAmount());
		assertEquals("OTHER", hits.get(1).getOwnership());
		for (DamageRecord damage : hits)
		{
			assertEquals(Boolean.FALSE, damage.getImpactPositionKnown());
			assertTrue(
				damage.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("defenderWorldPosition:")));
		}
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
	}

	@Test
	public void failedPrayerReadsRetainHitsAndCannotBecomeADeactivationAcrossTheGap()
	{
		trackPrayers = true;
		prayer = Prayer.PROTECT_FROM_MAGIC;
		tick();
		failPrayerFlags = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.POISON, 4);
		tick();
		assertEquals(Boolean.FALSE, journal.getTickHistory().get(1).getPrayerStateKnown());
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(2, hits.size());
		assertEquals(Boolean.FALSE, hits.get(0).getImpactPrayerStateKnown());
		assertEquals("PARTIAL_PRAYER_FLAGS", hits.get(0).getOverheadAtImpactEvidence());
		assertTrue(hits.get(0).getDefenderPrayerStateAtImpact().getFlags().isEmpty());
		assertTrue(hits.get(0).getDefenderPrayerStateAtImpact().isOverheadIconKnown());
		assertNull(hits.get(0).getDefenderPrayerStateAtImpact().getOverheadIcon());
		assertFalse(hits.get(0).getDefenderPrayerStateAtImpact().isOverheadPrayerKnown());
		assertEquals("Poison", hits.get(1).getAttackerName());
		assertEquals("poison", hits.get(1).getDamageType());
		failPrayerFlags = false;
		prayer = null;
		tick();
		assertEquals(Boolean.TRUE, journal.getTickHistory().get(2).getPrayerStateKnown());
		assertTrue(export().getEvents().getEffectEvents().stream()
			.noneMatch(effect -> effect.getType().startsWith("prayer_")));
	}

	@Test
	public void oneFailedSourceClassifierCannotEraseTheBatchOrRetryItsAmountsNextTick()
	{
		animation = 123;
		failNpcAnimation = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.POISON, 4);
		tick();
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(2, hits.size());
		assertEquals(7, hits.get(0).getAmount());
		assertUnresolvedSource(hits.get(0));
		assertEquals("unknown", hits.get(0).getDamageType());
		assertEquals("UNKNOWN", hits.get(0).getDetermination());
		assertTrue(hits.get(0).getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("impactHazards:")));
		assertEquals("Poison", hits.get(1).getAttackerName());
		failNpcAnimation = false;
		tick();
		assertEquals(2, export().getEvents().getDamageEvents().size());
		assertTrue(journal.getTickHistory().get(1).getDamageEvents().isEmpty());
	}

	@Test
	public void failedAttributionStillRetainsJournalOnlyDamage() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		animation = 123;
		failNpcAnimation = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(7, journal.getTickHistory().get(0).getDamageEvents().get(0).getAmount());
		assertEquals("Unknown", journal.getTickHistory().get(0).getDamageEvents().get(0).getSource());
	}

	@Test
	public void unavailableNpcReferenceEnrichmentCannotEraseOwnedDamage()
	{
		failNpcDefinitionMetadata = true;
		hit(boss, HitsplatID.DAMAGE_ME, 12);
		tick();
		assertEquals(12, capturedLastDamage().getAmount());
		assertEquals("DEALT", capturedLastDamage().getDirection());
		Map<String, Object> raw = export().getEvents().getObservations().stream()
			.filter(event -> "HITSPLAT".equals(event.getType())).findFirst().get().getData();
		assertEquals("unavailable", raw.get("actorResearchStatus"));
		assertEquals("PARTIAL_ACTOR_METADATA", ((Map<?, ?>) raw.get("actorState")).get("captureStatus"));
	}

	@Test
	public void journalOnlyRecordingDoesNotReadUnusedActorDisplayMetadata() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		failPlayerDisplayMetadata = true;
		hp = 92;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
		assertEquals(7, journal.getTickHistory().get(0).getDamageEvents().get(0).getAmount());
	}

	@Test
	public void aLogicalReloadCannotAttributeAnEarlierHitAgainstReusedSceneContainers()
	{
		animation = 123;
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		GameStateChanged state = new GameStateChanged();
		state.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(state);
		state.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(state);
		tick();
		assertEquals(7, capturedLastDamage().getAmount());
		DamageRecord damage = capturedLastDamage();
		Map<String, Object> originalActor = damage.getAttributionCandidates().stream()
			.filter(witness -> "NPC_ACTIVITY_OBSERVATION".equals(witness.get("kind"))).findFirst().get();
		assertNotNull(originalActor.get("actorRef"));
		assertTrue(damage.getSourceResolution().isUnidentifiedPossible());
		for (com.tickreplaylogger.model.world.SourceResolution.Candidate candidate : damage.getSourceResolution().getCandidates())
		{
			assertEquals("A later scene cannot add a different attacker", originalActor.get("actorRef"), candidate.getActorRef());
			assertEquals("Only the original uncertain pose was observed", "POSSIBLE", candidate.getStrength());
		}
		assertEquals(1, export().getEvents().getDamageEvents().size());
		assertTrue(export().getEvents().getObservations().stream().anyMatch(o -> o.getType().equals("HITSPLAT")));
		assertRecordedEpochs(null, true);
	}

	@Test
	public void originalApiProjectileKeepsItsSourceWhenOnlyFinalizationCrossesALoadingBoundary()
	{
		npcId = 8061;
		npcLevel = 732;
		npcObservedName = "Vorkath";
		tick();
		Projectile shot = projectile(npc("Vorkath", 123), 1479, 30);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		GameStateChanged changed = new GameStateChanged();
		changed.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(changed);
		changed.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(changed);
		moveSourceShot(shot); // A later observation crosses the lifetime; the earlier API/HP facts do not.
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals(6, damage.getAmount());
		assertEquals("Vorkath", damage.getAttackerName());
		assertEquals("TIMED", damage.getAttribution());
		assertEquals("magic", damage.getDamageType());
		assertTrue(damage.getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Vorkath".equals(candidate.getName())));
		assertRecordedEpochs(null, true);
		assertTrue("Later scope changes remain raw evidence without overwriting the original owner",
			damage.getAttributionCandidates().stream().anyMatch(witness ->
			{
				Object raw = witness.get("sourceHistory");
				if (!(raw instanceof com.tickreplaylogger.model.world.ProjectileSourceHistory))
				{
					return false;
				}
				com.tickreplaylogger.model.world.ProjectileSourceHistory history =
					(com.tickreplaylogger.model.world.ProjectileSourceHistory) raw;
				return history.isScopeChanged() && history.firstActor() != null
					&& history.firstActor().getContextEpoch() == damage.getImpactContextEpoch();
			}));
	}

	@Test
	public void aProjectileFromBeforeLogicalReloadCannotExplainANewHit()
	{
		projectile(npc("Old archer", 2), 100);
		tick();
		long launchEpoch = world.snapshotAtTick(1).getProjectiles().get(0).getSourceHistory().getContextEpoch();
		GameStateChanged state = new GameStateChanged();
		state.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(state);
		state.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(state);
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(7, capturedLastDamage().getAmount());
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.noneMatch(candidate -> "Old archer".equals(candidate.getName())));
		assertRecordedEpochs(launchEpoch, false);
	}

	@Test
	public void anExplicitWorldViewReloadCannotReuseEarlierProjectileHypotheses()
	{
		projectile(npc("Old archer", 2), 100);
		tick();
		long launchEpoch = world.snapshotAtTick(1).getProjectiles().get(0).getSourceHistory().getContextEpoch();
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class,
			(n, a) -> n.equals("getId") ? 2 : null);
		plugin.onWorldViewUnloaded(new net.runelite.api.events.WorldViewUnloaded(view));
		plugin.onWorldViewLoaded(new net.runelite.api.events.WorldViewLoaded(view));
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(7, capturedLastDamage().getAmount());
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.noneMatch(candidate -> "Old archer".equals(candidate.getName())));
		assertTrue(
			export().getEvents().getObservations().stream().anyMatch(o -> o.getType().equals("WORLD_VIEW_LOADED")));
		assertTrue(
			export().getEvents().getObservations().stream().anyMatch(o -> o.getType().equals("WORLD_VIEW_UNLOADED")));
		assertRecordedEpochs(launchEpoch, false);
	}

	private void assertRecordedEpochs(Long earlierProjectileEpoch, boolean changedBeforeFinalization)
	{
		DamageRecord hit = capturedLastDamage();
		Number original = (Number) callback("HITSPLAT").getData().get("causalSceneEpoch");
		assertNotNull("Original callback lifetime is retained", original);
		assertEquals(Long.valueOf(original.longValue()), hit.getImpactContextEpoch());
		assertNotNull(hit.getFinalizationContextEpoch());
		if (earlierProjectileEpoch != null)
		{
			assertTrue("Earlier projectile provenance must remain distinguishable", earlierProjectileEpoch < hit.getImpactContextEpoch());
		}
		if (changedBeforeFinalization)
		{
			assertTrue(hit.getFinalizationContextEpoch() > hit.getImpactContextEpoch());
			assertTrue(hit.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("impactContextEpoch: CHANGED_BEFORE_FINALIZATION")));
		}
	}

	@Test
	public void anImpactReportedOnAnotherPlaneCannotBorrowCurrentPlaneAttackers()
	{
		animation = 123;
		position = new WorldPoint(3200, 3200, 1);
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals(7, capturedLastDamage().getAmount());
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertTrue(capturedLastDamage().getCaptureIssues().contains("impactPositionScope: WORLD_POINT_PLANE_MISMATCH"));
	}

	@Test
	public void primaryObjectCallbacksKeepTheirIdentityThroughInitialAndReloadBaselines() throws Exception
	{
		net.runelite.api.GroundObject[] object = new net.runelite.api.GroundObject[1];
		net.runelite.api.Tile tile = stub(net.runelite.api.Tile.class,
			(n, a) -> n.equals("getGroundObject") ? object[0]
				: n.equals("getLocalLocation") ? localPosition
					: n.equals("getWorldLocation") ? position : n.equals("getPlane") ? position.getPlane() : null);
		// One native grid and an actual scoped tile distinguish stable membership from a reload.
		net.runelite.api.Tile[][][] nativeTiles = new net.runelite.api.Tile[1][104][104];
		nativeTiles[0][localPosition.getSceneX()][localPosition.getSceneY()] = tile;
		hazardScene = stub(net.runelite.api.Scene.class, (n, a) -> n.equals("getTiles") ? nativeTiles : null);
		java.lang.reflect.Method callback = TickReplayLoggerPlugin.class.getDeclaredMethod("observeObject",
			String.class, net.runelite.api.TileObject.class, boolean.class, net.runelite.api.Tile.class);
		callback.setAccessible(true);
		object[0] = stub(net.runelite.api.GroundObject.class,
			(n, a) -> n.equals("getId") ? 999901
				: n.equals("getLocalLocation") ? localPosition
					: n.equals("getWorldLocation") ? position
						: n.equals("getWorldView") ? cueView : n.equals("getPlane") ? position.getPlane() : null);
		callback.invoke(plugin, "groundObject", object[0], true, tile);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent first = export().getEvents().getObservations().stream()
			.filter(o -> o.getType().equals("OBJECT_SPAWNED")).findFirst().get();
		com.tickreplaylogger.model.world.ObservedObjectState observed = (com.tickreplaylogger.model.world.ObservedObjectState) first
			.getData().get("object");
		assertEquals(world.snapshotAtTick(1).getSceneKey(), first.getSceneKey());
		assertEquals(observed.getObjectKey(), world.snapshotAtTick(1).getObservedObjects().get(0).getObjectKey());
		String priorScene = first.getSceneKey();
		long priorObject = observed.getObjectKey();
		GameStateChanged state = new GameStateChanged();
		state.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(state);
		state.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(state);
		object[0] = stub(net.runelite.api.GroundObject.class,
			(n, a) -> n.equals("getId") ? 999902
				: n.equals("getLocalLocation") ? localPosition
					: n.equals("getWorldLocation") ? position
						: n.equals("getWorldView") ? cueView : n.equals("getPlane") ? position.getPlane() : null);
		callback.invoke(plugin, "groundObject", object[0], true, tile);
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> callbacks = new ArrayList<>();
		for (com.tickreplaylogger.model.world.ObservedEvent event : export().getEvents().getObservations())
			if (event.getType().equals("OBJECT_SPAWNED"))
				callbacks.add(event);
		assertEquals(2, callbacks.size());
		com.tickreplaylogger.model.world.ObservedEvent next = callbacks.get(1);
		com.tickreplaylogger.model.world.ObservedObjectState nextObject = (com.tickreplaylogger.model.world.ObservedObjectState) next
			.getData().get("object");
		assertNotEquals(priorScene, next.getSceneKey());
		assertNotEquals(priorObject, nextObject.getObjectKey());
		assertEquals(world.snapshotAtTick(2).getSceneKey(), next.getSceneKey());
		assertEquals(1, world.snapshotAtTick(2).getObservedObjects().size());
		assertEquals(nextObject.getObjectKey(), world.snapshotAtTick(2).getObservedObjects().get(0).getObjectKey());
		assertEquals(999901, world.snapshotAtTick(1).getObservedObjects().get(0).getBaseId());
		assertEquals(999902, world.snapshotAtTick(2).getObservedObjects().get(0).getBaseId());
	}

	@Test
	public void firstNumericArraySamplesSurviveARepeatedGetterFailure() throws Exception
	{
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client client = (Client) field.get(plugin);
		rejectRepeatedClientArrayReads = true;
		clientVarps = new int[]
		{ 17, 0 };
		serverVarps = clientVarps;
		longVarps = new long[]
		{ Long.MAX_VALUE, Long.MIN_VALUE };
		serverLongVarps = longVarps;
		com.tickreplaylogger.model.world.WorldSnapshot sample = new WorldCaptureService().captureTick(client, 1, "s",
			Collections.emptyList(), null, true);
		clientVarps[0] = 99;
		longVarps[0] = 1;
		assertArrayEquals(new int[]
		{ 17, 0 }, sample.getObservedVarps());
		assertArrayEquals(new long[]
		{ Long.MAX_VALUE, Long.MIN_VALUE }, sample.getObservedNumericVariables().getVarpsLong());
		assertSame(sample.getObservedVarps(), sample.getObservedNumericVariables().getServerVarps());
		assertSame(sample.getObservedNumericVariables().getVarpsLong(),
			sample.getObservedNumericVariables().getServerVarpsLong());
		assertEquals("available", sample.getCaptureCoverage().get("variables"));
		assertEquals("available", sample.getCaptureCoverage().get("longVariables"));
		world.addSnapshot(sample);
		DeathRecordingV2 rich = export();
		assertArrayEquals(new int[]
		{ 17, 0 }, rich.getTicks().get(0).getVarpBaseline());
		assertArrayEquals(new String[]
		{ "9223372036854775807", "-9223372036854775808" }, rich.getTicks().get(0).getVarpLongBaseline());
	}

	@Test
	public void observedEmptyNumericArraysAreAvailableRatherThanMissing() throws Exception
	{
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client client = (Client) field.get(plugin);
		rejectRepeatedClientArrayReads = true;
		clientVarps = new int[0];
		longVarps = new long[0];
		com.tickreplaylogger.model.world.WorldSnapshot sample = new WorldCaptureService().captureTick(client, 1, "s",
			Collections.emptyList(), null, true);
		assertEquals("available", sample.getCaptureCoverage().get("variables"));
		assertEquals("available", sample.getCaptureCoverage().get("longVariables"));
		assertArrayEquals(new int[0], sample.getObservedVarps());
		assertArrayEquals(new long[0], sample.getObservedNumericVariables().getVarpsLong());
		world.addSnapshot(sample);
		DeathRecordingV2 rich = export();
		assertArrayEquals(new int[0], rich.getTicks().get(0).getVarpBaseline());
		assertArrayEquals(new String[0], rich.getTicks().get(0).getVarpLongBaseline());
	}

	@Test
	public void numericVariableChannelsRetainOnlyIntegersAndNeverRoundLongs() throws Exception
	{
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client client = (Client) field.get(plugin);
		WorldCaptureService capture = new WorldCaptureService();
		clientVariables = new java.util.HashMap<>();
		clientVariables.put(1, 7);
		clientVariables.put(2, 0);
		clientVariables.put(3, "Ignored client string");
		clientVariables.put(4, Boolean.TRUE);
		serverVarps = new int[]
		{ 0, 5 };
		longVarps = new long[]
		{ Long.MAX_VALUE, Long.MIN_VALUE };
		serverLongVarps = new long[]
		{ 0 };
		com.tickreplaylogger.model.world.WorldSnapshot first = capture.captureTick(client, 1, "s",
			Collections.emptyList(), null, true);
		assertEquals(2, first.getObservedNumericVariables().getClientIntegers().size());
		clientVariables.remove(1);
		clientVariables.put(2, 4);
		serverVarps[1] = 0;
		longVarps[0] = 9007199254740993L;
		com.tickreplaylogger.model.world.WorldSnapshot second = capture.captureTick(client, 2, "s",
			Collections.emptyList(), null, true);
		assertEquals(Long.MAX_VALUE, first.getObservedNumericVariables().getVarpsLong()[0]);
		assertEquals(Integer.valueOf(7), first.getObservedNumericVariables().getClientIntegers().get(1));
		world.addSnapshot(first);
		world.addSnapshot(second);
		DeathRecordingV2 rich = export();
		assertEquals("9223372036854775807", rich.getTicks().get(0).getVarpLongBaseline()[0]);
		assertEquals("9007199254740993", rich.getTicks().get(1).getVarpLongChanges().get(0));
		assertEquals(Collections.singletonList(1), rich.getTicks().get(1).getClientIntegerRemovals());
		assertEquals(Integer.valueOf(0), rich.getTicks().get(1).getServerVarpChanges().get(1));
		assertNull(rich.getTicks().get(0).getObservedNumericVariables());
		assertFalse(gson.toJson(rich).contains("Ignored client string"));
		java.nio.file.Path fixture = java.nio.file.Paths.get("build/test-fixtures/numeric-variables.json");
		java.nio.file.Files.createDirectories(fixture.getParent());
		java.nio.file.Files.write(fixture, gson.toJson(rich).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		DeathRecordingV2 selected = world.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().build(), 2, 2);
		assertEquals("9007199254740993", selected.getTicks().get(0).getVarpLongBaseline()[0]);
		assertEquals(Integer.valueOf(4), selected.getTicks().get(0).getClientIntegerBaseline().get(2));
	}

	@Test
	public void deathBeforeFatalHitsWaitsForTheirAttributionAndWorldSnapshot()
	{
		plugin.onActorDeath(new ActorDeath(player));
		plugin.onActorDeath(new ActorDeath(player));
		assertTrue(storedDeaths().isEmpty());
		hit(HitsplatID.DAMAGE_ME, 23);
		hit(HitsplatID.DAMAGE_ME, 51);
		animation = 123;
		hp = 0;
		tick();
		assertEquals(1, storedDeaths().size());
		TickRecording death = storedDeaths().get(0);
		assertTrue(death.isEventsBeforeSnapshot());
		assertEquals(74, death.getTotalDamageReceived());
		assertEquals("Unknown", death.getKillerName());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals(51, rich.getSummary().getFatalHit().getAmount());
		assertEquals(Integer.valueOf(1), rich.getMeta().getDeathTick());
		assertEquals(1, rich.getTicks().size());
		assertEquals(Integer.valueOf(0), rich.getTicks().get(0).getPlayer().getHp());
		assertEquals(2, rich.getEvents().getDamageEvents().size());
		for (DamageRecord damage : rich.getEvents().getDamageEvents())
		{
			assertEquals("Unknown", damage.getAttackerName());
			assertUnresolvedSource(damage);
			assertEquals("unknown", damage.getDamageType());
		}
		// Later callbacks must not mutate a recording already saved or shown in playback.
		world.addPlayerAction(2, "EAT", PlayerAction.ate("Shark").getDescription());
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals(2, death.getTicks().get(0).getDamageEvents().size());
		assertTrue(death.getTicks().get(0).getPlayerActions().isEmpty());
		assertEquals(1, storedDeaths().size());
	}

	@Test
	public void resolverV1OrdinaryProfileReachesActualAnimationHitIdleHandlersWithoutKnownAttackAnimation() throws Exception
	{
		useProjectileNativeView(); npcId = 2045; npcLevel = 90; npcObservedName = "Snakeling";
		npcReportsInteraction = true;
		npcObservedEffectiveId = 2045; npcObservedSize = 1;
		npcLocalPosition = new LocalPoint(6400,6272,net.runelite.api.WorldView.TOPLEVEL);
		tick(); // Establish a real native scene/lifetime before the first actor callback.
		int[] amounts = {13,20,1}; int[] types = {HitsplatID.DAMAGE_ME,HitsplatID.DAMAGE_ME,HitsplatID.VENOM};
		for (int i=0;i<amounts.length;i++)
		{
			animation = 777+i;
			net.runelite.api.events.AnimationChanged active = new net.runelite.api.events.AnimationChanged(); active.setActor(boss);
			plugin.onAnimationChanged(active); hp -= amounts[i]; hit(types[i],amounts[i]);
			if (i != 1)
			{
				animation = -1;
				net.runelite.api.events.AnimationChanged idle = new net.runelite.api.events.AnimationChanged(); idle.setActor(boss);
				plugin.onAnimationChanged(idle);
			}
			tick(); // The over-cap case deliberately retains the later active pose too.
		}
		DeathRecordingV2 recording = export();
		java.nio.file.Path output = java.nio.file.Paths.get("build/test-fixtures/resolver-v1-ordinary-handler.json");
		java.nio.file.Files.createDirectories(output.getParent());
		java.nio.file.Files.write(output,gson.toJson(recording).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(3,recording.getEvents().getDamageEvents().size());
		for (int i=0;i<3;i++)
		{
			DamageRecord hit = recording.getEvents().getDamageEvents().get(i);
			assertEquals(amounts[i],hit.getAmount()); assertEquals(types[i],hit.getHitsplatType());
			boolean retained = hit.getSourceResolution().getCandidates().stream().anyMatch(c -> Integer.valueOf(2045).equals(c.getNpcId()));
			assertEquals("ordinary13 retained;20 and typedvenom cannot be that direct component",i==0,retained);
			if (i==1) assertTrue(hit.getSourceResolution().getExcludedCandidates().stream().anyMatch(c -> c.getEvidence().stream().anyMatch(e -> e.startsWith("RESOLVER_V1:"))));
		}
		assertEquals(Integer.valueOf(65),recording.getTicks().get(recording.getTicks().size()-1).getPlayer().getHp());
	}

	@Test
	public void deferredResolutionPreservesImpactPrayerAndPosition()
	{
		prayer = Prayer.PROTECT_FROM_MAGIC;
		hit(HitsplatID.DAMAGE_ME, 9);
		position = new WorldPoint(3201, 3202, 0);
		prayer = Prayer.PROTECT_FROM_MELEE;
		animation = 123;
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals("PROTECT_FROM_MAGIC", damage.getOverheadAtImpact());
		assertEquals(3200, damage.getImpactX());
		assertEquals(3200, damage.getImpactY());
		assertEquals(3201, world.snapshotAtTick(1).getPlayer().getWorldX());
	}

	@Test
	public void aBlockAndDamageInOneBatchCannotConsumeOneProjectileByCallbackOrder()
	{
		projectile(npc("Archer", 2), 100);
		tick();
		hit(HitsplatID.BLOCK_ME, 0);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		List<DamageRecord> records = export().getEvents().getDamageEvents();
		assertEquals(2, records.size());
		assertEquals(0, records.get(0).getAmount());
		assertEquals(8, records.get(1).getAmount());
		for (DamageRecord record : records)
		{
			assertEquals("Unknown", record.getAttackerName());
			assertEquals("UNKNOWN", record.getDetermination());
		}
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertSharedProjectileRecords(capturedLastDamage(), records);
	}

	@Test
	public void hunllefPrayerDisableProjectilesRetainMagicStyleWithoutOrdinaryAttackTiming()
	{
		npcObservedName = "Corrupted Hunllef";
		npcId = 9036;
		npcLevel = 894;
		for (int id : new int[]
		{ 1713, 1714 })
		{
			projectile(boss, id);
			tick();
			hit(HitsplatID.DAMAGE_ME, 9);
			tick();
			assertEquals(originalProjectileMetadata(capturedLastDamage()), "magic", capturedLastDamage().getDamageType());
			assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
			assertNull(capturedLastDamage().getDeterminationRuleKey());
		}
	}

	@Test
	public void poisonDoesNotConsumeEitherOfTwoSameTickProjectiles()
	{
		tick();
		prayer = Prayer.PROTECT_FROM_MISSILES;
		projectile(npc("Archer A", 2), 100);
		projectile(npc("Archer B", 3), 101);
		tick();
		prayer = Prayer.PROTECT_FROM_MAGIC;
		hit(HitsplatID.POISON, 2);
		hit(HitsplatID.DAMAGE_ME, 20);
		hit(HitsplatID.DAMAGE_ME, 21);
		tick();
		List<DamageRecord> damage = export().getEvents().getDamageEvents();
		assertEquals(3, damage.size());
		assertEquals("Poison", damage.get(0).getAttackerName());
		assertEquals("UNKNOWN", damage.get(0).getDetermination());
		assertFalse(damage.get(0).isPvp());
		assertEquals("Unknown", damage.get(1).getAttackerName());
		assertEquals("Unknown", damage.get(2).getAttackerName());
		assertEquals("UNKNOWN", damage.get(1).getAttribution());
		assertEquals(2, damage.get(1).getAttributionCandidates().stream()
			.filter(c -> "PROJECTILE_OBSERVATION".equals(c.get("kind"))).count());
		assertEquals(3, damage.get(1).getImpactTick());
		assertEquals("PROTECT_FROM_MAGIC", damage.get(1).getOverheadAtImpact());
	}

	@Test
	public void knownNativeGroundItemCallbacksKeepIdentityThroughShortLivedQuantityChanges() throws Exception
	{
		net.runelite.api.TileItem item = stub(net.runelite.api.TileItem.class,
			(n, a) -> n.equals("getId") ? 995 : n.equals("getQuantity") ? 7 : null);
		net.runelite.api.Tile tile = stub(net.runelite.api.Tile.class, (n, a) -> n.equals("getLocalLocation")
			? new LocalPoint(6464, 6464)
			: n.equals("getWorldLocation") ? position : n.equals("getGroundItems") ? Collections.emptyList() : null);
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		tiles[0][50][50] = tile;
		net.runelite.api.Scene scene = stub(net.runelite.api.Scene.class,
			(n, a) -> n.equals("getTiles") ? tiles : null);
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return net.runelite.api.WorldView.TOPLEVEL;
			case "getPlane":
				return 0;
			case "getScene":
				return scene;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			case "getSizeX":
			case "getSizeY":
				return 104;
			default:
				return null;
			}
		});
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client original = (Client) field.get(plugin);
		Client scoped = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]
		{ Client.class }, (proxy, method,
			args) -> method.getName().equals("getTopLevelWorldView") || method.getName().equals("getWorldView") ? view
				: method.getName().equals("getScene") ? scene : method.invoke(original, args));
		inject("client", scoped);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(tile, item));
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(item, tile, 3, 7));
		plugin.onItemDespawned(new net.runelite.api.events.ItemDespawned(tile, item));
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(tile, item));
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> events = new ArrayList<>();
		for (com.tickreplaylogger.model.world.ObservedEvent event : export().getEvents().getObservations())
			if (event.getType().startsWith("GROUND_ITEM_"))
				events.add(event);
		assertEquals(4, events.size());
		for (com.tickreplaylogger.model.world.ObservedEvent event : events)
		{
			assertNotNull(gson.toJson(event), event.getSceneKey());
			assertEquals(true, event.getData().get("scopeKnown"));
		}
		com.tickreplaylogger.model.world.GroundItemState first = (com.tickreplaylogger.model.world.GroundItemState) events
			.get(0).getData().get("item"),
			changed = (com.tickreplaylogger.model.world.GroundItemState) events.get(1).getData().get("item"),
			renewed = (com.tickreplaylogger.model.world.GroundItemState) events.get(3).getData().get("item");
		assertEquals(first.getItemKey(), changed.getItemKey());
		assertNotEquals(first.getItemKey(), renewed.getItemKey());
		assertTrue(export().getTicks().get(0).getGroundItemChanges().isEmpty());
	}

	@Test
	public void shortLivedGroundItemsRetainLifecycleAndQuantitiesWithoutInventingNativeScope()
	{
		net.runelite.api.Tile tile = stub(net.runelite.api.Tile.class,
			(n, a) -> n.equals("getLocalLocation") ? new LocalPoint(6400, 6400)
				: n.equals("getWorldLocation") ? position : null);
		net.runelite.api.TileItem item = stub(net.runelite.api.TileItem.class,
			(n, a) -> n.equals("getId") ? 995 : n.equals("getQuantity") ? 7 : null);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(tile, item));
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(item, tile, 3, 7));
		plugin.onItemDespawned(new net.runelite.api.events.ItemDespawned(tile, item));
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> observations = export().getEvents().getObservations();
		assertEquals(3, observations.stream().filter(event -> event.getType().startsWith("GROUND_ITEM_")).count());
		for (com.tickreplaylogger.model.world.ObservedEvent event : observations)
			if (event.getType().startsWith("GROUND_ITEM_"))
			{
				assertNull(event.getSceneKey());
				assertEquals(false, event.getData().get("scopeKnown"));
				assertNotNull(event.getData().get("item"));
			}
		com.tickreplaylogger.model.world.ObservedEvent changed = observations.stream()
			.filter(event -> event.getType().equals("GROUND_ITEM_QUANTITY_CHANGED")).findFirst().get();
		assertEquals(3, changed.getData().get("oldQuantity"));
		assertEquals(7, changed.getData().get("newQuantity"));
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void newProjectileReferencesClassifyCorrelatedDamageWithoutInventingTimingOrEffects() throws Exception
	{
		int[][] cases =
		{
			{ 8615, 426, 1662 },
			{ 8619, 426, 1663 },
			{ 12077, 741, 2327 },
			{ 12079, 741, 2329 },
			{ 11278, 1001, 1997 },
			{ 11278, 1001, 1999 },
			{ 11280, 1001, 2000 },
			{ 11281, 1001, 2004 },
			{ 11281, 1001, 2007 } };
		for (int[] row : cases)
		{
			npcs.clear();
			setup();
			npcId = row[0];
			npcLevel = row[1];
			npcObservedName = row[0] == 8615 || row[0] == 8619 ? "Alchemical Hydra"
				: row[0] == 12077 || row[0] == 12079 ? "Phantom Muspah" : "Nex";
			projectile(boss, row[2]);
			tick();
			hit(HitsplatID.DAMAGE_ME, 7);
			tick();
			DamageRecord damage = capturedLastDamage();
			String expected = row[2] == 1663 || row[2] == 2329 || row[2] == 1999 ? "ranged" : "magic";
			assertEquals("projectile " + row[2], expected, damage.getDamageType());
			assertNotNull(damage.getDamageTypeRuleKey());
			assertNotNull(damage.getDamageTypeCatalogId());
			assertEquals("UNKNOWN", damage.getDetermination());
			assertNull(damage.getPrayerEffect());
			assertTrue(export().getEvents().getEffectEvents().isEmpty());
		}
	}

	@Test
	public void questAndSeasonalProjectilesCannotAcquirePostquestBaseRules() throws Exception
	{
		npcId = 12073;
		npcLevel = 180;
		projectile(boss, 2327);
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertNull(capturedLastDamage().getDamageTypeRuleKey());
		npcs.clear();
		setup();
		npcId = 12077;
		npcLevel = 741;
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		projectile(boss, 2327);
		tick();
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertNull(capturedLastDamage().getDamageTypeRuleKey());
	}

	@Test
	public void projectileDestinationsUseThePinnedTopLevelConstantAndDoNotInventUnknownFrames() throws Exception
	{
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return net.runelite.api.WorldView.TOPLEVEL;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			default:
				return null;
			}
		});
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client original = (Client) field.get(plugin);
		Client scoped = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]
		{ Client.class }, (proxy, method, args) -> method.getName().equals("getTopLevelWorldView") ? view
			: method.getName().equals("getWorldView") ? null : method.invoke(original, args));
		inject("client", scoped);
		for (int viewId : new int[]
		{ net.runelite.api.WorldView.TOPLEVEL, -1, 7 })
		{
			LocalPoint target = new LocalPoint(6464, 6464, viewId);
			Projectile projectile = stub(Projectile.class, (n, a) -> n.equals("getTarget") ? target : null);
			com.tickreplaylogger.model.world.ProjectileState observed = new com.tickreplaylogger.capture.ProjectileObservation(
				projectile, scoped, player, true, null, a -> null, null, 1, 1, 1, 500, "TICK_SAMPLE", null).getState();
			if (viewId == net.runelite.api.WorldView.TOPLEVEL)
			{
				assertEquals(Integer.valueOf(3200), observed.getEndWorldX());
				assertEquals(Integer.valueOf(3200), observed.getEndWorldY());
				assertEquals(Boolean.TRUE, observed.getDestinationKnown());
			}
			else
			{
				assertNull(observed.getEndWorldX());
				assertEquals(Boolean.FALSE, observed.getDestinationKnown());
			}
		}
	}

	@Test
	public void scopedProjectileReferenceFixtureKeepsCategorySeparateFromHits() throws Exception
	{
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		net.runelite.api.Scene scene = stub(net.runelite.api.Scene.class,
			(n, a) -> n.equals("getTiles") ? tiles : null);
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return net.runelite.api.WorldView.TOPLEVEL;
			case "getPlane":
				return 0;
			case "getScene":
				return scene;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			case "getSizeX":
			case "getSizeY":
				return 104;
			default:
				return null;
			}
		});
		// Local, top-level and source actors describe one current native view, not two view-zero pointers.
		cueView = view;
		projectileNativeView = null;
		hazardScene = scene;
		NPC source = stub(NPC.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return 12077;
			case "getComposition":
			case "getTransformedComposition":
				return npcDefinition("Phantom Muspah", 12077);
			case "getIndex":
				return 5;
			case "getCombatLevel":
				return 741;
			case "getName":
				return "Phantom Muspah";
			case "getWorldView":
				return view;
			case "getWorldLocation":
				return new WorldPoint(3201, 3201, 0);
			case "getLocalLocation":
				return new LocalPoint(6592, 6592, net.runelite.api.WorldView.TOPLEVEL);
			default:
				return null;
			}
		});
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client original = (Client) field.get(plugin);
		Client scoped = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]
		{ Client.class },
			(proxy, method, args) -> method.getName().equals("getTopLevelWorldView") ? view
				: method.getName().equals("getWorldView")
					? ((Integer) args[0]) == net.runelite.api.WorldView.TOPLEVEL ? view : null
					: method.getName().equals("getScene") ? scene : method.invoke(original, args));
		inject("client", scoped);
		for (int projectileId : new int[]
		{ 2327, 2329 })
		{
			Projectile projectile = stub(Projectile.class, (n, a) ->
			{
				switch (n)
				{
				case "getId":
					return projectileId;
				case "getSourceActor":
					return source;
				case "getTargetActor":
					return player;
				case "getRemainingCycles":
					return 30;
				case "getSourcePoint":
					return new WorldPoint(3201, 3201, 0);
				case "getTargetPoint":
					return position;
				case "getX":
				case "getY":
					return 6592D;
				default:
					return null;
				}
			});
			ProjectileMoved event = new ProjectileMoved();
			event.setProjectile(projectile);
			plugin.onProjectileMoved(event);
			tick();
		}
		DeathRecordingV2 exported = export();
		assertEquals(2, exported.getTicks().size());
		assertTrue(exported.getEvents().getDamageEvents().isEmpty());
		assertFixtureNativeScopesAreUnique(exported);
		for (com.tickreplaylogger.model.world.WorldSnapshot frame : exported.getTicks())
			for (com.tickreplaylogger.model.world.ProjectileState projectile : frame.getProjectiles())
			{
				assertEquals(projectile.getProjectileId() == 2327 ? "magic" : "ranged", projectile.getDamageType());
				assertEquals("muspah_postquest_projectile_styles", projectile.getDamageTypeRuleKey());
				assertEquals(Boolean.TRUE, projectile.getSourceNormalWorldContextObserved());
			}
		com.google.gson.JsonObject fixture = gson.toJsonTree(exported).getAsJsonObject();
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/projectile-styles.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void poisonColouredDamageDoesNotProveARecurringTickOrTwentyDamageCap()
	{
		enableEffects = true;
		poisonVarp = 0;
		hit(HitsplatID.POISON, 60);
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals(60, damage.getAmount());
		assertEquals("poison", damage.getDamageType());
		assertEquals("UNKNOWN", damage.getDamageRecurrence());
		assertEquals("HITSPLAT_TYPE_ONLY", damage.getDamageRecurrenceEvidence());
		assertTrue(export().getEvents().getEffectEvents().stream().noneMatch(effect -> "dot".equals(effect.getType())));
	}

	@Test
	public void aTypedHitBeforeAnOrdinaryHitCannotLeaveAUniqueProjectileLink()
	{
		assertMixedTypedProjectileBatch(true);
	}

	@Test
	public void aTypedHitAfterAnOrdinaryHitCannotLeaveAUniqueProjectileLink()
	{
		assertMixedTypedProjectileBatch(false);
	}

	private void assertMixedTypedProjectileBatch(boolean typedFirst)
	{
		npcId = 12204;
		npcLevel = 240;
		projectile(boss, 2444);
		tick();
		if (typedFirst)
			hit(HitsplatID.POISON, 60);
		hit(HitsplatID.DAMAGE_ME, 8);
		if (!typedFirst)
			hit(HitsplatID.POISON, 60);
		tick();
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(2, hits.size());
		DamageRecord ordinary = hits.get(typedFirst ? 1 : 0), typed = hits.get(typedFirst ? 0 : 1);
		assertEquals("Unknown", ordinary.getAttackerName());
		assertEquals("UNKNOWN", ordinary.getDetermination());
		assertSharedProjectileRecords(ordinary, hits);
		assertEquals(60, typed.getAmount());
		assertEquals("poison", typed.getDamageType());
		assertEquals("UNKNOWN", typed.getDamageRecurrence());
	}

	@Test
	public void aTypedHitWithoutAnOrdinaryHitStillPreventsFalseProjectileReuseNextTick()
	{
		npcId = 12204;
		npcLevel = 240;
		projectile(boss, 2444);
		tick();
		hit(HitsplatID.POISON, 60);
		tick();
		long projectileId = solePhysicalProjectile(capturedLastDamage());
		assertProjectileAlternative(capturedLastDamage(), projectileId);
		Long typedSequence = capturedLastDamage().getObservationSequence();
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals(projectileId, solePhysicalProjectile(capturedLastDamage()));
		List<DamageRecord> peers = export().getEvents().getDamageEvents();
		assertEquals(2, peers.size());
		assertEquals(typedSequence, peers.get(0).getObservationSequence());
		assertSharedProjectileRecords(capturedLastDamage(), peers);
	}

	@Test
	public void typedPlayerAndNpcHpObservationsDoNotEstablishRecurrence()
	{
		for (int type : new int[]
		{ HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.BLEED, HitsplatID.BURN })
		{
			hit(type, 4);
			hit(boss, type, 4);
			tick();
		}
		assertEquals(8, export().getEvents().getDamageEvents().size());
		for (DamageRecord damage : export().getEvents().getDamageEvents())
		{
			assertEquals("UNKNOWN", damage.getDamageRecurrence());
			assertEquals("HITSPLAT_TYPE_ONLY", damage.getDamageRecurrenceEvidence());
		}
	}

	@Test
	public void burnAndBleedKeepMechanismReductionAndPrayerEvidenceSeparate() throws Exception
	{
		enableEffects = true;
		prayer = Prayer.PROTECT_FROM_MISSILES;
		for (int type : new int[]
		{ HitsplatID.BURN, HitsplatID.BLEED })
		{
			hit(type, 3);
			hit(boss, type, 3);
			tick();
		}
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(4, hits.size());
		DamageRecord playerBurn = hits.get(0), npcBurn = hits.get(1), playerBleed = hits.get(2), npcBleed = hits.get(3);
		assertEquals("burn", playerBurn.getDamageType());
		assertEquals("burn", npcBurn.getDamageType());
		assertEquals("PROTECT_FROM_MISSILES_INEFFECTIVE_REVIEWED", playerBurn.getPrayerEffect());
		assertEquals("SOURCE_REFERENCE", playerBurn.getDamagePropertyEvidence());
		assertNull(playerBurn.getNpcDamageReductionStyle());
		assertEquals("ranged", npcBurn.getNpcDamageReductionStyle());
		assertNull(npcBurn.getPrayerEffect());
		assertEquals("SOURCE_REFERENCE", npcBurn.getDamagePropertyEvidence());
		for (DamageRecord damage : hits)
		{
			assertEquals("UNKNOWN", damage.getDamageRecurrence());
			assertNotNull(damage.getDamageReferenceKey());
		}
		for (DamageRecord damage : new DamageRecord[]
		{ playerBleed, npcBleed })
		{
			assertEquals("bleed", damage.getDamageType());
			assertNull(damage.getPrayerEffect());
			assertNull(damage.getNpcDamageReductionStyle());
			assertNull(damage.getDamagePropertyEvidence());
		}
		assertTrue(export().getEvents().getEffectEvents().stream().noneMatch(effect -> "dot".equals(effect.getType())));
		com.google.gson.JsonObject fixture = gson
			.toJsonTree(export().toBuilder().journalTicks(journal.getTickHistory()).build()).getAsJsonObject();
		for (com.google.gson.JsonElement frame : fixture.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : fixture.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path output = java.nio.file.Paths.get("build/test-fixtures/burn-bleed.json");
		java.nio.file.Files.createDirectories(output.getParent());
		java.nio.file.Files.write(output, gson.toJson(fixture).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void anOrdinaryRedHitDoesNotInheritBurnProperties()
	{
		hit(HitsplatID.DAMAGE_ME, 3);
		hit(boss, HitsplatID.DAMAGE_OTHER, 3);
		tick();
		for (DamageRecord damage : export().getEvents().getDamageEvents())
		{
			assertEquals("unknown", damage.getDamageType());
			assertNull(damage.getDamageReferenceKey());
			assertNull(damage.getNpcDamageReductionStyle());
			assertNull(damage.getPrayerEffect());
		}
	}

	@Test
	public void failedPrayerContextCannotEraseATypedHitsProjectileAlternative()
	{
		npcId = 12204;
		npcLevel = 240;
		projectile(boss, 2444);
		tick();
		failPrayerFlags = true;
		hit(HitsplatID.POISON, 60);
		failPrayerFlags = false;
		tick();
		DamageRecord typed = capturedLastDamage();
		assertEquals(Boolean.FALSE, typed.getImpactPrayerStateKnown());
		assertFalse(typed.getCaptureIssues().isEmpty());
		long projectileId = solePhysicalProjectile(typed);
		assertProjectileAlternative(typed, projectileId);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals(projectileId, solePhysicalProjectile(capturedLastDamage()));
		assertSharedProjectileRecords(capturedLastDamage(), export().getEvents().getDamageEvents());
	}

	@Test
	public void idleBossWithoutAHazardDoesNotProveAnEnvironmentalSource()
	{
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals("UNKNOWN", journal.getTickHistory().get(0).getDamageEvents().get(0).getDetermination());
	}

	@Test
	public void anAnimatingTeammateCannotBeBlamedForPvmDamage()
	{
		Player teammate = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Teammate"
				: name.equals("getAnimation") ? 401
					: name.equals("getInteracting") ? boss : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(teammate);
		hit(HitsplatID.DAMAGE_ME, 12);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertFalse(capturedLastDamage().isPvp());
	}

	@Test
	public void pvpMeleeNeedsBothAnAttackAnimationTargetAndObservedPvpContext()
	{
		useProjectileNativeView();
		Player opponent = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Opponent"
				: name.equals("getAnimation") ? 401
					: name.equals("getInteracting") ? player : name.equals("isInteracting") ? true
						: name.equals("getWorldView") ? projectileNativeView : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		pvpArea = 1;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Opponent", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().isPvp());
	}

	@Test
	public void quickPrayerDeactivationIsManualAndUnexplainedPrayerChangesAreNotNpcAttacks()
	{
		trackPrayers = true;
		prayer = Prayer.PROTECT_FROM_MELEE;
		extraPrayers.add(Prayer.PIETY);
		tick();
		click("Deactivate", "Quick-prayers", null, -1);
		prayer = null;
		extraPrayers.clear();
		tick();
		assertTrue(export().getEvents().getEffectEvents().isEmpty());
		prayer = Prayer.PROTECT_FROM_MELEE;
		tick();
		prayer = null;
		tick();
		assertEquals("prayer_state_change", export().getEvents().getEffectEvents().get(0).getType());
		assertFalse(export().getEvents().getEffectEvents().get(0).getMagnitude().contains("attack"));
	}

	@Test
	public void anonymousPvpCandidateStillCompetesWithAnNpcWhenOtherPlayerCaptureIsOff() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		pvpArea = 1;
		animation = 123;
		Player opponent = stub(Player.class,
			(n, a) -> n.equals("getName") ? "Private opponent"
				: n.equals("getAnimation") ? 401
					: n.equals("getInteracting") ? player : n.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Other player".equals(c.get("name")) && c.get("actorRef") == null));
		assertFalse(gson.toJson(capturedLastDamage()).contains("Private opponent"));
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.filter(c -> "PLAYER_ANIMATION_CORRELATION".equals(c.get("kind")))
			.allMatch(c -> c.get("animationId") == null));
	}

	@Test
	public void anonymousPlayerProjectileIsNotSilentlyExcludedFromNpcCompetition() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		pvpArea = 1;
		Player opponent = stub(Player.class, (n, a) -> n.equals("getName") ? "Private opponent" : null);
		projectile(opponent, 200);
		tick();
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Other player".equals(c.get("name")) && c.get("actorRef") == null));
		assertFalse(gson.toJson(capturedLastDamage()).contains("Private opponent"));
	}

	@Test
	public void unknownPlayerAnimationCompetesWithAContactGraphicInsteadOfExcludingThePlayer()
	{
		pvpArea = 1;
		npcs.clear();
		addBloodPool();
		Player opponent = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Opponent"
				: name.equals("getAnimation") ? 987654
					: name.equals("getInteracting") ? player : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Opponent".equals(candidate.getName())));
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Maiden blood pool".equals(candidate.getName())));
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(candidate -> "PLAYER_ACTIVITY_OBSERVATION".equals(candidate.get("kind"))
				&& Integer.valueOf(987654).equals(candidate.get("animationId"))));
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream()
			.anyMatch(candidate -> "Maiden blood pool".equals(candidate.get("name"))));
	}

	@Test
	public void unknownPlayerActivityAloneDoesNotBecomeAnEstablishedAttack()
	{
		pvpArea = 1;
		npcs.clear();
		Player opponent = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Opponent"
				: name.equals("getAnimation") ? 987654
					: name.equals("getInteracting") ? player : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(gson.toJson(capturedLastDamage()), capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Opponent".equals(candidate.getName())));
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(witness -> "PLAYER_ACTIVITY_OBSERVATION".equals(witness.get("kind"))
				&& Integer.valueOf(987654).equals(witness.get("animationId"))));
		assertNull(capturedLastDamage().getAttackStyleName());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals("unknown", capturedLastDamage().getDamageType());
	}

	@Test
	public void unknownPlayerActivityStillCompetesWhenOtherPlayerRecordingIsDisabled() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		pvpArea = 1;
		animation = 123;
		Player opponent = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Private opponent"
				: name.equals("getAnimation") ? 987654
					: name.equals("getInteracting") ? player : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		Map<String, Object> candidate = capturedLastDamage().getAttributionCandidates().stream()
			.filter(value -> "PLAYER_ACTIVITY_OBSERVATION".equals(value.get("kind"))).findFirst().get();
		assertEquals("Other player", candidate.get("name"));
		assertNull(candidate.get("actorRef"));
		assertNull(candidate.get("animationId"));
		assertFalse(gson.toJson(capturedLastDamage()).contains("Private opponent"));
	}

	@Test
	public void currentPlayerActionCannotBeMergedIntoItsOlderProjectileByActorIdentity()
	{
		pvpArea = 1;
		npcs.clear();
		int[] action =
		{ 401 };
		Player opponent = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Opponent"
				: name.equals("isInteracting") ? true
				: name.equals("getAnimation") ? action[0]
					: name.equals("getInteracting") ? player : name.equals("getWorldLocation") ? position : null);
		otherPlayers.add(opponent);
		projectile(opponent, 200);
		tick();
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Opponent", capturedLastDamage().getAttackerName());
		assertEquals("SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES", capturedLastDamage().getAttributionEvidence());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		action[0] = -1;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN",capturedLastDamage().getAttribution());
		assertEquals("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED",capturedLastDamage().getAttributionEvidence());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"Opponent".equals(candidate.getName()) && candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:1")
				&& candidate.getEvidence().contains("SHARED_OBSERVED_PROJECTILE_ASSIGNMENT")));
		assertTrue(capturedLastDamage().getAttributionCandidates().stream().anyMatch(witness ->
			"PROJECTILE_OBSERVATION".equals(witness.get("kind"))
				&& ((Number)witness.get("projectileObservationId")).longValue()==1L));
	}

	@Test
	public void simultaneousHazardAndProjectileStayAmbiguousWithoutConsumingTheProjectile()
	{
		npcId = 9036;
		npcLevel = 894;
		projectile(boss, 1712);
		tick();
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Test boss".equals(candidate.getName())));
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Maiden blood pool".equals(candidate.getName())));
		graphicsObjects.clear();
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED",capturedLastDamage().getAttributionEvidence());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"Test boss".equals(candidate.getName()) && candidate.getEvidence().contains("SHARED_OBSERVED_PROJECTILE_ASSIGNMENT")
				&& candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:1")));
		List<DamageRecord> impacts=export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).collect(java.util.stream.Collectors.toList());
		assertEquals(2,impacts.size());assertEquals(10,impacts.get(0).getAmount());assertEquals(8,impacts.get(1).getAmount());
		for(DamageRecord impact:impacts)assertTrue(impact.getAttributionCandidates().stream().anyMatch(witness ->
			"PROJECTILE_OBSERVATION".equals(witness.get("kind"))
				&& ((Number)witness.get("projectileObservationId")).longValue()==1L));
	}

	@Test
	public void deathWithoutAFatalHpCallbackDoesNotBlameAnOlderNonfatalHit()
	{
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertNull(rich.getSummary().getFatalHit());
		assertEquals("UNKNOWN", rich.getSummary().getKillerConfidence());
		assertEquals("NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK", rich.getSummary().getFatalHitBasis());
		assertEquals("Unknown", rich.getSummary().getKillerName());
	}

	@Test
	public void impactHazardSurvivesItsRemovalBeforeTheTickSettles()
	{
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 10);
		graphicsObjects.clear();
		animation = 123;
		tick();
		DamageRecord observed = capturedLastDamage();
		assertEquals("Maiden blood pool", observed.getAttackerName());
		assertEquals("ENVIRONMENTAL", observed.getAttribution());
		assertEquals("unknown", observed.getDamageType());
		assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
		assertTrue(observed.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"Maiden blood pool".equals(candidate.getName()) && "POSSIBLE".equals(candidate.getStrength())
				&& candidate.getEvidence().contains("COMPONENT_UNKNOWN")));
		assertTrue(observed.getObservedHazardContacts().stream().anyMatch(witness ->
			"GRAPHIC_SURFACE".equals(witness.get("kind")) && "Maiden blood pool".equals(witness.get("name"))
				&& "HITSPLAT_CALLBACK".equals(witness.get("observationStage"))
				&& "SERVER_REPORTED_TILE".equals(witness.get("defenderPositionEvidence"))
				&& Boolean.TRUE.equals(witness.get("damaging")) && !Boolean.TRUE.equals(witness.get("finished"))
				&& ((Number) witness.get("observedCycle")).intValue() == observed.getObservedCycle()));
	}

	@Test
	public void hazardArrivingAfterHitsplatIsCheckedAtTheImpactTile()
	{
		hit(HitsplatID.DAMAGE_ME, 10);
		addBloodPool();
		localPosition = new LocalPoint(6656, 6656, net.runelite.api.WorldView.TOPLEVEL);
		position = new WorldPoint(3202, 3202, 0);
		animation = 123;
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"Maiden blood pool".equals(candidate.getName()) && candidate.getEvidence().contains("SETTLED_REFERENCE_ONLY")
				&& candidate.getEvidence().contains("COMPONENT_UNKNOWN")));
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(capturedLastDamage().getObservedHazardContacts()==null || capturedLastDamage().getObservedHazardContacts().stream()
			.noneMatch(witness -> "GRAPHIC_SURFACE".equals(witness.get("kind"))));
		assertEquals(3200, capturedLastDamage().getImpactX());
	}

	@Test
	public void activeCorruptedFloorIsRecordedAndAttributedFromItsTransformedDefinition()
	{
		addGauntletFloor(36048);
		hit(HitsplatID.DAMAGE_ME, 19);
		tick();
		assertEquals("Corrupted Hunllef floor", capturedLastDamage().getAttackerName());
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals("ENVIRONMENTAL", capturedLastDamage().getAttribution());
		assertEquals("active", export().getTicks().get(0).getHazards().get(0).getState());
	}

	@Test
	public void warningFloorDoesNotReplaceAnObservedBossAttackAsTheDamageSource()
	{
		npcReportsInteraction = true;
		addGauntletFloor(36047);
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Test boss", capturedLastDamage().getAttackerName());
		assertEquals("warning", export().getTicks().get(0).getHazards().get(0).getState());
	}

	@Test
	public void aFailedObjectHazardReadCannotMakeTheCompetingNpcWinByExclusion()
	{
		addGauntletFloor(36048);
		animation = 123;
		floorDefinition = stub(net.runelite.api.ObjectComposition.class, (name, args) ->
		{
			throw new IllegalStateException("definition unavailable");
		});
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals(10, capturedLastDamage().getAmount());
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertTrue(
			capturedLastDamage().getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("impactHazards:")));
	}

	private void addGauntletFloor(int effectiveId)
	{
		net.runelite.api.WorldView surfaceView = projectileNativeView == null ? cueView : projectileNativeView;
		LocalPoint surfaceLocal = localPosition;
		WorldPoint surfaceWorld = position;
		int surfacePlane = position.getPlane();
		floorDefinition = stub(net.runelite.api.ObjectComposition.class, (name, args) ->
		{
			if (name.equals("getImpostorIds"))
				return new int[]
				{ 36046, 36047, 36048 };
			if (name.equals("getImpostor"))
				return stub(net.runelite.api.ObjectComposition.class, (n, a) -> n.equals("getId") ? effectiveId : null);
			return null;
		});
		net.runelite.api.GroundObject floor = stub(net.runelite.api.GroundObject.class,
			(name, args) -> name.equals("getId") ? 36046
				: name.equals("getLocalLocation") ? surfaceLocal
					: name.equals("getWorldLocation") ? surfaceWorld
						: name.equals("getWorldView") ? surfaceView : name.equals("getPlane") ? surfacePlane : null);
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		tiles[0][50][50] = stub(net.runelite.api.Tile.class,
			(name, args) -> name.equals("getGroundObject") ? floor
				: name.equals("getLocalLocation") ? surfaceLocal
					: name.equals("getWorldLocation") ? surfaceWorld : name.equals("getPlane") ? surfacePlane : null);
		hazardScene = stub(net.runelite.api.Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
	}

	@Test
	public void reviewedPersistentSurfacesSupplySpatialCandidatesWithoutInventingSubtypeOrEffects()
	{
		npcs.clear();
		int[] ids =
		{ 32000, 32744, 57283, 45755, 37990 };
		String[] definitions =
		{ "Acid pool", "Acidic miasma", "Acid blood", "Banana peel", "Strange pool" };
		String[] labels =
		{ "Vorkath acid pool", "Xarpus acidic miasma", "Doom acid blood", "Ba-Ba banana peel", "Kroy strange pool" };
		for (int i = 0; i < ids.length; i++)
		{
			final int surfaceId = ids[i];
			addStaticSurface(ids[i], definitions[i]);
			hit(HitsplatID.DAMAGE_ME, 5);
			tick();
			assertEquals("Surface " + ids[i], labels[i], capturedLastDamage().getAttackerName());
			assertEquals("unknown", capturedLastDamage().getDamageType());
			assertEquals("HAZARD_ON_IMPACT_TILE", capturedLastDamage().getAttributionEvidence());
			assertTrue(capturedLastDamage().getObservedHazardContacts().stream().anyMatch(
				w -> "OBJECT_SURFACE".equals(w.get("kind")) && Integer.valueOf(surfaceId).equals(w.get("objectId"))));
		}
	}

	@Test
	public void conditionalMixedSurfacesRetainSourceCandidatesWithoutAssumingImmunityIsAbsent()
	{
		npcs.clear();
		int[] ids = {33423, 33424};
		String[] definitions = {"Poisonous bile", "Sticky bile"};
		String[] sources = {"Maggot King poisonous bile", "Maggot King sticky bile"};
		for (int i = 0; i < ids.length; i++)
		{
			addStaticSurface(ids[i], definitions[i]);
			hit(HitsplatID.DAMAGE_ME, 4);
			tick();
			assertEquals("Unknown", capturedLastDamage().getAttackerName());
			final String expected = sources[i];
			assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().anyMatch(c -> expected.equals(c.getName())));
			assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
			assertEquals("unknown", capturedLastDamage().getDamageType());
		}
	}

	@Test
	public void anUnresolvedSurfaceConditionRemainsAnAlternativeToAnObservedNpcAttack()
	{
		addStaticSurface(50746, "Molten Sand");
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(c -> "Colosseum molten sand".equals(c.getName())));
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.anyMatch(c -> "Test boss".equals(c.getName())));
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream()
			.anyMatch(w -> "CONDITION_NOT_OBSERVED".equals(w.get("sourceEligibility"))));
	}

	@Test
	public void anActiveObjectFirstObservedAfterTheHitCannotBeTheSoleEstablishedSurface()
	{
		npcs.clear();
		addGauntletFloor(36048);
		net.runelite.api.Tile[][][] tiles = hazardScene.getTiles();
		net.runelite.api.Tile surfaceTile = tiles[0][50][50];
		tiles[0][50][50] = null;
		hit(HitsplatID.DAMAGE_ME, 8);
		tiles[0][50][50] = surfaceTile;
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertTrue("Later scene facts remain available without becoming an original contact",
			export().getTicks().get(0).getHazards().stream()
				.anyMatch(hazard -> "Corrupted Hunllef floor".equals(hazard.getMappedName())));
		assertTrue(capturedLastDamage().getObservedHazardContacts().isEmpty());
	}

	@Test
	public void aFloorChangingFromWarningToActiveAfterTheHitDoesNotEstablishItsImpactState()
	{
		npcs.clear();
		addGauntletFloor(36047);
		hit(HitsplatID.DAMAGE_ME, 8);
		floorDefinition = stub(net.runelite.api.ObjectComposition.class,
			(n, a) -> n.equals("getImpostorIds") ? new int[]
			{ 36046, 36047, 36048 }
				: n.equals("getImpostor")
					? stub(net.runelite.api.ObjectComposition.class, (m, b) -> m.equals("getId") ? 36048 : null)
					: null);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertEquals(Boolean.FALSE, capturedLastDamage().getObservedHazardContacts().get(0).get("damaging"));
		assertTrue(export().getTicks().get(0).getHazards().stream().anyMatch(hazard -> Boolean.TRUE.equals(hazard.getDamaging())));
	}

	private void addStaticSurface(int id, String name)
	{
		net.runelite.api.WorldView surfaceView = projectileNativeView == null ? cueView : projectileNativeView;
		LocalPoint surfaceLocal = localPosition;
		WorldPoint surfaceWorld = position;
		int surfacePlane = position.getPlane();
		floorDefinition = stub(net.runelite.api.ObjectComposition.class, (n, a) ->
		{
			if (n.equals("getId"))
				return id;
			if (n.equals("getName"))
				return name;
			if (n.equals("getSizeX") || n.equals("getSizeY"))
				return 1;
			return null;
		});
		net.runelite.api.GroundObject surface = stub(net.runelite.api.GroundObject.class,
			(n, a) -> n.equals("getId") ? id
				: n.equals("getLocalLocation") ? surfaceLocal
					: n.equals("getWorldLocation") ? surfaceWorld
						: n.equals("getWorldView") ? surfaceView : n.equals("getPlane") ? surfacePlane : null);
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		tiles[0][50][50] = stub(net.runelite.api.Tile.class,
			(n, a) -> n.equals("getGroundObject") ? surface
				: n.equals("getLocalLocation") ? surfaceLocal
					: n.equals("getWorldLocation") ? surfaceWorld : n.equals("getPlane") ? surfacePlane : null);
		hazardScene = stub(net.runelite.api.Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
	}

	@Test
	public void multiTileConditionalContactUsesObservedMembershipAndNeverTheBlueSibling()
	{
		useProjectileNativeView();
		npcs.clear();
		addObservedObjectFootprint(36199);
		tick();
		for (int tile : new int[] { 50, 51, 52 })
		{
			position = new WorldPoint(3150 + tile, 3150 + tile, 0);
			localPosition = new LocalPoint(tile * 128 + 64, tile * 128 + 64, projectileNativeView);
			hit(HitsplatID.DAMAGE_ME, 8);
			tick();
			boolean named = capturedLastDamage().getSourceResolution().getCandidates().stream()
				.anyMatch(candidate -> "Orange demonic symbol".equals(candidate.getName()));
			assertEquals("Recorded 49..51 footprint at player tile " + tile, tile <= 51, named);
			assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
			assertEquals(8, capturedLastDamage().getAmount());
		}
		position = new WorldPoint(3200, 3200, 0);
		localPosition = new LocalPoint(6464, 6464, projectileNativeView);
		addObservedObjectFootprint(36200);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream()
			.noneMatch(candidate -> "Orange demonic symbol".equals(candidate.getName())));
	}

	private void addObservedObjectFootprint(int id)
	{
		floorDefinition = stub(net.runelite.api.ObjectComposition.class, (name, args) -> name.equals("getId") ? id
			: name.equals("getName") ? "Demonic Symbol" : name.equals("getSizeX") || name.equals("getSizeY") ? 3 : null);
		net.runelite.api.GameObject object = stub(net.runelite.api.GameObject.class, (name, args) ->
		{
			if (name.equals("getId")) return id;
			if (name.equals("getWorldView")) return projectileNativeView;
			if (name.equals("getLocalLocation")) return new LocalPoint(6464, 6464, projectileNativeView);
			if (name.equals("getWorldLocation")) return new WorldPoint(3200, 3200, 0);
			if (name.equals("getSceneMinLocation")) return new net.runelite.api.Point(49, 49);
			if (name.equals("getSceneMaxLocation")) return new net.runelite.api.Point(51, 51);
			return null;
		});
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		for (int x = 49; x <= 51; x++)
			for (int y = 49; y <= 51; y++)
				tiles[0][x][y] = stub(net.runelite.api.Tile.class, (name, args) ->
					name.equals("getGameObjects") ? new net.runelite.api.GameObject[] { object } : null);
		hazardScene = stub(net.runelite.api.Scene.class, (name, args) -> name.equals("getTiles") ? tiles : null);
	}

	@Test
	public void aConditionalSurfaceAloneCannotEstablishDamageEligibility()
	{
		npcs.clear();
		addStaticSurface(50746, "Molten Sand");
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertEquals("conditional", export().getTicks().get(0).getHazards().get(0).getState());
		assertNull(export().getTicks().get(0).getHazards().get(0).getDamaging());
	}

	@Test
	public void conflictingStaticDefinitionDoesNotChooseTheCompetingNpcByExclusion()
	{
		for (int id : new int[]
		{ 32000, 33424 })
		{
			addStaticSurface(id, "Different observed object");
			animation = 123;
			hit(HitsplatID.DAMAGE_ME, 8);
			tick();
			assertEquals("Unknown", capturedLastDamage().getAttackerName());
			assertTrue(capturedLastDamage().getObservedHazardContacts().stream()
				.anyMatch(w -> "DEFINITION_NOT_QUALIFIED".equals(w.get("sourceEligibility"))));
		}
	}

	@Test
	public void sameDefinitionAtTheSameTileOnAReplacementObjectIsNotTwoStageAgreement()
	{
		npcs.clear();
		addStaticSurface(32000, "Acid pool");
		hit(HitsplatID.DAMAGE_ME, 5);
		net.runelite.api.GroundObject replacement = stub(net.runelite.api.GroundObject.class,
			(n, a) -> n.equals("getId") ? 32000 : n.equals("getLocalLocation") ? localPosition : null);
		hazardScene.getTiles()[0][50][50] = stub(net.runelite.api.Tile.class,
			(n, a) -> n.equals("getGroundObject") ? replacement : null);
		tick();
		assertEquals(gson.toJson(capturedLastDamage()), "Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		Map<String, Object> original = capturedLastDamage().getObservedHazardContacts().stream()
			.filter(contact -> "OBJECT_SURFACE".equals(contact.get("kind"))).findFirst().get();
		com.tickreplaylogger.model.world.HazardState replacementSample = world.snapshotAtTick(1).getHazards().get(0);
		assertNotNull(replacementSample.getObjectObservationId());
		long originalIdentity = ((Number) original.get("objectObservationId")).longValue();
		assertNotEquals(originalIdentity, replacementSample.getObjectObservationId().longValue());
		assertEquals("Independent counters deliberately collide in this fixture; they are not physical identity",
			originalIdentity, replacementSample.getObjectKey().longValue());
	}

	@Test
	public void sharedSurfaceIdentityDoesNotDependOnTheIndependentObjectCounter() throws Exception
	{
		npcs.clear();
		com.tickreplaylogger.capture.ObjectHazards.ObservationIdentities identities =
			(com.tickreplaylogger.capture.ObjectHazards.ObservationIdentities) remainingField(plugin, "surfaceObservationIds");
		identities.id(stub(GraphicsObject.class, (name, args) -> null)); // Offset only the shared namespace.
		addStaticSurface(32000, "Acid pool");
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals(gson.toJson(damage), "Vorkath acid pool", damage.getAttackerName());
		Map<String, Object> original = damage.getObservedHazardContacts().stream()
			.filter(contact -> "OBJECT_SURFACE".equals(contact.get("kind"))).findFirst().get();
		com.tickreplaylogger.model.world.HazardState sample = world.snapshotAtTick(1).getHazards().get(0);
		long identity = ((Number) original.get("objectObservationId")).longValue();
		assertEquals(identity, sample.getObjectObservationId().longValue());
		assertNotEquals(identity, sample.getObjectKey().longValue());
		assertEquals(5, damage.getAmount());
	}

	@Test
	public void surfaceExportKeepsQualifiedConditionalLateAndResourceObservations() throws Exception
	{
		npcs.clear();
		int[] ids =
		{ 32000, 32744, 57283, 45755, 33423, 37990 };
		String[] names =
		{ "Acid pool", "Acidic miasma", "Acid blood", "Banana peel", "Poisonous bile", "Strange pool" };
		for (int i = 0; i < ids.length; i++)
		{
			addStaticSurface(ids[i], names[i]);
			hp -= 5;
			hit(HitsplatID.DAMAGE_ME, 5);
			tick();
		}
		npcs.add(boss);
		animation = 123;
		addStaticSurface(50746, "Molten Sand");
		hp -= 8;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		npcs.clear();
		animation = -1;
		addGauntletFloor(36047);
		hp -= 8;
		hit(HitsplatID.DAMAGE_ME, 8);
		floorDefinition = stub(net.runelite.api.ObjectComposition.class,
			(n, a) -> n.equals("getImpostorIds") ? new int[]
			{ 36046, 36047, 36048 }
				: n.equals("getImpostor")
					? stub(net.runelite.api.ObjectComposition.class, (m, b) -> m.equals("getId") ? 36048 : null)
					: null);
		tick();
		addGauntletFloor(36048);
		net.runelite.api.Tile[][][] tiles = hazardScene.getTiles();
		net.runelite.api.Tile active = tiles[0][50][50];
		tiles[0][50][50] = null;
		hp -= 8;
		hit(HitsplatID.DAMAGE_ME, 8);
		tiles[0][50][50] = active;
		tick();
		addStaticSurface(33424, "Sticky bile");
		hit(HitsplatID.DISEASE, 3);
		tick();
		npcs.add(boss);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		int remaining = hp;
		hp = 0;
		hit(HitsplatID.POISON, remaining);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-surface-controls");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/surface-tile-controls.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(11, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals("Unknown", export().getEvents().getDamageEvents().stream()
			.filter(value -> value.getImpactTick() == 8).findFirst().get().getAttackerName());
		for (int i = 0; i < ids.length; i++)
		{
			com.tickreplaylogger.model.world.WorldSnapshot frame = world.snapshotAtTick(i + 1);
			assertEquals("Positive surface fixture needs settled object membership", 1,
				frame.getObservedObjects().size());
			assertEquals(Long.valueOf(frame.getObservedObjects().get(0).getObjectKey()),
				frame.getHazards().get(0).getObjectKey());
			assertEquals(frame.getWorldViewId(), frame.getObservedObjects().get(0).getWorldViewId());
		}
	}

	@Test
	public void soleInfoboxStyleIsACandidateRatherThanProofOfTheIndividualAttack()
	{
		npcObservedName = "Aberrant spectre";
		npcId = 2; // Bundled sole-style NPC: magic.
		npcLevel = 96;
		npcReportsInteraction = true;
		useProjectileNativeView();
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getDamageTypeCandidates().contains("magic"));
		assertEquals(DamageEvent.DamageType.UNKNOWN,
			journal.getTickHistory().get(0).getDamageEvents().get(0).getDamageType());
	}

	@Test
	public void sharedPlayerProjectileKeepsNamedAlternativeWithoutAssigningEitherHit()
	{
		pvpArea = 1;
		Player attacker = stub(Player.class, (name, args) -> "getName".equals(name) ? "Opponent" : null);
		projectile(attacker, 200);
		tick();
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		hit(HitsplatID.VENOM, 6);
		tick();
		List<DamageRecord> damage = export().getEvents().getDamageEvents();
		assertEquals(2,damage.size());
		assertEquals(10,damage.get(0).getAmount());
		assertEquals(HitsplatID.DAMAGE_ME,damage.get(0).getHitsplatType());
		assertEquals("Unknown",damage.get(0).getAttackerName());
		assertEquals("UNKNOWN",damage.get(0).getAttribution());
		assertEquals("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED",damage.get(0).getAttributionEvidence());
		assertTrue(damage.get(0).getSourceResolution().isUnidentifiedPossible());
		assertTrue(damage.get(0).getSourceResolution().getCandidates().stream().anyMatch(candidate -> "Opponent".equals(candidate.getName())
			&& candidate.getEvidence().contains("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED")));
		assertFalse(damage.get(0).isPvp());
		assertEquals("Venom", damage.get(1).getAttackerName());
		assertEquals(6,damage.get(1).getAmount());
		assertEquals(HitsplatID.VENOM,damage.get(1).getHitsplatType());
		assertEquals("venom",damage.get(1).getDamageType());
		assertEquals("HITSPLAT_TYPE",damage.get(1).getDamageTypeEvidence());
		assertFalse(damage.get(1).isPvp());
		assertEquals(3, damage.get(1).getLaunchTick());
	}

	@Test
	public void mixedPlayerProjectileAndTypedDamageKeepThePlayerAsAnUnlinkedCandidate()
	{
		pvpArea = 1;
		Player attacker = stub(Player.class, (name, args) -> "getName".equals(name) ? "Opponent" : null);
		projectile(attacker, 200);
		tick();
		hit(HitsplatID.DAMAGE_ME, 10);
		hit(HitsplatID.VENOM, 6);
		tick();
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		assertEquals(2, hits.size());
		assertEquals("Unknown", hits.get(0).getAttackerName());
		assertFalse(hits.get(0).isPvp());
		assertTrue(hits.get(0).getAttributionCandidates().stream()
			.anyMatch(candidate -> "Opponent".equals(candidate.get("name"))));
		assertEquals("Venom", hits.get(1).getAttackerName());
		assertEquals("UNKNOWN", hits.get(1).getDamageRecurrence());
	}

	private void addBloodPool()
	{
		addBloodPool(new LocalPoint(6400, 6400));
	}

	private void addBloodPool(LocalPoint location)
	{
		graphicsObjects.add(stub(GraphicsObject.class, (name, args) ->
		{
			if ("getId".equals(name))
				return 1579; // MAIDEN_LINGERING_BLOOD in bundled map.
			if ("getLocation".equals(name))
				return location;
			return null;
		}));
	}

	@Test
	public void hazardOccupancyUsesServerTileAheadOfRenderedMovement()
	{
		npcs.clear();
		position = new WorldPoint(3201, 3200, 0);
		addBloodPool(new LocalPoint(51 * 128, 50 * 128));
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream().anyMatch(contact ->
			"SERVER_REPORTED_TILE".equals(contact.get("defenderPositionEvidence"))
				&& Integer.valueOf(3201).equals(contact.get("contactWorldX"))
				&& Integer.valueOf(3200).equals(contact.get("contactWorldY"))));
		assertNotEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void aHazardOnlyOnRenderedPreviousTileDoesNotBecomeTheCause()
	{
		npcs.clear();
		position = new WorldPoint(3201, 3200, 0);
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void observedBloodGraphicWithoutAReviewedSubtypeKeepsDamageTypeUnknown()
	{
		npcs.clear();
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Maiden blood pool", capturedLastDamage().getAttackerName());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("HAZARD_ON_IMPACT_TILE", capturedLastDamage().getAttributionEvidence());
	}

	private NPC contactHazard(int id, WorldPoint tile, int size)
	{
		return contactHazard(id, tile, size, null);
	}

	private NPC contactHazard(int id, WorldPoint tile, int size, net.runelite.api.coords.WorldArea area)
	{
		net.runelite.api.NPCComposition definition = stub(net.runelite.api.NPCComposition.class,
			(name, args) -> name.equals("getId") ? id : name.equals("getSize") ? size : null);
		return stub(NPC.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return id;
			case "getIndex":
				return 77;
			case "getWorldLocation":
				return tile;
			case "getWorldArea":
				return area;
			case "getComposition":
			case "getTransformedComposition":
				return definition;
			case "getAnimation":
				return -1;
			default:
				return null;
			}
		});
	}

	@Test
	public void noninteractiveContactNpcsPreserveTheirObservedIdentityAfterLeavingTheFrame()
	{
		npcs.clear();
		npcs.add(contactHazard(9039, position, 1));
		hit(HitsplatID.DAMAGE_ME, 8);
		npcs.clear();
		tick();
		assertEquals("Corrupted tornado", capturedLastDamage().getAttackerName());
		assertEquals("ENVIRONMENTAL", capturedLastDamage().getAttribution());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"POSSIBLE".equals(candidate.getStrength()) && candidate.getEvidence().contains("COMPONENT_UNKNOWN")));
		java.util.Map<?, ?> witness = capturedLastDamage().getObservedHazardContacts().get(0);
		assertEquals(9039, witness.get("npcId"));
		assertNotNull(witness.get("actorRef"));
		assertEquals(3200, witness.get("worldX"));
		assertEquals("HITSPLAT_CALLBACK", witness.get("observationStage"));
		assertEquals("SERVER_REPORTED_TILE", witness.get("defenderPositionEvidence"));
		assertEquals("SAME_SERVER_TILE", witness.get("contactPredicate"));
		assertEquals(Boolean.TRUE, witness.get("damaging"));
		assertEquals(capturedLastDamage().getObservedCycle().intValue(), ((Number) witness.get("observedCycle")).intValue());
		assertTrue("The later frame separately records that the contact NPC left", export().getTicks().get(0).getNpcs().isEmpty());
	}

	@Test
	public void contactOnlyAtSettledResolutionDoesNotProveItExistedAtTheHitsplat()
	{
		npcs.clear();
		hit(HitsplatID.DAMAGE_ME, 8);
		npcs.add(contactHazard(9039, position, 1));
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertTrue("No original HP callback contact is invented", capturedLastDamage().getObservedHazardContacts().isEmpty());
		assertEquals(1, export().getTicks().get(0).getNpcs().size());
		assertEquals(Integer.valueOf(9039), export().getTicks().get(0).getNpcs().get(0).getNpcId());
	}

	@Test
	public void aZeroHpSplatCompetesForTheProjectileWithoutProvingAMiss()
	{
		npcId = 12204;
		npcLevel = 240;
		projectile(boss, 2444);
		tick();
		hit(HitsplatID.DAMAGE_ME, 0);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		List<DamageRecord> damage = export().getEvents().getDamageEvents();
		assertEquals(2, damage.size());
		assertEquals(0, damage.get(0).getAmount());
		assertEquals(HitsplatID.DAMAGE_ME, damage.get(0).getHitsplatType());
		assertEquals("Unknown", damage.get(0).getAttackerName());
		assertEquals("Unknown", damage.get(1).getAttackerName());
		assertSharedProjectileRecords(damage.get(0), damage);
		assertSharedProjectileRecords(damage.get(1), damage);
		assertEquals(8,
			journal.getTickHistory().get(1).getDamageEvents().stream().mapToInt(DamageEvent::getAmount).sum());
	}

	@Test
	public void equalNamesAndNpcSlotsCannotCorroborateContactAcrossDifferentActorLifetimes()
	{
		npcs.clear();
		npcs.add(contactHazard(9039, position, 1));
		hit(HitsplatID.DAMAGE_ME, 8);
		npcs.clear();
		npcs.add(contactHazard(9039, position, 1));
		tick();
		assertEquals("Corrupted tornado", capturedLastDamage().getAttackerName());
		assertEquals("ENVIRONMENTAL",capturedLastDamage().getAttribution());
		assertEquals("unknown",capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		assertEquals(1, capturedLastDamage().getObservedHazardContacts().size());
		Map<String,Object> original=capturedLastDamage().getObservedHazardContacts().get(0);
		assertEquals("HITSPLAT_CALLBACK",original.get("observationStage"));
		assertEquals("SERVER_REPORTED_TILE",original.get("defenderPositionEvidence"));
		assertEquals("SAME_SERVER_TILE",original.get("contactPredicate"));
		assertEquals(Boolean.TRUE,original.get("damaging"));
		assertEquals(capturedLastDamage().getObservedCycle().intValue(),((Number)original.get("observedCycle")).intValue());
		assertEquals(1,capturedLastDamage().getSourceResolution().getCandidates().size());
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().allMatch(candidate ->
			"POSSIBLE".equals(candidate.getStrength()) && original.get("actorRef").equals(candidate.getActorRef())
				&& candidate.getEvidence().contains("COMPONENT_UNKNOWN")
				&& candidate.getEvidence().stream().noneMatch(evidence -> evidence.contains("ORIGINAL_CONTACT_AGREEMENT"))));
		assertNotEquals(capturedLastDamage().getObservedHazardContacts().get(0).get("actorRef"),
			export().getTicks().get(0).getNpcs().get(0).getActorRef());
	}

	@Test
	public void rangedResourceProjectileCannotBecomeCertainSourceOfASeparateHpCallback()
	{
		assertResourceProjectileDoesNotProveHpCause(
			net.runelite.api.gameval.SpotanimID.VFX_VARDORVIS_HEAD_PROJECTILE_RANGED_01, true);
	}

	@Test
	public void magicResourceProjectileCannotBecomeCertainSourceOfASeparateHpCallback()
	{
		assertResourceProjectileDoesNotProveHpCause(
			net.runelite.api.gameval.SpotanimID.VFX_VARDORVIS_HEAD_PROJECTILE_MAGIC_01, true);
	}

	@Test
	public void resourceProjectileAndPrayerChangeWithoutHpCallbackNeverCreateHpDamage()
	{
		assertResourceProjectileDoesNotProveHpCause(
			net.runelite.api.gameval.SpotanimID.VFX_VARDORVIS_HEAD_PROJECTILE_RANGED_01, false);
	}

	@Test
	public void unreviewedProjectileFromTheSameResourceActorRemainsAPossibleSource()
	{
		assertResourceProjectileDoesNotProveHpCause(987654, true);
	}

	private void assertResourceProjectileDoesNotProveHpCause(int projectileId, boolean separateHp)
	{
		useProjectileNativeView();
		npcId = net.runelite.api.gameval.NpcID.VARDORVIS_HEAD_TENTACLE;
		npcLevel = 0;
		npcObservedSize = 1;
		animation = -1;
		boss = npc("Vardorvis' Head", 1);
		npcs.clear();
		npcs.add(boss);
		enableEffects = true;
		trackPrayers = true;
		prayer = Prayer.PROTECT_FROM_MELEE;
		headIcon = HeadIcon.MELEE;
		tick();
		int start = gameCycle, end = start + 30;
		Projectile shot = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId": return projectileId;
			case "getSourceActor": return boss;
			case "getTargetActor": return player;
			case "getTargetPoint": return new WorldPoint(3200, 3200, 0);
			case "getWorldView": return projectileNativeView;
			case "getFloor": return 0;
			case "getStartCycle": return start;
			case "getEndCycle": return end;
			case "getRemainingCycles": return end - gameCycle;
			default: return null;
			}
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		moved.setPosition(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, projectileNativeView));
		plugin.onProjectileMoved(moved);
		listedProjectiles = new ArrayList<>(Collections.singletonList(shot));
		tick();
		prayerPoints = 89;
		prayer = null;
		headIcon = null;
		plugin.onStatChanged(new StatChanged(Skill.PRAYER, 0, 99, 89));
		if (separateHp)
		{
			// Independent synthetic HP ground truth; the HP API callback has no source-actor token.
			hit(HitsplatID.DAMAGE_ME, 13);
			hp = 86;
			plugin.onStatChanged(new StatChanged(Skill.HITPOINTS, 0, 99, 86));
		}
		tick();
		DeathRecordingV2 recording = export();
		assertEquals(separateHp ? 1 : 0, recording.getEvents().getDamageEvents().size());
		com.tickreplaylogger.model.world.PlayerState settled = recording.getTicks()
			.get(recording.getTicks().size() - 1).getPlayer();
		assertEquals(Integer.valueOf(separateHp ? 86 : 99), settled.getHp());
		assertEquals(Integer.valueOf(89), settled.getPrayer());
		if (separateHp)
		{
			DamageRecord damage = recording.getEvents().getDamageEvents().get(0);
			assertEquals(13, damage.getAmount());
			if (projectileId == 987654)
			{
				assertEquals("Vardorvis' Head", damage.getAttackerName());
				assertEquals("ALTERNATIVES", damage.getSourceResolution().getStatus());
				assertTrue(damage.getSourceResolution().isUnidentifiedPossible());
				assertEquals(1, damage.getSourceResolution().getCandidates().size());
				assertEquals(Integer.valueOf(npcId), damage.getSourceResolution().getCandidates().get(0).getNpcId());
				assertNotNull(damage.getSourceResolution().getCandidates().get(0).getActorRef());
			}
			else
			{
				assertEquals("A qualified non-HP projectile cannot remain the displayed HP source", "Unknown", damage.getAttackerName());
				assertEquals("Unknown", com.tickreplaylogger.util.SourceResolutionLabels.label(damage.getSourceResolution()));
				assertEquals("UNIDENTIFIED", damage.getSourceResolution().getStatus());
				assertTrue(damage.getSourceResolution().getCandidates().isEmpty());
				assertTrue(damage.getSourceResolution().getExcludedCandidates().stream().anyMatch(candidate ->
					Integer.valueOf(npcId).equals(candidate.getNpcId()) && candidate.getActorRef() != null
						&& "Vardorvis' Head".equals(candidate.getName())
						&& candidate.getReasons().stream().anyMatch(reason -> reason.startsWith("HITSPLAT_TYPE_MISMATCH:"))));
				assertTrue("Original projectile association remains exported even when ineligible for HP",
					damage.getAttributionCandidates().stream().anyMatch(raw -> raw.get("projectileObservationId") != null));
				assertTrue("The original actual owner and reference remain raw evidence after exclusion",
					damage.getAttributionCandidates().stream().filter(raw -> "PROJECTILE_OBSERVATION".equals(raw.get("kind")))
						.anyMatch(raw -> raw.get("sourceHistory") instanceof com.tickreplaylogger.model.world.ProjectileSourceHistory
							&& ((com.tickreplaylogger.model.world.ProjectileSourceHistory) raw.get("sourceHistory")).firstActor() != null
							&& "Vardorvis' Head".equals(((com.tickreplaylogger.model.world.ProjectileSourceHistory) raw.get("sourceHistory"))
								.firstActor().getSourceObservedName())
							&& ((com.tickreplaylogger.model.world.ProjectileSourceHistory) raw.get("sourceHistory")).firstActor().getSourceRef() != null));
			}
		}
	}

	@Test
	public void largeActorOriginAndObservedCyclesRetainAQualifiedProjectileSource() throws Exception
	{
		NPC original = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]
		{ NPC.class }, (proxy, method, args) ->
		{
			if (method.getName().equals("getWorldArea"))
				return new net.runelite.api.coords.WorldArea(3200, 3199, 5, 5, 0);
			return method.invoke(original, args);
		});
		npcs.clear();
		npcs.add(boss);
		gameCycle = 4708;
		tick();
		Projectile shot = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 990003;
			case "getTargetActor":
				return player;
			case "getSourcePoint":
				return new WorldPoint(3202, 3199, 0);
			case "getRemainingCycles":
				return 35;
			case "getEndCycle":
				return 4773;
			default:
				return null;
			}
		});
		listedProjectiles = new ArrayList<>();
		listedProjectiles.add(shot);
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		plugin.onProjectileMoved(moved);
		gameCycle = 4750;
		hit(HitsplatID.DAMAGE_ME, 43);
		hp = 56;
		gameCycle = 4720;
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals(43, damage.getAmount());
		assertEquals("Test boss", damage.getAttackerName());
		assertEquals("PROXIMITY", damage.getAttribution());
		assertEquals("PROJECTILE_TIMING_CORRELATION", damage.getAttributionEvidence());
		assertNull(damage.getAttackerRef());
		assertEquals("unknown", damage.getDamageType());
		assertEquals("UNKNOWN", damage.getDetermination());
		Map<String,Object> witness=damage.getAttributionCandidates().stream()
			.filter(value -> "PROJECTILE_OBSERVATION".equals(value.get("kind"))).findFirst().get();
		com.tickreplaylogger.model.world.ProjectileState recorded=
			(com.tickreplaylogger.model.world.ProjectileState)witness.get("projectile");
		assertEquals(Integer.valueOf(4750),damage.getObservedCycle());
		assertEquals(Integer.valueOf(4773),recorded.getEndCycle());
		assertEquals(-23,damage.getObservedCycle()-recorded.getEndCycle());
		assertEquals("PROXIMITY",recorded.getSourceHistory().getFirstObservedSource().getSourceEvidence());
		assertNotNull(recorded.getSourceHistory().getFirstObservedSource().getProximityCandidate().getActorRef());
		assertNull("An observed nearby actor is not an API projectile owner",recorded.getSourceRef());
		assertTrue(damage.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:"+recorded.getObservationId())));
	}

	@Test
	public void uncataloguedNpcAnimationAndProjectileRemainCorrelationsWithoutInventingAnAttackType()
	{
		npcReportsInteraction = true;
		useProjectileNativeView();
		npcId = 990001;
		npcLevel = 101;
		npcObservedSize = 1;
		npcObservedName = "Uncatalogued NPC";
		npcLocalPosition = new LocalPoint(6464, 6336);
		animation = -1;
		tick();
		animation = 990002;
		npcAnimationFrame = 0;
		net.runelite.api.events.AnimationChanged changed = new net.runelite.api.events.AnimationChanged();
		changed.setActor(boss);
		plugin.onAnimationChanged(changed);
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		DamageRecord correlated = capturedLastDamage();
		assertEquals(7, correlated.getAmount());
		assertEquals("Uncatalogued NPC", correlated.getAttackerName());
		assertEquals("TIMED", correlated.getAttribution());
		assertEquals("NPC_ANIMATION_CORRELATION", correlated.getAttributionEvidence());
		assertEquals("unknown", correlated.getDamageType());
		assertEquals("UNKNOWN", correlated.getDetermination());
		assertNull(correlated.getDamageTypeRuleKey());
		assertNull(correlated.getDeterminationRuleKey());
		assertRecordedAnimationReceipt(correlated, 990002);
		com.tickreplaylogger.model.world.NpcState observed = world.snapshotAtTick(2).getNpcs().get(0);
		assertEquals(Integer.valueOf(990001), observed.getNpcId());
		assertEquals(Integer.valueOf(990002), observed.getAnimationId());
		assertNull("An unclassified animation change is not a known attack onset", observed.getAttackOnsetEvidence());
		assertNotEquals("ATTACKING", observed.getDerivedAction());
		animation = -1;
		projectile(boss, 990003);
		tick();
		com.tickreplaylogger.model.world.ProjectileState shot = world.snapshotAtTick(3).getProjectiles().get(0);
		assertEquals(Integer.valueOf(990003), shot.getProjectileId());
		assertEquals(Integer.valueOf(990001), shot.getSourceNpcId());
		assertEquals(Boolean.TRUE, shot.getTargetsPlayer());
		assertNotNull(shot.getSourceRef());
		assertNotNull(shot.getTargetRef());
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		DamageRecord projectileCorrelation = capturedLastDamage();
		assertEquals(5, projectileCorrelation.getAmount());
		assertEquals("Uncatalogued NPC", projectileCorrelation.getAttackerName());
		assertEquals("PROJECTILE_TIMING_CORRELATION", projectileCorrelation.getAttributionEvidence());
		assertEquals("unknown", projectileCorrelation.getDamageType());
		assertEquals("UNKNOWN", projectileCorrelation.getDetermination());
		assertEquals(12, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void animationLabelsCannotExcludeAnUncataloguedEngagedNpcOrInventItsAttackIdentity()
	{
		npcReportsInteraction = true;
		useProjectileNativeView();
		npcId = 990001;
		npcLevel = 101;
		npcObservedSize = 1;
		npcObservedName = "Uncatalogued NPC";
		npcLocalPosition = new LocalPoint(6464, 6336);
		animation = -1;
		tick();
		// Unknown, spawn, transition, death and reaction labels are presentation categories, not negative damage rules.
		for (int activity : new int[] { 990002, 1540, 4522, 7799, 8754, 10867, 19 })
		{
			animation = activity;
			npcAnimationFrame = 0;
			net.runelite.api.events.AnimationChanged changed = new net.runelite.api.events.AnimationChanged();
			changed.setActor(boss);
			plugin.onAnimationChanged(changed);
			hit(HitsplatID.DAMAGE_ME, 7);
			tick();
			DamageRecord observed = capturedLastDamage();
			assertEquals("animation " + activity, "Uncatalogued NPC", observed.getAttackerName());
			assertEquals(7, observed.getAmount());
			assertEquals("unknown", observed.getDamageType());
			assertEquals("UNKNOWN", observed.getDetermination());
			assertNull(observed.getDamageTypeRuleKey());
			assertNull(observed.getDeterminationRuleKey());
			assertRecordedAnimationReceipt(observed, activity);
			assertNotNull(observed.getSourceResolution());
			assertTrue(observed.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
				"Uncatalogued NPC".equals(candidate.getName()) && "POSSIBLE".equals(candidate.getStrength())));
		}
	}

	@Test
	public void uncataloguedActivityBeforeAHitIsNotLostWhenTheNpcIsIdleAtTheSettledSnapshot()
	{
		useProjectileNativeView();
		NPC original = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
			(proxy, method, arguments) -> method.getName().equals("isInteracting") ? true : method.invoke(original, arguments));
		npcs.clear(); npcs.add(boss);
		npcId = 990001; npcLevel = 101; npcObservedSize = 1; npcObservedName = "Uncatalogued NPC";
		npcLocalPosition = new LocalPoint(6464, 6336);
		animation = -1; tick();
		animation = 990002;
		net.runelite.api.events.AnimationChanged active = new net.runelite.api.events.AnimationChanged(); active.setActor(boss);
		plugin.onAnimationChanged(active);
		hit(HitsplatID.DAMAGE_ME, 7);
		animation = -1;
		net.runelite.api.events.AnimationChanged idle = new net.runelite.api.events.AnimationChanged(); idle.setActor(boss);
		plugin.onAnimationChanged(idle);
		tick();
		DamageRecord observed = capturedLastDamage(); assertEquals(7, observed.getAmount());
		assertEquals("unknown", observed.getDamageType()); assertEquals("UNKNOWN", observed.getDetermination());
		assertNotNull(observed.getSourceResolution()); assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", observed.getSourceResolution().getStatus());
		assertRecordedAnimationReceipt(observed, 990002);
		assertEquals(Integer.valueOf(-1), world.snapshotAtTick(2).getNpcs().get(0).getAnimationId());
		assertTrue(observed.getSourceResolution().getExcludedCandidates() == null || observed.getSourceResolution().getExcludedCandidates().isEmpty());
	}

	@Test
	public void sourceWideBoundsUseEachOriginalActivityCohortEvenAfterTheNpcBecomesIdle()
	{
		useProjectileNativeView();
		NPC original = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
			(proxy, method, arguments) -> method.getName().equals("isInteracting") ? true : method.invoke(original, arguments));
		npcs.clear(); npcs.add(boss);
		npcId = 5779; npcObservedEffectiveId = 5779; npcLevel = 230; npcObservedSize = 3; npcObservedName = "Giant Mole";
		animation = -1; tick();
		for (int scenario = 0; scenario < 6; scenario++)
		{
			npcLevel = scenario == 3 || scenario == 5 ? 999 : 230;
			animation = 990002;
			if (scenario == 2) failedNpcSnapshotReads.add("getCombatLevel");
			net.runelite.api.events.AnimationChanged active = new net.runelite.api.events.AnimationChanged(); active.setActor(boss);
			plugin.onAnimationChanged(active);
			failedNpcSnapshotReads.remove("getCombatLevel");
			npcLevel = 230;
			if (scenario == 5)
			{
				net.runelite.api.events.AnimationChanged another = new net.runelite.api.events.AnimationChanged(); another.setActor(boss);
				plugin.onAnimationChanged(another);
			}
			hit(HitsplatID.DAMAGE_ME, 22);
			if (scenario != 0 && scenario != 2)
			{
				animation = -1;
				if (scenario == 4) npcLevel = 999;
				net.runelite.api.events.AnimationChanged idle = new net.runelite.api.events.AnimationChanged(); idle.setActor(boss);
				plugin.onAnimationChanged(idle);
			}
			tick();
			DamageRecord observed = capturedLastDamage();
			com.tickreplaylogger.model.world.SourceResolution resolution = observed.getSourceResolution();
			boolean viableOriginal = scenario == 2 || scenario == 3 || scenario == 5;
			assertEquals("Original source facts in scenario " + scenario + ": " + originalActivityEvidence(observed), viableOriginal,
				resolution.getCandidates().stream().anyMatch(candidate -> "Giant Mole".equals(candidate.getName())));
			if (!viableOriginal)
				assertTrue(resolution.getExcludedCandidates().stream().flatMap(candidate -> candidate.getReasons().stream())
					.anyMatch(reason -> reason.startsWith("MAXIMUM_EXCEEDED:whole_actor_bound:giant_mole_direct_hp")));
			assertEquals(22, observed.getAmount()); assertTrue(resolution.isUnidentifiedPossible());
		}
	}

	private String originalActivityEvidence(DamageRecord hit)
	{
		Map<String, Object> facts = new java.util.LinkedHashMap<>();
		facts.put("observations", world.observationsForSourceResolution().stream()
			.filter(event -> event.getTick() <= hit.getImpactTick()).filter(event -> "ANIMATION_CHANGED".equals(event.getType())
				|| "OVERHEAD_TEXT_CHANGED".equals(event.getType())).collect(java.util.stream.Collectors.toList()));
		facts.put("originalWitnesses", hit.getAttributionCandidates());
		facts.put("resolution", hit.getSourceResolution());
		return gson.toJson(facts);
	}

	@Test
	public void bodyAssociationUsesOnlyActualCurrentApiOccupiedBoundsAtCenterEdgeAndOutside()
	{
		useProjectileNativeView();
		npcId = 9035; npcLevel = 894; npcObservedSize = 5; npcObservedEffectiveId = 9035;
		npcObservedName = "Corrupted Hunllef"; npcLocalPosition = new LocalPoint(6464, 6464, projectileNativeView);
		final boolean[] areaAvailable = { true };
		NPC original = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class }, (proxy, method, arguments) ->
		{
			if (method.getName().equals("getWorldArea"))
				return areaAvailable[0] ? new net.runelite.api.coords.WorldArea(3200, 3200, 5, 5, 0) : null;
			if (method.getName().equals("getWorldLocation")) return new WorldPoint(3200, 3200, 0);
			return method.invoke(original, arguments);
		});
		npcs.clear(); npcs.add(boss);
		animation = -1; tick();
		for (int x : new int[] { 3200, 3204, 3205 })
		{
			position = new WorldPoint(x, 3202, 0);
			localPosition = new LocalPoint((x - 3150) * 128 + 64, 52 * 128 + 64, projectileNativeView);
			hit(HitsplatID.DAMAGE_ME, 7); tick();
			DamageRecord observed = capturedLastDamage();
			boolean bodyHint = observed.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
				"Corrupted Hunllef".equals(candidate.getName()) && candidate.getEvidence().stream()
					.anyMatch(e -> e.startsWith("NPC_BODY_SOURCE_RULE:")));
			assertEquals("Actual inclusive occupied bounds at x=" + x, x <= 3204, bodyHint);
			assertEquals(7, observed.getAmount());
			if (bodyHint) assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
			else assertEquals("UNIDENTIFIED", observed.getSourceResolution().getStatus());
			assertEquals("unknown", observed.getDamageType()); assertEquals("UNKNOWN", observed.getDetermination());
		}
		areaAvailable[0] = false;
		position = new WorldPoint(3200, 3202, 0); localPosition = new LocalPoint(6464, 52 * 128 + 64, projectileNativeView);
		hit(HitsplatID.DAMAGE_ME, 7); tick();
		assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().noneMatch(candidate ->
			candidate.getEvidence().stream().anyMatch(e -> e.startsWith("NPC_BODY_SOURCE_RULE:"))));
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
	}

	@Test
	public void animationCallbacksRetainFacingAndFrameObservationsForUnclassifiedActors()
	{
		NPC actor = stub(NPC.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 999881;
			case "getAnimation":
				return 1234;
			case "getAnimationFrame":
				return 5;
			case "getPoseAnimation":
				return 4321;
			case "getPoseAnimationFrame":
				return 2;
			case "getOrientation":
				return 1024;
			case "getCurrentOrientation":
				return 384;
			default:
				return null;
			}
		});
		npcs.clear();
		npcs.add(actor);
		net.runelite.api.events.AnimationChanged event = new net.runelite.api.events.AnimationChanged();
		event.setActor(actor);
		plugin.onAnimationChanged(event);
		tick();
		java.util.Map<String, Object> data = export().getEvents().getObservations().stream()
			.filter(e -> e.getType().equals("ANIMATION_CHANGED")).findFirst().get().getData();
		assertEquals(1024, data.get("orientation"));
		assertEquals(384, data.get("currentOrientation"));
		assertEquals(5, data.get("animationFrame"));
		assertEquals(2, data.get("poseAnimationFrame"));
	}

	@Test
	public void playerAppearanceChangesAreObservedWithoutClaimingAnEquipmentExecution()
	{
		net.runelite.api.PlayerComposition composition = stub(net.runelite.api.PlayerComposition.class, (name, args) ->
		{
			switch (name)
			{
			case "getEquipmentIds":
				return new int[12];
			case "getTransformedNpcId":
			case "getEquipmentId":
				return -1;
			default:
				return null;
			}
		});
		player = stub(Player.class, (name, args) -> name.equals("getPlayerComposition") ? composition
			: name.equals("getName") ? "Test player"
				: name.equals("getWorldLocation") ? position : name.equals("getLocalLocation") ? localPosition : null);
		plugin.onPlayerChanged(new net.runelite.api.events.PlayerChanged(player));
		tick();
		java.util.Map<String, Object> data = export().getEvents().getObservations().stream()
			.filter(e -> e.getType().equals("PLAYER_CHANGED")).findFirst().get().getData();
		com.tickreplaylogger.model.world.PlayerAppearanceState appearance = (com.tickreplaylogger.model.world.PlayerAppearanceState) data
			.get("appearance");
		assertEquals("PLAYER_COMPOSITION_VISIBLE_SLOTS", appearance.getEvidence());
		assertFalse(appearance.getEquipmentSlotKnown()[12]);
	}

	@Test
	public void contactHazardsDoNotCreateHitsAndCompeteWithActiveFloors()
	{
		npcs.clear();
		npcs.add(contactHazard(9039, position, 1));
		tick();
		assertNull(capturedLastDamage());
		addGauntletFloor(36048);
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
	}

	@Test
	public void statusHitKeepsConcurrentContactEvidenceWithoutInventingItsInflictor()
	{
		npcs.clear();
		npcs.add(contactHazard(11802, position, 1));
		hit(HitsplatID.VENOM, 6);
		npcs.clear();
		tick();
		assertEquals("Venom", capturedLastDamage().getAttackerName());
		assertNotNull(capturedLastDamage().getObservedHazardContacts());
		assertEquals(1, capturedLastDamage().getObservedHazardContacts().size());
		assertEquals(11802, capturedLastDamage().getObservedHazardContacts().get(0).get("npcId"));
		Map<?, ?> original = (Map<?, ?>) capturedLastDamage().getObservedHazardContacts().get(0)
			.get("originalActorState");
		assertEquals(11802, original.get("npcId"));
		assertEquals(Boolean.FALSE, original.get("worldAreaKnown"));
		assertNull("A one-tile contact must not invent an API WorldArea sample", original.get("worldArea"));
	}

	@Test
	public void largeContactFootprintNeedsCoherentAreaAndIncludesFarInteriorTiles()
	{
		npcs.clear();
		WorldPoint anchor = new WorldPoint(3197, 3197, 0);
		npcs.add(contactHazard(8633, anchor, 5, new net.runelite.api.coords.WorldArea(3197, 3197, 5, 5, 0)));
		hit(HitsplatID.DAMAGE_ME, 2);
		tick();
		npcs.clear();
		assertEquals("The Mimic stomp", capturedLastDamage().getAttackerName());
		assertEquals("COHERENT_SERVER_FOOTPRINT",
			capturedLastDamage().getObservedHazardContacts().get(0).get("contactPredicate"));
		Map<?, ?> original = (Map<?, ?>) capturedLastDamage().getObservedHazardContacts().get(0)
			.get("originalActorState");
		assertEquals(8633, original.get("npcId"));
		assertEquals(3197, original.get("worldX"));
		assertEquals(Boolean.TRUE, original.get("worldAreaKnown"));
		assertEquals("ACTOR_GET_WORLD_AREA", original.get("worldAreaEvidence"));
		assertEquals(3201L, ((Map<?, ?>) original.get("worldArea")).get("maxX"));
		npcs.add(contactHazard(8633, anchor, 5, new net.runelite.api.coords.WorldArea(3196, 3197, 5, 5, 0)));
		hit(HitsplatID.DAMAGE_ME, 2);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		npcs.clear();
		npcs.add(contactHazard(8633, anchor, 5, null));
		hit(HitsplatID.DAMAGE_ME, 2);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		npcs.clear();
		npcs.add(contactHazard(8633, new WorldPoint(3195, 3195, 0), 5,
			new net.runelite.api.coords.WorldArea(3195, 3195, 5, 5, 0)));
		hit(HitsplatID.DAMAGE_ME, 2);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void contactRuleRequiresSameServerTileKnownOneTileSizeAndNormalMode()
	{
		npcs.clear();
		npcs.add(contactHazard(9039, new WorldPoint(3201, 3200, 0), 1));
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		npcs.clear();
		npcs.add(contactHazard(9039, position, 2));
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		npcs.clear();
		npcs.add(contactHazard(9039, position, 1));
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void knownPositionOutsideSceneDoesNotBorrowRenderedTileHazard()
	{
		npcs.clear();
		position = new WorldPoint(4000, 4000, 0);
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void logoutDiscardsQueuedHitsAndDeathBeforeNextSession()
	{
		hit(HitsplatID.DAMAGE_ME, 99);
		plugin.onActorDeath(new ActorDeath(player));
		gameState = GameState.LOGIN_SCREEN;
		GameStateChanged stateChanged = new GameStateChanged();
		stateChanged.setGameState(gameState);
		plugin.onGameStateChanged(stateChanged);
		gameState = GameState.LOGGED_IN;
		stateChanged.setGameState(gameState);
		plugin.onGameStateChanged(stateChanged);
		tick();
		assertNull(capturedLastDamage());
		assertTrue(storedDeaths().isEmpty());
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
	}

	@Test
	public void trailingBlockCannotReplaceTheKillingBlow()
	{
		hit(HitsplatID.POISON, 99);
		hit(HitsplatID.BLOCK_ME, 0);
		animation = 123;
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording death = storedDeaths().get(0);
		assertEquals("Poison", death.getKillerName());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals("Poison", rich.getSummary().getKillerName());
		assertEquals(99, rich.getSummary().getFatalHit().getAmount());
		assertEquals(2, rich.getEvents().getDamageEvents().size());
	}

	@Test
	public void unknownFatalSourceIsNotReplacedByAnOlderKnownHit()
	{
		hit(HitsplatID.POISON, 2);
		tick();
		npcs.clear();
		hit(HitsplatID.DAMAGE_ME, 99);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording death = storedDeaths().get(0);
		assertEquals("Unknown", death.getKillerName());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals("UNKNOWN", rich.getSummary().getKillerConfidence());
		assertNull(rich.getSummary().getReactable());
	}

	private void useColosseumCueView()
	{
		int[][][] chunks = new int[1][13][13];
		for (int[] row : chunks[0])
			java.util.Arrays.fill(row, -1);
		chunks[0][6][6] = (224 << 14) | (384 << 3); // Template region7216; actual native local tile50,50.
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		hazardScene = stub(net.runelite.api.Scene.class, (n, a) -> n.equals("isInstance") ? true
			: n.equals("getTiles") ? tiles : n.equals("getInstanceTemplateChunks") ? chunks : null);
		cueView = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			if (n.equals("getId"))
				return -1;
			if (n.equals("isInstance"))
				return true;
			if (n.equals("getScene"))
				return hazardScene;
			if (n.equals("getInstanceTemplateChunks"))
				return chunks;
			if (n.equals("getBaseX") || n.equals("getBaseY"))
				return 3150;
			if (n.equals("getSizeX") || n.equals("getSizeY"))
				return 104;
			return null;
		});
		assertNotNull(com.tickreplaylogger.capture.WorldViews.scope(cueView));
	}

	@Test
	public void actualSystemMessageHandlerRetainsCueBeforeTheExportWindowWithoutInventingWaveDamage() throws Exception
	{
		useColosseumCueView();
		npcs.clear();
		tick();
		assertNotNull(gson.toJson(export().getTicks().get(0)), export().getTicks().get(0).getContext());
		systemMessage(ChatMessageType.GAMEMESSAGE, "<col=ef1020>Wave: 5</col>", 10);
		tick();
		assertNotNull(export().getTicks().get(export().getTicks().size() - 1).getContext().getEncounterWaveCue());
		for (int i = 0; i < 5; i++)
			tick();
		world.resize(6);
		systemMessage(ChatMessageType.GAMEMESSAGE, "Wave 5 completed! Duration: 0:20", 20);
		tick();
		systemMessage(ChatMessageType.GAMEMESSAGE, "Sol Heredit jumps down from his seat to face you!", 30);
		tick();
		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(reload);
		tick();
		systemMessage(ChatMessageType.PUBLICCHAT, "Wave: 8", 40);
		systemMessage(ChatMessageType.SPAM, "Wave: 8", 40);
		systemMessage(ChatMessageType.GAMEMESSAGE, "Wave: 8", 50);
		tick();
		apiTickCount += 3;
		tick();
		// Final six frames are7..12: a pre-window cue, completion, Sol, reset, fresh cue, gap.
		com.tickreplaylogger.capture.CombatCatalog catalog = DamageMap.load(gson).catalog();
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId("synthetic-encounter-wave-cues")
			.snapshotTick(12).referenceCatalogId(catalog.id())
			.captureBuildId(com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(com.tickreplaylogger.capture.CaptureBuild.apiVersion())
			.runeLiteApiArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.clientArtifactSha256()).build();
		com.google.gson.JsonObject out = gson.toJsonTree(world.buildExport(meta, DeathRecordingV2.Summary.builder()
			.activity("colosseum").bossName("Fortis Colosseum").ticksCaptured(6).build())).getAsJsonObject();
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/encounter-wave-cues.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(6, out.getAsJsonArray("ticks").size());
		assertTrue(world.buildExport(meta, DeathRecordingV2.Summary.builder().build()).getEvents().getDamageEvents()
			.isEmpty());
		assertEquals("colosseum", export().getTicks().get(0).getContext().getActivity());
		assertEquals(2, export().getTicks().get(0).getContext().getEncounterWaveCue().getObservedTick());
	}

	@Test
	public void retainedWaveCueSurvivesTrimmingButNotUnavailableMessageContext() throws Exception
	{
		useColosseumCueView();
		npcs.clear();
		tick();
		systemMessage(ChatMessageType.GAMEMESSAGE, "Wave: 5", 10);
		tick();
		world.resize(3);
		for (int i = 0; i < 5; i++)
			tick();
		com.tickreplaylogger.model.world.EncounterWaveCue cue = export().getTicks().get(0).getContext()
			.getEncounterWaveCue();
		assertNotNull(cue);
		assertTrue(cue.getObservedTick() < export().getTicks().get(0).getTick());
		assertEquals(5, cue.getWaveNumber());
		failGameState = true;
		systemMessage(ChatMessageType.GAMEMESSAGE, "Wave 5 completed!", 20);
		failGameState = false;
		tick();
		assertNull(export().getTicks().get(export().getTicks().size() - 1).getContext().getEncounterWaveCue());
	}

	@Test
	public void factualRuntimeCaptureRetainsFormsDamageAndSeparateInternalReconstructionEvidence() throws Exception
	{
		npcId = 2042;
		npcLevel = 725;
		npcObservedSize = 5;
		npcObservedName = "Zulrah";
		animation = 5069;
		npcAnimationFrame = 2;
		tick();
		hit(HitsplatID.DAMAGE_ME, 9);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		tick();
		npcId = 2044;
		animation = 5071;
		npcAnimationFrame = 1;
		tick();
		DeathRecordingV2 recording = export();
		assertEquals(9, recording.getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, recording.getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, recording.getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		com.tickreplaylogger.model.world.NpcState first = recording.getTicks().get(0).getNpcs().get(0),
			last = recording.getTicks().get(2).getNpcs().get(0);
		assertEquals(Integer.valueOf(2042), first.getNpcId());
		assertEquals(Integer.valueOf(2044), last.getNpcId());
		assertEquals("Zulrah", last.getName());
		assertEquals(Integer.valueOf(5071), last.getAnimationId());
		assertEquals(Integer.valueOf(1), last.getAnimationFrame());
		for (com.tickreplaylogger.model.world.WorldSnapshot frame : recording.getTicks())
			for (com.tickreplaylogger.model.world.NpcState npc : frame.getNpcs())
			{
				com.google.gson.JsonObject raw = gson.toJsonTree(npc).getAsJsonObject();
				assertFalse(raw.has("supplementalReferenceCandidates"));
				assertTrue(raw.has("actionEvidence"));
				assertTrue(raw.has("animationId"));
				assertTrue(raw.has("animationFrame"));
			}
	}

	@Test
	public void bundledRuntimeCatalogContainsFactualLookupsWithoutTheResearchEncyclopedia() throws Exception
	{
		try (java.io.InputStream input = getClass().getResourceAsStream("/com/tickreplaylogger/combat_catalog.json"))
		{
			assertNotNull(input);
			com.google.gson.JsonObject root = gson.fromJson(
				new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			assertEquals(root.get("catalogId").getAsString(),
				root.getAsJsonObject("runtimeProjection").get("sourceCatalogId").getAsString());
			assertFalse(root.has("supplementalReferenceAnnotations"));
			assertFalse(root.has("attackReferences"));
			assertEquals(0, root.getAsJsonObject("knowledge").size());
			assertTrue(root.getAsJsonObject("npcDefinitionStates").has("8338"));
			assertEquals("Serpentine", root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides")
				.getAsJsonObject("2042").get("form").getAsString());
			assertEquals("Tanzanite", root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides")
				.getAsJsonObject("2044").get("form").getAsString());
			assertTrue(root.has("hitsplatRules"));
			assertTrue(root.has("contextBindings"));
			com.google.gson.JsonObject echo = root.getAsJsonObject("conditionalNpcIdentities").getAsJsonArray("499")
				.get(0).getAsJsonObject();
			assertTrue(echo.has("identityCondition"));
			assertFalse(echo.has("max_hit"));
			assertFalse(echo.has("attack_speed"));
			assertFalse(echo.has("mechanic_fields_raw"));
		}
	}

	private void tick()
	{
		gameCycle += 30;
		try
		{
			plugin.onGameTick(new GameTick());
		}
		finally
		{
			apiTickCount++;
		}
	}

	@Test
	public void aFreshSpawnBatchDoesNotProveThatItsNewProjectileCausedTheHit()
	{
		npcId = 9036;
		npcLevel = 894;
		// Fresh recording: projectile 1712, 40 cycles of travel, hitsplat in the spawn batch.
		projectile(npc("Corrupted Hunllef", 9035), 1712, 40);
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getAttribution());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals(1, export().getTicks().get(0).getProjectiles().size());
		assertEquals(DamageEvent.DamageType.UNKNOWN,
			journal.getTickHistory().get(0).getDamageEvents().get(0).getDamageType());
	}

	@Test
	public void twoLaunchBatchCandidatesStayAmbiguous()
	{
		npcId = 9036;
		npcLevel = 894;
		projectile(npc("Corrupted Hunllef", 9035), 1712, 40);
		projectile(npc("Another attacker", 4), 1708, 40);
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("unknown", capturedLastDamage().getDamageType());
	}

	@Test
	public void latestHunllefStompAndEnvironmentalSequenceUsesBundledRules()
	{
		npcObservedName = "Corrupted Hunllef";
		npcId = 9036;
		npcLevel = 894;
		animation = 8420; // Recorded on ticks 415-417: validated Hunllef stomp.
		hit(HitsplatID.DAMAGE_ME, 30);
		tick();
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals("Stomp", capturedLastDamage().getAttackStyleName());
		assertEquals("sources.json:hunllef", capturedLastDamage().getMapRef());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		animation = -1;
		addGauntletFloor(36048);
		hit(HitsplatID.DAMAGE_ME, 18);
		tick();
		assertEquals("Corrupted Hunllef floor", capturedLastDamage().getAttackerName());
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals("replay_bindings.json:hazard_damage_type", capturedLastDamage().getMapRef());
		hit(HitsplatID.DAMAGE_ME, 30);
		tick();
		assertEquals("typeless", capturedLastDamage().getDamageType());
		// A missing projectile cannot exclude an ordinary attack and prove the floor caused this hit.
		animation = 8419;
		hit(HitsplatID.DAMAGE_ME, 7);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording death = storedDeaths().get(0);
		DeathRecordingV2 rich = gson.fromJson(preparedJson(death), DeathRecordingV2.class);
		assertEquals("Unknown", rich.getSummary().getKillerName());
		assertEquals("unknown", rich.getSummary().getFatalHit().getDamageType());
		assertTwoSourcesAndUnavailableInventory(rich.getSummary().getFatalHit());
		assertNull(rich.getSummary().getReactable());
	}

	@Test
	public void currentMeleeAnimationDoesNotRelabelAnOwnedArrivingHit()
	{
		playerAnimation = 401; // HUMAN_BLUNT_POUND, also present in the fresh Hunllef trace.
		hit(boss, HitsplatID.DAMAGE_ME, 7);
		tick();
		DamageEvent damage = journal.getTickHistory().get(0).getDamageEvents().get(0);
		assertEquals(DamageEvent.DamageDirection.DEALT, damage.getDirection());
		assertEquals(DamageEvent.DamageType.UNKNOWN, damage.getDamageType());
	}

	@Test
	public void lindirFailureMessageAfterHitsplatLabelsActualDamageWithoutCombatTargeting()
	{
		pickpocketLindir();
		hit(HitsplatID.DAMAGE_ME, 5);
		pickpocketFailure();
		tick();
		assertEquals(originalInteractionFacts(capturedLastDamage()), "Lindir (pickpocket)", capturedLastDamage().getAttackerName());
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals("Pickpocket failure", capturedLastDamage().getAttackStyleName());
		assertEquals("attack_rules.json:pickpocket", capturedLastDamage().getMapRef());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals(DamageEvent.DamageType.TYPELESS,
			export().getJournalTicks().get(0).getDamageEvents().get(0).getDamageType());
	}

	@Test
	public void aPickpocketClickAloneDoesNotRelabelUnrelatedDamage()
	{
		pickpocketLindir();
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void avoidedPickpocketFailureDoesNotCreateDamageOrConsumeALaterHit()
	{
		pickpocketLindir();
		pickpocketFailure();
		tick();
		assertNull(capturedLastDamage());
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void pickpocketFailureDoesNotStealPoisonOrChooseAmongSimultaneousHits()
	{
		pickpocketLindir();
		pickpocketFailure();
		hit(HitsplatID.POISON, 5);
		tick();
		assertEquals("Poison", capturedLastDamage().getAttackerName());
		pickpocketFailure();
		hit(HitsplatID.DAMAGE_ME, 5);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
	}

	@Test
	public void npcStyleRemainsAReferenceCandidateWhenTheIndividualProjectileIsUnknown()
	{
		npcObservedName = "Sergeant Steelwill";
		npcId = 2217; // Sergeant Steelwill: magic, carried by the source actor, not guessed from name.
		projectile(boss, 999999);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getDamageTypeCandidates().contains("magic"));
		assertEquals(DamageEvent.DamageType.UNKNOWN,
			journal.getTickHistory().get(1).getDamageEvents().get(0).getDamageType());
	}

	@Test
	public void scopedTypelessRuleOverridesGenericProjectileAccuracyStyle()
	{
		npcObservedName = "Thermonuclear smoke devil";
		npcId = 499;
		npcLevel = 301;
		projectile(boss, 1712);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals("NONE_REVIEWED", capturedLastDamage().getPrayerEffect());
		assertEquals(DamageEvent.DamageType.TYPELESS,
			export().getJournalTicks().get(1).getDamageEvents().get(0).getDamageType());
	}

	@Test
	public void rockCakeInputIsOnlyAPossibleSourceAndDoesNotFabricateHits()
	{
		npcs.clear();
		selfItemInput(7509, "Guzzle", "Dwarven rock cake");
		tick();
		assertNull(capturedLastDamage());
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Dwarven rock cake", capturedLastDamage().getAttackerName());
		assertEquals("SELF_DAMAGE_INPUT_CORRELATION", capturedLastDamage().getAttributionEvidence());
		assertEquals("TIMED", capturedLastDamage().getAttribution());
	}

	@Test
	public void selfDamageAndAnEngagedEnemyStayAmbiguous()
	{
		selfItemInput(7509, "Guzzle", "Dwarven rock cake");
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("MULTIPLE_POSSIBLE_DAMAGE_SOURCES", capturedLastDamage().getAttributionEvidence());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
	}

	@Test
	public void poisonousFoodDoesNotImplyAnOngoingPoisonSource()
	{
		npcs.clear();
		selfItemInput(3146, "Eat", "Poison karambwan");
		hit(HitsplatID.POISON, 5);
		tick();
		assertEquals("Poison", capturedLastDamage().getAttackerName());
		assertEquals("ENVIRONMENTAL", capturedLastDamage().getAttribution());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Poison karambwan".equals(c.get("name"))));
		assertEquals("poison", capturedLastDamage().getDamageType());
	}

	@Test
	public void venomSacInputAndActualVenomHitAreQualifiedRatherThanInvented()
	{
		npcs.clear();
		selfItemInput(29784, "Eat", "Araxyte venom sac");
		tick();
		assertNull(capturedLastDamage());
		hit(HitsplatID.VENOM, 4);
		tick();
		assertEquals("Venom", capturedLastDamage().getAttackerName());
		assertEquals("ENVIRONMENTAL", capturedLastDamage().getAttribution());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Araxyte venom sac".equals(c.get("name"))));
		assertEquals("venom", capturedLastDamage().getDamageType());
	}

	@Test
	public void venomColouredDamageAndAnObservedCureRemainSeparateEvents()
	{
		enableEffects = true;
		poisonVarp = 1_000_000;
		tick();
		selfItemInput(29784, "Eat", "Araxyte venom sac");
		poisonVarp = 0;
		hit(HitsplatID.VENOM, 4);
		tick();
		assertEquals(4, capturedLastDamage().getAmount());
		assertEquals("venom", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDamageRecurrence());
		assertTrue(export().getEvents().getEffectEvents().stream().anyMatch(
			effect -> "poison_state_change".equals(effect.getType()) && effect.getMagnitude().contains("clear")));
		assertTrue(export().getEvents().getEffectEvents().stream().noneMatch(effect -> "dot".equals(effect.getType())));
	}

	@Test
	public void activePoisonAndVenomStatesCannotEraseTheObservedStatusCategory()
	{
		npcs.clear();
		poisonVarp = 50;
		selfItemInput(3146, "Eat", "Poison karambwan");
		hit(HitsplatID.POISON, 5);
		tick();
		assertEquals("Poison", capturedLastDamage().getAttackerName());
		assertEquals("STATUS_HITSPLAT", capturedLastDamage().getAttributionEvidence());
		assertRecordedStatusState(capturedLastDamage(), 50, true);
		poisonVarp = 1000000;
		selfItemInput(29784, "Eat", "Araxyte venom sac");
		hit(HitsplatID.VENOM, 4);
		tick();
		assertEquals("Venom", capturedLastDamage().getAttackerName());
		assertEquals("STATUS_HITSPLAT", capturedLastDamage().getAttributionEvidence());
		assertRecordedStatusState(capturedLastDamage(), 1000000, true);
	}

	@Test
	public void specialResourceCostCompetesWithEnemyRatherThanBeingCreditedToEnemy()
	{
		equipment = stub(ItemContainer.class,
			(n, a) -> n.equals("getItem")
				&& (Integer) a[0] == net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx() ? new Item(28997, 1)
					: null);
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		specialVarp = 750;
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 20);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Blood Infusion (Dual macuahuitl)".equals(c.get("name"))));
		assertEquals(20, capturedLastDamage().getAmount());
	}

	@Test
	public void anActiveVenomStateIsNotReportedAsAnActivePoisonCompetitor()
	{
		npcs.clear();
		poisonVarp = 1_000_000;
		selfItemInput(3146, "Eat", "Poison karambwan");
		hit(HitsplatID.POISON, 5);
		tick();
		assertEquals(5, capturedLastDamage().getAmount());
		assertEquals("poison", capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Poison karambwan".equals(c.get("name"))));
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.noneMatch(c -> "ACTIVE_POISON_STATE".equals(c.get("kind"))));
		assertEquals("UNKNOWN", capturedLastDamage().getDamageRecurrence());
	}

	@Test
	public void unavailableStatusStateRemainsACompetingPossibilityForTypedSelfDamage()
	{
		npcs.clear();
		failPoisonVarp = true;
		selfItemInput(3146, "Eat", "Poison karambwan");
		hit(HitsplatID.POISON, 5);
		tick();
		assertEquals(5, capturedLastDamage().getAmount());
		assertEquals("poison", capturedLastDamage().getDamageType());
		assertRecordedStatusState(capturedLastDamage(), null, false);
		assertTrue(capturedLastDamage().getAttributionCandidates().stream()
			.anyMatch(c -> "Poison karambwan".equals(c.get("name"))));
	}

	@Test
	public void resumingEffectTrackingDoesNotLabelAnUnobservedEarlierChangeAsANewOnset()
	{
		enableEffects = true;
		poisonVarp = 0;
		tick();
		enableEffects = false;
		poisonVarp = 1_000_000;
		tick();
		tick();
		enableEffects = true;
		tick();
		assertTrue(export().getEvents().getEffectEvents().stream().noneMatch(effect -> "dot".equals(effect.getType())));
		poisonVarp = 0;
		tick();
		com.tickreplaylogger.model.world.EffectEvent cleared = export().getEvents().getEffectEvents().stream()
			.filter(effect -> "poison_state_change".equals(effect.getType())).findFirst().get();
		assertEquals(Integer.valueOf(0), cleared.getObservedRawValue());
		assertEquals(Integer.valueOf(1_000_000), cleared.getPreviousRawValue());
		assertEquals("poison_venom_client_state", cleared.getReferenceRuleKey());
		assertNotNull(cleared.getReferenceCatalogId());
	}

	private void selfItemInput(int id, String option, String name)
	{
		MenuEntry entry = stub(MenuEntry.class, (method, args) -> method.equals("getItemId") ? id
			: method.equals("getOption") ? option : method.equals("getTarget") ? name : null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	@Test
	public void matchingBaseLevelOnASeasonalWorldCannotApplyNormalThermyRules()
	{
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		npcId = 499;
		npcLevel = 301;
		projectile(boss, 1712);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertNull(capturedLastDamage().getPrayerEffect());
		assertNotEquals("typeless", capturedLastDamage().getDamageType());
	}

	@Test
	public void sceneChangeBeforeDeferredAttributionCannotBorrowTheNewSceneAttacker()
	{
		hit(HitsplatID.DAMAGE_ME, 6);
		hazardScene = stub(net.runelite.api.Scene.class, (n, a) -> null);
		animation = 123;
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertEquals(6, capturedLastDamage().getAmount());
	}

	@Test
	public void competingNpcAnimationsRemainAmbiguousInEitherListOrder()
	{
		NPC other = npc("Second attacker", 2);
		npcs.add(other);
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
		Collections.reverse(npcs);
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
	}

	@Test
	public void activeFloorAndRecognizedStompBothRemainAlternatives()
	{
		npcId = 9036;
		npcLevel = 894;
		animation = 8420;
		addGauntletFloor(36048);
		hit(HitsplatID.DAMAGE_ME, 30);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
		assertEquals("MULTIPLE_POSSIBLE_DAMAGE_SOURCES", capturedLastDamage().getAttributionEvidence());
	}

	@Test
	public void overlappingDamagingSurfacesDoNotChooseTheFirstGraphicOrObject()
	{
		npcs.clear();
		addBloodPool();
		addGauntletFloor(36048);
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertNamedSourceAlternatives(capturedLastDamage(), "Maiden blood pool", "Corrupted Hunllef floor");
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream().anyMatch(contact ->
			"GRAPHIC_SURFACE".equals(contact.get("kind")) && contact.get("graphicId") instanceof Number
				&& ((Number) contact.get("graphicId")).intValue() == 1579
				&& contact.get("graphicObservationId") instanceof Number && ((Number) contact.get("graphicObservationId")).longValue() > 0));
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream().anyMatch(contact ->
			"OBJECT_SURFACE".equals(contact.get("kind")) && contact.get("objectId") instanceof Number
				&& ((Number) contact.get("objectId")).intValue() == 36048
				&& contact.get("objectObservationId") instanceof Number && ((Number) contact.get("objectObservationId")).longValue() > 0));
	}

	@Test
	public void aDirectAttackCanOverlapItsOwnOlderProjectileWithoutConsumingIt()
	{
		npcReportsInteraction = true;
		npcId = 9036;
		npcLevel = 894;
		projectile(boss, 1712);
		tick();
		animation = 8420;
		hit(HitsplatID.DAMAGE_ME, 30);
		tick();
		assertEquals("Test boss", capturedLastDamage().getAttackerName());
		assertEquals("SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES", capturedLastDamage().getAttributionEvidence());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
		animation = -1;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertEquals("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED", capturedLastDamage().getAttributionEvidence());
		assertEquals("UNKNOWN",capturedLastDamage().getAttribution());
		assertSharedProjectileRecords(capturedLastDamage(),export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).collect(java.util.stream.Collectors.toList()));
	}

	@Test
	public void aHistoricalAttackerIsNotAssignedANewUnexplainedHit()
	{
		npcReportsInteraction = true;
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("Test boss", capturedLastDamage().getAttackerName());
		npcs.clear();
		animation = -1;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
	}

	@Test
	public void unknownSameActorAnimationCompetesWithItsOlderWhispererProjectile()
	{
		npcReportsInteraction = true;
		npcId = 12204;
		npcLevel = 240;
		projectile(boss, 2444);
		tick();
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 20);
		tick();
		assertEquals("Test boss", capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES", capturedLastDamage().getAttributionEvidence());
		assertTwoSourcesAndUnavailableInventory(capturedLastDamage());
		animation = -1;
		hit(HitsplatID.DAMAGE_ME, 8);
		tick();
		assertEquals("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED", capturedLastDamage().getAttributionEvidence());
		assertEquals("Unknown",capturedLastDamage().getAttackerName());
		assertEquals("UNKNOWN",capturedLastDamage().getAttribution());
		assertSharedProjectileRecords(capturedLastDamage(),export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).collect(java.util.stream.Collectors.toList()));
	}

	@Test
	public void sampledSameIdAnimationFrameResetRestartsOnlyAnEstimatedCycle()
	{
		npcId = 2042;
		npcLevel = 725;
		tick();
		animation = 5069;
		npcAnimationFrame = 2;
		tick();
		npcAnimationFrame = 8;
		tick();
		npcAnimationFrame = 1;
		tick();
		com.tickreplaylogger.model.world.NpcState npc = export().getTicks().get(3).getNpcs().get(0);
		assertEquals("OBSERVED_ANIMATION_FRAME_DECREASE", npc.getAttackOnsetEvidence());
		assertEquals("ATTACKING", npc.getDerivedAction());
		assertEquals(Integer.valueOf(1), npc.getAnimationFrame());
	}

	@Test
	public void journalOnlyDeathCannotBorrowAPositiveHitFromAnEarlierTick() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPrayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		animation = 123;
		hp = 89;
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		animation = -1;
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertEquals(1, storedDeaths().size());
		assertEquals("Unknown", storedDeaths().get(0).getKillerName());
		assertEquals("NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK", storedDeaths().get(0).getFatalHitBasis());
		assertTrue(storedDeaths().get(0).getFatalHitCandidates().isEmpty());
	}

	@Test
	public void deathWithReceivedTrackingDisabledRetainsTheObservedHpImpact() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean trackDamageReceived()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		hit(HitsplatID.DAMAGE_ME, 99);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertNull(rich.getSummary().getFatalHit());
		assertEquals("UNRESOLVED_IMPACT_IN_DEATH_TICK", rich.getSummary().getFatalHitBasis());
		assertEquals("Unknown", rich.getSummary().getKillerName());
	}

	@Test
	public void multipleDeathTickHitsRetainTheirSelectionBasisAndAlternatives()
	{
		npcs.clear();
		hit(HitsplatID.DAMAGE_ME, 5);
		hit(HitsplatID.DAMAGE_ME, 9);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(2, rich.getSummary().getFatalHitCandidates().size());
		assertEquals(9, rich.getSummary().getFatalHit().getAmount());
		assertEquals("LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK", rich.getSummary().getFatalHitBasis());
		assertNull(rich.getSummary().getReactable());
	}

	@Test
	public void mixedDeathTickSourcesDoNotTurnTheLastCallbackIntoAConfidentKiller()
	{
		animation = 123;
		hit(HitsplatID.POISON, 5);
		hit(HitsplatID.DAMAGE_ME, 9);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording saved = storedDeaths().get(0);
		DeathRecordingV2 rich = gson.fromJson(preparedJson(saved), DeathRecordingV2.class);
		assertEquals("Unknown", rich.getSummary().getFatalHit().getAttackerName());
		assertTrue(rich.getSummary().getFatalHit().getAttributionCandidates().stream()
			.anyMatch(candidate -> "Test boss".equals(candidate.get("name"))));
		assertEquals("Unknown", rich.getSummary().getKillerName());
		assertEquals("Unknown", saved.getKillerName());
		assertEquals("UNKNOWN", rich.getSummary().getKillerConfidence());
		assertEquals("MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS", rich.getSummary().getKillerBasis());
		assertEquals(2, rich.getSummary().getFatalHitCandidates().size());
		assertEquals(14, saved.getTotalDamageReceived());
		assertEquals("LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK", saved.getFatalHitBasis());
	}

	@Test
	public void journalOnlyUnresolvedResourceImpactCannotBorrowAnEarlierKnownSource() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		hit(HitsplatID.POISON, 5);
		hit(HitsplatID.DISEASE, 4);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording saved = storedDeaths().get(0);
		DeathRecordingV2 minimal = gson.fromJson(preparedJson(saved), DeathRecordingV2.class);
		assertTrue(minimal.getTicks().isEmpty());
		assertTrue(minimal.getScenes().isEmpty());
		assertEquals(1, minimal.getEvents().getDamageEvents().size());
		assertEquals(HitsplatID.POISON, minimal.getEvents().getDamageEvents().get(0).getHitsplatType());
		assertTrue(minimal.getEvents().getObservations().stream().anyMatch(event ->
			"HITSPLAT".equals(event.getType()) && event.getData().get("hitsplatType") instanceof Number
				&& ((Number) event.getData().get("hitsplatType")).intValue() == HitsplatID.DISEASE
				&& event.getData().containsKey("attributionContext")));
		assertEquals("Unknown", saved.getKillerName());
		assertEquals("UNRESOLVED_IMPACT_IN_DEATH_TICK", saved.getFatalHitBasis());
		assertEquals(1, saved.getUnresolvedImpactObservationCount());
		assertEquals(4, saved.getUnresolvedImpactObservations().get(0).get("amount"));
		assertEquals(1, saved.getFatalHitCandidates().size());
		assertEquals(5, saved.getTotalDamageReceived());
	}

	@Test
	public void earlierUnresolvedResourceObservationsCannotDowngradeALaterUniqueStatusDeath() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		hit(HitsplatID.DISEASE, 4);
		tick();
		hit(HitsplatID.POISON, 99);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording saved = storedDeaths().get(0);
		assertEquals("Poison", saved.getKillerName());
		assertEquals(0, saved.getUnresolvedImpactObservationCount());
		assertTrue(saved.getUnresolvedImpactObservations().isEmpty());
		assertEquals("UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK", saved.getFatalHitBasis());
	}

	@Test
	public void pickpocketFailureAndActiveHazardCannotStealEachOthersHit()
	{
		pickpocketLindir();
		pickpocketFailure();
		addBloodPool();
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertNamedSourceAlternatives(capturedLastDamage(), "Lindir (pickpocket)", "Maiden blood pool");
		assertTrue(capturedLastDamage().getObservedHazardContacts().stream().anyMatch(contact ->
			"GRAPHIC_SURFACE".equals(contact.get("kind")) && contact.get("graphicId") instanceof Number
				&& ((Number) contact.get("graphicId")).intValue() == 1579
				&& contact.get("graphicObservationId") instanceof Number && ((Number) contact.get("graphicObservationId")).longValue() > 0));
	}

	@Test
	public void prayerEffectIsSeparateFromTheInfoboxDamageType()
	{
		npcObservedName = "Blood Moon";
		npcId = 13011;
		projectile(boss, 999999);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		// Moon infobox says typeless slash; encounter prose says melee-based accuracy.
		// The prayer rule supplies NONE, not an automatic damage-type conversion.
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getDamageTypeCandidates().contains("typeless"));
		assertEquals("NONE_REVIEWED", capturedLastDamage().getPrayerEffect());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
	}

	@Test
	public void activeKrakenAndTentacleKeepTypelessRulesWithoutClaimingImpactTiming()
	{
		for (int[] definition : new int[][]
		{
			{ 494, 291 },
			{ 5535, 112 } })
		{
			npcId = definition[0];
			npcLevel = definition[1];
			npcObservedName = definition[0] == 494 ? "Kraken" : "Enormous Tentacle";
			projectile(boss, 999999);
			tick();
			hit(HitsplatID.DAMAGE_ME, 2);
			tick();
			assertEquals("typeless", capturedLastDamage().getDamageType());
			assertEquals("SCOPED_DEFINITION_RULE", capturedLastDamage().getDamageTypeEvidence());
			assertEquals("NONE_REVIEWED", capturedLastDamage().getPrayerEffect());
			assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		}
	}

	@Test
	public void inactiveKrakenWhirlpoolsCannotSupplyAnActiveDamageOrPrayerRule()
	{
		for (int definition : new int[]
		{ 496, 5534 })
		{
			npcId = definition;
			npcLevel = 0;
			projectile(boss, 999999);
			tick();
			hit(HitsplatID.DAMAGE_ME, 2);
			tick();
			assertEquals("unknown", capturedLastDamage().getDamageType());
			assertNull(capturedLastDamage().getPrayerEffect());
			assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		}
	}

	@Test
	public void echoOverridesDoNotInheritNormalDamageRulesAndLaunchVariantEvidenceSurvives()
	{
		npcObservedName = "Thermonuclear smoke devil";
		npcId = 499;
		npcLevel = 602;
		projectile(boss, 1712);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertTrue(capturedLastDamage().getDamageTypeCandidates().contains("ranged"));
		assertNull(capturedLastDamage().getPrayerEffect());
		assertEquals(Integer.valueOf(602), capturedLastDamage().getAttackerCombatLevel());
		assertEquals(Integer.valueOf(602), world.snapshotAtTick(1).getProjectiles().get(0).getSourceCombatLevel());
		npcLevel = 301;
		projectile(boss, 1712);
		npcLevel = 602;
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("typeless", capturedLastDamage().getDamageType());
		assertEquals(Integer.valueOf(301), capturedLastDamage().getAttackerCombatLevel());
	}

	@Test
	public void vorkathOrdinaryProjectileKeepsScopedStyleWithoutInventingPrayerTiming()
	{
		npcId = 8061;
		npcLevel = 732;
		projectile(npc("Vorkath", 123), 1479);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("magic", capturedLastDamage().getDamageType());
		assertEquals("Vorkath", capturedLastDamage().getAttackerName());
		assertEquals("TIMED", capturedLastDamage().getAttribution());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
	}

	@Test
	public void liveExportContainsSelectedWorldAndEventsWithoutInventingADeath()
	{
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 5);
		tick();
		TickRecording.CapturedWindow selected = captureLiveWindow();
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		DeathRecordingV2 live = selected.getCompletion().get();
		assertNull(live.getMeta().getDeathTick());
		assertEquals(Boolean.TRUE, live.getMeta().getWorldKnown());
		assertEquals(Integer.valueOf(1), live.getMeta().getSnapshotTick());
		assertEquals(1, live.getTicks().size());
		assertFalse(live.getTicks().get(0).getNpcs().isEmpty());
		assertEquals(1, live.getEvents().getDamageEvents().size());
		assertEquals(5, live.getEvents().getDamageEvents().get(0).getAmount());
		assertEquals(5, live.getSummary().getTotalDamageTakenInBuffer());
		assertNull(live.getSummary().getFatalHit());
	}

	@Test
	public void copiedDeathKeepsInputActionsOutgoingHitsAndRawCallbackOrderWithoutChangingTheFatalHit() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}

			@Override
			public boolean captureEffects()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		click("Attack", "Test boss", boss, -1);
		click("Eat", "Shark", null, 385);
		click("Activate", "Protect from Magic", null, -1);
		animation = 123;
		hit(HitsplatID.DAMAGE_ME, 5);
		hit(boss, HitsplatID.DAMAGE_ME, 50);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		TickRecording death = storedDeaths().get(0);
		DeathRecordingV2 rich = gson.fromJson(com.tickreplaylogger.capture.RecordingExport.forViewer(death, gson),
			DeathRecordingV2.class);
		assertEquals("1.3", rich.getSchemaVersion());
		assertEquals(3, rich.getEvents().getPlayerActions().size());
		assertTrue(rich.getEvents().getPlayerActions().stream().allMatch(a -> "INPUT".equals(a.getObservationKind())));
		assertEquals("npc:1:vunknown:g1", rich.getEvents().getPlayerActions().get(0).getTargetRef());
		assertEquals(2, rich.getEvents().getDamageEvents().size());
		DamageRecord received = rich.getEvents().getDamageEvents().get(0);
		DamageRecord dealt = rich.getEvents().getDamageEvents().get(1);
		assertEquals("RECEIVED", received.getDirection());
		assertEquals("DEALT", dealt.getDirection());
		assertNull(dealt.getAttackerRef()); // Owned hitsplat does not identify a direct player strike.
		assertEquals("LOCAL_PLAYER", dealt.getOwnership());
		assertEquals("npc:1:vunknown:g1", dealt.getDefenderRef());
		assertEquals(Integer.valueOf(500), received.getObservedCycle());
		assertTrue(received.getObservationSequence() < dealt.getObservationSequence());
		assertEquals(5, rich.getSummary().getFatalHit().getAmount());
		assertEquals(2, rich.getJournalTicks().get(0).getDamageEvents().size());
		assertEquals(1, rich.getEvents().getDeaths().size());
		assertEquals("player", rich.getEvents().getDeaths().get(0).getActorRef());
		assertEquals(530, rich.getTicks().get(0).getGameCycle());
		assertEquals("PER_TICK_OBSERVED", rich.getMeta().getCaptureSettings().getProjectileMode());
		assertEquals(6, rich.getEvents().getObservations().size());
	}

	@Test
	public void aPotionClickDoesNotInventDamageAndAnActualHitIsNeverSkipped()
	{
		click("Drink", "Divine super combat potion(4)", null, 23685);
		tick();
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
		assertNull(capturedLastDamage());
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals(10, capturedLastDamage().getAmount());
		assertEquals(10, journal.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
		assertEquals(1,
			export().getEvents().getObservations().stream().filter(e -> "HITSPLAT".equals(e.getType())).count());
	}

	@Test
	public void prayerFlagsAndVisibleOverheadAreSeparateAndConflictsAreNotResolvedByEnumOrder()
	{
		prayer = Prayer.PROTECT_FROM_MISSILES;
		extraPrayers.add(Prayer.PROTECT_FROM_MAGIC);
		headIcon = HeadIcon.MAGIC;
		hit(HitsplatID.DAMAGE_ME, 3);
		tick();
		com.tickreplaylogger.model.world.PlayerState observed = world.snapshotAtTick(1).getPlayer();
		assertTrue(observed.isPrayerStateConflict());
		assertEquals(2, observed.getReportedActivePrayers().size());
		assertEquals(Collections.singletonList("PROTECT_FROM_MAGIC"), observed.getActivePrayers());
		assertEquals("MAGIC", observed.getOverheadIcon());
		assertEquals("ACTOR_ICON", observed.getOverheadPrayerEvidence());
		assertEquals("PROTECT_FROM_MAGIC", capturedLastDamage().getOverheadAtImpact());
		headIcon = null;
		tick();
		assertNull(world.snapshotAtTick(2).getPlayer().getOverheadPrayer());
		assertEquals("AMBIGUOUS", world.snapshotAtTick(2).getPlayer().getOverheadPrayerEvidence());
	}

	@Test
	public void outgoingProjectilesKeepTheirTargetAndSampledPositionAcrossTicks()
	{
		int[] remaining =
		{ 60 };
		double[] x =
		{ 6400 };
		Projectile projectile = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 1712;
			case "getSourceActor":
				return player;
			case "getTargetActor":
				return boss;
			case "getSourcePoint":
				return position;
			case "getTargetPoint":
				return boss.getWorldLocation();
			case "getRemainingCycles":
				return remaining[0];
			case "getX":
				return x[0];
			case "getY":
				return 6400D;
			default:
				return null;
			}
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(projectile);
		plugin.onProjectileMoved(moved);
		tick();
		com.tickreplaylogger.model.world.ProjectileState first = world.snapshotAtTick(1).getProjectiles().get(0);
		assertEquals("player", first.getSourceRef());
		assertEquals("npc:1:vunknown:g1", first.getTargetRef());
		assertEquals(Integer.valueOf(3199), first.getEndWorldY());
		assertFalse(first.getTargetsPlayer());
		remaining[0] = 30;
		x[0] = 6528;
		tick();
		com.tickreplaylogger.model.world.ProjectileState second = world.snapshotAtTick(2).getProjectiles().get(0);
		assertEquals(first.getObservationId(), second.getObservationId());
		assertEquals(Double.valueOf(6528), second.getLocalX());
		assertEquals("TICK_SAMPLE", second.getPositionEvidence());
		remaining[0] = 0;
		tick();
		assertTrue(world.snapshotAtTick(3).getProjectiles().isEmpty());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void inventoryAndUnclassifiedHitsplatsRemainRawFacts()
	{
		inventory = stub(ItemContainer.class,
			(name, args) -> "getItem".equals(name) && (int) args[0] == 0 ? new Item(385, 2) : null);
		hit(999999, 0); // Not a recognized damage/block type; still an observed callback.
		tick();
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
		assertEquals(1, export().getEvents().getObservations().size());
		assertEquals(385, world.snapshotAtTick(1).getPlayer().getInventoryItemIds()[0]);
		assertEquals(2, world.snapshotAtTick(1).getPlayer().getInventoryQuantities()[0]);
	}

	@Test
	public void groundTargetAndRetargetingUseObservedDestinationsRatherThanThePlayersTile()
	{
		WorldPoint[] destination =
		{ new WorldPoint(3201, 3202, 0) };
		Projectile projectile = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 99999;
			case "getSourceActor":
				return boss;
			case "getSourcePoint":
				return boss.getWorldLocation();
			case "getTargetPoint":
				return destination[0];
			case "getRemainingCycles":
				return 60;
			default:
				return null;
			}
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(projectile);
		plugin.onProjectileMoved(moved);
		tick();
		com.tickreplaylogger.model.world.ProjectileState first = world.snapshotAtTick(1).getProjectiles().get(0);
		assertEquals(Integer.valueOf(3201), first.getEndWorldX());
		assertEquals(Integer.valueOf(3202), first.getEndWorldY());
		assertNull(first.getTargetRef());
		assertEquals("FIRST_OBSERVED", first.getSpawnTickEvidence());
		destination[0] = new WorldPoint(3202, 3203, 0);
		moved.setPosition(new LocalPoint(6656, 6784));
		plugin.onProjectileMoved(moved);
		tick();
		com.tickreplaylogger.model.world.ProjectileState second = world.snapshotAtTick(2).getProjectiles().get(0);
		assertEquals(first.getObservationId(), second.getObservationId());
		assertEquals(Integer.valueOf(3202), second.getEndWorldX());
		assertEquals(Integer.valueOf(3203), second.getEndWorldY());
		assertEquals(1, export().getEvents().getObservations().stream()
			.filter(e -> "PROJECTILE_TARGET_CHANGED".equals(e.getType())).count());
	}

	@Test
	public void aConsumedDivinePotionLabelsTheActualHitWithExplicitCorrelationEvidence()
	{
		npcs.clear(); // Isolate the consumed item from competing combat sources.
		int[] itemId =
		{ 23685 };
		inventory = stub(ItemContainer.class,
			(name, args) -> "getItem".equals(name) && (int) args[0] == 0 ? new Item(itemId[0], 1) : null);
		click("Drink", "Divine super combat potion(4)", null, itemId[0]);
		itemId[0] = 23688;
		hit(HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals(1, export().getEvents().getDamageEvents().size());
		assertEquals(10, capturedLastDamage().getAmount());
		assertEquals(gson.toJson(capturedLastDamage()), "Divine super combat potion(4)", capturedLastDamage().getAttackerName());
		assertEquals("ITEM_CONSUMPTION_CORRELATION", capturedLastDamage().getAttributionEvidence());
	}

	@Test
	public void initialResourceAndVenomValuesAreBaselinesRatherThanFabricatedNewEvents() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}

			@Override
			public boolean trackPrayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		poisonVarp = 1000000;
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		tick();
		assertTrue(export().getEvents().getEffectEvents().isEmpty());
		assertTrue(export().getEvents().getPlayerActions().isEmpty());
		assertEquals(1000000, world.snapshotAtTick(1).getPlayer().getPoisonValue());
		assertTrue(export().getEvents().getObservations().stream()
			.anyMatch(e -> "SPECIAL_ENERGY_OBSERVED".equals(e.getType())));
		specialVarp = 500;
		poisonVarp = 0;
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		tick();
		assertEquals("RESOURCE_CHANGE", export().getEvents().getPlayerActions().get(0).getObservationKind());
		poisonVarp = 1000000;
		tick();
		assertEquals(2, export().getEvents().getEffectEvents().size());
		assertEquals("poison_state_change", export().getEvents().getEffectEvents().get(0).getType());
		assertEquals(3, export().getEvents().getEffectEvents().get(1).getStartTick());
	}

	@Test
	public void poisonVenomDowngradesAndCuresAreObservedWithoutInventingHpDamage() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}

			@Override
			public boolean trackPrayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		poisonVarp = -12;
		tick();
		assertTrue(export().getEvents().getEffectEvents().isEmpty());
		poisonVarp = 25;
		tick();
		poisonVarp = 1000000;
		tick();
		poisonVarp = 1000004;
		tick();
		poisonVarp = 4;
		tick();
		poisonVarp = 0;
		tick();
		List<com.tickreplaylogger.model.world.EffectEvent> effects = export().getEvents().getEffectEvents();
		assertEquals(4, effects.size());
		assertEquals("Poisoned", effects.get(0).getJournalNote());
		assertEquals("Status changed: poison to venom", effects.get(1).getJournalNote());
		assertEquals("Status changed: venom to poison", effects.get(2).getJournalNote());
		assertEquals("Poison/venom state cleared", effects.get(3).getJournalNote());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void sharedBossHitsAndBlocksKeepOwnershipWithoutInflatingPersonalDamage()
	{
		playerAnimation = 401;
		hit(boss, HitsplatID.DAMAGE_ME, 12);
		hit(boss, HitsplatID.DAMAGE_OTHER, 31);
		hit(boss, HitsplatID.BLOCK_OTHER, 0);
		tick();
		List<DamageRecord> damage = export().getEvents().getDamageEvents();
		assertEquals(3, damage.size());
		assertEquals("DEALT", damage.get(0).getDirection());
		assertEquals("LOCAL_PLAYER", damage.get(0).getOwnership());
		assertEquals("unknown", damage.get(0).getDamageType());
		assertNull(damage.get(0).getAttackerRef());
		for (int i = 1; i < 3; i++)
		{
			assertEquals("NPC_RECEIVED", damage.get(i).getDirection());
			assertEquals("OTHER", damage.get(i).getOwnership());
			assertEquals("npc:1:vunknown:g1", damage.get(i).getDefenderRef());
			assertNull(damage.get(i).getAttackerRef());
			assertNull(damage.get(i).getAttackerName());
			assertEquals("unknown", damage.get(i).getDamageType());
		}
		assertEquals(31, damage.get(1).getAmount());
		assertEquals(0, damage.get(2).getAmount());
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
		assertEquals(Boolean.TRUE, export().getEvents().getObservations().get(1).getData().get("isOthers"));
		TickRecording recording = TickRecording.builder().ticks(journal.getTickHistory())
			.preparedExport(captureLive(journal.getTickHistory())).build();
		com.google.gson.JsonObject copied = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(recording, gson), com.google.gson.JsonObject.class);
		assertEquals(3, copied.getAsJsonObject("events").getAsJsonArray("damageEvents").size());
		assertEquals(12, copied.getAsJsonObject("summary").get("totalDamageDealtInBuffer").getAsInt());
	}

	@Test
	public void ownershipAndResourceSemanticsAreIndependentAcrossAllHitsplatColours()
	{
		int[] types =
		{ HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_OTHER_CYAN, HitsplatID.DAMAGE_OTHER_ORANGE,
			HitsplatID.DAMAGE_OTHER_YELLOW, HitsplatID.DAMAGE_OTHER_WHITE, HitsplatID.DAMAGE_OTHER_POISE,
			HitsplatID.BLOCK_OTHER };
		for (int type : types)
			hit(boss, type, type == HitsplatID.BLOCK_OTHER ? 0 : 10);
		hit(boss, HitsplatID.DAMAGE_MAX_ME, 29);
		tick();
		assertEquals(3, export().getEvents().getDamageEvents().size()); // ordinary HP, HP block, local max HP hit
		assertEquals(2,
			export().getEvents().getDamageEvents().stream().filter(d -> "OTHER".equals(d.getOwnership())).count());
		assertEquals(types.length + 1,
			export().getEvents().getObservations().stream().filter(e -> "HITSPLAT".equals(e.getType())).count());
		assertTrue(export().getEvents().getObservations().stream()
			.anyMatch(e -> "POISE".equals(e.getData().get("resourceSurface"))));
		assertEquals(1, journal.getTickHistory().get(0).getDamageEvents().size());
		assertEquals(29, journal.getTickHistory().get(0).getDamageEvents().get(0).getAmount());
	}

	@Test
	public void npcHitsRemainRecordedWhenOtherPlayerSnapshotsAndPersonalDamageTrackingAreOff() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}

			@Override
			public boolean captureEffects()
			{
				return false;
			}

			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackDamageDealt()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		hit(boss, HitsplatID.DAMAGE_OTHER, 30);
		hit(boss, HitsplatID.BLOCK_OTHER, 0);
		hit(boss, HitsplatID.DAMAGE_ME, 10);
		tick();
		assertEquals(2, export().getEvents().getDamageEvents().size());
		assertTrue(
			export().getEvents().getDamageEvents().stream().allMatch(d -> "NPC_RECEIVED".equals(d.getDirection())));
		assertEquals(3, export().getEvents().getObservations().size());
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
	}

	@Test
	public void npcStatusDamageKeepsUnknownOwnershipAndHealingIsNotCountedAsDamage()
	{
		hit(boss, HitsplatID.POISON, 6);
		hit(boss, HitsplatID.VENOM, 8);
		hit(boss, HitsplatID.HEAL, 20);
		tick();
		assertEquals(2, export().getEvents().getDamageEvents().size());
		assertEquals(3, export().getEvents().getObservations().size());
		assertTrue(export().getEvents().getDamageEvents().stream()
			.allMatch(d -> "UNKNOWN".equals(d.getOwnership()) && d.getAttackerRef() == null));
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
	}

	private void click(String option, String target, Actor targetActor, int itemId)
	{
		click(option, target, targetActor, itemId, 0);
	}

	private void click(String option, String target, Actor targetActor, int itemId, int slot)
	{
		MenuEntry entry = stub(MenuEntry.class, (name, args) ->
		{
			switch (name)
			{
			case "getOption":
				return option;
			case "getTarget":
				return target;
			case "getActor":
				return targetActor;
			case "getNpc":
				return targetActor instanceof NPC ? targetActor : null;
			case "getItemId":
				return itemId;
			case "getParam0":
				return slot;
			default:
				return null;
			}
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void pickpocketLindir()
	{
		NPC lindir = stub(NPC.class, (name, args) ->
		{
			switch (name)
			{
			case "getName":
				return "Lindir";
			case "getId":
				return 9062;
			case "getWorldLocation":
				return new WorldPoint(3200, 3199, 0);
			default:
				return null;
			}
		});
		npcs.clear();
		npcs.add(lindir);
		MenuEntry entry = stub(MenuEntry.class, (name, args) ->
		{
			switch (name)
			{
			case "getOption":
				return "Pickpocket";
			case "getTarget":
				return "<col=ffff00>Lindir</col>";
			case "getActor":
				return lindir;
			case "getNpc":
				return lindir;
			default:
				return null;
			}
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	@Test
	public void systemMessageSurvivesKnownSessionStateAndClockFailures()
	{
		tick();
		failGameState = true;
		failGameCycle = true;
		systemMessage(ChatMessageType.GAMEMESSAGE, "You enter the Theatre of Blood (Hard Mode)...", 123456);
		failGameState = false;
		failGameCycle = false;
		tick();
		com.tickreplaylogger.model.world.ObservedEvent o = export().getEvents().getObservations().stream()
			.filter(v -> "SYSTEM_MESSAGE".equals(v.getType())).findFirst().orElseThrow(AssertionError::new);
		assertEquals(2, o.getTick());
		assertNull(o.getGameCycle());
		assertEquals("GAMEMESSAGE", o.getData().get("channel"));
		assertEquals(123456, o.getData().get("messageTimestamp"));
		assertNull(o.getData().get("callbackGameState"));
		assertTrue(o.getData().containsKey("captureIssues"));
	}

	@Test
	public void systemMessageRetainsKnownLoadingWithoutBorrowingWorldContext()
	{
		tick();
		gameState = GameState.LOADING;
		failLocalPlayer = true;
		systemMessage(ChatMessageType.GAMEMESSAGE, "A door opens.", 0);
		failLocalPlayer = false;
		tick();
		com.tickreplaylogger.model.world.ObservedEvent o = export().getEvents().getObservations().stream()
			.filter(v -> "SYSTEM_MESSAGE".equals(v.getType())).findFirst().orElseThrow(AssertionError::new);
		assertEquals("LOADING", o.getData().get("callbackGameState"));
		assertEquals(0, o.getData().get("messageTimestamp"));
		assertNull(o.getSceneKey());
		assertNull(o.getActorRef());
	}

	@Test
	public void systemMessageRejectsPlayerChatBeforeOptionalClientReads()
	{
		tick();
		failGameState = true;
		systemMessage(ChatMessageType.PUBLICCHAT, "You enter the Theatre of Blood (Hard Mode)...", 7);
		systemMessage(ChatMessageType.PRIVATECHAT, "Private secret", 8);
		failGameState = false;
		tick();
		assertTrue(
			export().getEvents().getObservations().stream().noneMatch(v -> "SYSTEM_MESSAGE".equals(v.getType())));
	}

	@Test
	public void systemMessageSamplesStateAndClockOnce()
	{
		tick();
		gameStateReads = 0;
		gameCycleReads = 0;
		systemMessage(ChatMessageType.GAMEMESSAGE, "Recorded message", 42);
		assertEquals(1, gameStateReads);
		assertEquals(1, gameCycleReads);
	}

	@Test
	public void systemMessagePickpocketIdentityFailureCannotAbortPrimitiveIntake()
	{
		tick();
		pickpocketLindir();
		failLocalPlayer = true;
		systemMessage(ChatMessageType.SPAM, "You fail to pick the elf's pocket.", 43);
		failLocalPlayer = false;
		tick();
		assertTrue(export().getEvents().getObservations().stream().anyMatch(v -> "SYSTEM_MESSAGE".equals(v.getType())
			&& "You fail to pick the elf's pocket.".equals(v.getData().get("message"))));
	}

	@Test
	public void systemMessagePickpocketPositionFailureRetainsTheMessage()
	{
		tick();
		pickpocketLindir();
		failPlayerWorldPosition = true;
		systemMessage(ChatMessageType.SPAM, "You fail to pick the elf's pocket.", 44);
		failPlayerWorldPosition = false;
		tick();
		assertTrue(export().getEvents().getObservations().stream().anyMatch(v -> "SYSTEM_MESSAGE".equals(v.getType())));
	}

	@Test
	public void systemMessageFixtureRetainsUnknownClocksEmptyTextAndSharedDamage() throws Exception
	{
		tick();
		failGameState = true;
		failGameCycle = true;
		systemMessage(ChatMessageType.GAMEMESSAGE, "You enter the Theatre of Blood (Hard Mode)...", 123456);
		failGameState = false;
		failGameCycle = false;
		failLocalPlayer = true;
		systemMessage(ChatMessageType.SPAM, "You fail to pick the elf's pocket.", 43);
		failLocalPlayer = false;
		hit(HitsplatID.POISON, 5);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hp = 94;
		tick();
		gameState = GameState.LOADING;
		failLocalPlayer = true;
		systemMessage(ChatMessageType.GAMEMESSAGE, "A door opens.", 0);
		systemMessage(ChatMessageType.SPAM, null, 0);
		tick();
		gameState = GameState.LOGGED_IN;
		failLocalPlayer = false;
		systemMessage(ChatMessageType.GAMEMESSAGE, "", 123457);
		systemMessage(ChatMessageType.SPAM, "<col=ff0000></col>", 123458);
		systemMessage(ChatMessageType.PUBLICCHAT, "Private spoof message", 0);
		systemMessage(ChatMessageType.PRIVATECHAT, "Private secret", 0);
		hit(HitsplatID.POISON, 94);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-system-messages");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement frame : out.getAsJsonArray("ticks"))
			frame.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		String json = gson.toJson(out);
		assertFalse(json.contains("Private sender field"));
		assertFalse(json.contains("Private chat group"));
		assertFalse(json.contains("Private spoof message"));
		assertFalse(json.contains("Private secret"));
		assertEquals(6,
			export().getEvents().getObservations().stream().filter(v -> "SYSTEM_MESSAGE".equals(v.getType())).count());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/system-messages.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void exactSelfDamageSystemResponsesReachTheActualReceivedHitWithoutCreatingAnItemOrAttack()
	{
		useProjectileNativeView();
		npcs.clear();
		animation = -1;
		tick();
		String[] texts = {
			"The locator orb is unstable and hurts you as you use it.",
			"You attempt to string the bow, but burn yourself in the process. You'll need a Fletching level of 74 to craft a scorching bow."
		};
		String[] names = { "Locator orb", "Scorching bow" };
		int[] maximums = { 10, 4 };
		for (int i = 0; i < texts.length; i++)
		{
			if (i == 0) systemMessage(ChatMessageType.GAMEMESSAGE, texts[i], 0);
			hit(HitsplatID.DAMAGE_ME, maximums[i]);
			if (i == 1) systemMessage(ChatMessageType.SPAM, texts[i], 0);
			tick();
			DamageRecord observed = capturedLastDamage();
			assertEquals(maximums[i], observed.getAmount());
			assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
			final String expected = names[i];
			com.tickreplaylogger.model.world.SourceResolution.Candidate hint = observed.getSourceResolution().getCandidates().stream()
				.filter(candidate -> expected.equals(candidate.getName())).findFirst().orElseThrow(AssertionError::new);
			assertNull(hint.getActorRef());
			assertEquals("input-rule:wiki:" + names[i], hint.getFamilyKey());
			assertEquals("POSSIBLE", hint.getStrength());
			assertEquals("unknown", observed.getDamageType());
			assertTrue(hint.getEvidence().stream().anyMatch(e -> e.startsWith("SELF_SYSTEM_RESPONSE_RULE:")));
			systemMessage(ChatMessageType.GAMEMESSAGE, texts[i], 0);
			hit(HitsplatID.DAMAGE_ME, maximums[i] + 1);
			tick();
			assertTrue(capturedLastDamage().getSourceResolution().getCandidates().stream().noneMatch(candidate -> expected.equals(candidate.getName())));
		}
	}

	private void systemMessage(ChatMessageType type, String message, int timestamp)
	{
		ChatMessage event = new ChatMessage();
		event.setType(type);
		event.setMessage(message);
		event.setTimestamp(timestamp);
		event.setName("Private sender field");
		event.setSender("Private chat group");
		plugin.onChatMessage(event);
	}

	private void pickpocketFailure()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.SPAM);
		event.setMessage("You fail to pick the elf's pocket.");
		plugin.onChatMessage(event);
	}

	private void hit(int type, int amount)
	{
		hit(player, type, amount);
	}

	private void hit(Actor receiver, int type, int amount)
	{
		Hitsplat hitsplat = new Hitsplat()
		{
			@Override
			public int getAmount()
			{
				return amount;
			}

			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return gameCycle + 70;
			}
		};
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(receiver);
		event.setHitsplat(hitsplat);
		plugin.onHitsplatApplied(event);
	}

	@Test
	public void uncertainModeContextCannotResumeAnEarlierAttackCycle() throws Exception
	{
		npcObservedName = "Corrupted Hunllef";
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(DamageMap.load(gson));
		Client client = (Client) field.get(plugin);
		npcId = 9035;
		npcLevel = 894;
		animation = -1;
		capture.captureTick(client, 1, "s", Collections.emptyList(), null, true);
		animation = 8419;
		assertTrue(capture.captureTick(client, 2, "s", Collections.emptyList(), null, true).getNpcs().get(0)
			.getAttackCooldownTicksRemaining() > 0);
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		animation = -1;
		capture.captureTick(client, 3, "s", Collections.emptyList(), null, true);
		worldTypes = java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
		assertEquals(Integer.valueOf(0), capture.captureTick(client, 4, "s", Collections.emptyList(), null, true)
			.getNpcs().get(0).getAttackCooldownTicksRemaining());
	}

	@Test
	public void unavailableActorTimingCannotCarryCooldownButPreservesObservedActorLifetime() throws Exception
	{
		npcObservedName = "Corrupted Hunllef";
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(DamageMap.load(gson));
		Client client = (Client) field.get(plugin);
		npcId = 9035;
		npcLevel = 894;
		animation = -1;
		capture.captureTick(client, 1, "s", Collections.emptyList(), null, true);
		animation = 8419;
		com.tickreplaylogger.model.world.NpcState before = capture
			.captureTick(client, 2, "s", Collections.emptyList(), null, true).getNpcs().get(0);
		assertTrue(before.getAttackCooldownTicksRemaining() > 0);
		capture.invalidateActorTiming();
		animation = -1;
		com.tickreplaylogger.model.world.NpcState after = capture
			.captureTick(client, 3, "s", Collections.emptyList(), null, true).getNpcs().get(0);
		assertEquals(Integer.valueOf(0), after.getAttackCooldownTicksRemaining());
		assertEquals(before.getActorRef(), after.getActorRef());
	}

	@Test
	public void movementUsesObservedPositionRatherThanTurningPoseAndClonesOverheadSlots() throws Exception
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		Field clientField = TickReplayLoggerPlugin.class.getDeclaredField("client");
		clientField.setAccessible(true);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(DamageMap.load(gson));
		npcLocalPosition = new LocalPoint(6208, 6208);
		npcPoseAnimation = 12345;
		npcOverheadArchives = new int[]
		{ -1, 440 };
		npcOverheadSprites = new short[]
		{ -1, 2 };
		Client client = (Client) clientField.get(plugin);
		com.tickreplaylogger.model.world.NpcState first = capture
			.captureTick(client, 1, "s", Collections.emptyList(), null, true).getNpcs().get(0);
		assertEquals("UNDETERMINED", first.getDerivedAction());
		assertEquals("STATIONARY", capture.captureTick(client, 2, "s", Collections.emptyList(), null, true).getNpcs()
			.get(0).getDerivedAction());
		npcLocalPosition = new LocalPoint(6336, 6208);
		assertEquals("MOVING", capture.captureTick(client, 3, "s", Collections.emptyList(), null, true).getNpcs().get(0)
			.getDerivedAction());
		npcOverheadArchives[1] = 999;
		npcOverheadSprites[1] = 9;
		assertEquals(Integer.valueOf(440), Integer.valueOf(first.getOverheadArchiveIds()[1]));
		assertEquals(2, first.getOverheadSpriteIds()[1]);
		assertTrue(first.getOverheadIconsKnown());
		assertEquals(DamageEvent.DamageType.UNKNOWN, DamageEvent.DamageType.fromReference("NEW_UNSUPPORTED_TYPE"));
		assertEquals(DamageEvent.DamageType.UNKNOWN, DamageEvent.DamageType.fromReference(null));
		assertEquals(DamageEvent.DamageType.TYPELESS, DamageEvent.DamageType.fromReference("typeless"));
	}

	@Test
	public void tobDefinitionStatesExportObservedFormsWithoutAdoptingModeOrAttackPredictions() throws Exception
	{
		npcs.clear();
		boss = npc("The Maiden of Sugadinti", 7);
		npcs.add(boss);
		npcId = 10814;
		npcLevel = 324;
		npcObservedSize = 6;
		npcLocalPosition = new LocalPoint(6464, 6592);
		tick();
		npcId = 10815;
		tick();
		npcId = 10816;
		tick();
		npcId = 10818;
		tick();
		npcs.clear();
		boss = npc("Nylocas Vasilias", 8);
		npcs.add(boss);
		npcId = 10786;
		npcLevel = 301;
		npcObservedSize = 4;
		tick();
		npcId = 10787;
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		tick();
		npcId = 10788;
		tick();
		npcId = 10789;
		tick();
		npcs.clear();
		boss = npc("null", 9);
		npcs.add(boss);
		npcId = 10790;
		npcLevel = 0;
		npcObservedSize = 3;
		tick();
		npcs.clear();
		boss = npc("Verzik Vitur", 10);
		npcs.add(boss);
		npcId = 14798;
		npcLevel = 1040;
		npcObservedSize = 1;
		tick();
		npcId = 10851;
		npcLevel = 1265;
		npcObservedSize = 7;
		tick();
		npcId = 10852;
		npcLevel = 1520;
		npcObservedSpeech = "I'm not finished with you just yet!";
		tick();
		npcId = 10853;
		npcObservedSize = 3;
		npcObservedSpeech = null;
		tick();
		npcs.clear();
		boss = npc("Xarpus", 11);
		npcs.add(boss);
		npcId = 10772;
		npcLevel = 1160;
		npcObservedSize = 5;
		tick();
		npcs.clear();
		boss = npc("Sotetseg", 12);
		npcs.add(boss);
		npcId = 10867;
		npcLevel = 1195;
		tick();
		npcs.clear();
		boss = npc("Blood spawn", 13);
		npcs.add(boss);
		npcId = 10829;
		npcLevel = 183;
		npcObservedSize = 1;
		tick();
		npcLevel = 55;
		tick();
		npcLevel = 183;
		npcObservedName = "Unrelated creature";
		tick();
		npcObservedName = null;
		npcModelOverride = true;
		tick();
		npcModelOverride = false;
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		tick();
		worldTypes = java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
		npcObservedSize = 2;
		tick();
		npcObservedSize = 1;
		tick();
		npcs.clear();
		boss = npc("null", 14);
		npcs.add(boss);
		npcId = 10863;
		npcLevel = 1;
		hp = 0;
		hit(HitsplatID.POISON, 99);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-tob-definition-states");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		com.google.gson.JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals(23, frames.size());
		for (int i = 0; i < 23; i++)
		{
			com.google.gson.JsonObject npc = frames.get(i).getAsJsonObject().getAsJsonArray("npcs").get(0)
				.getAsJsonObject();
			assertEquals("tick " + (i + 1), !(i >= 16 && i <= 20), npc.has("definitionStateReference"));
		}
		assertEquals("Enraged", frames.get(11).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
			.get("observedPhase").getAsString());
		assertFalse(
			frames.get(12).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject().has("observedPhase"));
		assertEquals("Support", frames.get(8).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
			.getAsJsonObject("definitionStateReference").get("role").getAsString());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/tob-definition-states.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void playerSnapshotFailureExportKeepsPartyResourcesPartialPrayersAndDamage() throws Exception
	{
		npcId = 9036;
		npcLevel = 894;
		npcs.clear();
		boss = npc("Corrupted Hunllef", 7);
		npcs.add(boss);
		npcLocalPosition = new LocalPoint(6464, 6592);
		java.util.Set<String> teammateFailures = new java.util.HashSet<>();
		boolean[] slotFailure =
		{ false };
		Player teammate = stub(Player.class, (name, args) ->
		{
			if (teammateFailures.contains(name))
				throw new IllegalStateException("synthetic teammate " + name);
			switch (name)
			{
			case "getId":
				return 2;
			case "getName":
				return "Synthetic teammate";
			case "getLocalLocation":
				return new LocalPoint(6720, 6400);
			case "getWorldLocation":
				return new WorldPoint(3202, 3200, 0);
			case "getHealthRatio":
				return 10;
			case "getHealthScale":
				return 30;
			case "getAnimation":
				return -1;
			default:
				return null;
			}
		});
		otherPlayers.add(teammate);
		equipment = stub(ItemContainer.class, (name, args) ->
		{
			if (!name.equals("getItem"))
				return null;
			int slot = (Integer) args[0];
			if (slotFailure[0] && slot == 9)
				throw new IllegalStateException("synthetic slot unavailable");
			return slot == 7 ? new Item(1079, 1) : slot == 12 ? new Item(2550, 1) : null;
		});
		inventory = stub(ItemContainer.class, (name, args) ->
		{
			if (!name.equals("getItem"))
				return null;
			int slot = (Integer) args[0];
			if (slotFailure[0] && slot == 1)
				throw new IllegalStateException("synthetic inventory unavailable");
			return slot == 0 ? new Item(385, 3) : null;
		});
		prayer = Prayer.AUGURY;
		hp = 99;
		tick();
		failedPlayerSnapshotReads
			.addAll(java.util.Arrays.asList("getAnimation", "getCurrentOrientation", "getOverheadIcon"));
		failedBoostedSkills.add(Skill.HITPOINTS);
		failedBaseSkills.add(Skill.DEFENCE);
		failedPrayerReads.add(Prayer.PROTECT_FROM_MAGIC);
		slotFailure[0] = true;
		teammateFailures.addAll(java.util.Arrays.asList("getName", "getAnimation", "getHealthRatio"));
		projectile(boss, 1712);
		tick();
		failedPlayerSnapshotReads.clear();
		failedBoostedSkills.clear();
		failedBaseSkills.clear();
		failedPrayerReads.clear();
		slotFailure[0] = false;
		teammateFailures.clear();
		hp = 87;
		hit(HitsplatID.DAMAGE_ME, 12);
		tick();
		DamageRecord launched = capturedLastDamage();
		assertEquals(2, launched.getLaunchTick());
		assertEquals("FIRST_PROJECTILE_OBSERVATION", launched.getLaunchStateEvidence());
		assertEquals(Boolean.FALSE, launched.getLaunchPrayerStateKnown());
		assertTrue(launched.getAttributionCandidates().stream().anyMatch(witness ->
			"PROJECTILE_OBSERVATION".equals(witness.get("kind")) && witness.get("originalProjectileObservation") != null
				&& Boolean.FALSE.equals(witness.get("launchPrayerStateKnown"))));
		failPlayerWorldPosition = true;
		localPosition = null;
		teammateFailures.addAll(java.util.Arrays.asList("getWorldLocation", "getLocalLocation"));
		hp = 84;
		hit(HitsplatID.POISON, 3);
		tick();
		failPlayerWorldPosition = false;
		localPosition = new LocalPoint(6400, 6400);
		teammateFailures.clear();
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 84);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-player-snapshot");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		com.google.gson.JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals(6, frames.size());
		for (com.google.gson.JsonElement value : frames)
		{
			assertTrue(value.getAsJsonObject().has("player"));
			assertEquals(1, value.getAsJsonObject().getAsJsonArray("players").size());
		}
		com.google.gson.JsonObject partial = frames.get(1).getAsJsonObject().getAsJsonObject("player");
		assertFalse(partial.has("hp"));
		assertFalse(partial.has("animationId"));
		assertEquals(99, partial.get("prayer").getAsInt());
		assertFalse(partial.get("prayerStateKnown").getAsBoolean());
		assertFalse(partial.getAsJsonArray("equipmentSlotKnown").get(9).getAsBoolean());
		assertEquals(2550, partial.getAsJsonArray("equipmentItemIds").get(12).getAsInt());
		assertFalse(frames.get(3).getAsJsonObject().getAsJsonObject("player").get("planeKnown").getAsBoolean());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/player-snapshot.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void npcSnapshotFailureExportKeepsIndependentFactsAndMixedDamage() throws Exception
	{
		npcId = 9036;
		npcLevel = 894;
		npcs.clear();
		boss = npc("Corrupted Hunllef", 7);
		npcs.add(boss);
		npcLocalPosition = new LocalPoint(6464, 6592);
		npcOverheadArchives = new int[]
		{ 440 };
		npcOverheadSprites = new short[]
		{ 0 };
		hp = 99;
		animation = -1;
		tick();
		animation = 8419;
		npcAnimationFrame = 0;
		hp = 88;
		hit(HitsplatID.DAMAGE_ME, 11);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		tick();
		hp = 83;
		hit(HitsplatID.DAMAGE_ME, 5);
		failedNpcSnapshotReads.addAll(java.util.Arrays.asList("getAnimation", "getCurrentOrientation", "getHealthRatio",
			"getOverheadArchiveIds", "getModelOverrides"));
		tick();
		failedNpcSnapshotReads.clear();
		npcAnimationFrame = 3;
		tick();
		failNpcWorldPosition = true;
		tick();
		failedNpcSnapshotReads.add("getLocalLocation");
		hp = 79;
		hit(HitsplatID.POISON, 4);
		tick();
		failNpcWorldPosition = false;
		failedNpcSnapshotReads.clear();
		failedNpcSnapshotReads.addAll(java.util.Arrays.asList("getTransformedComposition", "getId", "getName"));
		tick();
		failedNpcSnapshotReads.clear();
		animation = 8419;
		npcAnimationFrame = 0;
		hp = 0;
		hit(HitsplatID.POISON, 79);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-npc-snapshot");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		com.google.gson.JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals(8, frames.size());
		for (com.google.gson.JsonElement value : frames)
			assertEquals(1, value.getAsJsonObject().getAsJsonArray("npcs").size());
		com.google.gson.JsonObject partial = frames.get(2).getAsJsonObject().getAsJsonArray("npcs").get(0)
			.getAsJsonObject();
		assertFalse(partial.has("animationId"));
		assertEquals(6464, partial.get("localX").getAsInt());
		com.google.gson.JsonObject unknown = frames.get(6).getAsJsonObject().getAsJsonArray("npcs").get(0)
			.getAsJsonObject();
		assertFalse(unknown.has("npcId"));
		assertFalse(unknown.has("name"));
		assertNotNull(unknown.get("actorRef"));
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/npc-snapshot.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void normalContextExportSeparatesBaseCadenceFromSeasonalOverridesAndUnknownWorld() throws Exception
	{
		npcId = 2042;
		npcLevel = 725;
		npcs.clear();
		boss = npc("Zulrah", 7);
		npcs.add(boss);
		npcLocalPosition = new LocalPoint(6464, 6592);
		animation = -1;
		tick();
		animation = 5069;
		tick();
		animation = -1;
		tick();
		worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
		tick();
		animation = 5069;
		tick();
		animation = -1;
		tick();
		worldTypes = java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
		tick();
		animation = 5069;
		tick();
		npcModelOverride = true;
		animation = -1;
		tick();
		animation = 5069;
		tick();
		npcModelOverride = false;
		worldTypes = null;
		animation = -1;
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 99);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(preparedJson(storedDeaths().get(0)),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-normal-context");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/normal-context.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		com.google.gson.JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals(12, frames.size());
		assertEquals(2, frames.get(2).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
			.get("attackCooldownTicksRemaining").getAsInt());
		for (int index : new int[]
		{ 3, 4, 5, 8, 9, 10, 11 })
		{
			com.google.gson.JsonObject npc = frames.get(index).getAsJsonObject().getAsJsonArray("npcs").get(0)
				.getAsJsonObject();
			assertFalse(npc.get("normalWorldContextObserved").getAsBoolean());
			assertNotEquals("tick " + (index + 1), "REVIEWED_ANIMATION_BINDING",
				npc.get("actionEvidence").getAsString());
			assertEquals("tick " + (index + 1), 0, npc.get("attackCooldownTicksRemaining").getAsInt());
			assertEquals(2042, npc.get("npcId").getAsInt());
		}
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void conflictingObservedNameCannotSupplyScopedProjectileStyleOrPrayerTiming()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Different observed NPC";
		projectile(boss, 1712);
		tick();
		com.tickreplaylogger.model.world.ProjectileState raw = world.snapshotAtTick(1).getProjectiles().get(0);
		assertEquals(Boolean.TRUE, raw.getSourceNormalWorldContextObserved());
		assertEquals("Different observed NPC", raw.getSourceName());
		assertNull(raw.getDamageType());
		assertNull(raw.getDamageTypeRuleKey());
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertNull(capturedLastDamage().getPrayerEffect());
	}

	@Test
	public void qualifiedProjectileLaunchNameEvidenceSurvivesALaterNameContradiction()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		projectile(boss, 1712);
		npcObservedName = "Different observed NPC";
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Corrupted Hunllef", capturedLastDamage().getAttackerName());
		assertEquals("ranged", capturedLastDamage().getDamageType());
		assertEquals("LAUNCH", capturedLastDamage().getDetermination());
		assertEquals("Different observed NPC", world.snapshotAtTick(2).getNpcs().get(0).getName());
	}

	@Test
	public void npcNameQualificationExportKeepsIdentityFailuresRecoveryAndDamage() throws Exception
	{
		npcId = 2042;
		npcLevel = 725;
		npcObservedSize = 5;
		npcObservedName = "Zulrah";
		npcLocalPosition = new LocalPoint(6464, 6592);
		for (int index = 1; index <= 18; index++)
		{
			animation = index == 1 || index == 7 || index == 11 || index >= 13 ? -1 : 5069;
			npcAnimationFrame = index == 2 || index == 8 || index == 12 ? 0 : index;
			if (index == 4)
				npcObservedName = "Different observed NPC";
			if (index == 6)
				npcObservedName = "Zulrah";
			if (index == 9)
				failedNpcSnapshotReads.add("getName");
			if (index == 10)
				failedNpcSnapshotReads.clear();
			if (index == 13)
			{
				npcId = 11278;
				npcLevel = 1001;
				npcObservedSize = 3;
				npcObservedName = "Nex";
				npcObservedSpeech = "Fill my soul with smoke!";
			}
			if (index == 14)
			{
				npcObservedName = "Different observed NPC";
				npcObservedSpeech = null;
			}
			if (index == 15)
				npcObservedName = "Nex";
			if (index == 16)
				npcObservedSpeech = "Darken my shadow!";
			if (index == 17)
			{
				failedNpcSnapshotReads.add("getName");
				npcObservedSpeech = null;
			}
			if (index == 18)
			{
				failedNpcSnapshotReads.clear();
				hp = 0;
				hit(HitsplatID.POISON, 99);
				hit(boss, HitsplatID.DAMAGE_OTHER, 7);
				plugin.onActorDeath(new ActorDeath(player));
			}
			tick();
		}
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-npc-name-qualification");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/npc-name-qualification.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		com.google.gson.JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals(18, frames.size());
		for (int index : new int[]
		{ 3, 4, 8 })
		{
			com.google.gson.JsonObject npc = frames.get(index).getAsJsonObject().getAsJsonArray("npcs").get(0)
				.getAsJsonObject();
			assertTrue(npc.get("normalWorldContextObserved").getAsBoolean());
			assertNotEquals("REVIEWED_ANIMATION_BINDING", npc.get("actionEvidence").getAsString());
			assertEquals(0, npc.get("attackCooldownTicksRemaining").getAsInt());
		}
		assertEquals("Smoke", frames.get(12).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
			.get("observedPhase").getAsString());
		assertEquals("Shadow", frames.get(15).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
			.get("observedPhase").getAsString());
		for (int index : new int[]
		{ 13, 14, 16, 17 })
			assertFalse(frames.get(index).getAsJsonObject().getAsJsonArray("npcs").get(0).getAsJsonObject()
				.has("observedPhase"));
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void richNpcHandlerExportPreservesLargeMembershipFailuresRecoveryAndDamage() throws Exception
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedSize = 5;
		npcLocalPosition = new LocalPoint(6464, 6592);
		npcs.clear();
		for (int index = 0; index < 100; index++)
			npcs.add(npc("Corrupted Hunllef", index));
		boss = npcs.get(0);
		for (int index = 1; index <= 24; index++)
		{
			animation = index % 3 == 0 ? 8419 : -1;
			npcAnimationFrame = index % 9;
			if (index == 5)
				failedNpcSnapshotReads
					.addAll(java.util.Arrays.asList("getAnimation", "getHealthRatio", "getOverheadArchiveIds"));
			if (index == 6)
				failedNpcSnapshotReads.clear();
			if (index == 9)
				worldTypes = java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
			if (index == 10)
				worldTypes = java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
			if (index == 12)
			{
				boss = npc("Corrupted Hunllef", 0);
				npcs.set(0, boss);
			}
			if (index == 16)
				npcModelOverride = true;
			if (index == 17)
				npcModelOverride = false;
			if (index == 24)
			{
				hp = 0;
				hit(HitsplatID.POISON, 99);
				hit(boss, HitsplatID.DAMAGE_OTHER, 7);
				plugin.onActorDeath(new ActorDeath(player));
			}
			tick();
		}
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-rich-npc-handler");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/rich-npc-handler.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("1.3", out.get("schemaVersion").getAsString());
		assertEquals(24, out.getAsJsonArray("ticks").size());
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
		{
			com.google.gson.JsonObject frame = value.getAsJsonObject();
			assertFalse(frame.has("npcs"));
			assertEquals(100, frame.getAsJsonArray("npcStateRefs").size());
		}
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void failedNpcCallbackLevelDoesNotEraseKnownIdentityAndPosition()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcObservedSize = 5;
		npcLocalPosition = new LocalPoint(6464, 6592);
		failedNpcSnapshotReads.add("getCombatLevel");
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_SPAWNED");
		assertEquals(6464, raw.get("localX").getAsInt());
		assertEquals(3200, raw.get("worldX").getAsInt());
		assertEquals(9036, raw.get("npcId").getAsInt());
		assertFalse(raw.has("combatLevel"));
		assertTrue(raw.getAsJsonObject("observationIssues").has("combatLevel"));
	}

	@Test
	public void failedPlayerCallbackLevelDoesNotEraseKnownPositionAndPrayerIconAvailability()
	{
		failedPlayerSnapshotReads.add("getCombatLevel");
		plugin.onPlayerChanged(new net.runelite.api.events.PlayerChanged(player));
		tick();
		com.google.gson.JsonObject raw = callbackData("PLAYER_CHANGED");
		assertEquals(6400, raw.get("localX").getAsInt());
		assertEquals(3200, raw.get("worldY").getAsInt());
		assertTrue(raw.get("overheadIconKnown").getAsBoolean());
		assertFalse(raw.has("combatLevel"));
		assertTrue(raw.getAsJsonObject("observationIssues").has("combatLevel"));
	}

	@Test
	public void unresolvedNpcCallbackDefinitionCannotBecomeAnActiveBaseIdOrResearchQualification()
	{
		com.google.gson.JsonObject seed = com.tickreplaylogger.capture.NpcDefinitionTestData.transforms().iterator()
			.next();
		npcId = seed.get("npcId").getAsInt();
		npcUnresolvedTransform = true;
		npcTransformIds = com.tickreplaylogger.capture.NpcDefinitionTestData.configs(npcId);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_SPAWNED");
		assertEquals(npcId, raw.get("baseNpcId").getAsInt());
		assertFalse(raw.has("npcId"));
		assertEquals("TRANSFORM_UNAVAILABLE", raw.get("definitionEvidence").getAsString());
		assertFalse(raw.has("actorResearchKeys"));
	}

	@Test
	public void failedNpcDespawnIndexDoesNotDiscardTheLifecycleObservation()
	{
		npcId = 9036;
		npcObservedName = "Corrupted Hunllef";
		npcLocalPosition = new LocalPoint(6464, 6592);
		tick();
		failedNpcSnapshotReads.add("getIndex");
		RuntimeException failure = null;
		try
		{
			plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		}
		catch (RuntimeException unavailable)
		{
			failure = unavailable;
		}
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_DESPAWNED");
		assertEquals(6464, raw.get("localX").getAsInt());
		assertNull(failure);
	}

	@Test
	public void failedOverheadDefinitionReadDoesNotDiscardTheOriginalTextCallback()
	{
		npcId = 11278;
		npcLevel = 1001;
		npcObservedName = "Nex";
		npcObservedSpeech = "Fill my soul with smoke!";
		failedNpcSnapshotReads.add("getTransformedComposition");
		RuntimeException failure = null;
		try
		{
			plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(boss, npcObservedSpeech));
		}
		catch (RuntimeException unavailable)
		{
			failure = unavailable;
		}
		tick();
		com.google.gson.JsonObject raw = callbackData("OVERHEAD_TEXT_CHANGED");
		assertEquals(npcObservedSpeech, raw.get("text").getAsString());
		assertNull(failure);
	}

	@Test
	public void overheadCallbackUsesItsOwnTextPayloadRatherThanALaterActorTextRead()
	{
		npcId = 11278;
		npcLevel = 1001;
		npcObservedName = "Nex";
		npcObservedSpeech = "Darken my shadow!";
		plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(boss, "Fill my soul with smoke!"));
		tick();
		com.google.gson.JsonObject raw = callbackData("OVERHEAD_TEXT_CHANGED");
		assertEquals("Fill my soul with smoke!", raw.get("text").getAsString());
		assertEquals("Darken my shadow!", raw.getAsJsonObject("actorState").get("overheadText").getAsString());
	}

	private com.google.gson.JsonObject callbackData(String type)
	{
		com.tickreplaylogger.model.world.ObservedEvent found = export().getEvents().getObservations().stream()
			.filter(e -> type.equals(e.getType())).findFirst().orElse(null);
		assertNotNull("A real " + type + " callback cannot disappear after an optional metadata failure", found);
		return gson.toJsonTree(found.getData()).getAsJsonObject();
	}

	@Test
	public void loadingLifecycleCallbackKeepsPublicNpcFieldsWithoutClaimingReadyContextOrMapAssignment()
	{
		tick();
		gameState = GameState.LOADING;
		npcId = 9036;
		npcObservedName = "Corrupted Hunllef";
		npcLocalPosition = new LocalPoint(6464, 6592);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		gameState = GameState.LOGGED_IN;
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_SPAWNED");
		assertEquals(6464, raw.get("localX").getAsInt());
		assertEquals("API_REPORTED_WITHOUT_READY_CONTEXT", raw.get("callbackMetadataEvidence").getAsString());
		assertFalse(raw.has("normalWorldContextObserved"));
		com.tickreplaylogger.model.world.ObservedEvent event = export().getEvents().getObservations().stream()
			.filter(e -> "NPC_SPAWNED".equals(e.getType())).findFirst().get();
		assertNull(event.getActorRef());
		assertNull(event.getActorName());
		assertNull(event.getSceneKey());
	}

	@Test
	public void disabledRichSnapshotsKeepMinimalSourceFactsWithoutSceneCapture() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}

			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}
		});
		npcId = 987654;
		npcObservedSize = 1; // Supply an actual definition independently of catalog coverage.
		animation = 123;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		net.runelite.api.events.AnimationChanged changed = new net.runelite.api.events.AnimationChanged();
		changed.setActor(boss);
		plugin.onAnimationChanged(changed);
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class,
			(name, args) -> name.equals("getOption") ? "Examine" : null)));
		NPC unreadable = stub(NPC.class, (name, args) ->
		{
			throw new AssertionError("Disabled rich effect callback read " + name);
		});
		plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(unreadable, "Raw text"));
		net.runelite.api.events.GraphicChanged graphic = new net.runelite.api.events.GraphicChanged();
		graphic.setActor(unreadable);
		plugin.onGraphicChanged(graphic);
		Player privatePlayer = stub(Player.class, (name, args) ->
		{
			throw new AssertionError("Opted-out player metadata read " + name);
		});
		plugin.onPlayerSpawned(new net.runelite.api.events.PlayerSpawned(privatePlayer));
		tick();
		DeathRecordingV2 recording = export();
		assertTrue(recording.getTicks().isEmpty());
		assertTrue(recording.getScenes() == null || recording.getScenes().isEmpty());
		assertEquals(987654, callback("NPC_SPAWNED").getData().get("npcId"));
		assertEquals(123, callback("ANIMATION_CHANGED").getData().get("animationId"));
		assertNotNull(callback("MENU_OPTION").getData().get("actorState"));
		assertTrue(recording.getEvents().getObservations().stream().noneMatch(event ->
			java.util.Arrays.asList("ACTOR_GRAPHICS_CHANGED", "OVERHEAD_TEXT_CHANGED", "PLAYER_SPAWNED").contains(event.getType())));
	}

	@Test
	public void optedOutPlayerMenuRedactsDuplicateLabelsAndIdentity() throws Exception
	{
		assertPlayerMenuPrivacy(false);
	}

	@Test
	public void optedOutTypedPlayerMenuRedactsWhenItsActorLookupIsUnavailable() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override public boolean captureOtherPlayers() { return false; }
			@Override public boolean trackPing() { return false; }
		});
		for (int failureMode = 0; failureMode < 3; failureMode++)
		{
			int mode = failureMode;
			int[] typeReads = { 0 };
			MenuEntry entry = stub(MenuEntry.class, (name, args) ->
			{
				if (name.equals("getType"))
				{
					typeReads[0]++;
					return MenuAction.PLAYER_FIRST_OPTION;
				}
				if (name.equals("getActor") && mode == 2)
				{
					throw new IllegalStateException("Unavailable actor");
				}
				return name.equals("getOption") ? "Attack" : name.equals("getTarget") ? "Private opponent"
					: name.equals("getIdentifier") ? 42 : null;
			});
			MenuOptionClicked event = new MenuOptionClicked(entry)
			{
				@Override public MenuEntry getMenuEntry()
				{
					if (mode == 0)
					{
						return null;
					}
					if (mode == 1)
					{
						throw new IllegalStateException("Unavailable menu entry");
					}
					return entry;
				}
			};
			plugin.onMenuOptionClicked(event);
			assertEquals("Use the existing typed sample, not another menu read", 1, typeReads[0]);
			tick();
			com.tickreplaylogger.model.world.ObservedEvent raw = callback("MENU_OPTION");
			assertEquals("Other player", raw.getData().get("target"));
			assertEquals("PLAYER_FIRST_OPTION", raw.getData().get("menuAction"));
			assertEquals(Boolean.TRUE, raw.getData().get("otherPlayerRedacted"));
			assertNull(raw.getData().get("identifier"));
			assertNull(raw.getRelatedActorRef());
			assertFalse(gson.toJson(export()).contains("Private opponent"));
		}
	}

	@Test
	public void optedOutPlayerSettingPreservesAPositivelyKnownOwnPlayerMenu() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override public boolean captureOtherPlayers() { return false; }
			@Override public boolean trackPing() { return false; }
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class, (name, args) ->
			name.equals("getActor") ? player : name.equals("getType") ? MenuAction.RUNELITE_PLAYER
				: name.equals("getOption") ? "Lookup" : name.equals("getTarget") ? "Your character" : null)));
		tick();
		assertEquals("Your character", callback("MENU_OPTION").getData().get("target"));
		assertFalse(callback("MENU_OPTION").getData().containsKey("otherPlayerRedacted"));
	}

	@Test
	public void optedInPlayerMenuKeepsRecordedTargetAndIdentity() throws Exception
	{
		assertPlayerMenuPrivacy(true);
	}

	private void assertPlayerMenuPrivacy(boolean includePlayers) throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return includePlayers;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		Player opponent = stub(Player.class, (name, args) ->
		{
			if (!includePlayers)
			{
				throw new AssertionError("Opted-out menu actor was queried: " + name);
			}
			return name.equals("getId") ? 42 : name.equals("getWorldView") ? projectileNativeView
				: name.equals("getName") ? "Private opponent" : null;
		});
		MenuEntry entry = stub(MenuEntry.class, (name, args) -> name.equals("getOption") ? "Attack"
			: name.equals("getTarget") ? "<col=ff0000>Private opponent</col>"
				: name.equals("getActor") ? opponent : name.equals("getIdentifier") ? 42 : null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("MENU_OPTION");
		assertEquals(includePlayers ? "Private opponent" : "Other player", raw.getData().get("target"));
		if (includePlayers)
		{
			assertNotNull(raw.getRelatedActorRef());
		}
		else
		{
			assertNull(raw.getRelatedActorRef());
			assertNull(raw.getData().get("identifier"));
			assertEquals(Boolean.TRUE, raw.getData().get("otherPlayerRedacted"));
		}
		DeathRecordingV2 recording = export();
		assertEquals(includePlayers, gson.toJson(recording).contains("Private opponent"));
		DeathRecordingV2.Events.PlayerActionRecord action = recording.getEvents().getPlayerActions().get(0);
		assertEquals(raw.getSequence(), action.getSequence().longValue());
		assertEquals(raw.getTick(), action.getTick());
		assertEquals(includePlayers ? "Private opponent" : "Other player", action.getTarget());
		assertEquals(raw.getRelatedActorRef(), action.getTargetRef());
		assertEquals(includePlayers ? "Private opponent" : "Other player",
			journal.getTickHistory().get(0).getPlayerActions().get(0).getTarget());
	}

	@Test
	public void optedOutPlayerSettingKeepsNpcAndItemMenuTargets() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		npcObservedSize = 1;
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class,
			(name, args) -> name.equals("getOption") ? "Attack" : name.equals("getTarget") ? "Test boss"
				: name.equals("getActor") ? boss : null)));
		tick();
		assertEquals("Test boss", callback("MENU_OPTION").getData().get("target"));
		assertNotNull(callback("MENU_OPTION").getRelatedActorRef());
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class,
			(name, args) -> name.equals("getOption") ? "Eat" : name.equals("getTarget") ? "Shark" : null)));
		tick();
		assertEquals("Shark", callback("MENU_OPTION").getData().get("target"));
		assertEquals("Shark", journal.getTickHistory().get(1).getPlayerActions().get(0).getItemName());
	}

	@Test
	public void disabledOtherPlayerCallbacksDoNotReadOptedOutMetadata() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		Player other = stub(Player.class, (name, args) ->
		{
			throw new AssertionError("Opted-out metadata read " + name);
		});
		plugin.onPlayerChanged(new net.runelite.api.events.PlayerChanged(other));
		plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(other, "Private text"));
		net.runelite.api.events.GraphicChanged graphic = new net.runelite.api.events.GraphicChanged();
		graphic.setActor(other);
		plugin.onGraphicChanged(graphic);
		assertTrue(export().getEvents().getObservations().isEmpty());
	}

	@Test
	public void graphicCallbackRetainsKnownPrimaryEffectThroughCollectionFailure()
	{
		playerGraphic = 1234;
		failGraphicsCollection = true;
		net.runelite.api.events.GraphicChanged graphic = new net.runelite.api.events.GraphicChanged();
		graphic.setActor(player);
		plugin.onGraphicChanged(graphic);
		tick();
		com.google.gson.JsonObject raw = callbackData("ACTOR_GRAPHICS_CHANGED");
		assertEquals(1234, raw.getAsJsonArray("graphicIds").get(0).getAsInt());
		assertFalse(raw.get("graphicsKnown").getAsBoolean());
		assertEquals(6400, raw.getAsJsonObject("actorState").get("localX").getAsInt());
	}

	@Test
	public void actorCallbackExportPreservesLifecyclePartialFieldsOriginalTextAndMixedDamage() throws Exception
	{
		npcId = 11278;
		npcLevel = 1001;
		npcObservedName = "Nex";
		npcObservedSize = 3;
		npcLocalPosition = new LocalPoint(6464, 6592);
		boolean[] oldIdUnavailable =
		{ false };
		net.runelite.api.NPCComposition oldNex = stub(net.runelite.api.NPCComposition.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				if (oldIdUnavailable[0])
					throw new IllegalStateException("Old ID unavailable");
				return 11278;
			case "getName":
				return "Nex";
			case "getSize":
				return 3;
			case "getCombatLevel":
				return 1001;
			default:
				return null;
			}
		});
		Player earlierTarget = stub(Player.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 22;
			case "getName":
				return "Synthetic teammate";
			default:
				return null;
			}
		});
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		tick();
		failedNpcSnapshotReads.add("getCombatLevel");
		plugin.onNpcChanged(new net.runelite.api.events.NpcChanged(boss, oldNex));
		plugin.onInteractingChanged(new net.runelite.api.events.InteractingChanged(boss, earlierTarget));
		tick();
		failedNpcSnapshotReads.clear();
		npcObservedSpeech = "Darken my shadow!";
		plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(boss, "Fill my soul with smoke!"));
		tick();
		failedPlayerSnapshotReads.add("getCombatLevel");
		plugin.onPlayerChanged(new net.runelite.api.events.PlayerChanged(player));
		plugin.onInteractingChanged(new net.runelite.api.events.InteractingChanged(boss, null));
		plugin.onOverheadTextChanged(new net.runelite.api.events.OverheadTextChanged(boss, null));
		tick();
		failedPlayerSnapshotReads.clear();
		com.google.gson.JsonObject seed = com.tickreplaylogger.capture.NpcDefinitionTestData.transforms().iterator()
			.next();
		npcId = seed.get("npcId").getAsInt();
		npcUnresolvedTransform = true;
		npcTransformIds = com.tickreplaylogger.capture.NpcDefinitionTestData.configs(npcId);
		npcNameNull = true;
		npcObservedSpeech = null;
		oldIdUnavailable[0] = true;
		plugin.onNpcChanged(new net.runelite.api.events.NpcChanged(boss, oldNex));
		tick();
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Different observed NPC";
		npcUnresolvedTransform = false;
		npcTransformIds = null;
		npcNameNull = false;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		tick();
		npcObservedName = "Corrupted Hunllef";
		npcObservedSize = 5;
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		failedNpcSnapshotReads.add("getIndex");
		plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		failedNpcSnapshotReads.clear();
		npcs.clear();
		hp = 0;
		hit(HitsplatID.POISON, 99);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-actor-callbacks");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/actor-callbacks.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(7, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void npcChangeKeepsTheEventOldCompositionSeparateFromTheCurrentActorDefinition()
	{
		assertOldComposition(false);
	}

	@Test
	public void failedCallbackWorldPositionDoesNotEraseKnownLocalPositionOrDefinition()
	{
		npcId = 9036;
		npcObservedName = "Corrupted Hunllef";
		npcLocalPosition = new LocalPoint(6464, 6592);
		failNpcWorldPosition = true;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_SPAWNED");
		assertEquals(6464, raw.get("localX").getAsInt());
		assertEquals(9036, raw.get("npcId").getAsInt());
		assertFalse(raw.has("worldX"));
		assertFalse(raw.has("plane"));
		assertFalse(raw.get("planeKnown").getAsBoolean());
	}

	@Test
	public void missingLocalIdentityDoesNotLabelAnObservedPlayerInteractionAsAnotherPlayer()
	{
		tick();
		failLocalPlayer = true;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		failLocalPlayer = false;
		tick();
		com.google.gson.JsonObject target = callbackData("NPC_SPAWNED").getAsJsonObject("interaction");
		assertEquals("PLAYER", target.get("actorKind").getAsString());
		assertFalse(target.has("actorRef"));
	}

	@Test
	public void missingLocalIdentityWithPlayerOptOutDoesNotReadOrMislabelTheUnknownPlayer() throws Exception
	{
		tick();
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		Player known = player;
		player = stub(Player.class, (name, args) ->
		{
			throw new AssertionError("Player identity unavailable, metadata opted out: " + name);
		});
		failLocalPlayer = true;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		failLocalPlayer = false;
		player = known;
		tick();
		com.google.gson.JsonObject target = callbackData("NPC_SPAWNED").getAsJsonObject("interaction");
		assertEquals("PLAYER", target.get("actorKind").getAsString());
		assertEquals("LOCAL_IDENTITY_UNAVAILABLE_PLAYER_CAPTURE_DISABLED", target.get("evidence").getAsString());
		assertFalse(target.has("actorName"));
	}

	@Test
	public void failedOldCompositionIdentityKeepsItsKnownNameAndSizeAndTheCurrentNpcFacts()
	{
		assertOldComposition(true);
	}

	private void assertOldComposition(boolean missingId)
	{
		npcId = 2042;
		npcObservedName = "Zulrah";
		npcObservedSize = 5;
		net.runelite.api.NPCComposition old = stub(net.runelite.api.NPCComposition.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				if (missingId)
					throw new IllegalStateException("Old composition ID unavailable");
				return 11278;
			case "getName":
				return "Nex";
			case "getSize":
				return 3;
			case "getCombatLevel":
				return 1001;
			default:
				return null;
			}
		});
		plugin.onNpcChanged(new net.runelite.api.events.NpcChanged(boss, old));
		tick();
		com.google.gson.JsonObject raw = callbackData("NPC_CHANGED");
		assertEquals(2042, raw.get("npcId").getAsInt());
		com.google.gson.JsonObject prior = raw.getAsJsonObject("oldComposition");
		assertNotNull("NpcChanged's actual old-composition payload must survive independently", prior);
		assertEquals("Nex", prior.get("name").getAsString());
		assertEquals(3, prior.get("size").getAsInt());
		if (missingId)
		{
			assertFalse(prior.has("id"));
			assertTrue(prior.getAsJsonObject("observationIssues").has("id"));
		}
		else
			assertEquals(11278, prior.get("id").getAsInt());
		assertEquals("NPC_CHANGED_OLD_COMPOSITION_EVENT_PAYLOAD", prior.get("evidence").getAsString());
	}

	@Test
	public void interactionCallbackKeepsItsTargetPayloadSeparateFromTheActorsLaterTarget()
	{
		npcId = 9036;
		npcObservedName = "Corrupted Hunllef";
		NPC original = stub(NPC.class,
			(name, args) -> name.equals("getName") ? "Earlier target" : name.equals("getIndex") ? 77 : null);
		plugin.onInteractingChanged(new net.runelite.api.events.InteractingChanged(boss, original));
		tick();
		com.google.gson.JsonObject raw = callbackData("INTERACTING_CHANGED");
		assertEquals("Earlier target", raw.getAsJsonObject("interaction").get("actorName").getAsString());
		assertEquals("EVENT_TARGET_PAYLOAD", raw.getAsJsonObject("interaction").get("targetEvidence").getAsString());
		assertEquals("LOCAL_PLAYER",
			raw.getAsJsonObject("actorState").getAsJsonObject("interaction").get("actorKind").getAsString());
	}

	@Test
	public void interactionCallbackKeepsAnExplicitClearEvenIfTheActorNowReportsAnotherTarget()
	{
		plugin.onInteractingChanged(new net.runelite.api.events.InteractingChanged(boss, null));
		tick();
		com.google.gson.JsonObject target = callbackData("INTERACTING_CHANGED").getAsJsonObject("interaction");
		assertEquals("EVENT_TARGET_CLEARED", target.get("evidence").getAsString());
		assertFalse(target.has("actorRef"));
	}

	@Test
	public void optionalInteractionHistoryFailureDoesNotAbortOrEraseTheCallback()
	{
		npcId = 9036;
		npcObservedName = "Corrupted Hunllef";
		npcLocalPosition = new LocalPoint(6464, 6592);
		failedNpcSnapshotReads.add("getIndex");
		RuntimeException failure = null;
		try
		{
			plugin.onInteractingChanged(new net.runelite.api.events.InteractingChanged(boss, player));
		}
		catch (RuntimeException unavailable)
		{
			failure = unavailable;
		}
		tick();
		assertNull("Optional recent-attacker history cannot abort event capture", failure);
		assertEquals(6464, callbackData("INTERACTING_CHANGED").getAsJsonObject("actorState").get("localX").getAsInt());
	}

	@Test
	public void unresolvedNpcTransformKeepsEffectiveProjectileIdentityUnknown()
	{
		com.google.gson.JsonObject seed = com.tickreplaylogger.capture.NpcDefinitionTestData.transforms().iterator()
			.next();
		npcId = seed.get("npcId").getAsInt();
		npcLevel = -1;
		npcObservedSize = seed.get("size").getAsInt();
		npcObservedName = seed.get("name").getAsString();
		npcNameNull = true;
		npcTransformIds = com.tickreplaylogger.capture.NpcDefinitionTestData.configs(npcId);
		npcUnresolvedTransform = true;
		projectile(boss, 1712);
		tick();
		com.tickreplaylogger.model.world.NpcState npc = world.snapshotAtTick(1).getNpcs().get(0);
		assertEquals("TRANSFORM_UNAVAILABLE", npc.getDefinitionEvidence());
		assertNull(npc.getNpcId());
		com.tickreplaylogger.model.world.ProjectileState shot = world.snapshotAtTick(1).getProjectiles().get(0);
		assertEquals(npc.getActorRef(), shot.getSourceRef());
		assertNull("A known unresolved transform cannot become an effective base definition", shot.getSourceNpcId());
		assertNull(shot.getDamageType());
	}

	@Test
	public void unresolvedNpcTransformCannotSupplyAProjectileDamageOrPrayerRule()
	{
		// Structural API-state control only: the current archived Hunllef definition has no configs.
		npcId = 9036;
		npcLevel = 894;
		npcObservedSize = 5;
		npcObservedName = "Corrupted Hunllef";
		npcTransformIds = new int[]
		{ 9036, 9037 };
		npcUnresolvedTransform = true;
		projectile(boss, 1712);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("unknown", capturedLastDamage().getDamageType());
		assertEquals("UNKNOWN", capturedLastDamage().getDetermination());
		assertNull(capturedLastDamage().getDeterminationRuleKey());
	}

	@Test
	public void failedProjectileSourceNameDoesNotDiscardIndependentFacts()
	{
		assertIndependentProjectileFacts("getName");
	}

	@Test
	public void failedProjectileSourceLevelDoesNotDiscardIndependentFacts()
	{
		assertIndependentProjectileFacts("getCombatLevel");
	}

	@Test
	public void failedProjectileSourceIndexDoesNotDiscardIndependentFacts()
	{
		assertIndependentProjectileFacts("getIndex");
	}

	@Test
	public void failedProjectileSourceViewDoesNotDiscardIndependentFacts()
	{
		assertIndependentProjectileFacts("getWorldView");
	}

	@Test
	public void unavailableProjectileTimingRemainsAnAlternativeToAnObservedFloor()
	{
		addGauntletFloor(36048);
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		Projectile shot = stub(Projectile.class, (name, args) ->
		{
			if (name.equals("getRemainingCycles") || name.equals("getEndCycle"))
				throw new IllegalStateException("Unavailable lifetime");
			return name.equals("getId") ? 1712
				: name.equals("getSourceActor") ? boss : name.equals("getTargetActor") ? player : null;
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		plugin.onProjectileMoved(moved);
		tick();
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals("Unknown", damage.getAttackerName());
		assertTrue(damage.getSourceResolution().isUnidentifiedPossible());
		assertTrue(damage.getAttributionCandidates().stream().anyMatch(witness ->
			"PROJECTILE_OBSERVATION".equals(witness.get("kind"))
				&& witness.get("projectile") instanceof com.tickreplaylogger.model.world.ProjectileState
				&& ((com.tickreplaylogger.model.world.ProjectileState) witness.get("projectile")).getEndCycle() == null
				&& ((com.tickreplaylogger.model.world.ProjectileState) witness.get("projectile")).getRemainingCycles() == null));
		assertTrue(damage.getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> Integer.valueOf(9036).equals(candidate.getNpcId())));
		assertTrue(damage.getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> "Corrupted Hunllef floor".equals(candidate.getName())));
	}

	@Test
	public void latePlayerTargetBindingIsRecordedEvenWhenTheTargetPositionDoesNotChange()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		boolean[] bound =
		{ false };
		int firstObservedCycle = gameCycle;
		Projectile shot = stub(Projectile.class,
			(name, args) -> name.equals("getId") ? 1712
				: name.equals("getSourceActor") ? boss
					: name.equals("getTargetActor") ? (bound[0] ? player : null)
						: name.equals("getRemainingCycles") ? 30
							: name.equals("getStartCycle") ? firstObservedCycle : name.equals("getEndCycle") ? gameCycle + 30 : null);
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		moved.setPosition(new LocalPoint(6400, 6400));
		plugin.onProjectileMoved(moved);
		bound[0] = true;
		plugin.onProjectileMoved(moved);
		tick();
		assertEquals(1, export().getEvents().getObservations().stream()
			.filter(e -> "PROJECTILE_RELATIONSHIP_CHANGED".equals(e.getType())).count());
		assertEquals(Boolean.TRUE, world.snapshotAtTick(1).getProjectiles().get(0).getTargetsPlayer());
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("ranged", capturedLastDamage().getDamageType());
	}

	@Test
	public void firstProjectileSourceSurvivesTheWindowAndDoesNotBecomeTheLaterForm()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> 120);
		moveSourceShot(shot);
		tick();
		npcId = 9037;
		for (int i = 0; i < 4; i++)
			tick();
		world.resize(2);
		com.google.gson.JsonObject raw = gson.toJsonTree(export().getTicks().get(0).getProjectiles().get(0))
			.getAsJsonObject();
		assertEquals(9037, raw.get("sourceNpcId").getAsInt());
		com.google.gson.JsonObject history = raw.getAsJsonObject("sourceHistory");
		assertNotNull("A trimmed event must not erase the original source observation", history);
		assertEquals(9036, history.getAsJsonObject("firstObservedSource").get("sourceNpcId").getAsInt());
		assertEquals(1, history.getAsJsonObject("firstObservedSource").get("observedTick").getAsInt());
		assertEquals(0, export().getEvents().getObservations().stream()
			.filter(e -> "PROJECTILE_FIRST_SEEN".equals(e.getType())).count());
	}

	@Test
	public void aDifferentReportedSourceActorCannotKeepAConfidentProjectileHit()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Actor[] source =
		{ boss };
		Projectile shot = sourceHistoryShot(() -> source[0], () -> player, () -> 60);
		moveSourceShot(shot);
		tick();
		source[0] = npc("Corrupted Hunllef", 1);
		moveSourceShot(shot);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertUnresolvedSource(capturedLastDamage());
		assertRetainedProjectileConflict(capturedLastDamage());
		DamageRecord observed = capturedLastDamage();
		assertTrue("The paired contradiction is an actual callback before HP, not a later settled sample",
			export().getEvents().getObservations().stream().anyMatch(event -> event.getType().startsWith("PROJECTILE_")
				&& event.getSequence() < observed.getObservationSequence() && event.getGameCycle() <= observed.getObservedCycle()
				&& event.getData().get("projectile") instanceof com.tickreplaylogger.model.world.ProjectileState
				&& ((com.tickreplaylogger.model.world.ProjectileState) event.getData().get("projectile"))
					.getSourceHistory().isActorChanged()));
	}

	@Test
	public void aLaterTickSourceConflictCannotRewriteTheOriginalPreHitOwner()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Actor[] source =
		{ boss };
		Projectile shot = sourceHistoryShot(() -> source[0], () -> player, () -> 120);
		moveSourceShot(shot);
		tick();
		source[0] = npc("Corrupted Hunllef", 1);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		DamageRecord observed = capturedLastDamage();
		// Original owner: cycle500. Actual HP:530. Later conflicting tick sample:560. Arrival:620.
		assertEquals("Corrupted Hunllef", com.tickreplaylogger.util.SourceResolutionLabels.label(observed.getSourceResolution()));
		assertEquals("UNKNOWN", observed.getAttribution());
		assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
		assertRetainedProjectileConflict(observed);
		assertTrue(observed.getAttributionCandidates().stream().filter(witness -> "PROJECTILE_OBSERVATION".equals(witness.get("kind")))
			.anyMatch(witness -> gson.toJsonTree(witness.get("projectile")).getAsJsonObject().get("observedCycle").getAsInt()
				> observed.getObservedCycle()));
	}

	@Test
	public void tickSourceSamplingIsSharedByAttributionAndTheWorldExport()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		int[] reads =
		{ 0 };
		Projectile shot = sourceHistoryShot(() ->
		{
			reads[0]++;
			return boss;
		}, () -> player, () -> 60);
		moveSourceShot(shot);
		tick();
		reads[0] = 0;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals(1, reads[0]);
		assertEquals("Corrupted Hunllef", capturedLastDamage().getAttackerName());
		assertEquals(Integer.valueOf(2), world.snapshotAtTick(2).getProjectiles().get(0).getObservedTick());
	}

	@Test
	public void lateTargetBindingKeepsTheFirstObservedSourceFormRatherThanTheActorsLaterForm()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		boolean[] target =
		{ false };
		Projectile shot = sourceHistoryShot(() -> boss, () -> target[0] ? player : null, () -> 30);
		moveSourceShot(shot);
		tick();
		npcId = 9037;
		target[0] = true;
		moveSourceShot(shot);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertEquals(Integer.valueOf(9036), capturedLastDamage().getAttackerNpcId());
	}

	@Test
	public void endpointCluesUseTheSameRecordedFactsForLocalTileAndOtherActorTargets()
	{
		endpointContext();
		Player other = stub(Player.class, (name, args) ->
		{
			if (name.equals("getWorldView")) return projectileNativeView;
			if (name.equals("getWorldLocation")) return position;
			if (name.equals("getLocalLocation")) return localPosition;
			if (name.equals("getName")) return "Synthetic target";
			if (name.equals("getId")) return 42;
			return null;
		});
		for (int id : new int[] { 1481, 1482 })
			for (Actor target : new Actor[] { player, null, other })
			{
				int end = gameCycle + 30;
				Projectile shot = endpointShot(id, () -> boss, () -> target, () -> end, () -> position);
				moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
				long observation = latestEndpointObservation();
				gameCycle++;
				hit(HitsplatID.DAMAGE_ME, 7);
				tick();
				DamageRecord recorded = capturedLastDamage();
				assertEquals(7, recorded.getAmount());
				assertTrue("projectile " + id + "; target=" + target, endpointClue(recorded, observation));
				assertTrue(recorded.getSourceResolution().isUnidentifiedPossible());
				assertTrue(recorded.getSourceResolution().getCandidates().stream()
					.anyMatch(candidate -> "Vorkath".equals(candidate.getName())));
				assertTrue(export().getEvents().getObservations().stream().anyMatch(event ->
				{
					if(!event.getType().startsWith("PROJECTILE") || event.getSequence()>=recorded.getObservationSequence()
						|| event.getGameCycle()==null || event.getGameCycle()>recorded.getObservedCycle())return false;
					com.google.gson.JsonElement original = gson.toJsonTree(event.getData().get("projectile"));
					if (!original.isJsonObject())
					{
						return false;
					}
					com.google.gson.JsonObject sample = original.getAsJsonObject();
					return sample.has("observationId") && sample.get("observationId").getAsLong() == observation
						&& sample.has("projectileId") && sample.get("projectileId").getAsInt() == id
						&& sample.has("observedCycle") && sample.get("observedCycle").getAsInt() <= recorded.getObservedCycle()
						&& sample.has("endCycle") && Math.abs(recorded.getObservedCycle() - sample.get("endCycle").getAsInt()) <= 30;
				})); // The declaration permits +/-30 client cycles; it does not prove a server arrival.
			}
		assertEquals(42, export().getEvents().getDamageEvents().stream().filter(value -> "RECEIVED".equals(value.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void endpointClueCanUseAnActualOriginalTickSampleBeforeTheFirstMoveCallback() throws Exception
	{
		endpointContext();
		int end = gameCycle + 60;
		Projectile shot = endpointShot(1481, () -> boss, () -> null, () -> end, () -> position);
		listedProjectiles = new ArrayList<>(); listedProjectiles.add(shot);
		tick();
		assertEquals("TICK_SAMPLE", world.snapshotAtTick(1).getProjectiles().get(0).getSourceHistory()
			.getFirstObservedSource().getSampleEvidence());
		moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
		long observation = latestEndpointObservation();
		gameCycle++;
		hit(HitsplatID.DAMAGE_ME, 7);
		hp -= 7;
		tick();
		assertTrue(endpointClue(capturedLastDamage(), observation));
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/projectile-endpoint-clue.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(export()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void endpointClueRetainsTheObservedSourceAfterThatNpcDespawns()
	{
		endpointContext();
		int end = gameCycle + 30;
		Projectile shot = endpointShot(1481, () -> boss, () -> null, () -> end, () -> position);
		moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
		long observation = latestEndpointObservation();
		plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		npcs.clear();
		gameCycle++;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertTrue(endpointClue(capturedLastDamage(), observation));
		assertEquals(7, capturedLastDamage().getAmount());
	}

	@Test
	public void endpointCallbackChangesAtTheSameLocalPointCannotReviveAnEarlierDestination()
	{
		endpointContext();
		for (int change = 0; change < 3; change++)
		{
			int[] end = { gameCycle + 30 };
			WorldPoint[] destination = { position };
			Projectile shot = endpointShot(1481, () -> boss, () -> null, () -> end[0], () -> destination[0]);
			moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
			long observation = latestEndpointObservation();
			if (change == 0) destination[0] = new WorldPoint(position.getX() + 2, position.getY(), 0);
			if (change == 1) destination[0] = new WorldPoint(position.getX(), position.getY(), 1);
			if (change == 2) end[0] += 300;
			gameCycle++;
			moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
			gameCycle++;
			hit(HitsplatID.DAMAGE_ME, 7);
			tick();
			assertFalse("same-point change " + change, endpointClue(capturedLastDamage(), observation));
			assertTrue(world.observationsForSourceResolution().stream().anyMatch(event ->
				java.util.Arrays.asList("PROJECTILE_RELATIONSHIP_CHANGED", "PROJECTILE_TARGET_CHANGED").contains(event.getType())
					&& event.getData().get("projectile") instanceof com.tickreplaylogger.model.world.ProjectileState
					&& Long.valueOf(observation).equals(((com.tickreplaylogger.model.world.ProjectileState) event.getData().get("projectile")).getObservationId())));
		}
	}

	@Test
	public void endpointClueUsesOnlyAPositiveApiSourceObservedBeforeTheHit()
	{
		endpointContext();
		Actor[] source = { null };
		int end = gameCycle + 30;
		Projectile shot = endpointShot(1481, () -> source[0], () -> null, () -> end, () -> position);
		moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
		long observation = latestEndpointObservation();
		source[0] = boss;
		gameCycle++;
		moveEndpointShot(shot, new LocalPoint(6464, 6464, projectileNativeView));
		gameCycle++;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertTrue(endpointClue(capturedLastDamage(), observation));
		assertTrue(capturedLastDamage().getSourceResolution().isUnidentifiedPossible());
	}

	private void endpointContext()
	{
		useProjectileNativeView();
		npcId = 8061; npcObservedEffectiveId = 8061; npcLevel = 732; npcObservedSize = 7;
		npcObservedName = "Vorkath"; npcLocalPosition = new LocalPoint(6464, 6336, projectileNativeView);
		animation = -1;
	}

	private Projectile endpointShot(int id, java.util.function.Supplier<Actor> source, java.util.function.Supplier<Actor> target,
		java.util.function.IntSupplier end, java.util.function.Supplier<WorldPoint> destination)
	{
		Projectile original = sourceHistoryShot(source, target, () -> Math.max(1, end.getAsInt() - gameCycle));
		return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(), new Class<?>[] { Projectile.class }, (proxy, method, args) ->
		{
			if (method.getName().equals("getId")) return id;
			if (method.getName().equals("getEndCycle")) return end.getAsInt();
			if (method.getName().equals("getTargetPoint")) return destination.get();
			return method.invoke(original, args);
		});
	}

	private void moveEndpointShot(Projectile shot, LocalPoint destination)
	{
		ProjectileMoved moved = new ProjectileMoved(); moved.setProjectile(shot); moved.setPosition(destination);
		plugin.onProjectileMoved(moved);
	}

	private long latestEndpointObservation()
	{
		return world.observationsForSourceResolution().stream().filter(event -> java.util.Arrays.asList(
			"PROJECTILE_FIRST_SEEN", "PROJECTILE_TARGET_CHANGED", "PROJECTILE_RELATIONSHIP_CHANGED").contains(event.getType()))
			.map(event -> (com.tickreplaylogger.model.world.ProjectileState) event.getData().get("projectile"))
			.reduce((first, last) -> last).orElseThrow(AssertionError::new).getObservationId();
	}

	private static boolean endpointClue(DamageRecord hit, long observation)
	{
		return hit.getSourceResolution() != null && hit.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			candidate.getEvidence().stream().anyMatch(e -> e.startsWith("PROJECTILE_ENDPOINT_SOURCE_RULE:"))
				&& candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:" + observation));
	}

	private Projectile sourceHistoryShot(java.util.function.Supplier<Actor> source,
		java.util.function.Supplier<Actor> target, java.util.function.IntSupplier remaining)
	{
		int firstObservedCycle = gameCycle;
		return stub(Projectile.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return 1712;
			case "getSourceActor":
				return source.get();
			case "getTargetActor":
				return target.get();
			case "getRemainingCycles":
				return remaining.getAsInt();
			case "getStartCycle":
				return firstObservedCycle;
			case "getEndCycle":
				return gameCycle + remaining.getAsInt();
			case "getSourcePoint":
				return new WorldPoint(3200, 3199, 0);
			case "getTargetPoint":
				return position;
			case "getX":
				return 6464D;
			case "getY":
				return 6592D;
			case "getZ":
				return 128D;
			default:
				return null;
			}
		});
	}

	private void moveSourceShot(Projectile shot)
	{
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		moved.setPosition(new LocalPoint(6400, 6400));
		plugin.onProjectileMoved(moved);
	}

	@Test
	public void aLaterActorObservationCannotFillTheOriginallyUnknownSource()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Actor[] source =
		{ null };
		Projectile shot = sourceHistoryShot(() -> source[0], () -> player, () -> 120);
		moveSourceShot(shot);
		tick();
		com.tickreplaylogger.model.world.ProjectileState original = world.snapshotAtTick(1).getProjectiles().get(0);
		String before = gson.toJson(original);
		source[0] = boss;
		moveSourceShot(shot);
		tick();
		com.tickreplaylogger.model.world.ProjectileSourceHistory history = world.snapshotAtTick(2).getProjectiles()
			.get(0).getSourceHistory();
		assertEquals("UNKNOWN", history.getFirstObservedSource().getSourceEvidence());
		assertNull(history.getFirstObservedSource().getSourceNpcId());
		assertEquals(Integer.valueOf(2), history.getFirstActorSource().getObservedTick());
		assertEquals(Integer.valueOf(9036), history.getFirstActorSource().getSourceNpcId());
		assertEquals("Later observations must not mutate prior snapshots", before, gson.toJson(original));
	}

	@Test
	public void retiringAnActorReferenceDoesNotInventADifferentReportedSourceActor()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> 60);
		moveSourceShot(shot);
		tick();
		plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		moveSourceShot(shot);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		com.tickreplaylogger.model.world.ProjectileSourceHistory h = world.snapshotAtTick(2).getProjectiles().get(0)
			.getSourceHistory();
		assertFalse(h.isActorChanged());
		assertTrue(h.isReferenceChanged());
		DamageRecord damage=capturedLastDamage();
		assertEquals("Unknown",damage.getAttackerName());
		assertEquals("UNKNOWN",damage.getAttribution());
		assertTrue(damage.getSourceResolution().isUnidentifiedPossible());
		assertTrue(damage.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			"Corrupted Hunllef".equals(candidate.getName()) && "POSSIBLE".equals(candidate.getStrength())
				&& h.firstActor().getSourceRef().equals(candidate.getActorRef()) && Integer.valueOf(9036).equals(candidate.getNpcId())));
		assertEquals("Corrupted Hunllef",h.firstActor().getSourceObservedName());
		assertNotNull(h.firstActor().getSourceRef());
		assertEquals(Integer.valueOf(500),h.firstActor().getObservedCycle());
		assertEquals(Integer.valueOf(530),damage.getObservedCycle());
		assertEquals(Integer.valueOf(620),world.snapshotAtTick(2).getProjectiles().get(0).getEndCycle());
		// The original owner remains an alternative; a hit 90 cycles before visual arrival is not arrival proof.
		assertEquals("unknown",damage.getDamageType());
		assertEquals("UNKNOWN",damage.getDamageTypeEvidence());
		assertEquals("UNKNOWN",damage.getDetermination());
		assertTrue(damage.getDamageTypeCandidates().contains("ranged"));
	}

	@Test
	public void optingOutDoesNotExtendAnOldOtherPlayerNameIntoANewWindow() throws Exception
	{
		npcs.clear();
		int[] reads =
		{ 0 };
		Player other = stub(Player.class, (n, a) ->
		{
			if (n.equals("getName"))
			{
				reads[0]++;
				return "Sensitive historical player";
			}
			return n.equals("getId") ? 77 : null;
		});
		Projectile shot = sourceHistoryShot(() -> other, () -> boss, () -> 120);
		moveSourceShot(shot);
		tick();
		int before = reads[0];
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}

			@Override
			public boolean captureSceneCollision()
			{
				return false;
			}
		});
		world.resize(1);
		moveSourceShot(shot);
		tick();
		assertEquals(before, reads[0]);
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first = world.snapshotAtTick(2)
			.getProjectiles().get(0).getSourceHistory().getFirstObservedSource();
		assertEquals("Other player", first.getSourceName());
		assertNull(first.getSourceObservedName());
		assertNull(first.getSourceRef());
		assertFalse(gson.toJson(export()).contains("Sensitive historical player"));
	}

	@Test
	public void aRetiredApiObjectBeginsANewSourceHistoryWhenItReturns()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		int[] remaining =
		{ 60 };
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> remaining[0]);
		moveSourceShot(shot);
		tick();
		Long first = world.snapshotAtTick(1).getProjectiles().get(0).getObservationId();
		remaining[0] = 0;
		tick();
		assertTrue(world.snapshotAtTick(2).getProjectiles().isEmpty());
		remaining[0] = 120;
		npcId = 9037;
		moveSourceShot(shot);
		tick();
		com.tickreplaylogger.model.world.ProjectileState returned = world.snapshotAtTick(3).getProjectiles().get(0);
		assertNotEquals(first, returned.getObservationId());
		assertEquals(Integer.valueOf(3), returned.getSourceHistory().getFirstObservedSource().getObservedTick());
		assertEquals(Integer.valueOf(9037), returned.getSourceHistory().getFirstObservedSource().getSourceNpcId());
		assertFalse(returned.getSourceHistory().isDefinitionChanged());
	}

	@Test
	public void aReusedApiObjectCannotOverwriteTheOlderPendingFlightsSourceHistory()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Actor[] source =
		{ boss };
		Projectile shot = sourceHistoryShot(() -> source[0], () -> player, () ->
		{
			throw new IllegalStateException("Unknown lifetime");
		});
		listedProjectiles = new ArrayList<>();
		listedProjectiles.add(shot);
		moveSourceShot(shot);
		tick();
		listedProjectiles.clear();
		tick();
		source[0] = npc("Corrupted Hunllef", 1);
		listedProjectiles.add(shot);
		moveSourceShot(shot);
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		java.util.Set<Long> histories = new java.util.HashSet<>();
		for (Map<String, Object> candidate : capturedLastDamage().getAttributionCandidates())
			if ("PROJECTILE_OBSERVATION".equals(candidate.get("kind")))
			{
				com.tickreplaylogger.model.world.ProjectileSourceHistory h = (com.tickreplaylogger.model.world.ProjectileSourceHistory) candidate
					.get("sourceHistory");
				histories.add(h.getFirstObservedSource().getObservationId());
			}
		assertEquals("Two registered lifetimes must not share the returning object's new source anchor", 2,
			histories.size());
	}

	@Test
	public void aReloadRetainsTheHistoricalFactWithoutReusingItsSourceReference()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> 120);
		moveSourceShot(shot);
		tick();
		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(reload);
		moveSourceShot(shot);
		tick();
		com.tickreplaylogger.model.world.ProjectileState state = world.snapshotAtTick(2).getProjectiles().get(0);
		assertEquals(Integer.valueOf(1), state.getSourceHistory().getFirstObservedSource().getObservedTick());
		assertTrue(state.getSourceHistory().isScopeChanged());
		assertNull(state.getDamageType());
	}

	@Test
	public void collectionOnlySamplesRetainTheirFirstSourceWithoutInventingACallback()
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Projectile shot = sourceHistoryShot(() -> boss, () -> player, () -> 120);
		listedProjectiles = new ArrayList<>();
		listedProjectiles.add(shot);
		tick();
		npcId = 9037;
		tick();
		com.tickreplaylogger.model.world.ProjectileState state = world.snapshotAtTick(2).getProjectiles().get(0);
		assertEquals("CLIENT_COLLECTION", state.getCollectionEvidence());
		assertEquals("TICK_SAMPLE", state.getSourceHistory().getFirstObservedSource().getSampleEvidence());
		assertEquals(Integer.valueOf(9036), state.getSourceHistory().getFirstObservedSource().getSourceNpcId());
		assertTrue(export().getEvents().getObservations().isEmpty());
	}

	private void useProjectileNativeView()
	{
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		hazardScene = stub(net.runelite.api.Scene.class, (n, a) -> n.equals("getTiles") ? tiles : null);
		net.runelite.api.IndexedObjectSet<NPC> actors = stub(net.runelite.api.IndexedObjectSet.class,
			(n, a) -> n.equals("iterator") ? npcs.iterator() : null);
		net.runelite.api.IndexedObjectSet<Player> players = stub(net.runelite.api.IndexedObjectSet.class, (n, a) ->
		{
			if (!n.equals("iterator"))
				return null;
			List<Player> values = new ArrayList<>(otherPlayers);
			values.add(player);
			return values.iterator();
		});
		net.runelite.api.Deque<GraphicsObject> graphics = stub(net.runelite.api.Deque.class,
			(n, a) -> n.equals("iterator") ? graphicsObjects.iterator() : null);
		projectileNativeView = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return net.runelite.api.WorldView.TOPLEVEL;
			case "getScene":
				return hazardScene;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			case "getSizeX":
			case "getSizeY":
				return 104;
			case "npcs":
				return actors;
			case "players":
				return players;
			case "getGraphicsObjects":
				return graphics;
			default:
				return null;
			}
		});
	}

	@Test
	public void sourceHistoryExportKeepsPreWindowFactsFormsConflictsAndANewObjectLifetime() throws Exception
	{
		useProjectileNativeView();
		npcId = 9036;
		npcLevel = 894;
		npcObservedName = "Corrupted Hunllef";
		npcs.clear();
		Actor[] source =
		{ boss };
		int[] remaining =
		{ 120 };
		Projectile shot = sourceHistoryShot(() -> source[0], () -> player, () -> remaining[0]);
		listedProjectiles = new ArrayList<>();
		listedProjectiles.add(shot);
		moveSourceShot(shot);
		tick();
		npcId = 9037;
		tick();
		tick();
		world.resize(6);
		journal.resize(6);
		source[0] = null;
		moveSourceShot(shot);
		tick();
		source[0] = npc("Corrupted Hunllef", 1);
		moveSourceShot(shot);
		tick();
		hp = 93;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		assertUnresolvedSource(capturedLastDamage());
		remaining[0] = 0;
		assertRetainedProjectileConflict(capturedLastDamage());
		tick();
		remaining[0] = 120;
		moveSourceShot(shot);
		npcs.add(boss);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hp = 0;
		hit(HitsplatID.POISON, 93);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-projectile-source-history");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/projectile-source-history.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(6, out.getAsJsonArray("ticks").size());
		assertEquals(3, out.getAsJsonArray("ticks").get(0).getAsJsonObject().get("tick").getAsInt());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void partialProjectileExportKeepsUnresolvedDefinitionsFailedFieldsRecoveryAndDeath() throws Exception
	{
		npcId = 12077;
		npcLevel = 741;
		npcObservedName = "Phantom Muspah";
		npcObservedSize = 5;
		npcLocalPosition = new LocalPoint(6592, 6592);
		net.runelite.api.Tile[][][] tiles = new net.runelite.api.Tile[1][104][104];
		net.runelite.api.Scene scene = stub(net.runelite.api.Scene.class,
			(n, a) -> n.equals("getTiles") ? tiles : null);
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return net.runelite.api.WorldView.TOPLEVEL;
			case "getScene":
				return scene;
			case "getBaseX":
			case "getBaseY":
				return 3150;
			case "getSizeX":
			case "getSizeY":
				return 104;
			default:
				return null;
			}
		});
		cueView = view;
		projectileNativeView = null;
		hazardScene = scene;
		NPC originalBoss = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]
		{ NPC.class }, (proxy, method, args) ->
		{
			if (method.getName().equals("getWorldView") && !failedNpcSnapshotReads.contains("getWorldView"))
				return view;
			try
			{
				return method.invoke(originalBoss, args);
			}
			catch (java.lang.reflect.InvocationTargetException unavailable)
			{
				throw unavailable.getCause();
			}
		});
		npcs.clear();
		npcs.add(boss);
		Field field = TickReplayLoggerPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		Client original = (Client) field.get(plugin);
		Client scoped = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]
		{ Client.class }, (proxy, method, args) ->
		{
			if (method.getName().equals("getTopLevelWorldView"))
				return view;
			if (method.getName().equals("getScene"))
				return scene;
			if (method.getName().equals("getWorldView"))
				return ((Integer) args[0]) == net.runelite.api.WorldView.TOPLEVEL ? view : null;
			try
			{
				return method.invoke(original, args);
			}
			catch (java.lang.reflect.InvocationTargetException unavailable)
			{
				throw unavailable.getCause();
			}
		});
		inject("client", scoped);
		java.util.Set<String> failures = new java.util.HashSet<>();
		Projectile shot = stub(Projectile.class, (name, args) ->
		{
			if (failures.contains(name))
				throw new IllegalStateException("Synthetic unavailable " + name);
			switch (name)
			{
			case "getId":
				return 2327;
			case "getSourceActor":
				return boss;
			case "getTargetActor":
				return player;
			case "getRemainingCycles":
				return 60;
			case "getSourcePoint":
				return new WorldPoint(3200, 3199, 0);
			case "getTargetPoint":
				return position;
			case "getTarget":
				return new LocalPoint(6400, 6400, net.runelite.api.WorldView.TOPLEVEL);
			case "getX":
				return 6464D;
			case "getY":
				return 6592D;
			case "getZ":
				return 128D;
			default:
				return null;
			}
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		moved.setPosition(new LocalPoint(6400, 6400, net.runelite.api.WorldView.TOPLEVEL));
		plugin.onProjectileMoved(moved);
		tick();
		failedNpcSnapshotReads.add("getName");
		plugin.onProjectileMoved(moved);
		tick();
		failedNpcSnapshotReads.clear();
		failedNpcSnapshotReads.add("getCombatLevel");
		plugin.onProjectileMoved(moved);
		tick();
		failedNpcSnapshotReads.clear();
		failures.addAll(java.util.Arrays.asList("getSourceLevel", "getTargetLevel", "getTargetActor",
			"getRemainingCycles", "getEndCycle"));
		tick();
		failures.clear();
		com.google.gson.JsonObject seed = com.tickreplaylogger.capture.NpcDefinitionTestData.transforms().iterator()
			.next();
		npcId = seed.get("npcId").getAsInt();
		npcObservedName = seed.get("name").getAsString();
		npcNameNull = true;
		npcLevel = -1;
		npcUnresolvedTransform = true;
		npcTransformIds = com.tickreplaylogger.capture.NpcDefinitionTestData.configs(npcId);
		plugin.onProjectileMoved(moved);
		tick();
		npcId = 12077;
		npcLevel = 741;
		npcObservedName = "Phantom Muspah";
		npcNameNull = false;
		npcUnresolvedTransform = false;
		npcTransformIds = null;
		plugin.onProjectileMoved(moved);
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 99);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		assertFixtureNativeScopesAreUnique(export());
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-partial-projectiles");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/partial-projectiles.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(7, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(value -> "RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(value -> "NPC_RECEIVED".equals(value.getDirection())).mapToInt(DamageRecord::getAmount).sum());
	}

	private void assertIndependentProjectileFacts(String failedRead)
	{
		npcId = 9036;
		npcLevel = 894;
		npcObservedSize = 5;
		npcObservedName = "Corrupted Hunllef";
		failedNpcSnapshotReads.add(failedRead);
		Projectile shot = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return 1712;
			case "getSourceActor":
				return boss;
			case "getTargetActor":
				return player;
			case "getRemainingCycles":
				return 60;
			case "getSourcePoint":
				return new WorldPoint(3200, 3199, 0);
			case "getTargetPoint":
				return new WorldPoint(3200, 3200, 0);
			case "getX":
				return 6464D;
			case "getY":
				return 6592D;
			case "getZ":
				return 128D;
			default:
				return null;
			}
		});
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(shot);
		moved.setPosition(new LocalPoint(6400, 6400));
		RuntimeException callbackFailure = null;
		try
		{
			plugin.onProjectileMoved(moved);
		}
		catch (RuntimeException failure)
		{
			callbackFailure = failure;
		}
		tick();
		List<com.tickreplaylogger.model.world.ProjectileState> states = world.snapshotAtTick(1).getProjectiles();
		assertEquals(failedRead + " erased a known projectile; callback=" + callbackFailure, 1, states.size());
		com.tickreplaylogger.model.world.ProjectileState observed = states.get(0);
		assertEquals(Integer.valueOf(1712), observed.getProjectileId());
		assertEquals(Double.valueOf(6464D), observed.getLocalX());
		assertEquals(Double.valueOf(6592D), observed.getLocalY());
		assertEquals(Boolean.TRUE, observed.getOriginKnown());
		assertEquals(Integer.valueOf(3200), observed.getStartWorldX());
		assertEquals(Boolean.TRUE, observed.getDestinationKnown());
		assertEquals(Integer.valueOf(3200), observed.getEndWorldY());
		assertNull("Optional actor metadata must not abort the callback", callbackFailure);
	}

	private NPC npc(String npcName, int index)
	{
		return stub(NPC.class, (name, args) ->
		{
			if (failedNpcSnapshotReads.contains(name))
				throw new IllegalStateException("synthetic unavailable NPC " + name);
			switch (name)
			{
			case "getName":
				return npcNameNull ? null : npcObservedName == null ? npcName : npcObservedName;
			case "getIndex":
				return index;
			case "getWorldView":
				return projectileNativeView;
			case "getId":
				return npcId;
			case "getTransformedComposition":
				if (failNpcDefinitionMetadata)
					throw new IllegalStateException("definition metadata unavailable");
				if (npcUnresolvedTransform)
					return null;
				if (npcObservedEffectiveId != null)
					return npcDefinition(npcName, npcObservedEffectiveId);
				return com.tickreplaylogger.capture.NpcDefinitionTestData.configs(npcId) == null
					? npcDefinition(npcName)
					: null;
			case "getComposition":
				return npcDefinition(npcName);
			case "getModelOverrides":
				return npcModelOverride
					? stub(net.runelite.api.NpcOverrides.class, (n, a) -> n.equals("getModelIds") ? new int[]
				{ 123 } : null)
					: null;
			case "getOverheadText":
				return npcObservedSpeech;
			case "getInteracting":
				return player;
			case "isInteracting":
				return npcReportsInteraction;
			case "getAnimation":
				if (failNpcAnimation)
					throw new IllegalStateException("animation unavailable");
				return animation;
			case "getAnimationFrame":
				return npcAnimationFrame;
			case "getCombatLevel":
				return npcLevel;
			case "getLocalLocation":
				return npcLocalPosition;
			case "getPoseAnimation":
				return npcPoseAnimation;
			case "getIdlePoseAnimation":
				return -1;
			case "getOverheadArchiveIds":
				return npcOverheadArchives;
			case "getOverheadSpriteIds":
				return npcOverheadSprites;
			case "getWorldLocation":
				if (failNpcWorldPosition)
					throw new IllegalStateException("position unavailable");
				return new WorldPoint(3200, 3199, 0);
			default:
				return null;
			}
		});
	}

	private net.runelite.api.NPCComposition npcDefinition(String name)
	{
		return npcDefinition(name, npcId);
	}

	private net.runelite.api.NPCComposition npcDefinition(String name, int id)
	{
		com.google.gson.JsonObject row = com.tickreplaylogger.capture.NpcDefinitionTestData.identity(id);
		return npcObservedSize == null && row == null ? null : stub(net.runelite.api.NPCComposition.class, (n, a) ->
		{
			switch (n)
			{
			case "getId":
				return id;
			case "getName":
				return npcObservedName == null ? name : npcObservedName;
			case "getSize":
				return npcObservedSize != null ? npcObservedSize : row.get("size").getAsInt();
			case "getConfigs":
				return npcTransformIds != null ? npcTransformIds
					: com.tickreplaylogger.capture.NpcDefinitionTestData.configs(id);
			default:
				return null;
			}
		});
	}

	private void projectile(Actor source, int id)
	{
		projectile(source, id, 30);
	}

	private Projectile projectile(Actor source, int id, int remainingCycles)
	{
		final int firstObservedCycle = gameCycle;
		Projectile projectile = stub(Projectile.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return id;
			case "getSourceActor":
				return source;
			case "getTargetActor":
				return player;
			case "getRemainingCycles":
				return remainingCycles;
			case "getStartCycle":
				return firstObservedCycle;
			case "getEndCycle":
				return firstObservedCycle + remainingCycles;
			default:
				return null;
			}
		});
		ProjectileMoved event = new ProjectileMoved();
		event.setProjectile(projectile);
		plugin.onProjectileMoved(event);
		return projectile;
	}

	/** Explicit capture-time analysis; the live recorder itself retains raw observations. */
	private DamageRecord capturedLastDamage()
	{
		List<DamageRecord> hits = export().getEvents().getDamageEvents();
		return hits.isEmpty() ? null : hits.get(hits.size() - 1);
	}

	private String preparedJson(TickRecording recording)
	{
		assertNotNull("A newly completed capture has a typed payload", recording.getPreparedExport());
		return gson.toJson(recording.getPreparedExport());
	}

	private DeathRecordingV2 captureLive(List<TickData> selected)
	{
		TickRecording.CapturedWindow captured = captureLiveWindow();
		assertEquals("Manual capture selects the displayed tick membership atomically", selected.size(), captured.getTicks().size());
		return captured.getCompletion().get();
	}

	private TickRecording.CapturedWindow captureLiveWindow()
	{
		try
		{
			java.lang.reflect.Method request = TickReplayLoggerPlugin.class.getDeclaredMethod("requestLiveCapture", java.util.function.Consumer.class);
			request.setAccessible(true);
			java.util.concurrent.atomic.AtomicReference<TickRecording.CapturedWindow> captured = new java.util.concurrent.atomic.AtomicReference<>();
			request.invoke(plugin, (java.util.function.Consumer<TickRecording.CapturedWindow>) captured::set);
			assertNotNull("The client-thread request returns one complete raw selection", captured.get());
			return captured.get();
		}
		catch (ReflectiveOperationException failure)
		{
			throw new AssertionError("Cannot prepare the selected manual capture", failure);
		}
	}

	private String captureLiveJson(List<TickData> selected)
	{
		return gson.toJson(captureLive(selected));
	}

	private DeathRecordingV2 export()
	{
		return world.buildExport(DeathRecordingV2.Meta.builder().build(), DeathRecordingV2.Summary.builder().build());
	}

	private void assertUnresolvedSource(DamageRecord hit)
	{
		assertEquals("Unknown", hit.getAttackerName());
		assertEquals("UNKNOWN", hit.getAttribution());
		assertNotNull(hit.getSourceResolution());
		assertTrue("The canonical result must retain unidentified source uncertainty", hit.getSourceResolution().isUnidentifiedPossible());
	}

	private void assertRetainedProjectileConflict(DamageRecord hit)
	{
		assertTrue("Original source-actor conflict must remain in the raw projectile history",
			hit.getAttributionCandidates().stream().anyMatch(witness ->
				witness.get("sourceHistory") instanceof com.tickreplaylogger.model.world.ProjectileSourceHistory
					&& ((com.tickreplaylogger.model.world.ProjectileSourceHistory) witness.get("sourceHistory")).isActorChanged()));
	}

	private String originalProjectileMetadata(DamageRecord hit)
	{
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> witness : hit.getAttributionCandidates())
			if ("PROJECTILE_OBSERVATION".equals(witness.get("kind")))
			{
				Map<String, Object> sample = new java.util.LinkedHashMap<>();
				sample.put("projectileId", witness.get("projectileId"));
				com.tickreplaylogger.model.world.ProjectileSourceHistory history =
					(com.tickreplaylogger.model.world.ProjectileSourceHistory) witness.get("sourceHistory");
				sample.put("originalApiSource", history == null ? null : history.firstActor());
				result.add(sample);
			}
		return gson.toJson(result);
	}

	private String originalInteractionFacts(DamageRecord hit)
	{
		return gson.toJson(world.observationsForSourceResolution().stream()
			.filter(event -> event.getTick() <= hit.getImpactTick()).filter(event -> "MENU_OPTION".equals(event.getType())
				|| "SYSTEM_MESSAGE".equals(event.getType())).collect(java.util.stream.Collectors.toList()));
	}

	@Test
	public void reviewedLifecycleCooccurrenceUsesActualDeathThenHpCallbacks() throws Exception
	{
		assertLifecycleCooccurrenceCallbacks(true, false);
	}

	@Test
	public void reviewedLifecycleCooccurrenceUsesActualHpThenRemovalCallbacks() throws Exception
	{
		assertLifecycleCooccurrenceCallbacks(false, false);
	}

	@Test
	public void lifecycleMissingOriginalLevelCannotBorrowLaterSettledLevel() throws Exception
	{
		assertLifecycleCooccurrenceCallbacks(true, true);
	}

	private void assertLifecycleCooccurrenceCallbacks(boolean deathFirst, boolean missingLevel) throws Exception
	{
		useProjectileNativeView();
		npcId = 999941;
		npcObservedEffectiveId = npcId;
		npcLevel = 10;
		npcObservedSize = 1;
		npcObservedName = "Heldout source";
		animation = -1;
		boss = npc("Heldout source", 1);
		npcs.clear();
		npcs.add(boss);
		java.lang.reflect.Field mapField = plugin.getClass().getDeclaredField("damageMap");
		mapField.setAccessible(true);
		com.tickreplaylogger.capture.DamageMap damageMap =
			(com.tickreplaylogger.capture.DamageMap) mapField.get(plugin);
		String type = deathFirst ? "ACTOR_DEATH" : "NPC_DESPAWNED";
		com.google.gson.JsonObject document = com.tickreplaylogger.capture.RuntimeCatalog.load(gson).section("resolver").deepCopy();
		com.google.gson.JsonObject synthetic = null;
		for (com.google.gson.JsonElement value : document.getAsJsonArray("resolutionRules"))
		{
			com.google.gson.JsonObject row = value.getAsJsonObject();
			if ("lifecycle_hint:grey-chinchompa:death".equals(row.get("id").getAsString())) synthetic = row.deepCopy();
		}
		assertNotNull("Fixture reuses a compiled current lifecycle admission contract",synthetic);
		com.google.gson.JsonObject declaration = com.tickreplaylogger.capture.ObservedLifecycleCooccurrenceTest.rule(type);
		synthetic.addProperty("id","heldout:"+type);synthetic.addProperty("label","Heldout source");
		synthetic.addProperty("familyKey","heldout:terminal");synthetic.remove("perHitMaximum");
		synthetic.add("declaration",declaration);
		com.google.gson.JsonObject selector=declaration.getAsJsonObject("selector").deepCopy();selector.addProperty("kind","npc");
		synthetic.add("source",selector);
		synthetic.add("signals",gson.fromJson("{lifecycleEvents:['"+(deathFirst?"actor_death":"npc_despawn")+"']}",com.google.gson.JsonObject.class));
		document.getAsJsonArray("resolutionRules").add(synthetic);
		com.tickreplaylogger.capture.ResolverV1 resolver = new com.tickreplaylogger.capture.ResolverV1(document,gson,damageMap.catalog());
		tick();
		if (missingLevel) failedNpcSnapshotReads.add("getCombatLevel");
		if (deathFirst) plugin.onActorDeath(new ActorDeath(boss));
		hit(HitsplatID.DAMAGE_ME, 8);
		hp = 91;
		if (!deathFirst) plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		failedNpcSnapshotReads.clear();
		npcs.clear();
		tick();
		DeathRecordingV2 recording = export();
		DeathRecordingV2 enriched = new com.tickreplaylogger.capture.ResolverV1Recording(resolver,gson,recording).enrich(recording);
		DamageRecord damage = enriched.getEvents().getDamageEvents().get(0);
		assertEquals(8, damage.getAmount());
		com.tickreplaylogger.model.world.SourceResolution resolution = damage.getSourceResolution();
		assertNotNull(resolution);
		boolean offered = resolution.getCandidates() != null && resolution.getCandidates().stream()
			.anyMatch(c -> c.getEvidence() != null && c.getEvidence().stream().anyMatch(e -> e.startsWith("LIFECYCLE_COOCCURRENCE_RULE:")));
		assertEquals(!missingLevel, offered);
		if (offered)
		{
			assertEquals("ALTERNATIVES", resolution.getStatus());
			assertTrue(resolution.isUnidentifiedPossible());
		}
		assertEquals(1, recording.getEvents().getDamageEvents().size());
		assertEquals(Integer.valueOf(91), recording.getTicks().get(recording.getTicks().size() - 1).getPlayer().getHp());
		assertTrue(recording.getEvents().getObservations().stream().anyMatch(e -> type.equals(e.getType())));
	}

	@Test
	public void partialImpactPrayerReadRetainsItsOriginalIconAndOtherFlags()
	{
		prayer = Prayer.PROTECT_FROM_MISSILES;
		extraPrayers.add(Prayer.PIETY);
		headIcon = HeadIcon.RANGED;
		failedPrayerReads.add(Prayer.AUGURY);
		hit(HitsplatID.DAMAGE_ME, 7);
		failedPrayerReads.clear();
		prayer = Prayer.PROTECT_FROM_MAGIC;
		headIcon = HeadIcon.MAGIC;
		tick();
		DamageRecord damage = capturedLastDamage();
		assertEquals("A failed flag cannot erase the independently observed callback icon", "RANGED",
			damage.getOverheadIconAtImpact());
		assertEquals("PROTECT_FROM_MISSILES", damage.getOverheadAtImpact());
		assertEquals(Boolean.FALSE, damage.getImpactPrayerStateKnown());
		assertTrue(damage.getReportedPrayersAtImpact().contains("PIETY"));
		assertTrue(damage.getReportedPrayersAtImpact().contains("PROTECT_FROM_MISSILES"));
		assertEquals(7, damage.getAmount());
	}

	@Test
	public void impactPrayerDoesNotRepeatTheAlreadyObservedLocalPlayerLookup()
	{
		useProjectileNativeView();
		tick();
		prayer = Prayer.PROTECT_FROM_MISSILES;
		headIcon = HeadIcon.RANGED;
		localPlayerReadCount = 0;
		failRedundantLocalPlayerRead = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		failRedundantLocalPlayerRead = false;
		headIcon = HeadIcon.MAGIC;
		tick();
		assertEquals("The callback's original actor icon survives failures of redundant context reads", "RANGED",
			capturedLastDamage().getOverheadIconAtImpact());
		assertEquals(7, capturedLastDamage().getAmount());
	}

	@Test
	public void rawAndStructuredImpactPrayersUseTheSameOriginalActorIcon()
	{
		prayer = Prayer.PROTECT_FROM_MISSILES;
		changingPlayerHeadIcon = true;
		hit(HitsplatID.DAMAGE_ME, 7);
		assertEquals("One original icon sample per hitsplat callback", 1, playerHeadIconReads);
		changingPlayerHeadIcon = false;
		headIcon = HeadIcon.MAGIC;
		tick();
		com.google.gson.JsonObject raw = gson.toJsonTree(export()).getAsJsonObject();
		com.google.gson.JsonObject event = null;
		for (com.google.gson.JsonElement value : raw.getAsJsonObject("events").getAsJsonArray("observations"))
			if ("HITSPLAT".equals(value.getAsJsonObject().get("type").getAsString()))
			{
				event = value.getAsJsonObject();
				break;
			}
		assertNotNull(event);
		assertEquals("RANGED",
			event.getAsJsonObject("data").getAsJsonObject("actorState").get("overheadIcon").getAsString());
		assertEquals("RANGED", capturedLastDamage().getOverheadIconAtImpact());
		assertEquals(event.getAsJsonObject("data").getAsJsonObject("localPrayerState"),
			gson.toJsonTree(capturedLastDamage().getDefenderPrayerStateAtImpact()));
	}

	@Test
	public void anUnmappedImpactIconRemainsUnresolvedInTheSettledSnapshotToo()
	{
		prayer = Prayer.PROTECT_FROM_MAGIC;
		headIcon = HeadIcon.RANGE_MAGE;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();
		assertEquals("UNMAPPED_ACTOR_ICON", capturedLastDamage().getOverheadAtImpactEvidence());
		assertEquals(Boolean.FALSE, capturedLastDamage().getImpactPrayerStateKnown());
		assertEquals("RANGE_MAGE", capturedLastDamage().getDefenderPrayerStateAtImpact().getOverheadIcon());
		assertEquals(Boolean.FALSE, export().getTicks().get(0).getPlayer().getPrayerStateKnown());
		assertTrue(export().getTicks().get(0).getPlayer().getReportedActivePrayers().contains("PROTECT_FROM_MAGIC"));
	}

	@Test
	public void foreignRecipientsCannotInheritLocalPrayerFlags()
	{
		prayer = Prayer.PROTECT_FROM_MAGIC;
		headIcon = HeadIcon.MAGIC;
		Player other = stub(Player.class, (n, a) -> n.equals("getName") ? "Other synthetic" : null);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(other, HitsplatID.DAMAGE_OTHER, 3);
		tick();
		assertEquals(1, export().getEvents().getDamageEvents().size());
		assertNull(capturedLastDamage().getDefenderPrayerStateAtImpact());
		assertTrue(export().getEvents().getObservations().stream().filter(e -> "HITSPLAT".equals(e.getType()))
			.allMatch(e -> e.getData().get("localPrayerState") == null));
	}

	@Test
	public void anUnidentifiedCallbackCannotBorrowLaterLocalPrayerFlags()
	{
		tick();
		failLocalPlayer = true;
		headIcon = HeadIcon.RANGED;
		hit(HitsplatID.POISON, 5);
		failLocalPlayer = false;
		prayer = Prayer.PROTECT_FROM_MAGIC;
		headIcon = HeadIcon.MAGIC;
		tick();
		DamageRecord hit = capturedLastDamage();
		assertEquals(5, hit.getAmount());
		assertEquals("UNAVAILABLE", hit.getDefenderPrayerStateAtImpact().getOverheadEvidence());
		assertTrue(hit.getDefenderPrayerStateAtImpact().getFlags().isEmpty());
		assertFalse(hit.getDefenderPrayerStateAtImpact().isOverheadIconKnown());
		assertTrue(export().getEvents().getObservations().stream().filter(e -> "HITSPLAT".equals(e.getType()))
			.allMatch(e -> e.getData().get("localPrayerState") == null));
	}

	@Test
	public void prayerObservationFixtureRetainsPartialFieldsAndOriginalCallbackState() throws Exception
	{
		useProjectileNativeView();
		npcObservedSize = 1;
		prayer = Prayer.PROTECT_FROM_MISSILES;
		headIcon = HeadIcon.RANGED;
		extraPrayers.add(Prayer.PIETY);
		failedPrayerReads.add(Prayer.AUGURY);
		hit(HitsplatID.DAMAGE_ME, 7);
		failedPrayerReads.clear();
		prayer = Prayer.PROTECT_FROM_MAGIC;
		headIcon = HeadIcon.MAGIC;
		hp = 92;
		tick();
		failPrayerFlags = true;
		headIcon = HeadIcon.RANGED;
		hit(HitsplatID.DAMAGE_ME, 8);
		failPrayerFlags = false;
		hp = 84;
		tick();
		failedPlayerSnapshotReads.add("getOverheadIcon");
		hit(HitsplatID.DAMAGE_ME, 9);
		failedPlayerSnapshotReads.clear();
		hp = 75;
		tick();
		headIcon = HeadIcon.RANGE_MAGE;
		hit(HitsplatID.DAMAGE_ME, 10);
		hp = 65;
		tick();
		headIcon = null;
		prayer = Prayer.PROTECT_FROM_MISSILES;
		extraPrayers.add(Prayer.PROTECT_FROM_MAGIC);
		hit(HitsplatID.DAMAGE_ME, 11);
		hp = 54;
		tick();
		extraPrayers.clear();
		prayer = null;
		headIcon = null;
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		hit(HitsplatID.POISON, 54);
		hp = 0;
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-prayer-observations");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement value : out.getAsJsonArray("journalTicks"))
		{
			value.getAsJsonObject().remove("timestamp");
			value.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/prayer-observations.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(6, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, export().getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void soundCallbacksRemainRawFactsWithNoDamageOrNativeSceneAssignment()
	{
		tick();
		int observedCycle = gameCycle;
		net.runelite.api.events.SoundEffectPlayed event = new net.runelite.api.events.SoundEffectPlayed(boss);
		event.setSoundId(456);
		event.setDelay(5);
		plugin.onSoundEffectPlayed(event);
		net.runelite.api.events.AreaSoundEffectPlayed area = new net.runelite.api.events.AreaSoundEffectPlayed(null);
		area.setSoundId(457);
		area.setSceneX(2);
		area.setSceneY(3);
		area.setRange(10);
		area.consume();
		plugin.onAreaSoundEffectPlayed(area);
		assertFalse(event.isConsumed());
		assertTrue(area.isConsumed());
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> sounds = export().getEvents().getObservations();
		com.tickreplaylogger.model.world.ObservedEvent first = sounds.stream()
			.filter(e -> "SOUND_EFFECT".equals(e.getType())).findFirst().get();
		com.tickreplaylogger.model.world.ObservedEvent second = sounds.stream()
			.filter(e -> "AREA_SOUND_EFFECT".equals(e.getType())).findFirst().get();
		assertTrue(first.getSequence() < second.getSequence());
		assertEquals(first.getTick(), second.getTick());
		assertNull(first.getSceneKey());
		assertNull(second.getSceneKey());
		assertNull(first.getActorRef());
		assertEquals(Integer.valueOf(observedCycle), first.getGameCycle());
		assertEquals(456, first.getData().get("soundId"));
		assertEquals(false, first.getData().get("consumedWhenObserved"));
		assertEquals(true, second.getData().get("consumedWhenObserved"));
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void soundFactsSurviveUnavailableCallbackContextWithoutActorEnrichment()
	{
		tick();
		failGameState = true;
		failGameCycle = true;
		net.runelite.api.events.SoundEffectPlayed event = new net.runelite.api.events.SoundEffectPlayed(boss);
		event.setSoundId(458);
		event.setDelay(2);
		plugin.onSoundEffectPlayed(event);
		failGameState = false;
		failGameCycle = false;
		tick();
		com.tickreplaylogger.model.world.ObservedEvent found = export().getEvents().getObservations().stream()
			.filter(e -> "SOUND_EFFECT".equals(e.getType())).findFirst().get();
		assertNull(found.getGameCycle());
		assertNull(found.getSceneKey());
		assertEquals(458, found.getData().get("soundId"));
		assertEquals(true, found.getData().get("sourceContextUnavailable"));
		assertFalse(found.getData().containsKey("reportedSource"));
		assertTrue(found.getData().containsKey("captureIssues"));
	}

	@Test
	public void soundCaptureHonorsDisabledWorldCaptureAndClosedSession() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureWorldSnapshots()
			{
				return false;
			}
		});
		plugin.onSoundEffectPlayed(new net.runelite.api.events.SoundEffectPlayed(null));
		tick();
		assertTrue(export().getEvents().getObservations().isEmpty());
		inject("config", new TickReplayLoggerConfig()
		{
		});
		gameState = GameState.LOGIN_SCREEN;
		plugin.onSoundEffectPlayed(new net.runelite.api.events.SoundEffectPlayed(null));
		assertTrue(export().getEvents().getObservations().isEmpty());
	}

	@Test
	public void soundExportKeepsQueuedSourcesConsumedEventsAndFailedContextSeparate() throws Exception
	{
		useProjectileNativeView();
		npcId = 9036;
		npcLevel = 894;
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		net.runelite.api.events.SoundEffectPlayed first = new net.runelite.api.events.SoundEffectPlayed(boss);
		first.setSoundId(12345);
		first.setDelay(3);
		plugin.onSoundEffectPlayed(first);
		net.runelite.api.events.AreaSoundEffectPlayed area = new net.runelite.api.events.AreaSoundEffectPlayed(null);
		area.setSoundId(12346);
		area.setSceneX(2);
		area.setSceneY(3);
		area.setRange(4);
		area.consume();
		plugin.onAreaSoundEffectPlayed(area);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		hp = 93;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		plugin.onNpcDespawned(new net.runelite.api.events.NpcDespawned(boss));
		npcs.clear();
		net.runelite.api.events.SoundEffectPlayed queued = new net.runelite.api.events.SoundEffectPlayed(boss);
		queued.setSoundId(12347);
		plugin.onSoundEffectPlayed(queued);
		tick();
		failGameState = true;
		failGameCycle = true;
		net.runelite.api.events.SoundEffectPlayed unready = new net.runelite.api.events.SoundEffectPlayed(boss);
		unready.setSoundId(12348);
		plugin.onSoundEffectPlayed(unready);
		failGameState = false;
		failGameCycle = false;
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 93);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> sounds = new ArrayList<>();
		for (com.tickreplaylogger.model.world.ObservedEvent event : export().getEvents().getObservations())
			if (event.getType().equals("SOUND_EFFECT") || event.getType().equals("AREA_SOUND_EFFECT"))
				sounds.add(event);
		assertEquals(4, sounds.size());
		assertNotNull(((Map<?, ?>) sounds.get(0).getData().get("reportedSource")).get("actorRef"));
		assertNull(((Map<?, ?>) sounds.get(2).getData().get("reportedSource")).get("actorRef"));
		assertNull(sounds.get(3).getGameCycle());
		for (com.tickreplaylogger.model.world.ObservedEvent sound : sounds)
		{
			assertNull(sound.getActorRef());
			assertNull(sound.getSceneKey());
			assertEquals(false, sound.getData().get("playbackConfirmed"));
		}
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-sound-callbacks");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/sound-callbacks.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(4, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, export().getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	private void inject(String name, Object value) throws Exception
	{
		Field field = TickReplayLoggerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void rawAndStructuredLocalHitsShareOneVisualSample()
	{
		playerGraphic = 3281;
		hit(HitsplatID.DAMAGE_ME, 6);
		assertEquals("One collection sample per callback", 1, playerGraphicCollectionReads);
		assertEquals("One primary sample per callback", 1, playerPrimaryGraphicReads);
	}

	@Test
	public void aChangingGetterCannotMakeRawAndStructuredImpactVisualsDisagree()
	{
		playerGraphic = 3281;
		changingPlayerPrimaryGraphic = true;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		com.google.gson.JsonObject raw = gson.toJsonTree(export()).getAsJsonObject();
		Integer original = null;
		for (com.google.gson.JsonElement value : raw.getAsJsonObject("events").getAsJsonArray("observations"))
		{
			com.google.gson.JsonObject row = value.getAsJsonObject();
			if ("HITSPLAT".equals(row.get("type").getAsString()))
				original = row.getAsJsonObject("data").getAsJsonObject("actorState").getAsJsonObject("graphicsState")
					.get("primaryGraphicId").getAsInt();
		}
		assertEquals(Integer.valueOf(3281), original);
		assertEquals("Structured damage must use the original callback visual", original,
			capturedLastDamage().getDefenderGraphicsAtImpact().getPrimaryGraphicId());
	}

	@Test
	public void rawAndStructuredNpcHitsShareOneVisualSample()
	{
		int[] reads =
		{ 0, 0 };
		NPC original = boss;
		boss = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class[]
		{ NPC.class }, (proxy, method, args) ->
		{
			if (method.getName().equals("getSpotAnims"))
				reads[0]++;
			if (method.getName().equals("getGraphic"))
				reads[1]++;
			try
			{
				return method.invoke(original, args);
			}
			catch (java.lang.reflect.InvocationTargetException unavailable)
			{
				throw unavailable.getCause();
			}
		});
		npcs.clear();
		npcs.add(boss);
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		assertArrayEquals("The NPC defender must be sampled once", new int[]
		{ 1, 1 }, reads);
	}

	@Test
	public void aDeferredLocalRecipientKeepsItsOriginalVisualInsteadOfALaterRead()
	{
		playerGraphic = 3281;
		missingLocalPlayer = true;
		hit(HitsplatID.DAMAGE_ME, 6);
		missingLocalPlayer = false;
		playerGraphic = 3280;
		tick();
		assertEquals("SETTLED_LOCAL_REFERENCE_MATCH", capturedLastDamage().getDefenderIdentityEvidence());
		assertNotNull("The original raw callback visual was captured before recipient resolution",
			capturedLastDamage().getDefenderGraphicsAtImpact());
		assertEquals(Integer.valueOf(3281), capturedLastDamage().getDefenderGraphicsAtImpact().getPrimaryGraphicId());
	}

	@Test
	public void graphicalLifecycleExportKeepsCallbackFactsSeparateFromSettledEffects() throws Exception
	{
		useProjectileNativeView();
		npcObservedSize = 1;
		playerGraphic = 3281;
		playerGraphicStartCycle = 900;
		playerGraphicFrame = -1;
		hp = 93;
		hit(HitsplatID.DAMAGE_ME, 6);
		playerGraphic = -1;
		tick();
		playerGraphic = 3280;
		playerGraphicStartCycle = 530;
		playerGraphicFrame = 2;
		tick();
		failPlayerGraphic = true;
		failGraphicsCollection = true;
		hp = 84;
		hit(HitsplatID.DAMAGE_ME, 9);
		failPlayerGraphic = false;
		failGraphicsCollection = false;
		playerGraphic = 3281;
		playerGraphicStartCycle = 560;
		playerGraphicFrame = 3;
		tick();
		playerGraphic = -1;
		hp = 77;
		hit(HitsplatID.DAMAGE_ME, 7);
		playerGraphic = 3280;
		playerGraphicStartCycle = 590;
		playerGraphicFrame = 4;
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		tick();
		playerGraphic = 3281;
		playerGraphicStartCycle = 620;
		playerGraphicFrame = 5;
		missingLocalPlayer = true;
		hp = 74;
		hit(HitsplatID.DAMAGE_ME, 3);
		missingLocalPlayer = false;
		playerGraphic = 3280;
		failGraphicsCollection = true;
		tick();
		failGraphicsCollection = false;
		playerGraphic = -1;
		hp = 0;
		hit(HitsplatID.POISON, 74);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-actor-graphics-lifecycle");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/actor-graphics-lifecycle.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(6, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, export().getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void impactGraphicsAreNotReplacedByLaterSettledEffects()
	{
		playerGraphic = 3281;
		playerGraphicStartCycle = 900;
		playerGraphicFrame = -1;
		hit(HitsplatID.DAMAGE_ME, 6);
		playerGraphic = -1;
		tick();
		com.google.gson.JsonObject damage = gson.toJsonTree(capturedLastDamage()).getAsJsonObject();
		assertNotNull("The callback visual state must survive a later same-tick change",
			damage.get("defenderGraphicsAtImpact"));
		com.google.gson.JsonObject graphics = damage.getAsJsonObject("defenderGraphicsAtImpact");
		assertEquals(3281, graphics.get("primaryGraphicId").getAsInt());
		assertEquals(900, graphics.getAsJsonArray("entries").get(0).getAsJsonObject().get("startCycle").getAsInt());
		assertEquals(-1, graphics.getAsJsonArray("entries").get(0).getAsJsonObject().get("frame").getAsInt());
		assertEquals(6, capturedLastDamage().getAmount());
	}

	@Test
	public void unavailableImpactGraphicsAreNotFilledByARecoveredTickSnapshot()
	{
		failPlayerGraphic = true;
		failGraphicsCollection = true;
		hit(HitsplatID.DAMAGE_ME, 9);
		failPlayerGraphic = false;
		failGraphicsCollection = false;
		playerGraphic = 3281;
		tick();
		com.google.gson.JsonObject damage = gson.toJsonTree(capturedLastDamage()).getAsJsonObject();
		assertNotNull("Unavailable callback channels must be represented independently",
			damage.get("defenderGraphicsAtImpact"));
		com.google.gson.JsonObject graphics = damage.getAsJsonObject("defenderGraphicsAtImpact");
		assertFalse(graphics.get("primaryGraphicKnown").getAsBoolean());
		assertFalse(graphics.get("collectionKnown").getAsBoolean());
		assertFalse(graphics.has("primaryGraphicId"));
		assertFalse(graphics.has("entries"));
		assertEquals(9, capturedLastDamage().getAmount());
	}

	@Test
	public void anEmptyImpactGraphicCollectionIsNotAnUnobservedProtectionState()
	{
		playerGraphic = -1;
		hit(HitsplatID.DAMAGE_ME, 7);
		playerGraphic = 3281;
		tick();
		com.google.gson.JsonObject damage = gson.toJsonTree(capturedLastDamage()).getAsJsonObject();
		assertNotNull("A successful empty callback sample must remain explicit",
			damage.get("defenderGraphicsAtImpact"));
		com.google.gson.JsonObject graphics = damage.getAsJsonObject("defenderGraphicsAtImpact");
		assertTrue(graphics.get("primaryGraphicKnown").getAsBoolean());
		assertTrue(graphics.get("collectionKnown").getAsBoolean());
		assertEquals(-1, graphics.get("primaryGraphicId").getAsInt());
		assertEquals(0, graphics.getAsJsonArray("entries").size());
	}

	private com.tickreplaylogger.model.world.ObservedEvent callback(String type)
	{
		return export().getEvents().getObservations().stream().filter(e -> type.equals(e.getType())).reduce((a, b) -> b)
			.get();
	}

	@Test
	public void callbackFieldFixturePreservesIndependentFactsAndOwnership() throws Exception
	{
		useProjectileNativeView();
		npcObservedSize = 1;
		inject("config", new TickReplayLoggerConfig()
		{
			@Override
			public boolean captureOtherPlayers()
			{
				return false;
			}

			@Override
			public boolean trackPing()
			{
				return false;
			}
		});
		net.runelite.api.WorldView unreadView = stub(net.runelite.api.WorldView.class, (name, args) ->
		{
			if (name.equals("getId"))
				throw new IllegalStateException("view id unavailable");
			return null;
		});
		plugin.onWorldViewUnloaded(new net.runelite.api.events.WorldViewUnloaded(unreadView));
		MenuEntry partialMenu = stub(MenuEntry.class, (name, args) ->
		{
			if (name.equals("getOption"))
				return "Drink";
			if (name.equals("getTarget"))
				return "Synthetic potion";
			if (name.equals("getActor"))
				throw new IllegalStateException("related actor unavailable");
			if (name.equals("getItemId"))
				return 2436;
			if (name.equals("getParam0"))
				return 5;
			return null;
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(partialMenu));
		GraphicsObject partialGraphic = stub(GraphicsObject.class, (name, args) ->
		{
			if (name.equals("getId"))
				return 456;
			if (name.equals("getLocation"))
				return new LocalPoint(6400, 6528);
			if (name.equals("getWorldView"))
				return projectileNativeView;
			if (name.equals("getLevel"))
				throw new IllegalStateException("plane unavailable");
			if (name.equals("getStartCycle"))
				return 505;
			if (name.equals("getZ"))
				return 32;
			return null;
		});
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(partialGraphic));
		failClientIntegerValue = true;
		plugin.onVarClientIntChanged(new net.runelite.api.events.VarClientIntChanged(13));
		failClientIntegerValue = false;
		ItemContainer unreadItems = stub(ItemContainer.class, (name, args) ->
		{
			if (name.equals("getItems"))
				throw new IllegalStateException("items unavailable");
			return null;
		});
		plugin.onItemContainerChanged(
			new ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(), unreadItems));
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(null, 123, 99, 50));
		hp = 93;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();

		plugin.onWorldViewLoaded(new net.runelite.api.events.WorldViewLoaded(projectileNativeView));
		MenuEntry attack = stub(MenuEntry.class, (name, args) -> name.equals("getOption") ? "Attack"
			: name.equals("getTarget") ? "Test boss" : name.equals("getActor") ? boss : null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(attack));
		GraphicsObject normalGraphic = callbackGraphic(457, projectileNativeView,
			new LocalPoint(6528, 6528, projectileNativeView), 530, 24);
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(normalGraphic));
		plugin.onVarClientIntChanged(new net.runelite.api.events.VarClientIntChanged(13));
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(),
			stub(ItemContainer.class, (name, args) -> name.equals("getItems") ? new Item[0] : null)));
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(Skill.MAGIC, 321, 99, 75));
		hp = 84;
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();

		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class, (name, args) -> null)));
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(
			stub(GraphicsObject.class, (name, args) -> name.equals("getId") ? 458 : null)));
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(), null));
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.InventoryID.EQUIPMENT.getId(),
			stub(ItemContainer.class, (name, args) -> null)));
		hp = 77;
		hit(HitsplatID.DAMAGE_ME, 7);
		tick();

		net.runelite.api.WorldView foreign = callbackView(42, 7000, 8000);
		int[] viewReads =
		{ 0 }, locationReads =
		{ 0 };
		GraphicsObject changing = stub(GraphicsObject.class, (name, args) ->
		{
			if (name.equals("getId"))
				return 459;
			if (name.equals("getWorldView"))
				return ++viewReads[0] == 1 ? foreign : projectileNativeView;
			if (name.equals("getLocation"))
			{
				locationReads[0]++;
				return new LocalPoint(6656, 6784);
			}
			return null;
		});
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(changing));
		assertEquals(1, viewReads[0]);
		assertEquals(1, locationReads[0]);
		Player privateTarget = stub(Player.class,
			(name, args) -> name.equals("getName") ? "Private callback target" : null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class,
			(name, args) -> name.equals("getOption") ? "Attack" : name.equals("getActor") ? privateTarget : null)));
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		hp = 74;
		hit(HitsplatID.DAMAGE_ME, 3);
		tick();

		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class, (name, args) ->
		{
			if (name.equals("getOption"))
				return "Examine";
			if (name.equals("getTarget"))
				throw new IllegalStateException("target unavailable");
			return null;
		})));
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(),
			stub(ItemContainer.class, (name, args) -> name.equals("getItems") ? new Item[]
			{ new Item(2436, 2), null } : null)));
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 74);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-callback-fields");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		String json = gson.toJson(out);
		assertFalse(json.contains("Private callback target"));
		assertFalse(json.contains("Proxy"));
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/callback-fields.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(6, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, export().getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	@Test
	public void menuRetainsTheOriginalLocalPositionBeforeAnyLaterImpactOrSnapshot()
	{
		useProjectileNativeView();
		tick();
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class,
			(name, args) -> name.equals("getOption") ? "Examine" : null)));
		position = new WorldPoint(3210, 3211, 0);
		tick();
		Map<?, ?> original = (Map<?, ?>) callback("MENU_OPTION").getData().get("actorState");
		assertEquals(Boolean.TRUE, original.get("worldPositionKnown"));
		assertEquals(3200, original.get("worldX"));
		assertEquals(3200, original.get("worldY"));
		assertEquals(0, original.get("plane"));
	}

	@Test
	public void primitiveMenuInputSurvivesAnUnavailableRelatedActor()
	{
		MenuEntry entry = stub(MenuEntry.class, (name, args) ->
		{
			if (name.equals("getOption"))
				return "Drink";
			if (name.equals("getTarget"))
				return "Synthetic potion";
			if (name.equals("getActor"))
				throw new IllegalStateException("related actor unavailable");
			if (name.equals("getItemId"))
				return 2436;
			if (name.equals("getParam0"))
				return 5;
			return null;
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		tick();
		Map<String, Object> data = callback("MENU_OPTION").getData();
		assertEquals("Drink", data.get("option"));
		assertEquals(2436, data.get("itemId"));
		assertEquals(5, data.get("param0"));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) data.get("fieldAvailability")).get("relatedActor"));
	}

	@Test
	public void primitiveMenuAndActionShareOneRelatedActorSample()
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0 };
		MenuEntry entry = stub(MenuEntry.class, (name, args) ->
		{
			if (name.equals("getOption"))
				return "Attack";
			if (name.equals("getTarget"))
				return "Test boss";
			if (name.equals("getActor"))
				return ++reads[0] == 1 ? boss : null;
			return null;
		});
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals(1, reads[0]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("MENU_OPTION");
		assertNotNull(raw.getRelatedActorRef());
		assertEquals(raw.getRelatedActorRef(), export().getEvents().getPlayerActions().get(0).getTargetRef());
	}

	@Test
	public void primitiveGraphicIdSurvivesAnUnavailablePlaneWithoutBorrowingTheCurrentScene()
	{
		tick();
		GraphicsObject graphic = stub(GraphicsObject.class, (name, args) ->
		{
			if (name.equals("getId"))
				return 456;
			if (name.equals("getLocation"))
				return new LocalPoint(6400, 6400);
			if (name.equals("getLevel"))
				throw new IllegalStateException("graphic plane unavailable");
			return null;
		});
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(graphic));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("GRAPHICS_OBJECT_CREATED");
		assertEquals(456, raw.getData().get("id"));
		assertEquals(6400, raw.getData().get("localX"));
		assertNull(raw.getSceneKey());
		assertEquals(Boolean.FALSE, raw.getData().get("scopeKnown"));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) raw.getData().get("fieldAvailability")).get("plane"));
	}

	@Test
	public void primitiveGraphicFieldsUseOneOriginalViewAndLocation()
	{
		useProjectileNativeView();
		tick();
		int[] viewReads =
		{ 0 }, locationReads =
		{ 0 };
		net.runelite.api.WorldView foreign = stub(net.runelite.api.WorldView.class,
			(name, args) -> name.equals("getId") ? 42
				: name.equals("getBaseX") ? 7000 : name.equals("getBaseY") ? 8000 : null);
		GraphicsObject graphic = stub(GraphicsObject.class, (name, args) ->
		{
			if (name.equals("getId"))
				return 456;
			if (name.equals("getWorldView"))
				return ++viewReads[0] == 1 ? foreign : projectileNativeView;
			if (name.equals("getLocation"))
			{
				locationReads[0]++;
				return new LocalPoint(6400, 6400);
			}
			return null;
		});
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(graphic));
		assertEquals(1, viewReads[0]);
		assertEquals(1, locationReads[0]);
		tick();
		assertEquals(42, callback("GRAPHICS_OBJECT_CREATED").getData().get("worldViewId"));
	}

	@Test
	public void primitiveClientIntegerIdSurvivesAnUnreadValue()
	{
		failClientIntegerValue = true;
		plugin.onVarClientIntChanged(new net.runelite.api.events.VarClientIntChanged(13));
		failClientIntegerValue = false;
		tick();
		Map<String, Object> data = callback("CLIENT_INTEGER_CHANGED").getData();
		assertEquals(13, data.get("id"));
		assertNull(data.get("value"));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) data.get("fieldAvailability")).get("value"));
	}

	@Test
	public void primitiveContainerIdSurvivesAnUnreadItemArray()
	{
		ItemContainer container = stub(ItemContainer.class, (name, args) ->
		{
			if (name.equals("getItems"))
				throw new IllegalStateException("item array unavailable");
			return null;
		});
		plugin.onItemContainerChanged(new net.runelite.api.events.ItemContainerChanged(
			net.runelite.api.InventoryID.INVENTORY.getId(), container));
		tick();
		Map<String, Object> data = callback("ITEM_CONTAINER_CHANGED").getData();
		assertEquals(net.runelite.api.InventoryID.INVENTORY.getId(), data.get("containerId"));
		assertNull(data.get("itemIds"));
		assertEquals(Boolean.FALSE, data.get("itemsKnown"));
	}

	@Test
	public void primitiveContainerEmptyAndUnavailableRemainDifferent()
	{
		ItemContainer empty = stub(ItemContainer.class, (name, args) -> name.equals("getItems") ? new Item[0] : null);
		plugin.onItemContainerChanged(
			new net.runelite.api.events.ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(), empty));
		plugin.onItemContainerChanged(
			new net.runelite.api.events.ItemContainerChanged(net.runelite.api.InventoryID.INVENTORY.getId(), null));
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> events = new ArrayList<>();
		for (com.tickreplaylogger.model.world.ObservedEvent e : export().getEvents().getObservations())
			if (e.getType().equals("ITEM_CONTAINER_CHANGED"))
				events.add(e);
		assertEquals(2, events.size());
		assertEquals(Boolean.TRUE, events.get(0).getData().get("itemsKnown"));
		assertEquals(0, ((int[]) events.get(0).getData().get("itemIds")).length);
		assertEquals(Boolean.FALSE, events.get(1).getData().get("itemsKnown"));
		assertNull(events.get(1).getData().get("itemIds"));
	}

	@Test
	public void primitiveSkillNumbersSurviveAnUnavailableSkillName()
	{
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(null, 123, 99, 50));
		tick();
		Map<String, Object> data = callback("SKILL_STATE_CHANGED").getData();
		assertEquals(123, data.get("xp"));
		assertEquals(99, data.get("level"));
		assertEquals(50, data.get("boostedLevel"));
		assertNull(data.get("skill"));
	}

	@Test
	public void primitiveLifecycleKindAndEpochSurviveAnUnavailableViewId()
	{
		net.runelite.api.WorldView view = stub(net.runelite.api.WorldView.class, (name, args) ->
		{
			if (name.equals("getId"))
				throw new IllegalStateException("view id unavailable");
			return null;
		});
		plugin.onWorldViewUnloaded(new net.runelite.api.events.WorldViewUnloaded(view));
		tick();
		Map<String, Object> data = callback("WORLD_VIEW_UNLOADED").getData();
		assertTrue(((Number) data.get("causalSceneEpoch")).longValue() > 0);
		assertNull(data.get("worldViewId"));
	}

	@Test
	public void primitiveMenuOptionFailureRetainsOtherInputFieldsWithoutInventingAnAction()
	{
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class, (name, args) ->
		{
			if (name.equals("getOption"))
				throw new IllegalStateException("option unavailable");
			if (name.equals("getTarget"))
				return "Observed target";
			if (name.equals("getItemId"))
				return 2436;
			if (name.equals("getParam0"))
				return 5;
			return null;
		})));
		tick();
		Map<String, Object> data = callback("MENU_OPTION").getData();
		assertNull(data.get("option"));
		assertEquals("Observed target", data.get("target"));
		assertEquals(2436, data.get("itemId"));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) data.get("fieldAvailability")).get("option"));
		assertTrue(export().getEvents().getPlayerActions().isEmpty());
	}

	@Test
	public void primitiveSuccessfulNullsRemainDistinctAfterGsonOmission()
	{
		plugin.onMenuOptionClicked(new MenuOptionClicked(stub(MenuEntry.class, (name, args) -> null)));
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(null, 0, 0, 0));
		tick();
		com.google.gson.JsonObject menu = gson.toJsonTree(callback("MENU_OPTION").getData()).getAsJsonObject();
		assertEquals(1, menu.get("fieldObservationVersion").getAsInt());
		assertFalse(menu.has("option"));
		assertTrue(menu.getAsJsonObject("fieldAvailability").get("option").getAsBoolean());
		assertTrue(menu.getAsJsonArray("nullFields").contains(new com.google.gson.JsonPrimitive("option")));
		assertTrue(menu.getAsJsonArray("nullFields").contains(new com.google.gson.JsonPrimitive("relatedActor")));
		Map<String, Object> skill = callback("SKILL_STATE_CHANGED").getData();
		assertEquals(0, skill.get("xp"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) skill.get("fieldAvailability")).get("skill"));
	}

	@Test
	public void primitiveDrinkInputWithNullTargetDoesNotInvokeTargetBasedDamageEnrichment()
	{
		plugin.onMenuOptionClicked(
			new MenuOptionClicked(stub(MenuEntry.class, (name, args) -> name.equals("getOption") ? "Drink" : null)));
		tick();
		assertNull(callback("MENU_OPTION").getData().get("target"));
		assertEquals(1, export().getEvents().getPlayerActions().size());
		assertEquals("INPUT", export().getEvents().getPlayerActions().get(0).getObservationKind());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
	}

	@Test
	public void primitiveGraphicPrimaryNullKeyStillHasAnObservedPrimaryScope()
	{
		useProjectileNativeView();
		tick();
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(
			callbackGraphic(456, projectileNativeView, new LocalPoint(6400, 6400, projectileNativeView), 0, 0)));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("GRAPHICS_OBJECT_CREATED");
		assertNotNull(raw.getSceneKey());
		assertEquals(Boolean.TRUE, raw.getData().get("scopeKnown"));
		assertEquals(Boolean.TRUE, raw.getData().get("positionScopeKnown"));
		assertNull(raw.getData().get("nativeSceneKey"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) raw.getData().get("fieldAvailability")).get("nativeSceneKey"));
	}

	@Test
	public void primitiveGraphicPrimaryScopeFailureRetainsItsOtherFacts()
	{
		useProjectileNativeView();
		tick();
		hazardScene = stub(net.runelite.api.Scene.class, (name, args) ->
		{
			if (name.equals("getTiles"))
				throw new IllegalStateException("primary scene unavailable");
			return null;
		});
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(
			callbackGraphic(456, projectileNativeView, new LocalPoint(6400, 6400), 900, 32)));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("GRAPHICS_OBJECT_CREATED");
		assertEquals(456, raw.getData().get("id"));
		assertEquals(6400, raw.getData().get("localX"));
		assertEquals(900, raw.getData().get("startCycle"));
		assertEquals(32, raw.getData().get("z"));
		assertNull(raw.getSceneKey());
		assertEquals(Boolean.FALSE, raw.getData().get("scopeKnown"));
		assertEquals(Boolean.FALSE, raw.getData().get("positionScopeKnown"));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) raw.getData().get("fieldAvailability")).get("nativeSceneKey"));
	}

	@Test
	public void primitiveGraphicMismatchedPointCannotEstablishPositionScope()
	{
		useProjectileNativeView();
		tick();
		net.runelite.api.WorldView foreign = callbackView(42, 0, 0);
		plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(
			callbackGraphic(456, foreign, new LocalPoint(6400, 6400, -1), 0, 0)));
		tick();
		Map<String, Object> data = callback("GRAPHICS_OBJECT_CREATED").getData();
		assertEquals(Boolean.TRUE, data.get("scopeKnown"));
		assertEquals(Boolean.FALSE, data.get("positionScopeKnown"));
		assertEquals(-1, data.get("localWorldViewId"));
		assertEquals(42, data.get("worldViewId"));
		assertEquals(6400, data.get("localX"));
	}

	@Test
	public void primitiveGraphicUnreadLocationDoesNotEraseTimingOrScope()
	{
		useProjectileNativeView();
		tick();
		plugin.onGraphicsObjectCreated(
			new net.runelite.api.events.GraphicsObjectCreated(stub(GraphicsObject.class, (name, args) ->
			{
				if (name.equals("getLocation"))
					throw new IllegalStateException("location unavailable");
				switch (name)
				{
				case "getId":
					return 456;
				case "getWorldView":
					return projectileNativeView;
				case "getStartCycle":
					return 900;
				case "getZ":
					return 32;
				default:
					return null;
				}
			})));
		tick();
		Map<String, Object> data = callback("GRAPHICS_OBJECT_CREATED").getData();
		assertEquals(456, data.get("id"));
		assertEquals(900, data.get("startCycle"));
		assertEquals(32, data.get("z"));
		assertNull(data.get("localX"));
		assertEquals(Boolean.TRUE, data.get("scopeKnown"));
		assertEquals(Boolean.FALSE, data.get("positionScopeKnown"));
	}

	@Test
	public void primitiveLifecycleResetFailureRemainsExplicitBesideIndependentId() throws Exception
	{
		inject("captureService", new WorldCaptureService()
		{
			@Override
			public void worldViewLifecycle(Client ignored, net.runelite.api.WorldView view)
			{
				throw new IllegalStateException("reset unavailable");
			}
		});
		plugin.onWorldViewLoaded(new net.runelite.api.events.WorldViewLoaded(
			stub(net.runelite.api.WorldView.class, (name, args) -> name.equals("getId") ? 42 : null)));
		tick();
		Map<String, Object> data = callback("WORLD_VIEW_LOADED").getData();
		assertEquals(42, data.get("worldViewId"));
		assertEquals("partial", data.get("captureResetStatus"));
		assertEquals(Boolean.FALSE, data.get("scopeKnown"));
	}

	@Test
	public void callbackConsistencyKnownNameDoesNotMixWithALaterIncompatibleName()
	{
		assertCallbackNameConsistency(0);
	}

	@Test
	public void callbackConsistencyFailedNameCannotBorrowALaterSuccessfulName()
	{
		assertCallbackNameConsistency(1);
	}

	@Test
	public void callbackConsistencyKnownNullNameCannotBorrowALaterName()
	{
		assertCallbackNameConsistency(2);
	}

	@Test
	public void callbackConsistencyKnownNameSurvivesAFailedReread()
	{
		assertCallbackNameConsistency(3);
	}

	@Test
	public void callbackConsistencyOriginalNameFailureMarksTheRawHeaderPartial()
	{
		assertOriginalFailureProjection(1, -1, -1, "observationActorName:");
	}

	@Test
	public void callbackConsistencyOriginalPositionFailureMarksTheRawScopePartial()
	{
		assertOriginalFailureProjection(-1, -1, 1, "observationActorWorldPosition:");
	}

	@Test
	public void callbackConsistencyOriginalViewFailureMarksTheRawScopePartial()
	{
		assertOriginalFailureProjection(-1, 3, -1, "observationActorWorldView:");
	}

	private void assertOriginalFailureProjection(int nameMode, int viewMode, int positionMode, String prefix)
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		NPC actor = consistencyNpc(nameMode, viewMode, positionMode, reads, null);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		assertEquals(1, reads[0]);
		assertEquals(1, reads[1]);
		assertEquals(1, reads[2]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertEquals("PARTIAL_EVENT_CONTEXT", raw.getData().get("captureStatus"));
		assertTrue(((List<?>) raw.getData().get("captureIssues")).stream().anyMatch(
			issue -> issue.toString().startsWith(prefix) && issue.toString().endsWith("IllegalStateException")));
		if (viewMode == 3 || positionMode == 1)
			assertNull(raw.getSceneKey());
	}

	@Test
	public void callbackConsistencySuccessfulNullsDoNotInventFailureDiagnostics()
	{
		useProjectileNativeView();
		npcObservedSize = 1;
		tick();
		int[] reads =
		{ 0, 0, 0 };
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(consistencyNpc(2, 1, 0, reads, null)));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertNull(raw.getActorName());
		assertNull(raw.getSceneKey());
		assertFalse(raw.getData().containsKey("captureIssues"));
		assertFalse(raw.getData().containsKey("captureStatus"));
	}

	@Test
	public void callbackConsistencyOptionalVisualFailureDoesNotBecomeEventContextFailure()
	{
		useProjectileNativeView();
		tick();
		failedNpcSnapshotReads.add("getOrientation");
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(boss));
		failedNpcSnapshotReads.clear();
		tick();
		Map<String, Object> raw = callback("NPC_SPAWNED").getData();
		assertEquals("IllegalStateException", ((Map<?, ?>) raw.get("observationIssues")).get("orientation"));
		assertEquals("PARTIAL_ACTOR_METADATA", raw.get("captureStatus"));
		assertFalse(raw.containsKey("captureIssues"));
	}

	@Test
	public void callbackConsistencyOriginalViewIdFailureIsRawOnlyAndRetainsOwnedHitEvidence()
	{
		useProjectileNativeView();
		tick();
		int[] idReads =
		{ 0 }, reads =
		{ 0, 0, 0 };
		net.runelite.api.WorldView unreadId = stub(net.runelite.api.WorldView.class, (name, args) ->
		{
			if (name.equals("getId"))
			{
				idReads[0]++;
				throw new IllegalStateException("original view id unavailable");
			}
			return null;
		});
		NPC actor = consistencyNpc(-1, 0, -1, reads, unreadId);
		hit(actor, HitsplatID.DAMAGE_ME, 4);
		assertEquals(1, idReads[0]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("HITSPLAT");
		assertEquals("PARTIAL_EVENT_CONTEXT", raw.getData().get("captureStatus"));
		assertNull(raw.getSceneKey());
		assertTrue(((List<?>) raw.getData().get("captureIssues")).stream()
			.anyMatch(issue -> issue.toString().startsWith("observationActorWorldViewId:")));
		DamageRecord damage = capturedLastDamage();
		assertEquals("OWNED_HITSPLAT", damage.getAttributionEvidence());
		assertEquals("LOCAL_PLAYER", damage.getOwnership());
		assertTrue(damage.getCaptureIssues() == null
			|| damage.getCaptureIssues().stream().noneMatch(issue -> issue.startsWith("observationActorWorldViewId:")));
	}

	private void assertCallbackNameConsistency(int mode)
	{
		useProjectileNativeView();
		tick();
		npcId = 9036;
		npcLevel = 894;
		int[] reads =
		{ 0, 0, 0 };
		NPC actor = consistencyNpc(mode, -1, -1, reads, null);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		assertEquals("A callback header must reuse its original name observation", 1, reads[0]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		if (mode == 1 || mode == 2)
			assertNull(raw.getActorName());
		else
			assertEquals("Corrupted Hunllef", raw.getActorName());
	}

	@Test
	public void callbackConsistencyOriginalForeignViewDefinesIdentityAndScope()
	{
		useProjectileNativeView();
		tick();
		npcId = 9036;
		npcLevel = 894;
		int[] reads =
		{ 0, 0, 0 };
		net.runelite.api.WorldView foreign = callbackView(42, 7000, 8000);
		NPC actor = consistencyNpc(-1, 0, -1, reads, foreign);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		assertEquals("Do not reread an already captured actor view", 1, reads[1]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertEquals(42, raw.getData().get("worldViewId"));
		assertTrue(raw.getActorRef().contains(":v42:"));
		assertTrue(raw.getSceneKey().startsWith("native:42:p0:"));
	}

	@Test
	public void callbackConsistencyKnownNullViewCannotBorrowTheCurrentPrimaryView()
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		NPC actor = consistencyNpc(-1, 1, -1, reads, null);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertNull(raw.getData().get("worldViewId"));
		assertNull(raw.getSceneKey());
	}

	@Test
	public void callbackConsistencyKnownNullPositionCannotBorrowALaterPosition()
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		NPC actor = consistencyNpc(-1, -1, 0, reads, null);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertEquals(Boolean.FALSE, raw.getData().get("worldPositionKnown"));
		assertNull(raw.getSceneKey());
	}

	@Test
	public void callbackConsistencyMenuActionKeepsTheOriginalReferenceAfterALaterFailure() throws Exception
	{
		assertMenuReferenceConsistency(false);
	}

	@Test
	public void callbackConsistencyMenuActionKeepsAnOriginalNullReference() throws Exception
	{
		assertMenuReferenceConsistency(true);
	}

	@Test
	public void callbackConsistencyPrimitiveSkillReusesOriginalLocalView()
	{
		useProjectileNativeView();
		tick();
		changingCallbackLocalView = true;
		callbackLocalViewReads = 0;
		plugin.onStatChanged(new net.runelite.api.events.StatChanged(Skill.MAGIC, 321, 99, 75));
		assertEquals(1, callbackLocalViewReads);
		changingCallbackLocalView = false;
		tick();
		assertNotNull(callback("SKILL_STATE_CHANGED").getSceneKey());
	}

	@Test
	public void callbackConsistencySelfInteractionReusesOriginalNameAndView()
	{
		assertSelfInteractionConsistency(0);
	}

	@Test
	public void callbackConsistencySelfInteractionKeepsFailedName()
	{
		assertSelfInteractionConsistency(1);
	}

	@Test
	public void callbackConsistencySelfInteractionKeepsNullName()
	{
		assertSelfInteractionConsistency(2);
	}

	private void assertSelfInteractionConsistency(int mode)
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		NPC actor = consistencyNpc(mode, 2, -1, reads, null);
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(actor));
		assertEquals(1, reads[0]);
		assertEquals(1, reads[1]);
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		com.tickreplaylogger.model.world.ActorInteractionState interaction = (com.tickreplaylogger.model.world.ActorInteractionState) raw
			.getData().get("interaction");
		assertEquals(raw.getActorName(), interaction.getActorName());
		assertEquals(raw.getActorRef(), interaction.getActorRef());
		assertEquals(raw.getData().get("worldViewId"), interaction.getWorldViewId());
	}

	@Test
	public void callbackConsistencyObservedIdentityPreservesLifetimeWithoutNativeRereads()
	{
		com.tickreplaylogger.capture.ActorIdentity identities = new com.tickreplaylogger.capture.ActorIdentity();
		NPC actor = stub(NPC.class, (name, args) ->
		{
			throw new IllegalStateException("identity must use supplied primitives");
		});
		String first = identities.observedRef(actor, player, 42, 77);
		assertTrue(first.startsWith("npc:77:v42:g"));
		assertEquals(first, identities.observedRef(actor, player, null, null));
		identities.forget(actor);
		String next = identities.observedRef(actor, player, null, null);
		assertTrue(next.startsWith("npc:unknown:vunknown:g"));
		assertNotEquals(first, next);
		identities.reset();
		assertNull(identities.existingRef(actor, player));
		assertEquals("player", identities.observedRef(player, player, null, null));
	}

	@Test
	public void callbackConsistencyMutualInteractionDoesNotRecursivelyCaptureActors()
	{
		useProjectileNativeView();
		tick();
		NPC[] pair =
		{ null, null };
		for (int i = 0; i < 2; i++)
		{
			final int index = i;
			pair[i] = stub(NPC.class, (name, args) ->
			{
				if (name.equals("getInteracting"))
					return pair[1 - index];
				if (name.equals("getName"))
					return "Mutual " + index;
				if (name.equals("getIndex"))
					return 70 + index;
				if (name.equals("getWorldView"))
					return projectileNativeView;
				if (name.equals("getWorldLocation"))
					return new WorldPoint(3200, 3199, 0);
				return null;
			});
		}
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(pair[0]));
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(pair[1]));
		tick();
		com.tickreplaylogger.model.world.ObservedEvent raw = callback("NPC_SPAWNED");
		assertNotNull(raw.getActorRef());
		assertNotNull(
			((com.tickreplaylogger.model.world.ActorInteractionState) raw.getData().get("interaction")).getActorRef());
	}

	@Test
	public void callbackConsistencyResourceActionReusesOriginalCallbackCycle()
	{
		tick();
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		gameCycleReads = 0;
		specialVarp = 750;
		plugin.onVarbitChanged(new net.runelite.api.events.VarbitChanged());
		assertEquals("Resource action must reuse the callback cycle sample", 1, gameCycleReads);
		tick();
		assertTrue(
			export().getEvents().getPlayerActions().stream().anyMatch(a -> "SPECIAL_ATTACK".equals(a.getType())));
	}

	@Test
	public void callbackConsistencyFixturePreservesOriginalSamplesAndOwnership() throws Exception
	{
		useProjectileNativeView();
		npcObservedSize = 1;
		tick();
		int[] failedReads =
		{ 0, 0, 0 };
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(consistencyNpc(1, -1, -1, failedReads, null)));
		assertEquals(1, failedReads[0]);
		hp = 93;
		hit(HitsplatID.DAMAGE_ME, 6);
		tick();
		int[] foreignReads =
		{ 0, 0, 0 };
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(
			consistencyNpc(0, 0, -1, foreignReads, callbackView(42, 7000, 8000))));
		assertEquals(1, foreignReads[0]);
		assertEquals(1, foreignReads[1]);
		hp = 84;
		hit(HitsplatID.DAMAGE_ME, 9);
		tick();
		int[] nullReads =
		{ 0, 0, 0 };
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(consistencyNpc(2, 1, 0, nullReads, null)));
		int[] selfReads =
		{ 0, 0, 0 };
		plugin.onNpcSpawned(new net.runelite.api.events.NpcSpawned(consistencyNpc(0, 2, -1, selfReads, null)));
		assertEquals(1, selfReads[0]);
		assertEquals(1, selfReads[1]);
		plugin.onMenuOptionClicked(
			new MenuOptionClicked(stub(MenuEntry.class, (name, args) -> name.equals("getOption") ? "Attack"
				: name.equals("getTarget") ? "Test boss" : name.equals("getActor") ? boss : null)));
		hit(boss, HitsplatID.DAMAGE_OTHER, 7);
		hit(boss, HitsplatID.DAMAGE_ME, 4);
		tick();
		hp = 0;
		hit(HitsplatID.POISON, 84);
		plugin.onActorDeath(new ActorDeath(player));
		tick();
		com.google.gson.JsonObject out = gson.fromJson(
			com.tickreplaylogger.capture.RecordingExport.forViewer(storedDeaths().get(0), gson),
			com.google.gson.JsonObject.class);
		out.getAsJsonObject("meta").addProperty("recordingId", "synthetic-callback-consistency");
		out.getAsJsonObject("meta").addProperty("exportedAtMs", 0L);
		for (com.google.gson.JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		for (com.google.gson.JsonElement row : out.getAsJsonArray("journalTicks"))
		{
			row.getAsJsonObject().remove("timestamp");
			row.getAsJsonObject().remove("tickDurationMs");
		}
		String json = gson.toJson(out);
		assertFalse(json.contains("Later incompatible name"));
		assertFalse(json.contains("Proxy"));
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/callback-consistency.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(5, out.getAsJsonArray("ticks").size());
		assertEquals(99, export().getEvents().getDamageEvents().stream()
			.filter(d -> "RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(7, export().getEvents().getDamageEvents().stream()
			.filter(d -> "NPC_RECEIVED".equals(d.getDirection())).mapToInt(DamageRecord::getAmount).sum());
		assertEquals(4, export().getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(DamageRecord::getAmount).sum());
	}

	private void assertMenuReferenceConsistency(boolean originalNull) throws Exception
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0 };
		WorldCaptureService capture = new WorldCaptureService()
		{
			@Override
			public String actorRef(Actor actor, Player local)
			{
				if (actor == boss)
				{
					reads[0]++;
					if (originalNull && reads[0] == 1)
						return null;
					if (!originalNull && reads[0] > 1)
						throw new IllegalStateException("later identity read failed");
				}
				return super.actorRef(actor, local);
			}
		};
		capture.setDamageMap(DamageMap.load(gson));
		inject("captureService", capture);
		MenuEntry entry = stub(MenuEntry.class, (name, args) -> name.equals("getOption") ? "Attack"
			: name.equals("getTarget") ? "Test boss" : name.equals("getActor") ? boss : null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals("Raw input and action share one identity result, including null", 1, reads[0]);
		tick();
		assertEquals(callback("MENU_OPTION").getRelatedActorRef(),
			export().getEvents().getPlayerActions().get(0).getTargetRef());
	}

	@Test
	public void remainingCallbackDeathKeepsOriginalSuccess() throws Exception
	{
		assertRemainingDeath(0);
	}

	@Test
	public void remainingCallbackDeathKeepsOriginalFailure() throws Exception
	{
		assertRemainingDeath(1);
	}

	@Test
	public void remainingCallbackDeathKeepsOriginalNull() throws Exception
	{
		assertRemainingDeath(2);
	}

	@Test
	public void remainingCallbackImpactKeepsOriginalSuccess() throws Exception
	{
		assertRemainingImpact(0);
	}

	@Test
	public void remainingCallbackImpactKeepsOriginalFailure() throws Exception
	{
		assertRemainingImpact(1);
	}

	@Test
	public void remainingCallbackImpactKeepsOriginalNull() throws Exception
	{
		assertRemainingImpact(2);
	}

	@Test
	public void remainingCallbackDefenderKeepsOriginalSuccess() throws Exception
	{
		assertRemainingDefender(0);
	}

	@Test
	public void remainingCallbackDefenderKeepsOriginalFailure() throws Exception
	{
		assertRemainingDefender(1);
	}

	@Test
	public void remainingCallbackDefenderKeepsOriginalNull() throws Exception
	{
		assertRemainingDefender(2);
	}

	private void assertRemainingDeath(int mode) throws Exception
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		player = remainingActor(Player.class, player, mode, reads);
		plugin.onActorDeath(new ActorDeath(player));
		Object pending = remainingField(plugin, "pendingDeath");
		int nameReads = reads[0], positionReads = reads[1];
		hp = 0;
		tick();
		Map<String, Object> raw = callback("ACTOR_DEATH").getData();
		assertEquals("Death raw sample must retain the original name", mode == 0 ? "Original callback actor" : null,
			raw.get("name"));
		assertEquals(raw.get("name"), remainingField(pending, "name"));
		assertEquals(mode == 0 ? new WorldPoint(3211, 3212, 0) : null, remainingField(pending, "position"));
		assertRemainingIssue(raw, mode, "worldPosition");
		assertEquals(1, nameReads);
		assertEquals(1, positionReads);
		assertEquals(1, storedDeaths().size());
		DeathRecordingV2 rich = gson.fromJson(preparedJson(storedDeaths().get(0)), DeathRecordingV2.class);
		assertEquals(mode == 0 ? "Original callback actor" : null, rich.getMeta().getRsn());
		assertEquals("Later settled position stays independent", 3221, world.snapshotAtTick(2).getPlayer().getWorldX());
	}

	private void assertRemainingImpact(int mode) throws Exception
	{
		useProjectileNativeView();
		tick();
		int[] reads =
		{ 0, 0, 0 };
		player = remainingActor(Player.class, player, mode, reads);
		hit(HitsplatID.DAMAGE_ME, 7);
		Object pending = ((List<?>) remainingField(plugin, "pendingHits")).get(0);
		assertEquals("Impact position must be the callback sample", mode == 0 ? new WorldPoint(3211, 3212, 0) : null,
			remainingField(pending, "position"));
		assertEquals(1, reads[1]);
		assertEquals(1, reads[2]);
		tick();
		Map<String, Object> raw = (Map<String, Object>) callback("HITSPLAT").getData().get("actorState");
		assertEquals("Local coordinates remain the original callback facts", mode == 0,
			raw.get("localPositionKnown"));
		assertEquals(mode == 0 ? 7104 : null, raw.get("localX"));
		assertEquals(mode == 0 ? 7232 : null, raw.get("localY"));
		assertEquals(mode == 0, raw.get("worldPositionKnown"));
		assertRemainingIssue(raw, mode, "worldPosition");
		DamageRecord damage = capturedLastDamage();
		assertEquals(7, damage.getAmount());
		assertEquals(Boolean.valueOf(mode == 0), damage.getImpactPositionKnown());
		if (mode == 0)
			assertEquals(3211, damage.getImpactX());
		if (mode == 1)
			assertTrue(damage.getCaptureIssues().stream().anyMatch(s -> s.startsWith("impactWorldPosition:")));
		assertEquals(3221, world.snapshotAtTick(2).getPlayer().getWorldX());
		assertEquals(1, journal.getTickHistory().get(1).getDamageEvents().size());
	}

	private void assertRemainingDefender(int mode) throws Exception
	{
		useProjectileNativeView();
		tick();
		for (boolean owned : new boolean[]
		{ true, false })
		{
			int[] reads =
			{ 0, 0, 0 };
			NPC defender = remainingActor(NPC.class, boss, mode, reads);
			hit(defender, owned ? HitsplatID.DAMAGE_ME : HitsplatID.DAMAGE_OTHER, owned ? 4 : 7);
			int nameReads = reads[0], positionReads = reads[1];
			tick();
			Map<String, Object> raw = (Map<String, Object>) callback("HITSPLAT").getData().get("actorState");
			DamageRecord damage = capturedLastDamage();
			assertEquals(mode == 0 ? "Original callback actor" : "Unknown", damage.getDefenderName());
			assertEquals(mode == 0 ? "Original callback actor" : null, raw.get("name"));
			assertEquals(Boolean.valueOf(mode == 0), damage.getImpactPositionKnown());
			if (mode == 0)
				assertEquals(3211, damage.getImpactX());
			assertEquals(owned ? "LOCAL_PLAYER" : "OTHER", damage.getOwnership());
			assertRemainingIssue(raw, mode, "worldPosition");
			if (mode == 1)
				assertTrue(damage.getCaptureIssues().stream().anyMatch(s -> s.startsWith("defenderWorldPosition:")));
			assertEquals(1, nameReads);
			assertEquals(1, positionReads);
		}
		List<DamageEvent> damage = journal.getTickHistory().get(1).getDamageEvents();
		assertEquals(1, damage.size());
		assertTrue(journal.getTickHistory().get(2).getDamageEvents().isEmpty());
		assertEquals(4, damage.get(0).getAmount());
		assertEquals(mode == 0 ? "Original callback actor" : "Unknown", damage.get(0).getSource());
		assertEquals(11, export().getEvents().getDamageEvents().stream().mapToInt(DamageRecord::getAmount).sum());
	}

	private void assertRemainingIssue(Map<String, Object> data, int mode, String field)
	{
		Map<?, ?> issues = (Map<?, ?>) data.get("observationIssues");
		assertEquals(mode == 1, issues != null && issues.containsKey(field));
	}

	private static Object remainingField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private <T extends Actor> T remainingActor(Class<T> type, T original, int mode, int[] reads)
	{
		return stub(type, (name, args) ->
		{
			int slot = name.equals("getName") ? 0
				: name.equals("getWorldLocation") ? 1 : name.equals("getLocalLocation") ? 2 : -1;
			if (slot >= 0)
			{
				int count = ++reads[slot];
				if (count == 1 && mode == 1)
					throw new IllegalStateException("original callback field unavailable");
				if (count == 1 && mode == 2)
					return null;
				if (slot == 0)
					return count == 1 ? "Original callback actor" : "Later callback actor";
				if (slot == 1)
					return count == 1 ? new WorldPoint(3211, 3212, 0) : new WorldPoint(3221, 3222, 0);
				return count == 1 ? new LocalPoint(7104, 7232) : new LocalPoint(8384, 8512);
			}
			for (java.lang.reflect.Method method : type.getMethods())
				if (method.getName().equals(name) && method.getParameterCount() == (args == null ? 0 : args.length))
				{
					try
					{
						return method.invoke(original, args);
					}
					catch (java.lang.reflect.InvocationTargetException failure)
					{
						throw (RuntimeException) failure.getCause();
					}
					catch (IllegalAccessException failure)
					{
						throw new AssertionError(failure);
					}
				}
			return null;
		});
	}

	@Test
	public void nativeImpactStableViewKeepsThePositiveFloorObservation() throws Exception
	{
		assertNativeImpactCohort(0);
	}

	@Test
	public void nativeImpactOriginalNullCannotBorrowALaterFloorScene() throws Exception
	{
		assertNativeImpactCohort(1);
	}

	@Test
	public void nativeImpactOriginalFailureCannotBorrowALaterFloorScene() throws Exception
	{
		assertNativeImpactCohort(2);
	}

	@Test
	public void nativeImpactOriginalEmptyForeignViewCannotBorrowALaterFloorScene() throws Exception
	{
		assertNativeImpactCohort(3);
	}

	@Test
	public void nativeImpactMismatchedLocalViewCannotQualifyAFloor() throws Exception
	{
		assertNativeImpactCohort(4);
	}

	@Test
	public void nativeImpactDeferredLookupNeverRereadsTheActorViewInsideItsGuard() throws Exception
	{
		useProjectileNativeView();
		npcs.clear();
		addGauntletFloor(36048);
		tick();
		Player prior = player;
		int[] escaped =
		{ 0 };
		Field scope = plugin.getClass().getDeclaredField("activeHitScope");
		scope.setAccessible(true);
		player = stub(Player.class, (name, args) ->
		{
			if (name.equals("getWorldView"))
				try
				{
					if (scope.get(plugin) != null)
					{
						escaped[0]++;
						throw new IllegalStateException("later actor view must not be read inside native scope");
					}
				}
				catch (IllegalAccessException failure)
				{
					throw new AssertionError(failure);
				}
			return nativeAuditDelegate(Player.class, prior, name, args);
		});
		hit(HitsplatID.DAMAGE_ME, 18);
		tick();
		assertEquals("Corrupted Hunllef floor", capturedLastDamage().getAttackerName());
		assertEquals("HAZARD_ON_IMPACT_TILE", capturedLastDamage().getAttributionEvidence());
		assertEquals(0, escaped[0]);
		assertNull(scope.get(plugin));
		assertEquals(18, capturedLastDamage().getAmount());
	}

	@Test
	public void nativeImpactOptionalLookupFailureRestoresTheScopeForTheNextHit() throws Exception
	{
		useProjectileNativeView();
		npcs.clear();
		addGauntletFloor(36048);
		tick();
		net.runelite.api.WorldView original = projectileNativeView;
		int[] reads =
		{ 0 };
		projectileNativeView = stub(net.runelite.api.WorldView.class, (name, args) ->
		{
			if (name.equals("getGraphicsObjects") && ++reads[0] == 1)
				throw new IllegalStateException("optional graphics unavailable");
			return nativeAuditDelegate(net.runelite.api.WorldView.class, original, name, args);
		});
		hit(HitsplatID.DAMAGE_ME, 18);
		assertNull(remainingField(plugin, "activeHitScope"));
		tick();
		assertEquals("Unknown", capturedLastDamage().getAttackerName());
		assertNull(remainingField(plugin, "activeHitScope"));
		hit(HitsplatID.DAMAGE_ME, 18);
		tick();
		assertEquals("Corrupted Hunllef floor", capturedLastDamage().getAttackerName());
		assertNull(remainingField(plugin, "activeHitScope"));
		assertEquals(36, export().getEvents().getDamageEvents().stream().mapToInt(DamageRecord::getAmount).sum());
	}

	private void assertNativeImpactCohort(int mode) throws Exception
	{
		useProjectileNativeView();
		npcs.clear();
		addGauntletFloor(36048);
		tick();
		Player prior = player;
		net.runelite.api.WorldView later = projectileNativeView;
		net.runelite.api.Tile[][][] emptyTiles = new net.runelite.api.Tile[1][104][104];
		net.runelite.api.Scene emptyScene = stub(net.runelite.api.Scene.class,
			(name, args) -> name.equals("getTiles") ? emptyTiles : null);
		net.runelite.api.WorldView original = stub(net.runelite.api.WorldView.class,
			(name, args) -> name.equals("getId") ? 42
				: name.equals("getScene") ? emptyScene
					: nativeAuditDelegate(net.runelite.api.WorldView.class, later, name, args));
		int[] reads =
		{ 0 };
		player = stub(Player.class, (name, args) ->
		{
			if (name.equals("getWorldView"))
			{
				if (++reads[0] == 1)
				{
					if (mode == 1)
						return null;
					if (mode == 2)
						throw new IllegalStateException("original native view unavailable");
					if (mode == 3)
						return original;
				}
				return later;
			}
			if (name.equals("getLocalLocation") && (mode == 3 || mode == 4) && reads[0] <= 1)
			{
				LocalPoint point = prior.getLocalLocation();
				return point == null ? null : new LocalPoint(point.getX(), point.getY(), 42);
			}
			return nativeAuditDelegate(Player.class, prior, name, args);
		});
		hit(HitsplatID.DAMAGE_ME, 18);
		Object pending = ((List<?>) remainingField(plugin, "pendingHits")).get(0);
		assertNull("Native callback scope is restored", remainingField(plugin, "activeHitScope"));
		Object capturedView = remainingField(remainingField(pending, "nativeScope"), "view");
		int callbackReads = reads[0];
		tick();
		assertNull("Native deferred scope is restored", remainingField(plugin, "activeHitScope"));
		DamageRecord damage = capturedLastDamage();
		assertEquals(18, damage.getAmount());
		Map<String, Object> actorState = (Map<String, Object>) callback("HITSPLAT").getData().get("actorState");
		if (mode == 0)
		{
			assertEquals("Corrupted Hunllef floor", damage.getAttackerName());
			assertEquals("HAZARD_ON_IMPACT_TILE", damage.getAttributionEvidence());
			assertSame(later, capturedView);
		}
		else
		{
			assertEquals("A later native frame cannot supply the callback's cause", "Unknown",
				damage.getAttackerName());
			assertFalse("HAZARD_ON_IMPACT_TILE".equals(damage.getAttributionEvidence()));
			if (mode == 3)
			{
				assertEquals(42, actorState.get("worldViewId"));
				assertSame(original, capturedView);
				assertNotSame(emptyScene, later.getScene());
			}
			else if (mode == 4)
			{
				assertEquals(net.runelite.api.WorldView.TOPLEVEL, actorState.get("worldViewId"));
				assertSame(later, capturedView);
			}
			else
			{
				assertNull(actorState.get("worldViewId"));
				assertNull(capturedView);
			}
		}
		assertEquals("The impact native cohort reuses the original actor view", 1, callbackReads);
		assertEquals(1, journal.getTickHistory().get(1).getDamageEvents().size());
		assertEquals(18, journal.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
	}

	private Object nativeAuditDelegate(Class<?> type, Object original, String name, Object[] args)
	{
		for (java.lang.reflect.Method method : type.getMethods())
			if (method.getName().equals(name) && method.getParameterCount() == (args == null ? 0 : args.length))
			{
				try
				{
					return method.invoke(original, args);
				}
				catch (java.lang.reflect.InvocationTargetException failure)
				{
					throw (RuntimeException) failure.getCause();
				}
				catch (IllegalAccessException failure)
				{
					throw new AssertionError(failure);
				}
			}
		return null;
	}

	private NPC consistencyNpc(int nameMode, int viewMode, int positionMode, int[] reads,
		net.runelite.api.WorldView foreign)
	{
		NPC original = npc("Corrupted Hunllef", 77);
		NPC[] self =
		{ null };
		self[0] = stub(NPC.class, (name, args) ->
		{
			if (viewMode == 2 && name.equals("getInteracting"))
				return self[0];
			if (name.equals("getName"))
			{
				int count = ++reads[0];
				if (nameMode == 1 && count == 1 || nameMode == 3 && count > 1)
					throw new IllegalStateException("controlled name unavailable");
				if (nameMode == 2 && count == 1)
					return null;
				return nameMode == 0 && count > 1 ? "Later incompatible name" : "Corrupted Hunllef";
			}
			if (name.equals("getWorldView"))
			{
				int count = ++reads[1];
				if (count == 1 && viewMode == 3)
					throw new IllegalStateException("controlled view unavailable");
				return count == 1 && viewMode == 0 ? foreign
					: count == 1 && viewMode == 1 ? null : projectileNativeView;
			}
			if (name.equals("getWorldLocation"))
			{
				int count = ++reads[2];
				if (count == 1 && positionMode == 1)
					throw new IllegalStateException("controlled position unavailable");
				return count == 1 && positionMode == 0 ? null : new WorldPoint(3200, 3199, 0);
			}
			for (java.lang.reflect.Method method : NPC.class.getMethods())
				if (method.getName().equals(name) && method.getParameterCount() == (args == null ? 0 : args.length))
				{
					try
					{
						return method.invoke(original, args);
					}
					catch (java.lang.reflect.InvocationTargetException failure)
					{
						throw (RuntimeException) failure.getCause();
					}
					catch (IllegalAccessException failure)
					{
						throw new AssertionError(failure);
					}
				}
			return null;
		});
		return self[0];
	}

	@Test
	public void graphicSourceHintsReachActualHandlerExportWithoutActorCueRules()
	{
		useProjectileNativeView();
		npcs.clear();
		tick();
		int[] ids = { 2148, 1556, 2346, 2347, 1295 };
		String[] labels = { "Bomber scarab explosion", "Xarpus poison splash", "Vet'ion lightning", "Vet'ion lightning", "Lizardman shaman spawn explosion" };
		for (int index = 0; index < ids.length; index++)
		{
			String label = labels[index];
			graphicsObjects.clear();
			GraphicsObject graphic = callbackGraphic(ids[index], projectileNativeView,
				new LocalPoint(6400, 6400, projectileNativeView), gameCycle, 0);
			graphicsObjects.add(graphic);
			plugin.onGraphicsObjectCreated(new net.runelite.api.events.GraphicsObjectCreated(graphic));
			hit(HitsplatID.DAMAGE_ME, 9);
			tick();
			DamageRecord record = capturedLastDamage();
			assertEquals(9, record.getAmount());
			assertNotNull(record.getSourceResolution());
			assertTrue(record.getSourceResolution().isUnidentifiedPossible());
			assertTrue("Graphic " + ids[index] + " must reach the actual current-tick adapter", record.getSourceResolution().getCandidates()
				.stream().anyMatch(candidate -> label.equals(candidate.getName())));
			assertTrue(callback("GRAPHICS_OBJECT_CREATED").getData().get("graphicObservationId") instanceof Number);
		}
	}

	private GraphicsObject callbackGraphic(int id, net.runelite.api.WorldView view, LocalPoint location, int startCycle,
		int z)
	{
		return stub(GraphicsObject.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return id;
			case "getWorldView":
				return view;
			case "getLocation":
				return location;
			case "getStartCycle":
				return startCycle;
			case "getZ":
				return z;
			default:
				return null;
			}
		});
	}

	private void assertFixtureNativeScopesAreUnique(DeathRecordingV2 recording)
	{
		for (com.tickreplaylogger.model.world.WorldSnapshot frame : recording.getTicks())
		{
			java.util.Set<String> scopes = new java.util.HashSet<>();
			assertNotNull("Current positive fixture has a known primary view", frame.getWorldViewId());
			scopes.add(frame.getWorldViewId() + ":" + frame.getPlane());
			if (frame.getAdditionalViews() != null)
				for (com.tickreplaylogger.model.world.WorldSnapshot child : frame.getAdditionalViews())
				{
					assertNotNull(child.getWorldViewId());
					assertTrue("Native view/plane must be unique within exported tick " + frame.getTick(),
						scopes.add(child.getWorldViewId() + ":" + child.getPlane()));
				}
		}
	}

	private net.runelite.api.WorldView callbackView(int id, int baseX, int baseY)
	{
		return stub(net.runelite.api.WorldView.class, (name, args) ->
		{
			switch (name)
			{
			case "getId":
				return id;
			case "getBaseX":
				return baseX;
			case "getBaseY":
				return baseY;
			default:
				return null;
			}
		});
	}

	@Test
	public void ambiguousResourceImpactEnrichesOriginalCallbackWithoutCreatingHpDamage()
	{
		for (int type : new int[] { HitsplatID.DISEASE, 18, 20, 22, 24, 44, 45, 46, 47, 999999 })
		{
			hit(type, 3);
			tick();
			com.tickreplaylogger.model.world.ObservedEvent event = export().getEvents().getObservations().stream()
				.filter(e -> "HITSPLAT".equals(e.getType()) && ((Number) e.getData().get("hitsplatType")).intValue() == type)
				.findFirst().orElseThrow(AssertionError::new);
			assertEquals(3, event.getData().get("amount"));
			assertEquals("UNRESOLVED", event.getData().get("resourceOutcomeStatus"));
			assertEquals("RETAINED_IMPACT_CONTEXT", event.getData().get("attributionCaptureStatus"));
			assertFalse(((List<?>) event.getData().get("possibleOutcomeSurfaces")).isEmpty());
			assertNotNull(event.getData().get("sourceResolution"));
			DamageRecord context = (DamageRecord) event.getData().get("attributionContext");
			assertEquals("RESOURCE_IMPACT", context.getDirection());
			assertEquals(Long.valueOf(event.getSequence()), context.getObservationSequence());
			assertEquals(event.getTick(), context.getImpactTick());
			assertEquals(1, export().getEvents().getObservations().stream()
				.filter(e -> "HITSPLAT".equals(e.getType()) && e.getSequence() == event.getSequence()).count());
		}
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(journal.getTickHistory().stream().allMatch(t -> t.getDamageEvents().isEmpty()));
		assertTrue(journal.getTickHistory().stream().anyMatch(t -> t.getEffectNotes().stream().anyMatch(n -> n.startsWith("Impact 3 (HP/"))));
		for (TickData tick : journal.getTickHistory())
			for (Map.Entry<Integer, Long> link : tick.getEffectNoteSourceSequences().entrySet())
				assertTrue(export().getEvents().getObservations().stream().anyMatch(e -> "HITSPLAT".equals(e.getType())
					&& e.getTick() == tick.getTickNumber() && e.getSequence() == link.getValue() && e.getData().containsKey("attributionContext")));
	}

	@Test
	public void ambiguousAndKnownHpHitsRetainSharedProjectileEvidenceWithoutHpPromotion()
	{
		tick();
		projectile(boss, 1044);
		hit(HitsplatID.DAMAGE_ME, 7);
		hit(HitsplatID.DISEASE, 3);
		tick();
		assertEquals(1, export().getEvents().getDamageEvents().size());
		assertEquals(7, export().getEvents().getDamageEvents().get(0).getAmount());
		com.tickreplaylogger.model.world.ObservedEvent event = export().getEvents().getObservations().stream()
			.filter(e -> "HITSPLAT".equals(e.getType()) && Integer.valueOf(HitsplatID.DISEASE).equals(e.getData().get("hitsplatType")))
			.findFirst().orElseThrow(AssertionError::new);
		DamageRecord context = (DamageRecord) event.getData().get("attributionContext");
		assertNull(context.getAttributionCandidates());
		assertTrue(((List<Map<String, Object>>) event.getData().get("attributionCandidates")).stream()
			.anyMatch(c -> c.get("projectileObservationId") != null));
		assertTrue(export().getEvents().getDamageEvents().get(0).getAttributionCandidates().stream()
			.anyMatch(c -> c.get("projectileObservationId") != null));
	}

	@Test
	public void receivedTrackingToggleDoesNotAdmitAmbiguousImpactsThroughTheNewPath() throws Exception
	{
		inject("config", new TickReplayLoggerConfig()
		{
			@Override public boolean trackDamageReceived() { return false; }
			@Override public boolean trackPing() { return false; }
		});
		hit(HitsplatID.DISEASE, 3);
		tick();
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		com.tickreplaylogger.model.world.ObservedEvent event = export().getEvents().getObservations().stream()
			.filter(e -> "HITSPLAT".equals(e.getType())).findFirst().orElseThrow(AssertionError::new);
		assertFalse(event.getData().containsKey("attributionContext"));
		assertEquals("RECEIVED_TRACKING_DISABLED", event.getData().get("attributionCaptureStatus"));
	}

	@Test
	public void ambiguousImpactQueueLimitRetainsRawObservationsAndExplicitCoverage()
	{
		for (int i = 0; i < 257; i++) hit(HitsplatID.DISEASE, 3);
		tick();
		List<com.tickreplaylogger.model.world.ObservedEvent> events = export().getEvents().getObservations();
		assertEquals(257, events.stream().filter(e -> "HITSPLAT".equals(e.getType())).count());
		assertEquals(256, events.stream().filter(e -> e.getData().containsKey("attributionContext")).count());
		assertEquals(1, events.stream().filter(e -> "RESOURCE_IMPACT_QUEUE_LIMIT".equals(e.getData().get("attributionCaptureStatus"))).count());
		assertTrue(export().getEvents().getDamageEvents().isEmpty());
		assertTrue(journal.getTickHistory().get(0).getCaptureIssues().stream().anyMatch(issue -> issue.contains("queue limit")));
	}

	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			String name = method.getName();
			if ("hashCode".equals(name))
				return System.identityHashCode(proxy);
			if ("equals".equals(name))
				return proxy == args[0];
			if ("toString".equals(name))
				return type.getSimpleName();
			Object value = values.apply(name, args);
			if (value != null)
				return value;
			Class<?> result = method.getReturnType();
			if (result == boolean.class)
				return false;
			if (result == int.class)
				return "getGraphic".equals(name) || "getAnimation".equals(name) ? -1 : 0;
			if (result == long.class)
				return 0L;
			if (result == double.class)
				return 0D;
			if (result == float.class)
				return 0F;
			return null;
		});
	}

	/** Missing inventory is evidence coverage, not a third named damage source. */
	private static long solePhysicalProjectile(DamageRecord hit)
	{
		assertNotNull(hit.getAttributionCandidates());
		java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
		for (Map<String, Object> witness : hit.getAttributionCandidates())
			if ("PROJECTILE_OBSERVATION".equals(witness.get("kind")) && witness.get("projectileObservationId") instanceof Number)
				ids.add(((Number) witness.get("projectileObservationId")).longValue());
		assertEquals("One actual projectile lifetime must remain in the raw witnesses", 1, ids.size());
		return ids.iterator().next();
	}

	private static void assertProjectileAlternative(DamageRecord hit, long projectileId)
	{
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
		assertTrue(hit.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:" + projectileId)));
	}

	private static void assertSharedProjectileRecords(DamageRecord hit, List<DamageRecord> peers)
	{
		long projectileId = solePhysicalProjectile(hit);
		java.util.Set<Long> sequences = new java.util.LinkedHashSet<>();
		for (DamageRecord peer : peers)
		{
			assertEquals(projectileId, solePhysicalProjectile(peer));
			assertNotNull(peer.getObservationSequence());
			assertTrue(peer.getImpactTick() <= hit.getImpactTick());
			sequences.add(peer.getObservationSequence());
		}
		assertEquals(peers.size(), sequences.size());
		assertProjectileAlternative(hit, projectileId);
		assertTrue(hit.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
			candidate.getEvidence().contains("PROJECTILE_OBSERVATION_ID:" + projectileId)
				&& candidate.getEvidence().contains("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED")));
	}

	private void assertRecordedAnimationReceipt(DamageRecord hit, int animationId)
	{
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
		assertTrue(export().getEvents().getObservations().stream().anyMatch(event ->
			"ANIMATION_CHANGED".equals(event.getType()) && event.getActorRef() != null
				&& event.getSequence() < hit.getObservationSequence() && event.getTick() <= hit.getImpactTick()
				&& event.getGameCycle() != null && event.getGameCycle() <= hit.getObservedCycle()
				&& event.getData().get("animationId") instanceof Number
				&& ((Number) event.getData().get("animationId")).intValue() == animationId
				&& hit.getSourceResolution().getCandidates().stream().anyMatch(candidate ->
					event.getActorRef().equals(candidate.getActorRef()) && "POSSIBLE".equals(candidate.getStrength())
						&& candidate.getEvidence().contains("OBSERVATION_SEQUENCE:" + event.getSequence())
						&& candidate.getEvidence().stream().anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED")))));
	}

	private void assertRecordedStatusState(DamageRecord hit, Integer expectedValue, boolean known)
	{
		com.tickreplaylogger.model.world.PlayerState sampled = world.snapshotAtTick(hit.getImpactTick()).getPlayer();
		assertEquals(Boolean.valueOf(known), sampled.getPoisonValueKnown());
		if (expectedValue != null)
			assertEquals(expectedValue.intValue(), sampled.getPoisonValue());
		else
			assertTrue(sampled.getObservationIssues().containsKey("poisonValue"));
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
	}

	private static void assertNamedSourceAlternatives(DamageRecord record, String... expectedNames)
	{
		assertEquals(1L, record.getAttributionCandidates().stream()
			.filter(candidate -> "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE".equals(candidate.get("kind"))).count());
		assertEquals(expectedNames.length, record.getSourceResolution().getCandidates().size());
		java.util.Set<String> names = new java.util.LinkedHashSet<>(), keys = new java.util.LinkedHashSet<>();
		for (com.tickreplaylogger.model.world.SourceResolution.Candidate candidate : record.getSourceResolution().getCandidates())
		{
			names.add(candidate.getName());
			assertNotNull(candidate.getKey());
			keys.add(candidate.getKey());
		}
		assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList(expectedNames)), names);
		assertEquals(expectedNames.length, keys.size());
		assertTrue(record.getSourceResolution().isUnidentifiedPossible());
	}

	private static void assertTwoSourcesAndUnavailableInventory(com.tickreplaylogger.model.world.DamageRecord record)
	{
		assertEquals(1L, record.getAttributionCandidates().stream()
			.filter(candidate -> "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE".equals(candidate.get("kind"))).count());
		assertNotNull(record.getSourceResolution());
		assertEquals("Two source alternatives survive independently of the raw witness storage channel", 2,
			record.getSourceResolution().getCandidates().size());
		assertTrue(record.getSourceResolution().isUnidentifiedPossible());
	}
}
