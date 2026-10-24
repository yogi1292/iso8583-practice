@echo off
rem Starts the practice issuer host. Optional argument: port (default 8583).
cd /d "%~dp0"
mvn -q compile exec:java -Dexec.mainClass=practice.iso.IssuerHost -Dexec.args="%*"
