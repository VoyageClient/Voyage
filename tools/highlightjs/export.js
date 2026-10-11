/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// Compiles every highlight.js grammar and writes the compiled mode graphs as JSON for the Kotlin port
// (im.vector.app.features.html.highlight). Usage: node export.js <path to the highlight.js package> <out dir>

const path = require("path");
const fs = require("fs");

const hljsPath = path.resolve(process.argv[2]);
const outDir = process.argv[3];
const hljs = require(hljsPath);

const MATHEMATICA_SOURCE = fs.readFileSync(path.join(hljsPath, "lib/languages/mathematica.js"), "utf8");

function source(re) {
    if (!re) return null;
    if (typeof re === "string") return re;
    return re.source;
}

function countMatchGroups(re) {
    return new RegExp(re.toString() + "|").exec("").length - 1;
}

// Callbacks are closures; recognize each by its source so the Kotlin side can run its own copy.
function callbackName(fn) {
    if (!fn) return null;
    const src = fn.toString();
    if (src.includes("before === \".\"")) return "skipIfHasPrecedingDot";
    if (src.includes("m.index !== 0")) return "shebangAtStart";
    if (src.includes("_beginMatch = m[1] || m[2]")) return "phpHeredocBegin";
    if (src.includes("_beginMatch = m[1]")) return "endSameAsBeginBegin";
    if (src.includes("_beginMatch !== m[1]")) return "endSameAsBeginEnd";
    if (src.includes("afterMatchIndex")) return "jsxIsTrulyOpeningTag";
    if (src.includes("charBeforeMatch")) return "gcodeLetterBoundary";
    if (src.includes("SYSTEM_SYMBOLS_SET")) return "mathematicaSystemSymbol";
    throw new Error("Unknown callback: " + src);
}

function mathematicaSystemSymbols() {
    const match = /const SYSTEM_SYMBOLS = \[([\s\S]*?)\];/.exec(MATHEMATICA_SOURCE);
    return [...match[1].matchAll(/"([^"]*)"/g)].map((m) => m[1]);
}

function scopeSpec(scope) {
    if (!scope) return null;
    if (scope._wrap) return { wrap: scope._wrap };
    if (scope._multi) {
        const names = {};
        Object.keys(scope).forEach((k) => {
            if (/^\d+$/.test(k)) names[k] = scope[k];
        });
        return { multi: names, emit: Object.keys(scope._emit).map(Number) };
    }
    throw new Error("Unexpected scope " + JSON.stringify(scope));
}

function exportLanguage(name) {
    const language = hljs.getLanguage(name);
    // Highlighting compiles the language (and mutates its modes in place).
    hljs.highlight("x", { language: name, ignoreIllegals: true });

    const ids = new Map();
    const modes = [];
    const keywordTables = [];
    const keywordIds = new Map();

    function keywordsId(keywords) {
        if (!keywords) return -1;
        if (keywordIds.has(keywords)) return keywordIds.get(keywords);
        const table = {};
        Object.keys(keywords).forEach((word) => { table[word] = keywords[word]; });
        keywordTables.push(table);
        keywordIds.set(keywords, keywordTables.length - 1);
        return keywordTables.length - 1;
    }

    function visit(mode) {
        if (ids.has(mode)) return ids.get(mode);
        const id = modes.length;
        ids.set(mode, id);
        const out = {};
        modes.push(out);

        if (typeof mode.scope === "string") out.scope = mode.scope;
        if (mode.beginScope) out.beginScope = scopeSpec(mode.beginScope);
        if (mode.endScope) out.endScope = scopeSpec(mode.endScope);
        if (mode.begin !== undefined && mode !== language) {
            out.begin = source(mode.begin);
            out.beginGroups = countMatchGroups(new RegExp(out.begin));
        }
        if (mode.endRe) out.end = mode.endRe.source;
        if (mode.terminatorEnd) {
            out.terminatorEnd = mode.terminatorEnd;
            out.terminatorEndGroups = countMatchGroups(new RegExp(mode.terminatorEnd));
        }
        if (mode.illegal) {
            out.illegal = source(mode.illegal);
            out.illegalGroups = countMatchGroups(new RegExp(out.illegal));
        }
        out.keywords = keywordsId(mode.keywords);
        if (mode.keywordPatternRe) out.keywordPattern = mode.keywordPatternRe.source;
        out.relevance = mode.relevance;
        ["excludeBegin", "excludeEnd", "returnBegin", "returnEnd", "endsParent", "endsWithParent", "skip"].forEach((flag) => {
            if (mode[flag]) out[flag] = true;
        });
        if (mode.subLanguage !== undefined && mode.subLanguage !== null) out.subLanguage = mode.subLanguage;
        const beforeBegin = callbackName(mode.__beforeBegin);
        if (beforeBegin) out.beforeBegin = beforeBegin;
        const onBegin = callbackName(mode["on:begin"]);
        if (onBegin) out.onBegin = onBegin;
        const onEnd = callbackName(mode["on:end"]);
        if (onEnd) out.onEnd = onEnd;

        out.contains = (mode.contains || []).map(visit);
        if (mode.starts) out.starts = visit(mode.starts);
        return id;
    }

    visit(language);
    const result = {
        name,
        aliases: language.aliases || [],
        caseInsensitive: !!language.case_insensitive,
        unicodeRegex: !!language.unicodeRegex,
        disableAutodetect: !!language.disableAutodetect,
        classNameAliases: Object.assign({}, language.classNameAliases),
        keywordTables,
        modes,
    };
    if (language.supersetOf) result.supersetOf = language.supersetOf;
    return result;
}

// One file per language, so a code block only loads the grammars it needs, plus an index in registration order
// (which decides highlightAuto ties).
const languages = hljs.listLanguages().map(exportLanguage);
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
languages.forEach((language) => {
    if (language.name === "mathematica") language.systemSymbols = mathematicaSystemSymbols();
    fs.writeFileSync(path.join(outDir, language.name + ".json"), JSON.stringify(language));
});
const index = languages.map((l) => {
    const entry = { name: l.name, aliases: l.aliases };
    if (l.disableAutodetect) entry.disableAutodetect = true;
    if (l.supersetOf) entry.supersetOf = l.supersetOf;
    return entry;
});
fs.writeFileSync(path.join(outDir, "index.json"), JSON.stringify({ version: hljs.versionString, languages: index }));
console.log(`Exported ${languages.length} languages from highlight.js ${hljs.versionString} to ${outDir}`);
