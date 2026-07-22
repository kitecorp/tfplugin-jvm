package cloud.kitelang.tfplugin;

/**
 * Exception thrown when the go-plugin handshake or lifecycle fails.
 *
 * <p>Covers handshake parse errors, timeouts, unsupported protocol versions,
 * unexpected process exits, and health-check failures.</p>
 */
public class GoPluginException extends RuntimeException {

    /**
     * Creates the exception with a message only, e.g. a handshake parse failure.
     *
     * @param message human-readable description of what went wrong
     */
    public GoPluginException(String message) {
        super(message);
    }

    /**
     * Creates the exception wrapping an underlying cause, e.g. an I/O failure
     * while reading the provider process's handshake line.
     *
     * @param message human-readable description of what went wrong
     * @param cause   the underlying failure
     */
    public GoPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
