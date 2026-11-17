#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <shlobj.h>
#include <wrl.h>
#include <wrl/event.h>
#include "WebView2.h"

#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

using namespace Microsoft::WRL;

namespace {

constexpr UINT_PTR kPollTimerId = 1;
constexpr int kMaxPollAttempts = 120;

HWND g_hwnd = nullptr;
ComPtr<ICoreWebView2Controller> g_controller;
ComPtr<ICoreWebView2> g_webview;
bool g_switchChannel = false;
bool g_completed = false;
int g_exitCode = 1;
int g_pollAttempts = 0;
std::wstring g_currentUrl;

std::string WideToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    int len = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    if (len <= 0) return {};
    std::string out(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), out.data(), len, nullptr, nullptr);
    return out;
}

std::string JsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (unsigned char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

void WriteStdout(const std::string& line) {
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    if (hOut != nullptr && hOut != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(hOut, line.data(), static_cast<DWORD>(line.size()), &written, nullptr);
    }
}

std::wstring GetWebView2UserDataDir() {
    PWSTR localAppData = nullptr;
    std::wstring base;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &localAppData)) && localAppData) {
        base = localAppData;
        CoTaskMemFree(localAppData);
    } else {
        wchar_t tempBuf[MAX_PATH];
        GetTempPathW(MAX_PATH, tempBuf);
        base = tempBuf;
    }
    std::wstring dir = base + L"\\miliMusic\\webview2";
    CreateDirectoryW((base + L"\\miliMusic").c_str(), nullptr);
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

bool IsYouTubeMusicUrl(const std::wstring& url) {
    return url.rfind(L"https://music.youtube.com", 0) == 0;
}

void CompleteWithAuth(
    const std::string& cookie,
    const std::string& visitorData,
    const std::string& dataSyncId,
    const std::string& authUser
) {
    if (g_completed) return;
    g_completed = true;
    KillTimer(g_hwnd, kPollTimerId);

    std::string json =
        "{\"cookie\":\"" + JsonEscape(cookie) +
        "\",\"visitorData\":\"" + JsonEscape(visitorData) +
        "\",\"dataSyncId\":\"" + JsonEscape(dataSyncId) +
        "\",\"authUser\":\"" + JsonEscape(authUser.empty() ? "0" : authUser) +
        "\"}\n";
    WriteStdout(json);
    g_exitCode = 0;
    DestroyWindow(g_hwnd);
}

void HandlePageConfigMessage(const std::wstring& message) {
    if (g_completed || !g_webview) return;

    size_t p1 = message.find(L'\n');
    size_t p2 = (p1 == std::wstring::npos) ? std::wstring::npos : message.find(L'\n', p1 + 1);
    if (p1 == std::wstring::npos || p2 == std::wstring::npos) return;

    std::wstring visitorDataW = message.substr(0, p1);
    std::wstring dataSyncIdW = message.substr(p1 + 1, p2 - p1 - 1);
    std::wstring authUserW = message.substr(p2 + 1);
    if (visitorDataW.empty()) return;

    ComPtr<ICoreWebView2_2> webview2;
    if (FAILED(g_webview.As(&webview2)) || !webview2) return;

    ComPtr<ICoreWebView2CookieManager> cookieManager;
    if (FAILED(webview2->get_CookieManager(&cookieManager)) || !cookieManager) return;

    cookieManager->GetCookies(
        L"https://music.youtube.com",
        Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [visitorDataW, dataSyncIdW, authUserW](HRESULT result, ICoreWebView2CookieList* list) -> HRESULT {
                if (FAILED(result) || !list || g_completed) return S_OK;

                UINT count = 0;
                list->get_Count(&count);
                std::wstring cookieHeader;
                bool hasSapisid = false;

                for (UINT i = 0; i < count; ++i) {
                    ComPtr<ICoreWebView2Cookie> cookie;
                    if (FAILED(list->GetValueAtIndex(i, &cookie)) || !cookie) continue;

                    LPWSTR namePtr = nullptr;
                    LPWSTR valPtr = nullptr;
                    cookie->get_Name(&namePtr);
                    cookie->get_Value(&valPtr);

                    std::wstring name = namePtr ? namePtr : L"";
                    std::wstring val = valPtr ? valPtr : L"";
                    if (namePtr) CoTaskMemFree(namePtr);
                    if (valPtr) CoTaskMemFree(valPtr);

                    if (!name.empty()) {
                        if (!cookieHeader.empty()) cookieHeader += L"; ";
                        cookieHeader += name + L"=" + val;
                        if (name == L"SAPISID") hasSapisid = true;
                    }
                }

                if (hasSapisid) {
                    CompleteWithAuth(
                        WideToUtf8(cookieHeader),
                        WideToUtf8(visitorDataW),
                        WideToUtf8(dataSyncIdW),
                        WideToUtf8(authUserW)
                    );
                }
                return S_OK;
            }
        ).Get()
    );
}

void PollYouTubeMusicConfig() {
    if (g_completed || !g_webview || !IsYouTubeMusicUrl(g_currentUrl)) return;
    if (++g_pollAttempts > kMaxPollAttempts) {
        KillTimer(g_hwnd, kPollTimerId);
        return;
    }

    static constexpr wchar_t kScript[] =
        L"(function(){"
        L"var c=window.yt&&window.yt.config_;"
        L"if(!c||!c.VISITOR_DATA)return;"
        L"var ds=(c.DATASYNC_ID||'').split('||')[0];"
        L"var su=String(c.SESSION_INDEX!==undefined?c.SESSION_INDEX:'0').replace(/\\D/g,'')||'0';"
        L"window.chrome.webview.postMessage(c.VISITOR_DATA+'\\n'+ds+'\\n'+su);"
        L"})();";
    g_webview->ExecuteScript(kScript, nullptr);
}

void ResizeWebView() {
    if (!g_controller || !g_hwnd) return;
    RECT bounds{};
    GetClientRect(g_hwnd, &bounds);
    g_controller->put_Bounds(bounds);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_SIZE:
            ResizeWebView();
            return 0;
        case WM_TIMER:
            if (wParam == kPollTimerId) {
                PollYouTubeMusicConfig();
            }
            return 0;
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            KillTimer(hwnd, kPollTimerId);
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
}

void InitWebView2() {
    std::wstring userDataDir = GetWebView2UserDataDir();
    CreateCoreWebView2EnvironmentWithOptions(
        nullptr,
        userDataDir.c_str(),
        nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [](HRESULT envResult, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(envResult) || !env) {
                    MessageBoxW(
                        g_hwnd,
                        L"No se pudo inicializar Microsoft Edge WebView2.",
                        L"miliMusic Login",
                        MB_ICONERROR | MB_OK
                    );
                    DestroyWindow(g_hwnd);
                    return envResult;
                }

                env->CreateCoreWebView2Controller(
                    g_hwnd,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [](HRESULT ctrlResult, ICoreWebView2Controller* controller) -> HRESULT {
                            if (FAILED(ctrlResult) || !controller) {
                                DestroyWindow(g_hwnd);
                                return ctrlResult;
                            }

                            g_controller = controller;
                            g_controller->get_CoreWebView2(&g_webview);
                            ResizeWebView();

                            if (!g_webview) {
                                DestroyWindow(g_hwnd);
                                return E_FAIL;
                            }

                            EventRegistrationToken msgToken{};
                            g_webview->add_WebMessageReceived(
                                Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                    [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                        LPWSTR text = nullptr;
                                        if (SUCCEEDED(args->TryGetWebMessageAsString(&text)) && text) {
                                            HandlePageConfigMessage(text);
                                            CoTaskMemFree(text);
                                        }
                                        return S_OK;
                                    }
                                ).Get(),
                                &msgToken
                            );

                            EventRegistrationToken navToken{};
                            g_webview->add_NavigationCompleted(
                                Callback<ICoreWebView2NavigationCompletedEventHandler>(
                                    [](ICoreWebView2* sender, ICoreWebView2NavigationCompletedEventArgs*) -> HRESULT {
                                        LPWSTR urlPtr = nullptr;
                                        if (SUCCEEDED(sender->get_Source(&urlPtr)) && urlPtr) {
                                            g_currentUrl = urlPtr;
                                            CoTaskMemFree(urlPtr);
                                        }
                                        if (IsYouTubeMusicUrl(g_currentUrl)) {
                                            g_pollAttempts = 0;
                                            PollYouTubeMusicConfig();
                                            SetTimer(g_hwnd, kPollTimerId, 500, nullptr);
                                        } else {
                                            KillTimer(g_hwnd, kPollTimerId);
                                        }
                                        return S_OK;
                                    }
                                ).Get(),
                                &navToken
                            );

                            const wchar_t* targetUrl = g_switchChannel
                                ? L"https://music.youtube.com"
                                : L"https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com";

                            if (!g_switchChannel) {
                                ComPtr<ICoreWebView2_2> webview2;
                                if (SUCCEEDED(g_webview.As(&webview2)) && webview2) {
                                    ComPtr<ICoreWebView2CookieManager> cookieManager;
                                    if (SUCCEEDED(webview2->get_CookieManager(&cookieManager)) && cookieManager) {
                                        cookieManager->DeleteAllCookies();
                                    }
                                }
                            }

                            g_webview->Navigate(targetUrl);
                            return S_OK;
                        }
                    ).Get()
                );
                return S_OK;
            }
        ).Get()
    );
}

}  // namespace

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, LPWSTR lpCmdLine, int nCmdShow) {
    if (lpCmdLine && wcsstr(lpCmdLine, L"--switch") != nullptr) {
        g_switchChannel = true;
    }

    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WNDCLASSW wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = L"MiliMusicWebView2Login";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    RegisterClassW(&wc);

    g_hwnd = CreateWindowExW(
        0,
        wc.lpszClassName,
        g_switchChannel
            ? L"miliMusic \u2014 Cambiar canal de YouTube Music"
            : L"miliMusic \u2014 Iniciar sesi\u00f3n en YouTube Music",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        980,
        740,
        nullptr,
        nullptr,
        hInstance,
        nullptr
    );

    if (!g_hwnd) {
        CoUninitialize();
        return 1;
    }

    ShowWindow(g_hwnd, nCmdShow ? nCmdShow : SW_SHOW);
    UpdateWindow(g_hwnd);
    InitWebView2();

    MSG msg{};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    g_webview.Reset();
    g_controller.Reset();
    CoUninitialize();
    return g_exitCode;
}
