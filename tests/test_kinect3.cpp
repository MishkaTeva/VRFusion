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
    
    // Proper Kinect 1.8 skeleton tracking initialization requires DEPTH_AND_PLAYER_INDEX:
    HRESULT hr = sensor->NuiInitialize(NUI_INITIALIZE_FLAG_USES_DEPTH_AND_PLAYER_INDEX | NUI_INITIALIZE_FLAG_USES_SKELETON);
    std::cout << "Init HR: 0x" << std::hex << hr << std::endl;

    HANDLE hDepthStream = NULL;
    hr = sensor->NuiImageStreamOpen(
        NUI_IMAGE_TYPE_DEPTH_AND_PLAYER_INDEX,
        NUI_IMAGE_RESOLUTION_320x240,
        0,
        2,
        NULL,
        &hDepthStream
    );
    std::cout << "Depth Stream Open HR: 0x" << std::hex << hr << std::endl;

    HANDLE hEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    hr = sensor->NuiSkeletonTrackingEnable(hEvent, 0);
    std::cout << "TrackingEnable HR: 0x" << std::hex << hr << std::endl;

    for (int i = 0; i < 20; i++) {
        DWORD waitRes = WaitForSingleObject(hEvent, 200);
        if (waitRes == WAIT_OBJECT_0) {
            ResetEvent(hEvent);
            NUI_SKELETON_FRAME frame{};
            hr = sensor->NuiSkeletonGetNextFrame(0, &frame);
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
                    std::cout << "Frame " << i << ": OK (Valid frame received, tracked=0)\n";
                }
            } else {
                std::cout << "GetNextFrame failed: 0x" << std::hex << hr << std::endl;
            }
        } else {
            std::cout << "Wait timed out: " << waitRes << std::endl;
        }
        Sleep(50);
    }

    sensor->NuiShutdown();
    sensor->Release();
    CloseHandle(hEvent);
    return 0;
}
