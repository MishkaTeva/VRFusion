// VRFusion SteamVR Driver
// Full HMD + 2 Controllers + Full Body Trackers (Waist, Feet, Knees) via Kinect 360 SDK v1.8
// S21 FE Phone sends IMU over UDP 9550
// Xbox Controller mapped to VR controllers with anti-sleep keep-alive

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <ole2.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <openvr_driver.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <condition_variable>

#include <timeapi.h>
#include <d3d11.h>
#include <dxgi.h>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "Kinect10.lib")
#pragma comment(lib, "xinput.lib")
#pragma comment(lib, "winmm.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")

#include <NuiApi.h>
#include <Xinput.h>

using namespace vr;

// Network constants
static const int IMU_PORT = 9550; // Phone UDP port
static const int DRIVER_CMD_PORT = 9555; // PC Control Center UDP port

static std::string g_phoneSenderIp = "192.168.31.6";
static std::atomic<uint64_t> g_lastImuPacketTime{ 0 };
static std::mutex g_phoneIpMutex;

static float g_userIpdMeters = 0.063f;
static int g_viewportIpdShift = 0;
static float g_kinectCenterZ = 0.0f;
static float g_lastHeadZ = 2.0f;

// ── Kinect Camera Stream & Passthrough ──
static std::atomic<int> g_kinectCamMode{ 0 }; // 0: OFF, 1: RGB, 2: DEPTH, 3: IR, 4: PIP
static std::atomic<bool> g_kinectCamPipeActive{ false };
static std::atomic<float> g_kinectAccX{ 0.0f };
static std::atomic<float> g_kinectAccY{ -1.0f };
static std::atomic<float> g_kinectAccZ{ 0.0f };
static std::atomic<float> g_kinectPitch{ 0.0f };
static std::atomic<float> g_kinectRoll{ 0.0f };
static std::vector<uint8_t> g_kinectCamFrame(640 * 480 * 4, 0);
static std::mutex g_kinectCamMutex;
static uint64_t g_kinectCamCounter = 0;
static std::condition_variable g_kinectCamCv;

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

// ════════════════════════════════════════════════════════════════════
//  DirectX 11 Headless Stereo Named Pipe Streamer (Zero Windows)
// ════════════════════════════════════════════════════════════════════
class CVRFusionMirrorPresenter {
public:
    static const uint32_t STREAM_WIDTH = 1920;
    static const uint32_t STREAM_HEIGHT = 1080;
    static const uint32_t STREAM_FRAME_BYTES = STREAM_WIDTH * STREAM_HEIGHT * 4;

    CVRFusionMirrorPresenter() = default;
    ~CVRFusionMirrorPresenter() { Stop(); }

    void Start(uint32_t width, uint32_t height) {
        if (m_running.exchange(true)) return;
        m_width = width;
        m_height = height;
        m_frameBuffer.assign(STREAM_FRAME_BYTES, 0);
        m_thread = std::thread(&CVRFusionMirrorPresenter::ThreadProc, this);
        m_pipeThread = std::thread(&CVRFusionMirrorPresenter::PipeThreadProc, this);
        m_mirrorPipeThread = std::thread(&CVRFusionMirrorPresenter::MirrorPipeThreadProc, this);
        m_camPipeThread = std::thread(&CVRFusionMirrorPresenter::KinectCamPipeThreadProc, this);
    }

    void Stop() {
        if (!m_running.exchange(false)) return;
        g_kinectCamPipeActive = false;
        m_presentCv.notify_all();
        m_frameCv.notify_all();
        g_kinectCamCv.notify_all();
        if (m_hPipe != INVALID_HANDLE_VALUE) {
            CancelIoEx(m_hPipe, nullptr);
            DisconnectNamedPipe(m_hPipe);
            CloseHandle(m_hPipe);
            m_hPipe = INVALID_HANDLE_VALUE;
        }
        if (m_hMirrorPipe != INVALID_HANDLE_VALUE) {
            CancelIoEx(m_hMirrorPipe, nullptr);
            DisconnectNamedPipe(m_hMirrorPipe);
            CloseHandle(m_hMirrorPipe);
            m_hMirrorPipe = INVALID_HANDLE_VALUE;
        }
        if (m_hCamPipe != INVALID_HANDLE_VALUE) {
            CancelIoEx(m_hCamPipe, nullptr);
            DisconnectNamedPipe(m_hCamPipe);
            CloseHandle(m_hCamPipe);
            m_hCamPipe = INVALID_HANDLE_VALUE;
        }
        if (m_thread.joinable()) {
            m_thread.join();
        }
        if (m_pipeThread.joinable()) {
            m_pipeThread.join();
        }
        if (m_mirrorPipeThread.joinable()) {
            m_mirrorPipeThread.join();
        }
        if (m_camPipeThread.joinable()) {
            m_camPipeThread.join();
        }
    }

    void PresentTexture(SharedTextureHandle_t handle) {
        if (!m_running || !handle) return;
        {
            std::lock_guard<std::mutex> lk(m_presentMutex);
            m_pendingHandle = handle;
        }
        m_presentCv.notify_one();
    }

private:
    void ThreadProc() {
        D3D_FEATURE_LEVEL featureLevels[] = { D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0 };
        D3D_FEATURE_LEVEL obtainedLevel;

        HRESULT hr = D3D11CreateDevice(
            nullptr,
            D3D_DRIVER_TYPE_HARDWARE,
            nullptr,
            0,
            featureLevels,
            3,
            D3D11_SDK_VERSION,
            &m_pDevice,
            &obtainedLevel,
            &m_pContext
        );

        if (FAILED(hr) || !m_pDevice || !m_pContext) {
            m_running = false;
            return;
        }

        CreateStagingTextures();

        SharedTextureHandle_t activeHandle = 0;
        ID3D11Texture2D* pSharedTex = nullptr;

        while (m_running) {
            SharedTextureHandle_t curHandle = 0;
            {
                std::unique_lock<std::mutex> lk(m_presentMutex);
                m_presentCv.wait_for(lk, std::chrono::milliseconds(50), [this]() {
                    return m_pendingHandle != 0 || !m_running;
                });
                if (!m_running) break;
                curHandle = m_pendingHandle;
                m_pendingHandle = 0;
            }

            if (curHandle != 0 && m_pDevice && m_pContext) {
                if (curHandle != activeHandle) {
                    if (pSharedTex) {
                        pSharedTex->Release();
                        pSharedTex = nullptr;
                    }
                    HRESULT openHr = m_pDevice->OpenSharedResource(
                        (HANDLE)curHandle,
                        __uuidof(ID3D11Texture2D),
                        (void**)&pSharedTex
                    );
                    if (SUCCEEDED(openHr) && pSharedTex) {
                        activeHandle = curHandle;
                        D3D11_TEXTURE2D_DESC desc;
                        pSharedTex->GetDesc(&desc);
                        if (desc.Width != m_width || desc.Height != m_height || desc.Format != m_format) {
                            m_width = desc.Width;
                            m_height = desc.Height;
                            m_format = desc.Format;
                            CreateStagingTextures();
                        }
                    }
                }

                if (pSharedTex) {
                    int writeStg = m_stagingIdx;
                    int readStg = 1 - m_stagingIdx;

                    IDXGIKeyedMutex* pKeyed = nullptr;
                    if (SUCCEEDED(pSharedTex->QueryInterface(__uuidof(IDXGIKeyedMutex), (void**)&pKeyed)) && pKeyed) {
                        if (pKeyed->AcquireSync(0, 10) == S_OK) {
                            if (m_pStagingTex[writeStg]) m_pContext->CopyResource(m_pStagingTex[writeStg], pSharedTex);
                            pKeyed->ReleaseSync(0);
                        }
                        pKeyed->Release();
                    } else {
                        if (m_pStagingTex[writeStg]) m_pContext->CopyResource(m_pStagingTex[writeStg], pSharedTex);
                    }

                    // Map previous frame's staging texture (already completed on GPU = 0ms wait)
                    if (m_pStagingTex[readStg]) {
                        D3D11_MAPPED_SUBRESOURCE mapped = {};
                        if (SUCCEEDED(m_pContext->Map(m_pStagingTex[readStg], 0, D3D11_MAP_READ, 0, &mapped))) {
                            const uint8_t* pSrc = (const uint8_t*)mapped.pData;
                            uint32_t copyW = (m_width < STREAM_WIDTH) ? m_width : STREAM_WIDTH;
                            uint32_t copyH = (m_height < STREAM_HEIGHT) ? m_height : STREAM_HEIGHT;
                            uint32_t dstPitch = STREAM_WIDTH * 4;
                            uint32_t copyPitch = copyW * 4;

                            {
                                std::lock_guard<std::mutex> lk(m_frameMutex);
                                if (m_frameBuffer.size() != STREAM_FRAME_BYTES) {
                                    m_frameBuffer.assign(STREAM_FRAME_BYTES, 0);
                                }

                                int camMode = g_kinectCamMode.load();

                                if (camMode >= 1 && camMode <= 3) {
                                    // Full-Screen Stereo Passthrough: 640x480 camera feed scaled to both eyes (960x720 centered in 960x1080)
                                    memset(m_frameBuffer.data(), 0, STREAM_FRAME_BYTES);
                                    std::vector<uint8_t> camCopy;
                                    {
                                        std::lock_guard<std::mutex> camLk(g_kinectCamMutex);
                                        camCopy = g_kinectCamFrame;
                                    }
                                    if (camCopy.size() == 640 * 480 * 4) {
                                        const uint8_t* pCam = camCopy.data();
                                        for (int y = 0; y < 720; y++) {
                                            int srcY = (y * 480) / 720;
                                            const uint8_t* pSrcLine = pCam + srcY * (640 * 4);
                                            uint8_t* pDstL = m_frameBuffer.data() + (y + 180) * dstPitch;
                                            uint8_t* pDstR = pDstL + (960 * 4);
                                            for (int x = 0; x < 960; x++) {
                                                int srcX = (x * 640) / 960;
                                                const uint8_t* px = pSrcLine + srcX * 4;
                                                // Left Eye
                                                pDstL[x * 4 + 0] = px[0];
                                                pDstL[x * 4 + 1] = px[1];
                                                pDstL[x * 4 + 2] = px[2];
                                                pDstL[x * 4 + 3] = 255;
                                                // Right Eye
                                                pDstR[x * 4 + 0] = px[0];
                                                pDstR[x * 4 + 1] = px[1];
                                                pDstR[x * 4 + 2] = px[2];
                                                pDstR[x * 4 + 3] = 255;
                                            }
                                        }
                                    }
                                } else {
                                    // SteamVR Rendering with physical IPD eye separation and pure OLED black margins
                                    int shift = g_viewportIpdShift;
                                    if (shift < -120) shift = -120;
                                    if (shift > 120) shift = 120;

                                    if (shift == 0) {
                                        for (uint32_t y = 0; y < copyH; y++) {
                                            memcpy(m_frameBuffer.data() + y * dstPitch, pSrc + y * mapped.RowPitch, copyPitch);
                                        }
                                    } else {
                                        memset(m_frameBuffer.data(), 0, STREAM_FRAME_BYTES);
                                        uint32_t halfW = STREAM_WIDTH / 2; // 960
                                        for (uint32_t y = 0; y < copyH; y++) {
                                            const uint8_t* srcRow = pSrc + y * mapped.RowPitch;
                                            uint8_t* dstRow = m_frameBuffer.data() + y * dstPitch;

                                            // Left Eye: shift outward by -shift
                                            int dstLeftX = -shift;
                                            int srcLeftX = 0;
                                            int copyLeftW = (int)halfW;
                                            if (dstLeftX < 0) {
                                                srcLeftX = -dstLeftX;
                                                copyLeftW += dstLeftX;
                                                dstLeftX = 0;
                                            }
                                            if (dstLeftX + copyLeftW > (int)halfW) {
                                                copyLeftW = (int)halfW - dstLeftX;
                                            }
                                            if (copyLeftW > 0) {
                                                memcpy(dstRow + dstLeftX * 4, srcRow + srcLeftX * 4, copyLeftW * 4);
                                            }

                                            // Right Eye: shift outward by +shift
                                            int dstRightX = (int)halfW + shift;
                                            int srcRightX = (int)halfW;
                                            int copyRightW = (int)halfW;
                                            if (dstRightX < (int)halfW) {
                                                srcRightX += ((int)halfW - dstRightX);
                                                copyRightW -= ((int)halfW - dstRightX);
                                                dstRightX = (int)halfW;
                                            }
                                            if (dstRightX + copyRightW > (int)STREAM_WIDTH) {
                                                copyRightW = (int)STREAM_WIDTH - dstRightX;
                                            }
                                            if (copyRightW > 0) {
                                                memcpy(dstRow + dstRightX * 4, srcRow + srcRightX * 4, copyRightW * 4);
                                            }
                                        }
                                    }

                                    // Mode 4: Picture-in-Picture (PIP) camera preview in bottom corner of each eye
                                    if (camMode == 4) {
                                        std::vector<uint8_t> camCopy;
                                        {
                                            std::lock_guard<std::mutex> camLk(g_kinectCamMutex);
                                            camCopy = g_kinectCamFrame;
                                        }
                                        if (camCopy.size() == 640 * 480 * 4) {
                                            const uint8_t* pCam = camCopy.data();
                                            int pipW = 320;
                                            int pipH = 240;
                                            int pipY0 = 780;
                                            for (int py = 0; py < pipH; py++) {
                                                int srcY = (py * 480) / pipH;
                                                const uint8_t* pSrcLine = pCam + srcY * (640 * 4);
                                                uint8_t* pDstL = m_frameBuffer.data() + (pipY0 + py) * dstPitch + (320 * 4);
                                                uint8_t* pDstR = m_frameBuffer.data() + (pipY0 + py) * dstPitch + ((960 + 320) * 4);
                                                for (int px = 0; px < pipW; px++) {
                                                    bool border = (py < 2 || py >= pipH - 2 || px < 2 || px >= pipW - 2);
                                                    if (border) {
                                                        pDstL[px * 4 + 0] = 0;   pDstL[px * 4 + 1] = 242; pDstL[px * 4 + 2] = 254; pDstL[px * 4 + 3] = 255;
                                                        pDstR[px * 4 + 0] = 0;   pDstR[px * 4 + 1] = 242; pDstR[px * 4 + 2] = 254; pDstR[px * 4 + 3] = 255;
                                                    } else {
                                                        int srcX = (px * 640) / pipW;
                                                        const uint8_t* pixel = pSrcLine + srcX * 4;
                                                        memcpy(pDstL + px * 4, pixel, 4);
                                                        memcpy(pDstR + px * 4, pixel, 4);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                m_hasNewFrame = true;
                                m_frameCounter++;
                            }
                            m_pContext->Unmap(m_pStagingTex[readStg], 0);
                            m_frameCv.notify_all();
                        }
                    }

                    m_stagingIdx = readStg;
                }
            }
        }

        if (pSharedTex) { pSharedTex->Release(); pSharedTex = nullptr; }
        for (int i = 0; i < 2; i++) {
            if (m_pStagingTex[i]) { m_pStagingTex[i]->Release(); m_pStagingTex[i] = nullptr; }
        }
        if (m_pContext) { m_pContext->Release(); m_pContext = nullptr; }
        if (m_pDevice) { m_pDevice->Release(); m_pDevice = nullptr; }
    }

    void CreateStagingTextures() {
        if (!m_pDevice) return;
        for (int i = 0; i < 2; i++) {
            if (m_pStagingTex[i]) {
                m_pStagingTex[i]->Release();
                m_pStagingTex[i] = nullptr;
            }
            D3D11_TEXTURE2D_DESC stgDesc = {};
            stgDesc.Width = m_width;
            stgDesc.Height = m_height;
            stgDesc.MipLevels = 1;
            stgDesc.ArraySize = 1;
            stgDesc.Format = m_format;
            stgDesc.SampleDesc.Count = 1;
            stgDesc.Usage = D3D11_USAGE_STAGING;
            stgDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            m_pDevice->CreateTexture2D(&stgDesc, nullptr, &m_pStagingTex[i]);
        }
    }

    void PipeThreadProc() {
        while (m_running) {
            if (m_hPipe == INVALID_HANDLE_VALUE) {
                m_hPipe = CreateNamedPipeA(
                    "\\\\.\\pipe\\vrfusion_stream",
                    PIPE_ACCESS_OUTBOUND,
                    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                    1,
                    16 * 1024 * 1024,
                    4096,
                    0,
                    nullptr
                );
            }

            if (m_hPipe == INVALID_HANDLE_VALUE) {
                Sleep(200);
                continue;
            }

            BOOL connected = ConnectNamedPipe(m_hPipe, nullptr) ? TRUE : (GetLastError() == ERROR_PIPE_CONNECTED);
            if (!connected) {
                Sleep(100);
                continue;
            }

            m_pipeConnected = true;
            std::vector<uint8_t> sendBuf;
            uint64_t lastSentCounter = 0;

            while (m_running && m_pipeConnected) {
                {
                    std::unique_lock<std::mutex> lk(m_frameMutex);
                    m_frameCv.wait_for(lk, std::chrono::milliseconds(25), [this, lastSentCounter]() {
                        return m_frameCounter != lastSentCounter || !m_running;
                    });
                    if (!m_running) break;
                    if (m_frameCounter == lastSentCounter) continue;

                    lastSentCounter = m_frameCounter;
                    sendBuf = m_frameBuffer;
                }

                if (!sendBuf.empty()) {
                    DWORD written = 0;
                    BOOL ok = WriteFile(m_hPipe, sendBuf.data(), (DWORD)sendBuf.size(), &written, nullptr);
                    if (!ok) {
                        m_pipeConnected = false;
                        DisconnectNamedPipe(m_hPipe);
                        break;
                    }
                }
            }
        }

        if (m_hPipe != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(m_hPipe);
            CloseHandle(m_hPipe);
            m_hPipe = INVALID_HANDLE_VALUE;
        }
    }

    void MirrorPipeThreadProc() {
        while (m_running) {
            if (m_hMirrorPipe == INVALID_HANDLE_VALUE) {
                m_hMirrorPipe = CreateNamedPipeA(
                    "\\\\.\\pipe\\vrfusion_mirror",
                    PIPE_ACCESS_OUTBOUND,
                    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                    1,
                    16 * 1024 * 1024,
                    4096,
                    0,
                    nullptr
                );
            }

            if (m_hMirrorPipe == INVALID_HANDLE_VALUE) {
                Sleep(500);
                continue;
            }

            BOOL connected = ConnectNamedPipe(m_hMirrorPipe, nullptr) ? TRUE : (GetLastError() == ERROR_PIPE_CONNECTED);
            if (!connected) {
                Sleep(200);
                continue;
            }

            std::vector<uint8_t> sendBuf;
            uint64_t lastMirrorCounter = 0;

            while (m_running) {
                {
                    std::unique_lock<std::mutex> lk(m_frameMutex);
                    m_frameCv.wait_for(lk, std::chrono::milliseconds(25), [this, lastMirrorCounter]() {
                        return m_frameCounter != lastMirrorCounter || !m_running;
                    });
                    if (!m_running) break;
                    if (m_frameCounter == lastMirrorCounter) continue;

                    lastMirrorCounter = m_frameCounter;
                    sendBuf = m_frameBuffer;
                }

                if (!sendBuf.empty()) {
                    DWORD written = 0;
                    BOOL ok = WriteFile(m_hMirrorPipe, sendBuf.data(), (DWORD)sendBuf.size(), &written, nullptr);
                    if (!ok) {
                        DisconnectNamedPipe(m_hMirrorPipe);
                        break;
                    }
                }
            }
        }

        if (m_hMirrorPipe != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(m_hMirrorPipe);
            CloseHandle(m_hMirrorPipe);
            m_hMirrorPipe = INVALID_HANDLE_VALUE;
        }
    }

    void KinectCamPipeThreadProc() {
        while (m_running) {
            if (m_hCamPipe == INVALID_HANDLE_VALUE) {
                m_hCamPipe = CreateNamedPipeA(
                    "\\\\.\\pipe\\vrfusion_kinect_cam",
                    PIPE_ACCESS_OUTBOUND,
                    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                    1,
                    4 * 1024 * 1024,
                    4096,
                    0,
                    nullptr
                );
            }
            if (m_hCamPipe == INVALID_HANDLE_VALUE) {
                Sleep(500);
                continue;
            }
            BOOL connected = ConnectNamedPipe(m_hCamPipe, nullptr) ? TRUE : (GetLastError() == ERROR_PIPE_CONNECTED);
            if (!connected) {
                Sleep(200);
                continue;
            }

            g_kinectCamPipeActive.store(true);
            std::vector<uint8_t> sendBuf;
            uint64_t lastCounter = 0;
            while (m_running) {
                {
                    std::unique_lock<std::mutex> lk(g_kinectCamMutex);
                    g_kinectCamCv.wait_for(lk, std::chrono::milliseconds(40), [this, lastCounter]() {
                        return g_kinectCamCounter != lastCounter || !m_running;
                    });
                    if (!m_running) break;
                    if (g_kinectCamCounter == lastCounter) continue;
                    lastCounter = g_kinectCamCounter;
                    sendBuf = g_kinectCamFrame;
                }
                if (!sendBuf.empty()) {
                    DWORD written = 0;
                    BOOL ok = WriteFile(m_hCamPipe, sendBuf.data(), (DWORD)sendBuf.size(), &written, nullptr);
                    if (!ok) {
                        g_kinectCamPipeActive.store(false);
                        DisconnectNamedPipe(m_hCamPipe);
                        break;
                    }
                }
            }
            g_kinectCamPipeActive.store(false);
        }
        if (m_hCamPipe != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(m_hCamPipe);
            CloseHandle(m_hCamPipe);
            m_hCamPipe = INVALID_HANDLE_VALUE;
        }
    }

    std::atomic<bool> m_running{false};
    std::thread m_thread;
    std::thread m_pipeThread;
    std::thread m_mirrorPipeThread;
    std::thread m_camPipeThread;
    std::mutex m_presentMutex;
    std::condition_variable m_presentCv;
    SharedTextureHandle_t m_pendingHandle = 0;

    uint32_t m_width = 1920;
    uint32_t m_height = 1080;
    DXGI_FORMAT m_format = DXGI_FORMAT_R8G8B8A8_UNORM;

    ID3D11Device* m_pDevice = nullptr;
    ID3D11DeviceContext* m_pContext = nullptr;
    ID3D11Texture2D* m_pStagingTex[2] = { nullptr, nullptr };
    int m_stagingIdx = 0;

    HANDLE m_hPipe = INVALID_HANDLE_VALUE;
    HANDLE m_hMirrorPipe = INVALID_HANDLE_VALUE;
    HANDLE m_hCamPipe = INVALID_HANDLE_VALUE;
    std::atomic<bool> m_pipeConnected{false};
    std::mutex m_frameMutex;
    std::condition_variable m_frameCv;
    bool m_hasNewFrame = false;
    uint64_t m_frameCounter = 0;
    std::vector<uint8_t> m_frameBuffer;
};

// ════════════════════════════════════════════════════════════════════
//  HMD Device
// ════════════════════════════════════════════════════════════════════
class CVRFusionHMD : public ITrackedDeviceServerDriver, public IVRDisplayComponent, public IVRVirtualDisplay {
public:
    EVRInitError Activate(uint32_t unObjectId) override {
        m_deviceId = unObjectId;
        auto props = VRProperties();
        m_container = props->TrackedDeviceToPropertyContainer(m_deviceId);

        props->SetStringProperty(m_container, Prop_ManufacturerName_String,    "VRFusion");
        props->SetStringProperty(m_container, Prop_ModelNumber_String,         "VRFusion HMD");
        props->SetStringProperty(m_container, Prop_SerialNumber_String,        "vrfusion_hmd");
        props->SetStringProperty(m_container, Prop_TrackingSystemName_String,  "VRFusion");
        props->SetBoolProperty  (m_container, Prop_IsOnDesktop_Bool,           false);
        props->SetBoolProperty  (m_container, Prop_HasVirtualDisplayComponent_Bool, true);
        props->SetBoolProperty  (m_container, Prop_DisplayDebugMode_Bool,      false);
        props->SetBoolProperty  (m_container, Prop_ContainsProximitySensor_Bool, true);
        props->SetBoolProperty  (m_container, Prop_DeviceProvidesBatteryStatus_Bool, false);
        props->SetBoolProperty  (m_container, Prop_DeviceCanPowerOff_Bool,     true);
        props->SetBoolProperty  (m_container, Prop_DeviceIsWireless_Bool,      true);
        props->SetBoolProperty  (m_container, Prop_ReportsTimeSinceVSync_Bool, true);
        props->SetBoolProperty  (m_container, Prop_DisplaySuppressed_Bool,     false);
        props->SetUint64Property(m_container, Prop_CurrentUniverseId_Uint64,   2);
        props->SetFloatProperty (m_container, Prop_SecondsFromVsyncToPhotons_Float, 0.004f);
        props->SetFloatProperty (m_container, Prop_DisplayFrequency_Float,     120.0f);
        props->SetFloatProperty (m_container, Prop_UserIpdMeters_Float,        0.063f);
        props->SetStringProperty(m_container, Prop_InputProfilePath_String,    "{vrfusion}/input/vrfusion_hmd_profile.json");

        if (auto vrinput = VRDriverInput()) {
            vrinput->CreateBooleanComponent(m_container, "/input/proximity/click", &m_hProximity);
            vrinput->UpdateBooleanComponent(m_hProximity, true, 0.0);
        }

        m_activated = true;
        m_lastVsyncTime = std::chrono::steady_clock::now();
        m_mirrorPresenter.Start(1920, 1080);
        return VRInitError_None;
    }

    void Deactivate() override {
        m_mirrorPresenter.Stop();
        m_activated = false;
        m_deviceId = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {
        m_inStandby = true;
        m_userPresent = false;
    }

    void LeaveStandby() {
        m_inStandby = false;
        m_userPresent = true;
        if (m_hProximity != k_ulInvalidInputComponentHandle) {
            if (auto vrinput = VRDriverInput()) {
                vrinput->UpdateBooleanComponent(m_hProximity, true, 0.0);
            }
        }
    }

    bool IsUserPresent() const {
        return m_userPresent;
    }

    bool IsInStandby() const {
        return m_inStandby;
    }

    void* GetComponent(const char* pchComponentNameAndVersion) override {
        if (pchComponentNameAndVersion) {
            if (!strcmp(pchComponentNameAndVersion, IVRDisplayComponent_Version)) {
                return static_cast<IVRDisplayComponent*>(this);
            }
            if (!strcmp(pchComponentNameAndVersion, IVRVirtualDisplay_Version)) {
                return static_cast<IVRVirtualDisplay*>(this);
            }
        }
        return nullptr;
    }

    void DebugRequest(const char*, char*, uint32_t) override {}

    DriverPose_t GetPose() override {
        DriverPose_t pose{};
        pose.poseIsValid = true;
        pose.result = TrackingResult_Running_OK;
        pose.deviceIsConnected = true;

        pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
        pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };
        pose.poseTimeOffset = 0.0;
        pose.shouldApplyHeadModel = false;
        pose.willDriftInYaw = false;

        std::lock_guard<std::mutex> lk(m_mutex);
        pose.qRotation = { m_qw, m_qx, m_qy, m_qz };
        pose.vecPosition[0] = m_px;
        pose.vecPosition[1] = m_py;
        pose.vecPosition[2] = m_pz;
        return pose;
    }

    void SetUserPresent(bool present) {
        if (m_userPresent != present) {
            m_userPresent = present;
            if (m_hProximity != k_ulInvalidInputComponentHandle) {
                if (auto vrinput = VRDriverInput()) {
                    vrinput->UpdateBooleanComponent(m_hProximity, present, 0.0);
                }
            }
        }
    }

    void UpdateOrientation(float qw, float qx, float qy, float qz) {
        auto now = std::chrono::steady_clock::now();
        bool sendProximityUpdate = false;
        bool newPresence = true;
        {
            std::lock_guard<std::mutex> lk(m_mutex);
            float d = std::abs(m_qw - qw) + std::abs(m_qx - qx) + std::abs(m_qy - qy) + std::abs(m_qz - qz);
            if (d > 0.0003f) {
                m_lastHeadMovement = now;
                if (!m_userPresent || m_inStandby) {
                    m_userPresent = true;
                    m_inStandby = false;
                    sendProximityUpdate = true;
                    newPresence = true;
                }
            } else {
                auto idleSec = std::chrono::duration_cast<std::chrono::seconds>(now - m_lastHeadMovement).count();
                if (m_userPresent && idleSec >= 15) {
                    m_userPresent = false;
                    sendProximityUpdate = true;
                    newPresence = false;
                }
            }
            m_qw = qw;
            m_qx = qx;
            m_qy = qy;
            m_qz = qz;
        }
        if (sendProximityUpdate && m_hProximity != k_ulInvalidInputComponentHandle) {
            if (auto vrinput = VRDriverInput()) {
                vrinput->UpdateBooleanComponent(m_hProximity, newPresence, 0.0);
            }
        }
    }

    void UpdatePosition(float px, float py, float pz) {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_px = px;
        m_py = py;
        m_pz = pz;
    }

    bool IsActivated() const { return m_activated; }
    uint32_t GetDeviceId() const { return m_deviceId; }

    void SetIPD(float ipd) {
        if (m_container != k_ulInvalidPropertyContainer) {
            VRProperties()->SetFloatProperty(m_container, Prop_UserIpdMeters_Float, ipd);
        }
    }

    // ── IVRDisplayComponent ──────────────────────────────────────────
    void GetWindowBounds(int32_t* px, int32_t* py, uint32_t* pw, uint32_t* ph) override {
        *px = 0; *py = 0; *pw = 1920; *ph = 1080;
    }

    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }

    void GetRecommendedRenderTargetSize(uint32_t* pw, uint32_t* ph) override {
        *pw = 1080; *ph = 1080;
    }

    void GetEyeOutputViewport(EVREye eye, uint32_t* px, uint32_t* py, uint32_t* pw, uint32_t* ph) override {
        *py = 0; *ph = 1080;
        *pw = 960;
        *px = (eye == Eye_Left) ? 0 : 960;
    }

    void GetProjectionRaw(EVREye, float* pfLeft, float* pfRight, float* pfTop, float* pfBottom) override {
        *pfLeft = -1.0f; *pfRight = 1.0f; *pfTop = -1.0f; *pfBottom = 1.0f;
    }

    DistortionCoordinates_t ComputeDistortion(EVREye, float u, float v) override {
        DistortionCoordinates_t d{};
        d.rfRed[0] = u; d.rfRed[1] = v;
        d.rfGreen[0] = u; d.rfGreen[1] = v;
        d.rfBlue[0] = u; d.rfBlue[1] = v;
        return d;
    }

    bool ComputeInverseDistortion(HmdVector2_t *pResult, EVREye, uint32_t, float u, float v) override {
        if (pResult) {
            pResult->v[0] = u;
            pResult->v[1] = v;
            return true;
        }
        return false;
    }

    // ── IVRVirtualDisplay ─────────────────────────────────────────────
    void Present(const PresentInfo_t *pPresentInfo, uint32_t unPresentInfoSize) override {
        if (pPresentInfo && pPresentInfo->backbufferTextureHandle) {
            m_lastFrameId = pPresentInfo->nFrameId;
            m_backbuffer = pPresentInfo->backbufferTextureHandle;
            m_mirrorPresenter.PresentTexture(m_backbuffer);
        }
    }

    void WaitForPresent() override {
        // High-precision 120 FPS vsync pacing (8333.33 microseconds) without CPU busy-wait
        using namespace std::chrono;
        auto target = m_lastVsyncTime + microseconds(8333);
        auto now = steady_clock::now();
        if (target > now) {
            std::this_thread::sleep_until(target);
        }
        m_lastVsyncTime = steady_clock::now();
    }

    bool GetTimeSinceLastVsync(float *pfSecondsSinceLastVsync, uint64_t *pulFrameCounter) override {
        auto now = std::chrono::steady_clock::now();
        float elapsed = std::chrono::duration<float>(now - m_lastVsyncTime).count();
        float framePeriod = 1.0f / 120.0f;
        if (pfSecondsSinceLastVsync) *pfSecondsSinceLastVsync = fmodf(elapsed, framePeriod);
        if (pulFrameCounter) *pulFrameCounter = ++m_vsyncCounter;
        return true;
    }

private:
    uint32_t m_deviceId = k_unTrackedDeviceIndexInvalid;
    PropertyContainerHandle_t m_container = k_ulInvalidPropertyContainer;
    std::atomic<bool> m_activated{false};
    std::mutex m_mutex;
    float m_qw = 1.0f, m_qx = 0.0f, m_qy = 0.0f, m_qz = 0.0f;
    float m_px = 0.0f, m_py = 1.65f, m_pz = 0.0f;
    uint64_t m_lastFrameId = 0;
    SharedTextureHandle_t m_backbuffer = 0;
    uint64_t m_vsyncCounter = 0;
    std::chrono::steady_clock::time_point m_lastVsyncTime;
    CVRFusionMirrorPresenter m_mirrorPresenter;
    bool m_userPresent = true;
    bool m_inStandby = false;
    std::chrono::steady_clock::time_point m_lastHeadMovement = std::chrono::steady_clock::now();
    VRInputComponentHandle_t m_hProximity = k_ulInvalidInputComponentHandle;
};

// ════════════════════════════════════════════════════════════════════
//  Controller Device (Left or Right)
// ════════════════════════════════════════════════════════════════════
class CVRFusionController : public ITrackedDeviceServerDriver {
public:
    CVRFusionController(ETrackedControllerRole role) : m_role(role) {}

    EVRInitError Activate(uint32_t id) override {
        m_deviceId = id;
        auto props = VRProperties();
        m_container = props->TrackedDeviceToPropertyContainer(m_deviceId);
        bool isLeft = (m_role == TrackedControllerRole_LeftHand);

        props->SetStringProperty(m_container, Prop_ManufacturerName_String,   "VRFusion");
        props->SetStringProperty(m_container, Prop_ModelNumber_String,        isLeft ? "VRFusion Left" : "VRFusion Right");
        props->SetStringProperty(m_container, Prop_SerialNumber_String,       isLeft ? "vrfusion_ctrl_left" : "vrfusion_ctrl_right");
        props->SetStringProperty(m_container, Prop_InputProfilePath_String,   "{vrfusion}/input/vrfusion_controller_profile.json");
        props->SetStringProperty(m_container, Prop_RenderModelName_String,    isLeft ? "{indexcontroller}/rendermodels/valve_controller_knu_1_0_left" : "{indexcontroller}/rendermodels/valve_controller_knu_1_0_right");
        props->SetStringProperty(m_container, Prop_ControllerType_String,     "knuckles");
        props->SetInt32Property (m_container, Prop_ControllerRoleHint_Int32,  m_role);
        props->SetUint64Property(m_container, Prop_CurrentUniverseId_Uint64,  2);
        props->SetBoolProperty  (m_container, Prop_DeviceIsWireless_Bool,     true);

        auto vrinput = VRDriverInput();
        if (vrinput) {
            vrinput->CreateBooleanComponent(m_container, "/input/trigger/click", &m_trigger);
            vrinput->CreateBooleanComponent(m_container, "/input/grip/click",    &m_grip);
            vrinput->CreateBooleanComponent(m_container, "/input/a/click",       &m_btnA);
            vrinput->CreateBooleanComponent(m_container, "/input/b/click",       &m_btnB);
            vrinput->CreateBooleanComponent(m_container, "/input/system/click",  &m_system);
            vrinput->CreateScalarComponent (m_container, "/input/trigger/value", &m_triggerAxis, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided);
            vrinput->CreateScalarComponent (m_container, "/input/joystick/x",    &m_joyX, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
            vrinput->CreateScalarComponent (m_container, "/input/joystick/y",    &m_joyY, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided);
            vrinput->CreateHapticComponent (m_container, "/output/haptic",       &m_haptic);
        }

        m_activated = true;
        return VRInitError_None;
    }

    void Deactivate() override {
        m_activated = false;
        m_deviceId = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char*, uint32_t) override {}

    DriverPose_t GetPose() override {
        DriverPose_t pose{};
        pose.poseIsValid = true;
        pose.result = TrackingResult_Running_OK;
        pose.deviceIsConnected = true;
        pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
        pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };
        pose.poseTimeOffset = 0.0;
        pose.shouldApplyHeadModel = false;
        pose.willDriftInYaw = false;

        std::lock_guard<std::mutex> lk(m_mutex);
        pose.qRotation = { m_qw, m_qx, m_qy, m_qz };
        pose.vecPosition[0] = m_px;
        pose.vecPosition[1] = m_py;
        pose.vecPosition[2] = m_pz;
        return pose;
    }

    void UpdatePosition(float x, float y, float z) {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_px = x;
        m_py = y;
        m_pz = z;
    }

    void UpdatePose(float x, float y, float z, float qw, float qx, float qy, float qz) {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_px = x;
        m_py = y;
        m_pz = z;
        m_qw = qw;
        m_qx = qx;
        m_qy = qy;
        m_qz = qz;
    }

    void UpdateFromXInput(const XINPUT_STATE& state, bool isLeft) {
        if (!m_activated || m_deviceId == k_unTrackedDeviceIndexInvalid) return;
        auto vrinput = VRDriverInput();
        if (!vrinput) return;

        float lt = state.Gamepad.bLeftTrigger  / 255.0f;
        float rt = state.Gamepad.bRightTrigger / 255.0f;
        float lx = state.Gamepad.sThumbLX / 32767.0f;
        float ly = state.Gamepad.sThumbLY / 32767.0f;
        float rx = state.Gamepad.sThumbRX / 32767.0f;
        float ry = state.Gamepad.sThumbRY / 32767.0f;
        WORD btn = state.Gamepad.wButtons;

        double now = 0.0;

        if (isLeft) {
            if (m_triggerAxis) vrinput->UpdateScalarComponent(m_triggerAxis, lt, now);
            if (m_trigger)     vrinput->UpdateBooleanComponent(m_trigger, lt > 0.5f, now);
            if (m_grip)        vrinput->UpdateBooleanComponent(m_grip, (btn & XINPUT_GAMEPAD_LEFT_SHOULDER) != 0, now);
            if (m_joyX)        vrinput->UpdateScalarComponent(m_joyX, lx, now);
            if (m_joyY)        vrinput->UpdateScalarComponent(m_joyY, ly, now);
            if (m_btnA)        vrinput->UpdateBooleanComponent(m_btnA, (btn & XINPUT_GAMEPAD_X) != 0, now);
            if (m_btnB)        vrinput->UpdateBooleanComponent(m_btnB, (btn & XINPUT_GAMEPAD_Y) != 0, now);
            if (m_system)      vrinput->UpdateBooleanComponent(m_system, (btn & XINPUT_GAMEPAD_START) != 0, now);
        } else {
            if (m_triggerAxis) vrinput->UpdateScalarComponent(m_triggerAxis, rt, now);
            if (m_trigger)     vrinput->UpdateBooleanComponent(m_trigger, rt > 0.5f, now);
            if (m_grip)        vrinput->UpdateBooleanComponent(m_grip, (btn & XINPUT_GAMEPAD_RIGHT_SHOULDER) != 0, now);
            if (m_joyX)        vrinput->UpdateScalarComponent(m_joyX, rx, now);
            if (m_joyY)        vrinput->UpdateScalarComponent(m_joyY, ry, now);
            if (m_btnA)        vrinput->UpdateBooleanComponent(m_btnA, (btn & XINPUT_GAMEPAD_A) != 0, now);
            if (m_btnB)        vrinput->UpdateBooleanComponent(m_btnB, (btn & XINPUT_GAMEPAD_B) != 0, now);
            if (m_system)      vrinput->UpdateBooleanComponent(m_system, (btn & XINPUT_GAMEPAD_BACK) != 0, now);
        }
    }

    bool IsActivated() const { return m_activated; }
    uint32_t GetDeviceId() const { return m_deviceId; }

private:
    ETrackedControllerRole m_role;
    uint32_t m_deviceId = k_unTrackedDeviceIndexInvalid;
    PropertyContainerHandle_t m_container = k_ulInvalidPropertyContainer;
    std::atomic<bool> m_activated{false};
    std::mutex m_mutex;
    float m_px = 0.0f, m_py = 1.0f, m_pz = 0.0f;
    float m_qw = 1.0f, m_qx = 0.0f, m_qy = 0.0f, m_qz = 0.0f;
    VRInputComponentHandle_t m_trigger = 0, m_grip = 0, m_btnA = 0, m_btnB = 0, m_system = 0;
    VRInputComponentHandle_t m_triggerAxis = 0, m_joyX = 0, m_joyY = 0;
    VRInputComponentHandle_t m_haptic = 0;
};

// ════════════════════════════════════════════════════════════════════
//  Full Body Tracker Device (Waist, Feet, Knees)
// ════════════════════════════════════════════════════════════════════
class CVRFusionTracker : public ITrackedDeviceServerDriver {
public:
    CVRFusionTracker(const std::string& serial, const std::string& roleStr)
        : m_serial(serial), m_roleStr(roleStr) {}

    EVRInitError Activate(uint32_t id) override {
        m_deviceId = id;
        auto props = VRProperties();
        m_container = props->TrackedDeviceToPropertyContainer(m_deviceId);

        props->SetStringProperty(m_container, Prop_TrackingSystemName_String, "VRFusion");
        props->SetStringProperty(m_container, Prop_ModelNumber_String,        "Vive Tracker Pro MV");
        props->SetStringProperty(m_container, Prop_SerialNumber_String,       m_serial.c_str());
        props->SetStringProperty(m_container, Prop_RenderModelName_String,    "{htc}/rendermodels/vr_tracker_vive_1_0");
        props->SetStringProperty(m_container, Prop_ControllerType_String,     "vive_tracker");
        props->SetInt32Property (m_container, Prop_DeviceClass_Int32,         TrackedDeviceClass_GenericTracker);
        props->SetInt32Property (m_container, Prop_ControllerRoleHint_Int32,  TrackedControllerRole_Invalid);
        props->SetStringProperty(m_container, Prop_InputProfilePath_String,   "{vrfusion}/input/vrfusion_tracker_profile.json");
        props->SetUint64Property(m_container, Prop_CurrentUniverseId_Uint64,  2);
        props->SetBoolProperty  (m_container, Prop_DeviceIsWireless_Bool,     true);

        m_activated = true;
        return VRInitError_None;
    }

    void Deactivate() override {
        m_activated = false;
        m_deviceId = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char*, uint32_t) override {}

    DriverPose_t GetPose() override {
        DriverPose_t pose{};
        pose.poseIsValid = m_tracked;
        pose.result = m_tracked ? TrackingResult_Running_OK : TrackingResult_Uninitialized;
        pose.deviceIsConnected = true;
        pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
        pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };
        pose.qRotation = { 1, 0, 0, 0 };
        pose.poseTimeOffset = 0.0;
        pose.shouldApplyHeadModel = false;
        pose.willDriftInYaw = false;

        std::lock_guard<std::mutex> lk(m_mutex);
        pose.vecPosition[0] = m_x;
        pose.vecPosition[1] = m_y;
        pose.vecPosition[2] = m_z;
        return pose;
    }

    void UpdatePosition(float x, float y, float z, bool tracked) {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_x = x;
        m_y = y;
        m_z = z;
        m_tracked = tracked;
    }

    bool IsActivated() const { return m_activated; }
    uint32_t GetDeviceId() const { return m_deviceId; }

private:
    std::string m_serial;
    std::string m_roleStr;
    uint32_t m_deviceId = k_unTrackedDeviceIndexInvalid;
    PropertyContainerHandle_t m_container = k_ulInvalidPropertyContainer;
    std::atomic<bool> m_activated{false};
    std::atomic<bool> m_tracked{false};
    std::mutex m_mutex;
    float m_x = 0.0f, m_y = 0.5f, m_z = 0.0f;
};

// Global device instances
static CVRFusionHMD*        g_hmd              = nullptr;
static CVRFusionController* g_ctrlLeft         = nullptr;
static CVRFusionController* g_ctrlRight        = nullptr;
static CVRFusionTracker*    g_trackerWaist     = nullptr;
static CVRFusionTracker*    g_trackerChest     = nullptr;
static CVRFusionTracker*    g_trackerLeftFoot  = nullptr;
static CVRFusionTracker*    g_trackerRightFoot = nullptr;
static CVRFusionTracker*    g_trackerLeftKnee  = nullptr;
static CVRFusionTracker*    g_trackerRightKnee = nullptr;
static CVRFusionTracker*    g_trackerLeftElbow = nullptr;
static CVRFusionTracker*    g_trackerRightElbow= nullptr;

static std::atomic<bool> g_running{false};
static std::atomic<float> g_telemYaw{0.0f};
static std::atomic<float> g_telemPitch{0.0f};
static std::atomic<float> g_telemRoll{0.0f};
static std::atomic<bool>  g_kinectTracking{false};
static std::atomic<bool>  g_kinectMirror{false};
static INuiSensor*        g_kinectSensor = nullptr;
static std::atomic<long>  g_kinectTiltAngle{0};
static std::atomic<long>  g_targetTiltAngle{-999};
static std::atomic<bool>  g_kinectSeated{false};
static std::atomic<bool>  g_pendingSeatedChange{false};
static std::atomic<float> g_kinectHeightOffset{0.0f};
static std::atomic<bool>  g_kinectAutoTilt{true};
static std::atomic<bool>  g_kinectMotorLocked{false};
static std::mutex         g_kinectSensorMutex;
static HANDLE             g_hKinectSkelEvent = nullptr;

// ════════════════════════════════════════════════════════════════════
//  UDP receiver thread — phone → PC (Port 9550)
// ════════════════════════════════════════════════════════════════════
static void UdpReceiverThread() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    SOCKET sock = socket(AF_INET, SOCK_DGRAM, 0);
    int optval = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, (char*)&optval, sizeof(optval));

    sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port        = htons(IMU_PORT);
    bind(sock, (sockaddr*)&addr, sizeof(addr));

    DWORD timeout = 200;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&timeout, sizeof(timeout));

    char buf[256];
    sockaddr_in fromAddr{};
    int fromLen = sizeof(fromAddr);
    while (g_running) {
        fromLen = sizeof(fromAddr);
        int r = recvfrom(sock, buf, sizeof(buf), 0, (sockaddr*)&fromAddr, &fromLen);
        if (r >= sizeof(BinaryIMUPacket)) {
            auto* pkt = reinterpret_cast<BinaryIMUPacket*>(buf);
            if (memcmp(pkt->magic, "KVRI", 4) == 0 && g_hmd) {
                char ipStr[64] = {0};
                inet_ntop(AF_INET, &fromAddr.sin_addr, ipStr, sizeof(ipStr));
                if (ipStr[0] != '\0') {
                    std::lock_guard<std::mutex> lock(g_phoneIpMutex);
                    g_phoneSenderIp = ipStr;
                }
                g_lastImuPacketTime = GetTickCount64();

                // Phone sends OpenVR-standard quaternion (X=right, Y=up, -Z=fwd):
                g_hmd->UpdateOrientation(pkt->qw, pkt->qx, pkt->qy, pkt->qz);
                if (g_hmd->IsActivated()) {
                    VRServerDriverHost()->TrackedDevicePoseUpdated(g_hmd->GetDeviceId(), g_hmd->GetPose(), sizeof(DriverPose_t));
                }

                // Calculate Euler angles for telemetry
                float qw = pkt->qw, qx = pkt->qx, qy = pkt->qy, qz = pkt->qz;
                float sqw = qw * qw, sqx = qx * qx, sqy = qy * qy, sqz = qz * qz;
                float unit = sqx + sqy + sqz + sqw;
                float test = qx * qy + qz * qw;
                float y, p, r;
                if (test > 0.499f * unit) {
                    y = 2.0f * atan2(qx, qw);
                    p = 3.14159265f / 2.0f;
                    r = 0.0f;
                } else if (test < -0.499f * unit) {
                    y = -2.0f * atan2(qx, qw);
                    p = -3.14159265f / 2.0f;
                    r = 0.0f;
                } else {
                    y = atan2(2.0f * qy * qw - 2.0f * qx * qz, sqx - sqy - sqz + sqw);
                    p = asin(2.0f * test / unit);
                    r = atan2(2.0f * qx * qw - 2.0f * qy * qz, -sqx + sqy - sqz + sqw);
                }
                g_telemYaw = y * (180.0f / 3.14159265f);
                g_telemPitch = p * (180.0f / 3.14159265f);
                g_telemRoll = r * (180.0f / 3.14159265f);
            }
        }
    }

    closesocket(sock);
    WSACleanup();
}

// ════════════════════════════════════════════════════════════════════
//  Isolated SEH Helpers for Kinect Hardware Protection
// ════════════════════════════════════════════════════════════════════
static bool SafeSetElevation(INuiSensor* s, LONG angle) {
    if (!s) return false;
    __try {
        return SUCCEEDED(s->NuiCameraElevationSetAngle(angle));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeGetElevation(INuiSensor* s, LONG* pAngle) {
    if (!s || !pAngle) return false;
    __try {
        return SUCCEEDED(s->NuiCameraElevationGetAngle(pAngle));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeGetAccelerometer(INuiSensor* s, Vector4* pReading) {
    if (!s || !pReading) return false;
    __try {
        return SUCCEEDED(s->NuiAccelerometerGetCurrentReading(pReading));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeEnableSkeleton(INuiSensor* s, HANDLE hEvent, DWORD flags) {
    if (!s || !hEvent) return false;
    __try {
        return SUCCEEDED(s->NuiSkeletonTrackingEnable(hEvent, flags));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeGetSkeletonFrame(INuiSensor* s, DWORD ms, NUI_SKELETON_FRAME* pFrame) {
    if (!s || !pFrame) return false;
    __try {
        return SUCCEEDED(s->NuiSkeletonGetNextFrame(ms, pFrame));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeSmoothSkeleton(INuiSensor* s, NUI_SKELETON_FRAME* pFrame) {
    if (!s || !pFrame) return false;
    __try {
        return SUCCEEDED(s->NuiTransformSmooth(pFrame, nullptr));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool SafeGetImageFrame(INuiSensor* s, HANDLE hStream, NUI_IMAGE_FRAME* pFrame) {
    if (!s || hStream == INVALID_HANDLE_VALUE || !pFrame) return false;
    __try {
        return SUCCEEDED(s->NuiImageStreamGetNextFrame(hStream, 0, pFrame));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static void SafeReleaseImageFrame(INuiSensor* s, HANDLE hStream, NUI_IMAGE_FRAME* pFrame) {
    if (!s || hStream == INVALID_HANDLE_VALUE || !pFrame) return;
    __try {
        s->NuiImageStreamReleaseFrame(hStream, pFrame);
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
}

// ════════════════════════════════════════════════════════════════════
//  Dedicated Kinect Elevation Thread (Isolated Hardware Task)
//  Matches Microsoft Developer Toolkit NuiTiltAngleViewer architecture
//  Prevents PC reboots, USB controller stalls and power supply trips
// ════════════════════════════════════════════════════════════════════
static void KinectElevationThread(INuiSensor* sensor) {
    if (!sensor) return;

    LONG initialAngle = 0;
    if (SafeGetElevation(sensor, &initialAngle)) {
        g_kinectTiltAngle.store(initialAngle);
    }
    Vector4 initAcc{};
    if (SafeGetAccelerometer(sensor, &initAcc)) {
        g_kinectAccX = initAcc.x;
        g_kinectAccY = initAcc.y;
        g_kinectAccZ = initAcc.z;
        g_kinectPitch = atan2(initAcc.z, -initAcc.y) * 57.29578f;
        g_kinectRoll = atan2(initAcc.x, sqrt(initAcc.y * initAcc.y + initAcc.z * initAcc.z)) * 57.29578f;
    }

    auto lastAccCheck = std::chrono::steady_clock::now();

    while (g_running) {
        long target = g_targetTiltAngle.load();
        long current = g_kinectTiltAngle.load();

        if (target != -999 && !g_kinectMotorLocked.load()) {
            if (target < -8L) target = -8L;
            if (target > 14L) target = 14L;

            if (target != current) {
                // Direct smooth motion to target angle in one single sweep
                SafeSetElevation(sensor, target);
                g_kinectTiltAngle.store(target);

                // Wait for hardware motor rotation to finish and electrical current to settle
                long diff = std::abs(target - current);
                int waitMs = 700 + (int)(diff * 80);
                int chunks = waitMs / 100;
                for (int i = 0; i < chunks && g_running; i++) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }

                LONG phys = 0;
                if (SafeGetElevation(sensor, &phys)) {
                    g_kinectTiltAngle.store(phys);
                }
            }
            g_targetTiltAngle.store(-999);
            lastAccCheck = std::chrono::steady_clock::now();
        } else {
            // Idle state: read accelerometer and angle at most once every 5 seconds
            auto now = std::chrono::steady_clock::now();
            auto elapsedSec = std::chrono::duration_cast<std::chrono::seconds>(now - lastAccCheck).count();
            if (elapsedSec >= 5) {
                lastAccCheck = now;
                Vector4 acc{};
                if (SafeGetAccelerometer(sensor, &acc)) {
                    g_kinectAccX = acc.x;
                    g_kinectAccY = acc.y;
                    g_kinectAccZ = acc.z;
                    g_kinectPitch = atan2(acc.z, -acc.y) * 57.29578f;
                    g_kinectRoll = atan2(acc.x, sqrt(acc.y * acc.y + acc.z * acc.z)) * 57.29578f;
                }
                LONG phys = 0;
                if (g_targetTiltAngle.load() == -999 && SafeGetElevation(sensor, &phys)) {
                    g_kinectTiltAngle.store(phys);
                }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Kinect 360 Full Body Tracking & Camera Stream Thread
// ════════════════════════════════════════════════════════════════════
static void KinectThread() {
    INuiSensor* sensor = nullptr;
    int count = 0;
    if (FAILED(NuiGetSensorCount(&count)) || count == 0) return;
    if (FAILED(NuiCreateSensorByIndex(0, &sensor))) return;

    DWORD nuiFlags = NUI_INITIALIZE_FLAG_USES_SKELETON | NUI_INITIALIZE_FLAG_USES_COLOR | NUI_INITIALIZE_FLAG_USES_DEPTH_AND_PLAYER_INDEX;
    if (FAILED(sensor->NuiInitialize(nuiFlags))) {
        nuiFlags = NUI_INITIALIZE_FLAG_USES_SKELETON;
        if (FAILED(sensor->NuiInitialize(nuiFlags))) {
            sensor->Release();
            return;
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_kinectSensorMutex);
        g_kinectSensor = sensor;
    }

    // Spawn dedicated elevation thread (matching Microsoft SDK)
    std::thread elevThread(KinectElevationThread, sensor);
    elevThread.detach();

    g_hKinectSkelEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    DWORD skelFlags = g_kinectSeated ? NUI_SKELETON_TRACKING_FLAG_ENABLE_SEATED_SUPPORT : 0;
    SafeEnableSkeleton(sensor, g_hKinectSkelEvent, skelFlags);

    HANDLE hColorStream = INVALID_HANDLE_VALUE;
    HANDLE hColorEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    HANDLE hDepthStream = INVALID_HANDLE_VALUE;
    HANDLE hDepthEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);

    if (nuiFlags & NUI_INITIALIZE_FLAG_USES_COLOR) {
        sensor->NuiImageStreamOpen(NUI_IMAGE_TYPE_COLOR, NUI_IMAGE_RESOLUTION_640x480, 0, 2, hColorEvent, &hColorStream);
    }
    if (nuiFlags & NUI_INITIALIZE_FLAG_USES_DEPTH_AND_PLAYER_INDEX) {
        sensor->NuiImageStreamOpen(NUI_IMAGE_TYPE_DEPTH_AND_PLAYER_INDEX, NUI_IMAGE_RESOLUTION_320x240, 0, 2, hDepthEvent, &hDepthStream);
    }

    int tiltUpFrames = 0;
    int tiltDownFrames = 0;
    int lostFrames = 0;

    while (g_running) {
        // Safe single-threaded hardware settings update between frames
        if (g_pendingSeatedChange.exchange(false)) {
            DWORD flags = g_kinectSeated ? NUI_SKELETON_TRACKING_FLAG_ENABLE_SEATED_SUPPORT : 0;
            SafeEnableSkeleton(sensor, g_hKinectSkelEvent, flags);
        }

        if (WaitForSingleObject(g_hKinectSkelEvent, 200) == WAIT_OBJECT_0) {
            NUI_SKELETON_FRAME frame{};
            if (SafeGetSkeletonFrame(sensor, 100, &frame)) {
                SafeSmoothSkeleton(sensor, &frame);

                float floorY = 1.0f;
                if (std::abs(frame.vFloorClipPlane.w) > 0.1f) {
                    floorY = frame.vFloorClipPlane.w;
                }
                float yOffset = floorY + g_kinectHeightOffset.load();

                bool found = false;
                for (int i = 0; i < NUI_SKELETON_COUNT; i++) {
                    auto& skel = frame.SkeletonData[i];
                    if (skel.eTrackingState == NUI_SKELETON_TRACKED) {
                        auto& head   = skel.SkeletonPositions[NUI_SKELETON_POSITION_HEAD];
                        auto& lhand  = skel.SkeletonPositions[NUI_SKELETON_POSITION_HAND_LEFT];
                        auto& rhand  = skel.SkeletonPositions[NUI_SKELETON_POSITION_HAND_RIGHT];
                        auto& hip    = skel.SkeletonPositions[NUI_SKELETON_POSITION_HIP_CENTER];
                        auto& chest  = skel.SkeletonPositions[NUI_SKELETON_POSITION_SPINE];
                        auto& lfoot  = skel.SkeletonPositions[NUI_SKELETON_POSITION_FOOT_LEFT];
                        auto& rfoot  = skel.SkeletonPositions[NUI_SKELETON_POSITION_FOOT_RIGHT];
                        auto& lknee  = skel.SkeletonPositions[NUI_SKELETON_POSITION_KNEE_LEFT];
                        auto& rknee  = skel.SkeletonPositions[NUI_SKELETON_POSITION_KNEE_RIGHT];
                        auto& lelbow = skel.SkeletonPositions[NUI_SKELETON_POSITION_ELBOW_LEFT];
                        auto& relbow = skel.SkeletonPositions[NUI_SKELETON_POSITION_ELBOW_RIGHT];

                        g_lastHeadZ = head.z;
                        if (g_kinectCenterZ <= 0.1f && head.z > 0.5f) {
                            g_kinectCenterZ = head.z;
                        }

                        // ── Auto-Tilt: True Deadband Full-Body Framing & Slew Jump ──
                        if (g_kinectAutoTilt.load() && !g_kinectMotorLocked.load() && head.z >= 0.8f && head.z <= 4.8f) {
                            float zDist = head.z;
                            float headAngleDeg = atan2(head.y, zDist) * 57.29578f;

                            bool feetTracked = (skel.eSkeletonPositionTrackingState[NUI_SKELETON_POSITION_FOOT_LEFT] == NUI_SKELETON_POSITION_TRACKED ||
                                                skel.eSkeletonPositionTrackingState[NUI_SKELETON_POSITION_FOOT_RIGHT] == NUI_SKELETON_POSITION_TRACKED);
                            bool kneesTracked = (skel.eSkeletonPositionTrackingState[NUI_SKELETON_POSITION_KNEE_LEFT] == NUI_SKELETON_POSITION_TRACKED ||
                                                 skel.eSkeletonPositionTrackingState[NUI_SKELETON_POSITION_KNEE_RIGHT] == NUI_SKELETON_POSITION_TRACKED);

                            float footY = 0.0f;
                            float footZ = zDist;
                            if (feetTracked) {
                                footY = (lfoot.y < rfoot.y) ? lfoot.y : rfoot.y;
                                footZ = (lfoot.y < rfoot.y) ? lfoot.z : rfoot.z;
                            } else if (kneesTracked) {
                                float lowestKneeY = (lknee.y < rknee.y) ? lknee.y : rknee.y;
                                footY = lowestKneeY - 0.45f;
                            } else {
                                footY = hip.y - 0.85f;
                            }
                            if (footZ < 0.5f) footZ = zDist;
                            float footAngleDeg = atan2(footY, footZ) * 57.29578f;

                            // Kinect vertical FOV is +-21.8 deg.
                            // Stable comfort zone: Head in upper half (<= +14 deg), Feet in lower half (>= -14 deg).
                            float deltaAngle = 0.0f;

                            if (headAngleDeg > 14.0f && footAngleDeg > -18.0f) {
                                // Head too high and approaching top edge: tilt UP to place head at ~+9 deg
                                deltaAngle = headAngleDeg - 9.0f;
                            } else if (footAngleDeg < -14.0f && headAngleDeg < 18.0f) {
                                // Feet too low and approaching bottom edge: tilt DOWN to place feet at ~-9 deg
                                deltaAngle = footAngleDeg + 9.0f; // negative
                            } else if (headAngleDeg > 14.0f && footAngleDeg < -14.0f) {
                                // Person is closer than full vertical FOV: center on torso
                                deltaAngle = (headAngleDeg + footAngleDeg) * 0.5f;
                            }
                            // If both head and feet are comfortably inside (-14 deg to +14 deg), deltaAngle is 0 (SILENT MOTOR)

                            if (std::abs(deltaAngle) >= 2.5f) {
                                long cur = g_kinectTiltAngle.load();
                                long desiredAngle = (long)std::round((float)cur + deltaAngle);
                                // Hardware safety limits: never point down into floor (-8 deg) or into ceiling (+14 deg)
                                if (desiredAngle < -8L) desiredAngle = -8L;
                                if (desiredAngle > 14L) desiredAngle = 14L;

                                long diff = desiredAngle - cur;
                                if (diff > 0) {
                                    tiltUpFrames++;
                                    tiltDownFrames = 0;
                                    if (tiltUpFrames >= 12 && g_targetTiltAngle.load() == -999) {
                                        g_targetTiltAngle.store(desiredAngle); // DIRECT JUMP in one smooth sweep!
                                        tiltUpFrames = 0;
                                    }
                                } else if (diff < 0) {
                                    tiltDownFrames++;
                                    tiltUpFrames = 0;
                                    if (tiltDownFrames >= 12 && g_targetTiltAngle.load() == -999) {
                                        g_targetTiltAngle.store(desiredAngle); // DIRECT JUMP in one smooth sweep!
                                        tiltDownFrames = 0;
                                    }
                                } else {
                                    tiltUpFrames = 0;
                                    tiltDownFrames = 0;
                                }
                            } else {
                                tiltUpFrames = 0;
                                tiltDownFrames = 0;
                            }
                        }

                        auto computeDirQuat = [](float dx, float dy, float dz) -> vr::HmdQuaternion_t {
                            float len = std::sqrt(dx * dx + dy * dy + dz * dz);
                            if (len < 0.05f) return { 1.0, 0.0, 0.0, 0.0 };
                            float vx = dx / len;
                            float vy = dy / len;
                            float vz = dz / len;

                            float dot = -vz;
                            if (dot > 0.9999f) return { 1.0, 0.0, 0.0, 0.0 };
                            if (dot < -0.9999f) return { 0.0, 0.0, 1.0, 0.0 };

                            float w = 1.0f + dot;
                            float qx = vy;
                            float qy = -vx;
                            float qlen = std::sqrt(w * w + qx * qx + qy * qy);
                            return { w / qlen, qx / qlen, qy / qlen, 0.0 };
                        };

                        float mx = g_kinectMirror ? -1.0f : 1.0f;

                        // Calculate forearm pointing vectors: Hand - Elbow
                        float ldx = (lhand.x - lelbow.x) * mx;
                        float ldy = lhand.y - lelbow.y;
                        float ldz = lhand.z - lelbow.z;
                        vr::HmdQuaternion_t lrot = computeDirQuat(ldx, ldy, ldz);

                        float rdx = (rhand.x - relbow.x) * mx;
                        float rdy = rhand.y - relbow.y;
                        float rdz = rhand.z - relbow.z;
                        vr::HmdQuaternion_t rrot = computeDirQuat(rdx, rdy, rdz);

                        if (g_hmd)       g_hmd->UpdatePosition(head.x * mx, head.y + yOffset, head.z - g_kinectCenterZ);
                        if (g_ctrlLeft)  g_ctrlLeft->UpdatePose(lhand.x * mx, lhand.y + yOffset, lhand.z - g_kinectCenterZ,
                                                                (float)lrot.w, (float)lrot.x, (float)lrot.y, (float)lrot.z);
                        if (g_ctrlRight) g_ctrlRight->UpdatePose(rhand.x * mx, rhand.y + yOffset, rhand.z - g_kinectCenterZ,
                                                                 (float)rrot.w, (float)rrot.x, (float)rrot.y, (float)rrot.z);

                        if (g_trackerWaist)     g_trackerWaist->UpdatePosition(hip.x * mx, hip.y + yOffset, hip.z - g_kinectCenterZ, true);
                        if (g_trackerChest)     g_trackerChest->UpdatePosition(chest.x * mx, chest.y + yOffset, chest.z - g_kinectCenterZ, true);
                        if (g_trackerLeftFoot)  g_trackerLeftFoot->UpdatePosition(lfoot.x * mx, lfoot.y + yOffset, lfoot.z - g_kinectCenterZ, !g_kinectSeated);
                        if (g_trackerRightFoot) g_trackerRightFoot->UpdatePosition(rfoot.x * mx, rfoot.y + yOffset, rfoot.z - g_kinectCenterZ, !g_kinectSeated);
                        if (g_trackerLeftKnee)  g_trackerLeftKnee->UpdatePosition(lknee.x * mx, lknee.y + yOffset, lknee.z - g_kinectCenterZ, !g_kinectSeated);
                        if (g_trackerRightKnee) g_trackerRightKnee->UpdatePosition(rknee.x * mx, rknee.y + yOffset, rknee.z - g_kinectCenterZ, !g_kinectSeated);
                        if (g_trackerLeftElbow)  g_trackerLeftElbow->UpdatePosition(lelbow.x * mx, lelbow.y + yOffset, lelbow.z - g_kinectCenterZ, true);
                        if (g_trackerRightElbow) g_trackerRightElbow->UpdatePosition(relbow.x * mx, relbow.y + yOffset, relbow.z - g_kinectCenterZ, true);

                        found = true;
                        break;
                    }
                }

                g_kinectTracking = found;
                if (!found) {
                    tiltUpFrames = 0;
                    tiltDownFrames = 0;
                    if (g_kinectAutoTilt.load() && !g_kinectMotorLocked.load()) {
                        lostFrames++;
                        // At ~30 FPS, 120 frames = ~4-5 seconds of sustained lost tracking
                        if (lostFrames >= 120) {
                            long cur = g_kinectTiltAngle.load();
                            // If tilted away from 0 deg, automatically level back to 0 deg horizon
                            if (cur != 0L && g_targetTiltAngle.load() == -999) {
                                g_targetTiltAngle.store(0L);
                            }
                            lostFrames = 0;
                        }
                    }
                    if (g_trackerWaist)      g_trackerWaist->UpdatePosition(0, 0, 0, false);
                    if (g_trackerChest)      g_trackerChest->UpdatePosition(0, 0, 0, false);
                    if (g_trackerLeftFoot)   g_trackerLeftFoot->UpdatePosition(0, 0, 0, false);
                    if (g_trackerRightFoot)  g_trackerRightFoot->UpdatePosition(0, 0, 0, false);
                    if (g_trackerLeftKnee)   g_trackerLeftKnee->UpdatePosition(0, 0, 0, false);
                    if (g_trackerRightKnee)  g_trackerRightKnee->UpdatePosition(0, 0, 0, false);
                    if (g_trackerLeftElbow)  g_trackerLeftElbow->UpdatePosition(0, 0, 0, false);
                    if (g_trackerRightElbow) g_trackerRightElbow->UpdatePosition(0, 0, 0, false);
                } else {
                    lostFrames = 0;
                }
            }
        }

        // ── Kinect Camera Stream Capture ──
        int camMode = g_kinectCamMode.load();
        bool camPipeActive = g_kinectCamPipeActive.load();
        if (camMode != 0 || camPipeActive) {
            int activeMode = (camMode != 0) ? camMode : 1; // Default to RGB Color for PC preview if phone is OFF
            if (activeMode == 1 || activeMode == 4) { // RGB Color or PIP
                if (hColorStream != INVALID_HANDLE_VALUE && WaitForSingleObject(hColorEvent, 20) == WAIT_OBJECT_0) {
                    NUI_IMAGE_FRAME imgFrame{};
                    if (SafeGetImageFrame(sensor, hColorStream, &imgFrame)) {
                        INuiFrameTexture* pTex = imgFrame.pFrameTexture;
                        if (pTex) {
                            NUI_LOCKED_RECT lockedRect{};
                            if (SUCCEEDED(pTex->LockRect(0, &lockedRect, nullptr, 0)) && lockedRect.pBits) {
                                std::lock_guard<std::mutex> camLk(g_kinectCamMutex);
                                const uint8_t* pSrc = (const uint8_t*)lockedRect.pBits;
                                uint8_t* pDst = g_kinectCamFrame.data();
                                for (int i = 0; i < 640 * 480; i++) {
                                    pDst[i * 4 + 0] = pSrc[i * 4 + 2]; // R
                                    pDst[i * 4 + 1] = pSrc[i * 4 + 1]; // G
                                    pDst[i * 4 + 2] = pSrc[i * 4 + 0]; // B
                                    pDst[i * 4 + 3] = 255;             // A
                                }
                                g_kinectCamCounter++;
                                pTex->UnlockRect(0);
                            }
                        }
                        SafeReleaseImageFrame(sensor, hColorStream, &imgFrame);
                        g_kinectCamCv.notify_all();
                    }
                }
            } else if (activeMode == 2) { // 3D Depth Map (Colorized)
                if (hDepthStream != INVALID_HANDLE_VALUE && WaitForSingleObject(hDepthEvent, 20) == WAIT_OBJECT_0) {
                    NUI_IMAGE_FRAME imgFrame{};
                    if (SafeGetImageFrame(sensor, hDepthStream, &imgFrame)) {
                        INuiFrameTexture* pTex = imgFrame.pFrameTexture;
                        if (pTex) {
                            NUI_LOCKED_RECT lockedRect{};
                            if (SUCCEEDED(pTex->LockRect(0, &lockedRect, nullptr, 0)) && lockedRect.pBits) {
                                std::lock_guard<std::mutex> camLk(g_kinectCamMutex);
                                const USHORT* pDepth = (const USHORT*)lockedRect.pBits;
                                uint8_t* pDst = g_kinectCamFrame.data();
                                for (int dy = 0; dy < 240; dy++) {
                                    for (int dx = 0; dx < 320; dx++) {
                                        USHORT raw = pDepth[dy * 320 + dx];
                                        int player = raw & 7;
                                        int depthMm = raw >> 3;
                                        uint8_t r = 0, g = 0, b = 0;
                                        if (player > 0) {
                                            r = 0; g = 255; b = 120; // Neon green/cyan for human player
                                        } else if (depthMm > 500 && depthMm < 4500) {
                                            float norm = (float)(depthMm - 500) / 4000.0f;
                                            if (norm < 0.25f) {
                                                r = 0; g = (uint8_t)(norm * 4.0f * 255.0f); b = 255;
                                            } else if (norm < 0.5f) {
                                                r = 0; g = 255; b = (uint8_t)((1.0f - (norm - 0.25f) * 4.0f) * 255.0f);
                                            } else if (norm < 0.75f) {
                                                r = (uint8_t)((norm - 0.5f) * 4.0f * 255.0f); g = 255; b = 0;
                                            } else {
                                                r = 255; g = (uint8_t)((1.0f - (norm - 0.75f) * 4.0f) * 255.0f); b = 0;
                                            }
                                        }
                                        int outY0 = dy * 2;
                                        int outX0 = dx * 2;
                                        for (int oy = 0; oy < 2; oy++) {
                                            uint8_t* row = pDst + ((outY0 + oy) * 640 + outX0) * 4;
                                            row[0] = r; row[1] = g; row[2] = b; row[3] = 255;
                                            row[4] = r; row[5] = g; row[6] = b; row[7] = 255;
                                        }
                                    }
                                }
                                g_kinectCamCounter++;
                                pTex->UnlockRect(0);
                            }
                        }
                        SafeReleaseImageFrame(sensor, hDepthStream, &imgFrame);
                        g_kinectCamCv.notify_all();
                    }
                }
            } else if (activeMode == 3) { // IR Night-vision
                if (hDepthStream != INVALID_HANDLE_VALUE && WaitForSingleObject(hDepthEvent, 20) == WAIT_OBJECT_0) {
                    NUI_IMAGE_FRAME imgFrame{};
                    if (SafeGetImageFrame(sensor, hDepthStream, &imgFrame)) {
                        INuiFrameTexture* pTex = imgFrame.pFrameTexture;
                        if (pTex) {
                            NUI_LOCKED_RECT lockedRect{};
                            if (SUCCEEDED(pTex->LockRect(0, &lockedRect, nullptr, 0)) && lockedRect.pBits) {
                                std::lock_guard<std::mutex> camLk(g_kinectCamMutex);
                                const USHORT* pDepth = (const USHORT*)lockedRect.pBits;
                                uint8_t* pDst = g_kinectCamFrame.data();
                                for (int dy = 0; dy < 240; dy++) {
                                    for (int dx = 0; dx < 320; dx++) {
                                        USHORT raw = pDepth[dy * 320 + dx];
                                        int depthMm = raw >> 3;
                                        uint8_t gray = 0;
                                        if (depthMm > 500 && depthMm < 4500) {
                                            float norm = 1.0f - ((float)(depthMm - 500) / 4000.0f);
                                            gray = (uint8_t)(norm * 255.0f);
                                        }
                                        int outY0 = dy * 2;
                                        int outX0 = dx * 2;
                                        for (int oy = 0; oy < 2; oy++) {
                                            uint8_t* row = pDst + ((outY0 + oy) * 640 + outX0) * 4;
                                            row[0] = gray; row[1] = gray; row[2] = gray; row[3] = 255;
                                            row[4] = gray; row[5] = gray; row[6] = gray; row[7] = 255;
                                        }
                                    }
                                }
                                g_kinectCamCounter++;
                                pTex->UnlockRect(0);
                            }
                        }
                        SafeReleaseImageFrame(sensor, hDepthStream, &imgFrame);
                        g_kinectCamCv.notify_all();
                    }
                }
            }
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_kinectSensorMutex);
        g_kinectSensor = nullptr;
    }
    sensor->NuiShutdown();
    sensor->Release();
    if (g_hKinectSkelEvent) {
        CloseHandle(g_hKinectSkelEvent);
        g_hKinectSkelEvent = nullptr;
    }
    if (hColorEvent) {
        CloseHandle(hColorEvent);
    }
    if (hDepthEvent) {
        CloseHandle(hDepthEvent);
    }
}

// ════════════════════════════════════════════════════════════════════
//  Driver UDP Command Receiver Thread (Port 9555)
// ════════════════════════════════════════════════════════════════════
static void DriverCommandThread() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) return;

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(DRIVER_CMD_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (sockaddr*)&addr, sizeof(addr)) == SOCKET_ERROR) {
        closesocket(sock);
        return;
    }

    char buf[512];
    while (g_running) {
        sockaddr_in clientAddr{};
        int clientLen = sizeof(clientAddr);
        int r = recvfrom(sock, buf, sizeof(buf) - 1, 0, (sockaddr*)&clientAddr, &clientLen);
        if (r > 0) {
            buf[r] = '\0';
            std::string cmd(buf);
            if (cmd.find("RECENTER_KINECT") != std::string::npos) {
                g_kinectCenterZ = g_lastHeadZ;
            } else if (cmd.find("KINECT_TILT_UP") != std::string::npos) {
                if (!g_kinectMotorLocked.load()) {
                    long cur = (g_targetTiltAngle.load() != -999) ? g_targetTiltAngle.load() : g_kinectTiltAngle.load();
                    long newAngle = cur + 3L;
                    if (newAngle > 14L) newAngle = 14L;
                    g_targetTiltAngle.store(newAngle);
                }
            } else if (cmd.find("KINECT_TILT_DOWN") != std::string::npos) {
                if (!g_kinectMotorLocked.load()) {
                    long cur = (g_targetTiltAngle.load() != -999) ? g_targetTiltAngle.load() : g_kinectTiltAngle.load();
                    long newAngle = cur - 3L;
                    if (newAngle < -8L) newAngle = -8L;
                    g_targetTiltAngle.store(newAngle);
                }
            } else if (cmd.find("KINECT_TILT_ZERO") != std::string::npos) {
                if (!g_kinectMotorLocked.load()) {
                    g_targetTiltAngle.store(0);
                }
            } else if (cmd.rfind("SET_KINECT_TILT ", 0) == 0) {
                if (!g_kinectMotorLocked.load()) {
                    try {
                        long deg = std::stol(cmd.substr(16));
                        if (deg > 14L) deg = 14L;
                        if (deg < -8L) deg = -8L;
                        g_targetTiltAngle.store(deg);
                    } catch (...) {}
                }
            } else if (cmd.find("TOGGLE_KINECT_MOTOR_LOCK") != std::string::npos) {
                g_kinectMotorLocked = !g_kinectMotorLocked;
            } else if (cmd.rfind("SET_KINECT_MOTOR_LOCK ", 0) == 0) {
                try {
                    g_kinectMotorLocked = (std::stoi(cmd.substr(22)) != 0);
                } catch (...) {}
            } else if (cmd.rfind("SET_KINECT_SEATED ", 0) == 0) {
                try {
                    bool seated = (std::stoi(cmd.substr(18)) != 0);
                    g_kinectSeated = seated;
                    g_pendingSeatedChange.store(true);
                } catch (...) {}
            } else if (cmd.find("TOGGLE_KINECT_SEATED") != std::string::npos) {
                g_kinectSeated = !g_kinectSeated;
                g_pendingSeatedChange.store(true);
            } else if (cmd.rfind("SET_KINECT_HEIGHT ", 0) == 0) {
                try {
                    float h = std::stof(cmd.substr(18));
                    g_kinectHeightOffset = h;
                } catch (...) {}
            } else if (cmd.find("KINECT_HEIGHT_UP") != std::string::npos) {
                g_kinectHeightOffset = g_kinectHeightOffset.load() + 0.05f;
            } else if (cmd.find("KINECT_HEIGHT_DOWN") != std::string::npos) {
                g_kinectHeightOffset = g_kinectHeightOffset.load() - 0.05f;
            } else if (cmd.find("KINECT_HEIGHT_ZERO") != std::string::npos) {
                g_kinectHeightOffset = 0.0f;
            } else if (cmd.rfind("SET_IPD ", 0) == 0) {
                try {
                    float ipd = std::stof(cmd.substr(8));
                    if (g_hmd && ipd >= 0.050f && ipd <= 0.080f) {
                        g_userIpdMeters = ipd;
                        g_hmd->SetIPD(ipd);
                    }
                } catch (...) {}
            } else if (cmd.rfind("SET_IPD_SHIFT ", 0) == 0) {
                try {
                    int shift = std::stoi(cmd.substr(14));
                    g_viewportIpdShift = shift;
                } catch (...) {}
            } else if (cmd.find("TOGGLE_KINECT_AUTO_TILT") != std::string::npos) {
                g_kinectAutoTilt = !g_kinectAutoTilt;
            } else if (cmd.rfind("SET_KINECT_AUTO_TILT ", 0) == 0) {
                try {
                    g_kinectAutoTilt = (std::stoi(cmd.substr(21)) != 0);
                } catch (...) {}
            } else if (cmd.find("TOGGLE_KINECT_MIRROR") != std::string::npos) {
                g_kinectMirror = !g_kinectMirror;
            } else if (cmd.rfind("SET_KINECT_MIRROR ", 0) == 0) {
                try {
                    g_kinectMirror = (std::stoi(cmd.substr(18)) != 0);
                } catch (...) {}
            } else if (cmd.rfind("SET_CAM_MODE ", 0) == 0) {
                try {
                    int mode = std::stoi(cmd.substr(13));
                    if (mode < 0) mode = 0;
                    if (mode > 4) mode = 4;
                    g_kinectCamMode = mode;
                } catch (...) {}
            } else if (cmd.find("TOGGLE_CAM_MODE") != std::string::npos) {
                int next = (g_kinectCamMode.load() + 1) % 5;
                g_kinectCamMode = next;
            }
            std::string reply = "OK";
            if (cmd.find("GET_STAT") != std::string::npos || cmd.find("GET_TELEMETRY") != std::string::npos || cmd.find("PING") != std::string::npos) {
                std::string currentPhoneIp;
                {
                    std::lock_guard<std::mutex> lock(g_phoneIpMutex);
                    currentPhoneIp = g_phoneSenderIp;
                }
                uint64_t nowMs = GetTickCount64();
                uint64_t lastImuMs = g_lastImuPacketTime.load();
                int imuAlive = (lastImuMs > 0 && (nowMs - lastImuMs) < 2000) ? 1 : 0;
                int imuAgeMs = (lastImuMs > 0) ? (int)(nowMs - lastImuMs) : 99999;

                char statBuf[512];
                snprintf(statBuf, sizeof(statBuf),
                         "STAT:YAW=%.1f;PITCH=%.1f;ROLL=%.1f;IPD=%.1f;SHIFT=%d;KINECT=%d;KMIRROR=%d;TILT=%ld;SEATED=%d;HEIGHT=%.2f;AUTOTILT=%d;CAM=%d;KX=%.2f;KY=%.2f;KZ=%.2f;KPITCH=%.1f;KROLL=%.1f;MLOCK=%d;PHONE_IP=%s;IMU_ALIVE=%d;IMU_AGE=%d",
                         g_telemYaw.load(), g_telemPitch.load(), g_telemRoll.load(),
                         g_userIpdMeters * 1000.0f, g_viewportIpdShift,
                         g_kinectTracking ? 1 : 0, g_kinectMirror ? 1 : 0,
                         g_kinectTiltAngle.load(), g_kinectSeated ? 1 : 0, g_kinectHeightOffset.load(),
                         g_kinectAutoTilt.load() ? 1 : 0,
                         g_kinectCamMode.load(),
                         g_kinectAccX.load(), g_kinectAccY.load(), g_kinectAccZ.load(),
                         g_kinectPitch.load(), g_kinectRoll.load(),
                         g_kinectMotorLocked.load() ? 1 : 0,
                         currentPhoneIp.c_str(), imuAlive, imuAgeMs);
                reply = statBuf;
            }
            sendto(sock, reply.c_str(), (int)reply.size(), 0, (sockaddr*)&clientAddr, clientLen);
        }
    }
    closesocket(sock);
}

// ════════════════════════════════════════════════════════════════════
//  Gamepad Polling + Keep-Alive Thread
// ════════════════════════════════════════════════════════════════════
static void GamepadThread() {
    DWORD keepAliveCounter = 0;
    while (g_running) {
        XINPUT_STATE state{};
        if (XInputGetState(0, &state) == ERROR_SUCCESS) {
            if (g_ctrlLeft  && g_ctrlLeft->IsActivated())  g_ctrlLeft->UpdateFromXInput(state, true);
            if (g_ctrlRight && g_ctrlRight->IsActivated()) g_ctrlRight->UpdateFromXInput(state, false);

            if (++keepAliveCounter >= 500) { // every 5 seconds (500 * 10ms)
                XINPUT_VIBRATION vib{};
                XInputSetState(0, &vib);
                keepAliveCounter = 0;
            }
        }
        Sleep(10);
    }
}

// ════════════════════════════════════════════════════════════════════
//  Server Driver Provider
// ════════════════════════════════════════════════════════════════════
class CVRFusionServerDriver : public IServerTrackedDeviceProvider {
public:
    EVRInitError Init(IVRDriverContext* ctx) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
        timeBeginPeriod(1);

        // 1. Register HMD
        g_hmd = new CVRFusionHMD();
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_hmd", TrackedDeviceClass_HMD, g_hmd);

        // 2. Register Controllers
        g_ctrlLeft  = new CVRFusionController(TrackedControllerRole_LeftHand);
        g_ctrlRight = new CVRFusionController(TrackedControllerRole_RightHand);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_ctrl_left",  TrackedDeviceClass_Controller, g_ctrlLeft);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_ctrl_right", TrackedDeviceClass_Controller, g_ctrlRight);

        // 3. Register Full Body Trackers (Waist, Chest, Feet, Knees, Elbows)
        g_trackerWaist      = new CVRFusionTracker("vrfusion_tracker_waist", "TrackerRole_Waist");
        g_trackerChest      = new CVRFusionTracker("vrfusion_tracker_chest", "TrackerRole_Chest");
        g_trackerLeftFoot   = new CVRFusionTracker("vrfusion_tracker_left_foot", "TrackerRole_LeftFoot");
        g_trackerRightFoot  = new CVRFusionTracker("vrfusion_tracker_right_foot", "TrackerRole_RightFoot");
        g_trackerLeftKnee   = new CVRFusionTracker("vrfusion_tracker_left_knee", "TrackerRole_LeftKnee");
        g_trackerRightKnee  = new CVRFusionTracker("vrfusion_tracker_right_knee", "TrackerRole_RightKnee");
        g_trackerLeftElbow  = new CVRFusionTracker("vrfusion_tracker_left_elbow", "TrackerRole_LeftElbow");
        g_trackerRightElbow = new CVRFusionTracker("vrfusion_tracker_right_elbow", "TrackerRole_RightElbow");

        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_waist",       TrackedDeviceClass_GenericTracker, g_trackerWaist);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_chest",       TrackedDeviceClass_GenericTracker, g_trackerChest);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_left_foot",   TrackedDeviceClass_GenericTracker, g_trackerLeftFoot);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_right_foot",  TrackedDeviceClass_GenericTracker, g_trackerRightFoot);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_left_knee",   TrackedDeviceClass_GenericTracker, g_trackerLeftKnee);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_right_knee",  TrackedDeviceClass_GenericTracker, g_trackerRightKnee);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_left_elbow",  TrackedDeviceClass_GenericTracker, g_trackerLeftElbow);
        VRServerDriverHost()->TrackedDeviceAdded("vrfusion_tracker_right_elbow", TrackedDeviceClass_GenericTracker, g_trackerRightElbow);

        // Start threads
        g_running = true;
        m_udpThread     = std::thread(UdpReceiverThread);
        m_kinectThread  = std::thread(KinectThread);
        m_gamepadThread = std::thread(GamepadThread);
        m_cmdThread     = std::thread(DriverCommandThread);

        return VRInitError_None;
    }

    void Cleanup() override {
        g_running = false;
        if (m_udpThread.joinable())     m_udpThread.join();
        if (m_kinectThread.joinable())  m_kinectThread.join();
        if (m_gamepadThread.joinable()) m_gamepadThread.join();
        if (m_cmdThread.joinable())     m_cmdThread.join();
        timeEndPeriod(1);
    }

    const char* const* GetInterfaceVersions() override { return k_InterfaceVersions; }

    void RunFrame() override {
        if (g_hmd && g_hmd->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_hmd->GetDeviceId(), g_hmd->GetPose(), sizeof(DriverPose_t));

        if (g_ctrlLeft && g_ctrlLeft->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_ctrlLeft->GetDeviceId(), g_ctrlLeft->GetPose(), sizeof(DriverPose_t));

        if (g_ctrlRight && g_ctrlRight->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_ctrlRight->GetDeviceId(), g_ctrlRight->GetPose(), sizeof(DriverPose_t));

        if (g_trackerWaist && g_trackerWaist->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerWaist->GetDeviceId(), g_trackerWaist->GetPose(), sizeof(DriverPose_t));

        if (g_trackerChest && g_trackerChest->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerChest->GetDeviceId(), g_trackerChest->GetPose(), sizeof(DriverPose_t));

        if (g_trackerLeftFoot && g_trackerLeftFoot->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerLeftFoot->GetDeviceId(), g_trackerLeftFoot->GetPose(), sizeof(DriverPose_t));

        if (g_trackerRightFoot && g_trackerRightFoot->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerRightFoot->GetDeviceId(), g_trackerRightFoot->GetPose(), sizeof(DriverPose_t));

        if (g_trackerLeftKnee && g_trackerLeftKnee->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerLeftKnee->GetDeviceId(), g_trackerLeftKnee->GetPose(), sizeof(DriverPose_t));

        if (g_trackerRightKnee && g_trackerRightKnee->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerRightKnee->GetDeviceId(), g_trackerRightKnee->GetPose(), sizeof(DriverPose_t));

        if (g_trackerLeftElbow && g_trackerLeftElbow->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerLeftElbow->GetDeviceId(), g_trackerLeftElbow->GetPose(), sizeof(DriverPose_t));

        if (g_trackerRightElbow && g_trackerRightElbow->IsActivated())
            VRServerDriverHost()->TrackedDevicePoseUpdated(g_trackerRightElbow->GetDeviceId(), g_trackerRightElbow->GetPose(), sizeof(DriverPose_t));
    }

    bool ShouldBlockStandbyMode() override {
        return (g_hmd != nullptr && g_hmd->IsUserPresent());
    }
    void EnterStandby() override {
        if (g_hmd) g_hmd->EnterStandby();
    }
    void LeaveStandby() override {
        if (g_hmd) g_hmd->LeaveStandby();
    }

private:
    std::thread m_udpThread;
    std::thread m_kinectThread;
    std::thread m_gamepadThread;
    std::thread m_cmdThread;
};

// ════════════════════════════════════════════════════════════════════
//  DLL exports
// ════════════════════════════════════════════════════════════════════
static CVRFusionServerDriver g_driverProvider;

extern "C" __declspec(dllexport)
void* HmdDriverFactory(const char* pInterfaceName, int* pReturnCode) {
    if (pInterfaceName && !strcmp(pInterfaceName, IServerTrackedDeviceProvider_Version)) {
        if (pReturnCode) *pReturnCode = VRInitError_None;
        return static_cast<IServerTrackedDeviceProvider*>(&g_driverProvider);
    }
    if (pReturnCode) *pReturnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
