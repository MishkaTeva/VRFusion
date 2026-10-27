#include <windows.h>
#include <iostream>
#include <NuiApi.h>
#pragma comment(lib, "Kinect10.lib")

int main() {
    int count = 0;
    HRESULT hr = NuiGetSensorCount(&count);
    std::cout << "Count: " << count << " HR: 0x" << std::hex << hr << std::endl;
    if (count == 0) return 1;
    INuiSensor* sensor = nullptr;
    hr = NuiCreateSensorByIndex(0, &sensor);
    std::cout << "Create: 0x" << std::hex << hr << std::endl;
    if (!sensor) return 2;
    hr = sensor->NuiInitialize(NUI_INITIALIZE_FLAG_USES_SKELETON);
    std::cout << "Init SKELETON: 0x" << std::hex << hr << std::endl;
    if (FAILED(hr)) {
        hr = sensor->NuiInitialize(NUI_INITIALIZE_FLAG_USES_DEPTH_AND_PLAYER_INDEX | NUI_INITIALIZE_FLAG_USES_SKELETON);
        std::cout << "Init DEPTH+SKELETON: 0x" << std::hex << hr << std::endl;
    }
    sensor->NuiShutdown();
    sensor->Release();
    return 0;
}
