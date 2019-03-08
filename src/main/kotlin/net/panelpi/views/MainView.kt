package net.panelpi.views

import javafx.scene.Parent
import javafx.scene.chart.LineChart
import javafx.scene.chart.XYChart.Series
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import net.panelpi.controllers.DuetController
import net.panelpi.map
import tornadofx.*

class MainView : View() {
    override val root: Parent by fxml()
    private val duetController: DuetController by inject()

    private val duetData = duetController.duetData
    private val status: Label by fxid()
    private val centerPane: BorderPane by fxid()
    private val printerName: Label by fxid()

    private val controlButton: Label by fxid()
    private val statusButton: Label by fxid()
    private val consoleButton: Label by fxid()
    private val fileButton: Label by fxid()
    private val settingButton: Label by fxid()

    private val stop: Button by fxid()

    private val statusView: StatusView by inject()
    private val controlView: ControlView by inject()
    private val settingView: SettingView by inject()
    private val consoleView: ConsoleView by inject()
    private val fileView: FileView by inject()


    init {
        // Status icon
        status.bind(duetData.map { it.status })
        status.styleProperty().bind(duetData.map { "-fx-background-color: ${it.status.color}" })

        // Printer name
        printerName.bind(duetData.map { it.name })

        // Menu buttons
        val allButton = mapOf(
                statusButton to statusView,
                controlButton to controlView,
                consoleButton to consoleView,
                fileButton to fileView,
                settingButton to settingView)

        allButton.forEach { button, view ->
            button.setOnMouseClicked {
                if (!button.hasClass("menu-icon-selected")) {
                    centerPane.center = view.root
                    button.toggleClass("menu-icon-selected", true)
                    allButton.keys.filter { it != button }.forEach {
                        it.toggleClass("menu-icon-selected", false)
                    }
                }
            }
        }

        fileButton.addEventHandler(MouseEvent.MOUSE_CLICKED) {duetController.refreshSDData()}

        // Select control view by default.
        runLater {
            controlButton.fireEvent(MouseEvent(MouseEvent.MOUSE_CLICKED, 0.0, 0.0, 0.0, 0.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, true, false, false, null))
        }

        // Emergency stop button.
        stop.setOnAction { duetController.emergencyStop() }
    }
}
