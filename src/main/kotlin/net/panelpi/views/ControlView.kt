package net.panelpi.views

import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.*
import net.panelpi.duetwifi.DuetWifi
import net.panelpi.map
import tornadofx.*

class ControlView : View() {
    override val root: Parent by fxml()

    private val duetData = DuetWifi.instance.duetData

    private val homeX: Button by fxid()
    private val homeY: Button by fxid()
    private val homeZ: Button by fxid()

    private val onButton: ToggleButton by fxid()
    private val offButton: ToggleButton by fxid()

    private val bedComp: Button by fxid()
    private val gridComp: Button by fxid()
    private val homeAll: Button by fxid()

    private val xAmount: ComboBox<String> by fxid()
    private val yAmount: ComboBox<String> by fxid()
    private val zAmount: ComboBox<String> by fxid()

    private val xLeft: Button by fxid()
    private val xRight: Button by fxid()

    private val yLeft: Button by fxid()
    private val yRight: Button by fxid()

    private val zLeft: Button by fxid()
    private val zRight: Button by fxid()

    private val toolTemp: Label by fxid()
    private val bedTemp: Label by fxid()

    private val xCoord: Label by fxid()
    private val yCoord: Label by fxid()
    private val zCoord: Label by fxid()

    private val extrude: Button by fxid()
    private val retract: Button by fxid()

    private val feedAmountCB: ComboBox<String> by fxid()
    private val feedRateCB: ComboBox<String> by fxid()

    private val bedActiveTemp: ComboBox<Int> by fxid()
    private val toolActiveTemp: ComboBox<Int> by fxid()
    private val toolStandbyTemp: ComboBox<Int> by fxid()

    private val fanSlider: Slider by fxid()
    private val fanSliderAmount: Label by fxid()

    // Observable value for listening will need to be declared here, or else listener will get GCed and won't trigger event.
    private val coords = duetData.map { it.axes }
    private val xHomed = coords.map { it["X"]?.homed ?: false }
    private val yHomed = coords.map { it["Y"]?.homed ?: false }
    private val zHomed = coords.map { it["Z"]?.homed ?: false }

    private val bedActive: ObservableValue<Int?> = duetData.map { it.temps.bed.active }
    private val toolActive: ObservableValue<Int?> = duetData.map { it.temps.tools.activeTemperature(0) }
    private val toolStandby: ObservableValue<Int?> = duetData.map { it.temps.tools.standbyTemperature(0) }

    init {
        bindHomeButton(homeX, xHomed, "X")
        bindHomeButton(homeY, yHomed, "Y")
        bindHomeButton(homeZ, zHomed, "Z")

        val toggleGroup = ToggleGroup()
        onButton.toggleGroup = toggleGroup
        offButton.toggleGroup = toggleGroup

        onButton.setOnAction {
            if (duetData.value?.params?.atxPower == false) {
                DuetWifi.instance.sendCmd("M80")
            }
            it.consume()
            onButton.isSelected = duetData.value?.params?.atxPower == true
        }
        offButton.setOnAction {
            if (duetData.value?.params?.atxPower == true) {
                DuetWifi.instance.sendCmd("M81")
            }
            it.consume()
            offButton.isSelected = duetData.value?.params?.atxPower == false
        }

        bedComp.setOnAction {
            runAsync {
                bedComp.isDisable = true
                DuetWifi.instance.sendCmd("G32")
            }.setOnSucceeded {
                bedComp.isDisable = false
            }
        }
        homeAll.setOnAction {
            runAsync {
                DuetWifi.instance.sendCmd("G28")
            }
        }
        gridComp.setOnAction {
            runAsync {
                DuetWifi.instance.sendCmd("G29")
            }
        }

        val amounts = observableList("100", "10", "1", "0.1")

        listOf(xAmount, yAmount, zAmount).forEach {
            it.items = amounts
            it.selectionModel.select(0)
        }

        bedTemp.bind(duetData.map { it.temps.bed.let { "${it.current}°C" } })
        toolTemp.bind(duetData.map { it.temps.current.let { "${it.firstOrNull()}°C" } })

        xCoord.bind(duetData.map { "${it.axes["X"]?.coord}" })
        yCoord.bind(duetData.map { "${it.axes["Y"]?.coord}" })
        zCoord.bind(duetData.map { "${it.axes["Z"]?.coord}" })

        val feedAmounts = observableList("100", "50", "20", "10", "5", "1")
        val feedRate = observableList("60", "30", "15", "5", "1")

        feedAmountCB.items = feedAmounts
        feedRateCB.items = feedRate

        feedAmountCB.selectionModel.select("10")
        feedRateCB.selectionModel.select("5")

        extrude.disableProperty().bind(duetData.map { !it.isExtrudeEnable })
        retract.disableProperty().bind(duetData.map { !it.isRetractEnable })

        // TODO: Make these configurable.
        val bedTemps = observableList(0, 55, 60, 65, 70, 80, 90, 110, 120)
        val toolTemps = observableList(0, 130, 180, 190, 200, 210, 220, 230, 235)

        bedActiveTemp.items = bedTemps
        toolActiveTemp.items = toolTemps
        toolStandbyTemp.items = toolTemps

        configTemperatureComboBoxes(bedActiveTemp, toolActiveTemp, toolStandbyTemp)

        bedActive.addListener { _, _, newValue -> bedActiveTemp.selectionModel.select(newValue) }
        toolActive.addListener { _, _, newValue -> toolActiveTemp.selectionModel.select(newValue) }
        toolStandby.addListener { _, _, newValue -> toolStandbyTemp.selectionModel.select(newValue) }

        bedActiveTemp.setOnAction {
            val selected = bedActiveTemp.selectionModel.selectedItem
            if (duetData.value.temps.bed.active != selected) {
                DuetWifi.instance.sendCmd("M140 S$selected")
            }
        }

        toolActiveTemp.setOnAction {
            val selected = toolActiveTemp.selectionModel.selectedItem
            if (duetData.value.temps.tools.activeTemperature(0) != selected) {
                // TODO: Support more extruder
                DuetWifi.instance.sendCmd("G10 P0 S$selected")
            }
        }

        toolStandbyTemp.setOnAction {
            val selected = toolStandbyTemp.selectionModel.selectedItem
            if (duetData.value.temps.tools.standbyTemperature(0) != selected) {
                // TODO: Support more extruder
                DuetWifi.instance.sendCmd("G10 P0 R$selected")
            }
        }

        fanSlider.min = 0.0
        fanSlider.max = 100.0
        fanSlider.majorTickUnit = 1.0
        fanSliderAmount.bind(fanSlider.valueProperty().map { "${it.toInt()} %" })

        // TODO: refactor axes.
        xLeft.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 X-${xAmount.value} F6000", "M121")
        }
        xRight.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 X${xAmount.value} F6000", "M121")
        }

        yLeft.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 Y-${yAmount.value} F6000", "M121")
        }
        yRight.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 Y${yAmount.value} F6000", "M121")
        }
        zLeft.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 Z-${zAmount.value} F6000", "M121")
        }
        zRight.setOnAction {
            DuetWifi.instance.sendCmd("M120", "G91", "G1 Z${zAmount.value} F6000", "M121")
        }
    }

    private fun bindHomeButton(homeButton: Button, homed: ObservableValue<Boolean>, axisName: String) {
        homed.addListener { _, _, newValue ->
            homeButton.toggleClass("primary", newValue)
            homeButton.toggleClass("warning", !newValue)
        }
        homeButton.setOnMouseClicked {
            DuetWifi.instance.sendCmd("G28 $axisName")
        }
    }

    private fun configTemperatureComboBoxes(vararg comboBox: ComboBox<Int>) {
        comboBox.forEach {
            it.converter = DegreeConverter
            it.buttonCell = object : ListCell<Int>() {
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item != null) {
                        text = "$item °C"
                        alignment = Pos.CENTER_RIGHT
                        padding = Insets(padding.top, 0.0, padding.bottom, 0.0)
                    }
                }
            }
        }
    }

    private fun configureMoveAmountComboBoxes(vararg comboBox: ComboBox<Int>) {
        comboBox.forEach {
            it.converter = DegreeConverter
            it.buttonCell = object : ListCell<Int>() {
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item != null) {
                        text = "$item °C"
                        alignment = Pos.CENTER_RIGHT
                        padding = Insets(padding.top, 0.0, padding.bottom, 0.0)
                    }
                }
            }
        }
    }
}