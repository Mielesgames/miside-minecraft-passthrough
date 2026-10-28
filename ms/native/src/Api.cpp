// The C interface the MiSide plugin (C#, BepInEx) calls. Everything Unity
// lives in the plugin; this DLL keeps what doesn't care about the engine: the
// shared memory, Minecraft's picture over the frame (Present hook), its blocks
// drawn in 3D, the keyboard and mouse, and starting Minecraft.

#include "Input.h"
#include "Launcher.h"
#include "Link.h"
#include "Overlay.h"
#include "World.h"

#include <DynamicOutput/DynamicOutput.hpp>

#include <Windows.h>

#include <cstdio>
#include <share.h>
#include <filesystem>
#include <mutex>

#define MSC_API extern "C" __declspec(dllexport)

namespace
{
    HostLink g_link;
    bool g_started = false;
    std::mutex g_log_mutex;
    std::FILE* g_log = nullptr;

    std::filesystem::path module_dir()
    {
        HMODULE self = nullptr;
        ::GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&module_dir),
            &self
        );
        wchar_t path[MAX_PATH]{};
        ::GetModuleFileNameW(self, path, MAX_PATH);
        return std::filesystem::path(path).parent_path();
    }
}

void RC::Output::write_line(LogLevel level, const std::wstring& text)
{
    std::lock_guard lock(g_log_mutex);

    if (g_log == nullptr)
    {
        g_log = _wfsopen((module_dir() / L"MiSideCraft.log").c_str(), L"w, ccs=UTF-8", _SH_DENYWR);
        if (g_log == nullptr)
        {
            return;
        }
    }

    const wchar_t* tag = level == LogLevel::Error ? L"[error] " : level == LogLevel::Warning ? L"[warn] " : L"";
    std::fwprintf(g_log, L"%s%s", tag, text.c_str());
    if (text.empty() || text.back() != L'\n')
    {
        std::fputwc(L'\n', g_log);
    }
    std::fflush(g_log);
}

// ---- start and stop

// Creates the shared memory and hooks the game's Present. True when both worked.
MSC_API bool msc_start()
{
    if (g_started)
    {
        return true;
    }

    if (!g_link.create())
    {
        RC::Output::send<RC::LogLevel::Error>(STR("MSC: the shared memory could not be created\n"));
        return false;
    }

    g_started = overlay::install(g_link);
    RC::Output::send<RC::LogLevel::Default>(STR("MSC: started (overlay hook {})\n"), g_started ? L"on" : L"off");
    return g_started;
}

MSC_API void msc_stop()
{
    if (!g_started)
    {
        return;
    }

    input::uninstall();
    overlay::uninstall();
    world::shutdown();
    g_started = false;
}

MSC_API void msc_log(const wchar_t* text)
{
    RC::Output::write_line(RC::LogLevel::Default, text != nullptr ? text : L"");
}

// ---- the link

MSC_API void msc_heartbeat()
{
    g_link.heartbeat();
}

MSC_API void msc_write_state(
    std::uint32_t flags,
    std::uint32_t world_id,
    std::uint32_t collision_epoch,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    std::uint32_t teleport_seq,
    std::uint32_t viewport_w,
    std::uint32_t viewport_h,
    float game_hour
)
{
    g_link.write_sky_state(flags, world_id, collision_epoch, x, y, z, yaw, pitch, teleport_seq, viewport_w, viewport_h, game_hour);
}

MSC_API void msc_push_input(std::uint16_t type, std::uint16_t code, std::int32_t a, std::int32_t b, std::int32_t c)
{
    g_link.push_input(type, code, a, b, c);
}

MSC_API bool msc_read_mc_state(skycraft::proto::McState* out)
{
    return out != nullptr && g_link.read_mc_state(*out);
}

MSC_API bool msc_read_event(skycraft::proto::McEvent* out)
{
    return out != nullptr && g_link.read_event(*out);
}

MSC_API bool msc_minecraft_targets_block()
{
    return g_link.minecraft_targets_block();
}

MSC_API bool msc_write_collision(std::uint32_t type, const void* header, std::uint64_t header_bytes, const void* body, std::uint64_t body_bytes)
{
    return g_link.write_collision(type, header, static_cast<std::size_t>(header_bytes), body, static_cast<std::size_t>(body_bytes));
}

// Start of the shared memory (the actor table and other blocks the plugin writes itself).
MSC_API std::uint8_t* msc_shared_base()
{
    return g_link.base();
}

// ---- keyboard and mouse (game thread)

MSC_API void msc_input_update(HWND window, bool minecraft_mode, bool screen_open, int viewport_w, int viewport_h)
{
    input::update(g_link, window, minecraft_mode, screen_open, viewport_w, viewport_h);
}

MSC_API bool msc_take_use_click()
{
    return input::take_use_click();
}

MSC_API void msc_forward_use_click()
{
    input::forward_use_click();
}

MSC_API void msc_consume_use_click()
{
    input::consume_use_click();
}

MSC_API bool msc_take_attack_click()
{
    return input::take_attack_click();
}

MSC_API bool msc_attack_held()
{
    return input::attack_held();
}

MSC_API bool msc_grab_held()
{
    return input::grab_held();
}

// ---- the overlay

// The cursor is input's (it moves it with the raw mouse).
MSC_API void msc_overlay_set(bool show, bool crosshair, int gui_scale)
{
    auto& state = overlay::state();
    state.show = show;
    state.crosshair = crosshair;
    state.gui_scale = gui_scale;
}

// The game's back buffer size and window, once the first frame was presented.
MSC_API void msc_overlay_viewport(int* width, int* height, HWND* window)
{
    auto& state = overlay::state();
    if (width != nullptr)
    {
        *width = state.viewport_w;
    }
    if (height != nullptr)
    {
        *height = state.viewport_h;
    }
    if (window != nullptr)
    {
        *window = state.window;
    }
}

MSC_API void msc_set_status(const wchar_t* text)
{
    overlay::set_status(text != nullptr ? text : L"");
}

// ---- Minecraft's things in 3D

// The game's camera, in the shared code's units and axes (see Units.cs in the plugin).
MSC_API void msc_set_camera(bool valid, const double* position, const float* forward, const float* right, const float* up, float tan_half_fov)
{
    world::Camera camera;
    camera.valid = valid;
    for (int i = 0; i < 3; ++i)
    {
        camera.position[i] = position[i];
        camera.forward[i] = forward[i];
        camera.right[i] = right[i];
        camera.up[i] = up[i];
    }
    camera.tan_half_fov = tan_half_fov;
    world::set_camera(camera);
}

MSC_API void msc_set_player_position(double x, double y, double z)
{
    world::set_player_position(x, y, z);
}

MSC_API void msc_set_near_plane(float host_units)
{
    world::set_near_plane(host_units);
}

MSC_API void msc_set_night_vision(bool on)
{
    world::set_night_vision(on);
}

MSC_API bool msc_solid_at(int x, int y, int z)
{
    return world::solid_at(x, y, z);
}

MSC_API int msc_hazard_at(int x, int y, int z)
{
    return world::hazard_at(x, y, z);
}

// ---- starting Minecraft

MSC_API bool msc_minecraft_running()
{
    return sbmp::launcher::minecraft_running();
}

// The bundled Minecraft (a Prism Launcher zip) in `folder`, unpacked and started. Off the game thread.
MSC_API bool msc_start_bundled(const wchar_t* folder)
{
    auto bundle = sbmp::launcher::bundle_next_to(folder);
    return !bundle.empty() && sbmp::launcher::start_bundled(bundle);
}

MSC_API bool msc_needs_sign_in()
{
    return sbmp::launcher::needs_sign_in();
}
