"""桌面控制工具的共享配置、审计与敏感窗口判断。

本模块位于工具脚本目录内，供 desktop_observe / desktop_control 复用；文件名以下划线开头，
不会被框架当作独立工具加载。
"""

import json
import os
import re
import time

TRUE_VALUES = {"1", "true", "yes", "on", "enabled"}

DEFAULT_DENIED_WINDOW_PATTERNS = (
    "password", "密码", "credential", "凭据", "1password", "bitwarden", "keepass",
    "lastpass", "windows security", "windows 安全", "user account control",
    "用户帐户控制", "安全中心", "security center",
)


def flag(name, default=False):
    """读取布尔环境变量；未设置时返回默认值。"""
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in TRUE_VALUES


def require_enabled():
    """所有桌面读写能力都受同一个显式开关保护。"""
    if not flag("CLARA_DESKTOP_CONTROL_ENABLED"):
        raise PermissionError(
            "桌面控制未启用。请设置环境变量 CLARA_DESKTOP_CONTROL_ENABLED=1 后重启 Clara。"
        )


def work_root():
    """当前分析目录；与 file_ops / run_command 使用同一口径。"""
    configured = os.environ.get("CLARA_WORK_DIR")
    return os.path.abspath(configured) if configured else os.path.abspath(os.getcwd())


def state_dir():
    """桌面工具的本地状态目录，默认位于 <工作目录>/.clara/desktop。"""
    configured = os.environ.get("CLARA_DESKTOP_STATE_DIR")
    if configured:
        return os.path.abspath(configured)
    return os.path.join(work_root(), ".clara", "desktop")


def screenshot_dir():
    """截图目录；默认 <工作目录>/.clara/desktop/screenshots。"""
    configured = os.environ.get("CLARA_DESKTOP_SCREENSHOT_DIR")
    if configured:
        return os.path.abspath(configured)
    return os.path.join(state_dir(), "screenshots")


def screenshot_keep():
    """本地保留的最近截图数量。"""
    try:
        value = int(os.environ.get("CLARA_DESKTOP_SCREENSHOT_KEEP", "50"))
    except ValueError:
        value = 50
    return max(1, min(value, 500))


def audit_enabled():
    return flag("CLARA_DESKTOP_AUDIT_ENABLED", True)


def audit(tool, action, params, result, duration_ms):
    """追加一条审计记录；不记录输入文本正文，避免把密码等敏感内容写进日志。"""
    if not audit_enabled():
        return
    try:
        directory = state_dir()
        os.makedirs(directory, exist_ok=True)
        payload = {
            "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "tool": tool,
            "action": action,
            "params": summarize_params(params),
            "ok": bool(result.get("ok")) if isinstance(result, dict) else None,
            "duration_ms": duration_ms,
        }
        if isinstance(result, dict):
            for key in ("path", "window", "error"):
                if key in result:
                    payload[key] = result[key]
        with open(os.path.join(directory, "audit.jsonl"), "a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError:
        # 审计失败不能阻断用户当前操作。
        pass


def summarize_params(params):
    """把入参压成可审计摘要，文本只记录长度。"""
    if not isinstance(params, dict):
        return {}
    summary = {}
    for key, value in params.items():
        if key in ("purpose", "viewport"):
            continue
        if key == "text":
            summary["text_length"] = len(str(value))
        elif key in ("key", "keys", "window", "button", "action", "target"):
            summary[key] = value
        elif isinstance(value, (int, float, bool)) or value is None:
            summary[key] = value
    return summary


def denied_patterns():
    """允许用户通过环境变量追加敏感窗口关键词。"""
    configured = os.environ.get("CLARA_DESKTOP_DENIED_WINDOW_PATTERNS")
    if not configured:
        return DEFAULT_DENIED_WINDOW_PATTERNS
    extra = [item.strip() for item in re.split(r"[;,]", configured) if item.strip()]
    return DEFAULT_DENIED_WINDOW_PATTERNS + tuple(extra)


def is_sensitive_title(title):
    value = (title or "").casefold()
    if not value:
        return False
    return any(pattern.casefold() in value for pattern in denied_patterns())


def assert_sensitive_window_allowed(window, allow_sensitive=False):
    """默认禁止把键鼠输入发往密码管理器、安全中心等敏感窗口。"""
    if allow_sensitive or flag("CLARA_DESKTOP_ALLOW_SENSITIVE"):
        return
    title = (window or {}).get("title") or ""
    if is_sensitive_title(title):
        raise PermissionError(
            "当前窗口标题命中了敏感窗口保护规则：%s。如确需操作，请显式传入 allow_sensitive=true。" % title
        )


def require_interactive_window(window):
    if not window or not window.get("handle"):
        raise RuntimeError("当前没有可交互的前台窗口（可能锁屏、处于 UAC 安全桌面或没有活动窗口）")
