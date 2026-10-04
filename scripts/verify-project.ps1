param(
    [switch] $CheckDemos
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    Write-Host 'Java:'
    java -version
    if ($LASTEXITCODE -ne 0) { throw 'java -version failed.' }

    Write-Host 'Maven:'
    mvn -version
    if ($LASTEXITCODE -ne 0) { throw 'mvn -version failed.' }

    Write-Host 'Running tests in offline mode (requires dependencies already in the local Maven cache)...'
    mvn "-Dmaven.repo.local=$(Join-Path $repoRoot '.m2/repository')" -o test
    if ($LASTEXITCODE -ne 0) { throw 'Offline Maven test suite failed.' }

    if ($CheckDemos) {
        $requiredFiles = @(
            'demo/README.md',
            'demo/standalone-java/Calculator.java',
            'demo/maven-java/pom.xml',
            'demo/maven-java/src/main/java/demo/Greeter.java',
            'demo/python/calculator.py'
        )
        foreach ($relativePath in $requiredFiles) {
            if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath) -PathType Leaf)) {
                throw "Required demo fixture is missing: $relativePath"
            }
        }
        Write-Host 'Demo fixture check passed.'
    }
}
finally {
    Pop-Location
}
