package cloud.kitelang.tfplugin;

import com.google.protobuf.ByteString;
import tfplugin5.ProviderGrpc;
import tfplugin5.Tfplugin5;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link TerraformProviderRpc} implementation for protocol version 5.
 *
 * <p>Purely mechanical translation between the neutral model and the generated
 * {@code tfplugin5} messages. The near-duplicate in {@link Tfplugin6Rpc} is
 * intentional: the two protocols generate distinct Java classes with no common
 * supertype, and each adapter must stay a faithful mirror of its frozen
 * protocol definition.</p>
 */
public final class Tfplugin5Rpc implements TerraformProviderRpc {

    private final ProviderGrpc.ProviderBlockingStub stub;

    public Tfplugin5Rpc(ProviderGrpc.ProviderBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public ProviderSchema getProviderSchema() {
        var response = stub.getSchema(Tfplugin5.GetProviderSchema.Request.getDefaultInstance());
        return new ProviderSchema(
                response.hasProvider() ? toTfSchema(response.getProvider()) : null,
                toTfSchemaMap(response.getResourceSchemasMap()),
                toTfSchemaMap(response.getDataSourceSchemasMap()),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public ProviderConfigValidation validateProviderConfig(byte[] configMsgpack) {
        var request = Tfplugin5.PrepareProviderConfig.Request.newBuilder()
                .setConfig(dynamicValue(configMsgpack))
                .build();
        var response = stub.prepareProviderConfig(request);
        // Legacy SDKs fill defaults into prepared_config; an empty prepared
        // config means "unchanged", so Configure gets the original bytes.
        var prepared = response.getPreparedConfig().getMsgpack().toByteArray();
        return new ProviderConfigValidation(
                prepared.length > 0 ? prepared : configMsgpack,
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public List<TfDiagnostic> configure(byte[] configMsgpack) {
        var request = Tfplugin5.Configure.Request.newBuilder()
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.configure(request).getDiagnosticsList());
    }

    @Override
    public List<TfDiagnostic> validateResourceConfig(String typeName, byte[] configMsgpack) {
        var request = Tfplugin5.ValidateResourceTypeConfig.Request.newBuilder()
                .setTypeName(typeName)
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.validateResourceTypeConfig(request).getDiagnosticsList());
    }

    @Override
    public List<TfDiagnostic> validateDataSourceConfig(String typeName, byte[] configMsgpack) {
        var request = Tfplugin5.ValidateDataSourceConfig.Request.newBuilder()
                .setTypeName(typeName)
                .setConfig(dynamicValue(configMsgpack))
                .build();
        return toDiagnostics(stub.validateDataSourceConfig(request).getDiagnosticsList());
    }

    @Override
    public UpgradeResult upgradeResourceState(String typeName, long storedSchemaVersion, byte[] rawStateJson) {
        var request = Tfplugin5.UpgradeResourceState.Request.newBuilder()
                .setTypeName(typeName)
                .setVersion(storedSchemaVersion)
                .setRawState(Tfplugin5.RawState.newBuilder()
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
        var request = Tfplugin5.PlanResourceChange.Request.newBuilder()
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
                response.getRequiresReplaceList().stream().map(Tfplugin5Rpc::toAttributePath).toList(),
                toDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public StateResult applyResourceChange(String typeName, byte[] priorState, byte[] plannedState,
                                           byte[] config, byte[] plannedPrivate) {
        var request = Tfplugin5.ApplyResourceChange.Request.newBuilder()
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
        var request = Tfplugin5.ReadResource.Request.newBuilder()
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
        var request = Tfplugin5.ImportResourceState.Request.newBuilder()
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
        var request = Tfplugin5.ReadDataSource.Request.newBuilder()
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
        stub.stop(Tfplugin5.Stop.Request.getDefaultInstance());
    }

    // ---------------------------------------------------------------
    // tfplugin5 -> neutral model conversions
    // ---------------------------------------------------------------

    public static TfSchema toTfSchema(Tfplugin5.Schema schema) {
        return new TfSchema(schema.getVersion(), toTfBlock(schema.getBlock()));
    }

    public static TfBlock toTfBlock(Tfplugin5.Schema.Block block) {
        return new TfBlock(
                block.getAttributesList().stream().map(Tfplugin5Rpc::toTfAttribute).toList(),
                block.getBlockTypesList().stream().map(Tfplugin5Rpc::toTfNestedBlock).toList());
    }

    private static Map<String, TfSchema> toTfSchemaMap(Map<String, Tfplugin5.Schema> schemas) {
        return schemas.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> toTfSchema(e.getValue())));
    }

    private static TfAttribute toTfAttribute(Tfplugin5.Schema.Attribute attribute) {
        return new TfAttribute(
                attribute.getName(),
                attribute.getType().toStringUtf8(),
                attribute.getRequired(),
                attribute.getOptional(),
                attribute.getComputed(),
                attribute.getSensitive(),
                attribute.getWriteOnly());
    }

    private static TfNestedBlock toTfNestedBlock(Tfplugin5.Schema.NestedBlock nestedBlock) {
        return new TfNestedBlock(
                nestedBlock.getTypeName(),
                toTfBlock(nestedBlock.getBlock()),
                toNesting(nestedBlock.getNesting()));
    }

    private static TfNestedBlock.Nesting toNesting(Tfplugin5.Schema.NestedBlock.NestingMode mode) {
        return switch (mode) {
            case SINGLE -> TfNestedBlock.Nesting.SINGLE;
            case LIST -> TfNestedBlock.Nesting.LIST;
            case SET -> TfNestedBlock.Nesting.SET;
            case MAP -> TfNestedBlock.Nesting.MAP;
            case GROUP -> TfNestedBlock.Nesting.GROUP;
            default -> TfNestedBlock.Nesting.INVALID;
        };
    }

    private static TfAttributePath toAttributePath(Tfplugin5.AttributePath path) {
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

    private static List<TfDiagnostic> toDiagnostics(List<Tfplugin5.Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> new TfDiagnostic(toSeverity(d.getSeverity()), d.getSummary(), d.getDetail(),
                        d.hasAttribute() ? toAttributePath(d.getAttribute()) : null))
                .toList();
    }

    private static TfDiagnostic.Severity toSeverity(Tfplugin5.Diagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> TfDiagnostic.Severity.ERROR;
            case WARNING -> TfDiagnostic.Severity.WARNING;
            default -> TfDiagnostic.Severity.INVALID;
        };
    }

    private static Tfplugin5.DynamicValue dynamicValue(byte[] msgpack) {
        return Tfplugin5.DynamicValue.newBuilder()
                .setMsgpack(ByteString.copyFrom(msgpack))
                .build();
    }
}
