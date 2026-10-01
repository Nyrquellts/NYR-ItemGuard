// NYR Item Guard build: shared conventions, then the plugin's shaded jar, its jar test, its API linkage check and its sale
// download.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    alias(libs.plugins.shadow) apply false
}

/**
 * The sellable plugin: its Gradle project (the directory), plugin name, main class and the file name buyers download. The
 * project and package keep the plugin's first name, Illegal Items; it is sold as NYR Item Guard.
 */
data class Fix(val id: String, val pluginName: String, val mainClass: String, val fileName: String)

val fixes: List<Fix> = listOf(
    Fix("illegal", "NYR-ItemGuard", "com.nyr.fixes.illegal.IllegalItemsPlugin", "NYR-ItemGuard")
)

/** Where the live testbed's servers unpacked their API jars: testbed/run once its matrix has run, or -Pnyr.fixes.serverRun=DIR. */
val serverRun: File = providers.gradleProperty("nyr.fixes.serverRun").map { File(it) }.orNull ?: file("testbed/run")

allprojects {
    group = "com.nyr.fixes"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.tcoded.com/releases") { name = "tcoded" }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(rootProject.libs.versions.java.get()))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(rootProject.libs.versions.java.get().toInt())
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    }

    tasks.named<JavaCompile>("compileTestJava") {
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }

    dependencies {
        // The plugin compiles against Paper 1.21.1's API. Everything newer than 1.20.6 is reached through reflection, and
        // the live matrix runs the jar on 1.20.6 through 26.2.
        "compileOnly"(rootProject.libs.paper.api)
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
        "testImplementation"(rootProject.libs.mockbukkit)
        "testImplementation"(rootProject.libs.paper.api)
    }

    // Paper API's transitive gson, slf4j and error_prone versions are pinned, so every build resolves the same ones. None of
    // them ships in the plugin jar.
    val aligned = mapOf(
        "com.google.code.gson:gson" to rootProject.libs.versions.gson.get(),
        "org.slf4j:slf4j-api" to rootProject.libs.versions.slf4j.get(),
        "com.google.errorprone:error_prone_annotations" to "2.27.0"
    )
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            aligned["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // MockBukkit throws UnimplementedOperationException, a JUnit TestAbortedException, for calls it cannot run: the test
        // is marked skipped and Gradle still passes. A skipped test proved nothing, so it fails the task here.
        val results = reports.junitXml.outputLocation
        doLast {
            val skipped = results.get().asFile.walk().filter { it.isFile && it.name.endsWith(".xml") }
                .flatMap { file -> Regex("""<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<skipped""").findAll(file.readText()).map { "${it.groupValues[2]}.${it.groupValues[1]}" } }
                .toList()
            if (skipped.isNotEmpty()) {
                throw GradleException("skipped tests prove nothing; make them run or remove them: " + skipped.joinToString(", "))
            }
        }
    }
}

val saleZips = tasks.register("saleZips") {
    group = "distribution"
    description = "Builds the sale download, once the jar has passed its jar test and API linkage check."
}

for (fix in fixes) {
    project(":${fix.id}") {
        apply(plugin = "com.gradleup.shadow")

        dependencies {
            "implementation"(project(":common"))
        }

        val sourceSets = extensions.getByType<SourceSetContainer>()
        val main = sourceSets.named("main")
        val test = sourceSets.named("test")
        val commonProject = project(":common")

        tasks.named<ProcessResources>("processResources") {
            val props = mapOf("version" to project.version.toString())
            inputs.properties(props)
            filesMatching("plugin.yml") { expand(props) }
        }

        // The jar is also the sale jar: nothing in it is GPL. FoliaLib (MIT) and :common are relocated under the fix's own
        // package, because plugin class loaders share classes by name and two NYR fixes would otherwise share statics.
        val shadowJar = tasks.named<ShadowJar>("shadowJar") {
            archiveBaseName.set(fix.fileName)
            archiveClassifier.set("")
            archiveVersion.set(project.version.toString())
            val base = "com.nyr.fixes.${fix.id}.lib"
            relocate("com.nyr.fixes.common", "$base.common")
            relocate("com.tcoded.folialib", "$base.folialib")
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**", "module-info.class", "META-INF/versions/*/module-info.class")
        }
        tasks.named("jar") { enabled = false }
        tasks.named("assemble") { dependsOn(shadowJar) }

        val verifyJar = tasks.register("verifyJar") {
            group = "verification"
            description = "Checks ${fix.fileName}'s jar: descriptor, bundled config, and that everything shared is relocated."
            val jar = shadowJar.flatMap { it.archiveFile }
            val version = project.version.toString()
            val id = fix.id
            val pluginName = fix.pluginName
            val mainClass = fix.mainClass
            inputs.file(jar).withPropertyName("jar")
            doLast {
                val file: File = jar.get().asFile
                ZipFile(file).use { zip ->
                    val names: List<String> = zip.entries().toList().map { it.name }
                    val problems = mutableListOf<String>()
                    val descriptorEntry = zip.getEntry("plugin.yml")
                    val descriptor = if (descriptorEntry == null) "" else String(zip.getInputStream(descriptorEntry).readAllBytes(), Charsets.UTF_8)
                    if (!descriptor.contains("name: $pluginName\n") && !descriptor.contains("name: $pluginName\r\n")) problems.add("plugin.yml does not name $pluginName")
                    if (!descriptor.contains("main: $mainClass")) problems.add("plugin.yml does not start $mainClass")
                    if (!descriptor.contains("version: '$version'")) problems.add("plugin.yml does not carry version $version")
                    if (!descriptor.contains("folia-supported: true")) problems.add("plugin.yml does not declare folia-supported")
                    for (needed in listOf("${pluginName.lowercase()}/config.yml", mainClass.replace('.', '/') + ".class",
                            "com/nyr/fixes/$id/lib/common/FixPlugin.class", "com/nyr/fixes/$id/lib/folialib/FoliaLib.class")) {
                        if (!names.contains(needed)) problems.add("missing $needed")
                    }
                    for (prefix in listOf("com/nyr/fixes/common/", "com/tcoded/")) {
                        val stray = names.count { it.startsWith(prefix) && it.endsWith(".class") }
                        if (stray > 0) problems.add("$stray unrelocated class(es) under $prefix")
                    }
                    if (names.contains("config.yml")) problems.add("carries a root config.yml another plugin could read")
                    if (problems.isNotEmpty()) throw GradleException("${file.name}:\n  " + problems.joinToString("\n  "))
                    logger.lifecycle("verified ${file.name}: ${names.size} entries, ${file.length() / 1024} KiB")
                }
            }
        }
        shadowJar.configure { finalizedBy(verifyJar) }

        // The unit tests run on compiled classes; jar tests (package <fix>.jar) run only here, against the built jar, with
        // the fix's own classes and :common kept off the class path so the plugin class loader must read the jar.
        tasks.named<Test>("test") {
            filter { excludeTestsMatching("com.nyr.fixes.${fix.id}.jar.*") }
        }
        val jarTest = tasks.register<Test>("jarTest") {
            group = "verification"
            description = "Loads ${fix.fileName}'s built jar into an in-memory server and plays the fix through it."
            val jarFile = shadowJar.flatMap { it.archiveFile }
            inputs.file(jarFile).withPropertyName("jar")
            testClassesDirs = test.get().output.classesDirs
            val excluded = main.get().output.plus(commonProject.extensions.getByType<SourceSetContainer>().named("main").get().output)
            classpath = test.get().output + configurations.getByName("testRuntimeClasspath").filter { file ->
                !excluded.files.contains(file) && !file.name.startsWith("common") && !file.name.startsWith("FoliaLib")
            }
            filter { includeTestsMatching("com.nyr.fixes.${fix.id}.jar.*") }
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dnyr.fixes.jar=" + jarFile.get().asFile.absolutePath) })
            shouldRunAfter(tasks.named("test"))
        }
        tasks.named("check") { dependsOn(jarTest) }

        // Every server API call in the jar must link on each server API a buyer may run: Paper 1.20.6 to 26.2 and Spigot.
        // The API jars are the ones the live testbed's servers unpacked (testbed/run/<server>/libraries); running the matrix
        // once provides them, -Pnyr.fixes.serverRun=DIR names another run directory and -Pnyr.fixes.apiJars=a.jar;b.jar the
        // jars themselves. Without them `check` warns and skips this check, and no sale download is built.
        val linkageTool = configurations.create("linkageTool")
        dependencies { linkageTool(project(":linkage")) }
        val apiJarList: List<String> = (providers.gradleProperty("nyr.fixes.apiJars").orNull?.split(';')?.filter { it.isNotBlank() }
            ?: listOf(
                "paper-1.20.6/libraries/io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-SNAPSHOT.jar",
                "paper-1.21.11/libraries/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar",
                "paper-26.1.2/libraries/io/papermc/paper/paper-api/26.1.2.build.74-stable/paper-api-26.1.2.build.74-stable.jar",
                "paper-26.2/libraries/io/papermc/paper/paper-api/26.2.build.123-stable/paper-api-26.2.build.123-stable.jar",
                "spigot-1.21.11/bundler/libraries/spigot-api-1.21.11-R0.2-SNAPSHOT.jar"
            ).map { File(serverRun, it).absolutePath })
        val apiLinkage = tasks.register<JavaExec>("apiLinkage") {
            group = "verification"
            description = "Checks that every server API call in ${fix.fileName}'s jar links on Paper 1.20.6 to 26.2 and Spigot."
            val jarFile = shadowJar.flatMap { it.archiveFile }
            val apiJars = apiJarList
            inputs.file(jarFile).withPropertyName("jar")
            inputs.files(apiJars).withPropertyName("apiJars").optional()
            classpath = linkageTool
            mainClass.set("com.nyr.fixes.linkage.ApiLinkage")
            argumentProviders.add(CommandLineArgumentProvider {
                listOf(jarFile.get().asFile.absolutePath, "com/nyr/fixes/${fix.id}/lib/folialib/") + apiJars
            })
            doFirst {
                val missing = apiJars.filter { !File(it).isFile }
                if (missing.isNotEmpty()) {
                    logger.warn("apiLinkage skipped: no server API jar at " + missing.joinToString(", ") + ". Run the live matrix once " +
                        "(testbed/matrix.mjs), or pass -Pnyr.fixes.serverRun=DIR or -Pnyr.fixes.apiJars=a.jar;b.jar")
                    throw StopExecutionException()
                }
            }
        }
        tasks.named("check") { dependsOn(apiLinkage) }

        val version = project.version.toString()
        val saleZip = tasks.register<Zip>("saleZip") {
            group = "distribution"
            description = "Packs ${fix.fileName}'s sale download, once its jar has passed its jar test."
            archiveBaseName.set(fix.fileName)
            archiveVersion.set(version)
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            dependsOn(jarTest, apiLinkage)
            val apiJars = apiJarList
            doFirst {
                val missing = apiJars.filter { !File(it).isFile }
                if (missing.isNotEmpty()) throw GradleException("a sale download needs the API linkage check: no server API jar at " + missing.joinToString(", "))
            }
            into("${fix.fileName}-$version") {
                from(shadowJar)
                from("src/dist") {
                    filteringCharset = "UTF-8"
                    filter { line: String -> line.replace("@version@", version) }
                }
                from(rootProject.file("THIRD-PARTY-NOTICES.txt"))
                into("defaults") {
                    from("src/main/resources/${fix.pluginName.lowercase()}") { include("config.yml") }
                }
            }
        }

        // What a buyer downloads, checked as it ships: exactly these files, the jar inside it, and no file name or byte
        // naming the tools it was made with.
        val verifySaleZip = tasks.register("verifySaleZip") {
            group = "verification"
            description = "Checks ${fix.fileName}'s sale download: its files, its jar and forbidden names in every byte."
            val zipFile = saleZip.flatMap { it.archiveFile }
            val checksum = layout.buildDirectory.file("distributions/${fix.fileName}-$version.zip.sha256")
            val fileName = fix.fileName
            val pluginName = fix.pluginName
            inputs.file(zipFile).withPropertyName("saleZip")
            outputs.file(checksum).withPropertyName("checksum")
            doLast {
                val file: File = zipFile.get().asFile
                // stored encoded, so this build file does not name them either
                val forbidden = listOf("Y2xhdWRl", "YW50aHJvcGlj").map { String(Base64.getDecoder().decode(it), Charsets.US_ASCII) }
                val problems = mutableListOf<String>()
                fun mentions(what: String, bytes: ByteArray) {
                    val low = ByteArray(bytes.size) { i ->
                        val b = bytes[i].toInt()
                        if (b in 'A'.code..'Z'.code) (b + 32).toByte() else bytes[i]
                    }
                    for (word in forbidden) {
                        val needle = word.toByteArray(Charsets.US_ASCII)
                        var at = 0
                        search@ while (at <= low.size - needle.size) {
                            for (k in needle.indices) {
                                if (low[at + k] != needle[k]) {
                                    at++
                                    continue@search
                                }
                            }
                            problems.add("$what contains \"$word\" at byte $at")
                            break
                        }
                    }
                }
                val root = "$fileName-$version/"
                val jarName = "$root$fileName-$version.jar"
                val expected = setOf(jarName, "${root}README.txt", "${root}THIRD-PARTY-NOTICES.txt", "${root}defaults/config.yml")
                val files = mutableSetOf<String>()
                ZipFile(file).use { zip ->
                    for (entry in zip.entries().toList()) {
                        mentions("the name ${entry.name}", entry.name.toByteArray(Charsets.UTF_8))
                        if (entry.isDirectory) continue
                        files.add(entry.name)
                        val bytes = zip.getInputStream(entry).readAllBytes()
                        mentions(entry.name, bytes)
                        if (String(bytes, Charsets.UTF_8).contains("@version@")) problems.add("${entry.name} still says @version@")
                        if (entry.name != jarName) continue
                        val inner = ZipInputStream(ByteArrayInputStream(bytes))
                        var descriptor = ""
                        while (true) {
                            val e = inner.nextEntry ?: break
                            val content = inner.readAllBytes()
                            mentions("$jarName!/${e.name} (name)", e.name.toByteArray(Charsets.UTF_8))
                            mentions("$jarName!/${e.name}", content)
                            if (e.name == "plugin.yml") descriptor = String(content, Charsets.UTF_8)
                        }
                        if (!descriptor.contains("name: $pluginName") || !descriptor.contains("version: '$version'")) {
                            problems.add("$jarName: plugin.yml is not $pluginName $version")
                        }
                    }
                }
                if (files != expected) problems.add("files are ${files.sorted()}, expected ${expected.sorted()}")
                if (problems.isNotEmpty()) throw GradleException("${file.name}:\n  " + problems.joinToString("\n  "))
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                checksum.get().asFile.writeText("$digest *${file.name}\n")
                logger.lifecycle("verified ${file.name}: ${files.size} files, ${file.length() / 1024} KiB, sha256 $digest")
            }
        }
        saleZip.configure { finalizedBy(verifySaleZip) }
        saleZips.configure { dependsOn(saleZip) }
    }
}
