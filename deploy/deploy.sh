#!/usr/bin/env sh
# ============================================================
#  Clara local dependency stack deployment (Linux / macOS)
#  Usage: ./deploy.sh [up|down|stop|start|restart|status|logs|pull|milvus|graph]
#  Default action: up
#
#  The stack is defined in docker-compose.yml next to this script:
#    - Milvus: etcd + MinIO + standalone + attu
#    - codebase-memory-mcp
# ============================================================
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

ACTION=${1:-up}

if ! command -v docker >/dev/null 2>&1; then
    echo "[ERROR] 未找到 docker，请安装 Docker 并加入 PATH。" >&2
    exit 1
fi

COMPOSE=""
if docker compose version >/dev/null 2>&1; then
    COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE="docker-compose"
else
    echo "[ERROR] 未找到 Docker Compose（docker compose 或 docker-compose）。" >&2
    exit 1
fi

# Compose 只自动读取当前目录的 .env。显式传 --env-file 后不会再自动读取，
# 所以这里把两个候选文件都列出来。
ENV_ARGS=""
if [ -f ".env" ]; then
    ENV_ARGS="--env-file .env"
fi
if [ -f "codebase-memory-mcp/.env" ]; then
    ENV_ARGS="$ENV_ARGS --env-file codebase-memory-mcp/.env"
fi

usage() {
    echo "Usage: ./deploy.sh [action]"
    echo ""
    echo "  up       Start the whole stack (default)"
    echo "  down     Stop and remove containers (volumes are kept)"
    echo "  stop     Stop containers without removing them"
    echo "  start    Start previously stopped containers"
    echo "  restart  Restart containers"
    echo "  status   Show container status (alias: ps)"
    echo "  logs     Follow logs"
    echo "  pull     Pull third-party images (etcd/minio/milvus/attu)"
    echo "  milvus   Start only Milvus and its dependencies"
    echo "  graph    Start/build only codebase-memory-mcp"
    echo "  help     Show this help"
    echo ""
    echo "Examples:"
    echo "  ./deploy.sh up"
    echo "  ./deploy.sh milvus"
    echo "  ./deploy.sh logs"
}

case "$ACTION" in
    up)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS up -d
        ;;
    down)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS down
        ;;
    stop)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS stop
        ;;
    start)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS start
        ;;
    restart)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS restart
        ;;
    status|ps)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS ps
        ;;
    logs)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS logs -f --tail=200
        ;;
    pull)
        # codebase-memory-mcp is built locally from deploy/codebase-memory-mcp.
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS pull etcd minio standalone attu
        ;;
    milvus)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS up -d etcd minio standalone attu
        ;;
    graph)
        # shellcheck disable=SC2086
        exec $COMPOSE $ENV_ARGS up -d codebase-memory-mcp
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        echo "[ERROR] 未知操作：$ACTION" >&2
        echo ""
        usage >&2
        exit 2
        ;;
esac
