using UnityEngine;

namespace MiSideCraft;

// MiSide's chasing legs (Location5_MitaLegs) catching Steve: the game's own catch didn't go off for
// him (it waits for its own player in a way that doesn't see Steve). Close to him for a moment, and
// the game not having caught him yet: its catch (eventStartPlayer, the jumpscare) is set off here.
internal static class Catch
{
    private const float Reach = 0.8f;
    private const float Hold = 0.5f;

    private static Location5_MitaLegs legs;
    private static float lastSearch = -10f;
    private static float closeSince = -1f;
    private static float lastLog;

    public static void Update(Vector3 player, bool steve)
    {
        float now = Time.realtimeSinceStartup;
        if (legs == null && now - lastSearch > 1f)
        {
            lastSearch = now;
            legs = Object.FindObjectOfType<Location5_MitaLegs>();
        }

        if (legs == null || !legs.isActiveAndEnabled)
        {
            closeSince = -1f;
            return;
        }

        var at = legs.nma != null ? legs.nma.transform.position : legs.transform.position;
        var flat = at - player;
        flat.y = 0f;
        float distance = flat.magnitude;
        if (now - lastLog > 2f)
        {
            lastLog = now;
            Plugin.Say($"legs: {distance:F2} m from the player, caught {legs.eventOk}, ending {legs.ending}, " +
                $"their player {(legs.player != null ? legs.player.name : "none")} at {(legs.player != null ? Vector3.Distance(legs.player.position, at).ToString("F2") : "?")} m");
        }

        if (!steve || legs.eventOk || legs.ending || distance > Reach)
        {
            closeSince = -1f;
            return;
        }

        if (closeSince < 0f)
        {
            closeSince = now;
        }

        if (now - closeSince < Hold)
        {
            return;
        }

        Plugin.Say("legs: they have Steve, the game's catch is set off");
        legs.eventOk = true;
        legs.eventStartPlayer?.Invoke();
        closeSince = -1f;
    }
}
