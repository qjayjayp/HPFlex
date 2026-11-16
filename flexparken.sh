#!/usr/bin/env bash
# flexparken.sh — HandyParken München: FlexParken (offener Parkschein) start/stop via the backend API
# Reverse-engineered from de.compiled HandyParken app v110.0 (dto lib 1.22.12). Requires: curl, jq, (sha256sum or openssl).
#
# Usage:
#   ./flexparken.sh info
#   ./flexparken.sh login                 # opens M-Login in browser, pastes authcode back, stores session
#   ./flexparken.sh status                # currently active parkschein
#   ./flexparken.sh tarif 48.137 11.575   # resolve flex tariff at GPS position
#   ./flexparken.sh start 48.137 11.575   # preauthorize (shows price) -> confirm -> flex ticket RUNNING
#   ./flexparken.sh stop                  # stop active ticket
#   ./flexparken.sh logout
#
# Config via environment (defaults are the app's own production values):
#   HP_BASE      (default: https://handyparken-backend-prod.web.azrapp.swm.de/api)
#   HP_APIKEY    (default: static apikey from app BuildConfig)
#   HP_APPVER    (default: 2026000023  = Play versionCode of app v110.0)
#   HP_SESSION   (default: $HOME/.flexparken_session)
#   HP_KFZ_ID / HP_KFZ_PLATE  optional vehicle to attach
#   HP_INSTALL_ID default: $HOME/.flexparken_installation (random UUID, created on first run)

set -euo pipefail

HP_BASE="${HP_BASE:-https://handyparken-backend-prod.web.azrapp.swm.de/api}"
HP_APIKEY="${HP_APIKEY:-P89pb1e-4c-a7xx-ab29f65efa}"
# v110 app
HP_APPVER="${HP_APPVER:-2026000023}"
HP_SESSION="${HP_SESSION:-$HOME/.flexparken_session}"
HP_INSTALL_ID="${HP_INSTALL_ID:-$HOME/.flexparken_installation}"
HP_CLIENTID="handyparken"
HP_REDIRECT="de.swm.login.handyparken:/oauth2redirect"
HP_MLOGIN_HOST="https://login.muenchen.de"

# Use vehicle to get values
#HP_KFZ_ID="..."
#HP_KFZ_PLATE="..."

need() { command -v "$1" >/dev/null || { echo "missing tool: $1" >&2; exit 1; }; }
need curl; need jq

# --- pretty-print with epoch-ms annotations (same convention as the HPFlex app):
# numbers 1e12..4.1e12 become "<ms>  =  yyyy-MM-dd HH:mm:ss TZ", and
# "start-end" ms-pair strings (parkingTimeInterval) become "<s>  =  dt1 .. dt2".
# Display only — internal logic always uses the raw values.
MARK='def isms: type=="number" and . >= 1000000000000 and . <= 4102444800000;
def dt: (. / 1000 | floor | strflocaltime("%Y-%m-%d %H:%M:%S %Z"));
walk(if isms then "\(.)  =  \(.|dt)"
     elif (type=="string" and test("^\\d{13}-\\d{13}$")) then
       . as $s | "\(.)  =  " + ($s|split("-")|map(tonumber/1000|floor|strflocaltime("%Y-%m-%d %H:%M:%S %Z"))|join(" .. "))
     else . end)'
markdates() { jq "$MARK" 2>/dev/null || cat; }

b64url() { openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='; }
rand_b64url() { head -c "$1" /dev/urandom | openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# --- generic request; args: METHOD URL [json-body] ---
api() {
  local method="$1" url="$2" body="${3:-}"
  local args=(-sS -X "$method" -H "swm_hp_apikey: $HP_APIKEY" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -n "$url" ] && :
  if [ -f "$HP_SESSION" ] && [ "$method" != "NONE" ]; then
    args+=(-H "swm_hp_authorization: $(cat "$HP_SESSION")")
  fi
  [ -n "$body" ] && args+=(-d "$body")
  local resp http
  resp=$(curl "${args[@]}" "$url")
  http=$(tail -n1 <<<"$resp")
  printf '%s' "${resp%$'\n'*}" | jq "$MARK" 2>/dev/null || printf '%s' "${resp%$'\n'*}"
  echo "HTTP $http" >&2
  if [ "$http" = 403 ] && [ ! -s "$HP_SESSION" ]; then
    echo "note: no session stored ($HP_SESSION) — without the swm_hp_authorization header every session" >&2
    echo "      endpoint answers 403 SECURITY_EXCEPTION/NOT_APPLICABLE. Run: $0 login" >&2
  fi
  [ "$http" -ge 400 ] && return 1
  return 0
}

# raw version (for scripting inside this file): prints body only, dies on >=400
api_raw() {
  local method="$1" url="$2" body="${3:-}"
  local args=(-sS -X "$method" -H "swm_hp_apikey: $HP_APIKEY" -H 'Content-Type: application/json' -w '\n%{http_code}')
  if [ -f "$HP_SESSION" ]; then args+=(-H "swm_hp_authorization: $(cat "$HP_SESSION")"); fi
  [ -n "$body" ] && args+=(-d "$body")
  local resp http
  resp=$(curl "${args[@]}" "$url")
  http=$(tail -n1 <<<"$resp"); body="${resp%$'\n'*}"
  if [ "$http" -ge 400 ]; then echo "$body" | jq "$MARK" 2>/dev/null || echo "$body" >&2; echo "HTTP $http for $method $url" >&2; exit 1; fi
  printf '%s' "$body"
}

install_id() {
  if [ ! -s "$HP_INSTALL_ID" ]; then
    cat /proc/sys/kernel/random/uuid > "$HP_INSTALL_ID"
  fi
  cat "$HP_INSTALL_ID"
}

cmd="${1:-help}"; shift || true
case "$cmd" in

info)
  # optional pre-flight: backend version + remoteConfig feature flags
  api GET "$HP_BASE/v2/info?os=ANDROID&version=$HP_APPVER"
  ;;

login)
  # M-Login = OAuth2 authorization code flow with PKCE (AppAuth).
  # The app itself opens login.muenchen.de in a Custom Tab; the redirect
  # (de.swm.login.handyparken:/oauth2redirect?code=...) is not a web page,
  # so we generate the PKCE verifier here, let the user finish login in a
  # browser, and paste the final URL (or just the code) back.
  verifier=$(rand_b64url 32)
  challenge=$(printf '%s' "$verifier" | b64url)
  state=$(rand_b64url 16)
  url="$HP_MLOGIN_HOST/oauth2/authorize?client_id=$HP_CLIENTID&redirect_uri=$HP_REDIRECT&response_type=code&scope=openid&code_challenge=$challenge&code_challenge_method=S256&state=$state&locale=de&login_action=widen_scope"
  echo "Open this URL and log in with your M-Login account:"
  echo
  echo "$url"
  echo
  echo "After login the browser is redirected to '$HP_REDIRECT?code=...&state=$state'"
  echo "(the page will fail to open — that is expected). Copy the FULL redirect URL or just the code value."
  printf 'Redirect URL or authcode: '
  read -r line
  code=${line##*code=}; code=${code%%&*}
  [ -z "$code" ] && { echo "no code found" >&2; exit 1; }
  # backend exchanges code+verifier and hands out its own session token
  body=$(jq -n --arg a "$code" --arg v "$verifier" '{authcode:$a, verificationCode:$v}')
  out=$(curl -sS -X POST -H "swm_hp_apikey: $HP_APIKEY" -H 'Content-Type: application/json' \
        -d "$body" -w '\n%{http_code}' "$HP_BASE/v3/user/login")
  http=$(tail -n1 <<<"$out"); out=${out%$'\n'*}
  if [ "$http" -ge 400 ]; then echo "$out" | jq . 2>/dev/null || echo "$out"; echo "login failed, HTTP $http" >&2; exit 1; fi
  echo "$out" | jq .
  jq -r '.session' <<<"$out" > "$HP_SESSION"; chmod 600 "$HP_SESSION"
  echo "session stored in $HP_SESSION"
  ;;

status)
  api GET "$HP_BASE/v2/parkschein/active"
  ;;

vehicles)
  api GET "$HP_BASE/v3/kfz"
  ;;

tarif)
  # note: anonymous calls answer KEIN_TARIFGEBIET for every coordinate; run `login` first for real zones
  lat="${1:?lat}"; lng="${2:?lng}"
  api GET "$HP_BASE/v5/tarif/resolve?isOffenerParkschein=true&userLat=$lat&userLng=$lng&eAuto=false&withShortTarifInfo=true&withLongTarifInfo=true"
  ;;

start)
  lat="${1:?lat}"; lng="${2:?lng}"
  # 1) resolve the open-ended (Flex) tariff for this position
  tarif=$(api_raw GET "$HP_BASE/v5/tarif/resolve?isOffenerParkschein=true&userLat=$lat&userLng=$lng&eAuto=false&withShortTarifInfo=false&withLongTarifInfo=false")
  res=$(jq -r '.resolveResult // "?"' <<<"$tarif")
  if [ "$res" != "RESOLVED" ]; then
    nvt=$(jq -r '.naechsteVerkaufszeit // empty' <<<"$tarif")
    echo "resolveResult=$res — nothing to buy at $lat,$lng (no tariff area, or outside selling hours)$([ -n "$nvt" ] && printf '; next selling time: %s' "$(date -d "@$((nvt / 1000))" '+%F %T')")" >&2
    exit 1
  fi
  tic=$(jq -r '.parkingTimeInterval // empty' <<<"$tarif")
  echo "Tarif: $(jq -r '.tarifID + " / " + .formatierterPreisEUR' <<<"$tarif")"
  # For a Flex (open-ended) request the server always answers with a degenerate
  # ~1-minute parkingTimeInterval: an open ticket simply has no fixed end, so the
  # interval is only a minimum-duration placeholder. It must NOT be echoed back into
  # the purchase — that is what turns the ticket into a fixed 1-minute one.
  if [ -n "$tic" ]; then
    span_min=$(( (${tic##*-} - ${tic%%-*}) / 60000 ))
    if [ "$span_min" -le 2 ]; then
      ab=$(jq -r '.abrechnungsStart // empty' <<<"$tarif")
      echo "note: resolve offers only a ${span_min}-min window here — that is the normal Flex placeholder." >&2
      [ -n "$ab" ] && echo "      first billing time of this tariff: $(date -d "@$((ab / 1000))" '+%F %T')" >&2
      echo "      the purchase below sends openTicket=true without parkingTimeInterval, so the ticket" >&2
      echo "      stays open (end = null, billed per minute) until 'flexparken.sh stop'." >&2
    fi
  fi

  # 2) vehicle — the server rejects a purchase without one (mandatory since v1.104)
  kfz=null
  if [ -n "${HP_KFZ_ID:-}" ] && [ -n "${HP_KFZ_PLATE:-}" ]; then
    kfz=$(jq -n --arg p "$HP_KFZ_PLATE" --argjson i "$HP_KFZ_ID" '{id:$i, kennzeichen:$p, standard:true, elektroAuto:false}')
  else
    echo "note: HP_KFZ_ID / HP_KFZ_PLATE not set — server will reject a purchase without a vehicle" >&2
  fi

  # 3) preauthorize — backend computes price and returns one-time "payload"
  #    wire name of the flex flag is "openTicket": the DTO field is called
  #    isOpenTicket, but the backend maps the boolean is-getter to "openTicket"
  #    (that is also the key it answers with). Sending "isOpenTicket" is silently
  #    ignored -> the server treats it as a FIXED ticket: with the 1-minute interval
  #    from resolve it sells a 1-minute ticket, without an interval it answers
  #    401 PURCHASE_SECURITY_EXCEPTION. For an open ticket no parkingTimeInterval
  #    (and no parkDauerInMinutes) is sent at all -> ticketEndInMs = null.
  req=$(jq -n \
    --argjson lat "$lat" --argjson lng "$lng" \
    --argjson kfz "$kfz" \
    --arg inst "$(install_id)" --arg appver "$HP_APPVER" \
    '{openTicket:true, userLat:$lat, userLng:$lng,
      paymentType:"MLOGIN",                       # charge M-Login payment method (no wallet token needed)
      versionInfoDTO:{appVersion:$appver, operatingSystem:"ANDROID"},
      appInstallation:{appInstallationId:$inst, operatingSystem:"ANDROID", sprache:"de"},
      kfz:$kfz}')
  pre=$(api_raw POST "$HP_BASE/v5/purchase/preauthorize" "$req")
  echo "--- preauthorize ---"; jq '{ticketUUID, priceEUR, formattedPrice, openTicket, ticketStartInMs, ticketEndInMs, maxPreisProParkvorgangInCent}' <<<"$pre" | markdates
  printf 'Confirm purchase of FlexParken ticket at %s EUR? [y/N] ' "$(jq -r '.formattedPrice // .priceEUR' <<<"$pre")"
  read -r y; [ "$y" = "y" ] || { echo "aborted (preauthorization expires on its own)"; exit 1; }

  # 4) confirm — encryptedPayload is just the payload string echoed back, paymentToken stays null for MLOGIN
  conf=$(jq -n --arg p "$(jq -r '.payload' <<<"$pre")" '{encryptedPayload:$p}')
  echo "--- confirm ---"
  confresp=$(api_raw POST "$HP_BASE/v5/purchase/confirm" "$conf")
  jq '{purchasedParkschein: (.purchasedParkschein | {uuid, backofficeid, status, open, start, "end", price, kfzKennzeichen}), beginAbrechnungInMs}' <<<"$confresp" | markdates
  st=$(jq -r '.purchasedParkschein.start // empty' <<<"$confresp"); en=$(jq -r '.purchasedParkschein.end // empty' <<<"$confresp"); ba=$(jq -r '.beginAbrechnungInMs // 0' <<<"$confresp")
  if [ -n "$st" ]; then
    if [ -z "$en" ]; then
      echo "  ticket: $(date -d "@$((st / 1000))" '+%F %T') .. open end (billed per minute until you stop it)"
    else
      echo "  ticket: $(date -d "@$((st / 1000))" '+%F %T') .. $(date -d "@$((en / 1000))" '+%F %T')$( [ "$ba" -ge 1000000000000 ] && printf '; billing from %s' "$(date -d "@$((ba / 1000))" '+%F %T')")"
    fi
  fi
  echo "Flex ticket running. Stop it later with: $0 stop"
  ;;

stop)
  api POST "$HP_BASE/v2/parkschein/active/stop"
  ;;

logout)
  # the logout request itself needs the session header — drop the local token only afterwards
  if [ ! -s "$HP_SESSION" ]; then
    echo "no session stored ($HP_SESSION) — nothing to log out." >&2
    echo "note: every session endpoint (status, logout, ...) answers 403 SECURITY_EXCEPTION /" >&2
    echo "      NOT_APPLICABLE 'Unerwarteter Fehler.' when the session header is missing or the" >&2
    echo "      session is no longer known — that is not a separate error." >&2
    exit 0
  fi
  # command substitution puts api_raw in a subshell, so its exit-on-error cannot skip the cleanup
  if lbody=$(api_raw POST "$HP_BASE/v3/user/logout" '{}'); then
    printf '%s' "$lbody" | jq "$MARK" 2>/dev/null || printf '%s\n' "$lbody"
    rm -f "$HP_SESSION"
    echo "logged out: server session ended, local token removed"
  else
    rm -f "$HP_SESSION"
    echo "(local token removed as well; 403 means the backend no longer knows this session —" >&2
    echo " it was already logged out or expired. Use '$0 login' for a fresh one.)" >&2
  fi
  ;;

*)
  sed -n '2,20p' "$0"
  ;;
esac
