#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gerador de Destino GPS  (parte 1 de 2 — corre no PC, com Internet)

Dado um destino e um raio, usa a Google Maps Platform para:
  1) obter as coordenadas do destino   (Geocoding API)
  2) descarregar uma imagem do mapa     (Maps Static API)
e grava dois ficheiros para copiar para o telemóvel:
  - <nome>.txt   coordenadas do destino, raio, limites exatos da imagem
                 e os pontos GPS do perímetro do círculo
  - <nome>.jpg   (ou .png) a imagem do mapa

Sem dependências externas: só a biblioteca padrão do Python (tkinter + urllib).
Se o Pillow estiver instalado, a pré-visualização também funciona para JPG.
"""

import json
import math
import os
import struct
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

import tkinter as tk
from tkinter import filedialog, messagebox, ttk

# --------------------------------------------------------------------------
# Matemática (funções puras, sem interface)
# --------------------------------------------------------------------------

RAIO_TERRA_M = 6371008.8          # raio médio da Terra (IUGG)
TILE = 256                         # Web Mercator: mundo = 256 * 2^zoom píxeis
STATIC_MAX_PX = 640                # limite da Maps Static API (por lado, escala 1)
STATIC_ESCALA = 2                  # escala 2 -> imagem real de 1280 x 1280


def metros_por_pixel(lat, zoom):
    """Metros por píxel lógico (escala 1) na latitude dada."""
    return 156543.03392804097 * math.cos(math.radians(lat)) / (2 ** zoom)


def escolher_zoom(lat, raio_m, margem=1.5, lado_px=STATIC_MAX_PX):
    """Maior zoom em que o diâmetro (raio * margem * 2) cabe na imagem."""
    alvo_m = 2.0 * raio_m * margem
    mpp_necessario = alvo_m / lado_px
    z = math.floor(math.log2(156543.03392804097 * math.cos(math.radians(lat)) / mpp_necessario))
    return max(1, min(21, z))


def latlon_para_mundo(lat, lon, zoom):
    """Coordenadas em píxeis do 'mundo' Web Mercator (escala 1)."""
    tamanho = TILE * (2 ** zoom)
    x = (lon + 180.0) / 360.0 * tamanho
    s = math.sin(math.radians(max(-85.05112878, min(85.05112878, lat))))
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * tamanho
    return x, y


def mundo_para_latlon(x, y, zoom):
    tamanho = TILE * (2 ** zoom)
    lon = x / tamanho * 360.0 - 180.0
    n = math.pi - 2.0 * math.pi * y / tamanho
    lat = math.degrees(math.atan(math.sinh(n)))
    return lat, lon


def limites_imagem(lat, lon, zoom, largura=STATIC_MAX_PX, altura=STATIC_MAX_PX):
    """Limites (norte, sul, este, oeste) da imagem centrada em lat/lon."""
    cx, cy = latlon_para_mundo(lat, lon, zoom)
    norte, oeste = mundo_para_latlon(cx - largura / 2, cy - altura / 2, zoom)
    sul, este = mundo_para_latlon(cx + largura / 2, cy + altura / 2, zoom)
    return norte, sul, este, oeste


def ponto_destino(lat, lon, rumo_graus, distancia_m):
    """Ponto a 'distancia_m' metros de lat/lon no rumo dado (esfera)."""
    d = distancia_m / RAIO_TERRA_M
    br = math.radians(rumo_graus)
    la1, lo1 = math.radians(lat), math.radians(lon)
    la2 = math.asin(math.sin(la1) * math.cos(d) + math.cos(la1) * math.sin(d) * math.cos(br))
    lo2 = lo1 + math.atan2(math.sin(br) * math.sin(d) * math.cos(la1),
                           math.cos(d) - math.sin(la1) * math.sin(la2))
    lo2 = (math.degrees(lo2) + 540) % 360 - 180
    return math.degrees(la2), lo2


def distancia_m(lat1, lon1, lat2, lon2):
    """Distância haversine em metros."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * RAIO_TERRA_M * math.asin(math.sqrt(a))


def perimetro(lat, lon, raio_m, passo_graus=5):
    return [ponto_destino(lat, lon, b, raio_m) for b in range(0, 360, passo_graus)]


def tentar_ler_latlon(texto):
    """Aceita 'lat, lon' escrito diretamente (dispensa a Geocoding API)."""
    try:
        partes = [p.strip() for p in texto.replace(";", ",").split(",")]
        if len(partes) != 2:
            return None
        lat, lon = float(partes[0]), float(partes[1])
        if -90 <= lat <= 90 and -180 <= lon <= 180:
            return lat, lon
    except ValueError:
        pass
    return None


def dimensoes_imagem(dados):
    """Largura/altura de um PNG ou JPEG a partir dos bytes."""
    if dados[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", dados[16:24])
    if dados[:2] == b"\xff\xd8":
        i = 2
        while i < len(dados) - 9:
            if dados[i] != 0xFF:
                i += 1
                continue
            marca = dados[i + 1]
            if marca in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", dados[i + 5:i + 9])
                return w, h
            tam = struct.unpack(">H", dados[i + 2:i + 4])[0]
            i += 2 + tam
    return None


# --------------------------------------------------------------------------
# Google Maps Platform
# --------------------------------------------------------------------------

def geocodificar(endereco, chave):
    url = "https://maps.googleapis.com/maps/api/geocode/json?" + urllib.parse.urlencode(
        {"address": endereco, "key": chave, "language": "pt-PT"})
    with urllib.request.urlopen(url, timeout=20) as r:
        resp = json.load(r)
    estado = resp.get("status")
    if estado != "OK":
        msg = resp.get("error_message", "")
        raise RuntimeError(f"Geocoding falhou: {estado} {msg}".strip())
    res = resp["results"][0]
    loc = res["geometry"]["location"]
    return loc["lat"], loc["lng"], res.get("formatted_address", endereco)


def descarregar_mapa(lat, lon, zoom, chave, tipo="roadmap", formato="jpg"):
    url = "https://maps.googleapis.com/maps/api/staticmap?" + urllib.parse.urlencode({
        "center": f"{lat:.7f},{lon:.7f}",
        "zoom": zoom,
        "size": f"{STATIC_MAX_PX}x{STATIC_MAX_PX}",
        "scale": STATIC_ESCALA,
        "maptype": tipo,
        "format": "jpg" if formato == "jpg" else "png32",
        "language": "pt-PT",
        "key": chave,
    })
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            tipo_conteudo = r.headers.get("Content-Type", "")
            dados = r.read()
    except urllib.error.HTTPError as e:
        corpo = e.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"Maps Static API respondeu {e.code}: {corpo}")
    if not tipo_conteudo.startswith("image/"):
        raise RuntimeError("Maps Static API não devolveu imagem: " + dados[:300].decode("utf-8", "replace"))
    return dados


def escrever_ficheiro_txt(caminho, nome, lat, lon, raio, zoom, limites, img_nome, img_wh, pontos):
    norte, sul, este, oeste = limites
    linhas = [
        "# Destino GPS - gerado por gerador_destino.py",
        f"# {datetime.now():%Y-%m-%d %H:%M}",
        "# Projeção da imagem: Web Mercator (Google). Limites = bordas exatas da imagem.",
        f"NOME={nome}",
        f"CENTRO_LAT={lat:.7f}",
        f"CENTRO_LON={lon:.7f}",
        f"RAIO_M={raio:.1f}",
        f"MAPA={img_nome}",
        f"MAPA_ZOOM={zoom}",
        f"MAPA_LARGURA_PX={img_wh[0]}",
        f"MAPA_ALTURA_PX={img_wh[1]}",
        f"MAPA_NORTE={norte:.7f}",
        f"MAPA_SUL={sul:.7f}",
        f"MAPA_ESTE={este:.7f}",
        f"MAPA_OESTE={oeste:.7f}",
        f"# Perímetro do círculo: {len(pontos)} pontos (lat,lon), de 5 em 5 graus a partir do Norte",
    ]
    linhas += [f"PONTO={a:.7f},{b:.7f}" for a, b in pontos]
    with open(caminho, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(linhas) + "\n")


# --------------------------------------------------------------------------
# Interface Tkinter
# --------------------------------------------------------------------------

CONFIG = os.path.join(os.path.expanduser("~"), ".gerador_destino_gps.json")


def pasta_por_defeito():
    """No PC: pasta pessoal. No Android (Pydroid 3): a pasta Download do telemóvel."""
    if "ANDROID_ROOT" in os.environ or "ANDROID_DATA" in os.environ:
        for p in ("/storage/emulated/0/Download", "/sdcard/Download"):
            if os.path.isdir(p):
                return p
    return os.path.expanduser("~")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Gerador de Destino GPS")
        self.minsize(520, 560)
        self._preview = None
        cfg = self._ler_config()

        self.v_chave = tk.StringVar(value=cfg.get("chave", os.environ.get("GOOGLE_MAPS_API_KEY", "")))
        self.v_destino = tk.StringVar()
        self.v_raio = tk.StringVar(value="500")
        self.v_margem = tk.StringVar(value="50")
        self.v_tipo = tk.StringVar(value=cfg.get("tipo", "roadmap"))
        self.v_formato = tk.StringVar(value=cfg.get("formato", "jpg"))
        self.v_pasta = tk.StringVar(value=cfg.get("pasta", pasta_por_defeito()))
        self.v_nome = tk.StringVar(value="destino")
        self.v_estado = tk.StringVar(value="Escreve o destino e o raio, e carrega em Gerar.")

        f = ttk.Frame(self, padding=12)
        f.pack(fill="both", expand=True)
        f.columnconfigure(1, weight=1)

        r = 0
        ttk.Label(f, text="Chave Google API:").grid(row=r, column=0, sticky="w", pady=3)
        ttk.Entry(f, textvariable=self.v_chave, show="•").grid(row=r, column=1, columnspan=2, sticky="ew")
        r += 1
        ttk.Label(f, text="1) Destino:").grid(row=r, column=0, sticky="w", pady=3)
        e_dest = ttk.Entry(f, textvariable=self.v_destino)
        e_dest.grid(row=r, column=1, columnspan=2, sticky="ew")
        e_dest.focus_set()
        r += 1
        ttk.Label(f, text="   (morada, local, ou 'lat, lon')", foreground="gray").grid(row=r, column=1, sticky="w")
        r += 1
        ttk.Label(f, text="2) Raio (metros):").grid(row=r, column=0, sticky="w", pady=3)
        ttk.Entry(f, textvariable=self.v_raio, width=12).grid(row=r, column=1, sticky="w")
        r += 1

        av = ttk.LabelFrame(f, text="Opções", padding=8)
        av.grid(row=r, column=0, columnspan=3, sticky="ew", pady=8)
        av.columnconfigure(1, weight=1)
        ttk.Label(av, text="Margem à volta do raio (%):").grid(row=0, column=0, sticky="w")
        ttk.Entry(av, textvariable=self.v_margem, width=8).grid(row=0, column=1, sticky="w")
        ttk.Label(av, text="Tipo de mapa:").grid(row=1, column=0, sticky="w")
        ttk.Combobox(av, textvariable=self.v_tipo, state="readonly", width=12,
                     values=["roadmap", "satellite", "hybrid", "terrain"]).grid(row=1, column=1, sticky="w")
        ttk.Label(av, text="Formato da imagem:").grid(row=2, column=0, sticky="w")
        fr = ttk.Frame(av)
        fr.grid(row=2, column=1, sticky="w")
        ttk.Radiobutton(fr, text="JPG (mais leve)", value="jpg", variable=self.v_formato).pack(side="left")
        ttk.Radiobutton(fr, text="PNG", value="png", variable=self.v_formato).pack(side="left", padx=8)
        ttk.Label(av, text="Pasta de saída:").grid(row=3, column=0, sticky="w")
        ttk.Entry(av, textvariable=self.v_pasta).grid(row=3, column=1, sticky="ew")
        ttk.Button(av, text="…", width=3, command=self._escolher_pasta).grid(row=3, column=2)
        ttk.Label(av, text="Nome dos ficheiros:").grid(row=4, column=0, sticky="w")
        ttk.Entry(av, textvariable=self.v_nome, width=20).grid(row=4, column=1, sticky="w")
        r += 1

        self.btn = ttk.Button(f, text="Gerar ficheiros", command=self._gerar)
        self.btn.grid(row=r, column=0, columnspan=3, pady=6, sticky="ew")
        r += 1
        ttk.Label(f, textvariable=self.v_estado, wraplength=480, justify="left").grid(
            row=r, column=0, columnspan=3, sticky="w")
        r += 1
        self.lbl_img = ttk.Label(f)
        self.lbl_img.grid(row=r, column=0, columnspan=3, pady=8)
        f.rowconfigure(r, weight=1)

        self.bind("<Return>", lambda e: self._gerar())

    # ----- configuração -----
    def _ler_config(self):
        try:
            with open(CONFIG, encoding="utf-8") as fh:
                return json.load(fh)
        except Exception:
            return {}

    def _gravar_config(self):
        try:
            with open(CONFIG, "w", encoding="utf-8") as fh:
                json.dump({"chave": self.v_chave.get().strip(), "tipo": self.v_tipo.get(),
                           "formato": self.v_formato.get(), "pasta": self.v_pasta.get()}, fh)
        except Exception:
            pass

    def _escolher_pasta(self):
        p = filedialog.askdirectory(initialdir=self.v_pasta.get() or None)
        if p:
            self.v_pasta.set(p)

    # ----- geração -----
    def _gerar(self):
        chave = self.v_chave.get().strip()
        destino = self.v_destino.get().strip()
        try:
            raio = float(self.v_raio.get().replace(",", "."))
            margem = 1.0 + float(self.v_margem.get().replace(",", ".")) / 100.0
            assert raio > 0 and margem >= 1.0
        except Exception:
            messagebox.showerror("Erro", "Raio e margem têm de ser números positivos.")
            return
        if not destino:
            messagebox.showerror("Erro", "Escreve um destino.")
            return
        if not chave:
            messagebox.showerror("Erro", "Falta a chave da Google Maps API.")
            return
        pasta = self.v_pasta.get().strip() or pasta_por_defeito()
        nome = (self.v_nome.get().strip() or "destino").replace(os.sep, "_")
        self.btn.state(["disabled"])
        self.v_estado.set("A trabalhar…")
        self._gravar_config()
        args = (chave, destino, raio, margem, self.v_tipo.get(), self.v_formato.get(), pasta, nome)
        threading.Thread(target=self._trabalho, args=args, daemon=True).start()

    def _trabalho(self, chave, destino, raio, margem, tipo, formato, pasta, nome_base):
        try:
            direto = tentar_ler_latlon(destino)
            if direto:
                lat, lon = direto
                nome = destino
            else:
                self._estado("A procurar o destino (Geocoding)…")
                lat, lon, nome = geocodificar(destino, chave)

            zoom = escolher_zoom(lat, raio, margem)
            self._estado(f"A descarregar mapa (zoom {zoom})…")
            dados = descarregar_mapa(lat, lon, zoom, chave, tipo, formato)
            wh = dimensoes_imagem(dados) or (STATIC_MAX_PX * STATIC_ESCALA,) * 2

            limites = limites_imagem(lat, lon, zoom)
            pontos = perimetro(lat, lon, raio)

            os.makedirs(pasta, exist_ok=True)
            img_nome = f"{nome_base}.{formato}"
            img_caminho = os.path.join(pasta, img_nome)
            txt_caminho = os.path.join(pasta, f"{nome_base}.txt")
            with open(img_caminho, "wb") as fh:
                fh.write(dados)
            escrever_ficheiro_txt(txt_caminho, nome, lat, lon, raio, zoom, limites, img_nome, wh, pontos)

            norte, sul, este, oeste = limites
            largura_m = distancia_m(lat, oeste, lat, este)
            msg = (f"Pronto!\n{nome}\n{lat:.6f}, {lon:.6f}  •  raio {raio:.0f} m\n"
                   f"Imagem {wh[0]}×{wh[1]} px, cobre ~{largura_m / 1000:.2f} km de largura, "
                   f"{len(dados) / 1024:.0f} KB\n\nFicheiros:\n{txt_caminho}\n{img_caminho}\n\n"
                   f"Copia os dois para o telemóvel.")
            self.after(0, lambda: self._concluido(msg, img_caminho, dados, wh, limites, lat, lon, pontos))
        except Exception as e:
            erro = str(e)
            self.after(0, lambda: self._falhou(erro))

    def _estado(self, txt):
        self.after(0, lambda: self.v_estado.set(txt))

    def _falhou(self, erro):
        self.btn.state(["!disabled"])
        self.v_estado.set("Erro: " + erro)
        messagebox.showerror("Erro", erro)

    def _concluido(self, msg, img_caminho, dados, wh, limites, lat, lon, pontos):
        self.btn.state(["!disabled"])
        self.v_estado.set(msg)
        self._mostrar_preview(img_caminho, wh, limites, lat, lon, pontos)

    def _mostrar_preview(self, caminho, wh, limites, lat, lon, pontos):
        """Pré-visualização reduzida, com o círculo desenhado (como na app)."""
        lado = 300
        foto = None
        try:
            from PIL import Image, ImageDraw, ImageTk  # opcional
            im = Image.open(caminho).convert("RGB")
            d = ImageDraw.Draw(im)
            xy = [self._proj(a, b, limites, im.width, im.height) for a, b in pontos]
            d.polygon(xy, outline=(0, 170, 0), width=max(2, im.width // 300))
            cx, cy = self._proj(lat, lon, limites, im.width, im.height)
            d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=(0, 170, 0))
            im.thumbnail((lado, lado))
            foto = ImageTk.PhotoImage(im)
        except ImportError:
            if caminho.lower().endswith(".png"):
                try:
                    foto = tk.PhotoImage(file=caminho)
                    fator = max(1, wh[0] // lado)
                    foto = foto.subsample(fator, fator)
                except tk.TclError:
                    foto = None
        except Exception:
            foto = None
        if foto is not None:
            self._preview = foto
            self.lbl_img.configure(image=foto, text="")
        else:
            self.lbl_img.configure(image="", text="(pré-visualização de JPG requer Pillow: pip install pillow)")

    @staticmethod
    def _proj(lat, lon, limites, w, h):
        norte, sul, este, oeste = limites
        merc = lambda la: math.log(math.tan(math.pi / 4 + math.radians(la) / 2))
        x = (lon - oeste) / (este - oeste) * w
        y = (merc(norte) - merc(lat)) / (merc(norte) - merc(sul)) * h
        return x, y


if __name__ == "__main__":
    App().mainloop()
