import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.jesusluna.bolsilloplus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jesusluna.bolsilloplus"
        minSdk = 33
        targetSdk = 35

        // versionCode: entero estrictamente creciente para Play Store.
        // Esquema: MAJOR*10000 + MINOR*100 + PATCH  → 1.3.2 = 10302
        versionCode = 10306
        versionName = "1.3.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Firma del release ───────────────────────────────────────────────────
    // Credenciales en local.properties (NO subir al repositorio).
    // Formato esperado en local.properties:
    //   RELEASE_STORE_FILE=../bolsillo_release.jks
    //   RELEASE_STORE_PASSWORD=contraseña_del_keystore
    //   RELEASE_KEY_ALIAS=bolsillo_key
    //   RELEASE_KEY_PASSWORD=contraseña_de_la_clave
    signingConfigs {
        create("release") {
            val props = Properties()
            val localProps = rootProject.file("local.properties")
            if (localProps.exists()) props.load(localProps.inputStream())

            val storeFilePath = props.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile     = rootProject.file(storeFilePath)
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias      = props.getProperty("RELEASE_KEY_ALIAS")
                keyPassword   = props.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // .debug permite instalar debug y release en el mismo dispositivo
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
        }

        release {
            // R8: shrink + ofuscación + optimización
            isMinifyEnabled   = true
            isShrinkResources = true
            isDebuggable      = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Firma automática si local.properties contiene el keystore.
            // Sin las propiedades → APK sin firmar (firmar manualmente con
            // Build > Generate Signed Bundle/APK en Android Studio).
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Core Android (versiones compatibles con AGP 8.5.0)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RecyclerView y CardView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Apache POI para Excel (lectura y escritura)
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    // Dependencias adicionales de POI
    implementation("org.apache.xmlbeans:xmlbeans:5.1.1")
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("commons-io:commons-io:2.11.0")
    implementation("org.apache.commons:commons-collections4:4.4")

    // Log4j API — requerida por POI en runtime pero marcada como 'optional' en su POM,
    // por lo que Gradle no la incluye transitivamente. Sin ella, IOUtils.<clinit> lanza
    // NoClassDefFoundError: Lorg/apache/logging/log4j/LogManager en Android.
    // Solo la API (sin log4j-core); sin implementación POI cae al SimpleLogger (stderr).
    implementation("org.apache.logging.log4j:log4j-api:2.20.0")

    // OpenCSV para archivos CSV
    implementation("com.opencsv:opencsv:5.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Fragment KTX (para BottomSheetDialogFragment + activityViewModels)
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // ViewPager2 para onboarding
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // MPAndroidChart para gráficas
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Gson para JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing - unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Testing - instrumented tests (Android / Room)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
}
