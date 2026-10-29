using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Il2CppInterop.Runtime;
using UnityEngine;
using UnityEngine.Rendering;

namespace MiSideCraft;

// Minecraft's blocks drawn inside MiSide's own rendering instead of over the finished picture: a
// command buffer on its main camera, after the solid things and before the see-through ones. The
// native half draws there with this frame's camera matrix against MiSide's own depth buffer, so
// walls hide blocks exactly and the dialogue's letters, glass and effects go over them.
//
// The camera of each frame travels with that frame's command (a slot of its own): MiSide renders
// on another thread, a frame behind the game thread.
//
// Mirrors: a camera of MiSide's that renders into a texture (a mirror's reflection) gets the blocks
// too, and Steve's body, which is the player's reflection. Their matrices are only set right before
// they render, so theirs are taken then (Camera.onPreRender).
internal static class SceneRenderer
{
    private const int Slots = 16;
    // world::SceneView: double position[3], float to_clip[16], int draw_avatar, int pad.
    private const int SlotBytes = 3 * 8 + 16 * 4 + 8;

    private sealed class Attached
    {
        public Camera camera;
        public CommandBuffer colour;
        public CommandBuffer depth;
        public bool mirror;
    }

    private static readonly Dictionary<int, Attached> cameras = new();
    private static IntPtr slots;
    private static IntPtr callback;
    private static int next;
    private static bool hooked;
    private static float mirrorsFound = -10f;
    private static int mainId;
    private static readonly HashSet<int> seen = new();

    // The main camera, every frame from the game thread; `avatar`: Steve's body seen (F5).
    public static void Frame(Camera camera, bool avatar)
    {
        if (camera == null)
        {
            return;
        }

        if (slots == IntPtr.Zero)
        {
            slots = Marshal.AllocHGlobal(Slots * SlotBytes);
            callback = Native.msc_scene_event();
        }

        var main = Attach(camera, false);
        mainId = camera.GetInstanceID();
        Record(main, avatar ? 1 : 0);
        FindMirrors(camera);
        Hook();
    }

    private static Attached Attach(Camera camera, bool mirror)
    {
        int id = camera.GetInstanceID();
        if (cameras.TryGetValue(id, out var attached) && attached.camera != null)
        {
            return attached;
        }

        attached = new Attached
        {
            camera = camera,
            mirror = mirror,
            colour = new CommandBuffer { name = "MiSideCraft blocks" },
        };
        camera.AddCommandBuffer(CameraEvent.BeforeForwardAlpha, attached.colour);
        if (!mirror)
        {
            // The same blocks, depth only, into MiSide's depth texture: what it draws by that
            // texture (the outlines of things) then knows the blocks are in front.
            attached.depth = new CommandBuffer { name = "MiSideCraft blocks (depth)" };
            camera.AddCommandBuffer(CameraEvent.AfterDepthTexture, attached.depth);
        }

        cameras[id] = attached;
        Plugin.Say($"blocks are drawn inside {camera.gameObject.name}'s rendering now{(mirror ? " (a mirror: Steve shows in it)" : "")}");
        return attached;
    }

    // This camera's view into a fresh slot, and its command buffers pointed at it.
    private static unsafe void Record(Attached attached, int avatar)
    {
        var camera = attached.camera;

        // From coordinates relative to the camera in host units (see Units) to MiSide's clip space:
        // host (X, Y, Z) -> Unity (X, Z, -Y) / 70 blocks, around the camera, then its own view and
        // projection (as the GPU gets it: reversed depth, flipped for a render texture).
        float s = (float)(1.0 / (Units.HostPerBlock * Units.BlocksPerMetre));
        var position = camera.transform.position;
        var hostToWorld = new Matrix4x4(
            new Vector4(s, 0f, 0f, 0f),
            new Vector4(0f, 0f, -s, 0f),
            new Vector4(0f, s, 0f, 0f),
            new Vector4(position.x, position.y, position.z, 1f));
        var projection = GL.GetGPUProjectionMatrix(camera.projectionMatrix, true);
        var toClip = projection * camera.worldToCameraMatrix * hostToWorld;

        var slot = slots + (next++ % Slots) * SlotBytes;
        var host = Units.HostPosition(position);
        double* p = (double*)slot;
        p[0] = host[0];
        p[1] = host[1];
        p[2] = host[2];
        float* m = (float*)(slot + 24);
        for (int row = 0; row < 4; row++)
        {
            for (int column = 0; column < 4; column++)
            {
                m[row * 4 + column] = toClip[row, column];
            }
        }

        int* flags = (int*)(slot + 24 + 64);
        flags[0] = avatar;
        flags[1] = 0;

        attached.colour.Clear();
        attached.colour.IssuePluginEventAndData(callback, 1, slot);
        if (attached.depth != null)
        {
            attached.depth.Clear();
            attached.depth.IssuePluginEventAndData(callback, 2, slot);
        }
    }

    // MiSide's cameras that render into a texture and aren't the main ones: mirrors. Twice a second.
    private static void FindMirrors(Camera main)
    {
        float now = Time.realtimeSinceStartup;
        if (now - mirrorsFound < 0.5f)
        {
            return;
        }

        mirrorsFound = now;
        foreach (var camera in Camera.allCameras)
        {
            if (camera == null || camera == main || camera.targetTexture == null || camera.gameObject.name == "CameraPersons")
            {
                continue;
            }

            Attach(camera, true);
            Hook();
        }

        // MiSide's mirrors keep their cameras switched off and have them render on demand, so
        // they aren't among the cameras above: they are found through the mirrors themselves.
        foreach (var mirror in UnityEngine.Object.FindObjectsOfType<MirrorRef>())
        {
            if (mirror.mirrorCamera != null)
            {
                Attach(mirror.mirrorCamera, true);
                Hook();
            }
        }

        foreach (var planar in UnityEngine.Object.FindObjectsOfType<PIDI_PlanarReflection>())
        {
            if (planar.v_reflectionCameras == null)
            {
                continue;
            }

            foreach (var reflection in planar.v_reflectionCameras)
            {
                if (reflection.reflector != null && reflection.reflector != main)
                {
                    Attach(reflection.reflector, true);
                    Hook();
                }
            }
        }

        foreach (var key in new List<int>(cameras.Keys))
        {
            if (cameras[key].camera == null)
            {
                cameras.Remove(key);
            }
        }
    }

    private static void Hook()
    {
        if (hooked)
        {
            return;
        }

        hooked = true;
        var before = DelegateSupport.ConvertDelegate<Camera.CameraCallback>(new Action<Camera>(BeforeRender));
        Camera.onPreRender = Camera.onPreRender == null ? before : (Camera.CameraCallback)Il2CppSystem.Delegate.Combine(Camera.onPreRender, before);
    }

    // A mirror's camera about to render, with its matrices just set: its view now, with Steve.
    private static void BeforeRender(Camera camera)
    {
        if (camera == null)
        {
            return;
        }

        // Every camera that renders is told once (which one is a mirror's), and one rendering into
        // a texture that isn't the main one or the characters' is taken for a mirror.
        int id = camera.GetInstanceID();
        if (seen.Add(id))
        {
            var target = camera.targetTexture;
            Plugin.Say($"camera renders: {camera.gameObject.name} (enabled {camera.enabled}, " +
                $"{(target != null ? $"into a texture {target.width}x{target.height}" : "to the screen")}, culling mask {camera.cullingMask:X})");
        }

        if (id != mainId && !cameras.ContainsKey(id) && camera.targetTexture != null && camera.gameObject.name != "CameraPersons" && camera.cameraType == CameraType.Game)
        {
            Attach(camera, true);
        }

        if (cameras.TryGetValue(id, out var attached) && attached.mirror)
        {
            // Being Mita, the player is her in the mirror: no Steve (spectating, he is a head).
            Record(attached, Possession.Active ? 0 : 2);
        }
    }

    public static void Detach()
    {
        foreach (var attached in cameras.Values)
        {
            if (attached.camera == null)
            {
                continue;
            }

            attached.camera.RemoveCommandBuffer(CameraEvent.BeforeForwardAlpha, attached.colour);
            if (attached.depth != null)
            {
                attached.camera.RemoveCommandBuffer(CameraEvent.AfterDepthTexture, attached.depth);
            }
        }

        cameras.Clear();
    }
}
