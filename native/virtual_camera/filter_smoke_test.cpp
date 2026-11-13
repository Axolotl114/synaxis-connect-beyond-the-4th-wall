// filter_smoke_test.cpp
// -----------------------------------------------------------------------------
// Development self-test for synaxis_cam_filter.dll. It does NOT register the
// filter (no registry writes, no admin needed): it loads the DLL, creates the
// filter through DllGetClassObject, feeds it a device-moniker-like property bag
// ("CameraIndex"=2), checks the exposed interfaces, then builds a real
// filter graph (Synaxis filter -> Null Renderer) and runs it for a couple of
// seconds to exercise connect/allocator/streaming. With the Java frame server
// not running the filter must still deliver "no signal" frames, so the run must
// not hang or fail.
//
// Usage: filter_smoke_test.exe [path\to\synaxis_cam_filter.dll]
// -----------------------------------------------------------------------------
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dshow.h>
#include <strmif.h>
#include <cstdio>

#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "quartz.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "uuid.lib")

// {6A1F0B7E-3C62-4C61-9E11-5A4D1A7C0001}
static const CLSID CLSID_SynaxisCamFilter =
    {0x6A1F0B7E, 0x3C62, 0x4C61, {0x9E, 0x11, 0x5A, 0x4D, 0x1A, 0x7C, 0x00, 0x01}};

// {C1F400A4-3F08-11D3-9F0B-006008039E37}: Null Renderer. qedit.h is gone from
// the modern SDK, so the CLSID is declared locally.
static const CLSID CLSID_SynaxisNullRenderer =
    {0xC1F400A4, 0x3F08, 0x11D3, {0x9F, 0x0B, 0x00, 0x60, 0x08, 0x03, 0x9E, 0x37}};

static int g_failures = 0;

#define CHECK(expression, message)                                                       \
    do {                                                                                 \
        HRESULT _hr = (expression);                                                      \
        if (SUCCEEDED(_hr)) {                                                            \
            wprintf(L"[ OK ] %s (hr=0x%08X)\n", message, static_cast<unsigned>(_hr));    \
        } else {                                                                         \
            wprintf(L"[FAIL] %s (hr=0x%08X)\n", message, static_cast<unsigned>(_hr));    \
            g_failures++;                                                                \
        }                                                                                \
    } while (0)

class MockDeviceBag : public IPropertyBag {
public:
    MockDeviceBag(int slot, int port) : m_ref(1), m_slot(slot), m_port(port) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override
    {
        if (ppv == nullptr) {
            return E_POINTER;
        }
        if (riid == IID_IUnknown || riid == IID_IPropertyBag) {
            *ppv = static_cast<IPropertyBag*>(this);
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
    HRESULT STDMETHODCALLTYPE Read(LPCOLESTR name, VARIANT* value, IErrorLog*) override
    {
        if (name == nullptr || value == nullptr) {
            return E_POINTER;
        }
        VariantInit(value);
        value->vt = VT_I4;
        if (wcscmp(name, L"CameraIndex") == 0) {
            value->lVal = m_slot;
            return S_OK;
        }
        if (wcscmp(name, L"DataPort") == 0) {
            value->lVal = m_port;
            return S_OK;
        }
        return E_INVALIDARG;
    }
    HRESULT STDMETHODCALLTYPE Write(LPCOLESTR, VARIANT*) override { return S_OK; }
    ~MockDeviceBag() {}

private:
    LONG m_ref;
    int m_slot;
    int m_port;
};

typedef HRESULT(STDAPICALLTYPE* GetClassObjectFn)(REFCLSID, REFIID, void**);
typedef HRESULT(STDAPICALLTYPE* CanUnloadNowFn)();

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

int wmain(int argumentCount, wchar_t** arguments)
{
    const wchar_t* dllPath = argumentCount > 1 ? arguments[1] : L"synaxis_cam_filter.dll";
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    wprintf(L"=== Synaxis Camera DirectShow filter smoke test ===\n");
    wprintf(L"DLL: %s\n", dllPath);

    HMODULE module = LoadLibraryW(dllPath);
    if (module == nullptr) {
        wprintf(L"[FAIL] LoadLibrary error %lu\n", GetLastError());
        return 2;
    }
    auto getClassObject = reinterpret_cast<GetClassObjectFn>(
        GetProcAddress(module, "DllGetClassObject"));
    auto canUnloadNow = reinterpret_cast<CanUnloadNowFn>(GetProcAddress(module, "DllCanUnloadNow"));
    if (getClassObject == nullptr || canUnloadNow == nullptr) {
        wprintf(L"[FAIL] missing DLL exports\n");
        return 2;
    }
    wprintf(L"[ OK ] DLL loaded and exports present\n");

    IClassFactory* factory = nullptr;
    CHECK(getClassObject(CLSID_SynaxisCamFilter, IID_IClassFactory,
                         reinterpret_cast<void**>(&factory)),
          L"DllGetClassObject(IClassFactory)");

    IBaseFilter* filter = nullptr;
    if (factory != nullptr) {
        CHECK(factory->CreateInstance(nullptr, IID_IBaseFilter, reinterpret_cast<void**>(&filter)),
              L"CreateInstance(IBaseFilter)");
    }

    if (filter != nullptr) {
        IPersistPropertyBag* bag = nullptr;
        CHECK(filter->QueryInterface(IID_IPersistPropertyBag, reinterpret_cast<void**>(&bag)),
              L"QI IPersistPropertyBag");
        if (bag != nullptr) {
            MockDeviceBag deviceBag(2, 25587);
            CHECK(bag->Load(&deviceBag, nullptr), L"IPersistPropertyBag::Load(CameraIndex=2)");
            bag->Release();
        }

        IAMStreamConfig* streamConfig = nullptr;
        IEnumPins* pins = nullptr;
        CHECK(filter->EnumPins(&pins), L"IBaseFilter::EnumPins");
        IPin* outputPin = nullptr;
        if (pins != nullptr) {
            ULONG fetched = 0;
            if (SUCCEEDED(pins->Next(1, &outputPin, &fetched)) && fetched == 1 && outputPin != nullptr) {
                wprintf(L"[ OK ] IEnumPins::Next -> output pin\n");
            } else {
                wprintf(L"[FAIL] IEnumPins::Next\n");
                g_failures++;
            }
        }
        if (outputPin != nullptr) {
            CHECK(outputPin->QueryInterface(IID_IAMStreamConfig,
                                            reinterpret_cast<void**>(&streamConfig)),
                  L"QI IAMStreamConfig");
        }
        if (streamConfig != nullptr) {
            int count = 0;
            int size = 0;
            CHECK(streamConfig->GetNumberOfCapabilities(&count, &size),
                  L"IAMStreamConfig::GetNumberOfCapabilities");
            wprintf(L"       capabilities=%d size=%d\n", count, size);
            AM_MEDIA_TYPE* mediaType = nullptr;
            auto* capabilities = static_cast<BYTE*>(CoTaskMemAlloc(size > 0 ? size : 1));
            if (count > 0 && capabilities != nullptr) {
                CHECK(streamConfig->GetStreamCaps(0, &mediaType, capabilities),
                      L"IAMStreamConfig::GetStreamCaps(0)");
                if (mediaType != nullptr) {
                    const auto* info = reinterpret_cast<const VIDEOINFOHEADER*>(mediaType->pbFormat);
                    wprintf(L"       cap0=%ldx%ld rgb24=%d\n",
                            info->bmiHeader.biWidth, info->bmiHeader.biHeight,
                            mediaType->subtype == MEDIASUBTYPE_RGB24 ? 1 : 0);
                    FreeMediaType(mediaType);
                    CoTaskMemFree(mediaType);
                }
                CoTaskMemFree(capabilities);
            }
            streamConfig->Release();
        }

        // --- real graph: our filter -> Null Renderer, run for 2 seconds ------
        IGraphBuilder* graph = nullptr;
        IMediaControl* control = nullptr;
        CHECK(CoCreateInstance(CLSID_FilterGraph, nullptr, CLSCTX_INPROC_SERVER,
                               IID_IGraphBuilder, reinterpret_cast<void**>(&graph)),
              L"CoCreateInstance(CLSID_FilterGraph)");
        if (graph != nullptr) {
            CHECK(graph->AddFilter(filter, L"Synaxis Camera"), L"IFilterGraph::AddFilter(filter)");
            IBaseFilter* nullRenderer = nullptr;
            CHECK(CoCreateInstance(CLSID_SynaxisNullRenderer, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_IBaseFilter, reinterpret_cast<void**>(&nullRenderer)),
                  L"CoCreateInstance(CLSID_NullRenderer)");
            if (nullRenderer != nullptr) {
                CHECK(graph->AddFilter(nullRenderer, L"Null Renderer"),
                      L"IFilterGraph::AddFilter(null renderer)");
                IEnumPins* rendererPins = nullptr;
                IPin* inputPin = nullptr;
                if (SUCCEEDED(nullRenderer->EnumPins(&rendererPins)) && rendererPins != nullptr) {
                    rendererPins->Next(1, &inputPin, nullptr);
                    rendererPins->Release();
                }
                if (outputPin != nullptr && inputPin != nullptr) {
                    CHECK(graph->Connect(outputPin, inputPin),
                          L"IFilterGraph::Connect(Synaxis Camera -> Null Renderer)");
                }
                if (inputPin != nullptr) {
                    inputPin->Release();
                }
                nullRenderer->Release();
            }
            if (SUCCEEDED(graph->QueryInterface(IID_IMediaControl, reinterpret_cast<void**>(&control))) &&
                control != nullptr) {
                CHECK(control->Run(), L"IMediaControl::Run");
                Sleep(2000);
                CHECK(control->Stop(), L"IMediaControl::Stop");
                control->Release();
            }
            graph->Release();
        }
        if (outputPin != nullptr) {
            outputPin->Release();
        }
        if (pins != nullptr) {
            pins->Release();
        }
        filter->Release();
    }
    if (factory != nullptr) {
        factory->Release();
    }
    CHECK(canUnloadNow(), L"DllCanUnloadNow");
    FreeLibrary(module);
    CoUninitialize();

    wprintf(L"=== result: %s (%d failures) ===\n", g_failures == 0 ? L"PASS" : L"FAIL", g_failures);
    return g_failures == 0 ? 0 : 1;
}
