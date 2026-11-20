/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent readers must retain their raw output, failure isolation and API call ordering. */
public class ActorSamplingParityTest
{
    private final Gson gson = new Gson();

    @Test
    public void callbackSamplesMatchTheFrozenReadersIncludingUnavailableFieldsAndRepeatedAreaReads()
    {
        for (Class<? extends Actor> kind : Arrays.asList(NPC.class, Player.class, Actor.class))
        {
            for (String unavailable : Arrays.asList("", "getWorldLocation", "getLocalLocation", "getWorldView",
                "getAnimation", "getAnimationFrame", "getHealthRatio", "getWorldArea", "getOverheadText"))
            {
                Sample before = new Sample(kind, unavailable), after = new Sample(kind, unavailable);
                Map<String, Object> expected = LegacyActorCallbackObservation22.capture(before.actor, null, null, true, actor -> "recorded");
                Map<String, Object> actual = ActorCallbackObservation.capture(after.actor, null, null, true, actor -> "recorded");
                String context = kind.getSimpleName() + ":" + unavailable;
                assertEquals(context, gson.toJsonTree(expected), gson.toJsonTree(actual));
                assertEquals(context, before.calls, after.calls);
                if (kind == NPC.class)
                    assertEquals(context, 2, Collections.frequency(after.calls, "actor.getWorldArea"));
            }
        }
    }

    @Test
    public void snapshotNpcSamplesMatchTheFrozenReaderWithoutReusingCallbackSamples()
    {
        for (String unavailable : Arrays.asList("", "getWorldView", "getName", "getAnimationFrame", "getWorldArea"))
        {
            Sample before = new Sample(NPC.class, unavailable), after = new Sample(NPC.class, unavailable);
            WorldPoint world = new WorldPoint(3201, 3202, 0);
            LegacyNpcSnapshotObservation22 old = new LegacyNpcSnapshotObservation22((NPC) before.actor, world, null);
            NpcSnapshotObservation current = new NpcSnapshotObservation((NPC) after.actor, world, null);
            assertEquals(unavailable, gson.toJsonTree(old.state.build()), gson.toJsonTree(current.state.build()));
            assertEquals(unavailable, old.issues, current.issues);
            assertEquals(unavailable, before.calls, after.calls);
            assertEquals(1, Collections.frequency(after.calls, "actor.getWorldArea"));
        }
    }

    @Test
    public void disabledOtherPlayerCaptureReadsNoActorMetadata()
    {
        Sample before = new Sample(Player.class, ""), after = new Sample(Player.class, "");
        assertTrue(LegacyActorCallbackObservation22.capture(before.actor, null, null, false, actor -> "private").isEmpty());
        assertTrue(ActorCallbackObservation.capture(after.actor, null, null, false, actor -> "private").isEmpty());
        assertTrue(before.calls.isEmpty());
        assertTrue(after.calls.isEmpty());
    }

    private static final class Sample
    {
        final List<String> calls = new ArrayList<>();
        final Actor actor;
        int areaRead;

        Sample(Class<? extends Actor> kind, String unavailable)
        {
            WorldView view = stub(WorldView.class, "view", unavailable);
            NPCComposition composition = stub(NPCComposition.class, "definition", unavailable);
            actor = (Actor) Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[] { kind }, (proxy, method, args) ->
            {
                String name = method.getName();
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == args[0];
                calls.add("actor." + name);
                if (name.equals(unavailable)) throw new IllegalStateException("unavailable");
                switch (name)
                {
                    case "getWorldLocation": return new WorldPoint(3201, 3202, 0);
                    case "getLocalLocation": return new LocalPoint(64, 192);
                    case "getWorldView": return view;
                    case "getWorldArea": return new WorldArea(3200 + ++areaRead, 3202, areaRead, 2, 0);
                    case "getComposition": return composition;
                    case "getName": return "<col=ff0000>Observed actor</col>";
                    case "getCombatLevel": return 77;
                    case "getAnimation": return 900001;
                    case "getAnimationFrame": return 3;
                    case "getPoseAnimation": return 900002;
                    case "getPoseAnimationFrame": return 4;
                    case "getOverheadText": return "Observed speech";
                    case "getOverheadCycle": return 19;
                    case "getHealthRatio": return 7;
                    case "getHealthScale": return 10;
                    case "getGraphic": return -1;
                    default: return zero(method.getReturnType());
                }
            });
        }

        private <T> T stub(Class<T> kind, String prefix, String unavailable)
        {
            return kind.cast(Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[] { kind }, (proxy, method, args) ->
            {
                String name = method.getName();
                calls.add(prefix + "." + name);
                if (name.equals(unavailable)) throw new IllegalStateException("unavailable");
                if (name.equals("getId")) return prefix.equals("definition") ? 900003 : 0;
                if (name.equals("getSize")) return 2;
                if (name.equals("getName")) return "Observed definition";
                if (name.equals("getCombatLevel")) return 77;
                return zero(method.getReturnType());
            }));
        }

        private static Object zero(Class<?> type)
        {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            return null;
        }
    }
}
