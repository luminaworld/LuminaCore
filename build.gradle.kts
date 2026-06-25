plugins {
    kotlin("jvm") version "2.3.0"
}

group = "core.luminaworld"
version = "1.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
    compileOnly("net.luckperms:api:5.4")
    
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        javaParameters.set(true)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<ProcessResources> {
    filteringCharset = "UTF-8"
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/MANIFEST.MF")
    }
}

// --- Standalone Modules Builder ---

val modulesDir = file("src/main/kotlin/core/luminaworld/modules")
val standaloneModuleList = mutableListOf<Triple<String, String, String>>() // (category, moduleName, packageName)

if (modulesDir.exists() && modulesDir.isDirectory) {
    modulesDir.listFiles()?.forEach { categoryDir ->
        if (categoryDir.isDirectory) {
            categoryDir.listFiles()?.forEach { moduleDir ->
                if (moduleDir.isDirectory) {
                    val moduleName = moduleDir.name
                    val category = categoryDir.name
                    val packageName = "core.luminaworld.modules.$category.$moduleName"
                    standaloneModuleList.add(Triple(category, moduleName, packageName))
                }
            }
        }
    }
}

val jarAllStandalone = tasks.register("jarAllStandalone") {
    group = "build-standalone"
    description = "Build all modules as standalone plugins"
}

standaloneModuleList.forEach { (category, moduleName, packageName) ->
    val taskName = "jar$moduleName"
    val standaloneJar = tasks.register<Jar>(taskName) {
        group = "build-standalone"
        description = "Build $moduleName as a standalone plugin"
        
        archiveClassifier.set("standalone-$moduleName")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        
        // คอมไพล์และแพ็คคลาสทั้งหมด ยกเว้นโมดูลย่อยตัวอื่นๆ
        from(sourceSets.main.get().output) {
            include("**")
            exclude("plugin.yml")
            standaloneModuleList.forEach { other ->
                if (other.second != moduleName) {
                    exclude("core/luminaworld/modules/${other.first}/${other.second}/**")
                }
            }
        }
        
        // แพ็ค dependencies
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
            exclude("META-INF/MANIFEST.MF")
        }
        
        // สร้าง plugin.yml และ standalone.properties เฉพาะกิจ
        val genDir = file("${layout.buildDirectory.get()}/tmp/standalone-$moduleName")
        doFirst {
            genDir.deleteRecursively()
            genDir.mkdirs()
            
            // เขียน plugin.yml
            val pluginYmlFile = File(genDir, "plugin.yml")
            pluginYmlFile.writeText("""
                name: Lumina-$moduleName
                version: ${project.version}
                main: core.luminaworld.LuminaCore
                api-version: "1.20"
                folia-supported: true
                description: Standalone plugin for LuminaCore module $moduleName
                author: Loma0531
            """.trimIndent())
            
            // เขียน standalone.properties
            val propFile = File(genDir, "standalone.properties")
            propFile.writeText("""
                module.name=$moduleName
                module.class=$packageName.${moduleName}Module
            """.trimIndent())
        }
        
        from(genDir) {
            include("plugin.yml")
            include("standalone.properties")
        }
    }
    
    jarAllStandalone.configure {
        dependsOn(standaloneJar)
    }
}
