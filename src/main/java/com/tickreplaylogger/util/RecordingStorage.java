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
package com.tickreplaylogger.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tickreplaylogger.model.TickRecording;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/** One queue owns loading, mutations, serialization and publication. Callers never do disk I/O. */
@Slf4j
public class RecordingStorage implements AutoCloseable
{
	private static final Type RECORDINGS = new TypeToken<ArrayList<TickRecording>>()
	{
	}.getType();
	private static final Map<String, StorageQueue> QUEUES = new HashMap<>();

	private static final class StorageQueue
	{
		private final String key;
		private int owners;
		private long generation;
		private final ExecutorService executor = Executors.newSingleThreadExecutor(task ->
		{
			Thread thread = new Thread(task, "tick-replay-storage");
			thread.setDaemon(true);
			return thread;
		});

		private StorageQueue(String key)
		{
			this.key = key;
		}
	}

	private static StorageQueue acquire(Filepath folder)
	{
		synchronized (QUEUES)
		{
			StorageQueue shared = QUEUES.computeIfAbsent(folder.toString(), StorageQueue::new);
			shared.owners++;
			shared.generation++;
			return shared;
		}
	}

	private static void release(StorageQueue shared)
	{
		synchronized (QUEUES)
		{
			shared.owners--;
			if (shared.owners != 0)
			{
				return;
			}
			long generation = shared.generation;
			shared.executor.execute(() ->
			{
				synchronized (QUEUES)
				{
					if (shared.owners == 0 && shared.generation == generation)
					{
						QUEUES.remove(shared.key, shared);
						shared.executor.shutdown();
					}
				}
			});
		}
	}

	private final Gson gson;
	private final Charset legacyCharset;
	private final Filepath folder;
	private final Filepath deathsFile;
	private final Filepath savedFile;
	private final ExecutorService queue;
	private final StorageQueue owner;
	private final Object snapshots = new Object();
	private final Object admission = new Object();
	private final CompletableFuture<Void> ready;
	private List<TickRecording> deaths = new ArrayList<>();
	private List<TickRecording> savedRecordings = new ArrayList<>();
	private boolean deathsWritable;
	private boolean savedWritable;
	private boolean closed;
	private int maxDeaths;

	public RecordingStorage(int maxDeaths, Gson gson, Filepath folder)
	{
		this(maxDeaths, gson, folder, Charset.defaultCharset());
	}

	/** The previous FileWriter used the environment's default charset. Tests pin that environment. */
	RecordingStorage(int maxDeaths, Gson gson, Filepath folder, Charset legacyCharset)
	{
		this.maxDeaths = Math.max(0, maxDeaths);
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.legacyCharset = Objects.requireNonNull(legacyCharset);
		this.folder = Objects.requireNonNull(folder);
		this.deathsFile = folder.join("deaths.json");
		this.savedFile = folder.join("saved.json");
		this.owner = acquire(folder);
		this.queue = owner.executor;
		ready = submit(() ->
		{
			folder.createDirectories();
			Exception failure = null;
			try
			{
				List<TickRecording> loaded = load(deathsFile);
				synchronized (snapshots)
				{
					deaths = loaded;
				}
				deathsWritable = true;
			}
			catch (Exception e)
			{
				failure = e;
				log.error("Death recordings could not be loaded; their file is protected", e);
			}
			try
			{
				List<TickRecording> loaded = load(savedFile);
				synchronized (snapshots)
				{
					savedRecordings = loaded;
				}
				savedWritable = true;
			}
			catch (Exception e)
			{
				if (failure == null)
				{
					failure = e;
				}
				else
				{
					failure.addSuppressed(e);
				}
				log.error("Saved recordings could not be loaded; their file is protected", e);
			}
			if (failure != null)
			{
				throw failure;
			}
		});
	}

	public CompletableFuture<Void> ready()
	{
		return ready;
	}

	/** Barrier for admitted work. Never wait for this on the client thread or EDT. */
	public CompletableFuture<Void> flush()
	{
		return submit(() ->
		{
		});
	}

	private List<TickRecording> load(Filepath file) throws Exception
	{
		Filepath backup = backup(file);
		if (!file.exists())
		{
			return backup.exists() ? recover(file, backup) : new ArrayList<>();
		}
		try
		{
			return read(file);
		}
		catch (Exception invalid)
		{
			if (!backup.exists())
			{
				throw invalid;
			}
			List<TickRecording> recovered = read(backup); // Validate before moving any original bytes.
			Filepath corrupt = folder.createTempFile(file.getFileName() + ".corrupt-", ".json");
			file.moveTo(corrupt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			publish(file, recovered);
			log.warn("Recovered {} from backup; original retained as {}", file.getFileName(), corrupt.getFileName());
			return recovered;
		}
	}

	private List<TickRecording> recover(Filepath file, Filepath backup) throws Exception
	{
		List<TickRecording> recovered = read(backup);
		publish(file, recovered);
		return recovered;
	}

	private List<TickRecording> read(Filepath file) throws Exception
	{
		try
		{
			return read(file, StandardCharsets.UTF_8);
		}
		catch (Exception invalid)
		{
			boolean malformedEncoding = false;
			for (Throwable cause = invalid; cause != null; cause = cause.getCause())
			{
				if (cause instanceof CharacterCodingException)
				{
					malformedEncoding = true;
				}
			}
			if (!malformedEncoding || legacyCharset.equals(StandardCharsets.UTF_8))
			{
				throw invalid;
			}
			List<TickRecording> recovered = read(file, legacyCharset);
			log.warn("Read legacy {} using {}; future saves use UTF-8", file.getFileName(), legacyCharset.name());
			return recovered;
		}
	}

	private List<TickRecording> read(Filepath file, Charset charset) throws Exception
	{
		try (Reader reader = new BufferedReader(new InputStreamReader(file.openInputStream(), charset.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))))
		{
			List<TickRecording> loaded = gson.fromJson(reader, RECORDINGS);
			if (loaded == null || loaded.stream().anyMatch(Objects::isNull))
			{
				throw new IOException("Invalid recordings collection: " + file.getFileName());
			}
			return loaded;
		}
	}

	private Filepath backup(Filepath file)
	{
		return folder.join(file.getFileName() + ".bak");
	}

	/** An incomplete serialization never opens/truncates the primary or its last-good backup. */
	protected void publish(Filepath file, List<TickRecording> records) throws Exception
	{
		Filepath temporary = folder.createTempFile(file.getFileName() + "-", ".tmp");
		try
		{
			try (Writer writer = temporary.openBufferedWriter())
			{
				gson.toJson(records, RECORDINGS, writer);
			}
			force(temporary);
			if (file.exists())
			{
				Filepath backupTemporary = folder.createTempFile(file.getFileName() + "-backup-", ".tmp");
				try
				{
					file.copyTo(backupTemporary, StandardCopyOption.REPLACE_EXISTING);
					force(backupTemporary);
					backupTemporary.moveTo(backup(file), StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
				}
				finally
				{
					backupTemporary.deleteIfExists();
				}
			}
			temporary.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			temporary.deleteIfExists();
		}
	}

	private static void force(Filepath file) throws IOException
	{
		try (FileChannel channel = file.openFileChannel(StandardOpenOption.WRITE))
		{
			channel.force(true);
		}
	}

	private void writable(boolean saved) throws IOException
	{
		if (!(saved ? savedWritable : deathsWritable))
		{
			throw new IOException("Recordings could not be loaded; existing file will not be overwritten");
		}
	}

	private List<TickRecording> current(boolean saved)
	{
		synchronized (snapshots)
		{
			return new ArrayList<>(saved ? savedRecordings : deaths);
		}
	}

	private void commit(boolean saved, List<TickRecording> replacement) throws Exception
	{
		writable(saved);
		publish(saved ? savedFile : deathsFile, replacement);
		synchronized (snapshots)
		{
			if (saved)
			{
				savedRecordings = replacement;
			}
			else
			{
				deaths = replacement;
			}
		}
	}

	private static TickRecording copy(TickRecording recording)
	{
		// Finalized tick DTOs and immutable rich JSON are shared read-only; mutable wrapper fields are detached.
		return recording.toBuilder().ticks(recording.getTicks() == null ? null : new ArrayList<>(recording.getTicks()))
			.build();
	}

	private static int index(List<TickRecording> records, TickRecording sought)
	{
		if (sought == null)
		{
			return -1;
		}
		for (int i = 0; i < records.size(); i++)
		{
			TickRecording record = records.get(i);
			if (record.getTimestamp() == sought.getTimestamp()
				&& Objects.equals(record.getRichExportJson(), sought.getRichExportJson())
				&& Objects.equals(record.getTicks(), sought.getTicks()))
			{
				return i;
			}
		}
		return -1;
	}

	public List<TickRecording> getDeaths()
	{
		return snapshot(false);
	}

	public List<TickRecording> getSavedRecordings()
	{
		return snapshot(true);
	}

	private List<TickRecording> snapshot(boolean saved)
	{
		List<TickRecording> result = new ArrayList<>();
		for (TickRecording record : current(saved))
		{
			result.add(copy(record));
		}
		return result;
	}

	public int getDeathCount()
	{
		synchronized (snapshots)
		{
			return deaths.size();
		}
	}

	public int getSavedCount()
	{
		synchronized (snapshots)
		{
			return savedRecordings.size();
		}
	}

	public CompletableFuture<Void> addDeath(TickRecording recording)
	{
		TickRecording detached = copy(recording);
		return addDeath(() -> detached);
	}

	public CompletableFuture<Void> addDeath(Supplier<TickRecording> supplier)
	{
		return submit(() ->
		{
			writable(false);
			List<TickRecording> next = current(false);
			next.add(0, copy(Objects.requireNonNull(supplier.get())));
			while (next.size() > maxDeaths)
			{
				next.remove(next.size() - 1);
			}
			commit(false, next);
		});
	}

	public CompletableFuture<Void> addSavedRecording(TickRecording recording)
	{
		TickRecording detached = copy(recording);
		return addSavedRecording(() -> detached);
	}

	public CompletableFuture<Void> addSavedRecording(Supplier<TickRecording> supplier)
	{
		return submit(() ->
		{
			writable(true);
			List<TickRecording> next = current(true);
			next.add(0, copy(Objects.requireNonNull(supplier.get())));
			commit(true, next);
		});
	}

	public CompletableFuture<Void> setMaxDeaths(int requested)
	{
		return submit(() ->
		{
			List<TickRecording> next = current(false);
			int limit = Math.max(0, requested);
			while (next.size() > limit)
			{
				next.remove(next.size() - 1);
			}
			if (next.size() != getDeathCount())
			{
				commit(false, next);
			}
			maxDeaths = limit;
		});
	}

	public CompletableFuture<Void> clearDeaths()
	{
		return submit(() -> commit(false, new ArrayList<>()));
	}

	public CompletableFuture<Void> clearSavedRecordings()
	{
		return submit(() -> commit(true, new ArrayList<>()));
	}

	public CompletableFuture<Void> deleteDeath(TickRecording recording)
	{
		return remove(false, recording);
	}

	public CompletableFuture<Void> deleteSavedRecording(TickRecording recording)
	{
		return remove(true, recording);
	}

	private CompletableFuture<Void> remove(boolean saved, TickRecording recording)
	{
		return submit(() ->
		{
			List<TickRecording> next = current(saved);
			int at = index(next, recording);
			if (at >= 0)
			{
				next.remove(at);
				commit(saved, next);
			}
		});
	}

	public CompletableFuture<Void> deleteDeath(int at)
	{
		return submit(() ->
		{
			List<TickRecording> next = current(false);
			if (at >= 0 && at < next.size())
			{
				next.remove(at);
				commit(false, next);
			}
		});
	}

	public CompletableFuture<Void> saveRecording(TickRecording recording)
	{
		return submit(() ->
		{
			writable(false);
			writable(true);
			List<TickRecording> recent = current(false);
			int at = index(recent, recording);
			if (at < 0)
			{
				return;
			}
			List<TickRecording> saved = current(true);
			// Destination first: interruption of the second write may duplicate, never lose a recording.
			// Retrying that interrupted move must not duplicate its already-published destination.
			if (index(saved, recent.get(at)) < 0)
			{
				saved.add(0, recent.get(at));
				commit(true, saved);
			}
			recent.remove(at);
			commit(false, recent);
		});
	}

	public CompletableFuture<Void> renameRecording(TickRecording recording, String name)
	{
		return submit(() ->
		{
			if (name == null || name.trim().isEmpty())
			{
				return;
			}
			for (boolean saved : new boolean[]
			{ false, true })
			{
				List<TickRecording> next = current(saved);
				int at = index(next, recording);
				if (at >= 0)
				{
					next.set(at, next.get(at).toBuilder().killerName(name.trim()).customLabel(Boolean.TRUE).build());
					commit(saved, next);
				}
			}
		});
	}

	@FunctionalInterface
	private interface Work
	{
		void run() throws Exception;
	}

	private CompletableFuture<Void> submit(Work work)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		synchronized (admission)
		{
			if (closed)
			{
				future.completeExceptionally(new IllegalStateException("Recording storage is closed"));
				return future;
			}
			try
			{
				queue.execute(() ->
				{
					try
					{
						work.run();
						future.complete(null);
					}
					catch (Exception failure)
					{
						log.error("Recording storage operation failed", failure);
						future.completeExceptionally(failure);
					}
				});
			}
			catch (RejectedExecutionException failure)
			{
				future.completeExceptionally(failure);
			}
		}
		return future;
	}

	/** New operations are rejected; admitted captures drain without blocking shutdown. */
	@Override
	public void close()
	{
		synchronized (admission)
		{
			if (!closed)
			{
				closed = true;
				release(owner);
			}
		}
	}
}
