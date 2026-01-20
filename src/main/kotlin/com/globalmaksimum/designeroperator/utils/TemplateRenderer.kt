package com.globalmaksimum.designeroperator.utils

import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter

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

    fun renderFromString(templateContent: String, context: Map<String, Any>): String {
        val velocityContext = VelocityContext()
        context.forEach { (k, v) -> velocityContext.put(k, v) }

        val writer = StringWriter()
        engine.evaluate(velocityContext, writer, "template", templateContent)
        return writer.toString()
    }
}