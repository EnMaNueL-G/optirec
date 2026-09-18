# OptiRec v1.0.2 — Grabación de vídeo + audio con la pantalla apagada

**Novedades v1.0.2:** icono propio (lente + punto REC); **modo Solo audio** (grabadora
de voz sin cámara, mucho menos calor/batería); cronómetro en la notificación;
**vibración** al iniciar/detener con el atajo (para saberlo a ciegas); orientación
correcta del vídeo aunque el móvil esté de lado; botón "Ver grabaciones"; linterna;
aviso de espacio insuficiente; y refuerzo de fiabilidad del doble Vol +.


App Android privada (sin Play Store) para grabar con tu propio teléfono aunque la
pantalla esté apagada. Se controla con **doble pulsación de Vol +** o con el botón
de la app. La grabación **solo se detiene manualmente** (doble Vol + o botón), nunca
sola por apagar la pantalla o salir de la app.

- **APK firmado:** `OptiRec-v1.0.2.apk` (4.7 MB)
- **Paquete:** `com.optisuite.optirec`
- **Compatibilidad:** Android 8 (API 26) a Android 14/15. Probado como objetivo en Galaxy S25.

---

## 1. Instalar

1. Copia `OptiRec-v1.0.0.apk` al teléfono.
2. Ábrelo desde el explorador de archivos → "Instalar" (permite "orígenes desconocidos" si lo pide).
3. Al abrir la app, concede **Cámara**, **Micrófono** y **Notificaciones**.

O por cable con adb:
```
adb install -r OptiRec-v1.0.0.apk
```

## 2. Preparar (una sola vez, MUY importante para que no se corte)

Dentro de la app, pulsa:
- **"Quitar límite de batería"** → acepta el diálogo del sistema.
- **"Guía anti-cierre (tu marca)"** → activa el **autoarranque / sin restricciones** de tu fabricante.
  - **Samsung:** Ajustes → Batería → esa app → **Sin restricciones**; y quítala de "apps en suspensión".
  - **Xiaomi:** Autoarranque **activado** + Batería "Sin restricciones" + bloquear la app en recientes (candado).

Sin esto, Samsung/Xiaomi pueden matar la grabación a los minutos.

## 3. Usar

- **Botón grande "Iniciar grabación"** → empieza a grabar; ya puedes apagar la pantalla.
- **Atajo con pantalla apagada:** pulsa **Vol + dos veces seguidas** (rápido) para **iniciar** o **detener**.
- **"Armar atajo doble Vol +"** → deja la app escuchando el atajo **sin grabar todavía** (bajo consumo). Luego, doble Vol + empieza a grabar.
- Detener: **doble Vol +** otra vez, o el botón **"Detener"**, o **"Salir"** en la notificación.

**Modo Vídeo + audio:** los vídeos se guardan en la **Galería**, álbum **OptiRec**
(`Movies/OptiRec/`), en MP4 de ~10 min cada uno.
**Modo Solo audio:** los audios (.m4a) se guardan en **Grabaciones/OptiRec**
(`Recordings/OptiRec/`); no salen en el carrete de fotos, sino en el explorador de
archivos o apps de audio. El modo audio consume mucha menos batería y calor.

Para copiarlos al PC:
```
adb pull /sdcard/Movies/OptiRec/
adb pull /sdcard/Recordings/OptiRec/
```

## 4. Menos recalentamiento (lo prioritario)

Medidas ya incluidas:
- **720p a 24 fps** por defecto (calidad "recomendada"). Usa **480p** para grabaciones muy largas y frías.
- **Estabilización óptica/digital apagada** y **reducción de ruido / realce de bordes apagados** (menos trabajo del procesador de imagen).
- **fps bloqueado**, encoder por **hardware**, audio **mono**.
- **Vigilante térmico:** si el SoC se calienta, baja fps y bitrate en el siguiente tramo; si llega a **crítico, detiene la grabación** para proteger el teléfono.
- **Sin wake lock cuando solo está "armado"** (no grabando): la CPU duerme → nada de calor en reposo.
- Segmentos de 10 min: si se corta por calor, solo pierdes el último tramo, no todo.

Consejo físico: **deja el móvil destapado y ventilado**. El calor es el límite real, no el software.

## 5. Límites honestos (del propio Android, no se pueden saltar)

1. **No es invisible.** Mientras graba, Android **obliga** a mostrar una notificación y el **punto verde de cámara/micro** en la barra de estado. No se pueden ocultar (es protección del sistema).
2. **Hay que arrancar con la app abierta** al menos una vez (Android 14+ no deja encender la cámara desde segundo plano). Después, con la app "armada", el doble Vol + con pantalla apagada sí funciona.
3. **El atajo es "lo mejor posible".** Si otra app de música toma el control del volumen, o el fabricante mata el proceso, el doble Vol + podría no responder; usa el botón de la app como respaldo.
4. Tras **reiniciar el móvil** hay que abrir la app y volver a armar/grabar.

## 6. Aviso de uso responsable

Graba en tu propio dispositivo y bajo tu control. Grabar a terceros sin su
conocimiento **puede ser ilegal** según tu país (muchos exigen consentimiento y
protegen la intimidad). Eres el único responsable del uso legal. Esto no es
asesoría jurídica.

---

## Estado de verificación

Probado en vivo en Galaxy S25 (SM-S938B, Android 15, One UI):
- ✅ Instala y abre sin fallos; firma verificada.
- ✅ Graba vídeo + audio **con la pantalla apagada** (comprobado a oscuras).
- ✅ Los vídeos se guardan en la **Galería** (álbum OptiRec), MP4 válido 720p/24fps + AAC.
- ✅ El **doble Vol +** físico inicia/detiene la grabación (nuestra app recibe los botones de volumen).
- ✅ Maneja sin romperse que otra app (p. ej. una videollamada) le quite la cámara.

## Compilar de nuevo

```
cd Proyectos/optirec
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat :app:assembleRelease
```
APK en `app/build/outputs/apk/release/app-release.apk`.
