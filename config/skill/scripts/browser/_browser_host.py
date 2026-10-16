"""browser 工具的常驻后端：把 Playwright 会话留在后台进程里，按 op 提供页面操作。

为什么要有常驻进程：框架每次工具调用都会新起一个短进程（带约 30 秒硬超时），若每次都重新
启动浏览器，登录态、页面进度、元素 ref 编号都保不住，需要登录的页面根本没法测。所以浏览器
只启动一次、常驻后台；每个工具调用只是朝它发一条本地 HTTP 请求（见 browser.py）。

文件为什么以下划线开头：框架扫描 config/skill/scripts/ 下的 .py 时会跳过下划线开头的文件，
因此它不会被当成独立工具，只是 browser.py 的私有后端（由 browser.py 以后台无窗口方式拉起）。

协议：POST http://127.0.0.1:<port>/rpc，请求头 X-Clara-Token 校验；请求体形如
{"op": "click", "spec": {...}, "budget_ms": 20000}，响应为 JSON。GET /health 用作就绪探针。
端口与令牌写在工作目录 .clara/browser/session.json，只监听回环地址，故仅本机可访问。

日志：写 <工作目录>/.clara/browser/host.log；本文件不向 stdout 输出任何内容（stdout 由调用方
用来读取结果，混入其它内容会破坏协议）。
"""

import json
import os
import re
import sys
import time
import traceback

# 元素 ref 标记属性：snapshot 时把 ref 写到 DOM 上，后续 target="ref=e3" 就能用 CSS 精确定位。
REF_ATTR = "data-clara-ref"
# 单个用例文件的步数上限：单次调用有 30 秒硬上限，超长的用例应当拆开。
CASE_MAX_STEPS = 200

# 单次动作 / 导航的默认超时（毫秒）。实际还会被「本轮预算」再夹一次，保证在框架硬超时内返回。
DEFAULT_ACTION_TIMEOUT_MS = 8000
DEFAULT_NAV_TIMEOUT_MS = 15000

# 每页保留的控制台 / 网络 / 对话框事件条数上限。
EVENT_LIMIT = 200

# 页面结构采集脚本：把可交互元素抽成「ref + 角色 + 名称 + 状态」的清单。
# 为什么不直接回 HTML：整页 HTML 动辄几万 token，模型只看得到噪声；这里只给能操作的东西。
SNAPSHOT_JS = r"""
(cfg) => {
  const refAttr = cfg.refAttr;
  const maxText = cfg.maxText || 120;
  const clip = (value, limit) => {
    const text = (value === null || value === undefined ? "" : String(value)).replace(/\s+/g, " ").trim();
    return text.length > limit ? text.slice(0, limit) + "..." : text;
  };
  const isVisible = (el) => {
    if (!el.isConnected) return false;
    if (typeof el.checkVisibility === "function") {
      try {
        return el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
      } catch (error) {
        // 参数不被支持时退回下面的手写判断
      }
    }
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    const style = window.getComputedStyle(el);
    return style.visibility !== "hidden" && style.display !== "none" && style.opacity !== "0";
  };
  const labelOf = (el) => {
    if (!el.id) return "";
    const label = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
    return label ? label.innerText : "";
  };
  const roleOf = (el) => {
    const explicit = el.getAttribute("role");
    if (explicit) return explicit.trim();
    const tag = el.tagName.toLowerCase();
    if (tag === "a") return "link";
    if (tag === "button" || tag === "summary") return "button";
    if (tag === "select") return el.multiple ? "listbox" : "combobox";
    if (tag === "textarea") return "textbox";
    if (tag === "input") {
      const type = (el.getAttribute("type") || "text").toLowerCase();
      if (type === "submit" || type === "button" || type === "reset" || type === "image") return "button";
      if (type === "checkbox") return "checkbox";
      if (type === "radio") return "radio";
      if (type === "search") return "searchbox";
      return "textbox";
    }
    return tag;
  };
  const nameOf = (el) => {
    const aria = el.getAttribute("aria-label");
    if (aria) return clip(aria, maxText);
    const labelled = el.getAttribute("aria-labelledby");
    if (labelled) {
      const parts = labelled.split(/\s+/).map((id) => {
        const node = document.getElementById(id);
        return node ? node.innerText : "";
      }).join(" ");
      if (parts.trim()) return clip(parts, maxText);
    }
    const tag = el.tagName.toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea") {
      const label = labelOf(el);
      if (label) return clip(label, maxText);
      if (tag === "select") {
        const texts = Array.from(el.options).map((option) => option.text).join(" ");
        return clip(texts || el.getAttribute("name") || "", maxText);
      }
    }
    if (tag === "input") {
      const type = (el.getAttribute("type") || "text").toLowerCase();
      if ((type === "submit" || type === "button" || type === "reset") && el.value) return clip(el.value, maxText);
      const placeholder = el.getAttribute("placeholder");
      if (placeholder) return clip(placeholder, maxText);
      if (el.value) return clip(el.value, maxText);
      return clip(el.getAttribute("name") || type, maxText);
    }
    const text = el.innerText || el.textContent || "";
    if (text.trim()) return clip(text, maxText);
    return clip(el.getAttribute("title") || el.getAttribute("alt") || el.getAttribute("name") || "", maxText);
  };
  document.querySelectorAll("[" + refAttr + "]").forEach((el) => el.removeAttribute(refAttr));
  const nodes = Array.from(document.querySelectorAll(
    "a[href],button,input,select,textarea,summary,[role],[onclick],[contenteditable=true],[tabindex]"));
  const elements = [];
  let hiddenSkipped = 0;
  let index = 0;
  for (const el of nodes) {
    if (elements.length >= cfg.maxElements) break;
    const shown = isVisible(el);
    if (!shown && !cfg.includeHidden) {
      hiddenSkipped += 1;
      continue;
    }
    index += 1;
    const ref = "e" + index;
    el.setAttribute(refAttr, ref);
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute("type") || "").toLowerCase();
    const item = { ref: ref, tag: tag, role: roleOf(el), name: nameOf(el), visible: shown, disabled: el.disabled === true };
    if (tag === "input" || tag === "textarea") {
      item.type = type || "text";
      if (type === "checkbox" || type === "radio") item.checked = el.checked === true;
      else if (el.value) item.value = clip(el.value, 60);
      const placeholder = el.getAttribute("placeholder");
      if (placeholder) item.placeholder = clip(placeholder, 60);
    }
    if (tag === "select") {
      item.options = Array.from(el.options).slice(0, 12)
        .map((option) => clip(option.text, 40) + (option.selected ? "(选中)" : ""));
    }
    if (tag === "a" && el.getAttribute("href")) item.href = clip(el.getAttribute("href"), 120);
    elements.push(item);
  }
  const headings = Array.from(document.querySelectorAll("h1,h2,h3,h4,h5,h6")).slice(0, 24)
    .map((el) => ({ level: Number(el.tagName.charAt(1)), text: clip(el.textContent, 80) }))
    .filter((item) => item.text);
  const iframes = Array.from(document.querySelectorAll("iframe")).slice(0, 8)
    .map((el) => clip(el.getAttribute("src") || "(同源内嵌文档，未给出 src)", 120));
  return {
    url: location.href,
    title: document.title,
    elements: elements,
    hidden_skipped: hiddenSkipped,
    interactive_total: nodes.length,
    truncated: elements.length >= cfg.maxElements,
    headings: headings,
    iframes: iframes,
    scroll: {
      x: Math.round(window.scrollX),
      y: Math.round(window.scrollY),
      height: Math.round(document.body ? document.body.scrollHeight : 0)
    }
  };
}
"""

class ActionError(Exception):
    """动作失败：message 直接回给模型，hint 是可选的纠正建议（也会回给模型）。"""

    def __init__(self, message, hint=None):
        Exception.__init__(self, message)
        self.hint = hint


class Logger(object):
    """把守护进程日志按行追加到 host.log（带时间戳），不打印到 stdout。"""

    def __init__(self, path):
        self.path = path

    def __call__(self, message):
        line = "%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), message)
        try:
            with open(self.path, "a", encoding="utf-8") as handle:
                handle.write(line)
        except OSError:
            pass


def _number(value, default):
    """宽容地解析数值入参：模型可能写 3000、"3000" 或 3.5。"""
    try:
        if value is None or value == "":
            return float(default)
        return float(value)
    except (TypeError, ValueError):
        return float(default)


def _bool(value, default=False):
    """宽容地解析布尔入参：模型可能写 true、"true"、1、"1"、"yes"。"""
    if value is None or value == "":
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "y", "on", "是"):
        return True
    if text in ("0", "false", "no", "n", "off", "否"):
        return False
    return default


def clip_text(value, limit):
    """压掉换行与多余空白并截断，避免把 Playwright 的长报错整段塞进结果。"""
    text = "" if value is None else str(value)
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[:limit] + "..."


def unquote(value):
    text = "" if value is None else str(value).strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("\"", "'"):
        return text[1:-1]
    return text


def _safe_title(page):
    try:
        return page.title()
    except Exception:
        return ""


def split_role(value):
    """解析 role=button[name=提交] / role=button::提交 / role=button，返回 (角色, 名称或 None)。"""
    text = (value or "").strip()
    matched = re.match(r"^([A-Za-z]+)\s*\[name\s*=\s*(.+?)\]\s*$", text)
    if matched:
        return matched.group(1), unquote(matched.group(2))
    if "::" in text:
        role, _, name = text.partition("::")
        return role.strip(), unquote(name)
    return text, None


def _jsonable(value, limit):
    """把 JS 返回值转成可 JSON 序列化的形式；过长时退化为截断文本。"""
    try:
        text = json.dumps(value, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        return str(value)[:limit]
    if len(text) <= limit:
        try:
            return json.loads(text)
        except ValueError:
            return text
    return text[:limit] + "...（已截断，原始长度 %d 字符）" % len(text)


def _parse_viewport(value):
    """解析 viewport 入参：1440x900 / {"width":1440,"height":900} / none。"""
    if value is None or value == "":
        return {"width": 1440, "height": 900}
    if isinstance(value, dict):
        return {"width": int(_number(value.get("width"), 1440)), "height": int(_number(value.get("height"), 900))}
    text = str(value).strip().lower()
    if text in ("none", "0", "default"):
        return None
    matched = re.match(r"^(\d+)\s*[x*]\s*(\d+)$", text)
    if matched:
        return {"width": int(matched.group(1)), "height": int(matched.group(2))}
    return {"width": 1440, "height": 900}


def element_expression(expression):
    """元素级 eval 的表达式收尾：裸表达式包成 (element) => ...，并把 el. 当作 element.。"""
    text = (expression or "").strip()
    if "=>" in text or text.startswith("function"):
        return text
    return "element => (%s)" % re.sub(r"\bel\.", "element.", text)

def normalize_url(raw, work_dir):
    """把 url 参数补全成可导航地址：裸端口 -> http://127.0.0.1:端口，相对路径 -> 绝对 file:// 地址。"""
    text = "" if raw is None else str(raw).strip()
    if not text:
        raise ActionError("op=open 缺少 url",
                          "可以给完整地址 http://localhost:5174，也可以只给端口 5174，或本地文件路径 index.html")
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*://", text):
        return text
    matched = re.match(r"^:?(\d{2,5})(/.*)?$", text)
    if matched:
        return "http://127.0.0.1:%s/%s" % (matched.group(1), (matched.group(2) or "").lstrip("/"))
    if text.startswith("localhost") or text.startswith("127.0.0.1"):
        return "http://" + text
    candidate = text if os.path.isabs(text) else os.path.join(work_dir, text)
    if os.path.exists(candidate):
        return path_to_url(candidate)
    return text


def path_to_url(path):
    """把本地路径转成 file:// 地址（Windows 盘符与空格都能正确处理）。"""
    text = os.path.abspath(path).replace("\\", "/")
    if not text.startswith("/"):
        text = "/" + text
    return "file://" + text


def substitute_vars(value, variables, where):
    """把用例里的 {{变量名}} 换成 vars 里的值；变量没定义就直接报错，别带着占位符去操作页面。"""
    if isinstance(value, str):
        def replace(match):
            key = match.group(1).strip()
            if key not in variables:
                raise ActionError("用例引用了未定义的变量 {{%s}}（%s）" % (key, where),
                                  "可用变量：%s；在用例文件的 vars 里补齐即可"
                                  % (", ".join(sorted(variables)) or "（没有定义任何变量）"))
            return str(variables[key])
        return re.sub(r"\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}", replace, value)
    if isinstance(value, list):
        return [substitute_vars(item, variables, where) for item in value]
    if isinstance(value, dict):
        return dict((key, substitute_vars(item, variables, where)) for key, item in value.items())
    return value


def load_case_steps(work_dir, raw):
    """读用例文件，返回 (steps, 用例信息)；文件坏或结构不对时抛 ActionError（含怎么改的提示）。

    用例 = 可反复回放的「一次测试」：文件内容要么直接是动作数组，要么是
    {"name": "...", "vars": {...}, "steps": [...]}；字符串里的 {{变量名}} 用 vars 替换，
    因此同一份用例能换环境（如基地址）回放。
    """
    path = resolve_case_path(work_dir, raw)
    if not os.path.isfile(path):
        raise ActionError("用例文件不存在：%s" % path,
                          "路径按当前工作目录解析；确认文件名与位置，或先用 steps 直接跑一遍")
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as error:
        raise ActionError("用例文件读不出来：%s" % clip_text(error, 200),
                          "用例文件用 JSON 写：结构就是 op=run 的步骤数组，或 {\"name\":..,\"vars\":{..},\"steps\":[..]}")
    if isinstance(data, dict):
        steps = data.get("steps")
        variables = data.get("vars") or {}
        name = str(data.get("name") or os.path.basename(path))
    else:
        steps, variables, name = data, {}, os.path.basename(path)
    if not isinstance(variables, dict):
        raise ActionError("用例文件里的 vars 必须是对象（变量名 -> 值）：%s" % path,
                          "写成 \"vars\": {\"base_url\": \"http://localhost:5174\"}")
    if not isinstance(steps, list) or not steps:
        raise ActionError("用例文件里没有 steps 数组：%s" % path,
                          "写成 {\"steps\": [{\"op\": \"open\", \"url\": \"{{base_url}}\"}, ...]}")
    if len(steps) > CASE_MAX_STEPS:
        raise ActionError("用例步数过多（%d 步，上限 %d）：%s" % (len(steps), CASE_MAX_STEPS, path),
                          "单次调用有 30 秒硬上限，长用例请拆成几份，或分多次调用接着跑")
    return (substitute_vars(steps, variables, path),
            {"case": path, "name": name, "vars": sorted(variables), "steps": len(steps)})


def resolve_case_path(work_dir, raw):
    """解析用例路径：绝对路径原样用，相对路径按当前工作目录解析。"""
    text = os.path.expanduser(str(raw).strip())
    return text if os.path.isabs(text) else os.path.abspath(os.path.join(work_dir, text))


class Ctx(object):
    """单次请求的时间预算：所有 Playwright 超时都从剩余预算里取，避免超过框架的硬超时。"""

    def __init__(self, budget_ms):
        self.started = time.time()
        self.budget_ms = int(max(1000.0, _number(budget_ms, 20000)))
        self.deadline = self.started + self.budget_ms / 1000.0

    def left_ms(self):
        return (self.deadline - time.time()) * 1000.0

    def timeout_ms(self, default_ms):
        return int(max(300.0, min(float(default_ms), self.left_ms())))

    def guard(self, what):
        if self.left_ms() < 400.0:
            raise ActionError("本轮时间预算（%dms）已用完，%s 未执行" % (self.budget_ms, what),
                              "把动作拆成多次调用：先做一部分，再用 op=status / op=snapshot 接着做")


class BrowserSession(object):
    """一个工作目录对应一个浏览器会话：Playwright 连接 + 浏览器 + 上下文 + 标签页 + 事件缓冲。"""

    def __init__(self, work_dir, state_dir, options, log):
        self.work_dir = work_dir
        self.state_dir = state_dir
        self.shots_dir = os.path.join(state_dir, "shots")
        self._log = log
        self._playwright = None
        self._browser = None
        self._context = None
        self._current_index = -1
        self._events = {}
        self._settings = {
            "headless": options.get("headless", True),
            "channel": options.get("channel") or "",
            "executable_path": options.get("executable_path") or "",
            "viewport": options.get("viewport"),
            "locale": options.get("locale") or "",
            "user_agent": options.get("user_agent") or "",
            "slow_mo": _number(options.get("slow_mo"), 0),
            "dialog": options.get("dialog") or "accept",
            "action_timeout_ms": _number(options.get("action_timeout_ms"), DEFAULT_ACTION_TIMEOUT_MS),
            "nav_timeout_ms": _number(options.get("nav_timeout_ms"), DEFAULT_NAV_TIMEOUT_MS),
        }
        self.started_at = time.time()
        self.last_activity = time.time()
        self.stopped = False

    def log(self, message):
        self._log("[pid %d] %s" % (os.getpid(), message))

    # ---------- 状态查询 ----------

    def settings(self):
        return dict(self._settings)

    def browser_running(self):
        return self._browser is not None and self._browser.is_connected()

    def browser_version(self):
        """当前浏览器版本，未启动时为空串（用来区分驱动的是内置 chromium 还是系统 Chrome / Edge）。"""
        if not self.browser_running():
            return ""
        try:
            return self._browser.version
        except Exception:
            return ""

    def describe(self):
        return {
            "running": True,
            "work_dir": self.work_dir,
            "state_dir": self.state_dir,
            "screenshots_dir": self.shots_dir,
            "browser_running": self.browser_running(),
            "browser_version": self.browser_version(),
            "settings": self.settings(),
            "uptime_ms": int((time.time() - self.started_at) * 1000),
        }

    def events_for(self, page):
        return self._events.get(id(page)) or {}

    def events_summary(self, page, limit=8):
        """把页面事件压成结果里的一小段：控制台报错、页面异常、失败请求、对话框。"""
        events = self.events_for(page)
        summary = {}
        errors = [item for item in events.get("console", []) if item.get("type") == "error"]
        warnings = [item for item in events.get("console", []) if item.get("type") == "warning"]
        page_errors = events.get("errors", [])
        failed = events.get("failed", [])
        dialogs = events.get("dialogs", [])
        if errors:
            summary["console_errors"] = errors[-limit:]
            summary["console_error_count"] = len(errors)
        if page_errors:
            summary["page_errors"] = page_errors[-limit:]
        if failed:
            summary["failed_requests"] = failed[-limit:]
            summary["failed_request_count"] = len(failed)
        if dialogs:
            summary["dialogs"] = dialogs[-limit:]
        if warnings:
            summary["console_warnings"] = warnings[-3:]
        return summary

    # ---------- 浏览器 / 页面生命周期 ----------

    def ensure_playwright(self):
        if self._playwright is None:
            try:
                from playwright.sync_api import sync_playwright
            except ImportError as error:
                raise ActionError("当前 Python 没有安装 playwright：%s" % error,
                                  "先执行 python -m pip install playwright，再执行 python -m playwright install chromium")
            self._playwright = sync_playwright().start()
            self.log("Playwright 已启动")
        return self._playwright

    def apply_settings(self, spec):
        """按入参调整浏览器启动参数；参数变化且浏览器已在运行时先关闭（登录态与当前页面会丢）。"""
        changed = []
        headless = spec.get("headless")
        if headless is not None and headless != "":
            value = _bool(headless, self._settings["headless"])
            if value != self._settings["headless"]:
                self._settings["headless"] = value
                changed.append("headless=%s" % value)
        for field in ("channel", "executable_path", "locale", "user_agent"):
            value = spec.get(field)
            if value:
                text = str(value).strip()
                if text != self._settings[field]:
                    self._settings[field] = text
                    changed.append("%s=%s" % (field, text))
        if spec.get("viewport") is not None and spec.get("viewport") != "":
            value = _parse_viewport(spec.get("viewport"))
            if value != self._settings["viewport"]:
                self._settings["viewport"] = value
                changed.append("viewport=%sx%s" % (value.get("width"), value.get("height")) if value else "viewport=默认")
        if spec.get("slow_mo") is not None and spec.get("slow_mo") != "":
            self._settings["slow_mo"] = _number(spec.get("slow_mo"), self._settings["slow_mo"])
        dialog = spec.get("dialog")
        if dialog:
            text = str(dialog).strip().lower()
            if text in ("accept", "dismiss"):
                self._settings["dialog"] = text
        if changed and self._browser is not None:
            self.close_browser()
            note = "浏览器启动参数已变更（%s），已重启浏览器：当前页面与登录态已丢失" % "，".join(changed)
            self.log(note)
            return note
        return None

    def close_browser(self):
        browser = self._browser
        self._browser = None
        self._context = None
        self._events = {}
        self._current_index = -1
        if browser is None:
            return
        try:
            browser.close()
        except Exception as error:
            self.log("关闭浏览器失败：%s" % error)
        self.log("浏览器已关闭")

    def ensure_browser(self):
        if self.browser_running():
            return self._browser
        if self._browser is not None:
            self.log("浏览器连接已断开，重新启动")
            self.close_browser()
        playwright = self.ensure_playwright()
        launch = {"headless": self._settings["headless"]}
        if self._settings["executable_path"]:
            launch["executable_path"] = self._settings["executable_path"]
        elif self._settings["channel"]:
            launch["channel"] = self._settings["channel"]
        if self._settings["slow_mo"]:
            launch["slow_mo"] = self._settings["slow_mo"]
        started = time.time()
        try:
            self._browser = playwright.chromium.launch(**launch)
        except Exception as error:
            self._browser = None
            raise ActionError("浏览器启动失败：%s" % clip_text(error, 300), self.launch_hint())
        context_options = {}
        if self._settings["viewport"]:
            context_options["viewport"] = self._settings["viewport"]
        if self._settings["locale"]:
            context_options["locale"] = self._settings["locale"]
        if self._settings["user_agent"]:
            context_options["user_agent"] = self._settings["user_agent"]
        self._context = self._browser.new_context(**context_options)
        self._context.on("page", self._on_new_page)
        self.log("浏览器已启动（%dms）headless=%s channel=%s" % (
            int((time.time() - started) * 1000), self._settings["headless"], self._settings["channel"] or "内置 chromium"))
        return self._browser

    def launch_hint(self):
        return ("内置 chromium 没装好时先执行 python -m playwright install chromium；"
                "机器上已有 Chrome / Edge 时也可以直接写 channel=\"chrome\" 或 channel=\"msedge\"")

    def _on_new_page(self, page):
        """应用自己打开的新标签页（如 target=_blank）也要挂事件监听，否则看不到它的报错。"""
        self.attach_page(page)
        self.log("检测到新标签页：%s" % clip_text(page.url, 120))

    def attach_page(self, page):
        if id(page) in self._events:
            return
        events = {"console": [], "errors": [], "failed": [], "dialogs": []}
        self._events[id(page)] = events
        page.on("console", lambda message, buffer=events: self._on_console(buffer, message))
        page.on("pageerror", lambda error, buffer=events: self._on_pageerror(buffer, error))
        page.on("requestfailed", lambda request, buffer=events: self._on_request_failed(buffer, request))
        page.on("dialog", lambda dialog, buffer=events: self._on_dialog(buffer, dialog))
        page.on("framenavigated", lambda frame, buffer=events: self._on_navigated(buffer, frame))

    def _on_console(self, events, message):
        try:
            kind = message.type
        except Exception:
            kind = "log"
        self._push(events["console"], {"type": kind, "text": clip_text(getattr(message, "text", ""), 300)})

    def _on_pageerror(self, events, error):
        self._push(events["errors"], {"text": clip_text(error, 300)})

    def _on_request_failed(self, events, request):
        failure = getattr(request, "failure", None)
        reason = failure.get("errorText") if isinstance(failure, dict) else failure
        self._push(events["failed"], {"method": getattr(request, "method", ""),
                                      "url": clip_text(getattr(request, "url", ""), 160),
                                      "reason": clip_text(reason, 120)})

    def _on_navigated(self, events, frame):
        """主文档跳转后清空事件缓冲：让「控制台有没有报错」反映当前这份文档，而不是整段会话累计。"""
        try:
            page = frame.page
        except Exception:
            return
        if frame == page.main_frame:
            events["console"] = []
            events["errors"] = []
            events["failed"] = []
            events["dialogs"] = []

    def _on_dialog(self, events, dialog):
        self._push(events["dialogs"], {"type": getattr(dialog, "type", ""),
                                       "message": clip_text(getattr(dialog, "message", ""), 200)})
        try:
            if self._settings["dialog"] == "dismiss":
                dialog.dismiss()
            else:
                dialog.accept()
        except Exception as error:
            self.log("处理对话框失败：%s" % error)

    @staticmethod
    def _push(target, entry):
        target.append(entry)
        if len(target) > EVENT_LIMIT:
            del target[0:len(target) - EVENT_LIMIT]

    def context(self):
        return self._context

    def current_index(self):
        return self._current_index

    def set_current_index(self, index):
        self._current_index = int(index)

    def pages(self):
        return list(self._context.pages) if self._context is not None else []

    def current_page(self):
        if self._context is None:
            raise ActionError("浏览器还没启动", "先调用一次 op=open 打开页面")
        pages = list(self._context.pages)
        if not pages:
            page = self._context.new_page()
            self.attach_page(page)
            pages = list(self._context.pages)
        if self._current_index < 0 or self._current_index >= len(pages):
            self._current_index = len(pages) - 1
        return pages[self._current_index]

    def ensure_page(self, new=False):
        self.ensure_browser()
        if new or not self._context.pages:
            page = self._context.new_page()
            self.attach_page(page)
            self._current_index = len(self._context.pages) - 1
        return self.current_page()

    def describe_pages(self):
        items = []
        for position, page in enumerate(self.pages()):
            try:
                url = page.url
                title = _safe_title(page)
            except Exception:
                url, title = "", ""
            items.append({"index": position, "url": clip_text(url, 160), "title": clip_text(title, 80),
                          "active": position == self._current_index})
        return items

    def page_state(self, page):
        state = {"url": page.url, "title": _safe_title(page)}
        try:
            size = page.viewport_size
        except Exception:
            size = None
        if size:
            state["viewport"] = size
        state["tab_count"] = len(self.pages()) or 1
        return state

    # ---------- 元素定位 / 快照 / 结果组装 ----------

    def resolve(self, page, target, index=None, required=True, keep_all=False):
        """把 target 字符串解析成 Playwright locator，返回 (locator, 匹配数, 提示)。

        target 写法：ref=e3（上一次 snapshot 的编号，最稳）、css=#id、text=登录、
        role=button[name=提交]、label=用户名、placeholder=请输入、testid=submit、xpath=//div；
        不带前缀时按 CSS 处理。required=False 时匹配 0 个也不报错（供 wait / expect 轮询用）。
        """
        text = "" if target is None else str(target).strip()
        if not text:
            raise ActionError("缺少 target：要操作哪个元素？",
                              "先 op=snapshot 看页面元素，再用 target=\"ref=e3\" 或 target=\"text=登录\" 指定")
        kind, separator, value = text.partition("=")
        kind = kind.strip().lower()
        if not separator or kind not in ("ref", "css", "text", "role", "label", "placeholder",
                                         "testid", "alt", "title", "xpath"):
            kind, value = "css", text
        value = unquote(value)
        if kind == "ref":
            ref = value.lstrip("@#").strip()
            locator = page.locator("[%s=\"%s\"]" % (REF_ATTR, ref))
            matched = locator.count()
            if matched == 0 and required:
                raise ActionError("ref=%s 定位不到元素（页面可能已跳转、重新渲染或元素已消失）" % ref,
                                  "先 op=snapshot 重新取一次 ref，再用新的 ref 操作")
            return locator, matched, None
        try:
            if kind == "css":
                locator = page.locator(value)
            elif kind == "xpath":
                locator = page.locator("xpath=" + value)
            elif kind == "text":
                exact = len(value) >= 2 and value.startswith("\"") and value.endswith("\"")
                locator = page.get_by_text(value[1:-1] if exact else value, exact=exact)
            elif kind == "role":
                role, name = split_role(value)
                locator = page.get_by_role(role, name=name) if name else page.get_by_role(role)
            elif kind == "label":
                locator = page.get_by_label(value)
            elif kind == "placeholder":
                locator = page.get_by_placeholder(value)
            elif kind == "testid":
                locator = page.get_by_test_id(value)
            elif kind == "alt":
                locator = page.get_by_alt_text(value)
            else:
                locator = page.get_by_title(value)
        except Exception as error:
            raise ActionError("target 写法无法解析：%s（%s）" % (text, clip_text(error, 160)),
                              "支持：ref=e3 / css=#id / text=登录 / role=button[name=提交] / label=用户名 / "
                              "placeholder=请输入 / testid=submit / xpath=//div")
        matched = locator.count()
        if matched == 0 and required:
            raise ActionError("没有元素匹配 target=%s" % text,
                              "先 op=snapshot 确认页面上有该元素（注意元素可能在 iframe 内或当前不可见），再换 target")
        note = None
        if matched > 1:
            note = ("target=%s 匹配到 %d 个元素，已取第一个可见项；可用 index 指定第几个，或换更精确的 target"
                    % (text, matched))
        if keep_all:
            return locator, matched, note
        if index is None:
            chosen = self.first_visible(locator)
        else:
            position = int(_number(index, 0))
            if position >= matched:
                raise ActionError("index=%d 超出范围（target=%s 共匹配 %d 个）" % (position, text, matched))
            chosen = locator.nth(position)
        return chosen, matched, note

    @staticmethod
    def first_visible(locator):
        """匹配到多个时优先返回可见的那个，避免 Playwright 严格模式直接报错。"""
        count = locator.count()
        if count <= 1:
            return locator.first
        for position in range(min(count, 20)):
            candidate = locator.nth(position)
            try:
                if candidate.is_visible():
                    return candidate
            except Exception:
                continue
        return locator.first

    def build_snapshot(self, page, spec):
        config = {
            "refAttr": REF_ATTR,
            "maxElements": int(_number(spec.get("max_elements"), 120)),
            "includeHidden": _bool(spec.get("include_hidden"), False),
            "maxText": int(_number(spec.get("max_text"), 120)),
        }
        started = time.time()
        try:
            data = page.evaluate(SNAPSHOT_JS, config)
        except Exception as error:
            raise ActionError("读取页面结构失败（页面可能正在跳转或已被关闭）：%s" % clip_text(error, 300),
                              "稍等片刻再试；页面已跳转的话先 op=open 重新打开目标地址")
        elements = data.get("elements") or []
        snapshot = {
            "url": data.get("url"),
            "title": data.get("title"),
            "elements": elements,
            "element_count": len(elements),
            "hidden_skipped": data.get("hidden_skipped"),
            "truncated": bool(data.get("truncated")),
            "headings": data.get("headings") or [],
            "scroll": data.get("scroll") or {},
            "elapsed_ms": int((time.time() - started) * 1000),
        }
        if data.get("iframes"):
            snapshot["iframes"] = data["iframes"]
            snapshot["iframe_note"] = "清单只列主文档的元素，iframe 内部元素未列出"
        snapshot.update(self.events_summary(page))
        return snapshot

    def action_error(self, what, error, note=None):
        """把 Playwright 的长报错压成一句，并补上「多半是什么原因」的提示。"""
        message = clip_text(error, 300)
        hint = note
        lowered = message.lower()
        if "timeout" in lowered:
            extra = ("元素可能不可见 / 不可点（被遮挡或 disabled），或页面还在加载："
                     "先 op=snapshot 看 visible、disabled，再决定 op=wait 还是换 target")
            hint = (hint + "；" + extra) if hint else extra
        elif "strict mode violation" in lowered:
            hint = (hint + "；" if hint else "") + "多个元素匹配，用 index 指定或用更精确的 target（如 ref=）"
        elif "not attached" in lowered or "element is not attached" in lowered:
            hint = (hint + "；" if hint else "") + "元素已从页面移除，重新 op=snapshot 取 ref"
        return ActionError("%s失败：%s" % (what, message), hint)

    def after_action(self, page, operation, spec, before_url, matched, note, ctx):
        """动作之后的统一收尾：可选等待、是否跳转、控制台报错，必要时带一份新快照。"""
        if spec.get("wait_ms"):
            page.wait_for_timeout(int(min(_number(spec.get("wait_ms"), 0), max(0.0, ctx.left_ms()))))
        if spec.get("wait_for"):
            locator, _, _ = self.resolve(page, spec.get("wait_for"), spec.get("wait_index"), required=False)
            try:
                locator.wait_for(state=str(spec.get("wait_state") or "visible"),
                                 timeout=ctx.timeout_ms(self._settings["action_timeout_ms"]))
            except Exception as error:
                raise ActionError("操作后等待 wait_for=%s 未满足：%s" % (spec.get("wait_for"), clip_text(error, 200)),
                                  "把 wait_for 换成更稳的标志（如跳转后的标题文字），或先 op=snapshot 看页面现状")
        result = {"ok": True, "op": operation, "matched": matched}
        result.update(self.page_state(page))
        navigated = page.url != before_url
        if navigated:
            result["navigated"] = True
        if note:
            result["note"] = note
        result.update(self.events_summary(page))
        if _bool(spec.get("snapshot"), navigated):
            result["snapshot"] = self.build_snapshot(page, {"max_elements": spec.get("max_elements") or 60})
        return result

    def shot_path(self, name):
        safe = re.sub(r"[^0-9A-Za-z_.\-\u4e00-\u9fff]+", "-", str(name or "shot")).strip("-") or "shot"
        stamp = time.strftime("%Y%m%d-%H%M%S")
        return os.path.join(self.shots_dir, "%s-%s-%03d.png" % (stamp, safe, int(time.time() * 1000) % 1000))

    def failure_result(self, operation, message, hint, ctx):
        """失败结果：带上当前地址、自动截图与一份新快照，方便模型自我修正。"""
        result = {"ok": False, "error": message}
        if hint:
            result["hint"] = hint
        page = None
        try:
            page = self.current_page()
        except Exception:
            page = None
        if page is None:
            return result
        try:
            result.update(self.page_state(page))
        except Exception:
            pass
        if ctx.left_ms() > 2500.0:
            try:
                path = self.shot_path("fail-" + str(operation))
                page.screenshot(path=path)
                result["screenshot"] = path
            except Exception as error:
                self.log("失败截图未成功：%s" % error)
        if ctx.left_ms() > 900.0:
            try:
                result["snapshot"] = self.build_snapshot(page, {"max_elements": 60})
            except Exception as error:
                self.log("失败快照未成功：%s" % error)
        return result

    def shutdown(self):
        self.close_browser()
        if self._playwright is not None:
            try:
                self._playwright.stop()
            except Exception as error:
                self.log("停止 Playwright 失败：%s" % error)
            self._playwright = None

    def handle(self, request):
        """处理一条 RPC：分派到对应 op，异常一律转成结构化结果（不向客户端抛栈）。"""
        operation = str(request.get("op") or "").strip().lower()
        spec = request.get("spec")
        if not isinstance(spec, dict):
            spec = {}
        ctx = Ctx(request.get("budget_ms"))
        self.last_activity = time.time()
        handler = OPS.get(operation)
        self.log("op=%s spec=%s" % (operation, clip_text(json.dumps(spec, ensure_ascii=False), 300)))
        if handler is None:
            return {"ok": False, "op": operation, "error": "不支持的 op：%s" % operation, "available": sorted(OPS)}
        started = time.time()
        try:
            result = handler(self, spec, ctx)
            if not isinstance(result, dict):
                result = {"ok": True, "result": result}
            result.setdefault("ok", True)
        except ActionError as error:
            result = self.failure_result(operation, str(error), error.hint, ctx)
        except Exception as error:
            self.log(traceback.format_exc())
            result = self.failure_result(operation, "%s: %s" % (type(error).__name__, clip_text(error, 300)), None, ctx)
        result["op"] = operation
        result["duration_ms"] = int((time.time() - started) * 1000)
        if operation == "shutdown":
            self.stopped = True
        return result

# 可用 op 的注册表：键是请求体里的 op 字段，值是下面的 op_xxx 函数。
OPS = {}


def op(name):
    """把函数注册成可调用的 op。"""

    def decorate(function):
        OPS[name] = function
        return function

    return decorate


def evaluate_expect(session, page, state, expected, locator):
    """断言求值：返回 (是否通过, 实际值)。不抛异常——不通过也要把实际值回给模型。"""
    if state in ("visible", "hidden"):
        try:
            visible = locator.is_visible()
        except Exception as error:
            return False, "查询可见性失败：%s" % clip_text(error, 120)
        return (visible if state == "visible" else not visible), ("可见" if visible else "不可见")
    if state in ("attached", "detached"):
        try:
            count = locator.count()
        except Exception as error:
            return False, "查询匹配数失败：%s" % clip_text(error, 120)
        return (count > 0 if state == "attached" else count == 0), "匹配 %d 个元素" % count
    if state in ("enabled", "disabled"):
        try:
            enabled = locator.is_enabled()
        except Exception as error:
            return False, "查询可用状态失败：%s" % clip_text(error, 120)
        return (enabled if state == "enabled" else not enabled), ("可用" if enabled else "不可用")
    if state == "count":
        try:
            count = locator.count()
        except Exception as error:
            return False, "查询匹配数失败：%s" % clip_text(error, 120)
        return count == int(_number(expected, 0)), "匹配 %d 个元素" % count
    if state == "text_contains":
        try:
            text = locator.first.inner_text(timeout=500)
        except Exception as error:
            return False, "读取文字失败：%s" % clip_text(error, 120)
        return str(expected) in text, clip_text(text, 200)
    if state == "value_equals":
        try:
            value = locator.first.input_value(timeout=500)
        except Exception as error:
            return False, "读取输入值失败：%s" % clip_text(error, 120)
        return value == str(expected), value
    if state == "url_contains":
        return str(expected) in page.url, page.url
    if state == "title_contains":
        title = _safe_title(page)
        return str(expected) in title, title
    if state in ("no_console_errors", "has_console_errors"):
        events = session.events_for(page)
        errors = [item for item in events.get("console", []) if item.get("type") == "error"]
        errors = errors + list(events.get("errors", []))
        passed = len(errors) == 0 if state == "no_console_errors" else len(errors) > 0
        return passed, "控制台/页面错误 %d 条：%s" % (
            len(errors), clip_text(json.dumps(errors[-3:], ensure_ascii=False), 240))
    return False, "不支持的 state=%s" % state


@op("status")
def op_status(session, spec, ctx):
    result = {"ok": True}
    result.update(session.describe())
    result["pages"] = session.describe_pages()
    if session.context() is not None and session.pages():
        page = session.current_page()
        result.update(session.page_state(page))
        result.update(session.events_summary(page, limit=5))
    result["hint"] = "会话常驻后台进程：同一工作目录的后续调用接着这个会话继续（登录态与页面进度都保留）"
    return result


@op("open")
def op_open(session, spec, ctx):
    url = normalize_url(spec.get("url") or spec.get("target"), session.work_dir)
    ctx.guard("打开页面")
    note = session.apply_settings(spec)
    page = session.ensure_page(_bool(spec.get("new_page"), False))
    wait_until = str(spec.get("wait_until") or "domcontentloaded")
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["nav_timeout_ms"]))
    started = time.time()
    try:
        response = page.goto(url, wait_until=wait_until, timeout=timeout)
    except Exception as error:
        raise ActionError("打开 %s 失败：%s" % (url, clip_text(error, 300)),
                          "确认地址可访问（本地服务是否已启动？）；页面加载慢可加大 timeout_ms，"
                          "但单次调用总时长约 30 秒上限，open 本身最多用掉 budget_ms")
    result = {"ok": True, "requested_url": url,
              "status_code": response.status if response is not None else None,
              "elapsed_ms": int((time.time() - started) * 1000)}
    if note:
        result["note"] = note
    result.update(session.page_state(page))
    result.update(session.events_summary(page))
    if _bool(spec.get("snapshot"), True):
        result["snapshot"] = session.build_snapshot(page, {"max_elements": spec.get("max_elements") or 60,
                                                           "include_hidden": spec.get("include_hidden")})
    if _bool(spec.get("snapshot"), True):
        result["hint"] = "接下来用 target=\"ref=eN\"（上面快照里的编号）或 css= / text= 定位元素"
    else:
        result["hint"] = "本次没取快照：要按 ref 操作就先 op=snapshot 取元素编号，否则请用 css= / text= / label= 等定位"
    return result


@op("snapshot")
def op_snapshot(session, spec, ctx):
    page = session.current_page()
    ctx.guard("读取页面快照")
    result = session.build_snapshot(page, spec)
    result["ok"] = True
    result["hint"] = "用 ref=eN 作为后续 click / fill 的 target；清单不含 iframe 内部元素与隐藏元素"
    return result


@op("text")
def op_text(session, spec, ctx):
    page = session.current_page()
    limit = int(_number(spec.get("max_chars"), 6000))
    target = spec.get("target") or spec.get("selector")
    if target:
        locator, _, note = session.resolve(page, target, spec.get("index"))
        try:
            text = locator.first.inner_text(timeout=ctx.timeout_ms(session.settings()["action_timeout_ms"]))
        except Exception as error:
            raise session.action_error("读取元素文字", error, note)
    else:
        try:
            text = page.inner_text("body", timeout=ctx.timeout_ms(session.settings()["action_timeout_ms"]))
        except Exception as error:
            raise ActionError("读取页面文字失败：%s" % clip_text(error, 300),
                              "页面可能还没加载完，或整个页面没有任何文本节点")
    result = {"ok": True, "text": text[:limit], "truncated": len(text) > limit, "chars": len(text)}
    result.update(session.page_state(page))
    return result


@op("click")
def op_click(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    button = str(spec.get("button") or "left")
    before_url = page.url
    try:
        if _bool(spec.get("double"), False):
            locator.dblclick(timeout=timeout, button=button, force=_bool(spec.get("force"), False))
        else:
            locator.click(timeout=timeout, button=button, force=_bool(spec.get("force"), False))
    except Exception as error:
        raise session.action_error("点击 %s" % spec.get("target"), error, note)
    return session.after_action(page, "click", spec, before_url, matched, note, ctx)


@op("fill")
def op_fill(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    value = spec.get("value")
    value = "" if value is None else str(value)
    before_url = page.url
    try:
        locator.fill(value, timeout=timeout)
        if _bool(spec.get("submit"), False):
            locator.press("Enter", timeout=timeout)
        elif spec.get("press"):
            locator.press(str(spec.get("press")), timeout=timeout)
    except Exception as error:
        raise session.action_error("填写 %s" % spec.get("target"), error, note)
    result = session.after_action(page, "fill", spec, before_url, matched, note, ctx)
    result["value"] = value
    return result


@op("type")
def op_type(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    value = spec.get("value")
    if value is None:
        value = spec.get("text")
    if value is None or str(value) == "":
        raise ActionError("op=type 需要 value（要逐字输入的文本）",
                          "只想整体替换输入框内容时用 op=fill 更稳；type 用于触发输入联想 / 逐字校验")
    text = str(value)
    delay = _number(spec.get("delay_ms"), 30)
    before_url = page.url
    try:
        locator.click(timeout=timeout)
        method = getattr(locator, "press_sequentially", None) or getattr(locator, "type")
        method(text, delay=delay, timeout=timeout)
    except Exception as error:
        raise session.action_error("逐字输入 %s" % spec.get("target"), error, note)
    return session.after_action(page, "type", spec, before_url, matched, note, ctx)


@op("press")
def op_press(session, spec, ctx):
    page = session.current_page()
    key = str(spec.get("key") or spec.get("value") or "Enter")
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    target = spec.get("target") or spec.get("selector")
    before_url = page.url
    matched = 0
    note = None
    try:
        if target:
            locator, matched, note = session.resolve(page, target, spec.get("index"))
            locator.press(key, timeout=timeout)
        else:
            page.keyboard.press(key)
    except ActionError:
        raise
    except Exception as error:
        raise session.action_error("按键 %s" % key, error, note)
    return session.after_action(page, "press", spec, before_url, matched, note, ctx)


@op("select")
def op_select(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    options = {}
    if spec.get("value") is not None and spec.get("value") != "":
        options["value"] = str(spec.get("value"))
    if spec.get("label") is not None and spec.get("label") != "":
        options["label"] = str(spec.get("label"))
    if spec.get("option_index") is not None and spec.get("option_index") != "":
        options["index"] = int(_number(spec.get("option_index"), 0))
    if not options:
        raise ActionError("op=select 需要 value / label / option_index 之一",
                          "value 是 option 的 value 属性值，label 是显示文字，option_index 从 0 开始")
    before_url = page.url
    try:
        selected = locator.select_option(timeout=timeout, **options)
    except Exception as error:
        raise session.action_error("选择下拉项", error, note)
    result = session.after_action(page, "select", spec, before_url, matched, note, ctx)
    result["selected"] = selected
    return result


@op("set_checked")
def op_set_checked(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    checked = _bool(spec.get("checked"), True)
    force = _bool(spec.get("force"), False)
    before_url = page.url
    try:
        if checked:
            locator.check(timeout=timeout, force=force)
        else:
            locator.uncheck(timeout=timeout, force=force)
    except Exception as error:
        raise session.action_error("勾选/取消勾选 %s" % spec.get("target"), error, note)
    result = session.after_action(page, "set_checked", spec, before_url, matched, note, ctx)
    result["checked"] = checked
    return result


@op("hover")
def op_hover(session, spec, ctx):
    page = session.current_page()
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    before_url = page.url
    try:
        locator.hover(timeout=timeout, force=_bool(spec.get("force"), False))
    except Exception as error:
        raise session.action_error("悬停 %s" % spec.get("target"), error, note)
    return session.after_action(page, "hover", spec, before_url, matched, note, ctx)


@op("upload")
def op_upload(session, spec, ctx):
    page = session.current_page()
    files = spec.get("files") or spec.get("paths")
    if isinstance(files, str):
        files = [files]
    if not files:
        raise ActionError("op=upload 需要 files（文件路径数组）", "相对路径按工作目录解析")
    resolved = []
    for item in files:
        path = str(item)
        if not os.path.isabs(path):
            path = os.path.join(session.work_dir, path)
        if not os.path.exists(path):
            raise ActionError("要上传的文件不存在：%s" % path, "用绝对路径，或相对工作目录的路径")
        resolved.append(path)
    locator, matched, note = session.resolve(page, spec.get("target") or spec.get("selector"), spec.get("index"))
    before_url = page.url
    try:
        locator.set_input_files(resolved, timeout=ctx.timeout_ms(session.settings()["action_timeout_ms"]))
    except Exception as error:
        raise session.action_error("上传文件", error, note)
    result = session.after_action(page, "upload", spec, before_url, matched, note, ctx)
    result["files"] = resolved
    return result


@op("wait")
def op_wait(session, spec, ctx):
    page = session.current_page()
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), 10000))
    state = str(spec.get("state") or "visible")
    try:
        if spec.get("ms") is not None and spec.get("ms") != "":
            milliseconds = int(min(_number(spec.get("ms"), 0), max(0.0, ctx.left_ms())))
            page.wait_for_timeout(milliseconds)
            return {"ok": True, "waited_ms": milliseconds}
        if spec.get("url"):
            page.wait_for_url(str(spec.get("url")), timeout=timeout)
        elif spec.get("text"):
            page.get_by_text(str(spec.get("text"))).first.wait_for(state=state, timeout=timeout)
        else:
            target = spec.get("target") or spec.get("selector")
            if target:
                locator, matched, note = session.resolve(page, target, spec.get("index"), required=False)
                locator.first.wait_for(state=state, timeout=timeout)
                result = {"ok": True, "matched": matched, "state": state}
                if note:
                    result["note"] = note
                result.update(session.page_state(page))
                return result
            page.wait_for_load_state("load", timeout=timeout)
    except Exception as error:
        raise ActionError("等待未满足：%s" % clip_text(error, 300),
                          "先 op=snapshot 看页面现状再决定等什么；页面本身就慢的话加大 timeout_ms")
    result = {"ok": True, "state": state}
    result.update(session.page_state(page))
    return result


@op("expect")
def op_expect(session, spec, ctx):
    page = session.current_page()
    state = str(spec.get("state") or spec.get("expect") or "").strip().lower()
    if not state:
        raise ActionError("op=expect 需要 state（要断言什么）",
                          "可选：visible / hidden / attached / detached / enabled / disabled / "
                          "text_contains / value_equals / count / url_contains / title_contains / "
                          "no_console_errors / has_console_errors")
    expected = spec.get("expected")
    if expected is None:
        expected = spec.get("value")
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), 5000))
    deadline = time.time() + timeout / 1000.0
    target = spec.get("target") or spec.get("selector")
    needs_element = state not in ("url_contains", "title_contains", "no_console_errors", "has_console_errors")
    locator = None
    if needs_element:
        if not target:
            raise ActionError("op=expect state=%s 需要 target（断言哪个元素）" % state,
                              "只有 url_contains / title_contains / no_console_errors 这类页面级断言不需要 target")
        # 计数 / 存在性断言要的是「匹配几个」，不能被 resolve 收敛成单个元素
        keep_all = state in ("count", "attached", "detached")
        locator, _, _ = session.resolve(page, target, spec.get("index"), required=False, keep_all=keep_all)
    actual = None
    while True:
        passed, actual = evaluate_expect(session, page, state, expected, locator)
        if passed or time.time() >= deadline:
            break
        time.sleep(0.2)
    result = {"ok": True, "pass": bool(passed), "state": state, "target": target, "expected": expected,
              "actual": actual}
    if not passed:
        result["note"] = "断言未通过：state=%s，期望=%s，实际=%s" % (state, expected, actual)
    return result


@op("eval")
def op_eval(session, spec, ctx):
    page = session.current_page()
    expression = spec.get("expression")
    if expression is None:
        expression = spec.get("script")
    if expression is None:
        expression = spec.get("js")
    expression = "" if expression is None else str(expression).strip().rstrip(";").strip()
    if not expression:
        raise ActionError("op=eval 需要 expression（要执行的 JS）",
                          "页面级写 document.title 这类表达式；元素级（带 target）写 el.value / el.textContent")
    target = spec.get("target") or spec.get("selector")
    note = None
    try:
        if target:
            locator, _, note = session.resolve(page, target, spec.get("index"))
            value = locator.first.evaluate(element_expression(expression))
        else:
            value = page.evaluate(expression)
    except ActionError:
        raise
    except Exception as error:
        raise ActionError("执行 JS 失败：%s" % clip_text(error, 300),
                          "表达式要能被浏览器求值；元素级 eval 里元素叫 element（写 el. 会自动按 element. 处理），"
                          "多语句要写成 () => { ... }()")
    result = {"ok": True, "result": _jsonable(value, int(_number(spec.get("max_chars"), 4000))),
              "url": page.url, "title": _safe_title(page)}
    if note:
        result["note"] = note
    return result

@op("screenshot")
def op_screenshot(session, spec, ctx):
    page = session.current_page()
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["action_timeout_ms"]))
    path = session.shot_path(spec.get("name") or "page")
    target = spec.get("target") or spec.get("selector")
    try:
        if target:
            locator, _, note = session.resolve(page, target, spec.get("index"))
            locator.first.screenshot(path=path, timeout=timeout)
        else:
            page.screenshot(path=path, full_page=_bool(spec.get("full_page"), False), timeout=timeout)
    except ActionError:
        raise
    except Exception as error:
        raise ActionError("截图失败：%s" % clip_text(error, 300))
    result = {"ok": True, "file": path, "target": target or "整个可视区域"}
    if target:
        result["target"] = target
    result.update(session.page_state(page))
    return result


@op("console")
def op_console(session, spec, ctx):
    page = session.current_page()
    events = session.events_for(page)
    result = {"ok": True, "url": page.url, "title": _safe_title(page),
              "console": events.get("console", [])[-30:],
              "page_errors": events.get("errors", [])[-10:],
              "failed_requests": events.get("failed", [])[-10:],
              "dialogs": events.get("dialogs", [])[-10:],
              "counts": {"console": len(events.get("console", [])),
                         "page_errors": len(events.get("errors", [])),
                         "failed_requests": len(events.get("failed", [])),
                         "dialogs": len(events.get("dialogs", []))}}
    if _bool(spec.get("clear"), False):
        events["console"] = []
        events["errors"] = []
        events["failed"] = []
        events["dialogs"] = []
        result["cleared"] = True
    return result


@op("tabs")
def op_tabs(session, spec, ctx):
    action = str(spec.get("action") or "list").strip().lower()
    if action == "list":
        return {"ok": True, "pages": session.describe_pages()}
    context = session.context()
    if context is None:
        raise ActionError("浏览器还没启动", "先调用一次 op=open 打开页面")
    if action == "new":
        created = context.new_page()
        session.attach_page(created)
        session.set_current_index(len(context.pages) - 1)
        result = {"ok": True, "pages": session.describe_pages()}
        if spec.get("url"):
            url = normalize_url(spec.get("url"), session.work_dir)
            created.goto(url, timeout=ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["nav_timeout_ms"])))
        result.update(session.page_state(created))
        if _bool(spec.get("snapshot"), True):
            result["snapshot"] = session.build_snapshot(created, {"max_elements": spec.get("max_elements") or 60})
        return result
    if action == "switch":
        wanted = spec.get("index")
        if wanted is None or wanted == "":
            pages = session.describe_pages()
            for item in pages:
                if spec.get("url") and str(spec.get("url")) in item["url"]:
                    wanted = item["index"]
                    break
                if spec.get("title") and str(spec.get("title")) in item["title"]:
                    wanted = item["index"]
                    break
        if wanted is None or wanted == "":
            raise ActionError("op=tabs action=switch 需要 index / url / title 之一指明切到哪个标签页",
                              "先 op=tabs action=list 看标签页清单")
        index = int(_number(wanted, 0))
        if index < 0 or index >= len(context.pages):
            raise ActionError("index=%d 超出标签页范围（共 %d 个）" % (index, len(context.pages)))
        session.set_current_index(index)
        switched = session.current_page()
        try:
            switched.bring_to_front()
        except Exception:
            pass
        result = {"ok": True, "pages": session.describe_pages()}
        result.update(session.page_state(switched))
        if _bool(spec.get("snapshot"), True):
            result["snapshot"] = session.build_snapshot(switched, {"max_elements": spec.get("max_elements") or 60})
        return result
    if action == "close":
        pages = session.pages()
        if len(pages) <= 1:
            raise ActionError("只剩一个标签页，关掉就没有页面了", "要整体结束用 op=close")
        index = int(_number(spec.get("index"), session.current_index()))
        if index < 0 or index >= len(pages):
            raise ActionError("index=%d 超出标签页范围（共 %d 个）" % (index, len(pages)))
        pages[index].close()
        remaining = session.pages()
        session.set_current_index(min(index, len(remaining) - 1))
        return {"ok": True, "pages": session.describe_pages()}
    raise ActionError("op=tabs 的 action 只支持 list / new / switch / close")


@op("nav")
def op_nav(session, spec, ctx):
    page = session.current_page()
    action = str(spec.get("action") or "reload").strip().lower()
    timeout = ctx.timeout_ms(_number(spec.get("timeout_ms"), session.settings()["nav_timeout_ms"]))
    if action not in ("back", "forward", "reload"):
        raise ActionError("op=nav 的 action 只支持 back / forward / reload")
    try:
        if action == "back":
            response = page.go_back(wait_until="domcontentloaded", timeout=timeout)
        elif action == "forward":
            response = page.go_forward(wait_until="domcontentloaded", timeout=timeout)
        else:
            response = page.reload(wait_until="domcontentloaded", timeout=timeout)
    except Exception as error:
        raise ActionError("页面导航（%s）失败：%s" % (action, clip_text(error, 300)),
                          "浏览器历史里可能没有上/下一页；确认页面还在（op=status）")
    result = {"ok": True, "action": action, "status_code": response.status if response is not None else None}
    result.update(session.page_state(page))
    if _bool(spec.get("snapshot"), True):
        result["snapshot"] = session.build_snapshot(page, {"max_elements": spec.get("max_elements") or 60})
    return result


@op("run")
def op_run(session, spec, ctx):
    case_info = None
    if spec.get("case"):
        steps, case_info = load_case_steps(session.work_dir, spec.get("case"))
        if spec.get("steps"):
            case_info["note"] = "本次 steps 与 case 同时给了，按 case 文件执行（steps 已忽略）"
    else:
        steps = spec.get("steps")
    if not isinstance(steps, list) or not steps:
        raise ActionError("op=run 需要 steps（动作数组）或 case（用例文件路径）",
                          "例如 steps=[{\"op\":\"open\",\"url\":\"http://localhost:5174\"},"
                          "{\"op\":\"fill\",\"target\":\"ref=e1\",\"value\":\"admin\",\"submit\":true},"
                          "{\"op\":\"expect\",\"state\":\"text_contains\",\"target\":\"body\",\"expected\":\"首页\"}]")
    results = []
    continue_on_error = _bool(spec.get("continue_on_error"), False)
    failed = 0
    for position, step in enumerate(steps):
        if not isinstance(step, dict):
            results.append({"ok": False, "step": position + 1, "error": "这一步不是对象"})
            failed += 1
            break
        name = str(step.get("op") or "").strip().lower()
        handler = OPS.get(name)
        if name in ("", "run") or handler is None:
            results.append({"ok": False, "step": position + 1, "op": name,
                            "error": "不支持的 op：%s" % name, "available": sorted(OPS)})
            failed += 1
            break
        step_spec = dict(step)
        step_spec.pop("op", None)
        # 这里刻意不统一关掉快照：open / nav / 切换标签页这类「换页面」的步骤必须重新生成快照，
        # 否则 DOM 上的 refAttr 不会被标记，后续步骤里的 ref=eN 会全部定位失败；
        # click / fill 这类动作步骤仍按各自默认（只在发生跳转时才带快照），输出保持精简。
        started = time.time()
        try:
            ctx.guard("第 %d 步（%s）" % (position + 1, name))
            outcome = handler(session, step_spec, ctx)
            if not isinstance(outcome, dict):
                outcome = {"ok": True, "result": outcome}
            outcome.setdefault("ok", True)
        except ActionError as error:
            outcome = {"ok": False, "error": str(error)}
            if error.hint:
                outcome["hint"] = error.hint
        outcome["step"] = position + 1
        outcome["op"] = name
        outcome["duration_ms"] = int((time.time() - started) * 1000)
        if name == "expect" and not outcome.get("pass", True):
            outcome["ok"] = False
            outcome["error"] = outcome.get("note") or "断言未通过"
        results.append(outcome)
        if not outcome.get("ok", True):
            failed += 1
            if not continue_on_error:
                break
    result = {"ok": failed == 0, "executed": len(results), "failed": failed, "steps": results}
    if case_info:
        result["case"] = case_info
    if failed:
        first = [item for item in results if not item.get("ok", True)][0]
        result["error"] = "第 %s 步（%s）失败：%s" % (first.get("step"), first.get("op"), first.get("error"))
        result["hint"] = "看这一步的 error / hint 与页面现状，调整后再跑；也可先 op=snapshot 看页面"
    try:
        page = session.current_page()
        result.update(session.page_state(page))
        result.update(session.events_summary(page, limit=5))
        if failed and ctx.left_ms() > 900.0:
            result["snapshot"] = session.build_snapshot(page, {"max_elements": 50})
    except ActionError:
        pass
    return result


@op("settings")
def op_settings(session, spec, ctx):
    note = session.apply_settings(spec)
    result = {"ok": True, "settings": session.settings(), "browser_running": session.browser_running()}
    if note:
        result["note"] = note
    return result


@op("restart")
def op_restart(session, spec, ctx):
    session.close_browser()
    note = session.apply_settings(spec)
    result = {"ok": True, "restarted": True, "settings": session.settings(),
              "hint": "浏览器已关闭；下一次 op=open 会用当前参数重新启动（登录态与页面进度已丢失）"}
    if note:
        result["note"] = note
    return result


@op("shutdown")
def op_shutdown(session, spec, ctx):
    session.close_browser()
    session.stopped = True
    return {"ok": True, "stopped": True, "hint": "会话已结束，后台进程即将退出；下次调用会重新拉起（需要重新登录）"}

def write_state(path, state):
    """原子写状态文件：先写临时文件再替换，避免客户端读到半截 JSON。"""
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(state, handle, ensure_ascii=False, indent=2)
    os.replace(temp, path)


def make_handler(session, token, logger):
    """构造 HTTP 处理类：GET /health 做就绪探针，POST /rpc 走一条 RPC。"""
    from http.server import BaseHTTPRequestHandler

    class Handler(BaseHTTPRequestHandler):
        server_version = "ClaraBrowserHost/1.0"

        def log_message(self, format, *args):
            logger("http " + clip_text(format % args, 200))

        def do_GET(self):
            if self.path.rstrip("/") in ("", "/health"):
                self._send({"ok": True, "pid": os.getpid(), "ready": True})
                return
            self._send({"ok": False, "error": "未知路径"}, 404)

        def do_POST(self):
            if self.path != "/rpc":
                self._send({"ok": False, "error": "未知路径"}, 404)
                return
            if token and self.headers.get("X-Clara-Token") != token:
                self._send({"ok": False, "error": "令牌不匹配"}, 403)
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b"{}"
                request = json.loads(body.decode("utf-8"))
            except Exception as error:
                self._send({"ok": False, "error": "请求体不是合法 JSON：%s" % clip_text(error, 200)})
                return
            try:
                payload = session.handle(request)
            except Exception as error:  # 兜底：任何意外都要有结构化响应，否则客户端只能等超时
                logger("处理请求时未捕获异常：" + traceback.format_exc())
                payload = {"ok": False, "error": "%s: %s" % (type(error).__name__, clip_text(error, 300))}
            self._send(payload)

        def _send(self, payload, status=200):
            data = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                # 客户端已超时断开：结果写不回去就算了，别让守护进程跟着倒
                logger("客户端连接已断开，响应未送达")

    return Handler


def parse_arguments(argv):
    """极简参数解析：--key value / --flag 两种写法，避免为此引入 argparse 的额外分支。"""
    values = {}
    position = 0
    while position < len(argv):
        token = argv[position]
        if token.startswith("--"):
            key = token[2:]
            if position + 1 < len(argv) and not argv[position + 1].startswith("--"):
                values[key] = argv[position + 1]
                position += 2
            else:
                values[key] = "1"
                position += 1
            continue
        position += 1
    return values


def main(argv):
    args = parse_arguments(argv)
    work_dir = os.path.abspath(args.get("work-dir") or os.getcwd())
    state_path = os.path.abspath(args.get("state") or os.path.join(work_dir, ".clara", "browser", "session.json"))
    state_dir = os.path.dirname(state_path)
    log_path = os.path.abspath(args.get("log") or os.path.join(state_dir, "host.log"))
    token = args.get("token") or ""
    try:
        os.makedirs(os.path.join(state_dir, "shots"), exist_ok=True)
    except OSError as error:
        sys.stderr.write("无法创建状态目录 %s：%s\n" % (state_dir, error))
        return 2
    logger = Logger(log_path)
    options = {
        "headless": _bool(args.get("headless"), True),
        "channel": args.get("channel") or "",
        "executable_path": args.get("executable_path") or "",
        "locale": args.get("locale") or "",
        "user_agent": args.get("user_agent") or "",
        "viewport": _parse_viewport(args.get("viewport")),
        "slow_mo": _number(args.get("slow-mo"), 0),
        "dialog": args.get("dialog") or "accept",
        "action_timeout_ms": _number(args.get("action-timeout-ms"), DEFAULT_ACTION_TIMEOUT_MS),
        "nav_timeout_ms": _number(args.get("nav-timeout-ms"), DEFAULT_NAV_TIMEOUT_MS),
    }
    session = BrowserSession(work_dir, state_dir, options, logger)
    port = int(_number(args.get("port"), 0))
    from http.server import HTTPServer

    server = HTTPServer(("127.0.0.1", port), make_handler(session, token, logger))
    actual_port = server.server_address[1]
    write_state(state_path, {
        "pid": os.getpid(),
        "port": actual_port,
        "token": token,
        "work_dir": work_dir,
        "host": "_browser_host.py",
        "host_fingerprint": args.get("host-fingerprint") or "",
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "log": log_path,
        "state_dir": state_dir,
        "settings": session.settings(),
    })
    logger("守护进程就绪：pid=%d port=%d work_dir=%s" % (os.getpid(), actual_port, work_dir))
    idle_seconds = _number(args.get("idle-seconds"), 1800)
    server.timeout = 1.0
    try:
        while not session.stopped:
            server.handle_request()
            if idle_seconds > 0 and (time.time() - session.last_activity) > idle_seconds:
                logger("空闲超过 %d 秒，自动退出" % idle_seconds)
                break
    except KeyboardInterrupt:
        logger("收到中断信号，退出")
    finally:
        session.shutdown()
        try:
            if os.path.exists(state_path):
                os.remove(state_path)
        except OSError:
            pass
        logger("守护进程已退出")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
