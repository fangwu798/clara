package com.clara.exception;

/**
 * 集中定义 Clara 的稳定错误码。
 *
 * <p>约定：
 * <ul>
 *   <li>常量名与字符串值一致，且作为 {@link ClaraError} 的错误码对外暴露，属于稳定契约——只能新增，
 *       不要改名或改值，否则会破坏按错误码分支的上层逻辑与日志比对；</li>
 *   <li>命名前缀表示归属模块：{@code MODEL_} 模型调用、{@code MCP_} MCP 协议与注册、{@code CONVERSATION_}
 *       会话与存储、{@code WORKFLOW_} /
 *       {@code PLAN_} / {@code BUDGET_} / {@code EXECUTION_} / {@code INTERACTION_} 编排与执行、
 *       {@code SKILL_} 技能、{@code WORKSPACE_} / {@code DOCUMENT_} / {@code CHANGESET_} 工作区与文本编辑、
 *       {@code SEMANTIC_MEMORY_} 语义记忆、{@code MEDIA_} 媒体资产与生成任务；</li>
 *   <li>具体归属的错误类别（鉴权、限流、资源限制等）与是否可重试由构造异常的一方决定，不在本类中表达。</li>
 * </ul>
 */
public final class ErrorCodes {
    /**
     * 调用已被取消：调用令牌被触发，任务在完成前主动终止。
     */
    public static final String INVOCATION_CANCELLED = "INVOCATION_CANCELLED";
    /**
     * 平台或当前实现不支持请求的能力（如不支持的媒体内容、采样参数）。
     */
    public static final String CAPABILITY_NOT_SUPPORTED = "CAPABILITY_NOT_SUPPORTED";
    /**
     * 模型已注册：同一标识的模型被重复注册。
     */
    public static final String MODEL_ALREADY_REGISTERED = "MODEL_ALREADY_REGISTERED";
    /**
     * 模型未注册：请求的模型不在注册表中，无法调用。
     */
    public static final String MODEL_NOT_REGISTERED = "MODEL_NOT_REGISTERED";
    /**
     * 模型端点被拒绝：目标地址不在允许清单内，或不是安全的绝对 HTTP(S) 地址。
     */
    public static final String MODEL_ENDPOINT_DENIED = "MODEL_ENDPOINT_DENIED";
    /**
     * 模型请求体超出配置上限，请求未发出。
     */
    public static final String MODEL_REQUEST_TOO_LARGE = "MODEL_REQUEST_TOO_LARGE";
    /**
     * 模型响应体或流式事件超出配置上限，读取被中止。
     */
    public static final String MODEL_RESPONSE_TOO_LARGE = "MODEL_RESPONSE_TOO_LARGE";
    /**
     * 模型传输层 IO 失败：连接中断、写入或读取失败等，通常可重试。
     */
    public static final String MODEL_TRANSPORT_IO_ERROR = "MODEL_TRANSPORT_IO_ERROR";
    /**
     * 模型请求超时：连接、读取或流式读取超过截止时间。
     */
    public static final String MODEL_REQUEST_TIMEOUT = "MODEL_REQUEST_TIMEOUT";
    /**
     * 模型凭据不可用：按租户解析后没有拿到密钥，属于配置问题。
     */
    public static final String MODEL_CREDENTIAL_UNAVAILABLE = "MODEL_CREDENTIAL_UNAVAILABLE";
    /**
     * 平台调用失败：模型服务返回非 2xx（如 401/403/429/5xx）。
     */
    public static final String MODEL_PROVIDER_FAILED = "MODEL_PROVIDER_FAILED";
    /**
     * 模型响应不符合协议：结构缺失、字段类型不符或跨块内容矛盾。
     */
    public static final String MODEL_PROTOCOL_ERROR = "MODEL_PROTOCOL_ERROR";
    /**
     * 入参非法：调用方传入的参数不满足接口契约。
     */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /**
     * 会话版本冲突：基于旧版本写入，期间已被其他请求修改。
     */
    public static final String CONVERSATION_VERSION_CONFLICT = "CONVERSATION_VERSION_CONFLICT";
    /**
     * 会话存储被占用：未能取得存储锁，稍后可重试。
     */
    public static final String CONVERSATION_STORE_LOCKED = "CONVERSATION_STORE_LOCKED";
    /**
     * 会话存储损坏：文件校验失败或内容无法解析。
     */
    public static final String CONVERSATION_STORE_CORRUPTED = "CONVERSATION_STORE_CORRUPTED";
    /**
     * 会话存储读写失败：磁盘 IO 异常。
     */
    public static final String CONVERSATION_STORE_IO_ERROR = "CONVERSATION_STORE_IO_ERROR";
    /**
     * MCP 服务未注册：请求的服务不在注册表中。
     */
    public static final String MCP_SERVER_NOT_REGISTERED = "MCP_SERVER_NOT_REGISTERED";
    /**
     * MCP 服务端不存在：按租户与服务端标识定位不到服务端。
     */
    public static final String MCP_SERVER_NOT_FOUND = "MCP_SERVER_NOT_FOUND";
    /**
     * MCP 服务已注册：同一服务被重复注册。
     */
    public static final String MCP_SERVER_ALREADY_REGISTERED = "MCP_SERVER_ALREADY_REGISTERED";
    /**
     * MCP 协议版本不兼容：服务端协商结果与客户端支持范围无交集。
     */
    public static final String MCP_PROTOCOL_INCOMPATIBLE = "MCP_PROTOCOL_INCOMPATIBLE";
    /**
     * MCP 服务身份已变更：服务端上报的名称或版本与已批准的基线不一致，需要重新审批。
     */
    public static final String MCP_SERVER_IDENTITY_CHANGED = "MCP_SERVER_IDENTITY_CHANGED";
    /**
     * MCP 分页轮次超限：翻页次数超过配置上限，判定为异常。
     */
    public static final String MCP_PAGINATION_LIMIT_EXCEEDED = "MCP_PAGINATION_LIMIT_EXCEEDED";
    /**
     * MCP 分页游标重复：同一游标再次出现，说明分页出现循环。
     */
    public static final String MCP_PAGINATION_CURSOR_REPEATED = "MCP_PAGINATION_CURSOR_REPEATED";
    /**
     * MCP 能力不存在：请求的工具或资源在服务端未声明。
     */
    public static final String MCP_CAPABILITY_NOT_FOUND = "MCP_CAPABILITY_NOT_FOUND";
    /**
     * MCP 能力已变更：服务端声明的能力与本地缓存的基线不一致。
     */
    public static final String MCP_CAPABILITY_CHANGED = "MCP_CAPABILITY_CHANGED";
    /**
     * MCP Schema 不一致：目录中的能力 Schema 哈希与调用时期望的哈希不匹配。
     */
    public static final String MCP_SCHEMA_MISMATCH = "MCP_SCHEMA_MISMATCH";
    /**
     * MCP 能力发现失败：列举工具或资源时服务端返回错误或超时。
     */
    public static final String MCP_DISCOVERY_FAILED = "MCP_DISCOVERY_FAILED";
    /**
     * MCP 服务不可用：服务当前不处于可调用状态。
     */
    public static final String MCP_SERVER_NOT_ACTIVE = "MCP_SERVER_NOT_ACTIVE";
    /**
     * MCP 传输已关闭：连接被关闭后仍尝试读写。
     */
    public static final String MCP_TRANSPORT_CLOSED = "MCP_TRANSPORT_CLOSED";
    /**
     * MCP 传输 IO 失败：与 MCP 服务通信时读写异常。
     */
    public static final String MCP_TRANSPORT_IO_ERROR = "MCP_TRANSPORT_IO_ERROR";
    /**
     * MCP 请求超时：等待响应超过截止时间。
     */
    public static final String MCP_REQUEST_TIMEOUT = "MCP_REQUEST_TIMEOUT";
    /**
     * MCP 请求 id 重复：同一请求标识被重复使用或重复响应。
     */
    public static final String MCP_DUPLICATE_REQUEST_ID = "MCP_DUPLICATE_REQUEST_ID";
    /**
     * MCP 报文过大：单条消息超出允许体积。
     */
    public static final String MCP_MESSAGE_TOO_LARGE = "MCP_MESSAGE_TOO_LARGE";
    /**
     * MCP 报文非法：不符合协议要求的 JSON 结构或字段类型。
     */
    public static final String MCP_INVALID_MESSAGE = "MCP_INVALID_MESSAGE";
    /**
     * 工作流定义非法：结构、字段或引用不满足契约。
     */
    public static final String WORKFLOW_DEFINITION_INVALID = "WORKFLOW_DEFINITION_INVALID";
    /**
     * 工作流版本冲突：基于旧版本更新定义或实例。
     */
    public static final String WORKFLOW_VERSION_CONFLICT = "WORKFLOW_VERSION_CONFLICT";
    /**
     * 工作流实例不存在：指定的实例标识查无对应记录。
     */
    public static final String WORKFLOW_INSTANCE_NOT_FOUND = "WORKFLOW_INSTANCE_NOT_FOUND";
    /**
     * 工作流状态非法：当前状态下不允许执行该操作。
     */
    public static final String WORKFLOW_INVALID_STATE = "WORKFLOW_INVALID_STATE";
    /**
     * 工作流输出冲突：本次产出与已记录的输出重复或不一致。
     */
    public static final String WORKFLOW_OUTPUT_CONFLICT = "WORKFLOW_OUTPUT_CONFLICT";
    /**
     * 计划非法：结构、步骤或依赖不满足契约。
     */
    public static final String PLAN_INVALID = "PLAN_INVALID";
    /**
     * 计划规模超限：步骤数、深度或并行度超过允许上限。
     */
    public static final String PLAN_LIMIT_EXCEEDED = "PLAN_LIMIT_EXCEEDED";
    /**
     * 计划能力被拒：计划要求的能力不在允许范围内。
     */
    public static final String PLAN_CAPABILITY_DENIED = "PLAN_CAPABILITY_DENIED";
    /**
     * 重新规划请求非法：当前状态或参数不允许重规划。
     */
    public static final String PLAN_RE_PLAN_INVALID = "PLAN_REPLAN_INVALID";
    /**
     * 预算已耗尽：本次调用或任务超出可用预算额度。
     */
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /**
     * 预算预留非法：预留金额、标识或状态不符合账本规则。
     */
    public static final String BUDGET_RESERVATION_INVALID = "BUDGET_RESERVATION_INVALID";
    /**
     * 执行账本冲突：并发写入或重复记录导致账本状态不一致。
     */
    public static final String EXECUTION_LEDGER_CONFLICT = "EXECUTION_LEDGER_CONFLICT";
    /**
     * 执行副作用未知：操作可能已生效但结果无法确认，需要按未知状态处理，不可直接重试。
     */
    public static final String EXECUTION_SIDE_EFFECT_UNKNOWN = "EXECUTION_SIDE_EFFECT_UNKNOWN";
    /**
     * 交互请求不存在：待确认的交互记录查无对应条目。
     */
    public static final String INTERACTION_NOT_FOUND = "INTERACTION_NOT_FOUND";
    /**
     * 交互冲突：交互已被处理或状态已变化，本次操作不再适用。
     */
    public static final String INTERACTION_CONFLICT = "INTERACTION_CONFLICT";
    /**
     * 交互已过期：超过有效窗口，不能再作答。
     */
    public static final String INTERACTION_EXPIRED = "INTERACTION_EXPIRED";
    /**
     * 交互回答非法：答案格式、选项或长度不符合该交互的要求。
     */
    public static final String INTERACTION_INVALID_ANSWER = "INTERACTION_INVALID_ANSWER";
    /**
     * 交互次数超限：同一任务的交互轮次超过配置上限。
     */
    public static final String INTERACTION_LIMIT_EXCEEDED = "INTERACTION_LIMIT_EXCEEDED";
    /**
     * 交互访问被拒：当前调用方无权处理该交互请求。
     */
    public static final String INTERACTION_ACCESS_DENIED = "INTERACTION_ACCESS_DENIED";
    /**
     * 技能不存在：指定标识（含版本）的技能未注册。
     */
    public static final String SKILL_NOT_FOUND = "SKILL_NOT_FOUND";
    /**
     * 技能版本已存在：同一技能的同版本不允许重复创建。
     */
    public static final String SKILL_VERSION_EXISTS = "SKILL_VERSION_EXISTS";
    /**
     * 技能生命周期非法：当前生命周期状态不允许该操作，例如未评审就发布。
     */
    public static final String SKILL_INVALID_LIFECYCLE = "SKILL_INVALID_LIFECYCLE";
    /**
     * 技能制品被拒：制品校验未通过（内容安全或完整性检查失败）。
     */
    public static final String SKILL_ARTIFACT_REJECTED = "SKILL_ARTIFACT_REJECTED";
    /**
     * 技能依赖非法：依赖缺失、未发布或版本约束无法满足。
     */
    public static final String SKILL_DEPENDENCY_INVALID = "SKILL_DEPENDENCY_INVALID";
    /**
     * 技能权限被拒：调用方无权使用或修改该技能。
     */
    public static final String SKILL_PERMISSION_DENIED = "SKILL_PERMISSION_DENIED";
    /**
     * 技能不可用：技能存在但当前无法执行。
     */
    public static final String SKILL_UNAVAILABLE = "SKILL_UNAVAILABLE";
    /**
     * 技能 schema 非法：入参或出参定义不符合契约。
     */
    public static final String SKILL_SCHEMA_INVALID = "SKILL_SCHEMA_INVALID";
    /**
     * 技能处理入口不存在：声明的能力找不到对应实现。
     */
    public static final String SKILL_HANDLER_NOT_FOUND = "SKILL_HANDLER_NOT_FOUND";
    /**
     * 技能执行超时：超过该技能允许的执行时间。
     */
    public static final String SKILL_TIMEOUT = "SKILL_TIMEOUT";
    /**
     * 工作区访问被拒：目标路径或资源不在允许范围内。
     */
    public static final String WORKSPACE_ACCESS_DENIED = "WORKSPACE_ACCESS_DENIED";
    /**
     * 工作区资源超限：文件数、单文件或累计体积超过配置上限。
     */
    public static final String WORKSPACE_RESOURCE_LIMIT = "WORKSPACE_RESOURCE_LIMIT";
    /**
     * 工作区文件不存在：指定路径在工作区内查无对应文件。
     */
    public static final String WORKSPACE_FILE_NOT_FOUND = "WORKSPACE_FILE_NOT_FOUND";
    /**
     * 文档格式不支持：后缀或内容类型不在允许解析的范围内。
     */
    public static final String DOCUMENT_FORMAT_UNSUPPORTED = "DOCUMENT_FORMAT_UNSUPPORTED";
    /**
     * 文档解析失败：内容损坏或结构无法识别。
     */
    public static final String DOCUMENT_PARSE_FAILED = "DOCUMENT_PARSE_FAILED";
    /**
     * 文档已加密：受密码保护，无法直接解析。
     */
    public static final String DOCUMENT_ENCRYPTED = "DOCUMENT_ENCRYPTED";
    /**
     * 工作区冲突：文件在读取后被其他操作修改，基于旧版本写入被拒绝。
     */
    public static final String WORKSPACE_CONFLICT = "WORKSPACE_CONFLICT";
    /**
     * 正则模式不支持：当前实现只支持字面量匹配，未提供正则能力。
     */
    public static final String REGEX_UNSUPPORTED = "REGEX_UNSUPPORTED";
    /**
     * 拒绝零长度编辑：匹配区间为空（起点等于终点），不构成有效修改。
     */
    public static final String ZERO_LENGTH_EDIT_DENIED = "ZERO_LENGTH_EDIT_DENIED";
    /**
     * 变更集审批无效：缺少有效审批，或审批与待应用的变更不匹配。
     */
    public static final String CHANGESET_APPROVAL_INVALID = "CHANGESET_APPROVAL_INVALID";
    /**
     * 变更集应用失败：写入过程出错，可能已部分应用，需要按回滚或未知副作用处理。
     */
    public static final String CHANGESET_APPLY_FAILED = "CHANGESET_APPLY_FAILED";
    /**
     * 语义记忆被占用：未能取得存储锁，稍后可重试。
     */
    public static final String SEMANTIC_MEMORY_LOCKED = "SEMANTIC_MEMORY_LOCKED";
    /**
     * 语义记忆损坏：快照或校验信息无法解析、与预期不一致。
     */
    public static final String SEMANTIC_MEMORY_CORRUPTED = "SEMANTIC_MEMORY_CORRUPTED";
    /**
     * 语义记忆配置不匹配：当前配置（后端、维度等）与既有存储数据不一致，不能继续使用。
     */
    public static final String SEMANTIC_MEMORY_CONFIG_MISMATCH = "SEMANTIC_MEMORY_CONFIG_MISMATCH";
    /**
     * 语义记忆资源超限：记录数、向量规模或查询规模超过配置上限。
     */
    public static final String SEMANTIC_MEMORY_RESOURCE_LIMIT = "SEMANTIC_MEMORY_RESOURCE_LIMIT";
    /**
     * 语义记忆后端失败：向量库或持久化后端返回错误。
     */
    public static final String SEMANTIC_MEMORY_BACKEND_FAILED = "SEMANTIC_MEMORY_BACKEND_FAILED";
    /**
     * 媒体资产不存在：指定资产标识查无对应记录。
     */
    public static final String MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND";
    /**
     * 媒体校验失败：格式、大小、时长或元信息不符合策略要求。
     */
    public static final String MEDIA_VALIDATION_FAILED = "MEDIA_VALIDATION_FAILED";
    /**
     * 媒体资源超限：资产体积、数量或解码规模超过配置上限。
     */
    public static final String MEDIA_RESOURCE_LIMIT = "MEDIA_RESOURCE_LIMIT";
    /**
     * 媒体存储读写失败：磁盘或对象存储 IO 异常。
     */
    public static final String MEDIA_STORE_IO_ERROR = "MEDIA_STORE_IO_ERROR";
    /**
     * 媒体存储被占用：未能取得存储锁，稍后可重试。
     */
    public static final String MEDIA_STORE_LOCKED = "MEDIA_STORE_LOCKED";
    /**
     * 媒体后端失败：媒体存储或处理后端返回错误。
     */
    public static final String MEDIA_BACKEND_FAILED = "MEDIA_BACKEND_FAILED";
    /**
     * 媒体访问被拒：调用方无权读取该媒体资产。
     */
    public static final String MEDIA_ACCESS_DENIED = "MEDIA_ACCESS_DENIED";
    /**
     * 媒体生成任务不存在：指定任务标识查无对应记录。
     */
    public static final String MEDIA_JOB_NOT_FOUND = "MEDIA_JOB_NOT_FOUND";
    /**
     * 媒体任务版本冲突：基于旧版本更新任务状态。
     */
    public static final String MEDIA_JOB_VERSION_CONFLICT = "MEDIA_JOB_VERSION_CONFLICT";
    /**
     * 媒体任务状态非法：当前状态下不允许该流转（如已完成的任务再次提交）。
     */
    public static final String MEDIA_JOB_INVALID_STATE = "MEDIA_JOB_INVALID_STATE";
    /**
     * 媒体传输通道不支持：缺少可用的发布或回传通道，或引用地址不是安全的 HTTPS。
     */
    public static final String MEDIA_TRANSPORT_NOT_SUPPORTED = "MEDIA_TRANSPORT_NOT_SUPPORTED";
    /**
     * 媒体元信息不可用：未启用或无法提供元信息探测能力。
     */
    public static final String MEDIA_METADATA_UNSUPPORTED = "MEDIA_METADATA_UNSUPPORTED";
    /**
     * 媒体内容：内容安全校验判定为不可接受。
     */
    public static final String MEDIA_CONTENT_REJECTED = "MEDIA_CONTENT_REJECTED";
    /**
     * 媒体任务平台失败：生成任务在平台侧执行失败。
     */
    public static final String MEDIA_JOB_PROVIDER_FAILED = "MEDIA_JOB_PROVIDER_FAILED";
    /**
     * 媒体任务提交结果未知：提交时超时或连接中断，无法确认平台是否已受理，不可直接重试。
     */
    public static final String MEDIA_JOB_SUBMISSION_UNKNOWN = "MEDIA_JOB_SUBMISSION_UNKNOWN";
    /**
     * 媒体任务轮询次数达上限：超过最大轮询次数仍未终态，任务判定为失败。
     */
    public static final String MEDIA_JOB_POLL_LIMIT = "MEDIA_JOB_POLL_LIMIT";
    /**
     * 媒体任务存储被占用：未能取得存储锁，稍后可重试。
     */
    public static final String MEDIA_JOB_STORE_LOCKED = "MEDIA_JOB_STORE_LOCKED";
    /**
     * 媒体任务存储损坏：任务记录校验失败或无法解析。
     */
    public static final String MEDIA_JOB_STORE_CORRUPTED = "MEDIA_JOB_STORE_CORRUPTED";
    /**
     * 媒体任务存储读写失败：磁盘 IO 异常。
     */
    public static final String MEDIA_JOB_STORE_IO_ERROR = "MEDIA_JOB_STORE_IO_ERROR";
    /**
     * 媒体任务资源超限：任务数量或累计规模超过配置上限。
     */
    public static final String MEDIA_JOB_RESOURCE_LIMIT = "MEDIA_JOB_RESOURCE_LIMIT";
    /**
     * 媒体回调鉴权失败：签名或身份校验不通过。
     */
    public static final String MEDIA_CALLBACK_AUTHENTICATION_FAILED = "MEDIA_CALLBACK_AUTHENTICATION_FAILED";
    /**
     * 媒体回调已过期：时间戳超出允许的时间窗口（过早或过晚）。
     */
    public static final String MEDIA_CALLBACK_EXPIRED = "MEDIA_CALLBACK_EXPIRED";
    /**
     * 媒体回调被重放：同一回调标识已处理过，重复投递被拒绝。
     */
    public static final String MEDIA_CALLBACK_REPLAYED = "MEDIA_CALLBACK_REPLAYED";
    /**
     * 媒体回调资源超限：回调频率或累计处理量超过配置上限。
     */
    public static final String MEDIA_CALLBACK_RESOURCE_LIMIT = "MEDIA_CALLBACK_RESOURCE_LIMIT";
    /**
     * 媒体回调报文非法：结构、字段类型或载荷内容不符合约定。
     */
    public static final String MEDIA_CALLBACK_INVALID = "MEDIA_CALLBACK_INVALID";

    private ErrorCodes() {
    }
}
