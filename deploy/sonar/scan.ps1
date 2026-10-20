<#
.SYNOPSIS
    在本地执行 SonarQube 代码扫描，导入 JaCoCo 覆盖率并汇总质量门禁结果。

.DESCRIPTION
    部署/运维入口：位于 deploy/sonar，供本地开发机一键执行代码扫描。
    脚本按以下顺序执行：
      1. 校验 Maven 与 SonarQube 服务可用性；
      2. 解析 Token（参数 > 环境变量 SONAR_TOKEN > .local/sonar-token.txt）；
      3. 按需运行单元测试，生成各模块 target/site/jacoco/jacoco.xml；
      4. 调用 Maven Sonar Scanner 上传分析结果（默认项目键 com.clara:clara）；
      5. 轮询质量门禁并打印指标明细，门禁失败时以非零码退出。

    扫描日志写入 .local/sonar/sonar-<时间戳>.log。

.PARAMETER SonarHostUrl
    SonarQube 地址，默认 http://localhost:9100。

.PARAMETER ProjectKey
    项目键，默认取 sonar-project.properties 中的 com.clara:clara。

.PARAMETER Token
    SonarQube Token。缺省时依次读取环境变量 SONAR_TOKEN、.local/sonar-token.txt。

.PARAMETER TokenFile
    自定义 Token 文件路径（优先级高于默认 .local/sonar-token.txt）。

.PARAMETER RunTests
    强制先运行 mvn test 再扫描。未指定时，若找不到任何 JaCoCo 报告会自动运行。

.PARAMETER SkipTests
    跳过测试。即使没有覆盖率报告也不运行（覆盖率指标会缺失）。

.PARAMETER SkipQualityGate
    上传完成后不等待质量门禁结果。

.PARAMETER QualityGateTimeoutSeconds
    等待质量门禁计算的超时时间，默认 300 秒。

.PARAMETER Open
    扫描结束后在浏览器打开项目看板。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\sonar\scan.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\sonar\scan.ps1 -RunTests -Open

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\sonar\scan.ps1 -ProjectKey "com.clara:clara" -SkipQualityGate
#>
[CmdletBinding()]
param(
    [string]$SonarHostUrl = "http://localhost:9100",
    [string]$ProjectKey = "com.clara:clara",
    [string]$Token = "",
    [string]$TokenFile = "",
    [switch]$RunTests,
    [switch]$SkipTests,
    [switch]$SkipQualityGate,
    [int]$QualityGateTimeoutSeconds = 300,
    [switch]$Open
)

$ErrorActionPreference = "Stop"

if ($RunTests -and $SkipTests) {
    throw "参数冲突：-RunTests 与 -SkipTests 不能同时使用。"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
$scannerVersion = "3.10.0.2594"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-SonarToken {
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        return $Token.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($env:SONAR_TOKEN)) {
        return $env:SONAR_TOKEN.Trim()
    }
    $candidate = if (-not [string]::IsNullOrWhiteSpace($TokenFile)) {
        $TokenFile
    } else {
        Join-Path $repoRoot ".local\sonar-token.txt"
    }
    if (Test-Path -LiteralPath $candidate) {
        $value = (Get-Content -LiteralPath $candidate -Raw).Trim()
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }
    throw "缺少 SonarQube Token。请传入 -Token，或设置环境变量 SONAR_TOKEN，或把 Token 写入 $candidate。"
}

function Get-SonarHeaders([string]$Value) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${Value}:"))
    return @{ Authorization = "Basic $basic" }
}

function Get-QualityGate([string]$BaseUrl, [string]$Key, [hashtable]$Headers) {
    $encoded = [uri]::EscapeDataString($Key)
    return Invoke-RestMethod -Uri "$BaseUrl/api/qualitygates/project_status?projectKey=$encoded" -Headers $Headers -TimeoutSec 15
}

function Write-QualityGate([object]$Gate) {
    $status = $Gate.projectStatus.status
    $color = switch ($status) {
        "OK" { "Green" }
        "ERROR" { "Red" }
        default { "Yellow" }
    }
    Write-Host ""
    Write-Host "质量门禁：$status" -ForegroundColor $color
    $conditions = @($Gate.projectStatus.conditions)
    if ($conditions.Count -gt 0) {
        $conditions |
            Select-Object metricKey, status, actualValue, errorThreshold |
            Format-Table -AutoSize | Out-Host
    }
}

Push-Location $repoRoot
try {
    Write-Step "检查运行环境"
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) {
        throw "未找到 mvn，请先安装 Maven 并加入 PATH。"
    }
    Write-Host "仓库根目录：$repoRoot"
    Write-Host "SonarQube ：$SonarHostUrl"
    Write-Host "项目键    ：$ProjectKey"

    Write-Step "检查 SonarQube 服务"
    try {
        $system = Invoke-RestMethod -Uri "$SonarHostUrl/api/system/status" -TimeoutSec 10
    } catch {
        throw "无法连接 SonarQube（$SonarHostUrl）：$($_.Exception.Message)。请确认本地服务已启动。"
    }
    if ($system.status -ne "UP") {
        throw "SonarQube 状态为 $($system.status)，无法执行扫描。"
    }
    Write-Host "SonarQube 版本：$($system.version)（$($system.status)）"

    $tokenValue = Resolve-SonarToken
    $headers = Get-SonarHeaders $tokenValue

    Write-Step "准备覆盖率数据"
    $jacocoReports = @(Get-ChildItem -Path $repoRoot -Filter "jacoco.xml" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\target\\site\\jacoco\\jacoco\.xml$" })
    $shouldRunTests = $RunTests -or ($jacocoReports.Count -eq 0 -and -not $SkipTests)
    if ($shouldRunTests) {
        Write-Host "执行 mvn -B test（生成 JaCoCo 报告）..."
        & mvn -B test
        if ($LASTEXITCODE -ne 0) {
            throw "单元测试失败（退出码 $LASTEXITCODE），已停止扫描。"
        }
        $jacocoReports = @(Get-ChildItem -Path $repoRoot -Filter "jacoco.xml" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "\\target\\site\\jacoco\\jacoco\.xml$" })
    } elseif ($SkipTests) {
        Write-Host "已跳过测试（-SkipTests）。"
    } else {
        Write-Host "复用已有 JaCoCo 报告：$($jacocoReports.Count) 个模块。"
    }
    if ($jacocoReports.Count -eq 0) {
        Write-Warning "没有找到 JaCoCo 报告，本次扫描不会包含覆盖率数据。"
    }

    Write-Step "执行 Sonar 扫描"
    $logDir = Join-Path $repoRoot ".local\sonar"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $logPath = Join-Path $logDir ("sonar-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))

    $scanArguments = @(
        "-B",
        "org.sonarsource.scanner.maven:sonar-maven-plugin:${scannerVersion}:sonar",
        "-Dsonar.host.url=$SonarHostUrl",
        "-Dsonar.login=$tokenValue",
        "-Dsonar.projectKey=$ProjectKey",
        "-Dsonar.qualitygate.wait=false"
    )
    # 仓库自带 .m2/repository（含 Sonar 插件）：优先使用，避免离线环境重新下载。
    $localRepo = Join-Path $repoRoot ".m2\repository"
    $pluginJar = Join-Path $localRepo "org\sonarsource\scanner\maven\sonar-maven-plugin\$scannerVersion\sonar-maven-plugin-$scannerVersion.jar"
    if (Test-Path -LiteralPath $pluginJar) {
        $scanArguments += "-Dmaven.repo.local=$localRepo"
        Write-Host "Maven 本地仓库：$localRepo"
    }

    $displayArguments = $scanArguments | ForEach-Object {
        if ($_ -like "-Dsonar.login=*") { "-Dsonar.login=***" } else { $_ }
    }
    Write-Host "执行：mvn $($displayArguments -join ' ')" -ForegroundColor DarkGray
    & mvn @scanArguments 2>&1 | Tee-Object -FilePath $logPath
    $scanExit = $LASTEXITCODE
    Write-Host "扫描日志：$logPath"
    if ($scanExit -ne 0) {
        throw "Sonar 扫描失败（退出码 $scanExit），请查看日志：$logPath"
    }
    $scanOutput = @(Get-Content -LiteralPath $logPath -ErrorAction SilentlyContinue)

    $dashboard = "$SonarHostUrl/dashboard?id=$([uri]::EscapeDataString($ProjectKey))"
    Write-Host "项目看板：$dashboard"

    if (-not $SkipQualityGate) {
        Write-Step "等待分析计算完成"
        $deadline = (Get-Date).AddSeconds($QualityGateTimeoutSeconds)
        $taskId = $null
        $task = $null
        $taskStatus = "UNKNOWN"
        foreach ($line in $scanOutput) {
            $match = [regex]::Match([string]$line, "api/ce/task\?id=([A-Za-z0-9_-]+)")
            if ($match.Success) { $taskId = $match.Groups[1].Value }
        }
        if ($taskId) {
            Write-Host "计算任务：$taskId"
            while ((Get-Date) -lt $deadline) {
                try {
                    $task = Invoke-RestMethod -Uri "$SonarHostUrl/api/ce/task?id=$taskId" -Headers $headers -TimeoutSec 15
                    $taskStatus = $task.task.status
                } catch {
                    $taskStatus = "UNKNOWN"
                }
                if (@("SUCCESS", "FAILED", "CANCELED") -contains $taskStatus) { break }
                Start-Sleep -Seconds 2
            }
            if ($taskStatus -eq "FAILED" -or $taskStatus -eq "CANCELED") {
                $message = if ($task.task.errorMessage) { $task.task.errorMessage } else { "无详细原因" }
                throw "Sonar 分析计算失败（$taskStatus）：$message"
            }
            if ($taskStatus -ne "SUCCESS") {
                Write-Warning "等待分析计算超时（$QualityGateTimeoutSeconds 秒），可稍后在看板查看结果。"
            }
        } else {
            Write-Warning "未从扫描日志中解析到计算任务 ID，直接查询质量门禁。"
        }

        Write-Step "读取质量门禁结果"
        $gate = $null
        while ((Get-Date) -lt $deadline) {
            try { $gate = Get-QualityGate $SonarHostUrl $ProjectKey $headers } catch { $gate = $null }
            if ($null -ne $gate -and $gate.projectStatus.status -and $gate.projectStatus.status -ne "NONE") {
                break
            }
            Start-Sleep -Seconds 2
        }

        if ($null -eq $gate -or -not $gate.projectStatus.status -or $gate.projectStatus.status -eq "NONE") {
            Write-Warning "等待质量门禁超时（$QualityGateTimeoutSeconds 秒），可稍后在看板查看结果。"
        } else {
            Write-QualityGate $gate
            if ($gate.projectStatus.status -eq "ERROR") {
                Write-Host ""
                Write-Host "质量门禁未通过，详见：$dashboard" -ForegroundColor Red
                $global:LASTEXITCODE = 1
                exit 1
            }
        }
    }

    if ($Open) {
        Start-Process $dashboard
    }
    Write-Host ""
    Write-Host "扫描完成。" -ForegroundColor Green
}
finally {
    Pop-Location
}
