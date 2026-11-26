# =============================================================================
# ProGuard / R8 — Bolsillo+
# =============================================================================

# ── Info de debug para crash reports (mantener en beta) ──────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ── Room ─────────────────────────────────────────────────────────────────────
# Las entidades y DAOs se acceden por reflection desde el código generado
# por kapt. Sin estas reglas Room falla en runtime con nombres ofuscados.
-keep class com.jesusluna.bolsilloplus.data.db.entity.** { *; }
-keep interface com.jesusluna.bolsilloplus.data.db.dao.** { *; }
-keep class com.jesusluna.bolsilloplus.data.db.AppDatabase { *; }
-keep class com.jesusluna.bolsilloplus.data.db.Converters { *; }

# Enums usados en TypeConverter (Room llama a Enum.valueOf(String))
-keepclassmembers enum com.jesusluna.bolsilloplus.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ── Gson (backup JSON) ───────────────────────────────────────────────────────
# Gson usa reflection para serializar/deserializar campos por nombre.
# Sin esto los campos se renombran y el JSON generado/leído es incompatible.
-keep class com.jesusluna.bolsilloplus.data.backup.** { *; }
-keepclassmembers class com.jesusluna.bolsilloplus.data.db.entity.** { <fields>; }
-keepclassmembers class com.jesusluna.bolsilloplus.data.backup.** { <fields>; }

# Gson internos
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer


# ── Apache POI ───────────────────────────────────────────────────────────────
# POI usa ServiceLoader + reflection extensivamente para XML/OOXML.
# Con shrinking agresivo la lectura/escritura de Excel falla en runtime.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.collections4.** { *; }
-keep class org.apache.commons.io.** { *; }

-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.apache.commons.**
-dontwarn org.etsi.**
-dontwarn org.w3.x2000.**
-dontwarn schemasMicrosoftComOfficeExcel.**
-dontwarn schemasMicrosoftComOfficeSpreadsheetMl.**
-dontwarn schemasMicrosoftComVml.**

# Dependencias opcionales de POI no disponibles en Android:
#  - java.awt: API de escritorio (Shape, etc.) usada por graphbuilder (dep de POI)
#  - org.osgi: soporte de contenedores OSGi en log4j2 (dep transitiva de POI)
#  - java.lang.management: ManagementFactory (process ID) — no disponible en Android;
#    log4j-api lo llama dentro de try/catch(Throwable) → fallo seguro en ART.
-dontwarn java.awt.**
-dontwarn org.osgi.framework.**
-dontwarn java.lang.management.**
-dontwarn org.apache.poi.xslf.draw.SVGUserAgent

# Log4j API (añadida explícitamente para que POI no explote en runtime)
-keep class org.apache.logging.log4j.** { *; }
-dontwarn org.apache.logging.log4j.**


# ── OpenCSV ───────────────────────────────────────────────────────────────────
-keep class com.opencsv.** { *; }
-dontwarn com.opencsv.**


# ── MPAndroidChart ────────────────────────────────────────────────────────────
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**


# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }
-dontwarn kotlin.**

# Coroutines: campos volátiles internos accedidos por reflection de JVM
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**


# ── ViewBinding ───────────────────────────────────────────────────────────────
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(android.view.View);
}


# ── AndroidX / Material ───────────────────────────────────────────────────────
-dontwarn androidx.**
-dontwarn com.google.android.material.**
