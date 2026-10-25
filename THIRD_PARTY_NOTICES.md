# Third-party notices

The app's own source code is licensed under the MIT License (see `LICENSE`). The components below are not covered by that license and remain under their own terms.

## scrcpy-server 5.0.1 (Apache-2.0)

`app/src/main/assets/scrcpy-server-v5.0.1` is the **unmodified** device-side server of [scrcpy](https://github.com/Genymobile/scrcpy) v5.0.1, downloaded from the official release (SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`, matching the release `SHA256SUMS.txt`). It is used for ADB screen mirroring. Licensed under the Apache License 2.0; the full text is in `third_party/scrcpy/LICENSE`.

Copyright (C) 2018 Genymobile
Copyright (C) 2018-2026 Romain Vimont

The client side (`ScrcpyProtocol.java`, `MirrorActivity.java`) is an independent implementation of the scrcpy wire protocol written for this app.

## UFI-TOOLS (MIT)

This app installs [UFI-TOOLS](https://github.com/kanoqwq/UFI-TOOLS) by MiniKano; it does not contain UFI-TOOLS itself. The one-click install flow follows the official UFI-TOOLS Windows installer 1.9.2, and the web-backend login / request signing in `ZteWeb.java` follows the algorithm in the UFI-TOOLS repository (`app/src/main/assets/shell/zreq.go`), which is licensed under the MIT License; its license text is in `LICENSES/UFI-TOOLS-MIT.txt`.

Copyright (c) 2025 Minikano

By default the app downloads the latest UFI-TOOLS APK from the same download server the official installer uses (`pan.kanokano.cn`, run by the UFI-TOOLS author). This project is not affiliated with or endorsed by the UFI-TOOLS author.

## GitHub logo

`app/src/main/res/drawable-nodpi/github_invertocat.png` is the GitHub Invertocat mark, used only to link to the author's GitHub profile, in line with [GitHub's logo guidelines](https://github.com/logos). GitHub and the Invertocat are trademarks of GitHub, Inc.; this app is not affiliated with or endorsed by GitHub.
