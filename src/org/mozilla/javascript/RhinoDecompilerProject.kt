package org.mozilla.javascript

import com.google.gson.Gson
import org.mozilla.javascript.decompiler.ScriptEncryption
import org.mozilla.javascript.serialize.ScriptableInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.ObjectStreamClass


class BuildInfo(
    var build_id: String,
)

class RhinoDecompilerProject(
    var packageName: String,
    var versionName: String,
    var versionCode: Int,
    var build: BuildInfo,
    var name: String,
    var main: String,
    var filePath: String,
    var dir: String
) {
    constructor() : this("", "", 0, BuildInfo(""), "", "", "", "")

    companion object {
        fun fromProject(dir: String): RhinoDecompilerProject {
            return Gson().fromJson(InputStreamReader(File(dir, "project.json").inputStream()), RhinoDecompilerProject::class.java)
                .apply {
                    this.dir = dir
                    this.filePath = "/${dir}/${this.main}"
                }
        }

        fun fromFile(path: String): RhinoDecompilerProject {
            return RhinoDecompilerProject().apply {
                this.filePath = path
            }
        }
    }

    fun decrypt(): InterpreterData {
        // 单独文件 反序列化
        if (packageName.isEmpty()) {
            val ctx = Context.enter()
            val scope = ctx.initStandardObjects()
            val scriptableInputStream: ScriptableInputStream = Compat(FileInputStream(filePath), scope)
            val script = scriptableInputStream.readObject() as Script
            scriptableInputStream.close()
            val data = (script as InterpretedFunction).idata
            return data
        }
        val source = File(filePath).readBytes()
        val encryption = ScriptEncryption(this)
        val result = encryption.decrypt(source, 8, source.size)
        val function = ScriptableInputStream(ByteArrayInputStream(result), null).readObject() as InterpretedFunction
        return function.idata
    }

    inner class Compat(`in`: InputStream, scope: Scriptable?) : ScriptableInputStream(`in`, scope) {
        @Throws(ClassNotFoundException::class, IOException::class)
        override fun readClassDescriptor(): ObjectStreamClass {
            val resultClassDescriptor = super.readClassDescriptor()
            try {
                val localClass = Class.forName(resultClassDescriptor.name)
                val localClassDescriptor = ObjectStreamClass.lookup(localClass)
                if (localClassDescriptor != null) {
                    val localSUID = localClassDescriptor.serialVersionUID
                    val streamSUID = resultClassDescriptor.serialVersionUID
                    if (streamSUID != localSUID) {
                        return localClassDescriptor
                    }
                    return resultClassDescriptor
                }
                return resultClassDescriptor
            } catch (e: ClassNotFoundException) {
                return resultClassDescriptor
            }
        }
    }

}