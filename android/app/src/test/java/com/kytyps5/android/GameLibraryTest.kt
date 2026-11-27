package com.kytyps5.android

import java.io.File
import java.io.RandomAccessFile
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
        File(root, "raw-disk.img").writeText("img") // only *.exfat is listed; .img is opened by path

        val result = GameLibrary.scan(root) as ScanResult.Found

        assertEquals(listOf("ALPHA", "Beta Game", "Gamma", "Middle"), result.games.map { it.title })
        assertEquals(File(root, "Beta Game.exfat").absolutePath, result.games[1].path)
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


    @Test
    fun scanListsValidatedPkgAsMetadataOnlyEntry() {
        val pkg = makeMetadataPkg("ASTRO.pkg", """{"titleName":"ASTRO BOT","titleId":"PPSA21567"}""")

        val result = GameLibrary.scan(root) as ScanResult.Found
        assertEquals(listOf("ASTRO BOT"), result.games.map { it.title })
        assertEquals(pkg.absolutePath, result.games.single().path)
        assertEquals("PPSA21567", result.games.single().titleId)
        assertTrue(!result.games.single().launchable)
        assertEquals("PKG metadata • not installable", result.games.single().status)
    }



    @Test
    fun scanIgnoresArbitraryFilesNamedPkg() {
        File(root, "not-a-package.pkg").writeBytes(ByteArray(256) { 0x41 })
        File(root, "another.pkg").writeText("not a PS5 package")

        val result = GameLibrary.scan(root) as ScanResult.Found
        assertTrue(result.games.isEmpty())
    }

    @Test
    fun scanIgnoresFihWhenCntSegmentIsMalformed() {
        val pkg = makeMetadataPkg("broken-cnt.pkg", """{"titleName":"Must Not Appear"}""")
        RandomAccessFile(pkg, "rw").use { raf ->
            raf.seek(0x20000L)
            raf.write(byteArrayOf(0x7f, 0x42, 0x41, 0x44))
        }

        val result = GameLibrary.scan(root) as ScanResult.Found
        assertTrue(result.games.isEmpty())
    }

    private fun makeMetadataPkg(name: String, paramJson: String): File {
        val cntOffset = 0x20000
        val cntSize = 0x800
        val bytes = ByteArray(cntOffset + cntSize)
        putMagic(bytes, 0, "FIH")
        putLe64(bytes, 0x10, 0x10000L)
        putLe64(bytes, 0x18, 0x10000L)
        putLe64(bytes, 0x58, cntOffset.toLong())
        putLe64(bytes, 0xa0, cntSize.toLong())

        // Plaintext outer PFS superblock identifiers used by the conservative probe.
        putLe64(bytes, 0x10000, 2L)
        putLe64(bytes, 0x10008, 20130315L)
        putLe32(bytes, 0x10020, 0x10000L)

        putMagic(bytes, cntOffset, "CNT")
        putBe32(bytes, cntOffset + 0x10, 2L)
        putBe32(bytes, cntOffset + 0x18, 0x5a0L)

        // Entry 0: entry-name table.
        putBe32(bytes, cntOffset + 0x5a0, 0x0200L)
        putBe32(bytes, cntOffset + 0x5a0 + 0x10, 0x700L)
        val names = byteArrayOf(0) + "sce_sys/param.json".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        putBe32(bytes, cntOffset + 0x5a0 + 0x14, names.size.toLong())

        // Entry 1: plaintext param.json.
        val json = paramJson.toByteArray(Charsets.UTF_8)
        putBe32(bytes, cntOffset + 0x5c0, 0x2000L)
        putBe32(bytes, cntOffset + 0x5c0 + 0x04, 1L)
        putBe32(bytes, cntOffset + 0x5c0 + 0x10, 0x740L)
        putBe32(bytes, cntOffset + 0x5c0 + 0x14, json.size.toLong())
        names.copyInto(bytes, cntOffset + 0x700)
        json.copyInto(bytes, cntOffset + 0x740)

        return File(root, name).apply { writeBytes(bytes) }
    }

    private fun putMagic(bytes: ByteArray, offset: Int, magic: String) {
        bytes[offset] = 0x7f
        magic.forEachIndexed { index, char -> bytes[offset + index + 1] = char.code.toByte() }
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun makeGame(folderName: String, paramJson: String?) {
        val folder = File(root, folderName)
        File(folder, "sce_sys").mkdirs()
        paramJson?.let { File(folder, GameLibrary.PARAM_JSON).writeText(it) }
    }
}
