/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import org.amshove.kluent.shouldBeEqualTo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.IOException

/** Payloads are trimmed from live captures of both endpoints. */
class GoogleTranslateWireTest {

    private fun web(payload: String?): String {
        val envelope = JSONArray().put("wrb.fr").put("MkEWBc").put(payload ?: JSONObject.NULL)
                .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONArray().put(3)).put("generic")
        return ")]}'\n\n" + JSONArray().put(envelope).put(JSONArray().put("di").put(24)).toString()
    }

    @Test
    fun `request mirrors the site's MkEWBc call`() {
        val req = JSONArray(GoogleTranslateWire.webRequest("Bonjour \"le\" monde", "auto", "en"))
        val call = req.getJSONArray(0).getJSONArray(0)
        call.getString(0) shouldBeEqualTo "MkEWBc"
        call.getString(3) shouldBeEqualTo "generic"
        val args = JSONArray(call.getString(1)).getJSONArray(0)
        args.getString(0) shouldBeEqualTo "Bonjour \"le\" monde"
        args.getString(1) shouldBeEqualTo "auto"
        args.getString(2) shouldBeEqualTo "en"
    }

    @Test
    fun `sentences are joined keeping their newline prefixes`() {
        val payload = """[[null,null,"fr",[[[0,[[[null,21]],[true]]]],49],null,null,["x","auto","en",true,null,2]],""" +
                """[[[null,null,null,null,null,[["Line one in French.",null,null,null,null,null,"Line one en français.",1],""" +
                """["\nLine two.",null,null,null,null,null,"Ligne deux.",1],["\n\nParagraph three.",null,null,null,null,null,"Paragraphe trois.",1]],""" +
                """null,null,null,[]]],"en",1,"fr",["x","auto","en",true,null,2]],"fr",null,null,null,null,[[[0],[[6,1]]]]]"""
        GoogleTranslateWire.parseWebResponse(web(payload)) shouldBeEqualTo ("Line one in French.\nLine two.\n\nParagraph three." to "fr")
    }

    @Test
    fun `sentences flagged as spaced get a space before them`() {
        val payload = """[[null,null,"de",[],null,null,["x","auto","en",true,null,2]],""" +
                """[[[null,null,null,null,null,[["Hello, how are you?",null,null,null,null,null,"Hallo, wie geht es dir?",1],""" +
                """["I'm doing well.",null,true,null,null,null,"Mir geht es gut.",1],["Thanks!",null,true,null,null,null,"Danke!",1],""" +
                """["\nSecond line.",null,null,null,null,null,"Zweite Zeile.",1]],null,null,null,[]]],"en",1,"de",["x","auto","en",true,null,2]],"de"]"""
        GoogleTranslateWire.parseWebResponse(web(payload)) shouldBeEqualTo
                ("Hello, how are you? I'm doing well. Thanks!\nSecond line." to "de")
    }

    @Test
    fun `gendered candidates take the first whole-text translation`() {
        val payload = """[[null,null,"en",[[[0,[[[null,13]],[true]]]],13],null,null,["I am a doctor","auto","es",true,null,2]],""" +
                """[[["Soy doctora",null,"(feminine)",null,null,null,null,1,null,[]],["Soy doctor",null,"(masculine)",null,null,null,null,2,null,[]]],""" +
                """"es",1,"en",["I am a doctor","auto","es",true,null,2]],"en"]"""
        GoogleTranslateWire.parseWebResponse(web(payload)) shouldBeEqualTo ("Soy doctora" to "en")
    }

    @Test
    fun `url-only input returns the text without a detected language`() {
        val payload = """[null,[[["https://example.org/foo",null,null,null,"https://translate.google.com/translate?u=https://example.org/foo"]],""" +
                """"fr",3,"auto",["https://example.org/foo","auto","fr",true,null,2]]]"""
        GoogleTranslateWire.parseWebResponse(web(payload)) shouldBeEqualTo ("https://example.org/foo" to null)
    }

    @Test(expected = IOException::class)
    fun `null payload is a rejection`() {
        GoogleTranslateWire.parseWebResponse(web(null))
    }

    @Test(expected = IOException::class)
    fun `html error page is not parsed as a translation`() {
        GoogleTranslateWire.parseWebResponse("<html><head><title>Sorry...</title></head></html>")
    }

    @Test
    fun `dictionary auto-detect shape carries the source language`() {
        GoogleTranslateWire.parseDictionaryResponse("""[["Hello world. How are you?","fr"]]""") shouldBeEqualTo ("Hello world. How are you?" to "fr")
    }

    @Test
    fun `dictionary explicit-source shape is a bare string`() {
        GoogleTranslateWire.parseDictionaryResponse("""["Good morning"]""") shouldBeEqualTo ("Good morning" to null)
    }
}
