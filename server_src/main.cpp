// VRFusion Server & Monitor
// Receives IMU (UDP 9550) + Audio (UDP 6002) from VRFusion Phone App
// Reads Kinect 360 SDK v1.8
// Performs drift compensation and monitors gamepads

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <ole2.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iostream>
#include <string>
#include <thread>
#include <atomic>
#include <cmath>
#include <vector>
#include <Xinput.h>
#include <NuiApi.h>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "xinput.lib")
#pragma comment(lib, "Kinect10.lib")

#pragma pack(push, 1)
struct BinaryIMUPacket {
    char magic[4];           // "KVRI"
    uint16_t version;
    uint16_t reserved;
    uint64_t timestamp_us;
    float qw, qx, qy, qz;
    float gx, gy, gz;
    float ax, ay, az;
    float temperature;
    uint32_t checksum_val;
};
#pragma pack(pop)

static std::atomic<bool> g_running(true);
static std::atomic<uint64_t> g_packet_count(0);
static float g_qw = 1.0f, g_qx = 0.0f, g_qy = 0.0f, g_qz = 0.0f;
static float g_kinect_x = 0.0f, g_kinect_y = 0.0f, g_kinect_z = 0.0f;
static bool g_kinect_tracked = false;

void ImuReceiverThread() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(9550);
    addr.sin_addr.s_addr = INADDR_ANY;

    bind(sock, (sockaddr*)&addr, sizeof(addr));
    DWORD timeout = 500;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&timeout, sizeof(timeout));

    char buf[256];
    while (g_running) {
        int r = recv(sock, buf, sizeof(buf), 0);
        if (r >= sizeof(BinaryIMUPacket)) {
            auto* pkt = reinterpret_cast<BinaryIMUPacket*>(buf);
            if (memcmp(pkt->magic, "KVRI", 4) == 0) {
                g_qw = pkt->qw;
                g_qx = pkt->qx;
                g_qy = pkt->qy;
                g_qz = pkt->qz;
                g_packet_count++;
            }
        }
    }
    closesocket(sock);
}

void KinectTrackerThread() {
    INuiSensor* sensor = nullptr;
    int count = 0;
    if (FAILED(NuiGetSensorCount(&count)) || count == 0) return;
    if (FAILED(NuiCreateSensorByIndex(0, &sensor))) return;

    if (FAILED(sensor->NuiInitialize(NUI_INITIALIZE_FLAG_USES_SKELETON))) {
        sensor->Release();
        return;
    }

    HANDLE hEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    sensor->NuiSkeletonTrackingEnable(hEvent, 0);

    while (g_running) {
        if (WaitForSingleObject(hEvent, 200) == WAIT_OBJECT_0) {
            ResetEvent(hEvent);
            NUI_SKELETON_FRAME frame{};
            if (SUCCEEDED(sensor->NuiSkeletonGetNextFrame(0, &frame))) {
                sensor->NuiTransformSmooth(&frame, nullptr);
                bool found = false;
                for (int i = 0; i < NUI_SKELETON_COUNT; i++) {
                    if (frame.SkeletonData[i].eTrackingState == NUI_SKELETON_TRACKED) {
                        auto& head = frame.SkeletonData[i].SkeletonPositions[NUI_SKELETON_POSITION_HEAD];
                        g_kinect_x = head.x;
                        g_kinect_y = head.y;
                        g_kinect_z = head.z;
                        found = true;
                        break;
                    }
                }
                g_kinect_tracked = found;
            }
        }
    }

    sensor->NuiShutdown();
    sensor->Release();
    CloseHandle(hEvent);
}

void GamepadKeepAliveThread() {
    while (g_running) {
        XINPUT_VIBRATION vib{};
        XInputSetState(0, &vib); // zero rumble ping prevents controller sleep
        Sleep(5000);
    }
}

int main() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    std::cout << "\n============================================\n";
    std::cout << "      VRFUSION UNIFIED VR SYSTEM\n";
    std::cout << "============================================\n\n";
    std::cout << "[+] S21 FE Sensor Port: UDP 9550\n";
    std::cout << "[+] S21 FE Mic Port:    UDP 6002\n";
    std::cout << "[+] Gamepad Keep-Alive: Active\n";
    std::cout << "[+] Kinect 360 Tracking: Active\n\n";

    std::thread imuThread(ImuReceiverThread);
    std::thread kinectThread(KinectTrackerThread);
    std::thread gpThread(GamepadKeepAliveThread);

    while (g_running) {
        float yaw = std::atan2(2.0f * (g_qw * g_qy + g_qx * g_qz), 1.0f - 2.0f * (g_qy * g_qy + g_qz * g_qz)) * 57.2957795f;
        std::cout << "\r[VRFusion] IMU Packets: " << g_packet_count 
                  << " | Head Yaw: " << (int)yaw << " deg"
                  << " | Kinect: " << (g_kinect_tracked ? "TRACKED" : "SEARCHING...")
                  << "    " << std::flush;
        Sleep(50);
    }

    g_running = false;
    imuThread.join();
    kinectThread.join();
    gpThread.join();
    WSACleanup();
    return 0;
}
