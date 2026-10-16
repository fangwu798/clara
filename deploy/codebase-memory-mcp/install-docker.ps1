<#
.SYNOPSIS
  用 Docker Compose 部署 codebase-memory-mcp（代码知识图谱 MCP），并接入 Clara。

.DESCRIPTION
  与 install.ps1（装到宿主机）并列的另一条部署路径：容器化后宿主不再需要 C:\clara-tools 之类的安装
  目录，二进制与索引都留在镜像和命名卷里。

  为什么容器只做「常驻 + 挂载」、不自己跑 MCP 服务：该服务端只讲 stdio，而 stdio 没法跨容器边界。
  于是容器保持常驻，宿主 Clara 用 `docker exec -i <容器> codebase-memory-mcp` 把标准输入输出接进去；
  这条命令同时写进 config/mcp/mcp.json —— Clara 的 stdio 传输按原样执行它，Clara 侧不需要任何改动。

  做六件事：
    1) 下载官方 linux portable 产物到 .build\，并用官方 checksums.txt 校验 SHA256；
    2) 写 .env（容器挂载根，容器内固定挂到 /work/repo）；
    3) docker compose up -d --build；
    4) 等容器 healthy；
    5) 在容器内对仓库目录建一次索引，项目名固定（默认 clara-code-graph）；
    6) 写 config/mcp/mcp.json 注册项，并设置用户环境变量 CLARA_CBM_PROJECT / CLARA_CBM_REPO。

  路径约定（重要）：容器内的挂载根固定是 /work/repo，对应宿主的 -MountRoot；CLARA_CBM_REPO 声明它，
  code_graph 据此在宿主路径与容器内路径之间换算。挂载点用 /work/repo 而不是顶层 /repo：图谱拒绝把
  卷顶层当项目根（index_repository 报 path is too broad to index as one root）。

  挂载根可以就是 -RepoPath 本身（默认），也可以放宽成工作区根 —— 也就是工作目录的公共父目录：挂载根下
  的任意子目录都能按需索引成独立项目，于是一个容器覆盖多个工作目录，换目录不必重建容器。
  - 部署时就放宽：-RepoPath <仓库目录> -MountRoot <工作区根>；
  - 之后导入别的工作目录：deploy\codebase-memory-mcp\index-workdir.ps1 -WorkDir <目录>，
    或在对话里让模型调 code_graph 的 op=prepare / op=index。
  - 挂载之外的目录也不必重建容器：ensure-mount.ps1 只给它补一条挂载（容器内路径 /work/roots/<slug>），
    index-workdir.ps1 与程序启动时的 codeGraph.autoMount 都会按需调用它。

.PARAMETER RepoPath
  要索引的仓库目录（宿主路径），默认仓库根；必须位于 -MountRoot 之下。

.PARAMETER MountRoot
  容器挂载根（宿主路径），默认等于 -RepoPath。设成工作区根（工作目录的公共父目录）可让一个容器覆盖
  多个工作目录，之后用 index-workdir.ps1 按需导入，不必重建容器。
  注意：改挂载根会让已索引项目的「项目根」跟着变，需要重新索引一次（本脚本第 5 步会做）。

.PARAMETER Project
  图谱里的项目名；缺省时本仓库用 clara-code-graph、别的目录取目录名（与 code_graph 的命名规则一致，
  免得把工作区根之类的大目录也塞进 clara-code-graph 这个名字）。

.PARAMETER Version
  版本号，默认 v0.11.0。

.PARAMETER UiPort
  图谱可视化 UI 的端口，默认 9749，只发布到宿主回环（http://127.0.0.1:<端口>/）。容器内用同一个端口，
  所以换端口只要改这里即可。

.PARAMETER SkipIndex
  只建镜像与容器，不建索引。

.PARAMETER SkipMcpConfig
  不改 config/mcp/mcp.json（自己维护配置时用）。

.PARAMETER SkipUserEnv
  不写用户环境变量 CLARA_CBM_PROJECT / CLARA_CBM_REPO。

.PARAMETER ClearHostEnv
  删掉用户环境变量 CLARA_CBM_BINARY 与 CBM_CACHE_DIR（宿主安装留下的）。这两个变量只在「装到宿主」
  这条路径上生效，容器部署用不到；其中 CBM_CACHE_DIR 还会让宿主版 CLI 因目录 ACL 校验失败而退出，
  确认不再需要宿主安装时可以带本开关清掉。

.PARAMETER RequireChecksum
  取不到官方校验和时直接失败（默认只警告后继续）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install-docker.ps1

.EXAMPLE
  # 索引另一个仓库，并清掉宿主安装留下的环境变量
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install-docker.ps1 -RepoPath D:\work\demo -ClearHostEnv

.EXAMPLE
  # 挂载整片工作区（工作目录的公共父目录）：之后任意子目录都能按需导入图谱
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install-docker.ps1 -RepoPath E:\workspace_self\xxxx\clara -MountRoot E:\workspace_self
#>
[CmdletBinding()]
param(
    [string]$RepoPath = "",
    [string]$MountRoot = "",
    [string]$Project = "",
    [string]$Version = "v0.11.0",
    [int]$UiPort = 9749,
    [switch]$SkipIndex,
    [switch]$SkipMcpConfig,
    [switch]$SkipUserEnv,
    [switch]$ClearHostEnv,
    [switch]$RequireChecksum
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 仓库根：本脚本位于 <仓库根>/deploy/codebase-memory-mcp 下，上溯两级即仓库根。
$ScriptDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
$McpConfig = Join-Path $RepoRoot "config\mcp\mcp.json"
$BuildDir = Join-Path $ScriptDir ".build"
$EnvFile = Join-Path $ScriptDir ".env"
$AssetBase = "https://github.com/DeusData/codebase-memory-mcp/releases/download"

# 容器与镜像的固定标识：注册项、setx 提示、日常命令都引用它。
$ContainerName = "clara-codebase-memory-mcp"
$ImageName = "clara/codebase-memory-mcp"
# 容器内的固定挂载点与项目根（见 docker-compose.yml）。
$ContainerRepo = "/work/repo"
# 路径分隔符集合：TrimStart 要的是 char[]，用数组字面量会被 PowerShell 挑错重载。
$PathSeparators = [char[]]@("\", "/")
# 共用小工具（项目名规则、宿主 <-> 容器路径换算、挂载表读取、管道输出编码）都在 graph-common.ps1：
# 同一个目录在两处必须得到同一个名字，否则图谱里会出现同目录的两份索引。
. (Join-Path $ScriptDir "graph-common.ps1")
Enable-Utf8PipelineOutput

function Write-Info([string]$Message) { Write-Host ("[cbm-docker] " + $Message) }

# 0) 前置检查：docker 可用且已就绪 -------------------------------------------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 docker 命令：请先安装并启动 Docker Desktop（或 Docker CE），再重跑本脚本。"
}
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& docker info 2>&1 | Out-Null
$dockerReady = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $previous
if (-not $dockerReady) {
    throw "Docker 未就绪：Docker Desktop 可能没启动，或当前用户不在 docker-users 组。"
}

# 1) 待索引仓库与容器挂载根 --------------------------------------------------
if (-not $RepoPath) { $RepoPath = $RepoRoot }
if (-not (Test-Path -LiteralPath $RepoPath)) { throw ("要索引的目录不存在：" + $RepoPath) }
$RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path
if (-not $MountRoot) { $MountRoot = $RepoPath }
if (-not (Test-Path -LiteralPath $MountRoot)) { throw ("挂载根目录不存在：" + $MountRoot) }
$MountRoot = (Resolve-Path -LiteralPath $MountRoot).Path
# 祖先判断与相对路径都用「去掉末尾分隔符」的形式：盘根（E:\）经 Resolve-Path 会带着末尾分隔符，
# 直接拿它做前缀比较就把那个分隔符算进了长度，「E:\ 是 E:\...\clara 的父目录」这种成立的判断会永远失败。
$mountKey = $MountRoot.TrimEnd($PathSeparators)
$repoKey = $RepoPath.TrimEnd($PathSeparators)
# 挂载根必须是仓库目录的祖先（或就是它）：容器只挂得到挂载根这一份，仓库不在里面的索引无从谈起。
if (-not $repoKey.Equals($mountKey, [StringComparison]::OrdinalIgnoreCase)) {
    if (-not $repoKey.StartsWith($mountKey, [StringComparison]::OrdinalIgnoreCase)) {
        throw ("仓库目录不在挂载根里：RepoPath=" + $RepoPath + "，MountRoot=" + $MountRoot)
    }
    $boundary = $repoKey.Substring($mountKey.Length, 1)
    if ($boundary -ne "\" -and $boundary -ne "/") {
        throw ("挂载根不是仓库目录的父目录（只是路径前缀相同）：RepoPath=" + $RepoPath + "，MountRoot=" + $MountRoot)
    }
}
# 仓库目录在容器内的路径：挂载根固定挂在 /work/repo，仓库是它下面的一层（容器里的项目根就是这个路径）。
$RepoInContainer = $ContainerRepo
if (-not $repoKey.Equals($mountKey, [StringComparison]::OrdinalIgnoreCase)) {
    $tail = $repoKey.Substring($mountKey.Length).TrimStart($PathSeparators)
    $RepoInContainer = ($ContainerRepo + "/" + $tail).Replace("\", "/")
}
# 项目名缺省：本仓库沿用既有图谱的名字（clara-code-graph），别的目录取目录名。
if (-not $Project) { $Project = Get-DefaultGraphProjectName -TargetDir $RepoPath -ScriptDir $ScriptDir }

# 2) 下载 linux portable 产物并校验 ------------------------------------------
# 用 portable 那份：普通 linux 产物动态链接 glibc，在 alpine（musl）里会以「not found」直接失败。
$TarballName = "codebase-memory-mcp-linux-amd64-portable.tar.gz"
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$TarballPath = Join-Path $BuildDir $TarballName
$TarballUrl = ($AssetBase + "/" + $Version + "/" + $TarballName)

$expected = $null
try {
    # Invoke-WebRequest 对没有 charset 的响应给的是 byte[]：直接 -split 拆不出行（整段会被拼成一串数字），
    # 校验和就静默取不到了，必须显式解码。
    $checksums = (Invoke-WebRequest -Uri ($AssetBase + "/" + $Version + "/checksums.txt") -UseBasicParsing).Content
    if ($checksums -is [byte[]]) { $checksums = [Text.Encoding]::UTF8.GetString($checksums) }
    foreach ($line in ($checksums -split "`n")) {
        # 官方格式：<sha256>  <文件名>。校验和与文件名要用同一条正则卡住：分写成两个 -match 时后一个
        # 匹配会覆盖 $Matches，$Matches[1] 就取不到校验和了。
        if ($line -match ("^\s*([0-9a-fA-F]{64})\s+\*?.*" + [regex]::Escape($TarballName) + "\s*$")) {
            $expected = $Matches[1].ToLower()
            break
        }
    }
} catch {
    Write-Warning ("无法获取官方 checksums.txt：" + $_.Exception.Message)
}

$needDownload = $true
if (Test-Path -LiteralPath $TarballPath) {
    if ($expected) {
        $existing = (Get-FileHash -LiteralPath $TarballPath -Algorithm SHA256).Hash.ToLower()
        if ($existing -eq $expected) {
            $needDownload = $false
            Write-Info ("复用已下载并校验过的产物：" + $TarballPath)
        } else {
            Write-Warning "本地产物与官方校验和不符，重新下载"
        }
    } else {
        $needDownload = $false
        Write-Warning "复用本地产物（取不到官方 checksums.txt，无法校验）"
    }
}
if ($needDownload) {
    Write-Info ("下载 " + $TarballUrl)
    Invoke-WebRequest -Uri $TarballUrl -OutFile $TarballPath -UseBasicParsing
}
if ($expected) {
    $actual = (Get-FileHash -LiteralPath $TarballPath -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) { throw ("SHA256 校验失败：期望 " + $expected + "，实际 " + $actual) }
    Write-Info "SHA256 校验通过"
} elseif ($RequireChecksum) {
    throw "取不到官方校验和，且指定了 -RequireChecksum；请稍后重试或改用 -Version 指定已核对的版本"
} else {
    Write-Warning "未校验下载包（未能取到官方 checksums.txt）"
}

# 3) 写 .env（compose 自动读取） ---------------------------------------------
# 用 -f 拼行，不写 `"前缀" + $值`：数组字面量里的逗号比 + 结合得更紧，
# `"CLARA_CBM_REPO=" + $RepoPath,` 会被拆成两个元素，于是写成每行末尾都多一个换行、值跑到下一行。
$RepoLine = "CLARA_CBM_REPO={0}" -f ($MountRoot -replace "\\", "/")
$envLines = @(
    "# 由 install-docker.ps1 生成：Compose 会自动读取本目录下的 .env。改动后重跑 docker compose up -d。",
    $RepoLine,
    ("CBM_VERSION={0}" -f $Version),
    ("CBM_ARCHIVE={0}" -f $TarballName),
    ("CBM_UI_PORT={0}" -f $UiPort)
)
[IO.File]::WriteAllLines($EnvFile, $envLines, (New-Object Text.UTF8Encoding($false)))

# 写完回读校验：Compose 读不到 CLARA_CBM_REPO 时会静默退回默认挂载目录，后面查图谱就会对着错的仓库，
# 所以这里宁可直接失败。
$written = [IO.File]::ReadAllLines($EnvFile, (New-Object Text.UTF8Encoding($false)))
if (-not ($written -contains $RepoLine)) {
    throw ("写入 .env 后校验失败：缺少单独一行「" + $RepoLine + "」，请检查 " + $EnvFile)
}
Write-Info ("已写入 " + $EnvFile + "（CLARA_CBM_REPO=" + $MountRoot + "，容器内挂到 " + $ContainerRepo + "）")

# 4) 构建并启动容器 ----------------------------------------------------------
# 图谱服务现在定义在上级的 deploy\docker-compose.yml（与 Milvus 共用同一个 compose），
# 这里只操作 codebase-memory-mcp 这一个服务，不会顺带把 Milvus 也拉起来。
$ComposeFile = Join-Path (Resolve-Path (Join-Path $ScriptDir "..")).Path "docker-compose.yml"
if (-not (Test-Path -LiteralPath $ComposeFile)) {
    throw ("未找到共用 compose 文件：" + $ComposeFile + "（应位于 deploy 目录下）")
}
Push-Location $ScriptDir
try {
    Write-Info "构建镜像并启动容器（首次需要拉取 alpine 基底，视网络几分钟）"
    & docker compose --env-file $EnvFile -f $ComposeFile up -d --build codebase-memory-mcp
    if ($LASTEXITCODE -ne 0) { throw "docker compose up 失败，请查看上面的输出。" }
} finally {
    Pop-Location
}

$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    $ErrorActionPreference = "Continue"
    $health = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $ContainerName 2>&1 | Select-Object -First 1)
    $ErrorActionPreference = $previous
    if ($health -eq "healthy") { break }
    if ($health -eq "unhealthy") { throw "容器健康检查失败：docker logs " + $ContainerName }
    Start-Sleep -Seconds 3
}
Write-Info ("容器已就绪：" + $ContainerName + "（镜像 " + $ImageName + ":" + $Version + "）")

# 图谱 UI 是附加能力：容器内 daemon 提供页面，entrypoint 再把它从回环转发出来。起不来只提示，
# MCP 与 CLI 都不受影响（它们走 docker exec 的 stdio，不依赖端口）。
$uiReady = $false
$uiDeadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $uiDeadline) {
    $probe = New-Object Net.Sockets.TcpClient
    try {
        $probe.Connect("127.0.0.1", $UiPort)
        $uiReady = $true
    } catch {
        Start-Sleep -Seconds 2
    } finally {
        $probe.Close()
    }
    if ($uiReady) { break }
}
if ($uiReady) {
    Write-Info ("图谱 UI 已就绪，浏览器打开：http://127.0.0.1:" + $UiPort + "/")
} else {
    Write-Warning ("图谱 UI 端口 " + $UiPort + " 暂未就绪（可直接访问试试）；MCP 与 CLI 不受影响")
}

# 5) 建索引（在容器内对 /work/repo 执行） -----------------------------------------
if (-not $SkipIndex) {
    Write-Info ("开始索引容器内 " + $RepoInContainer + "（项目名 " + $Project + "，大仓库可能要几分钟）")
    & docker exec $ContainerName codebase-memory-mcp cli --progress index_repository --repo-path $RepoInContainer --name $Project
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ("索引返回码 " + $LASTEXITCODE + "，可稍后重跑：" + $ContainerName + " 内执行 index_repository；失败时图谱为空，code_graph 会自动回退读本地文件")
    }
} else {
    Write-Info "按 -SkipIndex 跳过索引"
}

# 6) 写 config/mcp/mcp.json：注册项就是 docker exec 命令 ----------------------
if (-not $SkipMcpConfig) {
    if (-not (Test-Path -LiteralPath $McpConfig)) { throw ("未找到 MCP 配置：" + $McpConfig) }
    $json = Get-Content -LiteralPath $McpConfig -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $json.mcpServers) {
        $json | Add-Member -MemberType NoteProperty -Name mcpServers -Value (New-Object psobject)
    }
    $entry = New-Object psobject
    # -i 保留标准输入（不加 -t）：stdio 传输要的是管道，给伪终端会把 JSON-RPC 搅坏。
    $entry | Add-Member -MemberType NoteProperty -Name command -Value @("docker", "exec", "-i", $ContainerName, "codebase-memory-mcp")
    $entry | Add-Member -MemberType NoteProperty -Name args -Value @()
    if ($json.mcpServers.PSObject.Properties.Name -contains "codebase-memory-mcp") {
        $json.mcpServers.PSObject.Properties.Remove("codebase-memory-mcp")
    }
    $json.mcpServers | Add-Member -MemberType NoteProperty -Name "codebase-memory-mcp" -Value $entry
    $rendered = $json | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($McpConfig, $rendered + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Info ("已写入 MCP 注册项（docker exec 形式）：" + $McpConfig)
} else {
    Write-Info "按 -SkipMcpConfig 跳过 config/mcp/mcp.json"
}

# 7) 用户环境变量 ------------------------------------------------------------
if (-not $SkipUserEnv) {
    # CLARA_CBM_PROJECT 钉住项目名：容器内项目根是 /work/repo，宿主路径匹配不上，只能靠名字对上。
    # CLARA_CBM_REPO 声明宿主上哪个目录挂在容器内，code_graph 用它做两侧的路径换算。
    & setx CLARA_CBM_PROJECT $Project | Out-Null
    & setx CLARA_CBM_REPO ($MountRoot -replace "\\", "/") | Out-Null
    Write-Info ("已设置用户环境变量 CLARA_CBM_PROJECT=" + $Project + "、CLARA_CBM_REPO=" + $MountRoot + "（新开的终端才生效）")
}
if ($ClearHostEnv) {
    foreach ($name in @("CLARA_CBM_BINARY", "CBM_CACHE_DIR")) {
        [Environment]::SetEnvironmentVariable($name, $null, "User")
    }
    Write-Info "已删除用户环境变量 CLARA_CBM_BINARY / CBM_CACHE_DIR（宿主安装留下的）"
}

Write-Host ""
Write-Info "完成。后续步骤："
Write-Info "  1) 新开一个终端（让 CLARA_CBM_PROJECT / CLARA_CBM_REPO 生效），在仓库根运行 clara.bat / clara.sh；"
Write-Info "     启动横幅会打印「mcp : N 个服务端 / M 个外部工具」，其中应包含 codebase-memory-mcp；"
Write-Info ("  2) 看一眼已经索引的项目：docker exec -i " + $ContainerName + " codebase-memory-mcp cli list_projects --format json");
Write-Info "  3) 代码改动后重跑索引（也可让模型调 code_graph 的 op=index，项目名会自动沿用）："
Write-Info ("     docker exec " + $ContainerName + " codebase-memory-mcp cli index_repository --repo-path " + $RepoInContainer + " --name " + $Project)
Write-Info "  4) 把别的工作目录导入图谱（挂载根取工作区根时随时可用，不必重建容器）："
Write-Info "     powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\index-workdir.ps1 -WorkDir <目录>"
Write-Info "     （也可以在对话里让模型跑 code_graph 的 op=prepare / op=index）"
Write-Info "  5) 换到挂载之外的分析目录：不用重跑本脚本 —— index-workdir.ps1 与程序启动时会按需补一条挂载"
Write-Info "     （ensure-mount.ps1：只新增、不挪动已有目录，已建好的图谱不用重做索引）；也可以重跑本脚本"
Write-Info "     并加 -MountRoot <工作区根>，那会重建容器并重做索引；"
Write-Info "  6) 看图谱（可视化）：浏览器打开 http://127.0.0.1:" + $UiPort + "/ （只监听宿主回环，不对外）；"
Write-Info "     纯文本查看用容器内的 CLI：docker exec " + $ContainerName + " codebase-memory-mcp cli get_architecture --project " + $Project;
Write-Info "  7) 清空全部索引：docker compose down -v（-v 会删掉 cbm-data 卷）。"
