# 代码知识图谱 MCP（codebase-memory-mcp）容器镜像。
#
# 四个设计要点：
#   1) 用 portable 产物：同版本的 codebase-memory-mcp-linux-amd64.tar.gz 是动态链接 glibc 的，在
#      alpine（musl）里会以「not found」直接失败（缺动态加载器）；portable 那份静态链接，任何
#      linux/amd64 基底都能跑。
#   2) 二进制由构建上下文带入（.build/ 目录），构建期不联网：GitHub 从 Docker 的虚拟网络未必可达，
#      而且父脚本会先做一次 SHA256 校验，比在 Dockerfile 里下载更可控。
#   3) 不把 MCP 服务端做成网络服务：MCP 本体只讲 stdio，标准输入输出由宿主用 `docker exec -i` 接进来
#      （见 docker-compose.yml 与部署脚本）；容器里常驻的只有图谱 daemon，负责提供可视化 UI。
#   4) 仓库挂在 /work/repo（普通目录下），不挂到顶层卷根：图谱会拒绝把卷顶层当项目根
#      （index_repository 报 path is too broad to index as one root）。
#
# 构建与启动请走 deploy\codebase-memory-mcp\install-docker.ps1（它负责下载产物并写好 .env）。

ARG BASE_IMAGE=alpine:3.20
FROM ${BASE_IMAGE}

# 换架构（如 arm64）时用 --build-arg CBM_ARCHIVE=...-linux-arm64-portable.tar.gz 覆盖。
ARG CBM_ARCHIVE=codebase-memory-mcp-linux-amd64-portable.tar.gz

COPY .build/${CBM_ARCHIVE} /tmp/cbm.tar.gz
RUN tar -xzf /tmp/cbm.tar.gz -C /tmp \
    && install -m 0755 /tmp/codebase-memory-mcp /usr/local/bin/codebase-memory-mcp \
    && rm -rf /tmp/cbm.tar.gz /tmp/codebase-memory-mcp /tmp/install.sh /tmp/LICENSE /tmp/THIRD_PARTY_NOTICES.md

# 图谱索引（SQLite）落在这里：compose 把它挂成命名卷，容器重建不丢索引。
ENV CBM_CACHE_DIR=/data

# 明示两个约定位置：数据目录与待分析仓库的挂载点（compose 会挂上来）。
# 仓库在容器内固定为 /work/repo，code_graph 按 CLARA_CBM_REPO 在宿主与该挂载点之间换算路径。
RUN mkdir -p /data /work/repo

COPY entrypoint.sh /usr/local/bin/cbm-entrypoint.sh
RUN chmod 0755 /usr/local/bin/cbm-entrypoint.sh

HEALTHCHECK --interval=60s --timeout=10s --start-period=5s --retries=3 \
    CMD ["codebase-memory-mcp", "--version"]

# 启动脚本：常驻 daemon（图谱 UI）+ 把 UI 从容器回环转发到容器网卡；MCP 仍按需由 `docker exec -i` 拉起。
ENTRYPOINT ["/usr/local/bin/cbm-entrypoint.sh"]
CMD ["sleep", "infinity"]
