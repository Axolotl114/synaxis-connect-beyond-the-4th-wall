// synaxis_cam_filter.cpp
// -----------------------------------------------------------------------------
// "Synaxis Camera" DirectShow video input device.
//
// This is the native half of CC Synaxis Virtual Coms' camera output. External
// programs (OBS, Zoom, browsers, ...) enumerate this DLL's filter as a video
// capture device; one DLL exposes a fixed pool of N instances ("Synaxis Camera
// 1" .. "Synaxis Camera N") that are registered as separate device monikers
// under CLSID_VideoInputDeviceCategory.
//
// Frames do not travel through COM from the game process: every filter instance
// opens a plain TCP connection to the mod's loopback frame server
// (127.0.0.1:25587 by default) and receives raw RGB24 frames. The Java side
// keeps only the newest frame, so a slow external program can never stall the
// game, and a missing game/server simply makes the device show a
// "no signal" placeholder instead of failing.
//
// The filter is written directly against the Windows SDK DirectShow headers
// (dshow.h/strmif.h) and deliberately does NOT use DirectShow BaseClasses:
// everything below (filter/pin/enumerators/allocator negotiation/registration)
// is implemented in this file. This keeps the native artifact self-contained and
// free of third-party source licensing questions.
//
// Registration (one UAC prompt) is performed by regsvr32 through
// DllRegisterServer/DllUnregisterServer:
//   regsvr32 /s synaxis_cam_filter.dll      (install, needs admin)
//   regsvr32 /u /s synaxis_cam_filter.dll   (uninstall)
//
// Build: native/virtual_camera/build.ps1 (MSVC x64, Visual Studio 2022).
// -----------------------------------------------------------------------------
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <dshow.h>
#include <strmif.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "quartz.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "uuid.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")

// {6A1F0B7E-3C62-4C61-9E11-5A4D1A7C0001}
static const CLSID CLSID_SynaxisCamFilter =
    {0x6A1F0B7E, 0x3C62, 0x4C61, {0x9E, 0x11, 0x5A, 0x4D, 0x1A, 0x7C, 0x00, 0x01}};

static const wchar_t* kClsidKey = L"CLSID\\{6A1F0B7E-3C62-4C61-9E11-5A4D1A7C0001}";
static const wchar_t* kFilterDisplayName = L"Synaxis Camera Filter";

/** Number of virtual camera devices registered by this DLL. Keep in sync with
 *  CameraTransmitterBlockEntity.MAX_DEVICES on the Java side. */
static const int kDeviceCount = 4;

/** Default loopback TCP port of the Java frame server. Keep in sync with
 *  com.ccvirtualcoms.client.camera.dshow.VirtualCameraTransport.DEFAULT_PORT. */
static const int kDefaultDataPort = 25587;

static const uint16_t kProtocolVersion = 1;
static const char kHelloMagic[4] = {'S', 'C', 'M', 'H'};
static const char kFrameMagic[4] = {'S', 'C', 'A', 'M'};

static const int kMaxFrameWidth = 1920;
static const int kMaxFrameHeight = 1080;
static const int kMaxFrameBytes = kMaxFrameWidth * kMaxFrameHeight * 3;

static HINSTANCE g_module = nullptr;
static LONG g_objectCount = 0;

#pragma pack(push, 1)
struct HelloMessage {
    char magic[4];
    uint16_t version;
    uint16_t slot;
    uint16_t width;
    uint16_t height;
    uint16_t fps;
    uint16_t format; // 0 = RGB24
};

struct FrameHeader {
    char magic[4];
    uint16_t version;
    uint16_t slot;
    uint32_t sequence;
    uint64_t timestampUs;
    uint16_t width;
    uint16_t height;
    uint16_t format; // 0 = RGB24
    uint32_t length;
};
#pragma pack(pop)

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

static void InitVideoInfo(VIDEOINFOHEADER* info, int width, int height, int fps)
{
    ZeroMemory(info, sizeof(*info));
    info->AvgTimePerFrame = 10000000LL / (fps > 0 ? fps : 30);
    info->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info->bmiHeader.biWidth = width;
    info->bmiHeader.biHeight = height;
    info->bmiHeader.biPlanes = 1;
    info->bmiHeader.biBitCount = 24;
    info->bmiHeader.biCompression = BI_RGB;
    info->bmiHeader.biSizeImage = width * height * 3;
}

static void FillMediaType(AM_MEDIA_TYPE* mediaType, int width, int height, int fps)
{
    ZeroMemory(mediaType, sizeof(*mediaType));
    mediaType->majortype = MEDIATYPE_Video;
    mediaType->subtype = MEDIASUBTYPE_RGB24;
    mediaType->bFixedSizeSamples = TRUE;
    mediaType->bTemporalCompression = FALSE;
    mediaType->lSampleSize = static_cast<LONG>(width) * height * 3;
    mediaType->formattype = FORMAT_VideoInfo;
    mediaType->cbFormat = sizeof(VIDEOINFOHEADER);
    auto* info = static_cast<VIDEOINFOHEADER*>(CoTaskMemAlloc(sizeof(VIDEOINFOHEADER)));
    if (info != nullptr) {
        InitVideoInfo(info, width, height, fps);
        mediaType->pbFormat = reinterpret_cast<BYTE*>(info);
    } else {
        mediaType->cbFormat = 0;
    }
}

static void FreeMediaType(AM_MEDIA_TYPE* mediaType)
{
    if (mediaType->pbFormat != nullptr) {
        CoTaskMemFree(mediaType->pbFormat);
        mediaType->pbFormat = nullptr;
    }
    if (mediaType->pUnk != nullptr) {
        mediaType->pUnk->Release();
        mediaType->pUnk = nullptr;
    }
}

static bool CopyMediaType(AM_MEDIA_TYPE* destination, const AM_MEDIA_TYPE* source)
{
    *destination = *source;
    if (source->pbFormat != nullptr && source->cbFormat > 0) {
        destination->pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(source->cbFormat));
        if (destination->pbFormat == nullptr) {
            return false;
        }
        memcpy(destination->pbFormat, source->pbFormat, source->cbFormat);
    } else {
        destination->pbFormat = nullptr;
        destination->cbFormat = 0;
    }
    if (source->pUnk != nullptr) {
        source->pUnk->AddRef();
    }
    return true;
}

static bool AcceptFormat(const AM_MEDIA_TYPE* mediaType)
{
    if (mediaType == nullptr) {
        return true;
    }
    if (mediaType->majortype != MEDIATYPE_Video || mediaType->formattype != FORMAT_VideoInfo) {
        return false;
    }
    if (mediaType->subtype != MEDIASUBTYPE_RGB24) {
        return false;
    }
    if (mediaType->cbFormat < sizeof(VIDEOINFOHEADER) || mediaType->pbFormat == nullptr) {
        return false;
    }
    const auto* info = reinterpret_cast<const VIDEOINFOHEADER*>(mediaType->pbFormat);
    if (info->bmiHeader.biBitCount != 24 || info->bmiHeader.biCompression != BI_RGB) {
        return false;
    }
    if (info->bmiHeader.biWidth <= 0 || info->bmiHeader.biHeight == 0) {
        return false;
    }
    if (info->bmiHeader.biWidth > kMaxFrameWidth || abs(info->bmiHeader.biHeight) > kMaxFrameHeight) {
        return false;
    }
    return true;
}

static int FrameSizeFor(const AM_MEDIA_TYPE& mediaType)
{
    if (mediaType.pbFormat == nullptr || mediaType.cbFormat < sizeof(VIDEOINFOHEADER)) {
        return 640 * 480 * 3;
    }
    const auto* info = reinterpret_cast<const VIDEOINFOHEADER*>(mediaType.pbFormat);
    return static_cast<int>(info->bmiHeader.biWidth) * abs(info->bmiHeader.biHeight) * 3;
}

static int WidthFor(const AM_MEDIA_TYPE& mediaType)
{
    if (mediaType.pbFormat == nullptr) {
        return 640;
    }
    return reinterpret_cast<const VIDEOINFOHEADER*>(mediaType.pbFormat)->bmiHeader.biWidth;
}

static int HeightFor(const AM_MEDIA_TYPE& mediaType)
{
    if (mediaType.pbFormat == nullptr) {
        return 480;
    }
    return abs(reinterpret_cast<const VIDEOINFOHEADER*>(mediaType.pbFormat)->bmiHeader.biHeight);
}

static int FpsFor(const AM_MEDIA_TYPE& mediaType)
{
    if (mediaType.pbFormat == nullptr) {
        return 30;
    }
    const REFERENCE_TIME interval =
        reinterpret_cast<const VIDEOINFOHEADER*>(mediaType.pbFormat)->AvgTimePerFrame;
    if (interval <= 0) {
        return 30;
    }
    int fps = static_cast<int>(10000000LL / interval);
    if (fps < 1) {
        fps = 1;
    }
    if (fps > 60) {
        fps = 60;
    }
    return fps;
}

// ---------------------------------------------------------------------------
// Placeholder ("no signal") frame, drawn with GDI into a 32 bit DIB section.
// ---------------------------------------------------------------------------

static void BuildPlaceholderFrame(int width, int height, int slot, std::vector<BYTE>& destination)
{
    if (width <= 0 || height <= 0 || width > kMaxFrameWidth || height > kMaxFrameHeight) {
        width = 640;
        height = 480;
    }
    destination.resize(static_cast<size_t>(width) * height * 3);

    BITMAPINFO bitmapInfo{};
    bitmapInfo.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bitmapInfo.bmiHeader.biWidth = width;
    bitmapInfo.bmiHeader.biHeight = -height; // top-down
    bitmapInfo.bmiHeader.biPlanes = 1;
    bitmapInfo.bmiHeader.biBitCount = 32;
    bitmapInfo.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HDC screen = GetDC(nullptr);
    HDC memory = CreateCompatibleDC(screen);
    HBITMAP bitmap = CreateDIBSection(memory, &bitmapInfo, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (bitmap == nullptr || bits == nullptr) {
        memset(destination.data(), 0, destination.size());
        if (bitmap != nullptr) {
            DeleteObject(bitmap);
        }
        DeleteDC(memory);
        ReleaseDC(nullptr, screen);
        return;
    }

    HGDIOBJ oldBitmap = SelectObject(memory, bitmap);
    RECT client{0, 0, width, height};
    HBRUSH background = CreateSolidBrush(RGB(12, 12, 16));
    FillRect(memory, &client, background);
    DeleteObject(background);

    // Subtle grid so the device is not a pure black screen.
    HPEN gridPen = CreatePen(PS_SOLID, 1, RGB(32, 32, 40));
    HGDIOBJ oldPen = SelectObject(memory, gridPen);
    const int grid = max(16, height / 24);
    for (int x = 0; x < width; x += grid) {
        MoveToEx(memory, x, 0, nullptr);
        LineTo(memory, x, height);
    }
    for (int y = 0; y < height; y += grid) {
        MoveToEx(memory, 0, y, nullptr);
        LineTo(memory, width, y);
    }
    SelectObject(memory, oldPen);
    DeleteObject(gridPen);

    SetBkMode(memory, TRANSPARENT);
    const int titleSize = max(14, min(height / 7, width / 16));
    HFONT titleFont = CreateFontW(-titleSize, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                                  OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                                  DEFAULT_PITCH | FF_SWISS, L"Consolas");
    HGDIOBJ oldFont = SelectObject(memory, titleFont);
    SetTextColor(memory, RGB(235, 235, 235));
    wchar_t title[128];
    swprintf_s(title, L"Synaxis Camera %d", slot + 1);
    RECT titleRect{0, height / 2 - titleSize * 2, width, height / 2};
    DrawTextW(memory, title, -1, &titleRect, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    const int subtitleSize = max(10, titleSize / 2);
    HFONT subtitleFont = CreateFontW(-subtitleSize, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                     DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                     CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_SWISS, L"Consolas");
    SelectObject(memory, subtitleFont);
    SetTextColor(memory, RGB(160, 160, 170));
    RECT subtitleRect{0, height / 2 + subtitleSize, width, height / 2 + subtitleSize * 3};
    DrawTextW(memory, L"no signal - start the game and place a camera", -1, &subtitleRect,
              DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    SelectObject(memory, oldFont);
    DeleteObject(titleFont);
    DeleteObject(subtitleFont);

    // DIB is BGRA; the DirectShow format is tightly packed RGB24 (top row first).
    const auto* source = static_cast<const BYTE*>(bits);
    BYTE* target = destination.data();
    for (int y = 0; y < height; y++) {
        const BYTE* sourceRow = source + static_cast<size_t>(y) * width * 4;
        BYTE* targetRow = target + static_cast<size_t>(y) * width * 3;
        for (int x = 0; x < width; x++) {
            targetRow[x * 3 + 0] = sourceRow[x * 4 + 0];
            targetRow[x * 3 + 1] = sourceRow[x * 4 + 1];
            targetRow[x * 3 + 2] = sourceRow[x * 4 + 2];
        }
    }

    SelectObject(memory, oldBitmap);
    DeleteObject(bitmap);
    DeleteDC(memory);
    ReleaseDC(nullptr, screen);
}

// ---------------------------------------------------------------------------
// Forward declarations
// ---------------------------------------------------------------------------

class CFilter;
class CPin;
class CEnumPins;
class CEnumMediaTypes;

// ---------------------------------------------------------------------------
// CFilter - IBaseFilter + IPersistPropertyBag
// ---------------------------------------------------------------------------

class CFilter : public IBaseFilter, public IPersistPropertyBag {
public:
    CFilter();
    CPin* Pin() const { return m_pin; }
    int Slot() const { return m_slot; }
    int DataPort() const { return m_dataPort; }

    // IUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_ref); }
    ULONG STDMETHODCALLTYPE Release() override;

    // IPersist
    HRESULT STDMETHODCALLTYPE GetClassID(CLSID* clsid) override
    {
        if (clsid == nullptr) {
            return E_POINTER;
        }
        *clsid = CLSID_SynaxisCamFilter;
        return S_OK;
    }

    // IMediaFilter
    HRESULT STDMETHODCALLTYPE Stop() override;
    HRESULT STDMETHODCALLTYPE Pause() override;
    HRESULT STDMETHODCALLTYPE Run(REFERENCE_TIME startTime) override;
    HRESULT STDMETHODCALLTYPE GetState(DWORD milliseconds, FILTER_STATE* state) override;
    HRESULT STDMETHODCALLTYPE SetSyncSource(IReferenceClock* clock) override;
    HRESULT STDMETHODCALLTYPE GetSyncSource(IReferenceClock** clock) override;

    // IBaseFilter
    HRESULT STDMETHODCALLTYPE EnumPins(IEnumPins** enumerator) override;
    HRESULT STDMETHODCALLTYPE FindPin(LPCWSTR id, IPin** pin) override;
    HRESULT STDMETHODCALLTYPE QueryFilterInfo(FILTER_INFO* info) override;
    HRESULT STDMETHODCALLTYPE JoinFilterGraph(IFilterGraph* graph, LPCWSTR name) override;
    HRESULT STDMETHODCALLTYPE QueryVendorInfo(LPWSTR* vendor) override
    {
        if (vendor != nullptr) {
            *vendor = nullptr;
        }
        return E_NOTIMPL;
    }

    // IPersistPropertyBag: instance identity ("CameraIndex", "DataPort").
    HRESULT STDMETHODCALLTYPE InitNew() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE Load(IPropertyBag* bag, IErrorLog* errorLog) override;
    HRESULT STDMETHODCALLTYPE Save(IPropertyBag* bag, BOOL clearDirty, BOOL saveAll) override
    {
        (void)bag;
        (void)clearDirty;
        (void)saveAll;
        return S_OK;
    }

private:
    ~CFilter();
    LONG m_ref;
    FILTER_STATE m_state;
    IFilterGraph* m_graph;
    IReferenceClock* m_clock;
    CPin* m_pin;
    WCHAR m_name[128];
    int m_slot;
    int m_dataPort;
};

// ---------------------------------------------------------------------------
// CPin - IPin + IMemInputPin + IAMStreamConfig (push source output pin)
// ---------------------------------------------------------------------------

class CPin : public IPin, public IMemInputPin, public IAMStreamConfig {
public:
    explicit CPin(CFilter* filter);
    CFilter* Filter() const { return m_filter; }
    const AM_MEDIA_TYPE& MediaType() const { return m_mediaType; }

    /** Starts the loopback frame pump (called from IMediaFilter::Run). */
    HRESULT StartStreaming();
    /** Stops the frame pump but keeps the pin connected (called from Stop/Pause). */
    void PauseStreaming();

    // IUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_ref); }
    ULONG STDMETHODCALLTYPE Release() override;

    // IPin
    HRESULT STDMETHODCALLTYPE Connect(IPin* receivePin, const AM_MEDIA_TYPE* mediaType) override;
    HRESULT STDMETHODCALLTYPE ReceiveConnection(IPin* connector, const AM_MEDIA_TYPE* mediaType) override;
    HRESULT STDMETHODCALLTYPE Disconnect() override;
    HRESULT STDMETHODCALLTYPE ConnectedTo(IPin** pin) override;
    HRESULT STDMETHODCALLTYPE ConnectionMediaType(AM_MEDIA_TYPE* mediaType) override;
    HRESULT STDMETHODCALLTYPE QueryPinInfo(PIN_INFO* info) override;
    HRESULT STDMETHODCALLTYPE QueryDirection(PIN_DIRECTION* direction) override;
    HRESULT STDMETHODCALLTYPE QueryId(LPWSTR* id) override;
    HRESULT STDMETHODCALLTYPE QueryAccept(const AM_MEDIA_TYPE* mediaType) override;
    HRESULT STDMETHODCALLTYPE EnumMediaTypes(IEnumMediaTypes** enumerator) override;
    HRESULT STDMETHODCALLTYPE QueryInternalConnections(IPin** pins, ULONG* pinCount) override;
    HRESULT STDMETHODCALLTYPE EndOfStream() override;
    HRESULT STDMETHODCALLTYPE BeginFlush() override;
    HRESULT STDMETHODCALLTYPE EndFlush() override;
    HRESULT STDMETHODCALLTYPE NewSegment(REFERENCE_TIME start, REFERENCE_TIME stop, double rate) override;

    // IMemInputPin (a push-source output pin exposes this for allocator negotiation)
    HRESULT STDMETHODCALLTYPE GetAllocator(IMemAllocator** allocator) override;
    HRESULT STDMETHODCALLTYPE NotifyAllocator(IMemAllocator* allocator, BOOL readOnly) override;
    HRESULT STDMETHODCALLTYPE GetAllocatorRequirements(ALLOCATOR_PROPERTIES* properties) override;
    HRESULT STDMETHODCALLTYPE Receive(IMediaSample* sample) override
    {
        (void)sample;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE ReceiveMultiple(IMediaSample** samples, long count, long* processed) override
    {
        (void)samples;
        if (processed != nullptr) {
            *processed = 0;
        }
        (void)count;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE ReceiveCanBlock() override { return S_FALSE; }

    // IAMStreamConfig
    HRESULT STDMETHODCALLTYPE SetFormat(AM_MEDIA_TYPE* mediaType) override;
    HRESULT STDMETHODCALLTYPE GetFormat(AM_MEDIA_TYPE** mediaType) override;
    HRESULT STDMETHODCALLTYPE GetNumberOfCapabilities(int* count, int* size) override;
    HRESULT STDMETHODCALLTYPE GetStreamCaps(int index, AM_MEDIA_TYPE** mediaType, BYTE* capabilities) override;

private:
    ~CPin();
    HRESULT DecideAllocator(IPin* receivePin);
    HRESULT CheckConnect(IPin* receivePin);
    void BreakConnect();
    void StopStreaming();
    DWORD ThreadMain();
    static DWORD WINAPI ThreadProc(LPVOID parameter);
    bool EnsureConnected();
    void DisconnectSocket();
    bool ReadExact(void* buffer, int bytes);
    bool WriteExact(const void* buffer, int bytes);
    bool DeliverFrame(const BYTE* data, int width, int height);
    void DeliverOfflineFrame();
    int FrameInterval100ns() const;
    const AM_MEDIA_TYPE& CurrentMediaType() const { return m_mediaType; }

    LONG m_ref;
    CFilter* m_filter;
    AM_MEDIA_TYPE m_mediaType;
    bool m_hasMediaType;
    IPin* m_receivePin;
    IMemInputPin* m_inputPin;
    IMemAllocator* m_allocator;
    bool m_connected;
    bool m_connectedViaUs;

    HANDLE m_stopEvent;
    HANDLE m_thread;
    LONG m_streaming;
    SOCKET m_socket;
    REFERENCE_TIME m_nextFrameTime;
    bool m_sentFirstFrame;
    std::vector<BYTE> m_placeholder;
    DWORD m_lastOfflineTick;
    DWORD m_lastConnectAttempt;
};

// ---------------------------------------------------------------------------
// CEnumPins / CEnumMediaTypes
// ---------------------------------------------------------------------------

class CEnumPins : public IEnumPins {
public:
    explicit CEnumPins(CFilter* filter);
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override
    {
        if (ppv == nullptr) {
            return E_POINTER;
        }
        if (riid == IID_IUnknown || riid == IID_IEnumPins) {
            *ppv = static_cast<IEnumPins*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_ref); }
    ULONG STDMETHODCALLTYPE Release() override
    {
        LONG count = InterlockedDecrement(&m_ref);
        if (count == 0) {
            m_filter->Release();
            delete this;
        }
        return count;
    }
    HRESULT STDMETHODCALLTYPE Next(ULONG count, IPin** pins, ULONG* fetched) override;
    HRESULT STDMETHODCALLTYPE Skip(ULONG count) override
    {
        m_position += count;
        return m_position <= 1 ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE Reset() override
    {
        m_position = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Clone(IEnumPins** enumerator) override;

private:
    ~CEnumPins() {}
    LONG m_ref;
    CFilter* m_filter;
    ULONG m_position;
};

class CEnumMediaTypes : public IEnumMediaTypes {
public:
    explicit CEnumMediaTypes(CPin* pin);
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override
    {
        if (ppv == nullptr) {
            return E_POINTER;
        }
        if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
            *ppv = static_cast<IEnumMediaTypes*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_ref); }
    ULONG STDMETHODCALLTYPE Release() override
    {
        LONG count = InterlockedDecrement(&m_ref);
        if (count == 0) {
            m_pin->Release();
            delete this;
        }
        return count;
    }
    HRESULT STDMETHODCALLTYPE Next(ULONG count, AM_MEDIA_TYPE** mediaTypes, ULONG* fetched) override;
    HRESULT STDMETHODCALLTYPE Skip(ULONG count) override
    {
        m_position += count;
        return m_position <= 1 ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE Reset() override
    {
        m_position = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Clone(IEnumMediaTypes** enumerator) override;

private:
    ~CEnumMediaTypes() {}
    LONG m_ref;
    CPin* m_pin;
    ULONG m_position;
};

// ---------------------------------------------------------------------------
// CFilter implementation
// ---------------------------------------------------------------------------

CFilter::CFilter()
    : m_ref(1), m_state(State_Stopped), m_graph(nullptr), m_clock(nullptr), m_pin(nullptr),
      m_slot(0), m_dataPort(kDefaultDataPort)
{
    wcscpy_s(m_name, kFilterDisplayName);
    m_pin = new CPin(this);
}

CFilter::~CFilter()
{
    if (m_pin != nullptr) {
        m_pin->Release();
        m_pin = nullptr;
    }
    if (m_graph != nullptr) {
        m_graph->Release();
    }
    if (m_clock != nullptr) {
        m_clock->Release();
    }
}

ULONG STDMETHODCALLTYPE CFilter::Release()
{
    LONG count = InterlockedDecrement(&m_ref);
    if (count == 0) {
        delete this;
    }
    return count;
}

HRESULT STDMETHODCALLTYPE CFilter::QueryInterface(REFIID riid, void** ppv)
{
    if (ppv == nullptr) {
        return E_POINTER;
    }
    if (riid == IID_IUnknown || riid == IID_IBaseFilter || riid == IID_IMediaFilter) {
        *ppv = static_cast<IBaseFilter*>(this);
    } else if (riid == IID_IPersist || riid == IID_IPersistPropertyBag) {
        *ppv = static_cast<IPersistPropertyBag*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::Stop()
{
    if (m_pin != nullptr) {
        m_pin->PauseStreaming();
    }
    m_state = State_Stopped;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::Pause()
{
    m_state = State_Paused;
    if (m_pin != nullptr) {
        m_pin->PauseStreaming();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::Run(REFERENCE_TIME startTime)
{
    (void)startTime;
    m_state = State_Running;
    if (m_pin != nullptr) {
        return m_pin->StartStreaming();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::GetState(DWORD milliseconds, FILTER_STATE* state)
{
    (void)milliseconds;
    if (state == nullptr) {
        return E_POINTER;
    }
    *state = m_state;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::SetSyncSource(IReferenceClock* clock)
{
    if (m_clock != nullptr) {
        m_clock->Release();
    }
    m_clock = clock;
    if (m_clock != nullptr) {
        m_clock->AddRef();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::GetSyncSource(IReferenceClock** clock)
{
    if (clock == nullptr) {
        return E_POINTER;
    }
    *clock = m_clock;
    if (m_clock != nullptr) {
        m_clock->AddRef();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::EnumPins(IEnumPins** enumerator)
{
    if (enumerator == nullptr) {
        return E_POINTER;
    }
    *enumerator = new CEnumPins(this);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::FindPin(LPCWSTR id, IPin** pin)
{
    if (id == nullptr || pin == nullptr) {
        return E_POINTER;
    }
    *pin = nullptr;
    if (_wcsicmp(id, L"Output") == 0 && m_pin != nullptr) {
        *pin = m_pin;
        m_pin->AddRef();
        return S_OK;
    }
    return VFW_E_NOT_FOUND;
}

HRESULT STDMETHODCALLTYPE CFilter::QueryFilterInfo(FILTER_INFO* info)
{
    if (info == nullptr) {
        return E_POINTER;
    }
    wcscpy_s(info->achName, m_name);
    info->pGraph = m_graph;
    if (m_graph != nullptr) {
        m_graph->AddRef();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::JoinFilterGraph(IFilterGraph* graph, LPCWSTR name)
{
    if (m_graph != nullptr) {
        m_graph->Release();
        m_graph = nullptr;
    }
    m_graph = graph;
    if (m_graph != nullptr) {
        m_graph->AddRef();
    }
    if (name != nullptr) {
        wcscpy_s(m_name, name);
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CFilter::Load(IPropertyBag* bag, IErrorLog* errorLog)
{
    (void)errorLog;
    if (bag == nullptr) {
        return E_POINTER;
    }
    VARIANT value;
    VariantInit(&value);
    if (SUCCEEDED(bag->Read(L"CameraIndex", &value, nullptr))) {
        int slot = -1;
        if (value.vt == VT_I4) {
            slot = value.lVal;
        } else if (value.vt == VT_BSTR && value.bstrVal != nullptr) {
            slot = _wtoi(value.bstrVal);
        }
        if (slot >= 0 && slot < kDeviceCount) {
            m_slot = slot;
        }
        VariantClear(&value);
    }
    VariantInit(&value);
    if (SUCCEEDED(bag->Read(L"DataPort", &value, nullptr))) {
        int port = -1;
        if (value.vt == VT_I4) {
            port = value.lVal;
        } else if (value.vt == VT_BSTR && value.bstrVal != nullptr) {
            port = _wtoi(value.bstrVal);
        }
        if (port > 0 && port < 65536) {
            m_dataPort = port;
        }
        VariantClear(&value);
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// CPin implementation
// ---------------------------------------------------------------------------

CPin::CPin(CFilter* filter)
    : m_ref(1), m_filter(filter), m_mediaType{}, m_hasMediaType(false), m_receivePin(nullptr),
      m_inputPin(nullptr), m_allocator(nullptr), m_connected(false), m_connectedViaUs(false),
      m_stopEvent(nullptr), m_thread(nullptr), m_streaming(0), m_socket(INVALID_SOCKET),
      m_nextFrameTime(0), m_sentFirstFrame(false), m_lastOfflineTick(0),
      m_lastConnectAttempt(0)
{
    FillMediaType(&m_mediaType, 640, 480, 30);
    m_hasMediaType = true;
}

CPin::~CPin()
{
    StopStreaming();
    BreakConnect();
    FreeMediaType(&m_mediaType);
}

ULONG STDMETHODCALLTYPE CPin::Release()
{
    LONG count = InterlockedDecrement(&m_ref);
    if (count == 0) {
        delete this;
    }
    return count;
}

HRESULT STDMETHODCALLTYPE CPin::QueryInterface(REFIID riid, void** ppv)
{
    if (ppv == nullptr) {
        return E_POINTER;
    }
    if (riid == IID_IUnknown || riid == IID_IPin) {
        *ppv = static_cast<IPin*>(this);
    } else if (riid == IID_IMemInputPin) {
        *ppv = static_cast<IMemInputPin*>(this);
    } else if (riid == IID_IAMStreamConfig) {
        *ppv = static_cast<IAMStreamConfig*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

HRESULT CPin::CheckConnect(IPin* receivePin)
{
    if (receivePin == nullptr) {
        return E_POINTER;
    }
    PIN_DIRECTION direction = PINDIR_OUTPUT;
    HRESULT hr = receivePin->QueryDirection(&direction);
    if (FAILED(hr)) {
        return hr;
    }
    if (direction != PINDIR_INPUT) {
        return E_INVALIDARG;
    }
    return S_OK;
}

HRESULT CPin::DecideAllocator(IPin* receivePin)
{
    IMemInputPin* input = nullptr;
    HRESULT hr = receivePin->QueryInterface(IID_IMemInputPin, reinterpret_cast<void**>(&input));
    if (FAILED(hr) || input == nullptr) {
        return VFW_E_NO_ALLOCATOR;
    }
    IMemAllocator* allocator = nullptr;
    if (FAILED(input->GetAllocator(&allocator)) || allocator == nullptr) {
        hr = CoCreateInstance(CLSID_MemoryAllocator, nullptr, CLSCTX_INPROC_SERVER,
                              IID_IMemAllocator, reinterpret_cast<void**>(&allocator));
        if (FAILED(hr) || allocator == nullptr) {
            input->Release();
            return VFW_E_NO_ALLOCATOR;
        }
    }
    ALLOCATOR_PROPERTIES requested{};
    requested.cBuffers = 3;
    requested.cbBuffer = FrameSizeFor(m_mediaType);
    requested.cbAlign = 1;
    requested.cbPrefix = 0;
    ALLOCATOR_PROPERTIES actual{};
    hr = allocator->SetProperties(&requested, &actual);
    if (SUCCEEDED(hr)) {
        hr = input->NotifyAllocator(allocator, FALSE);
    }
    if (FAILED(hr)) {
        allocator->Release();
        input->Release();
        return hr;
    }
    if (m_allocator != nullptr) {
        m_allocator->Release();
    }
    if (m_inputPin != nullptr) {
        m_inputPin->Release();
    }
    m_allocator = allocator;
    m_inputPin = input;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::Connect(IPin* receivePin, const AM_MEDIA_TYPE* mediaType)
{
    if (m_connected) {
        return VFW_E_ALREADY_CONNECTED;
    }
    HRESULT hr = CheckConnect(receivePin);
    if (FAILED(hr)) {
        return hr;
    }
    AM_MEDIA_TYPE proposed{};
    ZeroMemory(&proposed, sizeof(proposed));
    if (mediaType != nullptr) {
        if (!AcceptFormat(mediaType)) {
            return VFW_E_TYPE_NOT_ACCEPTED;
        }
        if (!CopyMediaType(&proposed, mediaType)) {
            return E_OUTOFMEMORY;
        }
    } else {
        FillMediaType(&proposed, WidthFor(m_mediaType), HeightFor(m_mediaType), FpsFor(m_mediaType));
    }
    hr = receivePin->ReceiveConnection(this, &proposed);
    FreeMediaType(&proposed);
    if (FAILED(hr)) {
        return hr;
    }
    m_receivePin = receivePin;
    m_receivePin->AddRef();
    m_connectedViaUs = true;
    if (mediaType != nullptr) {
        FreeMediaType(&m_mediaType);
        if (!CopyMediaType(&m_mediaType, mediaType)) {
            BreakConnect();
            return E_OUTOFMEMORY;
        }
        m_hasMediaType = true;
    }
    hr = DecideAllocator(receivePin);
    if (FAILED(hr)) {
        BreakConnect();
        return hr;
    }
    m_connected = true;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::ReceiveConnection(IPin* connector, const AM_MEDIA_TYPE* mediaType)
{
    (void)connector;
    (void)mediaType;
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CPin::Disconnect()
{
    if (!m_connected) {
        return S_FALSE;
    }
    StopStreaming();
    if (m_allocator != nullptr) {
        m_allocator->Decommit();
    }
    if (m_connectedViaUs && m_receivePin != nullptr) {
        m_receivePin->Disconnect();
    }
    BreakConnect();
    m_connected = false;
    return S_OK;
}

void CPin::BreakConnect()
{
    if (m_allocator != nullptr) {
        m_allocator->Release();
        m_allocator = nullptr;
    }
    if (m_inputPin != nullptr) {
        m_inputPin->Release();
        m_inputPin = nullptr;
    }
    if (m_receivePin != nullptr) {
        m_receivePin->Release();
        m_receivePin = nullptr;
    }
    m_connected = false;
    m_connectedViaUs = false;
}

HRESULT STDMETHODCALLTYPE CPin::ConnectedTo(IPin** pin)
{
    if (pin == nullptr) {
        return E_POINTER;
    }
    *pin = m_receivePin;
    if (m_receivePin != nullptr) {
        m_receivePin->AddRef();
        return S_OK;
    }
    return VFW_E_NOT_CONNECTED;
}

HRESULT STDMETHODCALLTYPE CPin::ConnectionMediaType(AM_MEDIA_TYPE* mediaType)
{
    if (mediaType == nullptr) {
        return E_POINTER;
    }
    if (!m_connected || !m_hasMediaType) {
        ZeroMemory(mediaType, sizeof(*mediaType));
        return VFW_E_NOT_CONNECTED;
    }
    if (!CopyMediaType(mediaType, &m_mediaType)) {
        return E_OUTOFMEMORY;
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::QueryPinInfo(PIN_INFO* info)
{
    if (info == nullptr) {
        return E_POINTER;
    }
    info->pFilter = static_cast<IBaseFilter*>(m_filter);
    info->pFilter->AddRef();
    info->dir = PINDIR_OUTPUT;
    wcscpy_s(info->achName, L"Output");
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::QueryDirection(PIN_DIRECTION* direction)
{
    if (direction == nullptr) {
        return E_POINTER;
    }
    *direction = PINDIR_OUTPUT;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::QueryId(LPWSTR* id)
{
    if (id == nullptr) {
        return E_POINTER;
    }
    const wchar_t* name = L"Output";
    const size_t bytes = (wcslen(name) + 1) * sizeof(wchar_t);
    *id = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
    if (*id == nullptr) {
        return E_OUTOFMEMORY;
    }
    memcpy(*id, name, bytes);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::QueryAccept(const AM_MEDIA_TYPE* mediaType)
{
    return AcceptFormat(mediaType) ? S_OK : S_FALSE;
}

HRESULT STDMETHODCALLTYPE CPin::EnumMediaTypes(IEnumMediaTypes** enumerator)
{
    if (enumerator == nullptr) {
        return E_POINTER;
    }
    *enumerator = new CEnumMediaTypes(this);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::QueryInternalConnections(IPin** pins, ULONG* pinCount)
{
    if (pinCount == nullptr) {
        return E_POINTER;
    }
    *pinCount = 0;
    if (pins != nullptr) {
        *pins = nullptr;
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::EndOfStream()
{
    return m_receivePin != nullptr ? m_receivePin->EndOfStream() : S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::BeginFlush()
{
    return m_receivePin != nullptr ? m_receivePin->BeginFlush() : S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::EndFlush()
{
    return m_receivePin != nullptr ? m_receivePin->EndFlush() : S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::NewSegment(REFERENCE_TIME start, REFERENCE_TIME stop, double rate)
{
    if (m_receivePin != nullptr) {
        return m_receivePin->NewSegment(start, stop, rate);
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::GetAllocator(IMemAllocator** allocator)
{
    if (allocator == nullptr) {
        return E_POINTER;
    }
    if (m_allocator != nullptr) {
        m_allocator->AddRef();
        *allocator = m_allocator;
        return S_OK;
    }
    IMemAllocator* created = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_MemoryAllocator, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_IMemAllocator, reinterpret_cast<void**>(&created));
    if (FAILED(hr)) {
        return hr;
    }
    ALLOCATOR_PROPERTIES requested{};
    requested.cBuffers = 3;
    requested.cbBuffer = FrameSizeFor(m_mediaType);
    requested.cbAlign = 1;
    requested.cbPrefix = 0;
    ALLOCATOR_PROPERTIES actual{};
    hr = created->SetProperties(&requested, &actual);
    if (FAILED(hr)) {
        created->Release();
        return hr;
    }
    *allocator = created;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::NotifyAllocator(IMemAllocator* allocator, BOOL readOnly)
{
    (void)readOnly;
    if (allocator == nullptr) {
        return E_POINTER;
    }
    allocator->AddRef();
    if (m_allocator != nullptr) {
        m_allocator->Release();
    }
    m_allocator = allocator;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::GetAllocatorRequirements(ALLOCATOR_PROPERTIES* properties)
{
    if (properties == nullptr) {
        return E_POINTER;
    }
    ZeroMemory(properties, sizeof(*properties));
    properties->cBuffers = 3;
    properties->cbBuffer = FrameSizeFor(m_mediaType);
    properties->cbAlign = 1;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::SetFormat(AM_MEDIA_TYPE* mediaType)
{
    if (mediaType == nullptr) {
        return E_POINTER;
    }
    if (m_connected) {
        return VFW_E_ALREADY_CONNECTED;
    }
    if (!AcceptFormat(mediaType)) {
        return VFW_E_TYPE_NOT_ACCEPTED;
    }
    FreeMediaType(&m_mediaType);
    if (!CopyMediaType(&m_mediaType, mediaType)) {
        return E_OUTOFMEMORY;
    }
    m_hasMediaType = true;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::GetFormat(AM_MEDIA_TYPE** mediaType)
{
    if (mediaType == nullptr) {
        return E_POINTER;
    }
    auto* copy = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (copy == nullptr) {
        return E_OUTOFMEMORY;
    }
    ZeroMemory(copy, sizeof(*copy));
    if (!CopyMediaType(copy, &m_mediaType)) {
        CoTaskMemFree(copy);
        return E_OUTOFMEMORY;
    }
    *mediaType = copy;
    return S_OK;
}

static const struct {
    int width;
    int height;
    int fps;
} kStreamCaps[] = {
    {640, 480, 30},
    {640, 480, 15},
    {1280, 720, 30},
    {1280, 720, 15},
};

HRESULT STDMETHODCALLTYPE CPin::GetNumberOfCapabilities(int* count, int* size)
{
    if (count == nullptr || size == nullptr) {
        return E_POINTER;
    }
    *count = static_cast<int>(sizeof(kStreamCaps) / sizeof(kStreamCaps[0]));
    *size = sizeof(VIDEO_STREAM_CONFIG_CAPS);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CPin::GetStreamCaps(int index, AM_MEDIA_TYPE** mediaType, BYTE* capabilities)
{
    const int capCount = static_cast<int>(sizeof(kStreamCaps) / sizeof(kStreamCaps[0]));
    if (index < 0 || index >= capCount) {
        return S_FALSE;
    }
    if (mediaType == nullptr || capabilities == nullptr) {
        return E_POINTER;
    }
    const auto& cap = kStreamCaps[index];
    auto* copy = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (copy == nullptr) {
        return E_OUTOFMEMORY;
    }
    ZeroMemory(copy, sizeof(*copy));
    FillMediaType(copy, cap.width, cap.height, cap.fps);
    *mediaType = copy;

    auto* videoCaps = reinterpret_cast<VIDEO_STREAM_CONFIG_CAPS*>(capabilities);
    ZeroMemory(videoCaps, sizeof(*videoCaps));
    videoCaps->guid = FORMAT_VideoInfo;
    videoCaps->VideoStandard = AnalogVideo_None;
    videoCaps->InputSize.cx = cap.width;
    videoCaps->InputSize.cy = cap.height;
    videoCaps->MinCroppingSize.cx = cap.width;
    videoCaps->MinCroppingSize.cy = cap.height;
    videoCaps->MaxCroppingSize.cx = cap.width;
    videoCaps->MaxCroppingSize.cy = cap.height;
    videoCaps->CropGranularityX = 1;
    videoCaps->CropGranularityY = 1;
    videoCaps->MinOutputSize.cx = cap.width;
    videoCaps->MinOutputSize.cy = cap.height;
    videoCaps->MaxOutputSize.cx = cap.width;
    videoCaps->MaxOutputSize.cy = cap.height;
    videoCaps->OutputGranularityX = 1;
    videoCaps->OutputGranularityY = 1;
    videoCaps->MinFrameInterval = 10000000LL / cap.fps;
    videoCaps->MaxFrameInterval = 10000000LL / cap.fps;
    videoCaps->MinBitsPerSecond = static_cast<LONG>(cap.width) * cap.height * 3 * cap.fps;
    videoCaps->MaxBitsPerSecond = videoCaps->MinBitsPerSecond;
    return S_OK;
}

// ---------------------------------------------------------------------------
// Streaming
// ---------------------------------------------------------------------------

int CPin::FrameInterval100ns() const
{
    return static_cast<int>(10000000LL / (FpsFor(m_mediaType) > 0 ? FpsFor(m_mediaType) : 30));
}

HRESULT CPin::StartStreaming()
{
    if (!m_connected || m_allocator == nullptr || m_inputPin == nullptr) {
        return S_OK; // Nothing to stream to; the graph will report the error.
    }
    if (InterlockedCompareExchange(&m_streaming, 1, 0) != 0) {
        return S_OK;
    }
    HRESULT hr = m_allocator->Commit();
    if (FAILED(hr)) {
        InterlockedExchange(&m_streaming, 0);
        return hr;
    }
    m_stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (m_stopEvent == nullptr) {
        m_allocator->Decommit();
        InterlockedExchange(&m_streaming, 0);
        return HRESULT_FROM_WIN32(GetLastError());
    }
    m_nextFrameTime = 0;
    m_lastOfflineTick = 0;
    m_thread = CreateThread(nullptr, 0, &CPin::ThreadProc, this, 0, nullptr);
    if (m_thread == nullptr) {
        CloseHandle(m_stopEvent);
        m_stopEvent = nullptr;
        m_allocator->Decommit();
        InterlockedExchange(&m_streaming, 0);
        return HRESULT_FROM_WIN32(GetLastError());
    }
    return S_OK;
}

void CPin::PauseStreaming()
{
    StopStreaming();
    if (m_allocator != nullptr) {
        m_allocator->Decommit();
    }
}

void CPin::StopStreaming()
{
    if (InterlockedCompareExchange(&m_streaming, 0, 1) == 0) {
        return;
    }
    if (m_stopEvent != nullptr) {
        SetEvent(m_stopEvent);
    }
    if (m_thread != nullptr) {
        WaitForSingleObject(m_thread, 4000);
        CloseHandle(m_thread);
        m_thread = nullptr;
    }
    if (m_stopEvent != nullptr) {
        CloseHandle(m_stopEvent);
        m_stopEvent = nullptr;
    }
    DisconnectSocket();
}

DWORD WINAPI CPin::ThreadProc(LPVOID parameter)
{
    auto* pin = static_cast<CPin*>(parameter);
    return pin->ThreadMain();
}

bool CPin::WriteExact(const void* buffer, int bytes)
{
    const char* cursor = static_cast<const char*>(buffer);
    int remaining = bytes;
    while (remaining > 0) {
        if (m_stopEvent != nullptr && WaitForSingleObject(m_stopEvent, 0) == WAIT_OBJECT_0) {
            return false;
        }
        int sent = send(m_socket, cursor, remaining, 0);
        if (sent <= 0) {
            return false;
        }
        cursor += sent;
        remaining -= sent;
    }
    return true;
}

bool CPin::ReadExact(void* buffer, int bytes)
{
    char* cursor = static_cast<char*>(buffer);
    int remaining = bytes;
    while (remaining > 0) {
        if (m_stopEvent != nullptr && WaitForSingleObject(m_stopEvent, 0) == WAIT_OBJECT_0) {
            return false;
        }
        int received = recv(m_socket, cursor, remaining, 0);
        if (received == SOCKET_ERROR) {
            const int code = WSAGetLastError();
            if (code == WSAETIMEDOUT) {
                continue;
            }
            return false;
        }
        if (received == 0) {
            return false;
        }
        cursor += received;
        remaining -= received;
    }
    return true;
}

void CPin::DisconnectSocket()
{
    if (m_socket != INVALID_SOCKET) {
        closesocket(m_socket);
        m_socket = INVALID_SOCKET;
    }
}

bool CPin::EnsureConnected()
{
    if (m_socket != INVALID_SOCKET) {
        return true;
    }
    const DWORD now = GetTickCount();
    if (now - m_lastConnectAttempt < 500) {
        return false;
    }
    m_lastConnectAttempt = now;

    SOCKET socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (socket == INVALID_SOCKET) {
        return false;
    }
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<u_short>(m_filter->DataPort()));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    DWORD timeout = 250;
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));
    if (connect(socket, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0) {
        closesocket(socket);
        return false;
    }
    m_socket = socket;
    HelloMessage hello{};
    memcpy(hello.magic, kHelloMagic, sizeof(hello.magic));
    hello.version = kProtocolVersion;
    hello.slot = static_cast<uint16_t>(m_filter->Slot());
    hello.width = static_cast<uint16_t>(WidthFor(m_mediaType));
    hello.height = static_cast<uint16_t>(HeightFor(m_mediaType));
    hello.fps = static_cast<uint16_t>(FpsFor(m_mediaType));
    hello.format = 0; // RGB24
    if (!WriteExact(&hello, sizeof(hello))) {
        DisconnectSocket();
        return false;
    }
    return true;
}

bool CPin::DeliverFrame(const BYTE* data, int width, int height)
{
    if (m_allocator == nullptr || m_inputPin == nullptr) {
        return false;
    }
    const int size = width * height * 3;
    if (size <= 0 || size > kMaxFrameBytes) {
        return false;
    }
    IMediaSample* sample = nullptr;
    if (FAILED(m_allocator->GetBuffer(&sample, nullptr, nullptr, 0)) || sample == nullptr) {
        return false;
    }
    BYTE* destination = nullptr;
    if (FAILED(sample->GetPointer(&destination)) || destination == nullptr) {
        sample->Release();
        return false;
    }
    const LONG sampleSize = sample->GetSize();
    if (sampleSize < size) {
        sample->Release();
        return false;
    }
    memcpy(destination, data, size);
    sample->SetActualDataLength(size);
    sample->SetSyncPoint(TRUE);
    sample->SetPreroll(FALSE);
    sample->SetDiscontinuity(!m_sentFirstFrame);
    REFERENCE_TIME startTime = m_nextFrameTime;
    m_nextFrameTime += FrameInterval100ns();
    REFERENCE_TIME stopTime = m_nextFrameTime;
    sample->SetTime(&startTime, &stopTime);
    const HRESULT hr = m_inputPin->Receive(sample);
    sample->Release();
    m_sentFirstFrame = true;
    return SUCCEEDED(hr);
}

void CPin::DeliverOfflineFrame()
{
    const int width = WidthFor(m_mediaType);
    const int height = HeightFor(m_mediaType);
    if (m_placeholder.size() != static_cast<size_t>(width) * height * 3) {
        BuildPlaceholderFrame(width, height, m_filter->Slot(), m_placeholder);
        m_nextFrameTime = 0; // A new placeholder is a discontinuity; restart the timeline.
    }
    DeliverFrame(m_placeholder.data(), width, height);
}

DWORD CPin::ThreadMain()
{
    static std::once_flag winsockOnce;
    std::call_once(winsockOnce, []() {
        WSADATA wsaData{};
        WSAStartup(MAKEWORD(2, 2), &wsaData);
    });

    const int width = WidthFor(m_mediaType);
    const int height = HeightFor(m_mediaType);
    const int frameSize = width * height * 3;
    std::vector<BYTE> frameBuffer;

    while (m_stopEvent != nullptr && WaitForSingleObject(m_stopEvent, 0) != WAIT_OBJECT_0) {
        bool delivered = false;
        if (EnsureConnected()) {
            FrameHeader header{};
            if (ReadExact(&header, sizeof(header))) {
                const bool validHeader = memcmp(header.magic, kFrameMagic, sizeof(header.magic)) == 0
                        && header.version == kProtocolVersion
                        && header.format == 0
                        && header.length > 0
                        && header.length <= static_cast<uint32_t>(kMaxFrameBytes);
                if (validHeader) {
                    if (frameBuffer.size() != header.length) {
                        frameBuffer.resize(header.length);
                    }
                    if (ReadExact(frameBuffer.data(), static_cast<int>(header.length))) {
                        const bool ourFrame = header.slot == static_cast<uint16_t>(m_filter->Slot())
                                && header.width == width
                                && header.height == height
                                && header.length == static_cast<uint32_t>(frameSize);
                        if (ourFrame) {
                            delivered = DeliverFrame(frameBuffer.data(), width, height);
                        }
                    }
                }
            }
            if (!delivered) {
                DisconnectSocket();
            }
        }
        if (!delivered) {
            const DWORD now = GetTickCount();
            if (now - m_lastOfflineTick >= 1000) {
                m_lastOfflineTick = now;
                DeliverOfflineFrame();
            }
            Sleep(50);
        }
    }
    DisconnectSocket();
    return 0;
}

// ---------------------------------------------------------------------------
// CEnumPins / CEnumMediaTypes implementation
// ---------------------------------------------------------------------------

CEnumPins::CEnumPins(CFilter* filter) : m_ref(1), m_filter(filter), m_position(0)
{
    m_filter->AddRef();
}

HRESULT STDMETHODCALLTYPE CEnumPins::Next(ULONG count, IPin** pins, ULONG* fetched)
{
    if (pins == nullptr) {
        return E_POINTER;
    }
    ULONG returned = 0;
    while (returned < count && m_position == 0 && m_filter->Pin() != nullptr) {
        pins[returned] = m_filter->Pin();
        pins[returned]->AddRef();
        returned++;
        m_position++;
    }
    if (fetched != nullptr) {
        *fetched = returned;
    }
    return returned == count ? S_OK : S_FALSE;
}

HRESULT STDMETHODCALLTYPE CEnumPins::Clone(IEnumPins** enumerator)
{
    if (enumerator == nullptr) {
        return E_POINTER;
    }
    auto* clone = new CEnumPins(m_filter);
    clone->m_position = m_position;
    *enumerator = clone;
    return S_OK;
}

CEnumMediaTypes::CEnumMediaTypes(CPin* pin) : m_ref(1), m_pin(pin), m_position(0)
{
    m_pin->AddRef();
}

HRESULT STDMETHODCALLTYPE CEnumMediaTypes::Next(ULONG count, AM_MEDIA_TYPE** mediaTypes, ULONG* fetched)
{
    if (mediaTypes == nullptr) {
        return E_POINTER;
    }
    ULONG returned = 0;
    while (returned < count && m_position == 0) {
        auto* copy = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
        if (copy == nullptr) {
            break;
        }
        ZeroMemory(copy, sizeof(*copy));
        if (!CopyMediaType(copy, &m_pin->MediaType())) {
            CoTaskMemFree(copy);
            break;
        }
        mediaTypes[returned] = copy;
        returned++;
        m_position++;
    }
    if (fetched != nullptr) {
        *fetched = returned;
    }
    return returned == count ? S_OK : S_FALSE;
}

HRESULT STDMETHODCALLTYPE CEnumMediaTypes::Clone(IEnumMediaTypes** enumerator)
{
    if (enumerator == nullptr) {
        return E_POINTER;
    }
    auto* clone = new CEnumMediaTypes(m_pin);
    clone->m_position = m_position;
    *enumerator = clone;
    return S_OK;
}

// ---------------------------------------------------------------------------
// Class factory and DLL entry points
// ---------------------------------------------------------------------------

class CClassFactory : public IClassFactory {
public:
    CClassFactory() { InterlockedIncrement(&g_objectCount); }
    ~CClassFactory() { InterlockedDecrement(&g_objectCount); }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override
    {
        if (ppv == nullptr) {
            return E_POINTER;
        }
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_ref); }
    ULONG STDMETHODCALLTYPE Release() override
    {
        LONG count = InterlockedDecrement(&m_ref);
        if (count == 0) {
            delete this;
        }
        return count;
    }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override
    {
        if (outer != nullptr) {
            return CLASS_E_NOAGGREGATION;
        }
        auto* filter = new CFilter();
        if (filter == nullptr) {
            return E_OUTOFMEMORY;
        }
        const HRESULT hr = filter->QueryInterface(riid, ppv);
        filter->Release();
        return hr;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override
    {
        (void)lock;
        return S_OK;
    }

private:
    LONG m_ref{1};
};

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------

static HRESULT SetStringValue(HKEY key, LPCWSTR name, const std::wstring& value)
{
    const DWORD bytes = static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t));
    const LONG result = RegSetValueExW(key, name, 0, REG_SZ,
                                       reinterpret_cast<const BYTE*>(value.c_str()), bytes);
    return result == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(result);
}

static HRESULT RegisterComServer()
{
    wchar_t modulePath[MAX_PATH * 2] = {};
    const DWORD length = GetModuleFileNameW(g_module, modulePath, static_cast<DWORD>(MAX_PATH * 2));
    if (length == 0 || length >= MAX_PATH * 2) {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    HKEY classKey = nullptr;
    LONG result = RegCreateKeyExW(HKEY_CLASSES_ROOT, kClsidKey, 0, nullptr, 0, KEY_WRITE, nullptr,
                                  &classKey, nullptr);
    if (result != ERROR_SUCCESS) {
        return HRESULT_FROM_WIN32(result);
    }
    SetStringValue(classKey, nullptr, kFilterDisplayName);
    RegCloseKey(classKey);

    std::wstring inprocKey = std::wstring(kClsidKey) + L"\\InprocServer32";
    HKEY inprocKeyHandle = nullptr;
    result = RegCreateKeyExW(HKEY_CLASSES_ROOT, inprocKey.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr,
                             &inprocKeyHandle, nullptr);
    if (result != ERROR_SUCCESS) {
        return HRESULT_FROM_WIN32(result);
    }
    HRESULT hr = SetStringValue(inprocKeyHandle, nullptr, modulePath);
    if (SUCCEEDED(hr)) {
        hr = SetStringValue(inprocKeyHandle, L"ThreadingModel", L"Both");
    }
    RegCloseKey(inprocKeyHandle);
    return hr;
}

static HRESULT UnregisterComServer()
{
    const LONG result = RegDeleteTreeW(HKEY_CLASSES_ROOT, kClsidKey);
    if (result == ERROR_SUCCESS || result == ERROR_FILE_NOT_FOUND) {
        return S_OK;
    }
    return HRESULT_FROM_WIN32(result);
}

static std::wstring InstanceName(int slot)
{
    return std::wstring(L"Synaxis Camera ") + std::to_wstring(slot + 1);
}

static HRESULT RegisterDeviceInstances()
{
    IFilterMapper2* mapper = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_IFilterMapper2, reinterpret_cast<void**>(&mapper));
    if (FAILED(hr) || mapper == nullptr) {
        return hr;
    }

    REGFILTERPINS pin = {};
    pin.strName = const_cast<LPWSTR>(L"Output");
    pin.bOutput = TRUE;
    pin.bRendered = FALSE;
    pin.bZero = FALSE;
    pin.bMany = FALSE;
    pin.clsConnectsToFilter = nullptr;
    pin.strConnectsToPin = nullptr;
    pin.nMediaTypes = 0;
    pin.lpMediaType = nullptr;

    REGFILTER2 registration = {};
    registration.dwVersion = 1;
    registration.dwMerit = MERIT_DO_NOT_USE;
    registration.cPins = 1;
    registration.rgPins = &pin;

    for (int slot = 0; slot < kDeviceCount; slot++) {
        const std::wstring name = InstanceName(slot);
        // Re-registering an existing instance is an update; drop the old moniker first.
        mapper->UnregisterFilter(&CLSID_VideoInputDeviceCategory, name.c_str(), CLSID_SynaxisCamFilter);
        IMoniker* moniker = nullptr;
        hr = mapper->RegisterFilter(CLSID_SynaxisCamFilter, name.c_str(), &moniker,
                                    &CLSID_VideoInputDeviceCategory, name.c_str(), &registration);
        if (FAILED(hr) || moniker == nullptr) {
            continue;
        }
        IPropertyBag* bag = nullptr;
        if (SUCCEEDED(moniker->BindToStorage(nullptr, nullptr, IID_IPropertyBag,
                                            reinterpret_cast<void**>(&bag))) && bag != nullptr) {
            VARIANT value;
            VariantInit(&value);
            value.vt = VT_I4;
            value.lVal = slot;
            bag->Write(L"CameraIndex", &value);
            VariantClear(&value);
            VariantInit(&value);
            value.vt = VT_I4;
            value.lVal = kDefaultDataPort;
            bag->Write(L"DataPort", &value);
            VariantClear(&value);
            bag->Release();
        }
        moniker->Release();
    }
    mapper->Release();
    return S_OK;
}

static HRESULT UnregisterDeviceInstances()
{
    IFilterMapper2* mapper = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_IFilterMapper2, reinterpret_cast<void**>(&mapper));
    if (FAILED(hr) || mapper == nullptr) {
        return hr;
    }
    for (int slot = 0; slot < kDeviceCount; slot++) {
        const std::wstring name = InstanceName(slot);
        mapper->UnregisterFilter(&CLSID_VideoInputDeviceCategory, name.c_str(), CLSID_SynaxisCamFilter);
    }
    mapper->Release();
    return S_OK;
}

// ---------------------------------------------------------------------------
// DLL exports
// ---------------------------------------------------------------------------

STDAPI DllRegisterServer(void)
{
    const HRESULT coInit = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool comInitialised = SUCCEEDED(coInit);
    HRESULT hr = RegisterComServer();
    if (SUCCEEDED(hr)) {
        hr = RegisterDeviceInstances();
    }
    if (comInitialised) {
        CoUninitialize();
    }
    return hr;
}

STDAPI DllUnregisterServer(void)
{
    const HRESULT coInit = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool comInitialised = SUCCEEDED(coInit);
    const HRESULT devices = UnregisterDeviceInstances();
    const HRESULT server = UnregisterComServer();
    if (comInitialised) {
        CoUninitialize();
    }
    return SUCCEEDED(devices) ? server : devices;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** ppv)
{
    if (ppv == nullptr) {
        return E_POINTER;
    }
    if (!IsEqualCLSID(clsid, CLSID_SynaxisCamFilter)) {
        return CLASS_E_CLASSNOTAVAILABLE;
    }
    auto* factory = new CClassFactory();
    if (factory == nullptr) {
        return E_OUTOFMEMORY;
    }
    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

STDAPI DllCanUnloadNow(void)
{
    return g_objectCount == 0 ? S_OK : S_FALSE;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = module;
        DisableThreadLibraryCalls(module);
    }
    return TRUE;
}
