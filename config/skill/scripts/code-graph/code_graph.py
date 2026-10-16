"""代码知识图谱工具：读代码时优先查本地知识图谱，图谱没有的再从本地文件读。

知识图谱由 codebase-memory-mcp（独立进程，纯 C 静态二进制，随 release 发布）维护：
它把仓库索引成 SQLite 里的节点 / 边（函数、类、导入、HTTP 路由及其关系），并可回答
「某文件有哪些声明」「某符号的调用方 / 被调用方」「按名字 / 正则找符号」等问题，
以上问题的答案通常比整文件读取少两三个数量级的 token。

本脚本是「图谱优先、文件兜底」的落点：
- op=read 逐文件先取图谱的文件大纲（声明清单）；该文件不在图谱里（未索引 / 不在索引范围 /
  图谱不可用）或图谱对它没有任何声明时，才回退为本地文件读取（与 file_ops 同口径的路径解析、
  编码识别与截断）。散文 / 文档类文件（.md / .txt 等）不查图谱、直接返回原文：图谱对它们最多
  只给章节标题，大纲不足以替代正文。
- 其余 op（index/status/search/outline/snippet/trace/query）是图谱能力的直通调用，
  给工作流与模型在「需要结构化信息」时使用。

图谱可以装在宿主，也可以跑在容器里（见 deploy/codebase-memory-mcp）：容器部署的注册项是
`docker exec -i <容器> codebase-memory-mcp`，本脚本识别这种形态后原样带上 docker exec 前缀执行，
并按 CLARA_CBM_REPO（宿主「挂载根」↔ 容器内 /work/repo）换算两边路径。

挂载根不必等于当前工作目录，也可以是它的祖先目录（工作区根）：挂载根下的任意子目录都能按需索引成
独立项目，于是一个容器就能覆盖多个工作目录，换目录不必重建容器。项目名优先沿用「该目录已索引时的
名字」（重复索引不会新建重名项目、也不会丢原有图谱），其次取 CLARA_CBM_PROJECT 或目录名。
把当前工作目录导入图谱：先用 op=prepare 看是否已导入（秒回），再用 op=index 发起导入，
或直接运行 deploy/codebase-memory-mcp/index-workdir.ps1。

调用方式与其它工具脚本一致：stdin 读 JSON 入参，stdout 打印 JSON 结果；
图谱二进制与图谱不存在时不会报错退出，而是按「图谱不可用」处理（read 自动回退文件）。
"""

import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python code_graph.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "code_graph",
    "name": "code_graph",
    "type": "PYTHON",
    "description": "代码知识图谱工具：图谱覆盖当前工作目录时，读代码 / 查符号 / 查引用一律先用本工具，查不到再回退 text_search / file_ops 看本地文件。常用问题与 op 的对应：查「X 被谁引用 / 谁调用了 X / X 的调用方」→ op=query（只读 Cypher），例如 MATCH (c:Class)-[:DEFINES_METHOD]->(m)-[r]->(n) WHERE n.name='X' RETURN DISTINCT c.qualified_name（引用方是节点 m 及其外层类 c；关系类型有 USAGE / CALLS / DEFINES / TESTS）；只想看调用链层级就用 op=trace direction=inbound，但它只跟 CALLS 边、会漏掉 USAGE，别当完整引用清单。查「X 调用了谁」→ op=trace direction=outbound；查某文件的符号清单 → op=outline；按名字或正则找符号 → op=search（带 label=Function / Class 更准）；取某函数源码 → op=snippet；精确统计或筛选 → op=query；一次读多个文件的结构 → op=read（图谱未覆盖或不可用时自动回退为本地文件读取；.md/.txt 这类散文文件不走图谱、直接返回原文）。把某个工作目录导入图谱：先用 op=prepare 看是否已导入（秒回），再用 op=index 发起导入（缺省后台执行，不卡住调用）。入参 JSON：op(prepare|index|status|search|outline|snippet|trace|query|read)、file_path(仓库相对路径，outline 用)、paths(路径或通配列表，read 用；元素可写 * ? [ ] 与 ** 递归)、pattern(名字 / 文本片段，search 用)、regex(布尔，search 用；默认 false，为 true 时 pattern 按正则匹配，多关键词检索写 foo|bar 就要带上它，否则 | 只当字面量、命中恒为 0)、label(节点标签，如 Function / Class)、qualified_name(限定名，snippet 用)、function_name(函数名，trace 用)、direction(inbound|outbound|both，trace 用；inbound 查调用方、outbound 查被调用方)、depth(1-15，trace 用)、query(只读 Cypher，query 用)、project(图谱中的项目名；缺省按当前分析目录匹配，容器部署时先把该目录导入图谱才能匹配上)、repo_path(索引 / 检查的目标目录，index 与 prepare 用，缺省为当前分析目录)、background(索引是否后台执行，index 用；缺省 true 秒回、稍后用 op=status 看结果，false 则同步等待并返回节点 / 边计数)、index(prepare 时是否顺带后台发起导入，缺省 false)、max_bytes(单文件回退读取的截断字节，默认 20000)、max_files(展开后读取的文件数上限，默认 200)。返回：{op, source(graph|file|graph+file), files:[{path, source, outline|content}], ...}；其中 source 明确标注每条信息来自图谱还是本地文件。另需填写 purpose(请务必填写：一句话简短说明本次调用该工具的目的，会展示给用户)。",
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {"type": "string",
                   "enum": ["prepare", "index", "status", "search", "outline", "snippet", "trace",
                            "query", "read"]},
            "file_path": {"type": "string"},
            "paths": {"type": "array", "items": {"type": "string"}},
            "pattern": {"type": "string"},
            "regex": {"type": "boolean",
                      "description": "op=search 是否把 pattern 当正则（默认 false）：多关键词检索写 foo|bar 必须为 true，否则竖线只当字面量、命中恒为 0。"},
            "label": {"type": "string"},
            "qualified_name": {"type": "string"},
            "function_name": {"type": "string"},
            "direction": {"type": "string", "enum": ["inbound", "outbound", "both"],
                          "description": "op=trace 的调用图方向：inbound=调用方（谁调用了它），"
                                         "outbound=被调用方（它调用了谁），both=双向（默认）。"
                                         "注意 inbound 只跟 CALLS 边：要完整的「谁引用了它」清单请用 op=query。"},
            "depth": {"type": "integer"},
            "query": {"type": "string"},
            "project": {"type": "string"},
            "repo_path": {"type": "string"},
            "background": {"type": "boolean"},
            "index": {"type": "boolean"},
            "base_dir": {"type": "string"},
            "max_bytes": {"type": "integer"},
            "max_files": {"type": "integer"},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "code", "tags": "graph,code,search,index,knowledge"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本把「用户执行命令时所在目录」写入 CLARA_WORK_DIR，即本轮要分析的项目目录。
# CLARA_CBM_WORK_DIR 是本工具自己的覆盖项，优先于它（同一进程里换分析目录、或在别处复用本脚本时用）。
WORK_DIR_ENV = "CLARA_CBM_WORK_DIR"
WORK_ROOT = os.environ.get(WORK_DIR_ENV) or os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 仓库根相对前缀：与本仓库其它脚本（file_ops / dir_tree）保持一致，便于按 @repo 读取随应用发布的文件。
REPO_PREFIX = "@repo"

# 图谱命令的位置来源：环境变量 → config/mcp 的 MCP 注册项 → PATH。三者都取不到即视为「图谱不可用」。
BINARY_ENV = "CLARA_CBM_BINARY"
MCP_CONFIG_DIR = os.path.join(REPO_ROOT, "config", "mcp")
SERVER_KEY = "codebase-memory-mcp"

# 项目名来源（可选）：只在「项目根换算不出宿主路径」时才退回它（见 project_of）：一个容器可以覆盖多个
# 项目，钉死一个项目名去覆盖别的目录只会取到错内容。
PROJECT_ENV = "CLARA_CBM_PROJECT"

# 容器部署的路径映射：容器内的挂载根固定是 CONTAINER_REPO_MOUNT，宿主侧对应的目录写在 REPO_ENV。
# 有这对映射，才能把宿主路径换算成容器内路径（建索引）、把容器内的路径换回宿主路径（读结果）。
# 这个挂载根既可以是工作目录本身，也可以是工作目录的祖先（工作区根）——后者让一个容器覆盖多个项目。
CONTAINER_REPO_MOUNT = "/work/repo"
REPO_ENV = "CLARA_CBM_REPO"

# 自动挂载用的容器内目录前缀：ensure-mount.ps1 把一个工作目录挂到 /work/roots/<slug>（slug 由宿主路径
# 派生，稳定可复现），于是新挂一个目录不会挪动已有目录在容器里的位置，已有图谱不必重做索引。
CONTAINER_ROOTS_PREFIX = "/work/roots"

# 挂载表的查询超时：docker inspect 秒回，给足余量即可。
INSPECT_TIMEOUT_SECONDS = 20

# 挂载表缓存：进程内只查一次 docker inspect。
_CONTAINER_MOUNTS = None

# 执行入口缓存：resolve_runner 要读注册项并查 PATH，进程内解析一次就够。
_RUNNER = None

# 挂载根与项目名的取值来源（顺序见 host_mount / pinned_project）：deploy/codebase-memory-mcp/.env、
# 进程环境变量、Windows 用户级环境变量（setx 写的）。为什么把 .env 排在最前：它是 install-docker.ps1
# 写下的、compose 真正用来挂载的那一份，与容器里实际能看到的目录一致；而 setx 只对「之后启动」的
# 进程生效，进程态里的旧值（改过挂载根之后的老终端）会把新值盖掉，于是「目录就在挂载内」被误判成
# out_of_mount，图谱被静默跳过（读代码悄悄退回本地文件）。index-workdir.ps1 用的是同一口径。
DEPLOY_ENV_FILE = os.path.join(REPO_ROOT, "deploy", "codebase-memory-mcp", ".env")

# runner 形态只判定一次（每次重新解析注册项没必要）。
_RUNNER_IS_CONTAINER = None

# 后台索引用的进程创建标记（Windows）：DETACHED_PROCESS 让子进程脱离父进程的终端与控制台，
# CREATE_NO_WINDOW 避免弹出黑窗；POSIX 一侧用 start_new_session。
DETACHED_PROCESS = 0x00000008
CREATE_NO_WINDOW = 0x08000000

# 容器命令的宿主侧入口：注册项形如 `docker exec -i <容器> codebase-memory-mcp` 时整条带上前缀执行。
DOCKER_COMMANDS = ("docker", "podman")

# trace_path 的 --direction 只认 inbound / outbound / both：同时接受「调用方 / 被调用方」这类通俗说法，
# 免得工作流或模型按直觉传 callers / callees 时整条命令被 CLI 判成非法入参而白白失败一次。
TRACE_DIRECTIONS = {
    "inbound": "inbound", "callers": "inbound", "caller": "inbound", "调用方": "inbound",
    "outbound": "outbound", "callees": "outbound", "callee": "outbound", "被调用方": "outbound",
    "both": "both", "双向": "both",
}

# 单次查询超时与建索引超时：查询应秒回，建索引要读整个仓库，故分开给。
CLI_TIMEOUT_SECONDS = 180
INDEX_TIMEOUT_SECONDS = 1800

# 回退为本地文件读取时的默认截断字节与文件数上限（与 file_ops.read_many 默认值一致）。
DEFAULT_MAX_BYTES = 20000
DEFAULT_MAX_FILES = 200

# 路径中出现任一字符即按 glob 展开。
GLOB_CHARS = "*?["

# 散文 / 文档类扩展名：图谱对这类文件最多只给出章节标题，大纲既不如原文有用，还要多花一次 CLI 调用，
# 所以 op=read 见到它们直接读原文，不去问图谱（要单看标题结构请显式用 op=outline）。
PROSE_EXTENSIONS = {
    "md", "markdown", "mdx", "rst", "adoc", "asciidoc", "txt", "text", "csv", "tsv", "log",
}

# 递归展开目录时跳过的噪声目录（与 file_ops / dir_tree 一致）。
DEFAULT_SKIP_DIRS = [
    ".git", ".idea", ".m2", "target", "node_modules", "dist", "build",
    "logs", "volumes", "__pycache__", ".clara",
]


def resolve_root(base):
    # base 可为 "@repo" 前缀（仓库根相对）、绝对路径或相对工作根（CLARA_WORK_DIR）的路径。
    if base.startswith(REPO_PREFIX):
        rest = base[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return base if os.path.isabs(base) else os.path.join(WORK_ROOT, base)


def resolve(path, base):
    if path and path.startswith(REPO_PREFIX):
        rest = path[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    # 容器内路径（/work/repo/...）按挂载映射换回宿主路径：模型可能把上一轮结果里的容器路径原样传回来。
    path = host_path(path)
    root = resolve_root(base)
    if not path:
        return root
    return path if os.path.isabs(path) else os.path.join(root, path)


def relative_to_work(path):
    # 图谱按「仓库相对路径」寻址，故把绝对路径换算成相对工作根的路径（跨盘等算不出时退回原值）。
    try:
        rel = os.path.relpath(path, WORK_ROOT)
    except ValueError:
        return path
    return rel.replace("\\", "/")


def is_prose(path):
    # 判断是否为散文 / 文档类文件：只看扩展名，不看内容，保证同一次 read 的判定稳定可预期。
    name = os.path.basename(path)
    dot = name.rfind(".")
    if dot <= 0:
        return False
    return name[dot + 1:].lower() in PROSE_EXTENSIONS


def graph_relative(path, root):
    # 把绝对路径换算成图谱认的「项目内相对路径」；项目根未知、跨盘或不在项目内时返回 None，
    # 由调用方退回按当前分析目录换算（显式指定 project 的场景拿不到项目根）。
    if not root or not path:
        return None
    try:
        relative = os.path.relpath(path, root)
    except ValueError:
        return None
    if relative.startswith(".."):
        return None
    return relative.replace("\\", "/")


def config_command():
    # 从 config/mcp/*.json 里取本服务端注册的完整命令：安装脚本把二进制绝对路径或容器命令写在这里，
    # 脚本据此复用同一份配置（宿主安装给路径，容器部署给 docker exec 前缀）。
    if not os.path.isdir(MCP_CONFIG_DIR):
        return None
    for name in sorted(os.listdir(MCP_CONFIG_DIR)):
        if not name.lower().endswith(".json"):
            continue
        try:
            with open(os.path.join(MCP_CONFIG_DIR, name), "r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError):
            continue
        servers = payload.get("mcpServers") if isinstance(payload, dict) else None
        if not isinstance(servers, dict):
            continue
        entry = servers.get(SERVER_KEY)
        if not isinstance(entry, dict):
            continue
        command = entry.get("command")
        if isinstance(command, list) and command:
            parts = [str(part).strip() for part in command]
        elif isinstance(command, str) and command.strip():
            parts = [command.strip()]
        else:
            continue
        # 注册格式把参数放在 args（与 command 分开）时合并进来，命令行才算完整。
        extra = entry.get("args")
        if isinstance(extra, list):
            parts += [str(part).strip() for part in extra]
        parts = [part for part in parts if part]
        if parts:
            return parts
    return None


def docker_exec_runner(parts):
    # 识别注册项里的容器形态 `docker exec [-i] <容器> <命令...>`；返回 None 表示这不是一条容器命令。
    if len(parts) < 4 or os.path.splitext(os.path.basename(parts[0]))[0].lower() not in DOCKER_COMMANDS:
        return None
    if parts[1].lower() != "exec":
        return None
    index = 2
    while index < len(parts) and parts[index].startswith("-"):
        index += 1
    return parts if index + 1 < len(parts) else None


def resolve_runner():
    """返回执行图谱命令的 argv 前缀，取不到返回 None（调用方按「图谱不可用」处理，不报错）。

    两种形态：本地二进制 `[<路径>]`，与容器部署 `["docker", "exec", "-i", <容器>, "codebase-memory-mcp"]`；
    后者宿主没有二进制，只能整条命令带着 docker exec 前缀跑（容器内进程由 docker 拉起）。
    """
    explicit = (os.environ.get(BINARY_ENV) or "").strip()
    parts = None
    if explicit:
        if os.path.isfile(explicit):
            return [explicit]
        found = shutil.which(explicit)
        if found:
            return [found]
        # 也允许把整条命令写进环境变量（容器部署常见：docker exec -i <容器> codebase-memory-mcp）；
        # 只在整串既不是文件也不是命令时才按空白拆，避免拆坏含空格的路径。
        if len(explicit.split()) > 1:
            parts = explicit.split()
    parts = parts or config_command()
    if parts:
        if docker_exec_runner(parts):
            # 容器命令的宿主侧入口仍是 PATH 里的 docker 客户端。
            return [shutil.which(parts[0]) or parts[0]] + parts[1:]
        if os.path.isfile(parts[0]):
            return parts
        found = shutil.which(parts[0])
        if found:
            return [found] + parts[1:]
    found = shutil.which("codebase-memory-mcp")
    return [found] if found else None


def clean_text(text):
    # 图谱进程每次启动都会往 stderr 打一行内存分配器告警（level=warn ...），它跟本次调用结果无关，
    # 混进错误信息会让模型误以为调用本身出了问题，这里统一剔除。
    lines = [line for line in (text or "").splitlines() if not line.strip().startswith("level=")]
    return "\n".join(lines).strip()


def run_cli(runner, tool, flags=None, payload=None, timeout=CLI_TIMEOUT_SECONDS, format_json=True):
    """执行一次 `codebase-memory-mcp cli <tool>`，返回 (ok, 文本)。

    参数优先用命令行开关（与官方 README 中的示例一致）；开关调用失败时再用「stdin 传 JSON 入参」重试一次
    （官方对个别工具的开关名未在文档中给出，但两条通道都会生成同一份工具入参），两条都失败才返回失败。
    format_json=False 用于本身没有 --format 开关的工具（官方目前只有 index_repository 如此），
    此时两条通道都不带该开关。
    """
    tail = ["--format", "json"] if format_json else []
    attempts = []
    if flags is not None:
        attempts.append((["cli", tool] + list(flags) + tail, None))
    if payload is not None:
        attempts.append((["cli", tool] + tail, json.dumps(payload, ensure_ascii=False)))
    if not attempts:
        attempts.append((["cli", tool] + tail, None))

    last_error = ""
    for args, stdin_text in attempts:
        try:
            completed = subprocess.run(
                list(runner) + args,
                input=stdin_text,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except (OSError, subprocess.SubprocessError) as exception:  # noqa: PERF203 - 逐个通道尝试
            last_error = str(exception)
            continue
        if completed.returncode == 0:
            return True, hostify(clean_text(completed.stdout))
        last_error = hostify(clean_text(completed.stderr or completed.stdout)) or ("exit " + str(completed.returncode))
    return False, last_error


def as_json(text):
    # CLI 的 --format json 输出为 JSON 文本；解析失败时返回 None，由调用方决定如何呈现原始文本。
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except ValueError:
                return None
        return None


def list_projects(runner):
    ok, text = run_cli(runner, "list_projects")
    if not ok:
        return []
    payload = as_json(text)
    projects = payload.get("projects") if isinstance(payload, dict) else None
    return projects if isinstance(projects, list) else []


def canonical_path(path):
    # 比较路径前统一过一遍 realpath：图谱存的是规范化长路径，而 CLARA_WORK_DIR 可能是 8.3 短名
    # （形如 C:/Users/ADMINI~1/...），只做 normpath 会匹配不上，导致明明索引过却回退去读本地文件。
    if not path:
        return ""
    try:
        resolved = os.path.realpath(path)
    except (OSError, ValueError):
        resolved = path
    return os.path.normcase(os.path.normpath(resolved))


def is_container_runner(runner):
    # 判断这条命令是不是容器形态：容器里没有宿主的仓库路径，路径换算规则不同。
    return bool(runner) and os.path.splitext(os.path.basename(runner[0]))[0].lower() in DOCKER_COMMANDS


def env_file_value(name):
    # 读部署脚本写的 .env（KEY=VALUE，允许 # 注释）：它是 compose 实际使用的挂载来源，认它才和
    # 对话里读代码的口径一致。取不到（宿主安装、没跑过容器部署）时返回空串。
    try:
        with open(DEPLOY_ENV_FILE, "r", encoding="utf-8-sig") as handle:
            for line in handle:
                text = line.strip()
                if not text or text.startswith("#") or "=" not in text:
                    continue
                key, value = text.split("=", 1)
                if key.strip() == name:
                    return value.strip().strip("\"").strip("'")
    except (OSError, ValueError):
        return ""
    return ""


def user_env_value(name):
    # Windows 用户级环境变量（setx 写的那个）：本进程没继承到时用它兜底；其它平台不适用。
    if os.name != "nt":
        return ""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value, _ = winreg.QueryValueEx(key, name)
    except (ImportError, OSError, ValueError):
        return ""
    return value if isinstance(value, str) else ""


def current_runner():
    # 本次调用用的执行入口（进程内只解析一次）。
    global _RUNNER
    if _RUNNER is None:
        try:
            _RUNNER = resolve_runner()
        except Exception:
            _RUNNER = None
    return _RUNNER


def runner_is_container():
    # 本次执行入口是不是容器形态：容器部署才需要两边路径换算，也才需要回落读 .env / 用户级变量。
    global _RUNNER_IS_CONTAINER
    if _RUNNER_IS_CONTAINER is None:
        _RUNNER_IS_CONTAINER = is_container_runner(current_runner())
    return _RUNNER_IS_CONTAINER


def container_name(runner):
    # 从容器形态的 runner（`docker exec [-i ...] <容器> <命令...>`）里取出容器名。
    parts = docker_exec_runner(list(runner) if runner else None)
    if not parts:
        return None
    index = 2
    while index < len(parts) and parts[index].startswith("-"):
        index += 1
    return parts[index] if index < len(parts) else None


def host_source(source):
    # Docker Desktop for Windows 把 bind 的 Source 报成虚拟机内路径（/run/desktop/mnt/host/e/...），
    # 换回宿主路径才能和当前分析目录比；其它平台与形态原样返回。
    text = (source or "").replace("\\", "/")
    match = re.match(r"^/run/desktop/mnt/host/([A-Za-z])(?:/(.+))?$", text)
    if not match:
        return source
    drive = match.group(1).upper() + ":\\"
    rest = match.group(2)
    return os.path.join(drive, *rest.split("/")) if rest else drive


def inspect_mounts(runner):
    # 容器里到底挂了哪些宿主目录：直接问 docker，而不是信宿主侧的配置——两者不一致时（改过挂载根、
    # 还没重建容器）以容器为准，否则会把宿主路径换算成容器里并不存在的路径。取不到就返回空，
    # 由调用方退回单挂载根的老口径。
    name = container_name(runner)
    if not name:
        return []
    command = [runner[0], "inspect", "--format", "{{json .Mounts}}", name]
    try:
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=INSPECT_TIMEOUT_SECONDS, text=True, encoding="utf-8", errors="replace")
    except (OSError, subprocess.SubprocessError):
        return []
    if completed.returncode != 0:
        return []
    try:
        mounts = json.loads(completed.stdout)
    except ValueError:
        return []
    found = []
    for mount in mounts if isinstance(mounts, list) else []:
        if not isinstance(mount, dict) or mount.get("Type") != "bind":
            continue
        source, target = mount.get("Source"), mount.get("Destination")
        if isinstance(source, str) and isinstance(target, str) and source and target:
            found.append((host_source(source), target.rstrip("/") or "/"))
    return found


def container_mounts(runner=None):
    # 挂载表 [(宿主目录, 容器内目录)]：进程内只取一次。
    global _CONTAINER_MOUNTS
    if _CONTAINER_MOUNTS is None:
        resolved = runner if runner is not None else current_runner()
        _CONTAINER_MOUNTS = inspect_mounts(resolved) if is_container_runner(resolved) else []
    return _CONTAINER_MOUNTS


def mount_for_host(path, runner=None):
    # 宿主路径落在哪个挂载点里（多个都命中时取最深的那个）；没命中返回 None。
    best = None
    for source, target in container_mounts(runner):
        try:
            relative = os.path.relpath(path, source)
        except ValueError:
            continue
        if relative == os.pardir or relative.startswith(os.pardir + os.sep):
            continue
        if best is None or len(relative) < len(best[2]):
            best = (source, target, relative)
    return best


def mount_for_container(path, runner=None):
    # 容器内路径落在哪个挂载点里（取最长的那个）；返回 (容器内根, 宿主目录, 相对路径)。
    best = None
    for source, target in container_mounts(runner):
        if path != target and not path.startswith(target + "/"):
            continue
        rest = path[len(target):].strip("/")
        if best is None or len(rest) < len(best[2]):
            best = (target, source, rest)
    return best


def host_mount():
    # 挂载根（宿主侧目录）：部署脚本的 .env → 进程环境变量 → 用户级环境变量。
    # 只在容器形态下才认后两者：宿主安装不需要路径映射，本机若还留着容器部署写的 .env，
    # 认了它反而会把宿主路径换算成 /work/repo 这种容器内路径，越帮越忙。
    if not runner_is_container():
        return os.environ.get(REPO_ENV) or ""
    return env_file_value(REPO_ENV) or os.environ.get(REPO_ENV) or user_env_value(REPO_ENV) or ""


def pinned_project():
    # 被钉住的项目名（CLARA_CBM_PROJECT）：取值顺序同 host_mount，语义见 project_of。
    # 注意 .env 里没有这一项（install-docker.ps1 只把项目名写进用户级环境变量）。
    if not runner_is_container():
        return os.environ.get(PROJECT_ENV) or ""
    return env_file_value(PROJECT_ENV) or os.environ.get(PROJECT_ENV) or user_env_value(PROJECT_ENV) or ""


def host_path(path):
    # 把容器内路径换回宿主路径：容器部署时图谱认的是容器内的挂载点（/work/repo、/work/roots/<slug>），
    # 同一个文件在宿主上的位置由容器的挂载表决定。换算成宿主路径有两个用处：把项目根换算成宿主目录
    # 才能算出仓库相对路径；结果里的路径也必须是宿主路径，否则模型会拿着 /work/repo/... 去读文件，
    # 那边根本不存在。
    match = mount_for_container(path) if path else None
    if match:
        return os.path.join(match[1], *match[2].split("/")) if match[2] else match[1]
    # 回落：取不到挂载表（inspect 不可用）时按宿主侧声明的单一挂载根换算。
    host = host_mount()
    if not path or not host:
        return path
    if path != CONTAINER_REPO_MOUNT and not path.startswith(CONTAINER_REPO_MOUNT + "/"):
        return path
    rest = path[len(CONTAINER_REPO_MOUNT):].strip("/")
    return os.path.join(host, *rest.split("/")) if rest else host


def hostify(text):
    # 结果文本里的容器内路径换回宿主路径（正斜杠写法，避免 JSON 里再转义一层）。
    mounts = container_mounts()
    if mounts:
        # 容器内目录长的先替换，避免 /work/repo 把 /work/roots/... 这种更长的前缀切掉。
        for source, target in sorted(mounts, key=lambda item: len(item[1]), reverse=True):
            text = text.replace(target + "/", source.replace("\\", "/").rstrip("/") + "/")
        return text
    host = host_mount()
    if not text or not host:
        return text
    return text.replace(CONTAINER_REPO_MOUNT + "/", host.replace("\\", "/").rstrip("/") + "/")


def container_path(path):
    # 把宿主路径换算成容器内路径（建索引用）：不在挂载目录内时返回 None，由调用方给出提示。
    match = mount_for_host(path) if path else None
    if match:
        return match[1] if match[2] == os.curdir else match[1] + "/" + match[2].replace("\\", "/")
    # 回落：取不到挂载表（inspect 不可用）时按宿主侧声明的单一挂载根换算。
    host = host_mount()
    if not path or not host:
        return None
    try:
        relative = os.path.relpath(path, host)
    except ValueError:
        return None
    if relative == os.curdir:
        return CONTAINER_REPO_MOUNT
    if relative.startswith(os.pardir):
        return None
    return CONTAINER_REPO_MOUNT + "/" + relative.replace("\\", "/")


def project_root(project):
    # 项目根路径：图谱 list_projects 的字段名可能随版本变动，这里按已知候选依次取。
    for key in ("root_path", "root", "path", "repo_path", "indexed_path", "directory"):
        value = project.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def match_project(projects, wanted):
    # 先精确匹配索引根；再退一步按「当前分析目录落在某个已索引项目内 / 包住某个已索引项目」匹配，
    # 这样在仓库子目录里启动也能用上图谱。匹配不到就返回 None，让调用方老实地读本地文件——
    # 这里刻意不做「只有一个项目就直接用」的兜底：那会让「当前目录没索引」时白跑一路 CLI 探测。
    # 返回 {"name": 项目名, "root": 项目根}；root 用于把文件绝对路径换算成图谱认的项目内相对路径。
    fallback = None
    for project in projects:
        if not isinstance(project, dict):
            continue
        root = host_path(project_root(project))
        if not root:
            continue
        canonical = canonical_path(root)
        if not canonical:
            continue
        if canonical == wanted:
            return {"name": project.get("name"), "root": root}
        if fallback is None and (wanted.startswith(canonical + os.sep) or canonical.startswith(wanted + os.sep)):
            fallback = {"name": project.get("name"), "root": root}
    return fallback


def project_by_name(projects, name):
    # 按项目名取项目；返回 {"name", "root"}，其中 root 已换算成宿主路径（换算不出时为 None）。
    for project in projects:
        if isinstance(project, dict) and project.get("name") == name:
            return {"name": name, "root": host_path(project_root(project))}
    return None


def project_for_dir(projects, directory):
    # 找出根就是该目录（或与之互相包含）的已索引项目；与 read 的匹配口径一致。
    return match_project(projects, canonical_path(directory))


def project_of(spec, runner):
    """解析本次调用该用图谱里的哪个项目。

    优先级：显式 project 入参 → 按当前分析目录匹配 → CLARA_CBM_PROJECT 钉住的项目（仅在它的项目根
    换算不出宿主路径、即容器挂载信息缺失时才认）。

    为什么把「按目录匹配」排在环境变量前面：一个容器可以挂载工作区根、索引多个项目，此时钉死的项目名
    多半指向别的目录；硬用它会让每个文件都白跑一次 CLI，最坏还会把别的项目里同名文件的大纲当成当前
    目录的代码返回。匹配不到就返回 None，由调用方老实地读本地文件。
    """
    if not runner:
        return None
    projects = list_projects(runner)
    explicit = spec.get("project")
    if explicit:
        return project_by_name(projects, explicit)
    matched = project_for_dir(projects, WORK_ROOT)
    if matched:
        return matched
    pinned = pinned_project()
    if pinned:
        found = project_by_name(projects, pinned)
        if found and not found["root"]:
            return found
    return None


def project_name_for_index(target, spec, projects):
    # 建索引时的项目名：显式入参 > 该目录已索引时的项目名（重复索引不新建重名项目、不丢原图谱）>
    # 环境变量钉住的项目（只在该目录就是挂载根、或根本没配挂载映射时才认，兼容单目录容器部署与宿主安装）>
    # 目录名；取不到目录名（盘根）时返回 None，交给图谱自己命名。
    explicit = spec.get("project")
    if explicit:
        return explicit
    existing = project_for_dir(projects, target)
    if existing and existing.get("name"):
        return existing.get("name")
    pinned = pinned_project()
    mount = host_mount()
    if pinned and (not mount or canonical_path(target) == canonical_path(mount)):
        return pinned
    return os.path.basename(os.path.normpath(target)) or None


def as_bool(value, default=False):
    # 布尔入参宽容解析：模型 / YAML 可能给 true、"true"、"1"、"yes"、"是" 这些写法。
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value).strip().lower()
    if text in ("true", "1", "yes", "y", "on", "是", "开"):
        return True
    if text in ("false", "0", "no", "n", "off", "否", "关", "none", ""):
        return False
    return default


def normalize_direction(value):
    # 把入参里的方向说法归一成 trace_path 认的 inbound / outbound / both；未给或认不出时按 both（CLI 默认值）。
    if not value:
        return "both"
    return TRACE_DIRECTIONS.get(str(value).strip().lower(), "both")


def expand_targets(paths, base, max_files):
    # 把「路径 / 通配 / 目录」展开为真实存在的文件列表；返回 [(绝对路径, 仓库相对路径)]。
    targets = []
    seen = set()
    for raw in paths:
        candidate = resolve(raw, base)
        matches = []
        if any(char in raw for char in GLOB_CHARS):
            matches = [path for path in glob.glob(candidate, recursive=True) if os.path.isfile(path)]
        elif os.path.isdir(candidate):
            matches = [os.path.join(candidate, name) for name in sorted(os.listdir(candidate))
                       if os.path.isfile(os.path.join(candidate, name))]
        elif os.path.isfile(candidate):
            matches = [candidate]
        for path in matches:
            absolute = os.path.abspath(path)
            if absolute in seen:
                continue
            seen.add(absolute)
            targets.append((absolute, relative_to_work(absolute)))
            if len(targets) >= max_files:
                return targets
    return targets


def local_read(path, max_bytes):
    # 回退读取：与 file_ops 同口径的编码识别 + 按字节截断，读完直接返回文本。
    #
    # 多读 4 个字节做编码识别样本：样本若恰好切在多字节字符中间，UTF-8 判定会失败并落到 GB18030 等中文编码，
    # 于是整段文本变成乱码；单个 UTF-8 序列最长 4 字节，多读即可避免这种误判。
    # 截断仍按字节进行，解码时以 errors="ignore" 丢掉被切断的半个字符，保证输出是合法文本。
    try:
        with open(path, "rb") as handle:
            raw = handle.read(max_bytes + 4)
    except OSError as exception:
        return {"error": "读取失败：" + str(exception)}
    if _textio.is_binary(raw):
        return {"error": "二进制文件，已跳过（如需读取请改用对应的解析工具）"}
    truncated = len(raw) > max_bytes
    encoding = _textio.detect_encoding(raw) or _textio.DEFAULT_ENCODING
    raw = raw[:max_bytes]
    try:
        text = raw.decode(encoding, errors="ignore")
    except LookupError:
        text = raw.decode(_textio.DEFAULT_ENCODING, errors="ignore")
    if truncated:
        text += "\n[已截断：仅返回前 " + str(max_bytes) + " 字节]"
    return {"content": text}


def graph_outline(runner, project_name, rel_path):
    # 取单个文件的声明大纲；该文件不在图谱里（未索引 / 被忽略 / 索引范围外）时返回 None，由调用方回退读文件；图谱认得该文件、却给不出任何声明时同样返回 None。
    flags = ["--project", project_name, "--file-path", rel_path]
    ok, text = run_cli(runner, "get_file_outline", flags, {"project": project_name, "file_path": rel_path})
    if not ok:
        return None
    payload = as_json(text)
    if isinstance(payload, dict):
        # 只有真的拿到声明才用图谱：.gitignore / mcp.json / clara.bat 这类文件图谱认得，却一条声明都没有，
        # 空大纲没有任何信息量，必须交回调用方去读原文，否则这些文件的内容会被整段丢掉。
        for key in ("outline", "declarations", "symbols", "items", "rows", "tree"):
            value = payload.get(key)
            if value:
                return value
        return None
    if isinstance(payload, list):
        return payload or None
    if not payload:
        # 无结构化大纲时退回原始文本；文本为空或明确表示“没有”时按未覆盖处理。
        stripped = (text or "").strip()
        if not stripped or stripped in ("{}", "[]") or "not found" in stripped.lower():
            return None
        return stripped


def mounted_path(target, runner):
    # 容器部署时把宿主目录换算成容器内目录；宿主安装（或没配映射）时原样返回。
    if is_container_runner(runner):
        return container_path(target)
    return target


def mounted_summary():
    # 挂载情况的可读摘要：容器实际挂了哪些宿主目录，用于「目录不在挂载内」这类提示。
    mounts = container_mounts()
    if mounts:
        return "、".join(source + " -> " + target for source, target in mounts)
    return (host_mount() or CONTAINER_REPO_MOUNT) + " -> " + CONTAINER_REPO_MOUNT


def out_of_mount(op, target):
    # 目录在容器的挂载范围之外：不是「图谱不可用」，而是「容器看不到这个目录」，给一条能照做的一步。
    mount_root = mounted_summary()
    return {
        "op": op, "ok": False, "repo_path": target, "reason": "out_of_mount",
        "error": ("容器只挂载了 " + mount_root + "，这个目录不在其中，容器里看不到它，无法索引。"),
        "hint": ("给它补一条只读挂载即可（只新增，不挪动已有挂载，已建好的图谱不用重做索引）："
                 "powershell -ExecutionPolicy Bypass -File deploy\\codebase-memory-mcp\\ensure-mount.ps1 "
                 "-WorkDir <工作目录>；如果它就是在 Clara 里的当前工作目录，重启一次会话"
                 "（启动时的 codeGraph.autoMount）会自动补上。"),
    }


def detached_container_command(runner):
    # 把 `docker exec -i <容器> <命令...>` 改写成 `docker exec -d <容器> <命令...>`：-i / -t 与 -d 不能同时给。
    parts = list(runner)
    if len(parts) < 4 or parts[1].lower() != "exec":
        return None
    index = 2
    while index < len(parts) and parts[index].startswith("-"):
        index += 1
    if index + 1 >= len(parts):
        return None
    return [parts[0], "exec", "-d"] + parts[index:]


def background_index(runner, target, name):
    """后台发起一次索引，立刻返回 (ok, 说明)。

    为什么不在工具调用里同步等索引：宿主侧单次脚本执行有 30 秒硬超时（clara-skill 的 DefaultToolExecutor），
    而索引一个中等仓库就要一分钟上下；同步索引会被掐断，还会留下半份图谱。于是容器部署用 `docker exec -d`
    （命令在容器内跑，客户端立刻返回），宿主安装用脱离终端的子进程（日志落在临时目录）。
    """
    args = ["cli", "index_repository", "--repo-path", target]
    if name:
        args += ["--name", name]
    if is_container_runner(runner):
        command = detached_container_command(runner)
        if command is None:
            return False, "无法把注册项改写成后台命令：" + " ".join(runner)
        subprocess.Popen(command + args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         stdin=subprocess.DEVNULL)
        return True, ("已在容器内后台发起索引（客户端立刻返回，索引在容器里继续跑）；"
                      "稍后用 op=status 或 check_index_coverage 查看结果，大仓库要几分钟。")
    options = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL, "stdin": subprocess.DEVNULL}
    if os.name == "nt":
        options["creationflags"] = DETACHED_PROCESS | CREATE_NO_WINDOW
    else:
        options["start_new_session"] = True
    # 后台进程不再回传输出，日志留一份到临时目录便于排查。
    log_path = os.path.join(tempfile.gettempdir(), "clara-code-graph-index.log")
    handle = open(log_path, "ab")
    try:
        options["stdout"] = handle
        options["stderr"] = handle
        subprocess.Popen(list(runner) + args, **options)
    finally:
        handle.close()
    return True, ("已在后台发起索引（脱离本进程继续跑），日志：" + log_path +
                  "；稍后用 op=status 查看结果。")


def op_index(spec, runner):
    """为某个目录建立 / 重建图谱索引。

    默认后台执行（background 缺省为 true）：秒回一句「已发起」，避免被宿主侧 30 秒的脚本执行超时掐断；
    需要拿到节点 / 边计数时传 background=false 走同步等待（大仓库可能超时）。
    """
    target = os.path.abspath(resolve(spec.get("repo_path") or "", spec.get("base_dir") or ""))
    if not os.path.isdir(target):
        return {"op": "index", "error": "目录不存在：" + target}
    mount = mounted_path(target, runner)
    if mount is None:
        return out_of_mount("index", target)
    name = project_name_for_index(target, spec, list_projects(runner))
    if as_bool(spec.get("background"), True):
        ok, note = background_index(runner, mount, name)
        result = {"op": "index", "ok": ok, "background": True, "repo_path": host_path(mount),
                  "project": name, "note": note}
        if not ok:
            result["error"] = note
        if mount != target:
            result["host_repo_path"] = target
        return result
    flags = ["--repo-path", mount]
    payload = {"repo_path": mount}
    if name:
        flags += ["--name", name]
        payload["name"] = name
    # index_repository 没有 --format 开关，带上会被 CLI 判成未知开关，故关掉自动拼接。
    ok, text = run_cli(runner, "index_repository", flags, payload,
                       timeout=INDEX_TIMEOUT_SECONDS, format_json=False)
    result = {"op": "index", "ok": ok, "background": False, "repo_path": host_path(mount),
              "project": name, "result": as_json(text) or text}
    if mount != target:
        result["host_repo_path"] = target
    return result


def op_prepare(spec, runner):
    """检查当前分析目录是否已导入图谱，未导入时按需在后台发起导入。

    为什么要这个 op：图谱的多数调用都要求「当前目录已索引」，而建索引要一两分钟——超过宿主侧 30 秒的
    脚本执行硬超时，没法在工具调用里同步等。prepare 只做一次秒回的探测（list_projects），告诉调用方
    「已导入 / 需要导入 / 容器看不到这个目录」，需要导入时用后台进程发起（index=true），下一次调用就能
    用上图谱。入参：repo_path / base_dir 指定目录（缺省为当前分析目录）、index 是否后台发起导入。
    """
    target = os.path.abspath(resolve(spec.get("repo_path") or "", spec.get("base_dir") or ""))
    if not os.path.isdir(target):
        return {"op": "prepare", "error": "目录不存在：" + target}
    if runner is None:
        return {"op": "prepare", "prepared": False, "graph_available": False, "repo_path": target,
                "error": ("图谱不可用：未找到 codebase-memory-mcp（" + BINARY_ENV +
                          " 未设置、config/mcp 里没有注册项、PATH 里也没有）。"),
                "hint": "容器部署：deploy/codebase-memory-mcp/install-docker.ps1；宿主安装：同目录 install.ps1。"}
    mount = mounted_path(target, runner)
    if mount is None:
        result = out_of_mount("prepare", target)
        result["prepared"] = False
        return result
    projects = list_projects(runner)
    existing = project_for_dir(projects, target)
    if existing and existing.get("name"):
        return {"op": "prepare", "prepared": True, "indexed": True, "ok": True, "action": "reuse",
                "project": existing.get("name"), "repo_path": host_path(mount),
                "note": "当前目录已导入图谱，后续读取会优先取图谱里的文件大纲。"}
    name = project_name_for_index(target, spec, projects)
    result = {"op": "prepare", "prepared": False, "indexed": False, "repo_path": host_path(mount), "project": name}
    if mount != target:
        result["host_repo_path"] = target
    if as_bool(spec.get("index"), False):
        ok, note = background_index(runner, mount, name)
        result["ok"] = ok
        result["action"] = "indexing" if ok else "index_failed"
        result["note"] = note
        if not ok:
            result["error"] = note
        return result
    result["action"] = "needs_index"
    result["note"] = ("当前目录还没导入图谱（本次调用仍会回退读本地文件）；"
                      "可传 index=true 让本工具后台发起导入，或直接运行 "
                      "deploy/codebase-memory-mcp/index-workdir.ps1。")
    return result


def simple_op(op, tool, flags, payload, runner, timeout=CLI_TIMEOUT_SECONDS):
    ok, text = run_cli(runner, tool, flags, payload, timeout=timeout)
    return {"op": op, "ok": ok, "result": as_json(text) or text}


def op_read(spec, runner):
    paths = spec.get("paths") or ([spec.get("path")] if spec.get("path") else [])
    if isinstance(paths, str):
        paths = [paths]
    if not paths:
        return {"op": "read", "error": "缺少 paths（要读取的文件路径或通配）"}
    base = spec.get("base_dir") or ""
    max_files = int(spec.get("max_files") or DEFAULT_MAX_FILES)
    max_bytes = int(spec.get("max_bytes") or DEFAULT_MAX_BYTES)
    targets = expand_targets(paths, base, max_files)

    project = project_of(spec, runner)
    project_name = project.get("name") if project else None
    project_root = project.get("root") if project else None
    files = []
    from_graph = 0
    for absolute, rel in targets:
        # 图谱按「项目内相对路径」寻址；在仓库子目录里启动时它与当前分析目录的相对路径并不相同，
        # 所以先按项目根换算一次，换不出来（项目根未知 / 跨盘）才退回按当前分析目录算出的相对路径。
        graph_path = graph_relative(absolute, project_root) or rel
        outline = None
        if project_name and not is_prose(absolute):
            outline = graph_outline(runner, project_name, graph_path)
        if outline is not None:
            files.append({"path": rel, "source": "graph", "outline": outline})
            from_graph += 1
        else:
            entry = {"path": rel, "source": "file"}
            entry.update(local_read(absolute, max_bytes))
            files.append(entry)

    if not targets:
        source = "none"
        note = "没有匹配到任何文件；请确认路径 / 通配写法，或先 op=index 建立图谱。"
    elif from_graph == len(targets):
        source = "graph"
        note = "以上信息全部来自本地代码知识图谱（op=outline 可看原始声明清单）。"
    elif from_graph == 0:
        source = "file"
        if runner is None:
            note = ("图谱不可用（未安装 / 未配置 CLARA_CBM_BINARY 或 config/mcp），已全部回退为本地文件读取；"
                    "需要图谱能力请先执行 deploy/codebase-memory-mcp/install.ps1。")
        elif not project_name:
            note = ("图谱里没有匹配的项目（当前分析目录未索引，或指定的项目名不存在），"
                    "已全部回退为本地文件读取。把当前目录导入图谱：先 op=prepare 查看，"
                    "再 op=index 发起导入，或直接运行 deploy/codebase-memory-mcp/index-workdir.ps1；"
                    "也可以用 project / " + PROJECT_ENV + " 指定 list_projects 返回的项目名。")
        else:
            note = "图谱未覆盖这些文件（多为未索引的新文件），已回退为本地文件读取。"
    else:
        source = "graph+file"
        note = "部分文件取自图谱（source=graph），图谱未覆盖的已回退本地文件（source=file）。"

    result = {"op": "read", "source": source, "targets": len(targets),
              "graph_files": from_graph, "files": files, "note": note,
              "project": project_name, "graph_available": bool(runner)}
    if len(targets) >= max_files:
        result["truncated"] = True
    return result


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except ValueError as exception:
        print(json.dumps({"error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return

    op = (spec.get("op") or "read").strip()
    runner = resolve_runner()

    # index / prepare 之外的操作都需要图谱可用；不可用时 read 自动回退文件，index、prepare 自带说明。
    if runner is None and op not in ("read", "prepare"):
        print(json.dumps({
            "op": op, "ok": False,
            "error": ("未找到 codebase-memory-mcp：请设置 " + BINARY_ENV +
                      "，或在 config/mcp/*.json 中注册该服务端，或把它放进 PATH；"
                      "安装脚本：宿主安装见 deploy/codebase-memory-mcp/install.ps1，"
                      "容器部署见同目录 install-docker.ps1。"),
        }, ensure_ascii=False))
        return

    # 图谱的多数工具都要求 --project：入参没给时按「索引根 = 当前分析目录」自动匹配，匹配不到再退回唯一项目。
    needs_project = op in ("search", "outline", "snippet", "trace", "query")
    project = project_of(spec, runner) if needs_project else None
    project_name = project.get("name") if project else None
    if needs_project and not project_name:
        print(json.dumps({
            "op": op, "ok": False,
            "error": ("未找到匹配的已索引项目：请先用 op=index 为当前分析目录建立图谱，"
                      "或用 project 指定 list_projects 返回的项目名。"),
        }, ensure_ascii=False))
        return

    try:
        if op == "read":
            result = op_read(spec, runner)
        elif op == "prepare":
            result = op_prepare(spec, runner)
        elif op == "index":
            result = op_index(spec, runner)
        elif op == "status":
            result = simple_op("status", "list_projects", [], None, runner)
        elif op == "search":
            pattern = spec.get("pattern") or ""
            # regex 由调用方显式开关：图谱对含 "|" 的非正则模式只做字面量匹配（会回一条 warning 且
            # 命中恒为 0），多关键词检索必须显式带上 regex=true 才等价于「任一命中」。
            regex = as_bool(spec.get("regex"), False)
            regex_flags = ["--regex"] if regex else []
            if spec.get("label"):
                result = simple_op("search", "search_graph",
                                   ["--project", project_name, "--name-pattern", pattern, "--label", spec.get("label")]
                                   + regex_flags,
                                   {"project": project_name, "name_pattern": pattern, "label": spec.get("label")},
                                   runner)
            else:
                result = simple_op("search", "search_code",
                                   ["--project", project_name, "--pattern", pattern] + regex_flags,
                                   {"project": project_name, "pattern": pattern, "regex": regex}, runner)
        elif op == "outline":
            file_path = spec.get("file_path") or ""
            result = simple_op("outline", "get_file_outline",
                               ["--project", project_name, "--file-path", file_path],
                               {"project": project_name, "file_path": file_path}, runner)
        elif op == "snippet":
            name = spec.get("qualified_name") or ""
            result = simple_op("snippet", "get_code_snippet",
                               ["--project", project_name, "--qualified-name", name],
                               {"project": project_name, "qualified_name": name}, runner)
        elif op == "trace":
            function_name = spec.get("function_name") or ""
            direction = normalize_direction(spec.get("direction"))
            flags = ["--project", project_name, "--function-name", function_name, "--direction", direction]
            payload = {"project": project_name, "function_name": function_name, "direction": direction}
            if spec.get("depth"):
                flags += ["--depth", str(spec.get("depth"))]
                payload["depth"] = spec.get("depth")
            result = simple_op("trace", "trace_path", flags, payload, runner)
        elif op == "query":
            query = spec.get("query") or ""
            result = simple_op("query", "query_graph",
                               ["--project", project_name, "--query", query],
                               {"project": project_name, "query": query}, runner)
        else:
            result = {"error": "不支持的 op：" + op}
    except (OSError, subprocess.SubprocessError) as exception:
        result = {"op": op, "error": "调用图谱失败：" + str(exception)}

    print(json.dumps(result, ensure_ascii=False))


main()