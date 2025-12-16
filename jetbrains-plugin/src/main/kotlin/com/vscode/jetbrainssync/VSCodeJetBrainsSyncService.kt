package com.vscode.jetbrainssync

import com.google.gson.Gson
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI

data class EditorState(
    val filePath: String,
    val line: Int,
    val column: Int,
    val source: String? = "jetbrains",
    val isActive: Boolean = false,
    val action: String
)

@Service(Service.Level.PROJECT)
class VSCodeJetBrainsSyncService(private val project: Project) {
    private var webSocket: WebSocketClient? = null
    private var isActive = false
    private var isHandlingExternalUpdate = false
    private var isConnected = false
    private var isReconnecting = false
    private var autoReconnect = false
    private val gson = Gson()
    private var log: Logger = Logger.getInstance(VSCodeJetBrainsSyncService::class.java)

    init {
        log.info("Initializing VSCodeJetBrainsSyncService")
        setupStatusBar()
        connectWebSocket()
        setupEditorListeners()
        setupWindowListeners()
    }

    private fun setupStatusBar() {
        ApplicationManager.getApplication().invokeLater {
            SyncStatusBarWidgetFactory().createWidget(project)
        }
    }

    private fun updateStatusBarWidget() {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = WindowManager.getInstance().getStatusBar(project)
            val widget = statusBar?.getWidget(SyncStatusBarWidget.ID) as? SyncStatusBarWidget
            widget?.updateUI()
        }
    }

    fun toggleAutoReconnect() {
        autoReconnect = !autoReconnect

        if (!autoReconnect) {
            // Close the connection when turning sync off
            webSocket?.let { client ->
                if (client.isOpen) {
                    client.close()
                }
            }
            webSocket = null
            isConnected = false
            isReconnecting = false
            log.info("Sync disabled and connection closed")
        } else {
            log.info("Sync enabled")
            isReconnecting = true
            updateStatusBarWidget()
            connectWebSocket()
        }

        updateStatusBarWidget()
    }

    private fun connectWebSocket() {
        if (!autoReconnect) {
            isReconnecting = false
            updateStatusBarWidget()
            log.info("Auto-reconnect is disabled")
            return
        }

        isReconnecting = true
        updateStatusBarWidget()
        log.info("Attempting to connect to VSCode...")
        try {
            val port = VSCodeJetBrainsSyncSettings.getInstance(project).state.port
            webSocket = object : WebSocketClient(URI("ws://localhost:${port}/jetbrains")) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    log.info("Successfully connected to VSCode on port $port")
                    isConnected = true
                    isReconnecting = false
                    updateStatusBarWidget()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("VSCode JetBrains Sync")
                        .createNotification("Connected to VSCode", NotificationType.INFORMATION)
                        .notify(project)
                    // Send initial state
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                    if (editor != null && file != null) {
                        updateStateFromEditor(editor, file)
                    }
                }

                override fun onMessage(message: String?) {
                    log.info("Received message: $message")
                    message?.let {
                        try {
                            val state = gson.fromJson(it, EditorState::class.java)
                            if (state.source != "jetbrains") {
                                handleIncomingState(state)
                            }
                        } catch (e: Exception) {
                            log.info("Error parsing message: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    log.info("Disconnected from VSCode. Code: $code, Reason: $reason, Remote: $remote")
                    isConnected = false
                    updateStatusBarWidget()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("VSCode JetBrains Sync")
                        .createNotification("Disconnected from VSCode", NotificationType.WARNING)
                        .notify(project)
                    if (!isReconnecting && autoReconnect) {
                        isReconnecting = true
                        updateStatusBarWidget()
                        ApplicationManager.getApplication().executeOnPooledThread {
                            log.info("Attempting to reconnect in 5 seconds...")
                            Thread.sleep(5000)
                            ApplicationManager.getApplication().invokeLater {
                                connectWebSocket()
                            }
                        }
                    }
                }

                override fun onError(ex: Exception?) {
                    log.info("WebSocket error occurred:")
                    isConnected = false
                    updateStatusBarWidget()
                    ex?.printStackTrace()
                }
            }

            webSocket?.connectionLostTimeout = 0
            log.info("Connecting to VSCode...")
            ApplicationManager.getApplication().executeOnPooledThread {
                val connectResult = webSocket?.connectBlocking()
                log.info("Connection attempt result: $connectResult")
                if (connectResult != true) {
                    isReconnecting = true
                    updateStatusBarWidget()
                    Thread.sleep(5000)
                    ApplicationManager.getApplication().invokeLater {
                        connectWebSocket()
                    }
                }
            }

        } catch (e: Exception) {
            log.info("Failed to initialize WebSocket connection:")
            isConnected = false
            isReconnecting = true
            updateStatusBarWidget()
            e.printStackTrace()
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(5000)
                ApplicationManager.getApplication().invokeLater {
                    connectWebSocket()
                }
            }
        }
    }

    private fun setupEditorListeners() {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (isHandlingExternalUpdate) return

                    source.selectedTextEditor?.let {
                        updateStateFromEditor(it, file)
                        setupCaretListener(it, file)
                    }

                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (isHandlingExternalUpdate) {
                        log.info("Skipping fileClosed event (handling external update): ${file.path}")
                        return
                    }

                    log.info("File closed in IntelliJ: ${file.path}")
                    log.info("IsActive: $isActive, IsConnected: $isConnected")
                    
                    val state = EditorState(
                        filePath = file.path,
                        line = 0,
                        column = 0,
                        isActive = isActive,
                        action = "close"
                    )
                    
                    log.info("Sending close event: ${gson.toJson(state)}")
                    updateState(state)
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile
                    if (isHandlingExternalUpdate || file == null) {
                        return
                    }

                    FileEditorManager.getInstance(project).selectedTextEditor?.let {
                        updateStateFromEditor(it, file)
                        setupCaretListener(it, file)
                    }

                }
            }
        )
    }

    private fun setupCaretListener(editor: Editor, file: VirtualFile) {
        editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                if (!isHandlingExternalUpdate) {
                    updateStateFromEditor(editor, file)
                }
            }
        })
    }

    private fun setupWindowListeners() {
        val frame = WindowManager.getInstance().getFrame(project)
        frame?.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                if (frame.isVisible && frame.state != java.awt.Frame.ICONIFIED && frame.isFocused) {
                    isActive = true
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                    if (editor != null && file != null) {
                        updateStateFromEditor(editor, file)
                    }
                }
            }

            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                isActive = false
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (editor != null && file != null) {
                    updateStateFromEditor(editor, file)
                }
            }
        })
    }

    private fun updateStateFromEditor(editor: Editor, file: VirtualFile) {
        updateState(
            EditorState(
                filePath = file.path,
                line = editor.caretModel.logicalPosition.line,
                column = editor.caretModel.logicalPosition.column,
                isActive = isActive,
                action = "select"
            )
        )
    }

    private fun handleIncomingState(state: EditorState) {
        // Always handle close events, regardless of active state
        if (state.action == "close") {
            log.info("Received close event for file: ${state.filePath}")
            isHandlingExternalUpdate = true
            try {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val file = File(state.filePath)
                        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                        virtualFile?.let {
                            log.info("Closing file in JetBrains: ${it.path}")
                            FileEditorManager.getInstance(project).closeFile(it)
                        } ?: run {
                            log.info("File not found: ${state.filePath}")
                        }
                    } catch (e: Exception) {
                        log.info("Error closing file: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } finally {
                isHandlingExternalUpdate = false
            }
            return
        }

        // Only handle incoming state if the other IDE is active
        if (!state.isActive) {
            log.info("Ignoring update from inactive VSCode")
            return
        }

        isHandlingExternalUpdate = true
        try {
            ApplicationManager.getApplication().invokeLater {
                try {
                    val file = File(state.filePath)
                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)

                    virtualFile?.let { it ->
                        val fileEditorManager = FileEditorManager.getInstance(project)
                        // first check if the file is already open
                        val existingEditor =
                            fileEditorManager.selectedEditors.firstOrNull { it.file == virtualFile } as? TextEditor
                        val editor =
                            existingEditor ?: fileEditorManager.openFile(it, false).firstOrNull() as? TextEditor

                        editor?.let { textEditor ->
                            val position = LogicalPosition(state.line, state.column)
                            ApplicationManager.getApplication().runWriteAction {
                                textEditor.editor.caretModel.moveToLogicalPosition(position)

                                // only scroll if the caret is not visible
                                val visibleArea = textEditor.editor.scrollingModel.visibleArea
                                val targetPoint = textEditor.editor.logicalPositionToXY(position)
                                if (!visibleArea.contains(targetPoint)) {
                                    textEditor.editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.info("Error handling incoming state: ${e.message}")
                }
            }
        } finally {
            isHandlingExternalUpdate = false
        }
    }

    private fun updateState(state: EditorState) {
        if (isHandlingExternalUpdate) {
            log.info("Skipping updateState (handling external update)")
            return
        }

        webSocket?.let { client ->
            if (client.isOpen) {
                // Always send close events, regardless of active state
                val shouldSend = state.action == "close" || isActive
                log.info("updateState: action=${state.action}, isActive=$isActive, shouldSend=$shouldSend")
                
                if (shouldSend) {
                    val jsonState = gson.toJson(state)
                    log.info(
                        "Sending state update (JetBrains is ${if (isActive) "active" else "inactive"}): $jsonState"
                    )
                    try {
                        client.send(jsonState)
                        log.info("State update sent successfully")
                    } catch (e: Exception) {
                        log.info("Error sending state update: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    log.info("Skipping state update (JetBrains is not active and not a close event)")
                }
            } else {
                log.info("WebSocket is not open. Current state: ${client.readyState}")
                if (!isReconnecting) {
                    connectWebSocket()
                }
            }
        } ?: run {
            log.info("WebSocket client is null, attempting to reconnect...")
            connectWebSocket()
        }

    }

    fun isConnected(): Boolean = isConnected
    fun isAutoReconnectEnabled(): Boolean = autoReconnect
    fun isReconnecting(): Boolean = isReconnecting

    fun restartConnection() {
        webSocket?.let { client ->
            if (client.isOpen) {
                client.close()
            }
        }
        webSocket = null
        isConnected = false
        connectWebSocket()
    }
}