package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** One small recorded window per evidence rule, plus the SourceResolution contract the website validates. */
public class DamageResolverTest
{
	private static final String CATALOG = "{\"version\":1,\"hitsplats\":{\"status\":{\"5\":\"Venom\",\"65\":\"Poison\"},"
		+ "\"nonHp\":[6,60]},\"npcs\":{\"2042\":[41,3,0,[5069]],\"2045\":[15,3,0,[]],\"9035\":[10,5,1,[]],"
		+ "\"7000\":[20,4,0,[100]],\"9041\":[5,4,0,[8425]]},\"venomous\":[2042],\"attackAnims\":[100,5069,8339],\"projectileStyles\":{\"1046\":\"magic\"},"
		+ "\"hazardGraphics\":{},\"surfaces\":{\"11700\":\"Poison cloud\"},\"contactNpcs\":{},"
		+ "\"selfDamageItems\":{\"7510\":[\"Dwarven rock cake\",[\"Eat\"]]}}";
	private static final int TICK = 50, CYCLE = 5000;

	private final DamageResolver resolver = new DamageResolver(
		new ResolverCatalog("test", new Gson().fromJson(CATALOG, JsonObject.class)));
	private final List<ProjectileState> projectiles = new ArrayList<>();
	private final List<NpcState> npcs = new ArrayList<>();
	private final List<HazardState> hazards = new ArrayList<>();
	private final List<ObservedEvent> observations = new ArrayList<>();
	private final List<DamageRecord> hits = new ArrayList<>();

	@Test
	public void statusHitsplatIsTheStatus()
	{
		hit(1, 6, 5);
		DamageRecord result = resolve().get(1L);
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
		assertEquals("Venom", result.getAttackerName());
		assertEquals("venom", result.getDamageType());
		assertEquals("ENVIRONMENTAL", result.getAttribution());
	}

	@Test
	public void projectileLandingOnTheHitNamesItsSource()
	{
		projectile(1, "Zulrah", 2042, 1046);
		hit(1, 30, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Zulrah", result.getAttackerName());
		assertEquals("npc:1", result.getAttackerRef());
		assertEquals("magic", result.getDamageType());
		assertEquals("OBSERVED", result.getAttribution());
	}

	@Test
	public void maxHitsDecideSplatsLandingTogether()
	{
		projectile(1, "Zulrah", 2042, 1044);
		projectile(2, "Snakeling", 2045, 1230);
		hit(1, 39, 16);
		hit(2, 1, 16);
		Map<Long, DamageRecord> results = resolve();
		assertEquals("Zulrah", results.get(1L).getAttackerName());
		assertEquals("IDENTIFIED", results.get(1L).getSourceResolution().getStatus());
		assertEquals("Snakeling", results.get(2L).getAttackerName());
	}

	@Test
	public void scaledContentNeverExcludesByMaxHit()
	{
		projectile(1, "Corrupted Hunllef", 9035, 1712);
		hit(1, 50, 16);
		assertEquals("Corrupted Hunllef", resolve().get(1L).getAttackerName());
	}

	@Test
	public void standingOnADamagingSurface()
	{
		hazards.add(HazardState.builder().kind("gameObject").id(11700).worldX(3200).worldY(3200).plane(0).build());
		hit(1, 3, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Poison cloud", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	@Test
	public void surfaceAndTimedAttackAreBothShown()
	{
		hazards.add(HazardState.builder().kind("gameObject").id(11700).worldX(3200).worldY(3200).plane(0).build());
		attacker(7000, 100, TICK);
		hit(1, 3, 16);
		SourceResolution resolution = resolve().get(1L).getSourceResolution();
		assertEquals("ALTERNATIVES", resolution.getStatus());
		assertEquals(2, resolution.getCandidates().size());
	}

	@Test
	public void adjacentAttackerWhoseMeleeLandsNow()
	{
		attacker(7000, 100, TICK);
		hit(1, 12, 16);
		assertEquals("Goblin warlord", resolve().get(1L).getAttackerName());
	}

	@Test
	public void attackerAboveItsMaxHitIsRuledOut()
	{
		attacker(7000, 100, TICK);
		hit(1, 25, 16);
		assertEquals("UNIDENTIFIED", resolve().get(1L).getSourceResolution().getStatus());
	}

	@Test
	public void eatingARockCake()
	{
		Map<String, Object> click = new HashMap<>();
		click.put("option", "Eat");
		click.put("target", "Dwarven rock cake");
		click.put("itemId", 7510);
		observations.add(ObservedEvent.builder().tick(TICK).sequence(10).type("MENU_OPTION").actorRef("player")
			.data(click).build());
		hit(1, 1, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Dwarven rock cake", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	@Test
	public void rockCakeOnTheHitTickBeatsAnAttackerOutOfMeleeRange()
	{
		npcs.add(NpcState.builder().actorRef("npc:9").npcId(9183).name("Guard").worldX(3206).worldY(3200).plane(0)
			.size(1).interactingWithPlayer(true).worldViewId(0).build());
		animation("npc:9", 430, TICK - 3);
		click("Guzzle", "Dwarven rock cake", 7510, null, TICK);
		message("OW! A terrible shock jars through your skull.", TICK);
		hit(1, 7, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Dwarven rock cake", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	@Test
	public void failedPickpocketNamesTheNpcNotTheMessage()
	{
		npcs.add(NpcState.builder().actorRef("npc:4").npcId(5000).name("Lindir").worldX(3201).worldY(3200).plane(0)
			.size(1).worldViewId(0).build());
		click("Pickpocket", "Lindir", -1, "npc:4", TICK - 1);
		message("You've been stunned!", TICK);
		hit(1, 5, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Lindir", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
		assertEquals("npc:4", result.getAttackerRef());
	}

	@Test
	public void loneAdjacentAttackerIsIdentifiedWithoutKnownAnimations()
	{
		npcs.add(NpcState.builder().actorRef("npc:9").npcId(9183).name("Guard").worldX(3201).worldY(3200).plane(0)
			.size(1).interactingWithPlayer(true).worldViewId(0).build());
		animation("npc:9", 430, TICK);
		hit(1, 2, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Guard", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	/** Corrupted Gauntlet spiders share a cache symbol with crystal spiders; their own attack must still count. */
	@Test
	public void cacheNamedAttackCountsEvenWhenTheNpcListIsWrong()
	{
		npcs.add(NpcState.builder().actorRef("npc:3").npcId(9041).name("Corrupted Spider").worldX(3200).worldY(3201)
			.plane(0).size(1).interactingWithPlayer(true).worldViewId(0).build());
		animation("npc:3", 8339, TICK);
		hit(1, 5, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Corrupted Spider", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	/** Hunllef's ranged animation launched the projectile that landed earlier; it is not a second hit source. */
	@Test
	public void anAttackThatLaunchedAProjectileDoesNotAlsoCountAsATimedHit()
	{
		npcs.add(NpcState.builder().actorRef("npc:7").npcId(7000).name("Goblin warlord").worldX(3209).worldY(3200)
			.plane(0).size(1).interactingWithPlayer(true).worldViewId(0).build());
		observations.add(ObservedEvent.builder().tick(TICK - 3).gameCycle(CYCLE - 90).sequence(5).type("ANIMATION_CHANGED")
			.actorRef("npc:7").data(new HashMap<>(Map.of("animationId", 100))).build());
		projectiles.add(ProjectileState.builder().observationId(9L).projectileId(1046).targetRef("player")
			.sourceRef("npc:7").sourceName("Goblin warlord").sourceNpcId(7000).startCycle(CYCLE - 70).endCycle(CYCLE - 30)
			.observedTick(TICK - 2).build());
		hazards.add(HazardState.builder().kind("gameObject").id(11700).worldX(3200).worldY(3200).plane(0).build());
		hit(1, 6, 16);
		DamageRecord result = resolve().get(1L);
		assertEquals("Poison cloud", result.getAttackerName());
		assertEquals("IDENTIFIED", result.getSourceResolution().getStatus());
	}

	/** Two Snakelings: one's projectile must not cancel the other's adjacent melee attack. */
	@Test
	public void anotherSameNamedNpcsProjectileDoesNotHideAMeleeAttack()
	{
		npcs.add(NpcState.builder().actorRef("npc:9").npcId(7000).name("Goblin warlord").worldX(3201).worldY(3200)
			.plane(0).size(1).interactingWithPlayer(true).worldViewId(0).build());
		observations.add(ObservedEvent.builder().tick(TICK).gameCycle(CYCLE).sequence(5).type("ANIMATION_CHANGED")
			.actorRef("npc:9").data(new HashMap<>(Map.of("animationId", 100))).build());
		projectiles.add(ProjectileState.builder().observationId(9L).projectileId(1046).targetRef("npc:20")
			.sourceRef("npc:8").sourceName("Goblin warlord").startCycle(CYCLE + 20).endCycle(CYCLE + 60)
			.observedTick(TICK).build());
		hit(1, 12, 16);
		assertEquals("Goblin warlord", resolve().get(1L).getAttackerName());
	}

	@Test
	public void aMessageAloneIsNeverNamedAsTheSource()
	{
		message("You've been stunned!", TICK);
		hit(1, 3, 16);
		assertFalse("IDENTIFIED".equals(resolve().get(1L).getSourceResolution().getStatus()));
	}

	@Test
	public void nothingObservedIsUnknown()
	{
		hit(1, 7, 16);
		SourceResolution resolution = resolve().get(1L).getSourceResolution();
		assertEquals("UNIDENTIFIED", resolution.getStatus());
		assertEquals("NONE", resolution.getIdentityLevel());
		assertTrue(resolution.isUnidentifiedPossible());
		assertTrue(resolution.getCandidates().isEmpty());
	}

	/** The website accepts IDENTIFIED with one candidate only, and ACTOR identity only with a full location. */
	@Test
	public void resolutionsFollowTheExportContract()
	{
		projectile(1, "Zulrah", 2042, 1046);
		projectile(2, "Snakeling", 2045, 1230);
		hit(1, 5, 16);
		hit(2, 5, 16);
		for (DamageRecord result : resolve().values())
		{
			SourceResolution r = result.getSourceResolution();
			assertEquals(1, r.getVersion());
			assertEquals(DamageResolver.METHOD, r.getMethod());
			assertTrue(!"IDENTIFIED".equals(r.getStatus()) || r.getCandidates().size() == 1 && !r.isUnidentifiedPossible());
			for (SourceResolution.Candidate c : r.getCandidates())
			{
				assertNotNull(c.getFamilyKey());
				assertTrue(!"ACTOR".equals(r.getIdentityLevel()) || c.getSceneKey() != null && c.getPlane() != null);
			}
		}
	}

	private Map<Long, DamageRecord> resolve()
	{
		WorldSnapshot frame = WorldSnapshot.builder().tick(TICK).sceneKey("scene")
			.player(PlayerState.builder().worldX(3200).worldY(3200).plane(0).worldPositionKnown(true).build())
			.npcs(npcs).projectiles(projectiles).hazards(hazards).build();
		return resolver.resolve(DeathRecordingV2.builder().ticks(List.of(frame)).events(DeathRecordingV2.Events.builder()
			.damageEvents(hits).observations(observations).build()).build());
	}

	private void hit(long sequence, int amount, int type)
	{
		hits.add(DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(TICK).observedCycle(CYCLE)
			.observationSequence(sequence).amount(amount).hitsplatType(type).damageType("unknown").build());
	}

	private void projectile(long id, String source, int npcId, int projectileId)
	{
		projectiles.add(ProjectileState.builder().observationId(id).projectileId(projectileId).targetRef("player")
			.sourceRef("npc:" + id).sourceName(source).sourceNpcId(npcId).endCycle(CYCLE).sourceSceneKey("scene")
			.sourceWorldViewId(0).sourcePlane(0).observedTick(TICK - 1).build());
	}

	private void click(String option, String target, int itemId, String npcRef, int tick)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("option", option);
		data.put("target", target);
		data.put("itemId", itemId);
		data.put("menuAction", npcRef == null ? "CC_OP" : "NPC_THIRD_OPTION");
		observations.add(ObservedEvent.builder().tick(tick).sequence(10).type("MENU_OPTION").actorRef("player")
			.relatedActorRef(npcRef).data(data).build());
	}

	private void message(String text, int tick)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("message", text);
		observations.add(ObservedEvent.builder().tick(tick).sequence(11).type("SYSTEM_MESSAGE").data(data).build());
	}

	private void animation(String ref, int id, int tick)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("animationId", id);
		observations.add(ObservedEvent.builder().tick(tick).sequence(5).type("ANIMATION_CHANGED").actorRef(ref)
			.data(data).build());
	}

	private void attacker(int npcId, int animation, int tick)
	{
		npcs.add(NpcState.builder().actorRef("npc:9").npcId(npcId).name("Goblin warlord").worldX(3201).worldY(3200)
			.plane(0).size(1).interactingWithPlayer(true).worldViewId(0).build());
		Map<String, Object> data = new HashMap<>();
		data.put("animationId", animation);
		observations.add(ObservedEvent.builder().tick(tick).sequence(5).type("ANIMATION_CHANGED").actorRef("npc:9")
			.data(data).build());
	}
}
