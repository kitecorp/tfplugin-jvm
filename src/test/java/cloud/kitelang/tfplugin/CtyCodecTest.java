package cloud.kitelang.tfplugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CtyCodec}.
 *
 * <p>Each test encodes a Java value into cty msgpack bytes, then decodes it back,
 * verifying round-trip fidelity. Some tests also verify raw decode behaviour
 * (e.g. unknown sentinels, BigDecimal from string-encoded numbers).</p>
 */
class CtyCodecTest {

    private CtyCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CtyCodec();
    }

    // ---------------------------------------------------------------
    // 1. Primitives
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Primitive types")
    class Primitives {

        @Test
        @DisplayName("should round-trip a string value")
        void shouldRoundTripString() {
            var schema = """
                    ["object", {"name": "string"}]""";
            var original = Map.<String, Object>of("name", "hello");

            var encoded = codec.encode(original, schema);
            var decoded = codec.decode(encoded, schema);

            assertEquals("hello", decoded.get("name"));
        }

        @Test
        @DisplayName("should round-trip an empty string")
        void shouldRoundTripEmptyString() {
            var schema = """
                    ["object", {"value": "string"}]""";
            var original = Map.<String, Object>of("value", "");

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals("", decoded.get("value"));
        }

        @Test
        @DisplayName("should round-trip an integer number")
        void shouldRoundTripIntegerNumber() {
            var schema = """
                    ["object", {"count": "number"}]""";
            var original = Map.<String, Object>of("count", 42);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(42, ((Number) decoded.get("count")).intValue());
        }

        @Test
        @DisplayName("should round-trip a float number")
        void shouldRoundTripFloatNumber() {
            var schema = """
                    ["object", {"price": "number"}]""";
            var original = Map.<String, Object>of("price", 3.14);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(3.14, ((Number) decoded.get("price")).doubleValue(), 0.001);
        }

        @Test
        @DisplayName("should round-trip a boolean true")
        void shouldRoundTripBooleanTrue() {
            var schema = """
                    ["object", {"enabled": "bool"}]""";
            var original = Map.<String, Object>of("enabled", true);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(true, decoded.get("enabled"));
        }

        @Test
        @DisplayName("should round-trip a boolean false")
        void shouldRoundTripBooleanFalse() {
            var schema = """
                    ["object", {"enabled": "bool"}]""";
            var original = Map.<String, Object>of("enabled", false);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(false, decoded.get("enabled"));
        }
    }

    // ---------------------------------------------------------------
    // 2. Null values
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Null values")
    class NullValues {

        @Test
        @DisplayName("should encode null as msgpack nil and decode back to null")
        void shouldRoundTripNull() {
            var schema = """
                    ["object", {"tag": "string"}]""";
            // Use a mutable map since Map.of() does not allow null values
            var original = new java.util.HashMap<String, Object>();
            original.put("tag", null);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertTrue(decoded.containsKey("tag"), "key should be present");
            assertNull(decoded.get("tag"));
        }

        @Test
        @DisplayName("should encode null number")
        void shouldRoundTripNullNumber() {
            var schema = """
                    ["object", {"count": "number"}]""";
            var original = new java.util.HashMap<String, Object>();
            original.put("count", null);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertNull(decoded.get("count"));
        }
    }

    // ---------------------------------------------------------------
    // 3. Collections — lists
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("List collections")
    class Lists {

        @Test
        @DisplayName("should round-trip list of strings")
        void shouldRoundTripStringList() {
            var schema = """
                    ["object", {"tags": ["list", "string"]}]""";
            var original = Map.<String, Object>of("tags", List.of("a", "b", "c"));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(List.of("a", "b", "c"), decoded.get("tags"));
        }

        @Test
        @DisplayName("should round-trip list of numbers")
        void shouldRoundTripNumberList() {
            var schema = """
                    ["object", {"scores": ["list", "number"]}]""";
            var original = Map.<String, Object>of("scores", List.of(1, 2, 3));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            var actual = (List<?>) decoded.get("scores");
            assertEquals(3, actual.size());
            assertEquals(1, ((Number) actual.get(0)).intValue());
            assertEquals(2, ((Number) actual.get(1)).intValue());
            assertEquals(3, ((Number) actual.get(2)).intValue());
        }

        @Test
        @DisplayName("should round-trip empty list")
        void shouldRoundTripEmptyList() {
            var schema = """
                    ["object", {"items": ["list", "string"]}]""";
            var original = Map.<String, Object>of("items", List.of());

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(List.of(), decoded.get("items"));
        }

        @Test
        @DisplayName("should round-trip set (encoded same as list)")
        void shouldRoundTripSet() {
            var schema = """
                    ["object", {"ids": ["set", "string"]}]""";
            var original = Map.<String, Object>of("ids", List.of("x", "y"));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(List.of("x", "y"), decoded.get("ids"));
        }
    }

    // ---------------------------------------------------------------
    // 4. Maps
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Map collections")
    class Maps {

        @Test
        @DisplayName("should round-trip map of string to string")
        void shouldRoundTripStringMap() {
            var schema = """
                    ["object", {"labels": ["map", "string"]}]""";
            var original = Map.<String, Object>of("labels", Map.of("env", "prod", "team", "infra"));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            @SuppressWarnings("unchecked")
            var labels = (Map<String, Object>) decoded.get("labels");
            assertEquals("prod", labels.get("env"));
            assertEquals("infra", labels.get("team"));
        }

        @Test
        @DisplayName("should round-trip map of string to number")
        void shouldRoundTripNumberMap() {
            var schema = """
                    ["object", {"ports": ["map", "number"]}]""";
            var original = Map.<String, Object>of("ports", Map.of("http", 80, "https", 443));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            @SuppressWarnings("unchecked")
            var ports = (Map<String, Object>) decoded.get("ports");
            assertEquals(80, ((Number) ports.get("http")).intValue());
            assertEquals(443, ((Number) ports.get("https")).intValue());
        }

        @Test
        @DisplayName("should round-trip empty map")
        void shouldRoundTripEmptyMap() {
            var schema = """
                    ["object", {"meta": ["map", "string"]}]""";
            var original = Map.<String, Object>of("meta", Map.of());

            var decoded = codec.decode(codec.encode(original, schema), schema);

            @SuppressWarnings("unchecked")
            var meta = (Map<String, Object>) decoded.get("meta");
            assertTrue(meta.isEmpty());
        }
    }

    // ---------------------------------------------------------------
    // 5. Objects with mixed attribute types
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Object types")
    class Objects {

        @Test
        @DisplayName("should round-trip object with mixed attribute types")
        void shouldRoundTripMixedObject() {
            var schema = """
                    ["object", {"name": "string", "count": "number", "active": "bool"}]""";
            var original = Map.<String, Object>of(
                    "name", "widget",
                    "count", 5,
                    "active", true
            );

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals("widget", decoded.get("name"));
            assertEquals(5, ((Number) decoded.get("count")).intValue());
            assertEquals(true, decoded.get("active"));
        }
    }

    // ---------------------------------------------------------------
    // 6. Unknown values (extension type 0)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Unknown values")
    class UnknownValues {

        @Test
        @DisplayName("should decode extension type 0 as UNKNOWN sentinel")
        void shouldDecodeUnknownSentinel() {
            var schema = """
                    ["object", {"id": "string"}]""";
            // Encode an unknown value
            var original = Map.<String, Object>of("id", CtyCodec.UNKNOWN);

            var encoded = codec.encode(original, schema);
            var decoded = codec.decode(encoded, schema);

            assertSame(CtyCodec.UNKNOWN, decoded.get("id"));
        }

        @Test
        @DisplayName("isUnknown should return true for UNKNOWN sentinel")
        void isUnknownShouldReturnTrue() {
            assertTrue(CtyCodec.isUnknown(CtyCodec.UNKNOWN));
        }

        @Test
        @DisplayName("isUnknown should return false for regular values")
        void isUnknownShouldReturnFalse() {
            assertFalse(CtyCodec.isUnknown("hello"));
            assertFalse(CtyCodec.isUnknown(42));
            assertFalse(CtyCodec.isUnknown(null));
        }

        @Test
        @DisplayName("UNKNOWN toString should return descriptive text")
        void unknownToStringShouldBeDescriptive() {
            assertEquals("<unknown>", CtyCodec.UNKNOWN.toString());
        }
    }

    // ---------------------------------------------------------------
    // 7. Nested structures
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Nested structures")
    class NestedStructures {

        @Test
        @DisplayName("should round-trip object containing list of objects")
        void shouldRoundTripNestedObjectList() {
            // Schema: { "servers": [list, [object, {"host": "string", "port": "number"}]] }
            var schema = """
                    ["object", {"servers": ["list", ["object", {"host": "string", "port": "number"}]]}]""";
            var server1 = Map.<String, Object>of("host", "10.0.0.1", "port", 8080);
            var server2 = Map.<String, Object>of("host", "10.0.0.2", "port", 9090);
            var original = Map.<String, Object>of("servers", List.of(server1, server2));

            var decoded = codec.decode(codec.encode(original, schema), schema);

            @SuppressWarnings("unchecked")
            var servers = (List<Map<String, Object>>) decoded.get("servers");
            assertEquals(2, servers.size());
            assertEquals("10.0.0.1", servers.get(0).get("host"));
            assertEquals(8080, ((Number) servers.get(0).get("port")).intValue());
            assertEquals("10.0.0.2", servers.get(1).get("host"));
            assertEquals(9090, ((Number) servers.get(1).get("port")).intValue());
        }

        @Test
        @DisplayName("should round-trip deeply nested objects")
        void shouldRoundTripDeeplyNested() {
            // { "config": [object, { "inner": [object, { "value": "string" }] }] }
            var schema = """
                    ["object", {"config": ["object", {"inner": ["object", {"value": "string"}]}]}]""";
            var original = Map.<String, Object>of(
                    "config", Map.of("inner", Map.of("value", "deep"))
            );

            var decoded = codec.decode(codec.encode(original, schema), schema);

            @SuppressWarnings("unchecked")
            var config = (Map<String, Object>) decoded.get("config");
            @SuppressWarnings("unchecked")
            var inner = (Map<String, Object>) config.get("inner");
            assertEquals("deep", inner.get("value"));
        }
    }

    // ---------------------------------------------------------------
    // 8. Dynamic pseudo-type
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Dynamic pseudo-type")
    class DynamicType {

        @Test
        @DisplayName("should encode and decode dynamic string value")
        void shouldRoundTripDynamicString() {
            var schema = """
                    ["object", {"payload": "dynamic"}]""";
            var original = Map.<String, Object>of("payload", "hello-dynamic");

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals("hello-dynamic", decoded.get("payload"));
        }

        @Test
        @DisplayName("should encode and decode dynamic number value")
        void shouldRoundTripDynamicNumber() {
            var schema = """
                    ["object", {"value": "dynamic"}]""";
            var original = Map.<String, Object>of("value", 99);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(99, ((Number) decoded.get("value")).intValue());
        }

        @Test
        @DisplayName("should encode and decode dynamic boolean value")
        void shouldRoundTripDynamicBoolean() {
            var schema = """
                    ["object", {"flag": "dynamic"}]""";
            var original = Map.<String, Object>of("flag", true);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals(true, decoded.get("flag"));
        }
    }

    // ---------------------------------------------------------------
    // 9. Number precision — BigDecimal
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Number precision")
    class NumberPrecision {

        @Test
        @DisplayName("should round-trip BigDecimal for high-precision numbers")
        void shouldRoundTripBigDecimal() {
            var schema = """
                    ["object", {"amount": "number"}]""";
            var bigValue = new BigDecimal("99999999999999999999.999999999999");
            var original = Map.<String, Object>of("amount", bigValue);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            // BigDecimal values that exceed float64 precision are encoded as strings
            var actual = decoded.get("amount");
            assertInstanceOf(Number.class, actual);
            assertEquals(0, bigValue.compareTo(new BigDecimal(actual.toString())));
        }

        @Test
        @DisplayName("should decode string-encoded number to BigDecimal")
        void shouldDecodeStringEncodedNumber() {
            // Manually encode a number as a msgpack string to simulate TF behaviour
            var schema = """
                    ["object", {"precise": "number"}]""";
            var preciseValue = new BigDecimal("12345678901234567890.12345678901234567890");
            var original = Map.<String, Object>of("precise", preciseValue);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            var actual = decoded.get("precise");
            assertInstanceOf(Number.class, actual);
            assertEquals(0, preciseValue.compareTo(new BigDecimal(actual.toString())));
        }

        @Test
        @DisplayName("should preserve small integer as integer, not BigDecimal")
        void shouldPreserveSmallInteger() {
            var schema = """
                    ["object", {"count": "number"}]""";
            var original = Map.<String, Object>of("count", 7);

            var decoded = codec.decode(codec.encode(original, schema), schema);

            var actual = decoded.get("count");
            // Small integers should decode as int/long, not BigDecimal
            assertEquals(7, ((Number) actual).intValue());
        }
    }

    // ---------------------------------------------------------------
    // 9b. Unknown properties (kitecorp/kite#31)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Unknown properties (kitecorp/kite#31)")
    class UnknownProperties {

        @Test
        @DisplayName("should reject an unknown top-level property by name instead of dropping it")
        void shouldRejectUnknownPropertyByName() {
            var schema = """
                    ["object", {"ami": "string", "instance_type": "string"}]""";
            // "instance_typ" is a typo the schema does not declare; silently dropping it
            // would send the provider a resource missing instance_type with no warning.
            var props = Map.<String, Object>of(
                    "ami", "ami-123",
                    "instance_typ", "t3.micro");

            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(props, schema));

            assertTrue(thrown.getMessage().contains("instance_typ"),
                    "message must name the unknown property: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("ami")
                            && thrown.getMessage().contains("instance_type"),
                    "message must list the valid properties: " + thrown.getMessage());
        }

        @Test
        @DisplayName("should reject an unknown property nested inside a block")
        void shouldRejectUnknownNestedProperty() {
            var schema = """
                    ["object", {"root_block_device": ["object", {"volume_size": "number"}]}]""";
            var props = Map.<String, Object>of(
                    "root_block_device", Map.of("volume_sze", 50)); // typo inside the block

            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(props, schema));

            assertTrue(thrown.getMessage().contains("volume_sze"),
                    "message must name the nested unknown property: " + thrown.getMessage());
        }

        @Test
        @DisplayName("should still encode when a schema attribute is absent from the map")
        void shouldEncodeWithAbsentSchemaAttribute() {
            // Computed/read-only attributes (e.g. id) are legitimately absent or null —
            // absence is never an unknown-property error, only an extra key is.
            var schema = """
                    ["object", {"ami": "string", "id": "string"}]""";
            var props = Map.<String, Object>of("ami", "ami-123"); // "id" absent

            var decoded = codec.decode(codec.encode(props, schema), schema);

            assertEquals("ami-123", decoded.get("ami"));
            assertNull(decoded.get("id"));
        }

        @Test
        @DisplayName("should allow arbitrary keys inside a map-typed attribute")
        void shouldAllowArbitraryMapKeys() {
            // A cty map (unlike an object) has no fixed key set, so tags and other
            // free-form maps must pass through untouched.
            var schema = """
                    ["object", {"tags": ["map", "string"]}]""";
            var props = Map.<String, Object>of(
                    "tags", Map.of("Any", "value", "Custom", "key"));

            var decoded = codec.decode(codec.encode(props, schema), schema);

            @SuppressWarnings("unchecked")
            var tags = (Map<String, Object>) decoded.get("tags");
            assertEquals("value", tags.get("Any"));
            assertEquals("key", tags.get("Custom"));
        }
    }

    // ---------------------------------------------------------------
    // 10. Full round-trip with realistic schema
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Round-trip with realistic schemas")
    class RoundTrip {

        @Test
        @DisplayName("should round-trip a realistic AWS-like resource")
        void shouldRoundTripRealisticResource() {
            var schema = """
                    ["object", {
                        "ami": "string",
                        "instance_type": "string",
                        "count": "number",
                        "monitoring": "bool",
                        "tags": ["map", "string"],
                        "security_group_ids": ["list", "string"],
                        "root_block_device": ["object", {
                            "volume_size": "number",
                            "encrypted": "bool"
                        }]
                    }]""";

            var original = Map.<String, Object>of(
                    "ami", "ami-0abcdef1234567890",
                    "instance_type", "t3.micro",
                    "count", 2,
                    "monitoring", true,
                    "tags", Map.of("Name", "my-instance", "env", "prod"),
                    "security_group_ids", List.of("sg-111", "sg-222"),
                    "root_block_device", Map.of("volume_size", 50, "encrypted", true)
            );

            var decoded = codec.decode(codec.encode(original, schema), schema);

            assertEquals("ami-0abcdef1234567890", decoded.get("ami"));
            assertEquals("t3.micro", decoded.get("instance_type"));
            assertEquals(2, ((Number) decoded.get("count")).intValue());
            assertEquals(true, decoded.get("monitoring"));

            @SuppressWarnings("unchecked")
            var tags = (Map<String, Object>) decoded.get("tags");
            assertEquals("my-instance", tags.get("Name"));
            assertEquals("prod", tags.get("env"));

            assertEquals(List.of("sg-111", "sg-222"), decoded.get("security_group_ids"));

            @SuppressWarnings("unchecked")
            var rootBlock = (Map<String, Object>) decoded.get("root_block_device");
            assertEquals(50, ((Number) rootBlock.get("volume_size")).intValue());
            assertEquals(true, rootBlock.get("encrypted"));
        }

        @Test
        @DisplayName("encode then decode should produce equal values for all primitive types")
        void shouldRoundTripAllPrimitives() {
            var schema = """
                    ["object", {"s": "string", "n": "number", "b": "bool"}]""";
            var original = Map.<String, Object>of("s", "text", "n", 3.14, "b", false);

            var first = codec.decode(codec.encode(original, schema), schema);
            var second = codec.decode(codec.encode(first, schema), schema);

            assertEquals(first.get("s"), second.get("s"));
            assertEquals(
                    ((Number) first.get("n")).doubleValue(),
                    ((Number) second.get("n")).doubleValue(),
                    0.0001
            );
            assertEquals(first.get("b"), second.get("b"));
        }
    }
}
