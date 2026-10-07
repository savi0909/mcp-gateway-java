package dev.mcp.gateway.catalog;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Deliberately small flat-object schema subset used by the inspected coordinator DTOs. */
public final class FlatObjectSchema {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELD_KEYS = Set.of("type", "minLength", "maxLength", "format", "pattern",
            "minimum", "maximum");

    private FlatObjectSchema() {
    }

    public static void validateDefinition(JsonNode schema) {
        require(schema.isObject() && schema.propertyNames().equals(Set.of("type", "properties", "required",
                "additionalProperties")) && "object".equals(schema.path("type").asString())
                && schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").booleanValue()
                && schema.path("properties").isObject() && schema.path("required").isArray(), "unsupported object schema");
        var properties = schema.path("properties");
        var required = schema.path("required");
        var names = new java.util.HashSet<String>();
        required.forEach(name -> {
            require(name.isString() && names.add(name.stringValue()), "invalid required fields");
        });
        require(names.equals(properties.propertyNames()), "all declared fields must be required");
        properties.properties().forEach(entry -> {
            require(entry.getKey().matches("[A-Za-z][A-Za-z0-9]*"), "invalid schema field name");
            JsonNode field = entry.getValue();
            require(field.isObject() && field.has("type") && FIELD_KEYS.containsAll(field.propertyNames()),
                    "unsupported field schema");
            boolean duration = field.path("type").isArray()
                    && field.path("type").equals(JSON.readTree("[\"string\",\"number\"]"));
            String type = duration ? "duration" : field.path("type").asString();
            require(duration && field.size() == 1 || Set.of("string", "integer", "boolean", "number").contains(type),
                    "unsupported field type");
            for (String key : List.of("minLength", "maxLength")) {
                if (field.has(key)) {
                    require(type.equals("string") && field.get(key).isIntegralNumber()
                            && field.get(key).bigIntegerValue().signum() >= 0
                            && field.get(key).bigIntegerValue().bitLength() < 31, "invalid string length bound");
                }
            }
            require(!field.has("minLength") || !field.has("maxLength")
                    || field.get("minLength").intValue() <= field.get("maxLength").intValue(), "invalid length range");
            for (String key : List.of("minimum", "maximum")) {
                if (field.has(key)) {
                    require((type.equals("integer") || type.equals("number")) && field.get(key).isNumber(),
                            "invalid numeric bound");
                }
            }
            require(!field.has("minimum") || !field.has("maximum")
                    || field.get("minimum").decimalValue().compareTo(field.get("maximum").decimalValue()) <= 0,
                    "invalid numeric range");
            if (field.has("format")) {
                require(type.equals("string") && "uuid".equals(field.get("format").asString()), "unsupported format");
            }
            if (field.has("pattern")) {
                // Only these two bounded patterns are supported; arbitrary regex execution is excluded.
                require(type.equals("string") && field.get("pattern").isString()
                        && Set.of(".*\\S.*", "^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}$")
                                .contains(field.get("pattern").stringValue()), "unsupported string pattern");
            }
        });
    }

    public static boolean accepts(Map<String, Object> schema, Map<String, Object> values) {
        try {
            project(schema, values, false);
            return true;
        }
        catch (RuntimeException ex) {
            return false;
        }
    }

    /** Select only advertised fields, and validate every value before exposing it through MCP. */
    public static Map<String, Object> project(Map<String, Object> schema, Map<String, Object> values,
            boolean ignoreAdditional) {
        JsonNode definition = JSON.valueToTree(schema);
        JsonNode input = JSON.valueToTree(values);
        var fields = definition.path("properties");
        require(input.isObject() && (ignoreAdditional || input.propertyNames().equals(fields.propertyNames())),
                "arguments must match the advertised fields");
        var projected = new LinkedHashMap<String, Object>();
        fields.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode rule = entry.getValue();
            JsonNode value = input.path(name);
            String type = rule.path("type").isArray() ? "duration" : rule.path("type").stringValue();
            require(switch (type) {
                case "string" -> value.isString();
                case "integer" -> value.isIntegralNumber();
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                case "duration" -> value.isString() && !value.stringValue().isBlank() || value.isNumber();
                default -> false;
            }, "field has an invalid type");
            if (value.isString()) {
                String text = value.stringValue();
                int length = text.codePointCount(0, text.length());
                require(!rule.has("minLength") || length >= rule.get("minLength").intValue(), "string is too short");
                require(!rule.has("maxLength") || length <= rule.get("maxLength").intValue(), "string is too long");
                require(!rule.has("pattern") || java.util.regex.Pattern.compile(rule.get("pattern").stringValue(),
                        java.util.regex.Pattern.DOTALL).matcher(text).find(), "string has an invalid format");
                if (rule.has("format")) {
                    require(UUID.fromString(text).toString().equalsIgnoreCase(text), "UUID must use canonical syntax");
                }
                projected.put(name, text);
            }
            else if (value.isNumber()) {
                BigDecimal number = value.decimalValue();
                require(!rule.has("minimum") || number.compareTo(rule.get("minimum").decimalValue()) >= 0,
                        "number is below minimum");
                require(!rule.has("maximum") || number.compareTo(rule.get("maximum").decimalValue()) <= 0,
                        "number exceeds maximum");
                projected.put(name, value.isIntegralNumber() ? value.bigIntegerValue() : number);
            }
            else {
                projected.put(name, value.booleanValue());
            }
        });
        return Map.copyOf(projected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
