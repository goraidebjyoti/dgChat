#!/bin/sh
set -eu
dg_project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then dg_java="$JAVA_HOME/bin/java"; else dg_java=java; fi
if [ -f "$dg_project/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec "$dg_java" -classpath "$dg_project/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
exec "$dg_java" -classpath "$dg_project/gradle/wrapper/dgchat-bootstrap.jar" io.github.goraidebjyoti.dgchat.build.GradleBootstrap "$@"
