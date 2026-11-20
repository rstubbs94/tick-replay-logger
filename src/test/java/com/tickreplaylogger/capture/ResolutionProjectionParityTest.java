package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.SourceResolution;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Representation changes must preserve source grouping and every published receipt. */
public class ResolutionProjectionParityTest
{
	private final Gson gson = new Gson();

	@Test
	public void allSharedCasesKeepTheFullPresentationProjection() throws Exception
	{
		JsonObject fixture;
		try (InputStreamReader input = new InputStreamReader(getClass().getResourceAsStream("/resolver-v1-conformance.json"), StandardCharsets.UTF_8))
		{
			fixture = gson.fromJson(input, JsonObject.class);
		}
		int checked = 0;
		for (JsonElement value : fixture.getAsJsonArray("cases"))
		{
			JsonObject test = value.getAsJsonObject();
			ResolverV1 engine = ResolverTestCatalog.engine(ResolverTestCatalog.lower(test.getAsJsonObject("catalog")));
			JsonObject input=test.getAsJsonObject("input").deepCopy();
            java.util.List<ResolverV1.Facts> facts=new java.util.ArrayList<>();
            for(JsonElement cue:input.getAsJsonArray("cues")) facts.add(new DeclaredMechanicFacts(engine,input,cue.getAsJsonObject()));
            assertProjection(engine.evaluate(DirectFixtureEvidence.hit(input),facts), engine.id());
			checked++;
		}
		assertEquals(109, checked);
	}

	@Test
	public void unscopedAliasesMergeOnlyForTheSamePhysicalObservation()
	{
		JsonObject first = cue("observation:1", "{kind:'npc',id:101,name:'Same untracked name'}");
		JsonObject second = cue("observation:2", "{kind:'npc',id:101,name:'Same untracked name'}");
		ResolutionResult aliases = new ResolutionResult();
		aliases.candidates.add(ResolutionCandidate.hint("component:first", DirectFixtureEvidence.source(first)));
		aliases.candidates.add(ResolutionCandidate.hint("component:second", DirectFixtureEvidence.source(first)));
		assertProjection(aliases, "synthetic");
		assertEquals(1, SourceEvidenceResolver.project(aliases, "synthetic").getCandidates().size());
		aliases.candidates.add(ResolutionCandidate.hint("component:first", DirectFixtureEvidence.source(second)));
		assertProjection(aliases, "synthetic");
		SourceResolution distinct = SourceEvidenceResolver.project(aliases, "synthetic");
		assertEquals(2, distinct.getCandidates().size());
		assertNotEquals(distinct.getCandidates().get(0).getKey(), distinct.getCandidates().get(1).getKey());
	}

	@Test
	public void referenceConflictsAndFamilyFallbacksKeepTheirOriginalRoles()
	{
		for (String scope : Arrays.asList("ORDINARY_ATTACK", "ALL_DIRECT_HP_COMPONENTS"))
		{
			ResolutionResult result = new ResolutionResult();
			ResolutionCandidate row = ResolutionCandidate.observed("reference", DirectFixtureEvidence.source(cue("source:1",
				"{kind:'npc',id:101,name:'Recorded actor',actorRef:'npc:1',scope:{sceneKey:'room',worldViewId:0,plane:0}}")));
			row.status = "EXCLUDED";
			row.candidateStrength = "STRONG";
			row.exclusionScope = scope;
			row.legacyApiReferenceConflictEligible = true;
			row.reasons.add("MAXIMUM_EXCEEDED:reference");
			result.candidates.add(row);
			assertProjection(result, "synthetic");
			SourceResolution projected = SourceEvidenceResolver.project(result, "synthetic");
			assertEquals("ORDINARY_ATTACK".equals(scope) ? 1 : 0, projected.getCandidates().size());
			assertEquals("ORDINARY_ATTACK".equals(scope) ? 0 : 1, projected.getExcludedCandidates() == null ? 0 : projected.getExcludedCandidates().size());
		}
		ResolutionResult result = new ResolutionResult();
		JsonObject family = cue("family:1", "{kind:'status',statusFamily:'venom',name:'Venom'}");
		family.addProperty("hitFamilyOnly", true);
		result.candidates.add(ResolutionCandidate.hint("family", DirectFixtureEvidence.source(family)));
		assertProjection(result, "synthetic");
		assertEquals("Venom", SourceEvidenceResolver.project(result, "synthetic").getCandidates().get(0).getName());
		result.candidates.add(ResolutionCandidate.hint("physical-source", DirectFixtureEvidence.source(cue("source:2",
			"{kind:'npc',id:101,name:'Recorded actor',actorRef:'npc:1',scope:{sceneKey:'room',worldViewId:0,plane:0}}"))));
		assertProjection(result, "synthetic");
		SourceResolution projected = SourceEvidenceResolver.project(result, "synthetic");
		assertEquals(1, projected.getCandidates().size());
		assertEquals("Recorded actor", projected.getCandidates().get(0).getName());
		assertTrue(projected.getCandidates().get(0).getEvidence().contains("OBSERVED_HIT_FAMILY:venom"));
		assertTrue(projected.isUnidentifiedPossible());
	}

	private JsonObject cue(String key, String source)
	{
		JsonObject cue = new JsonObject();
		cue.addProperty("key", key);
		cue.add("source", gson.fromJson(source, JsonObject.class));
		return cue;
	}

	private void assertProjection(ResolutionResult result, String catalogId)
	{
		JsonObject before = ResolverTestCatalog.render(result).deepCopy();
		SourceResolution prior = new PreTypedSourceProjection(catalogId).projectResult(before.deepCopy());
		assertEquals(gson.toJsonTree(prior), gson.toJsonTree(SourceEvidenceResolver.project(result, catalogId)));
		assertEquals("Projection must not mutate evaluated receipts", before, ResolverTestCatalog.render(result));
	}
}
