"""browser 技能脚本（工具名 browser）：把页面操作转发给常驻的浏览器后台进程。

分工：本文件是「客户端」，每次工具调用都被框架以一个短进程执行一次；真正的浏览器会话在
_browser_host.py 里（由本文件按需拉起、后台无窗口运行），两者用回环 HTTP + JSON 通信。
为什么要拆两层：框架对单次脚本执行有约 30 秒硬超时，浏览器若每次调用都重启，登录态与页面
进度就保不住；常驻进程让「多次调用 = 同一个浏览器」，这正是需要登录的 UI 测试的前提。

产物与状态都落在工作目录（CLARA_WORK_DIR）下：
.clara/browser/session.json（后台端口与令牌）、.clara/browser/host.log（后台日志）、
.clara/browser/shots/*.png（截图）。

进度信息写到 stderr（框架会逐行转发到控制台与运行日志），因此调用过程在控制台上可见。
"""

import hashlib
import http.client
import json
import os
import socket
import subprocess
import sys
import time

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8，避免依赖系统区域设置（中文 Windows 默认 GBK 会与 Java 侧不一致）。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python browser.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "browser",
    "name": "browser",
    "type": "PYTHON",
    "description": (
        "浏览器自动化（UI 测试）：用 Playwright 操作本机或内网页面——打开、看页面、点击 / 输入 / 选择 / 勾选、"
        "断言结果、截图留证。适合「自动把页面点一遍看对不对」这类任务（注册、登录、下单、表单校验、冒烟回归），"
        "也适合需要在浏览器里跑 JS 取值的核对。浏览器会话常驻后台进程：同一工作目录的多次调用是同一个浏览器，"
        "登录态、页面进度、元素 ref 编号都跨调用保留，不必每次重新登录；默认无头运行（需要人工盯着看时传 "
        "headless=false 开有头窗口）。\n"
        "典型流程：op=open 打开地址（返回页面快照，每个可交互元素带 ref 编号）→ 用 op=click / op=fill 对 "
        "target=\"ref=e3\" 操作 → op=snapshot 或 op=expect 确认结果 → 结束用 op=close。\n"
        "op 一览：open(打开地址：url 可写完整地址 / 裸端口 5174 / 本地文件路径；new_page=true 新开标签页)、"
        "snapshot(取当前页可交互元素清单：ref/role/name/value/disabled/href，外加标题、标题大纲、控制台报错)、"
        "text(取可见文字)、click(可 double=true 双击)、fill(整体填写，submit=true 填完直接回车提交)、"
        "type(逐字输入，用于输入联想框)、press(按键，默认 Enter)、select(下拉：value / label / option_index)、"
        "set_checked(勾选或取消勾选)、hover、upload(上传文件)、wait(等元素 / 等文字 / 等地址 / 等毫秒)、"
        "expect(断言 visible|hidden|attached|detached|enabled|disabled|text_contains|value_equals|count|"
        "url_contains|title_contains|no_console_errors|has_console_errors，返回 pass 与实际值，不通过会说明差异)、"
        "eval(执行 JS 取值：页面级写 document.title 这类表达式，元素级带 target 时写 el.value)、screenshot(截图落在工作目录 .clara/browser/shots)、"
        "console(看控制台报错与失败请求)、tabs(多标签：list/new/switch/close)、nav(back/forward/reload)、"
        "run(一次跑多步：steps=[{op:...},...]，失败即停，适合把一次测试写成一段；换页面的步骤会带回新快照，" +
        "ref 以最新一次快照为准；也可以用 case=<用例文件> 回放存好的用例)、status、doctor(自检环境)、"
        "settings(改 headless / channel / viewport)、restart、close(结束会话并退出后台进程)。\n"
        "target 写法：ref=e3(上一次快照给的编号，最稳)、css=#id、text=登录、role=button[name=提交]、"
        "label=用户名、placeholder=请输入、testid=submit、xpath=//div；不带前缀时按 CSS 处理。"
        "匹配到多个时自动取第一个可见元素并在 note 里说明，也可用 index 指定第几个。\n"
        "限制：单次调用总时长约 30 秒（内部预算默认 20 秒，可用 budget_ms 调小），超时会返回「接着做」的提示，"
        "所以长流程要拆成多次调用；ref 在页面跳转或重新渲染后失效（会提示重新 snapshot）；操作失败会自动截图"
        "并把页面快照一并带回，便于自我修正。\n"
        "另需填写 purpose(请务必填写：一句话说明本次调用该工具的目的，会展示给用户，例如「打开登录页」"
        "「点击提交并检查跳转」「断言列表里有 3 条数据」)。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {"type": "string"},
            "url": {"type": "string"},
            "target": {"type": "string"},
            "selector": {"type": "string"},
            "index": {"type": ["number", "string"]},
            "value": {"type": ["string", "number", "boolean"]},
            "text": {"type": "string"},
            "key": {"type": "string"},
            "label": {"type": "string"},
            "option_index": {"type": ["number", "string"]},
            "checked": {"type": ["boolean", "string"]},
            "submit": {"type": ["boolean", "string"]},
            "double": {"type": ["boolean", "string"]},
            "force": {"type": ["boolean", "string"]},
            "button": {"type": "string"},
            "clear": {"type": ["boolean", "string"]},
            "full_page": {"type": ["boolean", "string"]},
            "name": {"type": "string"},
            "expression": {"type": "string"},
            "script": {"type": "string"},
            "files": {"type": ["array", "string"]},
            "paths": {"type": ["array", "string"]},
            "steps": {"type": "array"},
            "case": {"type": "string"},
            "continue_on_error": {"type": ["boolean", "string"]},
            "ms": {"type": ["number", "string"]},
            "action": {"type": "string"},
            "state": {"type": "string"},
            "expect": {"type": "string"},
            "expected": {"type": ["string", "number", "boolean"]},
            "timeout_ms": {"type": ["number", "string"]},
            "wait_ms": {"type": ["number", "string"]},
            "wait_for": {"type": "string"},
            "wait_state": {"type": "string"},
            "wait_until": {"type": "string"},
            "snapshot": {"type": ["boolean", "string"]},
            "max_elements": {"type": ["number", "string"]},
            "max_chars": {"type": ["number", "string"]},
            "max_text": {"type": ["number", "string"]},
            "include_hidden": {"type": ["boolean", "string"]},
            "budget_ms": {"type": ["number", "string"]},
            "headless": {"type": ["boolean", "string"]},
            "channel": {"type": "string"},
            "viewport": {"type": ["string", "object"]},
            "dialog": {"type": "string"},
            "new_page": {"type": ["boolean", "string"]},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "testing", "tags": "browser,playwright,ui-test,automation,python"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))
# 工作根：启动脚本把「用户执行 clara 时所在目录」写入 CLARA_WORK_DIR；会话与产物都落在它下面。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT
STATE_DIR = os.path.join(WORK_ROOT, ".clara", "browser")
STATE_PATH = os.path.join(STATE_DIR, "session.json")
LOG_PATH = os.path.join(STATE_DIR, "host.log")
SHOTS_DIR = os.path.join(STATE_DIR, "shots")
HOST_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_browser_host.py")

# 客户端也守一遍总时限：框架对单次脚本执行有约 30 秒硬超时，留出余量保证结果能写回去。
HARD_LIMIT_MS = 26000
DEFAULT_BUDGET_MS = 20000
DEFAULT_IDLE_SECONDS = 1800

OP_LIST = ["open", "snapshot", "text", "click", "fill", "type", "press", "select", "set_checked", "hover",
           "upload", "wait", "expect", "eval", "screenshot", "console", "tabs", "nav", "run",
           "status", "doctor", "settings", "restart", "close"]


class ClientError(Exception):
    """客户端侧的可预期失败（后台没起来、通信断了等），消息直接回给模型。"""


def log(message):
    """进度写 stderr：框架会逐行转发到控制台与 logs 下的运行日志。"""
    sys.stderr.write("[浏览器] %s\n" % message)
    sys.stderr.flush()


def clip(value, limit=60):
    text = "" if value is None else str(value)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + "..."


def as_bool(value, default=False):
    """宽容解析布尔：模型可能写 true / "true" / 1 / "yes"。"""
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


def default_headless():
    """默认无头运行；想让浏览器窗口显示出来，可设环境变量 CLARA_BROWSER_HEADLESS=0。"""
    value = str(os.environ.get("CLARA_BROWSER_HEADLESS") or "").strip().lower()
    if value in ("0", "false", "no", "off"):
        return False
    return True


def read_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, ValueError):
        return None
    if not isinstance(state, dict) or not state.get("port"):
        return None
    return state


def remove_state():
    try:
        if os.path.exists(STATE_PATH):
            os.remove(STATE_PATH)
    except OSError:
        pass


def log_tail(limit=1200):
    try:
        with open(LOG_PATH, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError:
        return "(没有后台日志)"
    text = text.replace("\r\n", "\n").strip()
    return text[-limit:]


def host_fingerprint():
    """后台脚本的指纹：常驻进程会一直跑旧代码，靠它发现「脚本改了」并把旧进程换掉。"""
    try:
        with open(HOST_SCRIPT, "rb") as handle:
            return hashlib.sha1(handle.read()).hexdigest()[:16]
    except OSError:
        return ""


def daemon_outdated(state):
    """后台进程是否在跑改之前的旧脚本：指纹缺失（老版本进程）或对不上都算过期。"""
    current = host_fingerprint()
    if not current:
        return False
    return (state or {}).get("host_fingerprint") != current


def shutdown_daemon(state, timeout=8.0):
    """请后台进程退出：它自己会关浏览器、退进程并删状态文件；失败不抛错，由调用方重建。"""
    if not state:
        return False
    try:
        rpc(state, {"op": "shutdown", "spec": {}, "budget_ms": 5000}, timeout)
        return True
    except ClientError:
        return False


def healthy(state, timeout=2.0):
    """就绪 / 存活探针：能答上来才算这个会话还在。"""
    if not state:
        return False
    try:
        connection = http.client.HTTPConnection("127.0.0.1", int(state["port"]), timeout=timeout)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            body = response.read()
        finally:
            connection.close()
    except (OSError, ValueError, http.client.HTTPException):
        return False
    try:
        return bool(json.loads(body.decode("utf-8")).get("ok"))
    except ValueError:
        return False


def rpc(state, request, timeout):
    """向后台进程发一条 RPC，返回解析后的 JSON 结果。"""
    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8", "Content-Length": str(len(body))}
    token = state.get("token")
    if token:
        headers["X-Clara-Token"] = token
    try:
        connection = http.client.HTTPConnection("127.0.0.1", int(state["port"]), timeout=timeout)
        try:
            connection.request("POST", "/rpc", body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            status = response.status
        finally:
            connection.close()
    except (OSError, ValueError, http.client.HTTPException) as error:
        raise ClientError("与后台浏览器进程通信失败：%s" % error)
    if status != 200:
        raise ClientError("后台浏览器进程返回 HTTP %d" % status)
    try:
        return json.loads(raw.decode("utf-8"))
    except ValueError as error:
        raise ClientError("后台浏览器进程返回的不是 JSON：%s（日志尾部：%s）" % (error, log_tail(400)))


def spawn_daemon(spec):
    """后台拉起守护进程：无窗口、输出重定向到 host.log、父进程退出后它继续在跑。"""
    os.makedirs(SHOTS_DIR, exist_ok=True)
    headless = default_headless()
    if spec.get("headless") is not None and spec.get("headless") != "":
        headless = as_bool(spec.get("headless"), headless)
    command = [sys.executable, HOST_SCRIPT,
               "--work-dir", WORK_ROOT,
               "--state", STATE_PATH,
               "--log", LOG_PATH,
               "--token", os.urandom(12).hex(),
               "--port", "0",
               "--host-fingerprint", host_fingerprint(),
               "--headless", "1" if headless else "0",
               "--idle-seconds", str(int(DEFAULT_IDLE_SECONDS))]
    if spec.get("channel"):
        command.extend(["--channel", str(spec["channel"])])
    if spec.get("viewport"):
        viewport = spec["viewport"]
        if isinstance(viewport, dict):
            command.extend(["--viewport", "%sx%s" % (viewport.get("width"), viewport.get("height"))])
        else:
            command.extend(["--viewport", str(viewport)])
    flags = 0
    if os.name == "nt":
        # 不加这两个标志的话，子进程会跟着父进程一起被杀掉，也容易弹出控制台窗口。
        flags = getattr(subprocess, "DETACHED_PROCESS", 0x00000008) | \
            getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
    handle = open(LOG_PATH, "ab")
    try:
        subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=handle, stderr=handle,
                         cwd=WORK_ROOT, creationflags=flags, close_fds=True)
    finally:
        handle.close()


def port_listening(state, timeout=1.0):
    """端口是否还在监听：后台进程正忙时 HTTP 探针会超时，但端口依然是开着的。"""
    if not state:
        return False
    connection = None
    try:
        connection = socket.create_connection(("127.0.0.1", int(state["port"])), timeout=timeout)
        return True
    except (OSError, ValueError):
        return False
    finally:
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass


def await_ready(deadline, spawn=False, spec=None):
    """等后台会话就绪：spawn=True 时先拉起；超时把 host.log 尾部带回去（缺 playwright、浏览器没装都能看出来）。"""
    if spawn:
        remove_state()
        try:
            spawn_daemon(spec or {})
        except OSError as error:
            raise ClientError("无法启动后台进程：%s" % error)
    limit = min(deadline, time.time() + 15.0)
    while time.time() < limit:
        current = read_state()
        if healthy(current, timeout=1.0):
            if spawn:
                log("后台会话就绪：端口 %s" % current.get("port"))
            return current
        time.sleep(0.3)
    raise ClientError("后台浏览器进程未在预期时间内应答；日志尾部：%s" % log_tail())

def describe_spec(spec):
    """挑几个关键字段做一行摘要，让控制台能看出这一步在做什么。"""
    parts = []
    for key in ("url", "target", "index", "value", "text", "key", "state", "action", "expected"):
        value = spec.get(key)
        if value is None or value == "":
            continue
        parts.append("%s=%s" % (key, clip(value, 50)))
        if len(parts) >= 3:
            break
    steps = spec.get("steps")
    if isinstance(steps, list) and steps:
        parts.append("steps=%d 步" % len(steps))
    return " ".join(parts)


def playwright_version():
    try:
        from importlib.metadata import version

        return version("playwright")
    except Exception:
        return ""


def first_existing(paths):
    for path in paths:
        if path and os.path.exists(path):
            return path
    return ""


def detect_browsers():
    """探测可用浏览器：内置 chromium（Playwright 自带）、系统 Chrome、系统 Edge。"""
    result = {"bundled_chromium": "", "chrome": "", "edge": ""}
    cache = os.environ.get("LOCALAPPDATA")
    if cache:
        playwright_dir = os.path.join(cache, "ms-playwright")
        if os.path.isdir(playwright_dir):
            names = sorted(os.listdir(playwright_dir))
            for name in names:
                if name.startswith("chromium"):
                    result["bundled_chromium"] = os.path.join(playwright_dir, name)
                    break
    program_files = os.environ.get("ProgramFiles", "")
    program_files_x86 = os.environ.get("ProgramFiles(x86)", "")
    result["chrome"] = first_existing([
        os.path.join(program_files, "Google", "Chrome", "Application", "chrome.exe"),
        os.path.join(program_files_x86, "Google", "Chrome", "Application", "chrome.exe"),
        os.path.join(cache or "", "Google", "Chrome", "Application", "chrome.exe")])
    result["edge"] = first_existing([
        os.path.join(program_files_x86, "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(program_files, "Microsoft", "Edge", "Application", "msedge.exe")])
    return result

def daemon_info():
    state = read_state()
    running = healthy(state)
    return {"running": running,
            "healthy": running,
            "pid": (state or {}).get("pid"),
            "port": (state or {}).get("port"),
            "started_at": (state or {}).get("started_at"),
            "script_changed": daemon_outdated(state),
            "state_file": STATE_PATH if state else ""}


def op_doctor(spec):
    """环境自检：Python、Playwright、可用浏览器、后台会话状态一次看清。"""
    info = {"ok": True,
            "python": sys.version.split()[0],
            "python_executable": sys.executable,
            "work_dir": WORK_ROOT,
            "state_dir": STATE_DIR,
            "screenshots_dir": SHOTS_DIR,
            "log": LOG_PATH,
            "playwright": playwright_version(),
            "browsers": detect_browsers(),
            "daemon": daemon_info()}
    browsers = info["browsers"]
    if not info["playwright"]:
        info["ready"] = False
        info["hint"] = ("当前 Python 没装 playwright：先执行 %s -m pip install playwright，"
                        "再执行 %s -m playwright install chromium" % (sys.executable, sys.executable))
    elif not (browsers["bundled_chromium"] or browsers["chrome"] or browsers["edge"]):
        info["ready"] = False
        info["hint"] = "没找到可用浏览器：执行 %s -m playwright install chromium，或调用时传 channel=\"chrome\"/\"msedge\"" % sys.executable
    else:
        info["ready"] = True
        info["hint"] = "环境就绪；截图落在 %s" % SHOTS_DIR
    return info


def op_close(spec):
    """结束会话：先让后台进程自己退出（它会关浏览器），退出后删状态文件。"""
    state = read_state()
    if not state:
        return {"ok": True, "closed": False, "hint": "当前工作目录没有正在运行的浏览器会话"}
    closed = False
    if healthy(state):
        try:
            rpc(state, {"op": "shutdown", "spec": {}, "budget_ms": 5000}, 8.0)
            closed = True
        except ClientError as error:
            log("让后台进程退出失败：%s" % error)
    remove_state()
    log("浏览器会话已结束")
    return {"ok": True, "closed": closed, "port": state.get("port"),
            "screenshots_dir": SHOTS_DIR,
            "hint": "后台进程已退出；下次 op=open 会重新拉起（需要重新登录）"}


def forward(operation, spec, started):
    """把一次调用转给后台：必要时先拉起它（或等它忙完），并按剩余时间折算本轮预算。"""
    deadline = started + HARD_LIMIT_MS / 1000.0
    state = read_state()
    note = None
    if daemon_outdated(state) and healthy(state):
        # 常驻进程会一直跑旧代码（直到 close 或空闲退出）：脚本改了就直接换掉它，否则改动一直不生效
        log("后台脚本已更新，重启浏览器会话以加载新代码")
        shutdown_daemon(state)
        remove_state()
        state = None
        note = "后台脚本 _browser_host.py 已更新，已自动重启浏览器会话（登录态与页面进度丢失）"
    if not healthy(state):
        # 端口还开着说明后台进程活着（多半在忙别的请求）：等它答话，别再拉第二个浏览器
        busy = port_listening(state)
        if busy:
            log("后台会话正忙，等它应答（端口 %s）" % state.get("port"))
            note = "后台会话当时正忙，本次已排队等待其应答"
        else:
            if state:
                note = "上一会话的后台进程已不在，已重新启动（登录态与页面进度丢失）"
            log("正在启动后台浏览器会话（首次会现起浏览器，可能要几秒）")
        try:
            state = await_ready(deadline, spawn=not busy, spec=spec)
        except ClientError as error:
            return {"ok": False, "error": str(error)}
    log("%s %s" % (operation, describe_spec(spec)))
    left_ms = (deadline - time.time()) * 1000.0
    budget_ms = int(max(2000.0, min(float(spec.get("budget_ms") or DEFAULT_BUDGET_MS), left_ms - 3000.0)))
    read_timeout = max(3.0, (deadline - time.time()) - 1.0)
    try:
        result = rpc(state, {"op": operation, "spec": spec, "budget_ms": budget_ms}, read_timeout)
    except ClientError as error:
        return {"ok": False, "error": str(error), "hint": "后台浏览器进程可能已退出或卡住：看日志 %s；可先 op=close 再 op=open 重来；若 op=close 也超时，结束残留的后台会话进程并删除 %s 后重试" % (LOG_PATH, STATE_PATH)}
    if note and isinstance(result, dict):
        result.setdefault("note", note)
    return result

def dispatch(operation, spec, started):
    if not operation or operation in ("help", "ops", "?"):
        return {"ok": True, "ops": OP_LIST, "state_dir": STATE_DIR,
                "flow": "open -> snapshot(取 ref) -> click/fill(target=\"ref=e3\") -> snapshot 或 expect 断言 -> close",
                "local_ops": ["status", "doctor", "restart", "close"]}
    if operation == "doctor":
        return op_doctor(spec)
    if operation in ("close", "shutdown", "quit"):
        return op_close(spec)
    if operation == "restart":
        op_close(spec)
        return {"ok": True, "restarted": True,
                "hint": "后台浏览器已关闭；下次 op=open 会按新参数重新启动（登录态与页面进度丢失）"}
    if operation == "status":
        state = read_state()
        if not (healthy(state) or port_listening(state)):
            return {"ok": True, "running": False,
                    "hint": "当前工作目录还没有活的浏览器会话：用 op=open 打开地址会自动拉起（首次要现起浏览器）"}
        return forward("status", spec, started)
    return forward(operation, spec, started)


def main():
    raw = sys.stdin.read()
    if raw[:1] == chr(65279):
        raw = raw[1:]  # 兼容 PowerShell / 重定向可能带入的 UTF-8 BOM
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as error:  # noqa: BLE001 - 入参不可解析时也以结果回传，避免非零退出中断整轮
        print(json.dumps({"ok": False, "error": "入参 JSON 解析失败：" + str(error)}, ensure_ascii=False))
        return
    started = time.time()
    operation = str(spec.get("op") or "").strip().lower()
    try:
        result = dispatch(operation, spec, started)
    except ClientError as error:
        result = {"ok": False, "error": str(error)}
    except Exception as error:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result = {"ok": False, "error": "%s: %s" % (type(error).__name__, clip(error, 200))}
    if not isinstance(result, dict):
        result = {"ok": True, "result": result}
    result["duration_ms"] = int((time.time() - started) * 1000)
    print(json.dumps(result, ensure_ascii=False, default=str))


main()
