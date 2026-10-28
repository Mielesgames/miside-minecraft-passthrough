using System.IO;
using BepInEx;
using BepInEx.Logging;
using BepInEx.Unity.IL2CPP;
using HarmonyLib;
using Il2CppInterop.Runtime.Injection;
using UnityEngine;

namespace MiSideCraft;

// Play MiSide as Steve: Minecraft runs hidden next to the game, F6 hands the player to
// Minecraft's physics and controls, and Minecraft's HUD, hand and blocks are drawn into
// MiSide's frame. The engine-free half lives in MiSideCraftNative.dll.
[BepInPlugin("dev.mielesgames.misidecraft", "MiSideCraft", "0.1.0")]
public class Plugin : BasePlugin
{
    internal static ManualLogSource Logger;
    internal static string Folder;

    public override void Load()
    {
        Logger = Log;
        Folder = Path.GetDirectoryName(typeof(Plugin).Assembly.Location);

        if (!Native.Load(Folder))
        {
            Log.LogError($"MiSideCraftNative.dll could not be loaded from {Folder}");
            return;
        }

        if (!Native.msc_start())
        {
            Log.LogError("The shared memory or the Present hook failed: see MiSideCraft.log");
        }

        ClassInjector.RegisterTypeInIl2Cpp<Craft>();
        var host = new GameObject("MiSideCraft");
        Object.DontDestroyOnLoad(host);
        host.hideFlags = HideFlags.HideAndDontSave;
        host.AddComponent<Craft>();

        new Harmony("dev.mielesgames.misidecraft").PatchAll(typeof(Plugin).Assembly);
        Log.LogInfo("MiSideCraft loaded");
    }

    internal static void Say(string text)
    {
        Logger?.LogInfo(text);
        Native.msc_log("MSC " + text);
    }
}
