/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.util.*;
import java.util.*;
import java.util.stream.*;
import lombok.*;

/**
 * Works out where each recorded hit on the player came from, after capture. Evidence is checked from
 * strongest to weakest, the way a person reviewing the fight would: status splats, projectiles landing
 * on the player, surfaces and timed attacks, nearby attacks, the player's own actions, game messages.
 * A source is only ruled out by a positive fact (an exceeded max hit in unscaled content). When more than
 * one source remains, all are reported.
 */
@RequiredArgsConstructor
final class DamageResolver
{
	static final String METHOD = "RESOLVER_V2";
	private static final int EARLY = 15, LATE = 35, NEARBY_TILES = 10, LAUNCH_CYCLES = 60;

	private final ResolverCatalog catalog;

	@AllArgsConstructor
	private static final class Candidate
	{
		final String name, actorRef;
		final Integer npcId;
		boolean strong;
		final String evidence;
		/** Where the source actor was: scene key, world view and plane, when recorded. */
		String sceneKey;
		Integer worldViewId, plane;

		Candidate(String name, String actorRef, Integer npcId, boolean strong, String evidence)
		{
			this(name, actorRef, npcId, strong, evidence, null, null, null);
		}

		String key()
		{
			return actorRef != null ? actorRef : name;
		}

		/** Same kind of source: one NPC definition, surface, graphic, status or action. */
		String family()
		{
			return npcId != null ? "npc:" + npcId : evidence.replaceAll(":.*", "").toLowerCase() + ":" + name;
		}

		Candidate at(String scene, Integer view, Integer level)
		{
			sceneKey = scene;
			worldViewId = view;
			plane = level;
			return this;
		}

		boolean located()
		{
			return actorRef != null && sceneKey != null && worldViewId != null && plane != null;
		}
	}

	/** Projectiles that may explain one hit, and whether every best assignment gives this hit one. */
	@AllArgsConstructor
	private static final class Assignment
	{
		final List<ProjectileState> projectiles;
		final boolean always;
	}

	/** Resolves a frozen capture on the storage worker; the journal and death summary follow from the result. */
	static DeathRecordingV2 resolveRecording(DeathRecordingV2 recording, Gson gson)
	{
		ResolverCatalog catalog = ResolverCatalog.bundled(gson);
		return recording == null || recording.getEvents() == null ? recording
			: RecordingProjection.apply(recording, new DamageResolver(catalog).resolve(recording), catalog.getId());
	}

	/** Each hit on the player, with its source resolution and display fields, keyed by observation sequence. */
	Map<Long, DamageRecord> resolve(DeathRecordingV2 recording)
	{
		Window window = new Window(recording);
		Map<DamageRecord, Assignment> assigned = assignProjectiles(window);
		Map<Long, DamageRecord> out = new LinkedHashMap<>();
		for (DamageRecord hit : window.hits)
		{
			if (hit.getObservationSequence() != null)
			{
				out.put(hit.getObservationSequence(), describe(hit, resolve(window, hit,
					assigned.getOrDefault(hit, new Assignment(Collections.emptyList(), true)))));
			}
		}
		return out;
	}

	/** Fills the journal and display fields from the resolution; the recorded facts stay unchanged. */
	private DamageRecord describe(DamageRecord hit, List<Candidate> found)
	{
		SourceResolution resolution = result(found);
		Candidate only = "IDENTIFIED".equals(resolution.getStatus()) ? found.get(0) : null;
		String status = catalog.getStatuses().get(hit.getHitsplatType()), style = only == null
			|| !only.evidence.startsWith("PROJECTILE:") ? null
				: catalog.getProjectileStyles().get(Integer.parseInt(only.evidence.substring(11)));
		String label = SourceResolutionLabels.label(resolution);
		return hit.toBuilder().sourceResolution(resolution).attackerName(label == null ? "Unknown" : label)
			.attackerRef(only == null ? null : only.actorRef).attackerNpcId(only == null ? null : only.npcId)
			.attribution(status != null ? "ENVIRONMENTAL" : only != null ? "OBSERVED" : "UNKNOWN")
			.attributionEvidence(status != null ? "STATUS_HITSPLAT" : only != null ? only.evidence
				: found.isEmpty() ? "UNRESOLVED_OBSERVATIONS" : "MULTIPLE_POSSIBLE_DAMAGE_SOURCES")
			.damageType(status != null ? status.toLowerCase(Locale.ROOT) : style != null ? style : hit.getDamageType())
			.build();
	}

	/** Every source behind the hits in one tick (the death tick); a single shared resolution is kept as is. */
	static SourceResolution merge(List<SourceResolution> resolutions, String catalogId)
	{
		if (resolutions.size() == 1)
		{
			return resolutions.get(0);
		}
		Map<String, SourceResolution.Candidate> byKey = new LinkedHashMap<>();
		resolutions.forEach(r -> r.getCandidates().forEach(c -> byKey.putIfAbsent(c.getKey(), c)));
		List<SourceResolution.Candidate> all = byKey.values().stream()
			.map(c -> c.toBuilder().strength("POSSIBLE").build()).collect(Collectors.toList());
		boolean actors = !all.isEmpty() && all.stream().allMatch(c -> c.getActorRef() != null && c.getSceneKey() != null
			&& c.getWorldViewId() != null && c.getPlane() != null);
		return SourceResolution.builder().version(1).status(all.isEmpty() ? "UNIDENTIFIED" : "ALTERNATIVES")
			.identityLevel(all.isEmpty() ? "NONE" : actors ? "ACTOR" : "FAMILY").candidates(all)
			.unidentifiedPossible(true).catalogId(catalogId).method(METHOD).build();
	}

	private List<Candidate> resolve(Window w, DamageRecord hit, Assignment assignment)
	{
		int tick = hit.getImpactTick(), amount = hit.getAmount();
		String status = catalog.getStatuses().get(hit.getHitsplatType());
		if (status != null)
		{
			return List.of(new Candidate(status, null, null, true, "STATUS"));
		}
		if (catalog.getNonHpHitsplats().contains(hit.getHitsplatType()))
		{
			return List.of(new Candidate("Non-HP effect", null, null, true, "NON_HP"));
		}
		List<Candidate> projectiles = new ArrayList<>();
		for (ProjectileState p : assignment.projectiles)
		{
			Integer npc = w.sourceNpcId(p);
			if (catalog.canDeal(npc, amount))
			{
				String name = p.getSourceName() != null ? p.getSourceName() : "Projectile " + p.getProjectileId();
				projectiles.add(new Candidate(name, p.getSourceRef(), npc, true, "PROJECTILE:" + p.getProjectileId())
					.at(p.getSourceSceneKey(), p.getSourceWorldViewId(), p.getSourcePlane()));
			}
		}
		if (!projectiles.isEmpty() && assignment.always)
		{
			return possibleIfSeveral(distinct(projectiles));
		}
		ObservedActorState player = w.player(tick);
		List<Candidate> area = player == null ? List.of() : area(w, hit, player);
		List<Candidate> attacks = player == null ? List.of() : attacks(w, tick, amount, player);
		List<Candidate> peers = distinct(Stream.of(projectiles, strong(area, true), attacks, ownActions(w, tick))
			.flatMap(List::stream).collect(Collectors.toList()));
		if (!peers.isEmpty())
		{
			// Weak clues never dilute strong evidence; a projectile shared with another splat always stays.
			List<Candidate> sure = strong(peers, true);
			List<Candidate> kept = sure.isEmpty() ? peers : distinct(concat(sure, projectiles));
			boolean certain = kept.size() == 1 && kept.get(0).strong && projectiles.isEmpty();
			kept.forEach(c -> c.strong = certain);
			return certain || !sure.isEmpty() ? kept : distinct(concat(kept, area));
		}
		List<Candidate> weak = player == null ? area : distinct(concat(area, nearbyAttacks(w, tick, amount, player)));
		return weak.isEmpty() ? messages(w, tick) : weak;
	}

	/** Surfaces and contact NPCs under the player, hazard graphics on the tile, projectiles aimed at it. */
	private List<Candidate> area(Window w, DamageRecord hit, ObservedActorState player)
	{
		List<Candidate> out = new ArrayList<>();
		for (int tick = hit.getImpactTick(); tick >= hit.getImpactTick() - 1; tick--)
		{
			WorldSnapshot frame = w.frames.get(tick);
			if (frame == null)
			{
				continue;
			}
			for (HazardState h : list(frame.getHazards()))
			{
				int width = span(h.getMinSceneX(), h.getMaxSceneX()), height = span(h.getMinSceneY(), h.getMaxSceneY());
				if (h.getPlane() != player.getPlane()
					|| distance(player, h.getWorldX(), h.getWorldY(), Math.max(width, height)) != 0)
				{
					continue;
				}
				String surface = catalog.getSurfaces().get(h.getId());
				String graphic = "graphicsObject".equals(h.getKind()) ? catalog.getHazardGraphics().get(h.getId()) : null;
				if (surface != null)
				{
					out.add(new Candidate(surface, null, null, true, "SURFACE:" + h.getId()));
				}
				else if (graphic != null)
				{
					out.add(new Candidate(graphic, null, null, false, "GRAPHIC_ON_TILE:" + h.getId()));
				}
			}
			for (NpcState npc : list(frame.getNpcs()))
			{
				String contact = catalog.getContactNpcs().get(npc.getNpcId());
				if (contact != null && distance(player, npc.getWorldX(), npc.getWorldY(), size(npc)) == 0)
				{
					out.add(new Candidate(contact, npc.getActorRef(), npc.getNpcId(), true, "CONTACT_NPC")
						.at(frame.getSceneKey(), npc.getWorldViewId(), npc.getPlane()));
				}
			}
		}
		Integer cycle = hit.getObservedCycle();
		for (ProjectileState p : w.projectiles)
		{
			if (cycle != null && !"player".equals(p.getTargetRef()) && lands(p, cycle) && p.getEndWorldX() != null
				&& distance(player, p.getEndWorldX(), p.getEndWorldY(), 1) <= 1
				&& catalog.canDeal(w.sourceNpcId(p), hit.getAmount()))
			{
				out.add(new Candidate(p.getSourceName() == null ? "Area attack" : p.getSourceName() + " (area attack)",
					p.getSourceRef(), w.sourceNpcId(p), false, "TILE_PROJECTILE:" + p.getProjectileId())
					.at(p.getSourceSceneKey(), p.getSourceWorldViewId(), p.getSourcePlane()));
			}
		}
		return out;
	}

	/** NPCs targeting the player whose attack animation lands on this tick by the wiki hit-delay rules. */
	private List<Candidate> attacks(Window w, int tick, int amount, ObservedActorState player)
	{
		List<Candidate> out = new ArrayList<>();
		for (ObservedEvent animation : w.animations)
		{
			NpcState npc = w.npc(animation.getTick(), animation.getActorRef());
			if (npc == null || !named(npc) || !Boolean.TRUE.equals(npc.getInteractingWithPlayer())
				|| npc.getPlane() != player.getPlane() || launchedProjectile(w, animation, npc))
			{
				continue;
			}
			// Name-linked animation lists can be wrong (variants share symbols), so a list adds confidence but
			// never excludes an animation the cache itself names as an attack.
			int id = number(animation.getData().get("animationId"));
			ResolverCatalog.Npc reference = catalog.getNpcs().get(npc.getNpcId());
			boolean known = catalog.getAttackAnimations().contains(id)
				|| reference != null && reference.getAttackAnimations().contains(id);
			if (!known && reference != null && !reference.getAttackAnimations().isEmpty())
			{
				continue;
			}
			int d = distance(player, npc.getWorldX(), npc.getWorldY(), size(npc));
			if (hitDelays(d).contains(tick - animation.getTick()) && catalog.canDeal(npc.getNpcId(), amount))
			{
				out.add(new Candidate(name(npc), npc.getActorRef(), npc.getNpcId(), known || d <= 1,
					"ATTACK_TIMING:" + id).at(w.sceneKey(animation.getTick()), npc.getWorldViewId(), npc.getPlane()));
			}
		}
		return possibleIfSeveral(distinct(out));
	}

	/**
	 * An animation followed by this NPC's own projectile is that projectile's attack, matched on its own. The
	 * projectile's actor reference decides; a name only counts when no other NPC in the recording shares it.
	 */
	private static boolean launchedProjectile(Window w, ObservedEvent animation, NpcState npc)
	{
		Integer cycle = animation.getGameCycle();
		return cycle != null && w.projectiles.stream().anyMatch(p -> p.getStartCycle() != null
			&& p.getStartCycle() >= cycle && p.getStartCycle() - cycle <= LAUNCH_CYCLES
			&& (p.getSourceRef() != null ? p.getSourceRef().equals(npc.getActorRef())
				: npc.getName().equals(p.getSourceName()) && w.uniqueNames.contains(npc.getName())));
	}

	/** Melee lands immediately; bows, thrown weapons and magic by distance; one tick of processing slack. */
	private static Set<Integer> hitDelays(int distance)
	{
		Set<Integer> delays = distance <= 1 ? new HashSet<>(List.of(0, 1))
			: new HashSet<>(List.of(1 + (1 + distance) / 3, 1 + (3 + distance) / 6, 1 + distance / 6));
		new ArrayList<>(delays).forEach(d -> delays.add(d + 1));
		return delays;
	}

	private List<Candidate> nearbyAttacks(Window w, int tick, int amount, ObservedActorState player)
	{
		List<Candidate> out = new ArrayList<>();
		for (ObservedEvent animation : w.animations)
		{
			NpcState npc = w.npc(animation.getTick(), animation.getActorRef());
			int age = tick - animation.getTick();
			if (npc != null && named(npc) && !launchedProjectile(w, animation, npc) && age >= 0 && age <= 2 && npc.getPlane() == player.getPlane()
				&& catalog.getAttackAnimations().contains(number(animation.getData().get("animationId")))
				&& distance(player, npc.getWorldX(), npc.getWorldY(), size(npc)) <= NEARBY_TILES
				&& catalog.canDeal(npc.getNpcId(), amount))
			{
				out.add(new Candidate(name(npc), npc.getActorRef(), npc.getNpcId(), false, "NEARBY_ATTACK")
					.at(w.sceneKey(animation.getTick()), npc.getWorldViewId(), npc.getPlane()));
			}
		}
		return out;
	}

	/**
	 * The player's own item, object or NPC click this tick or the previous one. The item, obstacle or NPC is the
	 * source (a failed pickpocket names the NPC); a game message at the hit confirms it but is never a source.
	 */
	private List<Candidate> ownActions(Window w, int tick)
	{
		boolean confirmed = !messages(w, tick).isEmpty();
		List<Candidate> out = new ArrayList<>();
		for (ObservedEvent click : w.clicks)
		{
			int age = tick - click.getTick();
			Map<String, Object> data = click.getData();
			String option = text(data.get("option")), target = text(data.get("target"));
			Object item = data.get("itemId");
			String selfItem = item instanceof Number ? catalog.getSelfDamageItems().get(((Number) item).intValue()) : null;
			if (age < 0 || age > 1 || option == null)
			{
				continue;
			}
			if (selfItem != null)
			{
				out.add(new Candidate(selfItem, null, null, true, "SELF_ITEM"));
			}
			else if (target != null && !Set.of("Walk here", "Attack", "Cancel", "Examine").contains(option)
				&& String.valueOf(data.get("menuAction")).matches(".*(GAME_OBJECT|NPC_).*"))
			{
				NpcState npc = w.npc(click.getTick(), click.getRelatedActorRef());
				out.add(new Candidate(target.replaceAll("\\s*\\(level-\\d+\\)", "").trim(), npc == null ? null
					: npc.getActorRef(), npc == null ? null : npc.getNpcId(), confirmed, "OWN_ACTION:" + option)
					.at(npc == null ? null : w.sceneKey(click.getTick()), npc == null ? null : npc.getWorldViewId(),
						npc == null ? null : npc.getPlane()));
			}
		}
		return out;
	}

	private List<Candidate> messages(Window w, int tick)
	{
		return w.messages.stream().filter(m -> tick - m.getTick() >= 0 && tick - m.getTick() <= 1)
			.map(m -> text(m.getData().get("message"))).filter(Objects::nonNull)
			.map(m -> new Candidate("Message: " + m.replaceAll("<[^>]+>", ""), null, null, false, "MESSAGE"))
			.collect(Collectors.toList());
	}

	/**
	 * Matches projectiles aimed at the player to hits landing on the same cycle. Every one-to-one assignment
	 * allowed by max hits is considered; the best explain the most hits, then the most hits too large for the
	 * weakest projectile source. A hit keeps every projectile it receives in a best assignment.
	 */
	private Map<DamageRecord, Assignment> assignProjectiles(Window w)
	{
		Map<Integer, List<DamageRecord>> byCycle = w.hits.stream()
			.filter(h -> h.getObservedCycle() != null && !catalog.getStatuses().containsKey(h.getHitsplatType())
				&& !catalog.getNonHpHitsplats().contains(h.getHitsplatType()))
			.collect(Collectors.groupingBy(DamageRecord::getObservedCycle, TreeMap::new, Collectors.toList()));
		Map<Integer, List<ProjectileState>> landing = new HashMap<>();
		for (ProjectileState p : w.projectiles)
		{
			if (!"player".equals(p.getTargetRef()) || p.getEndCycle() == null)
			{
				continue;
			}
			byCycle.keySet().stream().filter(c -> lands(p, c)).min(Comparator.comparingInt(c -> Math.abs(p.getEndCycle() - c)))
				.ifPresent(c -> landing.computeIfAbsent(c, k -> new ArrayList<>()).add(p));
		}
		Map<DamageRecord, Assignment> out = new HashMap<>();
		landing.forEach((cycle, projectiles) -> assignGroup(w, byCycle.get(cycle), projectiles, out));
		return out;
	}

	private void assignGroup(Window w, List<DamageRecord> hits, List<ProjectileState> projectiles,
		Map<DamageRecord, Assignment> out)
	{
		int weakest = projectiles.stream().map(p -> catalog.ceiling(w.sourceNpcId(p))).filter(Objects::nonNull)
			.min(Integer::compare).orElse(Integer.MAX_VALUE);
		List<List<ProjectileState>> best = new ArrayList<>();
		int[] bestScore = {-1, -1};
		permute(hits, projectiles, new ArrayList<>(), new HashSet<>(), choice ->
		{
			int matched = 0, forced = 0;
			for (int i = 0; i < hits.size(); i++)
			{
				ProjectileState p = choice.get(i);
				if (p != null && !catalog.canDeal(w.sourceNpcId(p), hits.get(i).getAmount()))
				{
					return;
				}
				matched += p == null ? 0 : 1;
				forced += p != null && hits.get(i).getAmount() > weakest ? 1 : 0;
			}
			if (matched > bestScore[0] || matched == bestScore[0] && forced > bestScore[1])
			{
				best.clear();
				bestScore[0] = matched;
				bestScore[1] = forced;
			}
			if (matched == bestScore[0] && forced == bestScore[1])
			{
				best.add(new ArrayList<>(choice));
			}
		});
		for (int i = 0; i < hits.size(); i++)
		{
			final int index = i;
			List<ProjectileState> options = best.stream().map(c -> c.get(index)).filter(Objects::nonNull).distinct()
				.collect(Collectors.toList());
			if (!options.isEmpty())
			{
				out.put(hits.get(i), new Assignment(options, best.stream().allMatch(c -> c.get(index) != null)));
			}
		}
	}

	/** Every one-to-one choice of a projectile (or none) per hit. Groups are a few splats on one cycle. */
	private static void permute(List<DamageRecord> hits, List<ProjectileState> projectiles, List<ProjectileState> chosen,
		Set<ProjectileState> used, java.util.function.Consumer<List<ProjectileState>> visit)
	{
		if (chosen.size() == hits.size())
		{
			visit.accept(chosen);
			return;
		}
		chosen.add(null);
		permute(hits, projectiles, chosen, used, visit);
		chosen.remove(chosen.size() - 1);
		for (ProjectileState p : projectiles)
		{
			if (used.add(p))
			{
				chosen.add(p);
				permute(hits, projectiles, chosen, used, visit);
				chosen.remove(chosen.size() - 1);
				used.remove(p);
			}
		}
	}

	/** The existing SourceResolution contract: one candidate when identified; actor identities need a location. */
	private SourceResolution result(List<Candidate> found)
	{
		boolean identified = found.size() == 1 && found.get(0).strong;
		boolean actors = !found.isEmpty() && found.stream().allMatch(Candidate::located);
		List<SourceResolution.Candidate> candidates = found.stream().map(c -> SourceResolution.Candidate.builder()
			.key(c.evidence + ":" + c.key()).name(c.name).familyKey(c.family())
			.actorRef(actors ? c.actorRef : null).npcId(c.npcId).sceneKey(actors ? c.sceneKey : null)
			.worldViewId(actors ? c.worldViewId : null).plane(actors ? c.plane : null)
			.strength(identified ? "STRONG" : "POSSIBLE").evidence(List.of(c.evidence)).build())
			.collect(Collectors.toList());
		return SourceResolution.builder().version(1)
			.status(found.isEmpty() ? "UNIDENTIFIED" : identified ? "IDENTIFIED" : "ALTERNATIVES")
			.identityLevel(found.isEmpty() ? "NONE" : actors ? "ACTOR" : "FAMILY")
			.candidates(candidates).unidentifiedPossible(!identified).catalogId(catalog.getId()).method(METHOD).build();
	}

	private static List<Candidate> possibleIfSeveral(List<Candidate> found)
	{
		if (found.stream().map(c -> c.name).distinct().count() > 1)
		{
			found.forEach(c -> c.strong = false);
		}
		return found;
	}

	private static List<Candidate> strong(List<Candidate> found, boolean strong)
	{
		return found.stream().filter(c -> c.strong == strong).collect(Collectors.toList());
	}

	private static List<Candidate> distinct(List<Candidate> found)
	{
		Map<String, Candidate> byKey = new LinkedHashMap<>();
		found.forEach(c -> byKey.putIfAbsent(c.key(), c));
		return new ArrayList<>(byKey.values());
	}

	private static List<Candidate> concat(List<Candidate> first, List<Candidate> second)
	{
		List<Candidate> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	private static boolean lands(ProjectileState p, int cycle)
	{
		int offset = p.getEndCycle() - cycle;
		return offset >= -EARLY && offset <= LATE;
	}

	/** Chebyshev distance from the player to the nearest tile of a size x size footprint anchored south-west. */
	private static int distance(ObservedActorState player, Integer x, Integer y, int size)
	{
		if (x == null || y == null)
		{
			return Integer.MAX_VALUE;
		}
		int dx = Math.max(Math.max(x - player.getWorldX(), 0), player.getWorldX() - (x + size - 1));
		int dy = Math.max(Math.max(y - player.getWorldY(), 0), player.getWorldY() - (y + size - 1));
		return Math.max(dx, dy);
	}

	private static int span(Integer min, Integer max)
	{
		return min == null || max == null ? 1 : max - min + 1;
	}

	private static int size(NpcState npc)
	{
		return npc.getSize() == null ? 1 : npc.getSize();
	}

	private static String name(NpcState npc)
	{
		return npc.getName();
	}

	/** Unnamed NPCs (the client reports "null") are scenery or effects, never attackers. */
	private static boolean named(NpcState npc)
	{
		return npc.getName() != null && !npc.getName().isEmpty() && !"null".equals(npc.getName());
	}

	private static int number(Object value)
	{
		return value instanceof Number ? ((Number) value).intValue() : -1;
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
	}

	private static <T> List<T> list(List<T> values)
	{
		return values == null ? List.of() : values;
	}

	/** Indexed view of one captured window. */
	private final class Window
	{
		final Map<Integer, WorldSnapshot> frames = new HashMap<>();
		final List<ProjectileState> projectiles;
		final List<DamageRecord> hits;
		final List<ObservedEvent> animations = new ArrayList<>(), clicks = new ArrayList<>(),
			messages = new ArrayList<>();
		/** NPC names carried by exactly one actor in this recording. */
		final Set<String> uniqueNames;

		Window(DeathRecordingV2 recording)
		{
			Map<Object, ProjectileState> latest = new LinkedHashMap<>();
			for (WorldSnapshot frame : list(recording.getTicks()))
			{
				frames.put(frame.getTick(), frame);
				for (ProjectileState p : list(frame.getProjectiles()))
				{
					latest.put(p.getObservationId() != null ? p.getObservationId()
						: Arrays.asList(p.getProjectileId(), p.getStartCycle()), p);
				}
			}
			projectiles = new ArrayList<>(latest.values());
			Map<String, Set<String>> actorsByName = new HashMap<>();
			frames.values().forEach(f -> list(f.getNpcs()).forEach(n -> actorsByName
				.computeIfAbsent(String.valueOf(n.getName()), k -> new HashSet<>()).add(n.getActorRef())));
			uniqueNames = actorsByName.entrySet().stream().filter(e -> e.getValue().size() == 1).map(Map.Entry::getKey)
				.collect(Collectors.toSet());
			DeathRecordingV2.Events events = recording.getEvents();
			hits = list(events.getDamageEvents()).stream().filter(h -> "player".equals(h.getDefenderRef())
				&& !"DEALT".equals(h.getDirection()) && !"NPC_RECEIVED".equals(h.getDirection()))
				.sorted(Comparator.comparing((DamageRecord h) -> h.getObservedCycle() == null ? 0 : h.getObservedCycle())
					.thenComparing(h -> h.getObservationSequence() == null ? 0 : h.getObservationSequence()))
				.collect(Collectors.toList());
			for (ObservedEvent e : list(events.getObservations()))
			{
				if ("ANIMATION_CHANGED".equals(e.getType()) && e.getActorRef() != null && e.getActorRef().startsWith("npc")
					&& e.getData() != null && number(e.getData().get("animationId")) >= 0)
				{
					animations.add(e);
				}
				else if ("MENU_OPTION".equals(e.getType()) && e.getData() != null)
				{
					clicks.add(e);
				}
				else if ("SYSTEM_MESSAGE".equals(e.getType()) && e.getData() != null)
				{
					messages.add(e);
				}
			}
		}

		ObservedActorState player(int tick)
		{
			for (int t : new int[]{tick, tick - 1, tick + 1})
			{
				WorldSnapshot frame = frames.get(t);
				if (frame != null && frame.getPlayer() != null && Boolean.TRUE.equals(frame.getPlayer().getWorldPositionKnown()))
				{
					return frame.getPlayer();
				}
			}
			return null;
		}

		String sceneKey(int tick)
		{
			WorldSnapshot frame = frames.get(tick);
			return frame == null ? null : frame.getSceneKey();
		}

		NpcState npc(int tick, String ref)
		{
			for (int t : new int[]{tick, tick - 1, tick + 1})
			{
				WorldSnapshot frame = frames.get(t);
				for (NpcState npc : frame == null ? List.<NpcState>of() : list(frame.getNpcs()))
				{
					if (ref != null && ref.equals(npc.getActorRef()))
					{
						return npc;
					}
				}
			}
			return null;
		}

		/** The projectile's source NPC id; older captures only name it, so same-named NPCs supply the id. */
		Integer sourceNpcId(ProjectileState p)
		{
			if (p.getSourceNpcId() != null)
			{
				return p.getSourceNpcId();
			}
			NpcState source = npc(p.getObservedTick() == null ? 0 : p.getObservedTick(), p.getSourceRef());
			if (source != null)
			{
				return source.getNpcId();
			}
			return frames.values().stream().flatMap(f -> list(f.getNpcs()).stream())
				.filter(n -> p.getSourceName() != null && p.getSourceName().equals(n.getName()))
				.map(NpcState::getNpcId).filter(Objects::nonNull).distinct()
				.max(Comparator.comparing(id -> Optional.ofNullable(catalog.ceiling(id)).orElse(Integer.MAX_VALUE)))
				.orElse(null);
		}
	}
}
