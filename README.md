# Lino-UFI工具（安卓版）

把 UFI-TOOLS 电脑版「一键安装器 1.9.2」的流程搬到安卓手机上：手机就是 ADB 主机，不需要电脑。1.1.0 起另有 ADB 投屏和安装包备份/恢复。

UFI-TOOLS 本体开源地址：https://github.com/kanoqwq/UFI-TOOLS（作者 MiniKano）。本项目是非官方的安装器移植，不包含 UFI-TOOLS 本体，与 UFI-TOOLS 作者无关联。

下载：到本仓库 [Releases](https://github.com/linote99/lino-ufi-tools-android/releases) 下载 `Lino-UFI-Tools-Android-<版本>.apk`，需要 Android 8.0 或更高版本。

## 怎么用

1. 手机连上随身 WiFi 的热点。
2. 打开 App，默认设置就是电脑版的行为：设备 `192.168.0.1`，后台端口 `8080`，后台密码 `Wa@9w+YWRtaW4=`（电脑版内置的默认值），安装包为在线最新版。
   要修改这些设置，**连续点击标题「Lino-UFI工具」5 次**打开单独的「高级设置」页面（设备卡片和安装包卡片都在里面，`SettingsActivity` 的 advanced 模式），返回即自动保存。
3. 点「开始一键安装UFI-TOOLS」。App 会依次：
   - 获取安装包（三选一：与电脑版相同的在线最新版 / 自定义下载链接 / 本地 APK 文件）；
   - 查找 ADB 设备（USB OTG 或无线 ADB 5555）；找不到时登录设备后台开启 ADB（`USB_PORT_SETTING`，`usb_port_switch=1`），再等 ADB 出现；
   - 安装或更新 `com.minikano.f50_sms`，授权短信/存储/电话/通知权限，设置使用情况访问、通知监听、电池白名单、关闭休眠和自动撤销权限；
   - 启动应用，确认已运行，15 秒后重启设备（可在界面上关闭自动重启）。
4. 重启后访问 `http://192.168.0.1:2333` 使用 UFI-TOOLS。

无线 ADB 不通时，用 OTG 线把随身 WiFi 接到手机（手机需支持 USB 主机模式），App 会弹出 USB 访问授权。

「仅开启 ADB」只做后台登录并开启 ADB，不安装。

### ADB 工具

投屏、备份、恢复使用「ADB 工具」卡片右上角「⚙ 设置」页面里**单独的设备 IP 和 ADB 端口（默认 `192.168.0.1:5555`）**，直连无线 ADB（或选 USB OTG），不登录后台、不需要后台密码（`Installer.connectTools()`）。设备需已开启 ADB；后台地址和密码只在“一键安装 / 仅开启 ADB”自动开启 ADB 时使用。

- **ADB 投屏**：流畅模式用打包进 APK 的 [scrcpy](https://github.com/Genymobile/scrcpy) 5.0.1 设备端组件（未修改，Apache-2.0，见 `third_party/scrcpy/`）：推送到 `/data/local/tmp/ufi-scrcpy-server.jar`，以 `tunnel_forward` 方式打开视频和控制两个 `localabstract` 连接，手机端用 MediaCodec 解码 H.264，触摸（含多指）和返回/主页/多任务键回传设备。无声音。画质三档可切换。启动失败或 Android 5 以下时自动改用截图模式（循环 `screencap -p` + `input tap/swipe/keyevent`）。
- **备份安装包**：列出 `pm list packages -3` 的应用，勾选后用 ADB sync `RECV` 拉取 `pm path` 的全部 APK，保存到用户选择的文件夹（SAF）；单 APK 存为 `.apk`，拆分安装存为 `.apks`（zip）。
- **恢复安装包**：多选 `.apk` / `.apks`，推送后 `pm install -r -d`，拆分包用 `pm install-create/-write/-commit`。

右上角「ⓘ」打开关于页：使用说明、连接方式（OTG / 无线 ADB）、注意事项（含固件版本限制），页脚为友情链接。

## 与电脑版的对应关系

| 电脑版 | 安卓版 |
| --- | --- |
| 内嵌 `adb.exe` | 内置 ADB 客户端（TCP 5555 与 USB OTG），代码复用自 OpenWrt 安装器 |
| 内嵌 `zreq.exe`（登录后台、开 ADB） | `ZteWeb.java`：密码 = SHA256(SHA256(pwd)+LD)，请求签名 AD = SHA256(SHA256(wa_inner_version+cr_version)+RD)，大写十六进制 |
| `pan.kanokano.cn/api/fs/list` 取最新 APK | `ApkSource.java`：同一请求，服务器和目录可改，也可填直链或选本地文件 |
| `adb install -r` | 推送到 `/data/local/tmp` 后 `pm install -r` |
| 授权、保活、启动、重启命令 | `Installer.java`，命令与顺序同电脑版 |

## 额外的安全检查

- 下载内容必须是 ZIP/APK，否则丢弃（避免把错误网页当安装包）。
- 逐项读取压缩包校验 CRC（对应电脑版的 `CheckApkValid`）。
- 显示 SHA-256；包名不是 `com.minikano.f50_sms` 时先询问。
- 自定义 HTTP（非 HTTPS）链接先询问。
- 设备流量绑定到 Wi-Fi 网络，手机同时开着移动数据也能访问 `192.168.0.1`。

## 构建

需要 JDK 17+ 和 Android SDK（`platforms;android-34`、`build-tools;34.0.0`），不用 Gradle：

```bash
./build.sh            # 产物：dist/Lino-UFI-Tools-Android-<版本>.apk 和 .sha256（1.0.x 叫 UFI-TOOLS-Installer-Android）
```

版本号只在 `version.properties` 里改，同时更新 `CHANGELOG.md`。

签名：`build.sh` 读取仓库根目录下的 `signing/release.keystore` 和 `signing/keystore.properties`（均已被 `.gitignore` 排除，不在仓库里）。找不到时会**生成一把新密钥**并给出警告；用新密钥签出的 APK 无法覆盖安装旧版本，所以发布新版前请先把原来的 `signing/` 文件夹放回仓库根目录。

Windows 上请在 WSL 或 Git Bash 中运行 `build.sh`（需要 bash、JDK 和 Android SDK 命令行工具）。

更多实现细节见 [`docs/SCRCPY-NOTES.md`](docs/SCRCPY-NOTES.md)（投屏协议要点）。

## 已知限制

- 系统版本过高时官方禁用了 ADB（F50 需 B15 以下，U30 亚太需 B19 以下，U30 普通版需 B25 以下，V50 需 B21 以下），这一点和电脑版一样无法绕过。
- 不支持 Android 11+ 的「无线调试」配对端口（TLS），只支持经典 5555 无线 ADB 和 USB 调试。

## 许可证

应用自身源码采用 **MIT License**，许可文本见 [`LICENSE`](LICENSE)。以下内容不在 MIT 许可范围内：

- `app/src/main/assets/scrcpy-server-v5.0.1`：未修改的 [scrcpy](https://github.com/Genymobile/scrcpy) 5.0.1 设备端组件，采用 Apache-2.0（见 [`third_party/scrcpy/`](third_party/scrcpy/)）。
- 后台登录与请求签名算法参照 UFI-TOOLS 仓库（MIT，见 [`LICENSES/UFI-TOOLS-MIT.txt`](LICENSES/UFI-TOOLS-MIT.txt)）。
- GitHub 图标为 GitHub, Inc. 的商标，仅用于链接作者主页。

完整说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 链接

- Lino 笔记：https://www.linote.top/
- GitHub 主页：https://github.com/linote99
