package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Запасной путь обновлялки: APK-адрес строится по тегу из редиректа `latest/download/update.json`, а не по
 * `latest/download/<файл>` (тот даёт 404, как только выходит релиз новее — поле, Fold 0.58 → 0.62).
 */
class UpdateTagTest {

    @Test
    fun `tag is taken from github redirect of latest download`() {
        val chain = listOf(
            "https://github.com/pico-soft/X-Gates/releases/latest/download/update.json",
            "https://github.com/pico-soft/X-Gates/releases/download/v0.62/update.json",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1335017878/f5d7?sp=r&sv=2018",
        )
        assertEquals("v0.62", UpdateChecker.tagFromRedirects(chain))
    }

    @Test
    fun `no tag without redirect chain`() {
        assertNull(UpdateChecker.tagFromRedirects(emptyList()))
        assertNull(UpdateChecker.tagFromRedirects(listOf("https://github.com/pico-soft/X-Gates/releases/latest/download/update.json")))
    }

    @Test
    fun `tag download url points at that release`() {
        val repo = UpdateChecker.Repo("pico-soft", "X-Gates")
        assertEquals(
            "https://github.com/pico-soft/X-Gates/releases/download/v0.62/XrayProxyDroid-0.62-arm64-v8a.apk",
            "${repo.tagDownload("v0.62")}/XrayProxyDroid-0.62-arm64-v8a.apk",
        )
    }
}
