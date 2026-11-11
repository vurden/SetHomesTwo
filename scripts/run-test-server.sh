#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
server_dir="$repo_dir/run/spigot-1.21.11"
build_dir="$repo_dir/run/buildtools"

# Use the same Java version as the 1.21.11 server on macOS.
if [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

for tool in java mvn curl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing $tool. Install Java 21, Maven and curl, then try again." >&2
        exit 1
    fi
done

mkdir -p "$server_dir/plugins/SetHomesTwo" "$server_dir/plugins/bStats"

if ! [[ -f "$server_dir/eula.txt" ]] || ! grep -Eq '^eula=true\r?$' "$server_dir/eula.txt"; then
    echo "Minecraft EULA: https://aka.ms/MinecraftEULA"
    read -r -p "Do you accept the Minecraft EULA for this test server? [y/N] " answer
    case "$answer" in
        y|Y|yes|YES) echo 'eula=true' > "$server_dir/eula.txt" ;;
        *) echo 'EULA not accepted; server not started.'; exit 1 ;;
    esac
fi

if ! [[ -f "$server_dir/spigot-1.21.11.jar" ]]; then
    mkdir -p "$build_dir"
    echo "Building Spigot 1.21.11 with official BuildTools (first run takes several minutes)."
    curl --fail --location --silent --show-error \
        https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar \
        -o "$build_dir/BuildTools.jar"
    (
        cd "$build_dir"
        java -jar BuildTools.jar --rev 1.21.11 --compile SPIGOT --nogui --output-dir "$server_dir"
    )
fi

cd "$repo_dir"
echo 'Building and testing SetHomesTwo...'
mvn -B -Dmaven.repo.local="$repo_dir/run/maven-repository" clean verify
jars=("$repo_dir"/target/SetHomesTwo-*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
    echo 'Expected one built SetHomesTwo jar in target/.' >&2
    exit 1
fi
cp "${jars[0]}" "$server_dir/plugins/SetHomesTwo.jar"

# Keep the test world, settings and saved homes across launches.
if ! [[ -f "$server_dir/server.properties" ]]; then
    cat > "$server_dir/server.properties" <<'PROPERTIES'
server-ip=127.0.0.1
server-port=25585
online-mode=true
view-distance=5
simulation-distance=5
spawn-protection=0
motd=SetHomesTwo test server - Spigot 1.21.11
PROPERTIES
fi
if ! [[ -f "$server_dir/plugins/SetHomesTwo/config.yml" ]]; then
    sed -e 's/^debugLevel:.*/debugLevel: "info"/' \
        -e 's/^checkForUpdates:.*/checkForUpdates: false/' \
        "$repo_dir/src/main/resources/default-config.yml" > "$server_dir/plugins/SetHomesTwo/config.yml"
fi
if ! [[ -f "$server_dir/plugins/bStats/config.yml" ]]; then
    echo 'enabled: false' > "$server_dir/plugins/bStats/config.yml"
fi

echo 'Join with Minecraft Java 1.21.11 at localhost:25585.'
echo 'Try /sethome base, /homes, and /home base. Type stop in this console to shut down.'
echo "Server files and logs: $server_dir"
cd "$server_dir"
exec java -Xms512M -Xmx2G -jar spigot-1.21.11.jar --nogui
