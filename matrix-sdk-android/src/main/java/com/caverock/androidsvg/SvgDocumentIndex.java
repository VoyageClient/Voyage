/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package com.caverock.androidsvg;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public final class SvgDocumentIndex {
    private SvgDocumentIndex() { }

    // AndroidSVG 1.4 scans the whole tree on each new gradient reference. Prime its private cache
    // in document order so thousands of distinct references do not require quadratic work.
    @SuppressWarnings("unchecked")
    public static void prepare(SVG svg) {
        try {
            Field field = SVG.class.getDeclaredField("idToElementMap");
            field.setAccessible(true);
            Map<String, SVG.SvgElementBase> index = (Map<String, SVG.SvgElementBase>) field.get(svg);
            ArrayDeque<SVG.SvgObject> pending = new ArrayDeque<>();
            pending.push(svg.getRootElement());
            while (!pending.isEmpty()) {
                SVG.SvgObject node = pending.pop();
                if (node instanceof SVG.SvgElementBase) {
                    SVG.SvgElementBase element = (SVG.SvgElementBase) node;
                    if (element.id != null && !index.containsKey(element.id)) {
                        index.put(element.id, element);
                    }
                }
                if (node instanceof SVG.SvgContainer) {
                    List<SVG.SvgObject> children = ((SVG.SvgContainer) node).getChildren();
                    for (int i = children.size() - 1; i >= 0; i--) {
                        pending.push(children.get(i));
                    }
                }
            }
        } catch (NoSuchFieldException e) {
            // A changed library can still render through its original lookup implementation.
            Timber.w(e, "Unable to prepare SVG element index");
        } catch (IllegalAccessException e) {
            Timber.w(e, "Unable to prepare SVG element index");
        }
    }
}
