package com.github.b3kt.arthasbridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope

/** Opens classes named by the target JVM (JVM names, possibly proxies or lambdas) in the editor. */
internal object SourceNavigation {

    /** Opens [className] at [line], or at [method], or at the class. False when the project and its libraries don't have it. */
    fun open(project: Project, className: String, method: String? = null, line: Int = 0): Boolean {
        val psiClass = find(project, className, GlobalSearchScope.allScope(project)) ?: return false
        if (line > 0) {
            val file = psiClass.navigationElement.containingFile?.virtualFile
            if (file != null) {
                OpenFileDescriptor(project, file, line - 1, 0).navigate(true)
                return true
            }
        }
        val target = method?.let { psiClass.findMethodsByName(it, false).firstOrNull() }
        (target ?: psiClass).navigate(true)
        return true
    }

    /** Classes declared in the project's own sources, for highlighting "your" frames. Safe off the EDT. */
    fun projectClasses(project: Project, classNames: Collection<String>): Set<String> {
        if (DumbService.isDumb(project)) return emptySet()
        val scope = GlobalSearchScope.projectScope(project)
        return ApplicationManager.getApplication().runReadAction(Computable {
            classNames.toSet().filterTo(HashSet()) { find(project, it, scope) != null }
        })
    }

    fun isProjectClass(project: Project, className: String) = className in projectClasses(project, listOf(className))

    private fun find(project: Project, className: String, scope: GlobalSearchScope): PsiClass? {
        if (DumbService.isDumb(project) || className.isBlank() || className.contains('*')) return null
        val facade = JavaPsiFacade.getInstance(project)
        // CGLIB proxies (Foo$$EnhancerBySpringCGLIB$$1) and lambdas/anonymous classes (Foo$1) fall back to the outer class.
        val name = className.substringBefore("$$")
        return facade.findClass(name.replace('$', '.'), scope) ?: facade.findClass(name.substringBefore('$'), scope)
    }
}
