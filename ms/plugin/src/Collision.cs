using System;
using System.Collections.Generic;
using System.Diagnostics;
using UnityEngine;

namespace MiSideCraft;

// MiSide's colliders around the player, sent to Minecraft as collision: Minecraft's own
// physics then walks Steve over MiSide's floors and into its walls.
//
// The world is felt out in regions of 8x8x8 blocks, the 3x3x3 regions around the player.
// Box colliders and meshes that can be read go over as exact triangles; everything else
// (spheres, capsules, meshes the game ships unreadable) is felt out with rays (Probe).
internal static class Collision
{
    private const int RegionBlocks = 8;
    private const int CellsPerBlock = 4;
    // Regions are felt out again after this long (doors open, furniture moves).
    private const float RescanSeconds = 4f;
    // Not more than this per frame: the rest waits for the next frame.
    private const double BudgetMs = 4.0;

    private static uint epoch = 1;
    private static readonly Dictionary<(int, int, int), float> scanned = new();
    private static readonly Dictionary<int, CachedMesh> meshes = new();
    private static int regionsSent;
    private static bool logNext;
    private static float lastLog;
    private static readonly System.Text.StringBuilder stats = new();
    private static readonly SortedDictionary<string, int> counts = new();

    // Names of what went over, logged once per scene and region.
    private static readonly HashSet<string> named = new();

    private static void Name(Collider collider)
    {
        if (logNext && named.Add(collider.gameObject.name + collider.bounds.center))
        {
            var b = collider.bounds;
            names.Append($" [{collider.gameObject.name} {LayerMask.LayerToName(collider.gameObject.layer)} {b.center} size {b.size}]");
        }
    }

    private static readonly System.Text.StringBuilder names = new();

    private static void Count(string what)
    {
        counts.TryGetValue(what, out int n);
        counts[what] = n + 1;
    }
    private static readonly HashSet<string> loggedUnreadable = new();

    public static uint Epoch => epoch;
    public static int RegionsSent => regionsSent;

    private sealed class CachedMesh
    {
        public Matrix4x4 matrix;
        public Vector3[] world;
        public int[] triangles;
    }

    // A new scene: everything Minecraft knows is thrown away.
    public static unsafe void Reset()
    {
        epoch++;
        scanned.Clear();
        doorRotations.Clear();
        doorsFound = -10f;
        meshes.Clear();
        uint value = epoch;
        Native.msc_write_collision(Proto.ColClear, &value, sizeof(uint), null, 0);
        Plugin.Say($"collision: cleared (epoch {epoch})");
    }

    // What the player bumps into: its capsule's layer in Unity's collision matrix, plus the
    // layers MiSide's own movement casts against (PlayerMove.castMove).
    private static int playerLayer;
    private static int castMove;

    public static void SetPlayer(PlayerMove move)
    {
        playerLayer = move.capsule != null ? move.capsule.gameObject.layer : move.gameObject.layer;
        castMove = move.castMove.value;
        Plugin.Say($"collision: player layer {LayerMask.LayerToName(playerLayer)}, castMove {castMove:X}");
    }

    private static bool Blocks(int layer)
    {
        return (castMove & (1 << layer)) != 0 || !Physics.GetIgnoreLayerCollision(playerLayer, layer);
    }

    // The regions a box of MiSide's (Unity) overlaps are felt out again at once: a door that
    // moved, something broken or put back.
    public static void Invalidate(Bounds bounds)
    {
        var a = Units.ToMc(bounds.min);
        var b = Units.ToMc(bounds.max);
        int x0 = FloorDiv((int)Math.Floor(Math.Min(a.x, b.x)), RegionBlocks), x1 = FloorDiv((int)Math.Floor(Math.Max(a.x, b.x)), RegionBlocks);
        int y0 = FloorDiv((int)Math.Floor(Math.Min(a.y, b.y)), RegionBlocks), y1 = FloorDiv((int)Math.Floor(Math.Max(a.y, b.y)), RegionBlocks);
        int z0 = FloorDiv((int)Math.Floor(Math.Min(a.z, b.z)), RegionBlocks), z1 = FloorDiv((int)Math.Floor(Math.Max(a.z, b.z)), RegionBlocks);
        for (int x = x0; x <= x1; x++)
        for (int y = y0; y <= y1; y++)
        for (int z = z0; z <= z1; z++)
        {
            scanned.Remove((x, y, z));
        }
    }

    // MiSide's doors that swung since the last look: their regions again.
    private static ObjectDoor[] doors = Array.Empty<ObjectDoor>();
    private static readonly Dictionary<int, Quaternion> doorRotations = new();
    private static float doorsFound = -10f;

    private static void WatchDoors(float now)
    {
        if (now - doorsFound > 2f)
        {
            doorsFound = now;
            doors = UnityEngine.Object.FindObjectsOfType<ObjectDoor>();
        }

        foreach (var door in doors)
        {
            if (door == null)
            {
                continue;
            }

            var t = door.rb != null ? door.rb.transform : door.transform;
            int id = door.GetInstanceID();
            if (doorRotations.TryGetValue(id, out var was) && Quaternion.Angle(was, t.rotation) > 2f)
            {
                var bounds = new Bounds(t.position, Vector3.one * 2.5f);
                Invalidate(bounds);
            }
            else if (doorRotations.ContainsKey(id))
            {
                continue;
            }

            doorRotations[id] = t.rotation;
        }
    }

    public static void Update(Vector3d feet, Transform player)
    {
        WatchDoors(Time.realtimeSinceStartup);

        var watch = Stopwatch.StartNew();
        int fx = FloorDiv((int)Math.Floor(feet.x), RegionBlocks);
        int fy = FloorDiv((int)Math.Floor(feet.y), RegionBlocks);
        int fz = FloorDiv((int)Math.Floor(feet.z), RegionBlocks);
        float now = Time.realtimeSinceStartup;

        // Nearest first: the region Steve stands in, then its neighbours. Two layers below:
        // Minecraft waits for what is a whole region under the feet before it lets go of Steve.
        foreach (var (dx, dy, dz) in Offsets)
        {
            var key = (fx + dx, fy + dy, fz + dz);
            if (scanned.TryGetValue(key, out var when) && now - when < RescanSeconds)
            {
                continue;
            }

            counts.Clear();
            if (dx == 0 && dy == 0 && dz == 0 && now - lastLog > 3f)
            {
                lastLog = now;
                logNext = true;
            }

            Scan(key, player);
            scanned[key] = now;

            if (watch.Elapsed.TotalMilliseconds > BudgetMs)
            {
                return;
            }
        }
    }

    private static readonly List<(int, int, int)> Offsets = MakeOffsets();

    private static List<(int, int, int)> MakeOffsets()
    {
        var list = new List<(int, int, int)>();
        for (int dx = -1; dx <= 1; dx++)
        for (int dy = -2; dy <= 1; dy++)
        for (int dz = -1; dz <= 1; dz++)
        {
            list.Add((dx, dy, dz));
        }

        list.Sort((a, b) => (a.Item1 * a.Item1 + a.Item2 * a.Item2 + a.Item3 * a.Item3)
            .CompareTo(b.Item1 * b.Item1 + b.Item2 * b.Item2 + b.Item3 * b.Item3));
        return list;
    }

    private static unsafe void Scan((int x, int y, int z) region, Transform player)
    {
        int minX = region.x * RegionBlocks, minY = region.y * RegionBlocks, minZ = region.z * RegionBlocks;
        int maxX = minX + RegionBlocks - 1, maxY = minY + RegionBlocks - 1, maxZ = minZ + RegionBlocks - 1;

        var centre = Units.FromMc(minX + RegionBlocks / 2.0, minY + RegionBlocks / 2.0, minZ + RegionBlocks / 2.0);
        float half = (float)(RegionBlocks / 2.0 / Units.BlocksPerMetre);
        var regionBounds = new Bounds(centre, Vector3.one * half * 2f);

        var tris = new List<Proto.ColTri>();
        var blocks = new Dictionary<(int, int, int), Proto.ColBlock>();
        var sampled = new List<Collider>();

        // The player's own colliders are out of the way while the world is felt out.
        var own = player.GetComponentsInChildren<Collider>();
        var ownEnabled = new bool[own.Length];
        for (int i = 0; i < own.Length; i++)
        {
            ownEnabled[i] = own[i].enabled;
            own[i].enabled = false;
        }

        try
        {
            var found = Physics.OverlapBox(centre, Vector3.one * half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore);
            stats.Clear();
            stats.Append($"found {found.Length}");

            foreach (var collider in found)
            {
                if (collider == null || collider.isTrigger || !collider.enabled)
                {
                    Count("trigger");
                    continue;
                }

                if (!Blocks(collider.gameObject.layer))
                {
                    Count($"layer {LayerMask.LayerToName(collider.gameObject.layer)}");
                    continue;
                }

                // Mita and the other characters aren't walls: they become stand-ins (Actors).
                if (Actors.IsCharacter(collider.transform))
                {
                    Count("character");
                    continue;
                }

                // Things that move by physics (cups, balls) aren't walls; doors are, even swinging.
                var body = collider.attachedRigidbody;
                if (body != null && !body.isKinematic && collider.GetComponentInParent<ObjectDoor>() == null)
                {
                    Count($"physics {collider.gameObject.name}");
                    continue;
                }

                var box = collider.TryCast<BoxCollider>();
                if (box != null)
                {
                    AddBox(box, tris);
                    Count("box");
                    Name(collider);
                    continue;
                }

                var meshCollider = collider.TryCast<MeshCollider>();
                if (meshCollider != null && AddMesh(meshCollider, regionBounds, tris))
                {
                    Count("mesh");
                    continue;
                }

                Count($"sampled {collider.GetIl2CppType().Name}");
                Name(collider);
                sampled.Add(collider);
            }

            foreach (var collider in sampled)
            {
                Probe(collider, regionBounds, tris);
            }
        }
        finally
        {
            for (int i = 0; i < own.Length; i++)
            {
                if (own[i] != null)
                {
                    own[i].enabled = ownEnabled[i];
                }
            }
        }

        var header = new Proto.ColRegion
        {
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            epoch = epoch,
        };

        header.count = (uint)tris.Count;
        var triArray = tris.ToArray();
        fixed (Proto.ColTri* body = triArray)
        {
            Native.msc_write_collision(Proto.ColTris, &header, (ulong)sizeof(Proto.ColRegion), body, (ulong)(triArray.Length * sizeof(Proto.ColTri)));
        }

        var blockArray = new Proto.ColBlock[blocks.Count];
        blocks.Values.CopyTo(blockArray, 0);
        header.count = (uint)blockArray.Length;
        fixed (Proto.ColBlock* body = blockArray)
        {
            Native.msc_write_collision(Proto.ColRegionMsg, &header, (ulong)sizeof(Proto.ColRegion), body, (ulong)(blockArray.Length * sizeof(Proto.ColBlock)));
        }

        regionsSent++;

        // What was found where the player stands, now and then: the first thing to look at
        // when Steve falls through something.
        if (logNext)
        {
            logNext = false;
            foreach (var pair in counts)
            {
                stats.Append($", {pair.Key} {pair.Value}");
            }

            Plugin.Say($"collision: region ({region.x}, {region.y}, {region.z}): {stats}; sent {tris.Count} triangles, {blocks.Count} blocks{names}");
            names.Clear();
        }
    }

    private static void AddBox(BoxCollider box, List<Proto.ColTri> tris)
    {
        var t = box.transform;
        var c = box.center;
        var e = box.size * 0.5f;
        var corners = new Vector3[8];
        for (int i = 0; i < 8; i++)
        {
            var local = new Vector3(
                c.x + ((i & 1) != 0 ? e.x : -e.x),
                c.y + ((i & 2) != 0 ? e.y : -e.y),
                c.z + ((i & 4) != 0 ? e.z : -e.z));
            corners[i] = t.TransformPoint(local);
        }

        // Two triangles for each of the six faces.
        int[] faces =
        {
            0, 2, 3, 0, 3, 1, // -z
            4, 5, 7, 4, 7, 6, // +z
            0, 1, 5, 0, 5, 4, // -y
            2, 6, 7, 2, 7, 3, // +y
            0, 4, 6, 0, 6, 2, // -x
            1, 3, 7, 1, 7, 5, // +x
        };

        for (int i = 0; i < faces.Length; i += 3)
        {
            tris.Add(Tri(corners[faces[i]], corners[faces[i + 1]], corners[faces[i + 2]]));
        }
    }

    // False when the mesh can't be read: then it is sampled instead.
    private static bool AddMesh(MeshCollider collider, Bounds region, List<Proto.ColTri> tris)
    {
        var mesh = collider.sharedMesh;
        if (mesh == null)
        {
            return true;
        }

        if (!mesh.isReadable)
        {
            if (loggedUnreadable.Add(mesh.name))
            {
                Plugin.Say($"collision: mesh '{mesh.name}' ({collider.gameObject.name}) can't be read, sampled instead");
            }
            return false;
        }

        int id = collider.GetInstanceID();
        var matrix = collider.transform.localToWorldMatrix;
        if (!meshes.TryGetValue(id, out var cached) || cached.matrix != matrix)
        {
            var vertices = mesh.vertices;
            var world = new Vector3[vertices.Length];
            for (int i = 0; i < world.Length; i++)
            {
                world[i] = matrix.MultiplyPoint3x4(vertices[i]);
            }

            cached = new CachedMesh { matrix = matrix, world = world, triangles = mesh.triangles };
            meshes[id] = cached;
        }

        var w = cached.world;
        var idx = cached.triangles;
        for (int i = 0; i + 2 < idx.Length; i += 3)
        {
            var a = w[idx[i]];
            var b = w[idx[i + 1]];
            var c = w[idx[i + 2]];
            var bounds = new Bounds(a, Vector3.zero);
            bounds.Encapsulate(b);
            bounds.Encapsulate(c);
            if (bounds.Intersects(region))
            {
                tris.Add(Tri(a, b, c));
            }
        }

        return true;
    }

    // A collider whose triangles can't be read (MiSide ships its rooms' meshes unreadable):
    // felt out with rays from all six sides, on a grid of a quarter metre. Every hit is a
    // small square on the surface at exactly the hit's depth, and squares in a row at the
    // same depth become one strip. Floors come out exactly as high as MiSide's.
    private const float ProbeStep = 0.25f;

    private static void Probe(Collider collider, Bounds region, List<Proto.ColTri> tris)
    {
        var bounds = collider.bounds;
        var min = Vector3.Max(bounds.min, region.min);
        var max = Vector3.Min(bounds.max, region.max);
        if (min.x > max.x || min.y > max.y || min.z > max.z)
        {
            return;
        }

        for (int axis = 0; axis < 3; axis++)
        {
            // The two other axes span the grid the rays start from.
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            for (int sign = -1; sign <= 1; sign += 2)
            {
                ProbeSide(collider, axis, u, v, sign, min, max, tris);
            }
        }
    }

    private static void ProbeSide(Collider collider, int axis, int u, int v, int sign, Vector3 min, Vector3 max, List<Proto.ColTri> tris)
    {
        var direction = Vector3.zero;
        direction[axis] = sign;
        float start = sign < 0 ? max[axis] + 0.05f : min[axis] - 0.05f;
        float length = max[axis] - min[axis] + 0.1f;

        // On the grid of the whole world, so neighbouring regions line up.
        int u0 = Mathf.FloorToInt(min[u] / ProbeStep), u1 = Mathf.FloorToInt(max[u] / ProbeStep);
        int v0 = Mathf.FloorToInt(min[v] / ProbeStep), v1 = Mathf.FloorToInt(max[v] / ProbeStep);
        var hits = new List<float>(4);

        for (int j = v0; j <= v1; j++)
        {
            // The strip being built along u: its depth and where it started.
            float stripDepth = float.NaN;
            int stripStart = 0;

            for (int i = u0; i <= u1 + 1; i++)
            {
                float depth = float.NaN;
                if (i <= u1)
                {
                    var origin = Vector3.zero;
                    origin[axis] = start;
                    origin[u] = (i + 0.5f) * ProbeStep;
                    origin[v] = (j + 0.5f) * ProbeStep;
                    // The first surface facing the ray; the faces behind it come from the other side's rays.
                    if (collider.Raycast(new Ray(origin, direction), out var hit, length) && Vector3.Dot(hit.normal, direction) < -0.2f)
                    {
                        depth = hit.point[axis];
                    }
                }

                bool same = !float.IsNaN(depth) && !float.IsNaN(stripDepth) && Mathf.Abs(depth - stripDepth) < 0.004f;
                if (same)
                {
                    continue;
                }

                if (!float.IsNaN(stripDepth))
                {
                    AddStrip(axis, u, v, sign, stripDepth, stripStart * ProbeStep, i * ProbeStep, j * ProbeStep, (j + 1) * ProbeStep, tris);
                }

                stripDepth = depth;
                stripStart = i;
            }
        }
    }

    // A rectangle on the plane `axis` = depth, from (u0, v0) to (u1, v1).
    private static void AddStrip(int axis, int u, int v, int sign, float depth, float u0, float u1, float v0, float v1, List<Proto.ColTri> tris)
    {
        Vector3 Corner(float a, float b)
        {
            var p = Vector3.zero;
            p[axis] = depth;
            p[u] = a;
            p[v] = b;
            return p;
        }

        var c00 = Corner(u0, v0);
        var c10 = Corner(u1, v0);
        var c11 = Corner(u1, v1);
        var c01 = Corner(u0, v1);
        // Wound to face the side the ray came from.
        if (sign < 0)
        {
            tris.Add(Tri(c00, c11, c10));
            tris.Add(Tri(c00, c01, c11));
        }
        else
        {
            tris.Add(Tri(c00, c10, c11));
            tris.Add(Tri(c00, c11, c01));
        }
    }

    private static unsafe Proto.ColTri Tri(Vector3 a, Vector3 b, Vector3 c)
    {
        var tri = new Proto.ColTri();
        var pa = Units.ToMc(a);
        var pb = Units.ToMc(b);
        var pc = Units.ToMc(c);
        tri.v[0] = (float)pa.x; tri.v[1] = (float)pa.y; tri.v[2] = (float)pa.z;
        tri.v[3] = (float)pb.x; tri.v[4] = (float)pb.y; tri.v[5] = (float)pb.z;
        tri.v[6] = (float)pc.x; tri.v[7] = (float)pc.y; tri.v[8] = (float)pc.z;
        return tri;
    }

    private static int FloorDiv(int value, int by) => (int)Math.Floor(value / (double)by);
}
