# Compiles and runs the `shared` module's test suites without Gradle.
#
# Why this exists: Gradle needs a loopback socket to talk to its own daemon,
# which some restricted environments block. This script drives kotlinc
# directly so the shared logic — protocol, crypto, guardrail, mesh — can
# still be verified. It is a fallback, not a replacement:
# `./gradlew :shared:testDebugUnitTest` remains the source of truth.
#
# Usage:  pwsh -File tools/verify-shared.ps1
# First run downloads ~90 MB of compiler and jars into $HOME\tools.

$ErrorActionPreference = "Stop"

$KotlinVersion      = "2.0.21"
$CoroutinesVersion  = "1.9.0"
$SerializationVer   = "1.7.3"

$Root      = Split-Path -Parent $PSScriptRoot
$ToolsDir  = Join-Path $HOME "tools"
$KotlincDir= Join-Path $ToolsDir "kotlinc"
$JarsDir   = Join-Path $ToolsDir "kjars"
$Work      = Join-Path $env:TEMP "proximity-verify"

function Get-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $candidate = Get-ChildItem $ToolsDir -Filter "jdk-*" -Directory -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw "No JDK found. Set JAVA_HOME or install a JDK 17+ under $ToolsDir."
}

function Get-AndroidJar {
    $base = Join-Path $HOME "AppData\Local\Android\Sdk\platforms"
    if (-not (Test-Path $base)) { throw "Android SDK not found at $base" }
    $platform = Get-ChildItem $base -Directory | Sort-Object Name -Descending | Select-Object -First 1
    return (Join-Path $platform.FullName "android.jar")
}

function Ensure-Jar($name, $url) {
    $path = Join-Path $JarsDir $name
    if (-not (Test-Path $path)) {
        Write-Host "  downloading $name"
        Invoke-WebRequest -Uri $url -OutFile $path
    }
    return $path
}

New-Item -ItemType Directory -Force -Path $ToolsDir, $JarsDir | Out-Null

if (-not (Test-Path "$KotlincDir\lib\kotlin-compiler.jar")) {
    Write-Host "Downloading Kotlin $KotlinVersion compiler..."
    $zip = Join-Path $ToolsDir "kotlinc.zip"
    Invoke-WebRequest -OutFile $zip `
        -Uri "https://github.com/JetBrains/kotlin/releases/download/v$KotlinVersion/kotlin-compiler-$KotlinVersion.zip"
    Expand-Archive -Path $zip -DestinationPath $ToolsDir -Force
    Remove-Item $zip
}

$M = "https://repo1.maven.org/maven2"
$coroutines     = Ensure-Jar "coroutines-core-jvm.jar"   "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$CoroutinesVersion/kotlinx-coroutines-core-jvm-$CoroutinesVersion.jar"
$coroutinesTest = Ensure-Jar "coroutines-test-jvm.jar"   "$M/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/$CoroutinesVersion/kotlinx-coroutines-test-jvm-$CoroutinesVersion.jar"
$serCore        = Ensure-Jar "serialization-core-jvm.jar" "$M/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/$SerializationVer/kotlinx-serialization-core-jvm-$SerializationVer.jar"
$serJson        = Ensure-Jar "serialization-json-jvm.jar" "$M/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/$SerializationVer/kotlinx-serialization-json-jvm-$SerializationVer.jar"
$junit          = Ensure-Jar "junit.jar"                  "$M/junit/junit/4.13.2/junit-4.13.2.jar"
$hamcrest       = Ensure-Jar "hamcrest.jar"               "$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

$java       = Join-Path (Get-JavaHome) "bin\java.exe"
$lib        = Join-Path $KotlincDir "lib"
$androidJar = Get-AndroidJar

# Stage sources. `expect fun currentTimeMillis` cannot be compiled outside a
# multiplatform build, so it is swapped for a JVM implementation here. This
# is the only source difference from the real build.
Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Work\src", "$Work\tsrc" | Out-Null
Copy-Item "$Root\shared\src\commonMain\kotlin\*" "$Work\src" -Recurse
Copy-Item "$Root\shared\src\androidMain\kotlin\*" "$Work\src" -Recurse -Force
Remove-Item "$Work\src\os\proximity\shared\util\Time.android.kt" -ErrorAction SilentlyContinue
Set-Content -Path "$Work\src\os\proximity\shared\util\Time.kt" -Encoding utf8 -Value @"
package os.proximity.shared.util

fun currentTimeMillis(): Long = System.currentTimeMillis()
"@
Copy-Item "$Root\shared\src\commonTest\kotlin\*" "$Work\tsrc" -Recurse
Copy-Item "$Root\shared\src\androidUnitTest\kotlin\*" "$Work\tsrc" -Recurse -Force

$mainCp = "$coroutines;$serCore;$serJson;$lib\kotlin-stdlib.jar;$androidJar"
$plugin = "-Xplugin=$lib\kotlinx-serialization-compiler-plugin.jar"

Write-Host "Compiling shared sources..."
& $java -cp "$lib\kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -classpath $mainCp $plugin -nowarn -d "$Work\main" "$Work\src"
if ($LASTEXITCODE -ne 0) { throw "shared sources failed to compile" }

$testCp = "$mainCp;$coroutinesTest;$junit;$hamcrest;$lib\kotlin-test.jar;$lib\kotlin-test-junit.jar;$Work\main"

Write-Host "Compiling tests..."
& $java -cp "$lib\kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -classpath $testCp "-Xfriend-paths=$Work\main" -nowarn -d "$Work\test" "$Work\tsrc"
if ($LASTEXITCODE -ne 0) { throw "tests failed to compile" }

# Discover test classes rather than hard-coding them, so new suites are
# picked up without editing this script.
$testClasses = Get-ChildItem "$Work\test" -Recurse -Filter "*Test.class" |
    Where-Object { $_.Name -notmatch '\$' } |
    ForEach-Object {
        $_.FullName.Substring("$Work\test\".Length) -replace '\', '.' -replace '\.class$', ''
    }

Write-Host "Running $($testClasses.Count) test classes..."
$runCp = "$coroutines;$coroutinesTest;$serCore;$serJson;$junit;$hamcrest;$lib\kotlin-stdlib.jar;$lib\kotlin-test.jar;$lib\kotlin-test-junit.jar;$Work\main;$Work\test"
& $java -cp $runCp org.junit.runner.JUnitCore @testClasses
exit $LASTEXITCODE
