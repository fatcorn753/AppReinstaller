package com.fatcorn753.reinstaller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.fatcorn753.reinstaller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指定したアプリをアンインストールしてから、選んだ APK をインストールする。
 *
 * Android では通常アプリがサイレントに他アプリを削除／導入することはできないため、
 * 各ステップでシステムの確認ダイアログが表示される。このアプリはその一連の流れを
 * 自動で連鎖させ、途中経過をログに出す。
 *
 * 一度使った対象はランチャーの長押しメニュー (App Shortcuts) に並び、
 * ホーム画面へのピン留めもできる。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 現在選択されている APK の保存先。null なら未選択。 */
    private var stagedApk: File? = null

    /** アンインストール対象／インストールされる APK のパッケージ名。 */
    private var targetPackage: String? = null

    /** 実行中は各ボタンを無効化するためのフラグ。 */
    private var running = false

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** 登録リストのメモリキャッシュ。文字入力のたびに SharedPreferences を読まないため。 */
    private var registeredCache: List<String>? = null

    /** アプリ一覧のキャッシュ。ダイアログを開くたびに数百件を舐め直さないため。 */
    private var appListCache: List<AppPickerAdapter.AppEntry>? = null

    /** APK の保管ディレクトリ。キャッシュだと消える可能性があるので filesDir に置く。 */
    private val apkDir by lazy { File(filesDir, "apks").apply { mkdirs() } }

    /** 複製した APK の一時置き場。 */
    private val clonesDir by lazy { File(cacheDir, "clones").apply { mkdirs() } }

    // ---------------------------------------------------------------- ランチャー

    private val pickApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                log("APK の選択がキャンセルされました")
            } else {
                stageApk(uri)
            }
        }

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onUninstallFinished(result.resultCode)
        }

    private val installConfirmLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 実際の結果は PackageInstaller の PendingIntent (installStatusReceiver) に届く。
            // ここではユーザーが確認画面を閉じたことだけが分かる。
        }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canInstallPackages()) {
                log("「不明なアプリのインストール」が許可されました")
            } else {
                log("「不明なアプリのインストール」が許可されていません")
            }
        }

    // ---------------------------------------------------- インストール結果レシーバ

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_INSTALL_STATUS) return
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intentExtra(intent)
                    if (confirm == null) {
                        finishFlow("インストール確認画面を開けませんでした", success = false)
                    } else {
                        log("インストールの確認画面を表示します")
                        installConfirmLauncher.launch(confirm)
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    finishFlow("インストールが完了しました", success = true)
                }

                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    finishFlow(
                        "インストールに失敗しました (${statusName(status)}${
                            if (message.isNullOrBlank()) "" else ": $message"
                        })",
                        success = false
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ ライフサイクル

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ContextCompat.registerReceiver(
            this,
            installStatusReceiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.btnPickApk.setOnClickListener {
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }
        binding.btnPickInstalled.setOnClickListener { showInstalledAppPicker() }
        binding.btnRun.setOnClickListener { startFlow(auto = false) }
        binding.btnLaunchApp.setOnClickListener { launchTargetApp() }
        binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }
        binding.btnPinShortcut.setOnClickListener { requestPinShortcut() }
        binding.btnRegisterShortcut.setOnClickListener { toggleRegistration() }
        binding.btnClone.setOnClickListener { startCloneFlow() }
        binding.btnManageShortcuts.setOnClickListener { showShortcutManager() }
        // パッケージ名を手入力したときも登録ボタンの状態を合わせる。
        binding.etPackage.doAfterTextChanged { if (!running) updateUi() }

        log("準備完了。APK を選んでから「実行」を押してください。")
        if (!canInstallPackages()) {
            log("※ このアプリにはまだ「不明なアプリのインストール」権限がありません。実行時に設定画面へ案内します。")
        }

        refreshDynamicShortcuts()
        updateUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(installStatusReceiver)
        super.onDestroy()
    }

    /** 長押しメニュー／ピン留めショートカットから起動されたときの処理。 */
    private fun handleIntent(intent: Intent) {
        if (intent.action != ACTION_REINSTALL) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        // 同じ Intent で二度起動しないように消費済みにする。
        intent.action = Intent.ACTION_MAIN
        if (pkg.isNullOrBlank()) {
            // 「APK を選んで実行」ショートカット。
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
            return
        }

        val apk = apkFileFor(pkg)
        if (!apk.exists()) {
            log("$pkg の APK が保存されていません。もう一度 APK を選択してください。")
            removeSavedTarget(pkg)
            return
        }
        adoptStagedApk(apk, pkg, savedLabel(pkg), savedVersion(pkg))
        log("ショートカットから起動: $pkg")
        startFlow(auto = true)
    }

    // ------------------------------------------------------------------ APK の取り込み

    /** 選択された APK を保存し、パッケージ名を読み取る。 */
    private fun stageApk(uri: Uri) {
        setRunning(true)
        log("APK を読み込んでいます…")
        lifecycleScope.launch {
            // apkDir 内に置いてから rename する。同一ファイルシステムなのでコピーが1回で済む。
            val temp = File(apkDir, "incoming.tmp")
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "APK を開けませんでした" }
                        temp.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    @Suppress("DEPRECATION")
                    val archive = packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                        ?: throw IllegalArgumentException("APK として解析できませんでした")
                    val label = archive.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = temp.absolutePath
                        appInfo.publicSourceDir = temp.absolutePath
                        runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrNull()
                    }
                    // パッケージ名ごとに保管し、ショートカットから再利用できるようにする。
                    val dest = apkFileFor(archive.packageName)
                    dest.delete()
                    if (!temp.renameTo(dest)) {
                        temp.copyTo(dest, overwrite = true)
                        temp.delete()
                    }
                    ApkInfo(
                        packageName = archive.packageName,
                        versionName = archive.versionName ?: "?",
                        label = label,
                        file = dest
                    )
                }
            }

            info.onSuccess { apk ->
                adoptStagedApk(apk.file, apk.packageName, apk.label, apk.versionName)
                saveApkMeta(apk.packageName, apk.label, apk.versionName)
                refreshDynamicShortcuts()
                log("APK を読み込みました: ${apk.packageName} (v${apk.versionName})")
                val installed = installedVersion(apk.packageName)
                if (installed == null) {
                    log("この端末には未インストールです。アンインストールは省略されます。")
                } else {
                    log("現在インストールされているバージョン: $installed")
                }
                if (registeredTargets().contains(apk.packageName)) {
                    log("この対象は長押しメニューに登録済みです。")
                } else {
                    log("「長押しメニューに追加」を押すと、アイコン長押しから直接実行できます。")
                }
            }.onFailure { e ->
                temp.delete()
                stagedApk = null
                binding.tvApkInfo.text = getString(R.string.apk_not_selected)
                log("APK の読み込みに失敗しました: ${e.message}")
            }
            setRunning(false)
        }
    }

    /** 画面と内部状態に APK を反映する。 */
    private fun adoptStagedApk(file: File, pkg: String, label: String?, version: String?) {
        stagedApk = file
        targetPackage = pkg
        binding.etPackage.setText(pkg)
        binding.tvApkInfo.text = getString(
            R.string.apk_info,
            label ?: "(名称不明)",
            pkg,
            version ?: "?",
            file.length() / 1024
        )
        updateUi()
    }

    // ------------------------------------------------------------------ 実行フロー

    /** @param auto ショートカット起動など、ユーザーの明示的なタップ以外からの実行か。 */
    private fun startFlow(auto: Boolean) {
        if (running) return

        val apk = stagedApk
        if (apk == null || !apk.exists()) {
            log("先に APK ファイルを選択してください")
            return
        }

        val pkg = binding.etPackage.text?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) {
            log("パッケージ名が空です")
            return
        }
        if (pkg == packageName) {
            log("このアプリ自身は対象にできません")
            return
        }
        targetPackage = pkg

        if (!canInstallPackages()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_message)
                .setPositiveButton(R.string.perm_open_settings) { _, _ -> openUnknownSourcesSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (auto) log("自動実行を開始します")
        setRunning(true)
        binding.btnLaunchApp.isEnabled = false

        if (installedVersion(pkg) == null) {
            log("── $pkg は未インストールのため、アンインストールを省略します")
            beginInstall()
        } else {
            log("── アンインストールを開始します: $pkg")
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            runCatching { uninstallLauncher.launch(intent) }.onFailure { e ->
                finishFlow("アンインストール画面を開けませんでした: ${e.message}", success = false)
            }
        }
    }

    private fun onUninstallFinished(resultCode: Int) {
        val pkg = targetPackage ?: return
        lifecycleScope.launch {
            // パッケージ削除の反映に一瞬かかることがあるので少しだけ待って確認する。
            var stillInstalled = installedVersion(pkg) != null
            var waited = 0
            while (stillInstalled && waited < UNINSTALL_CHECK_TIMEOUT_MS) {
                delay(UNINSTALL_CHECK_INTERVAL_MS)
                waited += UNINSTALL_CHECK_INTERVAL_MS.toInt()
                stillInstalled = installedVersion(pkg) != null
            }

            if (stillInstalled) {
                val reason = if (resultCode == RESULT_CANCELED) {
                    "ユーザーがキャンセルしました"
                } else {
                    "システムが削除を完了しませんでした (resultCode=$resultCode)"
                }
                finishFlow("アンインストールできませんでした: $reason", success = false)
            } else {
                log("アンインストールが完了しました")
                beginInstall()
            }
        }
    }

    private fun beginInstall() {
        log("── インストールを開始します")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { commitInstallSession() } }
            result.onFailure { e ->
                finishFlow("インストールセッションの作成に失敗しました: ${e.message}", success = false)
            }
            // 成功時の結果は installStatusReceiver に届く。
        }
    }

    /** APK を PackageInstaller のセッションに書き込んで commit する。 */
    private fun commitInstallSession() {
        val apk = stagedApk ?: throw IllegalStateException("APK が選択されていません")
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        targetPackage?.let { params.setAppPackageName(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val callback = PendingIntent.getBroadcast(
                this,
                sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(packageName),
                flags
            )
            session.commit(callback.intentSender)
        }
    }

    private fun finishFlow(message: String, success: Boolean) {
        log(if (success) "✔ $message" else "✖ $message")
        setRunning(false)
        binding.btnLaunchApp.isEnabled = success && targetPackage?.let { installedVersion(it) } != null
        if (success) {
            targetPackage?.let { pkg ->
                log("インストール後のバージョン: ${installedVersion(pkg) ?: "?"}")
            }
        }
    }

    // ------------------------------------------------------------------ ショートカット

    /**
     * 長押しメニューに出る動的ショートカットを、ユーザーが登録した対象だけで作り直す。
     * 並び順は登録リストの順序をそのまま rank にする（先頭がメニューの上）。
     */
    private fun refreshDynamicShortcuts() {
        val registered = registeredTargets()
        val labels = registered.associateWith { savedLabel(it) ?: it }
        // ファイル存在チェックと ShortcutManager 呼び出しはどちらも I/O なので UI スレッドから外す。
        lifecycleScope.launch(Dispatchers.IO) {
            val shortcuts = registered
                .filter { apkFileFor(it).exists() }
                .mapIndexed { index, pkg ->
                    val label = labels[pkg] ?: pkg
                    ShortcutInfoCompat.Builder(this@MainActivity, shortcutId(pkg))
                        .setShortLabel(getString(R.string.shortcut_short, label.take(SHORT_LABEL_MAX)))
                        .setLongLabel(getString(R.string.shortcut_long, label))
                        .setIcon(IconCompat.createWithResource(this@MainActivity, R.drawable.ic_shortcut_reinstall))
                        .setIntent(reinstallIntent(pkg))
                        .setRank(index)
                        .build()
                }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(this@MainActivity, shortcuts) }
                .onFailure { e ->
                    withContext(Dispatchers.Main) { log("ショートカットを更新できませんでした: ${e.message}") }
                }
        }
    }

    /**
     * 長押しメニューに出せる動的ショートカットの上限。
     * システム上限から静的ショートカット1件分を引いた値を使うが、多くのランチャーは
     * 4〜5件しか表示しないため、実際に見える範囲に丸めておく。
     */
    private fun dynamicShortcutLimit(): Int {
        val system = ShortcutManagerCompat.getMaxShortcutCountPerActivity(this) - 1
        return system.coerceIn(1, LAUNCHER_VISIBLE_SHORTCUTS)
    }

    /** 現在の対象を長押しメニューに登録／解除する。 */
    private fun toggleRegistration() {
        val pkg = binding.etPackage.text?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) {
            log("先に対象パッケージを指定してください")
            return
        }
        if (!apkFileFor(pkg).exists()) {
            log("$pkg 用の APK が保存されていません。先に APK を選択してください。")
            return
        }

        val current = registeredTargets()
        if (current.contains(pkg)) {
            setRegisteredTargets(current - pkg)
            log("長押しメニューから外しました: $pkg")
        } else {
            val limit = dynamicShortcutLimit()
            if (current.size >= limit) {
                log("長押しメニューに登録できるのは $limit 件までです。「メニューを管理」で入れ替えてください。")
                return
            }
            setRegisteredTargets(current + pkg)
            log("長押しメニューに追加しました: ${savedLabel(pkg) ?: pkg}")
        }
        refreshDynamicShortcuts()
        updateUi()
    }

    /**
     * 保存済みの APK 一覧をチェックボックスで見せて、長押しメニューの登録を編集する。
     * チェックした順が、そのままメニューの並び順になる。
     */
    private fun showShortcutManager() {
        val known = knownTargets()
        if (known.isEmpty()) {
            log("APK がまだ1つも保存されていません")
            return
        }

        val registered = registeredTargets()
        // 登録済みを上に、その中は登録順で並べる。
        val ordered = registered.filter { known.contains(it) } + known.filterNot { registered.contains(it) }
        val labels = ordered.map { "${savedLabel(it) ?: it}\n$it" }.toTypedArray()
        val checked = ordered.map { registered.contains(it) }.toBooleanArray()
        // ダイアログ内でチェックされた順序を保つ（メニューの並び順になる）。
        val selection = registered.filter { known.contains(it) }.toMutableList()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.manage_shortcuts_title, dynamicShortcutLimit()))
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                val pkg = ordered[which]
                if (isChecked) {
                    if (selection.size >= dynamicShortcutLimit()) {
                        // 上限超過はその場で戻す。
                        (dialog as AlertDialog).listView.setItemChecked(which, false)
                        log("登録できるのは ${dynamicShortcutLimit()} 件までです")
                    } else {
                        selection.add(pkg)
                    }
                } else {
                    selection.remove(pkg)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setRegisteredTargets(selection)
                refreshDynamicShortcuts()
                updateUi()
                log(
                    if (selection.isEmpty()) "長押しメニューの登録をすべて解除しました"
                    else "長押しメニュー: ${selection.joinToString(" / ") { savedLabel(it) ?: it }}"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.manage_shortcuts_delete_apk) { _, _ -> showApkDeleteDialog(ordered) }
            .show()
    }

    /** 保存済み APK の削除。登録も同時に外す。 */
    private fun showApkDeleteDialog(targets: List<String>) {
        val labels = targets.map { "${savedLabel(it) ?: it}\n$it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_apk_title)
            .setItems(labels) { _, which ->
                val pkg = targets[which]
                apkFileFor(pkg).delete()
                removeSavedTarget(pkg)
                if (targetPackage == pkg) {
                    stagedApk = null
                    binding.tvApkInfo.text = getString(R.string.apk_not_selected)
                }
                updateUi()
                log("保存済み APK を削除しました: $pkg")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 現在の対象をホーム画面にピン留めする（対象アプリごとの独立アイコン）。 */
    private fun requestPinShortcut() {
        val pkg = binding.etPackage.text?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) {
            log("先に対象パッケージを指定してください")
            return
        }
        if (!apkFileFor(pkg).exists()) {
            log("$pkg 用の APK が保存されていません。先に APK を選択してください。")
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            log("このランチャーはピン留めショートカットに対応していません")
            return
        }

        val label = savedLabel(pkg) ?: pkg
        val shortcut = ShortcutInfoCompat.Builder(this, pinnedShortcutId(pkg))
            .setShortLabel(getString(R.string.shortcut_short, label.take(SHORT_LABEL_MAX)))
            .setLongLabel(getString(R.string.shortcut_long, label))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_reinstall))
            .setIntent(reinstallIntent(pkg))
            .build()

        val ok = runCatching {
            ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        }.getOrDefault(false)
        log(
            if (ok) "ホーム画面への追加をランチャーに依頼しました"
            else "ホーム画面への追加に失敗しました"
        )
    }

    /** ショートカットから叩かれる Intent。MainActivity が受けて即実行する。 */
    private fun reinstallIntent(pkg: String): Intent =
        Intent(this, MainActivity::class.java)
            .setAction(ACTION_REINSTALL)
            .putExtra(EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    // ------------------------------------------------------------------ 保存データ

    private fun apkFileFor(pkg: String) = File(apkDir, "$pkg.apk")

    private fun shortcutId(pkg: String) = "reinstall:$pkg"

    private fun pinnedShortcutId(pkg: String) = "pinned:$pkg"

    private fun savedLabel(pkg: String): String? = prefs.getString("label:$pkg", null)

    private fun savedVersion(pkg: String): String? = prefs.getString("version:$pkg", null)

    /**
     * 長押しメニューに出すよう明示的に登録されたパッケージ名（この順に並ぶ）。
     * 入力のたびに読むのでメモリにキャッシュしておく。
     */
    private fun registeredTargets(): List<String> {
        registeredCache?.let { return it }
        val value = prefs.getString(KEY_REGISTERED, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
        registeredCache = value
        return value
    }

    private fun setRegisteredTargets(targets: List<String>) {
        registeredCache = targets
        prefs.edit().putString(KEY_REGISTERED, targets.joinToString("\n")).apply()
    }

    /** APK を保存済みのすべてのパッケージ名（登録の有無に関わらず）。 */
    private fun knownTargets(): List<String> =
        apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.map { it.name.removeSuffix(".apk") }
            ?.sortedBy { (savedLabel(it) ?: it).lowercase(Locale.getDefault()) }
            .orEmpty()

    /** APK のメタ情報だけを保存する。登録リストは変更しない。 */
    private fun saveApkMeta(pkg: String, label: String?, version: String?) {
        prefs.edit()
            .putString("label:$pkg", label ?: pkg)
            .putString("version:$pkg", version ?: "?")
            .apply()
    }

    private fun removeSavedTarget(pkg: String) {
        setRegisteredTargets(registeredTargets() - pkg)
        prefs.edit()
            .remove("label:$pkg")
            .remove("version:$pkg")
            .apply()
        refreshDynamicShortcuts()
    }

    // ------------------------------------------------------------------ 複製

    /** 複製元アプリを選ばせ、設定ダイアログへ進む。 */
    private fun startCloneFlow() {
        pickApp(R.string.clone_pick_source) { entry ->
            val applicationInfo = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(entry.packageName, 0)
            }.getOrNull()

            if (applicationInfo == null) {
                log("${entry.packageName} の情報を取得できませんでした")
                return@pickApp
            }
            if (!applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                log("✖ ${entry.label} は分割 APK (Play 由来) のため複製できません。単一 APK のアプリのみ対応しています。")
                return@pickApp
            }
            val sourceApk = File(applicationInfo.sourceDir)
            if (!sourceApk.canRead()) {
                log("✖ ${entry.label} の APK を読み取れません")
                return@pickApp
            }
            showCloneDialog(entry.label, entry.packageName, sourceApk)
        }
    }

    /** パッケージ名の3つ目のセグメントだけを編集させるダイアログ。 */
    private fun showCloneDialog(label: String, originalPackage: String, sourceApk: File) {
        val view = layoutInflater.inflate(R.layout.dialog_clone, null)
        val checkBox = view.findViewById<CheckBox>(R.id.cbChangePackage)
        val segmentRow = view.findViewById<View>(R.id.segmentRow)
        val prefixView = view.findViewById<TextView>(R.id.tvPrefix)
        val suffixView = view.findViewById<TextView>(R.id.tvSuffix)
        val segmentEdit = view.findViewById<EditText>(R.id.etSegment)
        val previewView = view.findViewById<TextView>(R.id.tvClonePreview)

        val parts = originalPackage.split(".")
        // 「3つ目」が存在しないパッケージ名では最後のセグメントを編集対象にする。
        val editableIndex = if (parts.size >= 3) 2 else parts.lastIndex
        val prefix = parts.take(editableIndex).joinToString("") { "$it." }
        val suffix = parts.drop(editableIndex + 1).joinToString("") { ".$it" }

        view.findViewById<TextView>(R.id.tvCloneSource).text =
            getString(R.string.clone_source, label, originalPackage)
        prefixView.text = prefix
        suffixView.text = suffix
        // 元のアドレスをそのまま初期値にする。
        segmentEdit.setText(parts[editableIndex])
        segmentEdit.setSelection(segmentEdit.text.length)

        fun composed(): String = prefix + segmentEdit.text.toString().trim() + suffix

        fun refreshPreview() {
            val enabled = checkBox.isChecked
            segmentRow.alpha = if (enabled) 1f else 0.4f
            segmentEdit.isEnabled = enabled
            previewView.text = getString(
                R.string.clone_preview,
                if (enabled) composed() else originalPackage
            )
        }

        checkBox.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        segmentEdit.doAfterTextChanged { refreshPreview() }
        refreshPreview()

        AlertDialog.Builder(this)
            .setTitle(R.string.clone_title)
            .setView(view)
            .setPositiveButton(R.string.clone_run) { _, _ ->
                val newPackage = if (checkBox.isChecked) composed() else null
                if (newPackage != null && !isValidPackageName(newPackage)) {
                    log("✖ パッケージ名が不正です: $newPackage")
                    return@setPositiveButton
                }
                if (newPackage == originalPackage) {
                    log("パッケージ名が元と同じです。再署名のみ行います。")
                }
                runClone(label, sourceApk, newPackage ?: originalPackage, newPackage)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runClone(label: String, sourceApk: File, resultPackage: String, newPackage: String?) {
        setRunning(true)
        log("── 複製を開始します: $label")
        if (installedVersion(resultPackage) != null) {
            log("※ $resultPackage は既にインストール済みです。実行するとアンインストールしてから入れ直します。")
        }

        lifecycleScope.launch {
            val dest = File(clonesDir, "$resultPackage.apk")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApkCloner(this@MainActivity).clone(sourceApk, newPackage, dest) { message ->
                        lifecycleScope.launch(Dispatchers.Main) { log(message) }
                    }
                }
            }

            result.onSuccess { cloned ->
                cloned.changes.take(MAX_LOGGED_CHANGES).forEach { log("  $it") }
                if (cloned.changes.size > MAX_LOGGED_CHANGES) {
                    log("  … ほか ${cloned.changes.size - MAX_LOGGED_CHANGES} 件")
                }
                // 複製した APK をそのまま実行対象にする。
                val stored = apkFileFor(cloned.packageName)
                stored.delete()
                withContext(Dispatchers.IO) {
                    if (!cloned.file.renameTo(stored)) cloned.file.copyTo(stored, overwrite = true)
                }
                val cloneLabel = "$label (複製)"
                saveApkMeta(cloned.packageName, cloneLabel, null)
                adoptStagedApk(stored, cloned.packageName, cloneLabel, null)
                log("✔ 複製が完了しました: ${cloned.packageName} (${stored.length() / 1024} KB)")
                log("「③ 実行」を押すとインストールできます。")
            }.onFailure { e ->
                dest.delete()
                log("✖ 複製に失敗しました: ${e.message}")
            }
            setRunning(false)
        }
    }

    private fun isValidPackageName(value: String): Boolean {
        val segments = value.split(".")
        if (segments.size < 2) return false
        return segments.all { it.matches(PACKAGE_SEGMENT) }
    }

    // ------------------------------------------------------------------ 補助

    private fun launchTargetApp() {
        val pkg = targetPackage ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            log("$pkg の起動用アクティビティが見つかりません")
        } else {
            startActivity(intent)
        }
    }

    /** アンインストール対象を選ぶ。 */
    private fun showInstalledAppPicker() {
        pickApp(R.string.pick_installed_title) { entry ->
            binding.etPackage.setText(entry.packageName)
            targetPackage = entry.packageName
            saveApkMeta(entry.packageName, entry.label, savedVersion(entry.packageName))
            updateUi()
            log("対象を ${entry.label} (${entry.packageName}) に設定しました")
        }
    }

    /** インストール済みアプリをアイコン付きの一覧から選ばせる共通ダイアログ。 */
    private fun pickApp(titleRes: Int, onPick: (AppPickerAdapter.AppEntry) -> Unit) {
        setRunning(true)
        lifecycleScope.launch {
            val apps = appListCache ?: withContext(Dispatchers.IO) { loadLaunchableApps() }
            appListCache = apps
            setRunning(false)

            if (apps.isEmpty()) {
                log("アプリ一覧を取得できませんでした")
                return@launch
            }

            val adapter = AppPickerAdapter(this@MainActivity, apps)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(titleRes)
                .setAdapter(adapter) { _, which -> onPick(apps[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { adapter.shutdown() }
                .show()
        }
    }

    /**
     * ランチャーに出るアプリだけを列挙する。
     * getInstalledApplications() は数百件返ってきて重いうえ、ユーザーが認識できない
     * システムコンポーネントばかり混ざるため、こちらを使う。
     */
    private fun loadLaunchableApps(): List<AppPickerAdapter.AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map {
                AppPickerAdapter.AppEntry(
                    packageName = it.packageName,
                    label = runCatching { packageManager.getApplicationLabel(it).toString() }
                        .getOrDefault(it.packageName),
                    info = it
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun installedVersion(pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    }.getOrNull()

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        runCatching { unknownSourcesLauncher.launch(intent) }.onFailure {
            unknownSourcesLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
    }

    private fun setRunning(value: Boolean) {
        running = value
        updateUi()
    }

    private fun updateUi() {
        val pkg = binding.etPackage.text?.toString()?.trim().orEmpty()
        val hasApk = pkg.isNotEmpty() && apkFileFor(pkg).exists()
        binding.progress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRun.isEnabled = !running
        binding.btnPickApk.isEnabled = !running
        binding.btnPickInstalled.isEnabled = !running
        binding.btnPinShortcut.isEnabled = !running && hasApk
        binding.btnRegisterShortcut.isEnabled = !running && hasApk
        binding.btnRegisterShortcut.setText(
            if (registeredTargets().contains(pkg)) R.string.unregister_shortcut
            else R.string.register_shortcut
        )
        binding.btnManageShortcuts.isEnabled = !running
        binding.etPackage.isEnabled = !running
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvLog.append("[$stamp] $message\n")
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    @Suppress("DEPRECATION")
    private fun intentExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED (キャンセル)"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT (署名違い等)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID (APK が不正)"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE (容量不足)"
        else -> "status=$status"
    }

    private data class ApkInfo(
        val packageName: String,
        val versionName: String,
        val label: String?,
        val file: File
    )

    private companion object {
        const val ACTION_INSTALL_STATUS = "com.fatcorn753.reinstaller.INSTALL_STATUS"
        const val ACTION_REINSTALL = "com.fatcorn753.reinstaller.action.REINSTALL"
        const val EXTRA_PACKAGE = "com.fatcorn753.reinstaller.extra.PACKAGE"
        const val APK_ENTRY_NAME = "package.apk"
        const val PREFS = "reinstaller"
        const val KEY_REGISTERED = "registered"
        const val LAUNCHER_VISIBLE_SHORTCUTS = 4
        const val MAX_LOGGED_CHANGES = 12
        val PACKAGE_SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")
        const val SHORT_LABEL_MAX = 10
        const val UNINSTALL_CHECK_INTERVAL_MS = 250L
        const val UNINSTALL_CHECK_TIMEOUT_MS = 3000
    }
}
