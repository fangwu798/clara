# 变更记录

记录对外发布版本的主要变更；版本号遵循语义化版本。

## [Unreleased]

### 新增

- CI 工作流 `.github/workflows/ci.yml`：push / PR 执行完整 `mvn verify`。
- PR 依赖审查工作流 `.github/workflows/dependency-review.yml`：高危漏洞阻断合并。
- Dependabot 配置：Maven 依赖与 GitHub Actions 每周检查更新。
- `Diagnostics` 诊断端口：库模块不再直接写标准输出，嵌入宿主不再被 CLI 进度输出污染。

### 变更

- 发布到 GitHub Packages 时附带 `-sources.jar` 与 `-javadoc.jar`。
- CI 在 `mvn verify` 后执行 80% 行覆盖率门禁。
- 标签发布同时创建 GitHub Release，并附加可执行的 `clara.jar`。
- CLI 运行日志默认只保留最近 30 份，可用 `-Dclara.logs.keep=N` 调整。
- `clara-bom` 不再管理未参与发布的 `clara-application`。

## [1.0.1] - 2026-10-26

### 修复

- 专用提示词（图片、视频、方案、评审、工作流生成、压缩总结等）不再参与按用户输入的语义召回，
  避免「AI 能做什么」这类无关问题命中图片理解提示词导致答非所问。

### 变更

- 新增 GitHub Packages 发布 profile（`mvn -Pgithub deploy`）与标签自动发布工作流。
- `clara-application` 作为可执行 CLI 不参与制品发布。

## [1.0.0] - 2026-10-23

### 新增

- 首个正式版本：Java 8 可嵌入 Agent 客户端库 + 交互式 CLI。
- 多模型对话（OpenAI、DeepSeek、火山方舟、Kimi、Anthropic、阿里通义）。
- 上下文编排、工具与 MCP、工作流、技能、语义记忆、代码知识图谱、媒体能力与可观测。
- 整体单元测试行覆盖率提升至 90%。
