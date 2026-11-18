plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.recording"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.recording"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            // The default file keeps @JavascriptInterface methods, which the page calls by name.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // On-phone tests of the encoders, MediaStore and crash recovery (connectedAndroidTest).
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

/**
 * Generates the app's colours from design/theme.json, the single source of truth:
 * - assets/theme.css: CSS custom properties, light by default and dark under prefers-color-scheme
 * - res/values/colors.xml: fixed light_* and dark_* colours plus theme_* aliases (light)
 * - res/values-night/colors.xml: theme_* aliases (dark)
 */
abstract class GenerateThemeTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val themeFile: RegularFileProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val themes = groovy.json.JsonSlurper().parse(themeFile.get().asFile) as Map<String, Map<String, String>>
        val light = themes.getValue("Light")
        val dark = themes.getValue("Dark")
        require(light.keys == dark.keys) { "Light and dark must define the same tokens" }

        fun cssVars(tokens: Map<String, String>, indent: String) =
            tokens.entries.joinToString("\n") { (k, v) -> "$indent--$k: $v;" }
        fun xmlColor(name: String, value: String) = "    <color name=\"$name\">${value.uppercase()}</color>"
        fun write(file: java.io.File, text: String) { file.parentFile.mkdirs(); file.writeText(text) }
        val header = "Generated from design/theme.json by :app:generateTheme. Do not edit."

        write(assetsDir.get().file("theme.css").asFile, buildString {
            appendLine("/* $header */")
            // Follows the system unless the page sets data-theme="light" or "dark" on <html>.
            appendLine(":root {\n  color-scheme: light;\n${cssVars(light, "  ")}\n}")
            appendLine("@media (prefers-color-scheme: dark) {\n  :root:not([data-theme=\"light\"]) {\n    color-scheme: dark;\n${cssVars(dark, "    ")}\n  }\n}")
            appendLine(":root[data-theme=\"dark\"] {\n  color-scheme: dark;\n${cssVars(dark, "  ")}\n}")
        })

        val res = resDir.get().asFile
        write(res.resolve("values/colors.xml"), buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- $header -->\n<resources>")
            light.forEach { (k, v) -> appendLine(xmlColor("light_$k", v)) }
            dark.forEach { (k, v) -> appendLine(xmlColor("dark_$k", v)) }
            light.keys.forEach { k -> appendLine("    <color name=\"theme_$k\">@color/light_$k</color>") }
            appendLine("</resources>")
        })
        write(res.resolve("values-night/colors.xml"), buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- $header -->\n<resources>")
            dark.keys.forEach { k -> appendLine("    <color name=\"theme_$k\">@color/dark_$k</color>") }
            appendLine("</resources>")
        })
    }
}

val generateTheme = tasks.register<GenerateThemeTask>("generateTheme") {
    themeFile.set(rootProject.layout.projectDirectory.file("design/theme.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateTheme, GenerateThemeTask::assetsDir)
        variant.sources.res?.addGeneratedSourceDirectory(generateTheme, GenerateThemeTask::resDir)
    }
}

/** Runs the pure page scripts' tests (app/src/test/js) with Node's built-in runner, when Node is installed. */
val jsTest = tasks.register("jsTest") {
    val dir = layout.projectDirectory.dir("src/test/js")
    inputs.dir(dir)
    inputs.dir(layout.projectDirectory.dir("src/main/assets/js"))
    doLast {
        val files = dir.asFile.listFiles { f -> f.name.endsWith(".test.js") }.orEmpty().sorted().map { it.path }
        val node = try {
            ProcessBuilder("node", "--version").start().waitFor() == 0
        } catch (e: java.io.IOException) { false }
        if (!node) { logger.warn("jsTest: node not found, skipping the JS tests"); return@doLast }
        val p = ProcessBuilder(listOf("node", "--test") + files).inheritIO().start()
        if (p.waitFor() != 0) throw GradleException("JS tests failed")
    }
}
tasks.matching { it.name == "test" }.configureEach { dependsOn(jsTest) }
