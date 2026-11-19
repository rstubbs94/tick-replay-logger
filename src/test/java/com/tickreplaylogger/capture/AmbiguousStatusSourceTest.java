/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedStatusState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AmbiguousStatusSourceTest
{
	private static WorldSnapshot frame(int tick, Boolean active, boolean known)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).worldPositionKnown(true).planeKnown(true).worldX(100).worldY(200).plane(0).build())
			.localStatuses(Collections.singletonList(ObservedStatusState.builder().key("disease_client_state").ruleKey("disease_client_state")
				.state(known ? Boolean.TRUE.equals(active) ? "disease" : "inactive" : "unknown").stateLabel("Disease")
				.active(active).valueObserved(known).build())).build();
	}

	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(2).direction("RESOURCE_IMPACT").defenderRef("player").impactSceneKey("room")
			.impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(100).impactY(200).amount(3)
			.hitsplatType(4).hitsplatTypeKnown(true).attackerName("Unknown").build();
	}

	@Test
	public void dataDeclaredStatusFamilyRequiresAnIndependentObservedActiveEnvelope()
	{
		DamageMap map = DamageMap.load(new Gson());
		assertEquals(Collections.singletonList("disease"), map.catalog().possibleStatusFamilies(4));
		SourceResolution active = new RecordedSourceResolver(Arrays.asList(frame(1, true, true), frame(2, false, true)),
			Collections.emptyList(), map).resolve(hit());
		assertTrue(active.getCandidates().stream().anyMatch(c -> "Disease".equals(c.getName())));
		SourceResolution inactive = new RecordedSourceResolver(Arrays.asList(frame(1, false, true), frame(2, false, true)),
			Collections.emptyList(), map).resolve(hit());
		assertTrue(inactive.getCandidates().stream().noneMatch(c -> "Disease".equals(c.getName())));
		SourceResolution failed = new RecordedSourceResolver(Arrays.asList(frame(1, null, false), frame(2, null, false)),
			Collections.emptyList(), map).resolve(hit());
		assertTrue(failed.getCandidates().stream().noneMatch(c -> "Disease".equals(c.getName())));
		assertTrue(failed.isUnidentifiedPossible());
	}
}
