"""Clara × ComfyUI 技能：改写 config/comfui-api 下导出的 API 工作流并提交执行。

工作方式：
- 工作流来自 ComfyUI 的 Workflow → Export (API)，原样放在 config/comfui-api；
- config/clara-comfui.yml 只配服务器地址与目录，节点绑定由脚本自动识别
  （正向/负向提示词、LoadImage 参考图、宽高/种子/步数/时长等参数）；
- 只改写识别到的入参，其余节点保持导出时的样子；不校验模型文件是否存在（交给 ComfyUI 自己报错）；
- 提交与等待分离：submit 秒回 prompt_id，status 轮询，避免宿主 30 秒脚本超时。
"""

import json
import mimetypes
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SCRIPTS_ROOT = os.path.dirname(SCRIPT_DIR)
CONFIG_ROOT = os.path.dirname(os.path.dirname(SCRIPTS_ROOT))
REPO_ROOT = os.path.dirname(CONFIG_ROOT)
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

_textio.force_utf8_stdio()

try:
    import yaml
except ImportError:
    yaml = None

_TOOL = {
    "id": "comfyui_video",
    "name": "comfyui_video",
    "type": "PYTHON",
    "description": (
        "本地 ComfyUI 工作流调用：自动识别 config/comfui-api 下导出的 API 工作流"
        "（提示词节点、LoadImage 参考图、宽高/种子/步数/时长等参数），替换后提交生成图片/视频。"
        "op=list 列出工作流及自动识别到的绑定；op=inspect 列出每个可改参数的类型/范围/枚举与当前值；"
        "op=probe 看服务端版本/设备/节点数；"
        "op=validate 检查工作流结构能否执行（不校验模型是否存在）；"
        "op=submit 改写提示词与参数后提交，秒回 prompt_id；"
        "op=status 查一次进度（pending/running/success/error）；"
        "op=submit 对视频默认阻塞轮询（每 60 秒一次）到完成并自动下载，传 wait=false 可只提交；"
        "中断或超时后用 op=wait 按 prompt_id 继续等；"
        "op=fetch 下载产物到本地；"
        "op=upload 上传首帧/参考图；op=submit 时 image/images 传本地路径也会自动上传；"
        "op=plan_check 校验分镜表；"
        "op=assemble 用本机 ffmpeg 拼接片段（可选 BGM/字幕）。"
        "渲染要几分钟，提交后用 status 轮询，不要一次调用里等。"
        "另需填写 purpose（一句话说明本次调用目的，会展示给用户）。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {
                "type": "string",
                "enum": ["list", "inspect", "probe", "plan_check", "validate", "upload",
                         "submit", "wait", "status", "fetch", "assemble"],
            },
            "server": {"type": "string"},
            "workflow": {"type": "string"},
            "prompt": {"type": "string"},
            "negative": {"type": "string"},
            "image": {"type": "string"},
            "images": {"type": "array", "items": {"type": "string"}},
            "params": {"type": "object"},
            "overrides": {"type": "object"},
            "shot_spec": {"type": "object"},
            "file": {"type": "string"},
            "files": {"type": "array", "items": {"type": "string"}},
            "prompt_id": {"type": "string"},
            "out": {"type": "string"},
            "audio": {"type": "string"},
            "subtitle": {"type": "string"},
            "timeout_seconds": {"type": "integer"},
            "wait": {"type": "boolean"},
            "poll_seconds": {"type": "integer"},
            "max_wait_seconds": {"type": "integer"},
            "fetch": {"type": "boolean"},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "media", "tags": "comfyui,video,image,workflow,python",
                 "timeoutMillis": "1800000"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

CONFIG_CANDIDATES = (
    os.path.join(CONFIG_ROOT, "clara-comfui.yml"),
    os.path.join(CONFIG_ROOT, "clara-comfui.yaml"),
)

# 模型文件名不在这里校验：ComfyUI 会在提交时给出缺模型的节点错误。
MODEL_FIELDS = (
    "unet_name", "clip_name", "clip_name1", "clip_name2", "vae_name",
    "lora_name", "ckpt_name", "upscale_model_name", "model_name",
)

TRANSITIONS = ("cut", "dissolve", "fade")

# 文本输入可能出现的字段名；不同自定义节点用 text / prompt / positive_prompt 等。
TEXT_FIELDS = ("text", "prompt", "positive_prompt", "negative_prompt")

# 负向提示词节点常见的字面特征（没有 CLIPTextEncode 连线时兜底判断）。
NEGATIVE_HINTS = ("negative", "worst quality", "low quality", "blurry", "watermark",
                  "bad anatomy", "deformed", "低质量", "模糊", "水印")

# 常见可覆盖参数的字段名 → 对外使用的参数名（取节点 id 最小的那个）。
PARAM_FIELDS = {
    "width": ("width",),
    "height": ("height",),
    "seed": ("seed", "noise_seed"),
    "steps": ("steps",),
    "cfg": ("cfg",),
    "fps": ("fps", "frame_rate"),
    "batch_size": ("batch_size",),
    "aspect_ratio": ("aspect_ratio",),
    "megapixels": ("megapixels",),
    "denoise": ("denoise",),
    "length": ("length", "frames", "frame_count", "video_frames"),
    "sampler_name": ("sampler_name",),
    "scheduler": ("scheduler",),
    "filename_prefix": ("filename_prefix",),
}

# 值本身是字符串的参数（枚举 / 名称）；其余参数按数值识别。
STRING_PARAMS = ("aspect_ratio", "sampler_name", "scheduler", "filename_prefix")


class Failure(Exception):
    """可预期的失败：消息直接回传给调用方，不带堆栈。"""


def _strip_comment(line):
    """去掉行尾注释，但保留引号内的 #。"""
    quote = None
    for index, char in enumerate(line):
        if quote:
            if char == quote:
                quote = None
            continue
        if char in ("'", '"'):
            quote = char
        elif char == "#":
            return line[:index]
    return line


def _parse_scalar(text):
    if text == "":
        return None
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        return text[1:-1]
    lowered = text.lower()
    if lowered in ("true", "yes", "on"):
        return True
    if lowered in ("false", "no", "off"):
        return False
    if lowered in ("null", "~"):
        return None
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        pass
    return text


def _parse_block(lines, index, indent):
    result = {}
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        current = len(line) - len(line.lstrip())
        if current < indent:
            break
        if current > indent:
            raise Failure("YAML 缩进不一致（第 " + str(index + 1) + " 行）")
        content = line.strip()
        if ":" not in content:
            raise Failure("YAML 只支持 key: value（第 " + str(index + 1) + " 行：" + content + "）")
        key, _, rest = content.partition(":")
        key = key.strip()
        rest = rest.strip()
        if rest:
            result[key] = _parse_scalar(rest)
            index += 1
        else:
            index += 1
            child_indent = indent + 2
            while index < len(lines) and not lines[index].strip():
                index += 1
            if index < len(lines):
                child_indent = len(lines[index]) - len(lines[index].lstrip())
            if index >= len(lines) or child_indent <= indent:
                result[key] = {}
            else:
                child, index = _parse_block(lines, index, child_indent)
                result[key] = child
    return result, index


def parse_simple_yaml(text):
    """无 PyYAML 时的兜底：只支持缩进映射、标量与行内注释。"""
    lines = []
    for raw in text.splitlines():
        line = _strip_comment(raw).rstrip()
        if line.strip():
            lines.append(line.replace("\t", "  "))
    parsed, _ = _parse_block(lines, 0, len(lines[0]) - len(lines[0].lstrip()) if lines else 0)
    return parsed


def load_config():
    path = None
    for candidate in CONFIG_CANDIDATES:
        if os.path.isfile(candidate):
            path = candidate
            break
    if path is None:
        raise Failure("找不到配置文件：config/clara-comfui.yml")
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    if yaml is not None:
        try:
            config = yaml.safe_load(text)
        except Exception as exception:  # noqa: BLE001 - 把解析错误转成可读消息
            raise Failure("config/clara-comfui.yml 解析失败：" + str(exception))
    else:
        config = parse_simple_yaml(text)
    if not isinstance(config, dict):
        raise Failure("config/clara-comfui.yml 必须是键值结构")
    return config


def require_enabled(config):
    if config.get("enabled") is False:
        raise Failure("config/clara-comfui.yml 里 enabled 为 false，ComfyUI 调用已关闭")


def server_url(config, params):
    url = params.get("server") or config.get("server")
    if not url:
        raise Failure("没有配置 ComfyUI 服务地址（config/clara-comfui.yml 的 server）")
    return str(url).rstrip("/")


def request_timeout(config, params, minimum=30):
    return max(minimum, int(params.get("timeout_seconds") or config.get("requestTimeoutSeconds") or 30))


def http_request(url, payload=None, headers=None, timeout=30, method=None, body=None):
    data = body
    request_headers = dict(headers or {})
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def get_json(url, timeout=30):
    status, body = http_request(url, timeout=timeout)
    if status >= 400:
        raise Failure("请求失败 " + str(status) + "：" + url)
    try:
        return json.loads(body.decode("utf-8"))
    except ValueError:
        raise Failure("响应不是合法 JSON：" + url)


def post_json(url, payload, timeout=30):
    status, body = http_request(url, payload=payload, timeout=timeout)
    try:
        parsed = json.loads(body.decode("utf-8"))
    except ValueError:
        parsed = {"raw": body.decode("utf-8", "replace")}
    return status, parsed


def api_dir(config):
    folder = config.get("apiDir") or "config/comfui-api"
    return folder if os.path.isabs(folder) else os.path.join(REPO_ROOT, folder)


def output_dir(config, params):
    folder = params.get("out") or config.get("outputDir") or "output/comfyui"
    return folder if os.path.isabs(folder) else os.path.join(REPO_ROOT, folder)


def registry(config):
    """YAML 中可选的 workflows 覆盖段；默认空，绑定完全依靠自动识别。"""
    workflows = config.get("workflows") or {}
    if not isinstance(workflows, dict):
        raise Failure("config/clara-comfui.yml 里的 workflows 必须是映射（键为工作流 id）")
    return workflows


def _node_sort_key(node_id):
    """节点 id 自然序：9 排在 92 之前，140:119 按数字比较。"""
    parts = re.split(r"(\d+)", str(node_id))
    return [(0, int(part)) if part.isdigit() else (1, part) for part in parts if part != ""]


def _iter_inputs(graph):
    for node_id, node in graph.items():
        for name, value in (node.get("inputs") or {}).items():
            yield node_id, node, name, value


def workflow_id_of(file_name):
    stem = os.path.splitext(os.path.basename(file_name))[0]
    slug = re.sub(r"[^0-9A-Za-z]+", "_", stem).strip("_").lower()
    return slug or stem


def read_api_workflow(path):
    """读 API 格式工作流；不是 API 格式时返回 None（例如分镜样例文件）。"""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or not data:
        return None
    if not all(isinstance(value, dict) and value.get("class_type") for value in data.values()):
        return None
    return data


def _looks_negative(text):
    lowered = text.lower()
    return any(hint in lowered for hint in NEGATIVE_HINTS)


def _text_field(graph, node_id):
    inputs = graph.get(node_id, {}).get("inputs") or {}
    for field in TEXT_FIELDS:
        if isinstance(inputs.get(field), str):
            return field
    return None


GENERATOR_HINTS = ("textgenerate", "text_generate", "promptenhance", "prompt_enhance",
                   "ollama", "llmnode", "chatglm")


def _literal_text_field(graph, node_id):
    """节点自身携带的字面量文本字段（CLIPTextEncode.text、PrimitiveString.value 等）。"""
    inputs = (graph.get(node_id) or {}).get("inputs") or {}
    for field in ("text", "string", "value"):
        if isinstance(inputs.get(field), str):
            return field
    return None


def _switch_branch(graph, node_id):
    """取 Switch 节点的字面量分支；同时给出让该分支生效的开关覆盖。"""
    inputs = (graph.get(node_id) or {}).get("inputs") or {}
    branch_field = None
    for field in ("on_false", "on_true"):
        if isinstance(inputs.get(field), str):
            branch_field = field
            break
    if branch_field is None:
        return None, []
    want = branch_field == "on_true"
    overrides = []
    switch_value = inputs.get("switch")
    if isinstance(switch_value, bool):
        if switch_value != want:
            overrides.append([node_id + ".switch", want])
    elif isinstance(switch_value, list) and len(switch_value) == 2 and isinstance(switch_value[0], str):
        source = graph.get(switch_value[0]) or {}
        if source.get("class_type") == "PrimitiveBoolean":
            if (source.get("inputs") or {}).get("value") is not want:
                overrides.append([switch_value[0] + ".value", want])
    return node_id + "." + branch_field, overrides


def _resolve_text_literal(graph, node_id, depth=4):
    """顺上游找到真正可写的文本位置，返回 (路径, 开关覆盖列表)。"""
    if depth <= 0 or node_id not in graph:
        return None, []
    node = graph.get(node_id) or {}
    class_type = str(node.get("class_type") or "").lower()
    if any(hint in class_type for hint in GENERATOR_HINTS):
        return None, []
    field = _literal_text_field(graph, node_id)
    if field:
        return node_id + "." + field, []
    inputs = node.get("inputs") or {}
    if "on_false" in inputs or "on_true" in inputs:
        return _switch_branch(graph, node_id)
    for value in inputs.values():
        if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
            path, overrides = _resolve_text_literal(graph, value[0], depth - 1)
            if path:
                return path, overrides
    return None, []


def _conditioning_roles(graph):
    """采样器的 positive / negative 连线指向谁，用来给纯 text 字段定角色。"""
    roles = {}
    for node_id, node, name, value in _iter_inputs(graph):
        if name not in ("positive", "negative"):
            continue
        if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
            if value[0] in graph:
                roles.setdefault(value[0], name)
    return roles


def _image_labels(graph):
    """参考图被哪个输入消费，例如 ref_images.ref_image_0 → ref_image_0。"""
    labels = {}
    for node_id, node, name, value in _iter_inputs(graph):
        if not (isinstance(value, list) and len(value) == 2 and isinstance(value[0], str)):
            continue
        source = graph.get(value[0]) or {}
        if source.get("class_type") == "LoadImage":
            labels.setdefault(value[0], name.split(".")[-1])
    return labels


def detect_bindings(graph):
    """自动识别提示词、参考图与可覆盖参数；返回 (bindings, warnings)。"""
    warnings = []
    roles = _conditioning_roles(graph)

    slots = []
    for node_id, node, name, value in _iter_inputs(graph):
        if name not in TEXT_FIELDS:
            continue
        class_type = str(node.get("class_type") or "").lower()
        if any(hint in class_type for hint in GENERATOR_HINTS):
            continue
        negative = "negative" in name
        if isinstance(value, str):
            slots.append({"node": node_id, "field": name, "path": node_id + "." + name,
                          "overrides": [], "negative": negative})
        elif isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
            if value[0] not in graph:
                continue
            path, overrides = _resolve_text_literal(graph, value[0])
            if path:
                slots.append({"node": node_id, "field": name, "path": path,
                              "overrides": overrides, "negative": negative})
            else:
                warnings.append("节点 " + str(node_id) + "（" + str(node.get("class_type"))
                                + "）的 " + name + " 输入是连线，但没有找到可写的文本节点")
    slots.sort(key=lambda item: _node_sort_key(item["node"]))

    positive = None
    negative = None
    switch_overrides = []
    used = set()
    for slot in slots:
        path = slot["path"]
        if path in used:
            continue
        if slot["negative"]:
            if negative is None:
                negative = slot
                used.add(path)
            continue
        if positive is None:
            role = roles.get(slot["node"])
            if role == "negative" and negative is None:
                negative = slot
            else:
                positive = slot
            used.add(path)

    if positive is None:
        for slot in slots:
            if slot["path"] not in used:
                positive = slot
                used.add(slot["path"])
                break
    if negative is not None and positive is not None and negative["path"] == positive["path"]:
        negative = None
    for slot in (positive, negative):
        if slot is not None:
            switch_overrides.extend(slot["overrides"])

    images = {}
    load_nodes = sorted(
        [node_id for node_id, node in graph.items() if node.get("class_type") == "LoadImage"],
        key=_node_sort_key)
    labels = _image_labels(graph)
    image_labels = {}
    for index, node_id in enumerate(load_nodes):
        key = "image" if index == 0 else "image" + str(index + 1)
        images[key] = node_id + ".image"
        if node_id in labels:
            image_labels[key] = labels[node_id]

    params = dict(images)
    for name, aliases in PARAM_FIELDS.items():
        hits = []
        for node_id, node, field, value in _iter_inputs(graph):
            if field not in aliases:
                continue
            if name in STRING_PARAMS:
                if isinstance(value, str):
                    hits.append((node_id, field))
            elif isinstance(value, (int, float)) and not isinstance(value, bool):
                hits.append((node_id, field))
        if hits:
            hits.sort(key=lambda item: _node_sort_key(item[0]))
            params[name] = hits[0][0] + "." + hits[0][1]
            if len(hits) > 1:
                warnings.append("参数 " + name + " 有多个候选，取 " + params[name]
                                + "（其余：" + ", ".join(h[0] + "." + h[1] for h in hits[1:]) + "）")

    # 时长：常见写法是「PrimitiveFloat → ComfyMathExpression 的 values.x」表示秒数。
    for node_id, node in graph.items():
        if node.get("class_type") != "ComfyMathExpression":
            continue
        for field, value in (node.get("inputs") or {}).items():
            if not field.startswith("values.") or not isinstance(value, list) or len(value) != 2:
                continue
            source = graph.get(value[0]) or {}
            if source.get("class_type") in ("PrimitiveFloat", "PrimitiveInt"):
                params.setdefault("duration", value[0] + ".value")

    bindings = {
        "positive": positive["path"] if positive else None,
        "negative": negative["path"] if negative else None,
        "images": images,
        "image_labels": image_labels,
        "params": params,
        "switch_overrides": switch_overrides,
    }
    if not bindings["positive"]:
        warnings.append("没有识别到正向提示词节点（该工作流可能不接受文本输入）")
    if not bindings["negative"]:
        warnings.append("没有识别到负向提示词节点（该工作流不支持负向提示词）")
    return bindings, warnings


def discover(config):
    """扫描 apiDir，返回可执行工作流与识别结果。"""
    folder = api_dir(config)
    if not os.path.isdir(folder):
        raise Failure("工作流目录不存在：" + folder)
    entries = []
    for file_name in sorted(os.listdir(folder)):
        if not file_name.lower().endswith(".json"):
            continue
        path = os.path.join(folder, file_name)
        entry = {"id": workflow_id_of(file_name), "file": file_name, "path": path}
        graph = read_api_workflow(path)
        if graph is None:
            entry["skipped"] = "不是 API 格式工作流（没有 class_type 节点）"
            entries.append(entry)
            continue
        bindings, warnings = detect_bindings(graph)
        entry["graph"] = graph
        entry["bindings"] = bindings
        entry["warnings"] = warnings
        entries.append(entry)
    return entries


def resolve_workflow(config, params):
    """按 id / 文件名定位工作流；绑定来自自动识别，YAML 仅作可选覆盖。"""
    name = params.get("workflow") or params.get("template")
    entries = [entry for entry in discover(config) if "graph" in entry]
    if not entries:
        raise Failure("config/comfui-api 下没有可执行的 API 工作流（请用 ComfyUI 的 Export (API) 导出）")
    if name:
        wanted = str(name).strip().lower()
        matched = None
        for entry in entries:
            if wanted in (entry["id"], entry["file"].lower(),
                          os.path.splitext(entry["file"])[0].lower()):
                matched = entry
                break
        if matched is None:
            available = ", ".join(entry["id"] for entry in entries)
            raise Failure("找不到工作流 " + str(name) + "，可用：" + available)
    elif len(entries) == 1:
        matched = entries[0]
    else:
        available = ", ".join(entry["id"] for entry in entries)
        raise Failure("缺少 workflow（当前有多个工作流，必须指定）：" + available)

    override = registry(config).get(matched["id"]) or {}
    prompts = {
        "positive": matched["bindings"].get("positive"),
        "negative": matched["bindings"].get("negative"),
    }
    prompts.update({key: value for key, value in (override.get("prompts") or {}).items() if value})
    params_map = dict(matched["bindings"].get("params") or {})
    params_map.update(override.get("params") or {})
    definition = {
        "prompts": prompts,
        "params": params_map,
        "defaults": override.get("defaults") or {},
        "switch_overrides": (override.get("switch_overrides")
                             or matched["bindings"].get("switch_overrides") or []),
    }
    return matched, definition


def binding_summary(entry):
    """把自动识别结果整理成给模型/人看的形式（参数里不再重复列参考图）。"""
    bindings = entry.get("bindings") or {}
    images = bindings.get("images") or {}
    params_map = bindings.get("params") or {}
    return {
        "positive": bindings.get("positive"),
        "negative": bindings.get("negative"),
        "images": dict(images),
        "image_labels": dict(bindings.get("image_labels") or {}),
        "params": {key: value for key, value in params_map.items() if key not in images},
        "switch_overrides": list(bindings.get("switch_overrides") or []),
    }


def set_input(graph, path, value):
    node_id, _, field = path.rpartition(".")
    node = graph.get(node_id)
    if node is None:
        raise Failure("工作流里没有节点 " + node_id + "（绑定 " + path + "）")
    node.setdefault("inputs", {})[field] = value


def _image_pairs(params, entry_labels=None):
    pairs = []
    single = params.get("image")
    if isinstance(single, str) and single.strip():
        pairs.append(("image", single.strip()))
    many = params.get("images")
    if isinstance(many, dict):
        labels = (entry_labels or {})
        for key, value in many.items():
            if not value:
                continue
            slot = next((name for name, label in labels.items() if label == str(key)), str(key))
            pairs.append((slot, value))
    elif isinstance(many, list):
        for index, value in enumerate(many):
            if value:
                pairs.append(("image" if index == 0 else "image" + str(index + 1), value))
    return pairs


def apply_bindings(entry, definition, params):
    """把提示词 / 参考图 / 参数写进工作流；返回告警列表。"""
    graph = entry["graph"]
    warnings = []
    for path, value in (definition.get("defaults") or {}).items():
        set_input(graph, str(path), value)
    # Switch 类工作流：写入字面量分支的同时把开关拨到该分支，否则文本会被另一条分支覆盖。
    for item in definition.get("switch_overrides") or []:
        switch_path, switch_value = item[0], item[1]
        set_input(graph, str(switch_path), switch_value)

    prompts = definition.get("prompts") or {}
    positive_path = prompts.get("positive")
    negative_path = prompts.get("negative")

    text = params.get("prompt")
    if text:
        if positive_path:
            set_input(graph, positive_path, text)
        else:
            raise Failure("工作流没有识别到正向提示词节点，无法写入 prompt")
    negative = params.get("negative")
    if negative:
        if negative_path:
            set_input(graph, negative_path, negative)
        else:
            warnings.append("工作流没有识别到负向提示词节点，已忽略 negative")

    images = (entry.get("bindings") or {}).get("images") or {}
    for key, value in _image_pairs(params, entry.get("bindings", {}).get("image_labels")):
        path = images.get(key)
        if path:
            set_input(graph, path, value)
        else:
            warnings.append("工作流没有参考图输入 " + key + "（已识别：" +
                            (", ".join(sorted(images)) or "无") + "）")

    bound = definition.get("params") or {}
    provided = params.get("params") or {}
    if not isinstance(provided, dict):
        raise Failure("params 必须是对象（键是工作流绑定的参数名）")
    unknown = sorted(key for key in provided if key not in bound)
    if unknown:
        warnings.append("params 里这些键没有绑定到节点，已忽略：" + ", ".join(unknown))
    for name, value in provided.items():
        if name in bound and value is not None:
            set_input(graph, bound[name], value)

    overrides = params.get("overrides") or {}
    if not isinstance(overrides, dict):
        raise Failure("overrides 必须是对象（键是「节点 id.字段名」）")
    for path, value in overrides.items():
        set_input(graph, str(path), value)
    return warnings


def _is_autogrow(declaration):
    """COMFY_AUTOGROW_V3 是动态输入组：min=0 时可以整组不出现。"""
    return (isinstance(declaration, list) and declaration
            and isinstance(declaration[0], str)
            and declaration[0].upper() == "COMFY_AUTOGROW_V3")


def _autogrow_min(declaration):
    options = declaration[1] if len(declaration) > 1 and isinstance(declaration[1], dict) else {}
    value = options.get("min", 0)
    return value if isinstance(value, int) else 0


def validate_graph(graph, object_info):
    errors = []
    warnings = []
    for node_id, node in graph.items():
        class_type = node.get("class_type")
        inputs = node.get("inputs") or {}
        spec = object_info.get(class_type)
        if spec is None:
            errors.append("节点 " + str(node_id) + "：服务端没有节点类型 " + str(class_type))
            continue
        required = (spec.get("input") or {}).get("required") or {}
        optional = (spec.get("input") or {}).get("optional") or {}
        for name in required:
            # 动态输入（COMFY_AUTOGROW_V3）导出成 "values.a": [...] 这种点号键，不能按同名判断。
            if name in inputs or any(key.startswith(name + ".") for key in inputs):
                continue
            declaration = required[name]
            if _is_autogrow(declaration) and _autogrow_min(declaration) == 0:
                continue
            errors.append("节点 " + str(node_id) + "（" + class_type + "）缺少必填入参 " + name)
        for name, value in inputs.items():
            declaration = required.get(name) or optional.get(name)
            if declaration is None:
                continue
            choices = _declaration_options(declaration)
            if choices is not None and isinstance(value, str) and value not in choices:
                if name in MODEL_FIELDS:
                    continue
                errors.append("节点 " + str(node_id) + "（" + class_type + "）参数 " + name + "=" + str(value)
                              + " 不在可选值内")
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                target = value[0]
                if target not in graph:
                    errors.append("节点 " + str(node_id) + "（" + class_type + "）引用了不存在的节点 " + target)
                else:
                    outputs = object_info.get(graph[target].get("class_type"), {}).get("output") or []
                    if isinstance(value[1], int) and value[1] >= len(outputs):
                        errors.append("节点 " + str(node_id) + "（" + class_type + "）引用了 " + target
                                      + " 的第 " + str(value[1]) + " 个输出，但该节点只有 "
                                      + str(len(outputs)) + " 个")
    return errors, warnings


def op_list(config, params):
    items = []
    for entry in discover(config):
        item = {"id": entry["id"], "file": entry["file"]}
        if entry.get("skipped"):
            item["skipped"] = entry["skipped"]
            items.append(item)
            continue
        item["nodes"] = len(entry["graph"])
        item["bindings"] = binding_summary(entry)
        item["warnings"] = entry.get("warnings") or []
        items.append(item)
    return {"ok": True, "server": config.get("server"), "api_dir": api_dir(config), "workflows": items}


def op_probe(config, params):
    base = server_url(config, params)
    timeout = request_timeout(config, params)
    stats = get_json(base + "/system_stats", timeout=timeout)
    try:
        object_info = get_json(base + "/object_info", timeout=max(timeout, 60))
    except Failure:
        object_info = {}
    devices = stats.get("devices") or []
    gpu_ready = any(str(device.get("type", "")).lower() in ("cuda", "mps", "xpu", "rocm") for device in devices)
    warnings = [] if gpu_ready else [
        "当前推理设备是 CPU：启动参数里带了 --cpu，或 torch.cuda.is_available() 为 False。"
        "机器有 GPU 也要满足两条——去掉 --cpu 重启，且 NVIDIA 驱动版本能覆盖 torch 的 CUDA 版本"]
    system = stats.get("system") or {}
    return {
        "ok": True,
        "server": base,
        "comfyui_version": system.get("comfyui_version"),
        "python_version": system.get("python_version"),
        "pytorch_version": system.get("pytorch_version"),
        "argv": system.get("argv"),
        "devices": devices,
        "gpu_ready": gpu_ready,
        "node_types": len(object_info),
        "workflows": sorted(entry["id"] for entry in discover(config) if "graph" in entry),
        "warnings": warnings,
    }


def _navigate(graph, path):
    node_id, _, field = path.rpartition(".")
    return ((graph.get(node_id) or {}).get("inputs") or {}).get(field)


def _declaration_options(declaration):
    """兼容两种枚举声明：[["a", "b"]] 与 ["COMBO", {"options": ["a", "b"]}]。"""
    if not isinstance(declaration, list) or not declaration:
        return None
    head = declaration[0]
    options = declaration[1] if len(declaration) > 1 and isinstance(declaration[1], dict) else {}
    if isinstance(head, list):
        return head
    if isinstance(head, str) and head.upper() == "COMBO":
        values = options.get("options")
        if isinstance(values, list):
            return values
    return None


def _field_constraints(object_info, graph, path):
    """从服务端 /object_info 取该字段的类型、范围、枚举选项。"""
    node_id, _, field = path.rpartition(".")
    node = graph.get(node_id) or {}
    spec = object_info.get(node.get("class_type")) or {}
    inputs = spec.get("input") or {}
    declaration = (inputs.get("required") or {}).get(field)
    if declaration is None:
        declaration = (inputs.get("optional") or {}).get(field)
    info = {}
    if isinstance(declaration, list) and declaration:
        head = declaration[0]
        options = declaration[1] if len(declaration) > 1 and isinstance(declaration[1], dict) else {}
        choices = _declaration_options(declaration)
        if choices is not None:
            info["type"] = "enum"
            info["options"] = choices[:50]
            if len(choices) > 50:
                info["option_count"] = len(choices)
        elif isinstance(head, str):
            info["type"] = head.lower()
        for key in ("min", "max", "step", "default"):
            if key not in options:
                continue
            value = options[key]
            # ComfyUI 对无范围字段会给 ±9e18 的占位值，展示出来只会干扰判断。
            if key in ("min", "max") and isinstance(value, (int, float)) and abs(value) >= 1e15:
                continue
            info[key] = value
    return info


def op_inspect(config, params):
    """列出工作流可改的位置、当前值与服务端约束，供大模型决定怎么填参数。"""
    entry, definition = resolve_workflow(config, params)
    graph = entry["graph"]
    base = server_url(config, params)
    object_info = {}
    extra_warnings = []
    try:
        object_info = get_json(base + "/object_info", timeout=max(request_timeout(config, params), 60))
    except (Failure, OSError) as failure:
        extra_warnings.append("没有读到服务端参数约束（" + str(failure) + "），只返回当前值")

    images = {}
    for key, path in (entry["bindings"].get("images") or {}).items():
        images[key] = {"path": path,
                       "label": (entry["bindings"].get("image_labels") or {}).get(key),
                       "current": _navigate(graph, path)}

    prompt_info = {}
    for role in ("positive", "negative"):
        path = entry["bindings"].get(role)
        if not path:
            continue
        text = _navigate(graph, path)
        preview = text
        if isinstance(text, str) and len(text) > 200:
            preview = text[:200] + "..."
        prompt_info[role] = {"path": path,
                             "chars": len(text) if isinstance(text, str) else None,
                             "preview": preview}

    summaries = {}
    for name, path in (definition.get("params") or {}).items():
        if name in images:
            continue
        info = {"path": path, "current": _navigate(graph, path)}
        info.update(_field_constraints(object_info, graph, path))
        summaries[name] = info

    return {"ok": True, "workflow": entry["id"], "file": entry["file"], "nodes": len(graph),
            "prompt": prompt_info, "images": images, "params": summaries,
            "switch_overrides": list(entry["bindings"].get("switch_overrides") or []),
            "warnings": list(entry.get("warnings") or []) + extra_warnings}


def materialize_images(params, base, timeout):
    """本地图片先上传到 ComfyUI，再交给 apply_bindings 写进 LoadImage。"""
    warnings = []
    params = dict(params)

    def value_of(raw):
        if not isinstance(raw, str) or not raw.strip():
            return raw
        text = raw.strip()
        candidate = text if os.path.isabs(text) else os.path.join(REPO_ROOT, text)
        if os.path.isfile(candidate):
            name = upload_image(base, candidate, timeout)
            warnings.append("已上传参考图 " + os.path.basename(candidate) + " → " + name)
            return name
        return text

    if params.get("image"):
        params["image"] = value_of(params["image"])
    many = params.get("images")
    if isinstance(many, list):
        params["images"] = [value_of(item) for item in many]
    elif isinstance(many, dict):
        params["images"] = {key: value_of(item) for key, item in many.items()}
    return params, warnings


def op_validate(config, params):
    require_enabled(config)
    base = server_url(config, params)
    timeout = request_timeout(config, params)
    entry, definition = resolve_workflow(config, params)
    params, warnings = materialize_images(params, base, timeout)
    warnings.extend(apply_bindings(entry, definition, params))
    object_info = get_json(base + "/object_info", timeout=max(timeout, 60))
    errors, extra = validate_graph(entry["graph"], object_info)
    warnings.extend(extra)
    return {"ok": not errors, "workflow": entry["id"], "file": entry["file"],
            "nodes": len(entry["graph"]), "bindings": binding_summary(entry),
            "errors": errors, "warnings": warnings}


def op_submit(config, params):
    require_enabled(config)
    base = server_url(config, params)
    timeout = request_timeout(config, params)
    entry, definition = resolve_workflow(config, params)
    graph = entry["graph"]
    params, warnings = materialize_images(params, base, timeout)
    warnings.extend(apply_bindings(entry, definition, params))
    object_info = get_json(base + "/object_info", timeout=max(timeout, 60))
    errors, extra = validate_graph(graph, object_info)
    warnings.extend(extra)
    if errors:
        return {"ok": False, "workflow": entry["id"], "file": entry["file"],
                "error": "工作流结构校验未通过，未提交", "errors": errors, "warnings": warnings}
    status, response = post_json(base + "/prompt",
                                 {"prompt": graph, "client_id": "clara-" + uuid.uuid4().hex},
                                 timeout=timeout)
    if status >= 400 or response.get("node_errors"):
        return {"ok": False, "http_status": status, "workflow": entry["id"],
                "error": "ComfyUI 拒绝执行工作流", "node_errors": response.get("node_errors"),
                "response": response}
    submitted = {"ok": True, "prompt_id": response.get("prompt_id"), "number": response.get("number"),
                 "workflow": entry["id"], "bindings": binding_summary(entry), "warnings": warnings}
    wait = params.get("wait")
    if wait is None:
        wait = looks_like_video(entry)
    if wait:
        return wait_result(config, params, submitted["prompt_id"], submitted)
    return submitted


def looks_like_video(entry):
    """视频工作流默认阻塞等待：带时长参数或 SaveVideo 节点。"""
    if "duration" in ((entry.get("bindings") or {}).get("params") or {}):
        return True
    return any((node or {}).get("class_type") == "SaveVideo"
               for node in (entry.get("graph") or {}).values())


def _progress(message):
    """进度写 stderr：宿主会实时回显到控制台，stdout 只留给最终 JSON。"""
    print("[comfyui] " + message, file=sys.stderr, flush=True)


def _human_seconds(seconds):
    seconds = int(seconds)
    if seconds < 60:
        return str(seconds) + " 秒"
    if seconds < 3600:
        return str(seconds // 60) + " 分 " + str(seconds % 60) + " 秒"
    return str(seconds // 3600) + " 小时 " + str((seconds % 3600) // 60) + " 分"


def wait_result(config, params, prompt_id, submitted):
    """阻塞轮询到任务结束（默认 60 秒一次），成功后自动下载产物。"""
    interval = max(5, int(params.get("poll_seconds") or config.get("pollSeconds") or 60))
    limit = max(interval, int(params.get("max_wait_seconds") or config.get("maxWaitSeconds") or 1500))
    started = time.time()
    _progress("已提交 " + str(prompt_id) + "，每 " + str(interval) + " 秒查询一次，最多等 "
              + _human_seconds(limit))
    status = {}
    state = "unknown"
    elapsed = 0
    failures = 0
    while True:
        try:
            status = op_status(config, {"prompt_id": prompt_id, "server": params.get("server")})
        except (Failure, OSError) as failure:
            status = {"ok": False, "state": "unknown", "error": str(failure)}
        state = status.get("state") or "unknown"
        elapsed = int(time.time() - started)
        _progress(time.strftime("%H:%M:%S") + " 状态 " + state + "（已等 " + _human_seconds(elapsed) + "）")
        if not status.get("ok") and state == "unknown":
            # 网络抖动或服务端短暂无响应：继续等，不把 prompt_id 丢掉；连续失败 3 次才放弃。
            failures += 1
            if failures >= 3:
                result = dict(submitted or {})
                result.update({"ok": False, "state": "unknown", "prompt_id": prompt_id,
                               "elapsed_seconds": elapsed,
                               "error": "连续 3 次查不到任务状态：" + str(status.get("error"))})
                return result
            if elapsed < limit:
                time.sleep(min(interval, max(1, limit - elapsed)))
                continue
        failures = 0
        if state == "success":
            break
        if state == "error":
            result = dict(submitted or {})
            result.update({"ok": False, "state": state, "prompt_id": prompt_id,
                           "elapsed_seconds": elapsed,
                           "error": status.get("error") or "任务执行失败"})
            return result
        if elapsed >= limit:
            result = dict(submitted or {})
            result.update({"ok": True, "state": state, "prompt_id": prompt_id, "pending": True,
                           "elapsed_seconds": elapsed,
                           "note": "等待超过 " + _human_seconds(limit) + "，任务仍在执行；"
                                   "稍后用 op=wait 带同一个 prompt_id 继续等"})
            return result
        time.sleep(min(interval, max(1, limit - elapsed)))

    result = dict(submitted or {})
    result.update({"ok": True, "state": "success", "prompt_id": prompt_id,
                   "elapsed_seconds": elapsed, "outputs": status.get("outputs")})
    if params.get("fetch") is False:
        return result
    fetched = op_fetch(config, {"prompt_id": prompt_id, "server": params.get("server"),
                                "out": params.get("out")})
    result.update({"out_dir": fetched.get("out_dir"), "files": fetched.get("files")})
    return result


def op_wait(config, params):
    """按已有 prompt_id 继续阻塞等待（中断、超时或分开提交时用）。"""
    prompt_id = params.get("prompt_id")
    if not prompt_id:
        raise Failure("缺少 prompt_id")
    submitted = {"workflow": params.get("workflow")}
    return wait_result(config, params, prompt_id, submitted)


def op_status(config, params):
    base = server_url(config, params)
    prompt_id = params.get("prompt_id")
    if not prompt_id:
        raise Failure("缺少 prompt_id")
    timeout = request_timeout(config, params)
    history = get_json(base + "/history/" + urllib.parse.quote(prompt_id), timeout=timeout)
    entry = history.get(prompt_id)
    if entry:
        status = entry.get("status") or {}
        state = status.get("status_str") or ("success" if status.get("completed") else "unknown")
        outputs = []
        for node_id, node_output in (entry.get("outputs") or {}).items():
            for key, items in node_output.items():
                if not isinstance(items, list):
                    continue
                for item in items:
                    if isinstance(item, dict) and item.get("filename"):
                        outputs.append({"node": node_id, "kind": key, "filename": item.get("filename"),
                                        "subfolder": item.get("subfolder", ""),
                                        "type": item.get("type", "output")})
        return {"ok": True, "state": state, "prompt_id": prompt_id, "outputs": outputs,
                "error": status.get("messages") if state == "error" else None}
    queue = get_json(base + "/queue", timeout=timeout)
    running = [item[1] for item in queue.get("queue_running") or [] if len(item) > 1]
    pending = [item[1] for item in queue.get("queue_pending") or [] if len(item) > 1]
    if prompt_id in running:
        return {"ok": True, "state": "running", "prompt_id": prompt_id}
    if prompt_id in pending:
        return {"ok": True, "state": "pending", "prompt_id": prompt_id}
    return {"ok": False, "state": "unknown", "prompt_id": prompt_id,
            "error": "队列与历史里都没有该任务（可能已被取消或服务端重启）"}


def download_file(base, filename, subfolder, kind, target_dir, timeout):
    query = urllib.parse.urlencode({"filename": filename, "subfolder": subfolder, "type": kind})
    status, body = http_request(base + "/view?" + query, timeout=timeout)
    if status >= 400:
        raise Failure("下载失败 " + str(status) + "：" + filename)
    target = os.path.join(target_dir, filename)
    os.makedirs(target_dir, exist_ok=True)
    with open(target, "wb") as handle:
        handle.write(body)
    return {"path": target, "bytes": len(body)}


def op_fetch(config, params):
    base = server_url(config, params)
    timeout = request_timeout(config, params)
    target_dir = output_dir(config, params)
    prompt_id = params.get("prompt_id")
    items = []
    if prompt_id:
        history = get_json(base + "/history/" + urllib.parse.quote(prompt_id), timeout=timeout)
        entry = history.get(prompt_id) or {}
        for node_id, node_output in (entry.get("outputs") or {}).items():
            for key, values in node_output.items():
                if isinstance(values, list):
                    for item in values:
                        if isinstance(item, dict) and item.get("filename"):
                            items.append((item["filename"], item.get("subfolder", ""),
                                          item.get("type", "output")))
    else:
        filename = params.get("file")
        if not filename:
            raise Failure("缺少 prompt_id 或 file")
        items.append((os.path.basename(filename), "", "output"))
    if not items:
        raise Failure("该任务没有可下载的输出")
    stored = [download_file(base, name, subfolder, kind, target_dir, timeout)
              for name, subfolder, kind in items]
    return {"ok": True, "out_dir": target_dir, "files": stored}


def upload_image(base, path, timeout):
    """上传本地图片到 ComfyUI 的 input 目录，返回可写进 LoadImage 的名称。"""
    if not os.path.isabs(path):
        path = os.path.join(REPO_ROOT, path)
    if not os.path.isfile(path):
        raise Failure("文件不存在：" + path)
    boundary = "----clara" + uuid.uuid4().hex
    filename = os.path.basename(path)
    content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    with open(path, "rb") as handle:
        content = handle.read()
    parts = []
    for field, value in (("type", "input"), ("overwrite", "true")):
        parts.append(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                      + "\"\r\n\r\n" + value + "\r\n").encode("utf-8"))
    parts.append(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                  + filename + "\"\r\nContent-Type: " + content_type + "\r\n\r\n").encode("utf-8"))
    parts.append(content)
    parts.append(("\r\n--" + boundary + "--\r\n").encode("utf-8"))
    # 参考图走的是客户端上行带宽，实测慢链路只有十几 KB/s；按体积放宽等待时间。
    wait = max(timeout, 60 + int(len(content) / 10240))
    try:
        status, raw = http_request(base + "/upload/image", body=b"".join(parts), timeout=wait,
                                   headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
                                   method="POST")
    except TimeoutError:
        raise Failure("上传参考图超时（" + str(round(len(content) / 1024.0 / 1024.0, 1)) + " MB，"
                      "等待 " + str(wait) + " 秒）：本机到 ComfyUI 的上行带宽不足，"
                      "可先在本地压缩图片，或把 clara.yml 的 tools.timeoutMillis 调大")
    if status >= 400:
        raise Failure("上传失败 " + str(status))
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except ValueError:
        parsed = {}
    name = parsed.get("name", filename)
    subfolder = (parsed.get("subfolder") or "").strip("/")
    return subfolder + "/" + name if subfolder else name


def op_upload(config, params):
    base = server_url(config, params)
    path = params.get("file")
    if not path:
        raise Failure("缺少 file（要上传的本地图片路径）")
    name = upload_image(base, path, request_timeout(config, params))
    return {"ok": True, "name": name}


def op_plan_check(config, params):
    spec = params.get("shot_spec")
    if isinstance(spec, str):
        path = spec if os.path.isabs(spec) else os.path.join(REPO_ROOT, spec)
        with open(path, "r", encoding="utf-8") as handle:
            spec = json.load(handle)
    if not isinstance(spec, dict):
        raise Failure("缺少 shot_spec（分镜表对象或 JSON 文件路径）")

    errors = []
    warnings = []
    if not spec.get("episode_id"):
        errors.append("缺少 episode_id")
    fps = spec.get("fps", 16)
    if not isinstance(fps, int) or not 8 <= fps <= 30:
        errors.append("fps 必须是 8~30 的整数，当前 " + str(fps))
    for key in ("width", "height"):
        value = spec.get(key)
        if not isinstance(value, int) or value <= 0:
            errors.append(key + " 必须是正整数")
        elif value % 2 != 0:
            errors.append(key + " 必须是偶数（视频编码要求），当前 " + str(value))

    shots = spec.get("shots")
    if not isinstance(shots, list) or not shots:
        errors.append("shots 不能为空")
        shots = []
    seen = set()
    total = 0.0
    for index, shot in enumerate(shots):
        label = "第 " + str(index + 1) + " 个镜头"
        if not isinstance(shot, dict):
            errors.append(label + " 不是对象")
            continue
        shot_id = shot.get("shot_id")
        if not shot_id:
            errors.append(label + " 缺少 shot_id")
        elif shot_id in seen:
            errors.append("shot_id 重复：" + str(shot_id))
        else:
            seen.add(shot_id)
        duration = shot.get("duration_sec")
        if not isinstance(duration, (int, float)) or duration <= 0:
            errors.append(label + " 的 duration_sec 必须是正数")
        else:
            total += float(duration)
            if duration > 5:
                warnings.append(label + " 时长 " + str(duration) + "s 超过 5s，需要拆段接力")
            if duration > 10:
                errors.append(label + " 时长 " + str(duration) + "s 超过 10s，必须拆分成多段")
        prompt = shot.get("prompt")
        if not isinstance(prompt, str) or len(prompt.strip()) < 8:
            errors.append(label + " 的 prompt 太短，至少要描述清画面主体与动作")
        if not shot.get("first_frame"):
            warnings.append(label + " 没有 first_frame，只能走文生视频，角色一致性会下降")
        seed = shot.get("seed")
        if seed is not None and (not isinstance(seed, int) or seed < 0):
            errors.append(label + " 的 seed 必须是非负整数")
        transition = shot.get("transition_out")
        if transition is not None and transition not in TRANSITIONS:
            warnings.append(label + " 的 transition_out=" + str(transition)
                            + " 不是 " + "/".join(TRANSITIONS) + " 之一")

    if total > 120:
        warnings.append("总时长 " + str(total) + "s 超过 120s，建议拆分集")
    return {"ok": not errors, "shots": len(shots), "total_seconds": total,
            "errors": errors, "warnings": warnings}


def op_assemble(config, params):
    files = [item for item in (params.get("files") or []) if item]
    if not files:
        raise Failure("缺少 files（要拼接的片段列表）")
    resolved = []
    for item in files:
        path = item if os.path.isabs(item) else os.path.join(REPO_ROOT, item)
        if not os.path.isfile(path):
            raise Failure("片段不存在：" + path)
        resolved.append(os.path.abspath(path))

    out = params.get("out") or os.path.join(output_dir(config, params), "final.mp4")
    if not os.path.isabs(out):
        out = os.path.join(REPO_ROOT, out)
    os.makedirs(os.path.dirname(out) or REPO_ROOT, exist_ok=True)

    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise Failure("本机没有 ffmpeg，无法拼接（装 ffmpeg 后重试，或改用 ComfyUI 的 ConcatenateVideo 工作流）")

    audio = params.get("audio")
    subtitle = params.get("subtitle")
    workdir = tempfile.mkdtemp(prefix="clara-assemble-")
    try:
        list_path = os.path.join(workdir, "list.txt")
        with open(list_path, "w", encoding="utf-8") as handle:
            for path in resolved:
                normalized = path.replace("\\", "/").replace("'", "'\\''")
                handle.write("file '" + normalized + "'\n")
        command = [ffmpeg, "-y", "-f", "concat", "-safe", "0", "-i", list_path]
        if audio:
            audio_path = audio if os.path.isabs(audio) else os.path.join(REPO_ROOT, audio)
            command += ["-i", audio_path]
        if subtitle:
            subtitle_path = subtitle if os.path.isabs(subtitle) else os.path.join(REPO_ROOT, subtitle)
            shutil.copyfile(subtitle_path, os.path.join(workdir, "subs.srt"))
            command += ["-vf", "subtitles=subs.srt", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18"]
            command += ["-c:a", "aac"] if audio else ["-an"]
        else:
            command += ["-c:v", "copy"]
            command += ["-c:a", "aac", "-shortest"] if audio else ["-an"]
        command.append(out)
        completed = subprocess.run(command, cwd=workdir, capture_output=True,
                                   timeout=int(params.get("timeout_seconds") or 600))
        if completed.returncode != 0:
            tail = completed.stderr.decode("utf-8", "replace").strip().splitlines()[-8:]
            return {"ok": False, "error": "ffmpeg 拼接失败", "detail": tail}
        return {"ok": True, "out": out, "bytes": os.path.getsize(out), "clips": len(resolved)}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


OPERATIONS = {
    "list": op_list,
    "inspect": op_inspect,
    "probe": op_probe,
    "plan_check": op_plan_check,
    "validate": op_validate,
    "upload": op_upload,
    "submit": op_submit,
    "wait": op_wait,
    "status": op_status,
    "fetch": op_fetch,
    "assemble": op_assemble,
}


def main():
    raw = sys.stdin.read()
    try:
        params = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(params, dict):
            raise Failure("入参必须是 JSON 对象")
    except ValueError as exception:
        print(json.dumps({"ok": False, "error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return

    operation = params.get("op")
    handler = OPERATIONS.get(operation)
    if handler is None:
        print(json.dumps({"ok": False, "error": "未知 op：" + str(operation)
                          + "，可选：" + ", ".join(sorted(OPERATIONS))}, ensure_ascii=False))
        return

    try:
        config = load_config()
        result = handler(config, params)
    except Failure as failure:
        result = {"ok": False, "error": str(failure)}
    except urllib.error.URLError as error:
        reason = getattr(error, "reason", error)
        if isinstance(reason, TimeoutError):
            result = {"ok": False, "error": "ComfyUI 响应超时：服务端可能在加载/推理大模型，或内存不足导致进程卡住"}
        else:
            result = {"ok": False, "error": "连接 ComfyUI 失败：" + str(reason)}
    except TimeoutError:
        result = {"ok": False, "error": "ComfyUI 响应超时：服务端可能在加载/推理大模型，或内存不足导致进程卡住"}
    except subprocess.TimeoutExpired:
        result = {"ok": False, "error": "外部命令执行超时"}
    except Exception as exception:  # noqa: BLE001 - 工具协议要求任何异常都以 JSON 回传
        result = {"ok": False, "error": type(exception).__name__ + ": " + str(exception)}
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
