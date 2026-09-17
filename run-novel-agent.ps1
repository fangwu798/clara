param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AgentArguments
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$projectRoot = $PSScriptRoot
$localRepository = Join-Path $projectRoot ".m2\repository"
$jar = Join-Path $projectRoot "clara-novel\target\clara-novel-0.1.0-SNAPSHOT-agent.jar"

$helpRequested = ($AgentArguments -contains "--help") -or ($AgentArguments -contains "-h")
$interactiveRequested = ($AgentArguments.Count -eq 0) -or ($AgentArguments -contains "--interactive")
if (-not $helpRequested -and -not $env:CLARA_DEEPSEEK_API_KEY) {
    throw "Please set CLARA_DEEPSEEK_API_KEY first."
}
if (-not $helpRequested -and $interactiveRequested -and -not $env:CLARA_VOLCENGINE_API_KEY) {
    throw "Interactive mode also requires CLARA_VOLCENGINE_API_KEY for the Volcengine world-view editor."
}

Push-Location $projectRoot
try {
    & mvn "-Dmaven.repo.local=$localRepository" -pl clara-novel -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
    & java "-Dfile.encoding=UTF-8" -jar $jar @AgentArguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
