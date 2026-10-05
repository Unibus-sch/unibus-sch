package com.unibus.backend.common.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;

/** JSON-domain JavaScript coercions used by the remaining legacy handlers. */
public final class EdgeJson {
    private EdgeJson() { }

    public static boolean truthy(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble() != 0;
        if (value.isString()) return !value.stringValue().isEmpty();
        return true; // Empty arrays and objects are truthy in JavaScript.
    }

    public static String string(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isString()) return value.stringValue();
        if (value.isObject()) return "[object Object]";
        if (value.isArray()) {
            var parts = new ArrayList<String>();
            for (JsonNode child : value) parts.add(child.isNull() ? "" : string(child));
            return String.join(",", parts);
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            if (number == 0) return "0";
            if (!Double.isFinite(number)) return Double.toString(number);
            if (Math.abs(number) >= 1e21 || Math.abs(number) < 1e-6) {
                String[] parts = Double.toString(number).split("E");
                int exponent = Integer.parseInt(parts[1]);
                return parts[0].replaceFirst("\\.0$", "") + "e" + (exponent >= 0 ? "+" : "") + exponent;
            }
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    public static double number(JsonNode value) {
        if (value == null || value.isNull()) return 0;
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        if (value.isNumber()) return value.asDouble();
        if (value.isObject()) return Double.NaN;
        String text = string(value).trim();
        if (text.isEmpty()) return 0;
        try {
            if (text.matches("(?i)0x[0-9a-f]+")) return new BigInteger(text.substring(2), 16).doubleValue();
            if (text.matches("(?i)0b[01]+")) return new BigInteger(text.substring(2), 2).doubleValue();
            if (text.matches("(?i)0o[0-7]+")) return new BigInteger(text.substring(2), 8).doubleValue();
            if (text.matches("[+-]?Infinity") || text.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
                return Double.parseDouble(text);
            }
            return Double.NaN;
        } catch (NumberFormatException error) { return Double.NaN; }
    }
}
