# ytm_player_stylisher / ym_morphe

YouTube Music **9.30.53** 向けの独立した Morphe パッチです。バンドルに公開されるパッチは `ym_morphe` の 1 件だけです。

- ジャケットの角丸、曲情報・シークバーの余白、メニューアイコンを調整
- 全画面・縮小中・ミニプレイヤーにジャケット由来のぼかし背景を追加
- アプリ内設定でプレイヤー背景を調整（0%：テーマ色、100%：ぼかしたジャケット）。明るいジャケットでも白い文字を読みやすく補正
- 「ジャケットの色をアプリ全体に反映」をオンにすると、背景・バー・メニューも再生中のジャケットに連動（初期オフ、再起動不要）。プレイヤーとは異なる明るさを使います。
- 「前回の再生を少ない通信で再開する（実験的）」：条件を満たす一時停止でのローカル復元後に、追加のキュー取得だけを省きます（パッチ設定、初期オフ）。
- 画面復帰時の再取得と曲変更時のクロスフェード
- [再生優先（実験的・初期オフ）](ym_morphe/NETWORK_PRIORITY.md)：再生準備・バッファ不足時に別曲の新規先読みを控える

**実験的な実装です。ビルドと APK への適用は検証していますが、実機の表示・操作・端末ごとの互換性は未検証です。**

## パッチをダウンロード（GitHub Actions）

1. [Actions → Patch artifact](https://github.com/haya9924/ytm_player_stylisher/actions/workflows/patch-artifact.yml) で `main` の最新の成功した実行を開きます。
2. 実行結果の **Artifacts** から `ym_morphe-<バージョン>-<コミット>` をダウンロードします（GitHub へのログインが必要）。
3. ZIP を展開し、中の `patches-<バージョン>.mpp` を Morphe のパッチバンドルとして使います。

`main` への push または手動実行でビルドし、Artifact は **30日間**保持します。配布するのは未署名の `.mpp` だけです。YouTube Music の APK は含まず、CI では APK への適用や実機再生は検証しません。

## ソースとビルド

ソースは [`ym_morphe/`](ym_morphe/)、実装の詳細と制約は [README](ym_morphe/README.md) を参照してください。

JDK 21 と Android SDK（API 36 / 対応する Build Tools）が必要です。Gradle の Java toolchain は JDK 17 を使用します。

```sh
cd ym_morphe
# JAVA_HOME / ANDROID_HOME は自分の環境のパスを設定する
./gradlew :patches:buildAndroid --no-daemon
```

出力は `ym_morphe/patches/build/libs/patches-1.6.1.mpp` です。APK 自体は同梱していません。対応する正規の APK を別途用意してください。

### 依存関係

GitHub Packages を利用する場合、必要な認証情報は環境変数 `GITHUB_ACTOR` / `GITHUB_TOKEN`、または **リポジトリ外の** `~/.gradle/gradle.properties` に設定してください。トークンをプロジェクトのファイルやコマンド例に書き込まないでください。

この開発環境では、リポジトリ直下に次の依存ソースを別途配置し、`settings.gradle.kts` の optional composite build を使用しています。依存ソースそのものは公開対象に含めません。

| 配置先 | リポジトリ | 検証した commit |
| --- | --- | --- |
| `morphe-patcher/` | https://github.com/MorpheApp/morphe-patcher | `d19545e8505e0329cc640723cb8ff91eddd3cd05` |
| `morphe-patches-library/` | https://github.com/MorpheApp/morphe-patches-library | `11f8c7e4d9dd64e18b0594d2c7cbf09176a65327` |

依存ソースとその Gradle プラグインがローカルで解決できる構成では、認証用プロパティに実際の秘密を渡さず、次のダミー値でビルドを検証しました。

```sh
./gradlew :patches:buildAndroid --no-daemon -Pgpr.user=unused -Pgpr.key=unused
```

## テスト

ぼかし・縮小領域の計算は Android 端末なしで検査できます。

```sh
mkdir -p /tmp/ym-player-tests
javac -d /tmp/ym-player-tests \
  ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym/Artwork{Blur,Crop}.java \
  tests/java/app/morphe/extension/ym/Artwork{Blur,Crop}Test.java
java -cp /tmp/ym-player-tests app.morphe.extension.ym.ArtworkBlurTest
java -cp /tmp/ym-player-tests app.morphe.extension.ym.ArtworkCropTest
```

- `tests/check_ym_morphe.py`：ビルドしたバンドルとパッチ適用済み APK を検査
- `tests/check_layout_compat.py ORIGINAL_DECODED PATCHED_DECODED`：apktool で展開した元 APK と適用後 APK の背景位置・描画順のリソースを比較

これらは実機での見た目やタッチ操作を保証するテストではありません。

## 公開対象・秘密情報

`.gitignore` は `/home/ai/pi-pocket/box_markdown` の Gradle / Android 用方針を参考に、より厳しくしています。

APK、解析したアプリのコード、クラッシュログ、SDK・ツール、依存チェックアウト、ビルド成果物、キャッシュ、署名鍵、環境ファイル、認証情報はコミットしません。Gradle Wrapper JAR だけは再現性のため追跡します。`.gitignore` だけに頼らず、公開前に差分と秘密情報のスキャンを確認してください。

## ライセンス

初期リポジトリの [MIT LICENSE](LICENSE) は維持しています。Morphe ベースのパッチプロジェクトには [`ym_morphe/LICENSE`](ym_morphe/LICENSE)（GPLv3）と [`ym_morphe/NOTICE`](ym_morphe/NOTICE) が適用されます。Gradle Wrapper などの第三者コンポーネントには各提供元のライセンスが適用されます。
