// Lists every DirectShow video input device on the machine.
//
// Development/diagnostic tool for verifying that the Synaxis Camera devices were registered and are
// visible to any DirectShow based application. It performs read-only enumeration; it never writes to
// the registry and never registers or unregisters a filter.
//
// Build (from a Visual Studio 2022 x64 developer prompt, or via build_devices.ps1):
//   cl /nologo /EHsc /std:c++17 list_dshow_devices.cpp strmiids.lib ole32.lib oleaut32.lib

#include <windows.h>
#include <dshow.h>
#include <stdio.h>

#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")

static void printProperty(IPropertyBag* bag, const wchar_t* name, const wchar_t* label) {
    VARIANT value;
    VariantInit(&value);
    if (SUCCEEDED(bag->Read(name, &value, nullptr)) && value.vt == VT_BSTR && value.bstrVal) {
        wprintf(L"    %ls: %ls\n", label, value.bstrVal);
        VariantClear(&value);
    }
}

int wmain() {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        wprintf(L"CoInitializeEx failed: 0x%08lX\n", hr);
        return 1;
    }

    ICreateDevEnum* deviceEnumerator = nullptr;
    hr = CoCreateInstance(CLSID_SystemDeviceEnum, nullptr, CLSCTX_INPROC_SERVER,
                          IID_PPV_ARGS(&deviceEnumerator));
    if (FAILED(hr)) {
        wprintf(L"CoCreateInstance(CLSID_SystemDeviceEnum) failed: 0x%08lX\n", hr);
        CoUninitialize();
        return 1;
    }

    IEnumMoniker* enumerator = nullptr;
    hr = deviceEnumerator->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &enumerator, 0);
    if (hr != S_OK) {
        wprintf(L"No video input devices found (hr=0x%08lX).\n", hr);
        deviceEnumerator->Release();
        CoUninitialize();
        return 0;
    }

    wprintf(L"DirectShow video input devices:\n");
    int count = 0;
    int synaxis = 0;
    IMoniker* moniker = nullptr;
    while (enumerator->Next(1, &moniker, nullptr) == S_OK) {
        count++;
        IPropertyBag* bag = nullptr;
        if (SUCCEEDED(moniker->BindToStorage(nullptr, nullptr, IID_PPV_ARGS(&bag))) && bag) {
            wprintf(L"  [%d]\n", count);
            printProperty(bag, L"FriendlyName", L"FriendlyName");
            printProperty(bag, L"Description", L"Description");
            printProperty(bag, L"DevicePath", L"DevicePath");
            VARIANT friendly;
            VariantInit(&friendly);
            if (SUCCEEDED(bag->Read(L"FriendlyName", &friendly, nullptr))
                    && friendly.vt == VT_BSTR && friendly.bstrVal
                    && wcsstr(friendly.bstrVal, L"Synaxis Camera")) {
                synaxis++;
            }
            VariantClear(&friendly);
            bag->Release();
        }
        moniker->Release();
    }

    enumerator->Release();
    deviceEnumerator->Release();
    CoUninitialize();

    wprintf(L"total=%d synaxis=%d\n", count, synaxis);
    return 0;
}
