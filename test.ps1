$env:JAVA_HOME = "D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64"
$mvn = "D:\maven\apache-maven-3.9.9\bin\mvn.cmd"
$logFile = "D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1\target\test_out.txt"
Set-Location "D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1"
& $mvn test 2>&1 | Out-File -FilePath $logFile -Encoding UTF8
Write-Output "TEST DONE with exit code: $LASTEXITCODE"