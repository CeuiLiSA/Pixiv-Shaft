plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("kotlin-kapt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    kapt(project(":annotations"))
    compileOnly(project(":annotations"))

    kapt ("com.google.auto.service:auto-service:1.0")
    implementation ("com.google.auto.service:auto-service:1.0")


}