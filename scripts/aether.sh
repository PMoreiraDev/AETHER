#!/usr/bin/env bash
#
# AETHER launch script (Linux / macOS).
# Starts the AETHER JavaFX desktop application.
#
# Works in two layouts:
#   1. Source tree (after `mvn -B -DskipTests package` or `mvn compile
#      dependency:copy-dependencies`): classpath = target/classes:target/lib/*
#   2. Extracted distribution zip: classpath = AETHER-*.jar:lib/* (+resources)
#
# Usage:
#   ./scripts/aether.sh            # from the project root
#   ./aether.sh                    # from inside the extracted distribution
#
# Environment variables:
#   JAVA_HOME        Force a specific JDK 21 installation.
#   AETHER_PRISM_ORDER  Override the rendering pipeline (default: sw).
#                      Use "hw" / "es2" for hardware acceleration.
#
set -euo pipefail

# --- Locate the application home (parent of this script's directory). ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Detect a Java 21 runtime. ---
# Preference order: $JAVA_HOME (if it is a JDK 21) -> `java` on PATH (if 21)
# -> well-known JDK 21 install locations (the "JDK 21 fallback").
java_is_21() {
  "$1" -version 2>&1 | grep -qE 'version "(1\\.)?21\.'
}

resolve_java() {
  # 1) JAVA_HOME
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if java_is_21 "$JAVA_HOME/bin/java"; then
      printf '%s\n' "$JAVA_HOME/bin/java"
      return 0
    fi
  fi
  # 2) java on PATH
  if command -v java >/dev/null 2>&1; then
    if java_is_21 "$(command -v java)"; then
      printf '%s\n' "java"
      return 0
    fi
  fi
  # 3) Fallback: common JDK 21 locations
  local candidate
  for candidate in \
    "/usr/lib/jvm/java-21-openjdk-amd64/bin/java" \
    "/usr/lib/jvm/java-21-openjdk/bin/java" \
    "/usr/lib/jvm/java-1.21.0-openjdk/bin/java" \
    "/usr/lib/jvm/java-21-openjdk-arm64/bin/java" \
    "/opt/jdk-21/bin/java" \
    "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java" \
    "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin/java"; do
    if [ -x "$candidate" ] && java_is_21 "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JAVACMD="$(resolve_java)" || {
  echo "ERROR: Java 21 (JDK) was not found." >&2
  echo "Install a JDK 21, ensure 'java' is on PATH, or set JAVA_HOME." >&2
  exit 1
}

# --- Build the module path and classpath. ---
# JavaFX modules must be resolved on the module path: LauncherApp extends
# javafx.application.Application, so the JDK launcher takes the JavaFX launch
# path, which requires the javafx.* modules to be resolvable. Putting the
# JavaFX jars on -cp alone fails with "JavaFX runtime components are missing".
# The application jar/classes stay on the (unnamed) classpath.
if [ -d "$APP_HOME/target/classes" ]; then
  # Source tree layout.
  APP_CP="$APP_HOME/target/classes"
  MODULE_PATH="$APP_HOME/target/lib"
else
  # Distribution layout.
  JAR="$(ls "$APP_HOME"/AETHER-*.jar 2>/dev/null | head -n1 || true)"
  if [ -z "${JAR:-}" ]; then
    echo "ERROR: AETHER jar not found under $APP_HOME." >&2
    echo "Build it first with: mvn -B -DskipTests package" >&2
    exit 1
  fi
  APP_CP="$JAR"
  MODULE_PATH="$APP_HOME/lib"
  # Optional resources folder (fallback alongside the jar contents).
  if [ -d "$APP_HOME/resources" ]; then
    APP_CP="$APP_CP:$APP_HOME/resources"
  fi
fi

# --- Assemble the java invocation. ---
# prism.order=sw selects the software rendering pipeline, which keeps AETHER
# working on headless / CI / remote-display machines without a GPU. Set
# AETHER_PRISM_ORDER=hw (or edit below) to use hardware acceleration.
JAVA_ARGS=(
  -Dprism.order="${AETHER_PRISM_ORDER:-sw}"
  -cp "$APP_CP"
  app.LauncherApp
)

# Only add the module-path flags when the directory exists and is non-empty;
# --add-modules ALL-MODULE-PATH with an empty path is a hard error.
if [ -d "${MODULE_PATH:-}" ] && [ -n "$(ls -A "$MODULE_PATH" 2>/dev/null)" ]; then
  JAVA_ARGS=(
    --module-path "$MODULE_PATH"
    --add-modules ALL-MODULE-PATH
    "${JAVA_ARGS[@]}"
  )
fi

echo "Starting AETHER..."
echo "  JAVA: $JAVACMD"
echo "  HOME: $APP_HOME"
exec "$JAVACMD" "${JAVA_ARGS[@]}" "$@"
