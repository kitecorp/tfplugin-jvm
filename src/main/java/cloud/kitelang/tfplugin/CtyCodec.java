package cloud.kitelang.tfplugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bidirectional codec between Java Maps and Terraform's cty msgpack encoding.
 *
 * <p>Terraform providers exchange resource property values as {@code DynamicValue}
 * messages containing msgpack-encoded bytes. The encoding is schema-driven: each
 * property's cty type determines how its value is serialised. This codec translates
 * between Kite's {@code Map<String, Object>} property bags and those msgpack bytes.</p>
 *
 * <h3>Supported cty types</h3>
 * <ul>
 *   <li>{@code "string"} &mdash; msgpack string / Java {@link String}</li>
 *   <li>{@code "number"} &mdash; msgpack int, float, or string / Java {@link Number} or {@link BigDecimal}</li>
 *   <li>{@code "bool"} &mdash; msgpack boolean / Java {@link Boolean}</li>
 *   <li>{@code ["list", T]} and {@code ["set", T]} &mdash; msgpack array / Java {@link List}</li>
 *   <li>{@code ["map", T]} &mdash; msgpack map (string keys) / Java {@link Map}</li>
 *   <li>{@code ["object", {…}]} &mdash; msgpack map / Java {@link Map}</li>
 *   <li>{@code "dynamic"} &mdash; 2-element msgpack array [JSON type bytes, encoded value]</li>
 *   <li>{@code null} &mdash; msgpack nil</li>
 *   <li>unknown &mdash; msgpack extension type 0 / sentinel {@link #UNKNOWN}</li>
 * </ul>
 *
 * @see <a href="https://github.com/hashicorp/terraform/blob/main/docs/plugin-protocol/object-wire-format.md">
 *     Terraform object wire format</a>
 */
public class CtyCodec {

    /** Sentinel object representing a Terraform "unknown" value (not yet computed). */
    public static final Object UNKNOWN = new Object() {
        @Override
        public String toString() {
            return "<unknown>";
        }
    };

    /** Extension type code used by cty for unknown values. */
    private static final byte EXT_TYPE_UNKNOWN = 0;

    /** Extension type code used by cty for refined unknown values (v5.6+). */
    private static final byte EXT_TYPE_REFINED_UNKNOWN = 12;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Encode a Java property map into cty msgpack bytes.
     *
     * @param properties the property map to encode (may contain nulls and {@link #UNKNOWN})
     * @param schemaType JSON-encoded cty type, e.g. {@code ["object", {"ami": "string"}]}
     * @return msgpack bytes suitable for a Terraform {@code DynamicValue}
     * @throws UncheckedIOException if msgpack serialisation fails
     */
    public byte[] encode(Map<String, Object> properties, String schemaType) {
        var typeNode = parseType(schemaType);
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            encodeValue(packer, properties, typeNode);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode cty msgpack", e);
        }
    }

    /**
     * Decode cty msgpack bytes into a Java property map.
     *
     * @param msgpack the msgpack bytes from a Terraform {@code DynamicValue}
     * @param schemaType JSON-encoded cty type, e.g. {@code ["object", {"ami": "string"}]}
     * @return a mutable map of decoded property values
     * @throws UncheckedIOException if msgpack deserialisation fails
     */
    public Map<String, Object> decode(byte[] msgpack, String schemaType) {
        var typeNode = parseType(schemaType);
        try (var unpacker = MessagePack.newDefaultUnpacker(msgpack)) {
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) decodeValue(unpacker, typeNode);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode cty msgpack", e);
        }
    }

    /**
     * Check whether a value is the {@link #UNKNOWN} sentinel.
     *
     * @param value the value to test
     * @return true if the value represents a Terraform unknown
     */
    public static boolean isUnknown(Object value) {
        return value == UNKNOWN;
    }

    // ------------------------------------------------------------------
    // Type parsing
    // ------------------------------------------------------------------

    /**
     * Parse a JSON-encoded cty type string into a {@link JsonNode} tree.
     */
    private JsonNode parseType(String schemaType) {
        try {
            return JSON.readTree(schemaType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid cty type JSON: " + schemaType, e);
        }
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * Encode a single value according to its cty type descriptor.
     *
     * <p>Dispatches to type-specific encoders based on the JSON type node shape:
     * a plain string ({@code "string"}, {@code "number"}, etc.) for primitives,
     * or an array ({@code ["list", T]}, {@code ["object", {...}]}, etc.) for
     * compound types.</p>
     */
    private void encodeValue(MessagePacker packer, Object value, JsonNode typeNode) throws IOException {
        // Null values are always msgpack nil regardless of type
        if (value == null) {
            packer.packNil();
            return;
        }

        // Unknown values are always extension type 0 with empty payload
        if (value == UNKNOWN) {
            packer.packExtensionTypeHeader(EXT_TYPE_UNKNOWN, 0);
            return;
        }

        if (typeNode.isTextual()) {
            encodePrimitive(packer, value, typeNode.asText());
        } else if (typeNode.isArray()) {
            encodeCompound(packer, value, typeNode);
        } else {
            throw new IllegalArgumentException("Unsupported cty type node: " + typeNode);
        }
    }

    /**
     * Encode a primitive value ({@code "string"}, {@code "number"}, {@code "bool"},
     * or {@code "dynamic"}).
     */
    private void encodePrimitive(MessagePacker packer, Object value, String typeName) throws IOException {
        switch (typeName) {
            case "string" -> packer.packString((String) value);
            case "number" -> encodeNumber(packer, value);
            case "bool" -> packer.packBoolean((Boolean) value);
            case "dynamic" -> encodeDynamic(packer, value);
            default -> throw new IllegalArgumentException("Unknown primitive cty type: " + typeName);
        }
    }

    /**
     * Encode a number value.
     *
     * <p>Terraform accepts integers, floats, and string-encoded numbers (for
     * arbitrary precision). Small integers and doubles that fit in float64 are
     * encoded natively; anything else is encoded as a msgpack string.</p>
     */
    private void encodeNumber(MessagePacker packer, Object value) throws IOException {
        if (value instanceof Integer i) {
            packer.packInt(i);
        } else if (value instanceof Long l) {
            packer.packLong(l);
        } else if (value instanceof Double d) {
            packer.packDouble(d);
        } else if (value instanceof Float f) {
            packer.packFloat(f);
        } else if (value instanceof BigDecimal || value instanceof BigInteger) {
            // Arbitrary-precision numbers are encoded as strings per the cty spec
            packer.packString(value.toString());
        } else if (value instanceof Number n) {
            packer.packDouble(n.doubleValue());
        } else {
            throw new IllegalArgumentException("Cannot encode as number: " + value.getClass());
        }
    }

    /**
     * Encode a compound type ({@code ["list", T]}, {@code ["set", T]},
     * {@code ["map", T]}, or {@code ["object", {…}]}).
     */
    @SuppressWarnings("unchecked")
    private void encodeCompound(MessagePacker packer, Object value, JsonNode typeNode) throws IOException {
        var kind = typeNode.get(0).asText();
        switch (kind) {
            case "list", "set" -> {
                var elementType = typeNode.get(1);
                var list = (List<Object>) value;
                packer.packArrayHeader(list.size());
                for (var element : list) {
                    encodeValue(packer, element, elementType);
                }
            }
            case "map" -> {
                var valueType = typeNode.get(1);
                var map = (Map<String, Object>) value;
                packer.packMapHeader(map.size());
                for (var entry : map.entrySet()) {
                    packer.packString(entry.getKey());
                    encodeValue(packer, entry.getValue(), valueType);
                }
            }
            case "object" -> encodeObject(packer, (Map<String, Object>) value, typeNode.get(1));
            default -> throw new IllegalArgumentException("Unknown compound cty type: " + kind);
        }
    }

    /**
     * Encode an object value as a msgpack map, using the schema to determine
     * the type of each attribute.
     */
    private void encodeObject(MessagePacker packer, Map<String, Object> value, JsonNode attrTypes) throws IOException {
        // A cty object has a fixed, schema-declared attribute set. Collect it once;
        // it drives both the unknown-property check and the packed field order.
        var fieldNames = attrTypes.fieldNames();
        var fields = new ArrayList<String>();
        fieldNames.forEachRemaining(fields::add);

        rejectUnknownProperties(value.keySet(), fields);

        // Include all attributes from the schema, even if absent from the map (as nil)
        packer.packMapHeader(fields.size());
        for (var field : fields) {
            packer.packString(field);
            var attrValue = value.get(field);
            encodeValue(packer, attrValue, attrTypes.get(field));
        }
    }

    /**
     * Fail by name on any map key the object schema does not declare — the
     * encode-side mirror of the provider-config "Unsupported argument" check
     * ({@code TerraformBridgeProvider#configure} in the Kite bridge). Historically these keys were
     * silently skipped (only schema fields were read), which hid user typos in
     * {@code .kite} files: a mistyped property simply vanished from the request
     * instead of failing loudly (kitecorp/kite#31). Applies at every object level,
     * so typos inside nested blocks are caught too. Free-form {@code map(...)}
     * values are not objects and never reach here, so their arbitrary keys pass
     * through untouched.
     *
     * @param providedKeys the keys actually present in the value map
     * @param schemaFields  the attribute names the schema declares (packing order)
     */
    private void rejectUnknownProperties(Set<String> providedKeys, List<String> schemaFields) {
        var schema = new HashSet<>(schemaFields);
        var unknown = providedKeys.stream()
                .filter(key -> !schema.contains(key))
                .sorted()
                .map(key -> "'" + key + "'")
                .toList();
        if (!unknown.isEmpty()) {
            var valid = schemaFields.stream().sorted().map(name -> "'" + name + "'").toList();
            throw new IllegalArgumentException(
                    "Unknown resource property %s not in schema. Valid properties: %s"
                            .formatted(String.join(", ", unknown), valid));
        }
    }

    /**
     * Encode a dynamic-typed value as a 2-element msgpack array:
     * {@code [JSON type bytes, encoded value]}.
     *
     * <p>The type is inferred from the Java runtime type of the value.</p>
     */
    private void encodeDynamic(MessagePacker packer, Object value) throws IOException {
        var typeNode = inferCtyType(value);
        var typeJson = JSON.writeValueAsBytes(typeNode);

        // Pack 2-element array: [type bytes, value]
        packer.packArrayHeader(2);
        packer.packBinaryHeader(typeJson.length);
        packer.writePayload(typeJson);

        // Encode the value per its inferred type
        encodeValue(packer, value, typeNode);
    }

    /**
     * Infer a cty type JSON node from a Java runtime value.
     * Used when encoding dynamic pseudo-type values.
     */
    private JsonNode inferCtyType(Object value) {
        if (value instanceof String) {
            return JSON.getNodeFactory().textNode("string");
        } else if (value instanceof Boolean) {
            return JSON.getNodeFactory().textNode("bool");
        } else if (value instanceof Number) {
            return JSON.getNodeFactory().textNode("number");
        }
        throw new IllegalArgumentException("Cannot infer cty type for dynamic value: " + value.getClass());
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Decode a single value from the unpacker according to its cty type descriptor.
     */
    private Object decodeValue(MessageUnpacker unpacker, JsonNode typeNode) throws IOException {
        var format = unpacker.getNextFormat();

        // Null is always nil regardless of expected type
        if (format == MessageFormat.NIL) {
            unpacker.unpackNil();
            return null;
        }

        // Extension types: unknown (0) and refined unknown (12)
        if (format.getValueType() == org.msgpack.value.ValueType.EXTENSION) {
            var header = unpacker.unpackExtensionTypeHeader();
            var extType = header.getType();
            if (extType == EXT_TYPE_UNKNOWN || extType == EXT_TYPE_REFINED_UNKNOWN) {
                // Skip the payload bytes (if any)
                if (header.getLength() > 0) {
                    unpacker.readPayload(header.getLength());
                }
                return UNKNOWN;
            }
            throw new IllegalArgumentException("Unexpected msgpack extension type: " + extType);
        }

        if (typeNode.isTextual()) {
            return decodePrimitive(unpacker, typeNode.asText(), format);
        } else if (typeNode.isArray()) {
            return decodeCompound(unpacker, typeNode);
        }

        throw new IllegalArgumentException("Unsupported cty type node: " + typeNode);
    }

    /**
     * Decode a primitive value from the unpacker.
     */
    private Object decodePrimitive(MessageUnpacker unpacker, String typeName, MessageFormat format) throws IOException {
        return switch (typeName) {
            case "string" -> unpacker.unpackString();
            case "number" -> decodeNumber(unpacker, format);
            case "bool" -> unpacker.unpackBoolean();
            case "dynamic" -> decodeDynamic(unpacker);
            default -> throw new IllegalArgumentException("Unknown primitive cty type: " + typeName);
        };
    }

    /**
     * Decode a number value.
     *
     * <p>Terraform may encode numbers as msgpack int, float, or string (for
     * arbitrary precision). We return {@link Integer}/{@link Long} for integers,
     * {@link Double} for floats, and {@link BigDecimal} for string-encoded numbers.</p>
     */
    private Number decodeNumber(MessageUnpacker unpacker, MessageFormat format) throws IOException {
        return switch (format.getValueType()) {
            case INTEGER -> {
                var raw = unpacker.unpackLong();
                if (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE) {
                    yield (int) raw;
                }
                yield raw;
            }
            case FLOAT -> unpacker.unpackDouble();
            case STRING -> new BigDecimal(unpacker.unpackString());
            default -> throw new IllegalArgumentException(
                    "Expected number but got msgpack format: " + format);
        };
    }

    /**
     * Decode a compound type ({@code ["list", T]}, {@code ["set", T]},
     * {@code ["map", T]}, or {@code ["object", {…}]}).
     */
    private Object decodeCompound(MessageUnpacker unpacker, JsonNode typeNode) throws IOException {
        var kind = typeNode.get(0).asText();
        return switch (kind) {
            case "list", "set" -> decodeList(unpacker, typeNode.get(1));
            case "map" -> decodeMap(unpacker, typeNode.get(1));
            case "object" -> decodeObject(unpacker, typeNode.get(1));
            default -> throw new IllegalArgumentException("Unknown compound cty type: " + kind);
        };
    }

    /**
     * Decode a list/set from a msgpack array.
     */
    private List<Object> decodeList(MessageUnpacker unpacker, JsonNode elementType) throws IOException {
        var size = unpacker.unpackArrayHeader();
        var result = new ArrayList<Object>(size);
        for (var i = 0; i < size; i++) {
            result.add(decodeValue(unpacker, elementType));
        }
        return result;
    }

    /**
     * Decode a map from a msgpack map with string keys.
     */
    private Map<String, Object> decodeMap(MessageUnpacker unpacker, JsonNode valueType) throws IOException {
        var size = unpacker.unpackMapHeader();
        var result = new LinkedHashMap<String, Object>(size);
        for (var i = 0; i < size; i++) {
            var key = unpacker.unpackString();
            result.put(key, decodeValue(unpacker, valueType));
        }
        return result;
    }

    /**
     * Decode an object from a msgpack map, using the schema to determine
     * each attribute's type.
     */
    private Map<String, Object> decodeObject(MessageUnpacker unpacker, JsonNode attrTypes) throws IOException {
        var size = unpacker.unpackMapHeader();
        var result = new LinkedHashMap<String, Object>(size);
        for (var i = 0; i < size; i++) {
            var key = unpacker.unpackString();
            var attrType = attrTypes.get(key);
            if (attrType == null) {
                throw new IllegalArgumentException("Unknown attribute '" + key + "' not in schema");
            }
            result.put(key, decodeValue(unpacker, attrType));
        }
        return result;
    }

    /**
     * Decode a dynamic-typed value from a 2-element msgpack array:
     * {@code [JSON type bytes, encoded value]}.
     */
    private Object decodeDynamic(MessageUnpacker unpacker) throws IOException {
        var arraySize = unpacker.unpackArrayHeader();
        if (arraySize != 2) {
            throw new IllegalArgumentException(
                    "Dynamic type expected 2-element array but got " + arraySize + " elements");
        }

        // First element: binary-encoded JSON type constraint
        var typeJsonLen = unpacker.unpackBinaryHeader();
        var typeJsonBytes = unpacker.readPayload(typeJsonLen);
        var innerType = JSON.readTree(new String(typeJsonBytes, StandardCharsets.UTF_8));

        // Second element: the value encoded per the inner type
        return decodeValue(unpacker, innerType);
    }
}
