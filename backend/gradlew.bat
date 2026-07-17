@echo off
rem Simplified Gradle wrapper launcher (delegates to the official gradle-wrapper.jar)
setlocal
set DIRNAME=%~dp0
if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java
)
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
