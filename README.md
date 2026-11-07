# mideakt

[![build](https://github.com/hafacc/mideakt/actions/workflows/build.yml/badge.svg)](https://github.com/hafacc/mideakt/actions/workflows/build.yml)
[![jitpack](https://jitpack.io/v/hafacc/mideakt.svg)](https://jitpack.io/#hafacc/mideakt)

Native Kotlin library for **local** control of Midea (and rebranded) WiFi air
conditioners over the LAN — no cloud after a one-time key fetch.
This is a Kotlin port of the [msmart-ng](https://github.com/mill1000/midea-msmart).

Pure JVM (`java.net` sockets, `javax.crypto`), so it runs on plain JVM and on
Android (see [Android](#android)). The only dependency is
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), used to
parse the cloud's JSON.

## Features

- **Discovery** of devices on the LAN (UDP broadcast, plus probes to given addresses for modules that ignore broadcasts).
- **Cloud key fetch** (Midea's SmartHome cloud, the MSmartHome app's backend)
  to obtain a device's token/key once, with the account the device is
  registered to.
- **Setup**: discover → fetch key → verify → credentials.
- **Local control**: power, mode, temperature, fan, eco/turbo/swing, display
  toggle; reads full state. Persistent connection.

Older version-2 devices use an unauthenticated (`0x5A5A`) protocol: they skip
the cloud key fetch and are verified with a local query instead.

Calls are **blocking** — run them off the main thread (e.g. `Dispatchers.IO`).

## Installation

Available from [JitPack](https://jitpack.io/#hafacc/mideakt). Add the repository,
then the dependency:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.hafacc:mideakt:0.2.0")
}
```

<details>
<summary>Maven</summary>

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.hafacc</groupId>
  <artifactId>mideakt</artifactId>
  <version>0.2.0</version>
</dependency>
```
</details>

## Usage

```kotlin
// One-time setup (touches the cloud once), with the Midea (MSmartHome) account
// the devices are registered to — the cloud only issues keys for its own devices:
val credentials = Setup.run("you@example.com", "pw").first()

// All local from here — store the credentials and reuse:
val client = MideaClient(credentials)
val state = client.refresh()
println("${state.targetTemperature} ${state.mode}")  // e.g. 22.0 COOL

client.update { set ->
    set.powerOn = true
    set.targetTemperature = 22.0
    set.mode = OperationalMode.COOL
}
```

### Manual setup

`Setup.run` is the high-level path. To find devices that ignore broadcasts, discover
them yourself and pass them in:

```kotlin
val devices = Discovery.discover(unicastTargets = subnetHosts)
val credentials = Setup.run("you@example.com", "pw", devices)
```

The same steps are exposed individually if you want to drive setup yourself —
discover, fetch the key from the cloud, then connect:

```kotlin
val cloud = SmartHomeCloud("you@example.com", "pw")    // the device owner's account
val session = cloud.login()                            // CloudLoginException on a bad login
val device = Discovery.discover().first()              // a DiscoveredDevice on the LAN

// Fetches the device's key and returns the pair the device accepts; throws
// DeviceNotInAccountException for a device that isn't registered to this account.
val credentials = Setup.credentials(device, session)
println(MideaClient(credentials).refresh().mode)
```

## Android

Requires **minSdk 26** (Android 8.0): the protocol timestamp uses `java.time`,
which on older API levels needs
[core-library desugaring](https://developer.android.com/studio/write/java8-support-table).
API 26 covers essentially all active devices, so this is rarely a real
constraint. Receiving UDP discovery replies may also require a
`WifiManager.MulticastLock` on some devices.

## Design notes

### Post-authentication warm-up

A freshly authenticated device drops or ignores queries sent in the first moment
after the handshake. We send one throwaway `getState` probe and proceed the
instant its reply begins arriving, bounded by a ~1.2s ceiling — a fast unit
answers in a fraction of a second. `getState` is idempotent, so the probe is
harmless.

If a device answers *slower* than the ceiling, its probe reply is left queued;
before every call we drain any such buffered frames, so a slow probe reply is
discarded rather than mistaken for the next call's response. The only unclosable
residual is a probe reply still in flight during that drain — the protocol has no
request/reply correlation id to fully close it — but the next call self-corrects.

### Concurrency

`MideaClient` owns one stateful connection, so interleaving calls would corrupt
the stream. It deliberately does not serialize internally: a blocking lock would
block a thread mid-round-trip (wrong for coroutine callers), and a suspending
`Mutex` would pull in a coroutines dependency. Instead it fails fast (an
`AtomicBoolean` guard that throws on concurrent entry); correct callers that
already serialize never trip it.

### Token endianness

The cloud stores a device's token/key under a *udpid* derived from its device id.
The official app computed that udpid using a particular byte order of the id when
it registered the device, and that order varies across firmware/app versions.
Nothing in the device's discovery reply or the cloud API reports which order was
used, so it can't be computed or detected locally — the only signal is the cloud
itself, and it may answer for both with different pairs. So `Setup.credentials`
fetches the pair under each order and keeps the one the device itself accepts.

### Device ids and JSON

Device ids arrive as raw bytes from UDP discovery (6 bytes, ≤ 2^48), never from
JSON, and the cloud JSON carries only strings — so the JSON number representation
never affects them.

### Cross-validation

`src/test/resources/vectors.json` was generated from the canonical Python
reference ([msmart-ng](https://github.com/mill1000/midea-msmart)); the tests assert
mideakt's framing, CRC, command encodings, and state parsing match it.
