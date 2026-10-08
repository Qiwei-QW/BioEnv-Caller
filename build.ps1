# =====================================================================
#  BioEnv Caller - build, test & package
# =====================================================================
#  Builds the plugin from the repository root:
#
#      <root>\build.ps1              this script
#      <root>\aidev-project\src\     plugin sources (bioenv.*, Plugin.*)
#      <root>\vmadapt\src\           SSH transport module (vmadapt.*)
#      <root>\build\                 throwaway output, git-ignored
#      <root>\BioEnvCaller.plugin    the installable package
#
#  The vmadapt module is compiled first and put on the classpath, because
#  the plugin reuses vmadapt.core.* for the SSH transport instead of
#  vendoring a second implementation. Its classes are also packaged into
#  the output JAR so the plugin stays self-contained.
#
#  Compiles with TBtools' bundled JDK 11 (D:\TBtools\jre, javac 11.0.6) so
#  the class-file version is 55 by construction. A JAR built by the JDK 26
#  also installed on this machine would be refused at load time with
#  UnsupportedClassVersionError.
#
#  Compiles with TBtools' bundled JDK 11 (D:\TBtools\jre, javac 11.0.6) so
#  the class-file version is 55 by construction. A JAR built by the JDK 26
#  also installed on this machine would be refused at load time with
#  UnsupportedClassVersionError.
#
#  Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1
# =====================================================================

$ErrorActionPreference = 'Stop'

$Root        = $PSScriptRoot
$SrcDir      = Join-Path $Root 'aidev-project\src'
$BuildDir    = Join-Path $Root 'build'
$ClassesDir  = Join-Path $BuildDir 'classes'
$DistDir     = Join-Path $BuildDir 'dist'

# The vmadapt SSH module lives beside the plugin sources, at <root>\vmadapt.
$VmadaptSrc  = Join-Path $Root 'vmadapt\src'

# --- locate a Java 11 toolchain -------------------------------------
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
$Jar   = Join-Path $JdkHome 'bin\jar.exe'
$Java  = Join-Path $JdkHome 'bin\java.exe'

Write-Host "Using JDK: $JdkHome"
& $Javac -version

if (-not (Test-Path $VmadaptSrc)) {
    throw "vmadapt module not found at $VmadaptSrc - the merged plugin depends on it."
}

# --- clean -----------------------------------------------------------
if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null
New-Item -ItemType Directory -Path $DistDir    -Force | Out-Null

# --- compile vmadapt first, so the merged plugin links against it ----
$VmadaptClasses = Join-Path $BuildDir 'vmadapt-classes'
if (Test-Path $VmadaptClasses) { Remove-Item -Recurse -Force $VmadaptClasses }
New-Item -ItemType Directory -Path $VmadaptClasses -Force | Out-Null

$vmSources = Get-ChildItem $VmadaptSrc -Recurse -Filter *.java |
    Where-Object { $_.FullName -notlike '*\test\*' } |
    ForEach-Object { $_.FullName }
Write-Host "Compiling $($vmSources.Count) vmadapt source file(s)..."
& $Javac -encoding UTF-8 -Xlint:-options -d $VmadaptClasses $vmSources
if ($LASTEXITCODE -ne 0) { throw "vmadapt compilation failed." }

# --- compile the merged plugin ---------------------------------------
$tbJar = $null
foreach ($candidate in @('D:\TBtools\TBtools_JRE1.6.jar',
                         (Join-Path $env:USERPROFILE '.TBtools\TBtools_JRE1.6.jar'))) {
    if (Test-Path $candidate) { $tbJar = $candidate; break }
}

$sources = Get-ChildItem $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$stub = Join-Path $SrcDir 'Plugin\PluginInterface.java'
$cp = $VmadaptClasses
if ($tbJar) { $cp = "$VmadaptClasses;$tbJar" }

if ($tbJar) {
    Write-Host "Compiling $($sources.Count) source file(s) against $tbJar + vmadapt"
    $sources = $sources | Where-Object { $_ -ne $stub }
    & $Javac -encoding UTF-8 -Xlint:-options -cp $cp -d $ClassesDir $sources
} else {
    Write-Host "Compiling $($sources.Count) source file(s) against the local stub + vmadapt"
    & $Javac -encoding UTF-8 -Xlint:-options -cp $cp -d $ClassesDir $sources
}
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# --- verify class-file version ---------------------------------------
$bad = @()
Get-ChildItem $ClassesDir -Recurse -Filter *.class | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    $major = $bytes[7]
    if ($major -ne 55) { $bad += "$($_.Name) major=$major" }
}
if ($bad.Count -gt 0) {
    throw ("Class-file version must be 55 (Java 11) but found: " + ($bad -join ', '))
}
Write-Host "PASS  all class files are Java 11 (major 55)"

# --- stage non-Java resources -----------------------------------------
# javac only compiles .java, so anything else in the source tree (the Tux PNG
# the working-directory button uses) has to be copied into the classes
# directory by hand, or it never reaches the JAR and the icon silently
# disappears at runtime.
Get-ChildItem $SrcDir -Recurse -File |
    Where-Object { $_.Extension -ne '.java' } |
    ForEach-Object {
        $rel = $_.FullName.Substring($SrcDir.Length).TrimStart('\')
        $to = Join-Path $ClassesDir $rel
        New-Item -ItemType Directory -Path (Split-Path $to -Parent) -Force | Out-Null
        Copy-Item $_.FullName $to -Force
    }
Write-Host "PASS  source resources staged into classes"

# --- regression tests -------------------------------------------------
# The suite that shipped with BioEnvCaller. If any of these breaks, the
# merge changed behaviour that used to work - which is the single biggest
# risk of this whole exercise.
$testCp = "$ClassesDir;$VmadaptClasses"
if ($tbJar) { $testCp = "$testCp;$tbJar" }

$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$failed = @()
$passed = @()
$skipped = @()
try {
    $regression = @(
        'bioenv.test.SelfTest',
        'bioenv.test.CommandLineTest',
        'bioenv.test.ConcisePreviewTest',
        'bioenv.test.DiscoveryCacheTest',
        'bioenv.test.ProbeFailureTest',
        'bioenv.test.UsageLineTest',
        'bioenv.test.ChoiceDetectionTest',
        'bioenv.test.NoiseFilterTest',
        'bioenv.test.TypeInferenceTest',
        'bioenv.test.CorrectionStoreTest',
        'bioenv.test.EmptyStateTest',
        'bioenv.test.LayoutTest',
        'bioenv.test.InferenceFixTest',
        'bioenv.test.AliasGroupTest',
        'bioenv.test.HelpFormatTest',
        'bioenv.test.TypeColumnTest',
        'bioenv.test.FalseParameterTest',
        'bioenv.test.KindChooserTest',
        'bioenv.test.ExampleSectionTest',
        'bioenv.test.ValuePositionTest',
        'bioenv.test.EvidenceTest',
        'bioenv.test.DividerRangeTest',
        'bioenv.test.UiSmokeTest',
        'bioenv.test.SshNoProfileMessageTest',
        'bioenv.test.ConnectionModeRememberedTest',
        'bioenv.test.MultiChannelCacheTest',
        'bioenv.test.ProbeRouteTest',
        'bioenv.test.IncrementalScanTest',
        'bioenv.test.TargetMemoryTest',
        'bioenv.test.AutoLoadTest',
        'bioenv.test.WorkdirUiTest',
        'bioenv.test.ReinstallClassLossTest',
        'bioenv.test.HotPathClassLoadTest',

        # --- newly registered -------------------------------------------
        # These already lived in bioenv\test\ and already passed, but were
        # never listed here, so nothing ran them. They were found by running
        # every test class in the package and keeping the ones that pass
        # repeatably (3 consecutive runs each, to rule out flakiness).
        # Registering them costs nothing and takes the gate from 33 to 62.
        #
        # Two entries below need something the machine may not have, so each
        # reports a skip and exits 0 rather than failing:
        #   - OptionsWindowTest opens real windows and asserts that they are
        #     visible and that closing disposes of them. Headless it cannot
        #     observe any of that, so it prints SKIP and returns.
        #   - AutoCopyInputsTest finishes with a real WSL round trip: a work
        #     directory, a copy in, and the content read back. Where wsl.exe
        #     answers, it runs and passes (11/11 verified with WSL present);
        #     where it does not, that last step prints SKIP while the ten
        #     checks before it still run and still assert.
        # Both skip loudly - a green run never claims the step was exercised.
        #
        # A skip is a real result here, not a way to hide a failure: each was
        # also observed doing its work on a machine that has what it needs.
        #
        # Survey/debug tools are no longer in this list because they are no
        # longer in test\: they moved to bioenv\tools, which is compiled but
        # never packaged and never gated.
        #
        # Known flake: HotPathClassLoadTest asserts that setStatus loads no new
        # classes after warm-up, by comparing ClassLoadingMXBean counts. That
        # counter is JVM-wide, so a Swing class lazily loaded by another thread
        # between the two reads can bump it and fail the check. Observed once
        # in ~10 full-suite runs and never in 12 isolated runs. If it fails,
        # re-run before believing it; the flake is in the measurement, not in
        # the hot path it guards.
        'bioenv.test.AnsiTextTest',
        'bioenv.test.BuilderFixesTest',
        'bioenv.test.CacheDistroTest',
        'bioenv.test.ChoiceHintTest',
        'bioenv.test.CustomOptionTest',
        'bioenv.test.CustomSwitchTest',
        'bioenv.test.DistroFilterTest',
        'bioenv.test.EditorEndToEndTest',
        'bioenv.test.EditorFixesTest',
        'bioenv.test.EditorSyncTest',
        'bioenv.test.KindCatalogueTest',
        'bioenv.test.ParameterDragTest',
        'bioenv.test.ParameterPurgeTest',
        'bioenv.test.ParameterRenameTest',
        'bioenv.test.PositionalParamTest',
        'bioenv.test.PrefixedListTest',
        'bioenv.test.RebuildKeepsStateTest',
        'bioenv.test.StopRunTest',
        'bioenv.test.ToolVsDependencyTest',
        'bioenv.test.TypedCommandTest',
        'bioenv.test.TypedValueReachesCommandTest',
        'bioenv.test.WslWorkdirTest',

        # --- repaired: were headless-hostile or matched English UI text ------
        # Each of these wrote its own JFrame and called setVisible(true), which
        # needs a display, and identified components by English labels, which
        # only matches when English has been forced in by hand - the default
        # language is Chinese. Repaired two ways:
        #
        #   * laid out by hand and headless, the way LayoutTest already did it;
        #     where a divider is positioned from a componentResized listener,
        #     that event is delivered directly, since a frame used to do it.
        #   * labels looked up through I18n, so the lookup does not depend on
        #     which language is active.
        #
        # MainLayoutTest also needed a tool opened before it asserted on the
        # form/output column: the panel shows an empty state until then, where
        # the only split is the toolbar/tree one.
        'bioenv.test.MainLayoutTest',
        'bioenv.test.OptionAccessTest',
        'bioenv.test.ParameterEditorTest',
        'bioenv.test.SubcommandLayoutTest',
        'bioenv.test.SubcommandNavTest',
        'bioenv.test.SubcommandReloadTest',
        'bioenv.test.UsageLineDescriptionTest',

        # --- need something the machine may not have ------------------------
        # Both report a skip and exit 0 when the thing they need is absent,
        # instead of failing. They are still worth gating: on a machine that
        # has it, they do the real work.
        'bioenv.test.AutoCopyInputsTest',
        'bioenv.test.OptionsWindowTest',

        # --- regression for a reported bug -----------------------------------
        # A scan lists tools without reading any help, and saveCache() used to
        # write that list with every schema null - replacing the entry that held
        # the parameter tables. On the next start no tool had one, so a tool's
        # parameters appeared only after the editor was opened and confirmed.
        'bioenv.test.CacheKeepsSchemasTest',

        # --- removed kinds must not break an existing store -------------------
        # Three TBtools kinds were offered and did nothing their simpler cousins
        # did not: PasteAsInFilePath, TextAsOutFilePath, FilePathListInFile. A
        # correction file written before their removal can name them, so this
        # checks the stale entry is dropped while the file's other entries and
        # the parameter itself survive.
        'bioenv.test.RemovedKindStillLoadsTest'
    )

    foreach ($t in $regression) {
        Push-Location $Root
        try {
            # Run via cmd.exe and capture a merged stream. Two pitfalls avoided:
            # allocating a console for each test, and - the reason this is not a
            # plain `& $Java ... 2>&1` - PowerShell turning the JVM's
            # "Picked up JAVA_TOOL_OPTIONS" stderr banner into a
            # NativeCommandError record, which under $ErrorActionPreference='Stop'
            # aborts the whole build even though the test itself passed.
            # -Dbioenv.theme.readonly=1: 界面风格既不由用户存的那份决定，测试也不会
            # 把它写回去。回归测试盯着的是默认的"经典"，不该因为谁点过"个性"就变。
            $line = '"{0}" -Dbioenv.theme.readonly=1 -cp "{1}" {2} 2>&1' -f $Java, $testCp, $t
            $out = cmd.exe /c $line
            $code = $LASTEXITCODE
            $text = ($out | Out-String)

            if ($code -ne 0) {
                $failed += $t
                Write-Host "FAIL  $t (exit $code)"
                ($text -split "`r?`n" | Where-Object { $_.Trim() } |
                    Select-Object -Last 6) | ForEach-Object { Write-Host "      $_" }
            } else {
                $passed += $t
                # Surface the harness' own check count, so a test that silently
                # stops asserting shows up rather than reading as "PASS".
                $countLine = ($text -split "`r?`n" |
                    Where-Object { $_ -match 'PASS\s+\d+\s+check' } |
                    Select-Object -Last 1)
                # A test that skipped part of itself must say so here. Counting it
                # as a plain PASS would let a green build imply that the skipped
                # step - a real WSL copy, a window actually being shown - ran.
                #
                # Matched as the word SKIP at the start of a line followed by two
                # or more spaces, which is the shape the harness prints. Matching
                # any occurrence of "skip" also catches assertion text such as
                # "brackets meaning other options are skipped", and would report a
                # skip that never happened.
                $skipLine = ($text -split "`r?`n" |
                    Where-Object { $_ -match '^\s*SKIP\s{2,}' } |
                    Select-Object -First 1)
                if ($skipLine) {
                    $skipped += $t
                    Write-Host "PASS  $t  (SKIPPED: $($skipLine.Trim()))"
                } elseif ($countLine) {
                    Write-Host "PASS  $t  ($($countLine.Trim()))"
                } else {
                    Write-Host "PASS  $t"
                }
            }
        } finally {
            Pop-Location
        }
    }
} finally {
    Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Regression: $($passed.Count) passed, $($failed.Count) failed"
if ($skipped.Count -gt 0) {
    Write-Host ""
    Write-Host "Ran with a skipped step (the machine lacks what it needs):"
    foreach ($s in $skipped) { Write-Host "  - $s" }
}
if ($failed.Count -gt 0) {
    throw ("Regression tests failed: " + ($failed -join ', '))
}

# --- package ----------------------------------------------------------
# Layout mirrors the official FASTA Merge.plugin:
#   <Name>/<Name>.jar + MenuConfig.ini + AiDevBuild.txt + aidev-project/
$stage = Join-Path $DistDir 'BioEnv Caller'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

$jarStage = Join-Path $BuildDir 'jarstage'
if (Test-Path $jarStage) { Remove-Item -Recurse -Force $jarStage }
New-Item -ItemType Directory -Path $jarStage -Force | Out-Null

# Stage every product package under bioenv, plus Plugin - discovered, not listed.
#
# This used to be a hardcoded array ('bioenv\core', 'bioenv\ui', 'Plugin'). When
# the i18n package was added it was compiled but never staged, so the JAR shipped
# without bioenv.i18n and died at runtime with NoClassDefFoundError while the
# build reported success. Discovering the packages means a new one cannot be
# forgotten; the packages excluded are the ones that must not ship: bioenv\test
# and bioenv\tools.
#
# bioenv\tools holds the survey/debug mains that used to sit in bioenv\test.
# They are developer tools, so they are compiled (a survey that no longer
# compiles is worth knowing about) but never packaged.
$shipExcluded = @('test', 'tools')
$productPkgs = Get-ChildItem (Join-Path $ClassesDir 'bioenv') -Directory |
    Where-Object { $shipExcluded -notcontains $_.Name } |
    ForEach-Object { Join-Path 'bioenv' $_.Name }
$productPkgs = @($productPkgs) + @('Plugin')

foreach ($pkg in $productPkgs) {
    $from = Join-Path $ClassesDir $pkg
    if (-not (Test-Path $from)) { continue }
    $parent = Split-Path $pkg -Parent
    $leaf = Split-Path $pkg -Leaf
    $destParent = if ($parent) { Join-Path $jarStage $parent } else { $jarStage }
    New-Item -ItemType Directory -Path $destParent -Force | Out-Null
    Copy-Item -Recurse $from (Join-Path $destParent $leaf)
}
foreach ($excluded in $shipExcluded) {
    if (Test-Path (Join-Path $jarStage "bioenv\$excluded")) {
        throw "$excluded classes leaked into the packaged JAR"
    }
}

# Gate: every compiled product class must be staged. A package that compiles but
# is not staged produces a JAR that fails at runtime, and the build would still
# report success - exactly how bioenv.i18n was lost once.
$compiled = Get-ChildItem $ClassesDir -Recurse -Filter *.class |
    Where-Object { $_.FullName -notlike '*\bioenv\test\*' -and
                   $_.FullName -notlike '*\bioenv\tools\*' } |
    ForEach-Object { $_.FullName.Substring($ClassesDir.Length).TrimStart('\') }
$missing = @($compiled | Where-Object { -not (Test-Path (Join-Path $jarStage $_)) })
if ($missing.Count -gt 0) {
    throw ("staged JAR is missing $($missing.Count) compiled class(es), e.g. " +
           ($missing | Select-Object -First 5) -join ', ')
}
Write-Host "PASS  all $($compiled.Count) product classes staged into the JAR"

# Gate: every anonymous class MainPanel references must be in the package.
#
# A user hit NoClassDefFoundError: bioenv/ui/MainPanel$35. Checking it item by
# item showed the package itself was complete and that the error came from
# reinstalling the plugin while TBtools still held the previous instance - that
# instance can only load its remaining classes from the jar that was just
# overwritten. This gate is still worth keeping: anonymous-class numbers shift
# with the source, and a genuinely dropped class would only show up on a user's
# machine, as a stack trace naming a number and nothing else.
#
# NOTE: keep these comments in ASCII. This script is launched with Windows
# PowerShell 5.1, which decodes a BOM-less file as ANSI; UTF-8 Chinese then turns
# into mojibake that can swallow the FOLLOWING line - which is how this very
# assignment silently vanished once.
$mpRel = 'bioenv\ui\MainPanel.class'
if (Test-Path (Join-Path $jarStage $mpRel)) {
    $mpTxt = [System.Text.Encoding]::ASCII.GetString(
        [System.IO.File]::ReadAllBytes((Join-Path $jarStage $mpRel)))
    $mpNums = [regex]::Matches($mpTxt, 'MainPanel\$(\d+)') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $mpMiss = @($mpNums | Where-Object {
        -not (Test-Path (Join-Path $jarStage ("bioenv\ui\MainPanel`$$_.class"))) })
    if ($mpMiss.Count -gt 0) {
        throw ("MainPanel references anonymous classes that are not packaged: " +
               ($mpMiss -join ', '))
    }
    Write-Host "PASS  MainPanel's $($mpNums.Count) anonymous classes are all packaged"
}

# The vmadapt SSH transport goes into the same JAR, otherwise the merged
# plugin would carry a compile-time dependency it cannot satisfy at
# runtime. Self-containment is the point of the merge.
#
# javac already emitted the package root (vmadapt-classes\vmadapt\core\...),
# so copy its *contents* rather than the directory itself - copying the
# directory would produce a doubled vmadapt\vmadapt\ path.
$vmStage = Join-Path $jarStage 'vmadapt'
New-Item -ItemType Directory -Path $vmStage -Force | Out-Null
Copy-Item (Join-Path $VmadaptClasses 'vmadapt\*') $vmStage -Recurse -Force
if (-not (Test-Path (Join-Path $vmStage 'core\SshTransport.class'))) {
    throw "vmadapt transport classes were not packaged"
}

$stubClass = Join-Path $jarStage 'Plugin\PluginInterface.class'
if (Test-Path $stubClass) { Remove-Item -Force $stubClass }

$jarPath = Join-Path $stage 'BioEnvCaller.jar'
& $Jar cf $jarPath -C $jarStage .
if ($LASTEXITCODE -ne 0) { throw "jar creation failed." }

Copy-Item (Join-Path $Root 'aidev-project\MenuConfig.ini') $stage
Copy-Item (Join-Path $Root 'aidev-project\project.json')   $stage

$aidevStage = Join-Path $stage 'aidev-project'
New-Item -ItemType Directory -Path $aidevStage -Force | Out-Null
Copy-Item (Join-Path $Root 'aidev-project\MenuConfig.ini') $aidevStage
Copy-Item (Join-Path $Root 'aidev-project\project.json')   $aidevStage
Copy-Item (Join-Path $Root 'aidev-project\src')            $aidevStage -Recurse

$buildNumber = 1
$projectJson = Get-Content (Join-Path $Root 'aidev-project\project.json') -Raw | ConvertFrom-Json
if ($projectJson.buildNumber) { $buildNumber = [int]$projectJson.buildNumber }
@"
build=$buildNumber
pluginName=BioEnv Caller
"@ | Set-Content -Path (Join-Path $stage 'AiDevBuild.txt') -Encoding ASCII

# Named BioEnvCaller.plugin, not BioEnvCaller-merged.plugin: this is the package
# that ships, and the "-merged" suffix only described how it was produced. TBtools
# shows the plugin name from project.json, so the file name need not disambiguate
# a build flavour the user never chose.
$pluginPath = Join-Path $Root 'BioEnvCaller.plugin'
if (Test-Path $pluginPath) { Remove-Item -Force $pluginPath }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $DistDir, $pluginPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)

# Remove the superseded name so an older copy cannot be installed by mistake.
$legacyPath = Join-Path $Root 'BioEnvCaller-merged.plugin'
if (Test-Path $legacyPath) {
    Remove-Item -Force $legacyPath
    Write-Host "Removed superseded BioEnvCaller-merged.plugin"
}

Write-Host ""
Write-Host "============================================="
Write-Host " Built: $pluginPath"
Write-Host " Size : $((Get-Item $pluginPath).Length) bytes"
Write-Host "============================================="
Write-Host ""
Write-Host "  This package carries both the WSL and the SSH transport."
Write-Host "  The original BioEnvCaller\ tree was not modified."
