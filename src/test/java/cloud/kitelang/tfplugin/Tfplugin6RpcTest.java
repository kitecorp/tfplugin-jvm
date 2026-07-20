package cloud.kitelang.tfplugin;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tfplugin6.ProviderGrpc;
import tfplugin6.Tfplugin6.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD tests for {@link Tfplugin6Rpc}: the protocol-6 side of the version-agnostic
 * RPC facade. Mocks the tfplugin6 blocking stub to verify that every neutral
 * operation maps to the correct protocol-6 RPC (several are renamed relative to
 * tfplugin5) with correctly built requests, and that responses — including
 * nested-attribute schemas, which do not exist in tfplugin5 — map back into the
 * neutral model.
 */
@ExtendWith(MockitoExtension.class)
class Tfplugin6RpcTest {

    private static final String TYPE_NAME = "aws_instance";

    private static final byte[] PRIOR = {1};
    private static final byte[] PROPOSED = {2};
    private static final byte[] CONFIG = {3};
    private static final byte[] PRIVATE = {4};

    @Mock
    private ProviderGrpc.ProviderBlockingStub stub;

    private Tfplugin6Rpc newRpc() {
        return new Tfplugin6Rpc(stub);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static ByteString utf8(String text) {
        return ByteString.copyFrom(text, StandardCharsets.UTF_8);
    }

    private static DynamicValue msgpack(byte[] bytes) {
        return DynamicValue.newBuilder().setMsgpack(ByteString.copyFrom(bytes)).build();
    }

    private static Schema.Attribute typedAttribute(String name, String ctyTypeJson) {
        return Schema.Attribute.newBuilder()
                .setName(name)
                .setType(utf8(ctyTypeJson))
                .build();
    }

    private static Schema schemaOf(Schema.Attribute... attributes) {
        var block = Schema.Block.newBuilder();
        for (var attribute : attributes) {
            block.addAttributes(attribute);
        }
        return Schema.newBuilder().setBlock(block).build();
    }

    // ---------------------------------------------------------------
    // 1. GetProviderSchema (renamed from tfplugin5's GetSchema)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("getProviderSchema()")
    class GetProviderSchemaMapping {

        @Test
        @DisplayName("should call the GetProviderSchema RPC and map schemas into the neutral model")
        void shouldMapSchemasIntoNeutralModel() {
            var response = GetProviderSchema.Response.newBuilder()
                    .setProvider(schemaOf(Schema.Attribute.newBuilder()
                            .setName("region").setType(utf8("\"string\"")).setOptional(true).build()))
                    .putResourceSchemas(TYPE_NAME, schemaOf(
                            Schema.Attribute.newBuilder()
                                    .setName("ami").setType(utf8("\"string\"")).setRequired(true).build(),
                            Schema.Attribute.newBuilder()
                                    .setName("id").setType(utf8("\"string\"")).setComputed(true).build()))
                    .putDataSourceSchemas("aws_ami", schemaOf(Schema.Attribute.newBuilder()
                            .setName("name").setType(utf8("\"string\"")).setRequired(true)
                            .setSensitive(true).setWriteOnly(true).build()))
                    .build();
            when(stub.getProviderSchema(any())).thenReturn(response);

            var schema = newRpc().getProviderSchema();

            verify(stub).getProviderSchema(GetProviderSchema.Request.getDefaultInstance());
            assertEquals(new TfAttribute("region", "\"string\"", false, true, false, false, false),
                    schema.provider().block().attributes().get(0));

            var instance = schema.resourceSchemas().get(TYPE_NAME);
            assertEquals(List.of(
                            new TfAttribute("ami", "\"string\"", true, false, false, false, false),
                            new TfAttribute("id", "\"string\"", false, false, true, false, false)),
                    instance.block().attributes());

            var ami = schema.dataSourceSchemas().get("aws_ami");
            assertEquals(new TfAttribute("name", "\"string\"", true, false, false, true, true),
                    ami.block().attributes().get(0));
        }

        @Test
        @DisplayName("should map each resource schema's version")
        void shouldMapSchemaVersion() {
            when(stub.getProviderSchema(any())).thenReturn(GetProviderSchema.Response.newBuilder()
                    .putResourceSchemas(TYPE_NAME, Schema.newBuilder()
                            .setVersion(4)
                            .setBlock(Schema.Block.getDefaultInstance())
                            .build())
                    .build());

            var schema = newRpc().getProviderSchema();

            assertEquals(4, schema.resourceSchemas().get(TYPE_NAME).version());
        }

        @Test
        @DisplayName("should map an absent provider block to a null provider schema")
        void shouldMapAbsentProviderToNull() {
            when(stub.getProviderSchema(any())).thenReturn(GetProviderSchema.Response.getDefaultInstance());

            var schema = newRpc().getProviderSchema();

            assertNull(schema.provider());
            assertEquals(Map.of(), schema.resourceSchemas());
            assertEquals(Map.of(), schema.dataSourceSchemas());
        }

        @Test
        @DisplayName("should map nested blocks with their nesting mode and inner attributes")
        void shouldMapNestedBlocks() {
            var nested = Schema.NestedBlock.newBuilder()
                    .setTypeName("ingress")
                    .setNesting(Schema.NestedBlock.NestingMode.SET)
                    .setBlock(Schema.Block.newBuilder()
                            .addAttributes(typedAttribute("from_port", "\"number\"")))
                    .build();
            var schema = Schema.newBuilder()
                    .setBlock(Schema.Block.newBuilder().addBlockTypes(nested))
                    .build();
            when(stub.getProviderSchema(any())).thenReturn(GetProviderSchema.Response.newBuilder()
                    .putResourceSchemas("aws_security_group", schema)
                    .build());

            var converted = newRpc().getProviderSchema()
                    .resourceSchemas().get("aws_security_group").block().blockTypes().get(0);

            assertEquals("ingress", converted.typeName());
            assertEquals(TfNestedBlock.Nesting.SET, converted.nesting());
            assertEquals(new TfAttribute("from_port", "\"number\"", false, false, false, false, false),
                    converted.block().attributes().get(0));
        }
    }

    // ---------------------------------------------------------------
    // 2. Nested attributes (tfplugin6-only schema shape)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("nested_type cty type synthesis")
    class NestedAttributeSynthesis {

        /** Wraps a nested-attribute declaration in a one-resource schema response and converts it. */
        private TfAttribute convert(Schema.Attribute attribute) {
            when(stub.getProviderSchema(any())).thenReturn(GetProviderSchema.Response.newBuilder()
                    .putResourceSchemas(TYPE_NAME, schemaOf(attribute))
                    .build());
            return newRpc().getProviderSchema()
                    .resourceSchemas().get(TYPE_NAME).block().attributes().get(0);
        }

        private Schema.Object nestedObject(Schema.Object.NestingMode nesting, Schema.Attribute... attributes) {
            var object = Schema.Object.newBuilder().setNesting(nesting);
            for (var attribute : attributes) {
                object.addAttributes(attribute);
            }
            return object.build();
        }

        @Test
        @DisplayName("SINGLE nesting should synthesise a cty object type")
        void singleNestingShouldSynthesiseObjectType() {
            var attribute = Schema.Attribute.newBuilder()
                    .setName("settings")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SINGLE,
                            typedAttribute("endpoint", "\"string\""),
                            typedAttribute("ports", "[\"list\",\"number\"]")))
                    .setOptional(true)
                    .build();

            assertEquals(new TfAttribute("settings",
                            "[\"object\",{\"endpoint\":\"string\",\"ports\":[\"list\",\"number\"]}]",
                            false, true, false, false, false,
                            new TfObjectType(List.of(
                                    new TfAttribute("endpoint", "\"string\"", false, false, false, false, false),
                                    new TfAttribute("ports", "[\"list\",\"number\"]", false, false, false, false, false)),
                                    TfNestedBlock.Nesting.SINGLE)),
                    convert(attribute));
        }

        @Test
        @DisplayName("the structured nested_type (members + nesting) is carried on the neutral attribute")
        void nestedTypeStructureIsCarried() {
            // #12 needs the structure, not just the synthesised cty JSON, to
            // render nested attributes as first-class Kite types.
            var attribute = Schema.Attribute.newBuilder()
                    .setName("rules")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.LIST,
                            typedAttribute("action", "\"string\""),
                            typedAttribute("ports", "[\"list\",\"number\"]")))
                    .build();

            assertEquals(new TfObjectType(List.of(
                            new TfAttribute("action", "\"string\"", false, false, false, false, false),
                            new TfAttribute("ports", "[\"list\",\"number\"]", false, false, false, false, false)),
                            TfNestedBlock.Nesting.LIST),
                    convert(attribute).nestedType());
        }

        @Test
        @DisplayName("a plain (cty-typed) attribute carries a null nested_type")
        void plainAttributeHasNullNestedType() {
            assertNull(convert(typedAttribute("region", "\"string\"")).nestedType());
        }

        @Test
        @DisplayName("LIST nesting should wrap the object type in a cty list")
        void listNestingShouldWrapInList() {
            var attribute = Schema.Attribute.newBuilder()
                    .setName("rules")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.LIST,
                            typedAttribute("action", "\"string\"")))
                    .build();

            assertEquals("[\"list\",[\"object\",{\"action\":\"string\"}]]", convert(attribute).typeJson());
        }

        @Test
        @DisplayName("SET nesting should wrap the object type in a cty set")
        void setNestingShouldWrapInSet() {
            var attribute = Schema.Attribute.newBuilder()
                    .setName("tags")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SET,
                            typedAttribute("key", "\"string\"")))
                    .build();

            assertEquals("[\"set\",[\"object\",{\"key\":\"string\"}]]", convert(attribute).typeJson());
        }

        @Test
        @DisplayName("MAP nesting should wrap the object type in a cty map")
        void mapNestingShouldWrapInMap() {
            var attribute = Schema.Attribute.newBuilder()
                    .setName("environments")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.MAP,
                            typedAttribute("url", "\"string\"")))
                    .build();

            assertEquals("[\"map\",[\"object\",{\"url\":\"string\"}]]", convert(attribute).typeJson());
        }

        @Test
        @DisplayName("nested attributes inside nested attributes should synthesise recursively")
        void nestedTypesShouldSynthesiseRecursively() {
            var inner = Schema.Attribute.newBuilder()
                    .setName("inner")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SINGLE,
                            typedAttribute("leaf", "\"bool\"")))
                    .build();
            var attribute = Schema.Attribute.newBuilder()
                    .setName("outer")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SINGLE, inner))
                    .build();

            assertEquals("[\"object\",{\"inner\":[\"object\",{\"leaf\":\"bool\"}]}]",
                    convert(attribute).typeJson());
        }

        @Test
        @DisplayName("a sensitive leaf inside nested_type should mark the whole attribute sensitive")
        void nestedSensitiveLeafShouldMarkAttributeSensitive() {
            // cty type JSON has no per-leaf sensitivity slot, so a sensitive
            // nested attribute must surface on the containing attribute or the
            // flag is silently dropped (kitecorp/kite-providers#6)
            var attribute = Schema.Attribute.newBuilder()
                    .setName("credentials")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SINGLE,
                            typedAttribute("username", "\"string\""),
                            Schema.Attribute.newBuilder()
                                    .setName("password")
                                    .setType(utf8("\"string\""))
                                    .setSensitive(true)
                                    .build()))
                    .setOptional(true)
                    .build();

            assertEquals(new TfAttribute("credentials",
                            "[\"object\",{\"username\":\"string\",\"password\":\"string\"}]",
                            false, true, false, true, false,
                            new TfObjectType(List.of(
                                    new TfAttribute("username", "\"string\"", false, false, false, false, false),
                                    new TfAttribute("password", "\"string\"", false, false, false, true, false)),
                                    TfNestedBlock.Nesting.SINGLE)),
                    convert(attribute));
        }

        @Test
        @DisplayName("a sensitive leaf nested two levels deep should still mark the attribute sensitive")
        void deeplyNestedSensitiveLeafShouldMarkAttributeSensitive() {
            var inner = Schema.Attribute.newBuilder()
                    .setName("inner")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.SINGLE,
                            Schema.Attribute.newBuilder()
                                    .setName("token")
                                    .setType(utf8("\"string\""))
                                    .setSensitive(true)
                                    .build()))
                    .build();
            var attribute = Schema.Attribute.newBuilder()
                    .setName("outer")
                    .setNestedType(nestedObject(Schema.Object.NestingMode.LIST, inner))
                    .build();

            assertTrue(convert(attribute).sensitive(),
                    "sensitivity must propagate through every nested_type level");
        }

        @Test
        @DisplayName("an attribute with neither type nor nested_type should fall back to dynamic")
        void attributeWithoutAnyTypeShouldFallBackToDynamic() {
            var attribute = Schema.Attribute.newBuilder()
                    .setName("mystery")
                    .build();

            assertEquals("\"dynamic\"", convert(attribute).typeJson());
        }
    }

    // ---------------------------------------------------------------
    // 3. ConfigureProvider (renamed from tfplugin5's Configure)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("configure()")
    class ConfigureMapping {

        @Test
        @DisplayName("should call the ConfigureProvider RPC with the config msgpack and map diagnostics")
        void shouldCallConfigureProviderAndMapDiagnostics() {
            when(stub.configureProvider(any())).thenReturn(ConfigureProvider.Response.newBuilder()
                    .addDiagnostics(Diagnostic.newBuilder()
                            .setSeverity(Diagnostic.Severity.ERROR)
                            .setSummary("InvalidRegion")
                            .setDetail("region 'mars' is not valid"))
                    .build());

            var diagnostics = newRpc().configure(CONFIG);

            assertEquals(List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR,
                    "InvalidRegion", "region 'mars' is not valid")), diagnostics);
            var captor = ArgumentCaptor.forClass(ConfigureProvider.Request.class);
            verify(stub).configureProvider(captor.capture());
            assertEquals(ByteString.copyFrom(CONFIG), captor.getValue().getConfig().getMsgpack());
        }
    }

    @Nested
    @DisplayName("validateProviderConfig()")
    class ValidateProviderConfigMapping {

        @Test
        @DisplayName("should call the ValidateProviderConfig RPC and pass the config through unchanged")
        void shouldCallValidateProviderConfig() {
            // Protocol 6 dropped tfplugin5's prepare semantics: the response carries
            // no prepared config, so the input must pass through to Configure as-is.
            when(stub.validateProviderConfig(any()))
                    .thenReturn(ValidateProviderConfig.Response.getDefaultInstance());

            var validation = newRpc().validateProviderConfig(CONFIG);

            assertArrayEquals(CONFIG, validation.preparedConfig());
            assertEquals(List.of(), validation.diagnostics());
            var captor = ArgumentCaptor.forClass(ValidateProviderConfig.Request.class);
            verify(stub).validateProviderConfig(captor.capture());
            assertEquals(ByteString.copyFrom(CONFIG), captor.getValue().getConfig().getMsgpack());
        }

        @Test
        @DisplayName("should map an ERROR diagnostic with its attribute path")
        void shouldMapDiagnosticAttributePath() {
            when(stub.validateProviderConfig(any())).thenReturn(ValidateProviderConfig.Response.newBuilder()
                    .addDiagnostics(Diagnostic.newBuilder()
                            .setSeverity(Diagnostic.Severity.ERROR)
                            .setSummary("Invalid directory")
                            .setDetail("not a directory")
                            .setAttribute(AttributePath.newBuilder()
                                    .addSteps(AttributePath.Step.newBuilder()
                                            .setAttributeName("resource_directory"))))
                    .build());

            var validation = newRpc().validateProviderConfig(CONFIG);

            assertEquals(1, validation.diagnostics().size());
            var diagnostic = validation.diagnostics().get(0);
            assertEquals(TfDiagnostic.Severity.ERROR, diagnostic.severity());
            assertEquals("resource_directory", diagnostic.attributePath().render());
        }
    }

    // ---------------------------------------------------------------
    // 4. ValidateResourceConfig (renamed from ValidateResourceTypeConfig)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("validateResourceConfig()")
    class ValidateMapping {

        @Test
        @DisplayName("should call the ValidateResourceConfig RPC with type name and config")
        void shouldCallValidateResourceConfig() {
            when(stub.validateResourceConfig(any()))
                    .thenReturn(ValidateResourceConfig.Response.getDefaultInstance());

            var diagnostics = newRpc().validateResourceConfig(TYPE_NAME, CONFIG);

            assertEquals(List.of(), diagnostics);
            var captor = ArgumentCaptor.forClass(ValidateResourceConfig.Request.class);
            verify(stub).validateResourceConfig(captor.capture());
            assertEquals(TYPE_NAME, captor.getValue().getTypeName());
            assertEquals(ByteString.copyFrom(CONFIG), captor.getValue().getConfig().getMsgpack());
        }
    }

    // ---------------------------------------------------------------
    // 5. PlanResourceChange
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("planResourceChange()")
    class PlanMapping {

        @Test
        @DisplayName("should build the request from all five arguments and map the full response")
        void shouldMapRequestAndResponse() {
            when(stub.planResourceChange(any())).thenReturn(PlanResourceChange.Response.newBuilder()
                    .setPlannedState(msgpack(new byte[]{9, 9}))
                    .setPlannedPrivate(ByteString.copyFrom(new byte[]{8}))
                    .addRequiresReplace(AttributePath.newBuilder()
                            .addSteps(AttributePath.Step.newBuilder().setAttributeName("tags"))
                            .addSteps(AttributePath.Step.newBuilder().setElementKeyString("env"))
                            .addSteps(AttributePath.Step.newBuilder().setElementKeyInt(0)))
                    .addDiagnostics(Diagnostic.newBuilder()
                            .setSeverity(Diagnostic.Severity.WARNING)
                            .setSummary("Deprecated")
                            .setDetail("attribute is deprecated"))
                    .build());

            var plan = newRpc().planResourceChange(TYPE_NAME, PRIOR, PROPOSED, CONFIG, PRIVATE);

            var captor = ArgumentCaptor.forClass(PlanResourceChange.Request.class);
            verify(stub).planResourceChange(captor.capture());
            var request = captor.getValue();
            assertEquals(TYPE_NAME, request.getTypeName());
            assertEquals(ByteString.copyFrom(PRIOR), request.getPriorState().getMsgpack());
            assertEquals(ByteString.copyFrom(PROPOSED), request.getProposedNewState().getMsgpack());
            assertEquals(ByteString.copyFrom(CONFIG), request.getConfig().getMsgpack());
            assertEquals(ByteString.copyFrom(PRIVATE), request.getPriorPrivate());

            assertArrayEquals(new byte[]{9, 9}, plan.plannedState());
            assertArrayEquals(new byte[]{8}, plan.plannedPrivate());
            assertEquals(1, plan.requiresReplace().size());
            assertEquals("tags[\"env\"][0]", plan.requiresReplace().get(0).render());
            assertEquals(List.of(new TfDiagnostic(TfDiagnostic.Severity.WARNING,
                    "Deprecated", "attribute is deprecated")), plan.diagnostics());
        }
    }

    // ---------------------------------------------------------------
    // 6. ApplyResourceChange
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("applyResourceChange()")
    class ApplyMapping {

        @Test
        @DisplayName("should build the request from all five arguments and map the response")
        void shouldMapRequestAndResponse() {
            when(stub.applyResourceChange(any())).thenReturn(ApplyResourceChange.Response.newBuilder()
                    .setNewState(msgpack(new byte[]{7}))
                    .setPrivate(ByteString.copyFrom(new byte[]{6}))
                    .build());

            var result = newRpc().applyResourceChange(TYPE_NAME, PRIOR, PROPOSED, CONFIG, PRIVATE);

            var captor = ArgumentCaptor.forClass(ApplyResourceChange.Request.class);
            verify(stub).applyResourceChange(captor.capture());
            var request = captor.getValue();
            assertEquals(TYPE_NAME, request.getTypeName());
            assertEquals(ByteString.copyFrom(PRIOR), request.getPriorState().getMsgpack());
            assertEquals(ByteString.copyFrom(PROPOSED), request.getPlannedState().getMsgpack());
            assertEquals(ByteString.copyFrom(CONFIG), request.getConfig().getMsgpack());
            assertEquals(ByteString.copyFrom(PRIVATE), request.getPlannedPrivate());

            assertArrayEquals(new byte[]{7}, result.state());
            assertArrayEquals(new byte[]{6}, result.privateBytes());
            assertEquals(List.of(), result.diagnostics());
        }
    }

    // ---------------------------------------------------------------
    // 7. ReadResource
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("readResource()")
    class ReadMapping {

        @Test
        @DisplayName("should build the request with current state and private bytes and map the response")
        void shouldMapRequestAndResponse() {
            when(stub.readResource(any())).thenReturn(ReadResource.Response.newBuilder()
                    .setNewState(msgpack(new byte[]{5}))
                    .setPrivate(ByteString.copyFrom(new byte[]{4}))
                    .build());

            var result = newRpc().readResource(TYPE_NAME, PRIOR, PRIVATE);

            var captor = ArgumentCaptor.forClass(ReadResource.Request.class);
            verify(stub).readResource(captor.capture());
            assertEquals(TYPE_NAME, captor.getValue().getTypeName());
            assertEquals(ByteString.copyFrom(PRIOR), captor.getValue().getCurrentState().getMsgpack());
            assertEquals(ByteString.copyFrom(PRIVATE), captor.getValue().getPrivate());

            assertArrayEquals(new byte[]{5}, result.state());
            assertArrayEquals(new byte[]{4}, result.privateBytes());
        }
    }

    // ---------------------------------------------------------------
    // 8. ReadDataSource
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("readDataSource()")
    class ReadDataSourceMapping {

        @Test
        @DisplayName("should build the request with type name and config and map the response")
        void shouldMapRequestAndResponse() {
            when(stub.readDataSource(any())).thenReturn(ReadDataSource.Response.newBuilder()
                    .setState(msgpack(new byte[]{3}))
                    .build());

            var result = newRpc().readDataSource("aws_ami", CONFIG);

            var captor = ArgumentCaptor.forClass(ReadDataSource.Request.class);
            verify(stub).readDataSource(captor.capture());
            assertEquals("aws_ami", captor.getValue().getTypeName());
            assertEquals(ByteString.copyFrom(CONFIG), captor.getValue().getConfig().getMsgpack());

            assertArrayEquals(new byte[]{3}, result.state());
            assertEquals(List.of(), result.diagnostics());
        }
    }

    // ---------------------------------------------------------------
    // 8b. ValidateDataResourceConfig (renamed from tfplugin5's
    //     ValidateDataSourceConfig)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("validateDataSourceConfig()")
    class ValidateDataSourceConfigMapping {

        @Test
        @DisplayName("should call the ValidateDataResourceConfig RPC and map diagnostics")
        void shouldCallRenamedProtocol6Rpc() {
            when(stub.validateDataResourceConfig(any()))
                    .thenReturn(ValidateDataResourceConfig.Response.newBuilder()
                            .addDiagnostics(Diagnostic.newBuilder()
                                    .setSeverity(Diagnostic.Severity.ERROR)
                                    .setSummary("Missing name")
                                    .setDetail("name is required"))
                            .build());

            var diagnostics = newRpc().validateDataSourceConfig("aws_ami", CONFIG);

            assertEquals(List.of(new TfDiagnostic(
                            TfDiagnostic.Severity.ERROR, "Missing name", "name is required")),
                    diagnostics);
            var captor = ArgumentCaptor.forClass(ValidateDataResourceConfig.Request.class);
            verify(stub).validateDataResourceConfig(captor.capture());
            assertEquals("aws_ami", captor.getValue().getTypeName());
            assertEquals(ByteString.copyFrom(CONFIG), captor.getValue().getConfig().getMsgpack());
        }
    }

    // ---------------------------------------------------------------
    // 9. StopProvider (renamed from tfplugin5's Stop)
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("stop()")
    class StopMapping {

        @Test
        @DisplayName("should call the StopProvider RPC")
        void shouldCallStopProvider() {
            when(stub.stopProvider(any())).thenReturn(StopProvider.Response.getDefaultInstance());

            newRpc().stop();

            verify(stub).stopProvider(StopProvider.Request.getDefaultInstance());
        }
    }

    // ---------------------------------------------------------------
    // 10. UpgradeResourceState — same RPC name in both protocols
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("upgradeResourceState()")
    class UpgradeResourceStateMapping {

        @Test
        @DisplayName("should send the stored version and raw JSON state and map the upgraded state back")
        void shouldMapRequestAndResponse() {
            var upgradedMsgpack = new byte[]{8, 8};
            when(stub.upgradeResourceState(any())).thenReturn(UpgradeResourceState.Response.newBuilder()
                    .setUpgradedState(msgpack(upgradedMsgpack))
                    .build());
            var rawStateJson = "{\"ami\":\"ami-1\"}".getBytes(StandardCharsets.UTF_8);

            var result = newRpc().upgradeResourceState(TYPE_NAME, 1, rawStateJson);

            var captor = ArgumentCaptor.forClass(UpgradeResourceState.Request.class);
            verify(stub).upgradeResourceState(captor.capture());
            assertEquals(TYPE_NAME, captor.getValue().getTypeName());
            assertEquals(1, captor.getValue().getVersion());
            assertEquals(ByteString.copyFrom(rawStateJson), captor.getValue().getRawState().getJson());

            assertArrayEquals(upgradedMsgpack, result.upgradedState());
            assertEquals(List.of(), result.diagnostics());
        }
    }
}
