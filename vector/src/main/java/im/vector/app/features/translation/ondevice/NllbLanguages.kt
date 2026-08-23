/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation.ondevice

/**
 * NLLB-200 language handling: the model's FLORES-200 language-token vocabulary (in token-id order),
 * and the mapping from the app's Google-style language ids onto it.
 */
object NllbLanguages {

    /** All 202 FLORES codes, in the exact order of their token ids (256001 + index). */
    val FLORES_CODES = listOf(
            "ace_Arab", "ace_Latn", "acm_Arab", "acq_Arab", "aeb_Arab", "afr_Latn", "ajp_Arab", "aka_Latn", "amh_Ethi",
            "apc_Arab", "arb_Arab", "ars_Arab", "ary_Arab", "arz_Arab", "asm_Beng", "ast_Latn", "awa_Deva", "ayr_Latn",
            "azb_Arab", "azj_Latn", "bak_Cyrl", "bam_Latn", "ban_Latn", "bel_Cyrl", "bem_Latn", "ben_Beng", "bho_Deva",
            "bjn_Arab", "bjn_Latn", "bod_Tibt", "bos_Latn", "bug_Latn", "bul_Cyrl", "cat_Latn", "ceb_Latn", "ces_Latn",
            "cjk_Latn", "ckb_Arab", "crh_Latn", "cym_Latn", "dan_Latn", "deu_Latn", "dik_Latn", "dyu_Latn", "dzo_Tibt",
            "ell_Grek", "eng_Latn", "epo_Latn", "est_Latn", "eus_Latn", "ewe_Latn", "fao_Latn", "pes_Arab", "fij_Latn",
            "fin_Latn", "fon_Latn", "fra_Latn", "fur_Latn", "fuv_Latn", "gla_Latn", "gle_Latn", "glg_Latn", "grn_Latn",
            "guj_Gujr", "hat_Latn", "hau_Latn", "heb_Hebr", "hin_Deva", "hne_Deva", "hrv_Latn", "hun_Latn", "hye_Armn",
            "ibo_Latn", "ilo_Latn", "ind_Latn", "isl_Latn", "ita_Latn", "jav_Latn", "jpn_Jpan", "kab_Latn", "kac_Latn",
            "kam_Latn", "kan_Knda", "kas_Arab", "kas_Deva", "kat_Geor", "knc_Arab", "knc_Latn", "kaz_Cyrl", "kbp_Latn",
            "kea_Latn", "khm_Khmr", "kik_Latn", "kin_Latn", "kir_Cyrl", "kmb_Latn", "kon_Latn", "kor_Hang", "kmr_Latn",
            "lao_Laoo", "lvs_Latn", "lij_Latn", "lim_Latn", "lin_Latn", "lit_Latn", "lmo_Latn", "ltg_Latn", "ltz_Latn",
            "lua_Latn", "lug_Latn", "luo_Latn", "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym", "mar_Deva", "min_Latn",
            "mkd_Cyrl", "plt_Latn", "mlt_Latn", "mni_Beng", "khk_Cyrl", "mos_Latn", "mri_Latn", "zsm_Latn", "mya_Mymr",
            "nld_Latn", "nno_Latn", "nob_Latn", "npi_Deva", "nso_Latn", "nus_Latn", "nya_Latn", "oci_Latn", "gaz_Latn",
            "ory_Orya", "pag_Latn", "pan_Guru", "pap_Latn", "pol_Latn", "por_Latn", "prs_Arab", "pbt_Arab", "quy_Latn",
            "ron_Latn", "run_Latn", "rus_Cyrl", "sag_Latn", "san_Deva", "sat_Beng", "scn_Latn", "shn_Mymr", "sin_Sinh",
            "slk_Latn", "slv_Latn", "smo_Latn", "sna_Latn", "snd_Arab", "som_Latn", "sot_Latn", "spa_Latn", "als_Latn",
            "srd_Latn", "srp_Cyrl", "ssw_Latn", "sun_Latn", "swe_Latn", "swh_Latn", "szl_Latn", "tam_Taml", "tat_Cyrl",
            "tel_Telu", "tgk_Cyrl", "tgl_Latn", "tha_Thai", "tir_Ethi", "taq_Latn", "taq_Tfng", "tpi_Latn", "tsn_Latn",
            "tso_Latn", "tuk_Latn", "tum_Latn", "tur_Latn", "twi_Latn", "tzm_Tfng", "uig_Arab", "ukr_Cyrl", "umb_Latn",
            "urd_Arab", "uzn_Latn", "vec_Latn", "vie_Latn", "war_Latn", "wol_Latn", "xho_Latn", "ydd_Hebr", "yor_Latn",
            "yue_Hant", "zho_Hans", "zho_Hant", "zul_Latn",
    )

    /** Google-style app language id -> FLORES code. Languages NLLB doesn't cover are absent. */
    private val GOOGLE_TO_FLORES = mapOf(
            "af" to "afr_Latn", "am" to "amh_Ethi", "ar" to "arb_Arab", "az" to "azj_Latn", "be" to "bel_Cyrl",
            "bg" to "bul_Cyrl", "bn" to "ben_Beng", "bs" to "bos_Latn", "ca" to "cat_Latn", "ceb" to "ceb_Latn",
            "cs" to "ces_Latn", "cy" to "cym_Latn", "da" to "dan_Latn", "de" to "deu_Latn", "el" to "ell_Grek",
            "en" to "eng_Latn", "eo" to "epo_Latn", "es" to "spa_Latn", "et" to "est_Latn", "eu" to "eus_Latn",
            "fa" to "pes_Arab", "fi" to "fin_Latn", "fr" to "fra_Latn", "ga" to "gle_Latn", "gd" to "gla_Latn",
            "gl" to "glg_Latn", "gu" to "guj_Gujr", "ha" to "hau_Latn", "hi" to "hin_Deva", "hr" to "hrv_Latn",
            "ht" to "hat_Latn", "hu" to "hun_Latn", "hy" to "hye_Armn", "id" to "ind_Latn", "ig" to "ibo_Latn",
            "is" to "isl_Latn", "it" to "ita_Latn", "iw" to "heb_Hebr", "ja" to "jpn_Jpan", "jw" to "jav_Latn",
            "ka" to "kat_Geor", "kk" to "kaz_Cyrl", "km" to "khm_Khmr", "kn" to "kan_Knda", "ko" to "kor_Hang",
            "ku" to "kmr_Latn", "ky" to "kir_Cyrl", "lb" to "ltz_Latn", "lo" to "lao_Laoo", "lt" to "lit_Latn",
            "lv" to "lvs_Latn", "mg" to "plt_Latn", "mi" to "mri_Latn", "mk" to "mkd_Cyrl", "ml" to "mal_Mlym",
            "mn" to "khk_Cyrl", "mr" to "mar_Deva", "ms" to "zsm_Latn", "mt" to "mlt_Latn", "my" to "mya_Mymr",
            "ne" to "npi_Deva", "nl" to "nld_Latn", "no" to "nob_Latn", "ny" to "nya_Latn", "or" to "ory_Orya",
            "pa" to "pan_Guru", "pl" to "pol_Latn", "ps" to "pbt_Arab", "pt" to "por_Latn", "ro" to "ron_Latn",
            "ru" to "rus_Cyrl", "rw" to "kin_Latn", "sd" to "snd_Arab", "si" to "sin_Sinh", "sk" to "slk_Latn",
            "sl" to "slv_Latn", "sm" to "smo_Latn", "sn" to "sna_Latn", "so" to "som_Latn", "sq" to "als_Latn",
            "sr" to "srp_Cyrl", "st" to "sot_Latn", "su" to "sun_Latn", "sv" to "swe_Latn", "sw" to "swh_Latn",
            "ta" to "tam_Taml", "te" to "tel_Telu", "tg" to "tgk_Cyrl", "th" to "tha_Thai", "tk" to "tuk_Latn",
            "tl" to "tgl_Latn", "tr" to "tur_Latn", "tt" to "tat_Cyrl", "ug" to "uig_Arab", "uk" to "ukr_Cyrl",
            "ur" to "urd_Arab", "uz" to "uzn_Latn", "vi" to "vie_Latn", "xh" to "xho_Latn", "yi" to "ydd_Hebr",
            "yo" to "yor_Latn", "zh-CN" to "zho_Hans", "zh-TW" to "zho_Hant", "zu" to "zul_Latn",
    )

    val supportedGoogleIds: Set<String> = GOOGLE_TO_FLORES.keys

    fun floresOf(googleId: String): String? = GOOGLE_TO_FLORES[googleId]

    /** NLLB decoder token id of a FLORES language code. */
    fun tokenIdOf(floresCode: String): Int? =
            FLORES_CODES.indexOf(floresCode).takeIf { it >= 0 }?.let { 256000 + it + 1 }
}
