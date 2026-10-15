#!/bin/sh
# 容器启动脚本：常驻图谱 daemon（图谱可视化 UI 由它提供），并把 UI 从容器回环转发到容器网卡，
# 这样宿主通过 compose 发布的端口就能打开图谱。
#
# 为什么需要转发：UI 只监听 127.0.0.1（二进制里写死的，没有配置项），而 Docker 的端口发布是连到
# 容器网卡的，回环上的端口发布不出去。UI 还会校验请求的 Host 必须是回环地址，所以转发必须保持
# 端口号不变——换成别的端口再转发会被它以 403 拒掉。
#
# 另一条通道不受影响：MCP 走 stdio，由宿主 `docker exec -i` 按需拉起进程，不依赖这里的任何端口。
set -u

PORT="${CBM_UI_PORT:-9749}"

# 1) 常驻 daemon：提供 UI；顺带让容器内的 CLI 调用少一次冷启动。
codebase-memory-mcp config set ui_port "$PORT" >/dev/null 2>&1 || true
if ! codebase-memory-mcp daemon start >/dev/null 2>&1; then
    echo "[cbm] 图谱 daemon 启动失败：可视化 UI 不可用（MCP 与 CLI 不受影响）" >&2
fi

# 2) 端口转发：容器网卡:$PORT -> 127.0.0.1:$PORT。
ADDR="$(hostname -i 2>/dev/null | awk '{print $1}')"
if [ -z "$ADDR" ]; then
    echo "[cbm] 取不到容器 IP，跳过图谱 UI 端口转发" >&2
else
    (
        while true; do
            nc -lk -p "$PORT" -s "$ADDR" -e nc 127.0.0.1 "$PORT" 2>/dev/null
            sleep 1
        done
    ) &
fi

exec "$@"
