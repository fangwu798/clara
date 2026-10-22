# Clara 本地可观测栈

一条 `docker compose` 启动四件套，把 Clara 的链路与指标落到本地：

```text
Clara (JVM)
  ├─ OTLP/HTTP 4318 ──> otel-collector ──> tempo ──> Grafana 链路检索
  └─ /metrics 9464  ─────────────────────> prometheus ──> Grafana 指标看板
```

| 组件 | 端口 | 作用 |
| --- | --- | --- |
| `otel-collector` | 4317 / 4318 / 8888 | 接收 OTLP，批量转发到 Tempo；8888 为自身指标 |
| `tempo` | 3200 | 本地 trace 存储与查询 API |
| `prometheus` | 9090 | 抓取 Clara `/metrics`，加载告警规则 |
| `grafana` | 3001 | 链路检索与指标看板（容器内 3000） |

本栈只负责观测，不包含 Milvus、知识图谱或 Clara 自身。它们各自独立部署。

## 1. 启动

在仓库根目录执行：

```powershell
docker compose -f deploy/observability/docker-compose.yml up -d
```

首次启动会拉取镜像。检查状态：

```powershell
docker compose -f deploy/observability/docker-compose.yml ps
```

## 2. 开启 Clara 导出

修改 `config/clara.yml`：

```yaml
telemetry:
  enabled: true
  sampleRate: 1.0          # 本地调试全量保留；生产可降到 0.1
  slowTraceMillis: 10000
  otlp:
    enabled: true
    endpoint: http://localhost:4318
    headers: {}
    timeoutMillis: 5000
    serviceName: clara
    flushIntervalMillis: 1000
  prometheus:
    enabled: true
    host: 0.0.0.0          # Docker 内的 Prometheus 需要访问宿主机端口
    port: 9464
```

两个开关可以独立使用：

- 只要指标：开 `prometheus`，关 `otlp`。
- 只要链路：开 `otlp`，关 `prometheus`。

`host` 必须改为 `0.0.0.0`（或宿主可被 Docker 访问的网卡地址）。只绑 `127.0.0.1` 时，
容器内的 Prometheus 抓不到 `host.docker.internal:9464`。

重启 Clara 后，启动横幅会打印实际监听地址，例如：

```text
metrics: http://0.0.0.0:9464/metrics
```

## 3. 访问

| 入口 | 地址 | 说明 |
| --- | --- | --- |
| Grafana | <http://localhost:3001> | 默认账号 `admin` / 密码 `clara`（可用环境变量覆盖） |
| Prometheus | <http://localhost:9090> | 查看 Targets 与 Alerts |
| Tempo | <http://localhost:3200> | 查询 API，一般通过 Grafana 使用 |
| Clara 指标 | <http://localhost:9464/metrics> | 宿主机直接验证 |

Grafana 已自动配置 Prometheus / Tempo 数据源，并加载 `Clara 可观测总览` 看板。

链路检索：Grafana 左侧 `Explore` → 选择 `Tempo` → `Search` → Service Name 填 `clara`。
也可以直接粘贴 `/trace` 命令显示的 traceId。

账号密码可通过环境变量覆盖：

```powershell
$env:GRAFANA_ADMIN_USER="admin"
$env:GRAFANA_ADMIN_PASSWORD="change-me"
docker compose -f deploy/observability/docker-compose.yml up -d
```

## 4. 验证

1. Prometheus → `Status` → `Targets`，`clara` 与 `otel-collector` 都应为 `UP`。
2. 浏览器打开 `http://localhost:9464/metrics`，应看到 `clara_model_calls_total` 等指标。
3. 在 Clara 里完成一轮对话，`/trace` 能看到 Span；Grafana Explore 中按 traceId 能查到同一条链路。
4. Prometheus → `Alerts` 能看到 `clara-observability` 规则组。

## 5. 数据保留

- Tempo：`tempo/tempo.yaml` 的 `block_retention: 168h`（7 天）。
- Prometheus：compose 命令中的 `--storage.tsdb.retention.time=7d`。
- Grafana：看板与数据源为文件挂载，容器重建不丢；本地状态在 Docker volume。

需要缩短保留期时改这两个值后重启对应容器。

## 6. 停止与清理

停止但保留数据：

```powershell
docker compose -f deploy/observability/docker-compose.yml down
```

停止并删除 trace / 指标数据：

```powershell
docker compose -f deploy/observability/docker-compose.yml down -v
```

## 7. 常见问题

**Prometheus 中 clara target 为 DOWN**

- `config/clara.yml` 的 `telemetry.prometheus.enabled` 是否为 `true`；
- `host` 是否为 `0.0.0.0`；
- 宿主机 `9464` 是否被防火墙拦截；
- 先在宿主机执行 `curl http://localhost:9464/metrics` 验证端点本身可用。

**Grafana 里没有 trace**

- `telemetry.otlp.enabled` 是否为 `true`，`endpoint` 是否为 `http://localhost:4318`；
- `docker compose ... logs otel-collector` 是否出现导出错误；
- `sampleRate` 为 0 时成功链路会被丢弃；错误、取消和慢请求仍保留。

**Linux 上 host.docker.internal 不通**

compose 已为 Prometheus 配置：

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

若仍不通，把 `prometheus/prometheus.yml` 的 target 换成宿主机桥接地址（如 `172.17.0.1:9464`）。

## 8. 文件说明

```text
deploy/observability/
├─ docker-compose.yml
├─ otel-collector/config.yaml          # OTLP 接收 → batch → Tempo
├─ tempo/tempo.yaml                    # 本地块存储，保留 7 天
├─ prometheus/prometheus.yml           # 抓取 Clara + Collector
├─ prometheus/alerts.yml               # 错误率 / 延迟 / 超限 / 故障转移告警
└─ grafana/
   ├─ provisioning/datasources/        # Prometheus + Tempo 数据源
   ├─ provisioning/dashboards/         # 看板加载配置
   └─ dashboards/clara-observability.json
```