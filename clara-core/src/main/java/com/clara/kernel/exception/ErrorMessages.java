package com.clara.kernel.exception;

import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ErrorCodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把稳定错误码翻译成面向使用者的中文提示。
 *
 * <p>常规用法：{@link ClaraError} 在调用方没有显式给出消息时按错误码取默认中文提示，因此各模块无需逐个改抛点
 * 即可获得可读的错误信息；需要把平台名、状态码等上下文写进提示的场景，用 {@link #of(String, Map)} 传入变量。
 *
 * <p>约定：
 * <ul>
 *   <li>错误码本身是稳定契约，本类只提供文案，<b>不改变</b> {@link ErrorCodes} 中的常量；</li>
 *   <li>未登记的错误码原样返回错误码本身，既不抛异常也不返回 null，新增错误码忘记登记时行为退化为现状；</li>
 *   <li>提示用中文全角标点、句尾不加句号，且不重复错误码；不写入密钥、请求体与平台返回原文。</li>
 * </ul>
 *
 * <p>注意：本类不可实例化，所有方法都是纯函数，可安全并发使用。
 */
public final class ErrorMessages {
    /**
     * 错误码到中文提示模板的映射，模板中的 {@code {name}} 为占位符。
     */
    private static final Map<String, String> TEMPLATES = templates();

    private ErrorMessages() {
    }

    /**
     * 读取错误码对应的中文提示，不含变量。
     *
     * @param code 错误码
     * @return 中文提示；错误码未登记时返回错误码本身
     * @throws IllegalArgumentException 错误码为 null 或空白时
     */
    public static String of(String code) {
        return of(code, Collections.<String, String>emptyMap());
    }

    /**
     * 读取错误码对应的中文提示，并用变量替换模板中的占位符。
     *
     * <p>替换是单遍扫描，不会对变量值再次展开；模板中用到的占位符若未在 {@code args} 中提供，
     * 则原样保留 {@code {name}}，便于在测试中发现漏填。
     *
     * @param code 错误码
     * @param args 占位符名到值的映射，可为 null
     * @return 插值后的中文提示；错误码未登记时返回错误码本身
     * @throws IllegalArgumentException 错误码为 null 或空白时
     */
    public static String of(String code, Map<String, String> args) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        String template = TEMPLATES.get(code);
        if (template == null) {
            return code;
        }
        if (args == null || args.isEmpty()) {
            return template;
        }
        return interpolate(template, args);
    }

    /**
     * 判断错误码是否已登记中文提示。
     *
     * @param code 错误码，可为 null
     * @return 已登记时为 true
     */
    public static boolean isRegistered(String code) {
        return code != null && TEMPLATES.containsKey(code);
    }

    /**
     * 按占位符替换模板内容。
     *
     * @param template 提示模板
     * @param args     占位符取值
     * @return 替换后的文本
     */
    private static String interpolate(String template, Map<String, String> args) {
        StringBuilder result = new StringBuilder(template.length() + 32);
        int index = 0;
        while (index < template.length()) {
            int open = template.indexOf('{', index);
            if (open < 0) {
                result.append(template, index, template.length());
                break;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                result.append(template, index, template.length());
                break;
            }
            result.append(template, index, open);
            String name = template.substring(open + 1, close);
            String value = args.get(name);
            if (value == null) {
                result.append(template, open, close + 1);
            } else {
                result.append(value);
            }
            index = close + 1;
        }
        return result.toString();
    }

    /**
     * 构造错误码到中文提示模板的不可变映射。
     *
     * @return 模板映射
     */
    private static Map<String, String> templates() {
        Map<String, String> messages = new LinkedHashMap<String, String>();
        messages.put(ErrorCodes.INVOCATION_CANCELLED, "调用已被取消：调用方在完成前终止了本次调用");
        messages.put(ErrorCodes.CAPABILITY_NOT_SUPPORTED, "不支持该能力：当前平台或实现未提供请求的能力");
        messages.put(ErrorCodes.MODEL_ALREADY_REGISTERED, "模型已注册：同一标识的模型被重复注册");
        messages.put(ErrorCodes.MODEL_NOT_REGISTERED, "模型未注册：请求的模型不在注册表中");
        messages.put(ErrorCodes.MODEL_ENDPOINT_DENIED, "模型端点被拒绝：目标地址不在允许清单内，或不是安全的 HTTPS 地址");
        messages.put(ErrorCodes.MODEL_REQUEST_TOO_LARGE, "模型请求体过大：超出配置的体积上限，请求未发出");
        messages.put(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, "模型响应体过大：超出配置的体积上限，读取已中止");
        messages.put(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, "模型传输失败：连接中断或读写异常，通常可重试");
        messages.put(ErrorCodes.MODEL_REQUEST_TIMEOUT, "模型调用超时：连接或读取超过截止时间，可重试");
        messages.put(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, "模型凭据不可用：未解析到平台 {provider} 的可用密钥，请检查凭据配置");
        messages.put(ErrorCodes.MODEL_PROVIDER_FAILED, "模型平台调用失败：平台 {provider}，返回 {providerCode}");
        messages.put(ErrorCodes.MODEL_PROTOCOL_ERROR, "模型响应不符合协议：结构缺失、字段类型不符或跨块内容矛盾");
        messages.put(ErrorCodes.INVALID_ARGUMENT, "入参非法：调用方传入的参数不满足接口契约");
        messages.put(ErrorCodes.CONVERSATION_VERSION_CONFLICT, "会话版本冲突：基于旧版本写入，期间已被其他请求修改");
        messages.put(ErrorCodes.CONVERSATION_STORE_LOCKED, "会话存储被占用：未能取得存储锁，稍后可重试");
        messages.put(ErrorCodes.CONVERSATION_STORE_CORRUPTED, "会话存储损坏：文件校验失败或内容无法解析");
        messages.put(ErrorCodes.CONVERSATION_STORE_IO_ERROR, "会话存储读写失败：磁盘 IO 异常");
        messages.put(ErrorCodes.MCP_SERVER_NOT_REGISTERED, "MCP 服务未注册：请求的服务不在注册表中");
        messages.put(ErrorCodes.MCP_SERVER_ALREADY_REGISTERED, "MCP 服务已注册：同一服务被重复注册");
        messages.put(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, "MCP 协议版本不兼容：服务端协商结果与客户端支持范围无交集");
        messages.put(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, "MCP 服务身份已变更：服务端上报的名称或版本与已批准基线不一致，需要重新审批");
        messages.put(ErrorCodes.MCP_PAGINATION_LIMIT_EXCEEDED, "MCP 分页轮次超限：翻页次数超过配置上限");
        messages.put(ErrorCodes.MCP_PAGINATION_CURSOR_REPEATED, "MCP 分页游标重复：同一游标再次出现，分页出现循环");
        messages.put(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, "MCP 能力不存在：请求的工具或资源在服务端未声明");
        messages.put(ErrorCodes.MCP_CAPABILITY_CHANGED, "MCP 能力已变更：服务端声明的能力与本地缓存基线不一致");
        messages.put(ErrorCodes.MCP_DISCOVERY_FAILED, "MCP 能力发现失败：列举工具或资源时服务端返回错误或超时");
        messages.put(ErrorCodes.MCP_SERVER_NOT_ACTIVE, "MCP 服务不可用：服务当前不处于可调用状态");
        messages.put(ErrorCodes.MCP_TRANSPORT_CLOSED, "MCP 传输已关闭：连接关闭后仍尝试读写");
        messages.put(ErrorCodes.MCP_TRANSPORT_IO_ERROR, "MCP 传输失败：与 MCP 服务通信时读写异常");
        messages.put(ErrorCodes.MCP_REQUEST_TIMEOUT, "MCP 请求超时：等待响应超过截止时间");
        messages.put(ErrorCodes.MCP_DUPLICATE_REQUEST_ID, "MCP 请求标识重复：同一请求标识被重复使用或重复响应");
        messages.put(ErrorCodes.MCP_MESSAGE_TOO_LARGE, "MCP 报文过大：单条消息超出允许体积");
        messages.put(ErrorCodes.MCP_INVALID_MESSAGE, "MCP 报文非法：不符合协议要求的 JSON 结构或字段类型");
        messages.put(ErrorCodes.WORKFLOW_DEFINITION_INVALID, "工作流定义非法：结构、字段或引用不满足契约");
        messages.put(ErrorCodes.WORKFLOW_VERSION_CONFLICT, "工作流版本冲突：基于旧版本更新定义或实例");
        messages.put(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, "工作流实例不存在：指定实例标识查无对应记录");
        messages.put(ErrorCodes.WORKFLOW_INVALID_STATE, "工作流状态非法：当前状态下不允许执行该操作");
        messages.put(ErrorCodes.WORKFLOW_OUTPUT_CONFLICT, "工作流输出冲突：本次产出与已记录的输出重复或不一致");
        messages.put(ErrorCodes.PLAN_INVALID, "计划非法：结构、步骤或依赖不满足契约");
        messages.put(ErrorCodes.PLAN_LIMIT_EXCEEDED, "计划规模超限：步骤数、深度或并行度超过允许上限");
        messages.put(ErrorCodes.PLAN_CAPABILITY_DENIED, "计划能力被拒：计划要求的能力不在允许范围内");
        messages.put(ErrorCodes.PLAN_REPLAN_INVALID, "重新规划请求非法：当前状态或参数不允许重规划");
        messages.put(ErrorCodes.BUDGET_EXCEEDED, "预算已耗尽：本次调用或任务超出可用预算额度");
        messages.put(ErrorCodes.BUDGET_RESERVATION_INVALID, "预算预留非法：预留金额、标识或状态不符合账本规则");
        messages.put(ErrorCodes.EXECUTION_LEDGER_CONFLICT, "执行账本冲突：并发写入或重复记录导致账本状态不一致");
        messages.put(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, "执行副作用未知：操作可能已生效但结果无法确认，不可直接重试");
        messages.put(ErrorCodes.INTERACTION_NOT_FOUND, "交互请求不存在：待确认的交互记录查无对应条目");
        messages.put(ErrorCodes.INTERACTION_CONFLICT, "交互冲突：交互已被处理或状态已变化，本次操作不再适用");
        messages.put(ErrorCodes.INTERACTION_EXPIRED, "交互已过期：超过有效窗口，不能再作答");
        messages.put(ErrorCodes.INTERACTION_INVALID_ANSWER, "交互回答非法：答案格式、选项或长度不符合该交互的要求");
        messages.put(ErrorCodes.INTERACTION_LIMIT_EXCEEDED, "交互次数超限：同一任务的交互轮次超过配置上限");
        messages.put(ErrorCodes.INTERACTION_ACCESS_DENIED, "交互访问被拒：当前调用方无权处理该交互请求");
        messages.put(ErrorCodes.SKILL_NOT_FOUND, "技能不存在：指定标识与版本的技能未注册");
        messages.put(ErrorCodes.SKILL_VERSION_EXISTS, "技能版本已存在：同一技能的同版本不允许重复创建");
        messages.put(ErrorCodes.SKILL_INVALID_LIFECYCLE, "技能生命周期非法：当前生命周期状态不允许该操作");
        messages.put(ErrorCodes.SKILL_ARTIFACT_REJECTED, "技能制品被拒：制品校验未通过（内容安全或完整性检查失败）");
        messages.put(ErrorCodes.SKILL_DEPENDENCY_INVALID, "技能依赖非法：依赖缺失、未发布或版本约束无法满足");
        messages.put(ErrorCodes.SKILL_PERMISSION_DENIED, "技能权限被拒：调用方无权使用或修改该技能");
        messages.put(ErrorCodes.SKILL_UNAVAILABLE, "技能不可用：技能存在但当前无法执行");
        messages.put(ErrorCodes.SKILL_SCHEMA_INVALID, "技能入参或出参非法：定义不符合契约");
        messages.put(ErrorCodes.SKILL_HANDLER_NOT_FOUND, "技能处理入口不存在：声明的能力找不到对应实现");
        messages.put(ErrorCodes.SKILL_TIMEOUT, "技能执行超时：超过该技能允许的执行时间");
        messages.put(ErrorCodes.WORKSPACE_ACCESS_DENIED, "工作区访问被拒：目标路径或资源不在允许范围内");
        messages.put(ErrorCodes.WORKSPACE_RESOURCE_LIMIT, "工作区资源超限：文件数、单文件或累计体积超过配置上限");
        messages.put(ErrorCodes.WORKSPACE_FILE_NOT_FOUND, "工作区文件不存在：指定路径在工作区内查无对应文件");
        messages.put(ErrorCodes.DOCUMENT_FORMAT_UNSUPPORTED, "文档格式不支持：后缀或内容类型不在允许解析的范围内");
        messages.put(ErrorCodes.DOCUMENT_PARSE_FAILED, "文档解析失败：内容损坏或结构无法识别");
        messages.put(ErrorCodes.DOCUMENT_ENCRYPTED, "文档已加密：受密码保护，无法直接解析");
        messages.put(ErrorCodes.WORKSPACE_CONFLICT, "工作区冲突：文件在读取后被其他操作修改，基于旧版本写入被拒绝");
        messages.put(ErrorCodes.REGEX_UNSUPPORTED, "正则模式不支持：当前实现只支持字面量匹配");
        messages.put(ErrorCodes.ZERO_LENGTH_EDIT_DENIED, "零长度编辑被拒：匹配区间为空，不构成有效修改");
        messages.put(ErrorCodes.CHANGESET_APPROVAL_INVALID, "变更集审批无效：缺少有效审批，或审批与待应用的变更不匹配");
        messages.put(ErrorCodes.CHANGESET_APPLY_FAILED, "变更集应用失败：写入过程出错，可能已部分应用，请按未知副作用处理");
        messages.put(ErrorCodes.SEMANTIC_MEMORY_LOCKED, "语义记忆被占用：未能取得存储锁，稍后可重试");
        messages.put(ErrorCodes.SEMANTIC_MEMORY_CORRUPTED, "语义记忆损坏：快照或校验信息无法解析，与预期不一致");
        messages.put(ErrorCodes.SEMANTIC_MEMORY_CONFIG_MISMATCH, "语义记忆配置不匹配：当前配置与既有存储数据不一致，不能继续使用");
        messages.put(ErrorCodes.SEMANTIC_MEMORY_RESOURCE_LIMIT, "语义记忆资源超限：记录数、向量规模或查询规模超过配置上限");
        messages.put(ErrorCodes.SEMANTIC_MEMORY_BACKEND_FAILED, "语义记忆后端失败：向量库或持久化后端返回错误");
        messages.put(ErrorCodes.MEDIA_NOT_FOUND, "媒体资产不存在：指定资产标识查无对应记录");
        messages.put(ErrorCodes.MEDIA_VALIDATION_FAILED, "媒体校验失败：格式、大小、时长或元信息不符合策略要求");
        messages.put(ErrorCodes.MEDIA_RESOURCE_LIMIT, "媒体资源超限：资产体积、数量或解码规模超过配置上限");
        messages.put(ErrorCodes.MEDIA_STORE_IO_ERROR, "媒体存储读写失败：磁盘或对象存储 IO 异常");
        messages.put(ErrorCodes.MEDIA_STORE_LOCKED, "媒体存储被占用：未能取得存储锁，稍后可重试");
        messages.put(ErrorCodes.MEDIA_BACKEND_FAILED, "媒体后端失败：媒体存储或处理后端返回错误");
        messages.put(ErrorCodes.MEDIA_ACCESS_DENIED, "媒体访问被拒：调用方无权读取该媒体资产");
        messages.put(ErrorCodes.MEDIA_JOB_NOT_FOUND, "媒体生成任务不存在：指定任务标识查无对应记录");
        messages.put(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, "媒体任务版本冲突：基于旧版本更新任务状态");
        messages.put(ErrorCodes.MEDIA_JOB_INVALID_STATE, "媒体任务状态非法：当前状态下不允许该流转");
        messages.put(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, "媒体传输通道不支持：平台 {provider} 缺少可用的发布或回传通道，或引用地址不是安全的 HTTPS");
        messages.put(ErrorCodes.MEDIA_METADATA_UNSUPPORTED, "媒体元信息不可用：未启用或无法提供元信息探测能力");
        messages.put(ErrorCodes.MEDIA_CONTENT_REJECTED, "媒体内容被拒：内容安全校验判定为不可接受");
        messages.put(ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, "媒体任务平台失败：生成任务在平台侧执行失败");
        messages.put(ErrorCodes.MEDIA_JOB_SUBMISSION_UNKNOWN, "媒体任务提交结果未知：提交时超时或连接中断，无法确认平台是否已受理，不可直接重试");
        messages.put(ErrorCodes.MEDIA_JOB_POLL_LIMIT, "媒体任务轮询次数达上限：超过最大轮询次数仍未终态，任务判定为失败");
        messages.put(ErrorCodes.MEDIA_JOB_STORE_LOCKED, "媒体任务存储被占用：未能取得存储锁，稍后可重试");
        messages.put(ErrorCodes.MEDIA_JOB_STORE_CORRUPTED, "媒体任务存储损坏：任务记录校验失败或无法解析");
        messages.put(ErrorCodes.MEDIA_JOB_STORE_IO_ERROR, "媒体任务存储读写失败：磁盘 IO 异常");
        messages.put(ErrorCodes.MEDIA_JOB_RESOURCE_LIMIT, "媒体任务资源超限：任务数量或累计规模超过配置上限");
        messages.put(ErrorCodes.MEDIA_CALLBACK_AUTHENTICATION_FAILED, "媒体回调鉴权失败：签名或身份校验不通过");
        messages.put(ErrorCodes.MEDIA_CALLBACK_EXPIRED, "媒体回调已过期：时间戳超出允许的时间窗口");
        messages.put(ErrorCodes.MEDIA_CALLBACK_REPLAYED, "媒体回调被重放：同一回调标识已处理过，重复投递被拒绝");
        messages.put(ErrorCodes.MEDIA_CALLBACK_RESOURCE_LIMIT, "媒体回调资源超限：回调频率或累计处理量超过配置上限");
        messages.put(ErrorCodes.MEDIA_CALLBACK_INVALID, "媒体回调报文非法：结构、字段类型或载荷内容不符合约定");
        return Collections.unmodifiableMap(messages);
    }
}
