package io.noties.markwon.ext.latex;

import androidx.annotation.NonNull;

import org.commonmark.parser.block.BlockParserFactory;

import io.noties.markwon.inlineparser.InlineProcessor;

/** Package-private parser entry points exposed for the shared rich-text renderer. */
public final class JLatexMathParsers {

    private JLatexMathParsers() {
    }

    @NonNull
    public static InlineProcessor inlineProcessor() {
        return new JLatexMathInlineProcessor();
    }

    @NonNull
    public static BlockParserFactory blockParserFactory() {
        return new JLatexMathBlockParser.Factory();
    }
}
