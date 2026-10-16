# Destino GPS — duas apps

```
gerador_pc/        Parte 1: Python + Tkinter (no PC, com Internet)
app_android/       Parte 2: projeto Android Studio (no telemóvel, só GPS)
```

## Parte 1 — Gerador no PC

> **Também corre no telemóvel**, com a app *Pydroid 3* (Play Store), que suporta Tkinter.
> Abrir `gerador_destino.py` no Pydroid e carregar ▶. Os ficheiros vão por defeito para a pasta *Download*.
> Só esse passo precisa de Internet; depois a app Destino GPS funciona sem rede.

**Requisitos:** Python 3.8+ com Tkinter (já vem no Windows/macOS; no Linux: `sudo apt install python3-tk`).
Opcional: `pip install pillow` para a pré-visualização funcionar também com JPG.

**Chave Google:** em console.cloud.google.com crie uma chave e ative duas APIs:
- *Geocoding API* (converte a morada em coordenadas)
- *Maps Static API* (descarrega a imagem do mapa)

Se escrever o destino diretamente como `38.6916, -9.2160`, a Geocoding API não é usada.

**Executar:**
```
python gerador_destino.py
```
Preencha a chave (fica guardada em `~/.gerador_destino_gps.json`), o destino e o raio em metros, e carregue em **Gerar ficheiros**.
Saem dois ficheiros, por exemplo `destino.txt` e `destino.jpg`. Copie os dois para o telemóvel (cabo USB, cartão, Bluetooth…).

### O ficheiro de texto
Ver `gerador_pc/exemplo_destino.txt`. Contém:
- `CENTRO_LAT`, `CENTRO_LON`, `RAIO_M` — o destino
- `MAPA_NORTE/SUL/ESTE/OESTE` — as bordas exatas da imagem (é isto que permite pôr os pontos no sítio certo sem Internet)
- `PONTO=lat,lon` × 72 — o perímetro do círculo, de 5 em 5 graus

## Parte 2 — App Android

**Compilar:** abra a pasta `app_android` no Android Studio → espere a sincronização → *Run* com o telemóvel ligado por USB (depuração USB ativa).
Para gerar um APK: *Build → Generate App Bundles or APKs → Generate APKs*.
Se o Android Studio sugerir atualizar o plugin Android Gradle, pode aceitar.

**Usar:**
1. Abrir a app e aceitar a permissão de localização precisa.
2. **Carregar** → escolher o `.txt` → depois escolher a imagem.
   A app copia ambos para a memória interna; nas vezes seguintes abre logo com o último destino.
3. Ir para o ar livre. O primeiro sinal GPS demora 30 s a 2 min.

**No ecrã:**
- Círculo verde = zona do destino; ponto verde = centro
- Ponto vermelho = a sua posição (a mancha vermelha clara é a margem de erro do GPS)
- Seta vermelha na borda = está fora da zona visível do mapa, nessa direção
- Painel de cima: DENTRO / FORA, metros que faltam, direção (N, NE…), A APROXIMAR / A AFASTAR, precisão e satélites
- Vibra ao entrar no círculo
- Dois dedos = zoom; arrastar = mover; toque duplo = zoom ×2
- **Ver tudo** = imagem inteira; **Seguir-me** = mantém-no ao centro

**Bússola (canto superior direito):**
- Rosa dos ventos: o N vermelho mostra onde fica o Norte real
- **Seta verde = aponta, no mundo real, para o destino.** Rode o corpo até a seta ficar para cima e ande em frente
- Dentro do destino, a seta passa a ponto verde
- O painel também indica "↑ em frente", "↱ vira à direita 40°", etc.
- **Rodar** (ou tocar na bússola) = o mapa roda para ficar virado para onde está a olhar; voltar a tocar = Norte em cima
- Segure o telemóvel deitado, como uma bússola de mão
- Se aparecer "calibrar: faz um ∞", desenhe um 8 no ar com o telemóvel 2–3 vezes
- Longe de carros, grades metálicas e ímanes a bússola é mais certa
- Corrige automaticamente para o Norte verdadeiro, sem Internet (modelo magnético incluído no Android)

**Sem Internet de verdade:** o manifesto não pede a permissão `INTERNET`, por isso o Android impede a app de usar a rede. Usa só o recetor GPS (`GPS_PROVIDER`), sem Google Play Services.
