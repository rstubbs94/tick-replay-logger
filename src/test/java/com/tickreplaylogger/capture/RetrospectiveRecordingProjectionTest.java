/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RetrospectiveRecordingProjectionTest
{
	@Test
	public void unavailableResolverStillBuildsReplayRowsFromOriginalHits()
	{
		DamageRecord original = hit(1, 5, 7, null).toBuilder().attackerName("Unknown").attribution("UNKNOWN").build();
		com.tickreplaylogger.model.TickData state = com.tickreplaylogger.model.TickData.builder().tickNumber(5).build();
		DeathRecordingV2 raw = DeathRecordingV2.builder().journalTicks(Collections.singletonList(state))
			.events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(original)).build()).build();
		DeathRecordingV2 completed = new ResolverV1Recording(
			ResolverTestCatalog.engine(new com.google.gson.JsonObject()), new com.google.gson.Gson(), raw).enrich(raw);
		assertEquals(1, completed.getJournalTicks().get(0).getDamageEvents().size());
		assertEquals(7, completed.getJournalTicks().get(0).getDamageEvents().get(0).getAmount());
		assertEquals("Unknown", completed.getJournalTicks().get(0).getDamageEvents().get(0).getSource());
		assertTrue(state.getDamageEvents().isEmpty());
		assertNull(original.getSourceResolution());
	}

	private DamageRecord hit(long seq, int tick, int amount, String ref)
	{
		return DamageRecord.builder().observationSequence(seq).observedCycle(tick * 30).impactTick(tick)
			.amount(amount).direction("RECEIVED").defenderRef("player").hitsplatType(16).hitsplatTypeKnown(true)
			.impactX(3200).impactY(3201).prayersAtImpact(Collections.singletonList("PROTECT_FROM_MAGIC"))
			.attackerRef(ref).attackerName(ref).attribution("TIMED").build();
	}

	private ObservedEvent copy(ObservedEvent event, long sequence, String actor)
	{
		return ObservedEvent.builder().type(event.getType()).tick(event.getTick()).sequence(sequence).actorRef(actor).data(event.getData()).build();
	}

	@Test
	public void workerSelectsAllDeathTickImpactsAndKeepsLaterRevivalOutsideFatalChoice()
	{
		DamageRecord first = hit(1, 5, 23, "npc:a"), second = hit(2, 5, 51, "npc:b"), revival = hit(3, 6, 99, "npc:c");
		DeathRecordingV2 raw = DeathRecordingV2.builder().meta(DeathRecordingV2.Meta.builder().deathTick(5).build())
			.events(DeathRecordingV2.Events.builder().damageEvents(Arrays.asList(first, second, revival)).build())
			.summary(DeathRecordingV2.Summary.builder().totalDamageTakenInBuffer(74).build()).build();
		DeathRecordingV2 completed = RecordingProjection.apply(raw, Collections.emptyMap(), "catalog");
		assertEquals(Arrays.asList(first, second), completed.getSummary().getFatalHitCandidates());
		assertSame(second, completed.getSummary().getFatalHit());
		assertEquals("Unknown", completed.getSummary().getKillerName());
		assertEquals("UNKNOWN", completed.getSummary().getKillerConfidence());
		assertEquals("MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS", completed.getSummary().getKillerBasis());
		assertEquals("LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK", completed.getSummary().getFatalHitBasis());
		assertEquals(0, TickRecording.fromPrepared(completed, 1, "room", true).getFinalHp());
		assertNull(raw.getSummary().getFatalHit());
	}

	@Test
	public void unresolvedResourceBlocksKillerAndForgedOutcomeCannotReplaceOriginalMeasurements()
	{
		DamageRecord original = hit(1, 5, 7, null);
		DamageRecord forged = original.toBuilder().attackerName("Source").attackerRef("npc:a")
			.impactX(9999).prayersAtImpact(Collections.emptyList()).observedHazardContacts(Collections.singletonList(Collections.singletonMap("invented", true))).build();
		DeathRecordingV2 raw = DeathRecordingV2.builder().meta(DeathRecordingV2.Meta.builder().deathTick(5).build())
			.events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(original)).build())
			.summary(DeathRecordingV2.Summary.builder().unresolvedImpactObservationCount(1).build()).build();
		DeathRecordingV2 completed = RecordingProjection.apply(raw, Collections.singletonMap(1L, forged), "catalog");
		DamageRecord exported = completed.getEvents().getDamageEvents().get(0);
		assertEquals("Source", exported.getAttackerName());
		assertEquals(original.getImpactX(), exported.getImpactX());
		assertEquals(original.getPrayersAtImpact(), exported.getPrayersAtImpact());
		assertEquals(original.getObservedHazardContacts(), exported.getObservedHazardContacts());
		assertEquals(7, exported.getAmount());
		assertNull(completed.getSummary().getFatalHit());
		assertEquals("UNRESOLVED_IMPACT_IN_DEATH_TICK", completed.getSummary().getKillerBasis());
		assertEquals(1, completed.getSummary().getFatalHitCandidates().size());
		assertNull(original.getAttackerRef());
	}
	@Test
	public void laterUnfinalizedLocalCallbackAndRawGapsVetoTheEarlierNamedKiller()
	{
		DamageRecord known = hit(10, 5, 7, "npc:a");
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("amount", 9); fields.put("resourceSurface", "HP");
		fields.put("receiverKind", "PLAYER"); fields.put("actorState", Collections.singletonMap("actorKind", "LOCAL_PLAYER"));
		ObservedEvent later = ObservedEvent.builder().type("HITSPLAT").tick(5).sequence(11).data(fields).build();
		DeathRecordingV2 raw = DeathRecordingV2.builder().meta(DeathRecordingV2.Meta.builder().deathTick(5).build())
			.events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(known)).observations(Collections.singletonList(later)).build()).build();
		DeathRecordingV2.Summary result = RecordingProjection.apply(raw, Collections.emptyMap(), "catalog").getSummary();
		assertEquals("UNRESOLVED_IMPACT_IN_DEATH_TICK", result.getKillerBasis());
		assertNull(result.getFatalHit());
		assertEquals(Collections.singletonList(known), result.getFatalHitCandidates());
		for (String channel : Arrays.asList("rawCallbacks", "observationLinks"))
		{
			DeathRecordingV2 gap = raw.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(known))
				.captureGaps(Collections.singletonList(DeathRecordingV2.Events.CaptureGap.builder().tick(5).channel(channel).omittedObservations(1).build())).build()).build();
			assertEquals("UNRESOLVED_IMPACT_IN_DEATH_TICK", RecordingProjection.apply(gap, Collections.emptyMap(), "catalog").getSummary().getKillerBasis());
		}
		ObservedEvent foreign = copy(later, 11, "npc:foreign");
		DeathRecordingV2 other = raw.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(known)).observations(Collections.singletonList(foreign)).build()).build();
		assertEquals("npc:a", RecordingProjection.apply(other, Collections.emptyMap(), "catalog").getSummary().getKillerName());
	}

	@Test
	public void originalRecipientLinkCanRetainALaterResourceVetoButNonHpAndEarlierCallbacksCannot()
	{
		DamageRecord known = hit(10, 5, 7, "npc:a");
		Map<String, Object> fields = new LinkedHashMap<>(); fields.put("amount", 5); fields.put("resourceSurface", "HP_OR_COMBAT_RESOURCE");
		ObservedEvent later = ObservedEvent.builder().type("HITSPLAT").tick(5).sequence(11).data(fields).build();
		ObservedEventLink link = ObservedEventLink.builder().tick(5).resolvedTick(5).observationSequence(11).observationType("HITSPLAT")
			.actorRef("player").evidence("SETTLED_LOCAL_REFERENCE_MATCH").build();
		DeathRecordingV2 raw = DeathRecordingV2.builder().meta(DeathRecordingV2.Meta.builder().deathTick(5).build())
			.events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(known)).observations(Collections.singletonList(later)).observationLinks(Collections.singletonList(link)).build()).build();
		assertNull(RecordingProjection.apply(raw, Collections.emptyMap(), "catalog").getSummary().getFatalHit());
		fields.put("resourceSurface", "PRAYER");
		assertEquals("npc:a", RecordingProjection.apply(raw, Collections.emptyMap(), "catalog").getSummary().getKillerName());
		fields.put("resourceSurface", "HP");
		ObservedEvent earlier = copy(later, 9, "player");
		DeathRecordingV2 before = raw.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(known)).observations(Collections.singletonList(earlier)).build()).build();
		assertEquals("npc:a", RecordingProjection.apply(before, Collections.emptyMap(), "catalog").getSummary().getKillerName());
	}

}
