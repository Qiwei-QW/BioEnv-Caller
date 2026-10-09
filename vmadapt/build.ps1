# =====================================================================
#  虚拟机适配 (VM Adapt) - build & test script
# =====================================================================
#  Standalone module. It is NOT part of the BioEnv Caller plugin and it
#  never touches that tree; it compiles on its own classpath.
#
#  Deliberately compiles with the JDK bundled with TBtools (D:\TBtools\jre,
#  javac 11.0.6) so the class-file version is 55 by construction. A JAR
#  built by a newer javac (this machine also has JDK 26) would be refused
#  at load time by TBtools' runtime with UnsupportedClassVersionError.
#
#  Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1
# =====================================================================

$ErrorActionPreference = 'Stop'

$Root       = $PSScriptRoot
$SrcDir     = Join-Path $Root 'src'
$BuildDir   = Join-Path $Root 'build'
$ClassesDir = Join-Path $BuildDir 'classes'

# --- locate the JDK 11 toolchain -------------------------------------
$Candidates = @(
    'D:\TBtools\jre',
    'C:\Program Files\Eclipse Adoptium\jdk-11*',
    'C:\Program Files\Java\jdk-11*'
)
$JdkHome = $null
foreach ($c in $Candidates) {
    $resolved = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($resolved) {
        $javac = Join-Path $resolved.FullName 'bin\javac.exe'
        if (Test-Path $javac) { $JdkHome = $resolved.FullName; break }
    }
}
if (-not $JdkHome) { throw "No JDK 11 found. TBtools ships one at D:\TBtools\jre." }

$Javac = Join-Path $JdkHome 'bin\javac.exe'
$Java  = Join-Path $JdkHome 'bin\java.exe'
Write-Host "Using JDK: $JdkHome"
& $Javac -version

# --- clean -----------------------------------------------------------
if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null

# --- compile ---------------------------------------------------------
$sources = Get-ChildItem $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source file(s)..."
& $Javac -encoding UTF-8 -Xlint:-options -d $ClassesDir $sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# --- verify class-file version ---------------------------------------
$bad = @()
Get-ChildItem $ClassesDir -Recurse -Filter *.class | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    if ($bytes[7] -ne 55) { $bad += "$($_.Name) major=$($bytes[7])" }
}
if ($bad.Count -gt 0) {
    throw ("Class-file version must be 55 (Java 11) but found: " + ($bad -join ', '))
}
Write-Host "PASS  all class files are Java 11 (major 55)"

# --- tests -----------------------------------------------------------
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$failed = @()
try {
    foreach ($t in @(
        'vmadapt.test.ShellQuoteTest',
        'vmadapt.test.SshTargetTest',
        'vmadapt.test.ConnectionStoreTest',
        'vmadapt.test.SshCommandTest',
        'vmadapt.test.RemotePathTest',
        'vmadapt.test.UploadPlanTest',
        'vmadapt.test.ProbeScriptTest',
        'vmadapt.test.KeygenCommandTest',
        'vmadapt.test.CommandTimeoutTest',
        'vmadapt.test.ReachabilityTest',
        'vmadapt.test.RemoteWorkdirTest',

        # 连接面板的分组布局与标签顺序。
        #
        # 分成三组之后，标签曾经整体错位一格（「密钥路径」那一行显示的是「15」）：
        # rowLabels 按 addRow 的调用顺序排，而文案数组仍按旧顺序。错位不会报错，
        # 只是每一行都写着隔壁那一行的名字，所以要用「第 i 个标签配第 i 个控件」来验。
        'vmadapt.test.ConnectionLayoutTest',

        # 可选的联网冒烟测试：没配 VMADAPT_LIVE_* 时打印 SKIP 并以 0 退出。
        # 它的类注释写着"会出现在 build.ps1 的常规列表里"，但两个清单都漏了它。
        'vmadapt.test.SshLiveTest'
    )) {
        Write-Host ""
        Write-Host "=== $t ==="
        Push-Location $Root
        try {
            & $Java -cp $ClassesDir $t
            if ($LASTEXITCODE -ne 0) { $failed += $t }
        } finally { Pop-Location }
    }
} finally {
    Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

if ($failed.Count -gt 0) { throw ("Tests failed: " + ($failed -join ', ')) }

Write-Host ""
Write-Host "============================================="
Write-Host " All tests passed."
Write-Host " Classes: $ClassesDir"
Write-Host "============================================="
