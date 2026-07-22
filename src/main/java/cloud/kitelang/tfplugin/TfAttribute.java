package cloud.kitelang.tfplugin;

/**
 * Protocol-neutral schema attribute.
 *
 * <p>The cty type is carried as its JSON encoding ({@code typeJson}, e.g.
 * {@code "string"} or {@code ["list","number"]}) — the same representation both
 * protocols put in the {@code Schema.Attribute.type} bytes. tfplugin6 attributes
 * defined via {@code nested_type} (nested attributes, absent from tfplugin5) are
 * translated by {@link Tfplugin6Rpc} into the equivalent cty object type JSON,
 * which is exactly how the values are encoded on the wire, so {@link CtyCodec}
 * round-trips them without protocol-specific handling.</p>
 *
 * <p>A nested attribute additionally carries its {@link #nestedType} — the
 * structured member list and nesting mode — so a schema consumer (e.g. the Kite
 * bridge's {@code TerraformSchemaConverter})
 * can render it as a first-class type instead of collapsing the cty
 * object to a bare {@code Map} (kitecorp/kite-providers#12). {@code nestedType}
 * is {@code null} for every plain attribute (all of tfplugin5, and any tfplugin6
 * attribute whose type is a cty type rather than a {@code nested_type} object).</p>
 *
 * @param name       snake_case attribute name
 * @param typeJson   JSON-encoded cty type of the attribute's value
 * @param required   the practitioner must set a value
 * @param optional   the practitioner may set a value
 * @param computed   the provider fills in a value
 * @param sensitive  the value must not be displayed
 * @param writeOnly  the value is accepted on writes but never stored in state
 * @param nestedType the structured nested-attribute type, or {@code null} for a
 *                   plain attribute (see class notes)
 */
public record TfAttribute(String name, String typeJson, boolean required, boolean optional,
                          boolean computed, boolean sensitive, boolean writeOnly,
                          TfObjectType nestedType) {

    /**
     * Plain attribute with no {@code nested_type} structure — every tfplugin5
     * attribute and every tfplugin6 attribute typed by cty bytes. Nested
     * attributes use the canonical constructor with a non-null {@code nestedType}.
     *
     * @param name      snake_case attribute name
     * @param typeJson  JSON-encoded cty type of the attribute's value
     * @param required  the practitioner must set a value
     * @param optional  the practitioner may set a value
     * @param computed  the provider fills in a value
     * @param sensitive the value must not be displayed
     * @param writeOnly the value is accepted on writes but never stored in state
     */
    public TfAttribute(String name, String typeJson, boolean required, boolean optional,
                       boolean computed, boolean sensitive, boolean writeOnly) {
        this(name, typeJson, required, optional, computed, sensitive, writeOnly, null);
    }
}
