using UnityEngine;

namespace MiSideCraft;

// Three coordinate systems meet here:
// - Unity (MiSide): metres, Y up, left-handed.
// - Minecraft: blocks, Y up, right-handed (Z south). One metre is one block, and Z is
//   mirrored to turn the handedness around.
// - The native half's "host units" (inherited from the Unreal mod it grew out of, which spoke
//   Unreal): host X = Minecraft x * 70, host Y = Minecraft z * 70, host Z = Minecraft y * 70.
internal static class Units
{
    public const double BlocksPerMetre = 1.0;
    public const double HostPerBlock = 70.0;

    public static Vector3d ToMc(Vector3 unity) =>
        new(unity.x * BlocksPerMetre, unity.y * BlocksPerMetre, -unity.z * BlocksPerMetre);

    public static Vector3 FromMc(double x, double y, double z) =>
        new((float)(x / BlocksPerMetre), (float)(y / BlocksPerMetre), (float)(-z / BlocksPerMetre));

    // A direction, Minecraft axes.
    public static Vector3 DirToMc(Vector3 unity) => new(unity.x, unity.y, -unity.z);

    // Minecraft's yaw 0 looks south (+z, which is Unity's -z): Unity's yaw is half a turn off.
    public static float YawToMc(float unityYaw) => Mathf.Repeat(unityYaw + 180f, 360f) - 180f;
    public static float YawFromMc(float mcYaw) => mcYaw - 180f;

    public static double[] HostPosition(Vector3 unity)
    {
        var mc = ToMc(unity);
        return new[] { mc.x * HostPerBlock, mc.z * HostPerBlock, mc.y * HostPerBlock };
    }

    public static float[] HostDirection(Vector3 unity)
    {
        var mc = DirToMc(unity);
        return new[] { mc.x, mc.z, mc.y };
    }
}

internal struct Vector3d
{
    public double x, y, z;

    public Vector3d(double x, double y, double z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
