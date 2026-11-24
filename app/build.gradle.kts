plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/** مقدار یک Property گریدل (-Pname) یا متغیر محیطی؛ null اگر هیچ‌کدام نباشد یا خالی باشد. */
fun propOrEnv(propName: String, envName: String): String? =
    providers.gradleProperty(propName).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "app.solina.refuge"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.solina.refuge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // اثر انگشت SHA-256 گواهی امضای انتشار (مقدار عمومی؛ در assetlinks.json هم هست).
        // فقط برای بررسی یکپارچگی در بیلد release استفاده می‌شود؛ بیلد debug این بررسی را کاملاً رد می‌کند.
        buildConfigField("String", "RELEASE_SIGNING_SHA256", "\"03B600658806A03D5F56B610E6C9BA51E62DD292C09B530E96C8E3AA8A139CD7\"")
    }

    // امضای انتشار — فقط کد، هیچ رازی اینجا نیست. هر چهار مقدار باید از Propertyهای
    // گریدل (-Psolina.keystore.*) یا متغیرهای محیطی (SOLINA_KEYSTORE_*) برسند؛
    // اگر حتی یکی نباشد، release مثل امروز بدون امضا ساخته می‌شود و CI سبز می‌ماند.
    // مرحلهٔ CI که keystore را از Secret گیت‌هاب باز می‌کند در .github/workflows/build_apk.yml است.
    signingConfigs {
        val keystoreFile = propOrEnv("solina.keystore.file", "SOLINA_KEYSTORE_FILE")
        val keystorePassword = propOrEnv("solina.keystore.password", "SOLINA_KEYSTORE_PASSWORD")
        val keyAlias = propOrEnv("solina.key.alias", "SOLINA_KEY_ALIAS")
        val keyPassword = propOrEnv("solina.key.password", "SOLINA_KEY_PASSWORD")
        if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // فقط وقتی هر چهار مقدار امضا رسیده باشد، با همان امضا می‌سازد؛
            // وگرنه (مثل CI بدون Secret) release بدون امضا ساخته می‌شود.
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // خطاهای lint بیلد را می‌شکنند. هشدار «نسخهٔ جدیدتر» را Dependabot پوشش می‌دهد.
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    dependenciesInfo {
        // حذف متادیتای امضا‌شدهٔ گوگل از APK/AAB برای ساخت بازتولیدپذیر (Reproducible Builds)
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    // Argon2id (فقط کلاس‌های مستقیم، بدون ثبت Provider در سیستم)
    implementation(libs.bouncycastle.bcprov)

    // BiometricPrompt (نیازمند FragmentActivity که از این کتابخانه می‌آید)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // QR: ZXing (Apache-2.0، جاوای خالص، بدون سرویس گوگل) + CameraX برای دوربین
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // BEGIN TRACK C — اسکن کارت بانکی (فقط این بلوک به Track C تعلق دارد)
    // ML Kit Text Recognition، مدل همراه (bundled): تشخیص متن کاملاً روی دستگاه،
    // بدون دانلود مدل و بدون هیچ فراخوانی شبکه؛ عکس کارت هرگز ذخیره/ارسال نمی‌شود.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // END TRACK C

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
