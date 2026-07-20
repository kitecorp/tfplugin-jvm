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
        INVALID,
        SINGLE,
        LIST,
        SET,
        MAP,
        GROUP
    }
}
