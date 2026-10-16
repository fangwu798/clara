<#
.SYNOPSIS
  确保某个工作目录能被图谱容器看到：不在挂载里就补一条 bind 挂载并重建容器。

.DESCRIPTION
  Docker 不给运行中的容器加 bind 挂载，所以「换一个工作目录」在旧做法里要重跑 install-docker.ps1 换挂载根；
  而换根会让已有项目在容器里的路径全部改变，必须重做索引（几分钟）。本脚本改成「一条目录一条挂载」：

    1) 读容器当前的 bind 挂载（docker inspect，Docker Desktop 的虚拟机路径换算回宿主路径）；
    2) 目标目录已经可见就立即返回，不碰容器（这是启动时被自动调用的常见路径，必须快）；
    3) 否则给它分配容器内路径 /work/roots/<slug>（slug 由宿主路径派生，稳定可复现，
       所以新挂一个目录不会挪动已有目录的位置，已建好的图谱不必重做索引）；
    4) 把「已有的 /work/roots/* 挂载 + 本次新增」写进 deploy/docker-compose.mounts.yml（compose 覆盖文件），
       再带上它重建容器：docker compose -f docker-compose.yml -f docker-compose.mounts.yml up -d。

  为什么单独一个脚本而不并进 install-docker.ps1：程序启动时会按需自动调用它（config/clara.yml 的
  codeGraph.autoMount），所以这里不下载产物、不重建镜像、不改 MCP 注册项、不动用户环境变量，
  只做「加挂载 + 重建容器」这一件事。

.PARAMETER WorkDir
  要确保可见的工作目录（宿主路径），默认当前目录。

.PARAMETER Root
  实际挂载的目录，默认就是 WorkDir；想让整片工作区都可见时可以传父目录。

.PARAMETER Container
  容器名，默认 clara-codebase-memory-mcp。

.PARAMETER Prune
  顺带清掉清单里宿主侧已经不存在的目录挂载（目录删了，挂载没必要留着）。

.PARAMETER DryRun
  只打印计划：不写覆盖文件、不动容器。

.PARAMETER Optional
  部署或容器不可用时只提示、不报错（退出码 0）。程序启动时会自动调用本脚本，宿主机上没跑容器部署时
  不该每次启动都弹一段失败信息，所以那条路径加了这个开关。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\ensure-mount.ps1 -WorkDir E:\work\demo

.EXAMPLE
  # 只想确认会怎么挂、不动容器
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\ensure-mount.ps1 -WorkDir D:\work\demo -DryRun
#>
[CmdletBinding()]
param(
    [string]$WorkDir = "",
    [string]$Root = "",
    [string]$Container = "clara-codebase-memory-mcp",
    [switch]$Prune,
    [switch]$DryRun,
    [switch]$Optional
)

$ErrorActionPreference = "Stop"
$ContainerRootsPrefix = "/work/roots"
$ContainerRepo = "/work/repo"
$PathSeparators = [char[]]@("\", "/")

. (Join-Path $PSScriptRoot "graph-common.ps1")
Enable-Utf8PipelineOutput

$DeployDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ComposeFile = Join-Path $DeployDir "docker-compose.yml"
$MountsFile = Join-Path $DeployDir "docker-compose.mounts.yml"
$EnvFile = Join-Path $PSScriptRoot ".env"

function Write-Info([string]$Message) { Write-Host ("[cbm-mount] " + $Message) }

# 0) docker 与容器 ------------------------------------------------------------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    if ($Optional) {
        Write-Info "跳过：未找到 docker 命令（用容器部署图谱才需要挂载）"
        exit 0
    }
    throw "未找到 docker 命令：请先安装并启动 Docker Desktop。"
}
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$null = & docker inspect $Container 2>&1
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $previous
if (-not $exists) {
    if ($Optional) {
        Write-Info ("跳过：容器 " + $Container + " 不存在或未启动（用容器部署图谱才需要挂载）")
        exit 0
    }
    throw ("容器 " + $Container + " 不存在或未启动：先跑 deploy\codebase-memory-mcp\install-docker.ps1。")
}

# 1) 目标目录 ----------------------------------------------------------------
if (-not $WorkDir) { $WorkDir = (Get-Location).Path }
if (-not (Test-Path -LiteralPath $WorkDir)) { throw ("工作目录不存在：" + $WorkDir) }
$WorkDir = (Resolve-Path -LiteralPath $WorkDir).Path
if (-not $Root) { $Root = $WorkDir }
if (-not (Test-Path -LiteralPath $Root)) { throw ("要挂载的目录不存在：" + $Root) }
$Root = (Resolve-Path -LiteralPath $Root).Path

# 2) 容器当前挂了哪些宿主目录 -------------------------------------------------
$mounts = @(Get-ContainerBindMounts -Container $Container)

# 已经可见就到此为止：这条路径会被程序启动时频繁走到，必须什么都不做。
# 加了 -Prune 时不停在这里：那一次的目的是清掉已经失效的挂载，得走到下面的清单逻辑。
$covered = $false
if (-not $Prune) {
    foreach ($mount in $mounts) {
        if ((Get-RelativeTail -Root $mount.Source -Path $WorkDir) -ne $null) {
            Write-Info ("已可见，无需挂载：" + $WorkDir + "（挂载 " + $mount.Source.TrimEnd($PathSeparators) + " -> " + $mount.Target + "）")
            exit 0
        }
    }
}
foreach ($mount in $mounts) {
    if ((Get-RelativeTail -Root $mount.Source -Path $WorkDir) -ne $null) { $covered = $true; break }
}

# 3) 目标挂载清单：现有 /work/roots/* 挂载 + 本次新增 -------------------------
$desired = New-Object Collections.Generic.List[object]
foreach ($mount in $mounts) {
    $target = $mount.Target.TrimEnd($PathSeparators)
    if (-not $target.StartsWith($ContainerRootsPrefix + "/", [StringComparison]::OrdinalIgnoreCase)) { continue }
    if ($Prune -and -not (Test-Path -LiteralPath $mount.Source)) {
        Write-Info ("清掉宿主侧已不存在的挂载：" + $mount.Source)
        continue
    }
    $desired.Add([pscustomobject]@{ Host = $mount.Source.TrimEnd($PathSeparators); Target = $target })
}
$newTarget = $ContainerRootsPrefix + "/" + (Get-MountSlug -Path $Root)
if (-not $covered) {
    $desired.Add([pscustomobject]@{ Host = $Root; Target = $newTarget })
}

# 4) 写 compose 覆盖文件（内容没变就不写，避免无谓的重建） ---------------------
# 一份都不需要保留（-Prune 清空了失效挂载、而本次目录本来就可见）时删掉覆盖文件：留一个空的 volumes
# 列表会被 compose 判成非法配置。
if ($desired.Count -eq 0) {
    if ($DryRun) {
        Write-Info ("[DryRun] 没有需要保留的按需挂载：会删掉 " + $MountsFile)
        exit 0
    }
    if (Test-Path -LiteralPath $MountsFile) {
        Remove-Item -LiteralPath $MountsFile -Force
        Write-Info "没有需要保留的按需挂载，已删掉覆盖文件（挂载只剩 docker-compose.yml 的挂载根）"
    } else {
        Write-Info "没有需要保留的按需挂载，也不存在覆盖文件"
    }
    $MountsFile = ""
} else {
    $lines = New-Object Collections.Generic.List[string]
    $lines.Add("# 由 ensure-mount.ps1 生成：按需挂进图谱容器的工作目录（一条目录一条挂载，容器内路径固定，")
    $lines.Add("# 所以新增目录不会挪动已有目录的位置、不必重做已有图谱的索引）。")
    $lines.Add("# 改动后在 deploy 目录执行：docker compose -f docker-compose.yml -f docker-compose.mounts.yml up -d codebase-memory-mcp")
    $lines.Add("services:")
    $lines.Add("  codebase-memory-mcp:")
    $lines.Add("    volumes:")
    foreach ($item in $desired) {
        # 变量名不能叫 $host：PowerShell 里 $Host 是只读的自动变量（大小写不敏感）。
        $mountSource = $item.Host -replace "\\", "/"
        $lines.Add(("      - `"" + $mountSource + ":" + $item.Target + ":ro`""))
    }
    $content = ($lines -join "`r`n") + "`r`n"
    $changed = $true
    if (Test-Path -LiteralPath $MountsFile) {
        $changed = ([IO.File]::ReadAllText($MountsFile) -ne $content)
    }
    if ($DryRun) {
        Write-Info ("[DryRun] 将写入 " + $MountsFile + "：")
        foreach ($item in $desired) { Write-Info ("  " + $item.Host + " -> " + $item.Target + "（只读）") }
        exit 0
    }
    if ($changed) {
        [IO.File]::WriteAllText($MountsFile, $content, (New-Object Text.UTF8Encoding($false)))
        Write-Info ("已更新挂载清单：" + $MountsFile)
    } else {
        Write-Info "挂载清单无变化"
    }
}

# 5) 正在索引时不要重建容器：重建会把索引打断，留下半份图谱 -------------------
$top = & docker top $Container 2>$null
$indexing = $false
foreach ($line in @($top)) {
    if ($line -is [string] -and $line -match "index_repository") { $indexing = $true; break }
}
if ($indexing) {
    Write-Info "容器正在建索引，先不重建（会被打断）：稍后重跑本脚本，或在容器空闲时执行 /graph import。"
    exit 3
}

# 6) 重建容器（有覆盖文件时带上它） -------------------------------------------
$composeArgs = @("compose", "--env-file", $EnvFile, "-f", $ComposeFile)
if ($MountsFile) { $composeArgs += @("-f", $MountsFile) }
$composeArgs += @("up", "-d", "codebase-memory-mcp")
& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up 失败：请看上面的输出（挂载清单已写好，修好原因后重跑本脚本即可）。"
}

# 7) 回读校验并报出该目录在容器内的路径 ---------------------------------------
$after = @(Get-ContainerBindMounts -Container $Container)
# 「工作目录」在容器里的实际路径：取最具体的那个挂载点，与 index-workdir.ps1 的口径一致。
# 校验的是「工作目录可见」而不是「刚加了这条挂载」：目录本来就被别的挂载（如 /work/repo 那一条）
# 覆盖时，这一步同样应该成功。
$visible = $null
foreach ($mount in $after) {
    $tail = Get-RelativeTail -Root $mount.Source.TrimEnd($PathSeparators) -Path $WorkDir
    if ($null -eq $tail) { continue }
    if (-not $visible -or $mount.Target.Length -gt $visible.Target.Length) {
        $visible = [pscustomobject]@{
            Host = $mount.Source.TrimEnd($PathSeparators)
            Target = $mount.Target.TrimEnd($PathSeparators)
            Tail = $tail
        }
    }
}
if (-not $visible) {
    throw ("重建后容器仍看不到这个目录：" + $WorkDir + "（挂载清单写在 " + (Join-Path $DeployDir "docker-compose.mounts.yml") + "，可手工检查）")
}
$workInContainer = $visible.Target
if ($visible.Tail) { $workInContainer = ($visible.Target + "/" + $visible.Tail).Replace("\\", "/") }
if ($visible.Host.Equals($Root, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Info ("已挂载：" + $Root + " -> " + $visible.Target + "（只读）")
} else {
    Write-Info ("容器已能看到：" + $WorkDir + "（挂载 " + $visible.Host + " -> " + $visible.Target + "）")
}
Write-Info "下一步：在程序里执行 /graph import 导入这个目录的代码（索引要几分钟，也可以用 index-workdir.ps1）。"
