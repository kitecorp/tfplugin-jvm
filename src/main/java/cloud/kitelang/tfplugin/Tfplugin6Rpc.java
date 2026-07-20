package cloud.kitelang.tfplugin;

import com.google.protobuf.ByteString;
import tfplugin6.ProviderGrpc;
import tfplugin6.Tfplugin6;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link TerraformProviderRpc} implementation for protocol version 6.
 *
 * <p>Beyond the renamed RPCs (see {@link TerraformProviderRpc}), tfplugin6 adds
 * <em>nested attributes</em>: an attribute may carry a {@code nested_type}
 * ({@code Schema.Object}) instead of cty type bytes. On the wire such attributes
 * are encoded exactly like the corresponding cty object/list/set/map-of-object
 * type, so {@link #attributeTypeJson} synthesises that cty type JSON and the rest
 * of the bridge (encoding, decoding, schema conversion) works unchanged.
 * Alongside that cty JSON, the structured {@code nested_type} (members + nesting
 * mode) is carried on the neutral {@link TfAttribute#nestedType()} so a schema
 * consumer (e.g. the Kite bridge's {@code TerraformSchemaConverter}) can render
 * nested attributes as first-class types (kitecorp/kite-providers#12).</p>
 *
 * <p>The near-duplicate in {@link Tfplugin5Rpc} is intentional: the two
 * protocols generate distinct Java classes with no common supertype.</p>
 */
public final class Tfplugin6Rpc implements TerraformProviderRpc {

    private final ProviderGrpc.ProviderBlockingStub stub;

    public Tfplugin6Rpc(ProviderGrpc.ProviderBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public ProviderSchema getProviderSchema() {
        var response = stub.getProviderSchema(Tfplugin6.GetProviderSchema.Request.getDefaultInstance());
        return new ProviderSchema(
                response.hasProvider() ? toTfSchema(response.getProvider()) : null,
                toTfSchemaMap(response.getResourceSchemasMap()),
                toTfSchemaMap(response.getDataSourceSchemasMap()),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public List<TfDiagnostic> configure(byte[] configMsgpack) {
        var request = Tfplugin6.ConfigureProvider.Request.newBuilder()
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.configureProvider(request).getDiagnosticsList());
    }

    @Override
    public ProviderConfigValidation validateProviderConfig(byte[] configMsgpack) {
        var request = Tfplugin6.ValidateProviderConfig.Request.newBuilder()
                .setConfig(dynamicValue(configMsgpack))
                .build();
        var response = stub.validateProviderConfig(request);
        // Protocol 6 dropped tfplugin5's prepare semantics: validation never
        // rewrites the config, so the input passes through to Configure.
        return new ProviderConfigValidation(configMsgpack,
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public List<TfDiagnostic> validateResourceConfig(String typeName, byte[] configMsgpack) {
        var request = Tfplugin6.ValidateResourceConfig.Request.newBuilder()
                .setTypeName(typeName)
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.validateResourceConfig(request).getDiagnosticsList());
    }

    @Override
    public List<TfDiagnostic> validateDataSourceConfig(String typeName, byte[] configMsgpack) {
        // Renamed relative to tfplugin5: ValidateDataSourceConfig -> ValidateDataResourceConfig
        var request = Tfplugin6.ValidateDataResourceConfig.Request.newBuilder()
                .setTypeName(typeName)
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.validateDataResourceConfig(request).getDiagnosticsList());
    }

    @Override
    public UpgradeResult upgradeResourceState(String typeName, long storedSchemaVersion, byte[] rawStateJson) {
        var request = Tfplugin6.UpgradeResourceState.Request.newBuilder()
                .setTypeName(typeName)
                .setVersion(storedSchemaVersion)
                .setRawState(Tfplugin6.RawState.newBuilder()
                        .setJson(ByteString.copyFrom(rawStateJson)))
                .build();
        var response = stub.upgradeResourceState(request);
        return new UpgradeResult(
                response.getUpgradedState().getMsgpack().toByteArray(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public PlanResult planResourceChange(String typeName, byte[] priorState, byte[] proposedNewState,
                                         byte[] config, byte[] priorPrivate) {
        var request = Tfplugin6.PlanResourceChange.Request.newBuilder()
                .setTypeName(typeName)
                .setPriorState(dynamicValue(priorState))
                .setProposedNewState(dynamicValue(proposedNewState))
                .setConfig(dynamicValue(config))
                .setPriorPrivate(ByteString.copyFrom(priorPrivate))
                .build();
        var response = stub.planResourceChange(request);
        return new PlanResult(
                response.getPlannedState().getMsgpack().toByteArray(),
                response.getPlannedPrivate().toByteArray(),
                response.getRequiresReplaceList().stream().map(Tfplugin6Rpc::toAttributePath).toList(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public StateResult applyResourceChange(String typeName, byte[] priorState, byte[] plannedState,
                                           byte[] config, byte[] plannedPrivate) {
        var request = Tfplugin6.ApplyResourceChange.Request.newBuilder()
                .setTypeName(typeName)
                .setPriorState(dynamicValue(priorState))
                .setPlannedState(dynamicValue(plannedState))
                .setConfig(dynamicValue(config))
                .setPlannedPrivate(ByteString.copyFrom(plannedPrivate))
                .build();
        var response = stub.applyResourceChange(request);
        return new StateResult(
                response.getNewState().getMsgpack().toByteArray(),
                response.getPrivate().toByteArray(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public StateResult readResource(String typeName, byte[] currentState, byte[] privateBytes) {
        var request = Tfplugin6.ReadResource.Request.newBuilder()
                .setTypeName(typeName)
                .setCurrentState(dynamicValue(currentState))
                .setPrivate(ByteString.copyFrom(privateBytes))
                .build();
        var response = stub.readResource(request);
        return new StateResult(
                response.getNewState().getMsgpack().toByteArray(),
                response.getPrivate().toByteArray(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public StateResult importResourceState(String typeName, String importId) {
        var request = Tfplugin6.ImportResourceState.Request.newBuilder()
                .setTypeName(typeName)
                .setId(importId)
                .build();
        var response = stub.importResourceState(request);
        // Providers may import additional resources of other types alongside
        // the requested one; the bridge adopts exactly the requested type
        var imported = response.getImportedResourcesList().stream()
                .filter(resource -> typeName.equals(resource.getTypeName()))
                .findFirst();
        return new StateResult(
                imported.map(resource -> resource.getState().getMsgpack().toByteArray()).orElse(null),
                imported.map(resource -> resource.getPrivate().toByteArray()).orElse(new byte[0]),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public DataSourceResult readDataSource(String typeName, byte[] configMsgpack) {
        var request = Tfplugin6.ReadDataSource.Request.newBuilder()
                .setTypeName(typeName)
                .setConfig(dynamicValue(configMsgpack))
                .build();
        var response = stub.readDataSource(request);
        return new DataSourceResult(
                response.getState().getMsgpack().toByteArray(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public void stop() {
        stub.stopProvider(Tfplugin6.StopProvider.Request.getDefaultInstance());
    }

    // ---------------------------------------------------------------
    // tfplugin6 -> neutral model conversions
    // ---------------------------------------------------------------

    static TfSchema toTfSchema(Tfplugin6.Schema schema) {
        return new TfSchema(schema.getVersion(), toTfBlock(schema.getBlock()));
    }

    static TfBlock toTfBlock(Tfplugin6.Schema.Block block) {
        return new TfBlock(
                block.getAttributesList().stream().map(Tfplugin6Rpc::toTfAttribute).toList(),
                block.getBlockTypesList().stream().map(Tfplugin6Rpc::toTfNestedBlock).toList());
    }

    private static Map<String, TfSchema> toTfSchemaMap(Map<String, Tfplugin6.Schema> schemas) {
        return schemas.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> toTfSchema(e.getValue())));
    }

    private static TfAttribute toTfAttribute(Tfplugin6.Schema.Attribute attribute) {
        return new TfAttribute(
                attribute.getName(),
                attributeTypeJson(attribute),
                attribute.getRequired(),
                attribute.getOptional(),
                attribute.getComputed(),
                attribute.getSensitive() || hasSensitiveNestedAttribute(attribute),
                attribute.getWriteOnly(),
                attribute.hasNestedType() ? toTfObjectType(attribute.getNestedType()) : null);
    }

    /**
     * Structured form of a {@code nested_type} object, carried alongside the
     * synthesised cty {@code typeJson} ({@link #attributeTypeJson}) so the DSL
     * converter can render nested attributes as first-class Kite types
     * (kitecorp/kite-providers#12). Recurses through {@link #toTfAttribute} so
     * members that are themselves nested attributes keep their structure.
     */
    private static TfObjectType toTfObjectType(Tfplugin6.Schema.Object object) {
        return new TfObjectType(
                object.getAttributesList().stream().map(Tfplugin6Rpc::toTfAttribute).toList(),
                toObjectNesting(object.getNesting()));
    }

    /**
     * Maps a {@code Schema.Object.NestingMode} onto the shared nesting enum.
     * Nested attributes never use GROUP (a nested-block-only mode), so an
     * unrecognised value maps to INVALID rather than being forced onto a shape.
     */
    private static TfNestedBlock.Nesting toObjectNesting(Tfplugin6.Schema.Object.NestingMode mode) {
        return switch (mode) {
            case SINGLE -> TfNestedBlock.Nesting.SINGLE;
            case LIST -> TfNestedBlock.Nesting.LIST;
            case SET -> TfNestedBlock.Nesting.SET;
            case MAP -> TfNestedBlock.Nesting.MAP;
            default -> TfNestedBlock.Nesting.INVALID;
        };
    }

    /**
     * The synthesized cty type JSON ({@link #nestedTypeJson}) has no slot for
     * per-leaf sensitivity, so a sensitive attribute anywhere inside a
     * {@code nested_type} must surface on the containing attribute — masking
     * happens at top-level property granularity, and dropping the flag here
     * would leak the nested value in rendered plans (kitecorp/kite-providers#6).
     */
    private static boolean hasSensitiveNestedAttribute(Tfplugin6.Schema.Attribute attribute) {
        return attribute.hasNestedType() && attribute.getNestedType().getAttributesList().stream()
                .anyMatch(nested -> nested.getSensitive() || hasSensitiveNestedAttribute(nested));
    }

    /**
     * Resolves the cty type JSON for a tfplugin6 attribute. Framework providers
     * set exactly one of {@code type} (cty type bytes) or {@code nested_type}
     * (nested attributes); an attribute with neither is treated as dynamic
     * rather than producing invalid JSON downstream.
     */
    private static String attributeTypeJson(Tfplugin6.Schema.Attribute attribute) {
        if (!attribute.getType().isEmpty()) {
            return attribute.getType().toStringUtf8();
        }
        if (attribute.hasNestedType()) {
            return nestedTypeJson(attribute.getNestedType());
        }
        return "\"dynamic\"";
    }

    /**
     * Synthesises the cty type JSON equivalent of a {@code Schema.Object}
     * nested-attribute declaration: SINGLE nests as a bare object, LIST/SET/MAP
     * wrap it in the corresponding collection type. Attribute names come from
     * the provider schema and are restricted to {@code [a-z0-9_]}, so no JSON
     * escaping is needed (same assumption as the cty type bytes themselves).
     */
    private static String nestedTypeJson(Tfplugin6.Schema.Object object) {
        var objectType = object.getAttributesList().stream()
                .map(attr -> "\"%s\":%s".formatted(attr.getName(), attributeTypeJson(attr)))
                .collect(Collectors.joining(",", "[\"object\",{", "}]"));

        return switch (object.getNesting()) {
            case LIST -> "[\"list\"," + objectType + "]";
            case SET -> "[\"set\"," + objectType + "]";
            case MAP -> "[\"map\"," + objectType + "]";
            default -> objectType;
        };
    }

    private static TfNestedBlock toTfNestedBlock(Tfplugin6.Schema.NestedBlock nestedBlock) {
        return new TfNestedBlock(
                nestedBlock.getTypeName(),
                toTfBlock(nestedBlock.getBlock()),
                toNesting(nestedBlock.getNesting()));
    }

    private static TfNestedBlock.Nesting toNesting(Tfplugin6.Schema.NestedBlock.NestingMode mode) {
        return switch (mode) {
            case SINGLE -> TfNestedBlock.Nesting.SINGLE;
            case LIST -> TfNestedBlock.Nesting.LIST;
            case SET -> TfNestedBlock.Nesting.SET;
            case MAP -> TfNestedBlock.Nesting.MAP;
            case GROUP -> TfNestedBlock.Nesting.GROUP;
            default -> TfNestedBlock.Nesting.INVALID;
        };
    }

    private static TfAttributePath toAttributePath(Tfplugin6.AttributePath path) {
        var steps = path.getStepsList().stream()
                .map(step -> switch (step.getSelectorCase()) {
                    case ATTRIBUTE_NAME -> TfAttributePath.Step.attribute(step.getAttributeName());
                    case ELEMENT_KEY_STRING -> TfAttributePath.Step.stringKey(step.getElementKeyString());
                    case ELEMENT_KEY_INT -> TfAttributePath.Step.intKey(step.getElementKeyInt());
                    case SELECTOR_NOT_SET -> TfAttributePath.Step.unset();
                })
                .toList();
        return new TfAttributePath(steps);
    }

    private static List<TfDiagnostic> toDiagnostics(List<Tfplugin6.Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> new TfDiagnostic(toSeverity(d.getSeverity()), d.getSummary(), d.getDetail(),
                        d.hasAttribute() ? toAttributePath(d.getAttribute()) : null))
                .toList();
    }

    private static TfDiagnostic.Severity toSeverity(Tfplugin6.Diagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> TfDiagnostic.Severity.ERROR;
            case WARNING -> TfDiagnostic.Severity.WARNING;
            default -> TfDiagnostic.Severity.INVALID;
        };
    }

    private static Tfplugin6.DynamicValue dynamicValue(byte[] msgpack) {
        return Tfplugin6.DynamicValue.newBuilder()
                .setMsgpack(ByteString.copyFrom(msgpack))
                .build();
    }
}
