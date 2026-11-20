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
package com.tickreplaylogger.ui;

import com.google.gson.Gson;
import com.tickreplaylogger.TickReplayLoggerConfig;
import com.tickreplaylogger.capture.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.util.RecordingStorage;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LiveSnapshotSaveTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void pausedDisplayAndSaveUseOneWindowEvenWhenCapacityTrimsBetweenRequestAndDelivery() throws Exception
	{
		Gson gson = new Gson();
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			TraceRecorder recorder = new TraceRecorder(gson);
			for (int i = 0; i < 3; i++) addTick(recorder);
			AtomicReference<Consumer<TickRecording.CapturedWindow>> request = new AtomicReference<>();
			TickReplayLoggerPanel panel = panel(storage, recorder, gson, request::set);
			SwingUtilities.invokeAndWait(() -> invoke(panel, "toggleLiveUpdates"));
			assertNotNull(request.get());
			assertEquals(0, recorder.completions.get());
			addTick(recorder); // Tick 1 is trimmed before the single client-thread freeze executes.
			TickRecording.CapturedWindow capture = recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build());
			assertEquals(Arrays.asList(2, 3, 4), ticks(capture.getTicks()));
			addTick(recorder); // Original callback 50 is later than this selected cutoff.
			request.get().accept(capture);
			SwingUtilities.invokeAndWait(() -> {});
			assertSame(capture, field(panel, "pausedCapture"));
			assertEquals(Arrays.asList(2, 3, 4), ticks(((TickRecording.CapturedWindow) field(panel, "pausedCapture")).getTicks()));
			for (int i = 0; i < 6; i++) addTick(recorder); // Entire paused membership is now outside the live store.
			assertEquals(0, recorder.completions.get());
			SwingUtilities.invokeAndWait(() -> invoke(panel, "saveLiveBuffer"));
			storage.flush().get(5, TimeUnit.SECONDS);
			TickRecording saved = storage.getSavedRecordings().get(0);
			assertEquals(Arrays.asList(2, 3, 4), ticks(saved.getTicks()));
			assertEquals(Arrays.asList(20L, 30L, 40L), saved.getPreparedExport().getEvents().getDamageEvents().stream()
				.map(DamageRecord::getObservationSequence).collect(java.util.stream.Collectors.toList()));
			assertEquals(Arrays.asList(20L, 30L, 40L), saved.getPreparedExport().getEvents().getObservations().stream()
				.map(ObservedEvent::getSequence).collect(java.util.stream.Collectors.toList()));
			assertEquals(Integer.valueOf(4), saved.getPreparedExport().getMeta().getSnapshotTick());
			assertEquals(9, saved.getTotalDamageReceived());
			assertEquals(1, recorder.completions.get());
			SwingUtilities.invokeAndWait(panel::dispose);
		}
	}

	@Test public void resumedUiIgnoresAnOlderPauseResponseAndActiveSaveRequestsItsOwnWindow() throws Exception
	{
		Gson gson = new Gson();
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			TraceRecorder recorder = new TraceRecorder(gson); addTick(recorder);
			List<Consumer<TickRecording.CapturedWindow>> requests = new ArrayList<>();
			TickReplayLoggerPanel panel = panel(storage, recorder, gson, requests::add);
			SwingUtilities.invokeAndWait(() -> invoke(panel, "toggleLiveUpdates"));
			TickRecording.CapturedWindow earlier = recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build());
			SwingUtilities.invokeAndWait(() -> invoke(panel, "toggleLiveUpdates"));
			requests.get(0).accept(earlier);
			SwingUtilities.invokeAndWait(() -> assertNull(field(panel, "pausedCapture")));
			addTick(recorder);
			SwingUtilities.invokeAndWait(() -> invoke(panel, "saveLiveBuffer"));
			assertEquals(2, requests.size());
			requests.get(1).accept(recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build()));
			SwingUtilities.invokeAndWait(() -> {});
			storage.flush().get(5, TimeUnit.SECONDS);
			assertEquals(Integer.valueOf(2), storage.getSavedRecordings().get(0).getPreparedExport().getMeta().getSnapshotTick());
			assertEquals(1, recorder.completions.get());
			SwingUtilities.invokeAndWait(panel::dispose);
		}
	}

	private RecordingStorage storage(Gson gson) throws Exception
	{
		return new RecordingStorage(3, gson, net.runelite.client.util.Filepath.Unchecked.getRooted(temporary.newFolder().toPath()));
	}

	private TickReplayLoggerPanel panel(RecordingStorage storage, TraceRecorder recorder, Gson gson,
		Consumer<Consumer<TickRecording.CapturedWindow>> requester) throws Exception
	{
		TickReplayLoggerPanel[] result = new TickReplayLoggerPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			result[0] = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig() {}, DamageMap.load(gson));
			result[0].setTickRecorder(recorder);
			result[0].setLiveCaptureRequester(requester);
		});
		return result[0];
	}

	private static final class TraceRecorder extends WorldSnapshotRecorder
	{
		final AtomicInteger completions = new AtomicInteger();
		TraceRecorder(Gson gson) { super(3, gson); }
		@Override public DeathRecordingV2 completeExport(DeathRecordingV2 frozen)
		{
			assertFalse("Attribution must remain off Swing's thread", SwingUtilities.isEventDispatchThread());
			completions.incrementAndGet();
			return super.completeExport(frozen);
		}
	}

	private static void addTick(WorldSnapshotRecorder recorder)
	{
		recorder.startNewTick();
		int tick = recorder.getCurrentTickNumber();
		recorder.updateState(99 - tick, 99, 99, 99, Collections.emptyList());
		long seq = tick * 10L;
		recorder.addObservation(ObservedEvent.builder().tick(tick).gameCycle(tick * 30).sequence(seq).type("HITSPLAT").actorRef("player")
			.data(new LinkedHashMap<String, Object>() {{ put("amount", tick); put("hitsplatType", 16); put("resourceSurface", "HP"); }}).build());
		recorder.addDamage(DamageRecord.builder().impactTick(tick).observedCycle(tick * 30).observationSequence(seq).amount(tick)
			.direction("RECEIVED").defenderRef("player").hitsplatType(16).hitsplatTypeKnown(true).build());
		recorder.finalizeTick();
	}

	private static List<Integer> ticks(List<TickData> ticks)
	{
		return ticks.stream().map(TickData::getTickNumber).collect(java.util.stream.Collectors.toList());
	}

	private static Object field(Object target, String name)
	{
		try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
		catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}

	private static void invoke(Object target, String name)
	{
		try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
		catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
}
