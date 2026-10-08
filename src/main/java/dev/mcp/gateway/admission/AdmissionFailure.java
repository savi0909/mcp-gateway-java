package dev.mcp.gateway.admission;

/** Sanitized behavioral outcome, never upstream identity/policy diagnostics. */
public final class AdmissionFailure extends RuntimeException {
    private final String code;
    public AdmissionFailure(String code) { super("The operation could not be admitted.", null, false, false); this.code = code; }
    public String code() { return code; }
    public static AdmissionFailure denied() { return new AdmissionFailure("ACCESS_DENIED"); }
    public static AdmissionFailure unavailable() { return new AdmissionFailure("VERIFICATION_UNAVAILABLE"); }
}
