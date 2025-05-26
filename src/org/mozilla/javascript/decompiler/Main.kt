package org.mozilla.javascript.decompiler

import org.mozilla.javascript.RhinoDecompiler3
import org.mozilla.javascript.RhinoDecompilerProject
import java.io.File


fun main() {
    val projectURI = {}::class.java.getResource("/simple1")?.toURI()!!
    val project = RhinoDecompilerProject.fromProject(File(projectURI).absolutePath)
    val data = project.decrypt()
    println(RhinoDecompiler3.decompile(data))
}
