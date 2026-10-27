package com.vrfusion.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VRFusionService extends Service implements SensorEventListener {
    private static final String TAG = "VRFusionService";
    private static final String CHANNEL_ID = "vrfusion_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.vrfusion.tracker.START";
    public static final String ACTION_STOP = "com.vrfusion.tracker.STOP";
    public static final String ACTION_RECENTER = "com.vrfusion.tracker.RECENTER";
    public static final String ACTION_TOGGLE_MIC = "com.vrfusion.tracker.TOGGLE_MIC";
    public static final String EXTRA_IP = "extra_ip";
    public static final int CMD_PORT = 9552;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private SensorManager sensorManager;
    private HandlerThread sensorThread;
    private Handler sensorHandler;

    private Sensor rotVectorSensor;
    private DatagramSocket imuSocket;
    private DatagramSocket audioSocket;
    private DatagramSocket cmdSocket;
    private Thread cmdThread;
    private ServerSocket tcpCmdServer;
    private Thread tcpCmdThread;
    private InetAddress pcAddress;
    private int imuPort = 9550;
    private int audioPort = 6002;
    private String targetIp = "192.168.31.2";

    private volatile boolean isRunning = false;
    private volatile boolean isMicStreaming = false;
    private volatile boolean isScreenOff = false;

    private AudioRecord audioRecord;
    private Thread audioThread;

    private final float[] curQuat = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
    private float yawOffset = 0.0f;
    private volatile boolean hasInitialYaw = false;
    private volatile boolean needRecenter = true;
    private final byte[] packetBuffer = new byte[64];
    private long packetCounter = 0;
    private long lastSendNanos = 0;

    // Public state for UI
    public static volatile boolean isServiceActive = false;
    public static volatile boolean isLandscapeFlipped = false;
    public static volatile long activePackets = 0;
    public static volatile boolean activeMic = false;
    public static volatile float yawDeg = 0;
    public static volatile float pitchDeg = 0;
    public static volatile float rollDeg = 0;
    public static volatile boolean powerSaveMode = false;
    public static volatile int batteryPercent = 100;
    public static volatile boolean invertRoll = false;
    public static volatile boolean flipYaw180 = false; // Default false: looking straight ahead at 0 deg Yaw
    public static volatile VRFusionService activeService = null;
    private static volatile boolean staticNeedRecenter = true;

    public static void requestRecenter() {
        staticNeedRecenter = true;
    }

    public static void sendUdpToDriver(final String driverCmd) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = driverCmd.getBytes("UTF-8");
                    String ip = (activeService != null && activeService.targetIp != null) ?
                                 activeService.targetIp : "192.168.31.2";
                    InetAddress addr = InetAddress.getByName(ip);
                    DatagramPacket p = new DatagramPacket(data, data.length, addr, 9555);
                    DatagramSocket s = new DatagramSocket();
                    s.send(p);
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private final ScreenStateReceiver screenStateReceiver = new ScreenStateReceiver(this);
    private final CommandReceiver commandReceiver = new CommandReceiver(this);

    static class ScreenStateReceiver extends BroadcastReceiver {
        private final VRFusionService service;
        ScreenStateReceiver(VRFusionService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                service.isScreenOff = true;
                service.powerSaveMode = true;
                service.applyPowerSavingPolicy(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                service.isScreenOff = false;
                service.powerSaveMode = false;
                service.applyPowerSavingPolicy(false);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    VRFusionService.batteryPercent = (level * 100) / scale;
                }
            }
        }
    }

    static class CommandReceiver extends BroadcastReceiver {
        private final VRFusionService service;
        CommandReceiver(VRFusionService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String cmd = intent.getStringExtra("cmd");
            if (cmd != null && !cmd.isEmpty()) {
                service.handleRemoteCommand(cmd);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        createNotificationChannel();
        try {
            SharedPreferences prefs = getSharedPreferences("vr_settings", Context.MODE_PRIVATE);
            invertRoll = prefs.getBoolean("invert_roll", false);
            flipYaw180 = prefs.getBoolean("flip_yaw_180", false);
        } catch (Throwable ignored) {}

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VRFusion::ServiceWakeLock");
        wakeLock.setReferenceCounted(false);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "VRFusion::WifiLock");
            } else {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VRFusion::WifiLock");
            }
            wifiLock.setReferenceCounted(false);
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotVectorSensor == null) {
            rotVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        sensorThread = new HandlerThread("VRFusionSensorThread");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(screenStateReceiver, filter);

        IntentFilter cmdFilter = new IntentFilter("com.vrfusion.COMMAND");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, cmdFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(commandReceiver, cmdFilter);
        }
    }

    private void applyPowerSavingPolicy(boolean screenOff) {
        if (!isRunning) return;
        sensorManager.unregisterListener(this);
        int delay = screenOff ? SensorManager.SENSOR_DELAY_GAME : SensorManager.SENSOR_DELAY_FASTEST;
        sensorManager.registerListener(this, rotVectorSensor, delay, sensorHandler);
        Log.i(TAG, "Power policy updated: screenOff=" + screenOff + ", delay=" + delay);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VRFusion Tracking Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background high-rate wireless IMU streaming for VR");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("VRFusion VR-трекинг")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_RECENTER.equals(action)) {
            recenterOrientation();
            return START_STICKY;
        } else if (ACTION_TOGGLE_MIC.equals(action)) {
            toggleMic();
            return START_STICKY;
        }

        String ip = intent.getStringExtra(EXTRA_IP);
        if (ip != null && !ip.isEmpty()) targetIp = ip;

        startForeground(NOTIF_ID, buildNotification("Активен: передача на " + targetIp + ":" + imuPort));
        startTracking();
        return START_STICKY;
    }

    private synchronized void startTracking() {
        if (isRunning) return;

        try {
            pcAddress = InetAddress.getByName(targetIp);
            imuSocket = new DatagramSocket();
            imuSocket.setSendBufferSize(64 * 1024);

            try {
                cmdSocket = new DatagramSocket(CMD_PORT);
                cmdSocket.setReceiveBufferSize(32 * 1024);
                cmdThread = new Thread(new CommandWorker(this));
                cmdThread.setDaemon(true);
                cmdThread.start();
                Log.i(TAG, "Command UDP listener started on port " + CMD_PORT);
            } catch (Exception ce) {
                Log.w(TAG, "Command UDP port " + CMD_PORT + " bind warning: " + ce.getMessage());
            }

            try {
                tcpCmdServer = new ServerSocket(CMD_PORT);
                tcpCmdThread = new Thread(new TcpCommandWorker(this));
                tcpCmdThread.setDaemon(true);
                tcpCmdThread.start();
                Log.i(TAG, "Command TCP listener started on port " + CMD_PORT);
            } catch (Exception te) {
                Log.w(TAG, "Command TCP port " + CMD_PORT + " bind warning: " + te.getMessage());
            }

            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();

            int delay = isScreenOff ? SensorManager.SENSOR_DELAY_GAME : SensorManager.SENSOR_DELAY_FASTEST;
            sensorManager.registerListener(this, rotVectorSensor, delay, sensorHandler);

            isRunning = true;
            isServiceActive = true;
            Log.i(TAG, "Tracking started wirelessly to " + targetIp);
        } catch (Exception e) {
            Log.e(TAG, "Error starting tracking", e);
        }
    }

    private synchronized void stopTracking() {
        if (!isRunning) return;

        isRunning = false;
        isServiceActive = false;
        sensorManager.unregisterListener(this);

        if (cmdSocket != null && !cmdSocket.isClosed()) {
            try { cmdSocket.close(); } catch (Exception ignored) {}
            cmdSocket = null;
        }
        if (cmdThread != null) {
            cmdThread.interrupt();
            cmdThread = null;
        }
        if (tcpCmdServer != null && !tcpCmdServer.isClosed()) {
            try { tcpCmdServer.close(); } catch (Exception ignored) {}
            tcpCmdServer = null;
        }
        if (tcpCmdThread != null) {
            tcpCmdThread.interrupt();
            tcpCmdThread = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();

        if (imuSocket != null && !imuSocket.isClosed()) {
            imuSocket.close();
            imuSocket = null;
        }

        if (isMicStreaming) toggleMic();
        stopForeground(true);
        Log.i(TAG, "Tracking stopped");
    }

    private void recenterOrientation() {
        needRecenter = true;
        Log.i(TAG, "Recenter requested");
    }

    public void handleRemoteCommand(String cmd) {
        if (cmd == null) return;
        cmd = cmd.trim();
        Log.i(TAG, "Remote Command: " + cmd);
        if ("RECENTER".equalsIgnoreCase(cmd)) {
            requestRecenter();
        } else if ("FLIP_180".equalsIgnoreCase(cmd)) {
            flipYaw180 = !flipYaw180;
            requestRecenter();
            saveSettings();
        } else if ("TOGGLE_ROLL".equalsIgnoreCase(cmd)) {
            invertRoll = !invertRoll;
            saveSettings();
        } else if (cmd.startsWith("SET_ROLL")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                invertRoll = "1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1]);
                saveSettings();
            }
        } else if (cmd.startsWith("SCALE")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    float s = Float.parseFloat(parts[1]);
                    MainActivity.setRemoteEyeScale(s);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("OFFSET_Y")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    int off = Integer.parseInt(parts[1]);
                    MainActivity.setRemoteOffsetY(off);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("UI_SCALE")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    float u = Float.parseFloat(parts[1]);
                    MainActivity.setRemoteUiScale(u);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("DIVIDER")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    int d = Integer.parseInt(parts[1]);
                    MainActivity.setRemoteDivider(d);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("IPD ")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    float valMm = Float.parseFloat(parts[1]);
                    int shift = Math.round((valMm - 63.0f) * 12.0f);
                    MainActivity.setRemoteIpdShift(shift);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("IPD_SHIFT ")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    int shift = Integer.parseInt(parts[1]);
                    MainActivity.setRemoteIpdShift(shift);
                } catch (Exception ignored) {}
            }
        } else if (cmd.startsWith("SET_CAM_MODE ")) {
            String[] parts = cmd.split(" ");
            if (parts.length > 1) {
                try {
                    int m = Integer.parseInt(parts[1]);
                    MainActivity.setRemoteCamMode(m);
                    sendUdpToDriver(cmd);
                } catch (Exception ignored) {}
            }
        } else if ("TOGGLE_CAM_MODE".equalsIgnoreCase(cmd)) {
            MainActivity.toggleRemoteCamMode();
            sendUdpToDriver("TOGGLE_CAM_MODE");
        } else if ("TOGGLE_MIC".equalsIgnoreCase(cmd)) {
            toggleMic();
        } else if ("TOGGLE_HUD".equalsIgnoreCase(cmd)) {
            MainActivity.toggleRemoteHud();
        } else if ("RESET".equalsIgnoreCase(cmd)) {
            MainActivity.resetToDefaults();
        }
    }

    private void saveSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("vr_settings", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("invert_roll", invertRoll)
                        .putBoolean("flip_yaw_180", flipYaw180)
                        .apply();
        } catch (Throwable ignored) {}
    }

    private synchronized void toggleMic() {
        if (!isMicStreaming) {
            try {
                int sampleRate = 48000;
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);

                audioSocket = new DatagramSocket();
                audioRecord.startRecording();
                isMicStreaming = true;
                activeMic = true;

                audioThread = new Thread(new AudioStreamer(this));
                audioThread.setPriority(Thread.MAX_PRIORITY);
                audioThread.start();
            } catch (Exception e) {
                Log.e(TAG, "Microphone start error", e);
            }
        } else {
            isMicStreaming = false;
            activeMic = false;
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                audioRecord.release();
                audioRecord = null;
            }
            if (audioSocket != null && !audioSocket.isClosed()) {
                audioSocket.close();
                audioSocket = null;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRunning) return;

        if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {

            long now = System.nanoTime();
            if (isScreenOff && (now - lastSendNanos) < 16_000_000L) { // ~16ms = ~60Hz
                return;
            }
            lastSendNanos = now;

            float[] inR = new float[9];
            SensorManager.getRotationMatrixFromVector(inR, event.values);

            // Transform Android sensor frame into OpenVR world and headset frame:
            // OpenVR: +X is Right, +Y is Up (opposite gravity), -Z is Forward (line of sight).
            float r00, r01, r02;
            float r10, r11, r12;
            float r20, r21, r22;

            if (isLandscapeFlipped) {
                // Flipped Landscape (USB on left, front camera on right)
                r00 =  inR[1]; r01 = -inR[0]; r02 =  inR[2];
                r10 =  inR[7]; r11 = -inR[6]; r12 =  inR[8];
                r20 = -inR[4]; r21 =  inR[3]; r22 = -inR[5];
            } else {
                // Standard Landscape (USB on right, front camera on left)
                r00 = -inR[1]; r01 =  inR[0]; r02 =  inR[2];
                r10 = -inR[7]; r11 =  inR[6]; r12 =  inR[8];
                r20 =  inR[4]; r21 = -inR[3]; r22 = -inR[5];
            }

            // Raw yaw angle in horizontal plane
            float rawYaw = (float) Math.atan2(r02, r22);

            if (needRecenter || staticNeedRecenter || !hasInitialYaw) {
                float targetYaw = rawYaw;
                if (flipYaw180) {
                    targetYaw += (float) Math.PI;
                }
                yawOffset = targetYaw;
                hasInitialYaw = true;
                needRecenter = false;
                staticNeedRecenter = false;
                Log.i(TAG, "Recenter calibrated: yawOffset=" + Math.toDegrees(yawOffset) + ", flip180=" + flipYaw180);
            }

            // Yaw-only recenter around vertical Y axis (gravity horizon is never altered!):
            float cy = (float) Math.cos(yawOffset);
            float sy = (float) Math.sin(yawOffset);

            float c00 = cy * r00 - sy * r20;
            float c01 = cy * r01 - sy * r21;
            float c02 = cy * r02 - sy * r22;

            float c10 = r10;
            float c11 = r11;
            float c12 = r12;

            float c20 = sy * r00 + cy * r20;
            float c21 = sy * r01 + cy * r21;
            float c22 = sy * r02 + cy * r22;

            // Numerically stable matrix-to-quaternion (Shepperd algorithm)
            float trace = c00 + c11 + c22;
            float qw, qx, qy, qz;
            if (trace > 0.0f) {
                float s = (float) Math.sqrt(trace + 1.0f) * 2.0f;
                qw = 0.25f * s;
                qx = (c21 - c12) / s;
                qy = (c02 - c20) / s;
                qz = (c10 - c01) / s;
            } else if ((c00 > c11) && (c00 > c22)) {
                float s = (float) Math.sqrt(1.0f + c00 - c11 - c22) * 2.0f;
                qw = (c21 - c12) / s;
                qx = 0.25f * s;
                qy = (c01 + c10) / s;
                qz = (c02 + c20) / s;
            } else if (c11 > c22) {
                float s = (float) Math.sqrt(1.0f + c11 - c00 - c22) * 2.0f;
                qw = (c02 - c20) / s;
                qx = (c01 + c10) / s;
                qy = 0.25f * s;
                qz = (c12 + c21) / s;
            } else {
                float s = (float) Math.sqrt(1.0f + c22 - c00 - c11) * 2.0f;
                qw = (c10 - c01) / s;
                qx = (c02 + c20) / s;
                qy = (c12 + c21) / s;
                qz = 0.25f * s;
            }

            float qlen = (float) Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
            if (qlen > 0.0001f) {
                qw /= qlen; qx /= qlen; qy /= qlen; qz /= qlen;
            }

            synchronized (curQuat) {
                curQuat[0] = qw;
                curQuat[1] = qx;
                curQuat[2] = qy;
                curQuat[3] = invertRoll ? -qz : qz;
            }

            // Physical Euler angles for UI
            pitchDeg = (float) Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -c12))));
            yawDeg   = (float) Math.toDegrees(Math.atan2(c02, c22));
            rollDeg  = (float) Math.toDegrees(Math.atan2(c10, c11));
            if (invertRoll) rollDeg = -rollDeg;

            sendImuPacket();
        }
    }

    private void sendImuPacket() {
        if (imuSocket == null || pcAddress == null) return;

        float qw, qx, qy, qz;
        synchronized (curQuat) {
            qw = curQuat[0]; qx = curQuat[1]; qy = curQuat[2]; qz = curQuat[3];
        }

        // BinaryIMUPacket (64 bytes)
        ByteBuffer bb = ByteBuffer.wrap(packetBuffer);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.put((byte) 'K'); bb.put((byte) 'V'); bb.put((byte) 'R'); bb.put((byte) 'I');
        bb.putShort((short) 1);
        bb.putShort((short) 0);
        bb.putLong(System.nanoTime() / 1000L);

        bb.putFloat(qw);
        bb.putFloat(qx);
        bb.putFloat(qy);
        bb.putFloat(qz);

        bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f); // Gyro
        bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f); // Accel
        bb.putFloat(30.0f); // Temp

        int cs = 0;
        for (int i = 0; i < 60; i++) {
            cs ^= (packetBuffer[i] & 0xFF);
            cs = ((cs << 1) | (cs >>> 31));
        }
        bb.putInt(cs);

        try {
            DatagramPacket pkt = new DatagramPacket(packetBuffer, 64, pcAddress, imuPort);
            imuSocket.send(pkt);
            packetCounter++;
            activePackets = packetCounter;
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (activeService == this) activeService = null;
        try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        stopTracking();
        if (sensorThread != null) sensorThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static class AudioStreamer implements Runnable {
        private final VRFusionService service;
        AudioStreamer(VRFusionService service) {
            this.service = service;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            while (service.isMicStreaming) {
                if (service.audioRecord == null) break;
                int read = service.audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && service.pcAddress != null && service.audioSocket != null) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, read, service.pcAddress, service.audioPort);
                        service.audioSocket.send(packet);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public static String getStatusString() {
        return "STAT:YAW=" + Math.round(yawDeg) +
               ";PITCH=" + Math.round(pitchDeg) +
               ";ROLL=" + Math.round(rollDeg) +
               ";ROLL_INV=" + (invertRoll ? 1 : 0) +
               ";FLIP_180=" + (flipYaw180 ? 1 : 0) +
               ";MIC=" + (activeMic ? 1 : 0) +
               ";SCALE=" + MainActivity.getRemoteEyeScale() +
               ";OFFSET_Y=" + MainActivity.getRemoteOffsetY() +
               ";UI_SCALE=" + MainActivity.getRemoteUiScale() +
               ";DIVIDER=" + MainActivity.getRemoteDivider() +
               ";BATT=" + batteryPercent;
    }

    static class CommandWorker implements Runnable {
        private final VRFusionService service;
        CommandWorker(VRFusionService service) {
            this.service = service;
        }

        @Override
        public void run() {
            byte[] buf = new byte[1024];
            while (service.isRunning) {
                try {
                    if (service.cmdSocket == null || service.cmdSocket.isClosed()) break;
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    service.cmdSocket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8").trim();
                    String cmd = msg.startsWith("CMD:") ? msg.substring(4).trim() : msg;
                    if (!cmd.isEmpty() && !cmd.equalsIgnoreCase("PING") && !cmd.equalsIgnoreCase("GET_STAT")) {
                        service.handleRemoteCommand(cmd);
                    }
                    String resp = getStatusString();
                    byte[] respBytes = resp.getBytes("UTF-8");
                    DatagramPacket reply = new DatagramPacket(respBytes, respBytes.length, packet.getAddress(), packet.getPort());
                    service.cmdSocket.send(reply);
                } catch (Exception e) {
                    if (!service.isRunning) break;
                }
            }
        }
    }

    static class TcpCommandWorker implements Runnable {
        private final VRFusionService service;
        TcpCommandWorker(VRFusionService service) {
            this.service = service;
        }

        @Override
        public void run() {
            while (service.isRunning) {
                try {
                    if (service.tcpCmdServer == null || service.tcpCmdServer.isClosed()) break;
                    Socket client = service.tcpCmdServer.accept();
                    new Thread(new TcpClientHandler(service, client)).start();
                } catch (Exception e) {
                    if (!service.isRunning) break;
                }
            }
        }
    }

    static class TcpClientHandler implements Runnable {
        private final VRFusionService service;
        private final Socket socket;
        TcpClientHandler(VRFusionService service, Socket socket) {
            this.service = service;
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                String line = reader.readLine();
                if (line != null) {
                    String msg = line.trim();
                    String cmd = msg.startsWith("CMD:") ? msg.substring(4).trim() : msg;
                    if (!cmd.isEmpty() && !cmd.equalsIgnoreCase("PING") && !cmd.equalsIgnoreCase("GET_STAT")) {
                        service.handleRemoteCommand(cmd);
                    }
                    String resp = getStatusString() + "\n";
                    writer.write(resp);
                    writer.flush();
                }
                socket.close();
            } catch (Exception ignored) {}
        }
    }
}
