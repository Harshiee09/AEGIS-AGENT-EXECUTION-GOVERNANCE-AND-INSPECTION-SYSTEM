# AEGIS quickstart

```powershell
git clone https://github.com/Harshiee09/AEGIS-AGENT-EXECUTION-GOVERNANCE-AND-INSPECTION-SYSTEM.git
Set-Location AEGIS-AGENT-EXECUTION-GOVERNANCE-AND-INSPECTION-SYSTEM
```

```powershell
# Prerequisites: Windows, PowerShell, Java 17 JDK, MySQL Server 8.4,
# Git, and Ollama. MySQL Server 8.4 must be installed in its default location.
winget install --id EclipseAdoptium.Temurin.17.JDK --exact
winget install --id Ollama.Ollama --exact

java -version
javac -version
mysql --version
ollama --version

# Download the MySQL JDBC driver used by the project.
powershell -ExecutionPolicy Bypass -File .\scripts\setup-driver.ps1
```

```powershell
# Start Ollama in a terminal if it is not already running.
ollama serve
```

```powershell
# In another terminal, download the models to the local Ollama store.
ollama pull gemma4:latest
ollama pull nemotron-3-nano:4b
ollama list
```

```powershell
# Initialize the private local database, compile the Java source, and start AEGIS.
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

```powershell
# Optional: launch the Swing dashboard in another terminal.
powershell -ExecutionPolicy Bypass -File .\scripts\run-dashboard.ps1
```
