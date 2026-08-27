# ONDOKEI 自動システム監査

## 対象の同定

GitHub アカウント内を横断確認した結果、`AAA` と `-APK-` に保存されていた
`BatteryRelay-source-1.3.0.zip` は SHA-256
`31f27561bc38433cb3eed3dfaaea5449a2b0dc459b9073cc8f551db59606f000` で一致しました。
展開内容はこのリポジトリの Battery Relay 1.3.0 ソースと一致します。アプリ ID は
`jp.rstlab.batteryrelay`、デバッグ APK は `jp.rstlab.batteryrelay.debug` です。
したがって、リポジトリ名 ONDOKEI の実体は「バッテリー残量・温度・履歴・暗号化端末共有」
を扱う Battery Relay Android プロジェクトとして監査します。

## 実行条件とコスト制御

- 通常ブランチへの push と pull request: API 35 の代表検査だけを実行
- `main` への push: API 26 / 30 / 34 / 35 / 36 のフルマトリクス
- Actions 画面から手動実行: API 26 / 30 / 34 / 35 / 36 のフルマトリクス
- 同じブランチへ新しい push が来た場合、古い実行をキャンセル
- APK は最初のジョブで一度だけビルドし、各 Emulator ジョブで再利用
- AVD を API ごとにキャッシュ

GitHub の `Actions` → `ONDOKEI full system audit` → `Run workflow` で、任意時点の
フル監査を開始できます。

## 自動検査される内容

### ビルド・静的監査

- JDK 17 / Android SDK 36 / Gradle Wrapper を使った再現ビルド
- JUnit の全ローカル単体テスト
- Android Lint（警告ではなくエラーを失敗扱い）
- デバッグ APK と instrumentation APK の生成
- ZIP コンテナ破損、4-byte alignment、APK 署名と証明書情報
- package/application ID、minSdk 26、targetSdk 36、起動 Activity
- Manifest、Foreground Service 宣言、`allowBackup=false`
- INTERNET、network/Wi-Fi、Foreground Service、通知権限の宣言
- 連絡先、通話履歴、SMS、録音、精密位置、カメラ等の想定外権限がないこと
- APK の SHA-256

### Emulator 動的監査

- APK と test APK のインストール
- 通知権限の拒否状態での初回起動と、許可後の再起動
- MainActivity の明示起動
- MonitorService が Foreground Service として動作すること
- Dashboard のバッテリー・温度表示
- 「更新」と「Turbo 5秒」の操作
- 30分履歴の表示
- 暗号化共有ダイアログの開始・停止導線
- 画面回転
- HOME でバックグラウンド化した後のサービス維持と画面復帰
- Wi-Fi/データ通信断と復帰
- trim-memory COMPLETE 相当の通知
- force-stop 後のプロセス再起動
- Java `FATAL EXCEPTION`、ANR、native crash の対象パッケージ検出
- 最終 Activity、Service、メモリ、通知状態の採取

### 固有機能と回帰検査

- バッテリー率・温度変化率が実時間差から計算されること
- 欠損値、異常数値、未来時刻、long underflow の境界処理
- 同一分の測定値置換、31点上限、30分を超えた履歴の物理削除
- 通常15秒、Turbo 5秒、非表示/未選択/省電力/高熱時60秒の更新規則
- P-256 ECDH、HKDF-SHA-256、AES-256-GCM の往復
- 改ざん ciphertext、別 sequence/AAD による再利用、非P-256鍵の拒否
- 共有キーの並列生成で、文字種・長さ・衝突耐性が維持されること
- SQLite への複数スレッド同時書き込み・prune競合時も31点・30分制約が破れないこと

## Artifacts

失敗時も `if: always()` で次を14日間保存します。

- `ondokei-build-static-audit`: APK、test APK、JUnit/Lint/HTML レポート、署名・Manifest・権限・hash
- `ondokei-emulator-api-XX`: instrumentation 出力、logcat/crash buffer、各段階のスクリーンショット、
  UI hierarchy、Activity/Service/通知/メモリ情報

## 実機でのみ最終確認できる項目

Emulator ではバッテリー温度・電流・残容量・thermal status が実端末と同じ品質では提供されません。
また、二台の物理端末間の同一 Wi-Fi DNS-SD 検出、AP isolation、VPN/モバイル回線との
network binding、OEM のバックグラウンド制限、Doze、通知UI、長時間の熱/消費電力、
機種固有のセンサー欠損は実機が必要です。共有通信の暗号・入力境界・UI導線はCIで検査しますが、
無線環境を含む二台間 end-to-end は `tools/power-audit-windows.ps1` と実機二台で補完してください。
