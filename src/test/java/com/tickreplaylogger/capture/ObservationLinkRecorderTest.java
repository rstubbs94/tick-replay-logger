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

import com.tickreplaylogger.model.world.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservationLinkRecorderTest
{
	private ObservedEventLink link(int tick, long sequence)
	{
		return ObservedEventLink.builder().tick(tick).resolvedTick(tick).observationSequence(sequence)
			.observationType("HITSPLAT").actorRef("player").evidence("SETTLED_LOCAL_REFERENCE_MATCH").build();
	}

	@Test
	public void linkRetentionFollowsJournalTimeWithoutRewritingOriginalRecipients()
	{
		WorldSnapshotRecorder r = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		r.advanceTick(1);
		r.addObservation(ObservedEvent.builder().tick(1).sequence(1).type("HITSPLAT").build());
		r.addObservationLink(link(1, 1));
		DeathRecordingV2 first = r.buildExport(null, null, 1, 1);
		assertEquals(1, first.getEvents().getObservationLinks().size());
		assertNull(first.getEvents().getObservations().get(0).getActorRef());
		r.advanceTick(3);
		assertTrue(r.buildExport(null, null, 2, 3).getEvents().getObservationLinks().isEmpty());
		assertTrue(r.buildExport(null, null, 2, 3).getEvents().getObservations().isEmpty());
	}

	@Test
	public void unsupportedAndCrossBoundaryLinksCannotEnterTheRecorder()
	{
		WorldSnapshotRecorder r = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		for (ObservedEventLink invalid : new ObservedEventLink[]
		{ ObservedEventLink.builder().tick(1).resolvedTick(2).observationSequence(1).observationType("HITSPLAT")
			.actorRef("player").evidence("SETTLED_LOCAL_REFERENCE_MATCH").build(),
			ObservedEventLink.builder().tick(1).resolvedTick(1).observationSequence(1).observationType("HITSPLAT")
				.actorRef("npc:1").evidence("GUESSED").build() })
		{
			try
			{
				r.addObservationLink(invalid);
				fail("Invalid link accepted");
			}
			catch (IllegalArgumentException expected)
			{
			}
		}
		assertTrue(r.buildExport(null, null, 1, 1).getEvents().getObservationLinks().isEmpty());
	}

	@Test
	public void boundedRecipientEvidenceHasItsOwnOmissionChannelAndClearsWithTheSession()
	{
		WorldSnapshotRecorder r = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		r.advanceTick(1);
		for (int i = 0; i < WorldSnapshotRecorder.MAX_OBSERVATION_LINKS + 2; i++)
			r.addObservationLink(link(1, i + 1));
		DeathRecordingV2 rich = r.buildExport(null, null, 1, 1);
		assertEquals(WorldSnapshotRecorder.MAX_OBSERVATION_LINKS, rich.getEvents().getObservationLinks().size());
		DeathRecordingV2.Events.CaptureGap gap = rich.getEvents().getCaptureGaps().stream()
			.filter(g -> "observationLinks".equals(g.getChannel())).findFirst().get();
		assertEquals(2L, gap.getOmittedObservations());
		assertEquals("OBSERVATION_LINK_BUFFER_LIMIT", gap.getReason());
		r.clear();
		assertTrue(r.buildExport(null, null, 1, 1).getEvents().getObservationLinks().isEmpty());
		assertTrue(r.buildExport(null, null, 1, 1).getEvents().getCaptureGaps().isEmpty());
	}
}
