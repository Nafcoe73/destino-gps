package pt.sinergias.destinogps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Desenha a imagem do mapa (zoom, arrasto e rotação), o círculo verde do destino,
 * a minha posição a vermelho e a bússola. Tudo local, sem rede.
 */
public class MapaView extends View {

    private Bitmap mapa;
    private Destino destino;

    private boolean temPosicao = false;
    private double meuLat, meuLon;
    private float precisaoM;

    private boolean seguir = false;
    private boolean rodarMapa = false;

    private boolean temBussola = false;
    private boolean bussolaFiavel = true;
    private float rumo = 0f;                          // para onde aponta o topo do telemóvel (graus)

    private final Matrix matriz = new Matrix();       // imagem -> ecrã, sem rotação (zoom + arrasto)
    private final Matrix fin = new Matrix();          // matriz final, com a rotação do mapa
    private final Matrix aux = new Matrix();
    private float escalaAjuste = 1f;

    private float[] perimetroImg = new float[0];
    private final float[] tmp = new float[2];
    private final float[] resultado = new float[2];
    private final Path caminho = new Path();
    private final Path seta = new Path();
    private final float dp;

    private float bussolaX, bussolaY, bussolaR;
    private Runnable aoTocarBussola;

    private final Paint pMapa = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pCirculoFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCirculoTraco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVerde = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVermelho = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBranco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pContorno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPrecisao = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTexto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFundoBussola = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAnel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMarcas = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLetras = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAviso = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector escalaDet;
    private final GestureDetector gestosDet;

    public MapaView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;

        pCirculoFill.setColor(Color.argb(55, 0, 200, 60));
        pCirculoTraco.setColor(Color.rgb(0, 170, 40));
        pCirculoTraco.setStyle(Paint.Style.STROKE);
        pCirculoTraco.setStrokeWidth(3 * dp);
        pVerde.setColor(Color.rgb(0, 190, 50));
        pVermelho.setColor(Color.rgb(230, 30, 30));
        pBranco.setColor(Color.WHITE);
        pContorno.setColor(Color.WHITE);
        pContorno.setStyle(Paint.Style.STROKE);
        pContorno.setStrokeWidth(2.5f * dp);
        pContorno.setStrokeJoin(Paint.Join.ROUND);
        pPrecisao.setColor(Color.argb(45, 230, 30, 30));
        pTexto.setColor(Color.rgb(170, 170, 170));
        pTexto.setTextSize(16 * dp);
        pTexto.setTextAlign(Paint.Align.CENTER);
        pFundoBussola.setColor(Color.argb(190, 17, 17, 19));
        pAnel.setColor(Color.argb(200, 255, 255, 255));
        pAnel.setStyle(Paint.Style.STROKE);
        pAnel.setStrokeWidth(1.5f * dp);
        pMarcas.setColor(Color.argb(160, 255, 255, 255));
        pMarcas.setStrokeWidth(1.2f * dp);
        pLetras.setColor(Color.WHITE);
        pLetras.setTextSize(12 * dp);
        pLetras.setFakeBoldText(true);
        pLetras.setTextAlign(Paint.Align.CENTER);
        pAviso.setColor(Color.rgb(255, 200, 60));
        pAviso.setTextSize(11 * dp);
        pAviso.setTextAlign(Paint.Align.CENTER);
        pAviso.setShadowLayer(3 * dp, 0, 0, Color.BLACK);

        escalaDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                aplicarZoom(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestosDet = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                seguir = false;
                // O arrasto é no ecrã (já rodado): converter para o espaço sem rotação.
                float[] v = {-dx, -dy};
                aux.setRotate(rotacaoMapa());
                aux.mapVectors(v);
                matriz.postTranslate(v[0], v[1]);
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                float dx = e.getX() - bussolaX, dy = e.getY() - bussolaY;
                if (aoTocarBussola != null && dx * dx + dy * dy <= bussolaR * bussolaR * 1.4f) {
                    aoTocarBussola.run();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                aplicarZoom(2f, e.getX(), e.getY());
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ dados

    public void definirMapa(Bitmap bmp, Destino d) {
        mapa = bmp;
        destino = d;
        perimetroImg = new float[d.perimetro.size() * 2];
        for (int i = 0; i < d.perimetro.size(); i++) {
            double[] p = d.perimetro.get(i);
            d.paraImagem(p[0], p[1], bmp.getWidth(), bmp.getHeight(), tmp);
            perimetroImg[2 * i] = tmp[0];
            perimetroImg[2 * i + 1] = tmp[1];
        }
        ajustar();
    }

    public void definirPosicao(double lat, double lon, float precisao) {
        temPosicao = true;
        meuLat = lat;
        meuLon = lon;
        precisaoM = precisao;
        if (seguir) centrarEmMim();
        invalidate();
    }

    /** Novo rumo da bússola. Só redesenha se mudou o suficiente para se notar. */
    public void definirRumo(float graus, boolean fiavel) {
        float dif = Math.abs(((graus - rumo + 540f) % 360f) - 180f);
        boolean mudouEstado = !temBussola || fiavel != bussolaFiavel;
        temBussola = true;
        bussolaFiavel = fiavel;
        if (dif > 0.4f || mudouEstado) {
            rumo = graus;
            invalidate();
        }
    }

    public void aoTocarBussola(Runnable r) {
        aoTocarBussola = r;
    }

    public boolean alternarSeguir() {
        seguir = !seguir;
        if (seguir) centrarEmMim();
        return seguir;
    }

    public boolean aSeguir() {
        return seguir;
    }

    public boolean alternarRodar() {
        rodarMapa = !rodarMapa;
        invalidate();
        return rodarMapa;
    }

    public boolean aRodar() {
        return rodarMapa;
    }

    /** Mostra a imagem inteira, centrada. */
    public void ajustar() {
        seguir = false;
        if (mapa == null || getWidth() == 0) {
            invalidate();
            return;
        }
        float sx = (float) getWidth() / mapa.getWidth();
        float sy = (float) getHeight() / mapa.getHeight();
        escalaAjuste = Math.min(sx, sy);
        matriz.reset();
        matriz.postScale(escalaAjuste, escalaAjuste);
        matriz.postTranslate((getWidth() - mapa.getWidth() * escalaAjuste) / 2f,
                (getHeight() - mapa.getHeight() * escalaAjuste) / 2f);
        invalidate();
    }

    private float rotacaoMapa() {
        return rodarMapa && temBussola ? rumo : 0f;
    }

    private void centrarEmMim() {
        if (mapa == null || !temPosicao) return;
        destino.paraImagem(meuLat, meuLon, mapa.getWidth(), mapa.getHeight(), tmp);
        matriz.mapPoints(tmp);
        // A rotação é feita à volta do centro do ecrã, por isso o ponto fica no centro.
        matriz.postTranslate(getWidth() / 2f - tmp[0], getHeight() / 2f - tmp[1]);
        invalidate();
    }

    private float escalaAtual() {
        return matriz.mapRadius(1f);
    }

    private void aplicarZoom(float fator, float fx, float fy) {
        float atual = escalaAtual();
        float nova = Math.max(escalaAjuste * 0.5f, Math.min(escalaAjuste * 16f, atual * fator));
        fator = nova / atual;
        float[] f = {fx, fy};
        if (seguir) {
            f[0] = getWidth() / 2f;
            f[1] = getHeight() / 2f;
        } else {
            // Ponto de foco no ecrã rodado -> espaço sem rotação
            aux.setRotate(rotacaoMapa(), getWidth() / 2f, getHeight() / 2f);
            aux.mapPoints(f);
        }
        matriz.postScale(fator, fator, f[0], f[1]);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        ajustar();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean a = escalaDet.onTouchEvent(e);
        boolean b = gestosDet.onTouchEvent(e);
        return a || b || super.onTouchEvent(e);
    }

    // ------------------------------------------------------------------ desenho

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.rgb(28, 28, 30));
        if (mapa == null) {
            c.drawText("Carrega um destino (botão em baixo)", getWidth() / 2f, getHeight() / 2f, pTexto);
            desenharBussola(c);
            return;
        }

        fin.set(matriz);
        float rot = rotacaoMapa();
        if (rot != 0f) fin.postRotate(-rot, getWidth() / 2f, getHeight() / 2f);

        c.save();
        c.concat(fin);
        c.drawBitmap(mapa, 0, 0, pMapa);
        c.restore();

        // Círculo do destino
        if (perimetroImg.length >= 6) {
            float[] ecra = new float[perimetroImg.length];
            fin.mapPoints(ecra, perimetroImg);
            caminho.reset();
            caminho.moveTo(ecra[0], ecra[1]);
            for (int i = 2; i < ecra.length; i += 2) caminho.lineTo(ecra[i], ecra[i + 1]);
            caminho.close();
            c.drawPath(caminho, pCirculoFill);
            c.drawPath(caminho, pCirculoTraco);
        }
        destino.paraImagem(destino.centroLat, destino.centroLon, mapa.getWidth(), mapa.getHeight(), tmp);
        fin.mapPoints(tmp);
        c.drawCircle(tmp[0], tmp[1], 7 * dp, pBranco);
        c.drawCircle(tmp[0], tmp[1], 5 * dp, pVerde);

        if (temPosicao) {
            destino.paraImagem(meuLat, meuLon, mapa.getWidth(), mapa.getHeight(), tmp);
            fin.mapPoints(tmp);
            float x = tmp[0], y = tmp[1];
            float margem = 28 * dp;

            if (x >= 0 && x <= getWidth() && y >= 0 && y <= getHeight()) {
                float rPrec = (float) (precisaoM * destino.pixeisPorMetro(mapa.getWidth()) * escalaAtual());
                if (rPrec > 9 * dp) c.drawCircle(x, y, rPrec, pPrecisao);
                c.drawCircle(x, y, 10 * dp, pBranco);
                c.drawCircle(x, y, 7.5f * dp, pVermelho);
            } else {
                // Fora do ecrã: seta vermelha na borda a apontar para onde estou
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float dx = x - cx, dy = y - cy;
                float kx = dx == 0 ? Float.MAX_VALUE : (cx - margem) / Math.abs(dx);
                float ky = dy == 0 ? Float.MAX_VALUE : (cy - margem) / Math.abs(dy);
                float k = Math.min(kx, ky);
                desenharSetaBorda(c, cx + dx * k, cy + dy * k, (float) Math.toDegrees(Math.atan2(dy, dx)));
            }
        }

        desenharBussola(c);
    }

    private void desenharSetaBorda(Canvas c, float x, float y, float graus) {
        float t = 18 * dp;
        seta.reset();
        seta.moveTo(t, 0);
        seta.lineTo(-t * 0.7f, t * 0.7f);
        seta.lineTo(-t * 0.35f, 0);
        seta.lineTo(-t * 0.7f, -t * 0.7f);
        seta.close();
        c.save();
        c.translate(x, y);
        c.rotate(graus);
        c.drawPath(seta, pContorno);
        c.drawPath(seta, pVermelho);
        c.restore();
    }

    /**
     * Rosa dos ventos no canto superior direito.
     * As letras mostram onde fica o Norte real em relação ao topo do telemóvel;
     * a seta verde aponta, no mundo real, para o centro do destino.
     */
    private void desenharBussola(Canvas c) {
        float r = 50 * dp;
        float cx = getWidth() - r - 12 * dp, cy = r + 12 * dp;
        bussolaX = cx;
        bussolaY = cy;
        bussolaR = r;
        float topo = temBussola ? rumo : 0f;   // sem sensor: Norte em cima (como o mapa)

        c.drawCircle(cx, cy, r + 3 * dp, pFundoBussola);
        c.drawCircle(cx, cy, r, pAnel);

        // Marcas de 30 em 30 graus
        for (int g = 0; g < 360; g += 30) {
            double a = Math.toRadians(g - topo);
            float s = (float) Math.sin(a), co = (float) Math.cos(a);
            float r1 = r - (g % 90 == 0 ? 0 : 5 * dp), r2 = r;
            if (g % 90 != 0) c.drawLine(cx + s * r1, cy - co * r1, cx + s * r2, cy - co * r2, pMarcas);
        }

        // Agulha do Norte (triângulo vermelho junto ao anel)
        c.save();
        c.rotate(-topo, cx, cy);
        seta.reset();
        seta.moveTo(cx, cy - r + 1 * dp);
        seta.lineTo(cx - 6 * dp, cy - r + 11 * dp);
        seta.lineTo(cx + 6 * dp, cy - r + 11 * dp);
        seta.close();
        c.drawPath(seta, pVermelho);
        c.restore();

        // Letras sempre direitas
        String[] letras = {"N", "E", "S", "O"};
        float rl = r - 19 * dp;
        float ajusteY = pLetras.getTextSize() * 0.36f;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - topo);
            pLetras.setColor(i == 0 ? Color.rgb(255, 90, 80) : Color.WHITE);
            c.drawText(letras[i], cx + (float) Math.sin(a) * rl, cy - (float) Math.cos(a) * rl + ajusteY, pLetras);
        }

        // Seta verde para o destino
        if (temPosicao && destino != null) {
            Location.distanceBetween(meuLat, meuLon, destino.centroLat, destino.centroLon, resultado);
            if (resultado[0] <= destino.raioM) {
                c.drawCircle(cx, cy, 9 * dp, pBranco);
                c.drawCircle(cx, cy, 7 * dp, pVerde);
            } else {
                float t = r * 0.62f;
                seta.reset();
                seta.moveTo(cx, cy - t);
                seta.lineTo(cx + t * 0.42f, cy + t * 0.55f);
                seta.lineTo(cx, cy + t * 0.25f);
                seta.lineTo(cx - t * 0.42f, cy + t * 0.55f);
                seta.close();
                c.save();
                c.rotate(resultado[1] - topo, cx, cy);
                c.drawPath(seta, pContorno);
                c.drawPath(seta, pVerde);
                c.restore();
            }
        } else {
            c.drawCircle(cx, cy, 3 * dp, pAnel);
        }

        // Estado por baixo da bússola
        float ty = cy + r + 16 * dp;
        if (!temBussola) {
            c.drawText("sem bússola", cx, ty, pAviso);
        } else if (!bussolaFiavel) {
            c.drawText("calibrar: faz um ∞", cx, ty, pAviso);
        } else if (rodarMapa) {
            c.drawText("mapa a rodar", cx, ty, pAviso);
        }
    }
}
