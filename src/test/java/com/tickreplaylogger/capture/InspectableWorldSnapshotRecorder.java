/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.stream.Collectors;

/** Test diagnostics over the production recorder's frozen export (null-filtered, unbounded window). */
public class InspectableWorldSnapshotRecorder extends WorldSnapshotRecorder
{
    public InspectableWorldSnapshotRecorder(int capacity, Gson gson)
    {
        super(capacity, gson);
    }

    private DeathRecordingV2 frozen()
    {
        return freezeExport(null, null, Integer.MIN_VALUE, Integer.MAX_VALUE - 1);
    }

    private static <T> List<T> present(List<T> values)
    {
        List<T> result = new ArrayList<>();
        if (values != null)
        {
            for (T value : values)
            {
                if (value != null)
                {
                    result.add(value);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized WorldSnapshot snapshotAtTick(int tick)
    {
        return present(frozen().getTicks()).stream().filter(frame -> frame.getTick() == tick).findFirst().orElse(null);
    }

    public synchronized DamageRecord lastDamage()
    {
        List<DamageRecord> hits = present(frozen().getEvents().getDamageEvents());
        return hits.isEmpty() ? null : hits.get(hits.size() - 1);
    }

    public synchronized List<WorldSnapshot> snapshotsForSourceResolution() { return present(frozen().getTicks()); }
    public synchronized List<SceneData> scenesForSourceResolution() { return present(frozen().getScenes()); }
    public synchronized List<ObservedEvent> observationsForSourceResolution() { return present(frozen().getEvents().getObservations()); }
    public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsForSourceResolution() { return present(frozen().getEvents().getCaptureGaps()); }
    public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsAtTickForSourceResolution(int tick)
    {
        return Collections.unmodifiableList(present(frozen().getEvents().getCaptureGaps()).stream().filter(gap -> gap.getTick() == tick)
            .collect(Collectors.toList()));
    }
}
