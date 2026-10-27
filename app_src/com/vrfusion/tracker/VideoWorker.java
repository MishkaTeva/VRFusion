package com.vrfusion.tracker;

import android.media.MediaCodec;
import android.os.Build;
import android.util.Log;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

public class VideoWorker implements Runnable {
    private static final String TAG = "VideoWorker";
    private final MainActivity activity;
    private final int port;
    private ServerSocket serverSocket;
    private Thread outputThread;
    private volatile boolean isRunning = true;
    private volatile Socket currentClient;
    private long totalRenderedFrames = 0;

    public VideoWorker(MainActivity activity, int port) {
        this.activity = activity;
        this.port = port;
    }

    public void close() {
        isRunning = false;
        try {
            if (currentClient != null && !currentClient.isClosed()) {
                currentClient.close();
            }
        } catch (Exception ignored) {}
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        byte[] readBuf = new byte[65536];
        byte[] streamBuf = new byte[2 * 1024 * 1024]; // 2MB stream buffer
        int streamLen = 0;

        // Dedicated background thread to continuously pull and render decoded video frames
        startOutputDrainThread();

        while (activity.isDecodingActive() && isRunning) {
            try {
                if (serverSocket == null || serverSocket.isClosed()) {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(port));
                    serverSocket.setSoTimeout(1000);
                    Log.i(TAG, "TCP Video Server listening on port " + port + " (ready for USB/Wi-Fi stream)");
                }

                Socket client = null;
                try {
                    client = serverSocket.accept();
                    currentClient = client;
                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(2 * 1024 * 1024);
                    InputStream in = client.getInputStream();
                    Log.i(TAG, "Video Stream Connected from " + client.getRemoteSocketAddress());
                    streamLen = 0;

                    while (activity.isDecodingActive() && isRunning) {
                        int read = in.read(readBuf);
                        if (read <= 0) break;

                        activity.markVideoPacketReceived();

                        if (streamLen + read > streamBuf.length) {
                            byte[] newBuf = new byte[Math.max(streamBuf.length * 2, streamLen + read + 65536)];
                            System.arraycopy(streamBuf, 0, newBuf, 0, streamLen);
                            streamBuf = newBuf;
                        }
                        System.arraycopy(readBuf, 0, streamBuf, streamLen, read);
                        streamLen += read;

                        int nalStart = findNalStart(streamBuf, 0, streamLen);
                        while (nalStart >= 0) {
                            if (nalStart > 0) {
                                int remaining = streamLen - nalStart;
                                System.arraycopy(streamBuf, nalStart, streamBuf, 0, remaining);
                                streamLen = remaining;
                                nalStart = 0;
                            }

                            int nextNal = findNalStart(streamBuf, 3, streamLen);
                            if (nextNal < 0) {
                                if (streamLen > 2 * 1024 * 1024) {
                                    streamLen = 0;
                                }
                                break;
                            }

                            int nalLen = nextNal;
                            feedToCodec(streamBuf, 0, nalLen);

                            int remaining = streamLen - nextNal;
                            System.arraycopy(streamBuf, nextNal, streamBuf, 0, remaining);
                            streamLen = remaining;
                            nalStart = findNalStart(streamBuf, 0, streamLen);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (activity.isDecodingActive() && isRunning) {
                        Log.e(TAG, "Client connection error: " + e.getMessage());
                    }
                } finally {
                    if (client != null) {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                    currentClient = null;
                }
            } catch (Exception e) {
                if (activity.isDecodingActive() && isRunning) {
                    Log.w(TAG, "Server socket error on port " + port + ": " + e.getMessage() + ", retrying in 500ms...");
                }
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                    serverSocket = null;
                }
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
        }
        close();
    }

    private void startOutputDrainThread() {
        outputThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (activity.isDecodingActive() && isRunning) {
                MediaCodec codec = activity.getMediaCodec();
                if (codec == null) {
                    try { Thread.sleep(10); } catch (Exception ignored) {}
                    continue;
                }

                try {
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000); // 10ms wait
                    if (outIndex >= 0) {
                        // Render directly to surface!
                        codec.releaseOutputBuffer(outIndex, true);
                        totalRenderedFrames++;
                        if (totalRenderedFrames == 1 || totalRenderedFrames % 120 == 0) {
                            Log.i(TAG, "RENDERED frame #" + totalRenderedFrames + " directly to SurfaceView!");
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "MediaCodec Decoder Output Format: " + codec.getOutputFormat());
                    }
                } catch (IllegalStateException ignored) {
                    break;
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "Output drain error: " + e.getMessage());
                    }
                }
            }
        });
        outputThread.setName("VRFusion-OutputDrainer");
        outputThread.setPriority(Thread.MAX_PRIORITY);
        outputThread.start();
    }

    private static int findNalStart(byte[] data, int start, int length) {
        for (int i = start; i <= length - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void feedToCodec(byte[] data, int offset, int length) {
        MediaCodec codec = activity.getMediaCodec();
        if (codec == null || length <= 4) return;

        try {
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer inputBuffer = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        ? codec.getInputBuffer(inIndex)
                        : codec.getInputBuffers()[inIndex];
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, offset, length);

                    int nalType = -1;
                    if (data[offset] == 0 && data[offset + 1] == 0) {
                        int startLen = (data[offset + 2] == 1) ? 3 : 4;
                        if (offset + startLen < offset + length) {
                            nalType = data[offset + startLen] & 0x1F;
                        }
                    }

                    int flags = 0;
                    if (nalType == 7 || nalType == 8) {
                        // Crucial for Qualcomm Snapdragon: marks SPS/PPS parameter set
                        flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                    } else if (nalType == 5) {
                        flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }

                    codec.queueInputBuffer(inIndex, 0, length, System.nanoTime() / 1000L, flags);
                }
            }
        } catch (IllegalStateException ignored) {
        } catch (Exception e) {
            Log.e(TAG, "Codec feed error: " + e.getMessage());
        }
    }
}
