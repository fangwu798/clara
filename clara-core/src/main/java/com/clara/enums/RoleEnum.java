package com.clara.enums;

import com.clara.core.message.IContent;

/**
 * 对话消息的角色：{@link IContent#getRoleType()} 的类型判别取值。
 *
 * <p>是什么、在哪一层：内核共享枚举包（{@code com.clara.enums}）中的值类型，用于区分消息内容实现类；
 * 写入请求体的线格式角色名由各实现类的 {@code getRole()} 以字符串单独给出，两者不是同一份取值。
 */
public enum RoleEnum {
    /** 系统指令消息，线格式角色 {@code system}。 */
    SYSTEM,
    /** 用户输入消息，线格式角色 {@code user}。 */
    USER,
    /** 助手的文本回复消息，线格式角色 {@code assistant}。 */
    ASSISTANT,
    /** 工具执行结果消息，线格式角色 {@code tool}。 */
    TOOL,
    /** 助手要求调用工具的消息（{@code ToolCallContent}）；线格式角色仍是 {@code assistant}。 */
    TOOL_CALL
}
