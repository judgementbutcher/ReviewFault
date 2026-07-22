# HarmonyOS 6 SDK validation gate

The v0.6.0 project targets Stage model and `compatibleSdkVersion 6.0.0(20)`.
That level is not considered validated by source review alone. The release job
requires a self-hosted runner carrying labels `harmonyos-6` and `api-20`; it must
use the current stable DevEco Studio toolchain and fail before publishing unless
all checks below pass.

- ArkTS/ArkUI module compilation and signed HAP assembly;
- C++20 NAPI build with ABI v5 symbols and canonical replay call;
- RdbStore execution of migrations v1 through v5, JSON1, triggers and window functions;
- HUKS key generation, encrypted token restore and foreground/background lifecycle;
- signature verification plus `cn.reviewfault.app` bundle inspection;
- install/launch on the target HarmonyOS 6 tablet in portrait, landscape and split screen.

Signing material is injected as `HARMONY_SIGNING_CONFIG_BASE64` on the runner.
The decoded value is the complete `app.signingConfigs` JSON array and must contain
a configuration named `release`; the workflow validates this before invoking Hvigor.
No signing key, profile password, token or device identity is stored in the
repository or release artifact logs. This HAP uses a new identity and is not an
in-place upgrade for historical packages.
