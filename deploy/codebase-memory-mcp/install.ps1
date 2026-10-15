<#
.SYNOPSIS
  在本机部署 codebase-memory-mcp（代码知识图谱 MCP 服务端），并把它注册到 Clara 的 config/mcp。

.DESCRIPTION
  做四件事：
    1) 从 GitHub Release 下载 Windows 版压缩包并解压出 codebase-memory-mcp.exe；
    2) 用官方 checksums.txt 校验压缩包 SHA256（校验和取不到时给出警告，可用 -RequireChecksum 改为硬失败）；
    3) 把可执行文件绝对路径写进 <仓库根>/config/mcp/mcp.json，使 Clara 启动时能发现并下发这些工具；
    4)（可选）对新装的图谱执行一次索引，默认索引当前目录。

  两点约定（与 Clara 侧的实现有关，改动前请先看源码）：
    * 安装目录不能含空格：Clara 的 stdio 注册会把命令行按空白拆分，含空格的路径会被拆坏；
    * 只取二进制、不跑官方 install.ps1：官方安装脚本会改写各 AI 客户端的配置，而 Clara 的接入点是
      config/mcp/mcp.json，改由本脚本维护，避免两处配置互相覆盖。

.PARAMETER Version
  Release 版本号，默认 v0.11.0。传 latest 会用 GitHub API 取最新版本标签。

.PARAMETER InstallDir
  安装目录，默认 C:\clara-tools\codebase-memory-mcp；不能含空格。

.PARAMETER IndexRoot
  安装后要索引的目录，默认当前工作目录。传 -SkipIndex 可跳过索引。

.PARAMETER SkipIndex
  跳过索引步骤（只装二进制并注册 MCP）。

.PARAMETER SkipMcpConfig
  不改 config/mcp/mcp.json（自己维护配置时使用）。

.PARAMETER SkipUserEnv
  不写用户环境变量 CLARA_CBM_BINARY。

.PARAMETER RequireChecksum
  取不到官方校验和时直接失败（默认只警告后继续）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install.ps1

.EXAMPLE
  # 装到自定义目录并索引指定仓库
  powershell -ExecutionPolicy Bypass -File deploy\codebase-memory-mcp\install.ps1 -InstallDir D:\tools\cbm -IndexRoot E:\workspace\demo
#>
[CmdletBinding()]
param(
    [string]$Version = "v0.11.0",
    [string]$InstallDir = "C:\clara-tools\codebase-memory-mcp",
    [string]$IndexRoot = "",
    [switch]$SkipIndex,
    [switch]$SkipMcpConfig,
    [switch]$SkipUserEnv,
    [switch]$RequireChecksum
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 仓库根：本脚本位于 <仓库根>/deploy/codebase-memory-mcp 下，上溯两级即仓库根。
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$McpConfig = Join-Path $RepoRoot "config\mcp\mcp.json"
$AssetBase = "https://github.com/DeusData/codebase-memory-mcp/releases/download"

function Write-Info([string]$Message) { Write-Host ("[cbm] " + $Message) }

# 1) 版本与安装目录校验 ------------------------------------------------------
if ($Version -eq "latest") {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/DeusData/codebase-memory-mcp/releases/latest" -UseBasicParsing
    $Version = $release.tag_name
    Write-Info ("latest 解析为 " + $Version)
}
if ($Version -notmatch "^v") { $Version = "v" + $Version }
if ($InstallDir -match "\s") {
    throw ("安装目录不能含空格：" + $InstallDir + "（Clara 的 stdio 注册按空白拆分命令行）")
}
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

# 2) 下载压缩包与官方校验和 --------------------------------------------------
$ZipName = "codebase-memory-mcp-windows-amd64.zip"
$ZipUrl = ($AssetBase + "/" + $Version + "/" + $ZipName)
$ZipPath = Join-Path $env:TEMP $ZipName
Write-Info ("下载 " + $ZipUrl)
Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing

$expected = $null
try {
    $checksumsUrl = ($AssetBase + "/" + $Version + "/checksums.txt")
    $checksums = (Invoke-WebRequest -Uri $checksumsUrl -UseBasicParsing).Content
    foreach ($line in ($checksums -split "`n")) {
        # 官方格式：<sha256>  <文件名>
        if ($line -match "^\s*([0-9a-fA-F]{64})\s+" -and $line -match [regex]::Escape($ZipName)) {
            $expected = $Matches[1].ToLower()
            break
        }
    }
} catch {
    Write-Warning ("无法获取官方 checksums.txt：" + $_.Exception.Message)
}
if ($expected) {
    $actual = (Get-FileHash -Path $ZipPath -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) {
        throw ("SHA256 校验失败：期望 " + $expected + "，实际 " + $actual)
    }
    Write-Info "SHA256 校验通过"
} elseif ($RequireChecksum) {
    throw "取不到官方校验和，且指定了 -RequireChecksum；请稍后重试或改用 --Version 指定已核对的版本"
} else {
    Write-Warning "未校验下载包（未能取到官方 checksums.txt）"
}

# 3) 解压出可执行文件 --------------------------------------------------------
$extractDir = Join-Path $env:TEMP ("cbm-" + $Version)
if (Test-Path $extractDir) { Remove-Item -LiteralPath $extractDir -Recurse -Force }
Expand-Archive -Path $ZipPath -DestinationPath $extractDir -Force
$exe = Get-ChildItem -Path $extractDir -Recurse -Filter "codebase-memory-mcp.exe" | Select-Object -First 1
if (-not $exe) { throw ("压缩包内未找到 codebase-memory-mcp.exe：" + $extractDir) }
$binary = Join-Path $InstallDir "codebase-memory-mcp.exe"
Copy-Item -LiteralPath $exe.FullName -Destination $binary -Force
Write-Info ("已安装二进制：" + $binary)
& $binary --version 2>&1 | ForEach-Object { Write-Info ("版本输出：" + $_) }

# 4) 写入 config/mcp/mcp.json ------------------------------------------------
if (-not $SkipMcpConfig) {
    if (-not (Test-Path $McpConfig)) { throw ("未找到 MCP 配置：" + $McpConfig) }
    $json = Get-Content -LiteralPath $McpConfig -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $json.mcpServers) { $json | Add-Member -MemberType NoteProperty -Name mcpServers -Value (New-Object psobject) }
    $entry = New-Object psobject
    $entry | Add-Member -MemberType NoteProperty -Name command -Value @($binary)
    $entry | Add-Member -MemberType NoteProperty -Name args -Value @()
    if ($json.mcpServers.PSObject.Properties.Name -contains "codebase-memory-mcp") {
        $json.mcpServers.PSObject.Properties.Remove("codebase-memory-mcp")
    }
    $json.mcpServers | Add-Member -MemberType NoteProperty -Name "codebase-memory-mcp" -Value $entry
    $rendered = $json | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($McpConfig, $rendered + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Info ("已写入 MCP 注册项：" + $McpConfig)
} else {
    Write-Info "按 -SkipMcpConfig 跳过 config/mcp/mcp.json"
}

# 5) 用户环境变量：只写二进制位置 --------------------------------------------
# CLARA_CBM_BINARY 供 config/skill/scripts/code-graph/code_graph.py 定位 CLI。
#
# 刻意不设 CBM_CACHE_DIR：图谱启动时会校验缓存目录及其父目录的 DACL，只要路径链上存在
# 「非受信主体可写」的目录（C:\ 下新建的目录会继承 Authenticated Users: 修改），CLI 会直接
# 以「secure CLI coordination could not be created (cache-private)」失败退出。用默认位置
# %USERPROFILE%\.cache\codebase-memory-mcp 最省事，也不会把索引放在人人可写的目录里。
if (-not $SkipUserEnv) {
    & setx CLARA_CBM_BINARY $binary | Out-Null
    Write-Info ("已设置用户环境变量 CLARA_CBM_BINARY=" + $binary + "（新开的终端才生效）")
}

# 6) 索引（可选） ------------------------------------------------------------
if (-not $SkipIndex) {
    $root = $IndexRoot
    if (-not $root) { $root = (Get-Location).Path }
    $root = (Resolve-Path $root).Path
    Write-Info ("开始索引：" + $root + "（大仓库可能需要几分钟）")
    & $binary cli --progress index_repository --repo-path $root
    if ($LASTEXITCODE -ne 0) { Write-Warning ("索引返回码 " + $LASTEXITCODE + "，可稍后重跑：codebase-memory-mcp cli index_repository --repo-path <目录>") }
}

Write-Host ""
Write-Info "完成。后续步骤："
Write-Info ("  1) 新开一个终端（让 " + $binary + " 生效），在仓库根运行 clara.bat / clara.sh；")
Write-Info "  2) 启动横幅会打印「mcp : N 个服务端 / M 个外部工具」，之后模型即可调用 code_graph 相关工具；"
Write-Info "  3) 代码改动后重跑索引：codebase-memory-mcp cli index_repository --repo-path <目录>。"