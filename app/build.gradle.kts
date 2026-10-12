plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão do app: aumentar a cada PR que muda o app (o release sai no merge).
// O versionCode segue a fórmula do Briefcase, para continuar acima da 1.0.x:
// X.Y.Z -> X*1000000 + Y*10000 + Z*100 (1.0.15 -> 1001500, 2.0.0 -> 2000000).
val versao = "2.1.0"
val codigoDaVersao = versao.split(".").map(String::toInt).let { (x, y, z) ->
    x * 1_000_000 + y * 10_000 + z * 100
}

// Chave de assinatura: a mesma do app Python (alias "ponto"). No CI vem dos
// segredos por PONTO_KEYSTORE e PONTO_KEYSTORE_PASS; no PC, do arquivo padrão.
val chave = file(
    System.getenv("PONTO_KEYSTORE")
        ?: "${System.getProperty("user.home")}/.config/ponto/ponto-release.jks"
)
val senhaDaChave: String? = System.getenv("PONTO_KEYSTORE_PASS")

android {
    namespace = "io.github.fabiolimath.ponto"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.fabiolimath.ponto"
        minSdk = 24
        // 35, e não 36: no Android 16 o app 36 é obrigado a desenhar sob a barra de
        // status (edge-to-edge); com 35 o tema ainda pode recusar (values-v35).
        targetSdk = 35
        versionCode = codigoDaVersao
        versionName = versao
    }

    signingConfigs {
        if (chave.isFile && senhaDaChave != null) {
            create("ponto") {
                storeFile = chave
                storePassword = senhaDaChave
                keyAlias = "ponto"
                keyPassword = senhaDaChave
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("ponto")
        }
        // Build local pelo adb: pacote próprio, para não esbarrar na assinatura
        // do app instalado.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (usado pelo módulo sigrh) no Android 7 (API 24 e 25).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sigrh"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // A abertura automática ao conectar na Wi-Fi (trabalho expedito, com o app fechado).
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Testes das classes sem Android (pacote nucleo), na JVM.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
    // org.json de verdade: o do android.jar, nos testes da JVM, só tem métodos vazios.
    testImplementation("org.json:json:20250517")
}
