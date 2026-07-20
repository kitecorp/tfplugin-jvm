package cloud.kitelang.tfplugin;

import java.util.List;

/**
 * Protocol-neutral schema block: a set of attributes plus nested blocks.
 *
 * @param attributes the block's attribute definitions, in schema order
 * @param blockTypes the block's nested block definitions, in schema order
 */
public record TfBlock(List<TfAttribute> attributes, List<TfNestedBlock> blockTypes) {

    public TfBlock {
        attributes = List.copyOf(attributes);
        blockTypes = List.copyOf(blockTypes);
    }

    /**
     * True when any attribute in this block — or in a nested block at any
     * depth — is sensitive. A nested block surfaces as a single property, so
     * this is the sensitivity of that property: masking cannot reach
     * individual leaves inside the rendered value (kitecorp/kite-providers#6).
     */
    public boolean hasSensitiveValues() {
        return attributes.stream().anyMatch(TfAttribute::sensitive)
               || blockTypes.stream().anyMatch(nested -> nested.block().hasSensitiveValues());
    }
}
