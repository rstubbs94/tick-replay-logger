/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedActorStateTest
{
    @Test
    public void inheritedFieldsKeepTheEntireFlatWireContractAndUnknownValues() throws Exception
    {
        Gson gson = new GsonBuilder().serializeNulls().create();
        JsonObject fields;
        try (InputStreamReader input = new InputStreamReader(getClass().getResourceAsStream("/actor-state-flat-fields.json"), StandardCharsets.UTF_8))
        {
            fields = gson.fromJson(input, JsonObject.class);
        }
        JsonObject recorded = gson.fromJson("{worldX:3201,worldY:3202,plane:1,localX:64,localY:192,worldViewId:7,"
            + "worldPositionKnown:true,localPositionKnown:false,planeKnown:null,animationId:900001,animationFrame:0,"
            + "poseAnimationId:-1,poseAnimationFrame:null,graphicsKnown:false,graphicIds:[],overheadText:'observed',"
            + "combatLevel:77,interaction:{reportedInteracting:false},observationIssues:{orientation:'Unavailable'}}", JsonObject.class);
        recorded.add("interaction", gson.toJsonTree(ActorInteractionState.builder().reportedInteracting(false).build()));
        for (Class<? extends ObservedActorState> kind : Arrays.asList(NpcState.class, PlayerState.class, OtherPlayerState.class))
        {
            ObservedActorState value = gson.fromJson(recorded, kind);
            JsonObject actual = gson.toJsonTree(value).getAsJsonObject();
            Set<String> expected = new LinkedHashSet<>();
            for (JsonElement key : fields.getAsJsonArray(kind.getSimpleName())) expected.add(key.getAsString());
            assertEquals(kind.getSimpleName(), expected, actual.keySet());
            for (Map.Entry<String, JsonElement> entry : recorded.entrySet())
                assertEquals(kind.getSimpleName() + ":" + entry.getKey(), entry.getValue(), actual.get(entry.getKey()));
            assertEquals(value, gson.fromJson(gson.toJson(value), kind));
        }
    }

    @Test
    public void buildersAndEqualityIncludeInheritedFields()
    {
        NpcState npc = NpcState.builder().npcId(123).worldX(3201).worldViewId(7).build();
        PlayerState player = PlayerState.builder().hp(88).worldX(3201).worldViewId(7).build();
        OtherPlayerState other = OtherPlayerState.builder().actorRef("other:1").worldX(3201).worldViewId(7).build();
        assertEquals(npc, npc.toBuilder().build());
        assertEquals(player, player.toBuilder().build());
        assertEquals(other, other.toBuilder().build());
        assertNotEquals(npc, npc.toBuilder().worldX(3202).build());
        assertNotEquals(player, player.toBuilder().worldViewId(null).build());
        assertNotEquals(other, other.toBuilder().worldX(3202).build());
        assertEquals(Integer.valueOf(123), npc.toBuilder().worldX(3202).build().getNpcId());
        assertEquals(Integer.valueOf(88), player.toBuilder().worldX(3202).build().getHp());
        assertEquals("other:1", other.toBuilder().worldX(3202).build().getActorRef());
        assertNotEquals(player, other);
    }
}
