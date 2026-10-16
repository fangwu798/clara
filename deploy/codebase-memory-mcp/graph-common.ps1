<#
  图谱部署脚本共用的小工具：install-docker.ps1 / index-workdir.ps1 / ensure-mount.ps1 都点源本文件。

  为什么集中放一处：这几件事一旦各处一份实现，就会出现「同一个目录在图谱里被命名成两个项目」「宿主与容器
  路径换算口径不一致」这类只有踩到才发现的偏差（本仓库已经踩过一次）。规则放这里，脚本只管调用。
#>

function Enable-Utf8PipelineOutput {
    # 把管道输出钉成 UTF-8：Java 侧（CodeGraphConsole）按 UTF-8 读子进程管道，而 Windows PowerShell 在
    # stdout 被重定向时用的是控制台代码页（中文机器上是 GBK），不钉住就会出现「Java 自己打印的中文正常、
    # 唯独来自脚本的那行变成 锟斤拷 / �ѿɼ� 这类乱码」。与 config/skill/scripts/_textio.py 的
    # force_utf8_stdio 是同一件事。
    # 只在被重定向时动手：人工在控制台里跑时保持原样，输出仍交给宿主按控制台编码处理。
    try {
        if ([Console]::IsOutputRedirected) {
            [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
        }
    } catch { }
}

function Get-ClaraRepoRoot {
    # 本文件固定放在 <仓库根>\deploy\codebase-memory-mcp 下，上溯两级即仓库根。
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$ScriptDir)
    return (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
}

function Get-DefaultGraphProjectName {
    # 按规则给出某个目录缺省的项目名；只做命名，不碰图谱。
    #
    # 规则（前者优先）：
    #   1) 调用方显式给的 -Project；
    #   2) 调用方传入的 -Pinned：index-workdir.ps1 只在「目录就是挂载根」时传 CLARA_CBM_PROJECT，
    #      与 config/skill/scripts/code-graph/code_graph.py 的 op=index 口径一致；
    #   3) 目录就是本仓库（脚本所在目录上溯两级）：clara-code-graph，与文档、老用户环境变量里的名字对齐；
    #   4) 目录名（...\demo -> demo）。
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TargetDir,
        [Parameter(Mandatory = $true)][string]$ScriptDir,
        [string]$Pinned = ""
    )
    if ($Pinned) { return $Pinned }
    # TrimEnd 要 char[]：传字符串数组会被 PowerShell 挑错重载，先转成 char[]。
    $separators = [char[]]@("\", "/")
    $target = $TargetDir.TrimEnd($separators)
    $repoRoot = (Get-ClaraRepoRoot -ScriptDir $ScriptDir).TrimEnd($separators)
    if ($target.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) { return "clara-code-graph" }
    return (Split-Path -Leaf $target)
}

function Convert-DockerMountSource {
    # Docker Desktop for Windows 把 bind 的 Source 报成虚拟机内路径（/run/desktop/mnt/host/e/... ，在 Windows
    # 上会被拼成 E:\run\desktop\mnt\host\e\...），要换回宿主路径才能和 -WorkDir / CLARA_CBM_REPO 比。
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Source)
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

function Get-RelativeTail {
    # 取 Path 相对 Root 的那一段；Path 不在 Root 里（含只有前缀相同的情况）返回 $null；两者相同返回空串。
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $separators = [char[]]@("\", "/")
    $root = $Root.TrimEnd($separators)
    $target = $Path.TrimEnd($separators)
    if ($target.Equals($root, [StringComparison]::OrdinalIgnoreCase)) { return "" }
    if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    $boundary = $target.Substring($root.Length, 1)
    if ($boundary -ne "\" -and $boundary -ne "/") { return $null }
    return $target.Substring($root.Length).TrimStart($separators)
}

function Get-ContainerBindMounts {
    # 容器当前的 bind 挂载：[pscustomobject]@{ Source = 宿主目录; Target = 容器内目录 }。
    # 取不到（容器不存在 / docker 不可用）时返回空数组，由调用方判断怎么报错。
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Container)
    $json = & docker inspect --format "{{json .Mounts}}" $Container 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $json) { return @() }
    $parsed = $null
    try { $parsed = $json | ConvertFrom-Json } catch { return @() }
    $result = @()
    foreach ($mount in $parsed) {
        if ($mount.Type -ne "bind" -or -not $mount.Source -or -not $mount.Destination) { continue }
        $result += [pscustomobject]@{ Source = (Convert-DockerMountSource $mount.Source); Target = $mount.Destination }
    }
    return $result
}

function Get-MountSlug {
    # 宿主目录 -> 容器内目录名（/work/roots/<slug>）：小写、非字母数字折叠成 '-'、最长 60 字符；
    # 路径里没有可用字符（纯中文目录名等）时退化为 dir-<哈希前 8 位>。
    # 同一个目录永远得到同一个 slug，所以新挂一个目录不会挪动已有目录在容器里的位置，
    # 已建好的图谱也就不必因为「多了个挂载」而重做索引。
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)
    $separators = [char[]]@("\", "/")
    $normalized = $Path.TrimEnd($separators).ToLowerInvariant()
    $slug = [regex]::Replace($normalized, "[^a-z0-9._-]+", "-")
    $slug = [regex]::Replace($slug, "-{2,}", "-").Trim("-")
    if ($slug.Length -gt 60) { $slug = $slug.Substring(0, 60).Trim("-") }
    if ($slug) { return $slug }
    $sha = [Security.Cryptography.SHA1]::Create()
    $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))
    return "dir-" + ([BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant().Substring(0, 8))
}
