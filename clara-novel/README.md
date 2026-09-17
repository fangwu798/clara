# Clara Novel Agent

这是 Clara 的第一个可执行业务 Agent。它默认使用 `deepseek-chat` OpenAI-compatible 模型完成故事圣经、全书大纲、
逐章创作和审校修订，并使用字节火山引擎文生图模型根据故事设定生成竖版封面。每个阶段都会将状态保存到磁盘，
相同参数再次运行时会从已完成的阶段继续。

## 运行

在 PowerShell 中设置模型 Key（仅对当前终端有效）：

```powershell
$env:CLARA_DEEPSEEK_API_KEY = "你的 DeepSeek API Key"
```

从仓库根目录运行：

```powershell
.\run-novel-agent.ps1 --interactive --output output\my-novel
```

不传参数时也会默认进入交互模式。启动后先输入一段大概要求，剧情架构师、人物编辑、世界观设计师
会互相阅读、质疑和修订方案，由总编统一裁决；只有出现会改变主题、主角、核心冲突或结局的关键分歧时
才会询问你。讨论期间可直接输入补充或纠偏，输入 `/done` 可让委员会尽快收束并整理创作任务。
对话和最终任务分别保存在
`planning-chat.md` 与 `creative-brief.md`。

也可以跳过对话，直接提供完整任务：

```powershell
.\run-novel-agent.ps1 `
  --title "雾港来信" `
  --genre "近未来悬疑" `
  --premise "失踪多年的姐姐通过一座废弃气象站向弟弟发送明天才会发生的新闻" `
  --chapters 3 `
  --words 1500 `
  --output output\fog-harbor
```

首次运行需要 Maven 下载构建依赖。最终小说位于输出目录的 `novel.md`，封面位于 `cover.png`，
故事圣经、章节文件和可恢复的 `project.json` 也会保留。图片生成失败不会中断正文创作，失败状态会写入
`project.json`，并且断点恢复时不会重复请求封面。执行 `run-novel-agent.ps1 --help` 查看简要帮助。

默认启动时会同时加载 `config/model-providers/deepseek.yml`、`openai.yml` 和
`volcengine.yml` 三个平台配置；主文本默认使用 DeepSeek，委员会的世界观设计师使用火山方舟对话模型
`doubao-seed-character-260628`（端点
`https://ark.cn-beijing.volces.com/api/v3/chat/completions`），封面默认使用火山引擎。
因此交互模式需要同时设置 `CLARA_DEEPSEEK_API_KEY` 和 `CLARA_VOLCENGINE_API_KEY`；启动时控制台会显示本次
实际使用的文本、世界观和生图模型。
可以通过 `--config` 指定另一套文本配置目录中的 DeepSeek 配置。

火山引擎可以在同一配置中定义多个生图模型，使用 `--image-model` 选择：

```powershell
.\run-novel-agent.ps1 --interactive --image-model image-quality
```

可选名称由 `volcengine.yml` 的 `capabilities` 决定，当前提供 `image`、`image-fast`、
`image-quality` 三个节点。请把它们的 `modelId` 替换为方舟控制台中的真实 Endpoint ID。

三套平台 Key 分别使用 `CLARA_DEEPSEEK_API_KEY`、`CLARA_OPENAI_API_KEY` 和
`CLARA_VOLCENGINE_API_KEY`，密钥不会写入项目文件。OpenAI 配置当前完成加载和校验，
文本默认仍由 DeepSeek 执行。直接模式只强制要求 `CLARA_DEEPSEEK_API_KEY`，火山 Key 缺失时封面会记录失败状态；
交互模式会在启动前检查火山 Key，因为世界观设计师需要调用火山对话模型。未配置 OpenAI Key 不影响当前流程。
