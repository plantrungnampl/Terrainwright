# S1 Toolchain Evidence

- Captured: `2026-08-10T21:20:44.4001651+07:00`
- Host: Windows 11, amd64
- Scope: toolchain bootstrap only; client and dedicated-server launch are not evaluated here.

## Preflight

Before installation, `Get-Command java`, `Get-Command javac`, and `Test-Path .\gradlew.bat` reported `False`, `False`, and `False` respectively.

## Installed toolchain

Command:

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK --exact --version 25.0.4.7 --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
```

Exit code: `0`.

Resolved package: `EclipseAdoptium.Temurin.25.JDK 25.0.4.7`. The current Codex host process predated the installer PATH update, so verification used the installed executable at `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot` explicitly.

```text
openjdk version "25.0.4" 2026-07-21 LTS
OpenJDK Runtime Environment Temurin-25.0.4+7
javac 25.0.4
```

## Gradle wrapper

The wrapper files were retrieved without modification from the official Fabric example-mod `26.2` branch:

- `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradlew`
- `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradlew.bat`
- `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.jar`
- `https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.properties`

SHA-256 checksums:

```text
AB5C0CAD16305AF2E619C159C1F58DD68D07FAB9C11E36701E109C0277407F7A  gradlew
5C0A21ECD6B3A6292E0746BFF3B75FD2D8F47B9FF226CE53DC22B30184EF3BEC  gradlew.bat
497C8C2A7E5031F6AA847F88104AA80A93532EC32EE17BDB8D1D2F67A194A9C7  gradle/wrapper/gradle-wrapper.jar
E0846BD420BF1543F500E0C9B35C01D84611B0F1AFE01E24A82D0ECA53C33CCB  gradle/wrapper/gradle-wrapper.properties
```

Verification command:

```powershell
.\gradlew.bat --version --no-daemon
```

Exit code: `0`. Resolved Gradle version: `9.5.1`. Launcher JVM: Eclipse Adoptium `25.0.4+7-LTS`.
