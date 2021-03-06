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
package io.karte.android.inappmessaging.unit

import android.net.Uri
import android.net.http.SslError
import android.view.KeyEvent
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import com.google.common.truth.Truth.assertThat
import io.karte.android.application
import io.karte.android.inappmessaging.InAppMessaging
import io.karte.android.inappmessaging.internal.IAMWebView
import io.karte.android.inappmessaging.internal.MessageModel
import io.karte.android.inappmessaging.internal.ParentView
import io.karte.android.inappmessaging.internal.javascript.State
import io.karte.android.shadow.CustomShadowWebView
import io.karte.android.shadow.customShadowOf
import io.karte.android.tracking.Tracker
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

@Suppress("NonAsciiCharacters")
@RunWith(RobolectricTestRunner::class)
@Config(
    packageName = "io.karte.android.tracker",
    sdk = [24],
    shadows = [CustomShadowWebView::class]
)
class IAMWebViewTest {
    private fun makeStateReady() {
        webView.onReceivedMessage(
            "state_changed",
            JSONObject().put("state", "initialized").toString()
        )
    }

    val dummy_url = "https://dummy_url/test"
    val overlay_url = "https://api.karte.io/v0/native/overlay"
    val empty_data = "<html></html>"

    private lateinit var webView: IAMWebView
    private lateinit var shadowWebView: ShadowWebView
    @MockK
    private lateinit var adapter: MessageModel.MessageAdapter
    @MockK
    private lateinit var parent: ParentView

    @Before
    fun init() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        createKarteAppMock()
        webView = IAMWebView(application(), InAppMessaging.Config.enabledWebViewCache) { true }
        webView.adapter = adapter
        webView.parentView = parent
        shadowWebView = Shadows.shadowOf(webView)
    }

    @After
    fun tearDown() {
        customShadowOf(webView).resetLoadedUrls()
    }

    @Test
    fun EventCallbackでtrackが呼ばれること() {
        mockkStatic(Tracker::class)
        val eventNameSlot = slot<String>()
        val jsonSlot = slot<JSONObject>()
        every { Tracker.track(capture(eventNameSlot), capture(jsonSlot)) } just Runs

        val values = JSONObject().put("samplekey", "samplevalue")

        webView.onReceivedMessage(
            "event",
            JSONObject().put("event_name", "some_event").put("values", values).toString()
        )
        verify(exactly = 1) { Tracker.track(any(), any<JSONObject>()) }
        assertThat(eventNameSlot.captured).isEqualTo("some_event")
        assertThat(jsonSlot.captured.toString()).isEqualTo(values.toString())
    }

    @Test
    fun trackerJsの読み込み後にはすぐに読み込むこと() {
        // 中身の確認に一度読み込む
        every { adapter.dequeue() } returns null
        makeStateReady()
        verify(exactly = 1) { adapter.dequeue() }
        clearMocks(adapter)

        every { adapter.dequeue() } returnsMany listOf("test", "test", null)
        webView.notifyChanged()
        verify(exactly = 3) { adapter.dequeue() }
        val loadedUrls = customShadowOf(webView).loadedUrls
        assertThat(loadedUrls.size).isEqualTo(2)
        for (uri in loadedUrls) {
            assertThat(uri).isEqualTo("javascript:window.tracker.handleResponseData('test');")
        }
    }

    @Test
    fun StateChange_initializedでadapterからデータ取得されること() {
        every { adapter.dequeue() } returnsMany listOf("test", "test", null)

        // readyでなければdequeueしない。
        webView.notifyChanged()
        verify(inverse = true) { adapter.dequeue() }

        makeStateReady()
        Assert.assertEquals(webView.state, State.READY)
        verify(exactly = 3) { adapter.dequeue() }
        val loadedUrls = customShadowOf(webView).loadedUrls
        assertThat(loadedUrls.size).isEqualTo(2)
        for (uri in loadedUrls) {
            assertThat(uri).isEqualTo("javascript:window.tracker.handleResponseData('test');")
        }
    }

    @Test
    fun StateChange_errorでstateがDESTROYEDに変更されること() {
        webView.onReceivedMessage(
            "state_changed", JSONObject()
                .put("state", "error")
                .put("message", "samplemessage")
                .toString()
        )

        Assert.assertEquals(webView.state, State.DESTROYED)
    }

    @Test
    fun startActivityOnOpenUrlCallback() {
        webView.onReceivedMessage(
            "open_url",
            JSONObject().put("url", "http://sampleurl").toString()
        )

        verify(exactly = 1) { parent.openUrl(Uri.parse("http://sampleurl")) }
    }

    @Test
    fun startActivityOnOpenUrlCallbackWithQueryParameters() {
        webView.onReceivedMessage(
            "open_url",
            JSONObject().put("url", "http://sampleurl?hoge=fuga&hogehoge=fugafuga").toString()
        )

        verify(exactly = 1) { parent.openUrl(Uri.parse("http://sampleurl?hoge=fuga&hogehoge=fugafuga")) }
    }

    @Test
    fun DocumentChangedでupdateTouchableRegionsが呼ばれること() {
        webView.onReceivedMessage(
            "document_changed", JSONObject()
                .put(
                    "touchable_regions", JSONArray().put(
                        JSONObject()
                            .put("top", 10.0)
                            .put("bottom", 10.0)
                            .put("left", 10.0)
                            .put("right", 10.0)
                    )
                ).toString()
        )

        verify(exactly = 1) { parent.updateTouchableRegions(ofType()) }
    }

    @Test
    fun Visibility_visibleでshowが呼ばれること() {
        webView.onReceivedMessage("visibility", JSONObject().put("state", "visible").toString())
        verify(exactly = 1) { parent.show() }
    }

    @Test
    fun Visibility_invisibleでdismissが呼ばれること() {
        webView.onReceivedMessage("visibility", JSONObject().put("state", "invisible").toString())
        verify(exactly = 1) { parent.dismiss() }
    }

    @Test
    fun backボタンの処理を行うこと() {
        val backKey = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)

        webView.loadUrl(dummy_url)
        assertThat(webView.canGoBack()).isFalse()
        assertThat(webView.dispatchKeyEvent(backKey)).isFalse()

        webView.loadUrl(dummy_url)
        assertThat(webView.canGoBack()).isTrue()
        assertThat(webView.dispatchKeyEvent(backKey)).isTrue()
        assertThat(webView.canGoBack()).isFalse()
    }

    @Test
    fun onReceivedSslErrorでoverlayのロードに失敗するとparentがhandleする() {
        val error = mockk<SslError>()
        val handler = mockk<SslErrorHandler>(relaxUnitFun = true)

        // urlが異なる時はempty_dataをloadしない
        every { error.url } returns dummy_url
        shadowWebView.webViewClient.onReceivedSslError(webView, handler, error)
        assertThat(shadowWebView.lastLoadData).isNull()
        verify(inverse = true) { parent.errorOccurred() }

        // urlが同じ時はempty_dataをload
        webView.loadUrl(dummy_url)
        every { error.url } returns dummy_url
        shadowWebView.webViewClient.onReceivedSslError(webView, handler, error)
        assertThat(shadowWebView.lastLoadData.data).isEqualTo(empty_data)
        verify(inverse = true) { parent.errorOccurred() }

        // urlがoverlayの時は親に伝える
        every { error.url } returns overlay_url
        shadowWebView.webViewClient.onReceivedSslError(webView, handler, error)
        verify(exactly = 1) { parent.errorOccurred() }
    }

    @Test
    fun onReceivedHttpErrorでoverlayのロードに失敗するとparentがhandleする() {
        val webResourceRequest = mockk<WebResourceRequest>()

        // urlが異なる時はempty_dataをloadしない
        every { webResourceRequest.url } returns Uri.parse(dummy_url)
        shadowWebView.webViewClient.onReceivedHttpError(webView, webResourceRequest, null)
        assertThat(shadowWebView.lastLoadData).isNull()
        verify(inverse = true) { parent.errorOccurred() }

        // urlが同じ時はempty_dataをload
        webView.loadUrl(dummy_url)
        every { webResourceRequest.url } returns Uri.parse(dummy_url)
        shadowWebView.webViewClient.onReceivedHttpError(webView, webResourceRequest, null)
        assertThat(shadowWebView.lastLoadData.data).isEqualTo(empty_data)
        verify(inverse = true) { parent.errorOccurred() }

        // urlがoverlayの時は親に伝える
        every { webResourceRequest.url } returns Uri.parse(overlay_url)
        shadowWebView.webViewClient.onReceivedHttpError(webView, webResourceRequest, null)
        verify(exactly = 1) { parent.errorOccurred() }
    }

    @Test
    fun onReceivedErrorでoverlayのロードに失敗するとparentがhandleする() {
        val description = "dumy error reason"
        val request = mockk<WebResourceRequest>(relaxed = true)
        val error = mockk<WebResourceError>()
        every { error.description } returns description
        every { request.url } returns Uri.parse(dummy_url)
        // urlが異なる時はempty_dataをloadしない
        shadowWebView.webViewClient.onReceivedError(webView, request, error)
        assertThat(shadowWebView.lastLoadData).isNull()
        verify(inverse = true) { parent.errorOccurred() }

        // urlが同じ時はempty_dataをload
        webView.loadUrl(dummy_url)
        shadowWebView.webViewClient.onReceivedError(webView, request, error)
        assertThat(shadowWebView.lastLoadData.data).isEqualTo(empty_data)
        verify(inverse = true) { parent.errorOccurred() }

        // urlがoverlayの時は親に伝える
        every { request.url } returns Uri.parse(overlay_url)
        shadowWebView.webViewClient.onReceivedError(webView, request, error)
        verify(exactly = 1) { parent.errorOccurred() }
    }

    @Test
    fun 古いonReceivedErrorでoverlayのロードに失敗するとparentがhandleする() {
        val errorCode = 0
        val description = "dummy description"
        // urlが異なる時はempty_dataをloadしない
        shadowWebView.webViewClient.onReceivedError(webView, errorCode, description, dummy_url)
        assertThat(shadowWebView.lastLoadData).isNull()
        verify(inverse = true) { parent.errorOccurred() }

        // urlが同じ時はempty_dataをload
        webView.loadUrl(dummy_url)
        shadowWebView.webViewClient.onReceivedError(webView, errorCode, description, dummy_url)
        assertThat(shadowWebView.lastLoadData.data).isEqualTo(empty_data)
        verify(inverse = true) { parent.errorOccurred() }

        // urlがoverlayの時は親に伝える
        shadowWebView.webViewClient.onReceivedError(webView, errorCode, description, overlay_url)
        verify(exactly = 1) { parent.errorOccurred() }
    }

    @Test
    fun 非表示時にdestroyされること_cache無効() {
        val webView = IAMWebView(application(), false) { false }
        webView.resetOrDestroy()
        val loadedUrls = customShadowOf(webView).loadedUrls
        assertThat(loadedUrls.size).isEqualTo(0)
        assertThat(Shadows.shadowOf(webView).wasDestroyCalled()).isTrue()
    }

    @Test
    fun 非表示時にresetされること_cache有効() {
        val webView = IAMWebView(application(), true) { false }
        webView.resetOrDestroy()
        val loadedUrls = customShadowOf(webView).loadedUrls
        assertThat(loadedUrls.size).isEqualTo(1)
        assertThat(loadedUrls.last()).isEqualTo("javascript:window.tracker.resetPageState();")
    }
}
