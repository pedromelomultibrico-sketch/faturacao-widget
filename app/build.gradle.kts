plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.multibrico.faturacao"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.multibrico.faturacao"
        minSdk = 26
        targetSdk = 34
        // o número da compilação do GitHub (sobe sempre): é o que a app usa para saber se há versão nova
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.$versionCode"
    }

    // Assinatura fixa: todas as versões são assinadas com a mesma chave,
    // por isso uma atualização instala-se por cima da anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
