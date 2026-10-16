# codebase-memory-mcp 本地部署

把 [codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp)（本地代码知识图谱 MCP 服务端）装到本机，
并接入 Clara：Clara 启动时自动发现它的工具、把工具声明下发给模型，模型读代码时优先查图谱、图谱没覆盖的再读本地文件。

## 一、为什么用它

- 纯 C 静态二进制、无运行期依赖、无需联网即可查询；索引与查询都在本机完成，图谱落成 SQLite。
- 把函数 / 类 / 导入 / HTTP 路由及其关系索引成图，`get_file_outline`、`search_graph`、`trace_path`
  这类结构化查询的 token 开销远低于整文件读取。
- Clara 侧接的是标准 MCP stdio 协议，不引入新的运行时概念：注册项写在 `config/mcp/mcp.json`。

## 二、部署（装到宿主）

不想在本机留安装目录、或想让索引跟着容器走，用下一节的容器部署；两者二选一即可。

```powershell
# 在仓库根执行；默认装到 C:\clara-tools\codebase-memory-mcp（不能含空格），
# 并索引当前目录、把注册项写进 config/mcp/mcp.json
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install.ps1
```

常用开关：

| 开关 | 作用 |
| --- | --- |
| `-InstallDir D:\tools\cbm` | 换安装目录（同样不能含空格） |
| `-IndexRoot E:\workspace\demo` | 指定要索引的仓库，默认当前目录 |
| `-SkipIndex` | 只装二进制并注册 MCP，不建索引 |
| `-SkipMcpConfig` | 不改 `config/mcp/mcp.json`（自己维护配置时用） |
| `-SkipUserEnv` | 不写用户环境变量 |
| `-Version latest` | 用 GitHub API 取最新版本标签 |
| `-RequireChecksum` | 取不到官方校验和时直接失败（默认只警告） |

脚本做的事：下载官方 release 压缩包 → 用 `checksums.txt` 校验 SHA256（取不到时警告）→ 解压出
`codebase-memory-mcp.exe` → 写 `config/mcp/mcp.json` 的注册项 → 设置用户环境变量
`CLARA_CBM_BINARY` → 可选执行一次索引。图谱数据默认落在 `%USERPROFILE%\.cache\codebase-memory-mcp`。

> 为什么不直接跑官方的 `install.ps1`：它会改写各 AI 客户端的配置文件，而 Clara 只认 `config/mcp/mcp.json`，
> 两处配置会互相覆盖。本脚本只取官方二进制。
>
> 为什么安装目录不能含空格：Clara 的 stdio 注册会把命令行按空白拆分，含空格的路径会被拆坏
> （见 `clara-mcp` 的 `DefaultMcpClientFactory`）。

## 三、容器部署（Docker Compose）

不想在本机留安装目录、或想让索引跟着容器走时用这条路径：宿主只保留 Docker，二进制与索引都在容器里。
与上面的宿主安装**二选一**即可（两者都写 `config/mcp/mcp.json` 的同一个注册项，会互相覆盖）。

```powershell
# 在仓库根执行：下载 linux 产物 → 构建镜像 → 起容器 → 容器内建索引 → 写 MCP 注册项 → 设用户环境变量
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install-docker.ps1
```

常用开关：

| 开关 | 作用 |
| --- | --- |
| `-RepoPath E:\work\demo` | 要索引的仓库（宿主路径），默认仓库根；必须位于 `-MountRoot` 之下 |
| `-MountRoot E:\workspace_self` | 容器挂载根（宿主路径），默认等于 `-RepoPath`；不必为「多一个工作目录」放宽它——`ensure-mount.ps1` 会给单个目录补一条 `/work/roots/<slug>` 挂载 |
| `-Project demo` | 图谱里的项目名；缺省时本仓库用 `clara-code-graph`、别的目录取目录名 |
| `-SkipIndex` | 只建镜像与容器，不建索引 |
| `-SkipMcpConfig` | 不改 `config/mcp/mcp.json`（自己维护配置时用） |
| `-SkipUserEnv` | 不写用户环境变量 |
| `-ClearHostEnv` | 删掉宿主安装留下的 `CLARA_CBM_BINARY` / `CBM_CACHE_DIR` |
| `-Version v0.11.0` | 换版本（需同时用 `CBM_ARCHIVE` 换产物名，见 `.env.example`） |

### 为什么是这个形状

- **MCP 服务端只讲 stdio，而 stdio 跨不了容器边界**（二进制里没有 streamable / SSE 相关实现），
  所以容器只做「常驻 + 挂载」：图谱进程由宿主按需用
  `docker exec -i clara-codebase-memory-mcp codebase-memory-mcp` 拉起，
  这条命令同时就是 `config/mcp/mcp.json` 的注册项——Clara 的 stdio 传输按原样执行它，**Clara 侧零改动**。
- **用 portable 产物**：同版本的普通 linux 产物动态链接 glibc，在 alpine（musl）里会直接以 `not found`
  失败；portable 那份静态链接。产物由宿主脚本下载并校验 SHA256 后放进构建上下文，构建期不联网。
- **仓库挂在 `/work/repo` 而不是顶层 `/repo`**：图谱拒绝把卷顶层当项目根
  （`index_repository` 会报 `path is too broad to index as one root`）。
- **一条工作目录一条挂载**：`install-docker.ps1` 的挂载根（`/work/repo`）之外，`ensure-mount.ps1` 会给每个
  需要的工作目录补一条 `/work/roots/<slug>`（slug 由宿主路径派生，同一个目录永远得到同一个 slug），
  所以多挂一个目录不会挪动已有目录在容器里的位置，已建好的图谱不必重做索引；程序启动时的
  `codeGraph.autoMount` 也会按需调它。
- **挂载根与路径换算**：容器内的挂载根固定是 `/work/repo`，宿主侧对应目录写在 `CLARA_CBM_REPO`；
  `/work/roots/<slug>` 那条挂载的宿主侧来源直接读容器的挂载表（`docker inspect`），`code_graph` 与
  `index-workdir.ps1` 用同一份挂载表换算两侧路径。挂载根可以就是被索引的仓库，也可以是它的祖先
  （工作区根，见「把工作目录导入图谱」）。`CLARA_CBM_PROJECT` 只在「项目根换算不出宿主路径」时才兜底：
  平时按「项目根 = 当前分析目录」匹配项目，免得拿到别的目录的图谱。
- **MCP 不占端口**：`docker exec -i` 把宿主进程的 stdin/stdout 直接接到容器内的图谱进程上，
  JSON-RPC 走管道、不走网络，所以不需要发布任何端口；compose 里发布的那个端口只给图谱 UI 用。
- **子进程输出统一 UTF-8**：程序启动时会读 `ensure-mount.ps1` 的 stdout（横幅里的 `codegraph` 那行），
  而 Windows PowerShell 在 stdout 被重定向时按控制台代码页编码（中文机器上是 GBK，于是那行变成乱码，
  而 Java 自己打印的中文却正常）。`graph-common.ps1` 的 `Enable-Utf8PipelineOutput` 只在被重定向时把输出
  钉成 UTF-8，与 `config/skill/scripts/_textio.py` 的 `force_utf8_stdio` 是同一件事。

### 常用命令

```powershell
# 看容器状态（healthy 才算就绪）；图谱服务定义在 deploy\docker-compose.yml，与 Milvus 共用同一个 compose
docker compose -f deploy\docker-compose.yml ps codebase-memory-mcp

# 代码改动后刷新索引（也可让模型调 code_graph 的 op=index，路径与项目名会自动换算）
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli index_repository --repo-path /work/repo --name clara-code-graph

# 看已索引的项目
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli list_projects --format json
```

### 把工作目录导入图谱（一条目录一条挂载）

挂载是**一条目录一条挂载**：每个工作目录挂到固定的容器内路径 `/work/roots/<slug>`（slug 由宿主路径派生，
同一个目录永远得到同一个 slug），`install-docker.ps1` 的挂载根仍挂 `/work/repo`。于是「某个工作目录能不能
导入图谱」= 「它在不在容器的挂载表里」，而**多挂一个目录不会挪动已有目录在容器里的位置**，已建好的图谱
不用重做索引。

```powershell
# 只给这个目录补一条只读挂载（已挂上时只跑一次 docker inspect，秒回；要补挂才重建容器）
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\ensure-mount.ps1 -WorkDir D:\work\demo

# 只想看会挂成什么样，不动容器
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\ensure-mount.ps1 -WorkDir D:\work\demo -DryRun

# 把工作目录导入图谱（不在挂载里会先按需补挂，再建索引；-NoMount 表示只提示、不动容器）
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\index-workdir.ps1 -WorkDir D:\work\demo

# 只确认「会导入哪个目录、叫什么项目」，不真正建索引
powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\index-workdir.ps1 -WorkDir D:\work\demo -SkipIndex
```

- **程序启动时自动补挂**：`config/clara.yml` 的 `codeGraph.autoMount`（默认 `true`）会在启动时对当前工作
  目录调一次 `ensure-mount.ps1 -Optional`；宿主机安装图谱（没有容器）时它只打一行「跳过」。容器正在建索引
  时脚本拒绝重建（`exit 3`，免得留下半份图谱），下次启动会再试。
- **挂载清单**：`ensure-mount.ps1` 把「已有 `/work/roots/*` + 本次新增」写进
  `deploy\docker-compose.mounts.yml`（叠加在 `deploy\docker-compose.yml` 之上的 compose 覆盖文件），
  然后 `up -d codebase-memory-mcp`。手工生效：
  `docker compose -f deploy\docker-compose.yml -f deploy\docker-compose.mounts.yml up -d codebase-memory-mcp`。
- **项目名**：该目录已索引过就沿用原项目名（同一个目录不会出现两份图谱），否则按
  `deploy\codebase-memory-mcp\graph-common.ps1` 的规则取——`CLARA_CBM_PROJECT` 只在「目录就是挂载点」时
  算数，其次本仓库用 `clara-code-graph`，最后取目录名（`...\demo` → `demo`）。
- **改已有挂载根要重做索引**：图谱里的文件路径是相对项目根存的，项目根跟着挂载点变，所以放宽 / 收缩
  `install-docker.ps1` 的挂载根（`/work/repo`）之后必须重跑一次索引（第 5 步会做）；不做的话 `code_graph`
  匹配不到项目，会静默回退读本地文件（`op=read` 的 `note` 会说清楚）。新增 `/work/roots/<slug>` 挂载不受
  这条限制：已有目录在容器里的位置没变。
- **跨盘 / 盘根**：一条挂载只覆盖一个目录，所以 C: / D: 各挂各的即可，不必再为了「多覆盖几个目录」把挂载
  根放宽到盘根（如 `E:\`）。真要放宽也行，只是那会重建容器、已有索引要重做。盘根要写成 `E:\`，别写成
  `E:`——后者会被当成「E 盘当前目录」，既算不出相对路径，也不是合法的挂载来源。
- **对话里也能导入**：让模型调 `code_graph` 的 `op=prepare`（秒回：当前目录是否已导入、怎么导入）和
  `op=index`（缺省后台发起、秒回，完成后再用 `op=status` 看结果）。不让工具同步等索引的原因是宿主侧
  单次技能脚本执行有 30 秒硬超时，而索引一个中等仓库就要一分钟上下，同步发起会被掐断并留下半份图谱。
- **确认生效**：`index-workdir.ps1 -SkipIndex` 会打印工作目录 / 容器内路径 / 项目名；导入后在那个目录下
  启动 Clara，`op=prepare` 应返回 `prepared=true`，读代码时的 `source` 才会是 `graph`。

### 看图谱

**① 浏览器（推荐）**

容器里的图谱 UI 由常驻 daemon 提供。它只监听容器回环，所以 `entrypoint.sh` 把它转发到容器网卡
（端口号保持不变——UI 会校验 Host 是回环地址，换端口转发会被它 403 掉），compose 再把它发布到
**宿主回环**，因此只有本机能访问：

```
http://127.0.0.1:9749/     # 换端口：改 .env 的 CBM_UI_PORT 后 docker compose up -d
```

页面里能搜节点、按标签（`Class` / `Method` / `File` …）过滤、看调用关系与文件树。

**② 命令行（可脚本化）**

```powershell
# 架构总览（语言 / 包 / 入口点）
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli get_architecture --project clara-code-graph --aspects overview --format tree

# 找符号 / 看文件里的声明 / 查调用方
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli search_graph --project clara-code-graph --name-pattern ApplicationBootstrap --label Class
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli get_file_outline --project clara-code-graph --file-path clara-application/src/main/java/com/clara/ApplicationBootstrap.java
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli trace_path --project clara-code-graph --function-name resolve_runner --direction inbound

# 只读 Cypher
docker exec clara-codebase-memory-mcp codebase-memory-mcp cli query_graph --project clara-code-graph --query "MATCH (n) RETURN labels(n) AS kind, count(n) AS c ORDER BY c DESC LIMIT 5"
```

**③ 直接查 SQLite（图谱本身就是个库）**

```powershell
docker cp clara-codebase-memory-mcp:/data/clara-code-graph.db .
# nodes(id, project, label, name, qualified_name, file_path, start_line, end_line, properties)
# edges(id, project, source_id, target_id, type, properties)
# 拿来用任意 SQLite 客户端看：DB Browser for SQLite / sqlite3 CLI / Python 的 sqlite3
```

**④ 直接问 Clara**：图谱已经作为 MCP 工具下发给模型，说一句「用 code_graph 讲一下 X 的调用链」即可。

换到别的目录分析：直接 `index-workdir.ps1 -WorkDir <目录>`（不在挂载里它会先按需补一条
`/work/roots/<slug>` 挂载），或在该目录启动 Clara 让 `codeGraph.autoMount` 自动补挂；不必重跑
`install-docker.ps1`。改 `deploy\codebase-memory-mcp\.env` 的 `CLARA_CBM_REPO` 后要 `docker compose up -d`
让挂载生效。
清空全部索引：`docker compose -f deploy\docker-compose.yml down -v`（`-v` 删数据卷，会连带删掉 Milvus 的绑定卷声明，数据仍在 `deploy\volumes`）。
拉不到 Docker Hub 时，在 `.env` 里把 `CBM_BASE_IMAGE` 指向镜像站（见 `.env.example`）。

## 四、环境变量

| 变量 | 作用 |
| --- | --- |
| `CLARA_CBM_BINARY` | 图谱二进制绝对路径（也可写整条命令，如 `docker exec -i <容器> codebase-memory-mcp`）；`config/skill/scripts/code-graph/code_graph.py` 用它定位 CLI |
| `CLARA_CBM_PROJECT` | 图谱里的项目名；只在「项目根换算不出宿主路径」时兜底，平时按当前分析目录匹配项目 |
| `CLARA_CBM_REPO` | 宿主上的容器挂载根（挂到 `/work/repo`，可以是被索引的仓库，也可以是它的祖先）；由 `install-docker.ps1` 写入，code_graph 在取不到容器挂载表时用它换算两侧路径 |
| `CLARA_CBM_WORK_DIR` | 可选；覆盖 code_graph 认定的「当前分析目录」（缺省用启动脚本写入的 `CLARA_WORK_DIR`） |
| `CBM_CACHE_DIR` | 可选的图谱数据目录，默认 `%USERPROFILE%\.cache\codebase-memory-mcp`；见下方说明 |
| `CLARA_MCP_ALLOWED_EXECUTABLES` | 额外允许以 stdio 启动的可执行文件（分号或逗号分隔），一般不用设 |
| `CBM_ALLOWED_ROOT` | 限制 `index_repository` 只能索引该目录以下的仓库（多租户 / 不可信调用方场景） |

`setx` 写入的用户环境变量要**新开终端**才生效。

> 为什么默认不设 `CBM_CACHE_DIR`：图谱启动时会校验缓存目录及其父目录的 DACL，只要路径链上存在
> 「非受信主体可写」的目录（`C:\` 下新建的目录会继承 `Authenticated Users: 修改`），CLI 会直接以
> `secure CLI coordination could not be created (cache-private)` 失败退出。用默认位置最省事；
> 确实要自定义，就得先断开该目录的权限继承，只保留 `Administrators` / `SYSTEM`。

## 五、Clara 侧的接入方式

- 注册与发现：`config/mcp/mcp.json` 的 `mcpServers` 条目 → `ApplicationBootstrap.loadMcpServers()`
  在启动时注册、发现并登记工具；启动横幅会打印 `mcp : N 个服务端 / M 个外部工具`。
- 下发给模型：`CompositeToolCatalog` 把「本地技能工具 + 外部 MCP 工具」合并成一份工具声明（同名时本地优先，
  与 `CompositeToolInvoker` 的路由顺序保持一致）。
- 读写代码：技能脚本 `config/skill/scripts/code-graph/code_graph.py`（工具名 `code_graph`）——`op=read`
  逐文件先取图谱大纲，图谱未覆盖时回退本地文件读取，返回的 `source` 字段标明每条信息来自图谱还是文件；
  其余 `op`（`index`/`status`/`search`/`outline`/`snippet`/`trace`/`query`）是图谱能力的直通调用；`trace` 的 `inbound`
  只跟 `CALLS` 边、会漏掉 `USAGE`，要完整的「谁引用了 X」清单用 `query`（只读 Cypher）。
- 工作流：`analyze_repo_docs`、`design_business_solution` 的读取节点已改为 `code_graph`（图谱优先 + 自动回退）。
- 启动挂载 / 导入：`config/clara.yml` 的 `codeGraph.autoMount`（默认开）在启动时调 `ensure-mount.ps1`，确保
  容器能看到当前工作目录；`codeGraph.autoImport`（默认关）再幂等地按需导入。两者各在启动横幅打一行结果。
- 会话提示：`config/skill/prompt/code_graph_guide.json` 约定「先查图谱、再回退文件、最后才整文件读取」；
  它由 `config/clara.yml` 的 `context.alwaysPrompts`（默认 `[code_graph_guide]`）常驻注入每轮 system 指令，
  不参与按输入的相似度检索；图谱关闭时自动跳过。

## 六、日常维护

```powershell
# 容器部署时把 `& $env:CLARA_CBM_BINARY` 换成 `docker exec clara-codebase-memory-mcp codebase-memory-mcp`。

# 代码改动后刷新图谱（也可让模型调用 code_graph 的 op=index）
& $env:CLARA_CBM_BINARY cli index_repository --repo-path E:\workspace_self\xxxx\clara

# 看已索引的项目与规模
& $env:CLARA_CBM_BINARY cli list_projects --format json

# 单个工具的入参开关（由工具的 JSON Schema 生成）
& $env:CLARA_CBM_BINARY cli get_file_outline --help
```

图谱数据默认在 `%USERPROFILE%\.cache\codebase-memory-mcp`（`<项目名>.db` 与 `_config.db`），
删掉即清空全部索引；若自定义了 `CBM_CACHE_DIR`，交互式 CLI 与 `code_graph.py` 必须指向同一份。

宿主安装也能看图谱：`& $env:CLARA_CBM_BINARY daemon start` 之后浏览器打开 `http://127.0.0.1:9749/`
（UI 直接跑在本机）。注意 UI 固定用 9749，容器部署也占用宿主 9749，两条路径别同时开。

## 七、其它平台

macOS / Linux 用官方安装脚本即可（二进制放 `~/.local/bin`）：`curl -fsSL https://raw.githubusercontent.com/DeusData/codebase-memory-mcp/main/install.sh | bash`，
之后把 `~/.local/bin/codebase-memory-mcp` 写进 `config/mcp/mcp.json`。