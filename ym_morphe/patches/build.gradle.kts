patches {
    about {
        name = "ym_morphe"
        description = "Custom YouTube Music player design"
        source = "local"
        author = "ym_morphe"
        contact = "na"
        website = "local"
        license = "GNU General Public License v3.0"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.guava)
    implementation(libs.morphe.patches.library)
    compileOnly(libs.annotation)
}
