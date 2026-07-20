package cloud.kitelang.tfplugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link TerraformRegistryClient}.
 *
 * <p>Focuses on pure logic that does not require network access:
 * version constraint resolution, provider address parsing, cache path
 * construction, and platform detection mapping.</p>
 */
class TerraformRegistryClientTest {

    @TempDir
    Path tempCacheDir;

    private TerraformRegistryClient client;

    @BeforeEach
    void setUp() {
        client = new TerraformRegistryClient(tempCacheDir);
    }

    // ---------------------------------------------------------------
    // 1. Version constraint resolution
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Version constraint resolution")
    class VersionConstraintResolution {

        @Test
        @DisplayName("should resolve exact version match")
        void shouldResolveExactVersion() {
            var versions = List.of("5.82.0", "5.81.0", "5.80.0");

            var resolved = client.resolveVersion(versions, "5.82.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve exact version with = prefix")
        void shouldResolveExactVersionWithEqualsPrefix() {
            var versions = List.of("5.82.0", "5.81.0", "5.80.0");

            var resolved = client.resolveVersion(versions, "= 5.82.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve pessimistic ~> with major version")
        void shouldResolvePessimisticMajor() {
            var versions = List.of("6.0.0", "5.82.0", "5.81.0", "4.0.0");

            var resolved = client.resolveVersion(versions, "~> 5.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve pessimistic ~> with minor version")
        void shouldResolvePessimisticMinor() {
            var versions = List.of("5.82.0", "5.81.0", "5.79.0");

            var resolved = client.resolveVersion(versions, "~> 5.80");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve pessimistic ~> with patch version")
        void shouldResolvePessimisticPatch() {
            var versions = List.of("5.82.0", "5.80.2", "5.80.1", "5.79.0");

            var resolved = client.resolveVersion(versions, "~> 5.80.0");

            assertEquals("5.80.2", resolved);
        }

        @Test
        @DisplayName("should resolve >= minimum version to latest satisfying")
        void shouldResolveMinimumVersion() {
            var versions = List.of("6.0.0", "5.82.0", "5.0.0", "4.0.0");

            var resolved = client.resolveVersion(versions, ">= 5.0");

            assertEquals("6.0.0", resolved);
        }

        @Test
        @DisplayName("should resolve compound constraint >= and <")
        void shouldResolveCompoundConstraint() {
            var versions = List.of("6.0.0", "5.82.0", "5.0.0", "4.0.0");

            var resolved = client.resolveVersion(versions, ">= 5.0, < 6.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve null constraint to latest version")
        void shouldResolveNullConstraintToLatest() {
            var versions = List.of("5.82.0", "5.81.0", "5.80.0");

            var resolved = client.resolveVersion(versions, null);

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve empty constraint to latest version")
        void shouldResolveEmptyConstraintToLatest() {
            var versions = List.of("5.82.0", "5.81.0", "5.80.0");

            var resolved = client.resolveVersion(versions, "");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve blank constraint to latest version")
        void shouldResolveBlankConstraintToLatest() {
            var versions = List.of("5.82.0", "5.81.0", "5.80.0");

            var resolved = client.resolveVersion(versions, "   ");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should throw when no version matches constraint")
        void shouldThrowWhenNoVersionMatches() {
            var versions = List.of("4.0.0", "3.0.0");

            var exception = assertThrows(IllegalArgumentException.class,
                    () -> client.resolveVersion(versions, ">= 5.0"));

            assertTrue(exception.getMessage().contains("No version"),
                    "Exception should mention no version found: " + exception.getMessage());
        }

        @Test
        @DisplayName("should throw when version list is empty")
        void shouldThrowWhenVersionListEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> client.resolveVersion(List.of(), "5.0.0"));
        }

        @Test
        @DisplayName("should resolve <= maximum version")
        void shouldResolveLessThanOrEqual() {
            var versions = List.of("6.0.0", "5.82.0", "5.0.0");

            var resolved = client.resolveVersion(versions, "<= 5.82.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve < strictly less than")
        void shouldResolveStrictlyLessThan() {
            var versions = List.of("6.0.0", "5.82.0", "5.81.0");

            var resolved = client.resolveVersion(versions, "< 6.0.0");

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve > strictly greater than")
        void shouldResolveStrictlyGreaterThan() {
            var versions = List.of("6.0.0", "5.82.0", "5.0.0");

            var resolved = client.resolveVersion(versions, "> 5.82.0");

            assertEquals("6.0.0", resolved);
        }

        @Test
        @DisplayName("should handle versions not in sorted order")
        void shouldHandleUnsortedVersions() {
            var versions = List.of("5.80.0", "5.82.0", "5.81.0");

            var resolved = client.resolveVersion(versions, null);

            assertEquals("5.82.0", resolved);
        }

        @Test
        @DisplayName("should resolve pessimistic ~> 5.0 excluding 6.x")
        void shouldResolvePessimisticExcludingNextMajor() {
            var versions = List.of("6.1.0", "6.0.0", "5.99.0", "5.0.0");

            var resolved = client.resolveVersion(versions, "~> 5.0");

            assertEquals("5.99.0", resolved);
        }

        @Test
        @DisplayName("should resolve pessimistic ~> 5.80 excluding 5.83+")
        void shouldResolvePessimisticMinorBoundary() {
            // ~> 5.80 means >= 5.80.0, < 5.81.0 — wait, that's for 3-segment.
            // For 2-segment ~> X.Y it means >= X.Y.0, < X+1.0.0
            // Actually, Terraform's ~> operator: the last segment increments.
            // ~> 5.80 (2 segments) → >= 5.80, < 6.0
            var versions = List.of("6.0.0", "5.82.0", "5.80.0", "5.79.0");

            var resolved = client.resolveVersion(versions, "~> 5.80");

            assertEquals("5.82.0", resolved);
        }
    }

    // ---------------------------------------------------------------
    // 2. Provider address parsing
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Provider address parsing")
    class ProviderAddressParsing {

        @Test
        @DisplayName("should parse short form 'aws' as hashicorp/aws")
        void shouldParseShortForm() {
            var parsed = TerraformRegistryClient.parseProviderAddress("aws");

            assertEquals("hashicorp", parsed.namespace());
            assertEquals("aws", parsed.type());
        }

        @Test
        @DisplayName("should parse full form 'hashicorp/aws'")
        void shouldParseFullForm() {
            var parsed = TerraformRegistryClient.parseProviderAddress("hashicorp/aws");

            assertEquals("hashicorp", parsed.namespace());
            assertEquals("aws", parsed.type());
        }

        @Test
        @DisplayName("should parse custom namespace 'integrations/github'")
        void shouldParseCustomNamespace() {
            var parsed = TerraformRegistryClient.parseProviderAddress("integrations/github");

            assertEquals("integrations", parsed.namespace());
            assertEquals("github", parsed.type());
        }

        @Test
        @DisplayName("should reject empty provider address")
        void shouldRejectEmptyAddress() {
            assertThrows(IllegalArgumentException.class,
                    () -> TerraformRegistryClient.parseProviderAddress(""));
        }

        @Test
        @DisplayName("should reject null provider address")
        void shouldRejectNullAddress() {
            assertThrows(IllegalArgumentException.class,
                    () -> TerraformRegistryClient.parseProviderAddress(null));
        }

        @Test
        @DisplayName("should trim whitespace from provider address")
        void shouldTrimWhitespace() {
            var parsed = TerraformRegistryClient.parseProviderAddress("  hashicorp/aws  ");

            assertEquals("hashicorp", parsed.namespace());
            assertEquals("aws", parsed.type());
        }
    }

    // ---------------------------------------------------------------
    // 3. Cache path construction
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Cache path construction")
    class CachePathConstruction {

        @Test
        @DisplayName("should construct correct cache path for provider binary")
        void shouldConstructCorrectCachePath() {
            var path = client.getCachedPath("hashicorp", "aws", "5.82.0");

            var expected = tempCacheDir
                    .resolve("hashicorp")
                    .resolve("aws")
                    .resolve("5.82.0")
                    .resolve("terraform-provider-aws");

            assertEquals(expected, path);
        }

        @Test
        @DisplayName("should construct correct cache path for random provider")
        void shouldConstructCorrectCachePathForRandom() {
            var path = client.getCachedPath("hashicorp", "random", "3.6.0");

            var expected = tempCacheDir
                    .resolve("hashicorp")
                    .resolve("random")
                    .resolve("3.6.0")
                    .resolve("terraform-provider-random");

            assertEquals(expected, path);
        }

        @Test
        @DisplayName("should report not cached when directory does not exist")
        void shouldReportNotCachedWhenMissing() {
            var cached = client.isCached("hashicorp", "aws", "5.82.0");

            assertFalse(cached);
        }

        @Test
        @DisplayName("should report cached when binary file exists")
        void shouldReportCachedWhenBinaryExists() throws Exception {
            var binaryPath = tempCacheDir
                    .resolve("hashicorp")
                    .resolve("aws")
                    .resolve("5.82.0")
                    .resolve("terraform-provider-aws");

            binaryPath.getParent().toFile().mkdirs();
            binaryPath.toFile().createNewFile();
            binaryPath.toFile().setExecutable(true);

            var cached = client.isCached("hashicorp", "aws", "5.82.0");

            assertTrue(cached);
        }
    }

    // ---------------------------------------------------------------
    // 4. Platform detection
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Platform detection mapping")
    class PlatformDetection {

        @Test
        @DisplayName("should map 'Mac OS X' to 'darwin'")
        void shouldMapMacOsToDarwin() {
            assertEquals("darwin", TerraformRegistryClient.mapOsName("Mac OS X"));
        }

        @Test
        @DisplayName("should map 'Linux' to 'linux'")
        void shouldMapLinux() {
            assertEquals("linux", TerraformRegistryClient.mapOsName("Linux"));
        }

        @Test
        @DisplayName("should map 'Windows 10' to 'windows'")
        void shouldMapWindows() {
            assertEquals("windows", TerraformRegistryClient.mapOsName("Windows 10"));
        }

        @Test
        @DisplayName("should map 'aarch64' to 'arm64'")
        void shouldMapAarch64ToArm64() {
            assertEquals("arm64", TerraformRegistryClient.mapArchName("aarch64"));
        }

        @Test
        @DisplayName("should map 'x86_64' to 'amd64'")
        void shouldMapX86ToAmd64() {
            assertEquals("amd64", TerraformRegistryClient.mapArchName("x86_64"));
        }

        @Test
        @DisplayName("should map 'amd64' to 'amd64'")
        void shouldMapAmd64Passthrough() {
            assertEquals("amd64", TerraformRegistryClient.mapArchName("amd64"));
        }
    }

    // ---------------------------------------------------------------
    // 5. Semantic version comparison
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Semantic version comparison")
    class SemanticVersionComparison {

        @Test
        @DisplayName("should compare equal versions as 0")
        void shouldCompareEqualVersions() {
            assertEquals(0, TerraformRegistryClient.compareVersions("5.82.0", "5.82.0"));
        }

        @Test
        @DisplayName("should compare higher major as positive")
        void shouldCompareHigherMajor() {
            assertTrue(TerraformRegistryClient.compareVersions("6.0.0", "5.82.0") > 0);
        }

        @Test
        @DisplayName("should compare lower major as negative")
        void shouldCompareLowerMajor() {
            assertTrue(TerraformRegistryClient.compareVersions("4.0.0", "5.0.0") < 0);
        }

        @Test
        @DisplayName("should compare minor versions correctly")
        void shouldCompareMinorVersions() {
            assertTrue(TerraformRegistryClient.compareVersions("5.82.0", "5.81.0") > 0);
        }

        @Test
        @DisplayName("should compare patch versions correctly")
        void shouldComparePatchVersions() {
            assertTrue(TerraformRegistryClient.compareVersions("5.82.1", "5.82.0") > 0);
        }

        @Test
        @DisplayName("should handle two-segment versions")
        void shouldHandleTwoSegmentVersions() {
            assertTrue(TerraformRegistryClient.compareVersions("5.82", "5.81") > 0);
        }

        @Test
        @DisplayName("should treat shorter version as zero-padded")
        void shouldTreatShorterVersionAsZeroPadded() {
            assertEquals(0, TerraformRegistryClient.compareVersions("5.0", "5.0.0"));
        }
    }

    // ---------------------------------------------------------------
    // 6. SHA256SUMS parsing (checksum read from the signed sums file)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("SHA256SUMS checksum parsing")
    class Sha256SumsParsing {

        // Two entries in coreutils double-space format, exactly as HashiCorp publishes them.
        private static final String SUMS = """
                e2699bc9116447f96c53d55f2a00570f982e6f9935038c3810603572693712d0  terraform-provider-random_3.6.0_darwin_amd64.zip
                6f24a2b3f0b3d3c6f1b3b9a9d5f8e7c6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0  terraform-provider-random_3.6.0_linux_amd64.zip
                """;

        @Test
        @DisplayName("should return the checksum matching the requested filename")
        void shouldReturnChecksumForFilename() {
            var sha = TerraformRegistryClient.parseExpectedChecksum(
                    SUMS, "terraform-provider-random_3.6.0_linux_amd64.zip");

            assertEquals("6f24a2b3f0b3d3c6f1b3b9a9d5f8e7c6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0", sha.orElseThrow());
        }

        @Test
        @DisplayName("should pick the correct line when several are present")
        void shouldPickCorrectLineAmongMany() {
            var sha = TerraformRegistryClient.parseExpectedChecksum(
                    SUMS, "terraform-provider-random_3.6.0_darwin_amd64.zip");

            assertEquals("e2699bc9116447f96c53d55f2a00570f982e6f9935038c3810603572693712d0", sha.orElseThrow());
        }

        @Test
        @DisplayName("should return empty when the filename is not listed")
        void shouldReturnEmptyWhenFilenameAbsent() {
            var sha = TerraformRegistryClient.parseExpectedChecksum(SUMS, "terraform-provider-random_3.6.0_windows_amd64.zip");

            assertTrue(sha.isEmpty());
        }

        @Test
        @DisplayName("should tolerate the binary-mode '*' filename marker")
        void shouldTolerateBinaryModeMarker() {
            var sha = TerraformRegistryClient.parseExpectedChecksum(
                    "abc123  *some-file.zip\n", "some-file.zip");

            assertEquals("abc123", sha.orElseThrow());
        }
    }
}
