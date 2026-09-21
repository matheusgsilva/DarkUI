package com.matheus.darkui.pack

import com.matheus.darkui.model.AppIconItem

internal object PackMetadata {
    fun buildAppFilter(items: List<AppIconItem>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<resources>\n")

        items.forEachIndexed { index, item ->
            val drawable = "icon_%04d".format(index)

            item.app.components.forEach { component ->
                append("    <item component=\"ComponentInfo{")
                append(xml(component.packageName))
                    .append('/')
                    .append(xml(resolveClassName(component.packageName, component.className)))
                append("}\" drawable=\"")
                    .append(drawable)
                    .append("\" />\n")
            }
        }

        append("</resources>\n")
    }

    fun buildDrawableList(count: Int): String = buildString {
        require(count >= 0) { "count must be non-negative" }

        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<resources>\n")
        append("    <category title=\"DarkUI Generated\" />\n")

        repeat(count) { index ->
            append("    <item drawable=\"icon_%04d\" />\n".format(index))
        }

        append("</resources>\n")
    }

    internal fun resolveClassName(packageName: String, className: String): String =
        when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
}
