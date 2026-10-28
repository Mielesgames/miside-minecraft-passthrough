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
    private const float BallMass = 3f;
    private const float ClothPush = 4f;

    private const ushort InBreakEffect = 10;
    private const ushort InTokenName = 11;
    private const ushort InDropToken = 12;
    private const ushort InUseToken = 13;

    // ---- left click

    public static void Attack(Transform camera, Transform player, bool creative)
    {
        if (!Native.msc_take_attack_click() || camera == null || Native.msc_minecraft_targets_block())
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

        if (!Settings.Physics)
        {
            return;
        }

        Fire(camera, player, aim);
        var point = found ? hit.point : camera.position + aim * 2f;
        if (!found || !Actors.IsCharacter(hit.collider.transform))
        {
            PushCloth(point, 1.2f, aim * ClothPush);
        }
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
        foreach (var cloth in Cloths())
        {
            if (cloth != null && cloth.isActiveAndEnabled && Vector3.Distance(cloth.transform.position, point) <= radius)
            {
                cloth.AddForce(velocity, PhysicsManagerTeamData.ForceMode.VelocityAdd);
            }
        }
    }

    // A character's own hair and clothes all get `velocity` added.
    public static void PushCloth(Transform character, Vector3 velocity)
    {
        foreach (var cloth in character.GetComponentsInChildren<BaseCloth>())
        {
            if (cloth.isActiveAndEnabled)
            {
                cloth.AddForce(velocity, PhysicsManagerTeamData.ForceMode.VelocityAdd);
            }
        }
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

    public static bool Use(Transform camera, Transform player, uint heldToken, bool holdsBucket)
    {
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
