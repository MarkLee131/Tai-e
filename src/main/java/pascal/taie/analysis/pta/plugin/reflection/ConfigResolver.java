/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.reflection;

import pascal.taie.World;
import pascal.taie.util.collection.Sets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads HIGH-quality context for config-driven reflective names: when the name is
 * a value looked up by a property key (e.g. {@code props.getProperty("handler")}),
 * the actual value lives in a {@code .properties} file on the analysis classpath.
 * This scans the (app) classpath's {@code .properties} resources for the extracted
 * keys and returns the mapped values as high-confidence candidates to feed the LLM.
 *
 * <p>This is the "feed directly" path of the quality gate: when the config content
 * is obtainable, the candidate class/method names are surfaced verbatim; otherwise
 * the caller falls back to the preprocessed key/structure summary.
 */
final class ConfigResolver {

    /** Bound the directory walk depth (config files live near the root). */
    private static final int MAX_DEPTH = 6;

    private ConfigResolver() {
    }

    /**
     * Memoized results (E2): a full classpath walk (dirs to depth 6 + every jar's
     * .properties) per LLM query is pure repeated I/O — the classpath and files are
     * fixed for the run. Keyed by (classpath entries, requested keys).
     */
    private static final java.util.Map<String, List<String>> CACHE =
            java.util.Collections.synchronizedMap(
                    pascal.taie.util.collection.Maps.newMap());

    /** Values mapped to any of {@code keys} across all .properties on the classpath. */
    static List<String> valuesForKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> entries = classpathEntries();
        String cacheKey = String.join("|", entries) + "##" + String.join(",", keys);
        return CACHE.computeIfAbsent(cacheKey, k -> {
            Set<String> values = Sets.newLinkedSet();
            for (String entry : entries) {
                File f = new File(entry);
                if (f.isDirectory()) {
                    scanDir(f, keys, values);
                } else if (f.isFile() && f.getName().endsWith(".jar")) {
                    scanJar(f, keys, values);
                }
            }
            return new ArrayList<>(values);
        });
    }

    private static List<String> classpathEntries() {
        List<String> entries = new ArrayList<>();
        try {
            entries.addAll(World.get().getOptions().getAppClassPath());
            entries.addAll(World.get().getOptions().getClassPath());
        } catch (RuntimeException ignored) {
            // World/options unavailable — fall through to the override only
        }
        String root = System.getProperty("arm2.configRoot");
        if (root != null && !root.isBlank()) {
            entries.add(root);
        }
        return entries;
    }

    private static void scanDir(File dir, Collection<String> keys, Set<String> out) {
        try (Stream<Path> paths = Files.walk(dir.toPath(), MAX_DEPTH)) {
            paths.filter(p -> p.toString().endsWith(".properties"))
                    .forEach(p -> {
                        try (InputStream in = Files.newInputStream(p)) {
                            collect(in, keys, out);
                        } catch (IOException | RuntimeException ignored) {
                            // unreadable file — skip
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            // unwalkable dir — skip
        }
    }

    private static void scanJar(File jar, Collection<String> keys, Set<String> out) {
        try (JarFile jf = new JarFile(jar)) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().endsWith(".properties")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        collect(in, keys, out);
                    } catch (IOException | RuntimeException ignored) {
                        // skip
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not a readable jar — skip
        }
    }

    private static void collect(InputStream in, Collection<String> keys, Set<String> out)
            throws IOException {
        Properties props = new Properties();
        props.load(in);
        for (String key : keys) {
            String v = props.getProperty(key);
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
    }
}
