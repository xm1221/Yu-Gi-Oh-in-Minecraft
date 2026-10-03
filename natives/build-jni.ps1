<#
.SYNOPSIS
    构建 ygomc 的 ocgcore JNI 桥（ygomc_ocg.dll）。

.DESCRIPTION
    产物与 ocgcore.dll 分开：ocgcore 是 MIT 的第三方内核，我们是 GPLv3 的桥，
    两者各自独立编译，许可边界清楚。

    依赖 ocgcore.lib（先跑 natives/vendor/ocgcore 下的 premake + msbuild 生成）。
    与内核一致使用静态 CRT（/MT），避免两个 DLL 各带一套 CRT 时的意外。

.EXAMPLE
    pwsh natives/build-jni.ps1
    pwsh natives/build-jni.ps1 -Configuration Debug
#>
[CmdletBinding()]
param(
    [ValidateSet('Release', 'Debug')]
    [string]$Configuration = 'Release',

    # 留空则按 JAVA_HOME → 常见安装位置自动探测，并要求是 JDK 21
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'

$NativesDir = $PSScriptRoot
$RootDir    = Split-Path -Parent $NativesDir
$CoreDir    = Join-Path $NativesDir 'vendor\ocgcore'
$JniSrc     = Join-Path $NativesDir 'jni\ygomc_ocg.cpp'
$CoreLib    = Join-Path $CoreDir "build\bin\x64\$Configuration\ocgcore.lib"
$OutDir     = Join-Path $NativesDir "build\$Configuration"
$OutDll     = Join-Path $OutDir 'ygomc_ocg.dll'

function Find-Jdk {
    param([string]$Explicit)
    if ($Explicit) {
        if (Test-Path (Join-Path $Explicit 'include\jni.h')) { return $Explicit }
        throw "指定的 JDK 目录里没有 include\jni.h: $Explicit"
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'include\jni.h'))) {
        return $env:JAVA_HOME
    }
    $roots = @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium',
               'C:\Program Files\Microsoft', 'E:\Java')
    $found = foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk-?21' -and (Test-Path (Join-Path $_.FullName 'include\jni.h')) } |
            Select-Object -ExpandProperty FullName
    }
    if (-not $found) { throw '找不到 JDK 21；请用 -JdkHome 指定。' }
    return @($found)[0]
}

# 找 MSVC 环境脚本
$VcVars = $null
foreach ($p in @(
        'E:\VS\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat')) {
    if (Test-Path $p) { $VcVars = $p; break }
}
if (-not $VcVars) { throw '找不到 vcvars64.bat，请装 Visual Studio C++ 生成工具。' }
if (-not (Test-Path $CoreLib)) {
    throw "找不到 ocgcore.lib ($CoreLib)。先构建内核：`n" +
          "  `$pm = Join-Path $RootDir '.agent\toolchain\premake-5\premake5.exe'`n" +
          "  Push-Location '$CoreDir'; & `$pm vs2022 --file=dll.lua; Pop-Location`n" +
          "  E:\VS\MSBuild\Current\Bin\MSBuild.exe '$CoreDir\build\ocgcoredll.sln' /m /nologo /v:minimal /t:Build '/p:Configuration=$Configuration;Platform=x64;PlatformToolset=v145'"
}

$Jdk = Find-Jdk -Explicit $JdkHome
Write-Host "JDK        : $Jdk"
Write-Host "vcvars     : $VcVars"
Write-Host "ocgcore.lib: $CoreLib"
Write-Host "输出       : $OutDll"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 把 vcvars64 的环境导入当前 PowerShell 会话，然后用参数数组直接调 cl。
# （不用响应文件：cl/link 的响应文件引号转义规则会把 /OUT:"..." 逐字符拆开。）
Write-Host '正在导入 MSVC 环境 ...'
$envDump = & cmd.exe /c "`"$VcVars`" >nul 2>&1 && set"
if ($LASTEXITCODE -ne 0) { throw "vcvars64.bat 执行失败" }
foreach ($line in $envDump) {
    if ($line -match '^([^=]+)=(.*)$') {
        Set-Item -Path ("env:" + $matches[1]) -Value $matches[2] -ErrorAction SilentlyContinue
    }
}
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) { throw 'vcvars64.bat 之后仍找不到 cl.exe' }

$clArgs = @(
    '/nologo'
    '/LD'
    # 源码是 UTF-8（含中文注释），而 MSVC 默认按系统代码页(936/GBK)读，
    # 不显式指定会把注释里的中文误读成乱码并连带截断字符串常量。
    '/utf-8'
    '/std:c++17'
    '/EHsc'
    '/W4'
    '/MT'
    '/O2'
    '/DNDEBUG'
    '/DWIN32_LEAN_AND_MEAN'
    '/DNOMINMAX'
    "/I$CoreDir"
    "/I$(Join-Path $Jdk 'include')"
    "/I$(Join-Path $Jdk 'include\win32')"
    "/Fo$OutDir\"
    "/Fd$OutDir\ygomc_ocg.pdb"
    $JniSrc
    '/link'
    $CoreLib
    '/DLL'
    "/OUT:$OutDll"
    # 必须显式给 /IMPLIB：否则 .lib 和 .exp 会落到「当前工作目录」而不是 /OUT 所在的
    # 目录，在项目根留下一堆脏文件（而且 .gitignore 只挡 natives/ 下的）。
    "/IMPLIB:$OutDir\ygomc_ocg.lib"
    '/INCREMENTAL:NO'
)
if ($Configuration -eq 'Debug') {
    $clArgs = $clArgs | Where-Object { $_ -notin @('/O2', '/DNDEBUG') }
    $clArgs = @('/Od', '/Zi') + $clArgs
}

Write-Host ""
& cl.exe @clArgs
$code = $LASTEXITCODE
if ($code -ne 0) { throw "JNI 桥编译失败，退出码 $code" }

if (-not (Test-Path $OutDll)) { throw "编译报成功但没生成 $OutDll" }
$dll = Get-Item $OutDll
Write-Host ("`n构建成功: {0} ({1:N0} 字节)" -f $dll.FullName, $dll.Length)
