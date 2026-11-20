/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.SourceResolution;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Refactoring parity over primitive facts, not a claim of complete game observation. */
public class CanonicalMechanicsProjectionTest
{
	private final Gson gson = new Gson();

	@Test public void projectionRetainsConsumedFactsAndSeparatesLineageFromSemanticIdentity() throws Exception
	{
		RuntimeCatalog packaged = RuntimeCatalog.load(gson);
		JsonObject original = resource("combat_catalog.json");
		JsonObject authored=packaged.section("references").deepCopy();
		assertNotNull(authored.remove("inventorySourceCapture"));
		for (Map.Entry<String,JsonElement> field : authored.entrySet())
			assertJsonSame("references:"+field.getKey(),original.get(field.getKey()),field.getValue());
		assertFalse("Old constraints are compiled once into current resolution rules",authored.has("attributionConstraints"));
		assertFalse("Old mechanism tables remain in the test authoring input",authored.has("mechanismRules"));
		JsonObject originalCapture=resource("damage_map.json");
		for (Map.Entry<String,JsonElement> field : packaged.section("capture").entrySet())
			assertJsonSame("capture:"+field.getKey(),originalCapture.get(field.getKey()),field.getValue());
	}

	private JsonObject resource(String name) throws Exception
	{
		try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/com/tickreplaylogger/" + name), StandardCharsets.UTF_8))
		{ return gson.fromJson(reader, JsonObject.class); }
	}
	private static void assertJsonSame(String path, JsonElement expected, JsonElement actual)
	{
		if (expected==null || actual==null || expected.isJsonNull() || actual.isJsonNull())
		{ assertEquals(path,expected,actual); return; }
		if (expected.isJsonObject() && actual.isJsonObject())
		{
			assertEquals(path+":keys",expected.getAsJsonObject().keySet(),actual.getAsJsonObject().keySet());
			for (String key : expected.getAsJsonObject().keySet()) assertJsonSame(path+"."+key,expected.getAsJsonObject().get(key),actual.getAsJsonObject().get(key));
			return;
		}
		if (expected.isJsonArray() && actual.isJsonArray())
		{
			assertEquals(path+":length",expected.getAsJsonArray().size(),actual.getAsJsonArray().size());
			for (int index=0;index<expected.getAsJsonArray().size();index++) assertJsonSame(path+"["+index+"]",expected.getAsJsonArray().get(index),actual.getAsJsonArray().get(index));
			return;
		}
		assertTrue(path+":value-or-type",expected.equals(actual));
	}
	private static String text(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null; }
}
