package com.clara.utils;

import java.util.Objects;

/**
 * 对象通用操作工具。
 *
 * <p>定位：无状态的纯函数集合，提供项目内统一的对象判等入口。
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 */
public final class ObjectUtils {

    private ObjectUtils() {
    }

    /**
     * 判断两个对象是否相等，允许任意一方为 null。
     *
     * @param left  左值，可为 null
     * @param right 右值，可为 null
     * @return 两者均为 null，或左值的 {@link Object#equals(Object)} 判定相等时返回 true
     */
    public static boolean equal(Object left, Object right) {
        return Objects.equals(left, right);
    }
}
