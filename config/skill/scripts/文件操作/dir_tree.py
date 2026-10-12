import json
import os
import sys

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8，避免依赖系统区域设置。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python dir_tree.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "dir_tree",
    "name": "dir_tree",
    "type": "PYTHON",
    "description": "以缩进文本输出目录树，用于快速了解目录层级结构。相较 file_ops scan 的平铺列表，本工具保留层级关系，并可限制深度/条数。入参 JSON：path(可选，相对 base_dir 的目录，缺省为当前分析目录)、base_dir(可选根目录，缺省为 CLARA_WORK_DIR；以 @repo 开头按仓库根解析)、max_depth(默认 4，展示层级数)、max_entries(默认 1000，行数上限)、dirs_only(可选，默认 false，仅目录)、with_size(可选，默认 false，显示文件字节数)。默认跳过 .git/target/node_modules/logs 等噪声目录。返回：{root, tree:[缩进行], dir_count, file_count, truncated}。",
    "parameters": {
        "type": "object",
        "properties": {
            "path": {"type": "string"},
            "base_dir": {"type": "string"},
            "max_depth": {"type": "integer"},
            "max_entries": {"type": "integer"},
            "dirs_only": {"type": "boolean"},
            "with_size": {"type": "boolean"},
        },
        "required": [],
    },
    "metadata": {"domain": "sample", "tags": "tree,directory,python"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT
DEFAULT_BASE = os.environ.get("CLARA_WORK_DIR") or os.path.join(REPO_ROOT, "config", "sample", "workspace")
REPO_PREFIX = "@repo"

DEFAULT_SKIP_DIRS = [
    ".git", ".idea", ".m2", "target", "node_modules", "dist", "build",
    "logs", "volumes", "__pycache__", ".clara",
]


def resolve_root(base):
    if base.startswith(REPO_PREFIX):
        rest = base[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return base if os.path.isabs(base) else os.path.join(WORK_ROOT, base)


def resolve(path, base):
    if path and path.startswith(REPO_PREFIX):
        rest = path[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    root = resolve_root(base)
    if not path:
        return root
    return path if os.path.isabs(path) else os.path.join(root, path)


def build_tree(root, max_depth, max_entries, dirs_only, with_size, skip_dirs):
    # 以缩进文本行返回目录树，控制条数上限，避免超大仓库撑爆输出。
    lines = []
    counters = {"dir": 0, "file": 0}
    truncated = [False]

    def walk(current, depth):
        if truncated[0]:
            return
        try:
            entries = sorted(os.listdir(current))
        except OSError:
            return
        dirs = [name for name in entries
                if os.path.isdir(os.path.join(current, name))
                and name not in skip_dirs and not name.startswith(".")]
        files = [] if dirs_only else [name for name in entries
                                      if os.path.isfile(os.path.join(current, name))]
        for name in dirs:
            if len(lines) >= max_entries:
                truncated[0] = True
                return
            lines.append("  " * depth + name + "/")
            counters["dir"] += 1
            if depth < max_depth:
                walk(os.path.join(current, name), depth + 1)
        for name in files:
            if len(lines) >= max_entries:
                truncated[0] = True
                return
            suffix = ""
            if with_size:
                try:
                    suffix = " (%d B)" % os.path.getsize(os.path.join(current, name))
                except OSError:
                    suffix = ""
            lines.append("  " * depth + name + suffix)
            counters["file"] += 1

    walk(root, 0)
    return lines, counters, truncated[0]


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传
        print(json.dumps({"error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return

    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    skip_dirs = set(spec.get("skip_dirs") or DEFAULT_SKIP_DIRS)
    max_depth = int(spec.get("max_depth") or 4)
    max_entries = int(spec.get("max_entries") or 1000)

    result = {"root": target}
    if not os.path.isdir(target):
        result["error"] = "目录不存在：" + target
        print(json.dumps(result, ensure_ascii=False))
        return

    lines, counters, truncated = build_tree(
        target, max_depth, max_entries,
        bool(spec.get("dirs_only")), bool(spec.get("with_size")), skip_dirs)
    result["tree"] = lines
    result["dir_count"] = counters["dir"]
    result["file_count"] = counters["file"]
    result["truncated"] = truncated
    print(json.dumps(result, ensure_ascii=False))


main()
