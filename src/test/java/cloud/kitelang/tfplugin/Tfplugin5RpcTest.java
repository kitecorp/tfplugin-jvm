package cloud.kitelang.tfplugin;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tfplugin5.ProviderGrpc;
import tfplugin5.Tfplugin5.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for {@link Tfplugin5Rpc} paths not already exercised through the
 * handler and bridge-provider suites (which drive a real Tfplugin5Rpc over a
 * mocked stub): the Stop RPC, the absent-provider schema mapping, and
 * attribute-path rendering of an unset selector.
 */
@ExtendWith(MockitoExtension.class)
class Tfplugin5RpcTest {

    @Mock
    private ProviderGrpc.ProviderBlockingStub stub;

    @Test
    @DisplayName("stop() should call the Stop RPC")
    void stopShouldCallStopRpc() {
        when(stub.stop(any())).thenReturn(Stop.Response.getDefaultInstance());

        new Tfplugin5Rpc(stub).stop();

        verify(stub).stop(Stop.Request.getDefaultInstance());
    }

    @Test
    @DisplayName("getProviderSchema() should map an absent provider block to a null provider schema")
    void getProviderSchemaShouldMapAbsentProviderToNull() {
        when(stub.getSchema(any())).thenReturn(GetProviderSchema.Response.getDefaultInstance());

        var schema = new Tfplugin5Rpc(stub).getProviderSchema();

        verify(stub).getSchema(GetProviderSchema.Request.getDefaultInstance());
        assertNull(schema.provider());
        assertEquals(Map.of(), schema.resourceSchemas());
        assertEquals(Map.of(), schema.dataSourceSchemas());
    }

    @Test
    @DisplayName("validateProviderConfig() should call PrepareProviderConfig and return the prepared config")
    void validateProviderConfigShouldCallPrepareProviderConfig() {
        // tfplugin5's legacy-SDK providers fill defaults in PrepareProviderConfig;
        // the prepared config, not the original, must be what Configure receives.
        var prepared = new byte[]{9, 9};
        when(stub.prepareProviderConfig(any())).thenReturn(PrepareProviderConfig.Response.newBuilder()
                .setPreparedConfig(DynamicValue.newBuilder()
                        .setMsgpack(ByteString.copyFrom(prepared)))
                .build());

        var validation = new Tfplugin5Rpc(stub).validateProviderConfig(new byte[]{1});

        assertArrayEquals(prepared, validation.preparedConfig());
        assertEquals(List.of(), validation.diagnostics());
        var captor = org.mockito.ArgumentCaptor.forClass(PrepareProviderConfig.Request.class);
        verify(stub).prepareProviderConfig(captor.capture());
        assertEquals(ByteString.copyFrom(new byte[]{1}), captor.getValue().getConfig().getMsgpack());
    }

    @Test
    @DisplayName("validateProviderConfig() should fall back to the input config when prepared_config is empty")
    void validateProviderConfigShouldFallBackToInputConfig() {
        when(stub.prepareProviderConfig(any()))
                .thenReturn(PrepareProviderConfig.Response.getDefaultInstance());

        var validation = new Tfplugin5Rpc(stub).validateProviderConfig(new byte[]{7});

        assertArrayEquals(new byte[]{7}, validation.preparedConfig());
    }

    @Test
    @DisplayName("validateProviderConfig() should map an ERROR diagnostic with its attribute path")
    void validateProviderConfigShouldMapDiagnosticAttributePath() {
        when(stub.prepareProviderConfig(any())).thenReturn(PrepareProviderConfig.Response.newBuilder()
                .addDiagnostics(Diagnostic.newBuilder()
                        .setSeverity(Diagnostic.Severity.ERROR)
                        .setSummary("Invalid region")
                        .setDetail("region 'mars' is not valid")
                        .setAttribute(AttributePath.newBuilder()
                                .addSteps(AttributePath.Step.newBuilder().setAttributeName("region"))))
                .build());

        var validation = new Tfplugin5Rpc(stub).validateProviderConfig(new byte[]{1});

        assertEquals(1, validation.diagnostics().size());
        var diagnostic = validation.diagnostics().get(0);
        assertEquals(TfDiagnostic.Severity.ERROR, diagnostic.severity());
        assertEquals("Invalid region", diagnostic.summary());
        assertEquals("region", diagnostic.attributePath().render());
    }

    @Test
    @DisplayName("configure() should map a diagnostic without an attribute to a null attribute path")
    void configureShouldMapAbsentAttributeToNullPath() {
        when(stub.configure(any())).thenReturn(Configure.Response.newBuilder()
                .addDiagnostics(Diagnostic.newBuilder()
                        .setSeverity(Diagnostic.Severity.ERROR)
                        .setSummary("Boom")
                        .setDetail("it broke"))
                .build());

        var diagnostics = new Tfplugin5Rpc(stub).configure(new byte[]{1});

        assertEquals(List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Boom", "it broke")),
                diagnostics);
        assertNull(diagnostics.get(0).attributePath());
    }

    @Test
    @DisplayName("validateDataSourceConfig() should call the ValidateDataSourceConfig RPC and map diagnostics")
    void validateDataSourceConfigShouldCallProtocol5Rpc() {
        when(stub.validateDataSourceConfig(any())).thenReturn(ValidateDataSourceConfig.Response.newBuilder()
                .addDiagnostics(Diagnostic.newBuilder()
                        .setSeverity(Diagnostic.Severity.ERROR)
                        .setSummary("Missing name")
                        .setDetail("name is required"))
                .build());

        var diagnostics = new Tfplugin5Rpc(stub).validateDataSourceConfig("aws_ami", new byte[]{5});

        assertEquals(List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Missing name", "name is required")),
                diagnostics);
        var captor = org.mockito.ArgumentCaptor.forClass(ValidateDataSourceConfig.Request.class);
        verify(stub).validateDataSourceConfig(captor.capture());
        assertEquals("aws_ami", captor.getValue().getTypeName());
        assertEquals(ByteString.copyFrom(new byte[]{5}), captor.getValue().getConfig().getMsgpack());
    }

    @Test
    @DisplayName("getProviderSchema() should map each resource schema's version")
    void getProviderSchemaShouldMapSchemaVersion() {
        when(stub.getSchema(any())).thenReturn(GetProviderSchema.Response.newBuilder()
                .putResourceSchemas("aws_instance", Schema.newBuilder()
                        .setVersion(4)
                        .setBlock(Schema.Block.getDefaultInstance())
                        .build())
                .build());

        var schema = new Tfplugin5Rpc(stub).getProviderSchema();

        assertEquals(4, schema.resourceSchemas().get("aws_instance").version());
    }

    @Test
    @DisplayName("upgradeResourceState() should send the stored version and raw JSON state")
    void upgradeResourceStateShouldMapRequestAndResponse() {
        var upgradedMsgpack = new byte[]{8, 8};
        when(stub.upgradeResourceState(any())).thenReturn(UpgradeResourceState.Response.newBuilder()
                .setUpgradedState(DynamicValue.newBuilder()
                        .setMsgpack(ByteString.copyFrom(upgradedMsgpack)))
                .addDiagnostics(Diagnostic.newBuilder()
                        .setSeverity(Diagnostic.Severity.WARNING)
                        .setSummary("Assumed default")
                        .setDetail("filled a new attribute"))
                .build());
        var rawStateJson = "{\"ami\":\"ami-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var result = new Tfplugin5Rpc(stub).upgradeResourceState("aws_instance", 1, rawStateJson);

        var captor = org.mockito.ArgumentCaptor.forClass(UpgradeResourceState.Request.class);
        verify(stub).upgradeResourceState(captor.capture());
        assertEquals("aws_instance", captor.getValue().getTypeName());
        assertEquals(1, captor.getValue().getVersion());
        assertEquals(ByteString.copyFrom(rawStateJson), captor.getValue().getRawState().getJson());

        assertArrayEquals(upgradedMsgpack, result.upgradedState());
        assertEquals(List.of(new TfDiagnostic(TfDiagnostic.Severity.WARNING,
                "Assumed default", "filled a new attribute")), result.diagnostics());
    }

    @Test
    @DisplayName("planResourceChange() should render an unset path selector as <?>")
    void planShouldRenderUnsetSelector() {
        when(stub.planResourceChange(any())).thenReturn(PlanResourceChange.Response.newBuilder()
                .addRequiresReplace(AttributePath.newBuilder()
                        .addSteps(AttributePath.Step.newBuilder().setAttributeName("ami"))
                        .addSteps(AttributePath.Step.getDefaultInstance()))
                .build());

        var plan = new Tfplugin5Rpc(stub).planResourceChange(
                "aws_instance", new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[0]);

        assertEquals(List.of("ami<?>"),
                plan.requiresReplace().stream().map(TfAttributePath::render).toList());
    }
}
