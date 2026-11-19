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

import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation;
import com.tickreplaylogger.model.world.ProjectileState;
import java.util.*;
import net.runelite.api.*;

/** Share immutable source anchors through the rolling window without replacing current samples. */
public final class ProjectileSourceTracker
{
	private static final class Entry
	{
		SourceObservation first, firstActor;
		Actor actor;
		int lastTick;
		boolean actorChanged, referenceChanged, definitionChanged, scopeChanged, gap, projectileChanged;
	}

	private final IdentityHashMap<Projectile, Entry> entries = new IdentityHashMap<>();

	public void reset()
	{
		entries.clear();
	}

	public void forget(Projectile projectile)
	{
		entries.remove(projectile);
	}

	public void retain(Set<Projectile> active)
	{
		entries.keySet().retainAll(active);
	}

	/** Optional original metadata is sampled once, never filled by a later observation. */
	public boolean needsOriginalActorMetadata(Projectile projectile, Long observationId, Actor actor)
	{
		if (actor == null)
		{
			return false;
		}
		Entry entry = entries.get(projectile);
		return entry == null || !Objects.equals(entry.first.getObservationId(), observationId)
			|| entry.firstActor == null;
	}

	public ProjectileState observe(Projectile projectile, ProjectileState state, Actor actor, Player local, long epoch,
		boolean includeOthers)
	{
		Entry entry = entries.get(projectile);
		if (entry == null || !Objects.equals(entry.first.getObservationId(), state.getObservationId()))
		{
			entry = new Entry();
			entry.first = capture(state, actor, local, epoch);
			entry.lastTick = state.getObservedTick();
			if (actor != null)
			{
				entry.firstActor = entry.first;
				entry.actor = actor;
			}
			entries.put(projectile, entry);
		}
		int tick = state.getObservedTick();
		entry.gap |= tick < entry.lastTick || (long) tick > (long) entry.lastTick + 1;
		entry.lastTick = tick;
		entry.scopeChanged |= entry.first.getContextEpoch() != epoch;
		entry.projectileChanged |= different(entry.first.getProjectileId(), state.getProjectileId());
		if (actor != null)
		{
			if (entry.firstActor == null)
			{
				entry.firstActor = capture(state, actor, local, epoch);
				entry.actor = actor;
			}
			else
			{
				entry.actorChanged |= entry.actor != actor;
				entry.referenceChanged |= different(entry.firstActor.getSourceRef(), state.getSourceRef());
				entry.definitionChanged |= different(entry.firstActor.getSourceNpcId(), state.getSourceNpcId());
				entry.scopeChanged |= different(entry.firstActor.getSourceWorldViewId(), state.getSourceWorldViewId())
					|| different(entry.firstActor.getSourcePlane(), state.getSourcePlane());
			}
		}
		SourceObservation first = redact(entry.first, includeOthers);
		SourceObservation firstActor = entry.firstActor == null || entry.firstActor == entry.first ? null
			: redact(entry.firstActor, includeOthers);
		ProjectileSourceHistory history = ProjectileSourceHistory.builder().version(1).observedTick(tick)
			.contextEpoch(epoch).firstObservedSource(first).firstActorSource(firstActor)
			.actorChanged(entry.actorChanged).referenceChanged(entry.referenceChanged)
			.definitionChanged(entry.definitionChanged).scopeChanged(entry.scopeChanged).observationGap(entry.gap)
			.projectileDefinitionChanged(entry.projectileChanged).build();
		ProjectileState.ProjectileStateBuilder out = state.toBuilder().sourceHistory(history);
		if (history.isAttributionUnestablished())
		{
			out.damageType(null).damageTypeRuleKey(null).damageTypeCatalogId(null)
				.damageTypeEvidence("PROJECTILE_SOURCE_HISTORY_UNESTABLISHED");
		}
		return out.build();
	}

	private static boolean different(Object first, Object current)
	{
		return first != null && current != null && !first.equals(current);
	}

	private static SourceObservation redact(SourceObservation value, boolean includeOthers)
	{
		return value == null || includeOthers || !"OTHER_PLAYER".equals(value.getActorKind()) ? value
			: value.toBuilder().sourceName("Other player").sourceObservedName(null).sourceNameKnown(null)
				.sourceRef(null).build();
	}

	private static SourceObservation capture(ProjectileState state, Actor actor, Player local, long epoch)
	{
		Map<String, String> issues = new LinkedHashMap<>();
		if (state.getObservationIssues() != null)
		{
			for (Map.Entry<String, String> item : state.getObservationIssues().entrySet())
			{
				if (item.getKey().startsWith("source") || item.getKey().equals("projectileId"))
				{
					issues.put(item.getKey(), item.getValue());
				}
			}
		}
		String kind = actor == null ? "NONE"
			: actor instanceof NPC ? "NPC"
				: actor == local ? "LOCAL_PLAYER" : actor instanceof Player ? "OTHER_PLAYER" : "ACTOR";
		return SourceObservation.builder().observationId(state.getObservationId()).projectileId(state.getProjectileId())
			.observedTick(state.getObservedTick()).observedCycle(state.getObservedCycle()).contextEpoch(epoch)
			.sampleEvidence(state.getPositionEvidence()).actorKind(kind).sourceRef(state.getSourceRef())
			.sourceSceneKey(state.getSourceSceneKey()).sourceEvidence(state.getSourceEvidence())
			.sourceName(state.getSourceName()).sourceObservedName(state.getSourceObservedName())
			.sourceActorKnown(state.getSourceActorKnown()).sourceNameKnown(state.getSourceNameKnown())
			.proximitySampleRetained(true).proximityCandidate(state.getProximitySourceCandidate())
			.sourceNpcIndex(state.getSourceNpcIndex()).sourceNpcId(state.getSourceNpcId())
			.sourceBaseNpcId(state.getSourceBaseNpcId()).sourceCombatLevel(state.getSourceCombatLevel())
			.sourceWorldViewId(state.getSourceWorldViewId()).sourcePlane(state.getSourcePlane())
			.sourceDefinitionEvidence(state.getSourceDefinitionEvidence())
			.sourceDefinitionHasTransforms(state.getSourceDefinitionHasTransforms())
			.sourceNormalWorldContextObserved(state.getSourceNormalWorldContextObserved())
			.sourceModelOverridesPresent(state.getSourceModelOverridesPresent())
			.observationIssues(issues.isEmpty() ? null : Collections.unmodifiableMap(issues)).build();
	}
}
