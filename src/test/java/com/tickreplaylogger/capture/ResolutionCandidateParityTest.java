package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Public row output must survive the internal typed representation unchanged. */
public class ResolutionCandidateParityTest
{
	private static final Gson JSON = new Gson();

	@Test
	public void observedAndHintFactoriesKeepEveryOriginalReceipt() throws Exception
	{
		for (String input : Arrays.asList(
			"{}",
			"{key:'cue-a',ordinary:true,source:{kind:'npc',id:101,actorRef:'npc-a',name:'Recorded actor',scope:{sceneKey:'room-a',worldViewId:0,plane:0}}}",
			"{key:'status',source:{kind:'status',statusFamily:'poison',scope:{sceneKey:null,worldViewId:0,plane:0}}}",
			"{key:'object',source:{kind:'object',id:202,observationKey:'physical-1',actorRef:null},sequence:17,witness:{projectileId:303,observationId:7}}",
			"{source:{kind:'npc',familyKey:'family-a'},sequence:17.5,witness:{projectileId:303,observationId:7.5}}",
			"{source:{kind:'player',actorRef:'other-player'},sequence:9223372036854775807,evidenceMode:'API',candidateStrength:'STRONG',sourceAssociation:'observed',observedLabel:'raw label',originalNpcContact:true,hitFamilyOnly:true,unstableOriginalSource:true,clockEvidence:'CLIENT_END_CYCLE_CORRELATION'}",
			"{source:{kind:'npc',id:101},sequence:-1,witness:{observationId:1},ordinary:'true',clockEvidence:'HP_DURING_OBSERVED_FLIGHT',candidateEvidence:['retained fact',null,{value:null}]}"))
		{
			JsonObject cue = JSON.fromJson(input, JsonObject.class);
			for (String id : Arrays.asList("generic-rule", null))
			{
				assertEquals(legacy("row", id, cue), ResolverTestCatalog.render(ResolutionCandidate.observed(id, cue)));
				assertEquals(legacy("hintRow", id, cue), ResolverTestCatalog.render(ResolutionCandidate.hint(id, cue)));
			}
		}
	}

	@Test
	public void boundaryKeepsExplicitNullsAndFalseWithoutLeakingInternalPointers() throws Exception
	{
		JsonObject cue = JSON.fromJson("{source:{kind:'npc',id:101,actorRef:null,scope:{sceneKey:null,worldViewId:0,plane:0}}}", JsonObject.class);
		JsonObject expected = legacy("row", "generic-rule", cue);
		ResolutionCandidate actual = ResolutionCandidate.observed("generic-rule", cue);
		assertFalse(ResolverTestCatalog.render(actual).has("componentQualified"));
		actual.componentQualified = false;
		expected.addProperty("componentQualified", false);
		actual.maximum = JsonNull.INSTANCE;
		expected.add("maximum", JsonNull.INSTANCE);
		actual.hitMultiplicity = JsonNull.INSTANCE;
		expected.add("hitMultiplicity", JsonNull.INSTANCE);
		actual.referenceAnnotations = JSON.fromJson("{value:null}", JsonObject.class);
		expected.add("referenceAnnotations", actual.referenceAnnotations.deepCopy());
		actual.derivedAnnotations = JSON.fromJson("{value:null}", JsonObject.class);
		expected.add("derivedAnnotations", actual.derivedAnnotations.deepCopy());
		actual.references = JSON.fromJson("[{value:null}]", JsonArray.class);
		expected.add("references", actual.references.deepCopy());
		actual.sourceOnly = true;
		cue.addProperty("internalPointerSentinel", true);
		cue.getAsJsonObject("source").addProperty("name", "Later observation");
		JsonObject output = ResolverTestCatalog.render(actual);
		assertEquals(expected, output);
		assertFalse(output.has("sourceOnly"));
		assertFalse(output.has("cue"));
		assertFalse(output.getAsJsonObject("source").has("name"));
	}

	@Test
	public void qualifiedMaximumPreservesExistingStatusAndReasonOrder() throws Exception
	{
		Method apply = PreRuleIrResolverV1.class.getDeclaredMethod("applyMaximum", JsonObject.class, JsonElement.class, JsonObject.class, String.class);
		apply.setAccessible(true);
		for (int amount : new int[] { 0, 5, 8 })
		{
			JsonObject cue = JSON.fromJson("{key:'sample',source:{kind:'npc',id:101}}", JsonObject.class);
			JsonObject expected = legacy("hintRow", "generic-rule", cue);
			ResolutionCandidate actual = ResolutionCandidate.hint("generic-rule", cue);
			JsonObject hit = new JsonObject();
			hit.addProperty("amount", amount);
			for (int maximum : new int[] { 5, 20 })
			{
				JsonElement ceiling = new JsonPrimitive(maximum);
				apply.invoke(null, expected, ceiling, hit, "qualified-rule");
				actual.applyMaximum(ceiling, hit, "qualified-rule");
				assertEquals(expected, ResolverTestCatalog.render(actual));
			}
		}
	}

	private static JsonObject legacy(String method, String id, JsonObject cue) throws Exception
	{
		Method factory = PreRuleIrResolverV1.class.getDeclaredMethod(method, String.class, JsonObject.class);
		factory.setAccessible(true);
		return (JsonObject) factory.invoke(null, id, cue);
	}
}
