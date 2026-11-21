# ym_morphe：プレイヤーデザイン変更パッチ

## 成果物
- **パッチバンドル**: `output/ym_morphe.mpp`（未署名）
- ビルド元: `ym_morphe/`
- 公式 GmsCore support との併用検証 APK: `output/ym_morphe-combined-test-unsigned.apk`（未署名・インストール不可）
- パッチ適用結果: `output/ym_morphe-combined-report.json`

バンドル内のパッチは `ym_morphe` の 1 件のみ。公式 Morphe Patches のパッチ、公式の拡張（music / youtube / shared など）は含まない。必要なプレイヤー用コードだけを `extensions/ym.mpe` として同梱している。

## 対象・方針
- 保存済み YouTube Music 9.30.53（arm64-v8a）専用。
- 参考画像の赤枠・矢印は表示しない。
- ジャケットに控えめな角丸。全画面から縮小中、ミニプレイヤーまで、背景はテーマ色とぼかしたジャケット画像の混合。
- パッチはデフォルト無効。

## 音声・動画の切り替えボタンを非表示
YouTube Music の設定から「ym_morphe プレイヤー設定」を開き、「音声・動画の切り替えボタンを非表示」をオンにしてアプリを再起動してください。初期設定はオフです。表示を戻すにはオフにして再起動してください。プレイヤー上部のボタンだけを隠し、再生モードは変更しません。

9.30.53 へのパッチ適用と、切り替えボタンを取得する全3か所のフックを検証済みです。Android 13 の x86_64 エミュレーターでは、同じ設定画面と非表示処理を使う検証用アプリで設定保存・非表示・再表示を確認しました（画像は `output/screenshots/`）。YouTube Music 本体は arm64-v8a 専用のため、このエミュレーターにはインストールできませんでした。

## 再生優先（1.6.0）
「ym_morphe プレイヤー設定」に初期オフの実験的なスイッチを追加。準備・バッファリング・シーク中に別曲の新規先読みを最長 15 秒控え、再生開始後は最大 3 秒で解除します。画像やログの制御ではありません。詳細・副作用・検証範囲は [NETWORK_PRIORITY.md](NETWORK_PRIORITY.md) を参照してください。実機での動作・改善量は未確認です。

## キャッシュ保持の基準（準備中）
「ym_morphe プレイヤー設定」で、保持に必要な再生回数（1〜999回）、1回と数えるための秒数（1〜3600秒）、曲の長さに対する割合（1〜100%）を変更できます。初期値は2回・30秒・50%です。判定上は、設定した秒数と曲の長さに設定割合を掛けた時間のうち短い方を使います。曲の長さが不明なら秒数だけを使います。

現在のソースでは設定の保存と判定ロジックまで実装しています。再生回数の記録や実際の音声キャッシュ保持はまだ行いません。標準キャッシュの権限と有効期限を維持したまま保持できる経路は未確認です。

## 実装
- `ym_morphe/patches/src/main/kotlin/app/morphe/patches/music/layout/classicplayer/ClassicPlayerPatch.kt`
  - ジャケット角丸 8dp。CardView の elevation は元の 0dp のまま。1.3.0 の 8dp はコントロールや暗幕を背面に隠す可能性があるため削除。
  - 曲情報の水平余白 24dp、曲名 22sp 太字、シークバー余白
  - 上部メニューを横三点アイコンに変更
  - `player_page` 直下で、元の `player_page_background` の直後、scrim や操作部の手前に非操作の背景 View を追加。ホストが非表示化・型参照する元の背景は変更しない。
  - 1.3.0 の `ryc.B()` への補間変更は他の画面状態にも影響し得るため削除し、元の計算を維持。
- `ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym/ArtworkGradientView.java`
  - `player_thumbnail` の BitmapDrawable や代表的な drawable ラッパーから画像を取得し、32×32 に縮小してぼかし、テーマ色と混合。主画像が取得できないときはミニプレイヤーの pager 内で最も大きく見える画像も試す。0% はテーマ色、100% はぼかした画像。
  - 表示中は 500ms ごとに再確認し、ウィンドウ・View の表示復帰時にも即座に再試行。短い読み込み失敗では前の小さなぼかしを最大 5 秒保持。小さなぼかしをメモリ上に最大 60 秒キャッシュし、画面再作成時に一時表示できるようにする。
  - 次の画像が取得できたら 300ms でクロスフェード。元の大きな Bitmap は弱参照のみ保持。取得不能が続いた場合はテーマ色に戻る。
  - 各フレームの可視領域の変化を検出して center-crop を更新し、縮小・展開の途中でも表示領域にぼかしを合わせる。背景 View はタッチ・フォーカス・アクセシビリティの対象にしない。
  - View の拡大率・移動量を直接変更しない。ハードウェア Bitmap は 400 万画素以下なら一時コピー、超える場合はフォールバック。

- `ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym/GradientSettingsActivity.java`
  - YouTube Music の設定に「ym_morphe プレイヤー設定」を追加。背景のジャケット画像の割合を 0〜100% で調整。
  - Android 14 以降・パッケージ名変更後でも起動できるよう、設定画面への Intent を実行時に明示的なコンポーネントに変換する。設定画面の表示・変更の反映は実機未検証。

## ビルド
```bash
cd ym_morphe
export JAVA_HOME="$PWD/../tools/jdk21" ANDROID_HOME="$PWD/../tools/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :patches:buildAndroid --no-daemon -Pgpr.user=unused -Pgpr.key=unused
```
`unused` は GitHub Packages の認証を回避するためのダミー値。`morphe-patcher` v2.3.0 と `morphe-patches-library` v2.1.1 は `../` のローカルソースを composite build として使用する。

## 確認済み
- ビルド成功、パッチ一覧は `ym_morphe` の 1 件のみ。
- バンドルには `extensions/ym.mpe` のみを含み、他アプリ・他パッチの拡張を含まない。
- 対象 APK への適用成功。公式 GmsCore support と同時適用し、CardView の elevation、元のプレイヤー子要素の順序・配置属性、および新規背景の挿入位置を検証。実際の Android での表示確認ではない。
- `tests/check_layout_compat.py`、`tests/check_ym_morphe.py` でリソース・バンドル構成を検査。`tests/java/.../ArtworkBlurTest.java` と `ArtworkCropTest.java` でぼかしおよび拡大縮小時の計算を JVM 上で検査。

## 未確認・制約
- 実機/エミュレーター未接続のため、小さく残る症状が解消したか、元々のキュー表示時の重なり、設定画面の起動・設定保存、見た目、曲変更時のグラデーション追従、操作、横画面・小画面は未確認。
- 対応していない Drawable、巨大な hardware Bitmap、画像取得失敗が続く場合は、猶予後にテーマ色になる。復帰中は前のジャケット画像が一時的に残る可能性がある。
- 公式 GmsCore support との同時パッチ適用は検証したが、実機での併用動作とその他の公式パッチとの併用は未確認。パッチバンドルは未署名。
- `morphe-patches/` は参考にした公式ソースで、成果物には含まれない。

## 次の作業
1. 実機で全画面・縮小中・ミニプレイヤーに背景が表示されるか、文字とボタンの可読性、角丸、前後関係、スリープやアプリ切替復帰、曲変更時の更新を確認する。
2. 参考画像との差（上部の再生元表示、お気に入り、下部の接続先・キュー等）を埋める。動的な位置計算の調査が必要。
3. 再生・メニュー・お気に入り・キューの操作と、動画/広告/横画面での影響を確認する。
