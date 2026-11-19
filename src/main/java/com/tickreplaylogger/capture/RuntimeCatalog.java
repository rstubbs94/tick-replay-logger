/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** One immutable bundled mechanics package shared by capture and interpretation. */
public final class RuntimeCatalog
{
	private static volatile RuntimeCatalog bundled;
	private final JsonObject document;

	private RuntimeCatalog(JsonObject document)
	{
		this.document = document != null && Integer.valueOf(1).equals(ResolverV1.integer(document.get("schemaVersion")))
			&& "retrospective_combat_mechanics".equals(ResolverV1.text(document, "purpose")) ? document
				: new JsonObject();
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
				catch (RuntimeException | java.io.IOException unavailable)
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
		return ResolverV1.text(document, "catalogId");
	}
}
