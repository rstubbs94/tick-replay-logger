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
	private static final int EARLY = 15, LATE = 35, NEARBY_TILES = 10, LAUNCH_CYCLES = 60, VENOM = 1_000_000,
		CLICK_TICKS = 6;

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
		if (recording == null || recording.getEvents() == null)
		{
			return recording;
		}
		DamageResolver resolver = new DamageResolver(catalog);
		DeathRecordingV2 explained = resolver.explainPrayerLoss(recording);
		Map<Long, DamageRecord> resolved = resolver.resolve(explained);
		return RecordingProjection.apply(resolver.nameStatusOnsets(explained), resolved, catalog.getId());
	}

	/** Each hit on the player, with its source resolution and display fields, keyed by observation sequence. */
	Map<Long, DamageRecord> resolve(DeathRecordingV2 recording)
	{
		Window window = new Window(recording);
		Map<DamageRecord, Assignment> assigned = assignProjectiles(window);
		Map<DamageRecord, List<Candidate>> found = new LinkedHashMap<>();
		for (DamageRecord hit : window.hits)
		{
			found.put(hit,
				resolve(window, hit, assigned.getOrDefault(hit, new Assignment(Collections.emptyList(), true))));
		}
		Map<Long, DamageRecord> out = new LinkedHashMap<>();
		found.forEach((hit, candidates) ->
		{
			String status = catalog.getStatuses().get(hit.getHitsplatType());
			Integer onset = status == null ? null : statusOnset(window, hit.getImpactTick(), "Venom".equals(status));
			String from = onset == null ? null : applicator(found, onset);
			if (from != null)
			{
				applied.put(onset, from);
				candidates = List
					.of(new Candidate(status + " from " + from, null, null, true, "STATUS_APPLIED:" + onset));
			}
			if (hit.getObservationSequence() != null)
			{
				out.put(hit.getObservationSequence(), describe(hit, candidates));
			}
		});
		return out;
	}

	/**
	 * A poison- or venom-coloured splat while the recorded counter shows no such status, this tick and the one
	 * before, is a one-off hit in that colour (nettles, some attacks), not a poison tick.
	 */
	private static boolean oneOffColouredHit(Window w, DamageRecord hit, String status)
	{
		boolean venom = "Venom".equals(status);
		if (!venom && !"Poison".equals(status))
		{
			return false;
		}
		WorldSnapshot now = w.frames.get(hit.getImpactTick()), before = w.frames.get(hit.getImpactTick() - 1);
		return now != null && before != null && Boolean.FALSE.equals(poisoned(now, venom))
			&& Boolean.FALSE.equals(poisoned(before, venom));
	}

	/** Poison or venom start ticks resolved in this recording, with what hit the player then. */
	private final Map<Integer, String> applied = new HashMap<>();

	/**
	 * The tick this poison or venom began: the player's poison counter turned positive (venom from 1,000,000)
	 * with nothing in between clearing it. Null when it began before the recording or is not recorded.
	 */
	private static Integer statusOnset(Window w, int tick, boolean venom)
	{
		for (int t = tick; w.frames.containsKey(t - 1); t--)
		{
			Boolean now = poisoned(w.frames.get(t), venom), before = poisoned(w.frames.get(t - 1), venom);
			if (Boolean.FALSE.equals(now))
			{
				return null;
			}
			if (Boolean.TRUE.equals(now) && Boolean.FALSE.equals(before))
			{
				return t;
			}
		}
		return null;
	}

	private static Boolean poisoned(WorldSnapshot frame, boolean venom)
	{
		PlayerState p = frame.getPlayer();
		return p == null || !Boolean.TRUE.equals(p.getPoisonValueKnown()) ? null
			: venom ? p.getPoisonValue() >= VENOM : p.getPoisonValue() > 0;
	}

	/** Whatever hit the player on the tick the status began (or the tick before); several are all named. */
	private static String applicator(Map<DamageRecord, List<Candidate>> found, int onset)
	{
		Set<String> names = new LinkedHashSet<>();
		found.forEach((hit, candidates) ->
		{
			if (hit.getImpactTick() >= onset - 1 && hit.getImpactTick() <= onset)
			{
				candidates.stream().filter(c -> !c.evidence.startsWith("STATUS")).forEach(c -> names.add(c.name));
			}
		});
		return names.isEmpty() ? null : String.join(" or ", names);
	}

	/** The "Poisoned"/"Venomed" effect at a resolved start tick names what applied it. */
	DeathRecordingV2 nameStatusOnsets(DeathRecordingV2 recording)
	{
		if (applied.isEmpty() || recording.getEvents().getEffectEvents() == null)
		{
			return recording;
		}
		List<EffectEvent> effects = recording.getEvents().getEffectEvents().stream()
			.map(
				e -> e == null || !"dot".equals(e.getType()) || !applied.containsKey(e.getStartTick()) ? e
					: e.toBuilder().sourceName(applied.get(e.getStartTick()))
						.journalNote(e.getJournalNote() == null ? null
							: e.getJournalNote() + " by " + applied.get(e.getStartTick()))
						.build())
			.collect(Collectors.toList());
		return recording.toBuilder().events(recording.getEvents().toBuilder().effectEvents(effects).build()).build();
	}

	/** Fills the journal and display fields from the resolution; the recorded facts stay unchanged. */
	private DamageRecord describe(DamageRecord hit, List<Candidate> found)
	{
		SourceResolution resolution = result(found);
		Candidate only = "IDENTIFIED".equals(resolution.getStatus()) ? found.get(0) : null;
		String colour = catalog.getStatuses().get(hit.getHitsplatType());
		String status = only != null && only.evidence.startsWith("STATUS") ? colour : null, style = only == null ? null
			: only.evidence.startsWith("PROJECTILE:")
				? catalog.getProjectileStyles().get(Integer.parseInt(only.evidence.substring(11)))
				: only.evidence.startsWith("ATTACK_TIMING:")
					? catalog.getAnimationStyles().get(Integer.parseInt(only.evidence.substring(14)))
					: null;
		String label = SourceResolutionLabels.label(resolution);
		return hit.toBuilder().sourceResolution(resolution).attackerName(label == null ? "Unknown" : label)
			.attackerRef(only == null ? null : only.actorRef).attackerNpcId(only == null ? null : only.npcId)
			.attribution(status != null ? "ENVIRONMENTAL" : only != null ? "OBSERVED" : "UNKNOWN")
			.attributionEvidence(status != null ? "STATUS_HITSPLAT"
				: only != null ? only.evidence
					: found.isEmpty() ? "UNRESOLVED_OBSERVATIONS" : "MULTIPLE_POSSIBLE_DAMAGE_SOURCES")
			.damageType(colour != null ? colour.toLowerCase(Locale.ROOT) : style != null ? style : hit.getDamageType())
			.build();
	}

	/**
	 * A prayer switched off without the player's click, while they still had prayer points, is explained by the
	 * projectile that landed on them that tick. A projectile the cache names as a prayer attack is stated as the
	 * cause; any other names the attack that landed. Several sources, or none, leave the original wording.
	 */
	DeathRecordingV2 explainPrayerLoss(DeathRecordingV2 recording)
	{
		DeathRecordingV2.Events events = recording.getEvents();
		Window w = new Window(recording);
		Map<Integer, String[]> causes = new HashMap<>();
		Set<String> clicked = list(events.getPlayerActions()).stream()
			.filter(
				a -> a != null && "PRAYER_DEACTIVATED".equals(a.getType()) && "INPUT".equals(a.getObservationKind()))
			.map(a -> a.getTick() + ":" + prayerName(a)).collect(Collectors.toSet());
		Set<String> duplicates = new HashSet<>();
		List<DeathRecordingV2.Events.PlayerActionRecord> actions = new ArrayList<>();
		for (DeathRecordingV2.Events.PlayerActionRecord action : list(events.getPlayerActions()))
		{
			if (action != null && "DERIVED".equals(action.getObservationKind())
				&& clicked.contains(action.getTick() + ":" + prayerName(action)))
			{
				duplicates.add(action.getTick() + ":" + prayerName(action));
				continue;
			}
			String[] cause = action != null && "PRAYER_DEACTIVATED".equals(action.getType())
				&& action.getDetail() != null && action.getDetail().endsWith("cause not established)")
					? prayerCause(w, action.getTick())
					: null;
			if (cause != null)
			{
				causes.put(action.getTick(), cause);
				action = action.toBuilder().detail(action.getTarget() + " (" + cause[1] + ")").build();
			}
			actions.add(action);
		}
		if (causes.isEmpty() && duplicates.isEmpty())
		{
			return recording;
		}
		List<EffectEvent> effects = list(events.getEffectEvents()).stream()
			.filter(e -> e == null || !"prayer_state_change".equals(e.getType()) || e.getMagnitude() == null
				|| !duplicates.contains(
					e.getStartTick() + ":" + e.getMagnitude().replaceAll(" \\(.*", "").toLowerCase(Locale.ROOT)))
			.map(e -> e == null || !"prayer_state_change".equals(e.getType()) || !causes.containsKey(e.getStartTick())
				? e
				: e.toBuilder().sourceName(causes.get(e.getStartTick())[0]).evidence(causes.get(e.getStartTick())[2])
					.build())
			.collect(Collectors.toList());
		return recording.toBuilder().events(events.toBuilder().playerActions(actions).effectEvents(effects).build())
			.build();
	}

	/** The prayer named by a switch-off record ("Deactivated Piety" or "Piety (...)"), lower case. */
	private static String prayerName(DeathRecordingV2.Events.PlayerActionRecord action)
	{
		String name = action.getTarget() != null ? action.getTarget()
			: String.valueOf(action.getDetail()).replaceFirst("^Deactivated ", "").replaceAll(" \\(.*", "");
		return name.trim().toLowerCase(Locale.ROOT);
	}

	/** {source name, journal wording, evidence} for the projectile that landed on the player in this tick. */
	private String[] prayerCause(Window w, int tick)
	{
		WorldSnapshot frame = w.frames.get(tick);
		if (frame == null)
		{
			return null;
		}
		// Same landing window as damage: the visual end can trail the registered impact by about a tick.
		List<ProjectileState> landed = w.projectiles
			.stream().filter(p -> "player".equals(p.getTargetRef()) && p.getEndCycle() != null
				&& lands(p, frame.getGameCycle()) && SourceResolutionLabels.usableName(p.getSourceName()) != null)
			.collect(Collectors.toList());
		List<ProjectileState> prayer = landed.stream()
			.filter(p -> catalog.getPrayerDisablers().contains(p.getProjectileId())).collect(Collectors.toList());
		Set<String> sources = (prayer.isEmpty() ? landed : prayer).stream().map(ProjectileState::getSourceName)
			.collect(Collectors.toSet());
		if (sources.size() != 1)
		{
			return null;
		}
		String source = sources.iterator().next();
		return prayer.isEmpty() ? new String[]
		{ source, "deactivated as " + source + "'s attack landed", "PROJECTILE_LANDED_SAME_TICK" } : new String[]
		{ source, "disabled by " + source + "'s prayer attack", "PRAYER_ATTACK_PROJECTILE" };
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
		if (status != null && !oneOffColouredHit(w, hit, status))
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
			if (catalog.canDeal(npc, amount, catalog.getProjectileStyles().get(p.getProjectileId())))
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
		List<Candidate> actions = ownActions(w, tick);
		List<Candidate> items = actions.stream().filter(c -> "SELF_ITEM".equals(c.evidence))
			.collect(Collectors.toList());
		List<Candidate> peers = distinct(Stream.of(projectiles, strong(area, true), attacks, items)
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
		// Nothing attacked the player: their own click (pickpocket, nettles, an obstacle) is next.
		List<Candidate> clicks = actions.stream().filter(c -> !"SELF_ITEM".equals(c.evidence))
			.collect(Collectors.toList());
		if (!clicks.isEmpty())
		{
			return possibleIfSeveral(distinct(clicks));
		}
		List<Candidate> weak = player == null ? area : distinct(concat(area, nearbyAttacks(w, tick, amount, player)));
		List<Candidate> rest = weak.isEmpty() ? messages(w, tick) : weak;
		// A status-coloured splat with nothing else to explain it is still most likely that status.
		return rest.isEmpty() && status != null ? List.of(new Candidate(status, null, null, true, "STATUS")) : rest;
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
			// Compare with where the player stood in this same frame; running moves them every tick.
			ObservedActorState here = w.located(frame) ? frame.getPlayer() : player;
			for (HazardState h : list(frame.getHazards()))
			{
				int width = span(h.getMinSceneX(), h.getMaxSceneX()), height = span(h.getMinSceneY(), h.getMaxSceneY());
				if (h.getPlane() != here.getPlane()
					|| distance(here, h.getWorldX(), h.getWorldY(), Math.max(width, height)) != 0)
				{
					continue;
				}
				String surface = catalog.getSurfaces().get(h.getId());
				String graphic = "graphicsObject".equals(h.getKind()) ? catalog.getHazardGraphics().get(h.getId())
					: null;
				if (surface != null)
				{
					out.add(new Candidate(surface, null, null, true, "SURFACE:" + h.getId()));
				}
				else if (graphic != null)
				{
					Integer ends = w.graphicEnds.get(graphicKey(h));
					boolean lands = ends != null && ends >= hit.getImpactTick() && ends <= hit.getImpactTick() + 2;
					out.add(new Candidate(graphic, null, null, lands, "GRAPHIC_ON_TILE:" + h.getId()));
				}
			}
			for (NpcState npc : list(frame.getNpcs()))
			{
				String contact = catalog.getContactNpcs().get(npc.getNpcId());
				if (contact != null && distance(here, npc.getWorldX(), npc.getWorldY(), size(npc)) == 0)
				{
					out.add(new Candidate(contact, npc.getActorRef(), npc.getNpcId(), true, "CONTACT_NPC")
						.at(frame.getSceneKey(), npc.getWorldViewId(), npc.getPlane()));
				}
			}
		}
		Integer cycle = hit.getObservedCycle();
		for (ProjectileState p : w.projectiles)
		{
			if (cycle == null || "player".equals(p.getTargetRef()) || !lands(p, cycle) || p.getEndWorldX() == null
				|| !catalog.canDeal(w.sourceNpcId(p), hit.getAmount()))
			{
				continue;
			}
			// Landing exactly where the player stood (this tick or the one before) is certain; next to them, possible.
			int d = w.closestTo(hit.getImpactTick(), player, p.getEndWorldX(), p.getEndWorldY());
			String graphic = catalog.getHazardGraphics().get(p.getProjectileId());
			if (d <= 1 && (graphic != null || SourceResolutionLabels.usableName(p.getSourceName()) != null))
			{
				out.add(new Candidate(
					graphic != null ? graphic.replaceAll("(?i)\\s+travel$", "")
						: p.getSourceName() == null ? "Area attack" : p.getSourceName() + " (area attack)",
					p.getSourceRef(), w.sourceNpcId(p), d == 0, "TILE_PROJECTILE:" + p.getProjectileId())
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
			int d = w.closest(animation.getTick(), player, npc);
			if (hitDelays(d).contains(tick - animation.getTick()) && !landedEarlier(w, animation.getTick(), d, tick)
				&& catalog.canDeal(npc.getNpcId(), amount, catalog.getAnimationStyles().get(id)))
			{
				out.add(
					new Candidate(name(npc), npc.getActorRef(), npc.getNpcId(), known || d <= 1, "ATTACK_TIMING:" + id)
						.at(w.sceneKey(animation.getTick()), npc.getWorldViewId(), npc.getPlane()));
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
		return cycle != null && w.projectiles.stream()
			.anyMatch(p -> p.getStartCycle() != null && p.getStartCycle() >= cycle
				&& p.getStartCycle() - cycle <= LAUNCH_CYCLES
				&& (p.getSourceRef() != null ? p.getSourceRef().equals(npc.getActorRef())
					: npc.getName().equals(p.getSourceName()) && w.uniqueNames.contains(npc.getName())));
	}

	private static String graphicKey(HazardState h)
	{
		return h.getId() + ":" + h.getWorldX() + ":" + h.getWorldY() + ":" + h.getStartCycle();
	}

	/**
	 * One attack lands on one tick (several splats on that tick are fine): an animation whose attack already
	 * matched an earlier hit cannot explain a later one.
	 */
	private boolean landedEarlier(Window w, int animationTick, int distance, int tick)
	{
		Set<Integer> delays = hitDelays(distance);
		return w.hits.stream()
			.anyMatch(h -> h.getImpactTick() >= animationTick && h.getImpactTick() < tick
				&& delays.contains(h.getImpactTick() - animationTick)
				&& !catalog.getStatuses().containsKey(h.getHitsplatType()));
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
			if (npc != null && named(npc) && !launchedProjectile(w, animation, npc) && age >= 0 && age <= 2
				&& npc.getPlane() == player.getPlane()
				&& catalog.getAttackAnimations().contains(number(animation.getData().get("animationId")))
				&& w.closest(animation.getTick(), player, npc) <= NEARBY_TILES && catalog.canDeal(npc.getNpcId(),
					amount, catalog.getAnimationStyles().get(number(animation.getData().get("animationId")))))
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
		List<String> said = messageTexts(w, tick),
			harm = said.stream().filter(m -> HARM.matcher(m).find()).collect(Collectors.toList());
		boolean confirmed = !said.isEmpty();
		// With a confirming message, walking to the object first is allowed: the latest click of the last few ticks.
		int latest = !confirmed ? tick - 1
			: w.clicks.stream()
				.filter(c -> c.getTick() <= tick && c.getTick() >= tick - CLICK_TICKS && interaction(c.getData()))
				.mapToInt(ObservedEvent::getTick).max().orElse(tick - 1);
		List<Candidate> out = new ArrayList<>();
		for (ObservedEvent click : w.clicks)
		{
			int age = tick - click.getTick();
			if (confirmed && click.getTick() >= latest && age >= 0 && interaction(click.getData()))
			{
				age = 0;
			}
			Map<String, Object> data = click.getData();
			String option = text(data.get("option")), target = text(data.get("target"));
			Object item = data.get("itemId");
			String selfItem = item instanceof Number ? catalog.getSelfDamageItems().get(((Number) item).intValue())
				: null;
			if (age < 0 || age > 1 || option == null)
			{
				continue;
			}
			if (selfItem != null)
			{
				out.add(new Candidate(selfItem, null, null, true, "SELF_ITEM"));
			}
			else if (target != null && interaction(data))
			{
				// A harm message naming something else ("the Wintertodt's magic hits you") explains the hit instead.
				String name = target.replaceAll("\\s*\\(level-\\d+\\)", "").trim();
				if (!harm.isEmpty() && harm.stream().noneMatch(m -> mentions(m, name)))
				{
					continue;
				}
				NpcState npc = w.npc(click.getTick(), click.getRelatedActorRef());
				out.add(new Candidate(name, npc == null ? null : npc.getActorRef(), npc == null ? null : npc.getNpcId(),
					confirmed, "OWN_ACTION:" + option).at(npc == null ? null : w.sceneKey(click.getTick()),
						npc == null ? null : npc.getWorldViewId(), npc == null ? null : npc.getPlane()));
			}
		}
		return out;
	}

	/** A click on scenery or an NPC that is not walking, attacking or examining. */
	private static boolean interaction(Map<String, Object> data)
	{
		String option = text(data.get("option"));
		return option != null && text(data.get("target")) != null
			&& !Set.of("Walk here", "Attack", "Cancel", "Examine").contains(option)
			&& String.valueOf(data.get("menuAction")).matches(".*(GAME_OBJECT|NPC_).*");
	}

	/**
	 * Game messages at the hit, as the last resort: the message describes the cause ("The brazier is broken and
	 * shrapnel damages you."). Messages that speak of harm are preferred over routine ones on the same tick.
	 */
	private List<Candidate> messages(Window w, int tick)
	{
		List<String> all = messageTexts(w, tick);
		List<String> harm = all.stream().filter(m -> HARM.matcher(m).find()).collect(Collectors.toList());
		return (harm.isEmpty() ? all : harm).stream().map(m -> new Candidate(m, null, null, false, "MESSAGE"))
			.collect(Collectors.toList());
	}

	private static List<String> messageTexts(Window w, int tick)
	{
		return w.messages.stream().filter(m -> tick - m.getTick() >= 0 && tick - m.getTick() <= 1)
			.map(m -> text(m.getData().get("message"))).filter(Objects::nonNull)
			.map(m -> m.replaceAll("<[^>]+>", "").replaceAll("[.!]+$", "").trim()).filter(m -> !m.isEmpty()).distinct()
			.collect(Collectors.toList());
	}

	/** The message names the clicked thing ("stung by the nettles", "the brazier is broken"). */
	private static boolean mentions(String message, String target)
	{
		String lower = message.toLowerCase(Locale.ROOT);
		return Arrays.stream(target.toLowerCase(Locale.ROOT).split("[^a-z]+"))
			.anyMatch(word -> word.length() >= 4 && lower.contains(word));
	}

	private static final java.util.regex.Pattern HARM = java.util.regex.Pattern.compile(
		"(?i)damag|hits? you|hurt|injur|burn|sting|poison|venom|cold|freez|shock|explo|shrapnel|bones|ouch|\\bow\\b");

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
			byCycle.keySet().stream().filter(c -> lands(p, c))
				.min(Comparator.comparingInt(c -> Math.abs(p.getEndCycle() - c)))
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
		int[] bestScore =
		{ -1, -1 };
		permute(hits, projectiles, new ArrayList<>(), new HashSet<>(), choice ->
		{
			int matched = 0, forced = 0;
			for (int i = 0; i < hits.size(); i++)
			{
				ProjectileState p = choice.get(i);
				if (p != null && !catalog.canDeal(w.sourceNpcId(p), hits.get(i).getAmount(),
					catalog.getProjectileStyles().get(p.getProjectileId())))
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
	private static void permute(List<DamageRecord> hits, List<ProjectileState> projectiles,
		List<ProjectileState> chosen, Set<ProjectileState> used,
		java.util.function.Consumer<List<ProjectileState>> visit)
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
		List<SourceResolution.Candidate> candidates = found.stream()
			.map(c -> SourceResolution.Candidate.builder().key(c.evidence + ":" + c.key()).name(c.name)
				.familyKey(c.family()).actorRef(actors ? c.actorRef : null).npcId(c.npcId)
				.sceneKey(actors ? c.sceneKey : null).worldViewId(actors ? c.worldViewId : null)
				.plane(actors ? c.plane : null).strength(identified ? "STRONG" : "POSSIBLE")
				.evidence(List.of(c.evidence)).build())
			.collect(Collectors.toList());
		return SourceResolution.builder().version(1)
			.status(found.isEmpty() ? "UNIDENTIFIED" : identified ? "IDENTIFIED" : "ALTERNATIVES")
			.identityLevel(found.isEmpty() ? "NONE" : actors ? "ACTOR" : "FAMILY").candidates(candidates)
			.unidentifiedPossible(!identified).catalogId(catalog.getId()).method(METHOD).build();
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
		/** Last tick each graphics object was seen; a falling or landing effect resolves as it ends. */
		final Map<String, Integer> graphicEnds = new HashMap<>();

		Window(DeathRecordingV2 recording)
		{
			Map<Object, ProjectileState> latest = new LinkedHashMap<>();
			for (WorldSnapshot frame : list(recording.getTicks()))
			{
				frames.put(frame.getTick(), frame);
				list(frame.getHazards()).stream().filter(h -> "graphicsObject".equals(h.getKind()))
					.forEach(h -> graphicEnds.merge(graphicKey(h), frame.getTick(), Math::max));
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
			hits = list(events.getDamageEvents()).stream()
				.filter(h -> "player".equals(h.getDefenderRef()) && !"DEALT".equals(h.getDirection())
					&& !"NPC_RECEIVED".equals(h.getDirection()))
				.sorted(
					Comparator.comparing((DamageRecord h) -> h.getObservedCycle() == null ? 0 : h.getObservedCycle())
						.thenComparing(h -> h.getObservationSequence() == null ? 0 : h.getObservationSequence()))
				.collect(Collectors.toList());
			// Hits whose HP effect is unknown (unlisted hitsplat types) are kept on their hitsplat observation.
			Set<Long> listed = hits.stream().map(DamageRecord::getObservationSequence).filter(Objects::nonNull)
				.collect(Collectors.toSet());
			for (ObservedEvent e : list(events.getObservations()))
			{
				Object impact = e.getData() == null ? null : e.getData().get("attributionContext");
				if ("HITSPLAT".equals(e.getType()) && impact instanceof DamageRecord
					&& "player".equals(((DamageRecord) impact).getDefenderRef())
					&& "RESOURCE_IMPACT".equals(((DamageRecord) impact).getDirection())
					&& listed.add(((DamageRecord) impact).getObservationSequence()))
				{
					hits.add((DamageRecord) impact);
				}
			}
			hits.sort(Comparator.comparing((DamageRecord h) -> h.getObservedCycle() == null ? 0 : h.getObservedCycle())
				.thenComparing(h -> h.getObservationSequence() == null ? 0 : h.getObservationSequence()));
			for (ObservedEvent e : list(events.getObservations()))
			{
				if ("ANIMATION_CHANGED".equals(e.getType()) && e.getActorRef() != null
					&& e.getActorRef().startsWith("npc") && e.getData() != null
					&& number(e.getData().get("animationId")) >= 0)
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
			for (int t : new int[]
			{ tick, tick - 1, tick + 1 })
			{
				WorldSnapshot frame = frames.get(t);
				if (frame != null && frame.getPlayer() != null
					&& Boolean.TRUE.equals(frame.getPlayer().getWorldPositionKnown()))
				{
					return frame.getPlayer();
				}
			}
			return null;
		}

		boolean located(WorldSnapshot frame)
		{
			return frame.getPlayer() != null && Boolean.TRUE.equals(frame.getPlayer().getWorldPositionKnown());
		}

		/** Distance to the NPC from where the player stood at the attack tick or the tick before (they may step away). */
		int closest(int tick, ObservedActorState fallback, NpcState npc)
		{
			return closest(tick, fallback, npc.getWorldX(), npc.getWorldY(), size(npc), npc.getPlane());
		}

		/** Distance to a landing tile from where the player stood this tick or the tick before. */
		int closestTo(int tick, ObservedActorState fallback, int x, int y)
		{
			return closest(tick, fallback, x, y, 1, fallback.getPlane());
		}

		private int closest(int tick, ObservedActorState fallback, int x, int y, int size, int plane)
		{
			int best = distance(fallback, x, y, size);
			for (int t : new int[]
			{ tick, tick - 1 })
			{
				WorldSnapshot frame = frames.get(t);
				if (frame != null && located(frame) && frame.getPlayer().getPlane() == plane)
				{
					best = Math.min(best, distance(frame.getPlayer(), x, y, size));
				}
			}
			return best;
		}

		String sceneKey(int tick)
		{
			WorldSnapshot frame = frames.get(tick);
			return frame == null ? null : frame.getSceneKey();
		}

		NpcState npc(int tick, String ref)
		{
			for (int t : new int[]
			{ tick, tick - 1, tick + 1 })
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
				.filter(n -> p.getSourceName() != null && p.getSourceName().equals(n.getName())).map(NpcState::getNpcId)
				.filter(Objects::nonNull).distinct()
				.max(Comparator.comparing(id -> Optional.ofNullable(catalog.ceiling(id)).orElse(Integer.MAX_VALUE)))
				.orElse(null);
		}
	}
}
