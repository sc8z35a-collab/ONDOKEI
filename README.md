# Battery Relay

Battery Relay は、Android 端末のバッテリー残量・残り容量・バッテリー温度を表示し、直近30分の推移を同じ Wi‑Fi 上の別端末へ暗号化共有するアプリです。クラウドや外部サーバーは使いません。

## インストール

1. `dist/BatteryRelay-1.3.0.apk` を Android 端末へコピーします。
2. ファイルを開き、求められた場合は、そのファイル管理アプリに限って「不明なアプリのインストール」を許可します。
3. Battery Relay を起動し、Android 13 以降では通知を許可します。通知は継続計測を明示し、通知内の「計測を停止」からいつでも終了できます。

対応 OS は Android 8.0（API 26）以降です。

## 使い方

### 更新・Turbo・端末切替

起動すると通常は15秒ごとに値を読みます。「更新」は表示中の端末へ即時の新規測定を要求します。「Turbo 5秒」を有効にすると、表示中の端末を5秒間隔で更新します。省電力モード中またはOSの熱状態が「重度」以上なら、Turbo中でもローカル測定を60秒へ自動退避します。

画面上部には「この端末」、接続中の各端末、「＋ 端末」が横並びで表示されます。端末ボタンを押すだけで表示を切り替えられ、切替時も暗号化セッションは維持されます。端末ボタンの長押しだけが、その接続を明示的に解除します。

同じ1分内の測定はメモリ上の最新値で置き換え、SQLiteには各分の最後の測定を最大1回/分で書き込みます。グラフは1分あたり1点、直近30分（境界を含め最大31点）だけです。30分より古い行と未来時刻の異常行は、書き込み時と読み出し時の両方で SQLite から物理削除します。

各カードには、実際の計測時刻差から算出した変化量を `+1.0℃/分`、`-0.7%/分` の形式で表示します。十分な2点が揃うまでは「計測待ち」です。

### 別の端末へ共有する

1. 2台を同じ Wi‑Fi に接続します。
2. 共有元で「この端末から共有」を押し、表示された26文字の128ビット共有キーを確認します。
3. 共有先で画面上部の「＋ 端末」を押し、自動検出された共有元を選び、共有キーを入力します。
4. 接続後は通常15秒、Turboは5秒ごとに暗号化スナップショットを取得し、共有先にも同じ数値・変化率・30分グラフを表示します。

表示していない接続先と、アプリ画面が見えていない間の接続先は60秒間隔へ自動的に下げます。接続は切らず、再選択すると直ちに通常/Turbo間隔へ戻ります。最大8台を同時に維持できます。

## 省電力・小熱設計

- WakeLock、AlarmManager、常時GPS、クラウド同期、分析SDK、広告SDKは使いません。
- 測定・暗号・ネットワークのスレッドはAndroidのバックグラウンド優先度です。
- SQLiteはTurboでも最大1回/分、通知の再描画も最大1回/分です。
- 選択していない共有端末、画面非表示中の共有端末は60秒間隔です。
- Android省電力モードまたは熱状態「重度」以上ではローカル測定を60秒へ退避します。
- DNS-SD検索は「＋ 端末」のダイアログを開いている間だけ動作します。共有元の待受はブロッキングI/Oで、通信がない間にポーリングしません。

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

- 履歴は30分を超えて保持せず、Android バックアップ／端末移行からも除外します。
- 共有はローカル Wi‑Fi 内だけです。クラウド、分析 SDK、広告 SDKはありません。
- 128ビット共有キー自体は通信へ平文送信しません。コピー時は機密クリップとして扱い、60秒後に同じ内容なら消去します。
- ペアリングは P-256 ECDH + HKDF-SHA-256、データは AES-256-GCM で認証暗号化します。
- 再送防止用トークンと単調増加シーケンス、原子的なIP単位試行制限、認証前接続枠、同時閲覧上限、アイドル期限を設けています。
- 認証済み端末からの即時更新も、端末全体とセッションごとに最低5秒へ制限します。省電力中・重度以上の熱状態では即時測定を拒否します。
- TCP待受と接続ソケットは選択したWi‑Fiネットワークへ固定し、VPNやモバイル回線へ意図せず迂回しません。
- 「共有を停止」または計測通知の停止操作で、共有セッション鍵をメモリ上から消去します。

共有キーは128ビットのランダム値で、偽のmDNS共有元に対するオフライン総当たりを現実的に不可能にします。画面またはクリップボードを第三者へ渡さないでください。

## ビルド

Android SDK 36、JDK 17、Gradle 8.13 を使用します。生成バイトコードは不要なランタイム依存を避けるためJava 8互換です。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

外部ライブラリに依存しない Android Platform API ベースの実装です。`build-local.sh` は Android build-tools だけでも、パッケージ名 `jp.rstlab.batteryrelay` の署名済みローカル配布 APK を生成できます。公開配布する場合は、自分で安全に保管したリリース鍵へ差し替えてください。

## アイコン

アプリアイコンは内蔵画像生成で作成し、`app/src/main/res/drawable-nodpi/app_icon_foreground.png` に収録しています。最終プロンプトは次のとおりです。

> Use case: logo-brand. Asset type: Android adaptive app icon foreground artwork. Create an original, polished icon mark that combines a vertical thermometer with a partially filled smartphone battery, communicating battery level and device temperature monitoring. One unified geometric symbol, not two disconnected icons. Clean premium flat raster illustration, vector-like edges, subtle tactile paper-like depth only if it stays crisp. Perfectly centered square composition, strong silhouette, generous transparent padding, readable at 48 px. Muted terracotta orange, deep charcoal, and a small amount of calm slate blue; restrained natural colors. Genuinely transparent background and clean alpha edges; no text; no letters; no numbers; no gradients; no neon colors; no glow; no mockup; no border frame; no trademark; no watermark; no tiny details.

設計詳細は `docs/ARCHITECTURE.md`、検証結果は `docs/TEST_REPORT.md` を参照してください。

## GitHub Actions 自動システム監査

`.github/workflows/ondokei-system-audit.yml` は、ビルド、JUnit、Lint、APK署名・Manifest・
permissions・SDK整合性、Android Emulator上の初回起動・権限拒否/許可・Foreground Service・
画面回転・バックグラウンド復帰・通信断/復帰・低メモリ・プロセス再起動・主要UI機能・
クラッシュ/ANR検出を自動実行します。通常ブランチはAPI 35だけ、`main` と手動実行は
API 26/30/34/35/36です。失敗時もAPK、JUnit/Lint結果、logcat、Service/メモリ情報、
段階別スクリーンショットをArtifactsへ保存します。

検査範囲、Artifacts、実機で残る確認項目、手動フル監査の実行方法は
`docs/CI_AUDIT.md` を参照してください。
