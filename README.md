# IDE Sync - VSCode-JetBrains IDE Sync

>**Note:** This synchronization system is suitable for VSCode, VSCode forks like Cursor or Windsurf as well as JetBrains IntelliJ-based IDEs like Rider, IntelliJ IDEA and WebStorm.

A synchronization system that allows seamless switching between VSCode and JetBrains IntelliJ-based IDEs while maintaining the current file and cursor position.

## Installation via Marketplace

### VSCode Extension
Install directly from the [Visual Studio Code Marketplace](https://marketplace.visualstudio.com/items?itemName=denisbalber.vscode-jetbrains-sync)

- Open VSCode
- Go to Extensions (Ctrl+Shift+X)
- Search for "IDE Sync - Connect to JetBrains IDE"
- Click Install

### JetBrains IDE Plugin
Install directly from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26201-ide-sync--connect-to-vscode)
- Open JetBrains IDE
- Go to Settings > Plugins
- Search for "IDE Sync - Connect to VSCode"
- Click Install

## Version Compatibility

### VSCode
- Supported versions: VSCode 1.84.0 and newer

### JetBrains IDE
- Supported versions: 2023.3 and newer

## Configuration

The default port is 3000, this can be changed in the respective settings and must be the same:
- In VSCode: Settings > Extensions > IDE Sync - Connect to JetBrains IDE > Port
- In JetBrains IDE: Settings > Tools > IDE Sync - Connect to VSCode > WebSocket Port

## Usage

1. Start both IDEs
2. Make sure that the sync is activated in the status bar of both IDEs
3. When you switch between IDEs, the current file and cursor position will be synchronized automatically

## Features

- Real-time synchronization of file opening and cursor position
- Automatic reconnection on port changes
- Status bar indicator showing connection status

## Components

### VSCode Extension
- Located in `/vscode-extension`
- Monitors current file and cursor position in VSCode
- Communicates with JetBrains plugin via WebSocket

### JetBrains IDE Plugin
- Located in `/jetbrains-plugin`
- Monitors current file and cursor position in JetBrains IDE
- Communicates with VSCode extension via WebSocket

## Building

### Prerequisites
- Node.js and npm for VSCode extension
- JDK 21+ and Gradle for JetBrains IDE plugin

## Development

### VSCode Extension (run & debug)

Prerequisites:
- Node.js + npm

Steps:
1. Open the repository root in VSCode (or a VSCode fork like Cursor).
2. Install dependencies and start TypeScript watch build:
```bash
cd vscode-extension
npm install
npm run watch
```
3. Start the extension in **Extension Development Host**:
   - Open `vscode-extension` in VSCode
   - Press `F5` (Run -> Start Debugging)
4. In the Extension Development Host, open any workspace and enable sync via the status bar toggle.

Notes:
- If you don't want watch mode, use `npm run build` instead.
- The extension uses `dist/extension.js` as the entrypoint; `watch` keeps it up-to-date.

### JetBrains IDE Plugin (run & debug)

Prerequisites:
- JDK 21 (the Gradle build targets Java 21)

Steps:
1. From the repository root, start the IDE sandbox with the plugin:
```bash
cd jetbrains-plugin
./gradlew runIde
```
2. In the sandbox IDE, enable sync via:
   - Settings -> Tools -> IDE Sync - Connect to VSCode
   - Ensure the port matches VSCode extension (default: 3000)

#### Run from IntelliJ IDEA (Debug)

Option A (Run/Debug Configuration):
1. Open the `jetbrains-plugin` folder in IntelliJ IDEA and import it as a **Gradle** project (if prompted).
2. Open: Run -> Edit Configurations...
3. Click `+` -> **Gradle**
4. Set:
   - **Gradle project**: `jetbrains-plugin`
   - **Tasks** (Run): `runIde`
5. Click **Debug** on this configuration.
6. Set breakpoints in the plugin source, then use the sandbox IDE instance that starts.

Option B (Gradle tool window):
1. Open the **Gradle** tool window (View -> Tool Windows -> Gradle).
2. Navigate to: `Tasks` -> `intellijPlatform` -> `runIde`.
3. Right-click `runIde` and choose **Debug**.

Notes:
- `runIde` launches a sandbox IDE instance with the plugin installed.
- If you change Kotlin code, rerun `runIde` (or use Gradle continuous build if you prefer).
- On Windows, use `gradlew.bat` instead of `./gradlew` (e.g. `gradlew.bat runIde`).

### End-to-end dev check

1. Start the JetBrains sandbox IDE (`./gradlew runIde`).
2. Start the VSCode Extension Development Host (`F5`) with `npm run watch` running.
3. Turn sync **On** in both IDEs.
4. Open a file and move the cursor in one IDE — the other IDE should follow.

### Build Steps

1. Clone the repository
```bash
git clone https://github.com/denisbalber/IDESync-VSCode-JetBrains.git
cd IDESync-VSCode-JetBrains
```

2. Build VSCode extension
```bash
cd vscode-extension
npm install
npm run build
npm run package
cd ..
```

3. Build JetBrains plugin
```bash
cd jetbrains-plugin
./gradlew buildPlugin
cd ..
```

## Installation

### VSCode Extension
1. Install the VSCode extension: `Ctrl+Shift+P` > `Extensions: Install from VSIX...` > Select `IDESync-VSCode-JetBrains/vscode-extension/vscode-jetbrains-sync-0.1.0.vsix`
2. Restart VSCode

### JetBrains IDE Plugin
1. Install the JetBrains IDE plugin: `Settings` > `Plugins` > `Manage Repositories,... (Settings symbol)` > `Install Plugin from Disk...` > Select  `IDESync-VSCode-JetBrains/jetbrains-plugin/build/distributions/vscode-jetbrains-sync-1.0.0`
2. Restart JetBrains IDE