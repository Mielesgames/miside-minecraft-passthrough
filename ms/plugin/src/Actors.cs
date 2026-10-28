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
    private const uint EvLeash = 12;
    // Characters on a lead: where its other end is, and when Minecraft last said so.
    private static readonly Dictionary<uint, (Vector3 end, float at)> leashes = new();
    private const float LeashLength = 4f;

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

        HoldOnLeashes(now);
        Write(player);
    }

    // A character further from the lead's other end than the lead is long is pulled back to it,
    // through her navigation agent where she has one (so not through walls).
    private static void HoldOnLeashes(float now)
    {
        foreach (var (id, leash) in new List<KeyValuePair<uint, (Vector3 end, float at)>>(leashes).ConvertAll(p => (p.Key, p.Value)))
        {
            if (now - leash.at > 1f || !byId.TryGetValue(id, out var c) || c.root == null)
            {
                leashes.Remove(id);
                continue;
            }

            var at = Feet(c);
            var pull = leash.end - at;
            pull.y = 0f;
            if (pull.magnitude <= LeashLength)
            {
                continue;
            }

            var step = pull.normalized * (pull.magnitude - LeashLength);
            var agent = c.mita != null ? c.mita.nma : null;
            if (agent != null && agent.isActiveAndEnabled && agent.isOnNavMesh)
            {
                agent.Move(step);
            }
            else
            {
                c.root.position += step;
            }
        }
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
            // As big as she is drawn right now: lying on the floor she is low and long.
            var seen = Bounds(c);
            float width = Mathf.Clamp(Mathf.Max(seen.size.x, seen.size.z) * 0.6f, 0.3f, 1.6f);
            float height = Mathf.Clamp(seen.size.y, 0.3f, 2.2f);
            byte* r = table + RecordsOffset + count * RecordBytes;
            *(uint*)r = id;
            *(uint*)(r + 4) = 0;
            *(float*)(r + 8) = (float)feet.x;
            *(float*)(r + 12) = (float)feet.y;
            *(float*)(r + 16) = (float)feet.z;
            *(float*)(r + 20) = Units.YawToMc(t.eulerAngles.y);
            *(float*)(r + 24) = width;
            *(float*)(r + 28) = height;
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
        var bounds = Bounds(c);
        return bounds.size == Vector3.zero ? c.root.position : new Vector3(bounds.center.x, bounds.min.y, bounds.center.z);
    }

    private static Bounds Bounds(Character c)
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

        return any ? bounds : new Bounds(c.root.position, Vector3.zero);
    }

    // "Mita Chibi(Clone)" -> "Mita Chibi".
    public static int IdOf(MitaPerson mita) => mita.transform.GetInstanceID();

    public static MitaPerson MitaById(uint id) => byId.TryGetValue(id, out var c) ? c.mita : null;

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
            case EvHitActor when Possession.Active:
                // Being her: a blow does nothing (Minecraft's spectator can't hit anyway).
                return true;
            case EvHitActor:
            {
                // The way the hit went (b, c: Minecraft x, z), as strong as it was (d).
                var away = new Vector3(ev.b, 0f, -ev.c);
                away = away.sqrMagnitude > 1e-6f ? away.normalized : -c.root.forward;
                float strength = Mathf.Clamp(0.5f + ev.d, 0.5f, 3f);
                Hits.DumpCloths();
                var body = Bounds(c);
                body.Expand(0.4f);
                int pushed = Hits.PushCloth(body, (away + Vector3.up * 0.3f) * (6f + 3f * strength));

                // A sword's sweep (HIT_SWEEP): her upper body goes, her legs walk on.
                if (((ev.flags & 4u) != 0 || Time.realtimeSinceStartup - sweptAt < 0.6f) && Settings.Sweep)
                {
                    Cut(c);
                }

                bool knockback = c.mita != null ? Settings.MitaKnockback : Settings.NpcKnockback;
                if (knockback)
                {
                    knocks.Add(new Knock { root = c.root, velocity = away * (4f * strength), until = Time.realtimeSinceStartup + 0.6f });
                }

                Plugin.Say($"Steve hit {c.name} ({ev.a:F1} damage, {(knockback ? "knocked back" : "only physics")}, {pushed} hair/cloth parts pushed)");
                return true;
            }
            case EvActorPush:
                c.root.position += new Vector3(ev.a, ev.b, -ev.c);
                return true;
            case EvLeash:
                // On a lead (every tick while she is): the other end is at a, b, c.
                if (ev.flags == 0)
                {
                    leashes.Remove(ev.formId);
                }
                else
                {
                    leashes[ev.formId] = (Units.FromMc(ev.a, ev.b, ev.c), Time.realtimeSinceStartup);
                }

                return true;
        }

        return false;
    }

    // ---- flinching: the part that is hit sways back from the blow

    // A bone swinging on a spring: its offset is laid over whatever the animation set, every frame
    // after the animation (Craft.LateUpdate), so it works whatever she is doing.
    private sealed class Sway
    {
        public Transform bone;
        public Vector3 axis;      // world axis of the swing
        public float angle;       // degrees now
        public float speed;       // degrees per second
        // Its own pose under the swing, and the pose last set: a bone no animation sets each frame
        // gets its own pose back (the swing doesn't pile up on itself).
        public Quaternion baseLocal;
        public Quaternion setLocal;
        public bool applied;
    }

    private static readonly List<Sway> sways = new();

    // What can be struck, and how much of a blow it takes (the head nods back more than the chest).
    private static readonly (HumanBodyBones bone, float give)[] Struck =
    {
        (HumanBodyBones.Head, 1f), (HumanBodyBones.Neck, 0.6f), (HumanBodyBones.Chest, 0.35f), (HumanBodyBones.Spine, 0.3f),
        (HumanBodyBones.LeftUpperArm, 0.8f), (HumanBodyBones.RightUpperArm, 0.8f),
        (HumanBodyBones.LeftLowerArm, 1f), (HumanBodyBones.RightLowerArm, 1f),
        (HumanBodyBones.LeftHand, 1f), (HumanBodyBones.RightHand, 1f),
    };

    // A blow that landed on one of a character's own colliders (her head, her body): the bone it
    // sits on sways back, and the joint above it a little. Her hair, hanging from her head, swings
    // along by itself.
    public static void Flinch(Transform part, Vector3 from, Vector3 point, Vector3 aim)
    {
        // Only bones of her skeleton move (a collider on her root object, far from her model,
        // swung her whole body round it into the ceiling): the bone nearest to the line of the
        // blow (her arms have no colliders of their own; the blow lands on her body behind them).
        var bones = BonesOf(part);
        var bone = NearestBone(bones, part, from, aim, Vector3.Distance(from, point) + 0.3f);
        if (bone == null)
        {
            return;
        }

        var lever = point - bone.position;
        if (lever.sqrMagnitude < 0.0025f)
        {
            lever = (bone.parent != null ? bone.position - bone.parent.position : Vector3.up).normalized * 0.1f;
        }

        var axis = Vector3.Cross(lever, aim);
        if (axis.sqrMagnitude < 1e-6f)
        {
            axis = Vector3.Cross(Vector3.up, aim);
        }

        axis.Normalize();
        Kick(bone, axis, 420f);
        if (bone.parent != null && bones.Contains(bone.parent) && bones.Contains(bone.parent.parent))
        {
            Kick(bone.parent, axis, 160f);
        }

        Plugin.Say($"flinch: {bone.name} (hit {part.name})");
    }

    // The bones of the character `part` belongs to: what her skinned meshes are bound to.
    private static HashSet<Transform> BonesOf(Transform part)
    {
        var bones = new HashSet<Transform>();
        Transform root = null;
        foreach (var c in found)
        {
            if (c.root != null && part.IsChildOf(c.root))
            {
                root = c.root;
                break;
            }
        }

        if (root == null)
        {
            root = part.root;
        }

        foreach (var skin in root.GetComponentsInChildren<SkinnedMeshRenderer>())
        {
            foreach (var bone in skin.bones)
            {
                if (bone != null)
                {
                    bones.Add(bone);
                }
            }
        }

        return bones;
    }

    // The bone nearest to the line of the blow, up to where it landed; never the skeleton's root
    // (that moves her all) nor a finger.
    private static Transform NearestBone(HashSet<Transform> bones, Transform part, Vector3 from, Vector3 aim, float reach)
    {
        Transform best = null;
        float bestMiss = 0.3f;
        foreach (var bone in bones)
        {
            if (bone.parent == null || !bones.Contains(bone.parent) || IsFinger(bone.name))
            {
                continue;
            }

            float along = Vector3.Dot(bone.position - from, aim);
            if (along < 0f || along > reach)
            {
                continue;
            }

            float miss = Vector3.Distance(from + aim * along, bone.position);
            if (miss < bestMiss)
            {
                best = bone;
                bestMiss = miss;
            }
        }

        return best ?? (bones.Contains(part) && part.parent != null && bones.Contains(part.parent) ? part : null);
    }

    private static bool IsFinger(string name)
    {
        string n = name.ToLowerInvariant();
        return n.Contains("finger") || n.Contains("thumb") || n.Contains("index") || n.Contains("middle") || n.Contains("ring")
            || n.Contains("pinky") || n.Contains("little");
    }

    // A blow along `aim` from `from`: the nearest bone of a character within reach of the ray sways.
    // True when one was struck.
    public static bool Flinch(Vector3 from, Vector3 aim, float reach)
    {
        Transform best = null;
        float bestGive = 0f, bestMiss = 0.22f, bestAlong = 0f;
        foreach (var c in found)
        {
            if (c.root == null || !c.root.gameObject.activeInHierarchy)
            {
                continue;
            }

            var animator = c.mita != null && c.mita.animMita != null ? c.mita.animMita : c.root.GetComponentInChildren<Animator>();
            if (animator == null || !animator.isHuman)
            {
                continue;
            }

            foreach (var (which, give) in Struck)
            {
                var bone = animator.GetBoneTransform(which);
                if (bone == null)
                {
                    continue;
                }

                float along = Vector3.Dot(bone.position - from, aim);
                if (along < 0f || along > reach)
                {
                    continue;
                }

                float miss = Vector3.Distance(from + aim * along, bone.position);
                if (miss < bestMiss)
                {
                    best = bone;
                    bestGive = give;
                    bestMiss = miss;
                    bestAlong = along;
                }
            }
        }

        if (best == null)
        {
            return false;
        }

        // Swung away from the blow: about the axis across the blow and the bone's lever (where on it
        // the blow landed, from its joint).
        var landed = from + aim * bestAlong;
        var lever = landed - best.position;
        if (lever.sqrMagnitude < 0.0025f)
        {
            lever = best.parent != null ? best.position - best.parent.position : Vector3.up;
            lever = lever.normalized * 0.1f + Vector3.up * 0.05f;
        }

        var axis = Vector3.Cross(lever, aim);
        if (axis.sqrMagnitude < 1e-6f)
        {
            axis = Vector3.Cross(Vector3.up, aim);
        }

        axis.Normalize();
        Kick(best, axis, 420f * bestGive);
        // The joint above gives a little too: a nod of the head moves the neck.
        if (best.parent != null)
        {
            Kick(best.parent, axis, 160f * bestGive);
        }

        Hits.PushCloth(new Bounds(best.position, Vector3.one * 0.8f), aim * 6f + Vector3.up);
        Plugin.Say($"flinch: {best.name} of {best.root.name}");
        return true;
    }

    private static void Kick(Transform bone, Vector3 axis, float speed)
    {
        foreach (var sway in sways)
        {
            if (sway.bone == bone)
            {
                sway.axis = (sway.axis * Mathf.Abs(sway.angle) + axis * 10f).normalized;
                sway.speed += speed;
                return;
            }
        }

        sways.Add(new Sway { bone = bone, axis = axis, speed = speed });
    }

    // Once a frame: every swing's spring moves on (out quickly, a wobble, back), then it is laid on.
    private static int steppedFrame = -1;

    public static void LateUpdate()
    {
        if (steppedFrame != Time.frameCount)
        {
            steppedFrame = Time.frameCount;
            float dt = Mathf.Min(Time.deltaTime, 0.05f);
            for (int i = sways.Count - 1; i >= 0; i--)
            {
                var sway = sways[i];
                if (sway.bone == null)
                {
                    sways.RemoveAt(i);
                    continue;
                }

                sway.speed += (-160f * sway.angle - 14f * sway.speed) * dt;
                sway.angle = Mathf.Clamp(sway.angle + sway.speed * dt, -70f, 70f);
                if (Mathf.Abs(sway.angle) < 0.05f && Mathf.Abs(sway.speed) < 1f)
                {
                    sway.bone.localRotation = Pose(sway);
                    sways.RemoveAt(i);
                }
            }
        }

        ApplySways();
    }

    // The swings laid over the pose: again after anything that sets the pose late (her look at the
    // player, IK), so that doesn't undo them. As often as it runs, each lands once on the pose.
    public static void ApplySways()
    {
        ApplyCuts();
        foreach (var sway in sways)
        {
            if (sway.bone == null)
            {
                continue;
            }

            var pose = Pose(sway);
            sway.bone.localRotation = pose;
            sway.bone.rotation = Quaternion.AngleAxis(sway.angle, sway.axis) * sway.bone.rotation;
            sway.baseLocal = pose;
            sway.setLocal = sway.bone.localRotation;
            sway.applied = true;
        }
    }

    // The bone's own pose under the swing: unchanged since the swing was laid on (nothing set it
    // again), the pose it had then; otherwise what the animation or IK set just now.
    private static Quaternion Pose(Sway sway) =>
        sway.applied && sway.bone.localRotation == sway.setLocal ? sway.baseLocal : sway.bone.localRotation;

    // ---- cut in half by a sweep

    // When the player's client last made a sword sweep (kEvSweep): a blow right after is one.
    private static float sweptAt = -10f;

    public static void SweepMade()
    {
        sweptAt = Time.realtimeSinceStartup;
    }

    // The lowest bone of each cut character's spine: everything above it (her chest, arms, head and
    // hair) is folded away to nothing, so only her hips, skirt and legs are left, walking as before.
    private static readonly List<Transform> cut = new();

    // Where Steve's last blow went (Hits): which part of whom it was aimed at.
    private static Transform strikePart;
    private static Vector3 strikeFrom, strikeAim, strikePoint;
    private static float strikeAt = -10f;

    public static void RememberStrike(Transform part, Vector3 from, Vector3 aim, Vector3 point)
    {
        strikePart = part;
        strikeFrom = from;
        strikeAim = aim;
        strikePoint = point;
        strikeAt = Time.realtimeSinceStartup;
    }

    // A sweep takes off the part it was aimed at: an arm from the shoulder, a leg from the hip, the
    // head at the neck; anywhere else her upper body.
    private static void Cut(Character c)
    {
        if (strikePart != null && Time.realtimeSinceStartup - strikeAt < 1f && strikePart.IsChildOf(c.root))
        {
            var bones = BonesOf(c.root);
            var bone = NearestBone(bones, strikePart, strikeFrom, strikeAim, Vector3.Distance(strikeFrom, strikePoint) + 0.3f);
            var limb = bone != null ? LimbOf(bone, bones) : null;
            if (limb != null)
            {
                CutAt(c, limb);
                return;
            }
        }

        CutInHalf(c);
    }

    private static readonly string[] ArmWords = { "arm", "hand", "elbow", "shoulder", "clavicle" };
    private static readonly string[] LegWords = { "leg", "thigh", "knee", "calf", "shin", "foot", "toe" };
    private static readonly string[] HeadWords = { "head", "neck" };

    // The top bone of the arm, leg or head `bone` belongs to; null for the body itself.
    private static Transform LimbOf(Transform bone, HashSet<Transform> bones)
    {
        foreach (var words in new[] { ArmWords, LegWords, HeadWords })
        {
            if (!Has(bone.name, words))
            {
                continue;
            }

            var top = bone;
            while (top.parent != null && bones.Contains(top.parent) && Has(top.parent.name, words))
            {
                top = top.parent;
            }

            return top;
        }

        return null;
    }

    private static bool Has(string name, string[] words)
    {
        string n = name.ToLowerInvariant();
        foreach (var word in words)
        {
            if (n.Contains(word))
            {
                return true;
            }
        }

        return false;
    }

    private static void CutAt(Character c, Transform limb)
    {
        if (cut.Contains(limb))
        {
            return;
        }

        cut.Add(limb);
        Plugin.Say($"sweep: {c.name} loses {limb.name}");
    }

    private static void CutInHalf(Character c)
    {
        var bones = BonesOf(c.root);
        Transform spine = null;
        foreach (var bone in bones)
        {
            if (!bone.name.ToLowerInvariant().Contains("spine"))
            {
                continue;
            }

            // The first spine bone: the one whose parent isn't a spine bone.
            if (bone.parent == null || !bone.parent.name.ToLowerInvariant().Contains("spine"))
            {
                spine = bone;
                break;
            }
        }

        if (spine == null || cut.Contains(spine))
        {
            return;
        }

        cut.Add(spine);
        Plugin.Say($"sweep: {c.name} cut in half at {spine.name}");
    }

    private static void ApplyCuts()
    {
        for (int i = cut.Count - 1; i >= 0; i--)
        {
            if (cut[i] == null)
            {
                cut.RemoveAt(i);
                continue;
            }

            // Not quite nothing: a bone of size zero can't be turned back into anything.
            cut[i].localScale = Vector3.one * 0.0001f;
        }
    }

    // ---- Minecraft's tools on a character (right click on her)

    private const ushort InShear = 21;
    private const ushort InLeash = 20;
    private const ushort InWearPumpkin = 24;
    private const uint McHoldsShears = 1u << 20;
    private const uint McHoldsLead = 1u << 15;
    private const uint McHoldsPumpkin = 1u << 22;

    // The character Steve looks at within reach: on her colliders, or right in front of his eyes.
    private static Character Aimed(Transform camera, Transform player, float reach)
    {
        var hit = Hits.Aimed(camera, player, out var aimed);
        Character best = null;
        float bestAngle = 15f;
        foreach (var c in found)
        {
            if (c.root == null || !c.root.gameObject.activeInHierarchy)
            {
                continue;
            }

            if (aimed && hit.collider.transform.IsChildOf(c.root))
            {
                return c;
            }

            var seen = Bounds(c);
            var to = seen.center - camera.position;
            float angle = Vector3.Angle(camera.forward, to);
            if (to.magnitude < reach && angle < bestAngle)
            {
                best = c;
                bestAngle = angle;
            }
        }

        return best;
    }

    public static bool UseTool(Transform camera, Transform player, uint mcFlags)
    {
        ushort input = (mcFlags & McHoldsShears) != 0 ? InShear : (mcFlags & McHoldsLead) != 0 ? InLeash : (mcFlags & McHoldsPumpkin) != 0 ? InWearPumpkin : (ushort)0;
        if (input == 0)
        {
            return false;
        }

        var c = Aimed(camera, player, 3f);
        if (c == null)
        {
            return false;
        }

        uint id = (uint)c.root.GetInstanceID();
        Native.msc_push_input(input, (ushort)(input == InLeash ? 1 : 0), (int)id, 0, 0);
        Plugin.Say($"tool on {c.name}: {(input == InShear ? "shears" : input == InLeash ? "lead" : "something to wear")}");
        return true;
    }

    // ---- picking up a Chibi Mita (right click). True when one was picked up.

    public static bool PickUp(Transform camera, Transform player, bool holdsBucket)
    {
        var hit = Hits.Aimed(camera, player, out var aimed);
        Character chibi = null;
        foreach (var c in found)
        {
            if (c.root == null || !c.root.gameObject.activeInHierarchy || (!holdsBucket && !IsChibi(c)))
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
        sways.Clear();
        cut.Clear();
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
