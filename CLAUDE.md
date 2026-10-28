# CLAUDE.md – miside-minecraft-passthrough (MiSideCraft)

Doel: **MiSide als Steve spelen**, net als de Security Breach-mod (`D:\Github\fnafsb-minecraft-passthrough`, lees die CLAUDE.md voor hoe alles daar werkt). F6 = Minecraft-modus aan/uit. Wens van de gebruiker (28 okt): **niets mag nog aan Security Breach doen denken.**

## De gebruiker
Emiel (GitHub Mielesgames). Nederlands of Engels, korte uitleg zonder jargon. **Committen, pushen en releases doet hij zelf.** Voor een release: README met installatie-tutorial en featurelijst (staat erin, bijwerken).

## Opbouw (branch `miside`, begonnen 27 okt)
- Deze repo was SkyCraft (Skyrim, fork van chasmlol/SkyCraft). Op branch `miside` is `skse/` eruit gegaan en de basis van de Security Breach-mod erin gezet. Op 28 okt is alles omgebouwd naar MiSide.
- `fabric/`: de Minecraft-kant (Fabric, MC 26.3).
  - **Weg** (Security Breach): frames/end portal, bundels, ending, Freddy rijden, Pizzaplex-kit, sneakers, `/tp`-zones, altijd nacht.
  - **De Mita's** als personages (`Proto.HOST_CHARACTERS`: mita, kind, cappie, mila, short_hair, sleepy, chibi). Eigen spawn eggs en emmers (`textures/item/egg_*.png`, `bucket_*.png`, gemaakt door hercoloren) en een creative-tab "MiSide".
  - **Inhoud:** home-kit (roze bed, taart, tulpen, jukebox), gids "Steve's Guide to MiSide", herschreven advancements.
  - Kind Mita is de "vriend", wat vroeger Freddy was: golems laten haar met rust, met vleugels is ze een allay.
  - **Namen:** het menu heet `GameMenu`, de achievements van het spel `GameAchievements`, de wereld `MiSideCraft`. Mod-id en packages blijven `skycraft` / `dev.skycraft`.
  - Discord Rich Presence staat uit (app-id 0) tot er een MiSideCraft-app op Discord is.
- `protocol/skycraft_protocol.h` + `Proto.java`: het protocol (de HostCharacter-enum bevat de Mita's). Wijzig altijd beide.
- `ms/native/`: **MiSideCraftNative.dll**, de engine-vrije delen: Link, Overlay (Present-hook + HUD), World (blokken in 3D met dieptebuffer), Input en Launcher, zonder UE4SS.
  - `shim/DynamicOutput` vervangt UE4SS' logging (→ `MiSideCraft.log` naast de dll).
  - `src/Api.cpp` bevat de C-exports `msc_*`.
  - De overlay tekent alleen in een venster van klasse `UnityWndClass`.
  - De near plane voor de dieptetest komt van de plugin (`msc_set_near_plane`).
- `ms/plugin/`: **BepInEx 6 IL2CPP-plugin** (C#, net6.0).
  - `Craft.cs`: de hoofdlus.
    - F6, de sync/teleport-handdruk, en de speler op Minecrafts positie.
    - Zolang Minecraft stuurt wordt `PlayerMove.FixedUpdate` overgeslagen en is de Rigidbody kinematisch.
    - De camera wordt gezet in een postfix op `PlayerMove.LateUpdate`.
    - Het lichaam verdwijnt doordat alle renderers uit gaan.
  - `Collision.cs`: regio's van 8³ blokken (x/z ±1, y −2..+1).
    - Box-colliders en leesbare meshes worden driehoeken.
    - Wat niet leesbaar is (MiSide's vloeren en muren!) wordt met stralen uit 6 richtingen afgetast, op een raster van 0,25 m (`Probe`).
    - Laag-filter: de laag van de capsule plus `PlayerMove.castMove`.
    - `MitaPerson` en niet-kinematische rigidbodies tellen niet mee.
  - `Events.cs`:
    - Rechtsklik → `ObjectInteractive` met `castNow` (of een straal van 2,5 m) → `Click()`.
    - `EV_MENU`: 1 `FastMenuOpen`, 2 SceneMenu, 3 Quit.
    - Pauze (`Time.timeScale`) tijdens het Minecraft-pauzemenu.
  - `Actors.cs`: `MitaPerson` → actortabel (seqlock). Een klap duwt haar weg. `/mita` en `/summon mita` werken via `MitaTeleport` of `Instantiate`.
  - `Dialogue.cs`: `Dialogue_3DText.textPrint` → Minecraft-chat als `<speaker> tekst` (alleen Latin-1).
  - `Launcher.cs`: leest `MiSideCraft.ini` (`Minecraft = prism | source | none`; voor testen ook `AutoContinue = true`).
  - `TestInput.cs`: het bestand `MiSideCraft.test` in de pluginmap, met regels als `key <sdl> <ms>`, `mouse <1-3> <ms>`, `text <...>`, `scroll <n>`, `wait <ms>` en `f6`.
  - `Units.cs` (de assen) en `Native.cs` (P/Invoke en structs).
- `tools/package.ps1` maakt de release-zip: BepInEx 791 (gepind), de plugin en `MiSideCraft-Minecraft.zip` (Prism-bundel uit `tools/misidecraft-bundle`, ini uit `tools/misidecraft-release`). **Nog nooit gedraaid.**

## Assen
1 meter = 1 blok: de MiSide-speler is 1,85 m met de ogen op 1,6 m, en dat past bij Steve.
- Minecraft = (x, y, −z) van Unity.
- Minecraft-yaw = Unity-yaw + 180.
- "Host units" voor de native World-code (erfenis van Unreal): host X = mc x·70, host Y = mc z·70, host Z = mc y·70.

## MiSide
- Unity 2021.3.35, IL2CPP, D3D11, Steam-app 2527500, `C:\Program Files (x86)\Steam\steamapps\common\MiSide`.
- Camera's: `MainCamera` (de wereld, near 0,05, far 100, met dieptetextuur) en `CameraPersons` (alleen de personages).
- BepInEx 6 BE build 791 staat in het spel (`BepInEx\LogOutput.log`). Gedecompileerde interop-code: `C:\Users\emiel\MiSideCraft-dev\decomp` (ilspycmd 8.2; alleen signaturen).
- Voor het testen staat er een `steam_appid.txt` in de spelmap: het exe start dan direct, zonder Steams "already running" zolang Minecraft nog draait. Mag weg.
- Speler: `PlayerMove` (Rigidbody `rb`, `capsule`, `mainCamera`, `castMove`, `dontMove`, `animationRun`, `cameraOtherTarget`, `TeleportPlayer*`).
- `GameController`: `scrpm`, `cameraPlayer`, `isPaused`, `Pause`, `FastMenuOpen`. Het menu: `Menu.ButtonContinue()`.

## Bouwen en installeren
- Native: `"C:/Program Files/Microsoft Visual Studio/18/Community/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe" --build ms/native/build --config Release`
- Plugin + installeren (kopieert ook de native dll): `dotnet build ms/plugin -c Release -p:Install=true` (dotnet: `C:\Program Files\dotnet`).
  - Doel: `MiSide\BepInEx\plugins\MiSideCraft\`, met daar ook `MiSideCraft.ini` op `Minecraft = source`.
  - Het spel moet dicht zijn.
- Java: `fabric\gradlew compileJava compileClientJava`. Nooit terwijl Minecraft draait; controleer dan in een kopie in de scratchpad.
- Logs: `BepInEx\LogOutput.log` (plugin), `plugins\MiSideCraft\MiSideCraft.log` (native, regels `MSC`), `fabric/run/logs/latest.log`.
- **Claude kan zelf testen:**
  - Start het exe direct en zet `AutoContinue = true` in de ini.
  - Stuur toetsen via `MiSideCraft.test` of via SendInput.
  - Screenshots via een PowerShell-scriptbestand; inline blokkeert de virusscanner het.
  - Zet daarna `AutoContinue` weer uit.

## Stand (28 okt)
- **Getest door Claude** (zelf gespeeld met testinvoer):
  - HUD, hand en inventaris.
  - Lopen, springen en opstappen op MiSide's vloer en meubels; Steve springt niet meer terug.
  - Blokken plaatsen: in 3D, en de diepte klopt.
  - Dialoog in de chat, met de naam van de spreker.
  - Mila wordt als stand-in gevonden.
  - `/summon` en `/mila` komen aan, maar in een gescripte scène zet het spel Mila meteen terug.
- **Niet getest:**
  - rechtsklik op MiSide-dingen;
  - Mila slaan;
  - de pauzemenu-knoppen;
  - het licht van fakkels;
  - het releasepakket en de Prism-bundel.
- **Nog niet gebouwd:**
  - F5 (derde persoon);
  - lead, emmer en pompoen op personages (de host-kant);
  - loot-kisten (`kInLootChest`);
  - MiSide's achievements (`GameAchievements` wacht op de host);
  - deuren meteen opnieuw scannen.

## Wensen (nog niet gebouwd)
- **Mita overnemen, zoals spectator mobs overneemt** (wens 28 okt). In spectator mode op Mita klikken = je bent Mita.
  - Steve verbergen.
  - Een first-person camera in Mita's hoofd.
  - Haar bewegen zoals de speler loopt.
  - Slaan doet dan niets; haar eigen physics (haar, kleding) gewoon laten werken.
  - Haar "naar de speler kijken" (Character_Look / LookAtIK) uitzetten, anders draait ze steeds haar hoofd naar het midden van het beeld, want de speler staat op haar plek.
  - Haar hoofd niet tekenen (`MitaPerson.meshHead`, of het hoofdbot heel klein schalen), anders zit de camera erin. Haar haar wel tekenen als dat een los onderdeel is.
  - Ook genoteerd voor de Security Breach-mod.

