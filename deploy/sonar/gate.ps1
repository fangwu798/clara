<#
.SYNOPSIS
    创建 / 校准 Clara 专用质量门禁，并绑定到指定 SonarQube 项目。

.DESCRIPTION
    Sonar 内置的 Sonar way 门禁用 new_coverage（行覆盖 + 分支覆盖的综合口径）判定新代码覆盖率，
    而团队约定的覆盖率标准是 JaCoCo 行覆盖率 80%（见 deploy/coverage/check-coverage.mjs）。
    本脚本创建的 Clara Gate 用 new_line_coverage 替换 new_coverage，其余条件与 Sonar way 保持一致
    （可靠性 / 安全性 / 可维护性评级、重复率、安全热点复核率），不会放宽非覆盖率类要求。

    脚本可重复执行：门禁已存在时先删除旧条件再按当前定义重建，最后绑定到项目。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\sonar\gate.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\sonar\gate.ps1 -LineCoverageThreshold 80 -ProjectKey "com.clara:clara"
#>
[CmdletBinding()]
param(
    [string]$SonarHostUrl = "http://localhost:9100",
    [string]$ProjectKey = "com.clara:clara",
    [string]$GateName = "Clara Gate",
    [int]$LineCoverageThreshold = 80,
    [string]$Token = "",
    [string]$TokenFile = ""
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path

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

$tokenValue = Resolve-SonarToken
$headers = Get-SonarHeaders $tokenValue

# 与 Sonar way 对齐的条件集合；仅覆盖率指标由 new_coverage 换成 new_line_coverage。
$conditions = @(
    @{ metric = "new_line_coverage"; op = "LT"; error = "$LineCoverageThreshold" },
    @{ metric = "new_duplicated_lines_density"; op = "GT"; error = "3" },
    @{ metric = "new_maintainability_rating"; op = "GT"; error = "1" },
    @{ metric = "new_reliability_rating"; op = "GT"; error = "1" },
    @{ metric = "new_security_rating"; op = "GT"; error = "1" },
    @{ metric = "new_security_hotspots_reviewed"; op = "LT"; error = "100" }
)

Write-Step "查找质量门禁 $GateName"
$gates = (Invoke-RestMethod -Uri "$SonarHostUrl/api/qualitygates/list" -Headers $headers -TimeoutSec 15).qualitygates
$gate = $gates | Where-Object { $_.name -eq $GateName } | Select-Object -First 1

if ($null -eq $gate) {
    $created = Invoke-RestMethod -Method Post -Uri "$SonarHostUrl/api/qualitygates/create" `
        -Headers $headers -Body @{ name = $GateName } -TimeoutSec 15
    $gates = (Invoke-RestMethod -Uri "$SonarHostUrl/api/qualitygates/list" -Headers $headers -TimeoutSec 15).qualitygates
    $gate = $gates | Where-Object { $_.name -eq $GateName } | Select-Object -First 1
    if ($null -eq $gate) {
        throw "门禁创建失败：$GateName"
    }
    Write-Host "已创建门禁：$GateName（id=$($gate.id)）" -ForegroundColor Green
} else {
    Write-Host "门禁已存在：$GateName（id=$($gate.id)）" -ForegroundColor Yellow
}

Write-Step "重建门禁条件"
$encodedName = [uri]::EscapeDataString($GateName)
$detail = Invoke-RestMethod -Uri "$SonarHostUrl/api/qualitygates/show?name=$encodedName" -Headers $headers -TimeoutSec 15
foreach ($existing in @($detail.conditions)) {
    if ($null -eq $existing -or [string]::IsNullOrWhiteSpace($existing.id)) { continue }
    Invoke-RestMethod -Method Post -Uri "$SonarHostUrl/api/qualitygates/delete_condition" `
        -Headers $headers -Body @{ id = $existing.id } -TimeoutSec 15 | Out-Null
}

foreach ($condition in $conditions) {
    Invoke-RestMethod -Method Post -Uri "$SonarHostUrl/api/qualitygates/create_condition" `
        -Headers $headers -Body @{
            gateId = $gate.id
            metric = $condition.metric
            op = $condition.op
            error = $condition.error
        } -TimeoutSec 15 | Out-Null
    Write-Host ("  {0} {1} {2}" -f $condition.metric, $condition.op, $condition.error) -ForegroundColor Gray
}

Write-Step "绑定到项目 $ProjectKey"
Invoke-RestMethod -Method Post -Uri "$SonarHostUrl/api/qualitygates/select" `
    -Headers $headers -Body @{ gateId = $gate.id; projectKey = $ProjectKey } -TimeoutSec 15 | Out-Null
Write-Host "已绑定：$ProjectKey -> $GateName" -ForegroundColor Green

Write-Step "当前门禁状态"
$status = Invoke-RestMethod -Uri "$SonarHostUrl/api/qualitygates/project_status?projectKey=$([uri]::EscapeDataString($ProjectKey))" `
    -Headers $headers -TimeoutSec 15
$status.projectStatus.conditions |
    Select-Object metricKey, status, actualValue, errorThreshold |
    Format-Table -AutoSize

if ($status.projectStatus.status -ne "OK") {
    Write-Host "质量门禁：$($status.projectStatus.status)" -ForegroundColor Red
} else {
    Write-Host "质量门禁：OK" -ForegroundColor Green
}
