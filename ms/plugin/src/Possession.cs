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

    // A part of the one the player is.
    internal static bool Owns(Transform part) => mita != null && part != null && part.IsChildOf(mita.transform);

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
    private static bool agentWasEnabled;
    private static float lastDebug;
    private static float lastBlockLog;
    private static float lastMagnetOff;
    private static Vector3 walkDirection;

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
                try
                {
                    Begin(who);
                }
                catch (Exception e)
                {
                    Plugin.Say($"possession: taking her over failed ({e.Message})");
                }
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

        HookMirrors();

        try
        {
            who.AiShraplyStop();
        }
        catch (Exception)
        {
        }

        // Walking goes through her navigation agent (around walls); a copy of her has it off.
        agentWasEnabled = who.nma != null && who.nma.enabled;
        if (who.nma != null && !who.nma.enabled)
        {
            who.nma.enabled = true;
        }

        target = new GameObject("MiSideCraft walk target").transform;

        // What her animator listens to (its parameters), for walking her with her own animation.
        var animator = who.animMita;
        if (animator != null)
        {
            var names = new System.Text.StringBuilder();
            try
            {
                for (int i = 0; i < animator.parameterCount; i++)
                {
                    var parameter = animator.GetParameter(i);
                    names.Append($" {parameter.name} ({parameter.type})");
                }
            }
            catch (Exception e)
            {
                names.Append($" (not readable: {e.GetType().Name})");
            }

            Plugin.Say($"possession: her animator {animator.runtimeAnimatorController?.name}, root motion {animator.applyRootMotion}, parameters:{names}");

            // Her animations by name (what can be played for walking or sitting).
            var clips = new System.Text.StringBuilder();
            try
            {
                var controller = animator.runtimeAnimatorController;
                if (controller != null)
                {
                    var all = controller.animationClips;
                    for (int i = 0; i < all.Length; i++)
                    {
                        clips.Append($" {all[i].name};");
                    }
                }

                for (int layer = 0; layer < animator.layerCount; layer++)
                {
                    clips.Append($" [layer {layer} {animator.GetLayerName(layer)}: state {animator.GetCurrentAnimatorStateInfo(layer).shortNameHash}]");
                }
            }
            catch (Exception e)
            {
                clips.Append($" (not readable: {e.GetType().Name})");
            }

            Plugin.Say($"possession: her animations:{clips}");
        }
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
        if (animWalking)
        {
            Animate(false);
        }

        try
        {
            mita.AiShraplyStop();
        }
        catch (Exception)
        {
        }

        if (mita.nma != null && mita.nma.enabled != agentWasEnabled)
        {
            mita.nma.enabled = agentWasEnabled;
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
        // MiSide's "magnet" holds a character on a spot every frame: off while she is played.
        if (Time.realtimeSinceStartup - lastMagnetOff > 1f)
        {
            lastMagnetOff = Time.realtimeSinceStartup;
            try
            {
                Ours = true;
                mita.MagnetOff();
            }
            catch (Exception)
            {
            }
            finally
            {
                Ours = false;
            }
        }
        if (Time.realtimeSinceStartup - lastDebug > 2f)
        {
            lastDebug = Time.realtimeSinceStartup;
            Plugin.Say($"possession: move {move}, agent {(agent == null ? "none" : $"enabled {agent.isActiveAndEnabled}, on navmesh {agent.isOnNavMesh}")}, " +
                $"(active {agent?.gameObject.activeInHierarchy}), at {mita.transform.position}, head {(head != null ? head.position.ToString() : "?")}, paused {Paused} (cutscene {Cutscene}, busy {GameBusy})");
        }
        // Her own walking reused: the agent kept on, and a goal of hers just ahead in the way the keys
        // say, renewed as she goes (her walking animation and path finding come with it). The game's
        // own goals are kept off meanwhile (PlayedMitaPatch).
        if (agent != null && !agent.enabled)
        {
            agent.enabled = true;
        }

        if (agent != null && agent.isActiveAndEnabled && agent.isOnNavMesh)
        {
            float now = Time.realtimeSinceStartup;
            if (move.sqrMagnitude > 0.01f)
            {
                // A new goal only when the way changes, she is nearly there, or now and then: every
                // new order restarts her walk, and renewed too often she never gets past its start.
                var from = head != null ? new Vector3(head.position.x, mita.transform.position.y, head.position.z) : mita.transform.position;
                bool turned = Vector3.Angle(move, walkDirection) > 25f;
                bool near = Vector3.Distance(from, target.position) < 1.2f;
                if (!walking || turned || near || now - lastWalk > 1f)
                {
                    lastWalk = now;
                    walkDirection = move;
                    target.position = from + move.normalized * 3f;
                    agent.speed = speed;
                    try
                    {
                        Ours = true;
                        mita.AiWalkToTarget(target);
                    }
                    catch (Exception)
                    {
                        agent.SetDestination(target.position);
                    }
                    finally
                    {
                        Ours = false;
                    }
                }
            }
            else if (walking)
            {
                try
                {
                    Ours = true;
                    mita.AiShraplyStop();
                }
                catch (Exception)
                {
                    agent.ResetPath();
                }
                finally
                {
                    Ours = false;
                }
            }
        }
        else if (move.sqrMagnitude > 0.01f)
        {
            // No agent to keep her in the level: a step only where her body fits (no walls).
            var step = move.normalized * speed * Time.deltaTime;
            var bottom = mita.transform.position + Vector3.up * 0.35f;
            var top = mita.transform.position + Vector3.up * 1.4f;
            bool blocked = false;
            foreach (var hit in Physics.CapsuleCastAll(bottom, top, 0.25f, step.normalized, step.magnitude + 0.05f, ~0, QueryTriggerInteraction.Ignore))
            {
                if (!hit.collider.transform.IsChildOf(mita.transform) && !Actors.IsCharacter(hit.collider.transform) && !hit.collider.transform.IsChildOf(Craft.Instance.PlayerTransform))
                {
                    blocked = true;
                    if (Time.realtimeSinceStartup - lastBlockLog > 1f)
                    {
                        lastBlockLog = Time.realtimeSinceStartup;
                        Plugin.Say($"possession: her way is blocked by {hit.collider.gameObject.name} at {hit.distance:F2} m");
                    }

                    break;
                }
            }

            if (!blocked)
            {
                mita.transform.position += step;
            }
        }

        bool nowWalking = move.sqrMagnitude > 0.01f;
        Animate(nowWalking);
        walking = nowWalking;
        // Standing, she faces where the player looks; walking, her agent turns her. Turned around
        // where she stands: her model is a part of her object that her walking moves away from its
        // middle, and turned around that middle she would swing around it (and the camera with her).
        if (!walking && Feet(out var pivot))
        {
            // The part her walking agent is on carries her model (her object's middle stays behind).
            var body = mita.nma != null ? mita.nma.transform : mita.transform;
            var turned = Quaternion.RotateTowards(body.rotation, Quaternion.Euler(0f, lookYaw, 0f), 540f * Time.deltaTime);
            float angle = Mathf.DeltaAngle(body.eulerAngles.y, turned.eulerAngles.y);
            body.RotateAround(pivot, Vector3.up, angle);
        }
    }

    // Her animator's "Walk" while she walks (her AI's goals don't start it for us), and the state
    // she stood in before when she stops.
    private static int standingState;
    private static bool animWalking;

    private static void Animate(bool nowWalking)
    {
        var animator = mita.animMita;
        if (animator == null || nowWalking == animWalking)
        {
            return;
        }

        animWalking = nowWalking;
        try
        {
            if (nowWalking)
            {
                standingState = animator.GetCurrentAnimatorStateInfo(0).fullPathHash;
                animator.CrossFadeInFixedTime("Walk", 0.2f, 0);
            }
            else
            {
                animator.CrossFadeInFixedTime(standingState, 0.25f, 0);
            }
        }
        catch (Exception e)
        {
            Plugin.Say($"possession: her walking animation failed ({e.Message})");
        }
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
        SetHead(shown);
        HookMirrors();
    }

    private static void SetHead(bool shown)
    {
        foreach (var renderer in headOff)
        {
            if (renderer != null)
            {
                renderer.enabled = shown;
            }
        }
    }

    // A mirror (a camera of MiSide's rendering into a texture) shows her whole, head and face: it
    // is turned on just before such a camera picks what it draws, and off again after it.
    private static bool mirrorsHooked;

    private static void HookMirrors()
    {
        if (mirrorsHooked)
        {
            return;
        }

        mirrorsHooked = true;
        var before = Il2CppInterop.Runtime.DelegateSupport.ConvertDelegate<Camera.CameraCallback>(new Action<Camera>(BeforeMirror));
        var after = Il2CppInterop.Runtime.DelegateSupport.ConvertDelegate<Camera.CameraCallback>(new Action<Camera>(AfterMirror));
        Camera.onPreCull = Camera.onPreCull == null ? before : (Camera.CameraCallback)Il2CppSystem.Delegate.Combine(Camera.onPreCull, before);
        Camera.onPostRender = Camera.onPostRender == null ? after : (Camera.CameraCallback)Il2CppSystem.Delegate.Combine(Camera.onPostRender, after);
    }

    private static bool InMirror(Camera camera) =>
        mita != null && !headShown && camera != null && camera.targetTexture != null && camera.gameObject.name != "CameraPersons";

    private static void BeforeMirror(Camera camera)
    {
        if (InMirror(camera))
        {
            SetHead(true);
        }
    }

    private static void AfterMirror(Camera camera)
    {
        if (InMirror(camera))
        {
            SetHead(false);
        }
    }
}

// The keyboard as it is, whoever gets its messages (Minecraft does, while Steve plays).
internal static class Keys
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);

    // Only while MiSide is the window in front: typing elsewhere doesn't walk her.
    public static bool Down(int virtualKey) => Application.isFocused && (GetAsyncKeyState(virtualKey) & 0x8000) != 0;
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

// Her looking at the player (head, eyes, turning her body to him): her own script turns it back on,
// so its updates are skipped while the player is her (he stands where she does, she'd look at him).
[HarmonyLib.HarmonyPatch]
internal static class PlayedLookPatch
{
    private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
    {
        yield return HarmonyLib.AccessTools.Method(typeof(Character_Look), "Update");
        yield return HarmonyLib.AccessTools.Method(typeof(Character_Look), "LateUpdate");
    }

    private static bool Prefix(Character_Look __instance)
    {
        return !Possession.Owns(__instance.transform);
    }
}

// Her own little doings while she stands (clapping, stretching: "Behaviour 4") and the game's other
// animations for her: not while the player is her, unless a scene of the game's has her.
[HarmonyLib.HarmonyPatch]
internal static class PlayedAnimationPatch
{
    private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
    {
        foreach (var name in new[] { "AnimationClipSimple", "AnimationClipSimpleNext", "AnimationClipSimpleFrame", "AnimationIdlePlayAfterWalk" })
        {
            yield return HarmonyLib.AccessTools.Method(typeof(Animator_FunctionsOverride), name);
        }
    }

    private static bool Prefix(Animator_FunctionsOverride __instance)
    {
        return Possession.Paused || !Possession.Owns(__instance.transform);
    }
}
