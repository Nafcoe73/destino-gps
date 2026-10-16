package pt.sinergias.destinogps;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;

/**
 * Bússola 100% offline: usa os sensores do telemóvel (magnetómetro + giroscópio/acelerómetro)
 * e corrige para o Norte verdadeiro com o modelo magnético que já vem no Android.
 */
public class Bussola implements SensorEventListener {

    public interface Ouvinte {
        /** grausVerdadeiros: para onde aponta o topo do telemóvel (0 = Norte, 90 = Este). */
        void onRumo(float grausVerdadeiros, boolean fiavel);
    }

    private final SensorManager sm;
    private final Sensor rotacao, acel, magn;
    private final Ouvinte ouvinte;

    private final float[] matR = new float[9];
    private final float[] ori = new float[3];
    private float[] grav, geo;
    private float declinacao = 0f;
    private float suave = Float.NaN;
    private int precisaoMag = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;

    public Bussola(Context c, Ouvinte o) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        rotacao = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        acel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magn = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        ouvinte = o;
    }

    public boolean disponivel() {
        return rotacao != null || (acel != null && magn != null);
    }

    public boolean iniciar() {
        if (sm == null || !disponivel()) return false;
        if (rotacao != null) {
            sm.registerListener(this, rotacao, SensorManager.SENSOR_DELAY_UI);
            // O magnetómetro só serve aqui para saber se a bússola precisa de calibração.
            if (magn != null) sm.registerListener(this, magn, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sm.registerListener(this, acel, SensorManager.SENSOR_DELAY_UI);
            sm.registerListener(this, magn, SensorManager.SENSOR_DELAY_UI);
        }
        return true;
    }

    public void parar() {
        if (sm != null) sm.unregisterListener(this);
    }

    /** Diferença entre Norte magnético e verdadeiro no sítio onde estou (offline). */
    public void definirDeclinacao(Location l) {
        GeomagneticField g = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis());
        declinacao = g.getDeclination();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int tipo = e.sensor.getType();
        if (tipo == Sensor.TYPE_MAGNETIC_FIELD) {
            precisaoMag = e.accuracy;
        }
        if (tipo == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matR, e.values);
        } else if (rotacao == null && (tipo == Sensor.TYPE_ACCELEROMETER || tipo == Sensor.TYPE_MAGNETIC_FIELD)) {
            if (tipo == Sensor.TYPE_ACCELEROMETER) grav = filtrar(grav, e.values);
            else geo = filtrar(geo, e.values);
            if (grav == null || geo == null) return;
            if (!SensorManager.getRotationMatrix(matR, null, grav, geo)) return;
        } else {
            return;
        }

        SensorManager.getOrientation(matR, ori);
        float az = (float) Math.toDegrees(ori[0]) + declinacao;
        az = ((az % 360f) + 360f) % 360f;

        // Suavizar (cuidado com a passagem 359° -> 0°)
        if (Float.isNaN(suave)) {
            suave = az;
        } else {
            float d = ((az - suave + 540f) % 360f) - 180f;
            suave = ((suave + 0.2f * d) % 360f + 360f) % 360f;
        }
        ouvinte.onRumo(suave, precisaoMag >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM);
    }

    private static float[] filtrar(float[] antigo, float[] novo) {
        if (antigo == null) return novo.clone();
        for (int i = 0; i < 3; i++) antigo[i] += 0.25f * (novo[i] - antigo[i]);
        return antigo;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int precisao) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) precisaoMag = precisao;
    }
}
