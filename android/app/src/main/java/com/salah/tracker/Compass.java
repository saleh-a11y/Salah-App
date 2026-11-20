package com.salah.tracker;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Phone heading for the Qibla compass, corrected from magnetic north to true north
 * with Android's built-in World Magnetic Model (in Gauteng the difference is roughly 18°).
 */
@CapacitorPlugin(name = "Compass")
public class Compass extends Plugin implements SensorEventListener {
    private SensorManager sm;
    private Sensor rot, acc, mag;
    private float decl = 0f;
    private float[] grav, geo;
    private long last = 0;
    private int accuracy = -1;
    private boolean running = false;

    @PluginMethod
    public void start(PluginCall call) {
        double lat = call.getDouble("lat", -26.2041), lon = call.getDouble("lon", 28.0473);
        decl = new GeomagneticField((float) lat, (float) lon, 1500f, System.currentTimeMillis()).getDeclination();
        if (sm == null) sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) { call.reject("No sensors on this phone"); return; }
        stopSensors();
        rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        String kind;
        if (rot != null && mag != null) {
            sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
            sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI); // only for its calibration status
            kind = "rotation";
        } else if (acc != null && mag != null) {
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
            sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME);
            kind = "magnetometer";
        } else {
            call.reject("This phone has no compass");
            return;
        }
        running = true;
        JSObject r = new JSObject();
        r.put("declination", decl);
        r.put("sensor", kind);
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopSensors();
        call.resolve();
    }

    @PluginMethod
    public void declination(PluginCall call) {
        double lat = call.getDouble("lat", -26.2041), lon = call.getDouble("lon", 28.0473);
        JSObject r = new JSObject();
        r.put("declination", new GeomagneticField((float) lat, (float) lon, 1500f, System.currentTimeMillis()).getDeclination());
        call.resolve(r);
    }

    private void stopSensors() {
        running = false;
        if (sm != null) sm.unregisterListener(this);
        grav = null; geo = null;
    }

    @Override
    protected void handleOnPause() { if (sm != null && running) sm.unregisterListener(this); }

    @Override
    protected void handleOnResume() {
        if (!running || sm == null) return;
        if (rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
        else if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (mag != null) sm.registerListener(this, mag, rot != null ? SensorManager.SENSOR_DELAY_UI : SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        float[] R = new float[9];
        if (t == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(R, e.values);
        } else if (t == Sensor.TYPE_ACCELEROMETER || t == Sensor.TYPE_MAGNETIC_FIELD) {
            if (t == Sensor.TYPE_ACCELEROMETER) grav = lowPass(e.values, grav); else geo = lowPass(e.values, geo);
            if (rot != null || grav == null || geo == null) return;
            if (!SensorManager.getRotationMatrix(R, null, grav, geo)) return;
        } else return;
        long now = System.currentTimeMillis();
        if (now - last < 40) return;
        last = now;
        float[] R2 = new float[9];
        int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
        switch (displayRotation()) {
            case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
            default: break;
        }
        SensorManager.remapCoordinateSystem(R, x, y, R2);
        float[] o = new float[3];
        SensorManager.getOrientation(R2, o);
        double magnetic = (Math.toDegrees(o[0]) + 360) % 360;
        double tilt = Math.toDegrees(Math.max(Math.abs(o[1]), Math.abs(o[2])));
        JSObject r = new JSObject();
        r.put("heading", (magnetic + decl + 360) % 360);
        r.put("magnetic", magnetic);
        r.put("tilt", tilt);
        r.put("accuracy", accuracy);
        notifyListeners("heading", r);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) accuracy = a;
    }

    private int displayRotation() {
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            return wm.getDefaultDisplay().getRotation();
        } catch (Exception e) { return Surface.ROTATION_0; }
    }

    private static float[] lowPass(float[] in, float[] out) {
        if (out == null) return in.clone();
        for (int i = 0; i < in.length && i < out.length; i++) out[i] = out[i] + 0.2f * (in[i] - out[i]);
        return out;
    }
}
