/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.kotlin.dsl.plugins.precompiled

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle

import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

import org.gradle.kotlin.dsl.*

import org.gradle.kotlin.dsl.precompile.PrecompiledInitScript
import org.gradle.kotlin.dsl.precompile.PrecompiledProjectScript
import org.gradle.kotlin.dsl.precompile.PrecompiledScriptDependenciesResolver
import org.gradle.kotlin.dsl.precompile.PrecompiledSettingsScript

import org.gradle.kotlin.dsl.provider.gradleKotlinDslJarsOf

import org.gradle.kotlin.dsl.support.ImplicitImports
import org.gradle.kotlin.dsl.support.serviceOf

import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugin.devel.plugins.JavaGradlePluginPlugin

import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


/**
 * Exposes `*.gradle.kts` scripts from regular Kotlin source-sets as binary Gradle plugins.
 *
 * ## Defining the plugin target
 *
 * Precompiled script plugins can target one of the following Gradle model types, [Gradle], [Settings] or [Project].
 *
 * The target of a given script plugin is defined via its file name suffix in the following manner:
 *  - the `.init.gradle.kts` file name suffix defines a [Gradle] script plugin
 *  - the `.settings.gradle.kts` file name suffix defines a [Settings] script plugin
 *  - and finally, the simpler `.gradle.kts` file name suffix  defines a [Project] script plugin
 *
 * ## Definining the plugin id
 *
 * The Gradle plugin id for a precompiled script plugin is defined via its file name
 * plus optional package declaration in the following manner:
 *  - for a script without a package declaration, the plugin id is simply the file name without the
 *  related plugin target suffix (see above)
 *  - for a script containing a package declaration, the plugin id is the declared package name dot the file name without the
 *  related plugin target suffix (see above)
 *
 * For a concrete example, take the definition of a precompiled [Project] script plugin id of
 * `my.project.plugin`. Given the two rules above, there are two conventional ways to do it:
 *  * by naming the script `my.project.plugin.gradle.kts` and including no package declaration
 *  * by naming the script `plugin.gradle.kts` and including a package declaration of `my.project`:
 *    ```kotlin
 *    // plugin.gradle.kts
 *    package my.project
 *
 *    // ... plugin implementation ...
 *    ```
 * ## Applying plugins
 * Precompiled script plugins can apply plugins much in the same way as regular scripts can, using one
 * of the many `apply` method overloads or, in the case of [Project] scripts, via the `plugins` block.
 *
 * And just as regular [Project] scripts can take advantage of
 * [type-safe model accessors](https://docs.gradle.org/current/userguide/kotlin_dsl.html#type-safe-accessors)
 * to model elements contributed by plugins applied via the `plugins` block, so can precompiled [Project] script plugins:
 * ```kotlin
 *
 * // java7-project.gradle.kts
 *
 * plugins {
 *     java
 * }
 *
 * java { // type-safe model accessor to the `java` extension contributed by the `java` plugin
 *     sourceCompatibility = JavaVersion.VERSION_1_7
 *     targetCompatibility = JavaVersion.VERSION_1_7
 * }
 *
 * ```
 * ## Implementation Notes
 * External plugin dependencies are declared as regular artifact dependencies but a more
 * semantic preserving model could be introduced in the future.
 * ## Todo
 *  - [ ] type-safe plugin spec accessors for plugins in the precompiled script plugin classpath
 *  - [ ] limit the set of type-safe accessors visible to a precompiled script plugin to
 *        those provided by the plugins in its `plugins` block
 *        - [ ] each set of accessors would be emitted to an internal object to avoid conflicts with
 *              external plugins
 *        - [ ] an internal object named against its precompiled script plugin would also let users
 *              know not to import them
 *  - [ ] emit error when a precompiled script plugin includes the version of a in its `plugins` block
 *  - [ ] validate plugin ids against declared plugin dependencies
 */
class PrecompiledScriptPlugins : Plugin<Project> {

    override fun apply(project: Project): Unit = project.run {

        enableScriptCompilation()

        plugins.withType<JavaGradlePluginPlugin> {
            exposeScriptsAsGradlePlugins()
        }
    }
}


private
fun Project.enableScriptCompilation() {

    dependencies {
        "kotlinCompilerPluginClasspath"(gradleKotlinDslJarsOf(project))
        "kotlinCompilerPluginClasspath"(gradleApi())
    }

    tasks.named<KotlinCompile>("compileKotlin") {
        kotlinOptions {
            freeCompilerArgs += listOf(
                "-script-templates", scriptTemplates,
                // Propagate implicit imports and other settings
                "-Xscript-resolver-environment=${resolverEnvironment()}"
            )
        }
    }
}


private
val scriptTemplates by lazy {
    listOf(
        // treat *.settings.gradle.kts files as Settings scripts
        PrecompiledSettingsScript::class.qualifiedName!!,
        // treat *.init.gradle.kts files as Gradle scripts
        PrecompiledInitScript::class.qualifiedName!!,
        // treat *.gradle.kts files as Project scripts
        PrecompiledProjectScript::class.qualifiedName!!
    ).joinToString(separator = ",")
}


private
fun Project.resolverEnvironment() =
    (PrecompiledScriptDependenciesResolver.EnvironmentProperties.kotlinDslImplicitImports
        + "=\"" + implicitImports().joinToString(separator = ":") + "\"")


private
fun Project.implicitImports(): List<String> =
    serviceOf<ImplicitImports>().list


private
fun Project.exposeScriptsAsGradlePlugins() {

    val scriptSourceFiles = pluginSourceSet.allSource.matching {
        it.include("**/*.gradle.kts")
    }

    val scriptPlugins =
        scriptSourceFiles.map(::ScriptPlugin)

    declareScriptPlugins(scriptPlugins)

    generatePluginAdaptersFor(scriptPlugins)
}


private
val Project.pluginSourceSet
    get() = gradlePlugin.pluginSourceSet


private
val Project.gradlePlugin
    get() = the<GradlePluginDevelopmentExtension>()


private
fun Project.declareScriptPlugins(scriptPlugins: List<ScriptPlugin>) {

    configure<GradlePluginDevelopmentExtension> {
        for (scriptPlugin in scriptPlugins) {
            plugins.create(scriptPlugin.id) {
                it.id = scriptPlugin.id
                it.implementationClass = scriptPlugin.implementationClass
            }
        }
    }
}


private
fun Project.generatePluginAdaptersFor(scriptPlugins: List<ScriptPlugin>) {

    val generatedSourcesDir = layout.buildDirectory.dir("generated-sources/kotlin-dsl-plugins/kotlin")
    sourceSets["main"].kotlin.srcDir(generatedSourcesDir)

    val generateScriptPluginAdapters by tasks.registering(GenerateScriptPluginAdapters::class) {
        plugins = scriptPlugins
        outputDirectory.set(generatedSourcesDir)
    }

    tasks.named("compileKotlin") {
        it.dependsOn(generateScriptPluginAdapters)
    }
}


private
val Project.sourceSets
    get() = project.the<SourceSetContainer>()


private
val SourceSet.kotlin: SourceDirectorySet
    get() = withConvention(KotlinSourceSet::class) { kotlin }
