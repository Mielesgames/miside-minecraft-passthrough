using System.Collections.Generic;
using UnityEngine;

namespace MiSideCraft;

// What the characters say (MiSide's 3D dialogue lines) also goes into Minecraft's chat, like a
// player talking: "<Mita> ...". Lines Minecraft can't show (the protocol carries Latin-1) stay out.
internal static class Dialogue
{
    private const ushort InChatText = 17;
    private const ushort InChatSay = 18;

    private static readonly HashSet<int> said = new();
    private static float lastLook;
    private static ushort nextLine;

    public static void Update()
    {
        float now = Time.realtimeSinceStartup;
        if (now - lastLook < 0.25f)
        {
            return;
        }

        lastLook = now;
        foreach (var line in Object.FindObjectsOfType<Dialogue_3DText>())
        {
            if (line == null || !line.isActiveAndEnabled)
            {
                continue;
            }

            string text = line.textPrint;
            if (string.IsNullOrWhiteSpace(text) || !said.Add(line.GetInstanceID()))
            {
                continue;
            }

            string speaker = line.speaker != null ? Speaker(line.speaker.name) : "";
            Say(speaker.Length > 0 ? $"<{speaker}> {text.Trim()}" : text.Trim());
        }

        // The same line object can say something new after a while.
        if (said.Count > 512)
        {
            said.Clear();
        }
    }

    // "Mita Kind(Clone)" -> "Mita Kind".
    private static string Speaker(string name)
    {
        int clone = name.IndexOf("(Clone)", System.StringComparison.Ordinal);
        return (clone >= 0 ? name.Substring(0, clone) : name).Trim();
    }

    private static void Say(string text)
    {
        foreach (char c in text)
        {
            if (c > 0xFF)
            {
                return;
            }
        }

        ushort id = nextLine++;
        for (int i = 0; i < text.Length; i += 12)
        {
            Native.msc_push_input(InChatText, id, Pack(text, i), Pack(text, i + 4), Pack(text, i + 8));
        }

        Native.msc_push_input(InChatSay, id, 0, 0, 0);
    }

    // Four characters (Latin-1) into one word, the first in the lowest byte.
    private static int Pack(string text, int from)
    {
        int word = 0;
        for (int k = 0; k < 4 && from + k < text.Length; k++)
        {
            word |= (text[from + k] & 0xFF) << (k * 8);
        }

        return word;
    }
}
