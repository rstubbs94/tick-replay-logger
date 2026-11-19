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
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.util.RecordingStorage;
import com.tickreplaylogger.util.TickRecorder;
import java.lang.reflect.Method;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LiveSnapshotSaveTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void activeAndPausedSavesRetainTheRichPayloadForTheirSelectedTicks() throws Exception
	{
		Gson gson = new Gson();
		try (RecordingStorage storage = new RecordingStorage(3, gson,
			net.runelite.client.util.Filepath.Unchecked.getRooted(temporary.newFolder().toPath())))
		{
			storage.ready().get(5, java.util.concurrent.TimeUnit.SECONDS);
			TickRecorder ticks = new TickRecorder(5);
			addTick(ticks, 99);
			TickReplayLoggerPanel[] panel = new TickReplayLoggerPanel[1];
			SwingUtilities.invokeAndWait(() ->
			{
				panel[0] = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig()
				{
				}, com.tickreplaylogger.capture.DamageMap.load(gson));
				panel[0].setTickRecorder(ticks);
				panel[0].setLiveSnapshotPreparer(selected ->
				{
					int frozenTick = selected.get(selected.size() - 1).getTickNumber();
					return () ->
					{
						assertFalse("Rich serialization must not run on Swing's thread",
							SwingUtilities.isEventDispatchThread());
						return "{\"snapshotTick\":" + frozenTick + "}";
					};
				});
				invokeUnchecked(panel[0], "saveLiveBuffer");
			});
			storage.flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals("{\"snapshotTick\":1}", storage.getSavedRecordings().get(0).getRichExportJson());
			SwingUtilities.invokeAndWait(() ->
			{
				invokeUnchecked(panel[0], "toggleLiveUpdates");
				for (int i = 0; i < 10; i++)
					addTick(ticks, 90);
				invokeUnchecked(panel[0], "saveLiveBuffer");
			});
			storage.flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
			TickRecording paused = storage.getSavedRecordings().get(0);
			assertEquals(1, paused.getTicks().get(0).getTickNumber());
			assertEquals("{\"snapshotTick\":1}", paused.getRichExportJson());
			SwingUtilities.invokeAndWait(() ->
			{
				invokeUnchecked(panel[0], "toggleLiveUpdates");
				invokeUnchecked(panel[0], "saveLiveBuffer");
			});
			storage.flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals("{\"snapshotTick\":11}", storage.getSavedRecordings().get(0).getRichExportJson());
			panel[0].dispose();
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	private static void invokeUnchecked(TickReplayLoggerPanel panel, String name)
	{
		try
		{
			invoke(panel, name);
		}
		catch (Exception error)
		{
			throw new AssertionError(error);
		}
	}

	private static void addTick(TickRecorder recorder, int hp)
	{
		recorder.startNewTick();
		recorder.updateState(hp, 99, 99, 99, Collections.emptyList());
		recorder.finalizeTick();
	}

	private static void invoke(TickReplayLoggerPanel panel, String methodName) throws Exception
	{
		Method method = TickReplayLoggerPanel.class.getDeclaredMethod(methodName);
		method.setAccessible(true);
		method.invoke(panel);
	}
}
