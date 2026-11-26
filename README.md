# Bolsillo+ (Finanzas IA)

Android app in Kotlin to track personal spending. It imports the Excel or CSV statement exported from your bank, detects its layout automatically (Santander, BBVA, CaixaBank formats), stores transactions locally with Room, categorises them with prioritised rules and shows charts. Everything stays on the phone: no account, no network, no cloud. Includes unit and instrumented tests.

*Personal project, designed by me and built with AI-assisted development (Claude Code). The guide below is in Spanish.*

---

# Finanzas IA — Guía de uso

> Controla tus gastos e ingresos sin complicaciones. Importa tus movimientos del banco, ponles etiqueta y entiende en qué gastas tu dinero.

---

## ¿Qué hace esta app?

Importas el fichero Excel que te da tu banco → la app lo lee, organiza tus movimientos y los guarda en tu móvil → tú les pones categorías (Alimentación, Ocio, Transporte…) → puedes ver gráficas y exportar todo a Excel cuando quieras.

**Tus datos nunca salen de tu móvil.** No hay cuenta, no hay internet, no hay nube.

---

## Antes de empezar: consigue el fichero de tu banco

1. Entra en la app o web de tu banco.
2. Ve a **Movimientos** o **Extracto de cuenta**.
3. Busca la opción de exportar o descargar en formato **Excel** (`.xls` o `.xlsx`).
4. Guarda el fichero en tu móvil (en Descargas, por ejemplo).

> Si no encuentras la opción, busca en Google: *"[nombre de tu banco] exportar movimientos Excel"*.

---

## Paso 1 — Importar tus movimientos

1. Abre **Finanzas IA**.
2. Pulsa el botón **＋** (esquina inferior derecha).
3. Busca y selecciona el fichero Excel que descargaste del banco.
4. La app lo procesa en unos segundos y añade los movimientos nuevos.

> Si importas el mismo fichero dos veces, no se duplica nada. La app detecta automáticamente los repetidos.

---

## Paso 2 — La pantalla principal

Nada más importar verás tres zonas:

```
┌─────────────────────────────────┐
│  💚 Ingresos   🔴 Gastos   Balance │  ← Resumen de dinero
├─────────────────────────────────┤
│  [Semana] [Mes] [Año] [Todo]    │  ← Filtra por periodo
├─────────────────────────────────┤
│  🥧 Gráfico de gastos por tipo  │
│  📊 Evolución de los últimos    │
│     6 meses                     │
├─────────────────────────────────┤
│  🔍 Buscar...                   │
│  [Todas] [Sin categoría] [...]  │  ← Filtra por categoría
├─────────────────────────────────┤
│  Lista de movimientos           │
└─────────────────────────────────┘
```

**Selector de periodo:** toca *Semana*, *Mes*, *Año* o *Todo* para ver solo los movimientos de ese rango. Las gráficas y los totales cambian al instante.

**Barra de búsqueda:** escribe cualquier palabra para encontrar un movimiento concreto (p. ej. "Mercadona" o "Netflix").

**Filtro de categoría:** toca una etiqueta para ver solo esos movimientos.

---

## Paso 3 — Categorizar movimientos

Las categorías son las "etiquetas" que explican en qué gastaste: Alimentación, Transporte, Ocio, etc.

### Categorizar un movimiento concreto

1. **Toca** el movimiento en la lista.
2. Elige la categoría que corresponde entre los chips de colores.
3. Aparece un diálogo con **dos opciones fijas** y una tercera opcional:

   | Opción | Qué hace |
   |---|---|
   | **Solo este movimiento** | Cambia solo este, sin crear ninguna regla |
   | **Mismo nombre exacto** | Cambia este y todos los que se llamen igual, y crea una regla para el futuro |
   | **Crear regla personalizada…** | Tú escribes el patrón (p. ej. "amazon") y eliges si debe buscar *esa frase en cualquier parte* o *esa palabra completa* |

4. Pulsa **Aplicar**.
5. La app te dice cuántos movimientos se van a cambiar → pulsa **Confirmar** para aplicar.

> **Consejo:** la segunda opción (*Mismo nombre exacto*) es la más habitual. Cambia todos los "Mercadona" de golpe y la app aprende para la próxima vez.

### Categorías que vienen de serie

La app ya conoce muchos comercios habituales:

| Categoría | Ejemplos que detecta |
|---|---|
| 🍎 Alimentación | Mercadona, Gadis, Gadisline |
| 🛒 Compras Online | Amazon, Amazon Prime |
| 🎬 Ocio | Netflix |
| … | Puedes añadir los que necesites |

---

## Paso 4 — Qué significa el icono ⚠️

A veces verás un **⚠️ amarillo** junto a un movimiento. Significa que **dos o más reglas automáticas coinciden** con ese movimiento y no queda claro a qué categoría pertenece.

### Cómo resolverlo

**Opción A — Toca el movimiento con ⚠️**

Se abre un diálogo que te muestra las reglas en conflicto, cuál está ganando ahora y dos botones:

- **Cambiar regla ganadora** → ves la lista de reglas candidatas, eliges cuál debe ganar y luego seleccionas el alcance (solo este movimiento, todos con el mismo nombre, o una regla nueva personalizada).
- **Categorizar manualmente** → abre el selector de categorías normal para asignar manualmente sin tocar reglas.

**Opción B — Mantén pulsado el movimiento con ⚠️**

Se abre el **diagnóstico completo**: ves todas las reglas que coinciden, cuál está activa (marcada con ▶) y puedes elegir cuál debe ganar o crear una regla nueva más específica.

> **Ejemplo real:** tienes una regla *"amazon → Compras Online"* y otra *"amazon prime → Ocio"*. Ambas coinciden con "Amazon Prime Video". El ⚠️ te avisa del conflicto. La solución es crear una regla exacta *"amazon prime video → Ocio"* que siempre gane a la genérica de "amazon".

---

## Paso 5 — Gestión de categorías

Pulsa el menú **⋮** (tres puntos, arriba a la derecha) → **Categorías**.

### Qué puedes hacer desde aquí

**Crear una categoría nueva**
Pulsa el botón **+** (abajo a la derecha) → escribe el nombre → listo.

**Ver los movimientos de una categoría**
Toca cualquier categoría de la lista → se abre un panel con dos pestañas:

- **Movimientos**: todos los movimientos de esa categoría. Toca uno para cambiar su categoría; mantenlo pulsado para ver el diagnóstico de reglas.
- **Automatizaciones**: las reglas que tiene esta categoría (p. ej. *"mercadona" — Contiene*). Puedes añadir más, editarlas, activarlas/desactivarlas o borrarlas.

**Renombrar una categoría**
Desde el panel de detalle → botón **Renombrar**.

**Mover todos los movimientos a otra categoría**
Desde el panel de detalle → botón **Mover todos a otra categoría…** → elige el destino → confirma.

**Eliminar una categoría**
Desde el panel de detalle → botón **Eliminar**. Los movimientos quedan como *Sin categoría* (no se borran).

### Añadir una regla de automatización

Dentro del detalle de categoría → pestaña **Automatizaciones** → **Añadir patrón**:

| Tipo | Cuándo usarlo |
|---|---|
| **Exacta** | El concepto es exactamente ese texto |
| **Contiene frase** | El concepto contiene esa secuencia de palabras en cualquier parte |
| **Contiene palabra** | El concepto contiene esa palabra completa (no sirve si es parte de otra) |
| **Regex** | Para usuarios avanzados que saben expresiones regulares |

### Re-escanear con las reglas actualizadas

Si cambias o añades reglas y quieres aplicarlas a todos tus movimientos anteriores:

**Categorías** → botón **Re-escanear automatizaciones** → confirma.

La app repasa todos los movimientos que no hayas categorizado manualmente y les aplica las reglas más actualizadas. Al final te dice cuántos se actualizaron.

> Esto es útil tras resolver conflictos o añadir reglas nuevas, para que los cambios se apliquen al historial completo.

---

## Paso 6 — Estadísticas detalladas

Pulsa el menú **⋮** → **Estadísticas**.

Verás un desglose por **año y mes** con:
- Total de ingresos y gastos
- Desglose por categoría
- Comparativas entre periodos

---

## Paso 7 — Exportar a Excel

Pulsa el menú **⋮** → **Exportar a Excel**.

Se genera un fichero `.xlsx` con todos tus movimientos (fecha, concepto, categoría, importe, tipo, saldo). Puedes compartirlo por WhatsApp, guardarlo en Drive, abrirlo en tu ordenador, etc.

---

## Eliminar todo el historial

Si quieres empezar de cero:

Menú **⋮** → **Eliminar historial** → confirma dos veces (la app te lo pregunta dos veces para evitar borrados accidentales).

> Las categorías y las reglas **no se borran**, solo los movimientos.

---

## Preguntas frecuentes

**¿Puedo importar el mismo fichero dos veces?**
Sí, sin problema. La app detecta los duplicados y los ignora automáticamente.

**¿Mis datos están seguros?**
Sí. Todo se guarda únicamente en tu móvil. La app no tiene conexión a internet y no comparte nada con nadie.

**Categoricé un movimiento mal. ¿Cómo lo corrijo?**
Toca el movimiento → elige la categoría correcta → *Solo este movimiento* si solo quieres corregir ese, o *Mismo nombre exacto* si quieres corregirlos todos de golpe.

**Un movimiento tiene ⚠️ pero ya tiene categoría. ¿Es un problema?**
La categoría que ves es correcta (la regla ganadora la eligió la app), pero el aviso te dice que hay ambigüedad. Si la categoría es la que quieres, puedes ignorarlo. Si no lo es, tócalo y elige la regla correcta.

**Añadí una regla nueva pero los movimientos antiguos no cambiaron.**
Las reglas nuevas se aplican automáticamente a las importaciones futuras. Para aplicarlas al historial usa **Re-escanear automatizaciones** desde la pantalla de Categorías.

**¿Puedo crear mis propias categorías?**
Sí, tantas como quieras. Ve a Categorías → pulsa **+**.

---

## Requisitos del móvil

- Android 13 o superior
- ~50 MB de espacio libre
- Fichero de movimientos en formato Excel (`.xls` / `.xlsx`)
                                                                                      