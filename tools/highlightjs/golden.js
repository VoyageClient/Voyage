/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// Records what highlight.js itself makes of a corpus, for HighlightJsGoldenTest to compare the Kotlin port against.
// Usage: node golden.js <path to the highlight.js package> <highlight.js repo checkout> <out.json.gz>

const path = require("path");
const fs = require("fs");
const zlib = require("zlib");

const hljs = require(path.resolve(process.argv[2]));
const repo = process.argv[3];
const outPath = process.argv[4];

function tokens(emitter) {
    const out = [];
    let pos = 0;
    function walk(node) {
        if (typeof node === "string") {
            pos += node.length;
            return;
        }
        if (node === undefined || node === null) return;
        const start = pos;
        let entry = null;
        if (node.scope) {
            entry = [start, 0, node.scope];
            out.push(entry);
        }
        (node.children || []).forEach(walk);
        if (entry) {
            entry[1] = pos;
            if (pos === start) out.splice(out.indexOf(entry), 1);
        }
    }
    walk(emitter.rootNode);
    return out;
}

function record(code, language) {
    const result = hljs.highlight(code, { language });
    return {
        code,
        language,
        illegal: !!result.illegal,
        relevance: result.relevance,
        tokens: result.illegal ? [] : tokens(result._emitter),
    };
}

const cases = [];
const detectDir = path.join(repo, "test/detect");
fs.readdirSync(detectDir).sort().filter((name) => fs.statSync(path.join(detectDir, name)).isDirectory()).forEach((language) => {
    fs.readdirSync(path.join(detectDir, language)).sort().forEach((file) => {
        cases.push(record(fs.readFileSync(path.join(detectDir, language, file), "utf8"), language));
    });
});

// Matrix-ish snippets, and code the named language finds illegal.
[
    ["json", "{\"type\": \"m.room.message\", \"content\": {\"body\": \"hi\"}}"],
    ["c", "int main(void) {\n    printf(\"hi\\n\");\n    return 0;\n}\n"],
    ["rust", "fn main() {\n    let mut v = vec![1, 2];\n    println!(\"{:?}\", v);\n}"],
    ["python", "def f(x):\n    return x * 2\n"],
    ["html", "<div class=\"x\"><script>let a = 1</script><style>b { color: red }</style></div>"],
    ["python", "Hello, could you check the logs? It crashed => again"],
    ["json", ""],
].forEach(([language, code]) => cases.push(record(code, language)));

fs.writeFileSync(outPath, zlib.gzipSync(JSON.stringify({ version: hljs.versionString, cases })));
console.log(`Recorded ${cases.length} cases to ${outPath}`);
