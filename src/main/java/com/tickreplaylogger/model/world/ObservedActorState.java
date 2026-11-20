/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.model.world;

import java.util.*;
import lombok.*;
import lombok.experimental.*;

/** Shared observed actor fields. Gson keeps inherited values in the existing flat recording shape. */
@Getter
@EqualsAndHashCode
@ToString
@SuperBuilder(toBuilder = true)
public abstract class ObservedActorState
{
	private final Integer worldViewId;
	private final ActorInteractionState interaction;
	private final Integer combatLevel;
	private final Map<String, String> observationIssues;
	private final Boolean localPositionKnown;
	private final Boolean worldPositionKnown;
	private final Boolean planeKnown;
	private final int worldX;
	private final int worldY;
	private final int plane;
	private final int localX;
	private final int localY;
	private final Integer orientation;
	private final Integer currentOrientation;
	private final Integer animationId;
	private final Integer animationFrame;
	private final Integer poseAnimationId;
	private final Integer poseAnimationFrame;
	private final int[] graphicIds;
	private final Boolean graphicsKnown;
	private final ActorGraphicsState graphicsState;
	private final String overheadText;
	private final Integer overheadCycle;
	private final Integer interactingNpcIndex;
}
