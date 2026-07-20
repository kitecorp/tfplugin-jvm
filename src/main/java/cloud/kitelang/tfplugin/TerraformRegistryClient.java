package cloud.kitelang.tfplugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;

/**
 * Client for the public Terraform Registry (registry.terraform.io).
 *
 * <p>Downloads Terraform provider binaries and caches them locally under a structured
 * directory layout: {@code {cacheDir}/{namespace}/{type}/{version}/terraform-provider-{type}}.
 *
 * <p>Supports Terraform-style version constraints:
 * <ul>
 *   <li>{@code ~> 5.0} — pessimistic: {@code >= 5.0.0, < 6.0.0}</li>
 *   <li>{@code ~> 5.82.0} — pessimistic: {@code >= 5.82.0, < 5.83.0}</li>
 *   <li>{@code >= 5.0} — minimum version</li>
 *   <li>{@code = 5.82.0} or {@code 5.82.0} — exact version</li>
 *   <li>{@code >= 5.0, < 6.0} — compound (comma-separated)</li>
 *   <li>{@code null} or empty — latest available</li>
 * </ul>
 *
 * @see <a href="https://developer.hashicorp.com/terraform/registry/api-docs">Terraform Registry API</a>
 */
@Slf4j
public class TerraformRegistryClient {

    private static final String DEFAULT_REGISTRY_BASE = "https://registry.terraform.io/v1/providers";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cacheDir;
    private final String registryBase;
    private final HttpClient httpClient;
    private final OpenPgpSignatureVerifier signatureVerifier = new OpenPgpSignatureVerifier();

    /**
     * Creates a new registry client with the given local cache directory, talking to the
     * public Terraform Registry.
     *
     * @param cacheDir root directory for cached provider binaries
     *                 (e.g. {@code ~/.kite/providers/tf/})
     */
    public TerraformRegistryClient(Path cacheDir) {
        this(cacheDir, DEFAULT_REGISTRY_BASE);
    }

    /**
     * Creates a registry client pointed at an explicit registry base URL.
     *
     * <p>Package-private: production code uses the public constructor against the real registry;
     * this overload lets tests point the client at a local server serving captured fixtures so the
     * full download-and-verify flow can be exercised without external network access.
     *
     * @param cacheDir     root directory for cached provider binaries
     * @param registryBase base URL of the provider registry (no trailing slash)
     */
    TerraformRegistryClient(Path cacheDir, String registryBase) {
        this.cacheDir = cacheDir;
        this.registryBase = registryBase;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Ensures a provider binary is available locally, downloading it from the
     * Terraform Registry if not already cached.
     *
     * @param providerAddress address in the form {@code "aws"} (defaults to hashicorp namespace)
     *                        or {@code "hashicorp/aws"}
     * @param versionConstraint Terraform-style version constraint, or null/empty for latest
     * @return path to the executable provider binary
     * @throws IOException if download or filesystem operations fail
     */
    public Path ensureProvider(String providerAddress, String versionConstraint) throws IOException {
        var address = parseProviderAddress(providerAddress);
        var namespace = address.namespace();
        var type = address.type();

        log.info("Ensuring provider {}/{} with constraint '{}'", namespace, type, versionConstraint);

        // Check if a specific version is already cached (exact match only)
        if (isExactVersion(versionConstraint) && isCached(namespace, type, versionConstraint.trim())) {
            var cachedPath = getCachedPath(namespace, type, versionConstraint.trim());
            log.info("Provider {}/{} v{} already cached at {}", namespace, type, versionConstraint.trim(), cachedPath);
            return cachedPath;
        }

        // Fetch available versions from registry
        var availableVersions = listVersions(namespace, type);
        var resolvedVersion = resolveVersion(availableVersions, versionConstraint);
        log.info("Resolved version constraint '{}' to {}", versionConstraint, resolvedVersion);

        // Check cache with resolved version
        if (isCached(namespace, type, resolvedVersion)) {
            var cachedPath = getCachedPath(namespace, type, resolvedVersion);
            log.info("Provider {}/{} v{} already cached at {}", namespace, type, resolvedVersion, cachedPath);
            return cachedPath;
        }

        // Download the provider binary
        return downloadProvider(namespace, type, resolvedVersion);
    }

    /**
     * Lists available versions for a provider from the Terraform Registry.
     *
     * @param namespace provider namespace (e.g. {@code "hashicorp"})
     * @param type provider type (e.g. {@code "aws"})
     * @return list of version strings
     * @throws IOException if the HTTP call fails
     */
    public List<String> listVersions(String namespace, String type) throws IOException {
        var url = "%s/%s/%s/versions".formatted(registryBase, namespace, type);
        log.debug("Fetching provider versions from {}", url);

        var responseBody = httpGet(url);
        var versionsResponse = JSON.readValue(responseBody, VersionsResponse.class);

        return versionsResponse.versions().stream()
                .map(VersionEntry::version)
                .toList();
    }

    /**
     * Resolves a Terraform-style version constraint against a list of available versions.
     *
     * <p>Returns the highest version that satisfies all constraints. Supports:
     * exact, pessimistic ({@code ~>}), comparison ({@code >=}, {@code <=}, {@code >}, {@code <}),
     * compound (comma-separated), and null/empty (latest).
     *
     * @param availableVersions list of available version strings
     * @param constraint the version constraint string, or null/empty for latest
     * @return the resolved version string
     * @throws IllegalArgumentException if no version matches the constraint
     */
    public String resolveVersion(List<String> availableVersions, String constraint) {
        if (availableVersions == null || availableVersions.isEmpty()) {
            throw new IllegalArgumentException("No versions available to resolve against");
        }

        // Filter out pre-release versions (e.g. "3.7.0-alpha1") unless the constraint
        // explicitly targets one. Terraform convention: hyphens denote pre-release.
        var stable = availableVersions.stream()
                .filter(v -> !v.contains("-"))
                .toList();
        var candidates = stable.isEmpty() ? availableVersions : stable;

        // Sort descending so we pick the highest match first
        var sorted = candidates.stream()
                .sorted((a, b) -> compareVersions(b, a))
                .toList();

        // Null, empty, or blank → latest
        if (constraint == null || constraint.isBlank()) {
            return sorted.getFirst();
        }

        var trimmed = constraint.trim();

        // Compound constraints: split on comma and check all
        var constraints = Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .toList();

        return sorted.stream()
                .filter(version -> constraints.stream().allMatch(c -> satisfiesConstraint(version, c)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No version from %s satisfies constraint '%s'".formatted(availableVersions, constraint)));
    }

    /**
     * Checks whether a provider binary is already cached and executable.
     *
     * @param namespace provider namespace
     * @param type provider type
     * @param version provider version
     * @return true if the binary exists and is executable
     */
    public boolean isCached(String namespace, String type, String version) {
        var path = getCachedPath(namespace, type, version);
        return Files.exists(path) && path.toFile().canExecute();
    }

    /**
     * Returns the filesystem path where a provider binary would be cached.
     *
     * @param namespace provider namespace (e.g. {@code "hashicorp"})
     * @param type provider type (e.g. {@code "aws"})
     * @param version provider version (e.g. {@code "5.82.0"})
     * @return path to the binary: {@code {cacheDir}/{namespace}/{type}/{version}/terraform-provider-{type}}
     */
    public Path getCachedPath(String namespace, String type, String version) {
        return cacheDir
                .resolve(namespace)
                .resolve(type)
                .resolve(version)
                .resolve("terraform-provider-" + type);
    }

    // ---------------------------------------------------------------
    // Provider address parsing
    // ---------------------------------------------------------------

    /**
     * Parsed provider address containing namespace and type.
     *
     * @param namespace the provider namespace (e.g. {@code "hashicorp"})
     * @param type the provider type (e.g. {@code "aws"})
     */
    public record ProviderAddress(String namespace, String type) {}

    /**
     * Parses a provider address string into namespace and type components.
     *
     * <p>If only a type is given (e.g. {@code "aws"}), the namespace defaults
     * to {@code "hashicorp"}.
     *
     * @param address provider address like {@code "aws"} or {@code "hashicorp/aws"}
     * @return parsed address with namespace and type
     * @throws IllegalArgumentException if the address is null or empty
     */
    public static ProviderAddress parseProviderAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Provider address must not be null or empty");
        }

        var trimmed = address.trim();
        var slashIndex = trimmed.indexOf('/');

        if (slashIndex < 0) {
            return new ProviderAddress("hashicorp", trimmed);
        }

        var namespace = trimmed.substring(0, slashIndex);
        var type = trimmed.substring(slashIndex + 1);
        return new ProviderAddress(namespace, type);
    }

    // ---------------------------------------------------------------
    // Platform detection
    // ---------------------------------------------------------------

    /**
     * Maps a Java {@code os.name} value to the Terraform platform OS string.
     *
     * @param osName the value of {@code System.getProperty("os.name")}
     * @return terraform-style OS name ({@code "darwin"}, {@code "linux"}, or {@code "windows"})
     */
    static String mapOsName(String osName) {
        if (osName == null) {
            throw new IllegalArgumentException("OS name must not be null");
        }
        var lower = osName.toLowerCase();
        if (lower.contains("mac") || lower.contains("darwin")) return "darwin";
        if (lower.contains("linux")) return "linux";
        if (lower.contains("windows")) return "windows";
        throw new IllegalArgumentException("Unsupported OS: " + osName);
    }

    /**
     * Maps a Java {@code os.arch} value to the Terraform platform architecture string.
     *
     * @param archName the value of {@code System.getProperty("os.arch")}
     * @return terraform-style architecture ({@code "amd64"} or {@code "arm64"})
     */
    static String mapArchName(String archName) {
        if (archName == null) {
            throw new IllegalArgumentException("Architecture name must not be null");
        }
        return switch (archName) {
            case "aarch64" -> "arm64";
            case "x86_64", "amd64" -> "amd64";
            default -> archName;
        };
    }

    /**
     * Detects the current platform's OS string for the Terraform Registry.
     */
    private static String detectOs() {
        return mapOsName(System.getProperty("os.name"));
    }

    /**
     * Detects the current platform's architecture string for the Terraform Registry.
     */
    private static String detectArch() {
        return mapArchName(System.getProperty("os.arch"));
    }

    // ---------------------------------------------------------------
    // Version comparison (semantic versioning)
    // ---------------------------------------------------------------

    /**
     * Compares two semantic version strings numerically, segment by segment.
     *
     * <p>Shorter versions are zero-padded: {@code "5.0"} equals {@code "5.0.0"}.
     *
     * @param a first version string
     * @param b second version string
     * @return negative if a &lt; b, zero if equal, positive if a &gt; b
     */
    static int compareVersions(String a, String b) {
        var partsA = a.split("\\.");
        var partsB = b.split("\\.");
        var maxLen = Math.max(partsA.length, partsB.length);

        for (var i = 0; i < maxLen; i++) {
            var segA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            var segB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (segA != segB) {
                return Integer.compare(segA, segB);
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------
    // Version constraint evaluation
    // ---------------------------------------------------------------

    /**
     * Tests whether a single version satisfies a single constraint clause.
     *
     * @param version the version to test
     * @param constraint a single constraint (e.g. {@code ">= 5.0"}, {@code "~> 5.82.0"})
     * @return true if the version satisfies the constraint
     */
    private static boolean satisfiesConstraint(String version, String constraint) {
        var trimmed = constraint.trim();

        // Pessimistic operator: ~>
        if (trimmed.startsWith("~>")) {
            return satisfiesPessimistic(version, trimmed.substring(2).trim());
        }

        // Comparison operators (order matters: >= before >, <= before <)
        if (trimmed.startsWith(">=")) {
            return compareVersions(version, normalizeVersion(trimmed.substring(2).trim())) >= 0;
        }
        if (trimmed.startsWith("<=")) {
            return compareVersions(version, normalizeVersion(trimmed.substring(2).trim())) <= 0;
        }
        if (trimmed.startsWith(">")) {
            return compareVersions(version, normalizeVersion(trimmed.substring(1).trim())) > 0;
        }
        if (trimmed.startsWith("<")) {
            return compareVersions(version, normalizeVersion(trimmed.substring(1).trim())) < 0;
        }
        if (trimmed.startsWith("=")) {
            return compareVersions(version, normalizeVersion(trimmed.substring(1).trim())) == 0;
        }

        // Bare version string → exact match
        return compareVersions(version, normalizeVersion(trimmed)) == 0;
    }

    /**
     * Evaluates the pessimistic constraint operator ({@code ~>}).
     *
     * <p>The rightmost segment is allowed to increment freely; the segment before it
     * defines the upper boundary:
     * <ul>
     *   <li>{@code ~> 5.0} (2 segments) → {@code >= 5.0.0, < 6.0.0}</li>
     *   <li>{@code ~> 5.82} (2 segments) → {@code >= 5.82.0, < 6.0.0}</li>
     *   <li>{@code ~> 5.82.0} (3 segments) → {@code >= 5.82.0, < 5.83.0}</li>
     * </ul>
     */
    private static boolean satisfiesPessimistic(String version, String constraintVersion) {
        var parts = constraintVersion.split("\\.");
        var lowerBound = normalizeVersion(constraintVersion);

        // Build the upper bound by incrementing the second-to-last segment
        // and dropping everything after it.
        // For 2 segments (X.Y): increment X → upper is (X+1).0
        // For 3 segments (X.Y.Z): increment Y → upper is X.(Y+1).0
        var upperParts = new int[parts.length];
        for (var i = 0; i < parts.length; i++) {
            upperParts[i] = Integer.parseInt(parts[i]);
        }

        var incrementIndex = parts.length - 2;
        if (incrementIndex < 0) {
            // Single segment (unusual, treat like exact)
            incrementIndex = 0;
        }
        upperParts[incrementIndex]++;

        // Zero out all segments after the incremented one
        for (var i = incrementIndex + 1; i < upperParts.length; i++) {
            upperParts[i] = 0;
        }

        var upperBound = buildVersionString(upperParts);

        return compareVersions(version, lowerBound) >= 0
                && compareVersions(version, upperBound) < 0;
    }

    /**
     * Normalizes a version string to at least 3 segments (e.g. {@code "5.0"} → {@code "5.0.0"}).
     */
    private static String normalizeVersion(String version) {
        var parts = version.split("\\.");
        if (parts.length >= 3) return version;
        var sb = new StringBuilder(version);
        for (var i = parts.length; i < 3; i++) {
            sb.append(".0");
        }
        return sb.toString();
    }

    /**
     * Builds a dot-separated version string from integer segments.
     */
    private static String buildVersionString(int[] segments) {
        var sb = new StringBuilder();
        for (var i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    /**
     * Checks whether a constraint string represents an exact version (no operators).
     */
    private static boolean isExactVersion(String constraint) {
        if (constraint == null || constraint.isBlank()) return false;
        var trimmed = constraint.trim();
        return !trimmed.startsWith("~>") && !trimmed.startsWith(">=") && !trimmed.startsWith("<=")
                && !trimmed.startsWith(">") && !trimmed.startsWith("<") && !trimmed.startsWith("=")
                && !trimmed.contains(",");
    }

    // ---------------------------------------------------------------
    // Download flow
    // ---------------------------------------------------------------

    /**
     * Downloads a provider binary from the Terraform Registry, verifies it against a
     * GPG-signed checksum, extracts it from the zip archive, and caches it locally.
     *
     * <p>Verification is fail-closed: the binary is only cached once the registry-advertised GPG
     * signature over the {@code SHA256SUMS} file is valid <em>and</em> the binary matches the
     * authenticated checksum (see {@link #verifyAndResolveChecksum(DownloadMetadata)}).
     */
    private Path downloadProvider(String namespace, String type, String version) throws IOException {
        var os = detectOs();
        var arch = detectArch();
        log.info("Downloading provider {}/{} v{} for {}/{}", namespace, type, version, os, arch);

        // 1. Get download metadata
        var downloadUrl = "%s/%s/%s/%s/download/%s/%s".formatted(
                registryBase, namespace, type, version, os, arch);
        var metadataBody = httpGet(downloadUrl);
        var metadata = JSON.readValue(metadataBody, DownloadMetadata.class);

        // 2. Download the zip to a temp file
        var tempZip = Files.createTempFile("terraform-provider-", ".zip");
        try {
            downloadFile(metadata.downloadUrl(), tempZip);

            // 3. Establish an AUTHENTICATED checksum, then verify the binary against it.
            //    Verifying only the checksum the registry hands back in JSON is not enough — the
            //    SHA256SUMS file is fetched over the network (often from a mirror) and is only
            //    trustworthy once its detached GPG signature is checked against the provider's key.
            var expectedSha = verifyAndResolveChecksum(metadata);
            verifySha256(tempZip, expectedSha);

            // 4. Extract the binary from the zip
            var binaryPath = getCachedPath(namespace, type, version);
            Files.createDirectories(binaryPath.getParent());
            extractBinaryFromZip(tempZip, binaryPath, type);

            // 5. Set executable permission
            if (!binaryPath.toFile().setExecutable(true)) {
                log.warn("Failed to set executable permission on {}", binaryPath);
            }

            log.info("Provider {}/{} v{} cached at {}", namespace, type, version, binaryPath);
            return binaryPath;
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /**
     * Verifies the GPG signature over the provider's {@code SHA256SUMS} file and returns the
     * authenticated SHA256 checksum for the artifact being installed.
     *
     * <p><b>Trust chain (mirrors Terraform's own).</b> The registry download response advertises,
     * for the release, a {@code SHA256SUMS} URL, a detached {@code SHA256SUMS.<keyid>.sig} URL, and
     * the ASCII-armored GPG public key(s) that signed the sums file. We:
     * <ol>
     *   <li>download the {@code SHA256SUMS} file and its detached signature;</li>
     *   <li>verify the signature against the advertised public key(s) — this is the trust anchor;</li>
     *   <li>read the artifact's expected checksum from the now-trusted {@code SHA256SUMS}.</li>
     * </ol>
     * The caller then confirms the downloaded binary hashes to that checksum. Doing the checksum
     * check <em>without</em> first verifying the sums file's signature is the hole this closes: a
     * compromised mirror could otherwise serve a matching checksum for a malicious binary.
     *
     * <p><b>Trust assumption (a real security boundary).</b> We trust whatever GPG key the registry
     * advertises for the provider's namespace. This is exactly Terraform's model: HashiCorp's own
     * providers are signed by HashiCorp's key, while third-party providers advertise their own key
     * via the registry. The registry (reached over TLS) is therefore the root of trust for which key
     * is authoritative for a namespace; we do not pin keys out-of-band.
     *
     * <p><b>Fail closed.</b> A missing key, missing sums/signature URL, a signature that does not
     * verify, or an artifact absent from the signed sums all abort the install with an error — never
     * a warning-and-continue.
     *
     * @param metadata the registry download metadata
     * @return the hex-encoded SHA256 checksum, proven to come from a signed {@code SHA256SUMS}
     * @throws IOException if any part of the trust chain cannot be established
     */
    private String verifyAndResolveChecksum(DownloadMetadata metadata) throws IOException {
        var publicKeys = advertisedPublicKeys(metadata);
        if (publicKeys.isEmpty()) {
            throw new IOException(
                    "Refusing to install %s: registry advertised no GPG signing key to verify the SHA256SUMS"
                            .formatted(metadata.filename()));
        }
        if (isBlank(metadata.shasumsUrl()) || isBlank(metadata.shasumsSignatureUrl())) {
            throw new IOException(
                    "Refusing to install %s: registry response is missing the SHA256SUMS or signature URL"
                            .formatted(metadata.filename()));
        }

        var shasums = httpGetBytes(metadata.shasumsUrl());
        var signature = httpGetBytes(metadata.shasumsSignatureUrl());

        if (!signatureVerifier.isValidAgainstAny(shasums, signature, publicKeys)) {
            throw new IOException(
                    "GPG signature verification FAILED for the SHA256SUMS of %s — refusing to install "
                            .formatted(metadata.filename())
                            + "(the checksums file is not authentic; a mirror may be compromised)");
        }
        log.info("GPG signature verified for {} against the registry-advertised signing key",
                metadata.filename());

        var sumsContent = new String(shasums, StandardCharsets.UTF_8);
        return parseExpectedChecksum(sumsContent, metadata.filename())
                .orElseThrow(() -> new IOException(
                        "The signed SHA256SUMS does not list artifact %s".formatted(metadata.filename())));
    }

    /**
     * Extracts the ASCII-armored GPG public keys the registry advertises for the provider, or an
     * empty list if none are present.
     */
    private static List<String> advertisedPublicKeys(DownloadMetadata metadata) {
        if (metadata.signingKeys() == null || metadata.signingKeys().gpgPublicKeys() == null) {
            return List.of();
        }
        return metadata.signingKeys().gpgPublicKeys().stream()
                .map(GpgPublicKey::asciiArmor)
                .filter(armor -> armor != null && !armor.isBlank())
                .toList();
    }

    /**
     * Finds the expected SHA256 checksum for a given filename inside a {@code SHA256SUMS} file.
     *
     * <p>Each line is {@code <hex-sha256>  <filename>} (coreutils format; the filename may carry a
     * leading {@code *} binary-mode marker). Returns empty if the filename is not listed.
     *
     * @param sha256sumsContent the full text of the SHA256SUMS file
     * @param filename          the artifact filename to look up
     * @return the hex checksum for that filename, if present
     */
    static Optional<String> parseExpectedChecksum(String sha256sumsContent, String filename) {
        if (sha256sumsContent == null || filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        for (var line : sha256sumsContent.split("\\R")) {
            var parts = line.strip().split("\\s+", 2);
            if (parts.length != 2) {
                continue;
            }
            var entryName = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            if (entryName.equals(filename)) {
                return Optional.of(parts[0]);
            }
        }
        return Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Sends an HTTP GET request and returns the raw response body bytes.
     *
     * <p>Used for binary/opaque payloads (the SHA256SUMS signature) and content whose exact bytes
     * matter for cryptographic verification (the SHA256SUMS file itself).
     *
     * @param url the URL to fetch
     * @return the response body bytes
     * @throws IOException if the request fails or returns a non-2xx status
     */
    private byte[] httpGetBytes(String url) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP %d from %s".formatted(response.statusCode(), url));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + url, e);
        }
    }

    /**
     * Sends an HTTP GET request and returns the response body as a string.
     *
     * @param url the URL to fetch
     * @return the response body
     * @throws IOException if the request fails or returns a non-2xx status
     */
    private String httpGet(String url) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP %d from %s: %s".formatted(
                        response.statusCode(), url, response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + url, e);
        }
    }

    /**
     * Downloads a file from the given URL to the target path.
     */
    private void downloadFile(String url, Path target) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP %d downloading %s".formatted(response.statusCode(), url));
            }
            try (InputStream body = response.body()) {
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Verifies the SHA256 checksum of a file.
     *
     * @param file the file to verify
     * @param expectedSha256 the expected hex-encoded SHA256 hash
     * @throws IOException if the checksum does not match or the file cannot be read
     */
    private static void verifySha256(Path file, String expectedSha256) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(file)) {
                var buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            var actualHash = HexFormat.of().formatHex(digest.digest());

            if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "SHA256 mismatch: expected %s, got %s".formatted(expectedSha256, actualHash));
            }
            log.debug("SHA256 checksum verified: {}", actualHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Extracts the provider binary from a zip archive.
     *
     * <p>Looks for a file named {@code terraform-provider-{type}} (with or without {@code .exe})
     * inside the zip and copies it to the target path.
     *
     * @param zipFile path to the zip archive
     * @param targetPath path where the binary should be written
     * @param type the provider type name (to locate the correct entry)
     * @throws IOException if the zip does not contain the expected binary
     */
    private static void extractBinaryFromZip(Path zipFile, Path targetPath, String type)
            throws IOException {
        var binaryPrefix = "terraform-provider-" + type;

        try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                var entryName = entry.getName();
                // Match the binary by prefix (handles .exe suffix on Windows)
                if (!entry.isDirectory() && entryName.contains(binaryPrefix)) {
                    Files.copy(zis, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Extracted {} from zip", entryName);
                    return;
                }
                entry = zis.getNextEntry();
            }
        }

        throw new IOException(
                "Provider binary '%s' not found in zip archive %s".formatted(binaryPrefix, zipFile));
    }

    // ---------------------------------------------------------------
    // JSON response DTOs (records)
    // ---------------------------------------------------------------

    /** Response from the versions API endpoint. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionsResponse(List<VersionEntry> versions) {}

    /** A single version entry from the versions API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionEntry(String version, List<String> protocols, List<PlatformEntry> platforms) {}

    /** A platform entry (os/arch) from the versions API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlatformEntry(String os, String arch) {}

    /**
     * Response from the download URL API endpoint.
     *
     * <p>{@code shasum} is the per-artifact checksum echoed in the JSON; we do NOT trust it directly
     * for verification. The authoritative checksum is read from the {@code SHA256SUMS} file (at
     * {@code shasumsUrl}) only after its detached signature (at {@code shasumsSignatureUrl}) is
     * verified against one of the advertised {@link #signingKeys() signing keys}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DownloadMetadata(
            String os,
            String arch,
            String filename,
            @JsonProperty("download_url") String downloadUrl,
            @JsonProperty("shasums_url") String shasumsUrl,
            @JsonProperty("shasums_signature_url") String shasumsSignatureUrl,
            String shasum,
            @JsonProperty("signing_keys") SigningKeys signingKeys,
            List<String> protocols
    ) {}

    /** The {@code signing_keys} object of a download response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SigningKeys(@JsonProperty("gpg_public_keys") List<GpgPublicKey> gpgPublicKeys) {}

    /** A single advertised GPG public key. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GpgPublicKey(
            @JsonProperty("key_id") String keyId,
            @JsonProperty("ascii_armor") String asciiArmor
    ) {}
}
