package com.tickreplaylogger.capture;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The direct internal representation retains original receipts without copying a normalized cue. */
public class ResolutionCandidateParityTest
{
    private static final Gson JSON = new Gson();

    @Test
    public void observedAndHintFactoriesKeepTheirOriginalPhysicalReceipt()
    {
        JsonObject cue = json("{key:'original',sequence:17,tick:3,cycle:180,source:{kind:'npc',id:101,actorRef:'npc-a',"
            + "name:'Recorded actor',scope:{sceneKey:'room-a',worldViewId:0,plane:0}},witness:{projectileId:303,observationId:7}}");
        ResolverEvidence.Source source = DirectFixtureEvidence.source(cue);
        ResolutionCandidate observed = ResolutionCandidate.observed("rule", source);
        assertSame(source, observed.source);
        assertEquals("original:rule", observed.key);
        assertEquals("npc:101", source.identity().kind + ":" + source.identity().id);
        assertTrue(observed.evidence.contains(new JsonPrimitive("OBSERVATION_SEQUENCE:17")));
        assertTrue(observed.evidence.contains(new JsonPrimitive("PROJECTILE_OBSERVATION_ID:7")));
        ResolutionCandidate hint = ResolutionCandidate.hint("rule", source);
        assertSame(source, hint.source);
        assertEquals(Boolean.FALSE, hint.componentQualified);
        assertEquals(Boolean.FALSE, hint.componentIdentified);
        assertEquals(Boolean.TRUE, hint.causalAssociationOnly);
        assertTrue(hint.evidence.contains(new JsonPrimitive("COMPONENT_UNKNOWN")));
    }

    @Test
    public void identityIsFrozenWhilePrivateSourcePointersStayOutOfTheBoundary()
    {
        JsonObject cue = json("{key:'source',source:{kind:'npc',id:101,scope:{sceneKey:null,worldViewId:0,plane:0}}}");
        ResolutionCandidate row = ResolutionCandidate.observed("rule", DirectFixtureEvidence.source(cue));
        row.componentQualified = false;
        row.sourceOnly = true;
        row.maximum = JsonNull.INSTANCE;
        row.referenceAnnotations = json("{value:null}");
        cue.getAsJsonObject("source").addProperty("name", "Later observation");
        assertNull(row.source.identity().name);
        JsonObject output = ResolverTestCatalog.render(row);
        assertFalse(output.has("sourceOnly"));
        assertFalse(output.has("cue"));
        assertFalse(output.has("rule"));
        assertFalse(output.getAsJsonObject("source").has("name"));
        assertFalse(output.get("componentQualified").getAsBoolean());
        assertTrue(output.get("maximum").isJsonNull());
        assertTrue(output.getAsJsonObject("referenceAnnotations").get("value").isJsonNull());
    }

    @Test
    public void qualifiedMaximumPreservesExistingExclusionAndReasonOrder()
    {
        for (int amount : new int[] {0, 5, 8})
        {
            ResolutionCandidate row = ResolutionCandidate.hint("rule", DirectFixtureEvidence.source(json("{key:'sample',source:{kind:'npc',id:101}}")));
            ResolverEvidence.Hit hit = DirectFixtureEvidence.hit(json("{hit:{amount:" + amount + "}}"));
            row.applyMaximum(new JsonPrimitive(5), hit, "first");
            row.applyMaximum(new JsonPrimitive(20), hit, "second");
            assertEquals(amount > 5 ? "EXCLUDED" : "POSSIBLE", row.status);
            assertEquals(amount > 5 ? 1 : 0, row.reasons.size());
            if (amount > 5)
            {
                assertEquals("MAXIMUM_EXCEEDED:first", row.reasons.get(0).getAsString());
            }
            assertEquals(new JsonPrimitive(20), row.maximum);
            assertEquals("QUALIFIED_MAXIMUM:second", row.evidence.get(row.evidence.size() - 1).getAsString());
        }
    }

    private static JsonObject json(String value)
    {
        return JSON.fromJson(value, JsonObject.class);
    }
}
