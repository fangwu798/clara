# clara-memory 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-memory-service-business-design.md`（已审核）、`conversation-context-call-record-business-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）

## 1. 目标

实现 core 定义的记忆契约，统一短时记忆（窗口）、压缩编排、语义记忆（向量存储 / 检索）与会话持久化。现有 memory 代码按新业务重写、不做兼容。

命名规范与包结构约束以 `clara-core-contract-technical-design.md` 为准：接口 / DTO / 枚举已在 core 定义，本模块只提供 `com.clara.memory.*` 下的实现类；DTO 若在本模块新增，遵循 Lombok 注解约定。

## 2. 模块依赖

- `clara-core`：实现 `IWindowMemory`、`ISummarizeCoordinator`、`ISemanticMemoryStore` 契约；`IEmbeddingClient` 由 clara-model 侧（或宿主）提供，memory 不实现嵌入；
- 压缩编排的模型调用走 clara-model，总结提示词走 core 的 `IPromptProvider`（由 clara-skill 实现）。

## 3. 文件结构（要创建的文件）

```
clara-memory/src/main/java/com/clara/memory/
├── window/
│   ├── DefaultWindowMemory.java           # 实现 IWindowMemory
│   └── WindowSupport.java                 # 窗口裁剪辅助（system 归一化、工具往返识别）
├── summarize/
│   └── DefaultSummarizeCoordinator.java   # 实现 ISummarizeCoordinator
├── semantic/
│   ├── local/
│   │   └── LocalSemanticMemoryStore.java  # 实现 ISemanticMemoryStore（本地后端）
│   ├── milvus/
│   │   ├── MilvusSemanticMemoryStore.java # 实现 ISemanticMemoryStore（Milvus 后端）
│   │   ├── MilvusBackend.java             # Milvus 后端抽象
│   │   ├── MilvusBackendFactory.java      # Milvus 后端工厂
│   │   ├── MilvusBackendQuery.java        # Milvus 查询封装
│   │   ├── MilvusCollectionSpec.java      # 集合规格
│   │   └── MilvusBackendException.java    # Milvus 后端异常
│   └── SemanticMemoryErrors.java          # 语义记忆领域错误
└── persistence/
    ├── local/
    │   └── LocalConversationStore.java    # 本地文件持久化
    ├── jdbc/
    │   └── JdbcConversationStore.java     # JDBC 持久化
    ├── InMemoryConversationStore.java     # 内存存储
    ├── BoundedCachingConversationStore.java # 有界缓存（LRU）
    ├── ConversationCodec.java             # 会话编解码
    └── ConversationStoreErrors.java       # 会话持久化领域错误
```

## 4. 实现类清单（每个类含作用 / 使用范围 / 为什么需要）

### 4.1 窗口记忆

**`DefaultWindowMemory`（`com.clara.memory.window`，实现 `IWindowMemory`）**
- 作用：以会话为单位维护短时历史消息，执行窗口限制与淘汰。
- 使用范围：对话编排发送请求前取窗口内历史；由本模块实现。
- 为什么需要：core 定义契约，这里落地窗口裁剪（条数 / 大小 / 百分比）与「完整工具往返整组淘汰」。

**`WindowSupport`（`com.clara.memory.window`）**
- 作用：提供 system 消息归一化、工具往返识别、淘汰索引计算等纯函数。
- 使用范围：`DefaultWindowMemory` 内部调用。
- 为什么需要：把裁剪公共逻辑抽成独立工具类，避免内部类型、便于复用与测试。

### 4.2 压缩编排

**`DefaultSummarizeCoordinator`（`com.clara.memory.summarize`，实现 `ISummarizeCoordinator`）**
- 作用：把超限被淘汰的消息组总结为一条 system 摘要。
- 使用范围：窗口超限、发送请求前触发。
- 为什么需要：落地压缩流程（模型调用 + 总结提示词 + 防递归），memory 只编排、不实现模型与提示词。

### 4.3 语义记忆

**`LocalSemanticMemoryStore`（`com.clara.memory.semantic.local`，实现 `ISemanticMemoryStore`）**
- 作用：本地语义记忆的写入、检索与按会话删除。
- 使用范围：单机部署的语义记忆后端。
- 为什么需要：提供无外部向量库依赖的本地实现，接口与 Milvus 一致。

**`MilvusSemanticMemoryStore`（`com.clara.memory.semantic.milvus`，实现 `ISemanticMemoryStore`）**
- 作用：Milvus 向量库的写入、检索与按会话删除。
- 使用范围：生产多实例部署的语义记忆后端。
- 为什么需要：对接 Milvus，提供持久化、可扩展的语义记忆。

**`MilvusBackend` / `MilvusBackendFactory` / `MilvusBackendQuery` / `MilvusCollectionSpec` / `MilvusBackendException`（`com.clara.memory.semantic.milvus`）**
- 作用：Milvus 连接、集合规格、查询构造与异常封装。
- 使用范围：`MilvusSemanticMemoryStore` 内部支撑。
- 为什么需要：把 Milvus SDK 交互隔离在独立类中，避免泄漏到语义记忆接口。

**`SemanticMemoryErrors`（`com.clara.memory.semantic`）**
- 作用：集中创建语义记忆领域错误。
- 使用范围：Local / Milvus 实现内部。
- 为什么需要：统一错误码与分类。

### 4.4 会话持久化

会话持久化是 memory 模块内部实现，不暴露为 core 契约；`IWindowMemory` 内部依赖它恢复 / 保存历史。

**`LocalConversationStore`（`com.clara.memory.persistence.local`）**
- 作用：会话历史 / 快照的本地文件持久化。
- 使用范围：单机单进程部署。
- 为什么需要：重启后恢复会话，采用排他锁保证单机一致性。

**`InMemoryConversationStore`（`com.clara.memory.persistence`）**
- 作用：会话历史的进程内存存储。
- 使用范围：测试或无需重启恢复的场景。
- 为什么需要：无持久化开销，进程重启即丢失。

**`JdbcConversationStore`（`com.clara.memory.persistence.jdbc`）**
- 作用：会话历史 / 快照的 JDBC 持久化。
- 使用范围：多实例共享部署。
- 为什么需要：提供跨实例一致的持久化实现。

**`BoundedCachingConversationStore`（`com.clara.memory.persistence`）**
- 作用：包装底层存储，按 LRU 缓存会话、限制常驻内存。
- 使用范围：需要降低重复磁盘读取的场景。
- 为什么需要：降低重复读取成本并限制内存占用。

**`ConversationCodec`（`com.clara.memory.persistence`）**
- 作用：会话快照与持久化格式之间的编解码。
- 使用范围：持久化读写时。
- 为什么需要：统一序列化 / 反序列化，隔离格式细节。

**`ConversationStoreErrors`（`com.clara.memory.persistence`）**
- 作用：集中创建会话持久化领域错误。
- 使用范围：持久化实现内部。
- 为什么需要：统一错误码与分类。

## 5. 关键实现细节

### 5.1 窗口记忆裁剪（`DefaultWindowMemory`）

**隔离维度**：历史按「供应商 + 模型」隔离（业务决策 D1）——同一会话内不同模型（或不同能力）各自持有一份独立历史，互不污染。

**三维度裁剪**：
- 条数：`windowMaxMessages`（默认不限制）；
- 大小：`windowMaxSizeBytes`（历史总字节数上限）；
- 百分比：`windowMaxTokensPercent`（默认 80%）× `contextWindowTokens`（平台上下文窗口 token 上限，作为计算基数），历史 token 数经 `ITokenCountEstimator` 估算后与基数比较；
- 组合规则：只设一种维度时该维度单独生效；多种维度同时设置时取 AND（更严，任一维度超限即触发裁剪）。

**淘汰策略**：
- 以「完整工具往返」为一组整体淘汰：从最旧消息开始，淘汰一条 assistant 携带 `tool_calls` 的消息时，连同其后连续的 tool 回执一并淘汰，不留下孤立工具结果；
- system 指令永居首位、不被淘汰；
- 历史不保留 reasoning（仅正文，业务决策 D6），reasoning 只在调用记录里留。

**读写语义**：`window(policy, estimator)` 只读裁剪、不改变内部存储；`append` 追加、`clear` 清空；本地持久化到 `.clara/state/conversations`，启动不加载全量、按需读取指定会话。

### 5.2 压缩编排（`DefaultSummarizeCoordinator`）

**触发时机**：发送请求之前，历史命中窗口上限时。

**流程**：
1. 取出最旧的连续消息组（整组）；
2. 取 `IPromptProvider.summarizePrompt()` 总结提示词；
3. 用当前上下文使用的模型（经 clara-model）生成一条摘要；
4. 摘要作为 system 指令的一部分回填，替代被淘汰部分。

**约束**：压缩是一次性独立任务、不走 RAG 递归流程；可异步并行执行（注意线程安全）；防递归——产生摘要的这次请求不进入历史、不再次触发压缩、不产生新的调用记录。

### 5.3 语义记忆（Local / Milvus）

**向量生成**：向量由 `IEmbeddingClient`（云端嵌入 API）生成，可配置 DeepSeek / 火山方舟 / Kimi 等平台；memory 只存储与检索。嵌入模型需全程一致（入库与检索同模型、同维度），更换嵌入模型需重建索引。

**写入（`upsert`）**：记忆条目含正文 + 向量 + 会话关联 + 元数据；语义来源固定三种（`MemorySourceTypeEnum`）：`PROMPT`、`TOOL_DESCRIPTION`、`CONVERSATION`。

**检索（`search`）**：发送请求前用当前输入向量检索，范围限定当前会话（按会话 ID 过滤）；召回相似度默认 70%、topK 默认 3（均可在 control 节点配置）；命中知识作为补充上下文注入。

**删除**：`deleteByConversation` 按会话删除该会话全部 CONVERSATION 来源记忆条目（不影响全局技能描述）；`deleteById` 按条目标识删除单个条目（支撑技能描述的热加载移除）。

**后端差异**：`LocalSemanticMemoryStore` 采用本地向量检索（暴力扫描 + 余弦相似度）；`MilvusSemanticMemoryStore` 通过 `MilvusBackend` / `MilvusCollectionSpec` 对接 Milvus 集合。

### 5.4 会话持久化

会话持久化是 memory 模块内部实现（不暴露为 core 契约），`IWindowMemory` 内部依赖它恢复 / 保存历史。由于历史按「供应商 + 模型」隔离（业务决策 D1），持久化以「会话历史单元」为单位，键为 `conversationId + 供应商 + 模型`。核心约定：`loadSnapshot` 返回快照（版本 + 消息列表）、`append(key, messages, expectedVersion)` 乐观并发追加、`clear` 清空。

**`LocalConversationStore`（本地文件，默认）**：
- 目录 `.clara/state/conversations`，会话文件命名为 `sha256(conversationId + 供应商 + 模型).bin`；
- 目录级排他锁（`store.lock`，`FileChannel.tryLock`），同一目录只允许一个实例持有、不支持多进程共享；
- 乐观并发：`append` 校验 `expectedVersion`，不匹配抛版本冲突，要求调用方重新加载；
- 原子写入：写临时文件 → `force(true)` → `ATOMIC_MOVE` 替换，避免半写文件；
- 单会话文件大小上限默认 128MB，超限报错；
- 构造只创建目录并取锁、不预加载历史；重启后按会话 ID 读取对应文件恢复。

**`InMemoryConversationStore`（内存）**：
- 用 `ConcurrentHashMap` 保存会话快照；
- 无容量上限、无持久化、进程重启即丢失，用于测试或短生命周期任务；
- 同样校验 `expectedVersion` 保证乐观并发。

**`JdbcConversationStore`（JDBC）**：
- 通过 JDBC 持久化会话快照，用于多实例共享部署，保证跨实例一致。

**`BoundedCachingConversationStore`（LRU 缓存包装）**：
- 包装底层存储，提供按需加载 + 容量受限的进程内 LRU 缓存；
- 两个容量限制：最大缓存会话数、最大缓存消息数；用 `LinkedHashMap`（accessOrder=true）实现 LRU 淘汰；
- 首次读取某会话才访问底层，后续读取命中缓存直接返回；`append` / `clear` 先写底层、成功后使对应缓存失效；
- 单会话消息数超过缓存上限时仍返回给当前调用方，但不进入缓存。
