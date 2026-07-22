package cloud.kitelang.tfplugin;

import java.util.List;

/**
 * Protocol-neutral nested-attribute type: the tfplugin6 {@code Schema.Object}
 * that backs an attribute declared via {@code nested_type} (absent from
 * tfplugin5). Holds the member attributes and how instances of the object nest
 * inside the owning attribute.
 *
 * <p>The equivalent cty type is also baked into the owning
 * {@link TfAttribute#typeJson()}, which stays the wire-encoding source of truth
 * for {@link CtyCodec}. This structured form exists so a schema consumer (e.g.
 * the Kite bridge's {@code TerraformSchemaConverter}) can render nested
 * attributes as first-class types instead of collapsing them to a bare
 * {@code Map} (kitecorp/kite-providers#12).</p>
 *
 * @param attributes the object's member attributes, in schema order (each may
 *                   itself carry a {@code nestedType}, so structure recurses)
 * @param nesting    how instances nest inside the owning attribute — SINGLE,
 *                   LIST, SET, or MAP; never GROUP, which is a nested-block-only
 *                   mode ({@link TfNestedBlock})
 */
public record TfObjectType(List<TfAttribute> attributes, TfNestedBlock.Nesting nesting) {

    /** Defensively copies {@link #attributes} to an immutable list. */
    public TfObjectType {
        attributes = List.copyOf(attributes);
    }
}
