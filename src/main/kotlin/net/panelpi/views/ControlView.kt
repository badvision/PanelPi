package net.panelpi.views

import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.*
import javafx.scene.layout.VBox
import net.panelpi.controllers.DuetController
import net.panelpi.map
import tornadofx.*
import kotlin.math.roundToInt

class ControlView : View() {
    override val root: Parent by fxml()

    private val duetController: DuetController by inject()
    private val duetData = duetController.duetData

    private val homeX: Button by fxid()
    private val homeY: Button by fxid()
    private val homeZ: Button by fxid()

    private val onButton: ToggleButton by fxid()
    private val offButton: ToggleButton by fxid()

    private val bedComp: Button by fxid()
    private val gridComp: Button by fxid()
    private val homeAll: Button by fxid()

    private val xAmount: ComboBox<Double> by fxid()
    private val yAmount: ComboBox<Double> by fxid()
    private val zAmount: ComboBox<Double> by fxid()

    private val xLeft: Button by fxid()
    private val xRight: Button by fxid()

    private val yLeft: Button by fxid()
    private val yRight: Button by fxid()

    private val zLeft: Button by fxid()
    private val zRight: Button by fxid()

    private val toolTemp: Label by fxid()
    private val bedTemp: Label by fxid()
    private val temperatureHistogram: LineChart<Number, Number> by fxid()

    private val xCoord: Label by fxid()
    private val yCoord: Label by fxid()
    private val zCoord: Label by fxid()

    private val extrude: Button by fxid()
    private val retract: Button by fxid()

    private val feedAmountCB: ComboBox<Int> by fxid()
    private val feedRateCB: ComboBox<Int> by fxid()

    private val bedActiveTemp: ComboBox<Int> by fxid()
    private val toolActiveTemp: ComboBox<Int> by fxid()
    private val toolStandbyTemp: ComboBox<Int> by fxid()

    private val fanSliderBox: VBox by fxid()

    // Observable value for listening will need to be declared here, or else listener will get GCed and won't trigger event.
    private val coords = duetData.map { it.axes }
    private val xHomed = coords.map { it["X"]?.homed ?: false }
    private val yHomed = coords.map { it["Y"]?.homed ?: false }
    private val zHomed = coords.map { it["Z"]?.homed ?: false }

    private val bedActive: ObservableValue<Int?> = duetData.map { it.temps.bed.active }
    private val toolActive: ObservableValue<Int?> = duetData.map { it.temps.tools.activeTemperature(0) }
    private val toolStandby: ObservableValue<Int?> = duetData.map { it.temps.tools.standbyTemperature(0) }
    private val fanPercent = duetData.map { it.params.fanPercent.firstOrNull() }

    init {
        bindHomeButton(homeX, xHomed, "X")
        bindHomeButton(homeY, yHomed, "Y")
        bindHomeButton(homeZ, zHomed, "Z")

        val toggleGroup = ToggleGroup()
        onButton.toggleGroup = toggleGroup
        offButton.toggleGroup = toggleGroup

        onButton.setOnAction {
            duetController.atxPower(true)
            it.consume()
            onButton.isSelected = duetData.value?.params?.atxPower == true
        }
        offButton.setOnAction {
            duetController.atxPower(false)
            it.consume()
            offButton.isSelected = duetData.value?.params?.atxPower == false
        }

        bedComp.setOnAction {
            runAsync {
                bedComp.isDisable = true
                duetController.bedCompensation()
            }.setOnSucceeded {
                bedComp.isDisable = false
            }
        }
        homeAll.setOnAction {
            duetController.homeAxis()
        }
        gridComp.setOnAction {
            duetController.gridCompensation()
        }

        val amounts = observableList(100.0, 10.0, 1.0, 0.1)

        listOf(xAmount, yAmount, zAmount).forEach {
            it.items = amounts
            it.selectionModel.select(0)
        }

        bedTemp.bind(duetData.map { it.temps.bed.let { "${it.current}°C" } })
        toolTemp.bind(duetData.map { it.temps.current.let { "${it.getOrNull(1)}°C" } })

        val bedHistory: XYChart.Series<Number, Number> = XYChart.Series()
        bedHistory.name = "Bed"
        bedHistory.data = duetController.bedValues

        val extruderHistory: XYChart.Series<Number, Number> = XYChart.Series()
        extruderHistory.name = "Ext"
        extruderHistory.data = duetController.extruderValues

        temperatureHistogram.data.add(bedHistory)
        temperatureHistogram.data.add(extruderHistory)

        temperatureHistogram.createSymbols = false
        val yAxis: NumberAxis = temperatureHistogram.yAxis as NumberAxis

        temperatureHistogram.yAxis.animated = false
        temperatureHistogram.xAxis.animated = true
        temperatureHistogram.xAxis.isTickLabelsVisible = false

        duetController.lowestTemperature.addListener { observable, oldValue, newValue ->  yAxis.isForceZeroInRange = false; yAxis.lowerBoundProperty().unbind(); yAxis.lowerBound = newValue.toDouble()}

        bedHistory.node.styleClass.add("bedTemps")
        extruderHistory.node.styleClass.add("extruderTemps")

        xCoord.bind(duetData.map { "${it.axes["X"]?.coord}" })
        yCoord.bind(duetData.map { "${it.axes["Y"]?.coord}" })
        zCoord.bind(duetData.map { "${it.axes["Z"]?.coord}" })

        val feedAmounts = observableList(100, 50, 20, 10, 5, 1)
        val feedRate = observableList(60, 30, 15, 5, 1)

        feedAmountCB.items = feedAmounts
        feedRateCB.items = feedRate

        feedAmountCB.selectionModel.select(10 as Int?)
        feedRateCB.selectionModel.select(5 as Int?)

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
                duetController.setBedTemperature(selected)
            }
        }

        toolActiveTemp.setOnAction {
            val selected = toolActiveTemp.selectionModel.selectedItem
            if (duetData.value.temps.tools.activeTemperature(0) != selected) {
                // TODO: Support more extruder
                duetController.setToolTemperature(0, selected)
            }
        }

        toolStandbyTemp.setOnAction {
            val selected = toolStandbyTemp.selectionModel.selectedItem
            if (duetData.value.temps.tools.standbyTemperature(0) != selected) {
                // TODO: Support more extruder
                duetController.setToolTemperature(0, selected, standby = true)
            }
        }

        fanSliderBox.apply {
            labeledSlider(0.0, 100.0) {
                fanPercent.onChange { it?.let { this.value = it } }
                valueChangingProperty().onChange {
                    if (!it) {
                        val intValue = value.roundToInt()
                        value = intValue.toDouble()
                        if (intValue != duetData.value.params.fanPercent.firstOrNull()?.toInt()) {
                            duetController.setFanSpeed(intValue)
                        }
                    }
                }
            }
        }

        // TODO: refactor axes.
        xLeft.setOnAction {
            duetController.moveAxis("X", -(xAmount.value))
        }
        xRight.setOnAction {
            duetController.moveAxis("X", xAmount.value)
        }

        yLeft.setOnAction {
            duetController.moveAxis("Y", -(yAmount.value))
        }
        yRight.setOnAction {
            duetController.moveAxis("Y", yAmount.value)
        }
        zLeft.setOnAction {
            duetController.moveAxis("Z", -(zAmount.value))
        }
        zRight.setOnAction {
            duetController.moveAxis("Z", zAmount.value)
        }

        extrude.setOnAction {
            duetController.extrude(feedAmountCB.value.toDouble(), feedRateCB.value)
        }

        retract.setOnAction {
            duetController.extrude(-feedAmountCB.value.toDouble(), feedRateCB.value)
        }
    }

    private fun bindHomeButton(homeButton: Button, homed: ObservableValue<Boolean>, axisName: String) {
        homed.addListener { _, _, newValue ->
            homeButton.toggleClass("primary", newValue)
            homeButton.toggleClass("warning", !newValue)
        }
        homeButton.setOnMouseClicked {
            duetController.homeAxis(axisName)
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