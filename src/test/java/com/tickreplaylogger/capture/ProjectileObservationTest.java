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
import com.tickreplaylogger.model.world.ProjectileState;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled API failures, not certification of a boss mechanic or a hidden hit outcome. */
public class ProjectileObservationTest
{
	@Test
	public void unavailableProjectileFieldsPreserveIndependentFactsAndStayAbsentRatherThanZero()
	{
		String[][] cases =
		{
			{ "getId", "projectileId" },
			{ "getSourceActor", "sourceActor" },
			{ "getTargetActor", "targetActor" },
			{ "getSourcePoint", "sourcePoint" },
			{ "getTargetPoint", "targetPoint" },
			{ "getTarget", "targetLocalPoint" },
			{ "getSourceLevel", "sourcePlane" },
			{ "getTargetLevel", "targetPlane" },
			{ "getRemainingCycles", "remainingCycles" },
			{ "getStartCycle", "startCycle" },
			{ "getEndCycle", "endCycle" },
			{ "getSlope", "slope" },
			{ "getStartHeight", "startHeight" },
			{ "getEndHeight", "endHeight" },
			{ "getStartPos", "startPos" },
			{ "getOrientation", "orientation" },
			{ "getAnimation", "animation" },
			{ "getAnimationFrame", "animationFrame" },
			{ "getX", "localX" },
			{ "getY", "localY" },
			{ "getZ", "z" } };
		for (String[] row : cases)
		{
			Fixture f = new Fixture();
			f.failedProjectile.add(row[0]);
			ProjectileState s = f.sample(null).getState();
			assertTrue(row[0], s.getObservationIssues().containsKey(row[1]));
			JsonObject raw = new Gson().toJsonTree(s).getAsJsonObject();
			assertFalse(row[0] + " fabricated an observed field", raw.has(row[1]));
			if (!row[0].equals("getX"))
				assertEquals(Double.valueOf(6464), s.getLocalX());
			if (!row[0].equals("getY"))
				assertEquals(Double.valueOf(6592), s.getLocalY());
			if (!row[0].equals("getId"))
				assertEquals(Integer.valueOf(2327), s.getProjectileId());
			if (!row[0].equals("getSourcePoint"))
				assertEquals(Integer.valueOf(3201), s.getStartWorldX());
			if (!row[0].equals("getSourceActor"))
				assertEquals(Integer.valueOf(12077), s.getSourceNpcId());
			if (row[0].equals("getSourceLevel"))
			{
				assertNull(s.getPlane());
				assertEquals("UNRESOLVED", s.getCoordinateEvidence());
			}
			if (row[0].equals("getTargetActor"))
			{
				assertNull(s.getTargetsPlayer());
				assertEquals(Boolean.FALSE, s.getTargetActorKnown());
			}
			if (row[0].equals("getRemainingCycles"))
				assertNull(s.getEstimatedImpactTick());
		}
	}

	@Test
	public void optionalNpcMetadataCannotEraseGeometryAndOnlyRelevantReferenceInputsAffectCategory()
	{
		for (String getter : new String[]
		{ "getName", "getCombatLevel", "getIndex", "getWorldView", "getId", "getTransformedComposition",
			"getModelOverrides" })
		{
			Fixture f = new Fixture();
			f.failedSource.add(getter);
			ProjectileState s = f.sample(null).getState();
			assertEquals(Double.valueOf(6464), s.getLocalX());
			assertEquals(Integer.valueOf(3200), s.getEndWorldY());
			assertEquals(Integer.valueOf(2327), s.getProjectileId());
			assertEquals(Boolean.TRUE, s.getTargetsPlayer());
			assertNotNull(s.getObservationIssues());
			// This exact-ID Muspah style binding does not require a level; cadence rules may do so.
			if (getter.equals("getIndex") || getter.equals("getWorldView") || getter.equals("getId")
				|| getter.equals("getCombatLevel"))
				assertEquals("magic", s.getDamageType());
			else
				assertNull(getter, s.getDamageType());
		}
	}

	@Test
	public void unknownSourceMetadataIsNotReplacedWithAProximityIdentity()
	{
		Fixture f = new Fixture();
		f.failedSource.add("getName");
		ProjectileState failed = f.sample(null).getState();
		assertEquals("Unknown", failed.getSourceName());
		assertEquals(Boolean.FALSE, failed.getSourceNameKnown());
		assertEquals("ACTOR", failed.getSourceEvidence());
		f = new Fixture();
		f.sourceAbsent = true;
		ProjectileState absent = f.sample(null).getState();
		assertEquals("Nearby candidate", absent.getSourceName());
		assertEquals("PROXIMITY", absent.getSourceEvidence());
		assertNull(absent.getSourceNpcId());
		assertNull(absent.getSourceRef());
		f = new Fixture();
		f.failedProjectile.add("getSourceActor");
		ProjectileState unknown = f.sample(null).getState();
		assertEquals("UNKNOWN", unknown.getSourceEvidence());
		assertEquals(Boolean.FALSE, unknown.getSourceActorKnown());
		assertNull(unknown.getSourceNpcId());
	}

	@Test
	public void callbackTargetsAreDistinctFromCurrentFlightPositionAndAreNotReusedByLaterSamples()
	{
		Fixture f = new Fixture();
		LocalPoint moved = new LocalPoint(7000, 7100, WorldView.TOPLEVEL);
		ProjectileState callback = f.sample(moved).getState();
		assertEquals(Integer.valueOf(7000), callback.getTargetLocalX());
		assertEquals("MOVED_CALLBACK_TARGET", callback.getTargetPositionEvidence());
		assertEquals(Double.valueOf(6464), callback.getLocalX());
		assertEquals(Integer.valueOf(3200), callback.getEndWorldY());
		f.projectileValues.remove("getTarget");
		ProjectileState later = f.sample(null).getState();
		assertNull(later.getTargetLocalX());
		assertNull(later.getTargetPositionEvidence());
		assertEquals(Double.valueOf(6464), later.getLocalX());
	}

	@Test
	public void nonFiniteCoordinatesRemainAnExplicitGapWithoutBreakingJsonExport()
	{
		for (double value : new double[]
		{ Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
		{
			Fixture f = new Fixture();
			f.projectileValues.put("getX", value);
			ProjectileState s = f.sample(null).getState();
			assertNull(s.getLocalX());
			assertTrue(s.getObservationIssues().get("localX").startsWith("NON_FINITE:"));
			assertEquals(Double.valueOf(6592), s.getLocalY());
			assertEquals(Integer.valueOf(2327), s.getProjectileId());
			assertFalse(new Gson().toJsonTree(s).getAsJsonObject().has("localX"));
		}
	}

	@Test
	public void anObservedZeroLifetimeDoesNotInventAnArrivalInAFutureTick()
	{
		Fixture f = new Fixture();
		f.projectileValues.put("getRemainingCycles", 0);
		assertEquals(Integer.valueOf(100), f.sample(null).getState().getEstimatedImpactTick());
	}

	@Test
	public void anObservedNullTargetIsDistinctFromAnUnavailableTargetRead()
	{
		Fixture f = new Fixture();
		Projectile untargeted = NpcSnapshotCaptureTest.stub(Projectile.class,
			(m, a) -> m.equals("getRemainingCycles") ? 30 : null);
		ProjectileState observed = new ProjectileObservation(untargeted, f.client, f.player, true, null, a -> null,
			null, 1, 1, 1, 500, "TICK_SAMPLE", null).getState();
		assertEquals(Boolean.TRUE, observed.getTargetActorKnown());
		assertEquals(Boolean.FALSE, observed.getTargetsPlayer());
		f.failedProjectile.add("getTargetActor");
		ProjectileState unavailable = f.sample(null).getState();
		assertEquals(Boolean.FALSE, unavailable.getTargetActorKnown());
		assertNull(unavailable.getTargetsPlayer());
	}

	@Test
	public void disablingOtherPlayerCaptureRetainsAnonymousProjectileFactsWithoutReadingPrivateNamesOrReferences()
	{
		Fixture f = new Fixture();
		Player other = NpcSnapshotCaptureTest.stub(Player.class, (m, a) ->
		{
			if (m.equals("getName"))
				throw new AssertionError("An opted-out player's name must not be read");
			return m.equals("getWorldView") ? f.view : null;
		});
		Projectile shot = NpcSnapshotCaptureTest.stub(Projectile.class, (m, a) ->
		{
			switch (m)
			{
			case "getSourceActor":
			case "getTargetActor":
				return other;
			case "getId":
				return 2327;
			case "getX":
				return 6464D;
			case "getY":
				return 6592D;
			case "getRemainingCycles":
				return 30;
			default:
				return null;
			}
		});
		ProjectileState observed = new ProjectileObservation(shot, f.client, f.player, false, null, a ->
		{
			throw new AssertionError("An opted-out actor reference must not be read");
		}, null, 1, 1, 1, 500, "TICK_SAMPLE", null).getState();
		assertEquals("Other player", observed.getSourceName());
		assertNull(observed.getSourceObservedName());
		assertNull(observed.getSourceNameKnown());
		assertNull(observed.getSourceRef());
		assertNull(observed.getTargetRef());
		assertEquals(Boolean.FALSE, observed.getTargetsPlayer());
		assertEquals(Integer.valueOf(2327), observed.getProjectileId());
		assertEquals(Double.valueOf(6464), observed.getLocalX());
	}

	private static final class Fixture
	{
		final Set<String> failedProjectile = new HashSet<>(), failedSource = new HashSet<>();
		final Map<String, Object> projectileValues = new HashMap<>();
		boolean sourceAbsent;
		final WorldView view = NpcSnapshotCaptureTest.stub(WorldView.class,
			(m, a) -> m.equals("getId") ? WorldView.TOPLEVEL : null);
		final NPCComposition definition = NpcSnapshotCaptureTest.stub(NPCComposition.class, (m, a) ->
		{
			switch (m)
			{
			case "getId":
				return 12077;
			case "getName":
				return "Phantom Muspah";
			case "getSize":
				return 5;
			default:
				return null;
			}
		});
		final Player player = NpcSnapshotCaptureTest.stub(Player.class, (m, a) -> null);
		final NPC source = NpcSnapshotCaptureTest.stub(NPC.class, (m, a) ->
		{
			if (failedSource.contains(m))
				throw new IllegalStateException("Unavailable " + m);
			switch (m)
			{
			case "getId":
				return 12077;
			case "getComposition":
			case "getTransformedComposition":
				return definition;
			case "getName":
				return "Phantom Muspah";
			case "getCombatLevel":
				return 741;
			case "getIndex":
				return 7;
			case "getWorldView":
				return view;
			default:
				return null;
			}
		});
		final Client client = NpcSnapshotCaptureTest.stub(Client.class,
			(m, a) -> m.equals("getWorldType") ? EnumSet.noneOf(WorldType.class)
				: m.equals("getTopLevelWorldView") ? view : null);
		final Projectile projectile = NpcSnapshotCaptureTest.stub(Projectile.class, (m, a) ->
		{
			if (failedProjectile.contains(m))
				throw new IllegalStateException("Unavailable " + m);
			if (m.equals("getSourceActor"))
				return sourceAbsent ? null : source;
			if (m.equals("getTargetActor"))
				return player;
			return projectileValues.get(m);
		});

		Fixture()
		{
			projectileValues.put("getId", 2327);
			projectileValues.put("getRemainingCycles", 60);
			projectileValues.put("getSourcePoint", new WorldPoint(3201, 3201, 0));
			projectileValues.put("getTargetPoint", new WorldPoint(3200, 3200, 0));
			projectileValues.put("getTarget", new LocalPoint(6400, 6400, WorldView.TOPLEVEL));
			projectileValues.put("getX", 6464D);
			projectileValues.put("getY", 6592D);
			projectileValues.put("getZ", 128D);
		}

		ProjectileObservation sample(LocalPoint callback)
		{
			return new ProjectileObservation(projectile, client, player, true, DamageMap.load(new Gson()).catalog(),
				a -> a == source ? "npc:7" : "player", (p, a) -> "Nearby candidate", 1, 99, 100, 500, "TICK_SAMPLE",
				callback);
		}
	}
}
