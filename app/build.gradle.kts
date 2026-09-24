import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.aloeil.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "org.aloeil.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

val testSyntheticDebugUnitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the Apache-2.0-only JVM synthetic fixture test."
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
    mainClass.set("org.aloeil.app.SyntheticFixtureJvmTest")

    doFirst {
        classpath = files(
            tasks.named("compileDebugKotlin").get().outputs.files,
            tasks.named("compileDebugUnitTestKotlin").get().outputs.files,
            configurations.getByName("debugUnitTestRuntimeClasspath"),
        )
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(testSyntheticDebugUnitTest)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
