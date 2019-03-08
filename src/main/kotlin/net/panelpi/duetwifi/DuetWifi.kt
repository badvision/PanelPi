package net.panelpi.duetwifi

import com.fazecast.jSerialComm.SerialPortDataListener
import com.fazecast.jSerialComm.SerialPortEvent
import com.pi4j.io.serial.*
import mu.KLogging
import net.panelpi.*
import net.panelpi.models.DuetData
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.regex.Pattern

fun main(args: Array<String>) {
    val v = "{\"status\":\"I\",\"coords\":{\"axesHomed\":[1,1,1],\"xyz\":[150.000,150.000,5.000],\"machine\":[150.000\u0000,150.005.000],\"extr\":[0.0]},\"currentTool\":0,\"params\":{\"atxPower\":1,\"fanPercent\":[0,100,100,0,0,0,0,0,0],\"speedFactor\":100.0,\"extrFactors\":[100.0],\"babystep\":0.000},\"sensors\":{\"probeValue\":0,\"fanRPM\":0},\"temps\":{\"bed\":{\"current\":27.2,\"active\":0.0,\"state\":0,\"heater\":1},\"current\":[27.1,27.2,2000.0,2000.0,2000.0,2000.0,2000.0,2000.0],\"state\":[2,0,0,0,0,0,0,0],\"heads\":{\"current\":[],\"active\":[],\"standby\":[],\"state\":[]},\"tools\":{\"active\":[[0.0]],\"standby\":[[0.0]]},\"extra\":[{\"name\":\"MCU\",\"temp\":35.4}]},\"time\":1955.0,\"currentLayer\":0,\"currentLayerTime\":0.0,\"extrRaw\":[0.0],\"fractionPrinted\":0.0,\"firstLayerDuration\":0.0,\"firstLayerHeight\":0.00,\"printDuration\":0.0,\"warmUpDuration\":0.0,\"timesLeft\":{\"file\":0.0,\"filament\":0.0,\"layer\":0.0},\"seq\":1,\"resp\":\"\"}"
    v.toJson()?.parseAs<DuetData>()
}


class DuetWifi {
    companion object : KLogging()

    private val serial = ConcurrentBox(getSerialIO())

    fun sendCmd(vararg cmd: String, resultTimeout: Long = 0): String {
        return serial.exclusive {
            sendCmd(*cmd, resultTimeout = resultTimeout)
        }
    }

    private fun getSerialIO(): DuetIO {
        return try {
            if (isRpi()) {
                RaspPiDuetIO()
            } else {
                TelnetDuetIO()
                // UsbDuetIO()
            }
        } catch (e: Throwable) {
            logger.info { "Cannot create RaspberryPi serial IO, running in dev mode." }
            DevDuetIO()
        }
    }

    fun isRpi():Boolean {
        val cpuinfo = File("/proc/cpuinfo")
        return if (!cpuinfo.exists()) {
            false
        } else {
            cpuinfo.readText(Charsets.UTF_8).contains("ARMv7")
        }
    }

    fun halt() {
        serial.exclusive { this.halt() }
    }
}

sealed class DuetIO {
    abstract fun sendCmd(vararg lines: String, resultTimeout: Long): String
    abstract fun halt()
}

class RaspPiDuetIO : DuetIO() {
    private val serial = SerialFactory.createInstance()

    private var responseBuffer = ""

    init {
        val config = SerialConfig().device(SerialPort.getDefaultPort())
                .baud(Baud._57600)
                .parity(Parity.NONE)
                .flowControl(FlowControl.NONE)
        serial.open(config)
        serial.addListener(SerialDataEventListener { event ->
            responseBuffer += event.asciiString
        })
    }

    override fun halt() {
        serial.close()
    }

    override fun sendCmd(vararg lines: String, resultTimeout: Long): String {
        responseBuffer = ""
        lines.forEach { serial.writeln(it.appendCheckSum()) }
        var sleep = 0
        while (!(responseBuffer.endsWith("\n") || responseBuffer == "ACK") && sleep < resultTimeout) {
            Thread.sleep(100)
            sleep += 100
        }
        return responseBuffer.split("\n").lastOrNull { it.isNotBlank() } ?: ""
    }
}

class UsbDuetIO : DuetIO() {
    var port: com.fazecast.jSerialComm.SerialPort

    init {
        port = com.fazecast.jSerialComm.SerialPort.getCommPorts()[0]
        port.baudRate = 57600
        port.parity = com.fazecast.jSerialComm.SerialPort.NO_PARITY
        port.setFlowControl(com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_DISABLED)
        port.numDataBits = 8
        port.numStopBits = 1
        port.openPort()
    }

    override fun halt() {
        port.closePort()
    }

    private val txBuffer = ByteArray(256)

    override fun sendCmd(vararg lines: String, resultTimeout: Long): String {
        var buffer = ""
        lines.forEach {
                port.outputStream.write((it.appendCheckSum() + "\n").toByteArray(Charset.forName("US-ASCII")))
        }
        var sleep = 0
        var avail:Int
        while (!(buffer.endsWith("\n") || buffer == "ACK") && sleep < resultTimeout) {
            if (port.inputStream.available() > 0) {
                avail = port.inputStream.read(txBuffer)
                buffer += String(txBuffer, 0, avail)
            } else {
                Thread.sleep(100)
                sleep += 100
            }
        }
        return buffer.split("\n").lastOrNull { it.isNotBlank() } ?: ""
    }
}

class TelnetDuetIO : DuetIO() {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null

    init {
        System.out.println("trying to connect...")
        reconnect()
        System.out.println("connected...")
    }

    private fun reconnect() {
        socket = Socket("192.168.1.69", 23)
        reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
    }

    override fun halt() {
        socket?.close()
    }

    override fun sendCmd(vararg lines: String, resultTimeout: Long): String {
        if (socket == null || socket!!.isClosed) {
            System.out.println("Detected dead socket, reconnecting")
            reconnect()
        }
        var buffer = ""
        try {
            lines.forEach {
                socket!!.outputStream.write("$it\n".toByteArray(Charset.forName("US-ASCII")))
//            System.out.println(">>$it")
                socket!!.outputStream.flush()
            }
        } catch (ex: SocketException) {
            socket?.close()
            throw ex
        }

        var wait = resultTimeout
        while (buffer == "ok" || buffer.contains("Begin file list") || buffer.contains("End file list") || buffer.isEmpty()) {
            while (!reader!!.ready() && wait > 0) {
                Thread.sleep(100)
                wait -= 100
            }
            if (wait <= 0) {
//        System.out.println("Response timeout")
                return ""
            } else {
                buffer = reader!!.readLine()
            }
        }
//        System.out.println("<<$buffer")
        return buffer
    }
}

class DevDuetIO : DuetIO() {
    companion object : KLogging() {
        private const val fullStatus = "{\"status\":\"I\",\"coords\":{\"axesHomed\":[0,0,0],\"extr\":[0.0],\"xyz\":[0.000,0.000,0.000]},\"currentTool\":0,\"params\":{\"atxPower\":0,\"fanPercent\":[0.00,100.00,100.00,0.00,0.00,0.00,0.00,0.00,0.00],\"speedFactor\":100.00,\"extrFactors\":[100.00],\"babystep\":0.000},\"sensors\":{\"probeValue\":0,\"fanRPM\":0},\"temps\":{\"bed\":{\"current\":23.5,\"active\":0.0,\"state\":0,\"heater\":1},\"current\":[23.1,23.5,2000.0,2000.0,2000.0,2000.0,2000.0,2000.0],\"state\":[2,0,0,0,0,0,0,0],\"heads\":{\"current\":[],\"active\":[],\"standby\":[],\"state\":[]},\"tools\":{\"active\":[[0.0]],\"standby\":[[0.0]]},\"extra\":[{\"name\":\"MCU\",\"temp\":23.5}]},\"time\":53.0,\"coldExtrudeTemp\":160,\"coldRetractTemp\":90,\"tempLimit\":290,\"endstops\":3096,\"firmwareName\":\"RepRapFirmware for Duet WiFi\",\"geometry\":\"coreXY\",\"axes\":3,\"axisNames\":\"XYZ\",\"volumes\":2,\"mountedVolumes\":1,\"name\":\"CR-10\",\"probe\":{\"threshold\":100,\"height\":-0.10,\"type\":8},\"tools\":[{\"number\":0,\"name\":\"\",\"heaters\":[0],\"drives\":[0],\"axisMap\":[[0],[1]],\"fans\":1,\"filament\":\"\",\"offsets\":[0.00,0.00,0.00]}],\"mcutemp\":{\"min\":19.1,\"cur\":23.5,\"max\":23.7},\"vin\":{\"min\":0.0,\"cur\":0.8,\"max\":0.9},\"seq\":0,\"resp\":\"\"}"
        private const val updates = "{\"status\":\"P\",\"coords\":{\"axesHomed\":[1,1,0],\"xyz\":[200.000,190.000,0.000],\"machine\":[200.000,190.000,0.000],\"extr\":[0.0]},\"currentTool\":0,\"params\":{\"atxPower\":1,\"fanPercent\":[0,100,100,0,0,0,0,0,0],\"speedFactor\":100.0,\"extrFactors\":[100.0],\"babystep\":0.000},\"sensors\":{\"probeValue\":0,\"fanRPM\":0},\"temps\":{\"bed\":{\"current\":31.8,\"active\":70.0,\"state\":2,\"heater\":1},\"current\":[26.2,31.8,2000.0,2000.0,2000.0,2000.0,2000.0,2000.0],\"state\":[2,2,0,0,0,0,0,0],\"heads\":{\"current\":[],\"active\":[],\"standby\":[],\"state\":[]},\"tools\":{\"active\":[[0.0]],\"standby\":[[0.0]]},\"extra\":[{\"name\":\"MCU\",\"temp\":33.1}]},\"time\":53.0,\"coldExtrudeTemp\":160.0,\"coldRetractTemp\":90.0,\"controllableFans\":1,\"tempLimit\":290.0,\"endstops\":4088,\"firmwareName\":\"RepRapFirmware for Duet 2 WiFi/Ethernet\",\"geometry\":\"coreXY\",\"axes\":3,\"axisNames\":\"XYZ\",\"volumes\":2,\"mountedVolumes\":1,\"name\":\"PanelPi\",\"probe\":\"0\",\"tools\":[{\"number\":0,\"heaters\":[0],\"drives\":[0],\"axisMap\":[[0],[1]],\"fans\":1,\"filament\":\"\",\"offsets\":[0.00,0.00,0.00]}],\"mcutemp\":{\"min\":32.3,\"cur\":32.8,\"max\":33.2},\"vin\":{\"min\":0.1,\"cur\":0.8,\"max\":2.8},\"seq\":2,\"resp\":\"\",\"currentLayer\":0,\"currentLayerTime\":0.0,\"extrRaw\":[0.0],\"fractionPrinted\":0.3,\"firstLayerDuration\":0.0,\"firstLayerHeight\":0.20,\"printDuration\":5.7,\"warmUpDuration\":5.1,\"timesLeft\":[0.0,0.0,0.0],\"heaters\":[32.1],\"active\":[70.0],\"standby\":[0.0],\"hstat\":[2],\"pos\":[200.000,190.000,0.000],\"machine\":[200.000,190.000,0.000],\"sfactor\":100.00,\"efactor\":[100.00],\"babystep\":0.000,\"tool\":0,\"fanPercent\":[0.0,100.0,100.0,0.0,0.0,0.0,0.0,0.0,0.0],\"fanRPM\":0,\"homed\":[1,1,0],\"fraction_printed\":0.0030,\"msgBox.mode\":-1}\n"
        private const val dir1 = "{\"dir\":\"0:/gcodes/\",\"first\":0,\"files\":[\"file1.gcode\",\"file2.gcode\", \"*test\"],\"next\":0,\"err\":0}"
        private const val file = "{\"err\":0,\"size\":35498176,\"lastModified\":\"2018-06-22T13:52:28\",\"height\":144.98,\"firstLayerHeight\":0.20,\"layerHeight\":0.20,\"printTime\":14400,\"filament\":[45914.7],\"generatedBy\":\"Simplify3D(R) Version 4.0.0\"}"
        private const val currentFile = "{\"err\":0,\"size\":1971438,\"height\":9.98,\"firstLayerHeight\":0.20,\"layerHeight\":0.20,\"filament\":[1892.9],\"generatedBy\":\"Simplify3D(R) Version 4.0.0\",\"printDuration\":5,\"fileName\":\"Z.gcode\"}"
        private const val dir2 = "{\"dir\":\"gcodes/test\",\"first\":0,\"files\":[\"file3.gcode\"],\"next\":0,\"err\":0}"
    }

    private var statusUpdate = updates.toJson()

    override fun sendCmd(vararg lines: String, resultTimeout: Long): String {
        logger.debug { lines.joinToString("\n") }
        return when {
            lines.contains("M408 S3") -> fullStatus
            lines.contains("M408 S4") -> statusUpdate.toString()
            lines.contains("M20 S2 P/gcodes") -> dir1
            lines.contains("M20 S2 P/gcodes/test") -> dir2
            lines.contains("M36 /gcodes/file1.gcode") -> file
            lines.contains("M36 /gcodes/file2.gcode") -> file
            lines.contains("M36 /gcodes/test/file3.gcode") -> file
            lines.contains("M36") -> currentFile
            lines.contains("M0") -> {
                statusUpdate = statusUpdate?.plus(Pair("status", "I"))
                ""
            }

            lines.contains("M25") -> {
                statusUpdate = statusUpdate?.plus(Pair("status", "A"))
                ""
            }

            lines.contains("M24") -> {
                statusUpdate = statusUpdate?.plus(Pair("status", "P"))
                ""
            }

            lines.contains("M24") -> {
                statusUpdate = statusUpdate?.plus(Pair("status", "P"))
                ""
            }
            lines.any { it.contains("M32") } -> {
                statusUpdate = statusUpdate?.plus(Pair("status", "P"))
                ""
            }
            else -> ""
        }
    }

    override fun halt() {
        logger.debug("Serial halted")
    }
}