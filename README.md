# tfplugin-jvm

A **JVM client for the Terraform plugin protocol**. It launches any Terraform
provider plugin, completes the go-plugin handshake, negotiates `tfplugin5` vs
`tfplugin6`, and drives the provider's resources and data sources over gRPC —
all from Java (or any JVM language).

It speaks only Terraform's wire protocol. It is provider-agnostic and has no
dependency on Terraform core, OpenTofu, or any particular consumer.

## Why this exists

HashiCorp publishes the plugin protocol, but the tooling that *drives* a
provider — the **client** side — ships only inside Terraform/OpenTofu, in Go.
The client role is not even packaged as a standalone Go library:
[hashicorp/terraform#32769](https://github.com/hashicorp/terraform/issues/32769)
is an open request for exactly that. Pulmi's Java SDK reaches Terraform
providers by shelling out through their Go bridge, never speaking `tfplugin`
from the JVM itself.

As of 2026, `tfplugin-jvm` appears to be the only JVM implementation of the
Terraform provider *client* protocol in existence. It was extracted from the
[Kite](https://kitelang.cloud) engine's `terraform-bridge`, which uses it to run
the entire Terraform provider ecosystem underneath Kite's own IaC language.

## What it does

| Concern | Type |
|---|---|
| go-plugin handshake, process lifecycle, health, protocol negotiation | `GoPluginClient` |
| Version-agnostic RPC facade (one API over tfplugin5 **and** tfplugin6) | `TerraformProviderRpc` (`Tfplugin5Rpc` / `Tfplugin6Rpc`) |
| cty ⇄ Java encoding (`DynamicValue` msgpack, incl. unknown/null ext types) | `CtyCodec` |
| Neutral schema model (blocks, attributes, nested types, diagnostics) | `TfSchema`, `TfBlock`, `TfAttribute`, `TfObjectType`, `TfNestedBlock`, `TfDiagnostic`, `TfAttributePath` |
| Registry download + SHA256SUMS + detached-OpenPGP signature verification | `TerraformRegistryClient`, `OpenPgpSignatureVerifier` |
| Per-call gRPC deadlines | `RpcDeadlines`, `RpcDeadlineInterceptor` |

The tfplugin5/tfplugin6 `.proto` sources ship with the library; their gRPC stubs
are generated at build time.

## Example — create a `random_pet` with hashicorp/random

```java
import cloud.kitelang.tfplugin.CtyCodec;
import cloud.kitelang.tfplugin.GoPluginClient;
import cloud.kitelang.tfplugin.TerraformProviderRpc;
import cloud.kitelang.tfplugin.TerraformRegistryClient;

import java.nio.file.Path;
import java.util.LinkedHashMap;

public class RandomPetExample {
    public static void main(String[] args) throws Exception {
        // 1. Download hashicorp/random from the public registry. The ZIP's
        //    SHA256SUMS and its detached GPG signature are verified against the
        //    registry-advertised signing key before the binary is trusted.
        var registry = new TerraformRegistryClient(
                Path.of(System.getProperty("user.home"), ".cache/tfplugin-jvm"));
        Path providerBinary = registry.ensureProvider("hashicorp/random", null); // null = latest

        // 2. Launch the provider and complete the go-plugin handshake. The app
        //    protocol version (5 or 6) is negotiated and hidden behind the facade.
        try (var client = new GoPluginClient(providerBinary)) {
            TerraformProviderRpc tf = client.rpc();
            System.out.println("negotiated tfplugin" + client.getAppProtocolVersion());

            // 3. random has an empty provider config block.
            var codec = new CtyCodec();
            tf.configure(codec.encode(new LinkedHashMap<>(), "[\"object\",{}]"));

            // 4. The cty type of a random_pet's state/config. In real code, derive
            //    this from tf.getProviderSchema().resourceSchemas().get("random_pet").
            var petType = "[\"object\",{"
                    + "\"keepers\":[\"map\",\"string\"],"
                    + "\"length\":\"number\",\"prefix\":\"string\","
                    + "\"separator\":\"string\",\"id\":\"string\"}]";

            var desired = new LinkedHashMap<String, Object>();
            desired.put("length", 2);
            desired.put("separator", "-");
            desired.put("keepers", null);
            desired.put("prefix", null);
            desired.put("id", null);            // computed — unknown until apply
            byte[] config = codec.encode(desired, petType);

            // 5. Plan (prior state is cty-nil for a create), then apply.
            var plan = tf.planResourceChange("random_pet",
                    null /* prior: nil */, config, config, new byte[0]);
            var applied = tf.applyResourceChange("random_pet",
                    null, plan.plannedState(), config, plan.plannedPrivate());

            var state = codec.decode(applied.state(), petType);
            System.out.println("created random_pet.id = " + state.get("id"));
        }
    }
}
```

`priorState`, `config`, and every state payload are cty msgpack byte arrays; a
cty null is the msgpack nil encoding (pass `null`), never an empty array.

## Install

Not yet published to Maven Central — tracked in
[kitecorp/tfplugin-jvm#2](https://github.com/kitecorp/tfplugin-jvm/issues/2).
Until then, consume it as a git submodule with a local Gradle dependency
substitution (this is how the Kite workspace builds it), or `./gradlew
publishToMavenLocal` and depend on `cloud.kitelang:tfplugin-jvm:0.1.0`.

## Build

Requires JDK 21 (Gradle toolchain; auto-provisioned).

```bash
./gradlew build   # compile + protocol tests
./gradlew test    # tests only
```

## License

Apache-2.0.
