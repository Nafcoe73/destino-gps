package pt.sinergias.destinogps;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * App 100% offline: só usa o recetor GPS do telemóvel.
 * Não tem permissão de Internet no manifesto — não consegue aceder à rede.
 */
public class MainActivity extends Activity {

    private static final int PEDIDO_PERMISSAO = 1;
    private static final int ESCOLHER_TXT = 10;
    private static final int ESCOLHER_IMG = 11;

    private static final String FICH_TXT = "destino.txt";
    private static final String FICH_IMG = "mapa.img";

    private MapaView mapaView;
    private TextView txtEstado, txtDetalhe, txtGps;
    private Button btnSeguir, btnRodar;

    private Bussola bussola;
    private float rumoBussola = Float.NaN;

    private Destino destino;
    private LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Location ultima;
    private long ultimaMs = 0;
    private int satUsados = -1, satVisiveis = -1;
    private Boolean dentroAntes = null;

    /** Histórico (tempo, distância) para saber se estou a aproximar ou a afastar. */
    private final ArrayDeque<double[]> historico = new ArrayDeque<>();

    private File txtTemp; // destino.txt escolhido, à espera da imagem

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mapaView = findViewById(R.id.mapa);
        txtEstado = findViewById(R.id.txt_estado);
        txtDetalhe = findViewById(R.id.txt_detalhe);
        txtGps = findViewById(R.id.txt_gps);
        btnSeguir = findViewById(R.id.btn_seguir);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        findViewById(R.id.btn_carregar).setOnClickListener(v -> escolherTxt());
        findViewById(R.id.btn_ajustar).setOnClickListener(v -> {
            mapaView.ajustar();
            atualizarBotaoSeguir();
        });
        btnSeguir.setOnClickListener(v -> {
            mapaView.alternarSeguir();
            atualizarBotaoSeguir();
        });

        btnRodar = findViewById(R.id.btn_rodar);
        bussola = new Bussola(this, (graus, fiavel) -> {
            rumoBussola = graus;
            mapaView.definirRumo(graus, fiavel);
        });
        Runnable alternarRodar = () -> {
            if (!bussola.disponivel()) {
                Toast.makeText(this, "Este telemóvel não tem bússola (magnetómetro).", Toast.LENGTH_LONG).show();
                return;
            }
            mapaView.alternarRodar();
            atualizarBotaoRodar();
        };
        btnRodar.setOnClickListener(v -> alternarRodar.run());
        mapaView.aoTocarBussola(alternarRodar);
        atualizarBotaoRodar();

        carregarGuardado();
        atualizarPainel();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (temPermissao()) iniciarGps();
        else requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, PEDIDO_PERMISSAO);
        handler.post(relogio);
        bussola.iniciar();
    }

    @Override
    protected void onStop() {
        super.onStop();
        pararGps();
        handler.removeCallbacks(relogio);
        bussola.parar();
    }

    // ------------------------------------------------------------------ GPS

    private boolean temPermissao() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != PEDIDO_PERMISSAO) return;
        if (temPermissao()) iniciarGps();
        else txtGps.setText("Sem permissão de localização precisa — a app não funciona sem ela.");
    }

    private final LocationListener ouvinte = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            novaPosicao(loc);
        }

        @Override
        public void onProviderEnabled(String p) {
            atualizarPainel();
        }

        @Override
        public void onProviderDisabled(String p) {
            atualizarPainel();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String p, int s, Bundle e) {
        }
    };

    private final GnssStatus.Callback gnss = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus st) {
            int usados = 0;
            for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) usados++;
            satUsados = usados;
            satVisiveis = st.getSatelliteCount();
        }
    };

    @SuppressWarnings("MissingPermission")
    private void iniciarGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, ouvinte, Looper.getMainLooper());
            lm.registerGnssStatusCallback(gnss, handler);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l != null && SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 60_000_000_000L) {
                novaPosicao(l);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            txtGps.setText("Não foi possível ligar o GPS: " + e.getMessage());
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) pedirParaLigarGps();
        atualizarPainel();
    }

    private void pararGps() {
        try {
            lm.removeUpdates(ouvinte);
            lm.unregisterGnssStatusCallback(gnss);
        } catch (Exception ignored) {
        }
    }

    private void pedirParaLigarGps() {
        new AlertDialog.Builder(this)
                .setTitle("GPS desligado")
                .setMessage("Liga a Localização para a app saber onde estás.")
                .setPositiveButton("Abrir definições",
                        (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void novaPosicao(Location loc) {
        ultima = loc;
        ultimaMs = SystemClock.elapsedRealtime();
        mapaView.definirPosicao(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
        bussola.definirDeclinacao(loc);
        if (destino != null) {
            double d = distanciaAoCentro(loc);
            historico.addLast(new double[]{ultimaMs, d});
            while (historico.size() > 1 && ultimaMs - historico.peekFirst()[0] > 8000) historico.removeFirst();

            boolean dentro = d <= destino.raioM;
            if (dentroAntes != null && dentro && !dentroAntes) {
                vibrar();
                Toast.makeText(this, "Chegaste ao destino!", Toast.LENGTH_LONG).show();
            }
            dentroAntes = dentro;
        }
        atualizarPainel();
    }

    private double distanciaAoCentro(Location loc) {
        float[] r = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), destino.centroLat, destino.centroLon, r);
        return r[0];
    }

    private float rumoAoCentro(Location loc) {
        float[] r = new float[2];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), destino.centroLat, destino.centroLon, r);
        return (r[1] + 360f) % 360f;
    }

    /** Positivo = a afastar, negativo = a aproximar (metros por segundo). */
    private double tendencia() {
        if (historico.size() < 2) return 0;
        double[] a = historico.peekFirst(), b = historico.peekLast();
        double dt = (b[0] - a[0]) / 1000.0;
        if (dt < 2.5) return 0;
        return (b[1] - a[1]) / dt;
    }

    private final Runnable relogio = new Runnable() {
        @Override
        public void run() {
            atualizarPainel();
            handler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ painel

    private void atualizarPainel() {
        boolean gpsLigado = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        long idade = ultima == null ? -1 : (SystemClock.elapsedRealtime() - ultimaMs) / 1000;

        // Linha do GPS
        String sats = satVisiveis >= 0 ? String.format(Locale.ROOT, " · satélites %d/%d", satUsados, satVisiveis) : "";
        if (!gpsLigado) {
            txtGps.setText("GPS desligado");
        } else if (ultima == null) {
            txtGps.setText("À espera de sinal GPS… (ao ar livre: 30 s a 2 min)" + sats);
        } else if (idade > 10) {
            txtGps.setText("Sinal GPS perdido há " + idade + " s" + sats);
        } else {
            txtGps.setText(String.format(Locale.ROOT, "GPS ±%.0f m", ultima.getAccuracy()) + sats);
        }

        if (destino == null) {
            txtEstado.setText("Sem destino");
            txtEstado.setTextColor(Color.LTGRAY);
            txtDetalhe.setText("Carrega o ficheiro .txt e depois a imagem do mapa.");
            return;
        }
        if (ultima == null) {
            txtEstado.setText(destino.nome.isEmpty() ? "Destino carregado" : destino.nome);
            txtEstado.setTextColor(Color.LTGRAY);
            txtDetalhe.setText("Raio " + formatar(destino.raioM));
            return;
        }

        double d = distanciaAoCentro(ultima);
        double borda = d - destino.raioM;
        float rumoDest = rumoAoCentro(ultima);
        String rumo = pontoCardeal(rumoDest);
        double t = tendencia();
        double limiar = Math.max(0.3, Math.min(1.0, ultima.getAccuracy() / 10.0)); // m/s
        String mov = idade > 10 ? "" : (t < -limiar ? " · A APROXIMAR" : (t > limiar ? " · A AFASTAR" : " · parado"));

        if (borda <= 0) {
            txtEstado.setText("DENTRO DO DESTINO");
            txtEstado.setTextColor(Color.rgb(60, 220, 90));
            txtDetalhe.setText(formatar(d) + " do centro (" + rumo + ")" + mov);
        } else {
            txtEstado.setText("FORA · faltam " + formatar(borda));
            txtEstado.setTextColor(t < -limiar ? Color.rgb(255, 200, 60) : Color.rgb(255, 90, 80));
            txtDetalhe.setText("Destino a " + formatar(d) + " para " + rumo + mov + indicacao(rumoDest));
        }
    }

    private static String formatar(double m) {
        if (m < 1000) return String.format(Locale.ROOT, "%.0f m", m);
        return String.format(Locale.forLanguageTag("pt-PT"), "%.2f km", m / 1000.0);
    }

    private static String pontoCardeal(float graus) {
        String[] p = {"N ↑", "NE ↗", "E →", "SE ↘", "S ↓", "SO ↙", "O ←", "NO ↖"};
        return p[(int) Math.floor(((graus + 22.5f) % 360f) / 45f)];
    }

    /** Com bússola: diz para que lado virar, em relação ao topo do telemóvel. */
    private String indicacao(float rumoDestino) {
        if (Float.isNaN(rumoBussola)) return "";
        float rel = ((rumoDestino - rumoBussola + 540f) % 360f) - 180f; // -180..180, + = direita
        int g = Math.round(Math.abs(rel));
        if (g <= 15) return "\n↑ em frente";
        if (g >= 160) return "\n↓ para trás (vira-te)";
        return rel > 0 ? "\n↱ vira à direita " + g + "°" : "\n↰ vira à esquerda " + g + "°";
    }

    private void atualizarBotaoRodar() {
        btnRodar.setText(mapaView.aRodar() ? "Rodar ✓" : "Rodar");
    }

    private void atualizarBotaoSeguir() {
        btnSeguir.setText(mapaView.aSeguir() ? "A seguir ✓" : "Seguir-me");
    }

    @SuppressWarnings("deprecation")
    private void vibrar() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        long[] padrao = {0, 400, 200, 400, 200, 700};
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(padrao, -1));
        else v.vibrate(padrao, -1);
    }

    // ------------------------------------------------------------------ ficheiros

    private void escolherTxt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream"});
        Toast.makeText(this, "1/2 — escolhe o ficheiro .txt do destino", Toast.LENGTH_SHORT).show();
        startActivityForResult(i, ESCOLHER_TXT);
    }

    private void escolherImagem() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        Toast.makeText(this, "2/2 — agora escolhe a imagem do mapa (.jpg/.png)", Toast.LENGTH_LONG).show();
        startActivityForResult(i, ESCOLHER_IMG);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == ESCOLHER_TXT) {
                txtTemp = new File(getCacheDir(), "destino_novo.txt");
                copiar(uri, txtTemp);
                try (InputStream in = new FileInputStream(txtTemp)) {
                    Destino.ler(in); // valida já, antes de pedir a imagem
                }
                escolherImagem();
            } else if (req == ESCOLHER_IMG && txtTemp != null) {
                File imgTemp = new File(getCacheDir(), "mapa_novo.img");
                copiar(uri, imgTemp);
                Destino d;
                try (InputStream in = new FileInputStream(txtTemp)) {
                    d = Destino.ler(in);
                }
                Bitmap bmp = descodificar(imgTemp);
                if (bmp == null) throw new IOException("A imagem não pôde ser lida.");
                avisarSeProporcaoDiferente(d, bmp);
                // Só substitui o destino antigo quando tudo está válido
                moverPara(txtTemp, new File(getFilesDir(), FICH_TXT));
                moverPara(imgTemp, new File(getFilesDir(), FICH_IMG));
                txtTemp = null;
                aplicar(d, bmp);
                Toast.makeText(this, "Destino carregado e guardado no telemóvel", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            new AlertDialog.Builder(this).setTitle("Erro").setMessage(e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private void avisarSeProporcaoDiferente(Destino d, Bitmap bmp) {
        if (d.larguraFicheiro <= 0 || d.alturaFicheiro <= 0) return;
        double a = (double) d.larguraFicheiro / d.alturaFicheiro;
        double b = (double) bmp.getWidth() / bmp.getHeight();
        if (Math.abs(a - b) > 0.02) {
            Toast.makeText(this, "Atenção: esta imagem parece não ser a do ficheiro .txt (proporção diferente).",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void carregarGuardado() {
        File t = new File(getFilesDir(), FICH_TXT), i = new File(getFilesDir(), FICH_IMG);
        if (!t.exists() || !i.exists()) return;
        try (InputStream in = new FileInputStream(t)) {
            Destino d = Destino.ler(in);
            Bitmap bmp = descodificar(i);
            if (bmp != null) aplicar(d, bmp);
        } catch (IOException ignored) {
        }
    }

    private void aplicar(Destino d, Bitmap bmp) {
        destino = d;
        historico.clear();
        dentroAntes = null;
        mapaView.definirMapa(bmp, d);
        if (ultima != null) novaPosicao(ultima);
        atualizarBotaoSeguir();
        atualizarPainel();
    }

    /** Descodifica a imagem, reduzindo-a se for enorme (evita falta de memória). */
    private static Bitmap descodificar(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int amostra = 1;
        while (Math.max(o.outWidth, o.outHeight) / amostra > 4096) amostra *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = amostra;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    private void copiar(Uri uri, File destinoFich) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destinoFich)) {
            if (in == null) throw new IOException("Não foi possível abrir o ficheiro.");
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 50L * 1024 * 1024) throw new IOException("Ficheiro demasiado grande.");
            }
        }
    }

    private static void moverPara(File de, File para) throws IOException {
        if (para.exists() && !para.delete()) throw new IOException("Não foi possível substituir " + para.getName());
        if (!de.renameTo(para)) throw new IOException("Não foi possível guardar " + para.getName());
    }
}
