package cloud.kitelang.tfplugin;

/**
 * Protocol-neutral nested block definition.
 *
 * @param typeName the nested block's type name (e.g. {@code "ingress"})
 * @param block    the nested block's own schema block
 * @param nesting  how instances of the block nest inside the parent
 */
public record TfNestedBlock(String typeName, TfBlock block, Nesting nesting) {

    /** Mirrors the {@code NestedBlock.NestingMode} enum shared by both protocol versions. */
    public enum Nesting {
        /** The provider sent no recognizable nesting mode (proto default / unset). */
        INVALID,
        /** Exactly one instance, addressed without an index (e.g. {@code timeouts { ... }}). */
        SINGLE,
        /** Zero or more ordered instances, addressed by integer index. */
        LIST,
        /** Zero or more unordered, deduplicated instances. */
        SET,
        /** Zero or more instances keyed by a string label. */
        MAP,
        /** Exactly one instance, collapsed into the parent block rather than nested under a key. */
        GROUP
    }
}
