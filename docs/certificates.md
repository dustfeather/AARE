# Certificates and head-unit compatibility

Most "AARE doesn't work with my car" reports come down to certificates. This page explains what
Android Auto checks, why AARE passes on some head units and fails on others, and how to find out
which case you are in.

Sources: the reverse-engineering notes in [research/gearhead-notes.md](research/gearhead-notes.md)
(§1, Google's Android Auto app 17.7), the AAP protobuf definitions in `aap/src/main/proto/`, and
tests against Google's Desktop Head Unit (DHU), open-source head units and AARE's own desktop head
unit. What isn't verified is marked as such.

## How the handshake works

- After the version exchange, phone and head unit run **TLS 1.2** inside AAP messages (channel 0,
  message type 3, `ENCAPSULATED_SSL`).
- **The phone is the TLS server; the head unit is the TLS client.** It is **mutual TLS**: each
  side sends a certificate and may check the other's.
- Once its handshake is done, the head unit sends **`AUTH_COMPLETE`** (message type 4), an
  `AuthResponse` with a status: `0` means accepted, a negative value means refused (see
  [Status codes](#status-codes)).

So there are two independent checks:

1. the **head unit checks the phone's certificate** (what decides whether AARE works in a car);
2. the **phone checks the head unit's certificate** (what decides whether a head unit works with
   Google's Android Auto app).

## What Google's Android Auto does

### The certificate it presents (as the phone)

- Every Android Auto install presents **the same certificate and RSA-2048 key**, embedded in the
  app. It isn't generated per phone or attested.
- The chain is `[CarService leaf, Google Automotive Link root]`:

| Certificate | Subject | Issuer | Valid |
|---|---|---|---|
| root | `O=Google Automotive Link`, Mountain View, US | itself | 2014-06-06 → 2044-06-05 |
| phone leaf | `O=CarService`, Mountain View, US | Google Automotive Link | 2014-07-04 → **2026-12-31** |

- The leaf expires on **2026-12-31**. Google can replace it remotely through server-side flags,
  so Android Auto will presumably get a new one; head units that check expiry report it with the
  `CERT_EXPIRED` status (-24).

### What it requires from the head unit

- Its trust store holds **one anchor: the Google Automotive Link root**. The head unit's
  certificate must chain to it, with the usual checks (validity dates included).
- It also refuses a head unit that sends the phone's own `CarService` certificate, or the root
  itself.

**Consequence:** Google's Android Auto only talks to head units with a certificate issued by
Google. Car makers get one; hobby projects don't. That's why:

- AARE's desktop head unit (`./headunit.sh`), which uses a self-signed certificate, works with
  AARE but **not with Google's Android Auto app**: the app hangs up during the TLS handshake. The
  head unit says so ("the phone refused the head unit's certificate").
- Open-source head units that work with Google's app do so with a Google-issued head-unit
  certificate taken from somewhere, or not at all.

## What head units do with the phone's certificate

This is what matters for AARE, and it varies:

| Head unit | Checks the phone's certificate? | AARE works? |
|---|---|---|
| Google's Desktop Head Unit (DHU) | yes, against the Google Automotive Link root | **no**: TLS completes, then `AUTH_COMPLETE` with status -3 |
| Factory head units built on Google's receiver library | believed to check like the DHU | most likely **no** (not tested in a real car yet) |
| openauto / Crankshaft | no | yes |
| Headunit Revived | no | yes |
| LIVI, aa-proxy-rs | no | yes |
| Some aftermarket / OEM units | varies | some do |
| AARE's desktop head unit | no | yes |

"Believed to check" because the DHU is Google's reference implementation of the receiver
library, and the status codes for expired or not-yet-valid phone certificates only make sense if
head units validate the chain. Reports from real cars are welcome (see below).

## What AARE does

- **Its own certificate:** on first launch AARE generates a self-signed certificate and key. Head
  units that don't check the phone accept it; Google-library head units refuse it.
- **Importing a certificate:** under *Advanced > Phone certificates* you can import a certificate
  chain and key (PEM, or a PKCS#12 file) that you are entitled to use. **AARE does not ship,
  download or extract Google's certificate or key**, and this repository doesn't explain how to
  get them.
- **Fallback order:** with several certificates, AARE offers them in turn. When a head unit
  refuses one (a failed handshake, or a non-zero `AUTH_COMPLETE`), the next connection offers
  the next one, and the connection log says so.
- **The head unit's certificate:** AARE accepts any head-unit certificate, like openauto does.

AARE's desktop head unit can also present a certificate you supply (`--hu-cert`, `--hu-key`),
for example to compare with Google's app.

## Finding out what happens with your car

1. Connect, then open AARE on the phone: *Advanced > Connection log*. It's kept across
   reconnections, so you can read it after a drive.
2. Look for these lines:

| Log line | Meaning |
|---|---|
| `TLS handshake did not complete (head unit rejected our certificate?)` | the head unit hung up during TLS: it most likely doesn't trust AARE's certificate |
| `head unit rejected authentication (status -3)` | TLS worked, then the head unit refused the certificate (`STATUS_AUTHENTICATION_FAILURE`) |
| `status -23` / `status -24` | the certificate isn't valid yet / has expired: check the phone's date, or the imported certificate's dates |
| `head unit rejected the certificate; next connection offers "…"` | AARE will try its next certificate on the next connection |
| no TLS line at all | the problem is earlier (USB, Bluetooth, Wi-Fi): see the README's compatibility section |

3. With adb, `adb logcat | grep -iE 'aare|AareAap'` shows the same, with more detail.

### Status codes

From `aap/src/main/proto/aap_protobuf/shared/MessageStatus.proto`:

| Status | Name |
|---|---|
| 0 | success |
| -3 | `STATUS_AUTHENTICATION_FAILURE` |
| -23 | `STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID` |
| -24 | `STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED` |

## Reporting a car

Open an issue with:

- the car (make, model, year) or the head unit (brand, model, software version);
- USB or wireless;
- the connection-log lines above, or the `adb logcat` output around the connection;
- whether Google's Android Auto works with the same car.

That's how the table above grows.
