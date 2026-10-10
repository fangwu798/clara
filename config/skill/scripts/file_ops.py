import json
import os
import sys

# 默认工作区：相对本脚本位置推导，保证脚本无论被哪个工作目录拉起都指向同一处。
# 本脚本位于 <repo>/config/skill/scripts/file_ops.py，故工作区为 <repo>/config/sample/workspace。
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_BASE = os.path.normpath(os.path.join(HERE, "..", "..", "sample", "workspace"))


def resolve(path, base):
    if not path:
        return base
    if os.path.isabs(path) or not base:
        return path
    return os.path.join(base, path)


def main():
    spec = json.load(sys.stdin)
    op = spec.get("op", "")
    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    result = {"op": op, "path": target}
    try:
        if op == "write":
            directory = os.path.dirname(target)
            if directory:
                os.makedirs(directory, exist_ok=True)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(spec.get("content", ""))
            result["size"] = os.path.getsize(target)
        elif op == "read":
            with open(target, "r", encoding="utf-8") as handle:
                result["content"] = handle.read()
        elif op == "append":
            with open(target, "a", encoding="utf-8") as handle:
                handle.write(spec.get("content", ""))
            result["size"] = os.path.getsize(target)
        elif op == "replace":
            with open(target, "r", encoding="utf-8") as handle:
                text = handle.read()
            find = spec.get("find", "")
            result["occurrences"] = text.count(find)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(text.replace(find, spec.get("replace", "")))
        elif op == "delete":
            result["deleted"] = os.path.exists(target)
            if result["deleted"]:
                os.remove(target)
        elif op == "exists":
            result["exists"] = os.path.exists(target)
        elif op == "list":
            result["entries"] = sorted(os.listdir(target)) if os.path.isdir(target) else []
        else:
            result["error"] = "unknown op: " + str(op)
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    print(json.dumps(result, ensure_ascii=False))


main()
