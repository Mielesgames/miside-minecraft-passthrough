using System;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiSideCraft;

// What Minecraft asks of MiSide (its event ring), and the clicks that belong to MiSide.
internal static class Events
{
    private const uint EvPlayerDied = 2;
    private const uint EvMenu = 15;

    private static bool pausedForMenu;
    private static float savedTimeScale = 1f;

    public static void Handle(GameController game, Transform camera)
    {
        while (Native.msc_read_event(out var ev))
        {
            switch (ev.type)
            {
                case EvMenu:
                    Menu(game, ev.flags);
                    break;
                case EvPlayerDied:
                    Plugin.Say("event: Steve died");
                    break;
                case 19:
                    Possession.Handle(ev);
                    break;
                case 20:
                    Actors.SweepMade();
                    break;
                case 6:
                    Actors.Command(ev, camera);
                    break;
                case Settings.EvSetting:
                    Settings.Apply(ev.formId, ev.flags);
                    break;
                default:
                    Actors.Handle(ev);
                    break;
            }
        }
    }

    // A button of Minecraft's pause menu that is MiSide's: 1 its own menu, 2 to the title, 3 quit.
    private static void Menu(GameController game, uint action)
    {
        Plugin.Say($"event: menu button {action}");
        Pause(false);
        switch (action)
        {
            case 1:
                if (game != null)
                {
                    game.FastMenuOpen();
                }
                break;
            case 2:
                SceneManager.LoadScene("SceneMenu");
                break;
            case 3:
                Application.Quit();
                break;
        }
    }

    // /tick in Minecraft: MiSide's time goes along (rate 10 is half speed, freeze stands still).
    // At Minecraft's normal speed MiSide's own time is left alone (its slow motion is its own).
    private static bool ticking;

    public static void FollowTicks(float tickMs)
    {
        if (pausedForMenu)
        {
            return;
        }

        bool normal = Mathf.Abs(tickMs - 50f) < 0.5f;
        if (normal)
        {
            if (ticking)
            {
                ticking = false;
                Time.timeScale = 1f;
                Plugin.Say("ticks: normal speed again");
            }

            return;
        }

        float scale = tickMs <= 0f ? 0f : Mathf.Clamp(50f / tickMs, 0.05f, 3f);
        if (!ticking || Mathf.Abs(Time.timeScale - scale) > 0.001f)
        {
            if (!ticking || Mathf.Abs(Time.timeScale - scale) > 0.05f)
            {
                Plugin.Say($"ticks: MiSide's time at {scale:F2}x");
            }

            ticking = true;
            Time.timeScale = scale;
        }
    }

    // Minecraft's pause menu is open: MiSide stands still under it.
    public static void Pause(bool paused)
    {
        if (paused == pausedForMenu)
        {
            return;
        }

        pausedForMenu = paused;
        if (paused)
        {
            savedTimeScale = Time.timeScale;
            Time.timeScale = 0f;
        }
        else
        {
            Time.timeScale = savedTimeScale > 0f ? savedTimeScale : 1f;
        }
    }

    // A right click outside Minecraft's screens: MiSide's when its player looks at something to use
    // (and Minecraft not at one of its own blocks), else Minecraft's.
    public static void UseClick(Transform camera, Transform player, uint heldToken, bool holdsBucket, uint mcFlags = 0)
    {
        if (!Native.msc_take_use_click())
        {
            return;
        }

        if (Native.msc_minecraft_targets_block())
        {
            Native.msc_forward_use_click();
            return;
        }

        // Steve's own ways with MiSide's things first: putting back, picking up, Minecraft doors.
        if (Hits.Use(camera, player, heldToken, holdsBucket, mcFlags))
        {
            Native.msc_consume_use_click();
            Native.msc_push_input(9, 0, 0, 0, 0); // kInSwing
            return;
        }

        var target = Looked(camera);
        if (target == null)
        {
            Native.msc_forward_use_click();
            return;
        }

        Plugin.Say($"use: {target.gameObject.name}");
        Native.msc_consume_use_click();
        Native.msc_push_input(9, 0, 0, 0, 0); // kInSwing: Steve's hand moves with it
        try
        {
            target.Click();
        }
        catch (Exception e)
        {
            Plugin.Say($"use failed: {e.Message}");
        }
    }

    // What MiSide's own look ray marked (castNow), or else the first thing to use within reach.
    private static ObjectInteractive Looked(Transform camera)
    {
        foreach (var interactive in UnityEngine.Object.FindObjectsOfType<ObjectInteractive>())
        {
            if (interactive != null && interactive.active && interactive.enabled && interactive.castNow)
            {
                return interactive;
            }
        }

        if (camera == null)
        {
            return null;
        }

        foreach (var hit in Physics.RaycastAll(camera.position, camera.forward, 2.5f, ~0, QueryTriggerInteraction.Collide))
        {
            var interactive = hit.collider.GetComponentInParent<ObjectInteractive>();
            if (interactive != null && interactive.active && interactive.enabled)
            {
                return interactive;
            }
        }

        return null;
    }
}
