#!/usr/bin/env bash
set -euo pipefail

if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    echo "Network isolation is only supported on disposable CI emulators." >&2
    exit 1
fi

adb root
adb wait-for-device
if [[ "$(adb shell id -u | tr -d '\r')" != "0" ]]; then
    echo "A root-capable emulator is required for smoke network isolation." >&2
    exit 1
fi

app_uid="$(adb shell cmd package list packages -U com.example.mochi_pet |
    tr -d '\r' |
    sed -n 's/^package:com\.example\.mochi_pet uid:\([0-9][0-9]*\)$/\1/p')"
if [[ ! "$app_uid" =~ ^[0-9]+$ ]] || (( app_uid < 10000 )); then
    echo "Could not resolve the installed Mochi application UID." >&2
    exit 1
fi

# Radio toggles do not cover every emulator network transport.
# Restrict the target UID, leaving adb and loopback fixtures reachable.
adb shell iptables -w -I OUTPUT 1 -m owner --uid-owner "$app_uid" '!' -d 127.0.0.0/8 -j REJECT
adb shell ip6tables -w -I OUTPUT 1 -m owner --uid-owner "$app_uid" '!' -d ::1/128 -j REJECT
adb shell iptables -w -C OUTPUT -m owner --uid-owner "$app_uid" '!' -d 127.0.0.0/8 -j REJECT
adb shell ip6tables -w -C OUTPUT -m owner --uid-owner "$app_uid" '!' -d ::1/128 -j REJECT
echo "Verified IPv4/IPv6 external-network isolation for Mochi UID $app_uid."
