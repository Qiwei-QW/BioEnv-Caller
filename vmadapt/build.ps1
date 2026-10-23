# =====================================================================
#  虚拟机适配 (VM Adapt) - build & test script
# =====================================================================
#  Standalone module. It is NOT part of the BioEnv Caller plugin and it
#  never touches that tree; it compiles on its own classpath.
#
#  Compiles with a JDK 11 toolchain -- normally the jre/ that ships with
#  TBtools, but any JDK 11 will do (see the search below). The class-file
#  version must stay 55: a JAR built by a newer javac would be refused at
#  load time by TBtools' runtime with UnsupportedClassVersionError.
#
#  Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1
# =====================================================================

$ErrorActionPreference = 'Stop'

$Root       = $PSScriptRoot
$SrcDir     = Join-Path $Root 'src'
$BuildDir   = Join-Path $Root 'build'
$ClassesDir = Join-Path $BuildDir 'classes'

# --- locate a Java 11 toolchain -------------------------------------
# Same search as the root build.ps1: TBTOOLS_HOME (the folder that holds jre\),
# then JAVA_HOME, then the usual install locations, so TBtools installed
# somewhere other than D:\ still works. A candidate only wins if its javac
# really reports version 11 -- a stray JDK 17 must not be picked up, or every
# class would come out as major 61.
$Candidates = @()
if ($env:TBTOOLS_HOME) { $Candidates += (Join-Path $env:TBTOOLS_HOME 'jre') }
if ($env:JAVA_HOME)    { $Candidates += $env:JAVA_HOME }
$Candidates += @(
    'D:\TBtools\jre',
    'C:\TBtools\jre',
    'C:\Program Files\Eclipse Adoptium\jdk-11*',
    'C:\Program Files\Java\jdk-11*',
    'C:\Program Files\Microsoft\jdk-11*'
)
$JdkHome = $null
foreach ($c in $Candidates) {
    if (-not $c) { continue }
    foreach ($resolved in (Get-Item $c -ErrorAction SilentlyContinue)) {
        $javac = Join-Path $resolved.FullName 'bin\javac.exe'
        if (-not (Test-Path $javac)) { continue }
        if (((& $javac -version 2>&1) -join ' ') -match 'javac 11\.') {
            $JdkHome = $resolved.FullName
            break
        }
    }
    if ($JdkHome) { break }
}
if (-not $JdkHome) {
    throw ("No JDK 11 found. Set TBTOOLS_HOME to your TBtools folder (it ships jre\), " +
           "or set JAVA_HOME to a JDK 11.")
}

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
        'vmadapt.test.SshLiveTest',

        # 密码通道：不需要服务器的几条每次都跑（版本门槛、辅助程序能不能输出密码）。
        'vmadapt.test.AskpassProbe',

        # 用密码装公钥：不需要服务器的几条（失败分型、classpath 推导）每次都跑；
        # 端到端部分通过 TBVM_LIVE_* 指定服务器，没配时明确说"本次没有验证"并以 0 退出。
        'vmadapt.test.KeyInstallLiveTest'
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
