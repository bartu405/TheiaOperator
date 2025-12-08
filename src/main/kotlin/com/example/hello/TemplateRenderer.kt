// File: TemplateRenderer.kt
package com.example.hello

import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter

// You will call this like
// TemplateRenderer.render("", mapOf(...))

object TemplateRenderer {

    private val engine: VelocityEngine = VelocityEngine().apply {
        setProperty("resource.loader", "class")
        setProperty("class.resource.loader.class", ClasspathResourceLoader::class.java.name)
        init()
    }

    fun render(templatePath: String, model: Map<String, Any?>): String {
        val context = VelocityContext()
        model.forEach { (k, v) -> context.put(k, v) }

        val writer = StringWriter()
        engine.mergeTemplate(templatePath, "UTF-8", context, writer)
        return writer.toString()
    }
}
