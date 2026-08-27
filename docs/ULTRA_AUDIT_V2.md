# ONDOKEI 第2段ウルトラ監査

実施日: 2026-08-28 (JST)

- 基準ブランチ: `main`
- 基準commit: `95f7fd8c85c30637f362e19e10cb7b8438875f4a`
- 修正ブランチ: `ultra-audit-v2`
- 検証対象SHAは固定値を手書きせず、各GitHub Actions runの `verification/commit-sha.txt` にそのrun自身の `GITHUB_SHA` を記録する。

## 結論

第1段監査の指摘を再検証し、さらに時刻異常、プロセス終了、暗号入力境界、NSD悪性入力、複数Network、session競合、rate limit、Foreground Service、WakeLock、OEM API例外、アクセシビリティ、Release経路、CI追跡性まで掘り下げた。

以下の45項目を独立した修正対象として扱い、コード修正またはCI/回帰検査を追加した。単なるコードスタイル変更は件数に含めていない。

## 修正台帳 — 45項目

| # | 重大度 | 修正対象 | 対応 |
|---:|:---:|---|---|
| 1 | High | 時計を未来へ進めたreadで正常履歴が物理削除され、時計を戻しても復元不能 | DBを31行リングにし、readは非破壊の30分表示フィルタへ変更 |
| 2 | Medium | `readWindow()` が読み取りなのにwrite transactionとDELETEを実行 | readable DBの非破壊queryへ変更 |
| 3 | Medium | DB schema upgradeが `DROP TABLE` で全履歴を破棄 | 暗黙DROPを廃止し、未実装migrationはfail-fast |
| 4 | High | 新しい1分の最初のサンプルが未永続で、process killにより1分丸ごと欠落 | 新minute最初の観測を即checkpoint |
| 5 | Medium | DB一時失敗時のpersistence backlogがUI cacheから見えなくなる | persisted/backlog/liveをcoalesceして表示 |
| 6 | Low | `elapsedRealtime` の異常巻き戻り時にfresh coalescingが誤判定 | `elapsed >= lastElapsed` を確認してから差分判定 |
| 7 | Low | 永続化queueへnullが入る防御が不足 | null enqueueを拒否 |
| 8 | High | 省電力/重度熱状態の60秒退避中もPartial WakeLockを保持し続ける | protection 60秒ではWakeLockを解放 |
| 9 | Medium | Turbo/保護状態の切替後にWakeLock状態が旧intervalのまま残る | interval変更ごとにWakeLockを再評価 |
| 10 | Low | Service teardown付近でworker参照がnullになった場合のcallback race | worker null guard追加 |
| 11 | Low | monotonic clockの異常巻き戻り時に通知更新が長時間止まる可能性 | notification elapsed clock rollback guard追加 |
| 12 | High | Android 14+でローカル計測だけのServiceを `connectedDevice` として開始する意味的不整合 | API34+は `specialUse` としてstartForeground |
| 13 | High | `specialUse` FGSに必要なmanifest permission/subtype説明がない | `FOREGROUND_SERVICE_SPECIAL_USE` と subtype property追加 |
| 14 | Medium | CIがDebugのみでRelease APK経路を一度もbuildしない | ephemeral CI keystoreで `assembleRelease` を追加 |
| 15 | Medium | Release固有LintをCIで検証しない | `lintRelease` を追加 |
| 16 | Medium | APK監査scriptがdebug package名にハードコード | expected packageを引数化しdebug/release両方監査 |
| 17 | Medium | 監査文書の手書きSHAが実際の最新検証commitと乖離可能 | run自身の `GITHUB_SHA` をartifactへ保存する方式に統一 |
| 18 | High | EC公開鍵を「field size=256」だけでP-256扱い | curve/generator/order/cofactorをsecp256r1と完全比較 |
| 19 | Medium | EC公開鍵encoding長の事前上限がない | X.509入力を256 bytes以下へ制限 |
| 20 | Low | pair key導出APIのnull入力境界が曖昧 | private/public/code/shareIdを明示検証 |
| 21 | Medium | `randomBytes()` が任意巨大allocationを許す | 0〜1MiBへ制限 |
| 22 | Low | pairing secret生成entropyが例外時にzeroizeされない可能性 | `finally` zeroization |
| 23 | Low | AES-GCM encryptのnull plaintextが低レベル例外へ漏れる | 明示argument validation |
| 24 | Low | AES-GCM decryptのnull ciphertextが低レベル例外へ漏れる | 明示argument validation |
| 25 | Low | AAD/Base64 helperのnull入力が不統一 | 明示argument validation |
| 26 | Medium | HKDF出力長にRFC上限チェックがない | `255 * HashLen` 上限を強制、秘密中間値をzeroize |
| 27 | High | mDNSからloopback/any/multicast endpointをpeerとして採用可能 | usable unicastのみ許可 |
| 28 | Medium | NSD TXT値をBase64 decodeする前のraw size上限がない | sid/salt/public key TXT長を事前制限 |
| 29 | Low | TXT parse途中のsalt/public-key byte列がGC任せ | `finally` zeroization |
| 30 | Low | stable peer key生成時のSHA-256 digest一時配列が残る | `finally` zeroization |
| 31 | Medium | 無効な新peer/共有キーを指定すると既存の正常sessionまで先にdisconnect | 新入力をvalidateしてから旧sessionを切断 |
| 32 | Medium | RemoteClient disconnectの一部経路でclone済みsession keyがzeroizeされない | 全shutdown pathでzeroize |
| 33 | High | fresh要求の成功判定が暗号化responseのexact sequence/fresh echoと十分結び付かない | decrypted payloadのsequence/freshを要求値と照合 |
| 34 | Medium | remote測定時刻をresponse受信時刻へ寄せ、network latency分だけ新しく見せる | request/response RTT midpointへ時刻map |
| 35 | Low | pair/snapshotのdecrypted plaintextがGC任せ | 使用後zeroize |
| 36 | Medium | 暗号/プロトコル破損を通常Wi-Fi障害と同様に再試行し続ける | local crypto/protocol violationをterminal分類 |
| 37 | Medium | 複数Wi-Fi/Local-only Network時にpeerへのrouteを考慮せず不適切Networkを選び得る | peer Network・route情報を優先してsocket binding |
| 38 | Low | RemoteDeviceManagerへnull peer/snapshotが届いた際のNPE経路 | null guardとsafe ignore |
| 39 | Medium | terminal callbackと再接続/切断が競合すると別Connectionを誤ってremoveし得る | `(key,current)` 条件付きremoveとidentity確認 |
| 40 | Medium | recovery discoveryがendpoint復旧後も15秒残り続ける | peer更新成功時に即stop |
| 41 | Medium | NSD stop/restart後の古いcallbackが新runのstateへ侵入し得る | generationで全callbackを隔離 |
| 42 | Medium | Android 14+ ServiceInfo callback、retry queue、resolved peerを別々に数え総上限を越え得る | tracking対象を統合して32件上限 |
| 43 | Medium | ShareHostのaddress rate-limit mapが多数source addressで無制限成長し得る | bucket総数を1024へ制限、IPv6は/64単位化 |
| 44 | Medium | global fresh gateに負けたsessionまで自身の5秒gateを消費する | global reservation成功後のみsession freshをcommit、race時rollback |
| 45 | Low | `.local-test/` の古いコンパイル済み `.class` がGit追跡され、ソースと乖離可能 | 追跡済みbytecodeを削除し `.local-test/` をgitignore |

## 再現・回帰検査

### JVM / unit

`UltraAuditV2RegressionTest` と既存core/security testsで、少なくとも次を自動検証する。

- P-256 key encode/decode
- 巨大random allocation拒否
- HKDF過大出力拒否
- null plaintext拒否
- 温度境界値 -40℃ / 90℃
- protection interval 60秒
- core trend/retention/security concurrency regression

### Android instrumentation

`FreshnessAndClockRegressionTest` 等で次を検証する。

- 時計rollbackでfuture-looking rowを物理削除しない
- 時計を大幅forward→元へ戻しても履歴が復元可能
- minute最初のsampleがprocess-style teardown前にDB checkpoint済み
- remote freshnessのRTT midpoint mapping
- actual receipt timeは偽装せず保持
- HistoryStore concurrency
- Activity/UI smoke test

### CI system audit

`.github/workflows/ondokei-system-audit.yml` は以下を実行する。

- `testDebugUnitTest`
- `lintDebug`
- `lintRelease`
- `assembleDebug`
- ephemeral signing identityによる `assembleRelease`
- `assembleDebugAndroidTest`
- Debug/Release APKのZIP、zipalign、signature、Manifest、permission、SDK/package監査
- Android Emulator API 35 instrumentation/UI/lifecycle/network-loss/force-stop/crash/ANR監査
- 検証したexact commit SHAのartifact記録

## 第1段指摘の扱い

- Forward clock destructive prune: 修正済み。
- current-minute process kill loss: 修正済み。
- Release APK未検証: 修正済み。
- remote freshness transport latency: 修正済み。
- exact P-256 validation: 修正済み。
- chart textのdp使用: sp化済み。
- destructive DB upgrade: 修正済み。
- continuous WakeLock: 60秒protectionでは解放。5/15秒の連続計測中は機能要件上維持する。
- connectedDevice FGS: API34+はspecialUseへ変更。Play Console側でもspecialUse用途申告が必要。
- Android 17 local-network: `ACCESS_LOCAL_NETWORK` は先行宣言済み。targetSdk 37へ上げる時点でruntime permission UXを実機検証する。

共有キーの5分rotationは「新規pairingに使えるキーを更新する」動作であり、すでに受理・復号処理へ入った1リクエストを強制中断するrevocation境界とは扱わない。host stop/share generation変更は `lifecycleGeneration` でin-flight処理を拒否する。この挙動は低リスクの仕様境界として明文化し、脆弱性件数には含めない。

## CIだけでは保証できない残存検証範囲

以下はコードバグを放置したという意味ではなく、GitHub-hosted単一Emulatorでは物理環境を保証できない項目。

- 物理Android端末2台間のmDNS/DNS-SD discovery
- ルーターAP isolation / multicast制限
- VPN・複数Wi-Fi・OEM独自network stack
- Xiaomi等OEMの長時間background制限
- 実画面OFF/Dozeでの長時間電力・発熱
- OEMごとのbattery current/temperature/remaining capacity品質
- targetSdk 37へ実際に上げた後のAndroid 17 local-network runtime permission UX
- Play ConsoleにおけるspecialUse FGS申告・審査

「バグゼロ」を数学的に保証する文書ではない。再現できたコード欠陥を修正し、そのうち自動化可能なものを回帰検査へ固定した記録である。
