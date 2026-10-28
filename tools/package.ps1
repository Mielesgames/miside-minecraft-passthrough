# Builds both halves of MiSideCraft and packs a release into dist\:
#   MiSideCraft-<version>.zip         unpacked into MiSide's folder (Steam\steamapps\common\MiSide):
#                                     BepInEx 6 (IL2CPP), the plugin and its native half, and
#                                     MiSideCraft-Minecraft.zip, the Minecraft it starts
#   misidecraft-fabric-<version>.jar  the Minecraft mod on its own (for your own launcher)
#
# MiSideCraft-Minecraft.zip holds a portable Prism Launcher with a ready "MiSideCraft" instance (Minecraft
# 26.3, Fabric, Fabric API, the mod; -Dskycraft.passthrough=true). The plugin unpacks it to
# %LOCALAPPDATA%\MiSideCraft and starts it; Prism asks the player to sign in once, then downloads
# Minecraft and Java itself.
#
#   powershell -ExecutionPolicy Bypass -File tools\package.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path "$root\fabric\gradle.properties" -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
$cmake = "C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
$dotnet = "C:\Program Files\dotnet\dotnet.exe"

# Pinned downloads (checked against these hashes).
$prismVersion = "11.1.1"
$prismZip = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"
$prismUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$prismZip"
$prismSha256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
$prismLicenseUrl = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/$prismVersion/LICENSE"
$fabricApiJar = "fabric-api-0.161.0+26.3.jar"
$fabricApiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/bNnaTiuM/fabric-api-0.161.0%2B26.3.jar"
$fabricApiSha512 = "ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"
$bepinexZip = "BepInEx-Unity.IL2CPP-win-x64-6.0.0-be.791+b782da4.zip"
$bepinexUrl = "https://builds.bepinex.dev/projects/bepinex_be/791/BepInEx-Unity.IL2CPP-win-x64-6.0.0-be.791%2Bb782da4.zip"
$bepinexSha256 = "bb1c510909b69522733791f3a0817a487379a52da6468a492f1c9c19b3ec6f9a"

function Get-Pinned([string]$url, [string]$path, [string]$algorithm, [string]$hash) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory (Split-Path $path) -Force | Out-Null
        Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
    }
    if ($hash -and (Get-FileHash $path -Algorithm $algorithm).Hash -ne $hash.ToUpper()) {
        Remove-Item $path
        throw "$path doesn't match its pinned $algorithm hash"
    }
}

# Zip entries named explicitly with forward slashes.
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
function New-Zip([string]$path, [System.Collections.IDictionary]$entries) {
    $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $entries.Keys) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $entries[$name], $name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
}
function Get-FolderEntries([string]$folder, [string]$prefix) {
    $entries = [ordered]@{}
    $base = (Resolve-Path $folder).Path.TrimEnd('\') + '\'
    Get-ChildItem $folder -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
        $entries[$prefix + $_.FullName.Substring($base.Length).Replace('\', '/')] = $_.FullName
    }
    return $entries
}

if (-not $NoBuild) {
    & $cmake --build "$root\ms\native\build" --config Release
    if ($LASTEXITCODE) { throw "the native half didn't build" }
    & $dotnet build "$root\ms\plugin" -c Release
    if ($LASTEXITCODE) { throw "the plugin didn't build (it needs MiSide with BepInEx run once: BepInEx\interop)" }
    Push-Location "$root\fabric"
    try {
        .\gradlew.bat build --no-configuration-cache
        if ($LASTEXITCODE) { throw "the Fabric mod didn't build" }
    } finally { Pop-Location }
}

$plugin = "$root\ms\plugin\bin\Release\net6.0\MiSideCraft.dll"
$native = "$root\ms\native\build\Release\MiSideCraftNative.dll"
$jar = "$root\fabric\build\libs\skycraft-$version.jar"
foreach ($f in @($plugin, $native, $jar)) {
    if (-not (Test-Path $f)) { throw "missing $f (build first, or drop -NoBuild)" }
}
$cache = "$root\.tools\prism"
Get-Pinned $prismUrl "$cache\$prismZip" SHA256 $prismSha256
Get-Pinned $fabricApiUrl "$cache\$fabricApiJar" SHA512 $fabricApiSha512
Get-Pinned $prismLicenseUrl "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "" ""
Get-Pinned $bepinexUrl "$cache\$bepinexZip" SHA256 $bepinexSha256

$dist = "$root\dist"
New-Item -ItemType Directory $dist -Force | Out-Null
Get-ChildItem $dist | Remove-Item -Recurse -Force

# The bundled Minecraft: Prism (portable), the MiSideCraft instance, its mods, Prism's default settings.
$bundle = "$dist\bundle"
Copy-Item -Recurse "$root\tools\misidecraft-bundle" $bundle
Expand-Archive "$cache\$prismZip" "$bundle\Prism" -Force
Copy-Item "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "$bundle\Prism\LICENSE-PrismLauncher.txt"
(Get-Content "$bundle\Prism\THIRD-PARTY.txt" -Raw).Replace("{PRISM_VERSION}", $prismVersion) | Set-Content "$bundle\Prism\THIRD-PARTY.txt" -NoNewline
$mods = "$bundle\Prism\instances\MiSideCraft\.minecraft\mods"
New-Item -ItemType Directory $mods -Force | Out-Null
Copy-Item "$cache\$fabricApiJar" $mods
Copy-Item $jar "$mods\misidecraft-$version.jar"
Set-Content "$bundle\bundle-version.txt" "MiSideCraft $version, Prism Launcher $prismVersion, $fabricApiJar" -NoNewline
$minecraftZip = "$dist\MiSideCraft-Minecraft.zip"
New-Zip $minecraftZip (Get-FolderEntries $bundle "")
Remove-Item -Recurse -Force $bundle

# BepInEx as its official build ships it, with the plugin in its plugins folder.
$bepinex = "$dist\bepinex"
Expand-Archive "$cache\$bepinexZip" $bepinex -Force
$entries = Get-FolderEntries $bepinex ""
$pluginDir = "BepInEx/plugins/MiSideCraft"
$entries["$pluginDir/MiSideCraft.dll"] = $plugin
$entries["$pluginDir/MiSideCraftNative.dll"] = $native
$entries["$pluginDir/MiSideCraft.ini"] = "$root\tools\misidecraft-release\MiSideCraft.ini"
$entries["$pluginDir/MiSideCraft-Minecraft.zip"] = $minecraftZip
$entries["$pluginDir/LICENSE.txt"] = "$root\LICENSE"
$entries["$pluginDir/THIRD-PARTY-NOTICES.md"] = "$root\THIRD-PARTY-NOTICES.md"
New-Zip "$dist\MiSideCraft-$version.zip" $entries
Copy-Item $jar "$dist\misidecraft-fabric-$version.jar"
Remove-Item $minecraftZip
Remove-Item -Recurse -Force $bepinex

Get-ChildItem $dist | ForEach-Object { "{0,-44} {1,14:N0} bytes" -f $_.Name, $_.Length }
