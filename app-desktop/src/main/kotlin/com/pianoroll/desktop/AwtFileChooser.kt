package com.pianoroll.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Native Windows open/save dialogs (AWT FileDialog) + a folder picker (JFileChooser). Must be called on the UI thread. */
class AwtFileChooser(private val parent: () -> Frame?) : FileChooser {

    override fun chooseOpenProject(): File? {
        val d = FileDialog(parent(), "Open project", FileDialog.LOAD)
        d.file = "*.prs"                                              // Windows treats this as the filter
        d.setFilenameFilter { _, name -> name.endsWith(".prs", ignoreCase = true) }   // other platforms
        d.isVisible = true
        return picked(d)
    }

    override fun chooseSave(title: String, suggestedName: String, extension: String): File? {
        val d = FileDialog(parent(), title, FileDialog.SAVE)
        d.file = "$suggestedName.$extension"
        d.isVisible = true
        return picked(d)
    }

    override fun chooseDirectory(title: String): File? {
        val c = JFileChooser()
        c.dialogTitle = title
        c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (c.showOpenDialog(parent()) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
    }

    private fun picked(d: FileDialog): File? {
        val name = d.file ?: return null
        return File(d.directory ?: "", name)
    }
}
