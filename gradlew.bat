@echo off
setlocal
set DGCHAT_JAVA=java.exe
if defined JAVA_HOME set DGCHAT_JAVA=%JAVA_HOME%\bin\java.exe
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  "%DGCHAT_JAVA%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
  "%DGCHAT_JAVA%" -classpath "%~dp0gradle\gradle\wrapper\dgchat-bootstrap.jar" io.github.goraidebjyoti.dgchat.build.GradleBootstrap %*
)
exit /b %ERRORLEVEL%
