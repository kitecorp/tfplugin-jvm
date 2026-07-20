package cloud.kitelang.tfplugin;

/**
 * Exception thrown when the go-plugin handshake or lifecycle fails.
 *
 * <p>Covers handshake parse errors, timeouts, unsupported protocol versions,
 * unexpected process exits, and health-check failures.</p>
 */
public class GoPluginException extends RuntimeException {

    public GoPluginException(String message) {
        super(message);
    }

    public GoPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
