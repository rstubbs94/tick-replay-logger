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
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import java.util.*;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import com.google.gson.stream.*;

/** Serializes completed captures once; historical payloads are opaque best-effort input. */
public final class RecordingExport
{
	private RecordingExport()
	{
	}

	private static final int COMPRESS_AT_BYTES = 1024 * 1024;
	private static final int MAX_EXPANDED_BYTES = 64 * 1024 * 1024;

	public static String forClipboard(TickRecording recording, Gson gson)
	{
		if (recording.getPreparedExport() != null)
		{
			String encoded = compress(out -> gson.toJson(recording.getPreparedExport(), out), gson, COMPRESS_AT_BYTES);
			return encoded == null ? forViewer(recording, gson) : encoded;
		}
		return clipboardTransport(forViewer(recording, gson), gson);
	}

	public static String forViewer(TickRecording recording, Gson gson)
	{
		if (recording.getPreparedExport() != null)
		{
			return gson.toJson(recording.getPreparedExport());
		}
		String historical = recording.getRichExportJson();
		if (historical == null || historical.isEmpty())
		{
			return gson.toJson(recording);
		}
		try (JsonReader reader = new JsonReader(new StringReader(historical)))
		{
			if (reader.peek() != JsonToken.BEGIN_OBJECT)
			{
				return gson.toJson(recording);
			}
			reader.skipValue();
			return reader.peek() == JsonToken.END_DOCUMENT ? historical : gson.toJson(recording);
		}
		catch (IOException | RuntimeException invalid)
		{
			return gson.toJson(recording);
		}
	}

	public static String clipboardTransport(String plain, Gson gson)
	{
		if (plain.length() < COMPRESS_AT_BYTES / 3 || !latestPayload(plain))
		{
			return plain;
		}
		String encoded = compress(out -> out.write(plain), gson, plain.length());
		return encoded == null ? plain : encoded;
	}

	@FunctionalInterface
	private interface WritePayload
	{
		void write(Writer out) throws IOException;
	}

	private static String compress(WritePayload payload, Gson gson, int sizeHint)
	{
		try
		{
			ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.min(1024 * 1024, sizeHint / 16));
			CountingOutputStream counted;
			try (GZIPOutputStream gzip = new GZIPOutputStream(compressed))
			{
				counted = new CountingOutputStream(gzip);
				try (Writer writer = new BufferedWriter(
					new OutputStreamWriter(counted, StandardCharsets.UTF_8.newEncoder()
						.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),
					32768))
				{
					payload.write(writer);
				}
			}
			if (counted.bytes < COMPRESS_AT_BYTES)
			{
				return null;
			}
			JsonObject envelope = new JsonObject();
			envelope.addProperty("schemaVersion", DeathRecordingV2.LATEST_SCHEMA_VERSION);
			envelope.addProperty("transportEncoding", "gzip-base64-v1");
			envelope.addProperty("uncompressedBytes", counted.bytes);
			envelope.addProperty("payload", Base64.getEncoder().encodeToString(compressed.toByteArray()));
			String result = gson.toJson(envelope);
			return result.length() < counted.bytes ? result : null; // Envelope is entirely ASCII.
		}
		catch (IOException unavailable)
		{
			return null;
		}
	}

	private static boolean latestPayload(String plain)
	{
		try (JsonReader reader = new JsonReader(new StringReader(plain)))
		{
			reader.beginObject();
			while (reader.hasNext())
			{
				if ("schemaVersion".equals(reader.nextName()))
				{
					return DeathRecordingV2.LATEST_SCHEMA_VERSION.equals(reader.nextString());
				}
				reader.skipValue();
			}
		}
		catch (IOException | RuntimeException invalid)
		{
			return false;
		}
		return false;
	}

	private static final class CountingOutputStream extends FilterOutputStream
	{
		long bytes;

		CountingOutputStream(OutputStream out)
		{
			super(out);
		}

		@Override
		public void write(int value) throws IOException
		{
			if (++bytes > MAX_EXPANDED_BYTES)
			{
				throw new IOException("Expanded recording limit");
			}
			out.write(value);
		}

		@Override
		public void write(byte[] values, int offset, int length) throws IOException
		{
			bytes += length;
			if (bytes > MAX_EXPANDED_BYTES)
			{
				throw new IOException("Expanded recording limit");
			}
			out.write(values, offset, length);
		}
	}

}
