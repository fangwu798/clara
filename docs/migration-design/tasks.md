# Clara migration tasks

Source baseline: `E:\workspace_self\XX\langchain4j`

Architecture baseline: `docs/architecture/java8-ai-capability-migration.md`

Status values: `DONE`, `IN_PROGRESS`, `BLOCKED`, `TODO`.

| ID | Phase | Deliverable | Depends on | Status |
| --- | --- | --- | --- | --- |
| MIG-000 | 0 | Freeze module/package names and Java 8 build baseline | - | DONE |
| MIG-001 | 0 | Record Java 8 dependency and license matrix | MIG-000 | DONE |
| MIG-002 | 0 | Freeze provider model IDs, regions and API profiles | MIG-000 | DONE |
| MIG-003 | 0 | Freeze local storage limits, retention and security policy | MIG-000 | DONE |
| MIG-010 | 1 | Migrate context, cancellation and stable errors | MIG-000 | DONE |
| MIG-011 | 1 | Migrate chat, model parameter and tool contracts | MIG-010 | DONE |
| MIG-012 | 1 | Implement model descriptors, registry and capability guard | MIG-011 | DONE |
| MIG-013 | 1 | Implement deterministic parameter overlay | MIG-011 | DONE |
| MIG-014 | 1 | Implement five text providers and contract tests | MIG-002,MIG-012 | DONE |
| MIG-020 | 2 | Implement media assets, stores and job state machine | MIG-010,MIG-003 | DONE |
| MIG-021 | 2 | Implement image, audio and video providers | MIG-002,MIG-020 | DONE |
| MIG-030 | 3 | Implement message/token windows and conversation stores | MIG-011 | DONE |
| MIG-031 | 3 | Implement local semantic memory with WAL/snapshots | MIG-030,MIG-003 | DONE |
| MIG-032 | 3 | Implement optional Milvus semantic memory | MIG-031 | DONE |
| MIG-040 | 4 | Implement MCP protocol, registry, discovery and catalog | MIG-010 | DONE |
| MIG-041 | 4 | Implement stdio and Streamable HTTP transports | MIG-040 | DONE |
| MIG-050 | 5 | Implement workflow nodes, scope and checkpoints | MIG-010 | DONE |
| MIG-051 | 5 | Implement task DAG, budgets, ledger and replanning | MIG-050 | DONE |
| MIG-052 | 5 | Implement feedback and resume coordination | MIG-050 | DONE |
| MIG-060 | 6 | Implement versioned skill registry and executor | MIG-050,MIG-051 | DONE |
| MIG-061 | 6 | Implement GeneralWorkspaceSkill | MIG-060,MIG-070 | DONE |
| MIG-070 | 7 | Implement workspace policy and document parsers | MIG-010 | DONE |
| MIG-071 | 7 | Implement search, ChangeSet apply and recovery | MIG-070 | DONE |
| MIG-072 | 7 | Implement CLI modes and REST/SSE adapters | MIG-052,MIG-061,MIG-071 | DONE |
| MIG-080 | all | Add unit, contract, security and Java 8 tests | all | DONE |

## Current iteration acceptance

- All eight modules build in one Maven reactor.
- Produced class files use Java 8 bytecode (major version 52).
- Core APIs expose no provider SDK types.
- Model lookup requires an explicit provider and alias.
- Capability checks use stable Clara error codes.
- Parameter overlays replace only explicitly provided values.

## Deferred live verification

All migration coding tasks are complete. Live Provider and optional Milvus-server
checks are deployment activation work, not acceptance conditions for this offline
migration iteration. The following history is retained for future activation:

- MIG-014: the Provider-neutral and mock-contract implementation is complete to the
  frozen local boundary. Live synchronous, streaming and tool-call probes pass for
  DeepSeek and Alibaba, including an explicit SSE `[DONE]`; Alibaba Embedding also
  passes with the frozen 1024-vector dimension. Volcengine is not known to reject
  every model: `doubao-seed-2-1-pro-260628`, `deepseek-v4-flash-260425`, and the
  user-selected `doubao-seed-2-0-code-preview-260215` return HTTP 403
  `AccountOverdueError`, while `doubao-seed-1-8-251228` and
  `doubao-1-5-pro-32k-250115` return HTTP 404
  `InvalidEndpointOrModel.NotFound`. Clear the account balance first and retest
  an account-visible model; provision or authorize an endpoint before retrying
  either 404 model. The supplied OpenAI-compatible gateway exposes its model
  directory, but both `/v1/chat/completions` and `/v1/responses` return HTTP 403
  `bad_response_status_code / openai_error`, so its upstream account or gateway
  authorization must be repaired before live contracts can run. Anthropic live
  verification is intentionally excluded.
- MIG-021: typed multimodal contracts, capability guards and concrete offline
  adapters are complete. OpenAI implements image generation/editing, transcription
  and speech synthesis; Volcengine implements image generation/editing and durable
  asynchronous video jobs; Alibaba implements native asynchronous image/video jobs,
  transcription and speech synthesis. Provider inputs and remote outputs cross the
  Clara-managed asset bridge with bounded bytes, HTTPS and fail-closed publication
  rules. Live media calls, including Volcengine image-to-video, are deferred.
- MIG-080: the local reactor, unit/mock-contract/security checks and Java 8 build
  checks pass. Live Provider checks and Milvus server verification are deferred and
  excluded from offline migration acceptance.

## In-progress boundaries

- MIG-012 descriptors now keep capability grants separate from immutable model
  catalog metadata. Metadata covers input/output modalities, token limits,
  supported parameters, execution modes, data-residency labels, pricing and
  lifecycle timestamps, discovery revision, and provenance. Catalog discovery
  cannot grant capabilities; provenance priorities preserve audited/probed/tested
  decisions over lower-confidence provider catalog data. The original descriptor
  constructor remains source-compatible and supplies explicit unknown metadata.
- MIG-014 includes the shared Java 8 Provider HTTP transport contract and its
  `HttpURLConnection` implementation. It enforces exact-origin allowlists, HTTPS
  by default, explicit request/response limits, bounded connect/read deadlines,
  cancellation checks, header validation, and disabled redirects. The synchronous
  OpenAI-compatible Chat mapper/adapter now covers the
  explicitly configured OPENAI, DEEPSEEK, VOLCENGINE, and ALIBABA profiles. It
  maps text, function tools, common parameters, usage, finish reasons, and tool
  calls; rejects model override, unsupported media/topK, cross-provider options,
  malformed responses, and missing credentials without exposing response bodies.
  The independent synchronous Anthropic Messages adapter maps leading system
  blocks, user/assistant text, tool use/results, tool schemas, common Anthropic
  parameters, usage and stop reasons. It pins the endpoint/model/API profile and
  `anthropic-version`, resolves `x-api-key` through `secretRef`, and rejects
  mid-conversation system messages, non-object tool inputs, unsupported media or
  parameters, cross-provider options, malformed responses, and unsafe HTTP error
  bodies. DeepSeek prefix completion is isolated in a typed Provider option that
  maps `prefix` and optional `reasoning_content` only onto a final assistant
  message; unknown same-provider option implementations are rejected instead of
  silently ignored. Shared streaming groundwork includes a
  Java 8 incremental SSE parser with strict UTF-8 decoding, CR/LF/CRLF framing,
  standard field handling, cancellation/deadline checks, listener-directed stop,
  and explicit line/event/count/total-byte limits. The blocking Java 8 SSE HTTP
  transport applies the same endpoint, redirect, request/response, timeout,
  cancellation, and header policies as synchronous HTTP; it validates successful
  `text/event-stream` responses and bounds non-success bodies before returning
  status to the Provider mapper. The native Anthropic streaming adapter validates
  the named Messages event
  lifecycle, incrementally assembles text and tool JSON blocks, maps usage and
  stop reasons, handles safe typed Provider errors, and emits the same structured
  terminal contract through an externally managed executor. Unsupported thinking
  blocks remain explicit protocol failures until that capability is represented
  in the frozen model profile. The OpenAI-compatible stream accumulator validates
  the single-choice chunk protocol, incrementally aggregates text, DeepSeek-style
  reasoning, fragmented parallel tool calls, usage, finish reason, and stable
  response metadata, and requires an explicit `[DONE]` terminator. Its explicit
  tool-count and accumulated-character limits prevent unbounded response state.
  `OpenAiCompatibleStreamingChatModel` runs on an injected bounded executor,
  exposes cancellable handles, emits ordered structured text/reasoning/tool/error
  events, maps HTTP failures without response-body disclosure, and attaches the
  fully aggregated `ChatResponse` to the terminal completion event.
  Cross-mode contract tests require equivalent synchronous and streaming final
  messages, tool calls, usage, finish reasons, and Provider metadata for both the
  OpenAI-compatible/DeepSeek and native Anthropic protocols.
  DeepSeek `GET /models` discovery produces a bounded, immutable availability
  snapshot with a deterministic model order and explicit discovery timestamp;
  it never infers or mutates registered model capabilities from catalog presence.
  The compatible Embedding
  adapter is deliberately limited to explicitly configured OPENAI and ALIBABA
  profiles; it enforces batch/text limits, a frozen vector dimension, finite
  coordinates, and a complete unique response-index permutation before exposing
  vectors to semantic memory. DeepSeek and Volcengine Embedding remain disabled
  until their concrete account/profile capabilities are frozen.
- MIG-020 includes the provider-neutral media API, shared explicit validation
  policy, `FileSystemMediaStore`, backend-neutral `ObjectMediaStore` SPI, and the
  optimistic-locking `MediaJob` state machine. Reopenable validation inputs,
  metadata-probe and content-safety SPIs, plus explicit fail-closed policy switches
  are implemented. The durable scheduler contract covers tenant idempotency keys,
  unknown submission outcomes, due-job polling, bounded exponential backoff,
  deadlines, cancellation, verified callbacks, and output persistence before
  success. `LocalMediaJobStore` persists a versioned, workspace-bound snapshot
  under `.clara/state/media-jobs` with SHA-256 integrity, an exclusive writer lock,
  forced temporary-file writes, atomic replacement, explicit job/file limits, and
  restart recovery of CAS versions, due times, and tenant idempotency indexes.
  The provider-neutral callback boundary verifies HMAC-SHA256 over canonical job
  identity, expected version, key ID, timestamp, nonce, and raw payload before
  parsing; explicit clock, payload/header, and replay-cache limits reject expired,
  tampered, replayed, or malformed callbacks with stable errors. Replay reservation
  is an explicit SPI with a bounded local implementation; provider-specific callback
  payload parsers, secret backends, and multi-instance replay stores remain adapter
  responsibilities.
  The Windows single-host policy freezes quotas, retention, encryption, backup,
  callback and credential requirements. Concrete object-store and non-WAV
  audio/video bindings are explicitly disabled for the first deployment. External
  or Provider-generated media fails closed until a safety scanner is configured.
- MIG-032 includes the provider-neutral Milvus adapter. The concrete SDK binding
  and live-server integration are explicitly deferred and excluded from current
  acceptance. Collection identity validation includes the required
  tenant, provenance, content-hash, embedding-version, metadata, and vector fields
  in addition to model/revision/dimension/distance. Search results are bounded by
  the requested candidate limit before local defense-in-depth filtering, and a
  backend is closed when collection initialization fails.
- MIG-021 includes typed image generation/editing, audio transcription, speech
  synthesis, and asynchronous video model contracts plus request-aware capability
  guards. It also includes shared bounded media HTTP/multipart/JSON support,
  OpenAI image/audio adapters, Volcengine image/video adapters, Alibaba native
  image/video/audio adapters, and the application asset bridge used to publish
  inputs and ingest Provider outputs into Clara-managed storage. Async Provider
  operations use the durable media scheduler contract for polling, cancellation,
  deadlines, idempotency and result persistence.

## Verification record

- 2026-09-07: the complete nine-project reactor passed with 64 suites and 234 tests,
  with zero failures, errors or skips. This run is the offline acceptance evidence
  for MIG-014, MIG-021 and MIG-080; it made no live Provider or Milvus calls.

- 2026-09-04: `mvn test` passed for the parent, BOM and all eight implementation
  modules. Surefire reported 62 suites and 227 tests, with zero failures, errors or
  skips.
- All 473 files under reactor `target/classes` directories use class-file major
  version 52 or lower.
- A real JDK 8u202 clean reactor initially exposed a JDK 9+ covariant
  `ByteBuffer.flip()` linkage in local semantic memory. The call now targets the
  Java 8 `Buffer` signature. A clean JDK 8 build passes, and a clean JDK 17
  `release=8` build followed by execution of the same artifacts on JDK 8 also
  passes all 227 tests.
- Java sources and POMs contain no `dev.langchain4j`, Milvus SDK or selected
  Provider SDK package references; Provider and Milvus boundaries remain Clara
  types.
- Authenticated read-only model-directory calls succeeded for the supplied
  DeepSeek, Volcengine, Alibaba and OpenAI-compatible endpoints. No credential or
  model-directory response payload was persisted.
- Official and account-visible IDs were frozen in the architecture model catalog.
  Synchronous, streaming and tool-call probes passed for DeepSeek and Alibaba;
  Alibaba Embedding returned one 1024-dimensional vector. Three Volcengine model
  probes, including `doubao-seed-2-0-code-preview-260215`, returned HTTP 403
  `AccountOverdueError`; two other catalog candidates returned HTTP 404
  `InvalidEndpointOrModel.NotFound`. The OpenAI-compatible gateway returned HTTP
  403 from both Chat Completions and Responses despite allowing model-directory
  access. Anthropic is display-only and was not invoked. Credentials and response
  bodies were not persisted.
- Volcengine image-to-video remains documented but is excluded from the current
  live contract and acceptance scope.
- Milvus remains documented with an SDK-neutral adapter and offline contracts, but
  live SDK/server verification is excluded from the current acceptance scope.
- MIG-003 and MIG-020 closed after adding and testing the typed Windows single-host
  storage policy and explicitly deferring unsupported production bindings.
- Final task state: 25 `DONE`, 0 `BLOCKED`, 0 `IN_PROGRESS`, 0 `TODO`.
