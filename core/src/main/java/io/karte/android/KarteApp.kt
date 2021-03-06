//
//  Copyright 2020 PLAID, Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
package io.karte.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import io.karte.android.core.config.Config
import io.karte.android.core.library.ActionModule
import io.karte.android.core.library.DeepLinkModule
import io.karte.android.core.library.Library
import io.karte.android.core.library.Module
import io.karte.android.core.library.NotificationModule
import io.karte.android.core.logger.LogLevel
import io.karte.android.core.logger.Logger
import io.karte.android.core.optout.OptOutConfig
import io.karte.android.core.repository.PreferenceRepository
import io.karte.android.core.repository.Repository
import io.karte.android.tracking.AppInfo
import io.karte.android.tracking.AutoEventName
import io.karte.android.tracking.Event
import io.karte.android.tracking.PvId
import io.karte.android.tracking.TrackingService
import io.karte.android.tracking.VisitorId
import io.karte.android.tracking.generateOriginalPvId
import io.karte.android.utilities.ActivityLifecycleCallback
import io.karte.android.utilities.connectivity.ConnectivityObserver
import java.util.ServiceLoader

private const val LOG_TAG = "KarteApp"

/**
 * KARTE SDKのエントリポイントであると共に、SDKの構成および依存ライブラリ等の管理を行うクラスです。
 *
 * SDKを利用するには、[KarteApp.setup]を呼び出し初期化を行う必要があります。
 *
 * 初期化が行われていない状態では、イベントのトラッキングを始め、SDKの機能が利用できません。
 *
 * なおアプリ内メッセージ等のサブモジュールについても同様です。
 *
 *
 * SDKの設定については、初期化時に一部変更することが可能です。
 * 設定を変更して初期化を行う場合は、[Config]を指定して[KarteApp.setup]を呼び出してください。
 */
class KarteApp private constructor() : ActivityLifecycleCallback() {
    lateinit var application: Application private set
    /**
     * [KarteApp.setup] 呼び出し時に指定したアプリケーションキーを返します。
     *
     * 初期化が行われていない場合は空文字列を返します。
     */
    var appKey: String = ""
        private set
    /**
     * [KarteApp.setup] 呼び出し時に指定した設定情報を返します。
     *
     * 初期化が行われていない場合はデフォルトの設定情報を返します。
     */
    var config: Config = Config.build()
        private set
    var appInfo: AppInfo? = null
        private set
    internal var connectivityObserver: ConnectivityObserver? = null
    internal var tracker: TrackingService? = null
    private var visitorId: VisitorId? = null
    private var optOutConfig: OptOutConfig? = null

    internal val libraries: MutableList<Library> = mutableListOf()
    internal val modules: MutableList<Module> = mutableListOf()
    private val isUnsupportedOsVersion: Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
    internal val isInitialized get() = this.appKey.isNotEmpty()
    val originalPvId = generateOriginalPvId()
    internal val pvIdContainer: PvId = PvId(originalPvId)
    val pvId get() = pvIdContainer.value

    fun register(module: Module) {
        Logger.i(LOG_TAG, "Register module: ${module.javaClass.name}(${module.name})")
        if (self.modules.none { it == module }) {
            self.modules.add(module)
        }
    }

    fun unregister(module: Module) {
        Logger.i(LOG_TAG, "Unregister module: ${module.name}")
        self.modules.removeAll { it == module }
    }

    fun repository(namespace: String = ""): Repository {
        return PreferenceRepository(application, appKey, namespace)
    }

    internal fun teardown() {
        firstActivityCreated = false
        activityCount = 0
        libraries.forEach { library ->
            library.unconfigure(self)
        }
        libraries.clear()
        tracker?.teardown()

        appKey = ""
        config = Config.build()
        appInfo = null
        connectivityObserver = null
        tracker = null
        visitorId = null
        optOutConfig = null
    }

    fun optOutTemporarily() {
        OptOutConfig.optOutTemporarily()
    }

    //region ActivityLifecycleCallback
    private var firstActivityCreated = false
    private var activityCount = 0
    private var presentActivityHash: Int? = null

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Logger.v(LOG_TAG, "onActivityCreated $activity")
        if (!firstActivityCreated) {
            self.appInfo?.trackAppLifecycle()
            self.tracker?.track(Event(AutoEventName.NativeAppOpen, values = null))
            firstActivityCreated = true
        }
        self.modules.filterIsInstance<DeepLinkModule>()
            .forEach { it.handle(activity.intent) }
    }

    override fun onActivityStarted(activity: Activity) {
        Logger.v(LOG_TAG, "onActivityStarted $activity")
        if (++activityCount == 1) {
            self.tracker?.track(Event(AutoEventName.NativeAppForeground, values = null))
        }
        self.modules.filterIsInstance<DeepLinkModule>()
            .forEach { it.handle(activity.intent) }
    }

    override fun onActivityResumed(activity: Activity) {
        val isNextActivity = presentActivityHash != activity.hashCode()
        Logger.v(LOG_TAG, "onActivityResumed $activity isNext:$isNextActivity")
        if (isNextActivity) {
            self.pvIdContainer.renew()
        }
        presentActivityHash = activity.hashCode()
    }

    override fun onActivityPaused(activity: Activity) {
        Logger.v(LOG_TAG, "onActivityPaused $activity")
        self.pvIdContainer.set(self.originalPvId)
    }

    override fun onActivityStopped(activity: Activity) {
        Logger.v(LOG_TAG, "onActivityStopped $activity")
        if (--activityCount == 0) {
            self.tracker?.track(Event(AutoEventName.NativeAppBackground, values = null))
        }
    }
    //endregion

    companion object {
        internal val self = KarteApp()
        /**
         * SDKの初期化を行います。
         *
         * 初期化オプションが未指定の場合は、デフォルト設定で初期化が行われます。
         * 初期化オプションのデフォルト値については `Configuration` クラスを参照してください。
         *
         * なお初期化後に初期化オプションを変更した場合、その変更はSDKには反映されません。
         *
         * また既に初期化されている状態で呼び出した場合は何もしません。
         *
         * @param[context] [Context]
         * @param[appKey] アプリケーションキー
         * @param[config] 設定
         */
        @JvmStatic
        @JvmOverloads
        fun setup(
            context: Context,
            appKey: String,
            config: Config? = null
        ) {
            if (self.appKey.isNotEmpty()) {
                Logger.w(LOG_TAG, "APP_KEY is already exists.")
                return
            }
            if (appKey.isEmpty()) {
                Logger.w(LOG_TAG, "Invalid APP_KEY is set.")
                return
            }
            if (self.isUnsupportedOsVersion) {
                Logger.i(LOG_TAG, "Initializing was canceled because os version is under 5.0.")
                return
            }
            if (config?.isDryRun == true) {
                Logger.w(
                    LOG_TAG,
                    "======================================================================"
                )
                Logger.w(LOG_TAG, "Running mode is dry run.")
                Logger.w(
                    LOG_TAG,
                    "======================================================================\n"
                )
                return
            }
            self.application = if (context.applicationContext is Application) {
                context.applicationContext as Application
            } else {
                Logger.i(LOG_TAG, "Application context is not an Application instance.")
                return
            }
            self.application.registerActivityLifecycleCallbacks(self)
            self.connectivityObserver = ConnectivityObserver(self.application)

            Logger.i(LOG_TAG, "KARTE SDK initialize. appKey=$appKey")
            self.appKey = appKey
            config?.let { self.config = it.copy() }
            val repository = self.repository()
            self.appInfo = AppInfo(context, repository, self.config)
            self.visitorId = VisitorId(repository)
            self.optOutConfig = OptOutConfig(self.config, repository)
            self.tracker = TrackingService()

            Logger.v(LOG_TAG, "load libraries")
            val libraries = ServiceLoader.load(Library::class.java, javaClass.classLoader)
            Logger.v(LOG_TAG, "loaded libraries: ${libraries.count()}. start configure.")
            libraries.forEach { library ->
                register(library)
                library.configure(self)
            }
            self.appInfo?.updateModuleInfo()
        }

        /**
         * ログレベルを設定します。
         *
         * なおデフォルトのログレベルは [LogLevel.WARN] です。
         *
         * @param[level] ログレベル
         */
        @JvmStatic
        fun setLogLevel(level: LogLevel) {
            Logger.level = level
        }

        @JvmStatic
        fun register(library: Library) {
            Logger.i(
                LOG_TAG,
                "Register library: ${library.name}, ${library.version}, ${library.isPublic}"
            )
            if (self.libraries.none { it.name == library.name }) {
                self.libraries.add(library)
            }
        }

        @JvmStatic
        fun unregister(library: Library) {
            Logger.i(LOG_TAG, "Unregister library: ${library.name}")
            self.libraries.removeAll { it.name == library.name }
        }

        /**
         * オプトアウトの設定有無を返します。
         *
         * オプトアウトされている場合は `true` を返し、されていない場合は `false` を返します。
         * また初期化が行われていない場合は `false` を返します。
         */
        @JvmStatic
        val isOptOut: Boolean
            get() = self.optOutConfig?.isOptOut ?: false

        /**
         * オプトインします。
         *
         * なお初期化が行われていない状態で呼び出した場合はオプトインは行われません。
         */
        @JvmStatic
        fun optIn() {
            self.optOutConfig?.optIn()
        }

        /**
         * オプトアウトします。
         *
         * なお初期化が行われていない状態で呼び出した場合はオプトアウトは行われません。
         */
        @JvmStatic
        fun optOut() {
            if (self.optOutConfig == null || isOptOut) {
                return
            }

            self.modules.forEach { if (it is ActionModule) it.resetAll() }
            self.modules.forEach { if (it is NotificationModule) it.unsubscribe() }

            self.optOutConfig?.optOut()
        }

        /**
         * ユーザーを識別するためのID（ビジターID）を返します。
         *
         * 初期化が行われていない場合は空文字列を返します。
         */
        @JvmStatic
        val visitorId: String
            get() = self.visitorId?.value ?: ""

        /**
         * ビジターIDを再生成します。
         *
         * ビジターIDの再生成は、現在のユーザーとは異なるユーザーとして計測したい場合などに行います。
         * 例えば、アプリケーションでログアウトした際などがこれに該当します。
         *
         * なお初期化が行われていない状態で呼び出した場合は再生成は行われません。
         */
        @JvmStatic
        fun renewVisitorId() {
            self.visitorId?.renew()
        }
    }
}
