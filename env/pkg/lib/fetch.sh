#!/data/data/com.agy/files/usr/bin/bash
# fetch.sh -- download with sha256 verification and cache.
# Requires curl, sha256sum, log, die.

# human_size BYTES
human_size() {
    local bytes="$1"
    if [ -z "$bytes" ] || [ "$bytes" -le 0 ] 2>/dev/null; then
        echo ""
    elif [ "$bytes" -ge 1048576 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%.1f MB", b / 1048576 }'
    elif [ "$bytes" -ge 1024 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%d kB", (b + 1023) / 1024 }'
    else
        printf '%d B' "$bytes"
    fi
}

# fetch URL SHA256 OUT [SIZE] [GET_INDEX]
fetch() {
    local url="$1" sha="$2" out="$3" size="${4:-}" idx="${5:-1}" have sz_str=""
    if [ -f "$out" ] && [ -n "$sha" ]; then
        have="$(sha256sum "$out" | cut -d' ' -f1)"
        if [ "$have" = "$sha" ]; then
            return 0
        fi
    fi
    mkdir -p "$(dirname "$out")"
    if [ -n "$size" ]; then
        sz_str=" [$(human_size "$size")]"
    fi
    printf 'Get:%s %s%s\n' "$idx" "$url" "$sz_str"
    local cacert_args=()
    [ -s "${PKG_CERT:-}" ] && cacert_args=(--cacert "$PKG_CERT")
    curl -# -fL --retry 3 --connect-timeout 20 "${cacert_args[@]}" -o "$out.part" "$url" \
        || { rm -f "$out.part"; die "download failed: $url"; }
    if [ -n "$sha" ]; then
        have="$(sha256sum "$out.part" | cut -d' ' -f1)"
        [ "$have" = "$sha" ] || { rm -f "$out.part"; die "sha256 mismatch $(basename "$out"): got $have want $sha"; }
    fi
    mv "$out.part" "$out"
}

_curl_supports_parallel() {
    curl --help all 2>&1 | grep -q -- '--parallel'
}

_escape_curl_cfg() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# fetch_batch -- reads lines of: name|rel_fn|url|sha256|out_path|size
# Downloads missing files concurrently via curl -Z (--http2) and retries ONLY
# failed or hash-mismatched files against remaining PKG_REPOS mirrors.
fetch_batch() {
    local items=() line
    while IFS= read -r line; do
        [ -n "$line" ] && items+=("$line")
    done
    [ "${#items[@]}" -eq 0 ] && return 0

    mkdir -p "$PKG_CACHE" "$PKG_ROOT/tmp"
    local use_parallel=0
    _curl_supports_parallel && use_parallel=1

    local pending=("${items[@]}")
    local mirror_idx=0
    local total_idx=0

    # Print planned downloads cleanly once
    for line in "${pending[@]}"; do
        total_idx=$((total_idx + 1))
        local name rel_fn url sha out sz sz_str=""
        IFS='|' read -r name rel_fn url sha out sz <<< "$line"
        [ -n "$sz" ] && [ "$sz" -gt 0 ] 2>/dev/null && sz_str=" [$(human_size "$sz")]"
        printf 'Get:%s %s%s\n' "$total_idx" "$url" "$sz_str"
    done

    local cfg="$PKG_ROOT/tmp/curl_batch.$$.cfg"
    trap 'rm -f "$cfg"' RETURN

    while [ "${#pending[@]}" -gt 0 ]; do
        local next_mirror_url=""
        if [ "$mirror_idx" -gt 0 ]; then
            if [ "$mirror_idx" -ge "${#PKG_REPOS[@]}" ]; then
                break
            fi
            next_mirror_url="${PKG_REPOS[$mirror_idx]%/}"
            local any_new_url=0
            for line in "${pending[@]}"; do
                local _n _rf _u _rest
                IFS='|' read -r _n _rf _u _rest <<< "$line"
                if [ "$next_mirror_url/$_rf" != "$_u" ]; then
                    any_new_url=1
                    break
                fi
            done
            if [ "$any_new_url" -eq 0 ]; then
                mirror_idx=$((mirror_idx + 1))
                continue
            fi
            warn "retrying ${#pending[@]} failed download(s) using mirror $next_mirror_url"
        fi

        local cacert_args=()
        [ -s "${PKG_CERT:-}" ] && cacert_args=(--cacert "$PKG_CERT")

        if [ "$use_parallel" -eq 1 ]; then
            : > "$cfg"
            for line in "${pending[@]}"; do
                local name rel_fn url sha out sz dl_url
                IFS='|' read -r name rel_fn url sha out sz <<< "$line"
                dl_url="$url"
                [ -n "$next_mirror_url" ] && dl_url="$next_mirror_url/$rel_fn"
                rm -f "$out.part"
                printf 'url = "%s"\noutput = "%s"\n' \
                    "$(_escape_curl_cfg "$dl_url")" \
                    "$(_escape_curl_cfg "$out.part")" >> "$cfg"
            done
            curl -Z -sS --show-error -fL --retry 2 --connect-timeout 15 --parallel --parallel-max 6 \
                --http2 "${cacert_args[@]}" -K "$cfg" || true
        else
            for line in "${pending[@]}"; do
                local name rel_fn url sha out sz dl_url
                IFS='|' read -r name rel_fn url sha out sz <<< "$line"
                dl_url="$url"
                [ -n "$next_mirror_url" ] && dl_url="$next_mirror_url/$rel_fn"
                rm -f "$out.part"
                curl -sS --show-error -fL --retry 2 --connect-timeout 15 \
                    "${cacert_args[@]}" -o "$out.part" "$dl_url" || true
            done
        fi

        # Verify each downloaded .part file individually and keep only failed ones for next mirror
        local sha_pairs=()
        for line in "${pending[@]}"; do
            local name rel_fn url sha out sz
            IFS='|' read -r name rel_fn url sha out sz <<< "$line"
            [ "$sha" = "-" ] && sha=""
            sha_pairs+=("$out.part:$sha")
        done

        local verify_map=""
        if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
            verify_map="$(python3 -B "$PKG_LIB/pkg_core.py" verify-sha256 "${sha_pairs[@]}")"
        else
            for pair in "${sha_pairs[@]}"; do
                local p="${pair%%:*}" w="${pair#*:}" h
                if [ ! -s "$p" ]; then
                    verify_map="${verify_map}FAIL	$p"$'\n'
                elif [ -n "$w" ]; then
                    h="$(sha256sum "$p" | cut -d' ' -f1)"
                    [ "$h" = "$w" ] && verify_map="${verify_map}OK	$p"$'\n' || verify_map="${verify_map}BADSHA	$p"$'\n'
                else
                    verify_map="${verify_map}OK	$p"$'\n'
                fi
            done
        fi

        local next_pending=()
        for line in "${pending[@]}"; do
            local name rel_fn url sha out sz
            IFS='|' read -r name rel_fn url sha out sz <<< "$line"
            if printf '%s\n' "$verify_map" | grep -Fqx "OK	$out.part"; then
                mv -f "$out.part" "$out"
            else
                if printf '%s\n' "$verify_map" | grep -Fqx "BADSHA	$out.part"; then
                    warn "sha256 mismatch for $(basename "$out") ($name)"
                fi
                rm -f "$out.part"
                next_pending+=("$line")
            fi
        done

        pending=("${next_pending[@]}")
        if [ "${#pending[@]}" -gt 0 ] && [ "$mirror_idx" -eq 0 ]; then
            mirror_idx=1
        else
            mirror_idx=$((mirror_idx + 1))
        fi
    done

    rm -f "$cfg"
    if [ "${#pending[@]}" -gt 0 ]; then
        local first_fail="${pending[0]%%|*}"
        die "download failed for ${#pending[@]} package(s) (first: $first_fail)"
    fi
}
