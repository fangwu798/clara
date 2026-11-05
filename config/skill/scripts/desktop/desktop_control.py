"""执行 Windows 鼠标、键盘和窗口操作，供模型完成 Computer Use 闭环。

所有会改变桌面状态的动作都受 CLARA_DESKTOP_CONTROL_ENABLED 开关和敏感窗口保护约束；
每次调用返回执行前后前台窗口、鼠标位置和审计结果。
"""

import json
import os
import sys
import time

SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS_ROOT)
sys.path.insert(0, TOOL_DIR)

import _desktop_common
import _textio
import _toolmeta
import _win32

_textio.force_utf8_stdio()

_INPUT_ACTIONS = {
    "move", "click", "double_click", "right_click", "middle_click", "drag", "scroll",
    "type_text", "press_key", "hotkey",
}

_TOOL = {
    "id": "desktop_control",
    "name": "desktop_control",
    "type": "PYTHON",
    "description": (
        "操作当前 Windows 桌面的鼠标、键盘和窗口（高风险，会改变电脑状态）。"
        "使用前先用 desktop_observe 截图定位；动作后再次 desktop_observe 验证。"
        "入参 JSON：purpose(一句话说明目的，必填)、action(move|click|double_click|right_click|middle_click|"
        "drag|scroll|type_text|press_key|hotkey|focus_window|list_windows|wait)、"
        "x/y(屏幕全局物理坐标；click 不填则点击当前位置)、to_x/to_y(drag 终点)、"
        "button(left|right|middle，默认 left)、clicks(1-5)、interval_ms、duration_ms、"
        "amount(滚轮格数，正数向上/向右)、horizontal(是否横向滚动)、text(输入文本)、"
        "key(单个按键，如 enter、tab、f5)、keys(快捷键数组或 ctrl+s 字符串)、"
        "window(窗口标题片段或句柄)、seconds(等待秒数，最多 30)、"
        "dry_run(只校验参数不执行，默认 false)、allow_sensitive(是否允许操作密码管理器等敏感窗口，默认 false)。"
        "支持中文输入；支持 click/drag/scroll/type_text/press_key/hotkey/focus_window。"
        "敏感窗口默认拒绝操作。使用前必须设置环境变量 CLARA_DESKTOP_CONTROL_ENABLED=1。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "action": {
                "type": "string",
                "enum": ["move", "click", "double_click", "right_click", "middle_click", "drag",
                         "scroll", "type_text", "press_key", "hotkey", "focus_window",
                         "list_windows", "wait"],
            },
            "x": {"type": "integer"},
            "y": {"type": "integer"},
            "to_x": {"type": "integer"},
            "to_y": {"type": "integer"},
            "button": {"type": "string", "enum": ["left", "right", "middle"]},
            "clicks": {"type": "integer"},
            "interval_ms": {"type": "integer"},
            "duration_ms": {"type": "integer"},
            "amount": {"type": "integer"},
            "horizontal": {"type": "boolean"},
            "text": {"type": "string"},
            "key": {"type": "string"},
            "keys": {"type": ["array", "string"]},
            "window": {"type": "string"},
            "seconds": {"type": ["integer", "number"]},
            "dry_run": {"type": "boolean"},
            "allow_sensitive": {"type": "boolean"},
            "window_query": {"type": "string"},
            "max_windows": {"type": "integer"},
        },
        "required": ["purpose", "action"],
    },
    "metadata": {
        "domain": "desktop",
        "tags": "desktop,mouse,keyboard,windows,computer-use,high-risk",
        "timeoutMillis": "30000",
    },
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)


def _integer(spec, name, default=None, minimum=None, maximum=None):
    value = spec.get(name, default)
    if value is None:
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise ValueError("%s 必须是整数" % name)
    if minimum is not None and parsed < minimum:
        raise ValueError("%s 不能小于 %s" % (name, minimum))
    if maximum is not None and parsed > maximum:
        raise ValueError("%s 不能大于 %s" % (name, maximum))
    return parsed


def _window_list(spec):
    limit = _integer(spec, "max_windows", 30, 1, 200)
    windows = _win32.list_windows(spec.get("window_query") or "", limit)
    for window in windows:
        window["sensitive"] = _desktop_common.is_sensitive_title(window.get("title"))
    return windows


def _require_point(spec, x_name="x", y_name="y"):
    x = _integer(spec, x_name)
    y = _integer(spec, y_name)
    if x is None or y is None:
        raise ValueError("该动作需要同时提供 %s 和 %s" % (x_name, y_name))
    return x, y


def _execute(action, spec):
    if action == "list_windows":
        return {"windows": _window_list(spec)}
    if action == "wait":
        seconds = spec.get("seconds", 1)
        try:
            seconds = float(seconds)
        except (TypeError, ValueError):
            raise ValueError("seconds 必须是数字")
        if seconds < 0 or seconds > 30:
            raise ValueError("seconds 必须在 0 到 30 之间")
        if not spec.get("dry_run"):
            time.sleep(seconds)
        return {"seconds": seconds}

    active = _win32.foreground_window()
    if action in _INPUT_ACTIONS:
        _desktop_common.require_interactive_window(active)
        _desktop_common.assert_sensitive_window_allowed(active, bool(spec.get("allow_sensitive")))

    if action == "move":
        x, y = _require_point(spec)
        if spec.get("dry_run"):
            return {"planned": {"x": x, "y": y}}
        return {"cursor": _win32.move_cursor(x, y, _integer(spec, "duration_ms", 0, 0, 5000))}
    if action in ("click", "double_click", "right_click", "middle_click"):
        x = _integer(spec, "x")
        y = _integer(spec, "y")
        if (x is None) != (y is None):
            raise ValueError("x 和 y 必须同时提供，或同时省略以点击当前位置")
        button = spec.get("button") or ("right" if action == "right_click"
                                       else "middle" if action == "middle_click" else "left")
        clicks = 2 if action == "double_click" else _integer(spec, "clicks", 1, 1, 5)
        if spec.get("dry_run"):
            return {"planned": {"x": x, "y": y, "button": button, "clicks": clicks}}
        return {"cursor": _win32.click(button, clicks,
                                       _integer(spec, "interval_ms", 60, 0, 1000), x, y)}
    if action == "drag":
        x, y = _require_point(spec)
        to_x, to_y = _require_point(spec, "to_x", "to_y")
        button = spec.get("button") or "left"
        if spec.get("dry_run"):
            return {"planned": {"x": x, "y": y, "to_x": to_x, "to_y": to_y, "button": button}}
        return {"cursor": _win32.drag(x, y, to_x, to_y, button,
                                      _integer(spec, "duration_ms", 450, 0, 10000))}
    if action == "scroll":
        amount = _integer(spec, "amount")
        if amount is None or amount == 0:
            raise ValueError("scroll 需要非零 amount")
        if abs(amount) > 100:
            raise ValueError("单次滚动不得超过 100 格")
        x = _integer(spec, "x")
        y = _integer(spec, "y")
        if (x is None) != (y is None):
            raise ValueError("x 和 y 必须同时提供，或同时省略")
        if spec.get("dry_run"):
            return {"planned": {"amount": amount, "horizontal": bool(spec.get("horizontal")),
                                "x": x, "y": y}}
        return {"cursor": _win32.scroll(amount, bool(spec.get("horizontal")), x, y)}
    if action == "type_text":
        text = spec.get("text")
        if text is None:
            raise ValueError("type_text 需要 text")
        if spec.get("dry_run"):
            return {"planned": {"text_length": len(str(text))}}
        return {"typed_chars": _win32.type_text(text,
                                                _integer(spec, "interval_ms", 8, 0, 100))}
    if action == "press_key":
        key = spec.get("key")
        if not key:
            raise ValueError("press_key 需要 key")
        if spec.get("dry_run"):
            return {"planned": {"key": key}}
        _win32.press_key(key)
        return {"key": key}
    if action == "hotkey":
        keys = spec.get("keys")
        if keys is None:
            keys = spec.get("key")
        if not keys:
            raise ValueError("hotkey 需要 keys")
        if spec.get("dry_run"):
            return {"planned": {"keys": keys}}
        _win32.hotkey(keys)
        return {"keys": keys}
    if action == "focus_window":
        target = _win32.resolve_window(spec.get("window"))
        _desktop_common.assert_sensitive_window_allowed(target, bool(spec.get("allow_sensitive")))
        if spec.get("dry_run"):
            return {"planned": {"window": target["title"]}}
        return {"window": _win32.focus_window(target["handle"])}
    raise ValueError("未知 action：%s" % action)


def main():
    raw = sys.stdin.read()
    started = time.time()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 解析失败也按工具结果返回
        print(json.dumps({"ok": False, "error": "入参 JSON 解析失败：" + str(exception)},
                         ensure_ascii=False))
        return

    action = str(spec.get("action") or "")
    result = {"ok": False, "action": action}
    try:
        _desktop_common.require_enabled()
        if not action:
            raise ValueError("action 不能为空")
        before_active = _win32.foreground_window()
        before_cursor = _win32.cursor_position()
        payload = _execute(action, spec)
        result.update({"ok": True, "executed": not bool(spec.get("dry_run")), "result": payload})
        result["before"] = {"active_window": before_active, "cursor": before_cursor}
        result["after"] = {"active_window": _win32.foreground_window(),
                           "cursor": _win32.cursor_position()}
        result["screenshot_hint"] = "调用 desktop_observe 检查动作后的屏幕，确认结果后再继续。"
    except Exception as exception:  # noqa: BLE001 - 保持工具协议，以 error 字段返回
        result["error"] = str(exception)
    result["duration_ms"] = int((time.time() - started) * 1000)
    _desktop_common.audit("desktop_control", action or "unknown", spec, result,
                          result["duration_ms"])
    print(json.dumps(result, ensure_ascii=False))


main()
