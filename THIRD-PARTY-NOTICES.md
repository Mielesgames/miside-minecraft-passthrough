# Third-party notices

MiSideCraft is MIT-licensed (see `LICENSE`). It is built on SkyCraft by chasmlol (MIT). A release
also contains, or is built from, the following.

## In the release zip (`MiSideCraft-<version>.zip`)

| Component | License | Source |
|---|---|---|
| BepInEx 6 (Unity IL2CPP build, unmodified) | LGPL-2.1 | https://github.com/BepInEx/BepInEx |
| Unity Doorstop (`winhttp.dll`, part of BepInEx) | LGPL-2.1 | https://github.com/NeighTools/UnityDoorstop |
| Il2CppInterop (part of BepInEx) | LGPL-3.0 | https://github.com/BepInEx/Il2CppInterop |
| HarmonyX (part of BepInEx) | MIT | https://github.com/BepInEx/HarmonyX |
| .NET runtime (`dotnet/`, part of BepInEx) | MIT | https://github.com/dotnet/runtime |

BepInEx keeps its own license files in its folders.

## In the bundled Minecraft (`MiSideCraft-Minecraft.zip`)

| Component | License | Source |
|---|---|---|
| Prism Launcher (unmodified portable Windows build) | GPL-3.0 | https://github.com/PrismLauncher/PrismLauncher (the version is in the bundle's `THIRD-PARTY.txt`) |
| Fabric API | Apache-2.0 | https://github.com/FabricMC/fabric |

The bundle carries Prism Launcher's full license text as `Prism/LICENSE-PrismLauncher.txt`.

## Not included

Minecraft, Java and Fabric Loader aren't included. Prism Launcher downloads them from Mojang,
the Java vendor and FabricMC after the player signs in with a Microsoft account that owns
Minecraft: Java Edition. MiSide isn't included either: you need to own it on Steam.
