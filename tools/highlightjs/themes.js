/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// Converts the highlight.js CSS themes offered as code block palettes into style rules for the app
// (im.vector.app.features.html.highlight.CodeTheme).
// Usage: node themes.js <highlight.js 11 package> <highlight.js 9 package> <out.json>

const path = require("path");
const fs = require("fs");

const hljs11 = process.argv[2];
const hljs9 = process.argv[3];
const outPath = process.argv[4];

const NAMED_COLORS = {
    white: 0xffffffff, black: 0xff000000, red: 0xffff0000, green: 0xff008000, blue: 0xff0000ff, navy: 0xff000080,
    gray: 0xff808080, grey: 0xff808080, silver: 0xffc0c0c0, maroon: 0xff800000, purple: 0xff800080, olive: 0xff808000,
    teal: 0xff008080, orange: 0xffffa500, yellow: 0xffffff00, fuchsia: 0xffff00ff, aqua: 0xff00ffff, lime: 0xff00ff00,
};

function parseColor(value) {
    value = value.trim().toLowerCase().replace(/\s*!important$/, "");
    if (value === "inherit" || value === "currentcolor") return "inherit";
    if (value === "transparent" || value === "none") return null;
    let m;
    if ((m = /^#([0-9a-f]{3,8})$/.exec(value))) {
        let hex = m[1];
        if (hex.length === 3 || hex.length === 4) hex = hex.split("").map((c) => c + c).join("");
        const alpha = hex.length === 8 ? parseInt(hex.slice(6), 16) : 255;
        return ((alpha << 24) | parseInt(hex.slice(0, 6), 16)) >>> 0;
    }
    if ((m = /^rgba?\(([^)]*)\)$/.exec(value))) {
        const parts = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
        const alpha = parts.length > 3 ? Math.round(parts[3] * 255) : 255;
        return ((alpha << 24) | (parts[0] << 16) | (parts[1] << 8) | parts[2]) >>> 0;
    }
    if (value in NAMED_COLORS) return NAMED_COLORS[value];
    return undefined;
}

// The block's own text and background colors: `.hljs`, `code.hljs` or `pre code.hljs`.
const BASE_SELECTOR = /^((pre\s+)?code)?\.hljs$/;

function parseCss(file, layer) {
    const css = fs.readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    const rules = [];
    const base = {};
    for (const [, selectorText, body] of css.matchAll(/([^{}@]+)\{([^{}]*)\}/g)) {
        const style = {};
        for (const decl of body.split(";")) {
            const colon = decl.indexOf(":");
            if (colon < 0) continue;
            const prop = decl.slice(0, colon).trim().toLowerCase();
            const value = decl.slice(colon + 1).trim();
            if (prop === "color") {
                const c = parseColor(value);
                if (c !== undefined && c !== null) style.c = c;
            } else if (prop === "background" || prop === "background-color") {
                const c = parseColor(value.split(/\s+/)[0]);
                if (c !== undefined && c !== "inherit") style.b = c;
            } else if (prop === "font-weight") {
                style.w = /bold|[6-9]00/.test(value);
            } else if (prop === "font-style") {
                style.i = /italic/.test(value);
            } else if (prop === "text-decoration") {
                style.u = /underline/.test(value);
            }
        }
        if (Object.keys(style).length === 0) continue;
        for (const selector of selectorText.split(",")) {
            if (BASE_SELECTOR.test(selector.trim())) {
                if (typeof style.c === "number") base.c = style.c;
                if (typeof style.b === "number") base.b = style.b;
                continue;
            }
            // A bare `code`/`pre` ancestor (Catppuccin scopes every rule under `code`) always holds the block.
            const compounds = selector.trim().split(/\s+/).filter((c) => c !== ">" && c !== "code" && c !== "pre");
            const chain = [];
            let valid = true;
            for (const compound of compounds) {
                if (!/^(\.[\w-]+)+$/.test(compound)) {
                    valid = false;
                    break;
                }
                const classes = compound.split(".").filter(Boolean);
                if (classes.length === 1 && classes[0] === "hljs") continue;
                chain.push(classes);
            }
            if (!valid || chain.length === 0) continue;
            rules.push(Object.assign({ s: chain, l: layer }, style));
        }
    }
    return { rules, base };
}

const css11 = (name, layer = 0) => parseCss(path.join(hljs11, "styles", name + ".css"), layer);
const css9 = (name, layer = 0) => parseCss(path.join(hljs9, "styles", name + ".css"), layer);
// @catppuccin/highlightjs 1.0.1, vendored next to this script.
const catppuccin = (flavor) => parseCss(path.join(__dirname, "catppuccin", `catppuccin-${flavor}.css`), 0);

// Element Web's own overrides (res/themes/{light,dark}/css), which sit above the imported theme.
const ELEMENT_LIGHT = [
    { s: [["hljs-addition"]], l: 1, b: 0xff98e1c1 },
    { s: [["hljs-deletion"]], l: 1, b: 0xffffc5bc },
];
const ELEMENT_DARK = [
    { s: [["hljs-tag"]], l: 1, c: "inherit" },
    { s: [["hljs-addition"]], l: 1, b: 0xff1a4b59 },
    { s: [["hljs-deletion"]], l: 1, b: 0xff53232a },
];

// Telegram colors its code by seven token kinds (TMessagesProj Theme key_code_*); these are the highlight.js
// scopes playing each part.
function telegram(colors) {
    const kinds = {
        keyword: ["hljs-keyword", "hljs-built_in", "hljs-type", "hljs-meta", "hljs-section", "hljs-selector-tag", "hljs-name", "hljs-doctag"],
        operator: ["hljs-operator", "hljs-punctuation"],
        constant: ["hljs-literal", "hljs-symbol", "hljs-bullet", "hljs-attr", "hljs-attribute", "hljs-variable", "hljs-template-variable"],
        string: ["hljs-string", "hljs-regexp", "hljs-char", "hljs-addition"],
        number: ["hljs-number"],
        comment: ["hljs-comment", "hljs-quote", "hljs-deletion"],
        function: ["hljs-title"],
    };
    const rules = [];
    Object.entries(kinds).forEach(([kind, classes]) => classes.forEach((cls) => rules.push({ s: [[cls]], l: 0, c: colors[kind] })));
    return rules;
}

const TELEGRAM_LIGHT = telegram({
    keyword: 0xffe05356, operator: 0xff4dbbff, constant: 0xff7f79f3, string: 0xff37c123,
    number: 0xff327fe5, comment: 0x80000000, function: 0xfff28c39,
});
// night.attheme
const TELEGRAM_DARK = telegram({
    keyword: (-27776) >>> 0, operator: 2147483647, constant: (-27776) >>> 0, string: (-7806088) >>> 0,
    number: (-10887465) >>> 0, comment: 2147483647, function: (-27776) >>> 0,
});

// xt256 is a dark terminal. Light is xterm's default black on white, each hue moved to a darker step of the
// same 256-color cube so it stays readable there.
const XTERM_ON_WHITE = {
    0xffeaeaea: 0xff000000, 0xffffffff: 0xff000000, 0xff969896: 0xff808080, 0xffda0000: 0xffaf0000,
    0xffff0000: 0xffaf0000, 0xff00ffff: 0xff008787, 0xfffff000: 0xff878700, 0xff00ff00: 0xff008700,
    0xff000fff: 0xff0000d7, 0xffff00ff: 0xffaf00af,
};
const XTERM_LIGHT = (() => {
    const dark = css11("xt256");
    const onWhite = (c) => (typeof c === "number" && XTERM_ON_WHITE[c] !== undefined ? XTERM_ON_WHITE[c] : c);
    return {
        rules: dark.rules.map((r) => Object.assign({}, r, r.c !== undefined ? { c: onWhite(r.c) } : {})),
        base: { c: 0xff000000, b: 0xffffffff },
    };
})();

// Element Web suppresses the theme's own block colors (`.mx_EventTile_pre_container` keeps the app's), so the
// Element palettes carry no base; Telegram draws code on the bubble's colors.
const palettes = [
    {
        id: "ELEMENT",
        light: { rules: css11("atom-one-light").rules.concat(ELEMENT_LIGHT), base: {} },
        dark: { rules: css11("atom-one-dark").rules.concat(ELEMENT_DARK), base: {} },
    },
    { id: "ELEMENT_2015", light: css9("github"), dark: css9("github") },
    // SchildiChat Web keeps Element's highlighting and recolors only the block, its border included (theme.sh).
    {
        id: "SCHILDICHAT",
        light: { rules: css11("atom-one-light").rules.concat(ELEMENT_LIGHT), base: { b: 0x10000000, o: 0x10000000 } },
        dark: { rules: css11("atom-one-dark").rules.concat(ELEMENT_DARK), base: { b: 0xff121212, o: 0xff121212 } },
    },
    { id: "GITHUB", light: css11("github"), dark: css11("github-dark") },
    { id: "VISUAL_STUDIO", light: css11("vs"), dark: css11("vs2015") },
    { id: "INTELLIJ", light: css11("intellij-light"), dark: css11("androidstudio") },
    { id: "STACK_OVERFLOW", light: css11("stackoverflow-light"), dark: css11("stackoverflow-dark") },
    { id: "TOKYO_NIGHT", light: css11("tokyo-night-light"), dark: css11("tokyo-night-dark") },
    { id: "ROSE_PINE", light: css11("rose-pine-dawn"), dark: css11("rose-pine") },
    { id: "SOLARIZED", light: css11("base16/solarized-light"), dark: css11("base16/solarized-dark") },
    { id: "GRUVBOX", light: css11("base16/gruvbox-light-hard"), dark: css11("base16/gruvbox-dark-hard") },
    { id: "CATPPUCCIN", light: catppuccin("latte"), dark: catppuccin("mocha") },
    // These are dark-only, like the themes themselves.
    { id: "NORD", light: css11("nord"), dark: css11("nord") },
    { id: "NIGHT_OWL", light: css11("night-owl"), dark: css11("night-owl") },
    { id: "MONOKAI", light: css11("monokai"), dark: css11("monokai") },
    { id: "DRACULA", light: css11("base16/dracula"), dark: css11("base16/dracula") },
    { id: "XTERM", light: XTERM_LIGHT, dark: css11("xt256") },
    { id: "TELEGRAM", light: { rules: TELEGRAM_LIGHT, base: {} }, dark: { rules: TELEGRAM_DARK, base: {} } },
];

// Kotlin reads colors as signed ints.
const signedColors = (style) => {
    const out = Object.assign({}, style);
    if (typeof out.c === "number") out.c = out.c | 0;
    if (typeof out.b === "number") out.b = out.b | 0;
    if (typeof out.o === "number") out.o = out.o | 0;
    return out;
};
const variant = (theme) => ({ base: signedColors(theme.base), rules: theme.rules.map(signedColors) });
fs.writeFileSync(outPath, JSON.stringify({ palettes: palettes.map((p) => ({ id: p.id, light: variant(p.light), dark: variant(p.dark) })) }));
console.log(`Wrote ${palettes.length} palettes to ${outPath}`);
