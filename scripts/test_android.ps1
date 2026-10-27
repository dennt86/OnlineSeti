param(
    # Pass custom Gradle task/options as an array, e.g.
    # -GradleArgs @(':app:lintOssDebug', '--stacktrace')
    [string[]] $GradleArgs = @(':app:testOssDebugUnitTest', ':app:testOssClientUnitTest')
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    throw 'Укажите хотя бы одну Gradle-задачу в -GradleArgs.'
}

$javaCandidates = @()
# Prefer Android Studio's bundled JBR: this project is pinned to Gradle 8.10.2,
# whose Groovy/ASM stack is not compatible with the JDK 25 found in JAVA_HOME.
if ($env:ANDROID_STUDIO_HOME) { $javaCandidates += (Join-Path $env:ANDROID_STUDIO_HOME 'jbr') }
if ($env:ProgramFiles) { $javaCandidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr') }
$javaCandidates += 'C:\Program Files\Android\Android Studio\jbr'
if ($env:JAVA_HOME) { $javaCandidates += $env:JAVA_HOME }

$javaHome = $null
foreach ($candidate in ($javaCandidates | Select-Object -Unique)) {
    $java = Join-Path $candidate 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { continue }
    $versionOutput = (& $java -version 2>&1 | Out-String)
    if ($versionOutput -match 'version "(?<version>\d+)') {
        $major = [int]$Matches.version
        if ($major -ge 17 -and $major -le 21) { $javaHome = $candidate; break }
    }
}
if (-not $javaHome) {
    throw 'Для Android Gradle Plugin нужен JDK 17+. Установите Android Studio или укажите JAVA_HOME на подходящий JDK.'
}

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$drive = $null
$createdSubst = $false
$gradleExitCode = 1
try {
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome 'bin') + [System.IO.Path]::PathSeparator + $oldPath

    $scriptHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    foreach ($letter in @('Z','Y','X','W','V','U','T','S','R','Q')) {
        $candidateScript = "${letter}:\scripts\test_android.ps1"
        if ((Test-Path -LiteralPath $candidateScript) -and
            (Get-FileHash -LiteralPath $candidateScript -Algorithm SHA256).Hash -eq $scriptHash) {
            $drive = $letter
            break
        }
    }
    if (-not $drive) {
        foreach ($letter in @('Z','Y','X','W','V','U','T','S','R','Q')) {
            if (-not (Get-PSDrive -Name $letter -ErrorAction SilentlyContinue)) {
                & subst.exe "${letter}:" $projectRoot
                if ($LASTEXITCODE -ne 0) { throw "Не удалось создать временный диск ${letter}: для Gradle." }
                $drive = $letter
                $createdSubst = $true
                break
            }
        }
    }
    if (-not $drive) { throw 'Не найден свободный диск для временного пути Gradle.' }

    Push-Location "${drive}:\"
    try {
        Write-Host "Gradle JDK: $javaHome"
        Write-Host "Project path: ${drive}:\"
        & .\gradlew.bat @GradleArgs --no-daemon
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($createdSubst -and $drive) { & subst.exe "${drive}:" /D | Out-Null }
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}

exit $gradleExitCode
