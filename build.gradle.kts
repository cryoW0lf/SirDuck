plugins {
    java
    id("com.github.johnrengelman.shadow").version("5.2.0")
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

tasks {
    withType<Jar> {
        manifest {
            attributes["Main-Class"] = "pw.cryow0lf.sirduck.SirDuck"
        }
    }
}