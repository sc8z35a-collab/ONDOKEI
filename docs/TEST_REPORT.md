# Battery Relay 1.3.1 検証記録

検証日: 2026-08-28 (JST)

## 検証対象の識別方法

この文書にcommit SHAを手書き固定しません。文書更新後にSHAが変わり、監査対象と記載値が乖離するためです。

GitHub Actions `ONDOKEI full system audit` は各runで、実際にcheckoutして検証した `GITHUB_SHA` とrefを `verification/commit-sha.txt` / `verification/ref.txt` に保存し、audit artifactへ含めます。したがって検証対象の正本は各runのartifactです。

第2段ウルトラ監査の修正台帳は `docs/ULTRA_AUDIT_V2.md` を参照してください。

## 自動検証

CIは次を同一SHAに対して実行します。

- `testDebugUnitTest`
- Android Lint: `lintDebug`, `lintRelease`
- `assembleDebug`
- ephemeral CI signing identityを使った `assembleRelease`
- `assembleDebugAndroidTest`
- Debug/Release APKのZIP integrity
- zipalign
- APK signature検証
- package / version / minSdk / targetSdk / Manifest / permission / service declaration監査
- Android Emulator API 35 instrumentation/UI監査
- notification permission deny/grant
- Foreground Service / WakeLock状態
- HOME/background/rotation/foreground復帰
- Wi-Fi/data disable/restore
- trim-memory
- force-stop/restart
- Java crash / native crash / ANR検出
- 診断artifact収集

## 第2段で追加した代表的な回帰検査

- forward wall-clock jumpで履歴を物理削除せず、時計復帰後に再表示できる
- rollbackでfuture-looking rowを破壊しない
- current minute最初のsampleをDBへcheckpointする
- remote freshnessをrequest/response RTT midpointへmapし、実際のreceipt timeは別に保持する
- exact P-256 parametersを検証する
- HKDF/random/AES入力境界を拒否する
- battery temperature境界値を正しく扱う
- protection modeを60秒intervalとする
- NSD/remote/session lifecycleのstale callbackと競合を防ぐ
- DebugだけでなくRelease経路もbuild/lint/APK監査する

## Foreground Service / WakeLock

Android 14+では継続ローカル計測を `specialUse` Foreground Serviceとして開始し、Manifestに `FOREGROUND_SERVICE_SPECIAL_USE` と用途説明を宣言します。Android 13以前との互換用に `connectedDevice` declarationも残します。

5秒/15秒の連続計測では機能要件上Partial WakeLockを使用しますが、省電力または重度以上の熱状態で60秒保護モードへ入った場合はWakeLockを解放し、保護モード自体が電力消費を増やす矛盾を避けます。

## Android 17 local network

`ACCESS_LOCAL_NETWORK` はtargetSdk 37移行に備えて先行宣言済みです。現在のtargetSdkは36です。targetSdkを37へ上げる際にはruntime permission UXを追加・実機検証する必要があります。

## CIだけでは完全保証できない範囲

- 物理端末2台間のmDNS/DNS-SD
- AP isolation / multicast制限のある実ルーター
- VPN・複数Wi-Fi・OEM固有network stack
- Xiaomi等OEMの長時間background制限
- 実画面OFF/Dozeでの長時間消費電力
- OEM固有battery property品質
- targetSdk37移行後のAndroid 17 local-network permission UI
- Google Play Console上のspecialUse FGS申告/審査

これらは「既知コードバグを未修正」という意味ではなく、GitHub-hosted単一Emulatorだけでは物理環境を保証できない検証範囲です。

「バグゼロ」を数学的に保証するものではありません。再現可能な欠陥を修正し、自動化できるものを回帰検査へ固定した記録です。
