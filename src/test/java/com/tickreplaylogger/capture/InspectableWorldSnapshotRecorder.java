/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.stream.Collectors;

/** Test diagnostics over the production recorder's immutable evidence-window API. */
public class InspectableWorldSnapshotRecorder extends WorldSnapshotRecorder
{
    public InspectableWorldSnapshotRecorder(int capacity, Gson gson)
    {
        super(capacity, gson);
    }

    private ResolverV1Recording.EvidenceWindow evidence()
    {
        DeathRecordingV2 frozen = freezeExport(null, null, Integer.MIN_VALUE, Integer.MAX_VALUE - 1);
        DeathRecordingV2.Events events = frozen.getEvents();
        return new ResolverV1Recording.EvidenceWindow(frozen.getTicks(), frozen.getScenes(), events.getObservations(),
            events.getDamageEvents(), events.getCaptureGaps(), events.getObservationLinks(), Integer.MIN_VALUE,
            Integer.MAX_VALUE, Long.MAX_VALUE, 0);
    }

    public synchronized WorldSnapshot snapshotAtTick(int tick)
    {
        return evidence().getTicks().stream().filter(frame -> frame.getTick() == tick).findFirst().orElse(null);
    }

    public synchronized DamageRecord lastDamage()
    {
        List<DamageRecord> hits = evidence().getHits();
        return hits.isEmpty() ? null : hits.get(hits.size() - 1);
    }

    public synchronized List<WorldSnapshot> snapshotsForSourceResolution() { return evidence().getTicks(); }
    public synchronized List<SceneData> scenesForSourceResolution() { return evidence().getScenes(); }
    public synchronized List<ObservedEvent> observationsForSourceResolution() { return evidence().getObservations(); }
    public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsForSourceResolution() { return evidence().getCaptureGaps(); }
    public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsAtTickForSourceResolution(int tick)
    {
        return Collections.unmodifiableList(evidence().getCaptureGaps().stream().filter(gap -> gap.getTick() == tick)
            .collect(Collectors.toList()));
    }
}
