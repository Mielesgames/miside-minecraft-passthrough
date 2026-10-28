namespace MiSideCraft;

// "/miside <option> <value>" in Minecraft (SkySettings.java), sent here as kEvSetting every two
// seconds. Until the first one arrives these defaults hold (the same as Minecraft's).
internal static class Settings
{
    public const uint EvSetting = 18;

    // Hitting a Mita (MitaPerson) knocks her back; otherwise only physics (hair, clothes).
    public static bool MitaKnockback;
    // The same for the other characters.
    public static bool NpcKnockback = true;
    // Doors the Minecraft way: right-click opens and closes, left-click breaks.
    public static bool MinecraftDoors;
    // Every hit also pushes MiSide's physics where Steve aims.
    public static bool Physics = true;
    // Steve may break MiSide's things (the block breaking experiment).
    public static bool BlockBreaking;

    public static void Apply(uint option, uint value)
    {
        bool before = MitaKnockback, beforeNpc = NpcKnockback, beforeDoors = MinecraftDoors, beforePhysics = Physics, beforeBreak = BlockBreaking;
        switch (option)
        {
            case 1: MitaKnockback = value == 1; break;
            case 2: NpcKnockback = value == 1; break;
            case 3: MinecraftDoors = value == 1; break;
            case 4: Physics = value == 0; break;
            case 5: BlockBreaking = value == 1; break;
        }

        if (before != MitaKnockback || beforeNpc != NpcKnockback || beforeDoors != MinecraftDoors || beforePhysics != Physics || beforeBreak != BlockBreaking)
        {
            Plugin.Say($"settings: mita {(MitaKnockback ? "minecraft" : "realistic")}, npcs {(NpcKnockback ? "minecraft" : "realistic")}, " +
                $"doors {(MinecraftDoors ? "minecraft" : "realistic")}, physics {(Physics ? "on" : "off")}, block breaking {(BlockBreaking ? "on" : "off")}");
        }
    }
}
