package net.panelpi.controllers

import javafx.application.Platform
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.chart.XYChart
import tornadofx.*
import mu.KLogging
import net.panelpi.duetwifi.DuetWifi
import net.panelpi.map
import net.panelpi.models.*
import net.panelpi.parseAs
import net.panelpi.plus
import net.panelpi.toJson
import java.nio.file.Files
import java.nio.file.Paths
import javax.json.JsonObject
import kotlin.concurrent.timer

class DuetController : Controller() {
    companion object : KLogging() {
        // Hack to get duet to send back ack after long running moves (e.g grid compensation) completed.
        private const val ACK = "M118 P2 S\"ACK\""
    }

    val extruderValues: ObservableList<XYChart.Data<Number, Number>> = FXCollections.observableArrayList()
    val bedValues: ObservableList<XYChart.Data<Number, Number>> = FXCollections.observableArrayList()
    val lowestTemperature = SimpleDoubleProperty(0.0)

    var isHalted = false

    private val duet = DuetWifi()

    //private val duetDataMessage = observableList(JsonObject.EMPTY_JSON_OBJECT)
    private val jsonDuetData = SimpleObjectProperty(JsonObject.EMPTY_JSON_OBJECT)

    val duetData: ObservableValue<DuetData> = jsonDuetData.map { it.parseAs() ?: DuetData() }
    private val _sdData = SimpleObjectProperty<SDFolder>(SDFolder("gcodes", emptyList()))
    val sdData: ObservableValue<SDFolder> = _sdData

    private val _currentFile = SimpleObjectProperty<SDFile>()
    val currentFile: ObservableValue<SDFile?> = _currentFile

    private val statusObservable = duetData.map { it.status }

    val console: ObservableList<ConsoleMessage> = FXCollections.observableArrayList<ConsoleMessage>()
    val macros: ObservableList<String> = FXCollections.observableArrayList<String>()

    init {
        (0..25).forEach{
            bedValues.add(XYChart.Data(it, 25.0))
            extruderValues.add(XYChart.Data(it, 25.0))
        }
        runAsync {
            val status = duet.sendCmd("M408 S3", resultTimeout = 5000).toJson()
            console.add(0, ConsoleMessage(MessageType.INFO, message = "Connection established!"))
            Pair(status, getFile())
        }.ui { (status, currentFile) ->
            status?.let { jsonDuetData.set(jsonDuetData.value + it) }
            _currentFile.set(currentFile)
        }

        refreshSDData()
        refreshMacros()

        timer(initialDelay = 1000, period = 500) {
            if (isHalted) {
                cancel()
            }
            try {
                val data = duet.sendCmd("M408 S4", resultTimeout = 5000).toJson()
                runLater {
                    data?.let { jsonDuetData.set(jsonDuetData.value + it) }
                }
            } catch (e: Throwable) {
                logger.debug(e) { "Ignoring error on update thread" }
            }
        }

        timer(initialDelay = 1000, period = 2000) {
            if (isHalted) {
                cancel()
            }
            runLater {
                if (duetData.value.temps.current.isEmpty()) {
                    return@runLater
                }
                val bedTemp = duetData.value.temps.bed.current
                val extTemp = duetData.value.temps.current[1]
                bedValues.forEach { it.xValue = it.xValue.toInt() - 1 }
                extruderValues.forEach { it.xValue = it.xValue.toInt() - 1 }
                var min = 2000
                bedValues.forEach{ min = Math.min(min, it.yValue.toInt()) }
                extruderValues.forEach{ min = Math.min(min, it.yValue.toInt()) }
                lowestTemperature.set(min.toDouble())
                val x = bedValues.last().xValue.toInt() + 1
                bedValues.add(XYChart.Data(x, bedTemp))
                extruderValues.add(XYChart.Data(x, extTemp))
                bedValues.remove(0, 1)
                extruderValues.remove(0, 1)
            }
        }

        statusObservable.onChange {
            if (it == Status.P) runAsync { getFile() }.ui { _currentFile.set(it) }
        }
    }

    fun halt() {
        isHalted = true
        duet.halt()
        Platform.runLater{System.exit(1)}
    }

    fun sendCmd(vararg cmd: String, resultTimeout: Long = 0): String {
        // Hacks to remove ACK message
        console.add(0, ConsoleMessage(MessageType.COMMAND, commands = cmd.asList().filterNot { it.startsWith("M118") }))
        return duet.sendCmd(*cmd, resultTimeout = resultTimeout)
    }

    private fun getSdData(folder: String = "gcodes"): SDFolder? {
        return duet.sendCmd("M20 S2 P/$folder", resultTimeout = 5000).toJson()?.parseAs<JsonSDFolder>()?.files?.sorted()?.mapNotNull {
            if (it.startsWith("*")) {
                getSdData("$folder/${it.drop(1)}")
            } else {
                getFile("/$folder/$it")
            }
        }?.let { SDFolder(folder.split("/").last(), it) }
    }

    private fun getFile(path: String? = null): SDFile? {
        return if (path == null) {
            duet.sendCmd("M36", resultTimeout = 5000).toJson()
        } else {
            duet.sendCmd("M36 $path", resultTimeout = 5000).toJson()?.plus("fileName" to path.split("/").last())
        }?.parseAs()
    }

    fun moveAxis(axisName: String, amount: Double, speed: Int = 6000) {
        sendCmd("M120", "G91", "G1 $axisName$amount F$speed", "M121")
    }

    fun extrude(amount: Double, feedRate: Int) {
        sendCmd("M120", "M83", "G1 E$amount F${feedRate * 60}", "M121")
    }

    fun atxPower(on: Boolean = true) {
        if (duetData.value.params.atxPower != on) {
            sendCmd(if (on) "M80" else "M81")
        }
    }

    fun logDuetData() {
        Files.write(Paths.get("logs/duet-data-dump-${System.currentTimeMillis()}.log"), listOf(jsonDuetData.value.toString()))
    }

    fun bedCompensation() {
        sendCmd("G32", ACK, resultTimeout = 60000)
    }

    fun gridCompensation() {
        sendCmd("G29", ACK, resultTimeout = 60000)
    }

    fun homeAxis(axisName: String? = null) {
        sendCmd(axisName?.let { "G28 $it" } ?: "G28", ACK, resultTimeout = 60000)
    }

    fun setBedTemperature(temperature: Int) {
        sendCmd("M140 S$temperature")
    }

    fun setToolTemperature(tool: Int, temperature: Int, standby: Boolean = false) {
        sendCmd("G10 P$tool ${if (standby) "R" else "S"}$temperature")
    }

    fun emergencyStop() {
        sendCmd("M112", "M999")
    }

    fun selectFileAndPrint(fileName: String) {
        sendCmd("M32 $fileName")
    }

    fun resumePrint() {
        sendCmd("M24")
    }

    fun pausePrint() {
        sendCmd("M25")
    }

    fun stopPrint() {
        sendCmd("M0")
    }

    fun setSpeedFactorOverride(percent: Int) {
        sendCmd("M220 S$percent")
    }

    fun setExtrudeFactorOverride(percent: Int) {
        sendCmd("M221 D0 S$percent")
    }

    fun setFanSpeed(percent: Int) {
        sendCmd("M106 S${percent.toDouble() / 100}")
    }

    fun babyStepping(up: Boolean) {
        if (up) {
            sendCmd("M290 S0.05")
        } else {
            sendCmd("M290 S-0.05")
        }
    }

    fun refreshMacros() {
        runAsync {
            val files = duet.sendCmd("M20 S2 P/macros", resultTimeout = 5000).toJson()?.parseAs<JsonSDFolder>()?.files?.sorted()
            macros.setAll(files)
        }
    }

    fun refreshSDData(func: () -> Unit = {}) {
        runAsync { getSdData() }.ui {
            it?.let { _sdData.set(it) }
            func()
        }
    }

    fun runMacro(macro: String?) {
        runAsync { duet.sendCmd("M98 P/macros/$macro")}
    }
}