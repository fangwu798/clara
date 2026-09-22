package com.clara.kernel.api.message;

import com.clara.enums.RoleEnum;

/**
 * 模型消息内容基接口。
 *
 * <p>位置：契约层（{@code com.clara.model.api}）。实现由契约层的具体内容类型提供，例如
 * 供模型实现与各平台编解码器在序列化/反序列化消息时按类型分派处理。
 *
 * <p>使用方法：
 * <ol>
 *   <li>处理消息的一方先调用 {@link #getRoleType()} 判断具体类型，再强转为对应实现类读取其字段；</li>
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
}
