/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;

/** Reference facts for damage resolution, generated from wiki and cache data. Facts only; no logic. */
@Getter
final class ResolverCatalog
{
	@Value
	static class Npc
	{
		Integer maxHit;
		Integer attackSpeed;
		boolean scaled;
		Set<Integer> attackAnimations;
		/** Max hit per combat style from the wiki, when every listed value is certain. */
		Map<String, Integer> styleMaxHits;
	}

	private final String id;
	private final Map<Integer, String> statuses = new HashMap<>();
	private final Set<Integer> nonHpHitsplats = new HashSet<>();
	private final Map<Integer, Npc> npcs = new HashMap<>();
	private final Set<Integer> venomous = new HashSet<>();
	private final Map<Integer, String> projectileStyles = new HashMap<>();
	private final Map<Integer, String> hazardGraphics = new HashMap<>();
	private final Set<Integer> attackAnimations = new HashSet<>();
	private final Map<Integer, String> surfaces = new HashMap<>();
	private final Map<Integer, String> contactNpcs = new HashMap<>();
	private final Map<Integer, String> selfDamageItems = new HashMap<>();
	/** Combat style of an attack animation, from its cache name. */
	private final Map<Integer, String> animationStyles = new HashMap<>();
	/** Projectiles the cache names as prayer-disabling attacks. */
	private final Set<Integer> prayerDisablers = new HashSet<>();

	ResolverCatalog(String id, JsonObject json)
	{
		this.id = id;
		JsonObject hitsplats = json.getAsJsonObject("hitsplats");
		names(hitsplats.getAsJsonObject("status"), statuses);
		hitsplats.getAsJsonArray("nonHp").forEach(e -> nonHpHitsplats.add(e.getAsInt()));
		json.getAsJsonObject("npcs").entrySet().forEach(e ->
		{
			JsonArray row = e.getValue().getAsJsonArray();
			Set<Integer> animations = new HashSet<>();
			row.get(3).getAsJsonArray().forEach(a -> animations.add(a.getAsInt()));
			Map<String, Integer> styles = new HashMap<>();
			if (row.size() > 4)
			{
				row.get(4).getAsJsonObject().entrySet().forEach(s -> styles.put(s.getKey(), s.getValue().getAsInt()));
			}
			npcs.put(Integer.parseInt(e.getKey()),
				new Npc(integer(row.get(0)), integer(row.get(1)), row.get(2).getAsInt() == 1, animations, styles));
		});
		json.getAsJsonArray("venomous").forEach(e -> venomous.add(e.getAsInt()));
		json.getAsJsonArray("attackAnims").forEach(e -> attackAnimations.add(e.getAsInt()));
		names(json.getAsJsonObject("projectileStyles"), projectileStyles);
		names(json.getAsJsonObject("hazardGraphics"), hazardGraphics);
		names(json.getAsJsonObject("surfaces"), surfaces);
		names(json.getAsJsonObject("contactNpcs"), contactNpcs);
		if (json.has("animationStyles"))
		{
			names(json.getAsJsonObject("animationStyles"), animationStyles);
		}
		if (json.has("prayerDisablers"))
		{
			json.getAsJsonArray("prayerDisablers").forEach(e -> prayerDisablers.add(e.getAsInt()));
		}
		json.getAsJsonObject("selfDamageItems").entrySet().forEach(
			e -> selfDamageItems.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonArray().get(0).getAsString()));
	}

	private static volatile ResolverCatalog bundled;

	/** The packaged catalog, loaded once. A missing or unreadable file gives an empty catalog (generic rules only). */
	static ResolverCatalog bundled(Gson gson)
	{
		if (bundled == null)
		{
			synchronized (ResolverCatalog.class)
			{
				if (bundled == null)
				{
					try (InputStream in = ResolverCatalog.class
						.getResourceAsStream("/com/tickreplaylogger/resolver-catalog.json"))
					{
						bundled = load(gson, in);
					}
					catch (IOException | RuntimeException unavailable)
					{
						bundled = new ResolverCatalog("catalog-unavailable", gson.fromJson(EMPTY, JsonObject.class));
					}
				}
			}
		}
		return bundled;
	}

	private static final String EMPTY = "{\"hitsplats\":{\"status\":{},\"nonHp\":[]},\"npcs\":{},\"venomous\":[],"
		+ "\"attackAnims\":[],\"projectileStyles\":{},\"hazardGraphics\":{},\"surfaces\":{},\"contactNpcs\":{},"
		+ "\"selfDamageItems\":{}}";

	static ResolverCatalog load(Gson gson, InputStream in) throws IOException
	{
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			JsonObject json = gson.fromJson(reader, JsonObject.class);
			return new ResolverCatalog("catalog-v" + json.get("version").getAsInt(), json);
		}
	}

	/** Max hit usable for exclusion, or null when unknown or scaled (raids and modes change max hits). */
	Integer ceiling(Integer npcId)
	{
		Npc npc = npcId == null ? null : npcs.get(npcId);
		return npc == null || npc.scaled ? null : npc.maxHit;
	}

	boolean canDeal(Integer npcId, int amount)
	{
		return canDeal(npcId, amount, null);
	}

	/** As above, using the max hit of the attack's style when the wiki lists one (Scurrius melee 13, rockfall 22). */
	boolean canDeal(Integer npcId, int amount, String style)
	{
		Npc npc = npcId == null ? null : npcs.get(npcId);
		Integer max = npc == null || npc.scaled ? null
			: style != null && npc.styleMaxHits.containsKey(style) ? npc.styleMaxHits.get(style) : npc.maxHit;
		return max == null || amount <= max;
	}

	private static void names(JsonObject from, Map<Integer, String> into)
	{
		from.entrySet().forEach(e -> into.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()));
	}

	private static Integer integer(JsonElement value)
	{
		return value == null || value.isJsonNull() ? null : value.getAsInt();
	}
}
