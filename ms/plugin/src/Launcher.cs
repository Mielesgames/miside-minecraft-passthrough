using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace MiSideCraft;

// Starts Minecraft next to MiSide. MiSideCraft.ini in the plugin's folder chooses which:
//   Minecraft = prism   the Prism Launcher bundle a release ships (MiSideCraft-Minecraft.zip)
//   Minecraft = source  the fabric project of this repo, run with gradlew (developing)
//   Minecraft = none    the player starts it themselves; the game only waits
// Without the ini, the bundle wins when there is one.
internal static class Launcher
{
    public static string Mode { get; private set; } = "";
    // For testing (MiSideCraft.ini "AutoContinue = true"): see Craft.AutoContinue.
    public static bool AutoContinue { get; private set; }
    private static Process gradle;

    public static void Start()
    {
        bool hasBundle = File.Exists(Path.Combine(Plugin.Folder, "MiSideCraft-Minecraft.zip"));
        Mode = hasBundle ? "prism" : "source";
        var ini = Path.Combine(Plugin.Folder, "MiSideCraft.ini");
        if (File.Exists(ini))
        {
            foreach (var raw in File.ReadAllLines(ini))
            {
                var line = raw.Replace(" ", "").Replace("\t", "").ToLowerInvariant();
                if (line == "minecraft=source" || line == "minecraft=none" || (line == "minecraft=prism" && hasBundle))
                {
                    Mode = line.Substring("minecraft=".Length);
                }
                else if (line == "autocontinue=true")
                {
                    AutoContinue = true;
                }
            }
        }

        if (Native.msc_minecraft_running())
        {
            Plugin.Say("launcher: Minecraft is running already");
            return;
        }

        Plugin.Say($"launcher: Minecraft = {Mode}");
        switch (Mode)
        {
            case "prism":
                // Unpacking takes a moment: not on the game's thread.
                new Thread(() => Native.msc_start_bundled(Plugin.Folder)) { IsBackground = true }.Start();
                break;
            case "source":
                StartFromSource();
                break;
        }
    }

    private static void StartFromSource()
    {
        var fabric = Environment.GetEnvironmentVariable("MSC_FABRIC_DIR");
        if (string.IsNullOrEmpty(fabric))
        {
            fabric = @"D:\Github\miside-minecraft-passthrough\fabric";
        }

        var info = new ProcessStartInfo(@"C:\Windows\System32\cmd.exe", $"/C call \"{fabric}\\gradlew.bat\" runClient --no-daemon")
        {
            WorkingDirectory = fabric,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        // Tells the Minecraft side there is no Skyrim collision feed: it is a passthrough host.
        // (No Gradle daemon: Minecraft is then a child of this process tree and closes with MiSide.)
        info.EnvironmentVariables["SKYCRAFT_PASSTHROUGH"] = "1";

        try
        {
            gradle = Process.Start(info);
            Plugin.Say($"launcher: gradlew runClient in {fabric}");
        }
        catch (Exception e)
        {
            Plugin.Say($"launcher: gradlew failed to start: {e.Message}");
        }
    }

    // MiSide closes: a Minecraft started from source goes with it.
    public static void Stop()
    {
        try
        {
            if (gradle != null && !gradle.HasExited)
            {
                gradle.Kill(true);
            }
        }
        catch (Exception)
        {
        }
    }
}
