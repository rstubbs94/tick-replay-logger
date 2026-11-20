/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;

/** One immutable bundled mechanics package shared by capture and interpretation. */
public final class RuntimeCatalog
{
	private static volatile RuntimeCatalog bundled;
	private final JsonObject document;

	private RuntimeCatalog(JsonObject document)
	{
		this.document = document != null && "1".equals(text(document, "schemaVersion"))
			&& "retrospective_combat_mechanics".equals(text(document, "purpose")) ? document : new JsonObject();
	}

	public static RuntimeCatalog load(Gson gson)
	{
		RuntimeCatalog value = bundled;
		if (value != null)
		{
			return value;
		}
		synchronized (RuntimeCatalog.class)
		{
			if (bundled == null)
			{
				try (InputStream input = RuntimeCatalog.class
					.getResourceAsStream("/com/tickreplaylogger/runtime-mechanics.json"))
				{
					bundled = new RuntimeCatalog(input == null ? null
						: gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class));
				}
				catch (RuntimeException | IOException unavailable)
				{
					bundled = new RuntimeCatalog(null);
				}
			}
			return bundled;
		}
	}

	/** Read-only views; callers must never mutate the shared package. */
	public JsonObject section(String key)
	{
		return document.has(key) && document.get(key).isJsonObject() ? document.getAsJsonObject(key) : new JsonObject();
	}

	public String id()
	{
		return text(document, "catalogId");
	}

	private static String text(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}
}
