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
package io.karte.android.notifications.unit

import io.karte.android.notifications.internal.BitmapUtil
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class BitmapUtilTest {
    @Test
    fun bigPictureShoudFitToScreenSize() {
        val url: URL = this.javaClass.classLoader!!.getResource("1kx1k.png")
        var bitmap = BitmapUtil.getBigPicture(url.toString())
        //1000x1000pxの画像を470x320に縦横共収まるように調整する。
        //320より小さくなるまで1/2を掛けるので1000 * (1/2) ^ 2 = 250となって、縦横共250pxになる。
        Assert.assertEquals(250, bitmap!!.height)
        Assert.assertEquals(250, bitmap!!.width)
    }
}
