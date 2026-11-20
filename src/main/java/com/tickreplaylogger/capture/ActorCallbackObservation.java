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

import com.tickreplaylogger.model.world.NpcState;
import static com.tickreplaylogger.capture.ObservationReads.read;
import lombok.*;
import lombok.experimental.Accessors;
import java.util.*;
import java.util.function.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/** Callback-time actor fields. Optional reads cannot erase an independently observed field. */
public final class ActorCallbackObservation
{
	private ActorCallbackObservation()
	{
	}

	/** Internal callback sample. Native pointers are never inserted in exported data. */
	@Getter
	@Accessors(fluent = true)
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Sample
	{
		private final Map<String, Object> data;
		private final WorldPoint world;
		private final LocalPoint local;
		private final WorldView view;
	}

	/** The supplied old composition is not transformed using today's actor/varbit state. */
	public static Map<String, Object> oldComposition(NPCComposition composition)
	{
		if (composition == null)
		{
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		result.put("evidence", "NPC_CHANGED_OLD_COMPOSITION_EVENT_PAYLOAD");
		result.put("id", read(issues, "id", composition::getId));
		result.put("name", read(issues, "name", composition::getName));
		result.put("size", read(issues, "size", composition::getSize));
		result.put("combatLevel", read(issues, "combatLevel", composition::getCombatLevel));
		int[] configs = read(issues, "transformIds", () ->
		{
			int[] values = composition.getConfigs();
			return values == null ? null : values.clone();
		});
		result.put("transformIds", configs);
		result.put("hasTransforms", issues.containsKey("transformIds") ? null : configs != null);
		if (!issues.isEmpty())
		{
			result.put("observationIssues", Collections.unmodifiableMap(new LinkedHashMap<>(issues)));
		}
		return Collections.unmodifiableMap(result);
	}

	public static Map<String, Object> capture(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		return captureSample(actor, readyClient, local, includeOthers, refs).data();
	}

	public static Sample captureSample(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		return captureSample(actor, readyClient, local, includeOthers, refs, null);
	}

	public static Sample captureSample(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs, BiFunction<Actor, Map<String, Object>, String> observedRefs)
	{
		if (actor == null || actor instanceof Player && actor != local && !includeOthers)
		{
			return new Sample(Collections.emptyMap(), null, null, null);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		WorldPoint world = read(issues, "worldPosition", actor::getWorldLocation);
		ActorObservation observation;
		String worldIssue = issues.get("worldPosition");
		result.put("actorKind",
			actor instanceof NPC ? "NPC"
				: actor == local ? "LOCAL_PLAYER"
					: actor instanceof Player ? local == null ? "PLAYER" : "OTHER_PLAYER" : "ACTOR");
		if (actor instanceof NPC)
		{
			NpcSnapshotObservation sample = new NpcSnapshotObservation((NPC) actor, world, worldIssue);
			issues = sample.issues;
			NpcState state = sample.state.build();
			observation = sample;
			sample.area();
			sample.callbackArea(result);
			result.put("sceneId", sample.index);
			result.put("npcId", sample.id);
			result.put("baseNpcId", sample.baseId);
			result.put("size", sample.size);
			result.put("definitionName", state.getDefinitionName());
			result.put("definitionEvidence", state.getDefinitionEvidence());
			result.put("definitionHasTransforms", sample.hasTransforms);
			result.put("definitionTransformIds", state.getDefinitionTransformIds());
			result.put("modelOverridesPresent", state.getModelOverridesPresent());
			result.put("overrideModelIds", state.getOverrideModelIds());
			result.put("normalWorldContextObserved", readyClient == null ? null
				: !issues.containsKey("modelOverrides")
					&& NpcIdentityContext.normalWorldObservedWithoutOverrides(readyClient, sample.overrides == null));
			result.put("overheadArchiveIds", state.getOverheadArchiveIds());
			result.put("overheadSpriteIds", state.getOverheadSpriteIds());
			result.put("overheadIconsKnown", state.getOverheadIconsKnown());

		}
		else if (actor instanceof Player)
		{
			Player player = (Player) actor;
			PlayerSnapshotObservation sample = new PlayerSnapshotObservation(player, world, worldIssue);
			issues = sample.issues;
			observation = sample;
			sample.name = sample.read("name", player::getName);
			result.put("playerId", sample.read("playerId", player::getId));
			sample.health();
			result.put("overheadIcon", sample.icon == null ? null : sample.icon.name());
			result.put("overheadIconKnown", !issues.containsKey("overheadIcon"));

			result.put("appearance", PlayerAppearance.capture(player));

		}
		else
		{
			observation = new ActorObservation(actor, world, worldIssue);
			issues = observation.issues;
			observation.genericCallback();
		}
		observation.callback(result);
		if (observedRefs != null && observation.target == actor)
		{
			observedRefs.apply(actor, result);
		}
		result.put("interaction",
			ActorInteractions.captureObserved(observation.target, observation.interactionFlag,
				!issues.containsKey("interactionTarget"), local, includeOthers, refs,
				observation.target == actor ? result : null, issues));

		if (!issues.isEmpty())
		{
			result.put("observationIssues", Collections.unmodifiableMap(new LinkedHashMap<>(issues)));
			result.put("captureStatus", "PARTIAL_ACTOR_METADATA");
		}
		return new Sample(Collections.unmodifiableMap(result), world, observation.local, observation.view);
	}

}
