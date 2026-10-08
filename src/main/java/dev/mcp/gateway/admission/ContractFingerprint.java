package dev.mcp.gateway.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import dev.mcp.gateway.catalog.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/** Binds the approved contract to public semantics and the private execution mapping. */
public final class ContractFingerprint {
    private ContractFingerprint() { }
    public static String of(ToolDefinition tool) {
        var binding = tool.binding(); var facts = new TreeMap<String, Object>();
        facts.put("name", tool.name()); facts.put("description", tool.description()); facts.put("input", tool.inputSchema());
        facts.put("output", tool.outputSchema()); facts.put("annotations", tool.annotations()); facts.put("backend", binding.backendRef());
        facts.put("method", binding.method().name()); facts.put("path", binding.path()); facts.put("body", binding.requestBodyJson() == null ? "" : binding.requestBodyJson());
        facts.put("pathArguments", binding.pathArguments()); facts.put("bodyArguments", binding.bodyArguments());
        facts.put("pointer", binding.responseIdPointer().toString()); facts.put("responseObject", binding.responseObject());
        facts.put("requestKeyField", binding.requestKeyField() == null ? "" : binding.requestKeyField());
        try {
            String canonical = JsonMapper.builder().build().writeValueAsString(canonical(facts));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest unavailable"); }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>(); map.forEach((k, v) -> sorted.put((String) k, canonical(v))); return sorted;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(ContractFingerprint::canonical).toList();
        return value;
    }
}
