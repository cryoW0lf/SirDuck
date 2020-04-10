plugins {
    java
}

group = "pw.cryow0lf"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.discord4j" ,"discord4j-core", "3.0.+")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_12
}