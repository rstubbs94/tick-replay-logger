/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.util.*;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The production collector retains facts; the frozen oracle is not a runtime verdict path. */
public class RawInventoryCaptureParityTest
{
	private final Gson gson = new Gson();

	@Test
	public void actualBundleKeepsEveryCapturedItemSlotClockAndWitness()
	{
		JsonObject namespace = namespace();
		ObservedInventorySourceHints capture = new ObservedInventorySourceHints(namespace);
		LegacyInventorySourceHints baseline = new LegacyInventorySourceHints(authoredNamespace());
		for (JsonElement row : namespace.getAsJsonArray("rules"))
			for (JsonElement item : row.getAsJsonObject().getAsJsonArray("ids"))
			{
				int id = item.getAsInt();
				for (Item[] contents : Arrays.asList(new Item[]{new Item(id, 1)},
					new Item[]{null, new Item(id, 500), new Item(id, 2)}, new Item[0]))
					assertSameFacts(capture, baseline, client(contents, false, false));
			}
		for (int type = -1; type <= 100; type++)
			for (int amount : new int[]{-1, 0, 1, 1000})
				assertEquals("type=" + type + ";amount=" + amount, baseline.accepts(type, amount), capture.accepts(type, amount));
	}

	@Test
	public void unavailableAndTruncatedInventoryRetainExplicitAvailabilityWithoutInventedSources()
	{
		JsonObject namespace = namespace();
		ObservedInventorySourceHints capture = new ObservedInventorySourceHints(namespace);
		LegacyInventorySourceHints baseline = new LegacyInventorySourceHints(authoredNamespace());
		for (boolean missingContext : new boolean[]{false, true})
		{
			assertSameFacts(capture, baseline, client(null, true, missingContext));
			assertSameFacts(capture, baseline, client(null, false, missingContext));
			int id = namespace.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("ids").get(0).getAsInt();
			Item[] oversized = new Item[30];
			oversized[27] = new Item(id, 2);
			oversized[28] = new Item(id, 3);
			assertSameFacts(capture, baseline, client(oversized, false, missingContext));
		}
	}

	@Test
	public void malformedCompiledSelectorsRemainUnavailableWithoutInventingPossession()
	{
		for (String field : Arrays.asList("ids", "hitsplatTypes", "sourceRef", "pageHash", "needsNumericContext"))
		{
			JsonObject bindings = namespace().deepCopy();
			int id = bindings.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("ids").get(0).getAsInt();
			bindings.getAsJsonArray("rules").get(0).getAsJsonObject().remove(field);
			ObservedInventorySourceHints capture = new ObservedInventorySourceHints(bindings);
			assertTrue(capture.accepts(HitsplatID.DAMAGE_ME, 5));
			Map<String, Object> observed = capture.capture(client(new Item[]{new Item(id, 1)}, false, false), 7, 203);
			List<Map<String, Object>> witnesses = capture.witnesses(observed, 41);
			assertTrue(witnesses.stream().anyMatch(row -> "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE".equals(row.get("kind"))));
			assertFalse(witnesses.stream().anyMatch(row -> "ITEM_POSSESSION_CANDIDATE".equals(row.get("kind"))));
		}
	}

	private JsonObject namespace()
	{
		return RuntimeCatalog.load(gson).section("references").getAsJsonObject("inventorySourceCapture");
	}

	private JsonObject authoredNamespace()
	{
		try (java.io.InputStream input = getClass().getResourceAsStream("/com/tickreplaylogger/combat_catalog.json"))
		{
			return gson.fromJson(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8),
				JsonObject.class).getAsJsonObject("observedInventorySourceHints");
		}
		catch (java.io.IOException unavailable)
		{
			throw new AssertionError(unavailable);
		}
	}

	private void assertSameFacts(ObservedInventorySourceHints capture, LegacyInventorySourceHints baseline, Client client)
	{
		Map<String, Object> expected = baseline.capture(client, 7, 203);
		Map<String, Object> actual = capture.capture(client, 7, 203);
		assertEquals(gson.toJson(expected), gson.toJson(actual));
		assertEquals(gson.toJson(baseline.witnesses(expected, 41)), gson.toJson(capture.witnesses(actual, 41)));
		assertEquals(gson.toJson(LegacyInventorySourceHints.captureNumericContext(client, 7, null)),
			gson.toJson(ObservedInventorySourceHints.captureNumericContext(client, 7, null)));
	}

	private static Client client(Item[] contents, boolean missingContainer, boolean missingContext)
	{
		ItemContainer inventory = ObservedInventorySourceHintsTest.stub(ItemContainer.class,
			(name, args) -> "getItems".equals(name) ? contents : null);
		return ObservedInventorySourceHintsTest.stub(Client.class, (name, args) ->
		{
			if ("getItemContainer".equals(name))
				return missingContainer ? null : inventory;
			if ("getWorldType".equals(name) || "getVarbitValue".equals(name))
			{
				if (missingContext)
					throw new IllegalStateException("synthetic original field unavailable");
				return "getWorldType".equals(name) ? EnumSet.noneOf(WorldType.class) : 0;
			}
			return null;
		});
	}
}
