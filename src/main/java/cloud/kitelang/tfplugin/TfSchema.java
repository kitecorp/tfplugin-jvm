package cloud.kitelang.tfplugin;

/**
 * Protocol-neutral Terraform resource/data-source/provider schema.
 *
 * <p>Both tfplugin5 and tfplugin6 define a structurally similar {@code Schema}
 * message; the protocol adapters ({@link Tfplugin5Rpc}, {@link Tfplugin6Rpc})
 * convert their generated classes into this shared model so schema consumers
 * (e.g. the Kite bridge's {@code TerraformSchemaConverter} and
 * {@code TerraformBridgeProvider}) stay version-agnostic.</p>
 *
 * @param version the schema version — the version resource state written under
 *                this schema is recorded with, and the trigger for
 *                {@code UpgradeResourceState} when a newer provider release
 *                reads older state (kitecorp/kite-providers#5); always 0 for
 *                provider-config and data-source schemas, which have no
 *                persisted state to upgrade
 * @param block   the top-level configuration block
 */
public record TfSchema(long version, TfBlock block) {
}
