#!/data/data/com.agy/files/usr/bin/bash
# version.sh -- Debian version comparison wrappers (Python fast-path + AWK fallback).
# Requires PKG_LIB (dir of pkg_core.py / vercmp.awk).

deb_vercmp() {
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" vercmp "$1" "$2"
    else
        awk -v a="$1" -v b="$2" -f "$PKG_LIB/vercmp.awk"
    fi
}
deb_ge() { [ "$(deb_vercmp "$1" "$2")" != "-1" ]; }
deb_gt() { [ "$(deb_vercmp "$1" "$2")" = "1" ]; }
