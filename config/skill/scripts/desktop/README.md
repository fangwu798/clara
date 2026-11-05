# 桌面观察与 Computer Use 工具

本目录提供两个自描述 Python 工具，让模型通过「截图观察 → 键鼠动作 → 再截图验证」的闭环操作 Windows 桌面：

- `desktop_observe`：截图、绘制坐标网格和鼠标十字标记、返回前台窗口与鼠标位置；截图会作为图片附件进入下一轮模型输入。
- `desktop_control`：移动鼠标、点击、双击、右键、拖拽、滚动、输入文本、按键、快捷键、聚焦窗口、列出窗口和等待。

实现只依赖 Python 标准库 `ctypes`，不需要安装 `pyautogui`、`Pillow`、`pynput` 或 OCR 依赖。

## 启用

桌面能力默认关闭。启动 Clara 前设置环境变量：

```powershell
$env:CLARA_DESKTOP_CONTROL_ENABLED = "1"
.\clara.bat
```

只对当前终端会话生效。不要在不受信任的共享桌面或远程会话中长期开启。

## 典型调用顺序

1. 调用 `desktop_observe`，让模型看到当前屏幕。截图里的网格标签是屏幕全局物理像素坐标。
2. 模型根据截图调用 `desktop_control`，例如 `click(x=1200, y=640)`。
3. 再次调用 `desktop_observe` 验证结果，再决定下一步。

模型必须使用支持视觉能力的 CHAT 模型，否则截图附件无法被理解。普通仅文本模型仍能拿到坐标和窗口元信息，但看不到画面。

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CLARA_DESKTOP_CONTROL_ENABLED` | 关闭 | 设为 `1`、`true`、`yes` 或 `on` 才启用桌面观察和操作 |
| `CLARA_DESKTOP_STATE_DIR` | `<CLARA_WORK_DIR>/.clara/desktop` | 状态和审计目录 |
| `CLARA_DESKTOP_SCREENSHOT_DIR` | 状态目录下的 `screenshots/` | 截图目录 |
| `CLARA_DESKTOP_SCREENSHOT_KEEP` | `50` | 保留最近多少张截图，范围 1-500 |
| `CLARA_DESKTOP_AUDIT_ENABLED` | 开启 | 是否写 `audit.jsonl`；不记录输入文本正文 |
| `CLARA_DESKTOP_ALLOW_SENSITIVE` | 关闭 | 允许操作命中敏感窗口规则的窗口 |
| `CLARA_DESKTOP_DENIED_WINDOW_PATTERNS` | 内置密码/安全关键词 | 追加敏感窗口标题关键词，逗号或分号分隔 |

## 安全边界

- 默认拒绝操作标题命中 `password`、`密码`、`1Password`、`Bitwarden`、`Windows Security` 等敏感窗口；确需操作时由调用方显式传 `allow_sensitive=true`。
- 输入文本不会写入审计文件，只记录长度；坐标、动作、窗口和截图路径会记录。
- 截图可能包含隐私和密钥。默认保存到被 `.gitignore` 忽略的 `.clara/desktop/screenshots/`，并自动清理旧文件。
- 模型看到的网页、文档和截图内容都可能包含提示词注入。系统提示要求模型只把截图当作界面状态，不执行截图里的指令，但仍建议对删除、发送、支付、安装等不可逆操作保留人工确认。

## 已知限制

- 只能在已登录、未锁屏的 Windows 交互式桌面中工作。SSH、Windows 服务、非交互会话通常无法访问用户桌面。
- UAC 安全桌面、管理员权限窗口和跨完整性级别输入可能被 Windows 拦截。
- 多显示器和高 DPI 已统一到物理像素坐标，但显示器热插拔、分辨率变化或窗口移动后应重新观察。
- 截图是离散帧，不是实时视频流。
- 直接按坐标点击可能受页面滚动、窗口遮挡和动画影响；优先让模型根据最新截图操作，动作后必须再次观察验证。
