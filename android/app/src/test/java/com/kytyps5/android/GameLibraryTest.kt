package com.kytyps5.android

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameLibraryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kytyps5-games").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun titlePrefersDefaultLanguageLocalizedTitle() {
        val json = JSONObject(
            """{"titleName":"Top","localizedParameters":{"defaultLanguage":"ja","ja":{"titleName":"JP"},"en":{"titleName":"EN"}}}""",
        )
        assertEquals("JP", GameLibrary.titleFromParamJson(json, "folder"))
    }

    @Test
    fun titleFallsBackToEnglishThenOtherLocalesThenTopLevel() {
        val english = JSONObject("""{"localizedParameters":{"defaultLanguage":"xx","en":{"titleName":"EN"}}}""")
        assertEquals("EN", GameLibrary.titleFromParamJson(english, "folder"))

        val other = JSONObject("""{"localizedParameters":{"defaultLanguage":"xx","fr":{"titleName":"FR"}}}""")
        assertEquals("FR", GameLibrary.titleFromParamJson(other, "folder"))

        val topLevel = JSONObject("""{"titleName":"  Top  "}""")
        assertEquals("Top", GameLibrary.titleFromParamJson(topLevel, "folder"))
    }

    @Test
    fun titleFallsBackToFolderNameWhenNoTitleIsPresent() {
        assertEquals("folder", GameLibrary.titleFromParamJson(JSONObject("{}"), "folder"))
        assertEquals("folder", GameLibrary.titleFromParamJson(JSONObject("""{"titleName":"   "}"""), "folder"))
    }

    @Test
    fun scanReturnsOnlyFoldersWithParamJsonSortedByTitle() {
        makeGame("CUSA00001", """{"titleName":"Zeta"}""")
        makeGame("CUSA00002", """{"localizedParameters":{"defaultLanguage":"en","en":{"titleName":"alpha"}}}""")
        makeGame("CUSA00003", null) // sce_sys/ without param.json is not a game
        File(root, "not-a-game").mkdirs()
        File(root, "loose-file.txt").writeText("x")

        val result = GameLibrary.scan(root) as ScanResult.Found

        assertEquals(listOf("alpha", "Zeta"), result.games.map { it.title })
        assertEquals(File(root, "CUSA00002").absolutePath, result.games.first().path)
    }

    @Test
    fun scanListsZArchiveDumpsAlongsideFoldersByFileName() {
        makeGame("CUSA00001", """{"titleName":"Middle"}""")
        File(root, "Beta Game.zar").writeText("zar")
        File(root, "ALPHA.ZAR").writeText("zar")
        File(root, "notes.zip").writeText("x")

        val result = GameLibrary.scan(root) as ScanResult.Found

        assertEquals(listOf("ALPHA", "Beta Game", "Middle"), result.games.map { it.title })
        assertEquals(File(root, "Beta Game.zar").absolutePath, result.games[1].path)
    }

    @Test
    fun scanListsExfatImagesAlongsideZArchivesByFileName() {
        makeGame("CUSA00001", """{"titleName":"Middle"}""")
        File(root, "Beta Game.exfat").writeText("exfat")
        File(root, "ALPHA.EXFAT").writeText("exfat")
        File(root, "Gamma.zar").writeText("zar")
        File(root, "raw-disk.img").writeBytes(validRawExfatImage())
        File(root, "partitioned-disk.img").writeBytes(wrapInMbrExfatImage(validRawExfatImage()))
        File(root, "not-a-disk.img").writeText("not an exFAT image")

        val result = GameLibrary.scan(root) as ScanResult.Found

        assertEquals(listOf("ALPHA", "Beta Game", "Gamma", "Middle", "partitioned-disk", "raw-disk"), result.games.map { it.title })
        assertEquals(File(root, "Beta Game.exfat").absolutePath, result.games[1].path)
        assertEquals("exFAT disk image", GameLibrary.imageLabel(File("raw-disk.img")))
    }

    @Test
    fun imageLabelNamesEachImageFormat() {
        assertEquals("ZArchive", GameLibrary.imageLabel(File("a.zar")))
        assertEquals("exFAT image", GameLibrary.imageLabel(File("a.EXFAT")))
        assertEquals("exFAT disk image", GameLibrary.imageLabel(File("a.img")))
    }

    @Test
    fun scanFindsNestedDumpsAndTreatsAGameRootAsOneGame() {
        val outer = File(root, "PPSA21567 ASTRO BOT (01.019.000)")
        File(outer, "PPSA21567 ASTRO BOT (01.019.000)/sce_sys").mkdirs()
        File(outer, "PPSA21567 ASTRO BOT (01.019.000)/sce_sys/param.json").writeText("""{"titleName":"ASTRO BOT"}""")
        File(outer, "PPSA21567 ASTRO BOT (01.019.000)/sce_module").mkdirs() // not descended into

        val fromParent = GameLibrary.scan(root) as ScanResult.Found
        assertEquals(listOf("ASTRO BOT"), fromParent.games.map { it.title })

        val gameDir = File(outer, "PPSA21567 ASTRO BOT (01.019.000)")
        val fromGame = GameLibrary.scan(gameDir) as ScanResult.Found
        assertEquals(gameDir.absolutePath, fromGame.games.single().path)
    }

    @Test
    fun scanStopsAtMaxDepth() {
        val deep = File(root, "a/b/c/d/game")
        File(deep, "sce_sys").mkdirs()
        File(deep, "sce_sys/param.json").writeText("""{"titleName":"Too Deep"}""")
        assertTrue((GameLibrary.scan(root) as ScanResult.Found).games.isEmpty())
    }

    @Test
    fun scanReportsMissingAndNonDirectoryRoots() {
        val missing = GameLibrary.scan(File(root, "nope"))
        assertTrue(missing is ScanResult.Failure)

        val file = File(root, "file.txt").apply { writeText("x") }
        assertTrue(GameLibrary.scan(file) is ScanResult.Failure)
    }

    @Test
    fun malformedParamJsonFallsBackToFolderName() {
        makeGame("BROKEN", "{not json")
        val result = GameLibrary.scan(root) as ScanResult.Found
        assertEquals("BROKEN", result.games.single().title)
    }

    @Test
    fun readEntryReadsTitleIdAndIcon() {
        makeGame("PPSA21567", """{"titleId":"PPSA21567","titleName":"ASTRO BOT"}""")
        val icon = File(root, "PPSA21567/" + GameLibrary.ICON).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val game = (GameLibrary.scan(root) as ScanResult.Found).games.single()
        assertEquals("PPSA21567", game.titleId)
        assertEquals(icon, game.icon)
    }

    @Test
    fun paramSfoSuppliesTitleAndIdWhenParamJsonLacksThem() {
        makeGame("CUSA1", "{}")
        File(root, "CUSA1/" + GameLibrary.PARAM_SFO)
            .writeBytes(com.kytyps5.android.library.SfoFixture.build("TITLE" to "From SFO", "TITLE_ID" to "CUSA00001"))
        val game = (GameLibrary.scan(root) as ScanResult.Found).games.single()
        assertEquals("From SFO", game.title)
        assertEquals("CUSA00001", game.titleId)
        assertEquals(null, game.icon)
    }

    private fun validRawExfatImage(): ByteArray {
        val image = ByteArray(512 * 100)
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(image, destinationOffset = 3)
        fun put32(offset: Int, value: Int) {
            for (i in 0 until 4) image[offset + i] = (value ushr (8 * i)).toByte()
        }
        fun put64(offset: Int, value: Long) {
            for (i in 0 until 8) image[offset + i] = (value ushr (8 * i)).toByte()
        }
        put64(72, 100L)
        put32(80, 24)
        put32(84, 1)
        put32(88, 25)
        put32(92, 1)
        put32(96, 2)
        image[108] = 9
        image[109] = 0
        image[110] = 1
        image[510] = 0x55
        image[511] = 0xAA.toByte()
        return image
    }

    private fun wrapInMbrExfatImage(volume: ByteArray): ByteArray {
        val firstPartitionLba = 2048
        val disk = ByteArray(firstPartitionLba * 512 + volume.size)
        disk[446 + 4] = 0x07
        val lba = firstPartitionLba
        for (i in 0 until 4) disk[446 + 8 + i] = (lba ushr (8 * i)).toByte()
        disk[510] = 0x55
        disk[511] = 0xAA.toByte()
        volume.copyInto(disk, destinationOffset = firstPartitionLba * 512)
        return disk
    }

    private fun makeGame(folderName: String, paramJson: String?) {
        val folder = File(root, folderName)
        File(folder, "sce_sys").mkdirs()
        paramJson?.let { File(folder, GameLibrary.PARAM_JSON).writeText(it) }
    }
}
