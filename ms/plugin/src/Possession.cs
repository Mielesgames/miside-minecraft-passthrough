using System;
using System.Collections.Generic;
using UnityEngine;

namespace MiSideCraft;

// Being a character, the way spectator mode takes over a mob: Minecraft's spectator looks through
// a character's stand-in (EV_POSSESS), and here the player becomes her. The camera sits in her head
// (which isn't drawn, her hair is), the mouse turns her, WASD walks her (Ctrl runs), and her own
// look at the player is off (the player is where she is, so she'd keep turning to the middle of the
// picture). Hits do nothing to her then; her own physics carry on. Sneak (Minecraft) leaves her.
internal static class Possession
{
    private const uint EvPossess = 19;

    private static MitaPerson mita;
    private static Transform head;
    private static readonly List<Behaviour> lookOff = new();
    private static readonly List<Renderer> headOff = new();
    private static Transform target;
    private static float lastWalk;
    private static bool walking;

    public static bool Active => mita != null;

    // Our own calls of her walking (Update) are let through; the game's are not while she is played.
    internal static bool Ours;

    internal static bool IsPlayed(MitaPerson who) => mita != null && who == mita;

    // A scene of the game's (a cutscene, a conversation, an animation of the player's): she is the
    // game's for that time, the camera too; the player has her back afterwards.
    public static bool Cutscene;
    public static bool GameBusy;
    // The game gave her an order of its own (walk there, come here, teleport) or plays a timeline:
    // she is the game's until a while after the last of it.
    private static float gameHasHerUntil;
    public static bool Paused => Cutscene || GameBusy || Time.realtimeSinceStartup < gameHasHerUntil || TimelinePlaying();

    public static void GameOrdered(string what)
    {
        if (Time.realtimeSinceStartup >= gameHasHerUntil)
        {
            Plugin.Say($"possession: the game has her ({what})");
        }

        gameHasHerUntil = Time.realtimeSinceStartup + 4f;
    }

    // A Unity timeline playing (MiSide's cutscenes): checked twice a second.
    private static float timelinesChecked = -10f;
    private static bool timelinePlaying;

    private static bool TimelinePlaying()
    {
        float now = Time.realtimeSinceStartup;
        if (now - timelinesChecked > 0.5f)
        {
            timelinesChecked = now;
            timelinePlaying = false;
            foreach (var director in UnityEngine.Object.FindObjectsOfType<UnityEngine.Playables.PlayableDirector>())
            {
                if (director != null && director.isActiveAndEnabled && director.state == UnityEngine.Playables.PlayState.Playing)
                {
                    timelinePlaying = true;
                    break;
                }
            }
        }

        return timelinePlaying;
    }
    private static bool wasPaused;

    public static bool Handle(in Native.McEvent ev)
    {
        if (ev.type != EvPossess)
        {
            return false;
        }

        if (ev.formId == 0)
        {
            End();
        }
        else if (mita == null || (uint)Actors.IdOf(mita) != ev.formId)
        {
            End();
            var who = Actors.MitaById(ev.formId);
            if (who != null)
            {
                Begin(who);
            }
        }

        return true;
    }

    private static void Begin(MitaPerson who)
    {
        mita = who;
        head = HeadOf(who);

        // Her looking at the player, and her looking around by herself: off.
        foreach (var behaviour in who.GetComponentsInChildren<Behaviour>(true))
        {
            string kind = behaviour.GetIl2CppType().Name;
            if (behaviour.enabled && (kind.Contains("Look") || kind.Contains("AimIK")))
            {
                behaviour.enabled = false;
                lookOff.Add(behaviour);
            }
        }

        // Her head isn't drawn (the camera is in it); her hair is, it is a part of its own.
        foreach (var renderer in new Renderer[] { who.meshHead, who.faceLayer })
        {
            if (renderer != null && renderer.enabled)
            {
                renderer.enabled = false;
                headOff.Add(renderer);
            }
        }

        try
        {
            who.AiShraplyStop();
        }
        catch (Exception)
        {
        }

        target = new GameObject("MiSideCraft walk target").transform;
        Plugin.Say($"possession: the player is {who.gameObject.name} now (head {(head != null ? head.name : "?")}, {lookOff.Count} look parts off)");
    }

    public static void End()
    {
        if (mita == null)
        {
            return;
        }

        foreach (var behaviour in lookOff)
        {
            if (behaviour != null)
            {
                behaviour.enabled = true;
            }
        }

        foreach (var renderer in headOff)
        {
            if (renderer != null)
            {
                renderer.enabled = true;
            }
        }

        headShown = false;
        try
        {
            mita.AiShraplyStop();
        }
        catch (Exception)
        {
        }

        Plugin.Say($"possession: {mita.gameObject.name} is herself again");
        lookOff.Clear();
        headOff.Clear();
        if (target != null)
        {
            UnityEngine.Object.Destroy(target.gameObject);
        }

        mita = null;
        head = null;
        target = null;
    }

    // The head bone: where her own "Head" collider sits, or a bone of that name.
    private static Transform HeadOf(MitaPerson who)
    {
        foreach (var collider in who.GetComponentsInChildren<Collider>(true))
        {
            if (collider.gameObject.name.Equals("Head", StringComparison.OrdinalIgnoreCase))
            {
                return collider.transform;
            }
        }

        foreach (var t in who.GetComponentsInChildren<Transform>(true))
        {
            if (t.name.EndsWith("Head", StringComparison.OrdinalIgnoreCase))
            {
                return t;
            }
        }

        return null;
    }

    // Every frame: walk her the way the keys and the look say.
    public static void Update(float lookYaw)
    {
        if (mita == null)
        {
            return;
        }

        if (Paused != wasPaused)
        {
            wasPaused = Paused;
            walking = false;
            Plugin.Say(Paused ? "possession: a scene of the game's, she is the game's for now" : "possession: the scene is over, she is the player's again");
        }

        if (Paused)
        {
            return;
        }

        if (!mita.gameObject.activeInHierarchy)
        {
            End();
            return;
        }

        var forward = Quaternion.Euler(0f, lookYaw, 0f) * Vector3.forward;
        var right = Quaternion.Euler(0f, lookYaw, 0f) * Vector3.right;
        var move = Vector3.zero;
        if (Keys.Down(0x57)) move += forward;   // W
        if (Keys.Down(0x53)) move -= forward;   // S
        if (Keys.Down(0x44)) move += right;     // D
        if (Keys.Down(0x41)) move -= right;     // A

        // Straight through her navigation agent: the game's own goals for her are dropped, she walks
        // where the keys say at her walking pace (Ctrl: running), around walls as the level allows,
        // and her walking animation goes by the agent's speed.
        var agent = mita.nma;
        float speed = Keys.Down(0x11) ? 3.5f : 1.6f;
        if (agent != null && agent.isActiveAndEnabled && agent.isOnNavMesh)
        {
            if (agent.hasPath)
            {
                agent.ResetPath();
            }

            agent.velocity = move.sqrMagnitude > 0.01f ? move.normalized * speed : Vector3.zero;
        }
        else if (move.sqrMagnitude > 0.01f)
        {
            mita.transform.position += move.normalized * speed * Time.deltaTime;
        }

        walking = move.sqrMagnitude > 0.01f;
        var facing = walking ? Quaternion.LookRotation(move.normalized, Vector3.up) : Quaternion.Euler(0f, lookYaw, 0f);
        mita.transform.rotation = Quaternion.RotateTowards(mita.transform.rotation, facing, 540f * Time.deltaTime);
    }

    // Where she stands (the middle under her model): MiSide's own player goes along there unseen,
    // so the rooms she walks into are loaded and its triggers fire as if the player came.
    public static bool Feet(out Vector3 feet)
    {
        feet = Vector3.zero;
        if (mita == null)
        {
            return false;
        }

        feet = head != null ? new Vector3(head.position.x, mita.transform.position.y, head.position.z) : mita.transform.position;
        return true;
    }

    // After the animations: the camera in her head, looking the player's way.
    public static bool PlaceCamera(Transform camera, float lookPitch, float lookYaw, uint cameraMode)
    {
        if (mita == null || camera == null || Paused)
        {
            return false;
        }

        var look = Quaternion.Euler(lookPitch, lookYaw, 0f);
        var eyes = head != null ? head.position + Vector3.up * 0.06f : mita.transform.position + Vector3.up * 1.5f;
        // F5: her head is drawn again, the camera stands behind her (or before her, looking back).
        ShowHead(cameraMode != 0);
        if (cameraMode == 0)
        {
            camera.SetPositionAndRotation(eyes + Quaternion.Euler(0f, lookYaw, 0f) * Vector3.forward * 0.1f, look);
        }
        else
        {
            var back = cameraMode == 2 ? look * Vector3.forward : -(look * Vector3.forward);
            float distance = 2.5f;
            if (Physics.SphereCast(eyes, 0.15f, back, out var wall, distance, ~0, QueryTriggerInteraction.Ignore))
            {
                distance = Mathf.Max(0.3f, wall.distance - 0.05f);
            }

            camera.SetPositionAndRotation(eyes + back * distance, Quaternion.LookRotation(-back, Vector3.up));
        }

        return true;
    }

    private static bool headShown;

    private static void ShowHead(bool shown)
    {
        if (shown == headShown)
        {
            return;
        }

        headShown = shown;
        foreach (var renderer in headOff)
        {
            if (renderer != null)
            {
                renderer.enabled = shown;
            }
        }
    }
}

// The keyboard as it is, whoever gets its messages (Minecraft does, while Steve plays).
internal static class Keys
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);

    public static bool Down(int virtualKey) => (GetAsyncKeyState(virtualKey) & 0x8000) != 0;
}

// While the player plays her, the game's own orders to her (walk, come, teleport) are carried out
// and pause the player's control a while: a scene of hers isn't fought over (that turned her out
// of the level).
[HarmonyLib.HarmonyPatch]
internal static class PlayedMitaPatch
{
    private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
    {
        foreach (var method in typeof(MitaPerson).GetMethods())
        {
            if (method.Name is "AiWalkToTarget" or "AiWalkToTargetTranform" or "AiWalkToTargetRotate" or "MagnetToTarget" or "MitaTeleport")
            {
                yield return method;
            }
        }
    }

    private static bool Prefix(MitaPerson __instance, System.Reflection.MethodBase __originalMethod)
    {
        if (Possession.Ours || !Possession.IsPlayed(__instance))
        {
            return true;
        }

        // Her everyday AI keeps sending her back to her spot: off while she is played. Only in a
        // scene the game recognisably runs (a cutscene, a timeline, the game holding the player) or
        // when it teleports her are its orders carried out, and she is the game's for a while.
        if (Possession.Paused || __originalMethod.Name == "MitaTeleport")
        {
            Possession.GameOrdered(__originalMethod.Name);
            return true;
        }

        return false;
    }
}

// The game's cutscenes, as it starts and ends them.
[HarmonyLib.HarmonyPatch(typeof(GameController), nameof(GameController.CutscenePlay))]
internal static class CutscenePlayPatch
{
    private static void Postfix()
    {
        Possession.Cutscene = true;
    }
}

[HarmonyLib.HarmonyPatch(typeof(GameController), nameof(GameController.CutsceneStop))]
internal static class CutsceneStopPatch
{
    private static void Postfix()
    {
        Possession.Cutscene = false;
    }
}
