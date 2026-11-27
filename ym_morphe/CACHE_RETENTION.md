# YouTube Music 9.30.53：本体接続の調査

再生セッションの重複排除と記録は JVM の単体テストで確認済み。実機で新規の Stats2 クライアントを作った後、同一オブジェクトの累積視聴時間の更新を32回（ログ上限）観測した。

`experimentalScopedPlayRecording`（現在は既定オン。初期実験時はオフ）は新規ローカル再生候補を Stats2 の累積視聴時間で判定し、アプリ専用のバックアップ対象外領域に再生セッションを記録する機能。保存キーは端末内のランダムキーで HMAC 化する。`adb logcat -s YmCacheRecord:D '*:S'` には結果の固定文字列だけを出す。対象外ケースの実機検証は未完了。音声キャッシュ保持は含まれない。

## 再生回数

`classes4.dex` の `Lbbyg;` は `toString()` と対応フィールドから、動画 ID (`c`)、CPN (`d`)、曲長 (`b`)、視聴時間 (`g`) を同じオブジェクトに持つ。`Lbbyh;` のコンストラクタには、この視聴時間を復元する経路がある。`Lbbyh;->a(ZJ)V` には `finishWatchTimeSegment` と視聴区間の開始時刻との差分を扱う処理がある。

これは視聴時間の計測候補だが、同曲リピートで CPN が毎回変わることや、復元と再送で同じセッションを識別できることは未確認。通常の音楽再生・広告・キャストをどう区別するか、対応するアカウントをどこで得るかも未確認。単発の `onPlaying()` やプレイヤー位置の差分で代用しない。視聴ログの通信、認証情報、送信時刻は変更しない。

## 標準キャッシュ

- `classes2.dex` の `MediaCache` は範囲照会、読み出し、書き込みを公開するが、有効期限を維持した保持指定は見つかっていない。`DeferredMediaCache.flush(MediaPushReceiver)` は受信側へのデータ引き渡しで、ディスク保持を指示できる根拠にはならない。
- `classes2.dex` の `Laueo;->b()Lsfx;` にはキャッシュなし・`Lsgu;` の構築・`Lauge;` を取得する分岐がある。`Laueo;->c(Lbslb;Ljava/io/File;)Lsfx;` は `exo` 領域の `Lsgu;` を構築するが、それだけでは通常の音楽再生が使うキャッシュを特定できない。
- `classes.dex` の `Lsgp;->i(Lsfx;J)V` は容量を確保するとき、`TreeSet.first()` の候補を `Lsfx;->n(Lsgc;)V` に渡して削除する。これは容量による削除経路の一つであり、曲を保持する API ではない。`Lsfx;` に明示的な保持指定は見つかっていない。汎用キャッシュの追い出しを止めたり、期限やライセンス判定を書き換えたりしない。

## 診断用パッチオプション

`ym_morphe` 適用時に `cacheRetentionDiagnostics`（「再生・キャッシュ診断（開発用）」、既定オフ）をオンにすると、`adb logcat -s YmCacheDiag:D '*:S'` でホストの視聴時間コールバックへの到達とキャッシュ供給元の分類を確認できる。コールバック到達は再生資格の成立を示さず、供給元の戻り値はキャッシュヒットを示さない。ID・曲名・URL・音声は出力せず、記録や保持は開始しない。

## arm64 実機と追加調査

改造版 9.30.53（versionCode 93053240）の arm64 実機で `YmCacheDiag: cache supplier returned: unknown` を確認した。初回診断が比較した `Lauge;` はインターフェースで、`Laued;->c(Ljava/io/File;Ljava/io/File;)Lauge;` は実装クラス `Laugy;` のインスタンスを返す。このため `unknown` は `Laugy;` と矛盾しないが、実機がその実装で音声を読んだ証拠ではない。

同じ実機で通常再生が進み、位置 158693 ms で一時停止したことを Android のメディアセッションで確認した。インストール済み APK には視聴時間・キャッシュ両方の診断呼び出しが存在するが、この試行では `watch-time callback` が出ていない。`Lbbyh` への到達を前提に再生回数を記録しない。

診断を更新した再生試験では、同一プロセスで `alternate` の供給と保存済み区間の返却29回を確認した。別プロセスでは `simple` の供給も観測した。視聴時間フックは引き続き0回だった一方、同じ試験で標準の `MedialibPlayerEvents` は `onProgressing`・`onPlaying`・`onPaused`・`onEnded` を出していた。追加の診断は `Lavdj;->q(J)V` と `Lbckg;->bD(Lbcre;JJJJZII)V` の本来の進捗通知を観測する。区間返却は音声データの実読込・通信量削減の証拠ではない。

一時停止・再開・シークの追加試験で `local director progress dispatched` 32回（診断上限）、`media engine progressing` 3回、保存済み区間の返却31回を観測した。`Lbbwy;->n(Lazmp;)V` は条件が成立すると `Lbbxt;->t(Lazmp;)V` に渡し、`Lbbxt;->u:J` が進捗の前進分を累積する。`Lbbxt;->f` は曲ID、`h` は CPN として使われる。Stats2 がこの実機で実際に累積を更新するかは追加診断で確認する。アカウント候補の `Lagdw;->e()` と `Lagcy;->e()` は、再生セッションで選ばれたアカウントと同一か未確認。

`Laugy;->x()V` はロック内で LRU 集合の先頭を選び、`E(String, true)` に渡す。通常音声とキャッシュキーの対応、アカウント切替時の掃除、期限・再生権限の失効経路との分離は追加で検証する。LRU だけに見えることを根拠に保持を有効化しない。

## 共有キャッシュでの実測

arm64 実機では `Laubi.b()` が共有キャッシュを供給した。記録は保存されたが、優先保持はアカウント別キャッシュに限るため、この試行では働いていない。計測版の再生で必要容量は約 98 MB、ホスト上限は 256 MiB、超過は 0 回だった。UID の受信バイト数はアプリ全体の値であり、キャッシュの削減量ではない。検証のためにキャッシュを埋めたり、共有キャッシュの認可・期限判定を変更したりしない。

`b2b63c2` の診断では `Lsga;->a([BII)I` のキャッシュ側データソースが返した実バイトを集計する。arm64 実機で、同一プロセスの15サンプル（約70秒）の間に `cachedDelivered` が 172,669 B から 3,324,961 B へ、UID の `rx` が 344,613,747 B から 345,984,167 B へ増えた。差分はそれぞれ **3,152,292 B** と **1,370,420 B**。これは標準キャッシュ側のデータソースが実際にバイトを返した証拠だが、音声専用の集計ではない。UID の受信はキャッシュと別の範囲を計測しており、値の差を節約量とは解釈できない。優先保持はオフで、保持オン／オフの比較ではない。

さらに同じ実機の通常利用中に、共有キャッシュのホスト上限が 268,435,456 B で、観測した最大の必要量が 190,471,033 B（残り 77,964,423 B）だった。観測範囲では容量超過も容量削除もなかった。`Lbaub.a()` → `Laqdc.A(now)` の標準ストリーム期限判定は確認できたが、共有キャッシュに残るバイトへのアクセス失効と同一ではない。

## 再開と三点の表示

9.30.53 の `Logl.h()` は `START_PAUSED` のローカル復元を要求し、`Logl.f()` は保存済みキューの再開位置を標準のプレイヤーに渡す。診断はこのオフセットと、別の標準ストリーム期限判定の結果だけを観測する。現段階では保存処理、サーバーへのフォールバック、キャッシュミス時の動作は変更しない。

三点ボタンの矢印は、実際の優先保持で別の削除候補を選んだ処理が正常に戻り、同じキャッシュで現在曲の区間がその後も見つかった場合だけ短時間表示する。表示は非操作のオーバーレイで、標準メニューを置き換えない。曲・アカウントが違う場合や診断だけの場合は表示しない。曲全体のオフライン保存を示すものではない。共有キャッシュ（unscoped）の `augy` バックエンド観測は実装済みで、容量削除候補の選択には受け入れたアカウントのポリシーを適用する（当時の時点では容量逼迫下の実機検証は未完了だったが、下の 2026-11-26 節・2026-11-27 節で検証済み）。

## 2026-11-25：通常再生と共有供給の再確認

SM-S942Q、YouTube Music 9.30.53 の別パッケージで、既存アプリを上書きせず、診断オン・記録と優先保持オフで2曲を再生し、直後に同じ曲を再生した。曲頭付近の進捗駆動サンプル間の UID 受信増分は初回がそれぞれ **4,853,860 B / 4,217,690 B**、再生し直した後の約45秒では **0 B / 494 B** だった。同区間でキャッシュ側データソースの返却累積は **4,595,204 B / 7,994,312 B** 増えた。これはこの端末・条件で再生し直したときの観察であり、先読み、既存のキャッシュ状態、測定区間のずれ、UID 内の他の通信を含む。音声専用の通信量、保持機能のオン／オフ比較、一般的な削減量ではない。キャッシュ返却バイトを受信バイトから差し引いて節約量としない。

別の観測専用ビルド（`sharedCacheSupplierProbe=true`、記録・保持・キャッシュ診断はオフ）では、再生中を含め `unscoped supplier: alternate` を観測した。これは unscoped 経路が `augy` を返した証拠であり、複数アカウントで同じオブジェクトを共有した証拠ではない。2026-11-25 時点の優先保持はアカウントに結び付くスコープのみを受け付け、unscoped 供給を結び付けていなかったため、**この試行では優先保持は有効にならなかった**（unscoped 観測への対応は下の 2026-11-26 節で実装）。容量不足時の削除もこの試験では発生しておらず、優先保持されたかどうかの実地比較はできない。キャッシュを埋める、期限・権限を変更する、未確認の共有領域を保持対象にする操作は行っていない。

## 本体接続の受入条件

対象 APK は arm64-v8a のみ。以下は実機で検証する。

1. 通常曲で、同じ再生の一時停止・再開やシークでは回数が増えず、別の正当な再生（同曲リピートを含む）で1回増える。広告・キャスト・別アカウントを混ぜない。まず書き込まない観測で確認する。
2. 通常音声のキャッシュヒット経路と、容量による追い出しと期限・権限による失効の区別を確認する。取得済みデータだけに作用し、失効処理を変えない保持操作が実証できた場合だけ接続する。追加取得・独自コピーは対象外。

## 2026-11-26：バックエンド観測の分離と決定のフェンシング

バックエンド観測をアカウント世代から分離した。`observedBackend` と `backendEpoch` が現在観測中の `augy` を表し、`accountEpoch` と `Policy`（受け入れたアカウントの優先曲）は独立して管理する。初回のバックエンド発見や同一アカウントでの差し替えは `accountEpoch` を進めず、`qualified` を破棄しない。`accountEpoch` が進むのは `invalidate()` と実際のアカウント切替だけ。

共有キャッシュ供給元 `Laubi;->b()Lsfx;` は `Laueo;->b()Lsfx;` の戻り値 `v0` を `NativeCacheRetention.unscopedBackend(Ljava/lang/Object;)` に渡す。この経路は `observedBackend` を unscoped として記録するだけで、`accountEpoch` も `qualified` も変更しない。`Laugy;` 以外の値は観測状態を変更しない。

ロック規約：`POLICY_LOCK` が保持するのは短いメモリ上の読み書きだけで、ホスト呼び出し・リフレクション・ログ出力は行わない。`Fields` の解決はロック外で行う。`choose`・`cachedLookup`・`currentTrackSpared` はロックを取らない（ホストのキャッシュロック保持中に呼ばれ得るため）。決定は二つの世代でフェンスする。`Spared` は `accountEpoch` と `backendEpoch` の両方を保持し、`capacityVictimRemoved()` は両方が一致し、かつ観測中のバックエンドが現在も同一である場合だけバッジを公開する。

ホストの削除呼び出し `augy.E(id, true)` は、フラグ次第ではエントリの一部の区間だけを削除し得る。バッジはこの呼び出しが正常に戻ったことだけを示し、エントリ全体が削除されたことも、ましてや曲全体がオフライン保存されたことも示さない。

### 2026-11-26 追記：コールドスタート復元とクローン実機での容量超過実証

- **コールドスタート復元**: `QualifiedPlayCounter.qualifiedTrackKeys` と `ScopedPlaybackKeys.deriveAccountKey`/`deriveTrackKey` を追加し、アプリ再起動後の初回再生受け入れ時にバックグラウンドワーカーが既存の `install.key`・`qualified.plays`（HMAC 記録のみ、生 ID は保存しない）を読み出して `NativeCacheRetention.hydratePersisted(...)` で優先曲セットを復元する。`choose`・`cachedLookup` のロックフリー・ディスク非接触規約は維持する。
- **検証用クローン限定・低予算オプション**: 実機で容量超過を再現するため、既定 `0`（無効・フック未注入）のオプトイン設定 `experimentalTestCacheBudgetMiB`（1..256 MiB、`Lauea.a`/`Lauec.a` の戻り値を `min(nativeBudget, capMiB)` に制限）を追加した。
- **SM-S942Q（`app.morphe.android.apps.youtube.music.ymverify`、12 MiB テスト上限、元アプリ非変更）での実測**:
  1. 同一プロセス内：`native unscoped cache bound` → 1曲目2回再生で `capacity preference ready` → 他曲連続再生で 12 回以上連続して `overBudget=true` → `capacity preferred entry spared`（各 3.5〜4.6 MB を別曲から削除）。再再生時の UID 受信増分は守られた1曲目が **779 B**、追い出された6曲目が **5,060,213 B（約 5.06 MB）**。
  2. コールドスタート後（`am force-stop` → 新 PID `27152`）：優先曲を一度も再生せず別曲 4 曲（計 +15.72 MB 受信）を再生したところ、初回再生から **29 ms** で `capacity preference restored` が発火し、続く 3 回の容量超過すべてで `capacity preferred entry spared` が動作した。直後に優先曲を再再生すると `preferred document matched native cached lookup` が発火し、`sga.t`（`cachedDelivered`）が **+3,882,147 B（+3.88 MB）** 増加する一方、UID 受信増分は **+231,792 B（約 0.23 MB、メタデータ等のみ）** に留まった。クラッシュ・`capacity preference ineligible` は 0 件。
  3. なお、起動直後の初回再生受け入れより前にキャッシュが既に上限付近でバックグラウンド先読み削除が走った場合は、復元が間に合わない窓が残る（通常の 256 MiB 上限では問題になりにくいが、既知の制約として記録する）。

## 2026-11-27：アプリ内キャッシュ上限設定と推測データ削減量プレビュー

設定画面「ym_morphe プレイヤー設定」に、**音声キャッシュの優先保持と上限設定**を追加した。従来の「キャッシュ保持の基準（準備中）」節を置き換える。

- **優先保持スイッチ**: 既定オン。`cache_retention_enabled`（`CacheRetentionPreferences.RETENTION_ENABLED`）に保存し、オフのとき `NativeCacheRetention.choose` はホストの削除候補を一切変更しない（バックエンド・ポリシーも読まない）。
- **キャッシュ上限**: `cache_budget_mib`（`CacheRetentionPreferences.CACHE_BUDGET_MIB`）。`0` は標準（アプリ任せ）、`16〜2048` MB は `Lauea.a(J)J`・`Lauec.a(J)J` の戻り値を `NativeCacheRetention.resolveCacheBudget(JI)J` で置き換える。プリセット（標準 / 128 / 256 / 512 / 1024 / 2048 MB）と数値入力の両方に対応し、`0` または範囲内のみ保存する。
- **保存**: 優先保持・上限・再生回数・秒数・割合の 5 キーを `CacheRetentionPreferences.saveAll(...)` で 1 回の `edit()...apply()` にまとめて書き込み、同時に `NativeCacheRetention.applyPreferences(...)` でメモリ上へ即時反映する。不正値がある場合は何も書き込まない。
- **推測データ削減量プレビュー**: 純 Java の `CacheSavingsEstimator` が、実効上限・収容曲数・LRU ヒット率・優先保持で救済される再生的再生・実測の再生履歴から月間削減量を試算する。入力のたびに即時再計算し、保存前でも確認できる。
  - 実測テレメトリ（`NativeCacheRetention.captureCacheTelemetry()` と `ScopedPlaybackRecordingBridge.readRecordedPlayCounts(...)`、`getExternalCacheDir()`/`getCacheDir()` の空き容量）は `ym-cache-ui-stats` デーモンスレッドで読み、UI スレッドへ戻してから反映する。UI スレッドはディスク I/O とホストのキャッシュロックに触れない。
  - 空き容量の半分を上限が超える場合と、保護対象曲に対して上限が小さい場合に警告を表示する。表示はすべて推測値であり、実際の通信削減量・オフライン保存を示さない。
- **既定値の変更**: `experimentalScopedPlayRecording`（曲別再生回数の記録）と `experimentalNativeCacheRetention`（優先保持）は既定オンになった。`experimentalTestCacheBudgetMiB` は既定 `0`（無効）のまま。テスト用上限フックは `installCacheBudgetHook(testCapMiB)`（`0..256`）に統合し、`0` でもユーザー上限を適用できる。
- **SM-S942Q（`app.morphe.android.apps.youtube.music.ymverify`、`experimentalTestCacheBudgetMiB="0"`）での実測**:
  - `ym_morphe プレイヤー設定` の描画・ライブプレビュー更新（標準 256 MB 時 `897 MB / 月`、`16 MB` 入力時 `146 MB / 月`＋小容量警告）・端末実測表示（`現在キャッシュ: 10 MB / 上限 256 MB（音声 4曲・10 MB）`）・保存（`16 MB` → `2048MB`）まで `AndroidRuntime:E` 0 件で動作。
  - `16 MB` 保存後、アプリ再起動なし（同一 PID `25714`）で `YmCacheDiag` の `limit` が `268435456`（256 MiB）から `16777216`（16 MiB）へ即時切り替わり、超過時に `overBudget=true` → `capacity preferred entry spared` が 4 回発火した。
  - ※ プレビュー数値は端末の再生履歴とキャッシュ状態に基づく月額目安であり、保証値ではない（実測で容量超過まで発火確認したのは `16 MB` への切替のみ）。
