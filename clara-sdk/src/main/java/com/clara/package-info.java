/**
 * Clara SDK 的装配入口包。
 *
 * <p>{@link com.clara.ApplicationBootstrap} 是宿主嵌入 Clara 的唯一入口：负责加载配置、装配模型调用、
 * 记忆、工具、工作流与 MCP 能力，并把对话编排暴露给宿主。{@code clara-application} 只是基于本 SDK
 * 实现的命令行外壳，宿主不需要依赖它。
 *
 * <p>带 {@link com.clara.core.annotation.PublicApi} 标记的类型属于兼容性承诺范围；其余公开类型均为
 * 内部实现，升级时可能调整。
 */
package com.clara;
