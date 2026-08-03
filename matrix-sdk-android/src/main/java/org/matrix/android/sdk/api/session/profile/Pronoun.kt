/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

/**
 * A single MSC4247 pronoun entry. The array is preference-ordered, most preferred first.
 * `summary` and `language` are the only required fields. The granular grammatical forms
 * (used by the gomuks-style schema) and `grammaticalGender` (the MSC schema) are all optional;
 * we read whichever is present and write both for known pronoun sets so any reader can consume them.
 */
data class Pronoun(
        val summary: String,
        val language: String? = null,
        val subject: String? = null,
        val objectForm: String? = null,
        val possessiveDeterminer: String? = null,
        val possessivePronoun: String? = null,
        val reflexive: String? = null,
        val grammaticalGender: String? = null,
)

/** MSC4247 grammatical_gender values, used to compose gendered sentences (e.g. "her" vs "his"). */
object GrammaticalGender {
    const val MASCULINE = "masculine"
    const val FEMININE = "feminine"
    const val NEUTER = "neuter"
    const val INANIMATE = "inanimate"
    const val COMMON = "common"
}
