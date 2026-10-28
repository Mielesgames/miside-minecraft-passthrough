using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using UnityEngine;

namespace MiSideCraft;

// The characters near the player, for Minecraft: each gets an invisible stand-in there that can be
// bumped into and hit. What Minecraft does to a stand-in comes back as an event and happens to the
// character here.
//
// Mitas (MitaPerson) and the other characters (anything else with Character_Look) each follow
// their own /miside setting: realistic, a hit only swings their hair and clothes; minecraft, they
// are knocked back like a mob.
//
// Chibi Mitas are small enough to pick up: right-click one with an empty hand (a spawn egg) or a
// water bucket (a bucket of her). Using that item puts her down again where Steve looks.
internal static class Actors
{
    private const ulong OffActorTable = 0x12000;
    private const int MaxActors = 256;
    private const int RecordBytes = 64;
    private const int RecordsOffset = 0x40;
    private const float Range = 48f;

    private const uint EvHitActor = 1;
    private const uint EvActorPush = 10;

    private const ushort InTokenName = 11;
    private const ushort InBucketToken = 16;
    private const ushort InPickToken = 19;

    // Proto.HOST_CHARACTERS, in its order (the command's formId is the index + 1).
    private static readonly string[] Cast = { "mita", "kind", "cappie", "mila", "short_hair", "sleepy", "chibi" };

    private sealed class Character
    {
        public Transform root;
        public MitaPerson mita;
        public string name;
    }

    private sealed class Knock
    {
        public Transform root;
        public Vector3 velocity;
        public float until;
    }

    private static readonly Dictionary<uint, Character> byId = new();
    private static List<Character> found = new();
    private static readonly HashSet<int> roots = new();
    private static readonly List<Knock> knocks = new();
    private static readonly Dictionary<uint, Character> pickedUp = new();
    private static uint nextToken = 1;
    private static float lastSearch = -10f;
    private static uint seq;

    public static void Update(Vector3 player)
    {
        float now = Time.realtimeSinceStartup;
        if (now - lastSearch > 0.5f)
        {
            lastSearch = now;
            Search();
        }

        // Knocked back: carried along for a moment, slowing down, like a mob that was hit.
        for (int i = knocks.Count - 1; i >= 0; i--)
        {
            var knock = knocks[i];
            if (knock.root == null || now >= knock.until)
            {
                knocks.RemoveAt(i);
                continue;
            }

            knock.root.position += knock.velocity * Time.deltaTime;
            knock.velocity *= Mathf.Exp(-6f * Time.deltaTime);
        }

        Write(player);
    }

    private static void Search()
    {
        var list = new List<Character>();
        roots.Clear();
        foreach (var mita in UnityEngine.Object.FindObjectsOfType<MitaPerson>())
        {
            if (mita != null && mita.isActiveAndEnabled)
            {
                Add(list, mita.transform, mita);
            }
        }

        // Everyone else who looks around like a character does.
        foreach (var look in UnityEngine.Object.FindObjectsOfType<Character_Look>())
        {
            if (look == null || !look.isActiveAndEnabled || look.GetComponentInParent<MitaPerson>() != null
                || look.GetComponentInParent<PlayerMove>() != null)
            {
                continue;
            }

            var animator = look.animPerson;
            Add(list, animator != null ? animator.transform : look.transform, null);
        }

        if (list.Count != found.Count)
        {
            Plugin.Say($"actors: {list.Count} characters ({string.Join(", ", list.ConvertAll(c => $"{c.name} root {c.root.position} seen {Feet(c)}"))})");
        }

        found = list;
    }

    private static void Add(List<Character> list, Transform root, MitaPerson mita)
    {
        if (roots.Add(root.GetInstanceID()))
        {
            list.Add(new Character { root = root, mita = mita, name = Clean(root.gameObject.name) });
        }
    }

    // Is this part of one of the characters (their colliders aren't walls)?
    public static bool IsCharacter(Transform t)
    {
        return t.GetComponentInParent<MitaPerson>() != null || t.GetComponentInParent<Character_Look>() != null;
    }

    private static bool IsChibi(Character c) => c.name.IndexOf("chibi", StringComparison.OrdinalIgnoreCase) >= 0;

    // The actor table (seqlock: odd while it is being written).
    private static unsafe void Write(Vector3 player)
    {
        var shared = Native.msc_shared_base();
        if (shared == IntPtr.Zero)
        {
            return;
        }

        byte* table = (byte*)shared + OffActorTable;
        int* seqPtr = (int*)table;
        Volatile.Write(ref *seqPtr, (int)++seq);
        seq++;

        byId.Clear();
        int count = 0;
        foreach (var c in found)
        {
            if (c.root == null || !c.root.gameObject.activeInHierarchy || count >= MaxActors)
            {
                continue;
            }

            var t = c.root;
            var at = Feet(c);
            if (Vector3.Distance(at, player) > Range)
            {
                continue;
            }

            uint id = (uint)t.GetInstanceID();
            byId[id] = c;
            var feet = Units.ToMc(at);
            bool small = IsChibi(c);
            byte* r = table + RecordsOffset + count * RecordBytes;
            *(uint*)r = id;
            *(uint*)(r + 4) = 0;
            *(float*)(r + 8) = (float)feet.x;
            *(float*)(r + 12) = (float)feet.y;
            *(float*)(r + 16) = (float)feet.z;
            *(float*)(r + 20) = Units.YawToMc(t.eulerAngles.y);
            *(float*)(r + 24) = small ? 0.4f : 0.6f;
            *(float*)(r + 28) = small ? 0.6f : 1.75f;
            *(float*)(r + 32) = 1f;
            *(ushort*)(r + 36) = 1;
            *(ushort*)(r + 38) = 0;
            WriteName(r + 40, c.name);
            count++;
        }

        *(uint*)(table + 4) = (uint)count;
        Thread.MemoryBarrier();
        Volatile.Write(ref *seqPtr, (int)seq);
    }

    private static unsafe void WriteName(byte* into, string name)
    {
        var bytes = Encoding.UTF8.GetBytes(name ?? "");
        int n = Math.Min(bytes.Length, 23);
        for (int i = 0; i < 24; i++)
        {
            into[i] = i < n ? bytes[i] : (byte)0;
        }
    }

    // Where the character is seen: her animations move the model away from her root object, so
    // the middle of what she draws, at the height of its lowest point.
    private static Vector3 Feet(Character c)
    {
        bool any = false;
        var bounds = default(Bounds);
        foreach (var renderer in c.root.GetComponentsInChildren<SkinnedMeshRenderer>())
        {
            if (!renderer.enabled)
            {
                continue;
            }

            if (any)
            {
                bounds.Encapsulate(renderer.bounds);
            }
            else
            {
                bounds = renderer.bounds;
                any = true;
            }
        }

        return any ? new Vector3(bounds.center.x, bounds.min.y, bounds.center.z) : c.root.position;
    }

    // "Mita Chibi(Clone)" -> "Mita Chibi".
    private static string Clean(string name)
    {
        int clone = name.IndexOf("(Clone)", StringComparison.Ordinal);
        return (clone >= 0 ? name.Substring(0, clone) : name).Trim();
    }

    // What Minecraft did to a stand-in. True when the event was one of these.
    public static bool Handle(in Native.McEvent ev)
    {
        if (!byId.TryGetValue(ev.formId, out var c) || c.root == null)
        {
            return ev.type == EvHitActor || ev.type == EvActorPush;
        }

        switch (ev.type)
        {
            case EvHitActor:
            {
                // The way the hit went (b, c: Minecraft x, z), as strong as it was (d).
                var away = new Vector3(ev.b, 0f, -ev.c);
                away = away.sqrMagnitude > 1e-6f ? away.normalized : -c.root.forward;
                float strength = Mathf.Clamp(0.5f + ev.d, 0.5f, 3f);
                Hits.PushCloth(c.root, (away + Vector3.up * 0.3f) * (3f + 2f * strength));

                bool knockback = c.mita != null ? Settings.MitaKnockback : Settings.NpcKnockback;
                if (knockback)
                {
                    knocks.Add(new Knock { root = c.root, velocity = away * (4f * strength), until = Time.realtimeSinceStartup + 0.6f });
                }

                Plugin.Say($"Steve hit {c.name} ({ev.a:F1} damage, {(knockback ? "knocked back" : "only physics")})");
                return true;
            }
            case EvActorPush:
                c.root.position += new Vector3(ev.a, ev.b, -ev.c);
                return true;
        }

        return false;
    }

    // ---- picking up a Chibi Mita (right click). True when one was picked up.

    public static bool PickUp(Transform camera, Transform player, bool holdsBucket)
    {
        var hit = Hits.Aimed(camera, player, out var aimed);
        Character chibi = null;
        foreach (var c in found)
        {
            if (c.root == null || !c.root.gameObject.activeInHierarchy || !IsChibi(c))
            {
                continue;
            }

            // Aimed at her, or right in front of Steve's eyes.
            bool onHer = aimed && hit.collider.transform.IsChildOf(c.root);
            var toHer = c.root.position + Vector3.up * 0.3f - camera.position;
            bool inFront = toHer.magnitude < 2.5f && Vector3.Angle(camera.forward, toHer) < 20f;
            if (onHer || inFront)
            {
                chibi = c;
                break;
            }
        }

        if (chibi == null)
        {
            return false;
        }

        uint token = 0x2000 + nextToken++;
        pickedUp[token] = chibi;
        chibi.root.gameObject.SetActive(false);
        lastSearch = -10f;

        string name = chibi.name.Length > 0 ? chibi.name : "Chibi Mita";
        for (int i = 0; i < name.Length; i += 12)
        {
            Native.msc_push_input(InTokenName, (ushort)token, Pack(name, i), Pack(name, i + 4), Pack(name, i + 8));
        }

        if (holdsBucket)
        {
            Native.msc_push_input(InBucketToken, (ushort)token, 0, 0, 0);
        }
        else
        {
            Native.msc_push_input(InPickToken, (ushort)token, 1, 0, 0);
        }

        Plugin.Say($"picked up {name} ({(holdsBucket ? "in a bucket" : "as an egg")}, token {token})");
        return true;
    }

    // The egg or bucket of a picked-up Chibi Mita used: she is put down where Steve looks.
    public static bool PutDown(uint token, Transform camera, Transform player)
    {
        if (!pickedUp.TryGetValue(token, out var chibi) || chibi.root == null)
        {
            return false;
        }

        pickedUp.Remove(token);
        var hit = Hits.Aimed(camera, player, out var aimed);
        var spot = aimed ? hit.point : camera.position + camera.forward * 2f;
        if (Physics.Raycast(spot + Vector3.up * 0.5f, Vector3.down, out var ground, 3f, ~0, QueryTriggerInteraction.Ignore))
        {
            spot = ground.point;
        }

        var facing = camera.position - spot;
        facing.y = 0f;
        chibi.root.SetPositionAndRotation(spot, facing.sqrMagnitude > 1e-4f ? Quaternion.LookRotation(facing) : chibi.root.rotation);
        chibi.root.gameObject.SetActive(true);
        lastSearch = -10f;
        Plugin.Say($"put {chibi.name} down at {spot}");
        return true;
    }

    public static void Forget()
    {
        pickedUp.Clear();
        knocks.Clear();
    }

    private static int Pack(string text, int from)
    {
        int word = 0;
        for (int k = 0; k < 4 && from + k < text.Length; k++)
        {
            word |= (text[from + k] & 0xFF) << (k * 8);
        }

        return word;
    }

    // ---- /mita, /summon mita

    // "/mita" brings her in front of the player, "/summon mita" makes another one of her there.
    // Only a character that is somewhere in the current chapter: a copy of her is what is made.
    public static void Command(in Native.McEvent ev, Transform camera)
    {
        int index = (int)ev.formId - 1;
        if (index < 0 || index >= Cast.Length || camera == null)
        {
            return;
        }

        string key = Cast[index].Replace("_", "").Replace("hair", "");
        MitaPerson match = null;
        foreach (var mita in UnityEngine.Object.FindObjectsOfType<MitaPerson>(true))
        {
            string name = mita.gameObject.name.ToLowerInvariant().Replace(" ", "");
            // "mita" is any Mita whose name says nothing more specific.
            bool fits = key == "mita" ? name.Contains("mita") && !OtherCast(name) : name.Contains(key);
            if (fits && (match == null || mita.isActiveAndEnabled))
            {
                match = mita;
            }
        }

        if (match == null)
        {
            Plugin.Say($"command: no {Cast[index]} in this chapter");
            return;
        }

        var forward = camera.forward;
        forward.y = 0f;
        var spot = camera.position + forward.normalized * 1.5f;
        if (Physics.Raycast(spot + Vector3.up, Vector3.down, out var ground, 4f, ~0, QueryTriggerInteraction.Ignore))
        {
            spot = ground.point;
        }

        var facing = Quaternion.LookRotation(-forward.normalized, Vector3.up);
        // Her own teleport, so her walking and animations start from there too.
        var target = new GameObject("MiSideCraft target").transform;
        target.SetPositionAndRotation(spot, facing);
        var who = match;
        if (ev.flags == 1)
        {
            who = UnityEngine.Object.Instantiate(match.gameObject, spot, facing).GetComponent<MitaPerson>();
        }

        who.gameObject.SetActive(true);
        try
        {
            who.MitaTeleport(target);
        }
        catch (Exception e)
        {
            Plugin.Say($"command: MitaTeleport failed ({e.Message}), moved directly");
        }

        who.transform.SetPositionAndRotation(spot, facing);
        UnityEngine.Object.Destroy(target.gameObject, 1f);
        lastSearch = -10f;
        Plugin.Say($"command: {(ev.flags == 1 ? "summoned another" : "brought")} {match.gameObject.name} to {spot}");
    }

    private static bool OtherCast(string name)
    {
        for (int i = 1; i < Cast.Length; i++)
        {
            if (name.Contains(Cast[i].Replace("_", "").Replace("hair", "")))
            {
                return true;
            }
        }

        return false;
    }
}
