# Battery Relay 1.3.1 検証記録

検証日: 2026-08-28 (JST)

コード検証対象: `0c8e356d17110cf01dd62869d9357b1b93626400`

## 結論

前回のウルトラ監査で特定した不具合群に対する修正を実装し、GitHub Actions の `ONDOKEI full system audit` でビルド、JUnit、Android Lint、APK静的監査、Android Emulator API 35 上の instrumentation/UI・ライフサイクル・通信断復帰・Foreground Service/WakeLock・クラッシュ/ANR監査を実行しました。

コード検証対象commitでは、ビルド/単体テスト/Lint/APK監査ジョブは `success`、Emulator の実検査ステップもすべて `success` です。push run #53 は PR #2 作成後に同一workflowのconcurrency制御で置き換えられたためrun全体は `cancelled` 表示になりましたが、キャンセル前に Emulator の検査本体、診断採取、Artifact upload を含む全stepが成功しています。PR側では同一headを再検証します。

「バグゼロ」を数学的に保証するものではありませんが、監査で具体的に再現・特定した問題には修正と回帰防止を入れ、CIで検証可能な範囲は通過しています。

## 自動検証結果

- Gradle Javaコンパイル: 成功
- `testDebugUnitTest`: 成功
- Android Lint (`lintDebug`): 成功
- Debug APK / instrumentation APK 組立: 成功
- Gradle build: `BUILD SUCCESSFUL`
- APK ZIP integrity: 成功
- zipalign 4-byte alignment: 成功
- APK署名検証: Debug APK の v2 signature 成功
- APK監査: package / SDK / Manifest / permission / service 宣言すべて成功
- package: `jp.rstlab.batteryrelay.debug`
- versionCode: `5`
- versionName: `1.3.1-debug`
- minSdk: `26`
- targetSdk: `36`
- compileSdk: `36`
- `MonitorService`: non-exported / connectedDevice Foreground Service
- `allowBackup=false`, `usesCleartextTraffic=false` を確認
- `WAKE_LOCK`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `ACCESS_LOCAL_NETWORK` を含む意図した権限セットを確認
- 連絡先・SMS・通話履歴・録音・カメラ・精密位置など、想定外のプライバシー権限なし

## Emulator API 35 動的監査

GitHub-hosted Android Emulator で次を実行し、検査本体はすべて成功しました。

- APK / test APK インストール
- instrumentation / UI smoke test
- 通知権限の拒否状態で初回起動
- 通知権限許可後の再起動
- MainActivity 起動確認
- MonitorService が Foreground Service として維持されること
- 継続計測用 Partial WakeLock が保持されること
- HOME によるバックグラウンド化後も Service / WakeLock が維持されること
- 画面回転
- アプリ復帰
- Wi-Fi / data 無効化と復帰
- trim-memory COMPLETE 相当
- force-stop 後の再起動
- 再起動後の Service / WakeLock 再取得
- logcat / crash buffer から対象アプリの Java crash / native crash / ANR がないこと
- Activity / Service / memory / notification / UI hierarchy / screenshot の診断採取

## 今回追加した回帰検査

### 計測と履歴

- 最新サンプルの温度が取得不可なら、過去の2点だけから古い温度変化率を現在値として表示しない
- 最新バッテリー値が取得不可の場合も同様に古い変化率を現在値として出さない
- 実測時刻差から `/分` の変化率を計算する
- 30分窓、1分バケット、最大31点を維持する
- システム時計を巻き戻して既存行が一時的に未来扱いになっても、正常履歴を即座に物理削除しない
- 現在窓の表示/共有から未来点は除外する
- SQLite全体は最大31行へ制限する
- Main ThreadでApplication起動時のSQLite読み込みを行わない
- Service停止時のSQLite flushをMain Threadで行わない
- listener解除後にpost済み初期callbackが古いActivityへ届かない

### 継続計測

- Foreground Service中のみ `PARTIAL_WAKE_LOCK` を利用する
- WakeLockは10分の安全timeout付きで、計測継続中は5分ごとにrenewする
- Service停止時にWakeLockを即解放する
- deep sleepでもHandlerThreadがCPU停止だけを理由に長時間止まらない設計へ変更
- 省電力/重度以上の熱状態では60秒へ退避する

### リモート共有

- TCP応答受信時刻と最新測定時刻を分離する
- 古い測定値を新しく受信しても「たった今更新」と表示しない
- 手動fresh要求を通信開始時に消費せず、成功した応答だけを完了扱いにする
- fresh要求中の通信失敗でも要求を失わない
- 同じ失敗fresh要求でbusy-loopせず指数バックオフする
- バックオフ中に新しいユーザーfresh要求が来た場合だけ即時wakeする
- `invalid_session` / rate limit / viewer limit / protocol mismatch 等をWi-Fi障害と誤表示しない
- permanent protocol error と retryable server/transport error を分類する

### NSD / LAN防御

- Android 14+ の `ServiceInfoCallback` 経路も32サービス上限へ含める
- queue / resolved peer / callback / delayed retry を一つの総数上限として扱う
- lost-service tombstone をTTL・件数上限付きにする
- delayed retry Runnable を名前単位で管理し、stop/lost時に明示キャンセルする
- Android 17 / targetSdk 37 移行用に `ACCESS_LOCAL_NETWORK` を先行宣言する
- 現在はtargetSdk 36の互換挙動を維持する

### Turbo

- Turboは現在表示中の端末へ適用する
- リモート表示中にローカル端末まで不要に5秒サンプリングし続けない
- ローカルへ戻ればTurbo設定をローカルSamplerへ反映する

### APK署名 / ビルド

- versionCodeを4から5、versionNameを1.3.0から1.3.1へ更新
- release署名鍵をrepository内で自動生成しない
- 配布用release APKは明示した長期保管keystoreがある場合だけ生成する
- release鍵未設定時は開発署名の `BatteryRelay-1.3.1-dev.apk` として明確に分離する
- 既存 `.dev-signing.jks` の旧aliasとの互換性を維持する
- build-tools 35.0.0固定を廃止し、36.0.0優先 + 利用可能な最新版自動検出へ変更する

## 暗号・入力境界

既存の防御を維持したまま、以下の対象を継続検査します。

- P-256 ECDH
- HKDF-SHA-256
- AES-256-GCM
- sequence/AADによる再利用拒否
- 非P-256公開鍵拒否
- 128-bit共有キー
- pair replay制限
- session sequence単調増加
- 同時session/connection上限
- IP単位rate limit
- JSON depth / line-size制限

## 実機二台でのみ残る確認

CIのAndroid Emulatorでは次を完全には再現できません。

- メーカー固有のバッテリー温度・電流・残容量・thermal statusの品質
- Xiaomi等OEM独自のバックグラウンド/省電力制御
- 実際の画面消灯・Dozeを長時間継続した際の消費電力
- 物理端末二台間のmDNS / DNS-SD discovery
- ルーターのAP isolation / multicast制限
- VPN・モバイル回線・複数Wi-Fiが同時に存在する実環境でのNetwork binding
- targetSdk 37へ実際に上げた後のAndroid 17 `ACCESS_LOCAL_NETWORK` runtime permission UI
- Normal/Turboの長時間電力・発熱比較

これらは「既知のコードバグが残っている」という意味ではなく、Emulatorだけでは物理環境を保証できない範囲です。

## 配布APKについて

CIでは安全のためDebug APKだけを自動生成し、配布用release秘密鍵はGitHubへ保存しません。

`build-local.sh` は次の二経路です。

- 長期保管したrelease keystoreを明示: `dist/BatteryRelay-1.3.1.apk`
- release keystore未設定: `dist/BatteryRelay-1.3.1-dev.apk`

したがって、この検証記録にはrelease APKの固定SHA-256を捏造して記載しません。実際に配布するrelease APKを固定鍵で生成した時点で、その成果物のSHA-256を別途記録してください。
