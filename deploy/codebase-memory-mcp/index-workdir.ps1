<#
.SYNOPSIS
  把一个工作目录导入代码知识图谱（容器部署），一条命令完成。

.DESCRIPTION
  导入 = 让图谱把某个目录索引成项目，之后 code_graph（以及需求解析等用到它的工作流）读这个目录时
  就能优先取图谱里的文件大纲 / 符号 / 调用关系。本脚本做五件事：
    1) 定位容器（默认 clara-codebase-memory-mcp），读它实际挂了哪些宿主目录（docker inspect）；
    2) 把工作目录换算成容器内路径；不在挂载里就先按需补一条挂载（ensure-mount.ps1，-NoMount 可关掉），
       补不上再退回放宽挂载根（可用 -Widen 直接执行）；
    3) 查已有项目：该目录此前索引过就沿用原项目名，避免同一个目录出现两份图谱；
    4) 在容器里对该目录执行 index_repository（大仓库要几分钟，脚本会等它跑完）；
    5) 打印项目名、容器内路径、节点 / 边计数与可视化地址。

  为什么导入不放在对话里自动做：宿主侧单次技能脚本执行有 30 秒硬超时，而索引一个中等仓库就要一分钟
  上下，同步索引会被掐断、还留下半份图谱。所以导入用本脚本（不受超时限制）；对话里让模型调
  code_graph 的 op=prepare / op=index 也可以，那条路是后台发起、秒回。

.PARAMETER WorkDir
  要导入的工作目录（宿主路径），默认当前目录。

.PARAMETER Project
  图谱里的项目名；缺省按 graph-common.ps1 的规则取：该目录已索引过就沿用原项目名，其次目录就是
  挂载点时的 CLARA_CBM_PROJECT，其次本仓库用 clara-code-graph，最后取目录名（如 ...\demo 就是 demo）。

.PARAMETER Container
  容器名，默认 clara-codebase-memory-mcp。

.PARAMETER SkipIndex
  只做检查与路径换算，不真正建索引：用来确认「会导入哪个目录、叫什么项目」。

.PARAMETER Widen
  按需补挂失败之后，自动用两边的公共父目录调用 install-docker.ps1 放宽挂载根。注意这会让容器重建、
  已索引项目的项目根发生变化并重做一次索引，耗时几分钟；正常情况用不着它。

.PARAMETER NoMount
  目录不在挂载里时不要动容器（不补挂、不重建），只打印三条可照做的办法后退出。

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
    [switch]$Widen,
    [switch]$NoMount
)

$ErrorActionPreference = "Stop"
$ContainerRepo = "/work/repo"
# 路径分隔符集合：TrimEnd / TrimStart 要 char[]，字符串数组会被 PowerShell 误判成 Split(char, int) 那类重载，
# 故统一在这里转成 char[]，并按同口径的正则拆分段。
$PathSeparators = [char[]]@("\", "/")
# 共用小工具（项目名规则、宿主 <-> 容器路径换算、挂载表读取）都在 graph-common.ps1：同一个目录在两处
# 必须得到同一个名字、两侧换算必须同口径，否则图谱里会出现同目录的两份索引。
. (Join-Path $PSScriptRoot "graph-common.ps1")
Enable-Utf8PipelineOutput

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

# 2) 容器挂了哪些宿主目录（挂载表） -------------------------------------------
# 直接问容器挂着什么（docker inspect），而不是信宿主侧的配置：两者不一致时（改过挂载根、还没重建容器）
# 以容器为准，否则会把宿主路径换算成容器里根本不存在的路径。这与 code_graph.py 做两侧路径换算时用的是
# 同一份挂载表，口径一致，项目名沿用 / 重复索引才不会被算错。
$mounts = @(Get-ContainerBindMounts -Container $Container)
if ($mounts.Count -eq 0) {
    throw ("容器 " + $Container + " 没有任何 bind 挂载：容器可能不是本仓库的部署脚本建的。")
}

function Select-VisibleMount([object[]]$CandidateMounts, [string]$Path) {
    # 命中最具体的那个挂载点：多个挂载同时包含该目录时取容器内路径最长的（嵌套挂载的情形）；
    # 目录不在任何挂载里时返回 $null。
    $best = $null
    foreach ($mount in $CandidateMounts) {
        $tail = Get-RelativeTail -Root $mount.Source -Path $Path
        if ($null -eq $tail) { continue }
        if (-not $best -or $mount.Target.Length -gt $best.Target.Length) {
            $best = [pscustomobject]@{
                Host = $mount.Source.TrimEnd($PathSeparators)
                Target = $mount.Target.TrimEnd($PathSeparators)
                Tail = $tail
            }
        }
    }
    return $best
}

$match = Select-VisibleMount -CandidateMounts $mounts -Path $WorkDir

# 不在任何挂载里：容器看不到这个目录。默认按需补一条挂载（ensure-mount.ps1 挂到 /work/roots/<slug>，
# 只新增、不挪动已有目录在容器里的位置，所以已建好的图谱不用重做索引）；加了 -NoMount 就只提示不动容器。
if (-not $match -and -not $NoMount) {
    Write-Info ("容器没挂 " + $WorkDir + "，先按需补一条只读挂载（不影响已索引的目录）...")
    $ensure = Join-Path $PSScriptRoot "ensure-mount.ps1"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $ensure -WorkDir $WorkDir -Container $Container
    $ensureCode = $LASTEXITCODE
    if ($ensureCode -eq 0) {
        $match = Select-VisibleMount -CandidateMounts @(Get-ContainerBindMounts -Container $Container) -Path $WorkDir
    } else {
        Write-Warning ("按需补挂没成功（退出码 " + $ensureCode + "），退回原来的两种办法。")
    }
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

if (-not $match) {
    # 容器看不到这个目录：给出能照做的三条路（-Widen 则直接执行 b）。放宽挂载根会让容器重建、已索引项目
    # 的项目根发生变化并重做一次索引，所以它排在「只补一条挂载」之后。
    $install = Join-Path $PSScriptRoot "install-docker.ps1"
    $ensure = Join-Path $PSScriptRoot "ensure-mount.ps1"
    $ancestor = $null
    foreach ($mount in $mounts) {
        $candidate = Get-CommonAncestor $mount.Source.TrimEnd($PathSeparators) $WorkDir
        if ($candidate -and (-not $ancestor -or $candidate.Length -lt $ancestor.Length)) { $ancestor = $candidate }
    }
    if (-not $ancestor) { $ancestor = Split-Path -Parent $WorkDir }
    $fix = "powershell -ExecutionPolicy Bypass -File " + $install + " -RepoPath `"" + $WorkDir + "`" -MountRoot `"" + $ancestor + "`""
    if (-not $Widen) {
        Write-Host ""
        Write-Info "这个目录不在容器的挂载里，容器看不到它，所以没法索引。三条路（推荐 a）："
        Write-Info "  a) 只给它补一条挂载（不挪动已有目录，已建好的图谱不用重做索引）："
        Write-Info ("     powershell -ExecutionPolicy Bypass -File " + $ensure + " -WorkDir `"" + $WorkDir + "`"")
        Write-Info "  b) 放宽挂载根到工作区根（一个容器覆盖多个工作目录，但容器会重建、索引要重做）："
        Write-Info ("     " + $fix)
        Write-Info "  c) 只想分析这一个目录：把上面的 -MountRoot 换成它自己即可（等于容器只挂这一个目录）。"
        Write-Info "重跑时可加 -Widen 让本脚本自己执行 b)。"
        exit 2
    }
    Write-Info ("目录不在挂载内，按 -Widen 放宽到工作区的公共父目录：" + $ancestor)
    & powershell -NoProfile -ExecutionPolicy Bypass -File $install -RepoPath $WorkDir -MountRoot $ancestor
    if ($LASTEXITCODE -ne 0) { throw "放宽挂载根失败，请看上面的输出。" }
    exit 0
}

$mountRoot = $match.Host
$workInContainer = $match.Target
if ($match.Tail) { $workInContainer = ($match.Target + "/" + $match.Tail).Replace("\", "/") }

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
if (-not $name) {
    # CLARA_CBM_PROJECT 只在「目录就是挂载根」时认（与 code_graph.py 的 op=index 同口径）：容器内项目根
    # 是 /work/repo，宿主路径换算不出来，落不回落全看这个名字对不对得上。
    $pinned = ""
    if (-not $match.Tail -and $match.Target.TrimEnd($PathSeparators).Equals($ContainerRepo.TrimEnd($PathSeparators), [StringComparison]::OrdinalIgnoreCase)) {
        $pinned = $env:CLARA_CBM_PROJECT
        if (-not $pinned) { $pinned = [Environment]::GetEnvironmentVariable("CLARA_CBM_PROJECT", "User") }
    }
    $name = Get-DefaultGraphProjectName -TargetDir $WorkDir -ScriptDir $PSScriptRoot -Pinned $pinned
}

Write-Info ("工作目录   : " + $WorkDir)
Write-Info ("容器内路径 : " + $workInContainer + "（挂载 " + $mountRoot + " -> " + $match.Target + "）")
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
