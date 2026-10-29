using System.Collections.Generic;
using System.IO;
using UnityEngine;

namespace MiSideCraft;

// For testing without hands: lines written to MiSideCraft.test in the plugin's folder are
// played into Minecraft, then the file is removed. "key <SDL scancode> <ms>" holds a key,
// "mouse <button 1 L, 2 M, 3 R> <ms>" holds a mouse button, "scroll <notches>" scrolls,
// "text <words>" types, "wait <ms>" waits, "f6" toggles Minecraft mode.
internal static class TestInput
{
    private const ushort InKey = 1;
    private const ushort InMouseButton = 2;
    private const ushort InScroll = 3;
    private static readonly Queue<string> steps = new();
    private static float busyUntil;
    private static int heldKey = -1;
    private static ushort heldType = InKey;
    private static float lastCheck;

    public static bool TakeF6;

    public static void Update()
    {
        float now = Time.realtimeSinceStartup;
        if (steps.Count == 0 && now - lastCheck > 1f)
        {
            lastCheck = now;
            var path = Path.Combine(Plugin.Folder, "MiSideCraft.test");
            if (File.Exists(path))
            {
                foreach (var line in File.ReadAllLines(path))
                {
                    steps.Enqueue(line.Trim());
                }

                File.Delete(path);
                Plugin.Say($"test: {steps.Count} steps");
            }
        }

        if (now < busyUntil)
        {
            return;
        }

        if (heldKey >= 0)
        {
            Native.msc_push_input(heldType, (ushort)heldKey, 0, 0, 0);
            heldKey = -1;
        }

        if (steps.Count == 0)
        {
            return;
        }

        var parts = steps.Dequeue().Split(' ');
        switch (parts[0])
        {
            case "key" or "mouse" when parts.Length >= 3:
                heldType = parts[0] == "mouse" ? InMouseButton : InKey;
                heldKey = int.Parse(parts[1]);
                Native.msc_push_input(heldType, (ushort)heldKey, 1, 0, 0);
                busyUntil = now + int.Parse(parts[2]) / 1000f;
                break;
            case "text" when parts.Length >= 2:
                foreach (char c in string.Join(" ", parts, 1, parts.Length - 1))
                {
                    Native.msc_push_input(5, 0, c, 0, 0);
                }
                break;
            case "scroll" when parts.Length >= 2:
                Native.msc_push_input(InScroll, 0, int.Parse(parts[1]) * 120, 0, 0);
                break;
            case "wait" when parts.Length >= 2:
                busyUntil = now + int.Parse(parts[1]) / 1000f;
                break;
            case "aim" when parts.Length >= 3:
            {
                var camera = Camera.main != null ? Camera.main.transform.position : Vector3.zero;
                var point = Actors.TestPoint(camera, parts[2].ToLowerInvariant());
                if (point.HasValue)
                {
                    Craft.Instance?.AimAt(point.Value);
                }

                break;
            }
            case "aimat" when parts.Length >= 2:
            {
                string name = string.Join(" ", parts, 1, parts.Length - 1).ToLowerInvariant();
                foreach (var collider in Object.FindObjectsOfType<Collider>())
                {
                    if (collider.enabled && collider.gameObject.name.ToLowerInvariant().Contains(name))
                    {
                        var b = collider.bounds;
                        Craft.Instance?.AimAt(new Vector3(b.center.x, b.max.y - 0.05f, b.center.z));
                        Plugin.Say($"test: aiming at {collider.gameObject.name}");
                        break;
                    }
                }

                break;
            }
            case "f6":
                TakeF6 = true;
                break;
        }
    }
}
