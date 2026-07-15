package snes

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Valida a CPU SPC700 contra os vetores ProcessorTests (SingleStepTests/spc700): cada opcode,
 * milhares de casos, comparando estado final (A/X/Y/SP/PC/PSW e RAM). Vetores (~81 MB) fora do
 * git; 8 amostras enxutas ficam commitadas para auto-validação; o teste pula se ausentes.
 */
class ProcessorTestSpc700 {

    private class FlatBus : Bus700 {
        val ram = IntArray(0x10000)
        override fun read(addr: Int) = ram[addr and 0xFFFF]
        override fun write(addr: Int, value: Int) { ram[addr and 0xFFFF] = value and 0xFF }
    }

    @TestFactory
    fun spc700Tests(): List<DynamicTest> {
        val url = javaClass.getResource("/spc700")
        assumeTrue(url != null, "vetores /spc700 ausentes")
        val dir = File(url!!.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(files.isNotEmpty(), "nenhum vetor .json em /spc700")

        return files.map { file ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) {
                val cases = JsonParser.parseString(file.readText()).asJsonArray
                var failures = 0; var first: String? = null
                for (case in cases) {
                    val obj = case.asJsonObject
                    val bus = FlatBus(); val cpu = Spc700(bus)
                    val ini = obj.getAsJsonObject("initial")
                    cpu.pc = ini.get("pc").asInt; cpu.a = ini.get("a").asInt; cpu.x = ini.get("x").asInt
                    cpu.y = ini.get("y").asInt; cpu.sp = ini.get("sp").asInt; cpu.psw = ini.get("psw").asInt
                    for (p in ini.getAsJsonArray("ram")) { val a = p.asJsonArray; bus.ram[a[0].asInt and 0xFFFF] = a[1].asInt and 0xFF }
                    cpu.step()
                    val f = obj.getAsJsonObject("final")
                    val diff = check(cpu, bus, f)
                    if (diff != null) { failures++; if (first == null) first = "${obj.get("name").asString}: $diff" }
                }
                if (failures > 0) throw AssertionError("${file.name}: $failures/${cases.size()} falharam. Primeiro: $first")
            }
        }
    }

    private fun check(cpu: Spc700, bus: FlatBus, f: com.google.gson.JsonObject): String? {
        fun c(n: String, g: Int, e: Int): String? = if (g != e) "$n esp=%X obt=%X".format(e, g) else null
        c("pc", cpu.pc, f.get("pc").asInt)?.let { return it }
        c("a", cpu.a, f.get("a").asInt)?.let { return it }
        c("x", cpu.x, f.get("x").asInt)?.let { return it }
        c("y", cpu.y, f.get("y").asInt)?.let { return it }
        c("sp", cpu.sp, f.get("sp").asInt)?.let { return it }
        c("psw", cpu.psw, f.get("psw").asInt)?.let { return it }
        for (p in f.getAsJsonArray("ram")) {
            val a = p.asJsonArray; val addr = a[0].asInt and 0xFFFF
            c("ram[%04X]".format(addr), bus.ram[addr], a[1].asInt and 0xFF)?.let { return it }
        }
        return null
    }
}
