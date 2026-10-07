package dev.mcp.gateway.rest;

import java.net.URI;

import dev.mcp.gateway.config.GatewayProperties;
import tools.jackson.core.JsonPointer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable catalog binding with explicit argument mappings and a fixed private backend reference. */
public record RestBinding(String type, String backendRef, Method method, String path,
        String requestBodyJson, JsonPointer responseIdPointer, String requestKeyField,
        java.util.Map<String, String> bodyArguments, java.util.Map<String, String> pathArguments,
        boolean responseObject) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public enum Method { GET, POST }

    public RestBinding(String type, String backendRef, Method method, String path,
            String requestBodyJson, JsonPointer responseIdPointer) {
        this(type, backendRef, method, path, requestBodyJson, responseIdPointer, null);
    }

    public RestBinding(String type, String backendRef, Method method, String path,
            String requestBodyJson, JsonPointer responseIdPointer, String requestKeyField) {
        this(type, backendRef, method, path, requestBodyJson, responseIdPointer, requestKeyField,
                java.util.Map.of(), java.util.Map.of(), false);
    }

    public RestBinding {
        require("REST".equals(type), "only REST bindings are supported");
        require(GatewayProperties.BACKEND.equals(backendRef) || "payment-api".equals(backendRef),
                "unsupported backend reference");
        require(method != null, "only GET and POST methods are supported");
        bodyArguments = java.util.Map.copyOf(bodyArguments);
        pathArguments = java.util.Map.copyOf(pathArguments);
        String validationPath = path;
        for (String parameter : pathArguments.keySet()) {
            require(parameter.matches("[A-Za-z][A-Za-z0-9]*") && path != null
                    && java.util.Arrays.asList(path.split("/")).contains("{" + parameter + "}"),
                    "path parameters must occupy a complete path segment");
            validationPath = validationPath.replace("{" + parameter + "}", "parameter");
        }
        require(validationPath != null && !validationPath.contains("{") && !validationPath.contains("}"),
                "all path parameters must be mapped");
        validatePath(validationPath);
        require(responseIdPointer != null, "responseIdPointer is required");
        validatePointer(responseIdPointer.toString());
        if (method == Method.GET) {
            require(requestBodyJson == null, "GET must omit requestBody");
            require(requestKeyField == null, "GET must omit requestKeyField");
            require(bodyArguments.isEmpty(), "GET must omit bodyArguments");
        }
        else {
            try {
                require(requestBodyJson != null && !requestBodyJson.isBlank(), "POST requires static JSON");
                var body = JSON.readTree(requestBodyJson);
                require(!body.isMissingNode(), "POST requires static JSON");
                require(bodyArguments.isEmpty() || body.isObject(), "bodyArguments requires an object body");
                for (String field : bodyArguments.keySet()) {
                    require(field.matches("[A-Za-z][A-Za-z0-9]*") && !body.has(field)
                            && !field.equals(requestKeyField), "mapped body fields cannot replace static fields");
                }
                if (requestKeyField != null) {
                    require("clientIdempotencyKey".equals(requestKeyField) && body.isObject()
                            && !body.has(requestKeyField), "unsupported requestKeyField");
                }
            }
            catch (RuntimeException ex) {
                throw new IllegalArgumentException("POST requires valid, unambiguous static JSON");
            }
        }
    }

    public static JsonPointer pointer(String expression) {
        validatePointer(expression);
        return JsonPointer.compile(expression);
    }

    public URI target(URI origin) {
        URI target = origin.resolve(path);
        require(origin.getScheme().equals(target.getScheme())
                && origin.getRawAuthority().equals(target.getRawAuthority()), "binding must preserve backend origin");
        return target;
    }

    /** Arguments have already passed schema validation; paths remain confined to one safe segment. */
    public RestBinding resolve(java.util.Map<String, Object> arguments) {
        String resolvedPath = path;
        for (var entry : pathArguments.entrySet()) {
            Object value = arguments.get(entry.getValue());
            require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}"),
                    "path argument must be a safe identifier");
            resolvedPath = resolvedPath.replace("{" + entry.getKey() + "}", (String) value);
        }
        String body = requestBodyJson;
        if (!bodyArguments.isEmpty()) {
            var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
            bodyArguments.forEach((field, argument) -> node.set(field, JSON.valueToTree(arguments.get(argument))));
            body = JSON.writeValueAsString(node);
        }
        return new RestBinding(type, backendRef, method, resolvedPath, body, responseIdPointer, requestKeyField,
                java.util.Map.of(), java.util.Map.of(), responseObject);
    }

    private static void validatePath(String path) {
        try {
            require(path != null && path.startsWith("/") && !path.startsWith("//"), "invalid binding path");
            URI uri = URI.create(path);
            require(uri.getScheme() == null && uri.getRawAuthority() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null, "invalid binding path");
            String decoded = uri.getPath();
            // Reject encoded separators and percent signs too: avoids double decoding/traversal upstream.
            require(!decoded.startsWith("//") && !decoded.contains("\\") && !decoded.contains("%")
                    && !decoded.contains("?") && !decoded.contains("#")
                    && decoded.chars().noneMatch(c -> c <= 32 || c == 127), "invalid binding path");
            require(path.chars().filter(c -> c == '/').count() == decoded.chars().filter(c -> c == '/').count(),
                    "encoded path separators are unsupported");
            for (String segment : decoded.split("/", -1)) {
                require(!segment.equals(".") && !segment.equals(".."), "path traversal is unsupported");
            }
        }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("binding path must be an absolute path without traversal, query or fragment");
        }
    }

    private static void validatePointer(String expression) {
        require(expression != null && (expression.isEmpty() || expression.startsWith("/")), "invalid JSON Pointer");
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) == '~') {
                require(++i < expression.length() && (expression.charAt(i) == '0' || expression.charAt(i) == '1'),
                        "invalid JSON Pointer escape");
            }
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public String toString() {
        return "RestBinding[type=REST, backendRef=" + backendRef + ", method=" + method + ", details=<private>]";
    }
}
