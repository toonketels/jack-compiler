/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package io.toon.jack

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        App().translate(args.getOrNull(0))
        exitProcess(0)
    } catch (e: Throwable) {
        println(e.message)
        exitProcess(1)
    }
}


class App {
    fun translate(fileName: String?) {

        if (fileName == null) throw IllegalArgumentException("Please provide a file or directory name")

        // @TODO check for absolute path
        var file = File(fileName)

        val sources = if (file.isDirectory) {
            println("Directory selected is ${file.absolutePath}")

            if (!file.exists()) throw IllegalArgumentException("Directory does not exist")
            if (!file.canRead()) throw IllegalArgumentException("Directory is not readable")

             file
                    .listFiles { file -> file.extension == "jack" }
                    .map { file -> file.nameWithoutExtension to file.readText() }
        } else {
            println(("File selected is ${file.absolutePath}"))

            if (file.extension != "jack") throw IllegalArgumentException("File needs to be of type jack")
            if (!file.exists()) throw IllegalArgumentException("File does not exist")
            if (!file.canRead()) throw IllegalArgumentException("File is not readable")

            listOf(file.nameWithoutExtension to file.readText())
        }

        sources.forEach { (name, _) -> println("File selected is ${name}") }

        sources
                .forEach { (name, content) ->
                    val converted = parseAndWrite(content).getOrThrow()
                    val output = File("${name}.xml")
                    output.writeText(converted)
                    println("Created ${output.absolutePath}")
                }
    }

}