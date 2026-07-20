package cloud.kitelang.tfplugin;

/**
 * Protocol-neutral Terraform diagnostic, decoupled from the generated
 * tfplugin5/tfplugin6 message classes so handlers can process diagnostics
 * without knowing which protocol version produced them.
 *
 * @param severity      the diagnostic severity
 * @param summary       short human-readable problem summary
 * @param detail        optional longer description (empty string when absent)
 * @param attributePath the offending attribute within the config/state this
 *                      diagnostic refers to, or null when the provider did not
 *                      attach one
 */
public record TfDiagnostic(Severity severity, String summary, String detail,
                           TfAttributePath attributePath) {

    /** Creates a diagnostic without an attribute path. */
    public TfDiagnostic(Severity severity, String summary, String detail) {
        this(severity, summary, detail, null);
    }

    /** Mirrors the {@code Diagnostic.Severity} enum shared by both protocol versions. */
    public enum Severity {
        INVALID,
        ERROR,
        WARNING
    }

    /** True for error-severity diagnostics — the operation's result cannot be trusted. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
