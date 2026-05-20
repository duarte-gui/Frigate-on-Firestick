# Frigate TV — App Fire TV

App Android leve (~44 KB) que exibe câmeras do [Frigate NVR](https://github.com/blakeblackshear/frigate) na Fire TV via **WebRTC**, com troca entre câmeras pelo controle remoto.

## Por que existe

O Fire TV usa **Amazon WebView**, que desabilita MSE (Media Source Extensions) por padrão (`--disable-webkit-media-source`). Isso quebra o live view padrão do Frigate, que tenta tocar `fmp4/h264` via `<video>`. Resultado: snapshot estático sem vídeo ao vivo.

Este app contorna isso apontando direto pra `/live/webrtc/webrtc.html` do go2rtc embutido no Frigate — WebRTC funciona nativamente no Amazon WebView.

## Funcionalidades

- Lista de câmeras carregada **dinamicamente** do `/api/config` (não precisa recompilar pra adicionar câmera nova)
- Troca entre câmeras com **← →** do D-pad
- Resolução automática do stream go2rtc por câmera (busca `<nome>`, `<nome>_main`, `<nome>_sub`, case-insensitive)
- Sem dependências externas — só WebView nativo do Android

## Controle remoto

| Botão | Ação |
|---|---|
| ← / → | Troca câmera |
| OK / Enter | Mostra nome da câmera atual |
| MENU | Recarrega a lista de câmeras (use depois de adicionar uma no Frigate) |
| BACK | Sai do app |

## Configuração no Frigate

Cada câmera precisa ter um stream correspondente em `go2rtc.streams`. Exemplo:

```yaml
go2rtc:
  streams:
    minha_camera_main: rtsp://user:pass@cam-ip:554/cam/realmonitor?channel=1&subtype=0
    minha_camera_sub:  rtsp://user:pass@cam-ip:554/cam/realmonitor?channel=1&subtype=1

cameras:
  minha_camera:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/minha_camera_sub
          input_args: preset-rtsp-restream
          roles: [detect]
        - path: rtsp://127.0.0.1:8554/minha_camera_main
          input_args: preset-rtsp-restream
          roles: [record]
```

> Se o go2rtc roda com config separada (ex: `/config/go2rtc_homekit.yml`), edite **esse** arquivo, não o `frigate.yml`. Em seguida, `docker restart frigate`.

## Configurar o app

Edite o IP/porta do seu Frigate em `app/src/main/res/values/strings.xml`:

```xml
<string name="frigate_host">http://192.168.1.110:5000</string>
```

## Build

Requer JDK 17+ e Android SDK 34 (compileSdk).

```bash
./gradlew assembleDebug     # APK debug (89 KB)
./gradlew assembleRelease   # APK release (44 KB)
```

APK gerado em:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## Instalar no Fire TV

1. Ative **ADB Debugging** no Fire TV: Configurações → Meu Fire TV → Sobre → clique 7x em "Fire TV Stick" → volte → Opções do desenvolvedor → ativar **ADB debugging** e **Apps de fontes desconhecidas**.

2. Descubra o IP do Fire TV (Configurações → Rede).

3. Instale:
```bash
adb connect FIRE_TV_IP:5555
adb install app/build/outputs/apk/release/app-release.apk
```

O app aparece como **"Câmeras"** na lista de apps do Fire TV.

## Licença

MIT
