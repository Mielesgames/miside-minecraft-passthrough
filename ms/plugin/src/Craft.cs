using System;
using HarmonyLib;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiSideCraft;

// The main loop, once a frame on Unity's thread. Minecraft mode on (F6): Minecraft's
// physics move the player and MiSide's colliders are sent over as collision. Off (or while
// the game takes the player for itself: a scene, an animation): MiSide moves the player and
// Minecraft's player just follows.
public class Craft : MonoBehaviour
{
    public Craft(IntPtr pointer) : base(pointer)
    {
    }

    internal static Craft Instance;

    // A jump this big is never walking: someone teleported (MiSide moving the player for a
    // scene, a /tp in Minecraft). MiSide wins.
    private const float MaxStep = 4f;
    // Minecraft counts as arrived at the player within this.
    private const float EngageDistance = 0.5f;
    // After the game had the player (a scene), it gets a moment before Steve takes over again.
    private const float GraceSeconds = 1.5f;

    private bool minecraftMode = true;
    private bool driving;
    private uint teleportSeq = 1;
    private float syncStarted;
    private float gameBusyUntil;
    private Native.McState mc;
    private bool haveMc;
    private ulong lastMcFrame;
    private int lastScene = -1;
    private float lastSearch;
    private float lastLog;

    private PlayerMove player;
    private GameController game;
    private bool hidingBody;
    private bool savedKinematic;
    private RigidbodyInterpolation savedInterpolation;

    private void Awake()
    {
        Instance = this;
        Launcher.Start();
    }

    private void OnApplicationQuit()
    {
        Launcher.Stop();
        Native.msc_stop();
    }

    private void Update()
    {
        Native.msc_heartbeat();
        haveMc = Native.msc_read_mc_state(out mc);
        float now = Time.realtimeSinceStartup;

        TestInput.Update();
        if (Input.GetKeyDown(KeyCode.F6) || TestInput.TakeF6)
        {
            TestInput.TakeF6 = false;
            minecraftMode = !minecraftMode;
            Plugin.Say($"F6: Minecraft mode {(minecraftMode ? "on" : "off")}");
            if (minecraftMode)
            {
                RequestSync("F6");
            }
        }

        int scene = SceneManager.GetActiveScene().buildIndex;
        if (scene != lastScene)
        {
            Plugin.Say($"scene {SceneManager.GetActiveScene().name} ({scene})");
            lastScene = scene;
            player = null;
            game = null;
            Collision.Reset();
            Doors.Forget();
            Actors.Forget();
            RequestSync("new scene");
        }

        FindPlayer(now);
        AutoContinue(now);

        Native.msc_overlay_viewport(out int viewportW, out int viewportH, out IntPtr window);
        bool inWorld = haveMc && (mc.flags & Proto.McInWorld) != 0;
        bool screenOpen = haveMc && (mc.flags & Proto.McScreenOpen) != 0;
        bool paused = game != null && game.isPaused;
        bool gameBusy = player != null && GameHasPlayer();
        if (gameBusy)
        {
            gameBusyUntil = now + GraceSeconds;
        }

        bool steve = minecraftMode && player != null && inWorld && now >= gameBusyUntil;
        UpdateStatus(inWorld);

        Native.msc_input_update(window, steve && !paused, screenOpen, viewportW, viewportH);
        Native.msc_overlay_set(minecraftMode && inWorld && player != null && !paused, minecraftMode && inWorld && !screenOpen && mc.cameraMode == 0,
            (int)mc.guiScale);
        Native.msc_set_night_vision(haveMc && (mc.flags & Proto.McNightVision) != 0);

        if (player == null)
        {
            Release();
            Native.msc_write_state(0, (uint)(scene + 1), Collision.Epoch, 0, 0, 0, 0, 0, teleportSeq,
                (uint)Math.Max(viewportW, 1), (uint)Math.Max(viewportH, 1), 12f);
            return;
        }

        var feetUnity = Feet();
        var feet = Units.ToMc(feetUnity);
        var look = LookAngles();

        Actors.Update(feetUnity);
        Doors.Update();
        Dialogue.Update();
        Events.Handle(game, CameraTransform());
        Events.Pause(steve && haveMc && (mc.flags & Proto.McPauseMenu) != 0);

        if (steve)
        {
            Collision.Update(feet, player.transform);
            Events.UseClick(CameraTransform(), player.transform, mc.heldToken, (mc.flags & Proto.McHoldsBucket) != 0);
            Hits.Attack(CameraTransform(), player.transform, (mc.flags & Proto.McCreative) != 0);
        }
        else if (driving)
        {
            // The game took the player (or F6): it moves him from here, and Minecraft follows.
            Release();
            RequestSync(minecraftMode ? "the game has the player" : "F6 off");
        }

        Native.msc_write_state(Proto.SkyInGame | (steve ? 0u : Proto.SkyHostDrives), (uint)(scene + 1), Collision.Epoch,
            feet.x, feet.y, feet.z, Units.YawToMc(look.y), look.x, teleportSeq,
            (uint)Math.Max(viewportW, 1), (uint)Math.Max(viewportH, 1), 12f);

        if (now - lastLog > 5f)
        {
            lastLog = now;
            Plugin.Say($"state: mc {(haveMc ? $"({mc.x:F2}, {mc.y:F2}, {mc.z:F2}) flags {mc.flags:X} ack {mc.teleportAck}" : "none")}, " +
                $"player ({feet.x:F2}, {feet.y:F2}, {feet.z:F2}), steve {steve}, driving {driving}, seq {teleportSeq}, regions {Collision.RegionsSent}");
        }

        if (!steve || mc.frameCounter == lastMcFrame)
        {
            return;
        }

        lastMcFrame = mc.frameCounter;
        var target = Units.FromMc(mc.x, mc.y, mc.z);
        float gap = Vector2.Distance(new Vector2(target.x, target.z), new Vector2(feetUnity.x, feetUnity.z));

        if (!driving)
        {
            // Minecraft may still stand where it was before the sync: wait until it arrived.
            if (mc.teleportAck == teleportSeq && gap < EngageDistance)
            {
                Take();
            }
            else if (now - syncStarted > 10f)
            {
                RequestSync("Minecraft did not arrive");
            }

            return;
        }

        if (Vector3.Distance(target, feetUnity) > MaxStep)
        {
            Plugin.Say($"drift: Minecraft at {target}, player at {feetUnity} (transform {player.transform.position}, " +
                $"body {(player.rb != null ? player.rb.position.ToString() : "-")}, kinematic {(player.rb != null && player.rb.isKinematic)})");
            Release();
            RequestSync("positions drifted apart");
            return;
        }

        PlaceAtMinecraft();
    }

    // Testing without hands: "AutoContinue = true" in MiSideCraft.ini presses Continue in the
    // main menu once Minecraft is in its world.
    private float menuSince = -1f;
    private bool continued;

    private void AutoContinue(float now)
    {
        if (!Launcher.AutoContinue || continued || SceneManager.GetActiveScene().name != "SceneMenu")
        {
            menuSince = -1f;
            return;
        }

        if (menuSince < 0f)
        {
            menuSince = now;
        }

        if (now - menuSince < 5f || !haveMc || (mc.flags & Proto.McInWorld) == 0)
        {
            return;
        }

        var menu = UnityEngine.Object.FindObjectOfType<Menu>();
        if (menu != null)
        {
            continued = true;
            Plugin.Say("AutoContinue: pressing Continue");
            menu.ButtonContinue();
        }
    }

    private void FindPlayer(float now)
    {
        if (player != null && game != null)
        {
            return;
        }

        if (now - lastSearch < 0.5f)
        {
            return;
        }

        lastSearch = now;
        player ??= UnityEngine.Object.FindObjectOfType<PlayerMove>();
        game ??= UnityEngine.Object.FindObjectOfType<GameController>();
        if (player != null)
        {
            driving = false;
            Plugin.Say($"player found: {player.gameObject.name}, rigidbody {(player.rb != null)}, capsule {(player.capsule != null)}");
            Collision.SetPlayer(player);
            foreach (var camera in Camera.allCameras)
            {
                Plugin.Say($"camera {camera.gameObject.name}: near {camera.nearClipPlane}, far {camera.farClipPlane}, fov {camera.fieldOfView}, " +
                    $"depth {camera.depth}, target {(camera.targetTexture != null ? camera.targetTexture.name : "screen")}, mask {camera.cullingMask:X}, " +
                    $"depthTextureMode {camera.depthTextureMode}, msaa {camera.allowMSAA}");
            }

            if (player.capsule != null)
            {
                var bounds = player.capsule.bounds;
                var cam = CameraTransform();
                Plugin.Say($"player size: capsule {bounds.size.y:F3} tall, {bounds.size.x:F3} wide, scale {player.transform.lossyScale}, " +
                    $"camera {(cam != null ? (cam.position.y - bounds.min.y).ToString("F3") : "?")} above the feet");
            }
        }
    }

    // The game's own scripts have the player: an animation, a scene, a forced look.
    private bool GameHasPlayer()
    {
        return player.animationRun || player.animationLoopRun || player.typeAnimation || player.cameraOtherTarget != null
            || player.dontMove || player.hide;
    }

    private Vector3 Feet()
    {
        var capsule = player.capsule;
        if (capsule != null)
        {
            var bounds = capsule.bounds;
            return new Vector3(bounds.center.x, bounds.min.y, bounds.center.z);
        }

        return player.transform.position;
    }

    // Pitch (down positive) and yaw of the camera, Unity degrees.
    private Vector2 LookAngles()
    {
        var camera = CameraTransform();
        if (camera == null)
        {
            return new Vector2(0f, player.transform.eulerAngles.y);
        }

        var euler = camera.eulerAngles;
        float pitch = euler.x > 180f ? euler.x - 360f : euler.x;
        return new Vector2(pitch, euler.y);
    }

    private Transform CameraTransform()
    {
        if (game != null && game.cameraPlayer != null)
        {
            return game.cameraPlayer.transform;
        }

        return player != null ? player.mainCamera : null;
    }

    // The game put the player somewhere itself (a scene, a door): Minecraft follows him there.
    internal void GameTeleported()
    {
        if (driving)
        {
            Release();
            RequestSync("the game teleported the player");
        }
    }

    private void RequestSync(string reason)
    {
        teleportSeq++;
        driving = false;
        syncStarted = Time.realtimeSinceStartup;
        Plugin.Say($"syncing Minecraft to the player (seq {teleportSeq}): {reason}");
    }

    // Minecraft drives from now on: MiSide's own physics let go of the player.
    private void Take()
    {
        driving = true;
        var body = player.rb;
        if (body != null)
        {
            savedKinematic = body.isKinematic;
            savedInterpolation = body.interpolation;
            body.velocity = Vector3.zero;
            body.isKinematic = true;
            body.interpolation = RigidbodyInterpolation.None;
        }

        SetBodyHidden(true);
        Plugin.Say("Minecraft is on the player, Minecraft drives now");
    }

    private void Release()
    {
        if (!driving)
        {
            return;
        }

        driving = false;
        if (player != null)
        {
            var body = player.rb;
            if (body != null)
            {
                body.isKinematic = savedKinematic;
                body.interpolation = savedInterpolation;
                body.velocity = Vector3.zero;
            }

            SetBodyHidden(false);
        }
    }

    private void SetBodyHidden(bool hidden)
    {
        if (hidingBody == hidden || player == null)
        {
            return;
        }

        hidingBody = hidden;

        // Everything the player's body draws (body, arms, what the hands hold): Minecraft
        // draws Steve's hand and body instead. Only what we turned off is turned on again.
        if (hidden)
        {
            hiddenRenderers.Clear();
            foreach (var renderer in player.GetComponentsInChildren<Renderer>(true))
            {
                if (renderer.enabled)
                {
                    renderer.enabled = false;
                    hiddenRenderers.Add(renderer);
                }
            }

            Plugin.Say($"body hidden ({hiddenRenderers.Count} renderers)");
        }
        else
        {
            foreach (var renderer in hiddenRenderers)
            {
                if (renderer != null)
                {
                    renderer.enabled = true;
                }
            }

            hiddenRenderers.Clear();
        }
    }

    private readonly System.Collections.Generic.List<Renderer> hiddenRenderers = new();

    internal bool Drives(PlayerMove move) => driving && move == player;

    // The player's capsule onto Minecraft's feet. Where he faces stays MiSide's: its mouse look
    // turns him and the camera, and Minecraft follows that look (SkyState yaw and pitch).
    private void PlaceAtMinecraft()
    {
        // MiSide turns its body back to physics now and then; while Minecraft drives it stays put.
        if (player.rb != null && !player.rb.isKinematic)
        {
            player.rb.isKinematic = true;
            player.rb.velocity = Vector3.zero;
        }

        var feet = Feet();
        var target = Units.FromMc(mc.x, mc.y, mc.z);
        var t = player.transform;
        t.position += target - feet;
        var body = player.rb;
        if (body != null)
        {
            body.position = t.position;
        }
    }

    // After PlayerMove's own LateUpdate: the camera at Minecraft's eye. It keeps looking where
    // MiSide turned it (setting the look here too fed back into itself and spun Steve around).
    internal void AfterPlayerLate(PlayerMove move)
    {
        if (move != player || player == null)
        {
            return;
        }

        var camera = CameraTransform();
        if (camera == null)
        {
            return;
        }

        if (driving)
        {
            camera.position = Units.FromMc(mc.eyeX, mc.eyeY, mc.eyeZ);
        }

        // Where the camera is, for Minecraft's blocks drawn in 3D.
        var cam = camera.GetComponent<Camera>();
        float tanHalf = 1f;
        if (cam != null)
        {
            tanHalf = Mathf.Tan(cam.fieldOfView * 0.5f * Mathf.Deg2Rad) * cam.aspect;
            Native.msc_set_near_plane((float)(cam.nearClipPlane * Units.BlocksPerMetre * Units.HostPerBlock));
        }

        Native.msc_set_camera(true, Units.HostPosition(camera.position), Units.HostDirection(camera.forward),
            Units.HostDirection(camera.right), Units.HostDirection(camera.up), tanHalf);
        var feet = Units.ToMc(Feet());
        Native.msc_set_player_position(driving ? mc.x : feet.x, driving ? mc.y : feet.y, driving ? mc.z : feet.z);
    }

    private string lastStatus = "";

    private void UpdateStatus(bool inWorld)
    {
        string text = "";
        if (!inWorld && Launcher.Mode != "none")
        {
            text = haveMc ? "Minecraft is loading its world..." : "Minecraft is starting...";
        }
        else if (!inWorld)
        {
            text = "Waiting for Minecraft (start it yourself)...";
        }

        if (text != lastStatus)
        {
            lastStatus = text;
            Native.msc_set_status(text);
        }
    }
}

[HarmonyPatch(typeof(PlayerMove), nameof(PlayerMove.LateUpdate))]
internal static class PlayerMoveLatePatch
{
    private static void Postfix(PlayerMove __instance)
    {
        Craft.Instance?.AfterPlayerLate(__instance);
    }
}

// Who moves the player besides us: logged, so a jump back can be traced to a script.
[HarmonyPatch]
internal static class PlayerTeleportLog
{
    private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
    {
        foreach (var method in typeof(PlayerMove).GetMethods())
        {
            if (method.Name is "TeleportPlayer" or "TeleportPlayerGeneral" or "ResetPlayer" or "TeleportRotate")
            {
                yield return method;
            }
        }
    }

    private static void Prefix(System.Reflection.MethodBase __originalMethod)
    {
        Plugin.Say($"the game calls PlayerMove.{__originalMethod.Name}");
        Craft.Instance?.GameTeleported();
    }
}

// MiSide's own movement (velocity, gravity, its floor checks) stays out while Minecraft drives.
[HarmonyPatch(typeof(PlayerMove), nameof(PlayerMove.FixedUpdate))]
internal static class PlayerMoveFixedPatch
{
    private static bool Prefix(PlayerMove __instance)
    {
        return Craft.Instance == null || !Craft.Instance.Drives(__instance);
    }
}
