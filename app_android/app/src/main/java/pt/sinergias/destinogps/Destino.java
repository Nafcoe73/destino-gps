package pt.sinergias.destinogps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê o ficheiro de texto gerado no PC e converte lat/lon em píxeis da imagem.
 * A imagem da Google está em Web Mercator: longitude é linear em X,
 * e em Y é linear o valor ln(tan(pi/4 + lat/2)).
 */
public class Destino {

    public String nome = "";
    public double centroLat, centroLon, raioM;
    public double norte, sul, este, oeste;
    public int larguraFicheiro, alturaFicheiro;
    public final List<double[]> perimetro = new ArrayList<>();

    private static final double RAIO_TERRA_M = 6371008.8;

    public static Destino ler(InputStream in) throws IOException {
        Destino d = new Destino();
        boolean temCentro = false, temRaio = false, temLimites = false;
        int limites = 0;
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String linha;
        while ((linha = br.readLine()) != null) {
            linha = linha.trim();
            if (linha.isEmpty() || linha.startsWith("#")) continue;
            int i = linha.indexOf('=');
            if (i <= 0) continue;
            String k = linha.substring(0, i).trim().toUpperCase();
            String v = linha.substring(i + 1).trim();
            try {
                switch (k) {
                    case "NOME": d.nome = v; break;
                    case "CENTRO_LAT": d.centroLat = num(v); temCentro = true; break;
                    case "CENTRO_LON": d.centroLon = num(v); break;
                    case "RAIO_M": d.raioM = num(v); temRaio = true; break;
                    case "MAPA_NORTE": d.norte = num(v); limites++; break;
                    case "MAPA_SUL": d.sul = num(v); limites++; break;
                    case "MAPA_ESTE": d.este = num(v); limites++; break;
                    case "MAPA_OESTE": d.oeste = num(v); limites++; break;
                    case "MAPA_LARGURA_PX": d.larguraFicheiro = (int) num(v); break;
                    case "MAPA_ALTURA_PX": d.alturaFicheiro = (int) num(v); break;
                    case "PONTO": {
                        String[] p = v.split(",");
                        if (p.length == 2) d.perimetro.add(new double[]{num(p[0]), num(p[1])});
                        break;
                    }
                    default: break;
                }
            } catch (NumberFormatException e) {
                throw new IOException("Valor inválido na linha: " + linha);
            }
        }
        temLimites = limites == 4;
        if (!temCentro || !temRaio || !temLimites) {
            throw new IOException("Ficheiro incompleto: faltam CENTRO, RAIO_M ou os limites MAPA_*.");
        }
        if (d.norte <= d.sul || d.este == d.oeste) {
            throw new IOException("Limites do mapa inválidos.");
        }
        if (d.perimetro.isEmpty()) d.gerarPerimetro();
        return d;
    }

    private static double num(String s) {
        return Double.parseDouble(s.trim().replace(',', '.'));
    }

    /** Se o ficheiro não trouxer pontos, calcula o círculo aqui. */
    private void gerarPerimetro() {
        double la1 = Math.toRadians(centroLat), lo1 = Math.toRadians(centroLon);
        double dist = raioM / RAIO_TERRA_M;
        for (int b = 0; b < 360; b += 5) {
            double br = Math.toRadians(b);
            double la2 = Math.asin(Math.sin(la1) * Math.cos(dist) + Math.cos(la1) * Math.sin(dist) * Math.cos(br));
            double lo2 = lo1 + Math.atan2(Math.sin(br) * Math.sin(dist) * Math.cos(la1),
                    Math.cos(dist) - Math.sin(la1) * Math.sin(la2));
            perimetro.add(new double[]{Math.toDegrees(la2), Math.toDegrees(lo2)});
        }
    }

    private static double merc(double lat) {
        double l = Math.max(-85.05112878, Math.min(85.05112878, lat));
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(l) / 2));
    }

    /** lat/lon -> píxeis da imagem (pode dar fora da imagem se estiver longe). */
    public void paraImagem(double lat, double lon, int w, int h, float[] out) {
        double x = (lon - oeste) / (este - oeste) * w;
        double mn = merc(norte), ms = merc(sul);
        double y = (mn - merc(lat)) / (mn - ms) * h;
        out[0] = (float) x;
        out[1] = (float) y;
    }

    /** Píxeis da imagem por metro, no centro (Mercator é conforme: igual em X e Y). */
    public double pixeisPorMetro(int w) {
        double pxPorGrau = w / (este - oeste);
        double metrosPorGrau = Math.PI / 180.0 * RAIO_TERRA_M * Math.cos(Math.toRadians(centroLat));
        return pxPorGrau / metrosPorGrau;
    }
}
