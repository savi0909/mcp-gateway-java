package dev.mcp.gateway.rest;

/** HTTP/JSON outcomes, independent of MCP serialization. Failures retain no upstream exception/body. */
public sealed interface AllocationResult {

    record ObjectSuccess(java.util.Map<String, Object> fields) implements AllocationResult {
        public ObjectSuccess {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }

        @Override
        public String toString() {
            return "ObjectSuccess[fields=<redacted>]";
        }
    }

    record Success(String id) implements AllocationResult {
        @Override
        public String toString() {
            return "Success[id=<redacted>]";
        }
    }

    record Failure(Code code) implements AllocationResult {
        public String message() {
            return code.message;
        }

        public String allocationOutcome() {
            return "unknown";
        }
    }

    enum Code {
        UPSTREAM_UNAVAILABLE("The backend could not be reached. The operation outcome is unknown."),
        UPSTREAM_TIMEOUT("The backend did not return within the configured deadline. The operation outcome is unknown."),
        UPSTREAM_HTTP_ERROR("The backend returned an unsuccessful HTTP status. The operation outcome is unknown."),
        UPSTREAM_INVALID_RESPONSE("The backend returned an unusable response. The operation outcome is unknown.");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
