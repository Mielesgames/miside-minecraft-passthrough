#pragma once

// Stand-in for UE4SS' logging, so the parts shared with the Security Breach mod
// (overlay, world, input, launcher) build without UE4SS. Lines go to
// MiSideCraft.log next to the DLL.

#include <format>
#include <string>
#include <string_view>

#ifndef STR
#define STR(text) L##text
#endif

namespace RC
{
    enum class LogLevel
    {
        Default,
        Normal,
        Verbose,
        Warning,
        Error,
    };

    namespace Output
    {
        void write_line(LogLevel level, const std::wstring& text);

        template <LogLevel Level, typename... Args>
        void send(std::wstring_view format, Args&&... args)
        {
            try
            {
                write_line(Level, std::vformat(format, std::make_wformat_args(args...)));
            }
            catch (...)
            {
                write_line(Level, std::wstring(format));
            }
        }
    }
}
