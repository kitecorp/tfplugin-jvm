package cloud.kitelang.tfplugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenPgpSignatureVerifier} using REAL HashiCorp release artifacts.
 *
 * <p>Fixtures under {@code src/test/resources/pgp/} are the genuine, publicly published
 * signing material for {@code hashicorp/random 3.6.0}:
 * <ul>
 *   <li>{@code random_3.6.0_SHA256SUMS} — the checksums file HashiCorp signs</li>
 *   <li>{@code random_3.6.0_SHA256SUMS.sig} — the detached binary GPG signature (key 72D7468F)</li>
 *   <li>{@code hashicorp_34365D9472D7468F.asc} — HashiCorp's real ASCII-armored public key (the signer)</li>
 *   <li>{@code other_provider_key.asc} — a DIFFERENT real key (integrations/github, 38027F80D7FD5FB2)
 *       used to prove a wrong key is rejected</li>
 * </ul>
 *
 * <p>These assert the real security boundary this verifier defends: the genuine triple validates,
 * and both a tampered checksums file and a wrong signing key are rejected (fail-closed).
 */
class OpenPgpSignatureVerifierTest {

    private OpenPgpSignatureVerifier verifier;

    private byte[] realSums;
    private byte[] realSignature;
    private String hashicorpKey;
    private String wrongKey;

    @BeforeEach
    void setUp() throws IOException {
        verifier = new OpenPgpSignatureVerifier();
        realSums = readBytes("/pgp/random_3.6.0_SHA256SUMS");
        realSignature = readBytes("/pgp/random_3.6.0_SHA256SUMS.sig");
        hashicorpKey = new String(readBytes("/pgp/hashicorp_34365D9472D7468F.asc"), StandardCharsets.UTF_8);
        wrongKey = new String(readBytes("/pgp/other_provider_key.asc"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("should validate the genuine SHA256SUMS + signature + HashiCorp key triple")
    void shouldValidateGenuineTriple() {
        var valid = verifier.isValid(realSums, realSignature, hashicorpKey);

        assertTrue(valid, "genuine HashiCorp signature must validate against the real signing key");
    }

    @Test
    @DisplayName("should validate when the correct key is one of several advertised keys")
    void shouldValidateAgainstAnyWhenCorrectKeyPresent() {
        var valid = verifier.isValidAgainstAny(realSums, realSignature, List.of(wrongKey, hashicorpKey));

        assertTrue(valid, "verification must succeed if any advertised key is the genuine signer");
    }

    @Test
    @DisplayName("should reject a tampered SHA256SUMS file (fail closed)")
    void shouldRejectTamperedSums() {
        var tampered = realSums.clone();
        // Flip one byte of a checksum hex digit so the content no longer matches the signature.
        tampered[0] = (byte) (tampered[0] ^ 0x01);

        var valid = verifier.isValid(tampered, realSignature, hashicorpKey);

        assertFalse(valid, "a modified checksums file must not verify against the original signature");
    }

    @Test
    @DisplayName("should reject a wrong signing key (fail closed)")
    void shouldRejectWrongKey() {
        var valid = verifier.isValid(realSums, realSignature, wrongKey);

        assertFalse(valid, "a signature must not verify against a key that did not produce it");
    }

    @Test
    @DisplayName("should reject a corrupted signature (fail closed)")
    void shouldRejectCorruptedSignature() {
        var corrupted = realSignature.clone();
        corrupted[corrupted.length - 1] = (byte) (corrupted[corrupted.length - 1] ^ 0x01);

        var valid = verifier.isValid(realSums, corrupted, hashicorpKey);

        assertFalse(valid, "a bit-flipped signature must not verify");
    }

    @Test
    @DisplayName("should reject when no keys are advertised (fail closed)")
    void shouldRejectWhenNoKeysAdvertised() {
        var valid = verifier.isValidAgainstAny(realSums, realSignature, List.of());

        assertFalse(valid, "verification must fail closed when the registry advertises no signing key");
    }

    @Test
    @DisplayName("should reject empty or missing inputs (fail closed)")
    void shouldRejectEmptyInputs() {
        assertFalse(verifier.isValid(new byte[0], realSignature, hashicorpKey), "empty data must fail");
        assertFalse(verifier.isValid(realSums, new byte[0], hashicorpKey), "empty signature must fail");
        assertFalse(verifier.isValid(realSums, realSignature, "   "), "blank key must fail");
    }

    private static byte[] readBytes(String resource) throws IOException {
        try (InputStream in = OpenPgpSignatureVerifierTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing test fixture: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
