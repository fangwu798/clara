import json
import os
import re
import sys

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8，避免依赖系统区域设置。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python doc_outline.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "doc_outline",
    "name": "doc_outline",
    "type": "PYTHON",
    "description": "低成本阅读 Markdown/文本长文档：op=outline 返回文档标题大纲（层级/标题/行号），op=slice 按行区间返回带行号的原文片段。用于先看大文档结构、再按需读取局部，避免整篇读取浪费上下文。入参 JSON：op(可选，outline|slice，默认 outline)、path(必填，相对 base_dir 的文件路径)、base_dir(可选根目录，缺省为 CLARA_WORK_DIR；以 @repo 开头按仓库根解析)、encoding(可选，默认自动识别文件编码，需要时可显式指定 gbk、utf-8-sig 等)；outline 额外支持 max_level(默认 6，仅取不超过该层级的标题)、max_items(默认 500)；slice 额外支持 start(默认 1，起始行，含)、end(默认 start+max_lines-1，结束行，含)、max_lines(默认 200，单次上限)。返回：op=outline 时 {op,path,total_lines,headings:[{level,title,line}],count,truncated}；op=slice 时 {op,path,total_lines,start,end,lines:[{line,text}],truncated}。",
    "parameters": {
        "type": "object",
        "properties": {
            "op": {"type": "string", "enum": ["outline", "slice"]},
            "path": {"type": "string"},
            "base_dir": {"type": "string"},
            "encoding": {"type": "string"},
            "max_level": {"type": "integer"},
            "max_items": {"type": "integer"},
            "start": {"type": "integer"},
            "end": {"type": "integer"},
            "max_lines": {"type": "integer"},
        },
        "required": ["path"],
    },
    "metadata": {"domain": "sample", "tags": "doc,markdown,outline,python"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：本脚本位于 <repo>/config/skill/scripts/doc_outline.py，上溯三级即仓库根。
HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT
DEFAULT_BASE = os.environ.get("CLARA_WORK_DIR") or os.path.join(REPO_ROOT, "config", "sample", "workspace")
REPO_PREFIX = "@repo"

# Markdown ATX 标题：1-6 个 # 后接空格与标题文本。
ATX_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


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


def read_lines(path, explicit=None):
    # 读取文本行：未显式指定编码时自动识别（见 _textio），返回 (lines, 实际使用的编码)。
    text, used = _textio.read_text(path, explicit)
    return text.splitlines(), used


def outline(lines, max_level, max_items):
    items = []
    truncated = False
    in_fence = False
    for index, text in enumerate(lines):
        if text.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        match = ATX_HEADING.match(text)
        if not match:
            continue
        level = len(match.group(1))
        if level > max_level:
            continue
        items.append({"level": level, "title": match.group(2), "line": index + 1})
        if len(items) >= max_items:
            truncated = True
            break
    return items, truncated


def slice_lines(lines, start, end, max_lines):
    total = len(lines)
    start = max(1, start)
    end = min(total, end)
    truncated = False
    if end - start + 1 > max_lines:
        end = start + max_lines - 1
        truncated = True
    items = [{"line": number, "text": lines[number - 1][:1000]}
             for number in range(start, end + 1) if 0 < number <= total]
    return start, end, items, truncated


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传
        print(json.dumps({"op": "", "error": "入参 JSON 解析失败：" + str(exception)},
                         ensure_ascii=False))
        return

    op = spec.get("op") or "outline"
    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    explicit = spec.get("encoding")
    explicit = explicit.strip() if isinstance(explicit, str) and explicit.strip() else None
    result = {"op": op, "path": target}

    try:
        lines, used = read_lines(target, explicit)
        result["encoding"] = used
    except FileNotFoundError:
        result["error"] = "文件不存在：" + target
        print(json.dumps(result, ensure_ascii=False))
        return
    except UnicodeDecodeError as exception:
        result["error"] = ("按 encoding=%s 解码失败：%s；"
                           "若文件为其它编码，可留空 encoding 让脚本自动识别，"
                           "或指定 gbk/utf-8-sig 等" % (explicit, exception))
        print(json.dumps(result, ensure_ascii=False))
        return
    except OSError as exception:
        result["error"] = str(exception)
        print(json.dumps(result, ensure_ascii=False))
        return

    result["total_lines"] = len(lines)
    if op == "outline":
        items, truncated = outline(lines,
                                   int(spec.get("max_level") or 6),
                                   int(spec.get("max_items") or 500))
        result["headings"] = items
        result["count"] = len(items)
        result["truncated"] = truncated
    elif op == "slice":
        start = int(spec.get("start") or 1)
        end = int(spec.get("end") or (start + int(spec.get("max_lines") or 200) - 1))
        start, end, items, truncated = slice_lines(lines, start, end,
                                                    int(spec.get("max_lines") or 200))
        result["start"] = start
        result["end"] = end
        result["lines"] = items
        result["truncated"] = truncated
    else:
        result["error"] = "unknown op: " + str(op)
    print(json.dumps(result, ensure_ascii=False))


main()
