#!/usr/bin/env python3
"""ComfyUI 工作流命令行：列出、查看参数、运行、下载产物。

工作流来自 config/comfui-api 下 ComfyUI 的 `Workflow -> Export (API)` 文件，
节点绑定由 comfyui_video.py 自动识别。用法：

  python comfyui_cli.py list
  python comfyui_cli.py inspect image_qwen_image_2_1_t2i
  python comfyui_cli.py run image_qwen_image_2_1_t2i --prompt "a serene koi pond" --wait
  python comfyui_cli.py run video_minimax_h3_r2v --prompt "..." --image a.png --image b.png --wait
  python comfyui_cli.py run image_qwen_image_2_1_t2i --prompt "..." --param aspect_ratio="9:16 (Portrait Widescreen)" --param seed=7
  python comfyui_cli.py status <prompt_id>
  python comfyui_cli.py fetch <prompt_id> --out output/comfyui/demo

参数说明：
  --param 可重复，键取自 `inspect` 列出的参数名；值会按 int -> float -> str 自动转型。
  --image 可重复，依次对应识别到的 image / image2 / ...；传本地路径会先上传。
  --wait  提交后每 --interval 秒（默认 60）轮询一次，成功后自动下载；不加则只打印 prompt_id。
  --timeout  --wait 的最长等待秒数（默认 1800）；超时会提示任务仍在运行，可用 prompt_id 续查。
"""

import argparse
import json
import sys
import time
import urllib.error

# 技能加载器会用 --describe 探测 scripts 下的每个 .py；这里先静默退出，
# 避免导入 comfyui_video 时把它的工具声明当成这个脚本的输出。
if "--describe" in sys.argv[1:]:
    sys.exit(0)

import comfyui_video as cv


def _load_config(args):
    config = cv.load_config()
    if getattr(args, "server", None):
        config = dict(config)
        config["server"] = args.server
    return config


def _call(config, payload):
    handler = cv.OPERATIONS.get(payload.get("op"))
    if handler is None:
        raise cv.Failure("未知操作：" + str(payload.get("op")))
    return handler(config, payload)


def _coerce(text):
    for cast in (int, float):
        try:
            return cast(text)
        except ValueError:
            continue
    return text


def _parse_params(pairs):
    params = {}
    for pair in pairs or []:
        if "=" not in pair:
            raise cv.Failure("--param 需要 key=value 形式：" + pair)
        key, _, value = pair.partition("=")
        params[key.strip()] = _coerce(value.strip())
    return params


def _print(text):
    print(text)


def cmd_list(config, args):
    result = _call(config, {"op": "list"})
    if args.json:
        _print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    _print("服务地址：" + str(result.get("server")))
    for item in result.get("workflows", []):
        if item.get("skipped"):
            _print("  [跳过] " + item["id"] + "（" + item["skipped"] + "）")
            continue
        bindings = item["bindings"]
        _print("  " + item["id"] + "  " + str(item["nodes"]) + " 节点")
        _print("      正向提示词：" + str(bindings.get("positive")))
        _print("      负向提示词：" + str(bindings.get("negative")))
        if bindings.get("images"):
            pairs = [key + "=" + str(bindings.get("image_labels", {}).get(key) or key)
                     for key in bindings["images"]]
            _print("      参考图：" + ", ".join(pairs))
        _print("      可调参数：" + ", ".join(sorted(bindings.get("params") or {})))
    return 0


def cmd_inspect(config, args):
    result = _call(config, {"op": "inspect", "workflow": args.workflow})
    if args.json:
        _print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    _print("工作流：" + result["workflow"] + "（" + str(result["nodes"]) + " 节点，" + result["file"] + "）")
    for role in ("positive", "negative"):
        info = (result.get("prompt") or {}).get(role)
        if info:
            _print("  " + role + "：" + info["path"] + "，" + str(info["chars"]) + " 字")
    for key, info in (result.get("images") or {}).items():
        _print("  参考图 " + key + " → " + str(info.get("label")) + "：" + info["path"]
               + "（当前 " + str(info.get("current")) + "）")
    for item in result.get("switch_overrides") or []:
        _print("  开关覆盖：" + str(item[0]) + " = " + str(item[1]))
    _print("  可调参数：")
    for name, info in sorted((result.get("params") or {}).items()):
        detail = info.get("type", "?") + "  当前 " + str(info.get("current"))
        if "min" in info or "max" in info:
            detail += "  范围 " + str(info.get("min", "-")) + " ~ " + str(info.get("max", "-"))
        if "step" in info:
            detail += "  步长 " + str(info.get("step"))
        if info.get("options"):
            options = info["options"]
            shown = ", ".join(options[:6]) + (" ..." if len(options) > 6 else "")
            detail += "  可选：" + shown
        _print("    " + name.ljust(16) + detail + "  (" + info["path"] + ")")
    for warning in result.get("warnings") or []:
        _print("  [提醒] " + warning)
    return 0


def cmd_run(config, args):
    payload = {"op": "submit", "workflow": args.workflow, "prompt": args.prompt}
    if args.negative:
        payload["negative"] = args.negative
    if args.image:
        if len(args.image) == 1:
            payload["image"] = args.image[0]
        else:
            payload["images"] = list(args.image)
    params = _parse_params(args.param)
    if params:
        payload["params"] = params
    result = _call(config, payload)
    if not result.get("ok"):
        _print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    prompt_id = result["prompt_id"]
    _print("已提交：" + str(prompt_id) + "（队列号 " + str(result.get("number")) + "）")
    for warning in result.get("warnings") or []:
        _print("  [提醒] " + warning)
    if not args.wait:
        return 0
    return _wait_and_fetch(config, prompt_id, args)


def _wait_and_fetch(config, prompt_id, args):
    deadline = time.time() + args.timeout
    state = "unknown"
    while time.time() < deadline:
        status = _call(config, {"op": "status", "prompt_id": prompt_id})
        state = status.get("state")
        if state == "success":
            break
        if state == "error":
            _print("执行失败：" + json.dumps(status.get("error"), ensure_ascii=False))
            return 1
        print("  状态：" + str(state), file=sys.stderr)
        time.sleep(args.interval)
    if state != "success":
        _print("等待超时，任务仍在运行：" + prompt_id)
        return 1
    fetched = _call(config, {"op": "fetch", "prompt_id": prompt_id, "out": args.out} if args.out
                    else {"op": "fetch", "prompt_id": prompt_id})
    _print("已下载：")
    for item in fetched.get("files") or []:
        _print("  " + item["path"] + "（" + str(item["bytes"]) + " 字节）")
    return 0


def cmd_status(config, args):
    result = _call(config, {"op": "status", "prompt_id": args.prompt_id})
    _print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("ok") else 1


def cmd_fetch(config, args):
    payload = {"op": "fetch", "prompt_id": args.prompt_id}
    if args.out:
        payload["out"] = args.out
    result = _call(config, payload)
    if args.json:
        _print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    for item in result.get("files") or []:
        _print(item["path"] + "（" + str(item["bytes"]) + " 字节）")
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description="Clara × ComfyUI 命令行",
                                     formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog=__doc__)
    parser.add_argument("--server", help="覆盖 clara-comfui.yml 里的服务地址")
    sub = parser.add_subparsers(dest="command", required=True)

    listing = sub.add_parser("list", help="列出工作流与自动识别到的绑定")
    listing.add_argument("--json", action="store_true", help="输出原始 JSON")
    listing.set_defaults(func=cmd_list)

    inspect = sub.add_parser("inspect", help="查看可改参数的类型、范围、枚举与当前值")
    inspect.add_argument("workflow")
    inspect.add_argument("--json", action="store_true", help="输出原始 JSON")
    inspect.set_defaults(func=cmd_inspect)

    run = sub.add_parser("run", help="替换提示词/参考图/参数后提交")
    run.add_argument("workflow")
    run.add_argument("--prompt", required=True, help="正向提示词")
    run.add_argument("--negative", help="负向提示词（工作流支持时）")
    run.add_argument("--image", action="append", help="参考图本地路径，可重复")
    run.add_argument("--param", action="append", help="参数 key=value，可重复")
    run.add_argument("--wait", action="store_true", help="轮询到完成并自动下载")
    run.add_argument("--timeout", type=int, default=1800, help="--wait 的最长等待秒数")
    run.add_argument("--interval", type=float, default=60.0, help="--wait 的轮询间隔秒数，默认 60")
    run.add_argument("--out", help="下载目录（默认 clara-comfui.yml 的 outputDir）")
    run.set_defaults(func=cmd_run)

    status = sub.add_parser("status", help="查询一次任务进度")
    status.add_argument("prompt_id")
    status.set_defaults(func=cmd_status)

    fetch = sub.add_parser("fetch", help="下载任务产物")
    fetch.add_argument("prompt_id")
    fetch.add_argument("--out", help="下载目录")
    fetch.add_argument("--json", action="store_true", help="输出原始 JSON")
    fetch.set_defaults(func=cmd_fetch)
    return parser


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    # 技能加载器会用 --describe 探测每个脚本；这里静默退出，表示「不是技能工具」。
    if "--describe" in argv:
        return 0
    args = build_parser().parse_args(argv)
    try:
        return args.func(_load_config(args), args)
    except cv.Failure as failure:
        print(json.dumps({"ok": False, "error": str(failure)}, ensure_ascii=False))
        return 1
    except urllib.error.URLError as error:
        print(json.dumps({"ok": False, "error": "连接 ComfyUI 失败：" + str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
