# Battery Relay

Battery Relay は、Android 端末のバッテリー残量・残り容量・バッテリー温度を表示し、直近30分の推移を同じ Wi‑Fi 上の別端末へ暗号化共有するアプリです。クラウドや外部サーバーは使いません。

## インストール

1. 配布用に固定した同一のリリース鍵で署名された `dist/BatteryRelay-1.3.1.apk` を Android 端末へコピーします。
2. ファイルを開き、求められた場合は、そのファイル管理アプリに限って「不明なアプリのインストール」を許可します。
3. Battery Relay を起動し、Android 13 以降では通知を許可します。通知は継続計測を明示し、通知内の「計測を停止」からいつでも終了できます。

対応 OS は Android 8.0（API 26）以降です。

## 使い方

### 更新・Turbo・端末切替

起動すると通常は15秒ごとに値を読みます。「更新」は表示中の端末へ即時の新規測定を要求します。「Turbo 5秒」を有効にすると、表示中の端末を5秒間隔で更新します。省電力モード中またはOSの熱状態が「重度」以上なら、Turbo中でもローカル測定を60秒へ自動退避します。

Turbo は現在表示している端末にだけ適用されます。リモート端末を選択している間、ローカル端末を不要に5秒間隔で測定し続けることはありません。ローカルタブへ戻ると、Turboが有効ならローカル測定も5秒へ戻ります。

画面上部には「この端末」、接続中の各端末、「＋ 端末」が横並びで表示されます。端末ボタンを押すだけで表示を切り替えられ、切替時も暗号化セッションは維持されます。端末ボタンの長押しだけが、その接続を明示的に解除します。

同じ1分内の測定はメモリ上の最新値で置き換え、SQLiteには各分の最後の測定を最大1回/分で書き込みます。グラフは1分あたり1点、直近30分（境界を含め最大31点）だけです。30分より古い行は物理削除します。実行中の手動/NTP時計変更で正常な履歴を「未来値」と誤認して削除しないよう、測定時刻には単調時計を基準にした連続タイムラインを使い、時計巻き戻し時の将来扱い行は削除せず一時的に非表示にします。

各カードには、実際の計測時刻差から算出した変化量を `+1.0℃/分`、`-0.7%/分` の形式で表示します。十分な2点が揃うまでは「計測待ち」です。最新サンプルで温度などを取得できなかった場合、過去の値から古い変化率を作って現在値のように表示することはありません。

### 別の端末へ共有する

1. 2台を同じ Wi‑Fi に接続します。
2. 共有元で「この端末から共有」を押し、表示された26文字の128ビット共有キーを確認します。
3. 共有先で画面上部の「＋ 端末」を押し、自動検出された共有元を選び、共有キーを入力します。
4. 接続後は通常15秒、Turboは5秒ごとに暗号化スナップショットを取得し、共有先にも同じ数値・変化率・30分グラフを表示します。

表示していない接続先と、アプリ画面が見えていない間の接続先は60秒間隔へ自動的に下げます。接続は切らず、再選択すると直ちに通常/Turbo間隔へ戻ります。最大8台を同時に維持できます。

リモート画面の「○秒前に更新」は、TCP応答を受信した時刻ではなく**最新の実測値の時刻**です。通信だけ成功して測定値が古い場合に「たった今更新」と誤表示しません。また、手動更新要求は通信成功まで保持されるため、更新開始直後にWi‑Fiが瞬断しても要求自体が消えません。

## 継続計測・省電力・小熱設計

- Android の deep sleep 中でも5/15/60秒の継続計測を維持するため、Foreground Service の計測中だけ `PARTIAL_WAKE_LOCK` を保持します。通知から計測を停止すると即座に解放します。
- このWakeLockは精密な短周期計測と引き換えに一定の電池消費があります。省電力モードまたは重度以上の熱状態では測定間隔を60秒へ退避します。
- AlarmManager、常時GPS、クラウド同期、分析SDK、広告SDKは使いません。
- 測定・暗号・ネットワーク・DB I/O のスレッドはAndroidのバックグラウンド優先度です。アプリ起動やサービス停止のMain ThreadでSQLiteトランザクションを実行しません。
- SQLiteはTurboでも最大1回/分、通知の再描画も最大1回/分です。
- 選択していない共有端末、画面非表示中の共有端末は60秒間隔です。
- Android省電力モードまたは熱状態「重度」以上ではローカル測定を60秒へ退避します。
- DNS-SD検索は「＋ 端末」のダイアログを開いている間だけ動作します。探索レコードは旧/新NSD APIを合算して最大32件、消失端末の墓標と再試行Runnableにも上限・期限があります。
- 共有元の待受はブロッキングI/Oで、通信がない間にポーリングしません。

実機の消費電力は端末・OS・Wi‑Fi環境で変わります。Windows + ADB用の `tools/power-audit-windows.ps1` でNormal/Turboを別々に測り、CPU使用率、バッテリー温度、thermal status、batterystatsを保存できます。

端末が見つからない場合は、両方が同じアクセスポイントにいることを確認してください。ゲスト Wi‑Fi の端末間隔離（AP isolation）が有効なネットワークでは共有できません。

## 表示値について

- バッテリー残量: Android の残量率（%）
- 残り容量: 端末が `BATTERY_PROPERTY_CHARGE_COUNTER` を公開する場合だけ mAh 表示
- バッテリー温度: Android の `EXTRA_TEMPERATURE`。CPU/SoC 温度ではありません
- 電流: 端末が公開する瞬時電流。正負の向きは端末実装に従います
- 熱状態: Android 10 以降の OS thermal status

メーカーが値を公開しない場合は、推測値を作らず「非対応」または「取得不可」と表示します。

## プライバシーと共有保護

- 履歴は30分を超えて表示・保持対象にせず、Android バックアップ／端末移行からも除外します。
- 共有はローカル Wi‑Fi 内だけです。クラウド、分析 SDK、広告 SDKはありません。
- 128ビット共有キー自体は通信へ平文送信しません。コピー時は機密クリップとして扱い、60秒後に同じ内容なら消去します。
- ペアリングは P-256 ECDH + HKDF-SHA-256、データは AES-256-GCM で認証暗号化します。
- 再送防止用トークンと単調増加シーケンス、原子的なIP単位試行制限、認証前接続枠、同時閲覧上限、アイドル期限を設けています。
- 認証済み端末からの即時更新も、端末全体とセッションごとに最低5秒へ制限します。省電力中・重度以上の熱状態では即時測定を拒否します。
- TCP待受と接続ソケットは選択したWi‑Fiネットワークへ固定し、VPNやモバイル回線へ意図せず迂回しません。
- サーバー側の `invalid_session`、rate limit、version mismatch などはWi‑Fi障害と分離して表示し、永久的なプロトコルエラーを無限再試行しません。
- 「共有を停止」または計測通知の停止操作で、共有セッション鍵をメモリ上から消去します。

共有キーは128ビットのランダム値で、偽のmDNS共有元に対するオフライン総当たりを現実的に不可能にします。画面またはクリップボードを第三者へ渡さないでください。

## Android 17 / targetSdk 37 について

Manifest には将来の `android.permission.ACCESS_LOCAL_NETWORK` を先行宣言しています。現在の targetSdk は36なので従来互換動作です。将来 targetSdk を37以上へ上げる際は、Android 17上でローカルネットワーク権限のRuntime permission UIを追加してからリリースしてください。権限拒否時はクライアント側がLAN権限不足として明示エラーを返します。

## ビルドと署名

Android SDK 36、JDK 17、Gradle 8.13 を使用します。生成バイトコードは不要なランタイム依存を避けるためJava 8互換です。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

`build-local.sh` は利用可能なbuild-toolsを自動検出します。リリースAPKを作る場合は、**初回配布から同じ長期リリース鍵を必ず使ってください**。

```bash
export BATTERY_RELAY_RELEASE_KEYSTORE=/secure/path/battery-relay-release.jks
export BATTERY_RELAY_STORE_PASSWORD='...'
export BATTERY_RELAY_KEY_ALIAS='...'
export BATTERY_RELAY_KEY_PASSWORD='...'
./build-local.sh
```

この設定時だけ `dist/BatteryRelay-1.3.1.apk` を生成します。release鍵を設定していない場合は、開発専用の `.dev-signing.jks` を使って `dist/BatteryRelay-1.3.1-dev.apk` を生成し、配布版と明確に区別します。Gradleのrelease assemble/bundle/packageもrelease署名環境変数が無ければ失敗させるため、別PCで勝手に新しい配布鍵を作って既存APKを更新不能にする事故を防ぎます。

## アイコン

アプリアイコンは内蔵画像生成で作成し、`app/src/main/res/drawable-nodpi/app_icon_foreground.png` に収録しています。最終プロンプトは次のとおりです。

> Use case: logo-brand. Asset type: Android adaptive app icon foreground artwork. Create an original, polished icon mark that combines a vertical thermometer with a partially filled smartphone battery, communicating battery level and device temperature monitoring. One unified geometric symbol, not two disconnected icons. Clean premium flat raster illustration, vector-like edges, subtle tactile paper-like depth only if it stays crisp. Perfectly centered square composition, strong silhouette, generous transparent padding, readable at 48 px. Muted terracotta orange, deep charcoal, and a small amount of calm slate blue; restrained natural colors. Genuinely transparent background and clean alpha edges; no text; no letters; no numbers; no gradients; no neon colors; no glow; no mockup; no border frame; no trademark; no watermark; no tiny details.

設計詳細は `docs/ARCHITECTURE.md`、検証結果は `docs/TEST_REPORT.md` を参照してください。

## GitHub Actions 自動システム監査

`.github/workflows/ondokei-system-audit.yml` は、ビルド、JUnit、Lint、APK署名・Manifest・permissions・SDK整合性、Android Emulator上の初回起動・権限拒否/許可・Foreground Service・**継続計測WakeLock**・画面回転・バックグラウンド復帰・通信断/復帰・低メモリ・プロセス再起動・主要UI機能・クラッシュ/ANR検出を自動実行します。通常ブランチはAPI 35だけ、`main` と手動実行はAPI 26/30/34/35/36です。失敗時もAPK、JUnit/Lint結果、logcat、Service/メモリ/Power情報、段階別スクリーンショットをArtifactsへ保存します。

検査範囲、Artifacts、実機で残る確認項目、手動フル監査の実行方法は `docs/CI_AUDIT.md` を参照してください。
