import * as vscode from 'vscode';
import WebSocket, { WebSocketServer, RawData } from 'ws';

interface SyncState {
    filePath: string;
    line: number;
    column: number;
    source: 'vscode' | 'jetbrains';
    isActive: boolean;
    action: 'close' | 'select';  // Add action field for document close events
}

class Logger {
    private outputChannel: vscode.OutputChannel;

    constructor(channelName: string) {
        this.outputChannel = vscode.window.createOutputChannel(channelName);
    }

    private formatMessage(level: string, message: string): string {
        const timestamp = new Date().toISOString();
        return `[${timestamp}] [${level}] ${message}`;
    }

    info(message: string) {
        const formattedMessage = this.formatMessage('INFO', message);
        this.outputChannel.appendLine(formattedMessage);
    }

    warn(message: string) {
        const formattedMessage = this.formatMessage('WARN', message);
        this.outputChannel.appendLine(formattedMessage);
    }

    error(message: string, error?: Error) {
        const formattedMessage = this.formatMessage('ERROR', message);
        this.outputChannel.appendLine(formattedMessage);
        if (error) {
            this.outputChannel.appendLine(this.formatMessage('ERROR', `Stack: ${error.stack}`));
        }
    }

    debug(message: string) {
        const formattedMessage = this.formatMessage('DEBUG', message);
        this.outputChannel.appendLine(formattedMessage);
    }

    dispose() {
        this.outputChannel.dispose();
    }
}

export class VSCodeJetBrainsSync {
    private wss: WebSocketServer | null = null;
    private jetbrainsClient: WebSocket | null = null;
    private disposables: vscode.Disposable[] = [];
    private currentState: SyncState | null = null;
    private isActive: boolean = false;
    private statusBarItem: vscode.StatusBarItem;
    private isConnected: boolean = false;
    private autoReconnect: boolean = false;
    private logger: Logger;

    constructor() {
        this.logger = new Logger('IDE Sync');
        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'vscode-jetbrains-sync.toggleAutoReconnect';
        this.updateStatusBarItem();
        this.statusBarItem.show();
        
        this.setupServer();
        this.setupEditorListeners();
        this.setupWindowListeners();
        this.isActive = vscode.window.state.focused;
        this.logger.info('VSCodeJetBrainsSync initialized');
    }

    private updateStatusBarItem() {
        let icon = '$(sync~spin)';
        if (this.isConnected) {
            icon = '$(check)';
        } else if (!this.autoReconnect) {
            icon = '$(sync-ignored)';
        }
        
        this.statusBarItem.text = `${icon} ${this.autoReconnect ? 'IDE Sync On' : 'Turn IDE Sync On'}`;
        this.statusBarItem.tooltip = `${this.isConnected ? 'Connected to JetBrains IDE\n' : 'Waiting for JetBrains IDE connection\n'}Click to turn sync ${this.autoReconnect ? 'off' : 'on'}`;
    }

    public toggleAutoReconnect() {
        this.autoReconnect = !this.autoReconnect;
        
        if (!this.autoReconnect) {
            // Close existing connections when turning sync off
            if (this.jetbrainsClient) {
                this.jetbrainsClient.close();
                this.jetbrainsClient = null;
            }
            if (this.wss) {
                this.wss.close(() => {
                    this.logger.info('WebSocket server closed');
                });
                this.wss = null;
            }
            this.isConnected = false;
            vscode.window.showInformationMessage('Sync disabled');
        } else {
            vscode.window.showInformationMessage('Sync enabled');
            this.setupServer();
        }
        
        this.updateStatusBarItem();
    }

    private setupServer() {
        if (!this.autoReconnect) {
            this.logger.info('Auto-reconnect is disabled');
            return;
        }

        if (this.wss) {
            this.wss.close(() => {
                this.logger.info('Closing existing WebSocket server');
            });
        }

        const port = vscode.workspace.getConfiguration('vscode-jetbrains-sync').get('port', 3000);
        this.wss = new WebSocketServer({ port });
        this.logger.info(`Starting WebSocket server on port ${port}...`);
        
        this.wss.on('connection', (ws: WebSocket, request) => {
            const clientType = request.url?.slice(1);

            if (clientType === 'jetbrains') {
                if (this.jetbrainsClient) {
                    this.jetbrainsClient.close();
                }
                this.jetbrainsClient = ws;
                this.isConnected = true;
                this.updateStatusBarItem();
                this.logger.info('JetBrains IDE client connected');
                vscode.window.showInformationMessage('JetBrains IDE connected');
            } else {
                ws.close();
                return;
            }

            ws.on('message', (data: RawData) => {
                try {
                    const state: SyncState = JSON.parse(data.toString());
                    this.logger.debug(`Received message: ${JSON.stringify(state)}`);
                    this.handleIncomingState(state);
                } catch (error) {
                    this.logger.error('Error parsing message:', error as Error);
                    vscode.window.showErrorMessage('Error handling sync message');
                }
            });

            ws.on('close', () => {
                if (this.jetbrainsClient === ws) {
                    this.jetbrainsClient = null;
                    this.isConnected = false;
                    this.updateStatusBarItem();
                    this.logger.warn('JetBrains IDE client disconnected');
                    vscode.window.showWarningMessage('JetBrains IDE disconnected');
                }
            });

            ws.on('error', (error: Error) => {
                this.logger.error('WebSocket error:', error);
                this.isConnected = false;
                this.updateStatusBarItem();
                vscode.window.showErrorMessage('WebSocket error occurred');
            });
        });

        this.wss.on('listening', () => {
            this.logger.info(`WebSocket server is listening on port ${port}`);
        });

        this.wss.on('error', (error: Error) => {
            this.logger.error('WebSocket server error:', error);
            vscode.window.showErrorMessage('Failed to start WebSocket server');
        });
    }

    private setupEditorListeners() {
        // Listen for tab close events (more precise than onDidCloseTextDocument, which may not fire
        // when a document is still open in another editor group).
        this.disposables.push(
            vscode.window.tabGroups.onDidChangeTabs((e) => {
                if (this.isHandlingExternalUpdate) {
                    return;
                }

                for (const tab of e.closed) {
                    const tabInput = tab.input as unknown as { uri?: vscode.Uri };
                    const uri = tabInput?.uri;
                    if (!uri || uri.scheme !== 'file') {
                        continue;
                    }

                    this.logger.info(`Tab closed in VSCode: ${uri.fsPath}`);
                    this.updateState({
                        filePath: uri.fsPath,
                        line: 0,
                        column: 0,
                        source: 'vscode',
                        isActive: this.isActive,
                        action: 'close'
                    });
                }
            })
        );

        // Listen for active editor changes
        this.disposables.push(
            vscode.window.onDidChangeActiveTextEditor((editor) => {
                if (editor && !this.isHandlingExternalUpdate) {
                    const document = editor.document;
                    const position = editor.selection.active;
                    this.updateState({
                        filePath: document.uri.fsPath,
                        line: position.line,
                        column: position.character,
                        source: 'vscode',
                        isActive: this.isActive,
                        action: 'select'
                    });
                }
            })
        );

        // Listen for document close events
        this.disposables.push(
            vscode.workspace.onDidCloseTextDocument((document) => {
                if (!this.isHandlingExternalUpdate) {
                    this.logger.info(`Document closed in VSCode: ${document.uri.fsPath}`);
                    this.updateState({
                        filePath: document.uri.fsPath,
                        line: 0,
                        column: 0,
                        source: 'vscode',
                        isActive: this.isActive,
                        action: 'close'
                    });
                }
            })
        );

        // Listen for cursor position changes
        this.disposables.push(
            vscode.window.onDidChangeTextEditorSelection((event) => {
                if (event.textEditor === vscode.window.activeTextEditor && !this.isHandlingExternalUpdate) {
                    const document = event.textEditor.document;
                    const position = event.selections[0].active;
                    this.updateState({
                        filePath: document.uri.fsPath,
                        line: position.line,
                        column: position.character,
                        source: 'vscode',
                        isActive: this.isActive,
                        action: 'select'
                    });
                }
            })
        );
    }

    private setupWindowListeners() {
        this.disposables.push(
            vscode.window.onDidChangeWindowState((e) => {
                this.isActive = e.focused;
                if (this.currentState) {
                    const state: SyncState = {
                        ...this.currentState,
                        isActive: this.isActive,
                        source: 'vscode'
                    };
                    this.updateState(state);
                }
            })
        );
    }

    private isHandlingExternalUpdate = false;

    private async handleIncomingState(state: SyncState) {
        if (state.source === 'vscode') {
            return; // Ignore our own updates
        }

        // Always handle close events, regardless of active state
        if (state.action === 'close') {
            try {
                this.isHandlingExternalUpdate = true;
                this.logger.info(`Received close event for file: ${state.filePath}`);
                
                // Try to find the document using URI comparison
                const targetUri = vscode.Uri.file(state.filePath);
                const documents = vscode.workspace.textDocuments;
                const visibleEditors = vscode.window.visibleTextEditors;
                
                this.logger.debug(`Open documents count: ${documents.length}, Visible editors: ${visibleEditors.length}`);
                
                // First try to find in textDocuments
                let editorToClose = documents.find(doc => {
                    const matches = doc.uri.fsPath === targetUri.fsPath || 
                                   doc.uri.toString() === targetUri.toString() ||
                                   doc.uri.fsPath.toLowerCase() === targetUri.fsPath.toLowerCase();
                    return matches;
                });
                
                // If not found, try to find in visible editors
                if (!editorToClose) {
                    const matchingEditor = visibleEditors.find(editor => {
                        const matches = editor.document.uri.fsPath === targetUri.fsPath ||
                                       editor.document.uri.fsPath.toLowerCase() === targetUri.fsPath.toLowerCase();
                        return matches;
                    });
                    if (matchingEditor) {
                        editorToClose = matchingEditor.document;
                    }
                }
                
                if (editorToClose) {
                    this.logger.info(`Closing document in VSCode: ${editorToClose.uri.fsPath}`);
                    
                    // Use tabGroups API to close the tab without activating it
                    let tabClosed = false;
                    for (const tabGroup of vscode.window.tabGroups.all) {
                        const tab = tabGroup.tabs.find(t => {
                            const tabInput = t.input as any;
                            return tabInput?.uri?.fsPath === editorToClose.uri.fsPath ||
                                   tabInput?.uri?.fsPath?.toLowerCase() === editorToClose.uri.fsPath.toLowerCase();
                        });
                        
                        if (tab) {
                            this.logger.debug(`Found tab in group ${tabGroup.viewColumn}, closing without activation`);
                            await vscode.window.tabGroups.close(tab);
                            tabClosed = true;
                            break;
                        }
                    }
                    
                    if (!tabClosed) {
                        this.logger.warn(`Tab not found in tabGroups for: ${editorToClose.uri.fsPath}`);
                    }
                } else {
                    // Log all open documents for debugging
                    const openPaths = documents.map(d => d.uri.fsPath).join('\n  ');
                    this.logger.warn(`Document not found. Looking for: ${targetUri.fsPath}`);
                    this.logger.warn(`Open documents:\n  ${openPaths}`);
                }
                return;
            } catch (error) {
                this.logger.error('Error closing document:', error as Error);
            } finally {
                this.isHandlingExternalUpdate = false;
            }
        }

        // Only handle incoming state if the other IDE is active
        if (!state.isActive) {
            this.logger.info('Ignoring update from inactive JetBrains IDE');
            return;
        }

        try {
            this.isHandlingExternalUpdate = true;
            
            const uri = vscode.Uri.file(state.filePath);
            const document = await vscode.workspace.openTextDocument(uri);
            const editor = await vscode.window.showTextDocument(document, {preview: false});
            
            const position = new vscode.Position(state.line, state.column);
            editor.selection = new vscode.Selection(position, position);
            
            editor.revealRange(
                new vscode.Range(position, position),
                vscode.TextEditorRevealType.InCenter
            );
        } catch (error) {
            this.logger.error('Error handling incoming state:', error as Error);
            vscode.window.showErrorMessage(`Failed to open file: ${state.filePath}`);
        } finally {
            this.isHandlingExternalUpdate = false;
        }
    }

    public updateState(state: SyncState) {
        this.currentState = state;
        if (this.jetbrainsClient?.readyState === WebSocket.OPEN) {
            try {
                // Always send close events, regardless of active state
                if (state.action === 'close' || this.isActive) {
                    this.logger.info(`Sending state update (VSCode is ${this.isActive ? 'active' : 'inactive'}):`);
                    this.logger.debug(JSON.stringify(state));
                    this.jetbrainsClient.send(JSON.stringify(state));
                } else {
                    this.logger.info('Skipping state update (VSCode is not active)');
                }
            } catch (error) {
                this.logger.error('Error sending state:', error as Error);
                vscode.window.showErrorMessage('Failed to sync VSCode position');
            }
        }
    }

    public dispose() {
        if (this.wss) {
            this.wss.close(() => {
                this.logger.info('WebSocket server closed');
            });
        }
        this.statusBarItem.dispose();
        this.logger.dispose();
        this.disposables.forEach(d => d.dispose());
    }
}

// Export activation and deactivation functions
let syncInstance: VSCodeJetBrainsSync | null = null;

export function activate(context: vscode.ExtensionContext) {
    syncInstance = new VSCodeJetBrainsSync();
    
    context.subscriptions.push(
        vscode.commands.registerCommand('vscode-jetbrains-sync.toggleAutoReconnect', () => {
            syncInstance?.toggleAutoReconnect();
        })
    );
    
    context.subscriptions.push({
        dispose: () => syncInstance?.dispose()
    });
}

export function deactivate() {
    syncInstance?.dispose();
} 