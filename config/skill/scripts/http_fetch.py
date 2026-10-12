import json
import sys
import urllib.request

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8，避免依赖系统区域设置。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python http_fetch.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "http_fetch",
    "name": "http_fetch",
    "type": "PYTHON",
    "description": "通过 HTTP GET 访问外部网络地址并返回状态码与响应体片段（用于外部网络查询能力验证）。入参 JSON：url(必填)、max_bytes(可选，默认 4096，响应体最大读取字节数)。返回：{url, status, body, body_length} 或 {url, error}。",
    "parameters": {
        "type": "object",
        "properties": {
            "url": {"type": "string"},
            "max_bytes": {"type": "integer"},
        },
        "required": ["url"],
    },
    "metadata": {"domain": "sample", "tags": "network,http,python"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传
        print(json.dumps({"url": "", "error": "入参 JSON 解析失败：" + str(exception)},
                         ensure_ascii=False))
        return

    url = spec.get("url")
    if not url:
        print(json.dumps({"url": "", "error": "缺少 url"}, ensure_ascii=False))
        return

    limit = int(spec.get("max_bytes") or 4096)
    result = {"url": url}
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "clara-sample"})
        response = urllib.request.urlopen(request, timeout=15)
        body = response.read(limit).decode("utf-8", "replace")
        result["status"] = response.status
        result["body"] = body
        result["body_length"] = len(body)
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    print(json.dumps(result, ensure_ascii=False))


main()
