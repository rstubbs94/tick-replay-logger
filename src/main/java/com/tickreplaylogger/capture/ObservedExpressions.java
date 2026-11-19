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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded integer expressions over facts supplied by a caller. Does not read or simulate the game. */
public final class ObservedExpressions
{
	public static final int MAX_DEPTH = 16;
	public static final int MAX_NODES = 128;
	public static final BigInteger MAX_ABSOLUTE_VALUE = BigInteger.valueOf(1_000_000_000_000L);
	private static final BigDecimal DECIMAL_LIMIT = new BigDecimal(MAX_ABSOLUTE_VALUE);

	public enum NumericStatus
	{
		KNOWN, UNKNOWN
	}

	public enum PredicateStatus
	{
		MATCH, CONTRADICTED, UNESTABLISHED
	}

	public static final class NumericResult
	{
		private final NumericStatus status;
		private final BigInteger value;
		private final String reason;
		private final Set<String> missingFacts;

		private NumericResult(BigInteger value, String reason, Set<String> missingFacts)
		{
			this.status = value == null ? NumericStatus.UNKNOWN : NumericStatus.KNOWN;
			this.value = value;
			this.reason = reason;
			this.missingFacts = Collections.unmodifiableSet(new LinkedHashSet<>(missingFacts));
		}

		public NumericStatus getStatus()
		{
			return status;
		}

		public BigInteger getValue()
		{
			return value;
		}

		public String getReason()
		{
			return reason;
		}

		public Set<String> getMissingFacts()
		{
			return missingFacts;
		}
	}

	public static final class PredicateResult
	{
		private final PredicateStatus status;
		private final String reason;
		private final Set<String> missingFacts;

		private PredicateResult(PredicateStatus status, String reason, Set<String> missingFacts)
		{
			this.status = status;
			this.reason = reason;
			this.missingFacts = Collections.unmodifiableSet(new LinkedHashSet<>(missingFacts));
		}

		public PredicateStatus getStatus()
		{
			return status;
		}

		public String getReason()
		{
			return reason;
		}

		public Set<String> getMissingFacts()
		{
			return missingFacts;
		}
	}

	public static final class NumericExpression
	{
		private final Node root;
		private final String validationError;
		private final Set<String> referencedFacts;

		private NumericExpression(Node root, String validationError)
		{
			this.root = root;
			this.validationError = validationError;
			this.referencedFacts = referenced(root);
		}

		public String getValidationError()
		{
			return validationError;
		}

		public Set<String> referencedFacts()
		{
			return referencedFacts;
		}

		public NumericResult evaluate(Map<String, Number> observedFacts)
		{
			return root == null ? unknown(validationError, Collections.emptySet())
				: numeric(root, facts(observedFacts));
		}
	}

	public static final class PredicateExpression
	{
		private final Node root;
		private final String validationError;
		private final Set<String> referencedFacts;

		private PredicateExpression(Node root, String validationError)
		{
			this.root = root;
			this.validationError = validationError;
			this.referencedFacts = referenced(root);
		}

		public String getValidationError()
		{
			return validationError;
		}

		public Set<String> referencedFacts()
		{
			return referencedFacts;
		}

		public PredicateResult evaluate(Map<String, Number> observedFacts)
		{
			return root == null ? undecided(validationError, Collections.emptySet())
				: predicate(root, facts(observedFacts));
		}
	}

	private static final class Node
	{
		final String op, name, rounding;
		final BigInteger constant;
		final List<Node> children;

		Node(String op, String name, String rounding, BigInteger constant, List<Node> children)
		{
			this.op = op;
			this.name = name;
			this.rounding = rounding;
			this.constant = constant;
			this.children = Collections.unmodifiableList(new ArrayList<>(children));
		}
	}

	private static final class InvalidExpression extends RuntimeException
	{
		InvalidExpression(String reason)
		{
			super(reason);
		}
	}

	private ObservedExpressions()
	{
	}

	public static NumericExpression compileNumeric(JsonElement expression)
	{
		try
		{
			return new NumericExpression(parse(expression, false, 1, new int[1]), null);
		}
		catch (InvalidExpression invalid)
		{
			return new NumericExpression(null, invalid.getMessage());
		}
		catch (NumberFormatException invalid)
		{
			return new NumericExpression(null, "INVALID_EXPRESSION:NUMBER");
		}
	}

	public static PredicateExpression compilePredicate(JsonElement expression)
	{
		try
		{
			return new PredicateExpression(parse(expression, true, 1, new int[1]), null);
		}
		catch (InvalidExpression invalid)
		{
			return new PredicateExpression(null, invalid.getMessage());
		}
		catch (NumberFormatException invalid)
		{
			return new PredicateExpression(null, "INVALID_EXPRESSION:NUMBER");
		}
	}

	private static Node parse(JsonElement element, boolean predicate, int depth, int[] count)
	{
		if (depth > MAX_DEPTH || ++count[0] > MAX_NODES)
			throw invalid("TREE_LIMIT");
		if (element == null || !element.isJsonObject())
			throw invalid("EXPECTED_OBJECT");
		JsonObject row = element.getAsJsonObject();
		String op = string(row.get("op"));
		List<Node> children = new ArrayList<>();
		String name = null, rounding = null;
		BigInteger constant = null;
		if (!predicate && "CONST".equals(op))
		{
			fields(row, "op", "value");
			JsonElement value = row.get("value");
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
				throw invalid("EXPECTED_INTEGER");
			String literal = value.getAsString();
			if (literal.length() > 64)
				throw invalid("NUMBER_LIMIT");
			constant = integer(new BigDecimal(literal));
			if (constant == null)
				throw invalid("INTEGER_OR_RANGE");
		}
		else if (!predicate && "FACT".equals(op))
		{
			fields(row, "op", "name");
			name = string(row.get("name"));
			if (!name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}"))
				throw invalid("FACT_NAME");
		}
		else if (!predicate && Arrays.asList("ADD", "SUBTRACT", "MULTIPLY", "MIN", "MAX").contains(op)
			|| predicate && Arrays.asList("ALL", "ANY").contains(op))
		{
			fields(row, "op", "args");
			JsonElement args = row.get("args");
			if (!args.isJsonArray() || args.getAsJsonArray().size() == 0
				|| "SUBTRACT".equals(op) && args.getAsJsonArray().size() != 2
				|| args.getAsJsonArray().size() > MAX_NODES)
				throw invalid("ARITY");
			for (JsonElement child : args.getAsJsonArray())
				children.add(parse(child, predicate, depth + 1, count));
		}
		else if (!predicate && "DIVIDE".equals(op))
		{
			fields(row, "op", "left", "right", "rounding");
			rounding = string(row.get("rounding"));
			if (!Arrays.asList("FLOOR", "CEILING").contains(rounding))
				throw invalid("ROUNDING");
			children.add(parse(row.get("left"), false, depth + 1, count));
			children.add(parse(row.get("right"), false, depth + 1, count));
		}
		else if (!predicate && "CLAMP".equals(op))
		{
			fields(row, "op", "value", "min", "max");
			for (String field : Arrays.asList("value", "min", "max"))
				children.add(parse(row.get(field), false, depth + 1, count));
		}
		else if (predicate && Arrays.asList("EQ", "LT", "LTE", "GT", "GTE").contains(op))
		{
			fields(row, "op", "left", "right");
			children.add(parse(row.get("left"), false, depth + 1, count));
			children.add(parse(row.get("right"), false, depth + 1, count));
		}
		else if (predicate && "NOT".equals(op))
		{
			fields(row, "op", "value");
			children.add(parse(row.get("value"), true, depth + 1, count));
		}
		else
			throw invalid("OPERATOR_OR_TYPE");
		return new Node(op, name, rounding, constant, children);
	}

	private static InvalidExpression invalid(String reason)
	{
		return new InvalidExpression("INVALID_EXPRESSION:" + reason);
	}

	private static String string(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
			throw invalid("EXPECTED_STRING");
		String text = value.getAsString();
		if (text.length() > 64)
			throw invalid("STRING_LIMIT");
		return text;
	}

	private static void fields(JsonObject row, String... names)
	{
		if (!row.keySet().equals(new LinkedHashSet<>(Arrays.asList(names))))
			throw invalid("FIELDS");
	}

	private static Map<String, Number> facts(Map<String, Number> values)
	{
		return values == null ? Collections.emptyMap() : values;
	}

	private static Set<String> referenced(Node root)
	{
		Set<String> names = new LinkedHashSet<>();
		collectFacts(root, names);
		return Collections.unmodifiableSet(names);
	}

	private static void collectFacts(Node node, Set<String> names)
	{
		if (node == null)
			return;
		if (node.name != null)
			names.add(node.name);
		for (Node child : node.children)
			collectFacts(child, names);
	}

	private static BigInteger integer(Number value)
	{
		try
		{
			BigDecimal decimal;
			if (value instanceof BigDecimal)
				decimal = (BigDecimal) value;
			else if (value instanceof BigInteger)
				decimal = new BigDecimal((BigInteger) value);
			else if (value instanceof Byte || value instanceof Short || value instanceof Integer
				|| value instanceof Long)
				decimal = BigDecimal.valueOf(value.longValue());
			else if (value instanceof Double || value instanceof Float)
			{
				if (!Double.isFinite(value.doubleValue()))
					return null;
				decimal = BigDecimal.valueOf(value.doubleValue());
			}
			else
				return null;
			return decimal.abs().compareTo(DECIMAL_LIMIT) > 0 ? null : decimal.toBigIntegerExact();
		}
		catch (ArithmeticException invalid)
		{
			return null;
		}
	}

	private static NumericResult known(BigInteger value)
	{
		return value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0 ? unknown("RANGE_LIMIT", Collections.emptySet())
			: new NumericResult(value, null, Collections.emptySet());
	}

	private static NumericResult unknown(String reason, Set<String> missing)
	{
		return new NumericResult(null, reason, missing);
	}

	private static PredicateResult undecided(String reason, Set<String> missing)
	{
		return new PredicateResult(PredicateStatus.UNESTABLISHED, reason, missing);
	}

	private static NumericResult numeric(Node node, Map<String, Number> facts)
	{
		if ("CONST".equals(node.op))
			return known(node.constant);
		if ("FACT".equals(node.op))
		{
			Number raw = facts.get(node.name);
			if (raw == null)
				return unknown("MISSING_FACT", Collections.singleton(node.name));
			BigInteger value = integer(raw);
			return value == null ? unknown("INVALID_FACT:" + node.name, Collections.emptySet()) : known(value);
		}
		List<BigInteger> values = new ArrayList<>();
		Set<String> missing = new LinkedHashSet<>();
		String failure = null;
		for (Node child : node.children)
		{
			NumericResult result = numeric(child, facts);
			values.add(result.value);
			missing.addAll(result.missingFacts);
			if (result.status == NumericStatus.UNKNOWN && failure == null)
				failure = result.reason;
		}
		if (failure != null)
			return unknown(failure, missing);
		BigInteger value = values.get(0);
		switch (node.op)
		{
		case "DIVIDE":
			if (values.get(1).signum() == 0)
				return unknown("DIVISION_BY_ZERO", Collections.emptySet());
			return known(new BigDecimal(value).divide(new BigDecimal(values.get(1)), 0,
				"FLOOR".equals(node.rounding) ? RoundingMode.FLOOR : RoundingMode.CEILING).toBigIntegerExact());
		case "CLAMP":
			if (values.get(1).compareTo(values.get(2)) > 0)
				return unknown("INVALID_CLAMP_BOUNDS", Collections.emptySet());
			return known(value.max(values.get(1)).min(values.get(2)));
		default:
			for (int i = 1; i < values.size(); i++)
			{
				BigInteger next = values.get(i);
				switch (node.op)
				{
				case "ADD":
					value = value.add(next);
					break;
				case "SUBTRACT":
					value = value.subtract(next);
					break;
				case "MULTIPLY":
					value = value.multiply(next);
					break;
				case "MIN":
					value = value.min(next);
					break;
				case "MAX":
					value = value.max(next);
					break;
				default:
					return unknown("INVALID_COMPILED_OPERATOR", Collections.emptySet());
				}
				if (value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0)
					return unknown("RANGE_LIMIT", Collections.emptySet());
			}
			return known(value);
		}
	}

	private static PredicateResult predicate(Node node, Map<String, Number> facts)
	{
		if ("ALL".equals(node.op) || "ANY".equals(node.op) || "NOT".equals(node.op))
		{
			Set<String> missing = new LinkedHashSet<>();
			String failure = null;
			boolean match = false, contradicted = false;
			for (Node child : node.children)
			{
				PredicateResult result = predicate(child, facts);
				match |= result.status == PredicateStatus.MATCH;
				contradicted |= result.status == PredicateStatus.CONTRADICTED;
				if (result.status == PredicateStatus.UNESTABLISHED && failure == null)
					failure = result.reason;
				missing.addAll(result.missingFacts);
			}
			if ("NOT".equals(node.op))
				return failure != null ? undecided(failure, missing)
					: new PredicateResult(match ? PredicateStatus.CONTRADICTED : PredicateStatus.MATCH, null,
						Collections.emptySet());
			boolean all = "ALL".equals(node.op);
			if (all && contradicted || !all && match)
				return new PredicateResult(all ? PredicateStatus.CONTRADICTED : PredicateStatus.MATCH, null,
					Collections.emptySet());
			if (failure != null)
				return undecided(failure, missing);
			return new PredicateResult(all ? PredicateStatus.MATCH : PredicateStatus.CONTRADICTED, null,
				Collections.emptySet());
		}
		NumericResult left = numeric(node.children.get(0), facts), right = numeric(node.children.get(1), facts);
		if (left.status == NumericStatus.UNKNOWN || right.status == NumericStatus.UNKNOWN)
		{
			Set<String> missing = new LinkedHashSet<>(left.missingFacts);
			missing.addAll(right.missingFacts);
			return undecided(left.reason != null ? left.reason : right.reason, missing);
		}
		int comparison = left.value.compareTo(right.value);
		boolean match;
		switch (node.op)
		{
		case "EQ":
			match = comparison == 0;
			break;
		case "LT":
			match = comparison < 0;
			break;
		case "LTE":
			match = comparison <= 0;
			break;
		case "GT":
			match = comparison > 0;
			break;
		case "GTE":
			match = comparison >= 0;
			break;
		default:
			return undecided("INVALID_COMPILED_OPERATOR", Collections.emptySet());
		}
		return new PredicateResult(match ? PredicateStatus.MATCH : PredicateStatus.CONTRADICTED, null,
			Collections.emptySet());
	}
}
