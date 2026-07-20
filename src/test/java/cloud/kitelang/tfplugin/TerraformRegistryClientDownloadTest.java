package cloud.kitelang.tfplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link TerraformRegistryClient}'s download-and-verify flow.
 *
 * <p>These drive the real {@link TerraformRegistryClient#ensureProvider} entry point against a local
 * {@link HttpServer} serving fixtures — no external network, so they are deterministic and never
 * skipped. Two kinds of fixtures are used:
 * <ul>
 *   <li><b>Real HashiCorp material</b> ({@code hashicorp/random 3.6.0}) to prove that GPG
 *       verification is actually wired into the flow: authentic sums pass verification (the flow
 *       advances to the checksum step), while a tampered signature or a missing key fail closed.</li>
 *   <li><b>A self-consistent generated triple</b> (throwaway PGP key signs a synthetic SHA256SUMS
 *       listing a synthetic zip) to prove the full happy path returns an installed binary once the
 *       signature <em>and</em> checksum both verify.</li>
 * </ul>
 */
class TerraformRegistryClientDownloadTest {

    /** A real entry in the captured {@code hashicorp/random 3.6.0} SHA256SUMS fixture. */
    private static final String REAL_ARTIFACT_FILENAME = "terraform-provider-random_3.6.0_linux_amd64.zip";

    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempCacheDir;

    private HttpServer server;
    private String base;
    private TerraformRegistryClient client;

    // Per-test response payloads the handler serves.
    private volatile String metadataJson;
    private volatile byte[] sumsBytes;
    private volatile byte[] sigBytes;
    private volatile byte[] zipBytes;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/versions")) {
                body = "{\"versions\":[{\"version\":\"3.6.0\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if (path.contains("/download/")) {
                body = metadataJson.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/SHA256SUMS.sig")) {
                body = sigBytes;
            } else if (path.endsWith("/SHA256SUMS")) {
                body = sumsBytes;
            } else if (path.endsWith("/artifact.zip")) {
                body = zipBytes;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort() + "/v1/providers";
        client = new TerraformRegistryClient(tempCacheDir, base);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("should install the binary when signature AND checksum both verify (full happy path)")
    void shouldInstallWhenSignatureAndChecksumValid() throws Exception {
        var keyPair = generatePgpKeyPair();
        var binaryContent = "PROVIDER-BINARY-CONTENT".getBytes(StandardCharsets.UTF_8);
        zipBytes = zipWithEntry("terraform-provider-random", binaryContent);
        sumsBytes = (sha256Hex(zipBytes) + "  " + REAL_ARTIFACT_FILENAME + "\n").getBytes(StandardCharsets.UTF_8);
        sigBytes = detachedSign(sumsBytes, keyPair);
        metadataJson = buildMetadata(List.of(armorPublicKey(keyPair.getPublicKey())));

        var installed = client.ensureProvider("hashicorp/random", "3.6.0");

        assertTrue(Files.exists(installed), "binary should be cached on disk");
        assertTrue(installed.toFile().canExecute(), "binary should be executable");
        assertArrayEquals(binaryContent, Files.readAllBytes(installed),
                "installed binary must be the exact bytes extracted from the verified zip");
    }

    @Test
    @DisplayName("should advance past verification for AUTHENTIC sums, then fail on the artifact checksum")
    void shouldAdvancePastVerificationForAuthenticSums() throws Exception {
        // Real HashiCorp sums + signature + key: the signature is genuine, so verification must PASS
        // and the flow must reach the checksum step. The served zip is a dummy, so the (now trusted)
        // checksum will not match — proving verification was wired in AND accepted authentic material.
        sumsBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS");
        sigBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS.sig");
        zipBytes = "not-the-real-provider".getBytes(StandardCharsets.UTF_8);
        metadataJson = buildMetadata(List.of(readFixtureString("/pgp/hashicorp_34365D9472D7468F.asc")));

        var ex = assertThrows(IOException.class,
                () -> client.ensureProvider("hashicorp/random", "3.6.0"));

        assertTrue(ex.getMessage().contains("SHA256 mismatch"),
                "authentic signature must pass so the flow reaches the checksum step; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("should reject a tampered SHA256SUMS signature in the download flow (fail closed)")
    void shouldRejectTamperedSignature() throws Exception {
        sumsBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS");
        var corruptedSig = readFixture("/pgp/random_3.6.0_SHA256SUMS.sig");
        corruptedSig[corruptedSig.length - 1] ^= 0x01;
        sigBytes = corruptedSig;
        zipBytes = "not-the-real-provider".getBytes(StandardCharsets.UTF_8);
        metadataJson = buildMetadata(List.of(readFixtureString("/pgp/hashicorp_34365D9472D7468F.asc")));

        var ex = assertThrows(IOException.class,
                () -> client.ensureProvider("hashicorp/random", "3.6.0"));

        assertTrue(ex.getMessage().contains("GPG signature verification FAILED"),
                "a corrupted signature must abort the install; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("should reject a wrong signing key in the download flow (fail closed)")
    void shouldRejectWrongSigningKey() throws Exception {
        // Authentic sums + signature, but the registry advertises a DIFFERENT provider's real key.
        sumsBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS");
        sigBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS.sig");
        zipBytes = "not-the-real-provider".getBytes(StandardCharsets.UTF_8);
        metadataJson = buildMetadata(List.of(readFixtureString("/pgp/other_provider_key.asc")));

        var ex = assertThrows(IOException.class,
                () -> client.ensureProvider("hashicorp/random", "3.6.0"));

        assertTrue(ex.getMessage().contains("GPG signature verification FAILED"),
                "a signature from a different key must not verify; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("should refuse to install when the registry advertises no signing key (fail closed)")
    void shouldRejectWhenNoSigningKeyAdvertised() throws Exception {
        sumsBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS");
        sigBytes = readFixture("/pgp/random_3.6.0_SHA256SUMS.sig");
        zipBytes = "not-the-real-provider".getBytes(StandardCharsets.UTF_8);
        metadataJson = buildMetadata(List.of()); // no gpg_public_keys

        var ex = assertThrows(IOException.class,
                () -> client.ensureProvider("hashicorp/random", "3.6.0"));

        assertTrue(ex.getMessage().contains("no GPG signing key"),
                "a release with no advertised key must be refused; got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Builds the download-metadata JSON, pointing the artifact/sums/sig URLs at the local server. */
    private String buildMetadata(List<String> asciiArmoredKeys) throws IOException {
        var keys = asciiArmoredKeys.stream()
                .map(armor -> Map.of("key_id", "TEST", "ascii_armor", armor))
                .toList();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("os", "linux");
        metadata.put("arch", "amd64");
        metadata.put("filename", REAL_ARTIFACT_FILENAME);
        metadata.put("download_url", base + "/files/artifact.zip");
        metadata.put("shasums_url", base + "/files/SHA256SUMS");
        metadata.put("shasums_signature_url", base + "/files/SHA256SUMS.sig");
        metadata.put("shasum", "unused-by-verification");
        metadata.put("signing_keys", Map.of("gpg_public_keys", keys));
        metadata.put("protocols", List.of("5.0"));
        return MAPPER.writeValueAsString(metadata);
    }

    private static PGPKeyPair generatePgpKeyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new JcaPGPKeyPair(
                PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, generator.generateKeyPair(), new Date());
    }

    private static byte[] detachedSign(byte[] data, PGPKeyPair keyPair) throws Exception {
        var signerBuilder = new JcaPGPContentSignerBuilder(
                keyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BOUNCY_CASTLE);
        var signatureGenerator = new PGPSignatureGenerator(signerBuilder, keyPair.getPublicKey());
        signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, keyPair.getPrivateKey());
        signatureGenerator.update(data);
        var out = new ByteArrayOutputStream();
        signatureGenerator.generate().encode(out);
        return out.toByteArray();
    }

    private static String armorPublicKey(PGPPublicKey publicKey) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var armored = ArmoredOutputStream.builder().build(out)) {
            publicKey.encode(armored);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] zipWithEntry(String entryName, byte[] content) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] readFixture(String resource) throws IOException {
        try (InputStream in = TerraformRegistryClientDownloadTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing test fixture: " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static String readFixtureString(String resource) throws IOException {
        return new String(readFixture(resource), StandardCharsets.UTF_8);
    }
}
