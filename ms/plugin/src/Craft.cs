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

    // After every animation of the frame: the parts of characters that were hit sway on top.
    private void LateUpdate()
    {
        Actors.LateUpdate();
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
            hidingBody = false;
            hiddenRenderers.Clear();
            Collision.Reset();
            Possession.Cutscene = false;
            Possession.End();
            haveSafe = false;
            Doors.Forget();
            Hits.ForgetCloths();
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

        // Being a character (spectator mode took one over): Steve stands still, she is played.
        Possession.GameBusy = gameBusy;
        bool possessed = Possession.Active && minecraftMode && inWorld && !Possession.Paused;
        bool steve = minecraftMode && player != null && inWorld && now >= gameBusyUntil && !Possession.Active;
        UpdateBody(minecraftMode && inWorld, now);
        UpdateStatus(inWorld);

        Native.msc_input_update(window, (steve || possessed) && !paused, screenOpen, viewportW, viewportH);
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
        var look = Look((steve && driving) || possessed);
        Possession.Update(lookYaw);
        FollowPlayed(possessed);
        PushDoors(steve && driving);

        Actors.Update(feetUnity);
        Catch.Update(feetUnity, steve && driving);
        Doors.Update();
        Dialogue.Update();
        Events.Handle(game, CameraTransform());
        Events.Pause(steve && haveMc && (mc.flags & Proto.McPauseMenu) != 0);
        Events.FollowTicks(haveMc && inWorld ? mc.tickMs : 50f);
        Seats.Update(haveMc ? mc.flags : 0u);

        if (steve)
        {
            Collision.Update(feet, player.transform);
            Events.UseClick(CameraTransform(), player.transform, mc.heldToken, (mc.flags & Proto.McHoldsBucket) != 0, mc.flags);
            Hits.Attack(CameraTransform(), player.transform, (mc.flags & Proto.McCreative) != 0);
        }
        else if (driving && possessed)
        {
            // Spectating her: no teleport now (it would end Minecraft's spectating). Steve stays
            // pinned where the player stands, and takes over again from there afterwards.
            Release();
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

        if (now - lastLog > 2f)
        {
            lastLog = now;
            var cameraT = CameraTransform();
            Plugin.Say($"look: camera yaw {(cameraT != null ? cameraT.eulerAngles.y : 0f):F0} pitch {(cameraT != null ? cameraT.eulerAngles.x : 0f):F0}, " +
                $"look {lookYaw:F0}/{lookPitch:F0}, sent yaw {Units.YawToMc(look.y):F0}, Minecraft yaw {mc.yaw:F0} pitch {mc.pitch:F0}, " +
                $"moved since last ({mc.x - lastLogX:F2}, {mc.z - lastLogZ:F2}) in Minecraft");
            lastLogX = mc.x;
            lastLogZ = mc.z;
            if (driving)
            {
                // What stands right in front of Steve (for "an invisible wall" questions).
                var ahead = Quaternion.Euler(0f, lookYaw, 0f) * Vector3.forward;
                var front = new System.Text.StringBuilder();
                foreach (var hit in Physics.RaycastAll(feetUnity + Vector3.up * 0.9f, ahead, 1.2f, ~0, QueryTriggerInteraction.Collide))
                {
                    if (!hit.collider.transform.IsChildOf(player.transform))
                    {
                        front.Append($" {hit.collider.gameObject.name} ({LayerMask.LayerToName(hit.collider.gameObject.layer)}{(hit.collider.isTrigger ? ", trigger" : "")}, " +
                            $"{hit.distance:F2} m, {(Collision.Blocks(hit.collider) ? "blocks" : "passes")});");
                    }
                }

                Plugin.Say($"ahead within 1.2 m:{(front.Length > 0 ? front.ToString() : " nothing of MiSide's")}");
            }
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

        // Safety nets under Minecraft's collision, for where MiSide's surfaces didn't all reach it.
        // Fell well below the floor Steve last stood on: back onto it, before he lands somewhere.
        // Flying (creative, spectator) goes where it likes, through floors too: no nets then, and the
        // floor stood on last counts again once Steve stands on one.
        bool flying = (mc.flags & (Proto.McFlying | Proto.McSpectator)) != 0;
        if (flying)
        {
            haveSafe = false;
        }
        else if ((mc.flags & Proto.McOnGround) != 0 && (!haveSafe || target.y >= lastSafe.y - 0.5f))
        {
            lastSafe = target;
            haveSafe = true;
        }
        else if (haveSafe && target.y < lastSafe.y - 3f)
        {
            BackTo(lastSafe, $"fell through the floor ({target.y - lastSafe.y:F1} m)");
            return;
        }

        // Went through one of MiSide's walls in one step: at the waist and at the head, a ray of
        // MiSide's own from where the player is to where Steve went hits something.
        // (The wall net, rays through MiSide's walls at Steve's waist and head, stopped doing more good
        // than harm: under tables and at doors it sent Steve back time and again. Kept for later.)
        if (WallNet && !flying && ThroughWall(feetUnity, target))
        {
            BackTo(feetUnity, "walked through a wall");
            return;
        }

        PlaceAtMinecraft();
    }

    // While she is played: MiSide's (hidden) player stands where she is, held there, so MiSide
    // loads the rooms she walks into. Steve, pinned to that player, comes along.
    private bool following;
    private bool followKinematic;

    private void FollowPlayed(bool possessed)
    {
        var body = player.rb;
        if (possessed && Possession.Feet(out var feet))
        {
            if (!following)
            {
                following = true;
                followKinematic = body != null && body.isKinematic;
            }

            if (body != null)
            {
                body.isKinematic = true;
                body.velocity = Vector3.zero;
            }

            player.transform.position += feet - Feet();
            if (body != null)
            {
                body.position = player.transform.position;
            }
        }
        else if (following)
        {
            following = false;
            if (body != null)
            {
                body.isKinematic = followKinematic;
            }
        }
    }

    // Realistic doors: walking into one (W) pushes it, the way MiSide's own player does by walking
    // into it. A locked one stays shut.
    private void PushDoors(bool walking)
    {
        if (!walking || Settings.MinecraftDoors || !Keys.Down(0x57))
        {
            return;
        }

        var ahead = Quaternion.Euler(0f, lookYaw, 0f) * Vector3.forward;
        if (!Physics.Raycast(Feet() + Vector3.up * 0.9f, ahead, out var hit, 0.7f, ~0, QueryTriggerInteraction.Ignore))
        {
            return;
        }

        var door = hit.collider.GetComponentInParent<ObjectDoor>();
        var body = hit.rigidbody;
        if (door == null || body == null || body.isKinematic || door.lockDoor)
        {
            return;
        }

        body.WakeUp();
        body.AddForceAtPosition(ahead * Mathf.Max(30f, body.mass * 12f), hit.point, ForceMode.Force);
    }

    private const bool WallNet = false;
    private Vector3 lastSafe;
    private bool haveSafe;
    private float lastWallLog;

    private bool ThroughWall(Vector3 from, Vector3 to)
    {
        var step = to - from;
        step.y = 0f;
        float distance = step.magnitude;
        if (distance < 0.02f || (mc.flags & Proto.McSpectator) != 0)
        {
            return false;
        }

        // At Steve's own waist and head: a small Steve (/attribute ... minecraft:scale) walks under
        // tables, which would stop rays at a full-sized Steve's heights.
        float eye = haveMc && mc.eyeHeight > 0.05f ? mc.eyeHeight / (float)Units.BlocksPerMetre : 1.62f;
        foreach (float height in new[] { eye * 0.55f, eye * 0.95f })
        {
            bool blocked = false;
            foreach (var hit in Physics.RaycastAll(from + Vector3.up * height, step / distance, distance + 0.25f, ~0, QueryTriggerInteraction.Ignore))
            {
                var t = hit.collider.transform;
                if (t.IsChildOf(player.transform) || Actors.IsCharacter(t) || hit.collider.attachedRigidbody != null && !hit.collider.attachedRigidbody.isKinematic
                    && !Collision.IsDoor(hit.collider) || !Collision.Blocks(hit.collider))
                {
                    continue;
                }

                blocked = true;
                break;
            }

            if (!blocked)
            {
                return false;
            }
        }

        return true;
    }

    // Minecraft goes back to where MiSide's player is put, and its region is felt out again.
    private void BackTo(Vector3 spot, string reason)
    {
        float now = Time.realtimeSinceStartup;
        if (now - lastWallLog > 1f)
        {
            lastWallLog = now;
            Plugin.Say($"safety net: {reason}, back to {spot}");
        }

        Release();
        var feet = Feet();
        player.transform.position += spot - feet;
        if (player.rb != null)
        {
            player.rb.position = player.transform.position;
        }

        Collision.Invalidate(new Bounds(spot, Vector3.one * 3f));
        RequestSync(reason);
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
        // The title menu is a 3D scene with a player of its own, but nobody plays it.
        string sceneName = SceneManager.GetActiveScene().name;
        if (sceneName == "SceneMenu" || sceneName == "SceneLoading" || sceneName == "SceneAihasto")
        {
            return;
        }

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
    // Where Steve looks (pitch down positive, yaw; Unity degrees). While Minecraft drives, the
    // mouse turns it here, the way Minecraft turns its own camera (its sensitivity, its speed):
    // MiSide's own look runs in the physics step that is skipped then. Otherwise it is MiSide's
    // camera, so the look carries on from wherever the game left it.
    private float lookYaw, lookPitch;
    private double lastLogX, lastLogZ;

    private Vector2 Look(bool steering)
    {
        Native.msc_take_look(out int dx, out int dy);
        if (!steering)
        {
            var angles = LookAngles();
            lookPitch = angles.x;
            lookYaw = angles.y;
            return angles;
        }

        // Minecraft's MouseHandler: (sensitivity * 0.6 + 0.2)^3 * 8, then 0.15 degrees a step.
        double f = (haveMc ? mc.sensitivity : 0.5f) * 0.6 + 0.2;
        float perCount = (float)(f * f * f * 8.0 * 0.15);
        lookYaw = Mathf.Repeat(lookYaw + dx * perCount, 360f);
        lookPitch = Mathf.Clamp(lookPitch + dy * perCount, -90f, 90f);
        return new Vector2(lookPitch, lookYaw);
    }

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
        }
    }

    // In Minecraft mode the player is Steve, also while a scene has him: MiSide's own player isn't
    // drawn (body, arms, what the hands hold), Minecraft draws Steve instead. MiSide turns parts of
    // him on again in its scenes, so what it turned on is turned off again once a second. Only
    // what was turned off here is turned on again (F6).
    private float bodyChecked;

    private void UpdateBody(bool hidden, float now)
    {
        if (player == null)
        {
            return;
        }

        if (hidden && hidingBody && now - bodyChecked < 1f)
        {
            return;
        }

        bodyChecked = now;
        SetBodyHidden(hidden);
    }

    private System.Collections.Generic.IEnumerable<Renderer> BodyRenderers()
    {
        foreach (var renderer in player.GetComponentsInChildren<Renderer>(true))
        {
            yield return renderer;
        }

        // The body and arms models may hang elsewhere in the scene than the player object.
        foreach (var part in new Component[] { player.scrpp, player.scrpah })
        {
            if (part != null && !part.transform.IsChildOf(player.transform))
            {
                foreach (var renderer in part.GetComponentsInChildren<Renderer>(true))
                {
                    yield return renderer;
                }
            }
        }
    }

    private void SetBodyHidden(bool hidden)
    {
        if (player == null || (!hidden && !hidingBody))
        {
            return;
        }

        if (hidden)
        {
            int before = hiddenRenderers.Count;
            foreach (var renderer in BodyRenderers())
            {
                if (renderer.enabled)
                {
                    renderer.enabled = false;
                    hiddenRenderers.Add(renderer);
                }
            }

            if (!hidingBody || hiddenRenderers.Count != before)
            {
                Plugin.Say($"body hidden ({hiddenRenderers.Count} renderers)");
            }

            hidingBody = true;
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
            hidingBody = false;
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
        t.rotation = Quaternion.Euler(0f, lookYaw, 0f);
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

        if (Possession.PlaceCamera(camera, lookPitch, lookYaw, haveMc ? mc.cameraMode : 0u))
        {
        }
        else if (driving)
        {
            // F5 like Minecraft's: behind Steve (1) or before him looking back (2), as far as
            // Minecraft's own camera is from his eyes after its zoom collision.
            var eye = Units.FromMc(mc.eyeX, mc.eyeY, mc.eyeZ);
            var look = Quaternion.Euler(lookPitch, lookYaw, 0f);
            float distance = mc.cameraDistance > 0.05f ? mc.cameraDistance / (float)Units.BlocksPerMetre : 4f;
            if (mc.cameraMode == 1)
            {
                camera.SetPositionAndRotation(eye - look * Vector3.forward * distance, look);
            }
            else if (mc.cameraMode == 2)
            {
                camera.SetPositionAndRotation(eye + look * Vector3.forward * distance, Quaternion.Euler(-lookPitch, lookYaw + 180f, 0f));
            }
            else
            {
                camera.SetPositionAndRotation(eye, look);
            }
        }

        // Where the camera is, for Minecraft's blocks drawn in 3D.
        var cam = camera.GetComponent<Camera>();
        // The blocks hide behind MiSide's walls by its depth texture: MiSide doesn't always have
        // its camera make one, and a stale one hid blocks by where the camera was before.
        if (cam != null && (cam.depthTextureMode & DepthTextureMode.Depth) == 0)
        {
            cam.depthTextureMode |= DepthTextureMode.Depth;
        }

        float tanHalf = 1f;
        if (cam != null)
        {
            tanHalf = Mathf.Tan(cam.fieldOfView * 0.5f * Mathf.Deg2Rad) * cam.aspect;
            Native.msc_set_near_plane((float)(cam.nearClipPlane * Units.BlocksPerMetre * Units.HostPerBlock));
        }

        if (cam != null)
        {
            SceneRenderer.Frame(cam, driving && haveMc && mc.cameraMode != 0);
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

// The swings of struck characters again after whatever sets their pose late (FinalIK's solvers,
// their look at the player): those run after our own LateUpdate and would undo them.
[HarmonyPatch]
internal static class LatePosePatch
{
    private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
    {
        foreach (var assembly in System.AppDomain.CurrentDomain.GetAssemblies())
        {
            string name = assembly.GetName().Name;
            if (name != "Assembly-CSharp" && name != "Assembly-CSharp-firstpass")
            {
                continue;
            }

            foreach (var typeName in new[] { "RootMotion.SolverManager", "Character_Look", "MitaPerson" })
            {
                var type = assembly.GetType(typeName);
                var late = type?.GetMethod("LateUpdate", System.Type.EmptyTypes);
                if (late != null && late.DeclaringType == type)
                {
                    yield return late;
                }
            }
        }
    }

    private static void Postfix()
    {
        Actors.ApplySways();
    }
}
