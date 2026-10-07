[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw 'Java is missing from PATH. Install a Java 21 JDK and set JAVA_HOME.'
}

$javaVersionLines = & java -version 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'java -version failed. Check PATH and JAVA_HOME.'
}
$javaVersionText = $javaVersionLines -join "`n"
if ($javaVersionText -notmatch 'version "21[.\-"]') {
    throw "This project uses Java 21. Detected: $($javaVersionLines[0])"
}
Write-Output "Java: $($javaVersionLines[0])"

if ($env:JAVA_HOME) {
    $configuredJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $configuredJava -PathType Leaf)) {
        throw 'JAVA_HOME does not point to a JDK containing bin/java.exe.'
    }
    $configuredJavaVersion = & $configuredJava -version 2>&1
    if ($LASTEXITCODE -ne 0 -or ($configuredJavaVersion -join "`n") -notmatch 'version "21[.\-"]') {
        throw 'JAVA_HOME must select Java 21; Maven Wrapper uses JAVA_HOME when set.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe') -PathType Leaf)) {
        throw 'JAVA_HOME must point to a JDK, not a JRE.'
    }
    Write-Output 'JAVA_HOME: Java 21 JDK verified.'
} elseif (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'A Java compiler is missing. Install a Java 21 JDK and set JAVA_HOME.'
}

foreach ($relativeFile in @('mvnw.cmd', '.mvn/wrapper/maven-wrapper.jar', '.mvn/wrapper/maven-wrapper.properties')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $relativeFile) -PathType Leaf)) {
        throw "Required Maven Wrapper file missing: $relativeFile"
    }
}
Write-Output 'Maven Wrapper: required files present; first build may need network access.'

foreach ($optionalTool in @('git', 'codex', 'claude')) {
    if (Get-Command $optionalTool -ErrorAction SilentlyContinue) {
        Write-Output "Optional tool available: $optionalTool"
    } else {
        Write-Output "Optional tool not on PATH: $optionalTool"
    }
}
Write-Output 'Prerequisites passed. Next: .\mvnw.cmd -B -ntp verify from the project root.'
