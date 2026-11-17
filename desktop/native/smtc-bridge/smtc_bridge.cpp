#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Media.Playback.h>
#include <winrt/Windows.Storage.Streams.h>

#include <chrono>
#include <mutex>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Media;
using namespace Windows::Media::Playback;
using namespace Windows::Storage::Streams;

namespace {

using ActionCallback = void(__stdcall*)(int);

constexpr int ACTION_PLAY = 0;
constexpr int ACTION_PAUSE = 1;
constexpr int ACTION_NEXT = 2;
constexpr int ACTION_PREVIOUS = 3;
constexpr int ACTION_STOP = 4;

constexpr int STATUS_STOPPED = 0;
constexpr int STATUS_PLAYING = 1;
constexpr int STATUS_PAUSED = 2;

std::mutex g_mutex;
MediaPlayer g_player{nullptr};
SystemMediaTransportControls g_smtc{nullptr};
event_token g_buttonToken{};
ActionCallback g_callback = nullptr;
bool g_initialized = false;

void HandleButton(SystemMediaTransportControls const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
    ActionCallback cb = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        cb = g_callback;
    }
    if (!cb) return;

    switch (args.Button()) {
        case SystemMediaTransportControlsButton::Play:
            cb(ACTION_PLAY);
            break;
        case SystemMediaTransportControlsButton::Pause:
            cb(ACTION_PAUSE);
            break;
        case SystemMediaTransportControlsButton::Next:
            cb(ACTION_NEXT);
            break;
        case SystemMediaTransportControlsButton::Previous:
            cb(ACTION_PREVIOUS);
            break;
        case SystemMediaTransportControlsButton::Stop:
            cb(ACTION_STOP);
            break;
        default:
            break;
    }
}

}  // namespace

extern "C" {

__declspec(dllexport) int __stdcall smtc_init(const wchar_t*, ActionCallback callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) {
        g_callback = callback;
        return 0;
    }
    try {
        init_apartment(apartment_type::multi_threaded);
    } catch (...) {
        // Ignore if COM apartment is already initialized on the calling thread.
    }

    try {
        g_callback = callback;
        g_player = MediaPlayer();
        g_player.CommandManager().IsEnabled(false);
        g_smtc = g_player.SystemMediaTransportControls();
        g_smtc.IsEnabled(true);
        g_smtc.IsPlayEnabled(true);
        g_smtc.IsPauseEnabled(true);
        g_smtc.IsNextEnabled(true);
        g_smtc.IsPreviousEnabled(true);
        g_smtc.IsStopEnabled(true);
        g_smtc.PlaybackStatus(MediaPlaybackStatus::Closed);
        g_buttonToken = g_smtc.ButtonPressed(&HandleButton);
        g_initialized = true;
        return 0;
    } catch (...) {
        g_smtc = nullptr;
        g_player = nullptr;
        g_callback = nullptr;
        return -1;
    }
}

__declspec(dllexport) void __stdcall smtc_update_metadata(
    const wchar_t* title,
    const wchar_t* artist,
    const wchar_t* album,
    const wchar_t* thumbnailUrl
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_smtc) return;

    try {
        auto updater = g_smtc.DisplayUpdater();
        updater.Type(MediaPlaybackType::Music);
        auto props = updater.MusicProperties();
        props.Title(title ? title : L"");
        props.Artist(artist ? artist : L"");
        props.AlbumTitle(album ? album : L"");
        if (thumbnailUrl && *thumbnailUrl) {
            try {
                updater.Thumbnail(RandomAccessStreamReference::CreateFromUri(Uri(thumbnailUrl)));
            } catch (...) {
                updater.Thumbnail(nullptr);
            }
        } else {
            updater.Thumbnail(nullptr);
        }
        updater.Update();
    } catch (...) {
    }
}

__declspec(dllexport) void __stdcall smtc_update_status(int statusCode) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_smtc) return;

    try {
        switch (statusCode) {
            case STATUS_PLAYING:
                g_smtc.PlaybackStatus(MediaPlaybackStatus::Playing);
                break;
            case STATUS_PAUSED:
                g_smtc.PlaybackStatus(MediaPlaybackStatus::Paused);
                break;
            case STATUS_STOPPED:
            default:
                g_smtc.PlaybackStatus(MediaPlaybackStatus::Stopped);
                break;
        }
    } catch (...) {
    }
}

__declspec(dllexport) void __stdcall smtc_update_timeline(long long positionMs, long long durationMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_smtc) return;

    try {
        SystemMediaTransportControlsTimelineProperties timeline;
        auto pos = std::chrono::milliseconds(positionMs > 0 ? positionMs : 0);
        auto dur = std::chrono::milliseconds(durationMs > 0 ? durationMs : 0);
        timeline.StartTime(TimeSpan::zero());
        timeline.MinSeekTime(TimeSpan::zero());
        timeline.Position(pos);
        timeline.EndTime(dur);
        timeline.MaxSeekTime(dur);
        g_smtc.UpdateTimelineProperties(timeline);
    } catch (...) {
    }
}

__declspec(dllexport) void __stdcall smtc_shutdown() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;

    try {
        if (g_smtc) {
            g_smtc.ButtonPressed(g_buttonToken);
            g_smtc.IsEnabled(false);
        }
        if (g_player) {
            g_player.Close();
        }
    } catch (...) {
    }
    g_smtc = nullptr;
    g_player = nullptr;
    g_callback = nullptr;
    g_initialized = false;
}

}  // extern "C"
