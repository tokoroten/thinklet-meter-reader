# Meter Reader（THINKLET メータ読み取りアプリ）

水道・ガス・電力・圧力・温度などの**各種メータを一人称カメラで撮影し、OpenAI(Vision)＋Structured Outputs で
数値化**して読み上げ・記録する、Fairy Devices **THINKLET (LC01)** 向け Android アプリ。
内蔵HTTPサーバ・履歴/CSV・Josee TTS・CameraX・露出制御を備え、メータ読取はクラウドVLMが担当する。

- **音量Down**：撮影 → OpenAIへ画像アップロード → メータ値をJSONで取得 → TTSで読み上げ＋履歴に記録
- **音量Up（1回）**：直前の読み取り値を再読み上げ／**音量Up（連打）**：アクセス先アドレスを読み上げ（→ [操作方法](#操作方法物理ボタン)）
- **QR/バーコードを同時認識**：撮影画像を端末内 ML Kit でもスキャンし、検出コードを**顧客ID/メーターID**として読み値とペアで記録（→ [QR/バーコードによるID連携](#qrバーコードによるid連携)）
- 設定（APIキー/モデル/ヒント）は**内蔵HTTPの設定ページ**から投入し、**Android Keystore で暗号化**して端末内に保存（→ [APIキーの保存](#apiキーの保存セキュリティ)）
- 撮影履歴は**画像＋認識結果をディスクに永続化**（再起動後も残る）。内蔵HTTPの**履歴画面**で画像付き閲覧・**CSVダウンロード**

> メータ読取は **WiFi/LTE 必須**（クラウドVLM利用）。QR/バーコード認識・TTS・履歴・暗号化保存は端末内（オフライン）で完結。

## 前提 / プライバシー

- **THINKLET (LC01) 実機**が必要（Android 8.1 / API27, arm64-v8a, GMSなし, 広角カメラ）。一般のAndroid/エミュレータは対象外。
- **自分の OpenAI API キー**が必要（設定ページで投入。端末内に暗号化保存）。
- **撮影画像は OpenAI(クラウド)へ送信**されます（メータ読取のため）。プライバシー要件のある現場では送信先・取扱いを確認のこと。QR/バーコード認識・TTS・履歴・キー暗号化は端末内で完結。
- TTS の **Josee は本リポジトリに含みません**（別途導入。未導入でも無音で動作）。

## 操作方法（物理ボタン）

画面・タッチが無いため、本体の音量ボタンで操作する。

| 操作 | 動作 |
| --- | --- |
| **音量↓（1回）** | **撮影** → OpenAIで読み取り → TTS読み上げ＋履歴に記録 |
| **音量↑（1回）** | **直近の読み取り結果を再読み上げ** |
| **音量↑（2回以上の連打）** | **アクセス先アドレスを読み上げ＋画面表示**（mDNS名 `<name>.local`。無ければIP＋ポート） |

> 連打判定は約0.45秒以内。adb から試すなら `input keyevent 25`（↓撮影）/ `input keyevent 24`（↑1回）/ `input keyevent 24 24`（↑連打＝アドレス読み上げ。別コマンドで2回呼ぶと間隔が空いて連打にならない）。

## ビルド & インストール

```powershell
# adb は PATH 上にある前提。シリアルは `adb devices` で確認して置き換える。
$ADB = "adb"; $S = "<YOUR_THINKLET_SERIAL>"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # Windows + Android Studio の例（任意）
cd path\to\thinklet-meter-reader
.\gradlew.bat :app:assembleDebug
& $ADB -s $S install -r -g app\build\outputs\apk\debug\app-debug.apk   # -g でCAMERA権限を事前付与
& $ADB -s $S shell am start -n com.example.meterreader/.MainActivity
& $ADB -s $S logcat -s MeterReader
```

## 使い方

### 1. 設定（APIキー投入）

画面が無いので、ブラウザから設定する。USBのみなら `adb forward` 経由が安全（キーがLANに出ない）:

```powershell
& $ADB -s $S forward tcp:8080 tcp:8080
# → ブラウザで http://localhost:8080/config を開き、APIキー・モデル名・任意ヒントを保存
```

同一WiFiなら端末IP（起動ログ `HTTP listening on :8080 -> ...` か、読み取り画面の表示）で `http://<端末IP>:8080/config`。

- **モデル**は**ドロップダウン選択**（既定 `gpt-5`／`gpt-5-mini`／`gpt-5.4`／`gpt-5.4-mini`／`gpt-4o`…）または**「カスタム入力」で自由記述**（任意のVision対応モデルID）。
- **reasoning 強度**（`reasoning_effort`）を選択可：`指定なし`（モデル既定）/ `minimal` / `low` / `medium` / `high`。指定なしのときはリクエストに付与しない。
- **エンドポイントは可変**＝**OpenAI互換サーバ**も利用可（`/v1/chat/completions` 互換）。互換先が `reasoning_effort` 非対応なら「指定なし」に。
- **ヒント**は任意（例: 「水道メータ 単位m3」「黒地に白の積算計、5桁＋小数1桁」）。読み取り精度の補助になる。
- **カメラ露出**（露光時間µs・ISO）も同じ設定ページで調整可（→ [カメラ露出の調整](#カメラ露出の調整ノイズ低減)）。
- APIキーは **Android Keystore で暗号化**して保存し、ログ・`/state.json` には**末尾4桁のマスク表示のみ**（→ [APIキーの保存](#apiキーの保存セキュリティ)）。

### 2. 撮影・読み取り

1. 読み取り画面（`http://<host>:8080/`）の**「ライブ（照準）」**でメータを画面に収める。
2. 端末の**音量Down**で撮影（adb からは `& $ADB -s $S shell input keyevent 25`）。
3. 数秒後、TTS が値を読み上げ、画面トップに最新値＋履歴が1行増える。
4. **音量Up 1回**で直前値を再読み上げ。**音量Up 2回以上の連打**で**アクセス先アドレス（mDNS名、無ければIP＋ポート）を音声＋画面で案内**（→ [名前でアクセス](#名前でアクセスmdns)）。

### 3. 履歴画面

撮影ごとに**画像＋認識結果（生JSON含む）＋検出ID をディスクに永続化**（再起動後も残る, 上限300件）。
`GET /history` が撮影履歴ギャラリー（画像・値・単位・種別・信頼度・ID・notes をカード表示, 自動更新）。

| エンドポイント | 内容 |
| --- | --- |
| `GET /` | ライブ（照準）＋直近撮影＋最新値＋直近テーブル（自動更新, オフライン保留件数も表示） |
| `GET /history` | **撮影履歴ギャラリー**（画像付きカード, ID表示, 生JSON展開, CSV/クリア） |
| `GET /history.jpg?ts=<ts>` | 各レコードの撮影画像（バーコード枠付き） |
| `GET /config` ・ `POST /config` | 設定フォーム（APIキー/モデル/エンドポイント/ヒント/露光/ISO） |
| `GET /state.json` | ビュー用JSON（最新値＋直近＋設定状態＋保留件数。**キー実体は返さない**） |
| `GET /records.json` ・ `GET /records.csv` | 全履歴の機械可読JSON / CSV（`codes`=検出ID 列を含む） |
| `GET /clear` | 記録（画像含む）を全消去 |
| `GET /shot.jpg` ・ `GET /preview.jpg` | 直近の撮影画像 / 照準用ライブ |

## デバッグ：adb で画像を流し込んで OpenAI に投げる

カメラで実物を撮らずに、任意の画像ファイルで読み取りパイプライン（OpenAI 呼び出し→記録→読み上げ）を検証できる。

```powershell
# 1) 画像を既定パスへ push
& $ADB -s $S push .\testdata\water_meter.jpg /sdcard/Android/data/com.example.meterreader/files/debug.jpg
# 2) ブロードキャストで推論実行（既定パス debug.jpg を使用）
& $ADB -s $S shell am broadcast -a com.example.meterreader.DEBUG_READ

# パスを明示する場合（任意の場所の画像でOK）
& $ADB -s $S push .\testdata\gauge.jpg /sdcard/foo.jpg
& $ADB -s $S shell am broadcast -a com.example.meterreader.DEBUG_READ --es path /sdcard/foo.jpg
```

結果は通常の撮影と同じく、TTS読み上げ＋ `/`（履歴）に `source=debug` として記録される。
> 受信は**アプリ起動中（前面）**であること。事前に `am start -n com.example.meterreader/.MainActivity` で前面化しておく。

## 読み取りスキーマ（OpenAI Structured Outputs, strict）

| フィールド | 型 | 内容 |
| --- | --- | --- |
| `meter_type` | enum | water/gas/electric/pressure/temperature/other/unknown |
| `display_type` | enum | digital/odometer/analog_dial/unknown |
| `value` | number\|null | 読み取り数値（読めなければ null） |
| `value_text` | string | 表示そのままの読み（例 `01234.5`） |
| `unit` | string\|null | 単位（m3, kWh, MPa, ℃ …） |
| `confidence` | number | 0.0〜1.0 |
| `reading_ok` | boolean | 確信を持って読めたか |
| `notes` | string | 曖昧さ・複数候補・読めない理由など |

system プロンプトに**読み取り規則**（計測レジスタのみ読む／オドメータは全桁・回転途中は小さい方／補助ダイヤルと混同しない／
**単位は文字盤表記を優先・無ければnull**／**種別は断定可能時のみ・不明はunknown**／value_textは表示そのまま 等）と
**confidence の目安**を明記して精度を上げている（[OpenAiClient.kt](app/src/main/java/com/example/meterreader/OpenAiClient.kt) の `SYSTEM_PROMPT`）。

## QR/バーコードによるID連携

撮影画像を OpenAI と**並行して端末内 ML Kit**（バンドル版＝GMS非依存, 全フォーマット）でスキャンし、検出した
バーコード/QR を **顧客ID・メーターID** として扱う。

- 検出コードは「**値ではなくID**」と明示して OpenAI のプロンプト文脈へ渡す（メータ値との取り違えを防止）。
- 履歴に `codes`（ID）として読み値とペアで保存（`/records.json`・CSV `codes` 列・`/history`・ライブ画面に表示）。
- **検出領域を撮影画像に可視化**：黄枠＋IDラベルを描画した画像を画面と履歴サムネに表示（OpenAIへ送る画像はクリーンなまま）。`/history.jpg?ts=` で確認可。

## カメラ露出の調整（ノイズ低減）

既定は自動（ブレ抑制のため上限露光を固定して明るさ適応）。ノイズが気になる場合は設定ページで
**露光時間（µs）を長く・ISO を低く**設定すると低ノイズにできる（メータは静止物なので長秒露光が有効）。

- `露光時間(µs)` と `ISO` を **両方 > 0** にすると手動固定、どちらか `0` で自動。設定ページにセンサーの有効範囲を表示。
- 例：屋内の据置メータなら 露光 20000–50000µs（1/50–1/20s）＋ ISO 100 で粒状ノイズが大きく減る。
- 露出設定も暗号化ストアに永続化（再起動後も維持）。

## オフライン時のキュー（接続復帰で自動認識）

WiFi/LTE 未接続時は OpenAI を呼ばず、撮影画像を**キューに保存**して即座にフィードバック（TTS/画面）。
**接続が復帰したら自動で順に再認識**して履歴へ反映する（`source` は `…+queued`）。

- 通信タイムアウトや 5xx/429 など一時障害も同様にキューへ（401 等の恒久エラーは破棄）。
- キューは `filesDir/queue/` に画像＋メタを永続化（アプリ再起動後の復帰時にも処理）。保留件数はライブ画面に表示。

## 名前でアクセス（mDNS）

画面の無い端末でIPが分からない／DHCPで変わる問題に対し、起動時に **mDNS（jmDNS, GMS非依存）でホスト名を登録**する。
同一LANのブラウザ/adb から `http://<name>.local:8080/` で名前アクセスできる（クライアントOSが `.local` 解決対応の場合）。

- **既定名は端末固有の `meter-XXXX`**（初回起動で乱数決定→暗号化ストアに永続化, 再起動でも不変）。**/config の「デバイス名」で任意名に変更可**（例 `meter` / `truck3` / `bldgA`。英小文字・数字・ハイフンに自動正規化）。
- **衝突回避は「一意な既定名」で担保**：本機(Android 8.1)では **jmDNS のマルチキャスト受信が不安定で、同名衝突時の自動リネーム(-2)が不確実**（実機で確認）。そのため**最初から重複しない既定名**にしている。**複数台で手動で同名に揃えると衝突する**ので、各端末は別名にすること（単一台なら `meter` 等の短名でOK）。
- 名前は **読み取り画面ヘッダ・`/state.json`(`config.mdns`)・logcat** に表示。**音量Up ダブルタップ**で mDNS名（無ければIP）＋ポートを**音声案内**＋画面表示（1回押しは直前値の再読み上げ）。
- `.local` 解決対応：macOS/iOS=◎, Android=○, **Windows10/11=△（内蔵リゾルバが不安定。下記トラブルシュート参照）**, 一部Linux=△。
- **WifiLock（既定で有効）**：起動時に `WIFI_MODE_FULL_HIGH_PERF`（API27）/`FULL_LOW_LATENCY`（API29+）の WifiLock を確保し、**Wi-Fi 省電力(PSM)中のマルチキャスト取りこぼし＝mDNS応答失敗を抑える**。無線を寝かせない分**電池消費は増える**ため、据置給電運用向け。無効化は `MdnsAdvertiser(holdWifiLock=false)`。取得には `WAKE_LOCK` 権限が必要（本機では必須。無いと `SecurityException`）。
- 無線adbと併用すると `adb connect <name>.local:5555` 等もIP非依存に。THINKLETは `ro.adb.secure=0` で無線adbの認証ダイアログが出ず、ヘッドレス運用と相性が良い。

### トラブルシュート：Windows で `<name>.local` が引けない

実機切り分けの結論：**端末側は mDNS に正しく応答している**（同一PC・同時刻に正規mDNSクライアント＝Python `zeroconf` では `meter.local → 192.168.0.107:8080` を解決成功）。引けない原因は **Windows 内蔵 `.local` リゾルバの弱さ**で、特に**有線PC↔無線端末をまたぐマルチキャスト配送が間欠的**な環境で顕著。

- **症状の正体は「ネガティブキャッシュ」**：正引き成功の保持はむしろ長い（jmDNS の A レコード TTL=3600秒）。問題は、ある時の問い合わせが間に合わないと Windows が**「存在しない」という失敗を記憶して再問い合わせをやめる**こと。端末は応答しているのに Windows だけ `could not be resolved` を返し続ける（＝「一度こけると戻らない」体感の正体）。
- **即復旧**：`ipconfig /flushdns` でネガティブキャッシュを消すと直後から解決可（その後しばらく＝最長TTL～1時間OK）。
- **恒久対策**：Windows に **Apple Bonjour（mDNSResponder）** を導入すると、再問い合わせを適切に行いネガティブキャッシュに陥りにくく、Chrome/Edge から `.local` が安定解決できる。
- **代替**：端末と**同じ Wi-Fi** の スマホ/Mac（正規mDNS実装）からアクセス、または **IP直 `http://<ip>:8080/`（常に確実）**。IPは音量Up連打の読み上げ・画面ヘッダ・`/state.json` で確認可。
- 診断スクリプト（参考, リポジトリ外）：`zeroconf` で `_http._tcp.local.` を browse すれば、端末が応答しているか（＝原因がクライアント側か）を切り分けられる。

## APIキーの保存（セキュリティ）

- **Android Keystore 由来のマスターキーで暗号化**した `EncryptedSharedPreferences`（`shared_prefs/secure_config.xml`）に保存。値もキー名もAESで暗号化され、**端末のKeystoreなしには復号不可**。
- 旧バージョンの平文 `filesDir/config.json` があれば初回起動時に暗号化ストアへ移行し、**平文ファイルは削除**。
- `android:allowBackup="false"`（`adb backup` での吸い出しを遮断）。`/state.json`・logcat に生キーは出さない（末尾4桁マスクのみ）。
- 投入経路（`/config` POST）はLAN平文なので、漏洩を避けたいときは `adb forward` で **localhost 限定**運用にする。

## 仕様 / 注意

- 端末: THINKLET LC01（Android 8.1 / API 27, arm64-v8a, GMSなし, 広角カメラ）
- ツールチェイン: AGP 8.10 / Kotlin 2.1 / compileSdk 36 / minSdk 27 / Gradle 8.13 / JDK 17(JBR)
- 依存: CameraX / **ML Kit barcode-scanning**（端末内バンドル＝GMS非依存, ID読取）/ **androidx.security-crypto**（キー暗号化）/ **jmDNS**（mDNS, 純Java）。ネットは `HttpURLConnection`、JSONは `org.json`＝**追加ネットライブラリなし**。OpenCVは不使用。APKは約16MB（大半はML Kitバーコードモデル）。
- 権限: `CAMERA` / `INTERNET` / `ACCESS_NETWORK_STATE`（接続判定）/ `CHANGE_WIFI_MULTICAST_STATE`（mDNS）/ `WAKE_LOCK`（mDNS応答安定化のWifiLock）/ `READ_EXTERNAL_STORAGE`（debug の任意パス画像読込, maxSdk32）。
- TTS: Fairy **Josee**（`ai.fd.josee.app.tts`, オフライン日英）。未導入時は無音で動作継続（導入は [FairyDevicesRD/droid.josee.tts](https://github.com/FairyDevicesRD/droid.josee.tts) 参照）。
- カメラは ImageAnalysis のみbind（連続フレームをプレビュー＋撮影元に使用）。露出は自動（ブレ抑制の上限露光固定）／設定で手動固定可。
- **アナログ丸ダイヤル（複数指針）は VLM でも誤読しうる**。`confidence`/`notes` に曖昧さが出る。デジタル/積算計（数字表示）が高信頼。
- メータ読取はクラウド（OpenAI）依存で **WiFi/LTE 必須**。未接続時はキューして接続復帰時に自動処理。QR/バーコード認識・TTS・履歴・暗号化保存は端末内で完結。

## ライセンス

[MIT License](LICENSE)。依存ライブラリ（CameraX / ML Kit / AndroidX security-crypto / jmDNS 等）は各々のライセンスに従います。
TTS の **Josee** は本リポジトリに含みません（別途導入）。
