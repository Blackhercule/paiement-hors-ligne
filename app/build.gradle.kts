plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "bf.pegasus.paiement"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "bf.pegasus.paiement"
        minSdk = 26 // Android 8.0 : startLocalOnlyHotspot
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Empreinte SHA-256 du certificat officiel (clé de démonstration app/cle-demo.p12).
        // Au démarrage, l'application compare sa propre signature à cette valeur.
        buildConfigField(
            "String",
            "EMPREINTE_OFFICIELLE",
            "\"7F700E4D2178AEDD1D0ADD8B1E04480AEEB6603DAE0610E6D606F955CE637130\""
        )
    }

    signingConfigs {
        // Clé de démonstration, publique pour que le jury puisse compiler l'application.
        // En production, la clé officielle reste secrète et n'est jamais dans le dépôt.
        create("demo") {
            storeFile = file("cle-demo.p12")
            storeType = "pkcs12"
            storePassword = "pegasus-demo"
            keyAlias = "pegasus"
            keyPassword = "pegasus-demo"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            isMinifyEnabled = true // obfuscation R8
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("demo")
        }
        // Démonstration : la même application signée avec une AUTRE clé.
        // Elle doit afficher « Application falsifiée ».
        create("falsifiee") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".falsifiee"
            versionNameSuffix = "-falsifiee"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
