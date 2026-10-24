@echo off
rem Starts the interactive terminal. Optional arguments: host port (default localhost 8583).
cd /d "%~dp0"
mvn -q compile exec:java -Dexec.mainClass=practice.iso.Terminal -Dexec.args="%*"
