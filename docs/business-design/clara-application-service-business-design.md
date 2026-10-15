# clara-application 服务 — 业务设计文档

> 状态：已审核
> 关联文档：`clara-orchestration-service-business-design.md`、`clara-workflow-service-business-design.md`
>
> 修订（2026-10-15）：运行模式与核心业务入口收敛——CLI 运行模式与 `WorkspaceBusinessRunner`（run / resume / cancel）均已移除（CLI 删除后无任何调用方），任务改由交互式控制台 + 工作流（`run_workflow` / `publish_workflow`）承担；下文提及它们的章节保留为当时的设计记录。

## 1. 背景与目标

### 现状痛点

**痛点 1：缺少统一的程序入口。** 各模块能力完整，但缺少一个宿主应用可直接使用的程序入口，把能力组合成可运行的应用。

**痛点 2：运行模式需灵活切换。** 宿主应用既需要命令行（CLI）运行模式，也需要 REST / SSE 等服务端模式。

### 目标

**目标 1：统一程序入口。** application 作为宿主应用直接使用的程序入口，组合各模块能力。

**目标 2：多模式运行。** 现阶段聚焦 CLI 运行模式，REST / SSE 等服务端模式后续阶段支持。

---

## 2. 术语定义

| 术语 | 定义 |
|---|---|
| 程序入口（Application Entry） | 宿主应用直接使用的启动入口 |
| CLI 运行模式 | 命令行交互模式，支持 plain / json / json-detached / json-stream / tui 等输出 |
| REST 模式 | 通过控制接口（run / resume / cancel）以 HTTP 语义暴露能力 |
| SSE 模式 | 将运行结果转换为带序号的 SSE 事件流 |

---

## 3. 业务职责

### 3.1 程序入口

**职责说明：** 作为宿主应用直接使用的程序入口，组合 clara-core / memory / skill / orchestration 等模块能力。

**核心组件：** `WorkspaceBusinessRunner` 是核心业务入口，提供 `run` / `resume` / `cancel`，被各运行模式共同调用。

### 3.2 CLI 运行模式

**职责说明：** 命令行交互入口，解析命令参数并调用业务运行器。

**关键能力：**

| 能力 | 说明 |
|---|---|
| 参数解析 | 解析命令、目标、写模式、UI 模式、输出格式等参数 |
| 运行 / 恢复 | 调用业务运行器执行或恢复 |
| 多种输出 | 支持 plain / json / json-detached / json-stream / tui 等模式 |

### 3.3 REST 模式（后续阶段支持）

**职责说明：** 通过控制器接口对外暴露运行、恢复、取消能力，供服务端 HTTP 路由映射。

### 3.4 SSE 模式（后续阶段支持）

**职责说明：** 将运行结果与错误转换为带序号的 SSE 事件，供流式服务端推送。

### 3.5 应用层组合能力

**职责说明：** 组合工作区、媒体、文档解析等应用层能力，供业务运行器调用（现有能力）。

---

## 4. 功能需求

### FR-A1 程序入口

作为宿主应用直接使用的程序入口，组合各模块能力。

### FR-A2 CLI 运行模式

解析命令行参数，调用业务运行器，支持多种输出模式。

### FR-A3 REST 模式（后续阶段支持）

通过控制器接口暴露运行 / 恢复 / 取消能力。

### FR-A4 SSE 模式（后续阶段支持）

将运行结果转换为带序号的 SSE 事件流。

### FR-A5 应用层组合

组合工作区、媒体、文档解析等应用层能力。

---

## 5. 业务规则汇总

| 编号 | 规则 |
|---|---|
| AR1 | application 是宿主应用直接使用的程序入口 |
| AR2 | 核心业务入口为 WorkspaceBusinessRunner（run / resume / cancel） |
| AR3 | CLI / REST / SSE 运行模式共用同一业务入口（现阶段仅实现 CLI，REST / SSE 后续支持） |
| AR4 | 应用层不依赖租户（tenantId），现有代码中的 tenantId 按新业务去除 |

---

## 6. 与各模块的衔接

application 作为程序入口，组合各模块：

| 能力 | 来源模块 |
|---|---|
| 对话编排 | clara-orchestration |
| 工作流 / 规划 / 交互 | clara-workflow |
| 记忆 | clara-memory |
| 技能 | clara-skill |
| 模型调用 | clara-model |
| mcp 联动 | clara-mcp |

---

## 7. 边界与非目标

**不实现核心领域能力。** 模型、记忆、技能、编排等由对应模块实现，application 只做入口与组合。

**不实现 Web 容器。** REST / SSE 适配器是协议边界，宿主应用负责映射到实际 HTTP 路由。

**现有代码的处理。** 除 clara-model 与 clara-core 外，其余模块现有代码仅作参考；现有 application 代码按新业务重写，不做兼容处理；现有代码中的 `tenantId` 为错误，按无租户模型去除。

---

## 8. 架构分层约束（已确认）

**core / memory / skill / workflow / orchestration / mcp 层：** 提供领域能力。

**clara-application 层：** 程序入口，组合各模块能力，提供 CLI / REST / SSE 运行模式。

---

## 9. 业务决策

| 编号 | 决策点 | 结论 |
|---|---|---|
| AD1 | 运行模式 | 现阶段仅 CLI；REST / SSE 后续阶段支持，共用同一业务入口（WorkspaceBusinessRunner） |
| AD2 | TUI 模式是否实现 | 仅 CLI 模式实现 TUI |
