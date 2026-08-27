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

Turbo は現在表示している端末にだけ適用されます。リモート端末を選択している間、ローカル端末を不要に5秒間隔で測定し続けません。Turboの切替だけでローカル端末を余分に即時測定することもありません。ローカルタブへ戻ると、Turboが有効ならローカル測定も5秒へ戻ります。

画面上部には「この端末」、接続中の各端末、「＋ 端末」が横並びで表示されます。端末ボタンを押すだけで表示を切り替えられ、切替時も暗号化セッションは維持されます。端末ボタンの長押しだけが、その接続を明示的に解除します。通信が一時的に切れている端末は「再接続中」と表示します。

各分の最初の観測値はクラッシュ耐性のためSQLiteへ即時チェックポイントし、同じ1分内の後続測定はメモリ上の最新値で置き換えます。次の分へ進む時または明示停止時に、その分の最新値で同じminute rowを置換します。画面と共有に出すグラフは1分あたり1点、直近30分（境界を含め最大31点）だけです。DB自体も最大31行です。手動時刻変更や時刻同期で既存行が一時的に「未来」に見える場合は正常履歴を即破壊せず、現在の30分窓から隠したまま最大31行の範囲で保持します。そのため、時計異常保護中や計測停止後は30分より古い行がDBに残る場合がありますが、表示・共有には含めません。

各カードには、実際の計測時刻差から算出した変化量を `+1.0℃/分`、`-0.7%/分` の形式で表示します。十分な2点が揃うまでは「計測待ち」です。最新サンプルで温度などを取得できなかった場合、過去の値から古い変化率を作って現在値のように表示することはありません。

### 別の端末へ共有する

1. 2台を同じ Wi‑Fi に接続します。
2. 共有元で「この端末から共有」を押し、表示された26文字の128ビット共有キーを確認します。
3. 共有先で画面上部の「＋ 端末」を押し、自動検出された共有元を選び、共有キーを入力します。
4. 接続後は通常15秒、Turboは5秒ごとに暗号化スナップショットを取得し、共有先にも同じ数値・変化率・30分グラフを表示します。

表示していない接続先と、アプリ画面が見えていない間の接続先は60秒間隔へ自動的に下げます。接続は切らず、再選択すると直ちに通常/Turbo間隔へ戻ります。最大8台を同時に維持できます。

共有ダイアログを閉じる場合は「共有を継続して閉じる」または「共有を停止」を明示的に選びます。外側タップや戻る操作だけで、共有が続いていることを見落とす状態にはしません。

リモート画面の「○秒前に更新」は、TCP応答を受信した時刻ではなく**最新の実測値の時刻**です。通信だけ成功して測定値が古い場合に「たった今更新」と誤表示しません。リモートグラフの右端も実際の現在時刻なので、通信が止まった古い点は時間とともに左へ移動します。端末時計が将来方向へずれている場合は「端末時刻に差があります」と表示します。また、手動更新要求は通信成功まで保持されるため、更新開始直後にWi‑Fiが瞬断しても要求自体が消えません。

## 継続計測・省電力・小熱設計

- Foreground Serviceで継続計測します。5秒/15秒モードではdeep sleep中の短周期測定を維持するため `PARTIAL_WAKE_LOCK` を保持します。WakeLockは10分の安全タイムアウト付きで、計測継続中は5分ごとに更新します。
- 省電力モードまたは重度以上の熱状態では60秒へ退避し、保護モード中はWakeLockも解放します。そのため保護中はOSのsleep状況により60秒より遅れる場合があります。
- 通知からの明示停止では、現在minuteの最新値をSQLiteへflushしてからForeground Serviceを終了します。
- AlarmManager、常時GPS、クラウド同期、分析SDK、広告SDKは使いません。
- 測定・暗号・ネットワーク・DB I/O のスレッドはAndroidのバックグラウンド優先度です。アプリ起動や明示停止のMain ThreadでSQLiteトランザクションを実行しません。
- SQLiteは1分1rowです。クラッシュ耐性の最初のcheckpointと、その分の最終値による置換が発生するため、物理write回数を「最大1回/分」とは保証しません。
- 選択していない共有端末、画面非表示中の共有端末は60秒間隔です。
- DNS-SD検索は「＋ 端末」のダイアログを開いている間だけ動作します。探索対象は最大32サービスに制限し、API 34以降の未検証サービスは5秒以内に有効なBattery Relay TXT情報を出せなければ追跡枠から解放します。消失端末の墓標と再試行Runnableにも上限・期限があります。
- 共有元のTCP要求と共有先のTCP応答には、`SO_TIMEOUT`に加えてリクエスト/レスポンス全体の絶対締切を設け、1バイトずつ送るslowlorisで接続枠を占有できないようにしています。

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

- 表示・共有履歴は直近30分・最大31点です。SQLiteも最大31行に制限します。時計巻き戻しや停止後に30分より古い行が物理的に残る場合でも、現在窓の表示・共有には含めません。Android バックアップ／端末移行からも除外します。
- 共有はローカル Wi‑Fi 内だけです。クラウド、分析 SDK、広告 SDKはありません。
- 128ビット共有キー自体は通信へ平文送信しません。コピー時は機密クリップとして扱い、60秒後に同じ内容なら消去します。
- ペアリングは P-256 ECDH + HKDF-SHA-256、データは AES-256-GCM で認証暗号化します。
- 再送防止用トークンと単調増加シーケンス、原子的なIP単位試行制限、認証前接続枠、同時閲覧上限、アイドル期限を設けています。
- IPv6は/64単位で接続枠・試行頻度をまとめ、privacy addressを切り替えて同一LAN端末が接続枠を水増ししにくくしています。
- 認証済み端末からの即時更新も、端末全体とセッションごとに最低5秒へ制限します。省電力中・重度以上の熱状態では即時測定を拒否します。
- TCP待受と接続ソケットは選択したWi‑Fiネットワークへ固定し、VPNやモバイル回線へ意図せず迂回しません。
- サーバー側の `invalid_session`、rate limit、version mismatch などはWi‑Fi障害と分離して表示し、永久的なプロトコルエラーを無限再試行しません。
- 「共有を停止」または計測通知の停止操作で、共有セッション鍵をメモリ上から消去します。

共有キーは128ビットのランダム値で、偽のmDNS共有元に対するオフライン総当たりを現実的に不可能にします。画面またはクリップボードを第三者へ渡さないでください。

## Android 17 / targetSdk 37 について

現在の targetSdk は36で、Manifestには `android.permission.ACCESS_LOCAL_NETWORK` をまだ宣言していません。targetSdkを37以上へ移行する時は、permission宣言とAndroid 17のRuntime permission UXを**同じ変更で**追加してからリリースします。権限拒否時はLAN共有を開始せず、ユーザーへ明示する設計にします。

## ビルドと署名

Android SDK 36、JDK 17、Gradle 8.13 を使用します。生成バイトコードは不要なランタイム依存を避けるためJava 8互換です。Gradle WrapperのdistributionはSHA-256を固定しています。

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

この設定時だけ `dist/BatteryRelay-1.3.1.apk` を生成します。release鍵を設定していない場合は、開発専用の `.dev-signing.jks` を使って `dist/BatteryRelay-1.3.1-dev.apk` を生成し、配布版と明確に区別します。`build-local.sh` は `apksigner` のpassword sourceに環境変数を使い、署名パスワードをprocess argvへ載せません。Gradleのrelease assemble/bundle/packageもrelease署名環境変数が無ければ失敗させるため、別PCで勝手に新しい配布鍵を作って既存APKを更新不能にする事故を防ぎます。

## アイコン

アプリアイコンは内蔵画像生成で作成し、`app/src/main/res/drawable-nodpi/app_icon_foreground.png` に収録しています。最終プロンプトは次のとおりです。

> Use case: logo-brand. Asset type: Android adaptive app icon foreground artwork. Create an original, polished icon mark that combines a vertical thermometer with a partially filled smartphone battery, communicating battery level and device temperature monitoring. One unified geometric symbol, not two disconnected icons. Clean premium flat raster illustration, vector-like edges, subtle tactile paper-like depth only if it stays crisp. Perfectly centered square composition, strong silhouette, generous transparent padding, readable at 48 px. Muted terracotta orange, deep charcoal, and a small amount of calm slate blue; restrained natural colors. Genuinely transparent background and clean alpha edges; no text; no letters; no numbers; no gradients; no neon colors; no glow; no mockup; no border frame; no trademark; no watermark; no tiny details.

設計詳細は `docs/ARCHITECTURE.md`、検証結果は `docs/TEST_REPORT.md` を参照してください。

## GitHub Actions 自動システム監査

`.github/workflows/ondokei-system-audit.yml` は、ビルド、JUnit、Lint、APK署名・Manifest・permissions・SDK整合性に加え、READMEで案内する**実際の `build-local.sh` 配布経路**も毎回実行してAPK監査します。Android Emulator上では初回起動・権限拒否/許可・Foreground Service・WakeLock・画面回転・バックグラウンド復帰・通信断/復帰・低メモリ・プロセス再起動・主要UI機能・クラッシュ/ANR検出を自動実行します。通常ブランチはAPI 35だけ、`main` と手動実行はAPI 26/30/34/35/36です。失敗時もAPK、JUnit/Lint結果、logcat、Service/メモリ/Power情報、段階別スクリーンショットをArtifactsへ保存します。

検査範囲、Artifacts、実機で残る確認項目、手動フル監査の実行方法は `docs/CI_AUDIT.md` を参照してください。
