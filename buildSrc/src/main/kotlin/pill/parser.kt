@file:Suppress("PackageDirectoryMismatch")
package org.jetbrains.kotlin.pill

import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.HasConvention
import org.jetbrains.kotlin.pill.POrderRoot.*
import org.jetbrains.kotlin.pill.PSourceRoot.*
import java.io.File
import java.util.LinkedList

data class PProject(
    val name: String,
    val rootDirectory: File,
    val modules: List<PModule>,
    val libraries: List<PLibrary>
)

data class PModule(
    val name: String,
    val bundleName: String,
    val rootDirectory: File,
    val contentRoots: List<PContentRoot>,
    val orderRoots: List<POrderRoot>,
    val moduleForProductionSources: PModule? = null,
    val group: String? = null
) {
    val moduleFile: File
        get() = File(rootDirectory, "$name.iml")
}

data class PContentRoot(
    val path: File,
    val forTests: Boolean,
    val sourceRoots: List<PSourceRoot>,
    val excludedDirectories: List<File>
)

data class PSourceRoot(
    val path: File,
    val kind: Kind
) {
    enum class Kind {
        PRODUCTION, TEST, RESOURCES, TEST_RESOURCES;

        val isResources get() = this == RESOURCES || this == TEST_RESOURCES
    }
}

data class POrderRoot(
    val dependency: PDependency,
    val scope: Scope,
    val isExported: Boolean = false,
    val isProductionOnTestDependency: Boolean = false
) {
    enum class Scope { COMPILE, TEST, RUNTIME, PROVIDED }
}

sealed class PDependency {
    data class Module(val name: String) : PDependency()
    data class Library(val name: String) : PDependency()
    data class ModuleLibrary(val library: PLibrary) : PDependency()
}

data class PLibrary(
    val name: String,
    val classes: List<File>,
    val javadoc: List<File> = emptyList(),
    val sources: List<File> = emptyList(),
    val jarDirectories: List<File> = emptyList(),
    val annotations: List<File> = emptyList(),
    val dependencies: List<PLibrary> = emptyList()
) {
    fun attachSource(file: File): PLibrary {
        return this.copy(sources = this.sources + listOf(file))
    }
}

fun parse(project: Project, context: ParserContext): PProject = with (context) {
    val modules = project.allprojects
        .filter { it.plugins.hasPlugin(JpsCompatiblePlugin::class.java) }
        .flatMap { parseModules(it) }

    return PProject("Kotlin", project.rootProject.projectDir, modules, emptyList())
}

private val CONFIGURATION_MAPPING = mapOf(
    listOf("compile") to Scope.COMPILE,
    listOf("compileOnly") to Scope.PROVIDED,
    listOf("runtime") to Scope.RUNTIME
)

private val TEST_CONFIGURATION_MAPPING = mapOf(
    listOf("compile", "testCompile") to Scope.COMPILE,
    listOf("compileOnly", "testCompileOnly") to Scope.PROVIDED,
    listOf("runtime", "testRuntime") to Scope.RUNTIME
)

private val SOURCE_SET_MAPPING = mapOf(
    "main" to Kind.PRODUCTION,
    "test" to Kind.TEST
)

private fun ParserContext.parseModules(project: Project): List<PModule> {
    val (productionContentRoots, testContentRoots) = parseContentRoots(project).partition { !it.forTests }

    val modules = mutableListOf<PModule>()

    fun getJavaExcludedDirs() = project.plugins.findPlugin(IdeaPlugin::class.java)
        ?.model?.module?.excludeDirs?.toList() ?: emptyList()

    val allExcludedDirs = getJavaExcludedDirs() + project.buildDir

    var productionSourcesModule: PModule? = null

    for ((nameSuffix, roots) in mapOf("src" to productionContentRoots, "test" to testContentRoots)) {
        if (roots.isEmpty()) {
            continue
        }

        val mainRoot = roots.first()

        var dependencies = parseDependencies(project, mainRoot.forTests)
        if (productionContentRoots.isNotEmpty() && mainRoot.forTests) {
            val productionModuleDependency = PDependency.Module(project.name + ".src")
            dependencies += POrderRoot(productionModuleDependency, Scope.COMPILE, true)
        }

        val module = PModule(
            project.name + "." + nameSuffix,
            project.name,
            mainRoot.path,
            roots,
            dependencies,
            productionSourcesModule
        )

        modules += module

        if (!mainRoot.forTests) {
            productionSourcesModule = module
        }
    }

    modules += PModule(
        project.name,
        project.name,
        project.projectDir,
        listOf(PContentRoot(project.projectDir, false, emptyList(), allExcludedDirs)),
        if (modules.isEmpty()) parseDependencies(project, false) else emptyList()
    )

    return modules
}

private fun parseContentRoots(project: Project): List<PContentRoot> {
    val sourceRoots = parseSourceRoots(project).groupBy { it.kind }
    fun getRoots(kind: PSourceRoot.Kind) = sourceRoots[kind] ?: emptyList()

    val productionSourceRoots = getRoots(Kind.PRODUCTION) + getRoots(Kind.RESOURCES)
    val testSourceRoots = getRoots(Kind.TEST) + getRoots(Kind.TEST_RESOURCES)

    fun createContentRoots(sourceRoots: List<PSourceRoot>, forTests: Boolean): List<PContentRoot> {
        return sourceRoots.map { PContentRoot(it.path, forTests, listOf(it), emptyList()) }
    }

    return createContentRoots(productionSourceRoots, forTests = false) +
            createContentRoots(testSourceRoots, forTests = true)
}

private fun parseSourceRoots(project: Project): List<PSourceRoot> {
    if (!project.plugins.hasPlugin(JavaPlugin::class.java)) {
        return emptyList()
    }

    val sourceRoots = mutableListOf<PSourceRoot>()

    project.configure<JavaPluginConvention> {
        for ((sourceSetName, kind) in SOURCE_SET_MAPPING) {
            val sourceSet = sourceSets.findByName(sourceSetName) ?: continue

            fun Any.getKotlin(): SourceDirectorySet {
                val kotlinMethod = javaClass.getMethod("getKotlin")
                val oldIsAccessible = kotlinMethod.isAccessible
                try {
                    kotlinMethod.isAccessible = true
                    return kotlinMethod(this) as SourceDirectorySet
                } finally {
                    kotlinMethod.isAccessible = oldIsAccessible
                }
            }

            val kotlinSourceDirectories = (sourceSet as HasConvention).convention.plugins
                    .getValue("kotlin").getKotlin().srcDirs

            val directories = (sourceSet.java.sourceDirectories.files + kotlinSourceDirectories).toList()
                .filter { it.exists() }
                .takeIf { it.isNotEmpty() }
                    ?: continue

            for (resourceRoot in sourceSet.resources.sourceDirectories.files) {
                if (!resourceRoot.exists() || resourceRoot in directories) {
                    continue
                }

                val resourceRootKind = when (kind) {
                    Kind.PRODUCTION -> Kind.RESOURCES
                    Kind.TEST -> Kind.TEST_RESOURCES
                    else -> error("Invalid source root kind $kind")
                }

                sourceRoots += PSourceRoot(resourceRoot, resourceRootKind)
            }

            for (directory in directories) {
                sourceRoots += PSourceRoot(directory, kind)
            }
        }
    }

    return sourceRoots
}

private fun ParserContext.parseDependencies(project: Project, forTests: Boolean): List<POrderRoot> {
    val configurationMapping = if (forTests) TEST_CONFIGURATION_MAPPING else CONFIGURATION_MAPPING

    with(project.configurations) {
        val moduleRoots = mutableListOf<POrderRoot>()
        val libraryRoots = mutableListOf<POrderRoot>()
        val deferredRoots = mutableListOf<POrderRoot>()

        fun collectConfigurations(): List<Pair<ResolvedConfiguration, Scope>> {
            val configurations = mutableListOf<Pair<ResolvedConfiguration, Scope>>()

            for ((configurationNames, scope) in configurationMapping) {
                for (configurationName in configurationNames) {
                    val configuration = findByName(configurationName)?.also { it.resolve() } ?: continue
                    configurations += Pair(configuration.resolvedConfiguration, scope)
                }
            }

            return configurations
        }

        nextDependency@ for ((configuration, scope, dependency) in collectConfigurations().collectDependencies()) {
            for (mapper in dependencyMappers) {
                if (dependency.moduleGroup == mapper.group
                    && dependency.moduleName == mapper.module
                    && dependency.configuration in mapper.configurations
                ) {
                    val mappedDependency = mapper.mapping(dependency)

                    if (mappedDependency != null) {
                        val orderRoot = POrderRoot(mappedDependency.main, scope)
                        if (mappedDependency.main is PDependency.Module) {
                            moduleRoots += orderRoot
                        } else {
                            libraryRoots += orderRoot
                        }

                        for (deferredDep in mappedDependency.deferred) {
                            deferredRoots += POrderRoot(deferredDep, scope)
                        }
                    }

                    continue@nextDependency
                }
            }

            if (dependency.configuration == "runtimeElements") {
                moduleRoots += POrderRoot(PDependency.Module(dependency.moduleName + ".src"), scope)
            } else if (dependency.configuration == "tests-jar") {
                moduleRoots += POrderRoot(
                    PDependency.Module(dependency.moduleName + ".test"),
                    scope,
                    isProductionOnTestDependency = true
                )
            } else {
                val classes = dependency.moduleArtifacts.map { it.file }
                val library = PLibrary(dependency.moduleName, classes)
                libraryRoots += POrderRoot(PDependency.ModuleLibrary(library), scope)
            }
        }

        return removeDuplicates(moduleRoots + libraryRoots + deferredRoots)
    }
}

private fun removeDuplicates(roots: List<POrderRoot>): List<POrderRoot> {
    val dependenciesByScope = roots.groupBy { it.scope }.mapValues { it.value.mapTo(mutableSetOf()) { it.dependency } }
    fun depenciesFor(scope: Scope) = dependenciesByScope[scope] ?: emptySet<PDependency>()

    val result = mutableSetOf<POrderRoot>()
    for (root in roots.distinct()) {
        val scope = root.scope
        val dependency = root.dependency

        if (root in result) {
            continue
        }

        if ((scope == Scope.PROVIDED || scope == Scope.RUNTIME) && dependency in depenciesFor(Scope.COMPILE)) {
            continue
        }

        if (scope == Scope.PROVIDED && dependency in depenciesFor(Scope.RUNTIME)) {
            result += POrderRoot(dependency, Scope.COMPILE)
            continue
        }

        if (scope == Scope.RUNTIME && dependency in depenciesFor(Scope.PROVIDED)) {
            result += POrderRoot(dependency, Scope.COMPILE)
            continue
        }

        result += root
    }

    return result.toList()
}

private data class DependencyInfo(val configuration: ResolvedConfiguration, val scope: Scope, val dependency: ResolvedDependency)

private fun List<Pair<ResolvedConfiguration, Scope>>.collectDependencies(): List<DependencyInfo> {
    val dependencies = mutableListOf<DependencyInfo>()
    val unprocessed = LinkedList<DependencyInfo>()

    for ((configuration, scope) in this) {
        for (dependency in configuration.firstLevelModuleDependencies) {
            unprocessed += DependencyInfo(configuration, scope, dependency)
        }
    }

    while (unprocessed.isNotEmpty()) {
        val info = unprocessed.removeAt(0)
        dependencies += info

        for (child in info.dependency.children) {
            unprocessed += DependencyInfo(info.configuration, info.scope, child)
        }
    }

    return dependencies
}