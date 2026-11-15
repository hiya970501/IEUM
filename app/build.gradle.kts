import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.ECPublicKey
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.StringReader

plugins {
    alias(libs.plugins.android.application)
}

// Format only: publisher ownership, a live server and market rollout still require external verification.
object ReleaseEndpointSyntax {
fun accepts(value: String): Boolean = runCatching {
    require(value.length in 1..2048 && value == value.trim() && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = URI(value)
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in listOf(-1,443))
    val host = requireNotNull(uri.host).lowercase()
    val labels = host.split('.')
    require(host.length <= 253 && labels.size >= 2 && labels.all {
        it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
    } && labels.last().any { it.isLetter() })
    val reserved = listOf("localhost", "local", "localdomain", "internal", "invalid", "test", "example",
        "home.arpa", "onion", "example.com", "example.net", "example.org")
    require(reserved.none { host == it || host.endsWith(".$it") })
    val path = uri.rawPath.orEmpty()
    require(path.matches(Regex("(?:/[A-Za-z0-9._~-]+)*/?")) && path.split('/').none { it == "." || it == ".." })
    require(uri.toASCIIString() == value)
    true
}.getOrDefault(false)
}

// A public HTTPS metadata document owned by the publisher; no invented production server.
val updateMetadataUrl = providers.gradleProperty("afterglow.updateMetadataUrl").orElse("")
val releaseStore = providers.environmentVariable("AFTERGLOW_RELEASE_STORE")
val releaseStorePassword = providers.environmentVariable("AFTERGLOW_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("AFTERGLOW_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("AFTERGLOW_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it.orNull?.isNotBlank() == true }

// Fail before packaging if the known-good low-memory base scenes drift from their
// validated files or budgets. Source showroom scenes are debug-private data only.
val validateBundledMemoryAssets by tasks.registering {
    val names = listOf("cabin-effects", "cabin-budget")
    val directory = layout.projectDirectory.dir("src/main/assets")
    inputs.files(names.flatMap { name -> listOf(directory.file("$name.glb"), directory.file("$name.json")) })
    doLast {
        val mib = 1024L * 1024L
        names.forEach { name ->
            val model = directory.file("$name.glb").asFile
            val sidecar = JsonSlurper().parse(directory.file("$name.json").asFile) as Map<*, *>
            val budget = sidecar["memoryBudget"] as? Map<*, *> ?: error("$name: missing memory budget")
            fun amount(key: String) = (budget[key] as? Number)?.toLong() ?: error("$name: missing $key")
            check(budget["validated"] == true && amount("schemaVersion") == 1L) { "$name: unvalidated asset" }
            check(model.length() in 20..24L * mib && amount("glbBytes") == model.length()) { "$name: file budget" }
            check(amount("decodedTextureBytes") in 0..8L * mib) { "$name: base texture budget" }
            check(amount("mipmappedTextureBytes") in amount("decodedTextureBytes")..12L * mib) { "$name: mip budget" }
            check(amount("geometryBytes") in 0..4L * mib) { "$name: base geometry budget" }
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { stream ->
                val chunk = ByteArray(8192)
                while (true) { val read = stream.read(chunk); if (read < 0) break; digest.update(chunk, 0, read) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == sidecar["previewGlbSha256"]) { "$name: asset changed after validation" }
            val header = ByteArray(20)
            model.inputStream().use { it.readNBytes(header, 0, 20) }
            val glb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            check(glb.getInt(0) == 0x46546c67 && glb.getInt(4) == 2 && glb.getInt(8).toLong() == model.length())
            check(glb.getInt(16) == 0x4e4f534a && glb.getInt(12).toLong() == amount("jsonBytes") &&
                amount("jsonBytes") in 1..mib) { "$name: JSON budget" }
        }
    }
}

// Build-time provenance pins complement runtime memory admission. They do not
// grant rights: local manufacturer showroom research must remain outside APKs.
object ModelAssetProvenanceVerifier {
private fun inspectMetadata(value: Any?) {
    when(value) {
        is Map<*, *> -> {
            if(value.containsKey("localReferenceOnly")) check(value["localReferenceOnly"]==false) { "Local-only research metadata in release assets." }
            if(value.containsKey("licenseClearedForPublic")) check(value["licenseClearedForPublic"]==true) { "Uncleared public-use metadata in release assets." }
            value.values.forEach(::inspectMetadata)
        }
        is List<*> -> value.forEach(::inspectMetadata)
    }
}
fun validate(assetRoot: File, manifest: File): Int {
    val root = assetRoot.canonicalFile
    check(root.isDirectory && manifest.isFile && manifest.length() in 1..262144) { "Model provenance inputs unavailable." }
    val document = JsonSlurper().parse(manifest) as? Map<*, *> ?: error("Model provenance must be an object.")
    check(document["schemaVersion"] == 1) { "Unsupported model provenance schema." }
    val rows = document["assets"] as? List<*> ?: error("Model provenance asset list missing.")
    check(rows.size in 1..64) { "Model provenance asset count outside budget." }
    fun child(name: String): File {
        check(name.matches(Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) &&
            name.split('/').none { it == "." || it == ".." }) { "Unsafe provenance asset path." }
        return File(root, name).canonicalFile.also {
            check(it.toPath().startsWith(root.toPath())) { "Provenance path leaves assets." }
        }
    }
    fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    val expected = linkedSetOf<String>()
    val extensions = setOf("glb", "gltf", "ktx", "hmc")
    rows.forEach { value ->
        val row = value as? Map<*, *> ?: error("Invalid provenance entry.")
        val name = row["path"] as? String ?: error("Provenance path missing.")
        check(expected.add(name) && name.substringAfterLast('.').lowercase() in extensions) { "Duplicate or unsupported provenance entry." }
        check(row["distribution"] == "recorded-open-license" && row["recordedLicense"] in setOf("CC0-1.0", "Apache-2.0")) {
            "Unreviewed distribution status: $name"
        }
        val file = child(name)
        val bytes = row["bytes"] as? Number ?: error("Provenance size missing.")
        check(bytes.toDouble() == bytes.toLong().toDouble() && bytes.toLong() in 1..24L * 1024 * 1024 &&
            file.isFile && file.length() == bytes.toLong()) { "Provenance size mismatch: $name" }
        val hash = row["sha256"] as? String ?: error("Provenance digest missing.")
        check(hash.matches(Regex("[a-f0-9]{64}")) && sha(file) == hash) { "Provenance asset changed: $name" }
        val evidence = child(row["sourceEvidence"] as? String ?: error("License evidence missing."))
        check(evidence.isFile && evidence.length() in 1..262144 && sha(evidence) == row["sourceEvidenceSha256"]) {
            "Recorded license evidence missing or changed: $name"
        }
    }
    val actual = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in extensions }
        .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    check(actual == expected) { "Unreviewed or missing packaged model/environment assets." }
    root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach {
        check(it.length() in 1..524288) { "Packaged model metadata outside size budget." }
        inspectMetadata(JsonSlurper().parse(it))
    }
    return rows.size
}
fun validateApk(apk:File,manifest:File):Int {
    val document=JsonSlurper().parse(manifest) as Map<*, *>
    check(document["schemaVersion"]==1) { "Unsupported APK provenance schema." }
    val rows=document["assets"] as List<*>
    check(rows.size in 1..64) { "APK provenance count outside budget." }
    val expected=rows.map { (it as Map<*, *>)["path"] as String }.toSet()
    check(expected.size==rows.size) { "Duplicate APK provenance pins." }
    ZipFile(apk).use { zip ->
        val entries=zip.entries().asSequence().toList()
        val models=entries.filter { !it.isDirectory && it.name.startsWith("assets/") &&
            it.name.substringAfterLast('.').lowercase() in setOf("glb","gltf","ktx","hmc") }
        check(models.map { it.name.removePrefix("assets/") }.toSet()==expected && models.size==expected.size) {
            "Merged APK contains unreviewed, duplicate or missing model assets."
        }
        fun digest(name:String,expectedBytes:Long,maximum:Long):String {
            val entry=zip.getEntry("assets/$name") ?: error("APK provenance asset missing: $name")
            check(entry.size==expectedBytes && expectedBytes in 1..maximum) { "APK provenance size mismatch: $name" }
            val hash=MessageDigest.getInstance("SHA-256");var total=0L
            zip.getInputStream(entry).use { stream ->
                val buffer=ByteArray(8192)
                while(true) { val count=stream.read(buffer);if(count<0) break;total+=count
                    check(total<=expectedBytes) { "APK provenance stream exceeds declared size." };hash.update(buffer,0,count) }
            }
            check(total==expectedBytes) { "Incomplete APK provenance stream." }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        rows.forEach { value ->
            val row=value as Map<*, *>;val name=row["path"] as String
            check(digest(name,(row["bytes"] as Number).toLong(),24L*1024*1024)==row["sha256"]) { "Packaged model changed: $name" }
            val evidence=row["sourceEvidence"] as String
            val entry=zip.getEntry("assets/$evidence") ?: error("Packaged license evidence missing: $name")
            check(digest(evidence,entry.size,262144)==row["sourceEvidenceSha256"]) { "Packaged license evidence changed: $name" }
        }
        entries.filter { !it.isDirectory && it.name.startsWith("assets/") && it.name.endsWith(".json",true) }.forEach { entry ->
            check(entry.size in 1..524288) { "Merged APK metadata outside size budget." }
            val bytes=zip.getInputStream(entry).use { it.readNBytes(524289) }
            check(bytes.size.toLong()==entry.size) { "Merged APK metadata size mismatch." }
            inspectMetadata(JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)))
        }
    }
    return rows.size
}
}

val verifyReleaseModelAssetProvenance = tasks.register("verifyReleaseModelAssetProvenance") {
    group = "verification"
    description = "Reject changed, unreviewed or local-only model assets before release packaging."
    val assets = layout.projectDirectory.dir("src/main/assets")
    val manifest = rootProject.layout.projectDirectory.file("config/release-model-assets.json")
    inputs.dir(assets); inputs.file(manifest)
    dependsOn(validateBundledMemoryAssets)
    doLast { println("Model/environment provenance pins: ${ModelAssetProvenanceVerifier.validate(assets.asFile, manifest.asFile)} verified; not legal certification.") }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseModelAssetProvenance) }
val verifyReleasePackagedModels=tasks.register("verifyReleasePackagedModels") {
    group="verification"
    val apk=layout.buildDirectory.file("outputs/apk/release/${if(releaseSigningReady) "app-release.apk" else "app-release-unsigned.apk"}")
    val manifest=rootProject.layout.projectDirectory.file("config/release-model-assets.json")
    inputs.file(apk);inputs.file(manifest)
    dependsOn("packageRelease",verifyReleaseModelAssetProvenance)
    doLast { println("Merged APK model/environment pins: ${ModelAssetProvenanceVerifier.validateApk(apk.get().asFile,manifest.asFile)} verified.") }
}
tasks.matching { it.name=="assembleRelease" }.configureEach { dependsOn(verifyReleasePackagedModels) }

// Test fixtures must never enlarge the production attack surface. Check the
// merged manifest, including library contributions, rather than source XML alone.
object ReleaseManifestIsolation {
    private const val androidNs = "http://schemas.android.com/apk/res/android"
    fun verify(xml: String): Int {
        require(xml.length in 1..524288) { "Merged manifest outside verifier bounds" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val apps = doc.getElementsByTagName("application")
        check(apps.length == 1) { "Exactly one release application required" }
        val app = apps.item(0) as org.w3c.dom.Element
        fun org.w3c.dom.Element.android(name: String) = getAttributeNS(androidNs, name)
        check(app.android("debuggable") != "true" && app.android("testOnly") != "true") { "Debug/test application forbidden in release" }
        check(app.android("allowBackup") == "false" && app.android("usesCleartextTraffic") != "true") { "Release backup/cleartext contract changed" }
        check(app.android("networkSecurityConfig") == "@xml/network_security_config") { "Release network policy missing" }
        val forbiddenPermissions = setOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_AUDIO", "READ_CONTACTS", "READ_SMS", "RECORD_AUDIO", "BODY_SENSORS", "QUERY_ALL_PACKAGES")
        val permissions = doc.getElementsByTagName("uses-permission")
        for (i in 0 until permissions.length) {
            val name = (permissions.item(i) as org.w3c.dom.Element).android("name")
            check(name.removePrefix("android.permission.") !in forbiddenPermissions) { "Unreviewed broad release permission" }
        }
        val providers = doc.getElementsByTagName("provider")
        for (i in 0 until providers.length) {
            val provider = providers.item(i) as org.w3c.dom.Element
            val authority = provider.android("authorities")
            check(!authority.endsWith(".owned.audio") && !authority.endsWith(".diagnostics") &&
                !provider.android("name").endsWith("BaselineQaDiagnosticsProvider")) { "Test provider leaked into release" }
            check(provider.android("exported") == "false") { "Unreviewed exported release provider" }
        }
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            check((services.item(i) as org.w3c.dom.Element).android("exported") == "false") { "Unreviewed exported release service" }
        }
        return providers.length + services.length
    }
}

val verifyReleaseManifestIsolationCorpus = tasks.register("verifyReleaseManifestIsolationCorpus") {
    group = "verification"
    doLast {
        val base = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="false" android:networkSecurityConfig="@xml/network_security_config"><provider android:name="androidx.startup.InitializationProvider" android:authorities="ieum.androidx-startup" android:exported="false"/><service android:name=".ZeroTapBackgroundService" android:exported="false"/></application></manifest>"""
        check(ReleaseManifestIsolation.verify(base) == 2)
        val rejected = listOf(
            base.replace("android:allowBackup=\"false\"", "android:allowBackup=\"true\""),
            base.replace("<application ", "<application android:debuggable=\"true\" "),
            base.replace("<application ", "<application android:testOnly=\"true\" "),
            base.replace("<application ", "<application android:usesCleartextTraffic=\"true\" "),
            base.replace("ieum.androidx-startup", "ieum.owned.audio"),
            base.replace("ieum.androidx-startup", "ieum.diagnostics"),
            base.replace("android:exported=\"false\"", "android:exported=\"true\""),
            base.replace("<application ", "<uses-permission android:name=\"android.permission.READ_MEDIA_AUDIO\"/><application "),
            base.replace("@xml/network_security_config", "@xml/other_policy")
        )
        rejected.forEachIndexed { index, xml ->
            check(runCatching { ReleaseManifestIsolation.verify(xml) }.isFailure) { "Manifest isolation negative case $index accepted" }
        }
        println("Release manifest isolation: valid fixture and ${rejected.size} negative cases passed.")
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val merged = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
    val verify = tasks.register("verifyReleaseManifestIsolation") {
        group = "verification"
        inputs.file(merged)
        dependsOn(verifyReleaseManifestIsolationCorpus)
        doLast { println("Merged release manifest isolation: ${ReleaseManifestIsolation.verify(merged.get().asFile.readText())} private components checked.") }
    }
    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verify) }
}

// Catch the observed partial-R8 package-private superclass regression before an
// APK is handed over. This is a narrow DEX access check, not runtime certification.
object DexPackageAccessVerifier {
    private data class Definition(val flags:Int,val parent:String?)
    fun verify(apk:File):Int {
        val classes=linkedMapOf<String,Definition>()
        ZipFile(apk).use { zip ->
            val dexFiles=zip.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }.toList()
            check(dexFiles.isNotEmpty()) { "No ordinary DEX files" }
            dexFiles.forEach { entry ->
                check(entry.size in 112..64L*1024*1024) { "DEX size outside verifier bounds" }
                val bytes=zip.getInputStream(entry).use { it.readBytes() }
                val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                check(bytes.size.toLong()==entry.size && String(bytes,0,4,Charsets.US_ASCII)=="dex\n" && bytes[7]==0.toByte() && data.getInt(32)==bytes.size) { "Invalid DEX header" }
                fun word(at:Int):Int { check(at>=0 && at<=bytes.size-4);return data.getInt(at) }
                val stringCount=word(56);val stringAt=word(60);val typeCount=word(64);val typeAt=word(68)
                check(stringCount in 1..1000000 && typeCount in 1..65536)
                fun descriptor(index:Int):String {
                    check(index in 0 until typeCount)
                    val stringIndex=word(typeAt+index*4);check(stringIndex in 0 until stringCount)
                    var at=word(stringAt+stringIndex*4);var lengthBytes=0
                    do { check(at in bytes.indices && lengthBytes++<5);val more=bytes[at++].toInt() and 128 } while(more!=0)
                    val start=at
                    while(at<bytes.size && bytes[at]!=0.toByte()) at++
                    check(at<bytes.size && at-start<=65536)
                    return String(bytes,start,at-start,Charsets.UTF_8).also { check('\uFFFD' !in it) { "Unsupported descriptor encoding" } }
                }
                val count=word(96);val at=word(100);check(count in 1..65536)
                repeat(count) { i ->
                    val offset=at+i*32;val child=descriptor(word(offset));val parentIndex=word(offset+8)
                    val parent=if(parentIndex==-1)null else descriptor(parentIndex)
                    check(classes.put(child,Definition(word(offset+4),parent))==null) { "Duplicate DEX class" }
                }
            }
        }
        classes.forEach { (child,definition) ->
            val parent=definition.parent
            val inherited=classes[parent]
            if(parent!=null && inherited!=null && inherited.flags and 1==0) {
                check(child.substringBeforeLast('/',"")==parent.substringBeforeLast('/',"")) {
                    "Inaccessible DEX superclass: $child extends $parent"
                }
            }
        }
        return classes.size
    }
}
val verifyReleaseDexPackageAccess=tasks.register("verifyReleaseDexPackageAccess") {
    group="verification"
    description="Reject in-APK direct inheritance across a nonpublic superclass package boundary."
    val apk=layout.buildDirectory.file("outputs/apk/release/${if(releaseSigningReady) "app-release.apk" else "app-release-unsigned.apk"}")
    inputs.file(apk);dependsOn("packageRelease")
    doLast { println("DEX direct superclass package access: ${DexPackageAccessVerifier.verify(apk.get().asFile)} classes checked; runtime/reflection checks remain required.") }
}
tasks.matching { it.name=="assembleRelease" }.configureEach { dependsOn(verifyReleaseDexPackageAccess) }

tasks.register("verifyModelAssetProvenanceCorpus") {
    group = "verification"
    val fixtureParent=layout.buildDirectory.dir("model-provenance-corpus").get().asFile.canonicalFile
    val fixtureProject=rootProject.projectDir.canonicalFile
    doLast {
        val parent = fixtureParent
        check(parent.toPath().startsWith(fixtureProject.toPath())) { "Fixture directory leaves project." }
        parent.mkdirs()
        val run = Files.createTempDirectory(parent.toPath(), "run-").toFile()
        var passed = 0
        fun test(name: String, accepted: Boolean, mutate: (File, MutableMap<String, Any>, MutableMap<String, Any>) -> Unit = { _, _, _ -> }) {
            val directory = File(run, name); val assets = File(directory, "assets").apply { mkdirs() }
            val model = File(assets, "approved.glb").apply { writeText("owned fixture bytes") }
            val license = File(assets, "LICENSE.txt").apply { writeText("SPDX-License-Identifier: CC0-1.0\n") }
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val row = linkedMapOf<String, Any>("path" to model.name, "bytes" to model.length(), "sha256" to digest(model),
                "distribution" to "recorded-open-license", "recordedLicense" to "CC0-1.0", "sourceEvidence" to license.name,
                "sourceEvidenceSha256" to digest(license))
            val doc = linkedMapOf<String, Any>("schemaVersion" to 1, "assets" to listOf(row))
            mutate(assets, row, doc)
            val manifest = File(directory, "manifest.json").apply { writeText(JsonOutput.toJson(doc)) }
            var rejected = false
            try { ModelAssetProvenanceVerifier.validate(assets, manifest) } catch (_: IllegalStateException) { rejected = true }
            check(rejected != accepted) { "Provenance corpus case failed: $name" }
            passed++
            if(accepted) {
                fun apkCase(caseName:String,shouldPass:Boolean,extra:(ZipOutputStream)->Unit={}) {
                    val apk=File(directory,"$caseName.apk")
                    ZipOutputStream(apk.outputStream()).use { zip ->
                        assets.walkTopDown().filter { it.isFile }.forEach { file ->
                            zip.putNextEntry(ZipEntry("assets/${file.relativeTo(assets).invariantSeparatorsPath}"));zip.write(file.readBytes());zip.closeEntry()
                        }
                        extra(zip)
                    }
                    var denied=false
                    try { ModelAssetProvenanceVerifier.validateApk(apk,manifest) } catch (_:IllegalStateException) { denied=true }
                    check(denied!=shouldPass) { "APK provenance corpus case failed: $caseName" };passed++
                }
                apkCase("valid-apk",true)
                apkCase("merged-unreviewed-model",false) { zip ->zip.putNextEntry(ZipEntry("assets/manufacturer.hmc"));zip.write("unreviewed".toByteArray());zip.closeEntry() }
                apkCase("merged-private-metadata",false) { zip ->zip.putNextEntry(ZipEntry("assets/private.json"));zip.write("{\"localReferenceOnly\":true}".toByteArray());zip.closeEntry() }
            }
        }
        test("valid", true)
        test("unreviewed-binary", false) { dir, _, _ -> File(dir, "manufacturer.hmc").writeText("unreviewed") }
        test("changed-model", false) { dir, _, _ -> File(dir, "approved.glb").appendText("changed") }
        test("local-only", false) { dir, _, _ -> File(dir, "model.json").writeText("{\"localReferenceOnly\":true}") }
        test("uncleared-public-license", false) { dir, _, _ -> File(dir, "model.json").writeText("{\"licenseClearedForPublic\":false}") }
        test("path-traversal", false) { _, row, _ -> row["path"] = "../outside.glb" }
        test("duplicate", false) { _, row, doc -> doc["assets"] = listOf(row, row) }
        test("unknown-schema", false) { _, _, doc -> doc["schemaVersion"] = 2 }
        test("missing-license", false) { _, row, _ -> row["sourceEvidence"] = "missing.txt" }
        test("changed-license", false) { dir, _, _ -> File(dir, "LICENSE.txt").appendText("changed") }
        test("unreviewed-distribution", false) { _, row, _ -> row["distribution"] = "local-reference-only" }
        println("Model provenance corpus: $passed cases passed. Generated fixtures retained under build/.")
    }
}

android {
    buildFeatures { resValues = true }
    // Let Android map the unchanged variable font from the APK instead of
    // inflating a process-private native buffer. No font/glyph/weight is removed.
    androidResources { noCompress += "ttf" }
    ndkVersion = "30.0.16248370"
    providers.gradleProperty("afterglow.ndkPath").orNull?.let { ndkPath = it }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.named("main") { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
    useLibrary("android.car")
    namespace = "com.example.version1"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.version1"
        minSdk = 28
        targetSdk = 34
        versionCode = 15
        versionName = "1.4.0"
        resValue("string", "update_metadata_url", updateMetadataUrl.get())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) create("publisher") {
            storeFile = file(releaseStore.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.get()
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("publisher")
            proguardFiles("proguard-kotlin-package-boundaries.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
        create("baselineQa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".baselineqa"
            versionNameSuffix = "-baselineqa"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "IEUM 성능 검증")
        }
    }
    compileOptions {
        // The pinned Filament 1.75 utility math inlines JVM17 bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Review builds remain possible. Submission is a separate, fail-closed operation.
tasks.register("verifyMarketListing") {
    group = "verification"
    description = "Verify reviewable IEUM market tags, package, category and version metadata; does not publish."
    val listingFile = rootProject.file("submission/market-listing.json")
    val manifestFile = file("src/main/AndroidManifest.xml")
    val labelsFile = file("src/main/res/values/strings.xml")
    val expectedPackage = android.defaultConfig.applicationId
    val expectedName = android.defaultConfig.versionName
    val expectedCode = android.defaultConfig.versionCode
    inputs.file(listingFile)
    inputs.file(manifestFile)
    inputs.file(labelsFile)
    doLast {
        val listing = JsonSlurper().parse(listingFile) as? Map<*, *> ?: error("Market listing object required.")
        val canonicalName = Regex("""<string\s+name="app_name">([^<]+)</string>""")
            .find(labelsFile.readText())?.groupValues?.get(1)
        check(canonicalName == "IEUM" && listing["englishTitle"] == canonicalName) {
            "The market English title must exactly match the official app name IEUM."
        }
        val tags = listing["tags"] as? List<*> ?: error("Market tags required.")
        check(tags.all { it is String && it.isNotBlank() } && canonicalName in tags) {
            "The exact official English app name IEUM is a required market tag."
        }
        check(listing["applicationId"] == expectedPackage &&
            listing["versionName"] == expectedName &&
            listing["versionCode"] is Int && listing["versionCode"] == expectedCode) {
            "Market package and version must match the current vehicle app."
        }
        val category = Regex("""android:appCategory="([^"]+)"""")
            .find(manifestFile.readText())?.groupValues?.get(1)
        check(category == listing["androidCategory"] && (listing["marketCategory"] as? String)?.isNotBlank() == true) {
            "Android manifest category and separate proposed market category must be recorded."
        }
        check(listing["marketRegistrationCompleted"] is Boolean && listing["categoryApprovalCompleted"] is Boolean) {
            "External market registration and category approval state must be explicit."
        }
    }
}
tasks.register("verifyReleaseEndpointCorpus") {
    group = "verification"
    val corpus = rootProject.file("shared/test-data/update-endpoint-cases.json")
    inputs.file(corpus)
    doLast {
        val cases = JsonSlurper().parse(corpus) as List<*>
        check(cases.size >= 25)
        cases.forEachIndexed { index, item ->
            val row = item as Map<*, *>
            check(ReleaseEndpointSyntax.accepts(row["value"] as String) == row["accepted"]) { "Release endpoint contract case $index failed" }
        }
        println("Release endpoint syntax contract: ${cases.size} cases passed.")
    }
}

tasks.register("verifySubmissionConfiguration") {
    group = "verification"
    description = "Reject a submission lacking publisher signing, HTTPS metadata or a newer app version."
    notCompatibleWithConfigurationCache("Submission checks re-read the publisher certificate and external signing configuration.")
    dependsOn("verifyMarketListing", "verifyReleaseEndpointCorpus", verifyReleaseModelAssetProvenance)
    doLast {
        check(ReleaseEndpointSyntax.accepts(updateMetadataUrl.get())) {
            "A publisher-owned public HTTPS release metadata endpoint is required."
        }
        check(releaseSigningReady && file(releaseStore.get()).isFile) {
            "Publisher release signing environment is required; debug or generated test keys are not accepted."
        }
        val keyStore = KeyStore.getInstance(file(releaseStore.get()),releaseStorePassword.get().toCharArray())
        val certificate = keyStore.getCertificate(releaseKeyAlias.get()) as? X509Certificate
            ?: error("Publisher signing certificate unavailable.")
        certificate.checkValidity()
        check(!certificate.subjectX500Principal.name.contains("Android Debug",ignoreCase=true) &&
            !certificate.sigAlgName.contains("SHA1",ignoreCase=true) && !certificate.sigAlgName.contains("MD5",ignoreCase=true)) {
            "A valid publisher certificate with a modern signature algorithm is required."
        }
        check(when(val key=certificate.publicKey){
            is RSAPublicKey -> key.modulus.bitLength() >= 2048
            is ECPublicKey -> key.params.curve.field.fieldSize >= 256
            else -> false
        }) { "Publisher signing key must meet the RSA-2048 or EC-256 minimum." }
        val previous = providers.gradleProperty("afterglow.previousVersionCode").orNull?.toIntOrNull()
        check(previous != null && previous >= 0 && android.defaultConfig.versionCode!! > previous) {
            "Record the previous submission versionCode and increment the version before submitting."
        }
    }
}

tasks.register("prepareSubmission") {
    group = "verification"
    description = "Build a release only after publisher signing, update metadata and version checks."
    dependsOn("verifySubmissionConfiguration", "assembleRelease")
}
tasks.matching { it.name == "assembleRelease" }.configureEach { mustRunAfter("verifySubmissionConfiguration") }

dependencies {
    implementation("com.google.android.filament:filament-android:1.75.0")
    implementation("com.google.android.filament:gltfio-android:1.75.0")
    implementation("com.google.android.filament:filament-utils-android:1.75.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Offline debug companion distribution is development-only, never a release asset.
val packagePhoneCompanion by tasks.registering(Copy::class) {
    dependsOn(":phone:assembleDebug")
    from(project(":phone").layout.buildDirectory.file("outputs/apk/debug/phone-debug.apk"))
    into(layout.buildDirectory.dir("generated/phoneCompanionAssets"))
    rename { "afterglow-phone.apk" }
}
android.sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/phoneCompanionAssets").get().asFile)
tasks.named("preBuild") { dependsOn(validateBundledMemoryAssets) }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val cases = rootProject.file("shared/test-data/private-ipv4-cases.json")
    inputs.file(cases)
    systemProperty("afterglow.ipv4.cases", cases.absolutePath)
    val endpoints = rootProject.file("shared/test-data/update-endpoint-cases.json")
    inputs.file(endpoints)
    systemProperty("ieum.updateEndpoint.cases", endpoints.absolutePath)
}

tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(packagePhoneCompanion) }
