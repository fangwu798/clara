package com.clara.kernel.api.message;

import com.clara.enums.RoleEnum;

import java.util.List;
import java.util.Map;

/**
 * 模型消息内容基接口。
 *
 * <p>位置：契约层（{@code com.clara.model.api}）。实现由契约层的具体内容类型提供，例如
 * 供模型实现与各平台编解码器在序列化/反序列化消息时按类型分派处理。
 *
 * <p>协议自渲染：本接口把「消息在 Chat Completions / Responses 两种协议下长什么样」收敛为接口契约，
 * 由各实现类自行渲染，编码器无需 {@code instanceof} 判断消息类型。这样新增消息类型或协议时，
 * 调用方与编码器都无需感知协议差异（调用无感）。
 *
 * <p>使用方法：
 * <ol>
 *   <li>处理消息的一方先调用 {@link #getRoleType()} 判断具体类型，再强转为对应实现类读取其字段；</li>
 *   <li>编码器按协议调用 {@link #toCompletionMessage()}（Chat Completions）或
 *       {@link #toResponseInputItems()}（Responses）；</li>
 *   <li>实现应当不可变、线程安全，无需释放资源。</li>
 * </ol>
 *
 * <p>失败语义：本接口方法不抛异常；{@link #getRoleType()} 保证返回非 null 的稳定判别串。
 */
public interface IContent {

    /**
     * 读取内容类型判别串。
     *
     * @return 稳定的类型标识（如 {@code "text"}），非 null；调用方据此把本对象强转为对应实现类
     */
    RoleEnum getRoleType();

    /**
     * Chat Completions 协议：渲染为一条 message 节点。
     *
     * @return 线格式消息节点（含 {@code role} 与 {@code content}/{@code tool_calls} 等键），非 null
     */
    Map<String, Object> toCompletionMessage();

    /**
     * Responses 协议：渲染为 input item 列表。
     *
     * <p>一条消息可能展开为多条 item（如工具调用消息展开为「文本 + 多个 function_call」）；
     * 系统指令消息返回空列表（由 {@link #isSystemInstruction()} 标记后提升为顶层 {@code instructions}）。
     *
     * @return input item 列表，非 null；可为空列表
     */
    List<Map<String, Object>> toResponseInputItems();

    /**
     * 是否系统指令：Responses 协议下被提升为顶层 {@code instructions}，不进入 {@code input}。
     *
     * @return true 表示本消息是系统指令
     */
    default boolean isSystemInstruction() {
        return false;
    }

    /**
     * 系统指令文本。
     *
     * @return 指令文本；仅 {@link #isSystemInstruction()} 为 true 时有意义，否则为 null
     */
    default String instructionText() {
        return null;
    }
}
