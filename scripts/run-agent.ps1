param([string]$Workspace = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($Workspace)) { $Workspace = $PWD.Path }
if (-not [IO.Path]::IsPathRooted($Workspace)) { $Workspace = Join-Path $PWD.Path $Workspace }
$Workspace = [IO.Path]::GetFullPath($Workspace)
if (-not (Test-Path -LiteralPath $Workspace -PathType Container)) { throw 'Workspace must be an existing project directory.' }
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { throw 'DEEPSEEK_API_KEY is missing. No Mock fallback.' }
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repository = Join-Path $project '.m2\repository'
Write-Host ('DAILY | DeepSeek / deepseek-flash | Workspace: ' + $Workspace)
Write-Host 'Real API may incur charges. No API request quota. Maven still requires exact R1 approval.'
Write-Host 'Do not run the old CLI and Daily TUI on the same workspace simultaneously; Daily instances use a workspace lock.'
& mvn -o -f (Join-Path $project 'pom.xml') "-Dmaven.repo.local=$repository" '-DskipTests' compile
if ($LASTEXITCODE -ne 0) { throw 'Offline build failed; no launch.' }
$file = Join-Path $project ('target\daily-classpath-' + [guid]::NewGuid().ToString('N') + '.txt')
& mvn -o -f (Join-Path $project 'pom.xml') "-Dmaven.repo.local=$repository" 'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath' '-DincludeScope=runtime' "-Dmdep.outputFile=$file"
if ($LASTEXITCODE -ne 0) { throw 'Offline classpath failed; no launch.' }
$classpath = (Join-Path $project 'target\classes') + ';' + [IO.File]::ReadAllText($file).Trim()
& java '-Dfile.encoding=UTF-8' '-cp' $classpath 'com.agent.tui.prototype.DailyTuiMain' '--workspace' $Workspace
if ($LASTEXITCODE -ne 0) { throw 'Daily TUI stopped with error; retain data; no automatic retry.' }
