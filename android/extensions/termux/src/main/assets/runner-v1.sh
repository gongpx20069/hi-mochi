#!/data/data/com.termux/files/usr/bin/bash
set -eu
umask 077
root="${HOME}/.local/state/mochi-termux"

valid_id() {
    [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
}

alive() {
    [ -f "$dir/process" ] || return 1
    read -r pid birth < "$dir/process"
    [ -r "/proc/$pid/stat" ] || return 1
    current=$(awk '{print $22}' "/proc/$pid/stat") || return 1
    [ "$birth" = "$current" ] && kill -0 -- "-$pid" 2>/dev/null
}

snapshot() {
    state=unknown
    code=-
    if [ -f "$dir/result" ]; then
        read -r state code < "$dir/result"
    elif alive; then
        state=running
        [ ! -f "$dir/stop" ] || state=stopping
    fi
    printf 'MOCHI_TASK_V1\n%s\n%s\n' "$state" "$code"
    for stream in stdout stderr; do
        if [ -f "$dir/$stream" ]; then
            head -c 16384 "$dir/$stream" | base64 | tr -d '\n'
        fi
        printf '\n'
    done
    truncated=0
    for stream in stdout stderr; do
        if [ -f "$dir/$stream" ] && [ "$(wc -c < "$dir/$stream")" -gt 16384 ]; then
            truncated=1
        fi
    done
    printf '%s\n' "$truncated"
}

action="${1:?action}"
if [ "$action" = probe ]; then
    for executable in bash setsid base64 head cat mkfifo awk sleep; do
        command -v "$executable" >/dev/null
    done
    printf 'MOCHI_READY_V1\n'
    exit
fi
id="${2:?task id}"
valid_id "$id" || exit 64
dir="$root/$id"

case "$action" in
run)
    mkdir -p "$dir"
    mkdir "$dir/submission"
    if [ -f "$dir/stop" ]; then
        printf 'stopped 0\n' > "$dir/result"
        snapshot
        exit
    fi
    printf '%s' "$3" | base64 -d > "$dir/command"
    workdir="$4"
    seconds="$5"
    [[ "$seconds" =~ ^[0-9]+$ ]] && [ "$seconds" -ge 1 ] && [ "$seconds" -le 1800 ] || exit 64
    # The supervisor, not the caller's wait, owns the deadline and process group.
    setsid bash "$0" worker "$id" "$workdir" &
    pid=$!
    if [ -r "/proc/$pid/stat" ]; then
        birth=$(awk '{print $22}' "/proc/$pid/stat")
        printf '%s %s\n' "$pid" "$birth" > "$dir/process"
    fi
    (
        sleep "$seconds" &
        sleeper=$!
        trap 'kill "$sleeper" 2>/dev/null || true; exit' TERM
        wait "$sleeper"
        if alive; then
            touch "$dir/timeout"
            kill -TERM -- "-$pid" 2>/dev/null || true
            sleep 3
            alive && kill -KILL -- "-$pid" 2>/dev/null || true
        fi
    ) < /dev/null > /dev/null 2>&1 &
    watchdog=$!
    code=0
    wait "$pid" || code=$?
    kill "$watchdog" 2>/dev/null || true
    wait "$watchdog" 2>/dev/null || true
    state=failed
    [ "$code" -ne 0 ] || state=succeeded
    [ ! -f "$dir/stop" ] || state=stopped
    [ ! -f "$dir/timeout" ] || state=timed_out
    printf '%s %s\n' "$state" "$code" > "$dir/result.tmp"
    mv "$dir/result.tmp" "$dir/result"
    rm -f "$dir/command" "$dir/out.pipe" "$dir/err.pipe"
    snapshot
    ;;
worker)
    # Keep the group leader identifiable until the supervisor escalates TERM to KILL.
    trap 'while :; do sleep 1; done' TERM
    # A stop requested between submission and process startup must not execute user code.
    for attempt in {1..100}; do
        [ ! -f "$dir/process" ] || break
        sleep 0.01
    done
    [ -f "$dir/process" ] || exit 70
    if [ -f "$dir/stop" ]; then exit 0; fi
    mkfifo "$dir/out.pipe" "$dir/err.pipe"
    (head -c 16385 > "$dir/stdout"; cat >/dev/null) < "$dir/out.pipe" &
    out=$!
    (head -c 16385 > "$dir/stderr"; cat >/dev/null) < "$dir/err.pipe" &
    err=$!
    code=0
    (cd -- "$3" && bash --noprofile --norc "$dir/command") \
        < /dev/null > "$dir/out.pipe" 2> "$dir/err.pipe" || code=$?
    wait "$out"
    wait "$err"
    exit "$code"
    ;;
read)
    [ -d "$dir" ] || exit 66
    snapshot
    ;;
stop)
    mkdir -p "$dir"
    if [ ! -f "$dir/result" ]; then touch "$dir/stop"; fi
    if alive; then
        kill -TERM -- "-$pid" 2>/dev/null || true
        sleep 1
        alive && kill -KILL -- "-$pid" 2>/dev/null || true
    fi
    snapshot
    ;;
forget)
    [ -f "$dir/result" ] && ! alive || exit 65
    rm -f "$dir/stdout" "$dir/stderr" "$dir/process" "$dir/result" "$dir/stop" "$dir/timeout"
    rmdir "$dir/submission"
    rmdir "$dir"
    ;;
*)
    exit 64
    ;;
esac
