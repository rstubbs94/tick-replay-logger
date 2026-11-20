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

import com.tickreplaylogger.model.world.ObservedObjectState;
import java.util.*;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Reconcile exposed scene membership every settled snapshot; callbacks remain separate observations. */
public final class ObjectStateTracker
{
	private static final int MAX_OBJECTS = 32768;
	private final IdentityHashMap<TileObject, Entry> objects = new IdentityHashMap<>();
	private String sceneKey;
	private long sequence;
	private int dropped;
	private int failures;
	private int currentFailures, currentDropped, observedObjects, currentMetadataFailures;
	private boolean metadataFailuresObserved;
	private boolean membershipAvailable, membershipComplete;
	private final List<TileObject> enumerationOrder = new ArrayList<>();
	private Integer sampledPlane;
	private List<ObservedObjectState> previousSnapshot;

	private static class Entry
	{
		final long key;
		final String kind;
		final TileObject object;
		boolean dynamic;
		ObservedObjectState state;

		Entry(long key, String kind, TileObject object)
		{
			this.key = key;
			this.kind = kind;
			this.object = object;
		}
	}

	public void reset()
	{
		objects.clear();
		enumerationOrder.clear();
		sceneKey = null;
		sampledPlane = null;
		dropped = 0;
		failures = 0;
		currentFailures = 0;
		currentDropped = 0;
		observedObjects = 0;
		currentMetadataFailures = 0;
		metadataFailuresObserved = false;
		membershipAvailable = false;
		membershipComplete = false;
		previousSnapshot = null;
	}

	public void spawned(String kind, TileObject object)
	{
		if (object == null || objects.containsKey(object))
		{
			return;
		}
		if (objects.size() >= MAX_OBJECTS)
		{
			dropped++;
			currentDropped++;
			return;
		}
		objects.put(object, new Entry(++sequence, kind, object));
	}

	public void despawned(TileObject object)
	{
		objects.remove(object);
	}

	/** Identity registration alone is not a settled membership observation. */
	public Long reference(String kind, TileObject object)
	{
		spawned(kind, object);
		Entry entry = objects.get(object);
		return entry == null ? null : entry.key;
	}

	public void enterScene(String key)
	{
		if (sceneKey != null && !Objects.equals(sceneKey, key))
		{
			objects.clear();
			previousSnapshot = null;
			dropped = 0;
			failures = 0;
		}
		sceneKey = key;
	}

	public ObservedObjectState observation(Client client, String kind, TileObject object)
	{
		spawned(kind, object);
		Entry entry = objects.get(object);
		return entry == null ? null : read(client, entry);
	}

	public Map<String, Object> coverage()
	{
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("scope", "LOADED_SCENE_REPORTED_OBJECT_PLANE");
		info.put("plane", sampledPlane);
		info.put("objects", objects.size());
		info.put("observedObjects", observedObjects);
		info.put("dropped", dropped);
		info.put("failures", failures);
		info.put("currentDropped", currentDropped);
		info.put("currentFailures", currentFailures);
		if (metadataFailuresObserved)
		{
			info.put("currentMetadataFailures", currentMetadataFailures);
		}
		info.put("reconciledEverySnapshot", false);
		info.put("membershipEvidence", "SCENE_SEED_AND_LIFECYCLE_EVENTS");
		info.put("membershipStatus", !membershipAvailable ? "UNAVAILABLE"
			: membershipComplete ? "COMPLETE_EXPOSED_SCENE_SCAN" : "PARTIAL_EXPOSED_SCENE_SCAN");
		info.put("complete", membershipComplete && currentFailures == 0 && currentDropped == 0);
		return info;
	}

	private void failedRead()
	{
		failures++;
		currentFailures++;
	}

	private IdentityHashMap<TileObject, String> enumerate(SceneCaptureTraversal traversal, int plane)
	{
		enumerationOrder.clear();
		if (traversal.arrays == null || plane < 0)
		{
			failedRead();
			return null;
		}
		membershipAvailable = true;
		IdentityHashMap<TileObject, String> found = new IdentityHashMap<>();
		for (SceneCaptureTraversal.ObjectRow row : traversal.objects(plane))
		{
			if (found.size() >= MAX_OBJECTS)
			{
				dropped++;
				currentDropped++;
				continue;
			}
			found.put(row.object, row.kind);
			enumerationOrder.add(row.object);
		}
		for (int i = 0; i < traversal.objectFailures(); i++)
		{
			failedRead();
		}
		return found;
	}

	public List<ObservedObjectState> snapshot(Client client, String key)
	{
		return snapshot(client, key, WorldViews.active(client));
	}

	public List<ObservedObjectState> snapshot(Client client, String key, WorldView active)
	{
		return snapshot(client, key, active, active == null ? client.getPlane() : active.getPlane());
	}

	public List<ObservedObjectState> snapshot(Client client, String key, WorldView active, int plane)
	{
		return snapshot(client, key, active, plane, SceneCaptureTraversal.capture(client, active));
	}

	List<ObservedObjectState> snapshot(Client client, String key, WorldView active, int plane,
		SceneCaptureTraversal traversal)
	{
		if (traversal == null || !traversal.matches(client, active))
		{
			throw new IllegalArgumentException("Mismatched scene traversal");
		}
		sampledPlane = plane;
		currentFailures = 0;
		currentDropped = 0;
		observedObjects = 0;
		currentMetadataFailures = 0;
		membershipAvailable = false;
		membershipComplete = false;
		enterScene(key);
		IdentityHashMap<TileObject, String> found;
		try
		{
			found = enumerate(traversal, plane);
		}
		catch (RuntimeException error)
		{
			failedRead();
			return null;
		}
		if (found == null)
		{
			return null;
		} // Unavailable scene is not a current observation of cached membership.
		membershipComplete = currentFailures == 0 && currentDropped == 0;
		if (membershipComplete)
		{
			objects.keySet().removeIf(object -> !found.containsKey(object));
		}
		for (TileObject observed : enumerationOrder)
		{
			spawned(found.get(observed), observed);
		}
		if (currentDropped > 0)
		{
			membershipComplete = false;
		}
		List<ObservedObjectState> out = new ArrayList<>();
		for (TileObject object : found.keySet())
		{
			Entry entry = objects.get(object);
			if (entry == null)
			{
				continue;
			}
			try
			{
				// A stationary object can acquire a new renderable/config without a lifecycle callback.
				ObservedObjectState current = read(client, entry);
				if (current.getObservationIssues() != null)
				{
					failedRead();
					currentMetadataFailures++;
					metadataFailuresObserved = true;
				}
				if (!current.equals(entry.state))
				{
					entry.state = current;
				}
				out.add(entry.state);
			}
			catch (RuntimeException error)
			{
				failedRead();
			} // One failed object cannot erase its neighbours.
		}
		observedObjects = out.size();
		out.sort(Comparator.comparingLong(ObservedObjectState::getObjectKey));
		if (previousSnapshot != null && out.equals(previousSnapshot))
		{
			return previousSnapshot;
		}
		previousSnapshot = Collections.unmodifiableList(out);
		return previousSnapshot;
	}

	private ObservedObjectState read(Client client, Entry entry)
	{
		TileObject object = entry.object;
		// The native object ID and plane identify this current row; optional metadata is independent.
		int baseId = object.getId(), plane = object.getPlane();
		SceneFieldObservation fields = new SceneFieldObservation();
		ObjectComposition base = fields.read("definition", () -> client.getObjectDefinition(baseId));
		ObjectComposition effective = base;
		String evidence = base == null ? "DEFINITION_UNAVAILABLE" : "OBJECT_DEFINITION";
		if (base != null)
		{
			int[] impostors = fields.read("impostorIds", base::getImpostorIds);
			if (!fields.known("impostorIds"))
			{
				effective = null;
				evidence = "TRANSFORM_CONTEXT_UNAVAILABLE";
			}
			else if (impostors != null)
			{
				entry.dynamic = true;
				effective = fields.read("effectiveDefinition", base::getImpostor);
				evidence = effective == null ? "TRANSFORM_UNAVAILABLE" : "TRANSFORMED_OBJECT_DEFINITION";
			}
		}
		ObjectComposition currentDefinition = effective;
		Integer id = currentDefinition == null ? null : fields.read("id", currentDefinition::getId);
		String name = currentDefinition == null ? null : fields.read("name", currentDefinition::getName);
		LocalPoint local = fields.read("localLocation", object::getLocalLocation);
		WorldPoint world = fields.read("worldLocation", object::getWorldLocation);
		WorldView view = fields.read("worldView", object::getWorldView);
		Integer viewId = view == null ? null : fields.read("worldViewId", view::getId);
		Point min = object instanceof GameObject
			? fields.read("minSceneLocation", ((GameObject) object)::getSceneMinLocation)
			: null;
		Point max = object instanceof GameObject
			? fields.read("maxSceneLocation", ((GameObject) object)::getSceneMaxLocation)
			: null;
		boolean single = object instanceof GroundObject;
		List<Renderable> models = new ArrayList<>();
		if (object instanceof GameObject)
		{
			models.add(fields.read("renderable", ((GameObject) object)::getRenderable));
		}
		if (object instanceof GroundObject)
		{
			models.add(fields.read("renderable", ((GroundObject) object)::getRenderable));
		}
		if (object instanceof WallObject)
		{
			models.add(fields.read("renderable1", ((WallObject) object)::getRenderable1));
			models.add(fields.read("renderable2", ((WallObject) object)::getRenderable2));
		}
		if (object instanceof DecorativeObject)
		{
			models.add(fields.read("renderable", ((DecorativeObject) object)::getRenderable));
			models.add(fields.read("renderable2", ((DecorativeObject) object)::getRenderable2));
		}
		List<Integer> animations = new ArrayList<>(), frames = new ArrayList<>();
		for (int index = 0; index < models.size(); index++)
		{
			if (models.get(index) instanceof DynamicObject)
			{
				entry.dynamic = true;
				DynamicObject dynamic = (DynamicObject) models.get(index);
				String prefix = "model" + index + ".";
				Animation animation = fields.read(prefix + "animation", dynamic::getAnimation);
				animations.add(animation == null ? (fields.known(prefix + "animation") ? -1 : null)
					: fields.read(prefix + "animationId", animation::getId));
				frames.add(fields.read(prefix + "animationFrame", dynamic::getAnimFrame));
			}
		}
		Integer varbit = base == null ? null : fields.read("varbitId", base::getVarbitId),
			varp = base == null ? null : fields.read("varpId", base::getVarPlayerId);
		Integer control = null;
		if (varbit != null && varbit >= 0)
		{
			control = fields.read("controlValue", () -> client.getVarbitValue(varbit));
		}
		else if (varbit != null && varp != null && varp >= 0)
		{
			control = fields.read("controlValue", () -> client.getVarpValue(varp));
		}
		Integer orientation = null, modelOrientation = null, orientationA = null, orientationB = null, config = null,
			offsetX = null, offsetY = null, offsetX2 = null, offsetY2 = null;
		if (object instanceof GameObject)
		{
			GameObject g = (GameObject) object;
			orientation = fields.read("orientation", g::getOrientation);
			modelOrientation = fields.read("modelOrientation", g::getModelOrientation);
			config = fields.read("config", g::getConfig);
		}
		if (object instanceof GroundObject)
		{
			config = fields.read("config", ((GroundObject) object)::getConfig);
		}
		if (object instanceof WallObject)
		{
			WallObject wall = (WallObject) object;
			orientationA = fields.read("orientationA", wall::getOrientationA);
			orientationB = fields.read("orientationB", wall::getOrientationB);
			config = fields.read("config", wall::getConfig);
		}
		if (object instanceof DecorativeObject)
		{
			DecorativeObject d = (DecorativeObject) object;
			config = fields.read("config", d::getConfig);
			offsetX = fields.read("offsetX", d::getXOffset);
			offsetY = fields.read("offsetY", d::getYOffset);
			offsetX2 = fields.read("offsetX2", d::getXOffset2);
			offsetY2 = fields.read("offsetY2", d::getYOffset2);
		}
		return ObservedObjectState.builder().objectKey(entry.key).kind(entry.kind).baseId(baseId).id(id).name(name)
			.definitionEvidence(evidence).plane(plane).worldViewId(viewId).localX(local == null ? null : local.getX())
			.localY(local == null ? null : local.getY()).worldX(world == null ? null : world.getX())
			.worldY(world == null ? null : world.getY())
			.minSceneX(min != null ? Integer.valueOf(min.getX())
				: single && local != null ? Integer.valueOf(local.getSceneX()) : null)
			.minSceneY(min != null ? Integer.valueOf(min.getY())
				: single && local != null ? Integer.valueOf(local.getSceneY()) : null)
			.maxSceneX(max != null ? Integer.valueOf(max.getX())
				: single && local != null ? Integer.valueOf(local.getSceneX()) : null)
			.maxSceneY(max != null ? Integer.valueOf(max.getY())
				: single && local != null ? Integer.valueOf(local.getSceneY()) : null)
			.varbitId(varbit == null || varbit < 0 ? null : varbit).varpId(varp == null || varp < 0 ? null : varp)
			.controlValue(control).orientation(orientation).modelOrientation(modelOrientation)
			.orientationA(orientationA).orientationB(orientationB).config(config).offsetX(offsetX).offsetY(offsetY)
			.offsetX2(offsetX2).offsetY2(offsetY2).animationIds(animations).animationFrames(frames)
			.fieldAvailability(fields.availability()).observationIssues(fields.issues()).nullFields(fields.nulls())
			.build();
	}
}
