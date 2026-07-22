package cloud.kitelang.tfplugin;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.util.List;

/**
 * Verifies OpenPGP <em>detached</em> signatures (RFC 4880) — the signing scheme the
 * Terraform Registry uses for provider {@code SHA256SUMS} files.
 *
 * <p>A detached signature is a standalone blob that signs some external content; verifying it
 * proves the content was produced by the holder of the private key matching the given public key.
 * The Terraform Registry publishes, per provider release, a {@code SHA256SUMS} file, a detached
 * {@code SHA256SUMS.<keyid>.sig}, and the ASCII-armored public key(s) that signed it. This class
 * checks that triple.
 *
 * <p>All methods <strong>fail closed</strong>: any parse error, missing key, key-mismatch, or
 * cryptographic failure returns {@code false} rather than throwing. Callers MUST treat {@code false}
 * as "authenticity could not be established" and refuse to proceed — never warn-and-continue.
 */
@Slf4j
public class OpenPgpSignatureVerifier {

    /**
     * A private BouncyCastle provider instance passed explicitly to the JCA content verifier.
     * We deliberately avoid {@link java.security.Security#addProvider} so verification never
     * mutates the JVM-global provider list (the bridge runs inside a shared provider process).
     */
    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

    /** Creates a verifier. Instances are stateless; every check is fail-closed per-call. */
    public OpenPgpSignatureVerifier() {
    }

    /**
     * Verifies the detached signature against a set of candidate public keys, succeeding if
     * <em>any</em> of them is the genuine signer.
     *
     * <p>The registry may advertise more than one signing key for a namespace; a match on any of
     * them establishes trust, which mirrors Terraform's own behaviour.
     *
     * @param data                  the exact bytes that were signed (e.g. the raw SHA256SUMS file)
     * @param detachedSignature     the detached signature bytes (armored or binary)
     * @param asciiArmoredPublicKeys the candidate ASCII-armored public keys
     * @return {@code true} iff the signature validates against at least one key
     */
    public boolean isValidAgainstAny(byte[] data, byte[] detachedSignature, List<String> asciiArmoredPublicKeys) {
        if (asciiArmoredPublicKeys == null || asciiArmoredPublicKeys.isEmpty()) {
            // No advertised key → no trust anchor → cannot verify. Fail closed.
            return false;
        }
        return asciiArmoredPublicKeys.stream()
                .anyMatch(key -> isValid(data, detachedSignature, key));
    }

    /**
     * Verifies the detached signature against a single ASCII-armored public key.
     *
     * @param data                 the exact bytes that were signed
     * @param detachedSignature    the detached signature bytes (armored or binary)
     * @param asciiArmoredPublicKey the ASCII-armored public key block
     * @return {@code true} iff the signature is authentic for the given key
     */
    public boolean isValid(byte[] data, byte[] detachedSignature, String asciiArmoredPublicKey) {
        if (isEmpty(data) || isEmpty(detachedSignature)
                || asciiArmoredPublicKey == null || asciiArmoredPublicKey.isBlank()) {
            return false;
        }
        try {
            var signature = readSignature(detachedSignature);
            var keyRings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(
                            asciiArmoredPublicKey.getBytes(StandardCharsets.UTF_8))),
                    new JcaKeyFingerprintCalculator());

            var publicKey = keyRings.getPublicKey(signature.getKeyID());
            if (publicKey == null) {
                // The signature names a key that is NOT among those advertised → wrong key. Fail closed.
                log.warn("Signature key id {} is not present in the supplied public key(s)",
                        Long.toHexString(signature.getKeyID()));
                return false;
            }

            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BOUNCY_CASTLE), publicKey);
            signature.update(data);
            return signature.verify();
        } catch (Exception e) {
            // Broad catch is intentional: BouncyCastle throws PGPException/IOException and can throw
            // unchecked exceptions on malformed input. For a security check, EVERY failure mode must
            // collapse to "not verified" so a caller can never mistake an error for success.
            log.warn("OpenPGP signature verification errored (treating as invalid): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reads the first {@link PGPSignature} out of a detached-signature blob.
     *
     * @throws PGPException if the blob contains no OpenPGP signature
     */
    private static PGPSignature readSignature(byte[] detachedSignature) throws IOException, PGPException {
        try (InputStream decoder = PGPUtil.getDecoderStream(new ByteArrayInputStream(detachedSignature))) {
            var object = new JcaPGPObjectFactory(decoder).nextObject();
            if (object instanceof PGPSignatureList list && !list.isEmpty()) {
                return list.get(0);
            }
            if (object instanceof PGPSignature signature) {
                return signature;
            }
            throw new PGPException("Input does not contain an OpenPGP signature");
        }
    }

    private static boolean isEmpty(byte[] bytes) {
        return bytes == null || bytes.length == 0;
    }
}
