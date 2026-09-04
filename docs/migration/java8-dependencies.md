# Java 8 dependency and license baseline

This inventory is the release baseline for dependencies currently declared by
the Clara reactor. Versions are frozen in the parent POM. Java compatibility is
verified from compiled class-file versions, JDK 9+ compilation with `release=8`,
and a clean compile/test reactor on JDK 8u202.

## Distributed runtime dependencies

| Component | Version | Introduced by | Java 8 | License | Distribution action |
| --- | --- | --- | --- | --- | --- |
| Jackson Databind | 2.13.4 | direct | yes | Apache-2.0 | preserve license and Jackson notice |
| Jackson Core / Annotations | 2.13.4 | Jackson Databind | yes | Apache-2.0 | preserve license and Jackson notice |
| Apache POI / OOXML / Scratchpad / OOXML Schemas | 4.1.2 | direct | yes | Apache-2.0 | preserve POI notice and bundled third-party attributions |
| Apache XMLBeans | 3.1.0 | POI OOXML Schemas | yes | Apache-2.0 | preserve XMLBeans notice |
| Apache Commons Compress | 1.19 | POI OOXML | yes | Apache-2.0 | preserve notice, including LZMA SDK public-domain attribution |
| curvesapi | 1.06 | POI OOXML | yes | BSD-3-Clause | ship BSD license text |
| Apache Commons Codec | 1.13 | Apache POI | yes | Apache-2.0 | preserve Commons Codec notice |
| Apache Commons Collections | 4.4 | Apache POI | yes | Apache-2.0 | preserve Commons Collections notice |
| Apache Commons Math | 3.6.1 | Apache POI | yes | Apache-2.0 | preserve notice, including Orekit attribution |
| SparseBitSet | 1.2 | Apache POI | yes | Apache-2.0 | preserve license |
| Apache PDFBox / FontBox | 2.0.29 | direct / PDFBox | yes | Apache-2.0 | preserve PDFBox and FontBox notices and bundled data attributions |
| Apache Commons Logging | 1.2 | PDFBox / FontBox | yes | Apache-2.0 | preserve Commons Logging notice |

Jackson is used by MCP JSON-RPC, Provider codecs, and versioned local state.
Document dependencies are isolated in `clara-application`; none of their types
enter the workspace or model public APIs.

The dependency POMs also declare optional integrations which Clara does not
request or distribute: POI's Log4j 1.2.17 and optional Commons Logging, and
PDFBox's Bouncy Castle providers. A release dependency report must fail review if
one of these appears without an explicit security and license decision.

## Test and build dependencies

| Component | Version | Scope | Java 8 | License |
| --- | --- | --- | --- | --- |
| JUnit | 4.13.2 | test | yes | EPL-1.0 |
| Hamcrest Core | 1.3 | test, via JUnit | yes | BSD-3-Clause |
| Maven Compiler Plugin | 3.8.1 | build | yes | Apache-2.0 |
| Maven Surefire Plugin | 2.22.2 | build | yes | Apache-2.0 |
| Maven Enforcer Plugin | 3.0.0 | build | yes | Apache-2.0 |

Build and test tools are not bundled in the Clara runtime distribution. Their
licenses remain part of the source/build compliance record.

## Deferred candidates

These components are architectural extension points, not current dependencies.
They must not be added until version, Java 8 bytecode, transitive dependencies,
security posture, license, and NOTICE obligations are reviewed.

| Component | Version | Expected license | Current decision |
| --- | --- | --- | --- |
| picocli | TBD | Apache-2.0 | deferred; phase 7 uses the dependency-free CLI protocol core |
| Lanterna | TBD | LGPL-3.0 | license family accepted in architecture, but version and distribution mechanics are not frozen; `auto`/`tui` fall back to plain |
| RE2/J | TBD | BSD-3-Clause | deferred; `SAFE_REGEX` fails closed with `REGEX_UNSUPPORTED` |
| Milvus SDK | TBD | Apache-2.0 | deferred; backend-neutral adapter core is implemented |
| Object storage SDK | TBD | TBD | deferred until a Service-stage backend is selected |
| Audio/video metadata parser | TBD | TBD | deferred; probe SPI and dependency-free WAV probe are implemented |
| Media content-safety service | TBD | provider-specific | deferred; scanner SPI and fail-closed policy are implemented |

## Release checks

1. Run the full Maven reactor and archive its dependency tree from a writable,
   clean local repository.
2. Confirm Clara classes and every dependency's Java-8-loadable class entries
   have major version 52 or lower, then run tests on a real JDK 8. Report module
   descriptors and multi-release entries separately: Java 8 does not load
   `module-info.class` or `META-INF/versions/*` (Jackson 2.13.4 contains Java 9
   metadata/entries with major 53 while its application classes remain compatible).
3. Compare the resolved runtime closure with this inventory. Any addition or
   version drift requires updating this file and `NOTICE` before release.
4. Scan public APIs for `dev.langchain4j`, Provider SDK, parser, storage SDK, and
   transport implementation types.
5. Package the Apache-2.0 license, dependency license texts, and the upstream
   attributions summarized in the root `NOTICE`. Binary distributions must retain
   each dependency artifact's embedded `META-INF/LICENSE*` and `META-INF/NOTICE*`;
   shaded distributions must reproduce those files in a third-party license bundle.
