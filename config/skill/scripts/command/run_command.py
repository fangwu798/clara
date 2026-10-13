import json
import os
import subprocess
import sys
import threading
import time

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道，避免中文 Windows 区域编码（GBK）致乱码。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python run_command.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "run_command",
    "name": "run_command",
    "type": "PYTHON",
    "description": (
        "运行本地命令并返回退出码、标准输出与标准错误（高风险：以当前进程权限执行本机命令，执行前请确认命令与目标目录）。"
        "命令的标准输出与标准错误会逐行实时回显（前缀 [out] / [err]）到工具运行日志，便于观察执行过程。"
        "入参 JSON：purpose(请务必填写：一句话说明本次执行该命令的目的，会展示给用户，例如「编译 Java 源代码」"
        "「查看 Git 状态」「运行单元测试」)、argv(命令与参数数组，不经 shell，推荐)、command(命令行，经系统 shell 执行：Windows 用 cmd /c，"
        "其它用 /bin/sh -c；与 argv 二选一，argv 优先)、"
        "cwd(工作目录，缺省为 CLARA_WORK_DIR（执行 run.bat 时所在目录）；以 @repo 开头表示按仓库根解析；可为绝对路径)、"
        "stdin(写入子进程标准输入的文本)、env(追加的环境变量对象)、"
        "timeout_seconds(超时秒数，默认 300，上限 600；超时后终止子进程)、"
        "max_output_bytes(标准输出与标准错误各自回显与回传的字节上限，默认 20000，超出部分省略)、"
        "encoding(输出解码编码，缺省自动识别，中文 Windows 下可自动识别 GBK)。"
        "示例：{\"argv\":[\"mvn\",\"-v\"],\"cwd\":\"@repo\"}、{\"command\":\"git status --short\"}、"
        "{\"argv\":[\"powershell\",\"-NoProfile\",\"-Command\",\"Get-ChildItem\"],\"cwd\":\"@repo\"}。"
        "生成图片/视频：调用 clara.jar 内置的生图/生视频能力（已配置的字节 Seedream 文生图 / Seedance 文生视频），"
        "如 {\"argv\":[\"java\",\"-jar\",\"clara.jar\",\"image\",\"--prompt\",\"夕阳下的海边灯塔\"],\"cwd\":\"@repo\","
        "\"timeout_seconds\":180} 或 "
        "{\"argv\":[\"java\",\"-jar\",\"clara.jar\",\"video\",\"--prompt\",\"一只在草地上奔跑的小狗\"],\"cwd\":\"@repo\","
        "\"timeout_seconds\":600}；命令返回单行 JSON，其中 file 为生成文件的绝对路径"
        "（缺省落在工作目录的 clara-output/ 下），可直接把该路径告知用户。"
        "当用户的意图是「生成/画一张图片」或「生成一段视频」时，优先用这种方式，不要回答「无法生成图片/视频」。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "argv": {"type": "array"},
            "command": {"type": "string"},
            "cwd": {"type": "string"},
            "stdin": {"type": "string"},
            "env": {"type": "object"},
            "timeout_seconds": {"type": ["integer", "number"]},
            "max_output_bytes": {"type": "integer"},
            "encoding": {"type": "string"},
        },
    },
    "metadata": {"domain": "shell", "tags": "python,command,process,high-risk"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本把「用户执行命令时所在目录」写入 CLARA_WORK_DIR；相对 cwd 都相对它解析。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 仓库根相对前缀：以 @repo 开头时按仓库根解析，与「当前分析目录」（CLARA_WORK_DIR）区分开。
REPO_PREFIX = "@repo"

DEFAULT_TIMEOUT_SECONDS = 300.0
MAX_TIMEOUT_SECONDS = 600.0
DEFAULT_MAX_OUTPUT_BYTES = 20000


def resolve_cwd(value):
    # cwd 可为 "@repo" 前缀、绝对路径或相对工作根（CLARA_WORK_DIR）的路径；缺省为工作根。
    if not value:
        return WORK_ROOT
    text = str(value)
    if text.startswith(REPO_PREFIX):
        rest = text[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return text if os.path.isabs(text) else os.path.join(WORK_ROOT, text)


def build_argv(spec):
    # argv 优先（不经 shell，避免注入与转义问题）；否则把 command 交给系统 shell 执行。
    argv = spec.get("argv")
    if isinstance(argv, list) and argv:
        return [str(item) for item in argv]
    command = spec.get("command")
    command = command.strip() if isinstance(command, str) else ""
    if not command:
        raise ValueError("需要提供 argv（数组，不经 shell）或 command（经系统 shell 执行）")
    if os.name == "nt":
        return ["cmd", "/c", command]
    return ["/bin/sh", "-c", command]


def build_environment(spec):
    # 继承当前环境并叠加入参 env（值统一转为字符串）。
    environment = dict(os.environ)
    for key, value in (spec.get("env") or {}).items():
        environment[str(key)] = str(value)
    return environment


def normalize_encoding(spec):
    explicit = spec.get("encoding")
    return explicit.strip() if isinstance(explicit, str) and explicit.strip() else None


def timeout_of(spec):
    seconds = float(spec.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)
    return min(max(seconds, 0.1), MAX_TIMEOUT_SECONDS)


def decode_line(raw, explicit):
    # 按行解码：优先显式编码，其次自动识别（中文 Windows 下可识别 GBK）；截断切断多字节字符时替换解码。
    try:
        text, _ = _textio.decode(raw, explicit)
    except UnicodeDecodeError:  # noqa: BLE001 - 截断可能切断多字节字符，退回替换解码
        text = raw.decode(explicit or _textio.DEFAULT_ENCODING, errors="replace")
    return text


class StreamBuffer:
    """子进程单条输出流的缓冲：既作为结果 JSON 的内容，又逐行实时回显到本脚本的 stderr。

    回显到 stderr 的行由框架逐行转发到 System.out，因此会出现在控制台与 logs 下的运行日志中，
    从而能观察命令的执行过程，而不是等命令结束才看到全部输出。字节上限同时约束回显与回传。
    """

    def __init__(self, label, explicit, limit):
        self.label = label
        self.explicit = explicit
        self.limit = limit
        self.bytes = 0
        self.truncated = False
        self.parts = []

    def add(self, raw):
        if self.truncated or not raw:
            return
        remaining = self.limit - self.bytes
        chunk = raw
        if len(raw) > remaining:
            chunk = raw[:remaining] if remaining > 0 else b""
            self.truncated = True
        self.bytes += len(chunk)
        text = decode_line(chunk, self.explicit)
        self.parts.append(text)
        self.echo(text)
        if self.truncated:
            self.echo("...（输出已达 max_output_bytes=%d 上限，后续输出不再回显与回传）" % self.limit)

    def echo(self, text):
        # 逐行回显到 stderr（框架逐行转发到控制台与运行日志），逐行 flush 保证实时可见。
        if not text:
            return
        for line in text.splitlines():
            sys.stderr.write("[%s] %s\n" % (self.label, line))
        sys.stderr.flush()

    def text(self):
        return "".join(self.parts)


def pump(stream, buffer):
    # 后台线程按行读取子进程输出，边累积边回显；避免管道写满导致子进程阻塞。
    try:
        while True:
            raw = stream.readline()
            if not raw:
                break
            buffer.add(raw)
    except (OSError, ValueError):  # 进程被终止时读管道可能抛错，忽略
        pass
    finally:
        try:
            stream.close()
        except OSError:
            pass


def feed_stdin(process, stdin_bytes):
    # 写入标准输入后立即关闭，作为子进程的输入结束信号。
    if stdin_bytes is None:
        return
    try:
        process.stdin.write(stdin_bytes)
    except OSError:  # 子进程未读取即退出时忽略
        pass
    finally:
        try:
            process.stdin.close()
        except OSError:
            pass


def execute(spec, argv, cwd):
    timeout = timeout_of(spec)
    max_bytes = int(spec.get("max_output_bytes") or DEFAULT_MAX_OUTPUT_BYTES)
    explicit = normalize_encoding(spec)
    stdin_text = spec.get("stdin")
    stdin_bytes = None if stdin_text is None else str(stdin_text).encode("utf-8")

    started = time.time()
    process = subprocess.Popen(argv, cwd=cwd, env=build_environment(spec),
                               stdin=subprocess.PIPE if stdin_bytes is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out_buffer = StreamBuffer("out", explicit, max_bytes)
    err_buffer = StreamBuffer("err", explicit, max_bytes)
    pumps = [threading.Thread(target=pump, args=(process.stdout, out_buffer), daemon=True),
             threading.Thread(target=pump, args=(process.stderr, err_buffer), daemon=True)]
    for thread in pumps:
        thread.start()
    feed_stdin(process, stdin_bytes)

    try:
        exit_code = process.wait(timeout=timeout)
        status = "ok" if exit_code == 0 else "failed"
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        exit_code = None
        status = "timeout"
    for thread in pumps:
        thread.join(1.0)

    result = {"status": status,
              "exit_code": exit_code,
              "stdout": out_buffer.text(), "stdout_truncated": out_buffer.truncated,
              "stderr": err_buffer.text(), "stderr_truncated": err_buffer.truncated,
              "duration_ms": int((time.time() - started) * 1000)}
    if status == "timeout":
        result["error"] = "命令在 %s 秒内未结束，已终止子进程（可加大 timeout_seconds）" % timeout
    return result


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传，避免非零退出中断整轮
        print(json.dumps({"error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return
    result = {}
    started = time.time()
    try:
        argv = build_argv(spec)
        cwd = resolve_cwd(spec.get("cwd"))
        result["command"] = " ".join(argv)
        result["cwd"] = cwd
        result.update(execute(spec, argv, cwd))
    except FileNotFoundError as exception:  # noqa: BLE001 - 命令不存在时给出可操作提示
        result["error"] = ("命令不存在或不可执行：%s；如需 shell 内建命令请改用 command 参数"
                           % exception)
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    result["duration_ms"] = int((time.time() - started) * 1000)
    print(json.dumps(result, ensure_ascii=False))


main()
