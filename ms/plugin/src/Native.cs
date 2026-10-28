using System;
using System.IO;
using System.Runtime.InteropServices;

namespace MiSideCraft;

// The native half (ms/native, MiSideCraftNative.dll): the shared memory with Minecraft,
// its picture over the frame, its blocks in 3D, keyboard and mouse. Layouts mirror
// protocol/skycraft_protocol.h.
internal static class Native
{
    private const string Dll = "MiSideCraftNative";

    // DllImport finds it by name once it is loaded from the plugin's folder.
    public static bool Load(string folder)
    {
        var path = Path.Combine(folder, Dll + ".dll");
        return NativeLibrary.TryLoad(path, out _);
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct McState
    {
        public uint seq;
        public uint flags;
        public double x, y, z;
        public float yaw, pitch;
        public float eyeHeight;
        public float sensitivity;
        public uint teleportAck;
        public uint guiScale;
        public ulong frameCounter;
        public float fovDeg;
        public float bobPhase;
        public float bobAmount;
        public uint heldToken;
        public double eyeX, eyeY, eyeZ;
        public long tickQpc;
        public double prevX, prevY, prevZ;
        public double curX, curY, curZ;
        public float tickEyeO, tickEye;
        public float walkDistO, walkDist;
        public float bobO, bob;
        public float tickMs;
        public uint tickPad;
        public uint cameraMode;
        public float cameraDistance;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct McEvent
    {
        public uint type;
        public uint formId;
        public float a, b, c, d;
        public uint flags;
        public uint weapon;
    }

    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_start();
    [DllImport(Dll)] public static extern void msc_stop();
    [DllImport(Dll, CharSet = CharSet.Unicode)] public static extern void msc_log(string text);

    [DllImport(Dll)] public static extern void msc_heartbeat();
    [DllImport(Dll)]
    public static extern void msc_write_state(uint flags, uint worldId, uint collisionEpoch, double x, double y, double z,
        float yaw, float pitch, uint teleportSeq, uint viewportW, uint viewportH, float gameHour);
    [DllImport(Dll)] public static extern void msc_push_input(ushort type, ushort code, int a, int b, int c);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_read_mc_state(out McState state);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_read_event(out McEvent ev);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_minecraft_targets_block();
    [DllImport(Dll)]
    [return: MarshalAs(UnmanagedType.I1)]
    public static extern unsafe bool msc_write_collision(uint type, void* header, ulong headerBytes, void* body, ulong bodyBytes);
    [DllImport(Dll)] public static extern IntPtr msc_shared_base();

    [DllImport(Dll)]
    public static extern void msc_input_update(IntPtr window, [MarshalAs(UnmanagedType.I1)] bool minecraftMode,
        [MarshalAs(UnmanagedType.I1)] bool screenOpen, int viewportW, int viewportH);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_take_use_click();
    [DllImport(Dll)] public static extern void msc_forward_use_click();
    [DllImport(Dll)] public static extern void msc_consume_use_click();
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_take_attack_click();
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_attack_held();
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_grab_held();
    [DllImport(Dll)] public static extern void msc_take_look(out int dx, out int dy);

    [DllImport(Dll)]
    public static extern void msc_overlay_set([MarshalAs(UnmanagedType.I1)] bool show, [MarshalAs(UnmanagedType.I1)] bool crosshair,
        int guiScale);
    [DllImport(Dll)] public static extern void msc_overlay_viewport(out int width, out int height, out IntPtr window);
    [DllImport(Dll, CharSet = CharSet.Unicode)] public static extern void msc_set_status(string text);

    [DllImport(Dll)]
    public static extern void msc_set_camera([MarshalAs(UnmanagedType.I1)] bool valid, double[] position, float[] forward,
        float[] right, float[] up, float tanHalfFov);
    [DllImport(Dll)] public static extern void msc_set_player_position(double x, double y, double z);
    [DllImport(Dll)] public static extern void msc_set_near_plane(float hostUnits);
    [DllImport(Dll)] public static extern IntPtr msc_scene_event();
    [DllImport(Dll)] public static extern void msc_set_night_vision([MarshalAs(UnmanagedType.I1)] bool on);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_solid_at(int x, int y, int z);

    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_minecraft_running();
    [DllImport(Dll, CharSet = CharSet.Unicode)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_start_bundled(string folder);
    [DllImport(Dll)] [return: MarshalAs(UnmanagedType.I1)] public static extern bool msc_needs_sign_in();
}

// protocol/skycraft_protocol.h, the parts the plugin uses.
internal static class Proto
{
    public const uint SkyInGame = 1u << 0;
    public const uint SkyMenuOpen = 1u << 1;
    public const uint SkyLoading = 1u << 2;
    public const uint SkyHostDrives = 1u << 3;

    public const uint McInWorld = 1u << 0;
    public const uint McScreenOpen = 1u << 1;
    public const uint McOnGround = 1u << 2;
    public const uint McSneaking = 1u << 3;
    public const uint McFlying = 1u << 7;
    public const uint McSpectator = 1u << 8;
    public const uint McNightVision = 1u << 9;
    public const uint McCreative = 1u << 10;
    public const uint McPauseMenu = 1u << 23;
    public const uint McHoldsBucket = 1u << 17;
    public const uint McHiding = 1u << 16;
    public const uint McHandEmpty = 1u << 28;

    public const uint ColClear = 1;
    public const uint ColRegionMsg = 2;
    public const uint ColTris = 3;

    [StructLayout(LayoutKind.Sequential)]
    public struct ColRegion
    {
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;
        public uint epoch;
        public uint count;
    }

    [StructLayout(LayoutKind.Sequential)]
    public unsafe struct ColTri
    {
        public fixed float v[9];
        public uint flags;
    }

    [StructLayout(LayoutKind.Sequential)]
    public unsafe struct ColBlock
    {
        public int x, y, z;
        public uint pad;
        public fixed ulong bits[8];
    }
}
