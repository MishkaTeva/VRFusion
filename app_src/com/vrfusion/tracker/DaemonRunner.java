package com.vrfusion.tracker;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Method;

public class DaemonRunner {
    public static void main(String[] args) {
        System.out.println("VRFusion Standalone Daemon started!");
        try {
            Looper.prepare();

            // Reflection to get system Context
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread");
            Object activityThread = currentActivityThreadMethod.invoke(null);
            if (activityThread == null) {
                Method systemMainMethod = activityThreadClass.getMethod("systemMain");
                activityThread = systemMainMethod.invoke(null);
            }
            Method getSystemContextMethod = activityThreadClass.getMethod("getSystemContext");
            Context context = (Context) getSystemContextMethod.invoke(activityThread);

            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            Sensor rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotSensor == null) {
                rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }

            DatagramSocket socket = new DatagramSocket();
            InetAddress pcAddr = InetAddress.getByName("192.168.31.2");
            int port = 9550;

            final float[] q = new float[4];
            final byte[] buf = new byte[64];

            sm.registerListener(new DaemonSensorListener(socket, pcAddr, port, q, buf),
                    rotSensor, SensorManager.SENSOR_DELAY_FASTEST);

            System.out.println("Sensor loop active and transmitting to 192.168.31.2:9550");
            Looper.loop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class DaemonSensorListener implements SensorEventListener {
        private final DatagramSocket socket;
        private final InetAddress pcAddr;
        private final int port;
        private final float[] q;
        private final byte[] buf;

        DaemonSensorListener(DatagramSocket socket, InetAddress pcAddr, int port, float[] q, byte[] buf) {
            this.socket = socket;
            this.pcAddr = pcAddr;
            this.port = port;
            this.q = q;
            this.buf = buf;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorManager.getQuaternionFromVector(q, event.values);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte) 'K'); bb.put((byte) 'V'); bb.put((byte) 'R'); bb.put((byte) 'I');
            bb.putShort((short) 1);
            bb.putShort((short) 0);
            bb.putLong(System.nanoTime() / 1000L);
            bb.putFloat(q[0]); // w
            bb.putFloat(q[1]); // x
            bb.putFloat(q[2]); // y
            bb.putFloat(q[3]); // z
            bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f); // gyro
            bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f); // accel
            bb.putFloat(30.0f); // temp

            int cs = 0;
            for (int i = 0; i < 60; i++) {
                cs ^= (buf[i] & 0xFF);
                cs = ((cs << 1) | (cs >>> 31));
            }
            bb.putInt(cs);

            try {
                socket.send(new DatagramPacket(buf, 64, pcAddr, port));
            } catch (Exception ignored) {}
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }
}
