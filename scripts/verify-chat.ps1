param(
    [ValidateSet('Full', 'Compile', 'Core', 'Baseline')]
    [string]$Scope = 'Full'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$socketDirectory = Join-Path $projectDirectory '.gradle'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previousGradleUserHome = $env:GRADLE_USER_HOME
try {
    # 某些受限 Windows 进程把 Java user.home 解析成 C:\，显式使用当前用户缓存，
    # 避免 Gradle Wrapper 尝试在系统盘根目录创建 C:\.gradle。
    if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    }
    # Windows Corretto 17 的默认 Unix-domain 回环管道在本机失败；仅为本次子进程指定可用实现。
    $env:JAVA_TOOL_OPTIONS = (($previousJavaOptions + ' -Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider -Djdk.net.unixdomain.tmpdir=' + $socketDirectory.Replace('\','/')).Trim())
    Push-Location $projectDirectory
    try {
        [string[]]$gradleArguments = @(switch ($Scope) {
            'Compile' { ':app:compileDebugKotlin' }
            'Core' { ':agent-core:test' }
            'Baseline' { ':agent-core:test'; '--tests'; 'xyz.chouxuewei.mobile_agent.core.ChatRuntimeBaselineTest'; '--rerun-tasks' }
            default { ':agent-core:test'; ':model:test'; ':data:testDebugUnitTest'; ':app:testDebugUnitTest'; ':app:assembleDebug'; ':app:lintDebug' }
        })
        & .\gradlew.bat @gradleArguments --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "聊天工程检查失败（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    $env:GRADLE_USER_HOME = $previousGradleUserHome
}
