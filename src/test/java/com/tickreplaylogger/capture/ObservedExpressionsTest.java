/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedExpressionsTest
{
	private static final Gson GSON = new Gson();
	private static JsonObject constant(long value)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", "CONST"); node.addProperty("value", value); return node;
	}
	private static JsonObject fact(String name)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", "FACT"); node.addProperty("name", name); return node;
	}
	private static JsonObject args(String op, JsonElement... values)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", op);
		JsonArray children = new JsonArray(); for (JsonElement value : values) children.add(value);
		node.add("args", children); return node;
	}
	private static JsonObject binary(String op, JsonElement left, JsonElement right)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", op); node.add("left", left); node.add("right", right); return node;
	}
	private static JsonObject division(long numerator, long denominator, String rounding)
	{
		JsonObject node = binary("DIVIDE", constant(numerator), constant(denominator)); node.addProperty("rounding", rounding); return node;
	}
	private static JsonObject clamp(JsonElement value, JsonElement min, JsonElement max)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", "CLAMP"); node.add("value", value);
		node.add("min", min); node.add("max", max); return node;
	}
	private static JsonObject not(JsonElement value)
	{
		JsonObject node = new JsonObject(); node.addProperty("op", "NOT"); node.add("value", value); return node;
	}
	private static ObservedExpressions.NumericResult number(JsonElement node)
	{
		return ObservedExpressions.compileNumeric(node).evaluate(Collections.emptyMap());
	}
	private static ObservedExpressions.PredicateStatus status(JsonElement node)
	{
		return ObservedExpressions.compilePredicate(node).evaluate(Collections.emptyMap()).getStatus();
	}
	private static void known(long expected, JsonElement node)
	{
		ObservedExpressions.NumericResult result = number(node);
		assertEquals(result.getReason(), ObservedExpressions.NumericStatus.KNOWN, result.getStatus());
		assertEquals(BigInteger.valueOf(expected), result.getValue());
	}
	private static void invalidNumeric(JsonElement node)
	{
		ObservedExpressions.NumericExpression expression = ObservedExpressions.compileNumeric(node);
		assertNotNull(expression.getValidationError());
		assertEquals(ObservedExpressions.NumericStatus.UNKNOWN, expression.evaluate(Collections.emptyMap()).getStatus());
		assertNull(expression.evaluate(Collections.emptyMap()).getValue());
	}

	@Test
	public void observedScalingFormulaNeedsEveryFactAndDoesNotInventMode()
	{
		JsonObject scaled = binary("DIVIDE", args("MULTIPLY", fact("observed.base"), fact("observed.scale")), constant(100));
		scaled.addProperty("rounding", "FLOOR");
		ObservedExpressions.NumericExpression expression = ObservedExpressions.compileNumeric(scaled);
		Map<String, Number> observed = new LinkedHashMap<>(); observed.put("observed.base", 37);
		ObservedExpressions.NumericResult missing = expression.evaluate(observed);
		assertEquals(ObservedExpressions.NumericStatus.UNKNOWN, missing.getStatus());
		assertEquals(Collections.singleton("observed.scale"), missing.getMissingFacts());
		observed.put("observed.scale", 125); assertEquals(BigInteger.valueOf(46), expression.evaluate(observed).getValue());
		assertEquals(ObservedExpressions.PredicateStatus.UNESTABLISHED,
			ObservedExpressions.compilePredicate(binary("EQ", fact("mode.hard"), constant(0))).evaluate(observed).getStatus());
	}

	@Test
	public void exactLargeIntegersAndBoundsDoNotOverflowIntoInt()
	{
		known(4_000_000_000L, args("MULTIPLY", constant(2_000_000_000L), constant(2)));
		known(1_000_000_000_000L, constant(1_000_000_000_000L));
		known(-1_000_000_000_000L, constant(-1_000_000_000_000L));
		assertEquals("RANGE_LIMIT", number(args("ADD", constant(1_000_000_000_000L), constant(1))).getReason());
		assertEquals("RANGE_LIMIT", number(args("MULTIPLY", constant(1_000_000_000_000L), constant(2), constant(0))).getReason());
		invalidNumeric(constant(1_000_000_000_001L));
	}

	@Test
	public void floorAndCeilingAreExplicitForAllSigns()
	{
		known(2, division(7, 3, "FLOOR")); known(3, division(7, 3, "CEILING"));
		known(-3, division(-7, 3, "FLOOR")); known(-2, division(-7, 3, "CEILING"));
		known(-3, division(7, -3, "FLOOR")); known(-2, division(7, -3, "CEILING"));
		known(2, division(-7, -3, "FLOOR")); known(3, division(-7, -3, "CEILING"));
		assertEquals("DIVISION_BY_ZERO", number(division(5, 0, "FLOOR")).getReason());
		JsonObject missing = division(5, 2, "FLOOR"); missing.remove("rounding"); invalidNumeric(missing);
		invalidNumeric(division(5, 2, "TRUNCATE"));
	}

	@Test
	public void arithmeticAndClampPreserveMissingOrInvalidChildren()
	{
		known(7, args("SUBTRACT", constant(10), constant(3)));
		known(-3, args("MIN", constant(9), constant(-3), constant(5)));
		known(9, args("MAX", constant(9), constant(-3), constant(5)));
		known(9, args("MIN", constant(9))); known(9, args("ADD", constant(9)));
		known(2, clamp(constant(-5), constant(2), constant(9))); known(9, clamp(constant(30), constant(2), constant(9)));
		assertEquals("INVALID_CLAMP_BOUNDS", number(clamp(constant(5), constant(9), constant(2))).getReason());
		assertEquals(ObservedExpressions.NumericStatus.UNKNOWN, number(clamp(fact("missing"), constant(0), constant(0))).getStatus());
		assertEquals(ObservedExpressions.NumericStatus.UNKNOWN, number(args("MIN", constant(0), fact("missing"))).getStatus());
		invalidNumeric(args("SUBTRACT", constant(10), constant(2), constant(1))); invalidNumeric(args("ADD"));
	}

	@Test
	public void observedFactsMustBeFiniteExactIntegersWithinRange()
	{
		ObservedExpressions.NumericExpression expression = ObservedExpressions.compileNumeric(fact("fact"));
		for (Number value : Arrays.<Number>asList(1.5, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("0.00001"),
			new BigInteger("1000000000001"), Long.MAX_VALUE))
			assertEquals(value.toString(), ObservedExpressions.NumericStatus.UNKNOWN,
				expression.evaluate(Collections.singletonMap("fact", value)).getStatus());
		for (Number value : Arrays.<Number>asList(42, 42L, new BigDecimal("42.000"), BigInteger.valueOf(42), 42.0))
			assertEquals(BigInteger.valueOf(42), expression.evaluate(Collections.singletonMap("fact", value)).getValue());
	}

	@Test
	public void missingNamesAreCaseExactAndMergedWithoutChangingInputs()
	{
		ObservedExpressions.NumericResult missing = ObservedExpressions.compileNumeric(args("ADD", fact("A"), fact("B"), fact("A")))
			.evaluate(Collections.singletonMap("a", 10));
		assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("A", "B")), missing.getMissingFacts());
		try { missing.getMissingFacts().add("extra"); fail("Result must be immutable"); }
		catch (UnsupportedOperationException expected) { }
		assertEquals(ObservedExpressions.NumericStatus.UNKNOWN, ObservedExpressions.compileNumeric(fact("A")).evaluate(null).getStatus());
	}

	@Test
	public void comparisonsUseExactNumbersAndAllFiveRelations()
	{
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(binary("EQ", constant(3), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(binary("LT", constant(2), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(binary("LTE", constant(3), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(binary("GT", constant(4), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(binary("GTE", constant(3), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.CONTRADICTED, status(binary("EQ", constant(4_000_000_000L), constant(3))));
		assertEquals(ObservedExpressions.PredicateStatus.UNESTABLISHED, status(binary("GT", fact("missing"), constant(0))));
	}

	@Test
	public void threeValuedLogicDoesNotTurnUnknownIntoFalseOrZero()
	{
		JsonObject yes = binary("EQ", constant(1), constant(1)), no = binary("EQ", constant(1), constant(2));
		JsonObject unknown = binary("EQ", fact("missing"), constant(0));
		assertEquals(ObservedExpressions.PredicateStatus.UNESTABLISHED, status(not(unknown)));
		assertEquals(ObservedExpressions.PredicateStatus.UNESTABLISHED, status(args("ALL", yes, unknown)));
		assertEquals(ObservedExpressions.PredicateStatus.UNESTABLISHED, status(args("ANY", no, unknown)));
		assertEquals(ObservedExpressions.PredicateStatus.CONTRADICTED, status(args("ALL", unknown, no)));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(args("ANY", unknown, yes)));
		assertEquals(ObservedExpressions.PredicateStatus.CONTRADICTED, status(not(yes)));
		assertEquals(ObservedExpressions.PredicateStatus.MATCH, status(not(no)));
	}

	@Test
	public void malformedOrUntypedExpressionsPermanentlyFailClosed()
	{
		for (String malformed : Arrays.asList("null", "5", "[]", "{}", "{\"op\":\"CONST\",\"value\":true}",
			"{\"op\":\"CONST\",\"value\":\"1\"}", "{\"op\":\"CONST\",\"value\":1.5}",
			"{\"op\":\"CONST\",\"value\":1e2147483648}", "{\"op\":\"FACT\",\"name\":\" hp\"}",
			"{\"op\":\"FACT\",\"name\":\"observed.hp\",\"fallback\":0}",
			"{\"op\":\"CONST\",\"value\":1,\"formula\":\"execute\"}"))
			invalidNumeric(GSON.fromJson(malformed, JsonElement.class));
		invalidNumeric(binary("EQ", constant(1), constant(1)));
		assertNotNull(ObservedExpressions.compilePredicate(constant(1)).getValidationError());
		assertNotNull(ObservedExpressions.compilePredicate(args("ALL")).getValidationError());
		JsonObject nonfinite = constant(1); nonfinite.addProperty("value", Double.NaN); invalidNumeric(nonfinite);
	}

	@Test
	public void compiledTreeIsIndependentOfLaterJsonMutation()
	{
		JsonObject original = args("ADD", constant(2), constant(3));
		ObservedExpressions.NumericExpression compiled = ObservedExpressions.compileNumeric(original);
		original.getAsJsonArray("args").add(fact("not-recorded")); original.addProperty("op", "EXECUTE");
		assertEquals(BigInteger.valueOf(5), compiled.evaluate(Collections.emptyMap()).getValue());
	}

	@Test
	public void referencedFactsAreBoundedImmutableAndIncludePredicateNumericLeaves()
	{
		ObservedExpressions.PredicateExpression compiled = ObservedExpressions.compilePredicate(args("ALL",
			binary("EQ", fact("context.activity.example"), constant(1)),
			binary("LTE", args("ADD", fact("observed.damage"), fact("observed.damage")), fact("observed.maximum"))));
		assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("context.activity.example", "observed.damage", "observed.maximum")),
			compiled.referencedFacts());
		try { compiled.referencedFacts().clear(); fail("References must be immutable"); }
		catch (UnsupportedOperationException expected) { }
		assertEquals(Collections.singleton("hp"), ObservedExpressions.compileNumeric(fact("hp")).referencedFacts());
		assertTrue(ObservedExpressions.compileNumeric(args("INVALID", fact("ignored"))).referencedFacts().isEmpty());
	}

	@Test
	public void treeBudgetsCountNumericChildrenInsidePredicates()
	{
		JsonObject depth = constant(1);
		for (int i = 1; i < 16; i++) depth = args("ADD", depth);
		assertNull(ObservedExpressions.compileNumeric(depth).getValidationError());
		invalidNumeric(args("ADD", depth));
		JsonObject width = args("ADD"); for (int i = 0; i < 127; i++) width.getAsJsonArray("args").add(constant(1));
		known(127, width);
		width.getAsJsonArray("args").add(constant(1)); invalidNumeric(width);
		JsonObject predicate = binary("EQ", depth, constant(1));
		assertNotNull(ObservedExpressions.compilePredicate(predicate).getValidationError());
		JsonObject cycle = args("ADD"); cycle.getAsJsonArray("args").add(cycle); invalidNumeric(cycle);
	}
}
