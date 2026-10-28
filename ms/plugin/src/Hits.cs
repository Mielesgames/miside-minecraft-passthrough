using System;
using System.Collections.Generic;
using MagicaCloth;
using UnityEngine;

namespace MiSideCraft;

// Steve's left and right clicks on MiSide's world (not on its characters: Minecraft hits their
// stand-ins, and Actors gets that as an event).
//
// Physics: every hit fires an invisible ball from Steve's eyes the way he looks. It is a real
// rigidbody, so whatever MiSide simulates (doors on their hinges, things lying around) is struck
// by it as if by the hit itself. Hair and clothes (MagicaCloth, not Unity's physics) near where
// the hit lands are given a push of their own.
//
// Doors the Minecraft way (/miside doors minecraft): right-click swings one open or shut and it
// stays there; left-click breaks it (with block breaking on, or in creative), and it drops a paper
// that puts it back.
internal static class Hits
{
    private const float Reach = 4f;
    private const float BallSpeed = 18f;
    private const float BallMass = 12f;
    private const float ClothPush = 7f;
    // The blow on what was struck: at least this (N·s), and enough for this speed (m/s).
    private const float Impulse = 6f;
    private const float Speed = 2f;

    private const ushort InBreakEffect = 10;
    private const ushort InTokenName = 11;
    private const ushort InDropToken = 12;
    private const ushort InUseToken = 13;
    private const ushort InHitSound = 36;

    // ---- left click

    public static void Attack(Transform camera, Transform player, bool creative)
    {
        if (!Native.msc_take_attack_click() || camera == null || Native.msc_minecraft_targets_block() || Possession.Active)
        {
            return;
        }

        var aim = camera.forward;
        var hit = Aimed(camera, player, out var found);
        var door = found ? hit.collider.GetComponentInParent<ObjectDoor>() : null;
        if (door != null && Settings.MinecraftDoors)
        {
            Doors.Hit(door, hit.point, creative);
            return;
        }

        // Aimed at a character: the part that is hit sways back (Minecraft hits her stand-in too).
        if (Settings.Reactions && found && Actors.IsCharacter(hit.collider.transform))
        {
            Actors.RememberStrike(hit.collider.transform, camera.position, aim, hit.point);
            Actors.Flinch(hit.collider.transform, camera.position, hit.point, aim);
            return;
        }

        if (!Settings.Physics)
        {
            return;
        }

        Fire(camera, player, aim);
        var point = found ? hit.point : camera.position + aim * 2f;

        // A small thing that never moved (a book, a cup on the table) becomes a physics object
        // first: then the blow knocks it over or away like anything else.
        var loose = found && hit.rigidbody == null ? MakePhysical(hit.collider.gameObject, hit.collider) : null;
        if (loose == null && (!found || hit.rigidbody == null))
        {
            loose = MakePhysicalAlong(camera.position, aim, found ? hit.distance : Reach, player, out var at);
            if (loose != null)
            {
                hit = default;
                found = Physics.Raycast(camera.position, aim, out hit, Reach, ~0, QueryTriggerInteraction.Ignore) && hit.rigidbody == loose;
                point = found ? hit.point : at;
            }
        }

        // What was struck gets the blow at once, where it landed (the ball can miss small things).
        var struck = loose != null ? loose : found ? hit.rigidbody : null;
        if (door != null || (struck != null && !struck.isKinematic))
        {
            Sound(found ? hit.point : point, door != null ? 0 : struck.mass < 0.5f ? 2 : 1);
        }

        if (struck != null && !struck.isKinematic)
        {
            struck.WakeUp();
            // Heavy things (a door) move about as much as light ones (a cup): the blow grows with
            // the mass, to about Speed metres per second where it lands.
            struck.AddForceAtPosition(aim * Mathf.Max(Impulse, struck.mass * Speed), found ? hit.point : point, ForceMode.Impulse);
        }

        Plugin.Say(found
            ? $"hit: {hit.collider.gameObject.name} at {hit.distance:F1} m, {(struck == null ? "no rigidbody" : struck.isKinematic ? "kinematic (not physics)" : $"pushed (mass {struck.mass:F1})")}"
            : "hit: nothing within reach");
        if (!found || !Actors.IsCharacter(hit.collider.transform))
        {
            PushCloth(point, 1.2f, aim * ClothPush);
        }
    }

    // ---- small still things into physics objects

    // Bigger than this (metres, longest side) stays put: furniture, walls, floors.
    private const float LooseSize = 0.45f;

    // A rigidbody on a small thing of MiSide's that had none (and the collider to go with it), so
    // it can be knocked over. Not the things the story uses (what can be clicked), not characters,
    // not what Unity baked together with its surroundings (it can't move on its own). Null when not.
    private static Rigidbody MakePhysical(GameObject thing, Collider collider)
    {
        var renderer = thing.GetComponent<MeshRenderer>();
        if (renderer == null && collider != null)
        {
            renderer = collider.GetComponentInParent<MeshRenderer>();
            thing = renderer != null ? renderer.gameObject : thing;
        }

        if (renderer == null || !renderer.enabled || renderer.isPartOfStaticBatch)
        {
            return null;
        }

        var size = renderer.bounds.size;
        if (Mathf.Max(size.x, Mathf.Max(size.y, size.z)) > LooseSize || thing.GetComponentInParent<ObjectInteractive>() != null
            || thing.GetComponentInChildren<ObjectInteractive>() != null || Actors.IsCharacter(thing.transform)
            || thing.GetComponentInParent<ObjectDoor>() != null || thing.GetComponentInParent<Rigidbody>() != null)
        {
            return null;
        }

        if (collider == null || collider.gameObject != thing)
        {
            collider = thing.GetComponent<Collider>();
        }

        if (collider == null)
        {
            // Fitted to its mesh's bounds (the mesh itself can't be read).
            collider = thing.AddComponent<BoxCollider>();
        }

        var meshCollider = collider.TryCast<MeshCollider>();
        if (meshCollider != null && !meshCollider.convex)
        {
            // A mesh collider can only move as a convex one, which an unreadable mesh can't be.
            meshCollider.enabled = false;
            collider = thing.AddComponent<BoxCollider>();
        }

        var body = thing.AddComponent<Rigidbody>();
        body.mass = Mathf.Clamp(size.x * size.y * size.z * 400f, 0.1f, 4f);
        body.collisionDetectionMode = CollisionDetectionMode.ContinuousDynamic;
        Plugin.Say($"loose: {thing.name} is a physics object now ({size.x:F2} x {size.y:F2} x {size.z:F2} m, {body.mass:F2} kg)");
        return body;
    }

    // Nothing with a collider struck: a small thing whose drawn shape lies on the ray, before
    // `until` (what was struck behind it), is made physical. Its nearest point on the ray: `at`.
    private static Rigidbody MakePhysicalAlong(Vector3 from, Vector3 aim, float until, Transform player, out Vector3 at)
    {
        at = Vector3.zero;
        var ray = new Ray(from, aim);
        MeshRenderer best = null;
        float bestDistance = until;
        foreach (var renderer in Renderers())
        {
            if (renderer == null || !renderer.enabled || renderer.isPartOfStaticBatch || renderer.transform.IsChildOf(player))
            {
                continue;
            }

            var bounds = renderer.bounds;
            var size = bounds.size;
            if (Mathf.Max(size.x, Mathf.Max(size.y, size.z)) > LooseSize || !bounds.IntersectRay(ray, out float distance) || distance >= bestDistance)
            {
                continue;
            }

            best = renderer;
            bestDistance = distance;
        }

        if (best == null)
        {
            return null;
        }

        at = from + aim * bestDistance;
        return MakePhysical(best.gameObject, null);
    }

    private static MeshRenderer[] renderers = Array.Empty<MeshRenderer>();
    private static float renderersFound = -10f;

    private static MeshRenderer[] Renderers()
    {
        if (Time.realtimeSinceStartup - renderersFound > 3f)
        {
            renderersFound = Time.realtimeSinceStartup;
            renderers = UnityEngine.Object.FindObjectsOfType<MeshRenderer>();
        }

        return renderers;
    }

    // The invisible ball: struck things move as physics says.
    private static void Fire(Transform camera, Transform player, Vector3 aim)
    {
        var ball = new GameObject("MiSideCraft hit");
        ball.hideFlags = HideFlags.HideAndDontSave;
        ball.transform.position = camera.position + aim * 0.3f;
        var sphere = ball.AddComponent<SphereCollider>();
        sphere.radius = 0.12f;
        foreach (var own in player.GetComponentsInChildren<Collider>())
        {
            Physics.IgnoreCollision(sphere, own);
        }

        var body = ball.AddComponent<Rigidbody>();
        body.mass = BallMass;
        body.useGravity = false;
        body.collisionDetectionMode = CollisionDetectionMode.ContinuousDynamic;
        body.velocity = aim * BallSpeed;
        UnityEngine.Object.Destroy(ball, Reach / BallSpeed + 0.1f);
    }

    // Hair and clothes within `radius` of `point` get `velocity` added.
    public static void PushCloth(Vector3 point, float radius, Vector3 velocity)
    {
        PushCloth(new Bounds(point, Vector3.one * radius * 2f), velocity);
    }

    // Hair and clothes (MagicaCloth: its own simulation, which Unity's collisions don't reach)
    // that hang inside `area` get `velocity` added. Where one hangs is its root bones: the
    // component itself often sits on the character's root, far from the hair.
    public static int PushCloth(Bounds area, Vector3 velocity)
    {
        int pushed = 0;
        foreach (var cloth in Cloths())
        {
            if (cloth == null || !cloth.isActiveAndEnabled || !area.Contains(ClothAt(cloth)))
            {
                continue;
            }

            cloth.AddForce(velocity, PhysicsManagerTeamData.ForceMode.VelocityAdd);
            pushed++;
        }

        return pushed;
    }

    private static Vector3 ClothAt(BaseCloth cloth)
    {
        var bone = cloth.TryCast<MagicaBoneCloth>();
        if (bone != null && bone.ClothTarget != null && bone.ClothTarget.RootCount > 0 && bone.ClothTarget.GetRoot(0) != null)
        {
            return bone.ClothTarget.GetRoot(0).position;
        }

        var spring = cloth.TryCast<MagicaBoneSpring>();
        if (spring != null && spring.ClothTarget != null && spring.ClothTarget.RootCount > 0 && spring.ClothTarget.GetRoot(0) != null)
        {
            return spring.ClothTarget.GetRoot(0).position;
        }

        return cloth.transform.position;
    }

    // All of MiSide's hair and clothes, where they hang: once per scene, to see what there is.
    private static bool dumped;

    public static void DumpCloths()
    {
        if (dumped)
        {
            return;
        }

        dumped = true;
        foreach (var cloth in Cloths())
        {
            if (cloth != null)
            {
                Plugin.Say($"cloth: {cloth.GetIl2CppType().Name} '{cloth.gameObject.name}' at {ClothAt(cloth)} (object at {cloth.transform.position}), active {cloth.isActiveAndEnabled}");
            }
        }
    }

    public static void ForgetCloths()
    {
        dumped = false;
        clothsFound = -10f;
    }

    private static BaseCloth[] cloths = Array.Empty<BaseCloth>();
    private static float clothsFound = -10f;

    private static BaseCloth[] Cloths()
    {
        if (Time.realtimeSinceStartup - clothsFound > 2f)
        {
            clothsFound = Time.realtimeSinceStartup;
            cloths = UnityEngine.Object.FindObjectsOfType<BaseCloth>();
        }

        return cloths;
    }

    // What Steve looks at within reach, not counting his own body or MiSide's triggers.
    public static RaycastHit Aimed(Transform camera, Transform player, out bool found)
    {
        found = false;
        var best = default(RaycastHit);
        foreach (var hit in Physics.RaycastAll(camera.position, camera.forward, Reach, ~0, QueryTriggerInteraction.Ignore))
        {
            if (hit.collider.transform.IsChildOf(player) || (found && hit.distance >= best.distance))
            {
                continue;
            }

            best = hit;
            found = true;
        }

        return best;
    }

    // ---- right click: true when it was used here

    public static bool Use(Transform camera, Transform player, uint heldToken, bool holdsBucket, uint mcFlags = 0)
    {
        // An empty hand on a sofa, a chair, a bed: Steve sits down on it.
        if (camera != null && (mcFlags & Proto.McHandEmpty) != 0 && Seats.SitOn(camera, player))
        {
            return true;
        }

        // Minecraft's tools on a character (her stand-in): shears, a lead, a pumpkin to wear.
        if (camera != null && Actors.UseTool(camera, player, mcFlags))
        {
            return true;
        }

        // A paper, egg or bucket of something of MiSide's in hand: it goes back.
        if (heldToken != 0 && (Doors.Restore(heldToken) || Actors.PutDown(heldToken, camera, player)))
        {
            Native.msc_push_input(InUseToken, (ushort)heldToken, 0, 0, 0);
            return true;
        }

        if (camera != null && Actors.PickUp(camera, player, holdsBucket))
        {
            return true;
        }

        if (!Settings.MinecraftDoors || camera == null)
        {
            return false;
        }

        var hit = Aimed(camera, player, out var found);
        var door = found ? hit.collider.GetComponentInParent<ObjectDoor>() : null;
        return door != null && Doors.Toggle(door, player.position);
    }

    // ---- telling Minecraft

    // The sound of a blow (kInHitSound): 0 wood (a door), 1 something hard, 2 something soft.
    public static void Sound(Vector3 at, int material)
    {
        var mc = Units.ToMc(at);
        Native.msc_push_input(InHitSound, (ushort)material, (int)(mc.x * 100), (int)(mc.y * 100), (int)(mc.z * 100));
    }

    public static void BreakEffect(Vector3 at)
    {
        var mc = Units.ToMc(at);
        Native.msc_push_input(InBreakEffect, 0, (int)(mc.x * 100), (int)(mc.y * 100), (int)(mc.z * 100));
    }

    // A paper named `name` that puts token `id` back, dropped at `at`.
    public static void DropToken(uint id, string name, Vector3 at)
    {
        for (int i = 0; i < name.Length; i += 12)
        {
            Native.msc_push_input(InTokenName, (ushort)id, Pack(name, i), Pack(name, i + 4), Pack(name, i + 8));
        }

        var mc = Units.ToMc(at);
        Native.msc_push_input(InDropToken, (ushort)id, (int)(mc.x * 100), (int)(mc.y * 100), (int)(mc.z * 100));
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
}

// MiSide's doors (ObjectDoor: a rigidbody on a hinge) the Minecraft way.
internal static class Doors
{
    private sealed class Held
    {
        public ObjectDoor door;
        public Vector3 closedPosition;
        public Quaternion closedRotation;
        public bool wasKinematic;
        public bool open;
    }

    private sealed class Broken
    {
        public string name;
        public List<Renderer> renderers = new();
        public List<Collider> colliders = new();
        public Bounds bounds;
    }

    private static readonly Dictionary<int, Held> held = new();
    private static readonly Dictionary<uint, Broken> broken = new();
    private static readonly Dictionary<int, (int hits, float last)> damage = new();
    private static uint nextToken = 1;

    // Survival: this many hits, each within this long of the last, break a door.
    private const int HitsToBreak = 3;
    private const float HitWindow = 2f;

    public static bool Toggle(ObjectDoor door, Vector3 player)
    {
        var body = door.rb != null ? door.rb : door.GetComponent<Rigidbody>();
        var t = body != null ? body.transform : door.transform;
        int id = door.GetInstanceID();
        if (!held.TryGetValue(id, out var state))
        {
            state = new Held { door = door, closedPosition = t.position, closedRotation = t.rotation, wasKinematic = body != null && body.isKinematic };
            held[id] = state;
        }

        if (body != null)
        {
            body.isKinematic = true;
        }

        t.SetPositionAndRotation(state.closedPosition, state.closedRotation);
        state.open = !state.open;
        if (state.open)
        {
            // Around its hinge, away from Steve.
            Vector3 anchor = t.position, axis = Vector3.up;
            var hinge = door.hj;
            if (hinge != null)
            {
                anchor = hinge.transform.TransformPoint(hinge.anchor);
                axis = hinge.transform.TransformDirection(hinge.axis).normalized;
            }

            var centre = Centre(door);
            float angle = 90f;
            var opened = Quaternion.AngleAxis(angle, axis) * (centre - anchor) + anchor;
            var other = Quaternion.AngleAxis(-angle, axis) * (centre - anchor) + anchor;
            if (Vector3.Distance(other, player) > Vector3.Distance(opened, player))
            {
                angle = -angle;
            }

            t.RotateAround(anchor, axis, angle);
        }

        Collision.Invalidate(Bounds(door));
        Plugin.Say($"door {door.gameObject.name} {(state.open ? "opened" : "closed")} the Minecraft way");
        return true;
    }

    public static void Hit(ObjectDoor door, Vector3 point, bool creative)
    {
        if (!creative && !Settings.BlockBreaking)
        {
            return;
        }

        int id = door.GetInstanceID();
        float now = Time.realtimeSinceStartup;
        damage.TryGetValue(id, out var d);
        int hits = now - d.last <= HitWindow ? d.hits + 1 : 1;
        damage[id] = (hits, now);
        Hits.Sound(point, 0);
        Hits.BreakEffect(point);
        if (!creative && hits < HitsToBreak)
        {
            return;
        }

        damage.Remove(id);
        var gone = new Broken { name = Pretty(door.gameObject.name), bounds = Bounds(door) };
        foreach (var renderer in door.GetComponentsInChildren<Renderer>())
        {
            if (renderer.enabled)
            {
                renderer.enabled = false;
                gone.renderers.Add(renderer);
            }
        }

        foreach (var collider in door.GetComponentsInChildren<Collider>())
        {
            if (collider.enabled)
            {
                collider.enabled = false;
                gone.colliders.Add(collider);
            }
        }

        uint token = 0x4000 + nextToken++;
        broken[token] = gone;
        Hits.DropToken(token, gone.name, gone.bounds.center);
        Collision.Invalidate(gone.bounds);
        Plugin.Say($"door {door.gameObject.name} broken (token {token})");
    }

    public static bool Restore(uint token)
    {
        if (!broken.Remove(token, out var gone))
        {
            return false;
        }

        foreach (var renderer in gone.renderers)
        {
            if (renderer != null)
            {
                renderer.enabled = true;
            }
        }

        foreach (var collider in gone.colliders)
        {
            if (collider != null)
            {
                collider.enabled = true;
            }
        }

        Collision.Invalidate(gone.bounds);
        Plugin.Say($"{gone.name} put back");
        return true;
    }

    // Back to MiSide's own physics once doors aren't Minecraft's any more (or a new scene).
    public static void Update()
    {
        if (Settings.MinecraftDoors || held.Count == 0)
        {
            return;
        }

        foreach (var state in held.Values)
        {
            if (state.door == null)
            {
                continue;
            }

            var body = state.door.rb != null ? state.door.rb : state.door.GetComponent<Rigidbody>();
            if (body != null)
            {
                body.isKinematic = state.wasKinematic;
            }
        }

        held.Clear();
    }

    public static void Forget()
    {
        held.Clear();
        broken.Clear();
        damage.Clear();
    }

    private static Vector3 Centre(ObjectDoor door) => Bounds(door).center;

    private static Bounds Bounds(ObjectDoor door)
    {
        var bounds = new Bounds(door.transform.position, Vector3.zero);
        foreach (var renderer in door.GetComponentsInChildren<Renderer>(true))
        {
            bounds.Encapsulate(renderer.bounds);
        }

        return bounds;
    }

    // "RoomDoor Main-Bedroom" -> "Door (Main-Bedroom)".
    private static string Pretty(string name)
    {
        int space = name.IndexOf(' ');
        return space > 0 ? $"Door ({name.Substring(space + 1)})" : "Door";
    }
}

// Sitting on MiSide's furniture the Minecraft way: an invisible seat (Minecraft's hiding seat, an
// armour stand) on the furniture's seat, which Steve rides; sneaking gets him off, and the seat
// goes again.
internal static class Seats
{
    private const ushort InHide = 15;
    private static readonly string[] Furniture = { "sofa", "couch", "chair", "stool", "bench", "bed", "seat", "armchair", "pouf", "tumb" };
    private static bool seated;

    public static bool SitOn(Transform camera, Transform player)
    {
        var hit = Hits.Aimed(camera, player, out var found);
        if (!found || !IsSeat(hit.collider.transform))
        {
            return false;
        }

        // The top of the seat under where Steve looks: a ray straight down onto it.
        var above = new Vector3(hit.point.x, hit.collider.bounds.max.y + 0.2f, hit.point.z);
        float seatY = hit.point.y;
        foreach (var down in Physics.RaycastAll(above, Vector3.down, hit.collider.bounds.size.y + 0.4f, ~0, QueryTriggerInteraction.Ignore))
        {
            if (down.collider == hit.collider && down.normal.y > 0.5f)
            {
                seatY = Mathf.Max(seatY, down.point.y);
            }
        }

        var seat = Units.ToMc(new Vector3(hit.point.x, seatY, hit.point.z));
        Native.msc_push_input(InHide, 1, (int)(seat.x * 100), (int)(seat.y * 100), (int)(seat.z * 100));
        seated = true;
        Plugin.Say($"seat: Steve sits on {hit.collider.gameObject.name}");
        return true;
    }

    // Got off (sneaked off the seat): it goes.
    public static void Update(uint mcFlags)
    {
        if (seated && (mcFlags & Proto.McHiding) == 0 && settled++ > 20)
        {
            seated = false;
            settled = 0;
            Native.msc_push_input(InHide, 0, 0, 0, 0);
        }
        else if (!seated || (mcFlags & Proto.McHiding) != 0)
        {
            settled = 0;
        }
    }

    private static int settled;

    private static bool IsSeat(Transform t)
    {
        for (var at = t; at != null; at = at.parent)
        {
            string name = at.name.ToLowerInvariant();
            foreach (var word in Furniture)
            {
                if (name.Contains(word))
                {
                    return true;
                }
            }
        }

        return false;
    }
}
