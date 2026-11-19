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
import com.tickreplaylogger.model.world.NpcState;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** An observed conflicting identity must not inherit a different NPC's reference mechanics. */
public class NpcNameQualificationTest
{
	@Test
	public void aConflictingOrUnavailableNameBreaksReviewedAnimationAndCadenceContinuity()
	{
		Fixture f = new Fixture();
		f.animation = -1;
		f.frame(1);
		f.animation = 5069;
		f.animationFrame = 0;
		assertEquals("REVIEWED_ANIMATION_BINDING", f.frame(2).getActionEvidence());
		f.animationFrame = 1;
		assertEquals(Integer.valueOf(2), f.frame(3).getAttackCooldownTicksRemaining());
		f.name = "Different observed NPC";
		f.animationFrame = 2;
		NpcState conflict = f.frame(4);
		assertEquals(Boolean.TRUE, conflict.getNormalWorldContextObserved());
		assertEquals("Different observed NPC", conflict.getName());
		assertNotEquals("REVIEWED_ANIMATION_BINDING", conflict.getActionEvidence());
		assertEquals(Integer.valueOf(0), conflict.getAttackCooldownTicksRemaining());
		f.name = "Zulrah";
		f.animationFrame = 3;
		assertNull(f.frame(5).getAttackOnsetEvidence());
		f.animation = -1;
		f.frame(6);
		f.animation = 5069;
		f.animationFrame = 0;
		f.frame(7);
		f.animationFrame = 1;
		assertEquals(Integer.valueOf(2), f.frame(8).getAttackCooldownTicksRemaining());
		f.failName = true;
		f.animationFrame = 2;
		NpcState missing = f.frame(9);
		assertNull(missing.getName());
		assertTrue(missing.getObservationIssues().containsKey("name"));
		assertNotEquals("REVIEWED_ANIMATION_BINDING", missing.getActionEvidence());
		assertEquals(Integer.valueOf(0), missing.getAttackCooldownTicksRemaining());
		f.failName = false;
		f.animationFrame = 3;
		assertNull(f.frame(10).getAttackOnsetEvidence());
	}

	@Test
	public void anObservedPhaseIsClearedOnAConflictingNameAndRequiresANewCueAfterRecovery()
	{
		Fixture f = new Fixture();
		f.id = 11278;
		f.level = 1001;
		f.name = "Nex";
		f.animation = -1;
		f.speech = "Fill my soul with smoke!";
		assertEquals("Smoke", f.frame(1).getObservedPhase());
		f.name = "Different observed NPC";
		f.speech = null;
		assertNull(f.frame(2).getObservedPhase());
		f.name = "Nex";
		assertNull(f.frame(3).getObservedPhase());
		f.speech = "Darken my shadow!";
		assertEquals("Shadow", f.frame(4).getObservedPhase());
		f.failName = true;
		assertNull(f.frame(5).getObservedPhase());
		f.failName = false;
		f.speech = null;
		assertNull(f.frame(6).getObservedPhase());
	}

	@Test
	public void successfulUnnamedAndFormattedNamesRemainDistinctFromFailedReads()
	{
		Fixture f = new Fixture();
		f.name = "<col=00ff00>Zulrah</col>";
		assertEquals("REVIEWED_ANIMATION_BINDING", f.frame(1).getActionEvidence());
		f.id = 10880;
		f.level = 0;
		f.name = null;
		f.animation = 11101;
		assertEquals("REVIEWED_ANIMATION_BINDING", f.frame(2).getActionEvidence());
		f.failName = true;
		assertNotEquals("REVIEWED_ANIMATION_BINDING", f.frame(3).getActionEvidence());
	}

	static final class Fixture
	{
		int id = 2042, level = 725, animation = 5069, animationFrame;
		String name = "Zulrah", speech;
		boolean failName;
		final NPCComposition definition = NpcSnapshotCaptureTest.stub(NPCComposition.class, (method, args) ->
		{
			switch (method)
			{
			case "getId":
				return id;
			case "getName":
				return name;
			case "getSize":
				return 5;
			default:
				return null;
			}
		});
		final Player local = NpcSnapshotCaptureTest.stub(Player.class,
			(method, args) -> method.equals("getWorldLocation") ? new WorldPoint(3200, 3200, 0)
				: method.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
		final NPC npc = NpcSnapshotCaptureTest.stub(NPC.class, (method, args) ->
		{
			switch (method)
			{
			case "getIndex":
				return 7;
			case "getId":
				return id;
			case "getCombatLevel":
				return level;
			case "getName":
				if (failName)
					throw new IllegalStateException("Synthetic unavailable name");
				return name;
			case "getComposition":
			case "getTransformedComposition":
				return definition;
			case "getWorldLocation":
				return new WorldPoint(3200, 3201, 0);
			case "getLocalLocation":
				return new LocalPoint(6464, 6592);
			case "getAnimation":
				return animation;
			case "getAnimationFrame":
				return animationFrame;
			case "getOverheadText":
				return speech;
			case "getInteracting":
				return local;
			default:
				return null;
			}
		});
		final Client client = NpcSnapshotCaptureTest.stub(Client.class,
			(method, args) -> method.equals("getNpcs") ? Collections.singletonList(npc)
				: method.equals("getLocalPlayer") ? local
					: method.equals("getPlayers") ? Collections.singletonList(local)
						: method.equals("getWorldType") ? EnumSet.noneOf(WorldType.class) : null);
		final WorldCaptureService capture = new WorldCaptureService();

		Fixture()
		{
			capture.setDamageMap(DamageMap.load(new Gson()));
		}

		NpcState frame(int tick)
		{
			return capture.captureTick(client, tick, "synthetic-name-qualification", Collections.emptyList(), null,
				false, false, false).getNpcs().get(0);
		}
	}
}
