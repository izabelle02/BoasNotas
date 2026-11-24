plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.boasnotas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.boasnotas"
        minSdk = 26
        targetSdk = 35
        // O GitHub Actions passa o número do build, para cada APK novo ser uma "atualização".
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "0")
    }

    signingConfigs {
        // Chave fixa guardada no repositório: assim cada APK novo instala POR CIMA do
        // anterior (sem desinstalar e sem perder os cadernos). Só serve para instalar
        // fora da Play Store; para publicar na loja, crie uma chave sua e guarde em segredo.
        create("boasnotas") {
            storeFile = file("boasnotas.keystore")
            storePassword = "boasnotas"
            keyAlias = "boasnotas"
            keyPassword = "boasnotas"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("boasnotas")
        }
        debug {
            signingConfig = signingConfigs.getByName("boasnotas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.testLogging {
                    events("passed", "failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStackTraces = true
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    // Previsão de movimento da caneta: o traço "acompanha" a ponta com menos atraso.
    implementation("androidx.input:input-motionprediction:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Salvamento automático em segundo plano (Google Drive)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Login/permissão do Google para o Drive
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reconhecimento da letra (busca), roda no próprio tablet
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    testImplementation("junit:junit:4.13.2")
    // Android "simulado" para testar as telas sem um aparelho
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
