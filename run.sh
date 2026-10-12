#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
    ""|build|test|selftest) ;;
    *) echo '用法：./run.sh [build | test | selftest [时钟单位数]]' >&2; exit 2 ;;
esac

SIMOS_JDK="${JAVA_HOME:-}"
if [[ -z "$SIMOS_JDK" && "$(uname -s)" == Darwin ]]; then
    SIMOS_JDK="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [[ -n "$SIMOS_JDK" ]]; then
    JAVA="$SIMOS_JDK/bin/java"; JAVAC="$SIMOS_JDK/bin/javac"; JAR="$SIMOS_JDK/bin/jar"
else
    JAVA="$(command -v java || true)"; JAVAC="$(command -v javac || true)"; JAR="$(command -v jar || true)"
fi
if [[ ! -x "$JAVA" || ! -x "$JAVAC" || ! -x "$JAR" ]]; then
    echo '需要完整的JDK 21或更高版本；请设置JAVA_HOME。' >&2; exit 1
fi
JDK_VERSION="$("$JAVAC" -version 2>&1)"
JDK_MAJOR="${JDK_VERSION#javac }"
JDK_MAJOR="${JDK_MAJOR%%.*}"
if [[ ! "$JDK_MAJOR" =~ ^[0-9]+$ ]] || (( JDK_MAJOR < 21 )); then
    echo "需要JDK 21或更高版本，当前为：$JDK_VERSION" >&2; exit 1
fi

mkdir -p target/classes
find target/classes -type f -name '*.class' -delete
find src/main/java -name '*.java' -print0 \
    | xargs -0 "$JAVAC" --release 21 -encoding UTF-8 -d target/classes
"$JAR" --create --file target/simos.jar --main-class cn.edu.scau.os.app.SimOsApp -C target/classes .
JAVA_OPTIONS=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)

case "${1:-}" in
    build) echo '构建完成：target/simos.jar' ;;
    selftest) "$JAVA" "${JAVA_OPTIONS[@]}" -cp target/classes cn.edu.scau.os.app.SimOsApp --selftest "${2:-600}" ;;
    test)
        mkdir -p target/test-classes
        find target/test-classes -type f -name '*.class' -delete
        find src/test/java -name '*.java' -print0 \
            | xargs -0 "$JAVAC" --release 21 -encoding UTF-8 -cp target/classes -d target/test-classes
        "$JAVA" "${JAVA_OPTIONS[@]}" -Djava.awt.headless=true -cp target/classes:target/test-classes cn.edu.scau.os.RequirementsRegressionTest
        "$JAVA" "${JAVA_OPTIONS[@]}" -Djava.awt.headless=true -cp target/classes:target/test-classes cn.edu.scau.os.ui.DirectoryTreeRegressionTest
        ;;
    "") exec "$JAVA" "${JAVA_OPTIONS[@]}" -jar target/simos.jar ;;
esac
