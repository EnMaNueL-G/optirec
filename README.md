<div align="center">

# OptiRec

**Grabadora de vídeo + audio (o solo audio) que sigue grabando con la pantalla apagada.**

Android 8+ · Gratis · Privada · Sin anuncios, sin telemetría, sin cuentas

</div>

---

OptiRec graba con tu propio teléfono aunque **apagues la pantalla** o salgas de la app.
Se controla con un atajo de **doble Vol +** o con el botón, y **solo se detiene
manualmente** (nunca sola por apagar la pantalla). Pensada para funcionar en segundo
plano con el **mínimo recalentamiento** posible.

## Características

- 🎥 **Vídeo + audio** con la pantalla apagada (Camera2 + MediaRecorder, sin vista previa).
- 🎙️ **Modo solo audio** (grabadora de voz): mucho menos consumo y calor, ideal para grabaciones largas.
- ⏺️ **Atajo doble Vol +**: inicia/detiene con la pantalla apagada, con vibración de confirmación.
- 🌡️ **Anti-recalentamiento**: 720p/24fps, estabilización y postproceso del ISP apagados, encoder por hardware, vigilante térmico que baja calidad o para si el teléfono se calienta demasiado.
- 🖼️ Los vídeos se guardan en la **Galería** (álbum OptiRec); los audios en **Grabaciones/OptiRec**.
- 🔦 Linterna opcional, cronómetro en la notificación, orientación correcta aunque el móvil esté de lado.
- 🔒 **100% local y privada**: sin anuncios, sin rastreo, sin cuentas, sin permiso de Internet innecesario.

## Instalación

1. Descarga el **APK** desde [Releases](https://github.com/EnMaNueL-G/optirec/releases/latest).
2. Ábrelo en el teléfono e instala (permite "orígenes desconocidos" si lo pide).
3. Concede **cámara**, **micrófono** y **notificaciones**.
4. Recomendado: dentro de la app, **quita el límite de batería** y activa el **autoarranque** de tu fabricante (Samsung/Xiaomi) para que no maten la grabación.

## Uso

- **Botón grande** para iniciar/detener, o el atajo **doble Vol +** (funciona con la pantalla apagada).
- Elige **Modo** (Vídeo+audio / Solo audio), cámara y calidad.
- **Ver grabaciones** abre la galería/audios.

## Límites honestos (del propio Android, no se pueden saltar)

- Mientras graba, Android **obliga** a mostrar una notificación y el **punto verde** de cámara/micrófono. No se pueden ocultar.
- Hay que **arrancar con la app abierta** al menos una vez (Android 14+ no permite encender la cámara desde segundo plano). Después, el atajo funciona con la pantalla apagada.
- Algunos fabricantes matan procesos en segundo plano: usa los ajustes de batería/autoarranque que la app te indica.

## Uso responsable

Graba en tu propio dispositivo y bajo tu control. **Grabar a terceros sin su
conocimiento puede ser ilegal** según tu país. Eres el único responsable del uso
legal. Esto no es asesoría jurídica.

## Compilar

```bash
git clone https://github.com/EnMaNueL-G/optirec
cd optirec
# crea keystore.properties con tu firma (no se incluye en el repo)
./gradlew :app:assembleRelease
```

## Licencia

[MIT](LICENSE) © Enmanuel Gil
