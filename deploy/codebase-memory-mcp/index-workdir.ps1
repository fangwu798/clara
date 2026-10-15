<#
.SYNOPSIS
  把一个工作目录导入代码知识图谱（容器部署），一条命令完成。

.DESCRIPTION
  导入 = 让图谱把某个目录索引成项目，之后 code_graph（以及需求解析等用到它的工作流）读这个目录时
  就能优先取图谱里的文件大纲 / 符号 / 调用关系。本脚本做五件事：
    1) 定位容器（默认 clara-codebase-memory-mcp），读它的挂载根（容器内固定是 /work/repo）；
    2) 把工作目录换算成容器内路径；不在挂载根里就给出放宽挂载根的那一条命令（可用 -Widen 直接执行）；
    3) 查已有项目：该目录此前索引过就沿用原项目名，避免同一个目录出现两份图谱；
    4) 在容器里对该目录执行 index_repository（大仓库要几分钟，脚本会等它跑完）；
    5) 打印项目名、容器内路径、节点 / 边计数与可视化地址。

  为什么导入不放在对话里自动做：宿主侧单次技能脚本执行有 30 秒硬超时，而索引一个中等仓库就要一分钟
  上下，同步索引会被掐断、还留下半份图谱。所以导入用本脚本（不受超时限制）；对话里让模型调
  code_graph 的 op=prepare / op=index 也可以，那条路是后台发起、秒回。

.PARAMETER WorkDir
  要导入的工作目录（宿主路径），默认当前目录。

.PARAMETER Project
  图谱里的项目名；缺省沿用该目录已索引时的项目名，没有则取目录名（如 ...\demo 就是 demo）。

.PARAMETER Container
  容器名，默认 clara-codebase-memory-mcp。

.PARAMETER SkipIndex
  只做检查与路径换算，不真正建索引：用来确认「会导入哪个目录、叫什么项目」。

.PARAMETER Widen
  工作目录不在挂载根里时，自动用两边的公共父目录调用 install-docker.ps1 放宽挂载根。注意这会让容器
  重建、已索引项目的项目根发生变化并重做一次索引，耗时几分钟。

.EXAMPLE
  # 把当前目录导入图谱
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\index-workdir.ps1

.EXAMPLE
  # 先只看会导入成什么，不真的建索引
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\index-workdir.ps1 -WorkDir D:\work\demo -SkipIndex
#>
[CmdletBinding()]
param(
    [string]$WorkDir = "",
    [string]$Project = "",
    [string]$Container = "clara-codebase-memory-mcp",
    [switch]$SkipIndex,
    [switch]$Widen
)

$ErrorActionPreference = "Stop"
$ContainerRepo = "/work/repo"
# 路径分隔符集合：TrimEnd / TrimStart 要 char[]，字符串数组会被 PowerShell 误判成 Split(char, int) 那类重载，
# 故统一在这里转成 char[]，并按同口径的正则拆分段。
$PathSeparators = [char[]]@("\", "/")

function Write-Info([string]$Message) { Write-Host ("[cbm-import] " + $Message) }

# 0) docker 与容器 ------------------------------------------------------------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 docker 命令：请先安装并启动 Docker Desktop。"
}
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$null = & docker inspect $Container 2>&1
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $previous
if (-not $exists) {
    throw ("容器 " + $Container + " 不存在或未启动：先跑 deploy\codebase-memory-mcp\install-docker.ps1，或 docker start " + $Container)
}

# 1) 工作目录 ----------------------------------------------------------------
if (-not $WorkDir) { $WorkDir = (Get-Location).Path }
if (-not (Test-Path -LiteralPath $WorkDir)) { throw ("工作目录不存在：" + $WorkDir) }
$WorkDir = (Resolve-Path -LiteralPath $WorkDir).Path

# 2) 容器挂载根（宿主侧路径） -------------------------------------------------
# 优先读同目录 .env 的 CLARA_CBM_REPO：它既是 compose 的挂载来源，也是 code_graph 做宿主 <-> 容器
# 路径换算的那一份，用它才和对话里读代码的口径完全一致。
function Convert-DockerMountSource([string]$Source) {
    # Docker Desktop for Windows 把 bind 的 Source 报成虚拟机内路径（/run/desktop/mnt/host/e/... ，
    # 在 Windows 上会被拼成 E:\run\desktop\mnt\host\e\...），要换回宿主路径才能和 -WorkDir 比。
    $text = $Source -replace "/", "\"
    if ($text -match "^[A-Za-z]:\\run\\desktop\\mnt\\host\\([A-Za-z])(?:\\(.*))?$") {
        $rest = $Matches[2]
        if ($rest) { return ($Matches[1] + ":\" + $rest) }
        return ($Matches[1] + ":\")
    }
    if ($text -match "^\\run\\desktop\\mnt\\host\\([A-Za-z])(?:\\(.*))?$") {
        $rest = $Matches[2]
        if ($rest) { return ($Matches[1] + ":\" + $rest) }
        return ($Matches[1] + ":\")
    }
    return $Source
}

$mountRoot = $null
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match "^\s*CLARA_CBM_REPO\s*=\s*(.+?)\s*$") { $mountRoot = $Matches[1]; break }
    }
}
if (-not $mountRoot) { $mountRoot = $env:CLARA_CBM_REPO }
if (-not $mountRoot) { $mountRoot = [Environment]::GetEnvironmentVariable("CLARA_CBM_REPO", "User") }

# 容器必须真的挂了 /work/repo，否则后面算出来的容器内路径在容器里根本不存在。
$mountsJson = & docker inspect --format "{{json .Mounts}}" $Container
$mounts = $mountsJson | ConvertFrom-Json
$inspectSource = $null
foreach ($mount in $mounts) {
    if ($mount.Destination -eq $ContainerRepo -and $mount.Source) { $inspectSource = $mount.Source; break }
}
if (-not $inspectSource) {
    throw ("容器 " + $Container + " 没有挂载 " + $ContainerRepo + "：容器可能不是本仓库的部署脚本建的。")
}
if (-not $mountRoot) {
    $mountRoot = Convert-DockerMountSource $inspectSource
    Write-Warning ("没读到 CLARA_CBM_REPO（.env / 环境变量都没有），按容器挂载来源推断：" + $mountRoot)
}
$mountRoot = ([IO.Path]::GetFullPath(($mountRoot -replace "/", "\"))).TrimEnd($PathSeparators)

function Get-RelativeTail([string]$Root, [string]$Path) {
    # 取 Path 相对 Root 的那一段；Path 不在 Root 里（含只有前缀相同）时返回 $null。
    if ($Path.Equals($Root, [StringComparison]::OrdinalIgnoreCase)) { return "" }
    if (-not $Path.StartsWith($Root, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    $boundary = $Path.Substring($Root.Length, 1)
    if ($boundary -ne "\" -and $boundary -ne "/") { return $null }
    return $Path.Substring($Root.Length).TrimStart($PathSeparators)
}

function Get-CommonAncestor([string]$Left, [string]$Right) {
    # 两边的公共祖先目录（用于放宽挂载根）；没有公共祖先时返回 $null。
    $leftParts = $Left.TrimEnd($PathSeparators) -split "[\\/]"
    $rightParts = $Right.TrimEnd($PathSeparators) -split "[\\/]"
    if ($leftParts.Count -eq 0 -or $rightParts.Count -eq 0) { return $null }
    if (-not $leftParts[0].Equals($rightParts[0], [StringComparison]::OrdinalIgnoreCase)) { return $null }
    $common = New-Object Collections.Generic.List[string]
    $limit = [Math]::Min($leftParts.Count, $rightParts.Count)
    for ($i = 0; $i -lt $limit; $i++) {
        if (-not $leftParts[$i].Equals($rightParts[$i], [StringComparison]::OrdinalIgnoreCase)) { break }
        $common.Add($leftParts[$i])
    }
    if ($common.Count -eq 0) { return $null }
    return ($common -join "\")
}

$tail = Get-RelativeTail $mountRoot $WorkDir
if ($null -eq $tail) {
    # 不在挂载根里：容器看不到这个目录，先给出放宽挂载根的那一条命令（-Widen 则直接执行）。
    $ancestor = Get-CommonAncestor $mountRoot $WorkDir
    if (-not $ancestor) { $ancestor = Split-Path -Parent $WorkDir }
    $install = Join-Path $PSScriptRoot "install-docker.ps1"
    $fix = "powershell -ExecutionPolicy Bypass -File " + $install + " -RepoPath `"" + $WorkDir + "`" -MountRoot `"" + $ancestor + "`""
    if (-not $Widen) {
        Write-Host ""
        Write-Info ("这个目录不在容器的挂载根里：容器挂载根 = " + $mountRoot + "（容器内 " + $ContainerRepo + "）")
        Write-Info ("工作目录 = " + $WorkDir)
        Write-Info "容器看不到它，所以没法索引。两条路："
        Write-Info "  a) 放宽挂载根到公共父目录（一个容器覆盖多个工作目录，之后换目录都不用再改）："
        Write-Info ("     " + $fix)
        Write-Info "  b) 只想分析这一个目录：把上面的 -MountRoot 换成它自己即可（等于容器只挂这一个目录）。"
        Write-Info "重跑时可加 -Widen 让本脚本自己执行 a)。"
        exit 2
    }
    Write-Info ("挂载根不含该目录，按 -Widen 放宽到公共父目录：" + $ancestor)
    & powershell -NoProfile -ExecutionPolicy Bypass -File $install -RepoPath $WorkDir -MountRoot $ancestor
    if ($LASTEXITCODE -ne 0) { throw "放宽挂载根失败，请看上面的输出。" }
    exit 0
}

$workInContainer = $ContainerRepo
if ($tail) { $workInContainer = ($ContainerRepo + "/" + $tail).Replace("\", "/") }

# 3) 已有项目沿用原名 --------------------------------------------------------
# 项目根在容器里记的是容器内路径（/work/repo/...），所以拿它和本目录的容器内路径比：比对得上就说明这个
# 目录早索引过，沿用原项目名重新索引（图谱按名字替换，不会留下两份同目录的图谱）。
$wantInContainer = $workInContainer.TrimEnd("/")
$projectsJson = & docker exec $Container codebase-memory-mcp cli list_projects --format json
$existingName = $null
try {
    $projects = ($projectsJson | ConvertFrom-Json).projects
    foreach ($item in $projects) {
        foreach ($key in @("root_path", "root", "path", "repo_path", "indexed_path", "directory")) {
            $root = $item.$key
            if ($root -is [string] -and $root) {
                if ($root.TrimEnd("/") -eq $wantInContainer) { $existingName = $item.name }
                break
            }
        }
        if ($existingName) { break }
    }
} catch {
    Write-Warning ("读取已索引项目失败（继续按新项目导入）：" + $_.Exception.Message)
}
$name = $Project
if (-not $name) { $name = $existingName }
if (-not $name) { $name = Split-Path -Leaf $WorkDir }

Write-Info ("工作目录   : " + $WorkDir)
Write-Info ("容器内路径 : " + $workInContainer + "（挂载根 " + $mountRoot + " -> " + $ContainerRepo + "）")
if ($existingName) {
    Write-Info ("项目名     : " + $name + "（沿用该目录已索引时的项目名）")
} else {
    Write-Info ("项目名     : " + $name)
}
if ($SkipIndex) {
    Write-Info "按 -SkipIndex 只做检查，不建索引。"
    exit 0
}

# 4) 建索引（同步等待；本脚本不在 30 秒的技能脚本超时里） ---------------------
Write-Info "开始索引（大仓库要几分钟，请等待）..."
$indexJson = & docker exec $Container codebase-memory-mcp cli --progress index_repository --repo-path $workInContainer --name $name
if ($LASTEXITCODE -ne 0) {
    throw ("索引失败（退出码 " + $LASTEXITCODE + "）：" + ($indexJson | Out-String).Trim())
}

# 5) 结果 --------------------------------------------------------------------
$uiPort = 9749
try {
    $envLines = & docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" $Container
    foreach ($line in $envLines) {
        if ($line -like "CBM_UI_PORT=*") { $uiPort = [int]($line -replace "^CBM_UI_PORT=", "") }
    }
} catch { }
$summary = $null
try { $summary = $indexJson | ConvertFrom-Json } catch { }
Write-Host ""
Write-Info "导入完成。"
if ($summary) {
    Write-Info ("节点 / 边  : " + $summary.nodes + " / " + $summary.edges)
    Write-Info ("未索引文件 : " + $summary.not_indexed_files_count)
}
Write-Info ("图谱项目   : " + $name)
Write-Info ("可视化     : 浏览器打开 http://127.0.0.1:" + $uiPort + "/")
Write-Info "后续：在这个目录下启动 Clara，读代码 / 需求解析都会优先取图谱里的信息（op=prepare 可随时确认）。"
Write-Info ("手动确认   : docker exec -i " + $Container + " codebase-memory-mcp cli list_projects --format json")
