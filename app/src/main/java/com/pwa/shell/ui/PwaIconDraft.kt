package com.pwa.shell.ui

internal class PwaIconDraft(private val originalPath: String) {
    private var currentPath = originalPath
    private var committed = false

    fun replace(path: String) {
        currentPath = path
    }

    fun commit() {
        committed = true
    }

    fun pathToDeleteOnDispose(): String? =
        currentPath.takeIf { !committed && it != originalPath }
}
