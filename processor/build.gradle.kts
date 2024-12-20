plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("kotlin-kapt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    kapt(project(":annotations"))
    compileOnly(project(":annotations"))

    kapt("com.google.auto.service:auto-service:1.1.1")
    implementation("com.google.auto.service:auto-service:1.1.1")
}
