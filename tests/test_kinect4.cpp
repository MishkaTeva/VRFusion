#include <windows.h>
#include <iostream>
#include <NuiApi.h>
#pragma comment(lib, "Kinect10.lib")

int main() {
    int count = 0;
    NuiGetSensorCount(&count);
    if (count == 0) return 1;
    INuiSensor* sensor = nullptr;
    NuiCreateSensorByIndex(0, &sensor);
    HRESULT hr = sensor->NuiInitialize(NUI_INITIALIZE_FLAG_USES_SKELETON);
    std::cout << "Init HR: 0x" << std::hex << hr << std::endl;

    HANDLE hEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    hr = sensor->NuiSkeletonTrackingEnable(hEvent, 0);
    std::cout << "TrackingEnable HR: 0x" << std::hex << hr << std::endl;

    for (int i = 0; i < 30; i++) {
        DWORD waitRes = WaitForSingleObject(hEvent, 500);
        if (waitRes == WAIT_OBJECT_0) {
            NUI_SKELETON_FRAME frame{};
            hr = sensor->NuiSkeletonGetNextFrame(100, &frame);
            if (SUCCEEDED(hr)) {
                int tracked = 0;
                for (int s = 0; s < NUI_SKELETON_COUNT; s++) {
                    if (frame.SkeletonData[s].eTrackingState == NUI_SKELETON_TRACKED) {
                        tracked++;
                        auto& head = frame.SkeletonData[s].SkeletonPositions[NUI_SKELETON_POSITION_HEAD];
                        std::cout << "Frame " << i << ": Skeleton TRACKED! Head: " << head.x << ", " << head.y << ", " << head.z << std::endl;
                    }
                }
                if (tracked == 0) {
                    std::cout << "Frame " << i << ": Frame OK (tracked=0, user not standing in front)\n";
                }
            } else {
                std::cout << "GetNextFrame failed: 0x" << std::hex << hr << std::endl;
            }
        } else {
            std::cout << "Wait timed out: " << waitRes << std::endl;
        }
        Sleep(33);
    }

    sensor->NuiShutdown();
    sensor->Release();
    CloseHandle(hEvent);
    return 0;
}
