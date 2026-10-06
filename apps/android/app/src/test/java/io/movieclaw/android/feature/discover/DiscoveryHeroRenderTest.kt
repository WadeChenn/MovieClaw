package io.movieclaw.android.feature.discover

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.movieclaw.android.MovieClawApp
import io.movieclaw.android.core.designsystem.*
import io.movieclaw.android.core.model.DiscoveredTitle
import io.movieclaw.android.core.model.DiscoverySection
import io.movieclaw.android.core.network.ShareCookieJar
import io.movieclaw.android.core.network.authInterceptor
import io.movieclaw.android.core.session.TokenVault
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryHeroRenderTest {
    @Test fun `douban row renders a titled hero and fetches its artwork with the real image loader`() {
        val server = MockWebServer().also { it.start() }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val poster = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
            poster.eraseColor(AndroidColor.rgb(204, 0, 85))
            val bytes = ByteArrayOutputStream().also { poster.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
            val application = RuntimeEnvironment.getApplication()
            val origin = server.url("/").toString().trimEnd('/')
            val vault = TokenVault(application).also { it.activate(origin, "fixture", "fixture-token") }
            // Supply the production loader without starting application background services.
            val app = MovieClawApp().also {
                org.robolectric.util.ReflectionHelpers.setField(it, "mBase", application)
                it.imageLoaders = ImageLoaders(application, vault, OkHttpClient.Builder().addInterceptor(authInterceptor(vault)).build(), ShareCookieJar())
            }
            val activity = controller.get()
            val context = object : ContextWrapper(activity) {
                override fun getApplicationContext(): Context = app
            }
            val title = DiscoveredTitle(titleRef = "douban:100", provider = "douban", mediaType = "movie", title = "豆瓣精选影片", posterUrl = server.url("poster.jpg").toString(), releaseYear = 2026, providerRating = 8.1f)
            val selected = discoveryHeroTitles(listOf(DiscoverRow(DiscoverySection(presentation = "ranked-row"), listOf(title))))
            assertEquals(listOf("douban:100"), selected.map { it.titleRef })
            activity.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MovieClawTheme {
                        Box(Modifier.fillMaxSize().background(Bg)) {
                            HeroCarousel(selected.map { it.toHeroSlide(origin, null) }, currentPage = 0, onPageChange = {}, onOpenSlide = {}, onAction = {})
                            McTopBar(variant = McTopBarVariant.Discover, title = "电影", sourceLabel = "豆瓣", onSourceClick = {})
                        }
                    }
                }
            }
            val view = activity.window.decorView
            fun frame() {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 800)
            }
            val output = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
            repeat(100) {
                frame()
                view.draw(Canvas(output))
                Thread.sleep(10)
            }
            File("build/ui-previews").mkdirs()
            File("build/ui-previews/douban-hero.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("Hero artwork was not requested", request)
            assertEquals("Bearer fixture-token", request!!.getHeader("Authorization"))
            assertTrue(request.requestUrl!!.queryParameter("w")!!.toInt() > 0)
            var painted = false
            repeat(80) {
                frame()
                view.draw(Canvas(output))
                val p = output.getPixel(180, 160)
                if (AndroidColor.red(p) > 100 && AndroidColor.green(p) < 10 && AndroidColor.blue(p) > 20) painted = true
                if (!painted) Thread.sleep(10)
            }
            assertTrue("Coil did not decode and draw the hero image", painted)
            File("build/ui-previews").mkdirs()
            File("build/ui-previews/douban-hero.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            app.imageLoaders.loader.shutdown()
            app.imageLoaders.guestLoader.shutdown()
        } finally {
            controller.pause().stop().destroy()
            server.shutdown()
        }
    }
}
