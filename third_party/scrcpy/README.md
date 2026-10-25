# scrcpy-server (bundled)

`app/src/main/assets/scrcpy-server-v5.0.1` is the unmodified device-side server of
[scrcpy](https://github.com/Genymobile/scrcpy) v5.0.1 by Genymobile / Romain Vimont,
licensed under the Apache License 2.0 (see `LICENSE` in this folder).

Downloaded from https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1
SHA-256 764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536 (matches the release SHA256SUMS.txt).

The app pushes it to `/data/local/tmp/ufi-scrcpy-server.jar` on the connected device and starts it with
`app_process` for ADB screen mirroring (H.264 video + touch/key control, no audio).

Copyright (C) 2018 Genymobile
Copyright (C) 2018-2026 Romain Vimont
