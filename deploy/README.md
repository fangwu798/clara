# Clara 本地依赖栈部署

`docker-compose.yml` 包含 Milvus（etcd + MinIO + standalone + attu）与 `codebase-memory-mcp`。
脚本会自动识别 `docker compose` / `docker-compose`，并加载本目录或
`codebase-memory-mcp/.env` 中的变量。

## 快速开始

Windows：

```bat
deploy.bat up
```

Linux / macOS：

```sh
chmod +x deploy.sh   # 首次执行
./deploy.sh up
```

## 操作

| 操作 | 说明 |
| --- | --- |
| `up` | 启动全部服务（默认） |
| `down` | 停止并删除容器，保留 `./volumes` 数据 |
| `stop` / `start` / `restart` | 停止 / 启动 / 重启容器 |
| `status` | 查看容器状态（`ps` 同义） |
| `logs` | 跟踪日志 |
| `pull` | 拉取 etcd / MinIO / Milvus / attu 镜像 |
| `milvus` | 只启动 Milvus 及其依赖 |
| `graph` | 只启动 / 构建代码知识图谱 |
| `help` | 查看帮助 |

未启动 Milvus 时 Clara 会自动使用本地持久化历史缓存，对话上下文不会丢失；
启动 Milvus 后下一次启动会自动切换到 Milvus 后端。
