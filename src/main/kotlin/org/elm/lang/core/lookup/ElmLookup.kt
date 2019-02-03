package org.elm.lang.core.lookup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.stubs.index.ElmNamedElementIndex
import org.elm.lang.core.types.moduleName
import org.elm.openapiext.findFileByPathTestAware
import org.elm.workspace.ElmPackageProject
import org.elm.workspace.ElmProject
import org.elm.workspace.elmWorkspace

/**
 * Like [ElmNamedElementIndex] but takes context into account. Given that a single
 * IntelliJ project can hold multiple Elm projects, it is important that we take into
 * account the caller's Elm project structure when looking for things by name.
 */
object ElmLookup {

    val log = logger<ElmLookup>()

    private val projectCacheKey =
            Key.create<CachedValue<GlobalSearchScope>>("ELM_PROJECT_SEARCH_SCOPE_KEY")

    private val projectAndLibrariesCacheKey =
            Key.create<CachedValue<GlobalSearchScope>>("ELM_PROJECT_SEARCH_SCOPE_KEY_WITH_TESTS")


    /** Find the named element with [name] which is visible to [clientLocation] */
    inline fun <reified T : ElmNamedElement> findByName(
            name: String,
            clientLocation: ClientLocation
    ): List<T> {
        if (clientLocation.elmProject == null) {
            if (log.isDebugEnabled) log.debug("Cannot lookup '$name' when Elm project context is unknown")
            return emptyList()
        }
        return ElmNamedElementIndex.find(name, clientLocation.intellijProject, ElmLookup.searchScopeAt(clientLocation))
                .filterIsInstance<T>()
    }


    /** Find the named element with [name] declared in [module] and visible to [clientLocation] */
    inline fun <reified T : ElmNamedElement> findByNameAndModule(
            name: String,
            module: String,
            clientLocation: ClientLocation
    ): T? =
            findByName<T>(name, clientLocation)
                    .find { it.moduleName == module }


    /**
     * Returns a [GlobalSearchScope] which includes all Elm files that belong to [elmProject]
     * taking into consideration:
     *
     *  - which source roots the [ElmProject] defines
     *  - which packages are visible from the client location's [ElmProject]
     *  - whether "test" code (and dependencies) should be included
     */
    fun searchScopeAt(loc: ClientLocation): GlobalSearchScope {
        val elmProject = loc.elmProject ?: return GlobalSearchScope.EMPTY_SCOPE

        /*
        Build (and cache) the search scope based on the ClientLocation.

        The cache will be invalidated whenever the Elm workspace changes
        (e.g. the user modifies an `elm.json` file)

        You might be wondering why we don't just construct the search scope when
        initializing the ElmProject value itself and store it in a property?
        I don't want to do that because it would start to bring things like
        VirtualFile into ElmProject. VirtualFile has multi-threading restrictions
        (http://www.jetbrains.org/intellij/sdk/docs/basics/virtual_file_system.html)
        that I'd rather not worry about when loading Elm projects on a background
        thread. Maybe it'd be fine, but until I understand things better, I'm going
        to keep them separated.
        */

        // We cannot close over the ClientLocation in the CachedValueProvider, so extract everything now.
        val isInTestsDirectory = loc.isInTestsDirectory
        val project = loc.intellijProject

        val cacheKey = if (loc.isInTestsDirectory) projectAndLibrariesCacheKey else projectCacheKey
        val manager = CachedValuesManager.getManager(project)
        return manager.getCachedValue(elmProject, cacheKey, {
            val scope = buildSearchScope(isInTestsDirectory, elmProject, project)
            CachedValueProvider.Result.create(scope, project.elmWorkspace.changeTracker)
        }, false)
    }
}


private fun buildSearchScope(includeTests: Boolean, p: ElmProject, intellijProject: Project): GlobalSearchScope {
    /*
     *  NOTE: if the inputs to this function change (e.g. adding a parameter),
     *        you must take that into account when retrieving the cached value.
     */
    val (srcDirPaths, dependencies) = if (includeTests)
        Pair(p.allSourceDirs, p.allResolvedDependencies)
    else
        Pair(p.absoluteSourceDirectories.asSequence(), p.dependencies.asSequence())

    val srcDirs = srcDirPaths.mapNotNull { findFileByPathTestAware(it) }.toList()
    val srcDirScope = GlobalSearchScopesCore.directoriesScope(intellijProject, true, *(srcDirs.toTypedArray()))

    val exposedDependencyFiles = dependencies.flatMap { it.exposedFiles() }.toList()
    val dependenciesScope = GlobalSearchScope.filesWithLibrariesScope(intellijProject, exposedDependencyFiles)

    return srcDirScope.uniteWith(dependenciesScope)
}


/**
 * Return the [VirtualFile] for each Elm module which is exposed by this package.
 */
private fun ElmPackageProject.exposedFiles(): Sequence<VirtualFile> =
        exposedModules.mapNotNull { moduleName ->
            val elmModuleRelativePath = moduleName.replace('.', '/') + ".elm"
            absoluteSourceDirectories.mapNotNull { srcDirPath ->
                findFileByPathTestAware(srcDirPath.resolve(elmModuleRelativePath))
            }.firstOrNull()
        }.asSequence()